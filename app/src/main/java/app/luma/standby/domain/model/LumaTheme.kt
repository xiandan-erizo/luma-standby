package app.luma.standby.domain.model

import android.graphics.Color
import kotlinx.serialization.Serializable

/** Font families we are willing to ask the platform for. All are system families, no bundled TTF. */
@Serializable
enum class FontChoice(val label: String, val family: String) {
    ROUNDED("Rounded", "sans-serif-rounded"),
    SANS("Sans", "sans-serif"),
    SERIF("Serif", "serif"),
    MONO("Mono", "monospace"),
}

/** The three product modes from the PRD. */
@Serializable
enum class DisplayMode {
    STANDARD,
    ULTRA_LOW_POWER,
    SHOWCASE,
}

@Serializable
enum class BrightnessProfile {
    STANDARD,
    ULTRA_LOW,
    SHOWCASE,
    NIGHT,
}

/**
 * A theme is pure data: it never touches a View, so it can be serialised,
 * exported, imported and unit-tested without an Android context.
 */
@Serializable
data class LumaTheme(
    val id: String,
    val name: String,
    val background: String,
    val primary: String,
    val secondary: String,
    val accent: String = primary,
    val gradient: Boolean = false,
    val gradientEnd: String = background,
    val font: FontChoice = FontChoice.ROUNDED,
    val showSeconds: Boolean = false,
    val brightnessProfile: BrightnessProfile = BrightnessProfile.STANDARD,
    val builtIn: Boolean = false,
)

data class ResolvedColors(
    val background: Int,
    val backgroundEnd: Int,
    val primary: Int,
    val secondary: Int,
    val accent: Int,
)

fun parseColorOr(raw: String, fallback: Int): Int = try {
    Color.parseColor(raw)
} catch (e: IllegalArgumentException) {
    fallback
}

fun LumaTheme.resolveColors(): ResolvedColors {
    val bg = parseColorOr(background, Color.BLACK)
    val fg = parseColorOr(primary, Color.WHITE)
    return ResolvedColors(
        background = bg,
        backgroundEnd = parseColorOr(gradientEnd, bg),
        primary = fg,
        secondary = parseColorOr(secondary, Color.GRAY),
        accent = parseColorOr(accent, fg),
    )
}

val LumaTheme.isPureBlack: Boolean
    get() = parseColorOr(background, Color.BLACK) == Color.BLACK
