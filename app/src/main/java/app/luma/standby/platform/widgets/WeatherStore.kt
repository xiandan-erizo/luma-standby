package app.luma.standby.platform.widgets

import android.content.Context
import android.os.SystemClock
import android.util.AtomicFile
import app.luma.standby.domain.widget.WeatherCondition
import app.luma.standby.domain.widget.WeatherInfo
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File

/**
 * No scope, timer, or work is started by construction/getters. Call [update] only from the
 * visible lifecycle. Its IO/request is a child of that call; concurrent same-location calls
 * share it. The UI collects [snapshot] instead of reading disk on every clock tick.
 */
class WeatherStore(context: Context) {
    private val appContext = context.applicationContext
    private val lock = Any()
    private val diskLock = Mutex()
    private val mutableSnapshot = MutableStateFlow<WeatherInfo?>(null)
    val snapshot: StateFlow<WeatherInfo?> = mutableSnapshot.asStateFlow()
    private var active: WeatherCoordinates? = null
    private var city: String? = null
    private var generation = 0L
    private var closed = false
    private var clearing = false
    private var loaded = false
    private var entry: WeatherCacheEntry? = null
    private var inFlight: Deferred<WeatherInfo?>? = null
    private val attempts = mutableMapOf<WeatherCoordinates, Long>()

    /** Also call for disabled/missing locations to cancel old work and hide its reading. */
    suspend fun update(enabled: Boolean, lat: Double?, lon: Double?, city: String?): WeatherInfo? = coroutineScope {
        val coordinates = if (enabled && lat != null && lon != null &&
            OpenMeteoClient.validCoordinates(lat, lon)) WeatherCoordinates(lat, lon) else null
        val pending = synchronized(lock) {
            if (closed || clearing) return@coroutineScope null
            if (active != coordinates) {
                generation++
                inFlight?.cancel()
                inFlight = null
                active = coordinates
            }
            this@WeatherStore.city = city?.trim()?.take(120)?.ifEmpty { null }
            publish(System.currentTimeMillis())
            if (coordinates == null) return@coroutineScope null
            inFlight?.takeIf { !it.isCompleted && !it.isCancelled } ?: run {
                val matching = entry?.takeIf { it.coordinates == coordinates }?.info
                if (loaded && !WeatherCachePolicy.shouldRefresh(matching, System.currentTimeMillis(),
                        attempts[coordinates], SystemClock.elapsedRealtime())) return@coroutineScope mutableSnapshot.value
                val token = generation
                async(start = CoroutineStart.LAZY) { loadAndRefresh(coordinates, token) }
                    .also { inFlight = it }
            }
        }
        try {
            pending.await()
        } finally {
            synchronized(lock) { if (inFlight === pending && pending.isCompleted) inFlight = null }
        }
    }

    private suspend fun loadAndRefresh(coordinates: WeatherCoordinates, token: Long): WeatherInfo? {
        withContext(Dispatchers.IO) {
            diskLock.withLock {
                val needsLoad = synchronized(lock) { !loaded && current(coordinates, token) }
                if (needsLoad) {
                    val cached = try {
                        val file = cacheFile()
                        if (file.baseFile.length() > 16_384L) null else
                            WeatherCacheCodec.decode(file.openRead().bufferedReader().use { it.readText() })
                    } catch (_: Exception) { null }
                    ensureActive()
                    synchronized(lock) {
                        if (current(coordinates, token)) { entry = cached; loaded = true }
                    }
                }
            }
        }
        val shouldFetch = synchronized(lock) {
            if (!current(coordinates, token)) return null
            val now = System.currentTimeMillis()
            publish(now)
            val matching = entry?.takeIf { it.coordinates == coordinates }?.info
            val elapsed = SystemClock.elapsedRealtime()
            val due = WeatherCachePolicy.shouldRefresh(matching, now, attempts[coordinates], elapsed)
            if (due) {
                if (attempts.size >= 32) attempts.clear()
                attempts[coordinates] = elapsed // failures/cancellation must not retry every tick
            }
            due
        }
        if (!shouldFetch) return synchronized(lock) { mutableSnapshot.value }
        val info = OpenMeteoClient.fetch(coordinates.lat, coordinates.lon, synchronized(lock) { city })
        if (info != null) {
            withContext(Dispatchers.IO) {
                diskLock.withLock {
                    ensureActive()
                    val valid = synchronized(lock) { current(coordinates, token) }
                    if (valid) {
                        val fresh = WeatherCacheEntry(coordinates, info)
                        val file = cacheFile()
                        var stream: java.io.FileOutputStream? = null
                        try {
                            stream = file.startWrite()
                            stream.write(WeatherCacheCodec.encode(fresh).toByteArray(Charsets.UTF_8))
                            ensureActive()
                            if (synchronized(lock) { current(coordinates, token) }) file.finishWrite(stream)
                            else file.failWrite(stream)
                        } catch (failure: Exception) {
                            stream?.let { file.failWrite(it) }
                            ensureActive() // never turn cancellation into a successful refresh
                            // A disk failure must not discard an otherwise valid in-memory reading.
                        }
                        synchronized(lock) { if (current(coordinates, token)) entry = fresh }
                    }
                }
            }
        }
        return synchronized(lock) {
            if (current(coordinates, token)) publish(System.currentTimeMillis())
            mutableSnapshot.value
        }
    }

    private fun cacheFile() = AtomicFile(File(appContext.filesDir, "weather-v1.json"))
    private fun current(coordinates: WeatherCoordinates, token: Long) =
        !closed && generation == token && active == coordinates

    /** Must hold lock. */
    private fun publish(now: Long) {
        val reading = entry?.takeIf { it.coordinates == active }?.info
        mutableSnapshot.value = WeatherCachePolicy.display(reading, now)?.copy(city = city)
    }

    /** Compatibility getter: memory only; never starts IO. */
    fun cached(lat: Double? = null, lon: Double? = null): WeatherInfo? = synchronized(lock) {
        if ((lat == null) != (lon == null)) return null
        if (lat != null && lon != null && active != WeatherCoordinates(lat, lon)) return null
        publish(System.currentTimeMillis())
        mutableSnapshot.value
    }

    fun isStale(now: Long = System.currentTimeMillis()): Boolean = synchronized(lock) {
        val reading = entry?.takeIf { it.coordinates == active }?.info
        WeatherCachePolicy.display(reading, now)?.isStale ?: true
    }

    /** Compatibility name; unlike the old implementation it is lifecycle-owned/suspending. */
    suspend fun refresh(lat: Double, lon: Double, city: String?) = update(true, lat, lon, city)

    /** Immediate cancellation, no disk IO. A shut down store cannot be reused. */
    fun shutdown() = synchronized(lock) {
        closed = true
        generation++
        inFlight?.cancel()
        inFlight = null
        active = null
        mutableSnapshot.value = null
    }

    /** Explicit privacy reset; call from a lifecycle scope and await completion. */
    suspend fun clear() {
        synchronized(lock) {
            clearing = true
            generation++
            inFlight?.cancel()
            inFlight = null
            active = null
            entry = null
            loaded = true
            attempts.clear()
            mutableSnapshot.value = null
        }
        try {
            withContext(Dispatchers.IO) {
                diskLock.withLock {
                    cacheFile().delete()
                    File(appContext.filesDir, "weather.tsv").delete() // discard unversioned, potentially unkeyed cache
                }
            }
        } finally {
            synchronized(lock) { clearing = false }
        }
    }

    companion object { const val REFRESH_MINUTES = 30L }
}

internal data class WeatherCoordinates(val lat: Double, val lon: Double)
internal data class WeatherCacheEntry(val coordinates: WeatherCoordinates, val info: WeatherInfo)

/** Pure policy: refresh and failure backoff are 30min; never display future or >24h-old data. */
internal object WeatherCachePolicy {
    const val REFRESH_MILLIS = 30 * 60 * 1_000L
    const val MAX_STALE_MILLIS = 24 * 60 * 60 * 1_000L

    fun display(info: WeatherInfo?, now: Long): WeatherInfo? {
        if (info == null || info.updatedAtMillis <= 0 || now < info.updatedAtMillis) return null
        val age = now - info.updatedAtMillis
        if (age > MAX_STALE_MILLIS) return null
        return info.copy(isStale = age >= REFRESH_MILLIS)
    }

    fun shouldRefresh(info: WeatherInfo?, now: Long, lastAttemptElapsed: Long?, elapsed: Long): Boolean {
        if (lastAttemptElapsed != null && elapsed >= lastAttemptElapsed &&
            elapsed - lastAttemptElapsed < REFRESH_MILLIS) return false
        return display(info, now)?.isStale != false
    }
}

/** Pure versioned codec. Legacy/unkeyed/partial/corrupt records cannot leak across locations. */
internal object WeatherCacheCodec {
    fun encode(entry: WeatherCacheEntry): String = buildJsonObject {
        put("version", 1)
        put("lat", entry.coordinates.lat)
        put("lon", entry.coordinates.lon)
        put("temperatureC", entry.info.temperatureC)
        put("condition", entry.info.condition.name)
        put("city", entry.info.city?.let(::JsonPrimitive) ?: JsonNull)
        put("updatedAtMillis", entry.info.updatedAtMillis)
    }.toString()

    fun decode(body: String): WeatherCacheEntry? = runCatching {
        val root = Json.parseToJsonElement(body).jsonObject
        fun primitive(key: String) = root[key] as? JsonPrimitive
        if (primitive("version")?.intOrNull != 1) return@runCatching null
        val lat = primitive("lat")?.doubleOrNull ?: return@runCatching null
        val lon = primitive("lon")?.doubleOrNull ?: return@runCatching null
        if (!OpenMeteoClient.validCoordinates(lat, lon)) return@runCatching null
        val temperature = primitive("temperatureC")?.intOrNull ?: return@runCatching null
        if (temperature !in -100..70) return@runCatching null
        val condition = WeatherCondition.valueOf(primitive("condition")?.contentOrNull ?: return@runCatching null)
        val updated = primitive("updatedAtMillis")?.longOrNull ?: return@runCatching null
        if (updated <= 0) return@runCatching null
        val city = primitive("city")?.contentOrNull?.take(120)
        WeatherCacheEntry(WeatherCoordinates(lat, lon), WeatherInfo(
            temperature, condition, city, updated, OpenMeteoClient.locationKey(lat, lon),
        ))
    }.getOrNull()
}
