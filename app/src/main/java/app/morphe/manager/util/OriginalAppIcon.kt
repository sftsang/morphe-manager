package app.morphe.manager.util

import app.morphe.manager.patcher.patch.Option
import app.morphe.manager.patcher.patch.PatchInfo

/**
 * Patched apps keep the launcher icon of the app they were patched from unless the user picks
 * another one. A patch that can change the icon and offers to keep the original says so with an
 * icon option carrying an [VALUE] preset, as the Custom branding patch does; such an option that
 * was never set is filled in with it. Patches without that preset are left as they are.
 */
object OriginalAppIcon {
    /** The preset value icon options use for the unpatched app's own icon. */
    const val VALUE = "original"

    /** Whether this is an icon option that offers the original icon. */
    fun Option<*>.offersOriginalIcon(): Boolean =
        key.contains("icon", ignoreCase = true) && presets?.values?.any { it == VALUE } == true

    /** [presets] of an icon option, as a dialog lists them, offer the original icon. */
    fun offersOriginalIcon(presets: Map<String, *>): Boolean = presets.values.any { it == VALUE }

    /**
     * [options] with every unset icon option of [bundles] that offers the original icon set to
     * it. Options the user set, and options of other kinds, are kept as they are.
     */
    fun withDefaults(options: Options, bundles: Map<Int, Map<String, PatchInfo>>): Options {
        if (bundles.isEmpty()) return options
        val result = options.mapValues { (_, patches) -> patches.toMutableMap() }.toMutableMap()
        bundles.forEach { (uid, patches) ->
            patches.forEach { (patchName, patch) ->
                patch.options.orEmpty().filter { it.offersOriginalIcon() }.forEach { option ->
                    val bundleOptions = result.getOrPut(uid) { mutableMapOf() }
                    val patchOptions = bundleOptions[patchName].orEmpty()
                    if (patchOptions[option.key] == null) {
                        bundleOptions[patchName] = patchOptions + (option.key to VALUE)
                    }
                }
            }
        }
        return result
    }
}
