package app.luma.standby.domain.widget

data class WeatherInfo(
    val temperatureC: Int,
    val condition: WeatherCondition,
    val city: String?,
    val updatedAtMillis: Long,
    /** Coordinates the reading was fetched for, so a city change invalidates it. */
    val locationKey: String? = null,
)
