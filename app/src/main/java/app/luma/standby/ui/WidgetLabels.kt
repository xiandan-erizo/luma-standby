package app.luma.standby.ui

import androidx.annotation.StringRes
import app.luma.standby.R
import app.luma.standby.domain.widget.BuiltinWidgets
import app.luma.standby.domain.widget.LumaWidget
import app.luma.standby.domain.widget.WidgetSlot

/** Widgets that are actually implemented. Calendar is a shell and stays hidden. */
internal val selectableWidgets: List<LumaWidget>
    get() = BuiltinWidgets.all.filter { widgetTitleRes(it.id) != null }

/** Localized title for a widget id; null means "not offered in settings". */
@StringRes
internal fun widgetTitleRes(id: String): Int? = when (id) {
    "battery" -> R.string.widget_battery
    "weather" -> R.string.widget_weather
    "alarm" -> R.string.widget_alarm
    "media" -> R.string.widget_media
    "notification" -> R.string.widget_notification
    else -> null
}

@StringRes
internal fun slotTitleRes(slot: WidgetSlot): Int = when (slot) {
    WidgetSlot.TOP_RIGHT -> R.string.widget_slot_top_right
    WidgetSlot.BOTTOM_RIGHT -> R.string.widget_slot_bottom_right
    WidgetSlot.BOTTOM_LEFT -> R.string.widget_slot_bottom_left
}

/** Right column (top + bottom) shares 3 tiles; bottom-left holds one. Mirrors WidgetRenderer. */
internal const val RIGHT_CAPACITY = 3
internal const val LEFT_CAPACITY = 1

internal fun WidgetSlot.isRight(): Boolean = this != WidgetSlot.BOTTOM_LEFT

/** Offline city presets: no geocoding call is ever made. */
internal data class CityPreset(@StringRes val nameRes: Int, val lat: Double, val lon: Double)

internal val cityPresets: List<CityPreset> = listOf(
    CityPreset(R.string.city_beijing, 39.9042, 116.4074),
    CityPreset(R.string.city_shanghai, 31.2304, 121.4737),
    CityPreset(R.string.city_guangzhou, 23.1291, 113.2644),
    CityPreset(R.string.city_shenzhen, 22.5431, 114.0579),
    CityPreset(R.string.city_chengdu, 30.5728, 104.0668),
    CityPreset(R.string.city_hangzhou, 30.2741, 120.1551),
)

internal fun CityPreset.matches(lat: Double?, lon: Double?): Boolean =
    lat != null && lon != null && kotlin.math.abs(lat - this.lat) < 1e-3 && kotlin.math.abs(lon - this.lon) < 1e-3

/** Parses a coordinate, rejecting NaN/Infinity and out-of-range values. */
internal fun parseCoordinate(text: String, limit: Double): Double? =
    text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it >= -limit && it <= limit }
