package app.luma.standby.domain

import app.luma.standby.domain.model.LumaSettings

/**
 * Night policy. Pure arithmetic on minutes-of-day: no clock, no location, no
 * network, so it is testable and timezone-agnostic (the caller supplies local
 * wall-clock minutes and re-evaluates whenever the timezone changes).
 *
 * Rules, in priority order:
 * 1. An explicit manual brightness always wins - silently overriding a value the
 *    user dragged to is worse than being too bright at night.
 * 2. autoNight off => no window override at all (-1, i.e. system).
 * 3. Inside the window => night brightness, faded by the ramp near each boundary.
 */
object NightMode {

    const val MINUTES_PER_DAY = 24 * 60

    fun isNight(startMinutes: Int, endMinutes: Int, minuteOfDay: Int): Boolean {
        val m = normalize(minuteOfDay)
        val start = normalize(startMinutes)
        val end = normalize(endMinutes)
        if (start == end) return false
        return if (start < end) m >= start && m < end else m >= start || m < end
    }

    /**
     * 0 = full day, 1 = full night. Linear across [rampMinutes] on both sides of
     * the window, and correct for windows that cross midnight.
     */
    fun nightFactor(startMinutes: Int, endMinutes: Int, rampMinutes: Int, minuteOfDay: Int): Float {
        val start = normalize(startMinutes)
        val end = normalize(endMinutes)
        if (start == end) return 0f
        val ramp = rampMinutes.coerceAtLeast(0)
        val m = normalize(minuteOfDay)
        val nightLength = ((end - start) + MINUTES_PER_DAY) % MINUTES_PER_DAY
        val sinceStart = ((m - start) + MINUTES_PER_DAY) % MINUTES_PER_DAY

        if (sinceStart < nightLength) {
            return if (ramp == 0) 1f else (sinceStart.toFloat() / ramp.toFloat()).coerceIn(0f, 1f)
        }

        // Daytime: fade back out over the ramp that follows the night's end.
        if (ramp == 0) return 0f
        val sinceEnd = ((m - end) + MINUTES_PER_DAY) % MINUTES_PER_DAY
        return (1f - sinceEnd.toFloat() / ramp.toFloat()).coerceIn(0f, 1f)
    }

    /**
     * The window brightness to apply, or -1 for "leave the system value alone".
     *
     * [systemBrightness] is the current system brightness scaled to 0..1 when it
     * could be read. It is only used as the day end of the ramp, so a ramp is
     * still meaningful when the user follows system brightness.
     */
    fun targetBrightness(
        settings: LumaSettings,
        minuteOfDay: Int,
        systemBrightness: Float?,
    ): Float {
        if (settings.brightness >= 0f) return settings.brightness
        if (!settings.autoNight) return -1f

        val factor = nightFactor(
            startMinutes = settings.nightStartMinutes,
            endMinutes = settings.nightEndMinutes,
            rampMinutes = settings.nightRampMinutes,
            minuteOfDay = minuteOfDay,
        )
        if (factor <= 0f) return -1f

        val night = settings.nightBrightness.coerceIn(0.01f, 1f)
        val day = systemBrightness?.coerceIn(0.01f, 1f)
            ?: return if (factor >= 0.5f) night else -1f

        return lerp(day, minOf(day, night), factor)
    }

    fun formatHhMm(minutes: Int): String {
        val m = normalize(minutes)
        return String.format(java.util.Locale.US, "%02d:%02d", m / 60, m % 60)
    }

    /** Parses "22:00" / "7:05" / "2200". Returns null when the text is not a time. */
    fun parseHhMm(text: String): Int? {
        val trimmed = text.trim()
        val parts = when {
            trimmed.contains(':') -> trimmed.split(':')
            trimmed.length == 4 -> listOf(trimmed.substring(0, 2), trimmed.substring(2))
            else -> return null
        }
        if (parts.size != 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }

    private fun lerp(from: Float, to: Float, fraction: Float): Float =
        from + (to - from) * fraction

    private fun normalize(minutes: Int): Int = ((minutes % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY
}
