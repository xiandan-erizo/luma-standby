package app.luma.standby.domain.widget

/**
 * A widget is a pure function of a [WidgetData] snapshot.
 *
 * The contract exists so that data sources (battery, weather, notifications,
 * calendar, media) and the renderer never know about each other: adding a source
 * means filling in one field, adding a widget means drawing from one field.
 * Everything here is serialisable-by-hand and unit-testable off-device.
 */
interface LumaWidget {

    /** Stable id, also the persistence key for placement/permission state. */
    val id: String

    /** Human name for the settings list. */
    val label: String

    /** Which permission (if any) the source needs before it can produce data. */
    val permission: WidgetPermission

    /** Data is only refreshed while a widget is enabled, so power follows intent. */
    fun render(data: WidgetData): WidgetContent?
}

enum class WidgetPermission {
    NONE,
    NOTIFICATION_ACCESS,
    CALENDAR,
    LOCATION,
}

/**
 * Everything a widget may read, captured once per redraw. Nulls mean "unknown"
 * and every widget must handle them by returning null rather than a placeholder.
 */
data class WidgetData(
    val battery: BatteryInfo? = null,
    val weather: WeatherInfo? = null,
    val nextAlarm: AlarmInfo? = null,
    val calendar: CalendarInfo? = null,
    val media: MediaInfo? = null,
    val notifications: List<NotificationInfo> = emptyList(),
)

data class BatteryInfo(val percent: Int, val charging: Boolean)

enum class WeatherCondition {
    CLEAR,
    PARTLY_CLOUDY,
    CLOUDY,
    RAIN,
    SNOW,
    THUNDER,
    FOG,
    UNKNOWN,
}

data class AlarmInfo(val hour: Int, val minute: Int)

data class CalendarInfo(val title: String, val startsAtMillis: Long, val startsToday: Boolean)

data class MediaInfo(val title: String, val artist: String?, val playing: Boolean)

data class NotificationInfo(
    val appLabel: String,
    val title: String,
    val packageName: String? = null,
    val postTimeMillis: Long = 0L,
    val key: String? = null,
)

enum class WidgetKind {
    STANDARD,
    BATTERY_RING,
    WEATHER_HERO,
    ALARM_BADGE,
    MEDIA_NOW,
    NOTIFICATION_CARD,
}

/**
 * What a widget draws. Rendering is primitive-only (text + vector paths/arcs) so
 * the face stays a single Canvas draw and never pulls in Compose per frame.
 */
data class WidgetContent(
    val primary: String,
    val secondary: String? = null,
    val glyph: WidgetGlyph = WidgetGlyph.NONE,
    val kind: WidgetKind = WidgetKind.STANDARD,
    val progress: Float? = null,
    val charging: Boolean = false,
)

enum class WidgetGlyph {
    NONE,
    BATTERY,
    BOLT,
    SUN,
    PARTLY_CLOUDY,
    CLOUD,
    RAIN,
    SNOW,
    THUNDER,
    FOG,
    ALARM,
    CALENDAR,
    MUSIC,
    BELL,
}
