package app.luma.standby.domain.widget

data class WeatherInfo(
    val temperatureC: Int,
    val condition: WeatherCondition,
    val city: String?,
    val updatedAtMillis: Long,
    /** Coordinates the reading was fetched for, so a city change invalidates it. */
    val locationKey: String? = null,
    /** Last known reading: render a stale label; readings older than 24h are hidden. */
    val isStale: Boolean = false,
)
