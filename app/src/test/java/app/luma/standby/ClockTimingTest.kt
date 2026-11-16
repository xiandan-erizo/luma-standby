package app.luma.standby

import app.luma.standby.ui.clock.ClockTiming
import org.junit.Assert.assertEquals
import org.junit.Test

class ClockTimingTest {

    @Test
    fun minuteBoundaryDrawsJustAfterTheMinute() {
        val atMinuteStart = 1_700_000_000_000L - (1_700_000_000_000L % 60_000L)
        assertEquals(60_040L, ClockTiming.delayToNextBoundary(atMinuteStart, secondsEnabled = false))
    }

    @Test
    fun minuteBoundaryLateInTheMinuteWaitsLess() {
        val atMinuteStart = 1_700_000_000_000L - (1_700_000_000_000L % 60_000L)
        assertEquals(540L, ClockTiming.delayToNextBoundary(atMinuteStart + 59_500L, secondsEnabled = false))
    }

    @Test
    fun secondsModeAlignsToSecondBoundary() {
        val atSecondStart = 1_700_000_000_000L - (1_700_000_000_000L % 1_000L)
        assertEquals(1_020L, ClockTiming.delayToNextBoundary(atSecondStart, secondsEnabled = true))
        assertEquals(520L, ClockTiming.delayToNextBoundary(atSecondStart + 500L, secondsEnabled = true))
    }

    @Test
    fun delayIsAlwaysPositive() {
        val base = 1_700_000_000_000L
        for (offset in 0L until 60_000L step 250L) {
            val delay = ClockTiming.delayToNextBoundary(base + offset, secondsEnabled = false)
            assert(delay > 0L) { "delay must stay positive at offset " + offset }
        }
    }
}
