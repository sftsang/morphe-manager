package app.morphe.manager.domain.bundles

import android.util.Log
import app.morphe.manager.domain.manager.PreferencesManager
import app.morphe.manager.domain.repository.PatchBundleRepository
import app.morphe.manager.domain.repository.SourceMuteRepository
import app.morphe.manager.network.service.HttpService
import app.morphe.manager.network.utils.getOrNull
import app.morphe.manager.util.COMMUNITY_PATCHES_URL
import app.morphe.manager.util.PM
import io.ktor.client.request.url
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration.Companion.minutes

/**
 * Adds community patch sources for the apps installed on this device.
 *
 * Reads the catalog the community patches website is built from, and for every installed app that
 * no source here brings yet, adds the community source with the most stars for it. Each source is
 * added for just the installed apps it was picked for, so it brings no other app to the home screen.
 */
class CommunitySourceSync(
    private val http: HttpService,
    private val pm: PM,
    private val prefs: PreferencesManager,
    private val patchBundleRepository: PatchBundleRepository,
    private val sourceMuteRepository: SourceMuteRepository
) {
    private val mutex = Mutex()
    private var ranThisProcess = false

    /**
     * @param releaseMetadataUrl The bundle file a GitHub source publishes its release in, which is
     *   empty until the author releases. Null where it cannot be told, which is taken on trust.
     */
    private data class CatalogSource(
        val url: String,
        val stars: Int,
        val apps: Set<String>,
        val releaseMetadataUrl: String?
    )

    /** Runs once per process; later calls return at once. */
    suspend fun syncOnce() = mutex.withLock {
        if (ranThisProcess) return@withLock
        ranThisProcess = true
        runCatching { sync() }.onFailure { Log.w(LOG_TAG, "Community source sync failed", it) }
    }

    private suspend fun sync() {
        // Wait for every source already here to load, so the apps it brings are known
        val ready = withTimeoutOrNull(SOURCES_LOAD_TIMEOUT) {
            patchBundleRepository.bundleState.first { state ->
                state is PatchBundleRepository.BundleState.Ready &&
                        state.sources.keys.all { it in state.info }
            } as PatchBundleRepository.BundleState.Ready
        } ?: (patchBundleRepository.bundleState.value as? PatchBundleRepository.BundleState.Ready)
        ?: return

        val installed = pm.getInstalledPackages().mapTo(HashSet()) { it.packageName }
        val handled = prefs.communityHandledApps.get()
        val alreadyBrought = ready.info.values
            .filter { it.enabled }
            .flatMapTo(HashSet()) { it.listedApps() }

        val wanted = installed - handled - alreadyBrought
        if (wanted.isEmpty()) return

        val catalog = fetchCatalog() ?: return

        // The source with the most stars for each wanted app that has published a release, grouped
        // by source. The catalog lists sources whose author never released, which cannot load
        val released = mutableMapOf<String, Boolean>()
        val picked = mutableMapOf<String, MutableSet<String>>()
        for (packageName in wanted) {
            val best = catalog
                .filter { packageName in it.apps }
                .sortedByDescending { it.stars }
                .firstOrNull { source -> released.getOrPut(source.url) { hasRelease(source) } }
                ?: continue
            picked.getOrPut(best.url) { mutableSetOf() } += packageName
        }
        if (picked.isEmpty()) return
        Log.i(LOG_TAG, "Adding ${picked.size} community sources for ${picked.values.sumOf { it.size }} apps")

        for ((url, apps) in picked) {
            val existingUid = patchBundleRepository.existingSourceUid(url)
            if (existingUid != null) {
                // Already added for other apps, so it only has to bring these as well
                sourceMuteRepository.unmuteApps(existingUid, apps)
            } else {
                patchBundleRepository.createRemotes(listOf(url), keptApps = apps)
            }
            prefs.communityHandledApps.update(prefs.communityHandledApps.get() + apps)
        }
    }

    /** Whether [source] has a release to download, which a bundle file without a download link has not. */
    private suspend fun hasRelease(source: CatalogSource): Boolean {
        val metadataUrl = source.releaseMetadataUrl ?: return true
        val body = http.request<String> { url(metadataUrl) }.getOrNull() ?: return false
        val downloadUrl = runCatching {
            http.json.parseToJsonElement(body).jsonObject["download_url"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        return !downloadUrl.isNullOrBlank().also { empty ->
            if (empty) Log.i(LOG_TAG, "Skipping ${source.url}, it has no release")
        }
    }

    private suspend fun fetchCatalog(): List<CatalogSource>? {
        val body = http.request<String> {
            url("$COMMUNITY_PATCHES_URL/data/catalog.json")
        }.getOrNull() ?: return null

        val bundles = http.json.parseToJsonElement(body).jsonObject["bundles"] as? JsonArray ?: return null
        return bundles.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val repo = obj["repo"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val host = when (obj["source"]?.jsonPrimitive?.contentOrNull) {
                "github" -> "github.com"
                "gitlab" -> "gitlab.com"
                else -> return@mapNotNull null
            }
            val apps = obj["targetApps"]?.jsonArray
                ?.mapNotNullTo(HashSet()) { it.jsonPrimitive.contentOrNull }
                .orEmpty()
            if (apps.isEmpty()) return@mapNotNull null
            val branch = obj["branch"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: "main"
            CatalogSource(
                url = "https://$host/$repo",
                stars = obj["stars"]?.jsonPrimitive?.intOrNull ?: 0,
                apps = apps,
                releaseMetadataUrl = if (host == "github.com") {
                    "https://raw.githubusercontent.com/$repo/$branch/patches-bundle.json"
                } else null
            )
        }
    }

    private companion object {
        const val LOG_TAG = "CommunitySourceSync"
        val SOURCES_LOAD_TIMEOUT = 2.minutes
    }
}
