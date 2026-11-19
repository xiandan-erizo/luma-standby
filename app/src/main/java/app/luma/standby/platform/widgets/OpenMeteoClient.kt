package app.luma.standby.platform.widgets

import app.luma.standby.domain.widget.WeatherCondition
import app.luma.standby.domain.widget.WeatherInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.coroutines.resume

/** No location permission or API key. Call only for an explicitly enabled, visible widget. */
object OpenMeteoClient {
    private const val BASE = "https://api.open-meteo.com/v1/forecast"
    private val json = Json { ignoreUnknownKeys = true }

    fun buildUrl(lat: Double, lon: Double): String {
        require(validCoordinates(lat, lon))
        return String.format(
            Locale.US,
            "%s?latitude=%.4f&longitude=%.4f&current=temperature_2m,weather_code&timezone=auto&forecast_days=1",
            BASE, lat, lon,
        )
    }

    /** IO is owned by the caller; cancelling its lifecycle disconnects the active request. */
    suspend fun fetch(lat: Double, lon: Double, city: String?, timeoutMs: Int = 8_000): WeatherInfo? {
        if (!validCoordinates(lat, lon) || timeoutMs <= 0) return null
        return withContext(Dispatchers.IO) {
            ensureActive()
            val connection = try {
                (URL(buildUrl(lat, lon)).openConnection() as HttpURLConnection).apply {
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    requestMethod = "GET"
                }
            } catch (_: Exception) {
                ensureActive()
                return@withContext null
            }
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { connection.disconnect() }
                try {
                    if (!continuation.isActive) return@suspendCancellableCoroutine
                    val info = if (connection.responseCode in 200..299) {
                        // Bound a malformed/server error response instead of growing without limit.
                        val body = connection.inputStream.bufferedReader().use { reader ->
                            val chars = CharArray(65_537)
                            var count = 0
                            while (count < chars.size) {
                                if (!continuation.isActive) return@suspendCancellableCoroutine
                                val n = reader.read(chars, count, chars.size - count)
                                if (n < 0) break
                                count += n
                            }
                            if (count > 65_536) null else String(chars, 0, count)
                        }
                        body?.let { parse(it, city, lat, lon) }
                    } else null
                    continuation.resume(info)
                } catch (_: Exception) {
                    // A cancelled continuation still throws CancellationException to its caller.
                    if (continuation.isActive) continuation.resume(null)
                } finally {
                    connection.disconnect()
                }
            }
        }
    }

    /** Pure parser: reject non-finite/out-of-range values rather than rounding them into a reading. */
    fun parse(
        body: String,
        city: String?,
        lat: Double,
        lon: Double,
        nowMillis: Long = System.currentTimeMillis(),
    ): WeatherInfo? = runCatching {
        if (!validCoordinates(lat, lon) || nowMillis <= 0) return@runCatching null
        val current = json.parseToJsonElement(body).jsonObject["current"]?.jsonObject ?: return@runCatching null
        val temperature = (current["temperature_2m"] as? JsonPrimitive)?.doubleOrNull ?: return@runCatching null
        if (!temperature.isFinite() || temperature !in -100.0..70.0) return@runCatching null
        val codeElement = current["weather_code"]
        val code = if (codeElement == null) null else {
            val value = (codeElement as? JsonPrimitive)?.doubleOrNull ?: return@runCatching null
            if (!value.isFinite() || value !in 0.0..99.0 || value % 1.0 != 0.0) return@runCatching null
            value.toInt()
        }
        WeatherInfo(
            temperatureC = Math.round(temperature).toInt(),
            condition = conditionFor(code),
            city = city?.trim()?.take(120)?.ifEmpty { null },
            updatedAtMillis = nowMillis,
            locationKey = locationKey(lat, lon),
        )
    }.getOrNull()

    internal fun validCoordinates(lat: Double, lon: Double): Boolean =
        lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0

    internal fun locationKey(lat: Double, lon: Double): String =
        String.format(Locale.US, "%.3f,%.3f", lat, lon)

    /** WMO codes, collapsed to the conditions a clock displays. Unknown valid codes stay unknown. */
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
}
