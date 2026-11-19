package app.luma.standby.domain.theme

import app.luma.standby.domain.model.BrightnessProfile
import app.luma.standby.domain.model.FontChoice
import app.luma.standby.domain.model.LumaTheme

/**
 * Curated colour pairs, rebuilt around low chroma rather than "nice colours".
 *
 * Why these values and not brighter ones: a standby face is mostly one large
 * colour field with a few glyphs on it. Saturated backgrounds compete with the
 * numbers and read as a phone wallpaper, not as a display surface. Every palette
 * here keeps background saturation in a narrow 8-25% band, holds the text at
 * 70-88% lightness so it glows instead of glowing AT you, and uses the accent only
 * on small elements (the reading, the icon) rather than on whole words.
 *
 * Groups: A = coloured dark surfaces, B = near-black for OLED, C = light surfaces.
 */
object PalettePresets {

    // A: coloured dark surfaces. Saturation deliberately restrained.
    val dusk = LumaTheme("dusk-teal", "Dusk Teal", "#2A4A52", "#E8C9B8", "#9DB4B0", "#D9A97F")
    val petrol = LumaTheme("petrol", "Petrol", "#1E3A45", "#E3B7A0", "#8FA6AE", "#C98F6B")
    val oliveClay = LumaTheme("olive-clay", "Olive Clay", "#3B3F32", "#E6D9BE", "#A8AE92", "#C7A76E")
    val wineSlate = LumaTheme("wine-slate", "Wine Slate", "#3A2733", "#E9C6C6", "#A98F9C", "#C79095")
    val indigoMist = LumaTheme("indigo-mist", "Indigo Mist", "#2B2F45", "#DCD9E8", "#9BA1B8", "#8E9BC4")
    val graphiteGold = LumaTheme("graphite-gold", "Graphite Gold", "#232527", "#D8C49A", "#8C8E8C", "#B39B66")

    // B: near-black for OLED, where the black pixels are genuinely off.
    val obsidianAmber = LumaTheme(
        "obsidian-amber", "Obsidian Amber", "#070707", "#E4C08A", "#7A7772", "#A9885C",
        brightnessProfile = BrightnessProfile.ULTRA_LOW,
    )
    val inkChampagne = LumaTheme(
        "ink-champagne", "Ink Champagne", "#050506", "#EADCC0", "#787672", "#B8A583",
        brightnessProfile = BrightnessProfile.ULTRA_LOW,
    )
    val nearBlackSage = LumaTheme(
        "near-black-sage", "Near Black Sage", "#060706", "#D9DCC8", "#75786F", "#9BA386",
        brightnessProfile = BrightnessProfile.ULTRA_LOW,
    )
    val cocoaNoir = LumaTheme(
        "cocoa-noir", "Cocoa Noir", "#0A0705", "#E2C7A8", "#7C7168", "#B08E6A",
        brightnessProfile = BrightnessProfile.ULTRA_LOW,
    )
    val midnightPlum = LumaTheme(
        "midnight-plum", "Midnight Plum", "#08060A", "#D9C0DD", "#7A7080", "#A98CB4",
        brightnessProfile = BrightnessProfile.ULTRA_LOW,
    )
    val coldSteel = LumaTheme(
        "cold-steel", "Cold Steel", "#060708", "#D2D8DB", "#717779", "#93A0A5",
        brightnessProfile = BrightnessProfile.ULTRA_LOW,
    )

    // C: light surfaces for daylight and desk use. Low saturation, never pure white.
    val bone = LumaTheme("bone", "Bone", "#EDE7DC", "#2B2A26", "#7A7568", "#4A4741")
    val paleSage = LumaTheme("pale-sage", "Pale Sage", "#DDE2D4", "#253026", "#6B7568", "#43503F")
    val softSand = LumaTheme("soft-sand", "Soft Sand", "#E7DED0", "#2E2822", "#7D7365", "#52463A")
    val mistBlue = LumaTheme("mist-blue", "Mist Blue", "#DDE3E8", "#1F2930", "#68737C", "#3C4A55")
    val blush = LumaTheme("blush", "Blush", "#EDDFDC", "#2E2426", "#7B6C6B", "#544445")
    val coolGrey = LumaTheme("cool-grey", "Cool Grey", "#E4E5E3", "#1E1F1E", "#6E706E", "#444645")

    val groupA: List<LumaTheme> = listOf(dusk, petrol, oliveClay, wineSlate, indigoMist, graphiteGold)
    val groupB: List<LumaTheme> = listOf(
        obsidianAmber, inkChampagne, nearBlackSage, cocoaNoir, midnightPlum, coldSteel,
    )
    val groupC: List<LumaTheme> = listOf(bone, paleSage, softSand, mistBlue, blush, coolGrey)

    val all: List<LumaTheme> = groupA + groupB + groupC

    /** Order used by the colour tab: dark surfaces first, near-black and light after. */
    fun groups(): List<Pair<String, List<LumaTheme>>> = listOf(
        "A" to groupA,
        "B" to groupB,
        "C" to groupC,
    )

    fun findById(id: String): LumaTheme? = all.firstOrNull { it.id == id }

    /** Applies a palette's colours to a theme, keeping its font. */
    fun applyPalette(base: LumaTheme, palette: LumaTheme, id: String, name: String): LumaTheme =
        base.copy(
            id = id,
            name = name,
            background = palette.background,
            primary = palette.primary,
            secondary = palette.secondary,
            accent = palette.accent,
            gradientEnd = palette.background,
            brightnessProfile = palette.brightnessProfile,
            builtIn = false,
        )
}