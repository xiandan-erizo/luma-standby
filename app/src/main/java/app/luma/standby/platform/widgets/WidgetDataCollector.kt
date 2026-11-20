package app.luma.standby.platform.widgets

import android.content.Context
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
    private val mediaSource = MediaSessionWidgetSource(context)

    fun collect(
        enabledIds: Set<String>,
        weather: WeatherInfo? = null,
        notificationAlertsEnabled: Boolean = false,
    ): WidgetData {
        val needsBattery = enabledIds.contains("battery")
        val needsAlarm = enabledIds.contains("alarm")
        val needsNotificationWidget = enabledIds.contains("notification")
        val needsNotifications = needsNotificationWidget || notificationAlertsEnabled
        val needsMedia = enabledIds.contains("media")
        val needsWeather = enabledIds.contains("weather")

        val needsSensitiveData = needsNotifications || needsMedia
        val hasAccess = needsSensitiveData && SensitiveWidgetAccess.isUnlocked(context) &&
            LumaNotificationListener.isEnabled(context)
        // Media opt-in never enables notification-title collection.
        LumaNotificationListener.setCollectionEnabled(needsNotifications && hasAccess)
        val notifications = if (needsNotifications && hasAccess) {
            LumaNotificationListener.latest(context)
        } else emptyList()
        val media = mediaSource.current(enabled = needsMedia && hasAccess)

        val battery = if (needsBattery) systemSource.battery() else null
        val nextAlarm = if (needsAlarm) systemSource.nextAlarm() else null
        val mayPublishSensitive = hasAccess && SensitiveWidgetAccess.isUnlocked(context) &&
            LumaNotificationListener.isEnabled(context)
        if (!mayPublishSensitive) LumaNotificationListener.setCollectionEnabled(false)

        return WidgetData(
            battery = battery,
            nextAlarm = nextAlarm,
            weather = if (needsWeather) weather else null,
            notifications = if (mayPublishSensitive) notifications else emptyList(),
            media = if (mayPublishSensitive) media else null,
        )
    }
}
