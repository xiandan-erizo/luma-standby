package app.luma.standby

import app.luma.standby.domain.theme.ThemeImport
import app.luma.standby.domain.theme.ThemePresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeImportTest {

    private val valid = """
        {
          "id": "custom-1",
          "name": "My Theme",
          "background": "#101820",
          "primary": "#F5E6C8",
          "secondary": "#8899AA",
          "accent": "#FFAA00",
          "gradient": true,
          "gradientEnd": "#080C12",
          "font": "SERIF",
          "showSeconds": true
        }
    """.trimIndent()

    @Test
    fun validThemeImports() {
        val result = ThemeImport.parse(valid)
        assertTrue(result is ThemeImport.Result.Ok)
        val theme = (result as ThemeImport.Result.Ok).theme
        assertEquals("custom-1", theme.id)
        assertEquals("#F5E6C8", theme.primary)
        assertTrue(theme.gradient)
        assertTrue(theme.showSeconds)
        assertEquals(false, theme.builtIn)
    }

    @Test
    fun missingRequiredFieldIsRejected() {
        val broken = valid.replace("\"primary\": \"#F5E6C8\",", "")
        assertTrue(ThemeImport.parse(broken) is ThemeImport.Result.Rejected)
    }

    @Test
    fun badColorIsRejected() {
        val broken = valid.replace("#101820", "101820")
        assertTrue(ThemeImport.parse(broken) is ThemeImport.Result.Rejected)
    }

    @Test
    fun badFontIsRejected() {
        val broken = valid.replace("SERIF", "COMIC")
        assertTrue(ThemeImport.parse(broken) is ThemeImport.Result.Rejected)
    }

    @Test
    fun notJsonIsRejected() {
        assertTrue(ThemeImport.parse("hello") is ThemeImport.Result.Rejected)
        assertTrue(ThemeImport.parse("[]") is ThemeImport.Result.Rejected)
        assertTrue(ThemeImport.parse("") is ThemeImport.Result.Rejected)
    }

    @Test
    fun blankNameIsRejected() {
        val broken = valid.replace("My Theme", "   ")
        assertTrue(ThemeImport.parse(broken) is ThemeImport.Result.Rejected)
    }

    @Test
    fun exportedPresetRoundTripsThroughImport() {
        val json = """
            {
              "id": "atlantic-rose",
              "name": "Atlantic Rose",
              "background": "#25596A",
              "primary": "#FFCAD9",
              "secondary": "#F9D9E3",
              "accent": "#FFF1F4",
              "gradient": false,
              "gradientEnd": "#25596A",
              "font": "DISPLAY",
              "showSeconds": false,
              "brightnessProfile": "STANDARD",
              "builtIn": true
            }
        """.trimIndent()
        val result = ThemeImport.parse(json)
        assertTrue(result is ThemeImport.Result.Ok)
        val theme = (result as ThemeImport.Result.Ok).theme
        assertEquals(ThemePresets.atlanticRose.background, theme.background)
        assertEquals(ThemePresets.atlanticRose.font, theme.font)
    }
}