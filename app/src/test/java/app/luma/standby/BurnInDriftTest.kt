package app.luma.standby

import app.luma.standby.domain.BurnInDrift
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BurnInDriftTest {

    @Test
    fun firstIntervalSitsAtTheOrigin() {
        assertEquals(0f, BurnInDrift.offsetFor(0, 2f, 20).first, 0.0001f)
        assertEquals(0f, BurnInDrift.offsetFor(19, 2f, 20).second, 0.0001f)
    }

    @Test
    fun positionChangesOnlyOnIntervalBoundaries() {
        val before = BurnInDrift.offsetFor(39, 2f, 20)
        val after = BurnInDrift.offsetFor(40, 2f, 20)
        assertNotEquals(before, after)
    }

    @Test
    fun offsetsStayInsideTheAmplitude() {
        for (minute in 0L until 24L * 60L step 7L) {
            val (dx, dy) = BurnInDrift.offsetFor(minute, 2f, 20)
            assertTrue("dx=" + dx, abs(dx) <= 2f + 0.0001f)
            assertTrue("dy=" + dy, abs(dy) <= 2f + 0.0001f)
        }
    }

    @Test
    fun consecutivePositionsDiffer() {
        val steps = BurnInDrift.stepCount()
        var previous = BurnInDrift.offsetFor(0, 2f, 20)
        for (index in 1..steps * 2) {
            val current = BurnInDrift.offsetFor(index.toLong() * 20, 2f, 20)
            assertNotEquals("step " + index, previous, current)
            previous = current
        }
    }

    @Test
    fun amplitudeScalesTheSequence() {
        val (dx, dy) = BurnInDrift.offsetFor(20, 1f, 20)
        val (fullX, fullY) = BurnInDrift.offsetFor(20, 2f, 20)
        assertEquals(fullX / 2f, dx, 0.0001f)
        assertEquals(fullY / 2f, dy, 0.0001f)
    }

    @Test
    fun deterministicAcrossCalls() {
        assertEquals(BurnInDrift.offsetFor(1234, 2f, 20), BurnInDrift.offsetFor(1234, 2f, 20))
    }
}
