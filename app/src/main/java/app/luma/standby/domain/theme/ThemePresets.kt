package app.luma.standby.domain.theme

import app.luma.standby.domain.model.BrightnessProfile
import app.luma.standby.domain.model.FontChoice
import app.luma.standby.domain.model.LumaSettings
import app.luma.standby.domain.model.LumaTheme

/** The five launch themes, exactly as specified in the PRD. */
object ThemePresets {

    const val DEFAULT_ID = "atlantic-rose"

    val atlanticRose = LumaTheme(
        id = "atlantic-rose",
        name = "Atlantic Rose",
        background = "#25596A",
        primary = "#FFCAD9",
        secondary = "#F9D9E3",
        accent = "#FFF1F4",
        font = FontChoice.DISPLAY,
        builtIn = true,
    )

    val midnightLavender = LumaTheme(
        id = "midnight-lavender",
        name = "Midnight Lavender",
        background = "#24283B",
        primary = "#D9CDED",
        secondary = "#AAA7BE",
        accent = "#A99ACF",
        font = FontChoice.ROUNDED,
        builtIn = true,
    )

    val forestMist = LumaTheme(
        id = "forest-mist",
        name = "Forest Mist",
        background = "#354A40",
        primary = "#E8E1CE",
        secondary = "#B7C7B3",
        accent = "#9EBAA5",
        font = FontChoice.SANS,
        builtIn = true,
    )

    val burgundy = LumaTheme(
        id = "burgundy",
        name = "Burgundy",
        background = "#492E3B",
        primary = "#F0CDD2",
        secondary = "#C6A8B2",
        accent = "#D5A5B1",
        font = FontChoice.SERIF,
        builtIn = true,
    )

    val blackAmber = LumaTheme(
        id = "black-amber",
        name = "Black Amber",
        background = "#050607",
        primary = "#D9B97E",
        secondary = "#777C7E",
        accent = "#A88B5D",
        gradient = false,
        font = FontChoice.ROUNDED,
        brightnessProfile = BrightnessProfile.ULTRA_LOW,
        builtIn = true,
    )

    val all: List<LumaTheme> = listOf(
        atlanticRose,
        midnightLavender,
        forestMist,
        burgundy,
        blackAmber,
    )

    fun findById(id: String, custom: List<LumaTheme> = emptyList()): LumaTheme =
        (all + custom).firstOrNull { it.id == id } ?: atlanticRose
}

fun LumaSettings.themes(): List<LumaTheme> = ThemePresets.all + customThemes

fun LumaSettings.currentTheme(): LumaTheme = ThemePresets.findById(themeId, customThemes)
