package app.luma.standby.platform.widgets

import android.content.Context
import app.luma.standby.domain.widget.WeatherCondition
import app.luma.standby.domain.widget.WeatherInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/**
 * Weather is the only thing in the app that talks to the network, so it gets an
 * explicit cache with a hard staleness cap. The clock never blocks on a fetch: it
 * reads whatever is cached and a refresh lands for a later redraw.
 */
class WeatherStore(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val file: File get() = File(context.filesDir, "weather.tsv")

    private fun locationKey(lat: Double, lon: Double): String =
        String.format(Locale.US, "%.3f,%.3f", lat, lon)

    /** Returns the cached reading, or null when it was fetched for another location. */
    fun cached(lat: Double? = null, lon: Double? = null): WeatherInfo? {
        val f = file
        if (!f.exists()) return null
        val parts = runCatching { f.readText().trim().split('\t') }.getOrNull() ?: return null
        if (parts.size < 4) return null
        val temperature = parts[0].toIntOrNull() ?: return null
        val condition = runCatching { WeatherCondition.valueOf(parts[1]) }.getOrNull()
            ?: WeatherCondition.UNKNOWN
        val city = parts[2].ifEmpty { null }
        val updated = parts[3].toLongOrNull() ?: return null
        val key = parts.getOrNull(4)

        if (lat != null && lon != null && key != null && key != locationKey(lat, lon)) return null

        return WeatherInfo(
            temperatureC = temperature,
            condition = condition,
            city = city,
            updatedAtMillis = updated,
            locationKey = key,
        )
    }

    fun isStale(now: Long = System.currentTimeMillis()): Boolean {
        val current = cached() ?: return true
        return now - current.updatedAtMillis > REFRESH_MINUTES * 60_000L
    }

    fun refresh(lat: Double, lon: Double, city: String?) {
        scope.launch {
            val info = OpenMeteoClient.fetch(lat, lon, city) ?: return@launch
            runCatching {
                file.writeText(
                    listOf(
                        info.temperatureC.toString(),
                        info.condition.name,
                        info.city.orEmpty(),
                        info.updatedAtMillis.toString(),
                        locationKey(lat, lon),
                    ).joinToString("\t"),
                )
            }
        }
    }

    fun clear() {
        runCatching { file.delete() }
    }

    companion object {
        const val REFRESH_MINUTES = 30L
    }
}
