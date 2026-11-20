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
import app.luma.standby.domain.widget.LiveActivityInfo
import app.luma.standby.domain.widget.LiveActivitySource
import app.luma.standby.domain.widget.NotificationInfo
import app.luma.standby.domain.widget.WeatherCondition
import app.luma.standby.domain.widget.WeatherInfo
import app.luma.standby.domain.widget.WidgetData
import app.luma.standby.platform.widgets.HeadsUpPolicyMath
import app.luma.standby.platform.widgets.WidgetRenderer
import app.luma.standby.ui.clock.AlertExitMode
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

        // 1b. Analog + BOTTOM_LEFT battery only (no right widgets): right Calendar Hero must stay full height!
        val leftOnlySettings = LumaSettings(
            themeId = "ios-coral",
            widgetPlacements = listOf(StoredPlacement("battery", "BOTTOM_LEFT")),
        )
        val leftOnlyTiles = WidgetRenderer.tiles(
            settings = leftOnlySettings,
            data = WidgetData(battery = BatteryInfo(percent = 100, charging = true)),
            context = context,
        )
        val analogLeftOnlyBmp = renderClock(1600, 720, ThemePresets.iosCoral, leftOnlyTiles)
        // Right half center band must contain the bright calendar card (not a squished top strip).
        var rightMidNonBlack = 0
        for (y in 250..500 step 4) {
            for (x in 900..1350 step 4) {
                val px = analogLeftOnlyBmp.getPixel(x, y)
                if (Color.red(px) > 12 || Color.green(px) > 12 || Color.blue(px) > 12) rightMidNonBlack++
            }
        }
        assertTrue("Calendar Hero must fill symmetric right square when only BOTTOM_LEFT is set", rightMidNonBlack > 4000)

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

        // 7. Full-Screen iOS StandBy Notification Alert (Stage 2 borderless Sender + Preview Body)
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
                expandImmediately = true,
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
        // Messages must be intercepted by DND so SUPPRESSED_EFFECT_PEEK actually hides OS floating banners.
        assertEquals(0, HeadsUpPolicyMath.ALL_AUDIBLE_CATEGORIES and NotificationManager.Policy.PRIORITY_CATEGORY_MESSAGES)
        assertEquals(0, HeadsUpPolicyMath.ALL_AUDIBLE_CATEGORIES and NotificationManager.Policy.PRIORITY_CATEGORY_CONVERSATIONS)
        // Android 15+ restores via INTERRUPTION_FILTER_ALL (deactivating only our app's implicit ZenRule);
        // older SDKs restore the exact pre-Standby saved filter.
        assertEquals(
            NotificationManager.INTERRUPTION_FILTER_ALL,
            HeadsUpPolicyMath.restoreFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY, sdkInt = 35),
        )
        assertEquals(
            NotificationManager.INTERRUPTION_FILTER_NONE,
            HeadsUpPolicyMath.restoreFilter(NotificationManager.INTERRUPTION_FILTER_NONE, sdkInt = 34),
        )
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

        // Repeated horizontal palette swipes reuse the active custom theme ID instead of bloating customThemes.
        val afterFirst = ThemeEditing.upsert(floatSettings, recolored)
        val secondSwipe = ThemeEditing.withPaletteKeepingStyle(afterFirst, ThemePresets.iosCoral, 3L)
        val afterSecond = ThemeEditing.upsert(afterFirst, secondSwipe)
        assertEquals(1, afterSecond.customThemes.size)
        assertEquals(recolored.id, secondSwipe.id)

        val floatFull = FaceLayout.create(2400f, 1080f, withWidgets = true, style = FaceStyle.FLOAT)
        assertTrue(floatFull.clock.width > 2000f)
        assertTrue(floatFull.date.bottom < floatFull.clock.top)
    }

    @Test
    fun alertClearButtonsHaveHitTargetsAndResetOnDismiss() {
        val view = ClockView(context)
        view.animationsEnabled = false
        view.applyState(ThemePresets.iosCoral, true, false, false, null)
        view.setActiveAlert(
            StandbyNotificationAlert(
                appLabel = "微信",
                title = "产品设计群",
                body = "张三：马上评审",
                key = "k-1",
                unreadCount = 3,
                expandImmediately = true,
            ),
        )
        view.measure(
            View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(590, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 1280, 590)
        val bmp = Bitmap.createBitmap(1280, 590, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))

        // Scan lower-right Stage-2 action region to verify both Clear and Clear-All hit targets exist and don't overlap.
        var clearHits = 0
        var clearAllHits = 0
        var overlapHits = 0
        for (y in 430..550 step 4) {
            for (x in 650..1180 step 4) {
                val c = view.hitTestAlertClear(x.toFloat(), y.toFloat())
                val a = view.hitTestAlertClearAll(x.toFloat(), y.toFloat())
                if (c) clearHits++
                if (a) clearAllHits++
                if (c && a) overlapHits++
            }
        }
        assertTrue("Expected Clear button hit target", clearHits > 10)
        assertTrue("Expected Clear-All button hit target when unreadCount > 1", clearAllHits > 10)
        assertTrue("Clear and Clear-All hit targets should remain well separated", overlapHits < 15)

        view.setActiveAlert(null, AlertExitMode.FLING_LEFT)
        assertFalse(view.hitTestAlertClear(1060f, 490f))
        assertFalse(view.hitTestAlertClearAll(940f, 490f))
    }

    @Test
    fun renderDynamicIslandDualDockAndExpandedSuperIslandSnapshots() {
        val outDir = File("build/robolectric-home/snapshots").apply { mkdirs() }
        val meituan = LiveActivityInfo(
            key = "mt-1",
            source = LiveActivitySource.XIAOMI_SUPER_ISLAND,
            appLabel = "美团外卖",
            packageName = "com.sankuai.meituan.takeoutnew",
            title = "骑手正在配送中",
            subtitle = "预计 14:18 送达 · 距离您 300 米",
            compactText = "骑手距您 300m",
            highlightMetric = "4 分钟",
            progress = 0.76f,
            accentColor = 0xFFFFB000.toInt(),
            business = "delivery",
        )
        val music = LiveActivityInfo(
            key = "music-1",
            source = LiveActivitySource.MEDIA_PLAYBACK,
            appLabel = "QQ 音乐",
            packageName = "com.tencent.qqmusic",
            title = "稻香",
            subtitle = "周杰伦 — 魔杰座",
            compactText = "稻香 · 周杰伦",
            highlightMetric = null,
            progress = 0.48f,
            accentColor = 0xFFFF375F.toInt(),
            business = "media",
            isPlayingMedia = true,
        )

        // 1. Dual-Island Split Dock: Meituan Super Island + Detached WeChat Unread Bubble
        val dualView = ClockView(context)
        dualView.animationsEnabled = false
        dualView.applyState(ThemePresets.iosCoral, true, false, false, null)
        dualView.setLiveActivities(listOf(meituan, music))
        dualView.setUnreadSummary(UnreadNotificationSummary("微信", count = 2))
        dualView.measure(
            View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(590, View.MeasureSpec.EXACTLY),
        )
        dualView.layout(0, 0, 1280, 590)
        val dualBmp = Bitmap.createBitmap(1280, 590, Bitmap.Config.ARGB_8888)
        dualView.draw(Canvas(dualBmp))
        savePng(dualBmp, File(outDir, "10_island_docked_dual.png"))

        // Verify both Dynamic Island pill and detached Unread Bubble are independently tappable!
        var islandHits = 0
        var bubbleHits = 0
        for (x in 300..980 step 4) {
            if (dualView.hitTestLiveIslandPill(x.toFloat(), 40f)) islandHits++
            if (dualView.hitTestUnreadPill(x.toFloat(), 40f)) bubbleHits++
        }
        assertTrue("Expected Dynamic Island pill hit target", islandHits > 10)
        assertTrue("Expected Detached Unread Bubble hit target", bubbleHits > 5)

        // 2. Expanded Super Island Hero Card (Meituan Delivery)
        val expView = ClockView(context)
        expView.animationsEnabled = false
        expView.applyState(ThemePresets.iosCoral, true, false, false, null)
        expView.setLiveActivities(listOf(meituan, music))
        expView.setExpandedLiveActivity(meituan)
        expView.measure(
            View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(590, View.MeasureSpec.EXACTLY),
        )
        expView.layout(0, 0, 1280, 590)
        val expBmp = Bitmap.createBitmap(1280, 590, Bitmap.Config.ARGB_8888)
        expView.draw(Canvas(expBmp))
        savePng(expBmp, File(outDir, "11_island_expanded_delivery.png"))

        var collapseHits = 0
        var cycleHits = 0
        for (y in 120..290 step 4) {
            for (x in 650..1130 step 4) {
                if (expView.hitTestLiveCardCollapse(x.toFloat(), y.toFloat())) collapseHits++
                if (expView.hitTestLiveCardCycle(x.toFloat(), y.toFloat())) cycleHits++
            }
        }
        assertTrue("Expected Collapse button hit target", collapseHits > 10)
        assertTrue("Expected 1/2 Switch button hit target", cycleHits > 10)

        // 3. Expanded Live Music Hero Card (with synthetic gradient album art)
        val art = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).apply {
            val c = Canvas(this)
            c.drawColor(Color.parseColor("#FF2D55"))
            val p = android.graphics.Paint().apply { color = Color.parseColor("#FFB347") }
            c.drawCircle(92f, 36f, 52f, p)
        }
        val musicView = ClockView(context)
        musicView.animationsEnabled = false
        musicView.applyState(ThemePresets.iosMint, true, false, false, null)
        musicView.setLiveActivities(listOf(music), albumArt = art)
        musicView.setExpandedLiveActivity(music)
        musicView.measure(
            View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(590, View.MeasureSpec.EXACTLY),
        )
        musicView.layout(0, 0, 1280, 590)
        val musicBmp = Bitmap.createBitmap(1280, 590, Bitmap.Config.ARGB_8888)
        musicView.draw(Canvas(musicBmp))
        savePng(musicBmp, File(outDir, "12_island_expanded_music.png"))
        assertTrue(countBright(musicBmp) > 8000)

        // 4. Light Paper Theme + Expanded Super Island contrast verification
        val lightView = ClockView(context)
        lightView.animationsEnabled = false
        lightView.applyState(ThemePresets.bone.copy(faceStyle = FaceStyle.FLOAT), true, false, false, null)
        lightView.setLiveActivities(listOf(meituan))
        lightView.setExpandedLiveActivity(meituan)
        lightView.measure(
            View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(590, View.MeasureSpec.EXACTLY),
        )
        lightView.layout(0, 0, 1280, 590)
        val lightBmp = Bitmap.createBitmap(1280, 590, Bitmap.Config.ARGB_8888)
        lightView.draw(Canvas(lightBmp))
        savePng(lightBmp, File(outDir, "13_island_light_theme.png"))
        assertTrue(countNonBlack(lightBmp) > 50_000)

        // 5. Night Red Mode + Expanded Super Island verification
        val redView = ClockView(context)
        redView.animationsEnabled = false
        redView.applyState(ThemePresets.iosNightRed, true, false, false, null)
        redView.setLiveActivities(listOf(meituan))
        redView.setExpandedLiveActivity(meituan)
        redView.measure(
            View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(590, View.MeasureSpec.EXACTLY),
        )
        redView.layout(0, 0, 1280, 590)
        val redBmp = Bitmap.createBitmap(1280, 590, Bitmap.Config.ARGB_8888)
        redView.draw(Canvas(redBmp))
        savePng(redBmp, File(outDir, "14_island_night_red.png"))
        assertTrue(countNonBlack(redBmp) > 15_000)

        // 6. Iconic iOS StandBy Poster Teal & Blush (`FaceStyle.POSTER`) + All 10 Digits Specimen
        val posterSettings = LumaSettings(
            themeId = "ios-poster-teal",
            widgetPlacements = listOf(
                StoredPlacement("weather", "TOP_RIGHT"),
                StoredPlacement("alarm", "BOTTOM_RIGHT"),
            ),
        )
        val posterTiles = WidgetRenderer.tiles(
            settings = posterSettings,
            data = WidgetData(
                weather = WeatherInfo(
                    temperatureC = 5,
                    condition = WeatherCondition.CLOUDY,
                    updatedAtMillis = System.currentTimeMillis(),
                    city = "北京",
                ),
                nextAlarm = AlarmInfo(hour = 8, minute = 40),
            ),
            context = context,
        )
        val posterBmp = renderClock(1600, 720, ThemePresets.iosPosterTeal, posterTiles)
        savePng(posterBmp, File(outDir, "15_poster_teal_reference.png"))
        assertTrue("Poster Teal renders cleanly", countNonBlack(posterBmp) > 80_000)

        // Render all 10 custom vector poster digits (`01234` + `56789`) on teal backdrop for visual audit!
        val specimenBmp = Bitmap.createBitmap(1600, 720, Bitmap.Config.ARGB_8888)
        val sc = Canvas(specimenBmp)
        sc.drawColor(Color.parseColor("#245666"))
        val digits = app.luma.standby.ui.clock.PosterDigitPaths()
        val pink = Color.parseColor("#FBC6D2")
        digits.drawTime(sc, "16:25", 90f, 60f, 270f, pink)
        digits.drawTime(sc, "03:47", 860f, 60f, 270f, pink)
        digits.drawTime(sc, "08:92", 90f, 390f, 270f, pink)
        digits.drawTime(sc, "14:58", 860f, 390f, 270f, pink)
        savePng(specimenBmp, File(outDir, "16_poster_digits_specimen.png"))
    }

    @Test
    fun refinedIosStandbyNotificationsMatchReference() {
        val outDir = File(System.getProperty("user.home"), "snapshots").apply { mkdirs() }
        val posterSettings = LumaSettings(
            themeId = ThemePresets.iosPosterTeal.id,
            widgetPlacements = listOf(
                StoredPlacement("weather", "TOP_RIGHT"),
                StoredPlacement("alarm", "BOTTOM_RIGHT"),
            ),
        )
        val posterTiles = WidgetRenderer.tiles(
            settings = posterSettings,
            data = WidgetData(
                weather = WeatherInfo(
                    temperatureC = 5,
                    condition = WeatherCondition.CLOUDY,
                    updatedAtMillis = System.currentTimeMillis(),
                    city = "北京",
                ),
                nextAlarm = AlarmInfo(hour = 8, minute = 40),
            ),
            context = context,
        )

        // Stage 1: Exact iOS StandBy arrival — blurred Poster Teal clock + centered Mail squircle + "Latent Space" + "1个通知"
        val stage1Alert = StandbyNotificationAlert(
            appLabel = "邮件",
            title = "Latent Space",
            body = null,
            unreadCount = 1,
            tapToReveal = false,
        )
        assertFalse("Short title without body stays on clean Stage 1 Hero", stage1Alert.hasDetailPreview)
        val stage1Bmp = renderClock(1600, 720, ThemePresets.iosPosterTeal, posterTiles, alert = stage1Alert)
        savePng(stage1Bmp, File(outDir, "17_ios_notif_stage1_poster_teal.png"))
        assertTrue("Stage 1 Hero notification renders visibly", countNonBlack(stage1Bmp) > 80_000)

        // Stage 2: Borderless reading view over blurred Poster Teal clock (icon glided up-left + full message + quiet Clear pills)
        val stage2Alert = StandbyNotificationAlert(
            appLabel = "微信",
            title = "产品设计群",
            body = "张三：10 分钟后评审新版待机表盘，记得带上小米 17",
            unreadCount = 3,
            tapToReveal = false,
            expandImmediately = true,
        )
        assertTrue("Message with preview body enables smooth Stage 2 glide", stage2Alert.hasDetailPreview)
        val stage2Bmp = renderClock(1600, 720, ThemePresets.iosPosterTeal, posterTiles, alert = stage2Alert)
        savePng(stage2Bmp, File(outDir, "18_ios_notif_stage2_detail.png"))
        assertTrue("Stage 2 borderless detail notification renders visibly", countNonBlack(stage2Bmp) > 80_000)
    }

    @Test
    fun appleSpringHasZeroFrame1KickAndInheritsReleaseVelocity() {
        // 1. Zero frame-1 velocity cliff: at t = 0.01, physical spring displacement stays tiny (< 0.015),
        //    unlike easeOutCubic (~0.03) or steep Bezier (~0.045).
        assertEquals(0f, app.luma.standby.ui.clock.AppleSpring.evaluate(0f, 0.52f, 0.44f, 0.94f), 0.0001f)
        assertEquals(1f, app.luma.standby.ui.clock.AppleSpring.evaluate(1f, 0.52f, 0.44f, 0.94f), 0.0001f)
        val early = app.luma.standby.ui.clock.AppleSpring.evaluate(0.01f, 0.52f, 0.44f, 0.94f)
        assertTrue("Spring starts smoothly from rest without frame-1 kick (was $early)", early in 0.0001f..0.015f)

        // 2. Cancelled drag with outward thumb velocity coasts briefly in thumb direction before settling to 0.
        val x0 = 60f
        val coast = app.luma.standby.ui.clock.AppleSpring.settleDisplacement(0.02f, x0 = x0, v0 = 900f)
        val settled = app.luma.standby.ui.clock.AppleSpring.settleDisplacement(0.60f, x0 = x0, v0 = 900f)
        assertTrue("Positive release velocity carries momentum smoothly past x0 ($coast > $x0)", coast > x0)
        assertTrue("Spring settles smoothly back near 0 ($settled)", kotlin.math.abs(settled) < 3f)

        // 3. Velocity-preserving glide lands cleanly on target and moves faster early with thumb assist.
        val slowMid = app.luma.standby.ui.clock.AppleSpring.glideWithVelocity(0.25f, 0.34f, 0f, 500f, v0 = 0f)
        val fastMid = app.luma.standby.ui.clock.AppleSpring.glideWithVelocity(0.25f, 0.34f, 0f, 500f, v0 = 2_400f)
        assertTrue("Fast flick advances further early ($fastMid > $slowMid)", fastMid > slowMid + 30f)
        assertEquals(500f, app.luma.standby.ui.clock.AppleSpring.glideWithVelocity(1f, 0.34f, 0f, 500f, v0 = 2_400f), 0.01f)
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
