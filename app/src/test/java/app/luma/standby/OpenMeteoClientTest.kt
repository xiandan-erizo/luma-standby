package app.luma.standby

import app.luma.standby.domain.widget.WeatherCondition
import app.luma.standby.platform.widgets.OpenMeteoClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenMeteoClientTest {

    private val sample = """
        {
          "latitude": 39.875,
          "longitude": 116.375,
          "timezone": "Asia/Shanghai",
          "current": {
            "time": "2026-11-19T15:00",
            "interval": 900,
            "temperature_2m": 7.4,
            "weather_code": 3
          }
        }
    """.trimIndent()

    @Test
    fun parsesCurrentTemperatureAndCondition() {
        val info = OpenMeteoClient.parse(sample, "Beijing", 39.9, 116.4, nowMillis = 1000L)
        assertEquals(7, info?.temperatureC)
        assertEquals(WeatherCondition.CLOUDY, info?.condition)
        assertEquals("Beijing", info?.city)
        assertEquals(1000L, info?.updatedAtMillis)
        assertEquals("39.900,116.400", info?.locationKey)
    }

    @Test
    fun missingCurrentBlockIsNotAnErrorButANull() {
        assertNull(OpenMeteoClient.parse("""{"latitude":1}""", null, 0.0, 0.0))
        assertNull(OpenMeteoClient.parse("not json", null, 0.0, 0.0))
        assertNull(OpenMeteoClient.parse("""{"current":{}}""", null, 0.0, 0.0))
    }

    @Test
    fun wmoCodesCollapseToVisibleConditions() {
        assertEquals(WeatherCondition.CLEAR, OpenMeteoClient.conditionFor(0))
        assertEquals(WeatherCondition.CLEAR, OpenMeteoClient.conditionFor(1))
        assertEquals(WeatherCondition.PARTLY_CLOUDY, OpenMeteoClient.conditionFor(2))
        assertEquals(WeatherCondition.CLOUDY, OpenMeteoClient.conditionFor(3))
        assertEquals(WeatherCondition.FOG, OpenMeteoClient.conditionFor(45))
        assertEquals(WeatherCondition.RAIN, OpenMeteoClient.conditionFor(61))
        assertEquals(WeatherCondition.RAIN, OpenMeteoClient.conditionFor(82))
        assertEquals(WeatherCondition.SNOW, OpenMeteoClient.conditionFor(75))
        assertEquals(WeatherCondition.THUNDER, OpenMeteoClient.conditionFor(95))
        assertEquals(WeatherCondition.UNKNOWN, OpenMeteoClient.conditionFor(1234))
        assertEquals(WeatherCondition.UNKNOWN, OpenMeteoClient.conditionFor(null))
    }

    @Test
    fun urlCarriesCoordinatesAndNoKey() {
        val url = OpenMeteoClient.buildUrl(39.9042, 116.4074)
        assertEquals(true, url.contains("latitude=39.9042"))
        assertEquals(true, url.contains("longitude=116.4074"))
        assertEquals(true, url.contains("current=temperature_2m,weather_code"))
        assertEquals(false, url.contains("api_key"))
    }
}
