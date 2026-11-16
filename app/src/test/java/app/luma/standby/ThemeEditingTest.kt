package app.luma.standby

import app.luma.standby.domain.model.LumaSettings
import app.luma.standby.domain.theme.ThemeEditing
import app.luma.standby.domain.theme.ThemePresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeEditingTest {

    @Test
    fun duplicateCreatesEditableCustomCopy() {
        val copy = ThemeEditing.duplicate(ThemePresets.midnightLavender, 42L)
        assertEquals("custom-42", copy.id)
        assertFalse(copy.builtIn)
        assertTrue(copy.name.contains("Midnight Lavender"))
        assertEquals(ThemePresets.midnightLavender.background, copy.background)
        assertTrue(ThemeEditing.isCustom(copy))
    }

    @Test
    fun upsertInsertsSelectsAndReplaces() {
        val first = ThemeEditing.duplicate(ThemePresets.forestMist, 7L)
        val afterInsert = ThemeEditing.upsert(LumaSettings(), first)
        assertEquals(first.id, afterInsert.themeId)
        assertEquals(1, afterInsert.customThemes.size)

        val renamed = first.copy(name = "Forest Night")
        val afterUpdate = ThemeEditing.upsert(afterInsert, renamed)
        assertEquals(1, afterUpdate.customThemes.size)
        assertEquals("Forest Night", afterUpdate.customThemes.first().name)
    }

    @Test
    fun removingSelectedCustomThemeFallsBackToDefault() {
        val custom = ThemeEditing.duplicate(ThemePresets.burgundy, 9L)
        val withCustom = ThemeEditing.upsert(LumaSettings(), custom)
        val afterRemove = ThemeEditing.remove(withCustom, custom.id)
        assertEquals(ThemePresets.DEFAULT_ID, afterRemove.themeId)
        assertTrue(afterRemove.customThemes.isEmpty())
    }

    @Test
    fun builtInThemesAreNotEditableAsCustom() {
        assertFalse(ThemeEditing.isCustom(ThemePresets.burgundy))
    }

    @Test
    fun validateHexAcceptsSixAndEightDigitValues() {
        assertEquals("#31596B", ThemeEditing.validateHex("  #31596B "))
        assertEquals("#8031596B", ThemeEditing.validateHex("#8031596B"))
        assertNull(ThemeEditing.validateHex("31596B"))
        assertNull(ThemeEditing.validateHex("#GGGGGG"))
        assertNull(ThemeEditing.validateHex("#12345"))
    }

    @Test
    fun validateAllReportsEveryBrokenField() {
        val broken = ThemePresets.atlanticRose.copy(
            name = " ",
            background = "nope",
            primary = "#12345",
            secondary = "",
            accent = "#XYZXYZ",
        )
        val problems = ThemeEditing.validateAll(broken)
        assertTrue(problems.contains("name"))
        assertTrue(problems.contains("background"))
        assertTrue(problems.contains("primary"))
        assertTrue(problems.contains("secondary"))
        assertTrue(problems.contains("accent"))
        assertFalse(problems.contains("gradientEnd"))
    }
}
