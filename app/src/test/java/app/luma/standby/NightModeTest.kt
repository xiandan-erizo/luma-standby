package app.luma.standby

import app.luma.standby.domain.NightMode
import app.luma.standby.domain.model.LumaSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NightModeTest {

    @Test
    fun windowThatCrossesMidnight() {
        assertFalse(NightMode.isNight(22 * 60, 7 * 60, 21 * 60))
        assertTrue(NightMode.isNight(22 * 60, 7 * 60, 22 * 60))
        assertTrue(NightMode.isNight(22 * 60, 7 * 60, 23 * 60 + 59))
        assertTrue(NightMode.isNight(22 * 60, 7 * 60, 0))
        assertTrue(NightMode.isNight(22 * 60, 7 * 60, 6 * 60 + 59))
        assertFalse(NightMode.isNight(22 * 60, 7 * 60, 7 * 60))
        assertFalse(NightMode.isNight(22 * 60, 7 * 60, 12 * 60))
    }

    @Test
    fun windowInsideOneDay() {
        assertTrue(NightMode.isNight(13 * 60, 14 * 60, 13 * 60 + 30))
        assertFalse(NightMode.isNight(13 * 60, 14 * 60, 14 * 60))
        assertFalse(NightMode.isNight(13 * 60, 14 * 60, 12 * 60 + 59))
    }

    @Test
    fun degenerateWindowIsNeverNight() {
        assertFalse(NightMode.isNight(22 * 60, 22 * 60, 22 * 60))
        assertEquals(0f, NightMode.nightFactor(22 * 60, 22 * 60, 45, 22 * 60), 0.0001f)
    }

    @Test
    fun factorRampsInAfterNightStart() {
        val start = 22 * 60
        val end = 7 * 60
        assertEquals(0f, NightMode.nightFactor(start, end, 45, 21 * 60), 0.0001f)
        assertEquals(0f, NightMode.nightFactor(start, end, 45, 22 * 60), 0.0001f)
        assertEquals(0.5f, NightMode.nightFactor(start, end, 45, 22 * 60 + 22), 0.02f)
        assertEquals(1f, NightMode.nightFactor(start, end, 45, 23 * 60), 0.0001f)
        assertEquals(1f, NightMode.nightFactor(start, end, 45, 3 * 60), 0.0001f)
    }

    @Test
    fun factorRampsOutAfterNightEnd() {
        val start = 22 * 60
        val end = 7 * 60
        assertEquals(1f, NightMode.nightFactor(start, end, 45, 7 * 60 - 1), 0.0001f)
        // At the end boundary the fade-out has just begun: still full night.
        assertEquals(1f, NightMode.nightFactor(start, end, 45, 7 * 60), 0.0001f)
        assertEquals(0.5f, NightMode.nightFactor(start, end, 45, 7 * 60 + 22), 0.02f)
        assertEquals(0f, NightMode.nightFactor(start, end, 45, 8 * 60), 0.0001f)
    }

    @Test
    fun zeroRampIsAStep() {
        assertEquals(1f, NightMode.nightFactor(22 * 60, 7 * 60, 0, 22 * 60), 0.0001f)
        assertEquals(1f, NightMode.nightFactor(22 * 60, 7 * 60, 0, 7 * 60 - 1), 0.0001f)
        assertEquals(0f, NightMode.nightFactor(22 * 60, 7 * 60, 0, 7 * 60), 0.0001f)
    }

    @Test
    fun manualBrightnessBeatsAutomaticNight() {
        val settings = LumaSettings(autoNight = true, brightness = 0.4f, nightBrightness = 0.06f)
        assertEquals(0.4f, NightMode.targetBrightness(settings, 23 * 60, 0.5f), 0.0001f)
    }

    @Test
    fun followingSystemMeansNoOverrideDuringDay() {
        val settings = LumaSettings(autoNight = true)
        assertEquals(-1f, NightMode.targetBrightness(settings, 12 * 60, 0.5f), 0.0001f)
    }

    @Test
    fun nightAppliesNightBrightnessAtFullFactor() {
        val settings = LumaSettings(autoNight = true, nightBrightness = 0.08f)
        assertEquals(0.08f, NightMode.targetBrightness(settings, 23 * 60, 0.6f), 0.0001f)
    }

    @Test
    fun rampInterpolatesFromSystemBrightness() {
        val settings = LumaSettings(autoNight = true, nightBrightness = 0.1f, nightRampMinutes = 40)
        // 20 minutes into the night with a 40 minute ramp => halfway between 0.6 and 0.1
        val value = NightMode.targetBrightness(settings, 22 * 60 + 20, 0.6f)
        assertEquals(0.35f, value, 0.02f)
    }

    @Test
    fun withoutSystemBrightnessTheRampDegradesToAStep() {
        val settings = LumaSettings(autoNight = true, nightBrightness = 0.1f, nightRampMinutes = 40)
        assertEquals(-1f, NightMode.targetBrightness(settings, 22 * 60 + 5, null), 0.0001f)
        assertEquals(0.1f, NightMode.targetBrightness(settings, 22 * 60 + 30, null), 0.0001f)
    }

    @Test
    fun timeParsingRoundTrips() {
        assertEquals(22 * 60, NightMode.parseHhMm("22:00"))
        assertEquals(7 * 60 + 5, NightMode.parseHhMm("7:05"))
        assertEquals(7 * 60 + 5, NightMode.parseHhMm("0705"))
        assertEquals("22:00", NightMode.formatHhMm(22 * 60))
        assertEquals("07:05", NightMode.formatHhMm(7 * 60 + 5))
        assertNull(NightMode.parseHhMm("24:00"))
        assertNull(NightMode.parseHhMm("aa:bb"))
        assertNull(NightMode.parseHhMm(""))
    }
}
