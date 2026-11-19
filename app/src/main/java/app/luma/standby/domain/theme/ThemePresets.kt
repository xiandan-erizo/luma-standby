package app.luma.standby.domain.theme

import app.luma.standby.domain.model.FontChoice
import app.luma.standby.domain.model.LumaSettings
import app.luma.standby.domain.model.LumaTheme

/**
 * The built-in themes. Colour lives in [PalettePresets] so that colour and typeface
 * stay independent choices; these presets just pair each launch palette with the
 * typeface that suits it.
 *
 * Legacy ids (`atlantic-rose`, `burgundy`, ...) are kept as aliases because they
 * are persisted in users' settings and embedded in exported theme JSON.
 */
object ThemePresets {

    const val DEFAULT_ID = "dusk-teal"

    private fun preset(palette: LumaTheme, font: FontChoice): LumaTheme =
        palette.copy(font = font, builtIn = true)

    val duskTeal = preset(PalettePresets.dusk, FontChoice.DISPLAY)
    val petrol = preset(PalettePresets.petrol, FontChoice.DISPLAY)
    val oliveClay = preset(PalettePresets.oliveClay, FontChoice.DISPLAY)
    val wineSlate = preset(PalettePresets.wineSlate, FontChoice.DISPLAY)
    val indigoMist = preset(PalettePresets.indigoMist, FontChoice.DISPLAY)
    val graphiteGold = preset(PalettePresets.graphiteGold, FontChoice.DISPLAY)

    val obsidianAmber = preset(PalettePresets.obsidianAmber, FontChoice.DISPLAY)
    val inkChampagne = preset(PalettePresets.inkChampagne, FontChoice.DISPLAY)
    val nearBlackSage = preset(PalettePresets.nearBlackSage, FontChoice.DISPLAY)
    val cocoaNoir = preset(PalettePresets.cocoaNoir, FontChoice.DISPLAY)
    val midnightPlum = preset(PalettePresets.midnightPlum, FontChoice.DISPLAY)
    val coldSteel = preset(PalettePresets.coldSteel, FontChoice.DISPLAY)

    val bone = preset(PalettePresets.bone, FontChoice.DISPLAY)
    val paleSage = preset(PalettePresets.paleSage, FontChoice.DISPLAY)
    val softSand = preset(PalettePresets.softSand, FontChoice.DISPLAY)
    val mistBlue = preset(PalettePresets.mistBlue, FontChoice.DISPLAY)
    val blush = preset(PalettePresets.blush, FontChoice.DISPLAY)
    val coolGrey = preset(PalettePresets.coolGrey, FontChoice.DISPLAY)

    val all: List<LumaTheme> = PalettePresets.all.map { palette ->
        preset(palette, FontChoice.DISPLAY)
    }

    /** Ids that older settings or exported JSON may still carry. */
    private val LEGACY_ALIASES: Map<String, String> = mapOf(
        "atlantic-rose" to "dusk-teal",
        "midnight-lavender" to "indigo-mist",
        "forest-mist" to "olive-clay",
        "burgundy" to "wine-slate",
        "black-amber" to "obsidian-amber",
        "palette-ocean-rose" to "dusk-teal",
        "palette-midnight-lavender" to "indigo-mist",
        "palette-forest-mist" to "olive-clay",
        "palette-burgundy" to "wine-slate",
        "palette-black-amber" to "obsidian-amber",
    )

    /** Maps an unknown id to a preset: legacy aliases first, then the palette- prefix. */
    fun migrate(id: String): String =
        LEGACY_ALIASES[id] ?: id.removePrefix("palette-")

    fun findById(id: String, custom: List<LumaTheme> = emptyList()): LumaTheme =
        (all + custom).firstOrNull { it.id == id }
            ?: all.firstOrNull { it.id == migrate(id) }
            ?: duskTeal
}

fun LumaSettings.themes(): List<LumaTheme> = ThemePresets.all + customThemes

fun LumaSettings.currentTheme(): LumaTheme = ThemePresets.findById(themeId, customThemes)