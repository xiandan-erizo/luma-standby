package app.luma.standby.platform.widgets

import android.content.Context
import app.luma.standby.domain.widget.MediaInfo
import app.luma.standby.domain.widget.NotificationInfo
import app.luma.standby.domain.widget.WeatherInfo
import app.luma.standby.domain.widget.WidgetData

/**
 * Collects one WidgetData snapshot for a redraw.
 *
 * Every source is optional and cheap-when-disabled: the caller passes the set of
 * enabled widget ids, and a source is not touched unless something needs it. That
 * is the whole point of the contract - no widget, no work, no wakeup.
 *
 * This class holds no cache of its own; the caller decides how long a weather
 * reading stays valid.
 */
class WidgetDataCollector(private val context: Context) {

    private val systemSource = SystemWidgetSource(context)

    fun collect(
        enabledIds: Set<String>,
        weather: WeatherInfo? = null,
    ): WidgetData {
        val needsBattery = enabledIds.contains("battery")
        val needsAlarm = enabledIds.contains("alarm")
        val needsNotification = enabledIds.contains("notification")
        val needsMedia = enabledIds.contains("media")
        val needsWeather = enabledIds.contains("weather")

        val needsNotificationAccess = needsNotification || needsMedia
        val hasNotificationAccess = needsNotificationAccess && LumaNotificationListener.isEnabled(context)
        val notifications: List<NotificationInfo> =
            if (hasNotificationAccess) LumaNotificationListener.latest(context) else emptyList()

        // Media is inferred from notification content: MediaSessionManager needs the
        // same grant and, on most ROMs, gives no extra certainty for a one-line tile.
        val media: MediaInfo? = if (needsMedia) mediaFrom(notifications) else null

        return WidgetData(
            battery = if (needsBattery) systemSource.battery() else null,
            nextAlarm = if (needsAlarm) systemSource.nextAlarm() else null,
            weather = if (needsWeather) weather else null,
            notifications = notifications,
            media = media,
        )
    }

    private fun mediaFrom(notifications: List<NotificationInfo>): MediaInfo? {
        val music = notifications.firstOrNull { isMusicApp(it.appLabel) } ?: return null
        return MediaInfo(title = music.title, artist = null, playing = true)
    }

    private fun isMusicApp(label: String): Boolean {
        val lower = label.lowercase()
        return MUSIC_HINTS.any { lower.contains(it) }
    }

    private companion object {
        val MUSIC_HINTS = listOf(
            "music",
            "spotify",
            "网易云",
            "qq音乐",
            "apple music",
            "youtube music",
        )
    }
}
