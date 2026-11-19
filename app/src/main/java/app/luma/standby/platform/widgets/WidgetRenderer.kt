package app.luma.standby.platform.widgets

import android.content.Context
import app.luma.standby.R
import app.luma.standby.domain.model.LumaSettings
import app.luma.standby.domain.model.widgetConfig
import app.luma.standby.domain.widget.BuiltinWidgets
import app.luma.standby.domain.widget.WidgetData
import app.luma.standby.domain.widget.WidgetSlot
import app.luma.standby.domain.widget.WeatherCondition
import app.luma.standby.ui.clock.WidgetTile

object WidgetRenderer {
    fun tiles(settings: LumaSettings, data: WidgetData,
        includeNotification: (String) -> Boolean = { BuiltinWidgets.byId(it) != null }, context: Context? = null,
    ): List<WidgetTile> {
        val seen = mutableSetOf<String>()
        var right = 0; var left = 0
        return settings.widgetConfig.placements.mapNotNull { placement ->
            if (!seen.add(placement.widgetId) || !includeNotification(placement.widgetId)) return@mapNotNull null
            // Calendar is not implemented: never expose its shell as a selectable capability.
            if (placement.widgetId == "calendar") return@mapNotNull null
            val widget = BuiltinWidgets.byId(placement.widgetId) ?: return@mapNotNull null
            var content = widget.render(data) ?: return@mapNotNull null
            if (placement.slot == WidgetSlot.BOTTOM_LEFT) { if (left++ >= 1) return@mapNotNull null }
            else if (right++ >= 3) return@mapNotNull null
            if (context != null) content = when (placement.widgetId) {
                "battery" -> content.copy(secondary = context.getString(if(data.battery?.charging == true) R.string.widget_charging else R.string.widget_battery))
                "alarm" -> content.copy(secondary = context.getString(R.string.widget_alarm))
                "weather" -> {
                    val res = when(data.weather?.condition) {
                        WeatherCondition.CLEAR -> R.string.weather_clear
                        WeatherCondition.PARTLY_CLOUDY -> R.string.weather_partly_cloudy
                        WeatherCondition.CLOUDY -> R.string.weather_cloudy
                        WeatherCondition.RAIN -> R.string.weather_rain
                        WeatherCondition.SNOW -> R.string.weather_snow
                        WeatherCondition.THUNDER -> R.string.weather_thunder
                        WeatherCondition.FOG -> R.string.weather_fog
                        else -> R.string.widget_weather
                    }
                    val label = listOfNotNull(data.weather?.city, context.getString(res),
                        if(data.weather?.isStale == true) context.getString(R.string.weather_cached) else null).joinToString(" · ")
                    content.copy(secondary = label)
                }
                "notification" -> if (content.secondary != null && content.secondary.equals(content.primary, ignoreCase = true)) {
                    // Private-visibility notifications expose only the app label, which is
                    // already the primary line; repeating it reads as a rendering bug.
                    content.copy(secondary = null)
                } else {
                    content
                }
                else -> content
            }
            WidgetTile(placement.slot, content.copy(primary = clean(content.primary), secondary = content.secondary?.let(::clean)))
        }
    }
    private fun clean(text: String) = text.replace(Regex("[\r\n\t]+"), " ").trim().take(160)
    fun slotsFor(settings: LumaSettings): Map<String, WidgetSlot> = settings.widgetConfig.placements.associate { it.widgetId to it.slot }
}