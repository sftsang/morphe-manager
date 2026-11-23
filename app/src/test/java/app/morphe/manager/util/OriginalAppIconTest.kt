package app.morphe.manager.util

import app.morphe.manager.patcher.patch.Option
import app.morphe.manager.patcher.patch.PatchInfo
import kotlinx.collections.immutable.persistentListOf
import kotlin.reflect.typeOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OriginalAppIconTest {
    private fun option(key: String, presets: Map<String, String?>?) = Option(
        title = key,
        key = key,
        description = "",
        required = false,
        type = typeOf<String>(),
        default = null,
        presets = presets,
        validator = { true }
    )

    private fun patch(name: String, vararg options: Option<*>) = PatchInfo(
        name = name,
        description = null,
        include = true,
        compatiblePackages = null,
        options = persistentListOf(*options)
    )

    private val branding = patch(
        "Custom branding",
        option("appIcon", mapOf("Automatic" to null, "Original" to "original", "Dark" to "dark")),
        option("customName", null)
    )

    @Test
    fun `an unset icon option offering the original icon gets it`() {
        val result = OriginalAppIcon.withDefaults(emptyMap(), mapOf(0 to mapOf(branding.name to branding)))
        assertEquals("original", result[0]?.get("Custom branding")?.get("appIcon"))
        assertNull(result[0]?.get("Custom branding")?.get("customName"))
    }

    @Test
    fun `an icon the user picked is kept`() {
        val saved = mapOf(0 to mapOf("Custom branding" to mapOf<String, Any?>("appIcon" to "dark")))
        val result = OriginalAppIcon.withDefaults(saved, mapOf(0 to mapOf(branding.name to branding)))
        assertEquals("dark", result[0]?.get("Custom branding")?.get("appIcon"))
    }

    @Test
    fun `other options saved with the icon are kept`() {
        val saved = mapOf(0 to mapOf("Custom branding" to mapOf<String, Any?>("customName" to "YT")))
        val result = OriginalAppIcon.withDefaults(saved, mapOf(0 to mapOf(branding.name to branding)))
        assertEquals("YT", result[0]?.get("Custom branding")?.get("customName"))
        assertEquals("original", result[0]?.get("Custom branding")?.get("appIcon"))
    }

    @Test
    fun `an icon option without an original preset is left alone`() {
        val other = patch("Icon pack", option("appIcon", mapOf("Blue" to "blue", "Red" to "red")))
        val result = OriginalAppIcon.withDefaults(emptyMap(), mapOf(3 to mapOf(other.name to other)))
        assertNull(result[3]?.get("Icon pack")?.get("appIcon"))
    }
}
