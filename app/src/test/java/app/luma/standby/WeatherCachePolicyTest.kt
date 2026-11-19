package app.luma.standby

import app.luma.standby.domain.widget.WeatherCondition
import app.luma.standby.domain.widget.WeatherInfo
import app.luma.standby.platform.widgets.WeatherCacheCodec
import app.luma.standby.platform.widgets.WeatherCacheEntry
import app.luma.standby.platform.widgets.WeatherCachePolicy
import app.luma.standby.platform.widgets.WeatherCoordinates
import org.junit.Assert.*
import org.junit.Test

class WeatherCachePolicyTest {
    private val info = WeatherInfo(20, WeatherCondition.CLEAR, "Beijing", 1_000L, "39.900,116.400")

    @Test fun freshAndStaleBoundaries() {
        assertFalse(WeatherCachePolicy.display(info, 1_001L)!!.isStale)
        assertFalse(WeatherCachePolicy.display(info, 1_000L + WeatherCachePolicy.REFRESH_MILLIS - 1)!!.isStale)
        assertTrue(WeatherCachePolicy.display(info, 1_000L + WeatherCachePolicy.REFRESH_MILLIS)!!.isStale)
        assertTrue(WeatherCachePolicy.display(info, 1_000L + WeatherCachePolicy.MAX_STALE_MILLIS)!!.isStale)
        assertNull(WeatherCachePolicy.display(info, 1_001L + WeatherCachePolicy.MAX_STALE_MILLIS))
    }

    @Test fun rejectsMissingFutureAndInvalidTime() {
        assertNull(WeatherCachePolicy.display(null, 1_000))
        assertNull(WeatherCachePolicy.display(info, 999))
        assertNull(WeatherCachePolicy.display(info.copy(updatedAtMillis = 0), 1_000))
        assertNull(WeatherCachePolicy.display(info.copy(updatedAtMillis = -1), 1_000))
    }

    @Test fun throttlesFailuresEvenWithoutCacheAndDespiteWallClockJump() {
        assertTrue(WeatherCachePolicy.shouldRefresh(null, 1_000, null, 100))
        assertFalse(WeatherCachePolicy.shouldRefresh(null, Long.MAX_VALUE, 100, 101))
        assertFalse(WeatherCachePolicy.shouldRefresh(null, 1_000, 100,
            100 + WeatherCachePolicy.REFRESH_MILLIS - 1))
        assertTrue(WeatherCachePolicy.shouldRefresh(null, 1_000, 100,
            100 + WeatherCachePolicy.REFRESH_MILLIS))
    }

    @Test fun refreshesStaleButNotFreshReading() {
        assertFalse(WeatherCachePolicy.shouldRefresh(info, 1_001, null, 100))
        assertTrue(WeatherCachePolicy.shouldRefresh(info, 1_000 + WeatherCachePolicy.REFRESH_MILLIS, null, 100))
        assertTrue(WeatherCachePolicy.shouldRefresh(info, 999, null, 100))
        assertTrue(WeatherCachePolicy.shouldRefresh(null, 1_000, 100, 99)) // monotonic clock reset
    }

    @Test fun versionedCacheRoundTripEscapesCityAndPreservesExactCoordinates() {
        val coordinates = WeatherCoordinates(39.9000123, 116.4000123)
        val entry = WeatherCacheEntry(coordinates, info.copy(city = "A\tB\n\"C"))
        val decoded = WeatherCacheCodec.decode(WeatherCacheCodec.encode(entry))!!
        assertEquals(coordinates, decoded.coordinates)
        assertNotEquals(WeatherCoordinates(39.9000124, 116.4000123), decoded.coordinates)
        assertEquals(entry.info.city, decoded.info.city)
        assertEquals(info.updatedAtMillis, decoded.info.updatedAtMillis)
        assertFalse(decoded.info.isStale)
    }

    @Test fun rejectsLegacyCorruptUnkeyedAndUnsupportedCacheVersions() {
        val body = WeatherCacheCodec.encode(WeatherCacheEntry(WeatherCoordinates(39.9, 116.4), info))
        assertNull(WeatherCacheCodec.decode("20\tCLEAR\tBeijing\t1000"))
        assertNull(WeatherCacheCodec.decode(body.take(body.length - 2)))
        assertNull(WeatherCacheCodec.decode(body.replace("\"version\":1", "\"version\":2")))
        assertNull(WeatherCacheCodec.decode(body.replace("\"lat\":39.9,", "")))
        assertNull(WeatherCacheCodec.decode(body.replace("\"lat\":39.9", "\"lat\":91")))
        assertNull(WeatherCacheCodec.decode(body.replace("\"temperatureC\":20", "\"temperatureC\":999")))
        assertNull(WeatherCacheCodec.decode(body.replace("CLEAR", "NOT_A_CONDITION")))
        assertNull(WeatherCacheCodec.decode(body.replace("\"updatedAtMillis\":1000", "\"updatedAtMillis\":0")))
    }
}
