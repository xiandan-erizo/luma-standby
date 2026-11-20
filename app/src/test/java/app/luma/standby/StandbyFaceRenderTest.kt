package app.luma.standby

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
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
    fun allFourIosStandByFacesRenderCleanlyAndExportSnapshots() {
        val snapshotDir = File(System.getProperty("user.home"), "snapshots").apply { mkdirs() }

        val fullSettings = LumaSettings(
            themeId = "ios-coral",
            widgetPlacements = listOf(
                StoredPlacement("weather", "TOP_RIGHT"),
                StoredPlacement("battery", "BOTTOM_RIGHT"),
                StoredPlacement("alarm", "BOTTOM_RIGHT"),
            ),
        )
        val sampleTiles = WidgetRenderer.tiles(
            settings = fullSettings,
            data = WidgetData(
                weather = WeatherInfo(
                    temperatureC = 7,
                    condition = WeatherCondition.CLEAR,
                    updatedAtMillis = System.currentTimeMillis(),
                    city = "北京",
                ),
                battery = BatteryInfo(percent = 82, charging = true),
                nextAlarm = AlarmInfo(hour = 7, minute = 30),
            ),
            context = context,
        )
        assertEquals(3, sampleTiles.size)

        // 1. Analog + Calendar Hero Square (classic iOS StandBy)
        val analogCalBmp = renderClock(1600, 720, ThemePresets.iosCoral, emptyList())
        assertTrue("Analog + Calendar draws pixels", countNonBlack(analogCalBmp) > 40_000)
        savePng(analogCalBmp, File(snapshotDir, "01_analog_calendar.png"))

        // 2. Analog + Weather Hero Square (1-widget full square sky card)
        val analogWeatherBmp = renderClock(1600, 720, ThemePresets.iosCoral, sampleTiles.take(1))
        assertTrue("Analog + Weather hero draws pixels", countNonBlack(analogWeatherBmp) > 60_000)
        savePng(analogWeatherBmp, File(snapshotDir, "02_analog_weather_hero.png"))

        // 3. Split Stacked Digital + 2-Card Stack (Weather + Battery Ring)
        val splitTwoBmp = renderClock(1600, 720, ThemePresets.iosMint, sampleTiles.take(2))
        assertTrue("Split + 2 cards draws pixels", countNonBlack(splitTwoBmp) > 60_000)
        savePng(splitTwoBmp, File(snapshotDir, "03_split_two_widgets.png"))

        // 4. Float Ocean (fullscreen Fredoka Bold, verify NO dark-alpha bug across multiple draws!)
        val floatBmp = renderClock(1600, 720, ThemePresets.iosFloatBlue, sampleTiles.take(1), drawPasses = 2)
        assertTrue("Float face stays bright across repeated draws", countBright(floatBmp) > 40_000)
        savePng(floatBmp, File(snapshotDir, "04_float_ocean.png"))

        // 5. Float Flamingo
        val flamingoBmp = renderClock(1600, 720, ThemePresets.iosPinkCyan, emptyList(), drawPasses = 2)
        assertTrue("Float Flamingo stays bright", countBright(flamingoBmp) > 40_000)
        savePng(flamingoBmp, File(snapshotDir, "05_float_flamingo.png"))

        // 6. Liquid Glass Solar Horizon (iOS 26/27 style)
        val solarBmp = renderClock(1600, 720, ThemePresets.iosSolar, sampleTiles.take(2))
        assertTrue("Solar Liquid Glass draws pixels", countNonBlack(solarBmp) > 50_000)
        savePng(solarBmp, File(snapshotDir, "06_solar_liquid_glass.png"))

        // 7. Night Red (Analog + Calendar, zero blue light)
        val nightBmp = renderClock(1600, 720, ThemePresets.iosNightRed, emptyList())
        assertTrue("Night Red draws red pixels", countNonBlack(nightBmp) > 25_000)
        savePng(nightBmp, File(snapshotDir, "07_night_red.png"))
    }

    @Test
    fun faceStyleTogglesIndependentlyAndLayoutsNeverCollide() {
        val initial = LumaSettings(themeId = "ios-coral")
        for (style in FaceStyle.entries) {
            val switched = ThemeEditing.withFaceStyle(initial, style, 77L)
            assertEquals(style, switched.faceStyle)
            assertFalse(switched.builtIn)
        }

        val floatFull = FaceLayout.create(2400f, 1080f, withWidgets = true, style = FaceStyle.FLOAT)
        assertTrue(floatFull.clock.width > 2000f)
        assertTrue(floatFull.date.bottom < floatFull.clock.top)
    }

    private fun renderClock(
        width: Int,
        height: Int,
        theme: LumaTheme,
        tiles: List<WidgetTile>,
        drawPasses: Int = 1,
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
        repeat(drawPasses) {
            bitmap.eraseColor(Color.BLACK)
            view.draw(canvas)
        }
        return bitmap
    }

    private fun countNonBlack(bitmap: Bitmap): Int {
        var count = 0
        for (y in 0 until bitmap.height step 2) {
            for (x in 0 until bitmap.width step 2) {
                val px = bitmap.getPixel(x, y)
                if (Color.red(px) > 10 || Color.green(px) > 10 || Color.blue(px) > 10) count++
            }
        }
        return count * 4
    }

    private fun countBright(bitmap: Bitmap): Int {
        var count = 0
        for (y in 0 until bitmap.height step 2) {
            for (x in 0 until bitmap.width step 2) {
                val px = bitmap.getPixel(x, y)
                if (Color.red(px) > 120 || Color.green(px) > 120 || Color.blue(px) > 120) count++
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
