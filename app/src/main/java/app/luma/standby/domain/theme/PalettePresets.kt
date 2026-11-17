package app.luma.standby.domain.theme

import app.luma.standby.domain.model.BrightnessProfile
import app.luma.standby.domain.model.FontChoice
import app.luma.standby.domain.model.LumaTheme

/**
 * Curated colour pairs in the spirit of the reference colour picker: a
 * background and a foreground that are already in tune, rather than a whole
 * theme. Picking a palette keeps the current font and produces a custom theme,
 * so colour and typography stay independent.
 */
object PalettePresets {

    val all: List<LumaTheme> = listOf(
        LumaTheme(
            id = "palette-ocean-rose",
            name = "Ocean Rose",
            background = "#31596B",
            primary = "#F4C9D5",
            secondary = "#E8E9E7",
            accent = "#E9A8C0",
            font = FontChoice.ROUNDED,
        ),
        LumaTheme(
            id = "palette-midnight-lavender",
            name = "Midnight Lavender",
            background = "#24283B",
            primary = "#D9CDED",
            secondary = "#AAA7BE",
            accent = "#A99ACF",
        ),
        LumaTheme(
            id = "palette-forest-mist",
            name = "Forest Mist",
            background = "#354A40",
            primary = "#E8E1CE",
            secondary = "#B7C7B3",
            accent = "#9EBAA5",
        ),
        LumaTheme(
            id = "palette-burgundy",
            name = "Burgundy",
            background = "#492E3B",
            primary = "#F0CDD2",
            secondary = "#C6A8B2",
            accent = "#D5A5B1",
        ),
        LumaTheme(
            id = "palette-black-amber",
            name = "Black Amber",
            background = "#050607",
            primary = "#D9B97E",
            secondary = "#777C7E",
            accent = "#A88B5D",
            brightnessProfile = BrightnessProfile.ULTRA_LOW,
        ),
        LumaTheme(
            id = "palette-sage-ink",
            name = "Sage Ink",
            background = "#A8B9A1",
            primary = "#10140F",
            secondary = "#2C3529",
            accent = "#3E5140",
        ),
        LumaTheme(
            id = "palette-ink-cream",
            name = "Ink Cream",
            background = "#101418",
            primary = "#EDE7DA",
            secondary = "#9AA0A6",
            accent = "#C9BFA6",
        ),
        LumaTheme(
            id = "palette-slate-blue",
            name = "Slate Blue",
            background = "#2B3A55",
            primary = "#CFE3F5",
            secondary = "#8FA3BD",
            accent = "#7FB0E8",
        ),
        LumaTheme(
            id = "palette-terracotta",
            name = "Terracotta",
            background = "#5A2E24",
            primary = "#F2D3B3",
            secondary = "#C09A82",
            accent = "#E0A268",
        ),
        LumaTheme(
            id = "palette-plum-noir",
            name = "Plum Noir",
            background = "#1A1020",
            primary = "#E7C6F2",
            secondary = "#9A86A6",
            accent = "#C48FE0",
        ),
        LumaTheme(
            id = "palette-sand",
            name = "Sand",
            background = "#C9B89A",
            primary = "#241E16",
            secondary = "#4A3F30",
            accent = "#6B5540",
        ),
        LumaTheme(
            id = "palette-arctic",
            name = "Arctic",
            background = "#E9EDF0",
            primary = "#1B2A33",
            secondary = "#4A5A66",
            accent = "#2E6F8E",
        ),
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
            builtIn = false,
        )
}
