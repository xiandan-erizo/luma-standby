package app.luma.standby

import app.luma.standby.domain.model.FontChoice
import app.luma.standby.domain.model.LumaSettings
import app.luma.standby.domain.theme.PalettePresets
import app.luma.standby.domain.theme.ThemeEditing
import app.luma.standby.domain.theme.ThemePresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaletteAndFontTest {

    private val hex = Regex("^#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")

    @Test
    fun palettesAreValidAndUnique() {
        val ids = PalettePresets.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue("at least a dozen curated pairs", PalettePresets.all.size >= 10)
        PalettePresets.all.forEach { palette ->
            assertTrue(palette.id + " background", hex.matches(palette.background))
            assertTrue(palette.id + " primary", hex.matches(palette.primary))
            assertTrue(palette.id + " secondary", hex.matches(palette.secondary))
            assertTrue(palette.id + " accent", hex.matches(palette.accent))
        }
    }

    @Test
    fun paletteKeepsTheCurrentFontAndForksToCustom() {
        val settings = LumaSettings(themeId = "black-amber")
        val target = PalettePresets.findById("palette-sage-ink")!!
        val theme = ThemeEditing.withPalette(settings, target, 11L)

        assertEquals("#A8B9A1", theme.background)
        assertEquals("#10140F", theme.primary)
        // black-amber uses ROUNDED; the palette must not change it
        assertEquals(ThemePresets.blackAmber.font, theme.font)
        assertFalse(theme.builtIn)
        assertTrue(ThemeEditing.isCustom(theme))
        assertEquals("custom-11", theme.id)
    }

    @Test
    fun fontOnBuiltInThemeForksOnce() {
        val settings = LumaSettings(themeId = "atlantic-rose")
        val theme = ThemeEditing.withFont(settings, FontChoice.CONDENSED, 22L)

        assertEquals(FontChoice.CONDENSED, theme.font)
        assertEquals(ThemePresets.atlanticRose.background, theme.background)
        assertFalse(theme.builtIn)
        assertEquals("custom-22", theme.id)
    }

    @Test
    fun fontOnCustomThemeKeepsItsId() {
        val custom = ThemeEditing.duplicate(ThemePresets.burgundy, 33L)
        val settings = LumaSettings(themeId = custom.id, customThemes = listOf(custom))
        val theme = ThemeEditing.withFont(settings, FontChoice.LIGHT, 99L)

        assertEquals(custom.id, theme.id)
        assertEquals(FontChoice.LIGHT, theme.font)
    }

    @Test
    fun everyFontChoiceMapsToAPlatformFamily() {
        FontChoice.entries.forEach { choice ->
            assertTrue(choice.family.isNotBlank())
            assertTrue(choice.label.isNotBlank())
            assertTrue("style must be a Typeface constant", choice.style == 0 || choice.style == 1)
        }
        assertTrue(FontChoice.entries.size >= 6)
    }
}
