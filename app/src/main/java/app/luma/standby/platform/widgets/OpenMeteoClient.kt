package app.luma.standby.platform.widgets

import app.luma.standby.domain.widget.WeatherCondition
import app.luma.standby.domain.widget.WeatherInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Open-Meteo: no API key, no account, free for non-commercial use. The user's
 * city is a coordinate pair chosen in settings, so the app never needs location
 * permission.
 *
 * All network work happens off the main thread and is cached: the clock asks for
 * the last known value and a background refresh happens at most every 30 minutes.
 */
object OpenMeteoClient {

    private const val BASE = "https://api.open-meteo.com/v1/forecast"

    private val json = Json { ignoreUnknownKeys = true }

    fun buildUrl(lat: Double, lon: Double): String = String.format(
        Locale.US,
        "%s?latitude=%.4f&longitude=%.4f&current=temperature_2m,weather_code&timezone=auto&forecast_days=1",
        BASE,
        lat,
        lon,
    )

    fun fetch(lat: Double, lon: Double, city: String?, timeoutMs: Int = 8_000): WeatherInfo? {
        return runCatching {
            val connection = (URL(buildUrl(lat, lon)).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                requestMethod = "GET"
            }
            try {
                if (connection.responseCode !in 200..299) return null
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                parse(body, city, lat, lon)
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }

    /** Public for unit tests: the parser must not need a network. */
    fun parse(
        body: String,
        city: String?,
        lat: Double,
        lon: Double,
        nowMillis: Long = System.currentTimeMillis(),
    ): WeatherInfo? {
        val root: JsonObject = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val current = runCatching { root["current"]?.jsonObject }.getOrNull() ?: return null
        val temperature = runCatching {
            current["temperature_2m"]?.jsonPrimitive?.content?.toDouble()
        }.getOrNull() ?: return null
        val code = runCatching {
            current["weather_code"]?.jsonPrimitive?.content?.toDouble()?.toInt()
        }.getOrNull()

        return WeatherInfo(
            temperatureC = Math.round(temperature).toInt(),
            condition = conditionFor(code),
            city = city,
            updatedAtMillis = nowMillis,
            locationKey = String.format(Locale.US, "%.3f,%.3f", lat, lon),
        )
    }

    /** WMO weather interpretation codes, collapsed to what a clock should show. */
    fun conditionFor(code: Int?): WeatherCondition = when (code) {
        null -> WeatherCondition.UNKNOWN
        0, 1 -> WeatherCondition.CLEAR
        2 -> WeatherCondition.PARTLY_CLOUDY
        3 -> WeatherCondition.CLOUDY
        45, 48 -> WeatherCondition.FOG
        51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> WeatherCondition.RAIN
        71, 73, 75, 77, 85, 86 -> WeatherCondition.SNOW
        95, 96, 99 -> WeatherCondition.THUNDER
        else -> WeatherCondition.UNKNOWN
    }

    @Suppress("unused")
    private fun JsonObject.arrayOrEmpty(key: String) = runCatching { this[key]?.jsonArray }.getOrNull().orEmpty()
}
