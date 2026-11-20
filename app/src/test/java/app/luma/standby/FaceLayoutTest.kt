package app.luma.standby

import app.luma.standby.domain.model.FaceStyle
import app.luma.standby.ui.clock.FaceBox
import app.luma.standby.ui.clock.FaceLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceLayoutTest {
    private fun overlap(a: FaceBox, b: FaceBox) =
        a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top

    @Test
    fun clockAndWidgetsNeverOverlap() {
        for (style in FaceStyle.entries) {
            for ((w, h) in listOf(2656f to 1220f, 1220f to 2656f, 800f to 400f, 320f to 240f)) {
                val layout = FaceLayout.create(w, h, withWidgets = true, style = style)
                for (tile in layout.rightTiles + listOfNotNull(layout.leftTile)) {
                    assertFalse("$style $w x $h clock overlaps tile", overlap(layout.clock, tile))
                    assertFalse("$style $w x $h date overlaps tile", overlap(layout.date, tile))
                    assertTrue(tile.left >= 0f && tile.top >= 0f && tile.right <= w && tile.bottom <= h)
                }
            }
        }
    }

    @Test
    fun emptyFaceHasNoReservedWidgetArea() {
        val layout = FaceLayout.create(2656f, 1220f, withWidgets = false, style = FaceStyle.SPLIT)
        assertTrue(layout.rightTiles.isEmpty())
        assertNull(layout.leftTile)
        assertTrue(layout.clock.width > 1000f)
    }

    @Test
    fun dualModesAreSymmetricSideBySideSquares() {
        for (style in listOf(FaceStyle.ANALOG, FaceStyle.SPLIT)) {
            val empty = FaceLayout.create(2656f, 1220f, withWidgets = false, style = style)
            val placed = FaceLayout.create(2656f, 1220f, withWidgets = true, style = style)

            assertEquals(empty.clock.width, empty.date.width, 0.5f)
            assertEquals(empty.clock.height, empty.date.height, 0.5f)
            assertEquals(empty.clock.top, empty.date.top, 0.5f)
            assertEquals(empty.clock.bottom, empty.date.bottom, 0.5f)

            assertEquals(empty.clock.right, placed.clock.right, 0.5f)
            assertEquals(placed.clock.top, placed.rightTiles.first().top, 0.5f)
            assertEquals(placed.clock.bottom, placed.rightTiles.last().bottom, 0.5f)
        }
    }

    @Test
    fun fullscreenModesStayFullWidthEvenWhenWidgetsExist() {
        for (style in listOf(FaceStyle.FLOAT, FaceStyle.SOLAR)) {
            val layout = FaceLayout.create(2656f, 1220f, withWidgets = true, style = style)
            assertTrue(layout.clock.width > 2200f)
            assertTrue(layout.date.bottom < layout.clock.top)
        }
    }

    @Test
    fun portraitFallsBackToDateUnderTheClock() {
        val portrait = FaceLayout.create(1220f, 2656f, withWidgets = true, style = FaceStyle.SPLIT)
        assertTrue(portrait.date.top > portrait.clock.bottom)
    }
}
