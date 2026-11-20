package app.luma.standby.domain.theme

import app.luma.standby.domain.model.FaceStyle
import app.luma.standby.domain.model.FontChoice
import app.luma.standby.domain.model.LumaSettings
import app.luma.standby.domain.model.LumaTheme

/**
 * Pure edit operations on settings. Keeping these out of the Compose layer means
 * they can be tested without an emulator, and the editor UI cannot invent its own
 * persistence rules.
 */
object ThemeEditing {

    const val CUSTOM_PREFIX = "custom-"

    private val hexPattern = Regex("^#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")

    fun customId(seed: Long): String = CUSTOM_PREFIX + seed

    fun duplicate(source: LumaTheme, seed: Long): LumaTheme = source.copy(
        id = customId(seed),
        name = source.name + " custom",
        builtIn = false,
    )

    /** Insert or replace a custom theme and select it. */
    fun upsert(settings: LumaSettings, theme: LumaTheme): LumaSettings {
        val others = settings.customThemes.filterNot { it.id == theme.id }
        return settings.copy(customThemes = others + theme, themeId = theme.id)
    }

    fun remove(settings: LumaSettings, id: String): LumaSettings {
        val remaining = settings.customThemes.filterNot { it.id == id }
        val nextTheme = if (settings.themeId == id) ThemePresets.DEFAULT_ID else settings.themeId
        return settings.copy(customThemes = remaining, themeId = nextTheme)
    }

    fun isCustom(theme: LumaTheme): Boolean = !theme.builtIn && theme.id.startsWith(CUSTOM_PREFIX)

    /**
     * Colour, font and face style are independent choices: applying a palette keeps
     * the current font, applying a font keeps the current colours, and changing any
     * property on a built-in theme forks it into a custom theme first.
     */
    fun withPalette(settings: LumaSettings, palette: LumaTheme, seed: Long): LumaTheme {
        val base = settings.currentTheme()
        val name = palette.name + " " + base.font.label
        return PalettePresets.applyPalette(base, palette, customId(seed), name)
    }

    /** Applies a palette's colours while keeping the user's current [FaceStyle] (for horizontal swipe). */
    fun withPaletteKeepingStyle(settings: LumaSettings, palette: LumaTheme, seed: Long): LumaTheme {
        val base = settings.currentTheme()
        val id = if (!base.builtIn) base.id else customId(seed)
        val updated = PalettePresets.applyPalette(base, palette, id, palette.name)
        return updated.copy(faceStyle = base.faceStyle)
    }

    fun withFont(settings: LumaSettings, font: FontChoice, seed: Long): LumaTheme {
        val base = settings.currentTheme()
        val forked = if (base.builtIn) {
            base.copy(id = customId(seed), name = base.name + " " + font.label, builtIn = false)
        } else {
            base.copy(name = base.name)
        }
        return forked.copy(font = font)
    }

    fun withFaceStyle(settings: LumaSettings, style: FaceStyle, seed: Long): LumaTheme {
        val base = settings.currentTheme()
        if (base.faceStyle == style && !base.builtIn) return base
        val styleLabel = when (style) {
            FaceStyle.ANALOG -> "Analog"
            FaceStyle.SPLIT -> "Split"
            FaceStyle.FLOAT -> "Float"
            FaceStyle.SOLAR -> "Solar"
        }
        val forked = if (base.builtIn) {
            base.copy(id = customId(seed), name = base.name + " " + styleLabel, builtIn = false)
        } else {
            base
        }
        return forked.copy(faceStyle = style)
    }

    fun validateHex(raw: String): String? {
        val trimmed = raw.trim()
        return if (hexPattern.matches(trimmed)) trimmed else null
    }

    fun validateAll(theme: LumaTheme): List<String> {
        val problems = mutableListOf<String>()
        if (theme.name.isBlank()) problems.add("name")
        if (validateHex(theme.background) == null) problems.add("background")
        if (validateHex(theme.primary) == null) problems.add("primary")
        if (validateHex(theme.secondary) == null) problems.add("secondary")
        if (validateHex(theme.accent) == null) problems.add("accent")
        if (theme.gradient && validateHex(theme.gradientEnd) == null) problems.add("gradientEnd")
        return problems
    }
}
