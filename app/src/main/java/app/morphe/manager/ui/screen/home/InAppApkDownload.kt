package app.morphe.manager.ui.screen.home

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.util.Log
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.morphe.manager.R
import app.morphe.manager.domain.manager.ApkDownloadSource
import app.morphe.manager.ui.screen.shared.AppDialog
import app.morphe.manager.ui.screen.shared.AppDialogButtonRow
import app.morphe.manager.ui.screen.shared.DialogHostScope
import app.morphe.manager.ui.screen.shared.Defaults
import app.morphe.manager.ui.screen.shared.rememberAppColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

private const val LOG_TAG = "InAppApkDownload"

/** Where the in-app download stands, which is all the user sees of the website. */
private sealed interface DownloadPhase {
    /** Waiting for Morphe's API to say where the APK is. */
    data object Resolving : DownloadPhase

    /** The hidden page is being walked to its download link. */
    data object Finding : DownloadPhase

    /** Cloudflare wants the user to show they are a person, so the page is shown. */
    data object Challenge : DownloadPhase

    /** @param progress Fraction done, or null while the size is unknown. */
    data class Downloading(val progress: Float?) : DownloadPhase

    data object Failed : DownloadPhase
}

/**
 * Downloads the original APK inside Morphe, from the page Morphe's API resolved [downloadUrl] to.
 *
 * The page is loaded in a WebView that is kept hidden. It may only load from APKMirror itself and
 * Cloudflare's challenge host, so ads, trackers and their redirects never load, and it may only
 * move between the pages of this one app. Morphe presses the download buttons itself, and the file
 * is taken straight into [tempDir]. Only a Cloudflare challenge is ever shown, for the user to pass.
 *
 * A link that leads straight to a file is downloaded without a page. Any other site cannot be
 * walked, so it goes to [onOpenWebsite] at once, as does a page whose download cannot be found.
 */
@Composable
fun DialogHostScope.InAppApkDownloadDialog(
    appName: String,
    packageName: String?,
    version: String?,
    downloadUrl: String?,
    tempDir: File,
    onDownloaded: (File) -> Unit,
    onOpenWebsite: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf<DownloadPhase>(DownloadPhase.Resolving) }
    var attempt by remember { mutableStateOf(0) }
    val source = remember(downloadUrl) { ApkDownloadSource.from(downloadUrl) }

    val controller = remember {
        ApkMirrorPageWalker(
            context = context,
            scope = scope,
            tempDir = tempDir,
            onPhase = { phase = it },
            onDownloaded = onDownloaded
        )
    }
    DisposableEffect(Unit) { onDispose { controller.destroy() } }

    LaunchedEffect(source, downloadUrl, attempt) {
        val url = downloadUrl ?: return@LaunchedEffect
        when (source) {
            is ApkDownloadSource.ApkMirror -> controller.start(url)
            is ApkDownloadSource.DirectFile -> controller.downloadDirect(url)
            // Still waiting for the redirect to land
            ApkDownloadSource.Unresolved -> phase = DownloadPhase.Resolving
            // A site the walker does not know, which the instructions cover
            else -> onOpenWebsite()
        }
    }

    val title = stringResource(R.string.home_inapp_download_title, appName)
    val status = when (val current = phase) {
        DownloadPhase.Resolving, DownloadPhase.Finding ->
            stringResource(R.string.home_inapp_download_finding, appName, version.orEmpty())
        DownloadPhase.Challenge -> stringResource(R.string.home_inapp_download_challenge)
        is DownloadPhase.Downloading -> current.progress?.let {
            stringResource(R.string.home_inapp_download_progress, (it * 100).toInt())
        } ?: stringResource(R.string.home_inapp_download_downloading)
        DownloadPhase.Failed -> stringResource(R.string.home_inapp_download_failed)
    }

    AppDialog(
        onDismissRequest = onDismiss,
        accentColor = rememberAppColor(packageName),
        title = title,
        description = status,
        scrollable = false,
        footer = {
            if (phase == DownloadPhase.Failed) {
                AppDialogButtonRow(
                    primaryText = stringResource(R.string.home_inapp_download_retry),
                    onPrimaryClick = { attempt++ },
                    secondaryText = stringResource(R.string.home_inapp_download_open_website),
                    onSecondaryClick = onOpenWebsite
                )
            } else {
                AppDialogButtonRow(
                    primaryText = stringResource(android.R.string.cancel),
                    onPrimaryClick = onDismiss
                )
            }
        }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Defaults.SpaceLarge),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (val current = phase) {
                is DownloadPhase.Downloading -> current.progress
                    ?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
                    ?: LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                DownloadPhase.Resolving, DownloadPhase.Finding ->
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                else -> Unit
            }
            if (phase == DownloadPhase.Failed) {
                Text(
                    text = stringResource(R.string.home_inapp_download_failed_hint),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            // The page stays in the layout so it keeps running, and is only shown for a challenge
            val showPage = phase == DownloadPhase.Challenge
            AndroidView(
                factory = { controller.webView },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (showPage) 420.dp else 1.dp)
                    .alpha(if (showPage) 1f else 0f)
            )
        }
    }
}

/**
 * Drives a filtered, hidden WebView from an APKMirror page to the APK file.
 *
 * All callbacks arrive on the main thread, where the WebView has to be used.
 */
@SuppressLint("SetJavaScriptEnabled")
private class ApkMirrorPageWalker(
    context: Context,
    private val scope: CoroutineScope,
    private val tempDir: File,
    private val onPhase: (DownloadPhase) -> Unit,
    private val onDownloaded: (File) -> Unit
) {
    val webView: WebView = WebView(context)

    /** The path every page of this app lives under, such as "/apk/google-inc/youtube/". */
    private var appPathPrefix: String? = null
    private var stepJob: Job? = null
    private var downloadJob: Job? = null
    private var inspectJob: Job? = null
    private var finished = false

    /** Whether a Cloudflare challenge is up, which waits for the user and so stops the clock. */
    private var challengeActive = false

    init {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            mediaPlaybackRequiresUserGesture = true
            allowFileAccess = false
            allowContentAccess = false
        }
        CookieManager.getInstance().setAcceptCookie(true)
        webView.webViewClient = FilteringClient()
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
            download(url, userAgent, referer = webView.url, fileName = name)
        }
    }

    fun start(url: String) {
        reset()
        appPathPrefix = appPathPrefixOf(url)
        Log.i(LOG_TAG, "Walking $url under $appPathPrefix")
        onPhase(DownloadPhase.Finding)
        webView.loadUrl(url)
        // A page that never gets anywhere ends the walk rather than spinning forever. Time spent
        // on a challenge is the user's, so it does not count
        stepJob = scope.launch {
            var waited = 0L
            while (waited < WALK_TIMEOUT_MS) {
                delay(CLOCK_TICK_MS)
                if (!challengeActive) waited += CLOCK_TICK_MS
            }
            if (!finished && downloadJob == null) fail("Timed out walking the page")
        }
    }

    fun downloadDirect(url: String) {
        reset()
        download(url, userAgent = null, referer = null, fileName = URLUtil.guessFileName(url, null, null))
    }

    fun destroy() {
        inspectJob?.cancel()
        stepJob?.cancel()
        downloadJob?.cancel()
        webView.stopLoading()
        webView.destroy()
    }

    private fun reset() {
        inspectJob?.cancel()
        stepJob?.cancel()
        downloadJob?.cancel()
        downloadJob = null
        finished = false
        challengeActive = false
        webView.stopLoading()
    }

    private fun fail(reason: String) {
        if (finished) return
        finished = true
        Log.w(LOG_TAG, "In-app download failed: $reason")
        webView.stopLoading()
        onPhase(DownloadPhase.Failed)
    }

    /**
     * Reads what the page in front of it offers and takes the next step. Every page that finishes
     * loading starts this afresh, a challenge passed on the same address included. A challenge is
     * waited on for as long as the user takes, anything else gets a few looks.
     */
    private fun inspectPage(url: String) {
        if (finished || downloadJob != null) return
        inspectJob?.cancel()
        inspectJob = scope.launch {
            var looks = 0
            while (looks < INSPECT_ATTEMPTS) {
                if (finished || downloadJob != null || webView.url != url) return@launch
                val step = evaluate(INSPECT_SCRIPT)
                val kind = step?.optString("kind")
                challengeActive = kind == "challenge"
                when (kind) {
                    "challenge" -> onPhase(DownloadPhase.Challenge)
                    "link", "button" -> {
                        onPhase(DownloadPhase.Finding)
                        val href = step.optString("href")
                        if (isAllowedNavigation(href)) webView.loadUrl(href) else fail("Link left the app: $href")
                        return@launch
                    }
                    "variants" -> {
                        onPhase(DownloadPhase.Finding)
                        val items = step.optJSONArray("items")
                        val variants = (0 until (items?.length() ?: 0)).mapNotNull { i ->
                            items?.optJSONObject(i)?.let { Variant(it.optString("href"), it.optString("text")) }
                        }
                        val best = pickVariant(variants)
                        if (best != null && isAllowedNavigation(best.href)) webView.loadUrl(best.href)
                        else fail("No variant for this device among ${variants.size}")
                        return@launch
                    }
                    else -> looks++
                }
                delay(INSPECT_INTERVAL_MS)
            }
            fail("Found nothing to press on $url")
        }
    }

    private suspend fun evaluate(script: String): JSONObject? {
        val raw = suspendCancellableCoroutine<String?> { cont ->
            webView.evaluateJavascript(script) { value -> if (cont.isActive) cont.resumeWith(Result.success(value)) }
        }
        return runCatching {
            // The result is the script's string, encoded as a JSON string literal
            val text = JSONTokener(raw).nextValue() as? String ?: return null
            JSONObject(text)
        }.getOrNull()
    }

    private fun download(url: String, userAgent: String?, referer: String?, fileName: String) {
        if (downloadJob != null) return
        stepJob?.cancel()
        webView.stopLoading()
        onPhase(DownloadPhase.Downloading(null))
        val cookies = CookieManager.getInstance().getCookie(url)
        val extension = fileName.substringAfterLast('.', "apk").lowercase().takeIf { it in FILE_EXTENSIONS } ?: "apk"
        downloadJob = scope.launch {
            val target = File(tempDir, "inapp_download_${System.currentTimeMillis()}.$extension")
            val ok = withContext(Dispatchers.IO) {
                runCatching { fetch(url, userAgent, referer, cookies, target) }
                    .onFailure { Log.w(LOG_TAG, "Download failed", it) }
                    .getOrDefault(false)
            }
            if (ok && target.length() > 0) {
                finished = true
                onDownloaded(target)
            } else {
                target.delete()
                downloadJob = null
                fail("Download did not complete")
            }
        }
    }

    private suspend fun fetch(url: String, userAgent: String?, referer: String?, cookies: String?, target: File): Boolean {
        var current = url
        repeat(MAX_REDIRECTS) {
            val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 30_000
                userAgent?.let { setRequestProperty("User-Agent", it) }
                referer?.let { setRequestProperty("Referer", it) }
                CookieManager.getInstance().getCookie(current)?.let { setRequestProperty("Cookie", it) }
                    ?: cookies?.let { setRequestProperty("Cookie", it) }
            }
            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location") ?: return false
                    current = URI(current).resolve(location).toString()
                    return@repeat
                }
                if (code !in 200..299) {
                    Log.w(LOG_TAG, "Download answered $code")
                    return false
                }
                val total = connection.contentLengthLong.takeIf { it > 0 }
                connection.inputStream.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var done = 0L
                        var lastReported = -1
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            done += read
                            val percent = total?.let { (done * 100 / it).toInt() } ?: -1
                            if (percent != lastReported) {
                                lastReported = percent
                                val progress = total?.let { done.toFloat() / it }
                                withContext(Dispatchers.Main) { onPhase(DownloadPhase.Downloading(progress)) }
                            }
                        }
                    }
                }
                return true
            } finally {
                connection.disconnect()
            }
        }
        return false
    }

    /** Whether the main page may go to [url]: this app's pages, the download step, or a challenge. */
    private fun isAllowedNavigation(url: String): Boolean {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        if (uri.scheme != "https") return false
        val host = uri.host?.lowercase() ?: return false
        if (host == CLOUDFLARE_CHALLENGE_HOST) return true
        if (!isApkMirrorHost(host)) return false
        val path = uri.path.orEmpty()
        val prefix = appPathPrefix
        return path.startsWith("/cdn-cgi/") ||
                path.startsWith(DOWNLOAD_SCRIPT_PATH) ||
                (prefix != null && path.startsWith(prefix)) ||
                // Files are served from APKMirror's own download hosts
                host != APKMIRROR_HOST
    }

    private inner class FilteringClient : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val host = request.url.host?.lowercase()
            val allowed = host != null && (isApkMirrorHost(host) || host == CLOUDFLARE_CHALLENGE_HOST)
            // Anything from another host, ads, trackers and their scripts, gets an empty answer
            return if (allowed) null else WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            // APKMirror's download step hands the file off to its storage host. That is the APK
            // itself, so it is fetched directly rather than opened as a page
            val host = request.url.host?.lowercase()
            if (request.isForMainFrame && request.url.scheme == "https" &&
                host != null && host.endsWith(FILE_STORAGE_HOST_SUFFIX) &&
                view.url?.let { Uri.parse(it).host?.lowercase()?.let(::isApkMirrorHost) } == true
            ) {
                Log.i(LOG_TAG, "Taking the file from $host")
                val name = request.url.lastPathSegment ?: "download.apk"
                download(url, view.settings.userAgentString, referer = view.url, fileName = name)
                return true
            }
            val allowed = isAllowedNavigation(url)
            if (!allowed) Log.i(LOG_TAG, "Blocked navigation to ${request.url.host}")
            return !allowed
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            if (!isAllowedNavigation(url)) view.stopLoading()
        }

        override fun onPageFinished(view: WebView, url: String) {
            inspectPage(url)
        }
    }

    private data class Variant(val href: String, val text: String)

    /**
     * The variant for this device: built for one of its ABIs or for all of them, a full APK over a
     * bundle, and a build for any screen density over one for a single density.
     */
    private fun pickVariant(variants: List<Variant>): Variant? {
        val deviceAbis = Build.SUPPORTED_ABIS.map { it.lowercase() }
        fun abisNamed(text: String): Set<String> =
            KNOWN_ABIS.filterTo(mutableSetOf()) { abi -> Regex("(^|[^a-z0-9_])${Regex.escape(abi)}($|[^a-z0-9_])").containsMatchIn(text) }

        return variants
            .filter { it.href.isNotBlank() }
            .map { variant ->
                val text = variant.text.lowercase()
                val named = abisNamed(text)
                var score = 0
                when {
                    named.isEmpty() || "universal" in text || "noarch" in text -> score += 4
                    named.any { it == deviceAbis.firstOrNull() } -> score += 6
                    named.any { it in deviceAbis } -> score += 3
                    else -> score -= 100
                }
                if ("bundle" in text) score -= 5
                if ("nodpi" in text) score += 2
                variant to score
            }
            .filter { (_, score) -> score > -100 }
            .maxByOrNull { (_, score) -> score }
            ?.first
    }

    private companion object {
        const val APKMIRROR_HOST = "www.apkmirror.com"
        const val CLOUDFLARE_CHALLENGE_HOST = "challenges.cloudflare.com"

        /** Where APKMirror's download step sends the file, a Cloudflare R2 bucket of its own. */
        const val FILE_STORAGE_HOST_SUFFIX = ".r2.cloudflarestorage.com"
        const val DOWNLOAD_SCRIPT_PATH = "/wp-content/themes/APKMirror/download.php"
        const val WALK_TIMEOUT_MS = 90_000L
        const val CLOCK_TICK_MS = 1_000L
        const val INSPECT_ATTEMPTS = 8
        const val INSPECT_INTERVAL_MS = 1_500L
        const val MAX_REDIRECTS = 8
        val FILE_EXTENSIONS = setOf("apk", "apkm", "apks", "xapk")
        val KNOWN_ABIS = listOf("arm64-v8a", "armeabi-v7a", "armeabi", "x86_64", "x86")

        fun isApkMirrorHost(host: String) = host == "apkmirror.com" || host.endsWith(".apkmirror.com")

        /** "/apk/<developer>/<app>/" of an APKMirror page, which every page of the app shares. */
        fun appPathPrefixOf(url: String): String? {
            val segments = Uri.parse(url).pathSegments
            return if (segments.size >= 3 && segments[0] == "apk") "/apk/${segments[1]}/${segments[2]}/" else null
        }

        /**
         * Says what the page offers: a Cloudflare challenge, the final download link, the download
         * button, or the list of variants of a release.
         */
        val INSPECT_SCRIPT = """
            (function() {
              var title = document.title || '';
              if (/just a moment|attention required/i.test(title) ||
                  document.querySelector('#challenge-form, #challenge-running, .cf-turnstile, iframe[src*="challenges.cloudflare.com"]')) {
                return JSON.stringify({kind: 'challenge'});
              }
              var link = document.querySelector('#download-link, a[href*="download.php?id="]');
              if (link && link.href) return JSON.stringify({kind: 'link', href: link.href});
              var button = document.querySelector('a.downloadButton');
              if (button && button.href) return JSON.stringify({kind: 'button', href: button.href});
              var rows = Array.prototype.slice.call(document.querySelectorAll('.variants-table .table-row'));
              var items = rows.map(function(row) {
                var a = row.querySelector('a[href*="-android-apk-download"]');
                return a ? {href: a.href, text: row.innerText || ''} : null;
              }).filter(function(x) { return x; });
              if (items.length) return JSON.stringify({kind: 'variants', items: items});
              return JSON.stringify({kind: 'unknown'});
            })();
        """.trimIndent()
    }
}
