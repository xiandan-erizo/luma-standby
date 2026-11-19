package app.luma.standby

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import androidx.test.core.app.ApplicationProvider
import app.luma.standby.domain.model.FaceStyle
import app.luma.standby.domain.model.LumaSettings
import app.luma.standby.domain.model.LumaTheme
import app.luma.standby.domain.model.StoredPlacement
import app.luma.standby.domain.theme.ThemeEditing
import app.luma.standby.domain.theme.ThemePresets
import app.luma.standby.domain.widget.AlarmInfo
import app.luma.standby.domain.widget.BatteryInfo
import app.luma.standby.domain.widget.WeatherCondition
import app.luma.standby.domain.widget.WeatherInfo
import app.luma.standby.domain.widget.WidgetData
import app.luma.standby.platform.widgets.WidgetRenderer
import app.luma.standby.ui.clock.ClockView
import app.luma.standby.ui.clock.FaceLayout
import app.luma.standby.ui.clock.IosNumerals
import app.luma.standby.ui.clock.WidgetTile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class StandbyFaceRenderTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun floatAndSplitFacesRenderCleanlyAndExportSnapshots() {
        val snapshotDir = File(System.getProperty("user.home"), "snapshots").apply { mkdirs() }

        // 1. Float Ocean (no widgets)
        val floatBmp = renderClock(
            width = 1600,
            height = 720,
            theme = ThemePresets.iosFloatBlue,
            tiles = emptyList(),
        )
        assertTrue("Float face draws bright duo-tone pixels on pure black", countNonBlack(floatBmp) > 50_000)
        savePng(floatBmp, File(snapshotDir, "float_ocean.png"))

        // 2. Float Flamingo
        val flamingoBmp = renderClock(
            width = 1600,
            height = 720,
            theme = ThemePresets.iosPinkCyan,
            tiles = emptyList(),
        )
        savePng(flamingoBmp, File(snapshotDir, "float_flamingo.png"))

        // 3. Split Coral + Calendar Hero (no widgets)
        val splitCalendarBmp = renderClock(
            width = 1600,
            height = 720,
            theme = ThemePresets.iosCoral,
            tiles = emptyList(),
        )
        assertTrue("Split calendar face draws clock + calendar hero", countNonBlack(splitCalendarBmp) > 30_000)
        savePng(splitCalendarBmp, File(snapshotDir, "split_coral_calendar.png"))

        // 4. Split Mint + Graphical Widgets (Weather Hero + Circular Battery Ring + Alarm)
        val widgetSettings = LumaSettings(
            themeId = "ios-coral",
            widgetPlacements = listOf(
                StoredPlacement("weather", "TOP_RIGHT"),
                StoredPlacement("battery", "BOTTOM_RIGHT"),
                StoredPlacement("alarm", "BOTTOM_RIGHT"),
            ),
        )
        val tiles = WidgetRenderer.tiles(
            settings = widgetSettings,
            data = WidgetData(
                weather = WeatherInfo(
                    temperatureC = 18,
                    condition = WeatherCondition.CLEAR,
                    updatedAtMillis = System.currentTimeMillis(),
                    city = "北京",
                ),
                battery = BatteryInfo(percent = 82, charging = true),
                nextAlarm = AlarmInfo(hour = 7, minute = 30),
            ),
            context = context,
        )
        assertEquals(3, tiles.size)
        val splitWidgetsBmp = renderClock(
            width = 1600,
            height = 720,
            theme = ThemePresets.iosCoral,
            tiles = tiles,
        )
        assertTrue("Split widgets face draws clock and graphical tiles", countNonBlack(splitWidgetsBmp) > 40_000)
        savePng(splitWidgetsBmp, File(snapshotDir, "split_widgets_three.png"))

        // 5. Split with 2 hero widgets (Weather + Battery Ring — classic 2-card StandBy)
        val twoTiles = tiles.take(2)
        val splitTwoWidgetsBmp = renderClock(
            width = 1600,
            height = 720,
            theme = ThemePresets.iosCoral,
            tiles = twoTiles,
        )
        savePng(splitTwoWidgetsBmp, File(snapshotDir, "split_widgets_two.png"))

        // 6. Digit specimen sheet (0..9 in both Float heavy and Split rounded)
        val specimenBmp = renderDigitSpecimen(1600, 640)
        savePng(specimenBmp, File(snapshotDir, "digits_specimen.png"))
    }

    @Test
    fun faceStyleTogglesIndependentlyAndLayoutsNeverCollide() {
        val initial = LumaSettings(themeId = "ios-coral")
        val switched = ThemeEditing.withFaceStyle(initial, FaceStyle.FLOAT, 77L)
        assertEquals(FaceStyle.FLOAT, switched.faceStyle)
        assertFalse(switched.builtIn)

        val floatEmpty = FaceLayout.create(2400f, 1080f, withWidgets = false, style = FaceStyle.FLOAT)
        assertTrue(floatEmpty.clock.width > 2000f)
        assertTrue(floatEmpty.date.bottom < floatEmpty.clock.top)
    }

    private fun renderClock(
        width: Int,
        height: Int,
        theme: LumaTheme,
        tiles: List<WidgetTile>,
    ): Bitmap {
        val view = ClockView(context)
        view.applyState(
            theme = theme,
            use24HourOverride = true,
            showSecondsOverride = false,
            burnInShift = false,
            hint = null,
        )
        view.setWidgets(tiles)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        view.draw(canvas)
        return bitmap
    }

    private fun renderDigitSpecimen(width: Int, height: Int): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.BLACK)
        val path = Path()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val digits = "0123456789"
        val margin = 48f
        val cellW = (width - margin * 2f) / 10f

        // Row 1: Float bubbly stroke
        digits.forEachIndexed { idx, ch ->
            val left = margin + idx * cellW + cellW * 0.08f
            val right = margin + (idx + 1) * cellW - cellW * 0.08f
            val sw = (right - left) * 0.28f
            paint.strokeWidth = sw
            paint.color = if (idx % 2 == 0) Color.parseColor("#0A84FF") else Color.parseColor("#5AC8FA")
            IosNumerals.buildDigitPath(path, ch, left, 48f, right, 288f, sw)
            canvas.drawPath(path, paint)
        }

        // Row 2: Split crisp digital stroke
        digits.forEachIndexed { idx, ch ->
            val left = margin + idx * cellW + cellW * 0.12f
            val right = margin + (idx + 1) * cellW - cellW * 0.12f
            val sw = (right - left) * 0.22f
            paint.strokeWidth = sw
            paint.color = Color.WHITE
            IosNumerals.buildDigitPath(path, ch, left, 352f, right, 592f, sw)
            canvas.drawPath(path, paint)
        }
        return bmp
    }

    private fun countNonBlack(bitmap: Bitmap): Int {
        var count = 0
        for (y in 0 until bitmap.height step 2) {
            for (x in 0 until bitmap.width step 2) {
                val px = bitmap.getPixel(x, y)
                if (Color.red(px) > 8 || Color.green(px) > 8 || Color.blue(px) > 8) count++
            }
        }
        return count * 4
    }

    private fun savePng(bitmap: Bitmap, file: File) {
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }
}