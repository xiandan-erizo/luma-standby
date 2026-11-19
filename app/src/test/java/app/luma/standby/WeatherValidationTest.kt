package app.luma.standby

import app.luma.standby.domain.widget.WeatherCondition
import app.luma.standby.platform.widgets.OpenMeteoClient
import org.junit.Assert.*
import org.junit.Test

class WeatherValidationTest {
    private fun body(temperature: String = "21.6", code: String = "3") =
        """{"current":{"temperature_2m":$temperature,"weather_code":$code}}"""

    @Test fun rejectsNonFiniteExtremeMissingAndStructuredTemperature() {
        for (value in listOf("\"NaN\"", "\"Infinity\"", "1e999", "-100.1", "70.1", "null", "{}", "[]")) {
            assertNull(value, OpenMeteoClient.parse(body(value), null, 39.9, 116.4, 1_000))
        }
        assertNull(OpenMeteoClient.parse("""{"current":{"weather_code":3}}""", null, 39.9, 116.4, 1_000))
        assertNotNull(OpenMeteoClient.parse(body("-100"), null, 39.9, 116.4, 1_000))
        assertNotNull(OpenMeteoClient.parse(body("70"), null, 39.9, 116.4, 1_000))
    }

    @Test fun rejectsNonFiniteFractionalOrOutOfRangeCode() {
        for (value in listOf("\"NaN\"", "1e999", "-1", "100", "3.5", "null", "{}")) {
            assertNull(value, OpenMeteoClient.parse(body(code = value), null, 39.9, 116.4, 1_000))
        }
        val unknown = OpenMeteoClient.parse(body(code = "4"), null, 39.9, 116.4, 1_000)!!
        assertEquals(WeatherCondition.UNKNOWN, unknown.condition)
    }

    @Test fun rejectsInvalidLocationAndTimestamp() {
        for ((lat, lon) in listOf(Double.NaN to 0.0, 0.0 to Double.POSITIVE_INFINITY,
                90.1 to 0.0, -90.1 to 0.0, 0.0 to 180.1, 0.0 to -180.1)) {
            assertNull(OpenMeteoClient.parse(body(), null, lat, lon, 1_000))
        }
        assertNotNull(OpenMeteoClient.parse(body(), null, -90.0, 180.0, 1_000))
        assertNull(OpenMeteoClient.parse(body(), null, 39.9, 116.4, 0))
    }

    @Test fun malformedRootAndCurrentNeverThrow() {
        for (body in listOf("garbage", "[]", "null", "{}", """{"current":[]} """)) {
            assertNull(OpenMeteoClient.parse(body, null, 39.9, 116.4, 1_000))
        }
    }
}
