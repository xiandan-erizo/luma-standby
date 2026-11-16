package app.luma.standby

import app.luma.standby.domain.model.FontChoice
import app.luma.standby.domain.model.LumaTheme
import app.luma.standby.domain.theme.ThemePresets
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeJsonTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val hex = Regex("^#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")

    @Test
    fun presetIdsAreUnique() {
        val ids = ThemePresets.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun presetColoursAreValidHex() {
        ThemePresets.all.forEach { theme ->
            assertTrue(theme.id + " background", hex.matches(theme.background))
            assertTrue(theme.id + " primary", hex.matches(theme.primary))
            assertTrue(theme.id + " secondary", hex.matches(theme.secondary))
            assertTrue(theme.id + " accent", hex.matches(theme.accent))
        }
    }

    @Test
    fun roundTripPreservesTheme() {
        val original = ThemePresets.burgundy
        val encoded = json.encodeToString(LumaTheme.serializer(), original)
        val decoded = json.decodeFromString(LumaTheme.serializer(), encoded)
        assertEquals(original, decoded)
        assertFalse(encoded.isEmpty())
    }

    @Test
    fun unknownKeysAreIgnored() {
        val raw = """
            {
              "id": "custom-1",
              "name": "Custom",
              "background": "#101010",
              "primary": "#FFFFFF",
              "secondary": "#888888",
              "futureField": "ignore me"
            }
        """.trimIndent()
        val theme = json.decodeFromString(LumaTheme.serializer(), raw)
        assertEquals("custom-1", theme.id)
        assertEquals(FontChoice.ROUNDED, theme.font)
        assertFalse(theme.gradient)
        assertFalse(theme.builtIn)
    }

    @Test
    fun findByIdFallsBackToDefault() {
        assertEquals("atlantic-rose", ThemePresets.findById("does-not-exist").id)
    }
}
