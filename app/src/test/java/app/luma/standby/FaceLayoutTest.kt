package app.luma.standby
import app.luma.standby.ui.clock.FaceLayout
import app.luma.standby.ui.clock.FaceBox
import org.junit.Assert.*
import org.junit.Test
class FaceLayoutTest {
    private fun overlap(a:FaceBox,b:FaceBox)=a.left<b.right&&a.right>b.left&&a.top<b.bottom&&a.bottom>b.top
    @Test fun clockAndWidgetsNeverOverlap() {
        for ((w,h) in listOf(2656f to 1220f,1220f to 2656f,800f to 400f,320f to 240f)) {
            val layout=FaceLayout.create(w,h,true)
            for (tile in layout.rightTiles+listOfNotNull(layout.leftTile)) {
                assertFalse(overlap(layout.clock,tile)); assertFalse(overlap(layout.date,tile))
                assertTrue(tile.left>=0&&tile.top>=0&&tile.right<=w&&tile.bottom<=h)
            }
        }
    }
    @Test fun emptyFaceHasNoReservedWidgetArea() {
        val layout=FaceLayout.create(2656f,1220f,false)
        assertTrue(layout.rightTiles.isEmpty()); assertNull(layout.leftTile)
        assertTrue(layout.clock.width>1000f)
    }

    @Test fun dateStaysInTheRailWhenWidgetsAppear() {
        val empty=FaceLayout.create(2656f,1220f,false)
        val placed=FaceLayout.create(2656f,1220f,true)
        assertEquals(empty.date.left, placed.date.left, 0.5f)
        assertEquals(empty.date.top, placed.date.top, 0.5f)
        assertEquals(empty.clock.right, placed.clock.right, 0.5f)
    }

    @Test fun portraitFallsBackToDateUnderTheClock() {
        val portrait=FaceLayout.create(1220f,2656f,true)
        assertTrue(portrait.date.top > portrait.clock.bottom)
    }
}