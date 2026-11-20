package app.luma.standby

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.luma.standby.domain.NightMode
import app.luma.standby.domain.model.DisplayMode
import app.luma.standby.domain.model.FaceStyle
import app.luma.standby.domain.model.LumaSettings
import app.luma.standby.domain.theme.PalettePresets
import app.luma.standby.domain.theme.ThemeEditing
import app.luma.standby.domain.theme.ThemeRepository
import app.luma.standby.domain.theme.currentTheme
import app.luma.standby.domain.widget.NotificationInfo
import app.luma.standby.platform.battery.BatteryController
import app.luma.standby.platform.display.BrightnessController
import app.luma.standby.platform.display.DisplayController
import app.luma.standby.platform.display.RefreshRatePolicy
import app.luma.standby.platform.display.RefreshState
import app.luma.standby.platform.power.PowerController
import app.luma.standby.platform.widgets.HeadsUpSuppressor
import app.luma.standby.platform.widgets.LumaNotificationListener
import app.luma.standby.platform.widgets.WeatherStore
import app.luma.standby.platform.widgets.WidgetDataCollector
import app.luma.standby.platform.widgets.WidgetRenderer
import app.luma.standby.ui.clock.AlertExitMode
import app.luma.standby.ui.clock.ClockView
import app.luma.standby.ui.clock.DebugAlertBus
import app.luma.standby.ui.clock.StandbyAlertTracker
import app.luma.standby.ui.clock.StandbyNotificationAlert
import app.luma.standby.ui.clock.UnreadNotificationSummary
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * The in-app standby surface. Works in whatever orientation the phone is held,
 * goes fullscreen and dim, and idles back to the static refresh state after a
 * few seconds of no touching.
 *
 * Gestures modelled after iOS 26/27 StandBy:
 * - Swipe up/down: cycle the 4 StandBy faces (Analog / Split / Float / Liquid Solar).
 * - Swipe left/right: cycle the curated iOS StandBy color palettes while keeping the current face.
 * - Tap top unread notification pill: open the full-screen iOS StandBy notification card.
 * - Tap full-screen alert: reveal (if Tap-to-View privacy is on) or dismiss.
 * - Double-tap clock: exit Standby.
 */
class StandbyActivity : ComponentActivity() {

    private lateinit var clockView: ClockView
    private val repository by lazy { ThemeRepository(applicationContext) }
    private val displayController by lazy { DisplayController(this) }
    private val batteryController by lazy { BatteryController(this) }
    private val powerController by lazy { PowerController(this) }
    private val brightnessController by lazy { BrightnessController(this) }
    private val widgetCollector by lazy { WidgetDataCollector(this) }
    private val weatherStore by lazy { WeatherStore(this) }
    private val headsUpSuppressor by lazy { HeadsUpSuppressor(this) }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val returnToIdle = Runnable { applyRefreshIntent(baselineState()) }
    private val dismissAlertRunnable = Runnable { dismissNotificationAlert() }

    private var stopObservingBattery: (() -> Unit)? = null
    private var settings: LumaSettings = LumaSettings()
    private var lastTapAt = 0L
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var touchDownOnUnreadPill = false
    private var swipeConsumed = false

    private val alertTracker = StandbyAlertTracker()
    private var activeAlert: StandbyNotificationAlert? = null
    private var latestNotifications: List<NotificationInfo> = emptyList()

    private val widgetTicks = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var receiverRegistered = false
    private val screenEvents = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                headsUpSuppressor.restore()
                clearSensitiveState()
            } else {
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    applySettings(settings)
                    syncHeadsUpSuppression()
                    refreshWidgets()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        clockView = ClockView(this)
        clockView.setBackgroundColor(Color.BLACK)
        setContentView(clockView)

        setShowWhenLocked(true)
        setTurnScreenOn(true)
        hideSystemBars()

        ContextCompat.registerReceiver(this, screenEvents, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true

        val isDebuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                launch {
                    repository.settings.collect { latest ->
                        val previousCity = settings.weatherCity
                        settings = latest
                        applySettings(latest)
                        syncHeadsUpSuppression()
                        applyRefreshIntent(baselineState())
                        refreshWidgets()
                        widgetTicks.tryEmit(Unit)
                        if (latest.weatherCity != previousCity) refreshWidgets()
                    }
                }
                launch { weatherStore.snapshot.collect { refreshWidgets() } }
                launch { LumaNotificationListener.changes.collect { refreshWidgets() } }
                launch {
                    widgetTicks.collectLatest {
                        weatherStore.update(
                            settings.mode != DisplayMode.ULTRA_LOW_POWER && settings.widgetPlacements.any { it.widgetId == "weather" },
                            settings.weatherLat, settings.weatherLon,
                            settings.weatherCity?.ifBlank { null },
                        )
                    }
                }
                if (isDebuggable) {
                    launch {
                        DebugAlertBus.alerts.collect { previewAlert ->
                            latestNotifications = List(previewAlert.unreadCount.coerceAtLeast(1)) { idx ->
                                NotificationInfo(
                                    appLabel = previewAlert.appLabel,
                                    title = if (idx == 0) previewAlert.title else "${previewAlert.title} #${idx + 1}",
                                    body = previewAlert.body,
                                    packageName = previewAlert.packageName,
                                    postTimeMillis = System.currentTimeMillis() - idx * 1000L,
                                    key = previewAlert.key ?: "debug-$idx",
                                )
                            }
                            clockView.setUnreadSummary(
                                UnreadNotificationSummary(
                                    appLabel = previewAlert.appLabel,
                                    packageName = previewAlert.packageName,
                                    count = latestNotifications.size,
                                ),
                            )
                            showNotificationAlert(previewAlert.copy(key = latestNotifications.first().key))
                        }
                    }
                }
            }
        }

        clockView.isClickable = true
        clockView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchDownX = event.x
                    touchDownY = event.y
                    touchDownOnUnreadPill = activeAlert == null && clockView.hitTestUnreadPill(event.x, event.y)
                    swipeConsumed = false
                    enterInteractive(flashHint = false)
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!touchDownOnUnreadPill) {
                        val dx = event.x - touchDownX
                        val dy = event.y - touchDownY
                        if (abs(dx) > 12f || abs(dy) > 12f) {
                            clockView.setInteractiveDrag(dx, dy)
                        }
                    }
                }

                MotionEvent.ACTION_CANCEL -> {
                    touchDownOnUnreadPill = false
                    clockView.releaseInteractiveDrag()
                }

                MotionEvent.ACTION_UP -> {
                    val dx = event.x - touchDownX
                    val dy = event.y - touchDownY
                    val absDx = abs(dx)
                    val absDy = abs(dy)
                    val currentAlert = activeAlert
                    val startedOnUnreadPill = touchDownOnUnreadPill
                    touchDownOnUnreadPill = false

                    if (currentAlert != null) {
                        // Notification takeover is visible:
                        // - Swipe left/right or tap "Clear" / "Clear N" permanently clears from Android shade.
                        // - Swipe up or tap body tucks back into top pill (or reveals if Tap-to-View is on).
                        // - Partial drag springs smoothly back to center.
                        if (absDx > SWIPE_THRESHOLD_PX && absDx > absDy * 1.15f) {
                            swipeConsumed = true
                            lastTapAt = 0L
                            clearCurrentNotification(
                                if (dx > 0f) AlertExitMode.FLING_RIGHT else AlertExitMode.FLING_LEFT,
                            )
                        } else if (absDy > SWIPE_THRESHOLD_PX) {
                            swipeConsumed = true
                            lastTapAt = 0L
                            dismissNotificationAlert(AlertExitMode.TUCK_TO_PILL)
                        } else if (absDx < TAP_SLOP_PX && absDy < TAP_SLOP_PX) {
                            clockView.releaseInteractiveDrag()
                            lastTapAt = 0L
                            when {
                                clockView.hitTestAlertClear(event.x, event.y) ->
                                    clearCurrentNotification(AlertExitMode.CLEAR_DISSOLVE)
                                clockView.hitTestAlertClearAll(event.x, event.y) ->
                                    clearAllNotifications(AlertExitMode.CLEAR_DISSOLVE)
                                currentAlert.tapToReveal && !currentAlert.revealed && !currentAlert.isPrivateRedacted ->
                                    showNotificationAlert(currentAlert.copy(revealed = true))
                                else ->
                                    dismissNotificationAlert(AlertExitMode.TUCK_TO_PILL)
                            }
                        } else {
                            clockView.releaseInteractiveDrag()
                        }
                    } else if (startedOnUnreadPill) {
                        lastTapAt = 0L
                        if (absDx > SWIPE_THRESHOLD_PX || absDy > SWIPE_THRESHOLD_PX) {
                            swipeConsumed = true
                            clearAllNotifications(AlertExitMode.CLEAR_DISSOLVE)
                            clockView.flashBanner(getString(R.string.notif_cleared_banner))
                        } else if (absDx < TAP_SLOP_PX && absDy < TAP_SLOP_PX) {
                            openLatestNotificationAlert()
                        }
                    } else if (absDy > SWIPE_THRESHOLD_PX && absDy > absDx * 1.35f) {
                        swipeConsumed = true
                        lastTapAt = 0L
                        cycleFaceStyle(step = if (dy < 0f) 1 else -1)
                    } else if (absDx > SWIPE_THRESHOLD_PX && absDx > absDy * 1.35f) {
                        swipeConsumed = true
                        lastTapAt = 0L
                        cycleIosPalette(step = if (dx < 0f) 1 else -1)
                    } else if (!swipeConsumed && absDx < TAP_SLOP_PX && absDy < TAP_SLOP_PX) {
                        clockView.releaseInteractiveDrag()
                        if (clockView.hitTestUnreadPill(event.x, event.y)) {
                            lastTapAt = 0L
                            openLatestNotificationAlert()
                        } else {
                            val now = event.eventTime
                            if (now - lastTapAt <= DOUBLE_TAP_WINDOW_MS) {
                                lastTapAt = 0L
                                clockView.performClick()
                                finish()
                            } else {
                                lastTapAt = now
                                clockView.flashHint()
                            }
                        }
                    } else {
                        clockView.releaseInteractiveDrag()
                    }
                }
            }
            true
        }

        // Re-evaluate the night window exactly when the clock redraws: that is the
        // only moment we are awake anyway, and it keeps the ramp in step with the
        // minute boundary instead of running its own timer.
        clockView.onTick = {
            val changedMinute = currentMinuteOfDay() != lastBrightnessMinute
            applyBrightnessIfMinuteChanged()
            if (changedMinute) {
                refreshWidgets()
                widgetTicks.tryEmit(Unit)
            }
        }

        stopObservingBattery = batteryController.observe { snapshot ->
            setKeepScreenOn(snapshot.charging)
        }
        setKeepScreenOn(batteryController.snapshot().charging)
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        clockView.resumeTicking()
        syncHeadsUpSuppression()
        enterInteractive(flashHint = false)
    }

    override fun onPause() {
        mainHandler.removeCallbacks(returnToIdle)
        clockView.pauseTicking()
        headsUpSuppressor.restore()
        clearSensitiveState()
        // The brightness override is window-scoped, but clear it explicitly so a
        // re-shown window never inherits a stale night value.
        brightnessController.restore(window)
        super.onPause()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(returnToIdle)
        mainHandler.removeCallbacks(dismissAlertRunnable)
        headsUpSuppressor.restore()
        stopObservingBattery?.invoke()
        stopObservingBattery = null
        if (receiverRegistered) unregisterReceiver(screenEvents)
        weatherStore.shutdown()
        super.onDestroy()
    }

    private fun syncHeadsUpSuppression() {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
            settings.notificationAlerts &&
            settings.suppressSystemHeadsUp
        ) {
            headsUpSuppressor.suppress()
        } else {
            headsUpSuppressor.restore()
        }
    }

    private fun clearSensitiveState() {
        mainHandler.removeCallbacks(dismissAlertRunnable)
        activeAlert = null
        latestNotifications = emptyList()
        alertTracker.reset()
        LumaNotificationListener.setPreviewEnabled(false)
        LumaNotificationListener.setCollectionEnabled(false)
        clockView.setWidgets(emptyList())
        clockView.clearSensitiveMemory()
    }

    private fun applySettings(settings: LumaSettings) {
        val theme = settings.currentTheme()
        val lowPower = powerController.isPowerSaveMode() || settings.mode == DisplayMode.ULTRA_LOW_POWER
        clockView.animationsEnabled = !lowPower
        clockView.applyState(
            theme = theme,
            use24HourOverride = settings.use24Hour,
            showSecondsOverride = if (lowPower) false else settings.showSeconds,
            burnInShift = settings.burnInShift,
            hint = getString(R.string.exit_standby_hint),
        )
        applyBrightness(settings)
    }

    private var lastBrightnessMinute = -1

    private fun applyBrightness(settings: LumaSettings) {
        val minuteOfDay = currentMinuteOfDay()
        lastBrightnessMinute = minuteOfDay
        val requested = NightMode.targetBrightness(
            settings = settings,
            minuteOfDay = minuteOfDay,
            systemBrightness = brightnessController.systemBrightness01(),
        )
        val thermalFactor = if (powerController.shouldDim()) 0.6f else 1f
        val target = if (requested < 0f) -1f else (requested * thermalFactor).coerceIn(0.01f, 1f)
        brightnessController.apply(window, target)
    }

    /**
     * Recomputes widget tiles and handles event-driven iOS StandBy notification alerts.
     * Sensitive notification collection runs only when unlocked, permission-granted, and visible.
     */
    private fun refreshWidgets() {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val enabled = settings.widgetPlacements.map { it.widgetId }.toSet()
        val alertsWanted = settings.notificationAlerts && settings.mode != DisplayMode.ULTRA_LOW_POWER

        if (enabled.isEmpty() && !alertsWanted) {
            LumaNotificationListener.setCollectionEnabled(false)
            clockView.setWidgets(emptyList())
            clockView.setUnreadSummary(null)
            return
        }

        val weather = if (enabled.contains("weather")) weatherStore.cached(settings.weatherLat, settings.weatherLon) else null
        val data = widgetCollector.collect(
            enabledIds = enabled,
            weather = weather,
            notificationAlertsEnabled = alertsWanted,
            notificationPreviewEnabled = settings.notificationPreview,
        )
        clockView.setWidgets(WidgetRenderer.tiles(settings, data, context = this))

        if (alertsWanted) {
            handleNotificationSnapshot(data.notifications)
        } else {
            latestNotifications = emptyList()
            clockView.setUnreadSummary(null)
            dismissNotificationAlert()
        }
    }

    private fun handleNotificationSnapshot(notifications: List<NotificationInfo>) {
        latestNotifications = notifications
        val top = notifications.firstOrNull()
        if (top == null) {
            clockView.setUnreadSummary(null)
            dismissNotificationAlert()
            alertTracker.onSnapshot(emptyList())
            return
        }

        clockView.setUnreadSummary(
            UnreadNotificationSummary(
                appLabel = top.appLabel,
                packageName = top.packageName,
                count = notifications.size,
            ),
        )

        val newlyArrived = alertTracker.onSnapshot(notifications)
        if (newlyArrived != null) {
            showNotificationAlert(buildAlert(newlyArrived, notifications.size))
        }
    }

    private fun openLatestNotificationAlert() {
        val top = latestNotifications.firstOrNull() ?: return
        showNotificationAlert(buildAlert(top, latestNotifications.size))
    }

    private fun buildAlert(info: NotificationInfo, count: Int): StandbyNotificationAlert {
        val redacted = info.title.equals(info.appLabel, ignoreCase = true) && info.body.isNullOrBlank()
        return StandbyNotificationAlert(
            appLabel = info.appLabel,
            title = info.title,
            body = info.body,
            packageName = info.packageName,
            key = info.key,
            unreadCount = count,
            tapToReveal = settings.notificationTapToReveal,
            revealed = false,
            isPrivateRedacted = redacted,
        )
    }

    private fun showNotificationAlert(alert: StandbyNotificationAlert) {
        activeAlert = alert
        clockView.setActiveAlert(alert)
        enterInteractive(flashHint = false)
        mainHandler.removeCallbacks(dismissAlertRunnable)
        mainHandler.postDelayed(dismissAlertRunnable, ALERT_VISIBLE_MS)
    }

    private fun dismissNotificationAlert(exitMode: AlertExitMode = AlertExitMode.TUCK_TO_PILL) {
        mainHandler.removeCallbacks(dismissAlertRunnable)
        if (activeAlert != null) {
            activeAlert = null
            clockView.setActiveAlert(null, exitMode)
        }
    }

    private fun clearCurrentNotification(exitMode: AlertExitMode) {
        val current = activeAlert ?: return
        val key = current.key
        if (!key.isNullOrBlank()) {
            LumaNotificationListener.dismiss(key)
        }
        val remaining = if (!key.isNullOrBlank()) {
            latestNotifications.filterNot { it.key == key }
        } else {
            latestNotifications.drop(1)
        }
        latestNotifications = remaining
        val nextTop = remaining.firstOrNull()
        clockView.setUnreadSummary(
            nextTop?.let { UnreadNotificationSummary(it.appLabel, it.packageName, remaining.size) },
        )
        dismissNotificationAlert(exitMode)
    }

    private fun clearAllNotifications(exitMode: AlertExitMode) {
        val keys = latestNotifications.mapNotNull { it.key?.takeIf(String::isNotBlank) }
        if (keys.isNotEmpty()) {
            LumaNotificationListener.dismissAll(keys)
        }
        latestNotifications = emptyList()
        clockView.setUnreadSummary(null)
        dismissNotificationAlert(exitMode)
    }

    private fun applyBrightnessIfMinuteChanged() {
        if (currentMinuteOfDay() != lastBrightnessMinute) applyBrightness(settings)
    }

    private fun currentMinuteOfDay(): Int {
        val calendar = java.util.Calendar.getInstance()
        return calendar.get(java.util.Calendar.HOUR_OF_DAY) * 60 + calendar.get(java.util.Calendar.MINUTE)
    }

    /** A touch buys a short window of normal refresh rate, then we idle again. */
    private fun enterInteractive(flashHint: Boolean = true) {
        if (flashHint) clockView.flashHint()
        applyRefreshIntent(RefreshState.INTERACTIVE)
        mainHandler.removeCallbacks(returnToIdle)
        mainHandler.postDelayed(returnToIdle, IDLE_TIMEOUT_MS)
    }

    private fun cycleFaceStyle(step: Int) {
        val styles = FaceStyle.entries
        val currentStyle = settings.currentTheme().faceStyle
        val idx = styles.indexOf(currentStyle).coerceAtLeast(0)
        val nextStyle = styles[(idx + step + styles.size) % styles.size]
        val label = getString(
            when (nextStyle) {
                FaceStyle.ANALOG -> R.string.style_analog
                FaceStyle.SPLIT -> R.string.style_split
                FaceStyle.FLOAT -> R.string.style_float
                FaceStyle.SOLAR -> R.string.style_solar
            },
        )
        clockView.prepareTransition(directionX = 0, directionY = step)
        clockView.flashBanner(label)
        lifecycleScope.launch {
            repository.update { current ->
                val updated = ThemeEditing.withFaceStyle(current, nextStyle, System.currentTimeMillis())
                ThemeEditing.upsert(current, updated)
            }
        }
    }

    private fun cycleIosPalette(step: Int) {
        val palettes = PalettePresets.groupIos
        val currentTheme = settings.currentTheme()
        val idx = palettes.indexOfFirst {
            it.background.equals(currentTheme.background, ignoreCase = true) &&
                it.primary.equals(currentTheme.primary, ignoreCase = true) &&
                it.accent.equals(currentTheme.accent, ignoreCase = true)
        }.let { if (it < 0) 0 else it }
        val nextPalette = palettes[(idx + step + palettes.size) % palettes.size]
        clockView.prepareTransition(directionX = step, directionY = 0)
        clockView.flashBanner(nextPalette.name)
        lifecycleScope.launch {
            repository.update { current ->
                val updated = ThemeEditing.withPaletteKeepingStyle(current, nextPalette, System.currentTimeMillis())
                ThemeEditing.upsert(current, updated)
            }
        }
    }

    private fun baselineState(): RefreshState = when {
        powerController.isPowerSaveMode() -> RefreshState.ULTRA_LOW_POWER
        settings.mode == DisplayMode.ULTRA_LOW_POWER -> RefreshState.ULTRA_LOW_POWER
        settings.mode == DisplayMode.SHOWCASE -> RefreshState.INTERACTIVE
        else -> RefreshState.STATIC
    }

    private fun applyRefreshIntent(state: RefreshState) {
        val powerSave = powerController.isPowerSaveMode()
        val showSeconds = if (powerSave) {
            false
        } else {
            settings.showSeconds ?: settings.currentTheme().showSeconds
        }
        val intent = RefreshRatePolicy.intentFor(state, showSeconds)
        RefreshRatePolicy.apply(window, displayController, intent)

        runCatching {
            if (Build.VERSION.SDK_INT >= 35) clockView.setRequestedFrameRate(intent.targetFps)
        }
    }

    private fun setKeepScreenOn(charging: Boolean) {
        if (charging) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private companion object {
        const val IDLE_TIMEOUT_MS = 8_000L
        const val ALERT_VISIBLE_MS = 7_500L
        const val DOUBLE_TAP_WINDOW_MS = 600L
        const val SWIPE_THRESHOLD_PX = 96f
        const val TAP_SLOP_PX = 56f
    }
}
