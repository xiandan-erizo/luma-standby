package app.luma.standby.domain.widget

import app.luma.standby.domain.widget.WidgetPermission.NONE

/** Read-only facts every phone can produce without a permission prompt. */
object BuiltinWidgets {

    val battery = object : LumaWidget {
        override val id = "battery"
        override val label = "Battery"
        override val permission = NONE
        override fun render(data: WidgetData): WidgetContent? {
            val b = data.battery ?: return null
            return WidgetContent(
                primary = b.percent.toString() + "%",
                secondary = if (b.charging) "charging" else null,
                glyph = if (b.charging) WidgetGlyph.BOLT else WidgetGlyph.BATTERY,
            )
        }
    }

    val weather = object : LumaWidget {
        override val id = "weather"
        override val label = "Weather"
        override val permission = NONE
        override fun render(data: WidgetData): WidgetContent? {
            val w = data.weather ?: return null
            val condition = when (w.condition) {
                WeatherCondition.CLEAR -> "Clear"
                WeatherCondition.PARTLY_CLOUDY -> "Partly cloudy"
                WeatherCondition.CLOUDY -> "Cloudy"
                WeatherCondition.RAIN -> "Rain"
                WeatherCondition.SNOW -> "Snow"
                WeatherCondition.THUNDER -> "Thunder"
                WeatherCondition.FOG -> "Fog"
                WeatherCondition.UNKNOWN -> null
            }
            return WidgetContent(
                primary = w.temperatureC.toString() + "\u00B0",
                secondary = w.city ?: condition,
                glyph = glyphFor(w.condition),
            )
        }

        private fun glyphFor(condition: WeatherCondition): WidgetGlyph = when (condition) {
            WeatherCondition.CLEAR -> WidgetGlyph.SUN
            WeatherCondition.PARTLY_CLOUDY, WeatherCondition.CLOUDY -> WidgetGlyph.CLOUD
            WeatherCondition.RAIN -> WidgetGlyph.RAIN
            WeatherCondition.SNOW -> WidgetGlyph.SNOW
            WeatherCondition.THUNDER -> WidgetGlyph.THUNDER
            WeatherCondition.FOG -> WidgetGlyph.FOG
            WeatherCondition.UNKNOWN -> WidgetGlyph.NONE
        }
    }

    val alarm = object : LumaWidget {
        override val id = "alarm"
        override val label = "Next alarm"
        override val permission = NONE
        override fun render(data: WidgetData): WidgetContent? {
            val a = data.nextAlarm ?: return null
            return WidgetContent(
                primary = String.format(java.util.Locale.US, "%02d:%02d", a.hour, a.minute),
                glyph = WidgetGlyph.ALARM,
            )
        }
    }

    val calendar = object : LumaWidget {
        override val id = "calendar"
        override val label = "Next event"
        override val permission = WidgetPermission.CALENDAR
        override fun render(data: WidgetData): WidgetContent? {
            val c = data.calendar ?: return null
            val time = if (c.startsToday) "today" else "next"
            return WidgetContent(
                primary = c.title,
                secondary = time,
                glyph = WidgetGlyph.CALENDAR,
            )
        }
    }

    val media = object : LumaWidget {
        override val id = "media"
        override val label = "Now playing"
        override val permission = WidgetPermission.NOTIFICATION_ACCESS
        override fun render(data: WidgetData): WidgetContent? {
            val m = data.media ?: return null
            if (!m.playing) return null
            return WidgetContent(
                primary = m.title,
                secondary = m.artist,
                glyph = WidgetGlyph.MUSIC,
            )
        }
    }

    val notification = object : LumaWidget {
        override val id = "notification"
        override val label = "Latest notification"
        override val permission = WidgetPermission.NOTIFICATION_ACCESS
        override fun render(data: WidgetData): WidgetContent? {
            val latest = data.notifications.firstOrNull() ?: return null
            return WidgetContent(
                primary = latest.title,
                secondary = latest.appLabel,
                glyph = WidgetGlyph.BELL,
            )
        }
    }

    /** Order here is the order shown in settings. */
    val all: List<LumaWidget> = listOf(battery, weather, alarm, calendar, media, notification)

    fun byId(id: String): LumaWidget? = all.firstOrNull { it.id == id }
}
