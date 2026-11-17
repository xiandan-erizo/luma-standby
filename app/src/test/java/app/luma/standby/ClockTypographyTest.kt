package app.luma.standby

import app.luma.standby.ui.clock.ClockTypography
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockTypographyTest {

    private val portrait = 1220f to 2656f
    private val landscape = 2656f to 1220f

    @Test
    fun orientationDetection() {
        assertTrue(ClockTypography.isPortrait(portrait.first, portrait.second))
        assertTrue(!ClockTypography.isPortrait(landscape.first, landscape.second))
    }

    @Test
    fun portraitGetsMoreWidthThanLandscape() {
        assertTrue(
            ClockTypography.maxWidthFraction(true) > ClockTypography.maxWidthFraction(false),
        )
        assertEquals(0.86f, ClockTypography.maxWidthFraction(true), 0.0001f)
        assertEquals(0.78f, ClockTypography.maxWidthFraction(false), 0.0001f)
    }

    @Test
    fun verticalOrderHoldsInBothOrientations() {
        for ((w, h) in listOf(portrait, landscape)) {
            val isPortrait = ClockTypography.isPortrait(w, h)
            val time = ClockTypography.timeBaseline(h)
            val date = ClockTypography.dateBaseline(h, isPortrait)
            val hint = ClockTypography.hintBaseline(h)
            assertTrue("date below time", date > time)
            assertTrue("hint below date", hint > date)
            assertTrue("hint inside view", hint < h)
        }
    }

    @Test
    fun typeSizesAreOrderedAndPositive() {
        val h = 2656f
        val time = ClockTypography.timeTextSize(h)
        val date = ClockTypography.dateTextSize(h)
        val hint = ClockTypography.hintTextSize(h)
        assertTrue(time > date)
        assertTrue(date > hint)
        assertTrue(hint > 0f)
        assertTrue("time must fit inside the view height", time < h)
    }

    @Test
    fun sizesScaleLinearlyWithHeight() {
        assertEquals(
            ClockTypography.timeTextSize(1000f),
            ClockTypography.timeTextSize(2000f) / 2f,
            0.01f,
        )
    }

    @Test
    fun shrinkFactorIsIdentityWhenItAlreadyFits() {
        assertEquals(1f, ClockTypography.shrinkFactor(500f, 800f), 0.0001f)
        assertEquals(0.5f, ClockTypography.shrinkFactor(1600f, 800f), 0.0001f)
    }

    @Test
    fun shrinkFactorHandlesDegenerateInput() {
        assertEquals(1f, ClockTypography.shrinkFactor(0f, 800f), 0.0001f)
        assertEquals(1f, ClockTypography.shrinkFactor(500f, 0f), 0.0001f)
    }
}
