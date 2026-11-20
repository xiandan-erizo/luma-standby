package app.luma.standby

import android.app.NotificationManager
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
import app.luma.standby.domain.widget.NotificationInfo
import app.luma.standby.domain.widget.WeatherCondition
import app.luma.standby.domain.widget.WeatherInfo
import app.luma.standby.domain.widget.WidgetData
import app.luma.standby.platform.widgets.HeadsUpPolicyMath
import app.luma.standby.platform.widgets.WidgetRenderer
import app.luma.standby.ui.clock.ClockView
import app.luma.standby.ui.clock.FaceLayout
import app.luma.standby.ui.clock.StandbyAlertTracker
import app.luma.standby.ui.clock.StandbyNotificationAlert
import app.luma.standby.ui.clock.UnreadNotificationSummary
import app.luma.standby.ui.clock.WidgetTile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun allFourIosStandByFacesAndNotificationsRenderCleanly() {
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

        // 1. Analog + Calendar Hero Square
        val analogCalBmp = renderClock(1600, 720, ThemePresets.iosCoral, emptyList())
        assertTrue("Analog + Calendar draws pixels", countNonBlack(analogCalBmp) > 40_000)
        savePng(analogCalBmp, File(snapshotDir, "01_analog_calendar.png"))

        // 2. Analog + Weather Hero Square
        val analogWeatherBmp = renderClock(1600, 720, ThemePresets.iosCoral, sampleTiles.take(1))
        assertTrue("Analog + Weather hero draws pixels", countNonBlack(analogWeatherBmp) > 60_000)
        savePng(analogWeatherBmp, File(snapshotDir, "02_analog_weather_hero.png"))

        // 3. Split Stacked Digital + 2-Card Stack
        val splitTwoBmp = renderClock(1600, 720, ThemePresets.iosMint, sampleTiles.take(2))
        assertTrue("Split + 2 cards draws pixels", countNonBlack(splitTwoBmp) > 60_000)
        savePng(splitTwoBmp, File(snapshotDir, "03_split_two_widgets.png"))

        // 4. Polished Float on Coral (the exact page the user was viewing!) + unread top pill
        val floatCoralTheme = ThemePresets.iosCoral.copy(faceStyle = FaceStyle.FLOAT)
        val floatCoralBmp = renderClock(
            width = 1600,
            height = 720,
            theme = floatCoralTheme,
            tiles = emptyList(),
            unread = UnreadNotificationSummary(appLabel = "微信", count = 2),
            drawPasses = 2,
        )
        assertTrue("Float Coral stays bright and harmonized", countBright(floatCoralBmp) > 40_000)
        savePng(floatCoralBmp, File(snapshotDir, "04_float_coral_polished.png"))

        // 5. Float Ocean
        val floatOceanBmp = renderClock(1600, 720, ThemePresets.iosFloatBlue, sampleTiles.take(1), drawPasses = 2)
        assertTrue("Float Ocean stays bright", countBright(floatOceanBmp) > 40_000)
        savePng(floatOceanBmp, File(snapshotDir, "05_float_ocean.png"))

        // 6. Liquid Glass Solar Horizon
        val solarBmp = renderClock(1600, 720, ThemePresets.iosSolar, sampleTiles.take(2))
        assertTrue("Solar Liquid Glass draws pixels", countNonBlack(solarBmp) > 50_000)
        savePng(solarBmp, File(snapshotDir, "06_solar_liquid_glass.png"))

        // 7. Full-Screen iOS StandBy Notification Alert (2-level Sender + Preview Body)
        val alertBmp = renderClock(
            width = 1600,
            height = 720,
            theme = floatCoralTheme,
            tiles = emptyList(),
            alert = StandbyNotificationAlert(
                appLabel = "微信",
                title = "产品设计群",
                body = "张三：10 分钟后评审新版待机表盘，记得带上小米 17",
                unreadCount = 3,
                tapToReveal = false,
            ),
        )
        assertTrue("Notification takeover renders clearly", countNonBlack(alertBmp) > 40_000)
        savePng(alertBmp, File(snapshotDir, "08_notif_alert_public.png"))

        // 8. Full-Screen iOS StandBy Notification Alert (Tap-to-View privacy shield)
        val privacyAlertBmp = renderClock(
            width = 1600,
            height = 720,
            theme = ThemePresets.iosFloatBlue,
            tiles = emptyList(),
            alert = StandbyNotificationAlert(
                appLabel = "信息",
                title = "验证码 482910",
                unreadCount = 1,
                tapToReveal = true,
                revealed = false,
            ),
        )
        savePng(privacyAlertBmp, File(snapshotDir, "09_notif_alert_privacy.png"))

        // 9. Night Red Notification Alert (zero blue light)
        val nightAlertBmp = renderClock(
            width = 1600,
            height = 720,
            theme = ThemePresets.iosNightRed,
            tiles = emptyList(),
            alert = StandbyNotificationAlert(
                appLabel = "Calendar",
                title = "Morning Standup at 09:30",
                unreadCount = 1,
            ),
        )
        savePng(nightAlertBmp, File(snapshotDir, "10_notif_alert_night_red.png"))
    }

    @Test
    fun alertTrackerSeedsWithoutBurstAndFiresOnlyOnNewArrivals() {
        val tracker = StandbyAlertTracker()
        val initial = listOf(
            NotificationInfo("WeChat", "Old message", postTimeMillis = 100L, key = "k1"),
        )
        // First snapshot seeds without popping up stale messages.
        assertNull(tracker.onSnapshot(initial))
        // Repeated identical snapshot does not re-fire.
        assertNull(tracker.onSnapshot(initial))
        // Genuinely new arrival triggers once.
        val newer = NotificationInfo("Mail", "New invoice", postTimeMillis = 200L, key = "k2")
        assertEquals(newer, tracker.onSnapshot(listOf(newer, initial.first())))
        assertNull(tracker.onSnapshot(listOf(newer, initial.first())))
        // Clearing resets active entries cleanly.
        assertNull(tracker.onSnapshot(emptyList()))
    }

    @Test
    fun headsUpPolicySuppressesOnlyPeekAndPreservesStricterUserDnd() {
        assertEquals(
            NotificationManager.INTERRUPTION_FILTER_PRIORITY,
            HeadsUpPolicyMath.targetFilter(NotificationManager.INTERRUPTION_FILTER_ALL),
        )
        assertEquals(
            NotificationManager.INTERRUPTION_FILTER_NONE,
            HeadsUpPolicyMath.targetFilter(NotificationManager.INTERRUPTION_FILTER_NONE),
        )
        val merged = HeadsUpPolicyMath.suppressedVisualEffects(NotificationManager.Policy.SUPPRESSED_EFFECT_BADGE)
        assertTrue(merged and NotificationManager.Policy.SUPPRESSED_EFFECT_PEEK != 0)
        assertTrue(merged and NotificationManager.Policy.SUPPRESSED_EFFECT_BADGE != 0)
        assertTrue(HeadsUpPolicyMath.ALL_AUDIBLE_CATEGORIES and NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS != 0)
        assertTrue(HeadsUpPolicyMath.ALL_AUDIBLE_CATEGORIES and NotificationManager.Policy.PRIORITY_CATEGORY_CALLS != 0)
    }

    @Test
    fun faceStyleAndPaletteHelpersWorkIndependently() {
        val initial = LumaSettings(themeId = "ios-coral")
        for (style in FaceStyle.entries) {
            val switched = ThemeEditing.withFaceStyle(initial, style, 77L)
            assertEquals(style, switched.faceStyle)
            assertFalse(switched.builtIn)
        }

        val floatSettings = ThemeEditing.upsert(initial, ThemeEditing.withFaceStyle(initial, FaceStyle.FLOAT, 1L))
        val recolored = ThemeEditing.withPaletteKeepingStyle(floatSettings, ThemePresets.iosMint, 2L)
        assertEquals(FaceStyle.FLOAT, recolored.faceStyle)
        assertEquals(ThemePresets.iosMint.accent, recolored.accent)

        val floatFull = FaceLayout.create(2400f, 1080f, withWidgets = true, style = FaceStyle.FLOAT)
        assertTrue(floatFull.clock.width > 2000f)
        assertTrue(floatFull.date.bottom < floatFull.clock.top)
    }

    private fun renderClock(
        width: Int,
        height: Int,
        theme: LumaTheme,
        tiles: List<WidgetTile>,
        alert: StandbyNotificationAlert? = null,
        unread: UnreadNotificationSummary? = null,
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
        view.setUnreadSummary(unread)
        view.setActiveAlert(alert)
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
