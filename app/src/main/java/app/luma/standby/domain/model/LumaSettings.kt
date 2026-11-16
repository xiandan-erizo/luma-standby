package app.luma.standby.domain.model

import kotlinx.serialization.Serializable

/**
 * Everything the app persists. Nullable booleans mean "follow the system / theme".
 * brightness uses the Android window convention: -1 = system, 0..1 = manual.
 *
 * The night window is stored as minutes-of-day so it survives serialisation
 * without locale or timezone assumptions; the ramp is the number of minutes used
 * to fade in/out around each boundary.
 */
@Serializable
data class LumaSettings(
    val themeId: String = "atlantic-rose",
    val mode: DisplayMode = DisplayMode.STANDARD,
    val use24Hour: Boolean? = null,
    val showSeconds: Boolean? = null,
    val brightness: Float = -1f,
    val nightBrightness: Float = 0.06f,
    val autoNight: Boolean = false,
    val nightStartMinutes: Int = 22 * 60,
    val nightEndMinutes: Int = 7 * 60,
    val nightRampMinutes: Int = 45,
    val burnInShift: Boolean = true,
    val customThemes: List<LumaTheme> = emptyList(),
)
