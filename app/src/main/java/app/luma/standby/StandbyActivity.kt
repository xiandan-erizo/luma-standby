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
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
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
import app.luma.standby.domain.widget.LiveActivityInfo
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
import app.luma.standby.ui.clock.DebugIslandBus
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
    private val returnToIdle = Runnable {
        if (!clockView.hasActiveMotion()) {
            applyRefreshIntent(baselineState())
        }
    }
    private val dismissAlertRunnable = Runnable { dismissNotificationAlert() }

    private var stopObservingBattery: (() -> Unit)? = null
    private var stopObservingMedia: (() -> Unit)? = null
    private var settings: LumaSettings = LumaSettings()
    private var lastTapAt = 0L
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var dragOriginX = 0f
    private var dragOriginY = 0f
    private var isTouchDragging = false
    private var touchSlopPx = 16f
    private var velocityTracker: VelocityTracker? = null
    private var touchDownOnUnreadPill = false
    private var touchDownOnLiveIsland = false
    private var swipeConsumed = false

    private val alertTracker = StandbyAlertTracker()
    private var activeAlert: StandbyNotificationAlert? = null
    private var latestNotifications: List<NotificationInfo> = emptyList()
    private var liveActivities: List<LiveActivityInfo> = emptyList()
    private var debugLiveActivities: List<LiveActivityInfo> = emptyList()
    private var selectedLiveIndex: Int = 0
    private var expandedLiveActivity: LiveActivityInfo? = null

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
                    syncMediaObserver()
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
        touchSlopPx = ViewConfiguration.get(this).scaledTouchSlop.toFloat().coerceIn(14f, 36f)
        clockView.onMotionActiveChanged = { moving ->
            if (moving) {
                mainHandler.removeCallbacks(returnToIdle)
                applyRefreshIntent(RefreshState.INTERACTIVE)
            } else {
                mainHandler.removeCallbacks(returnToIdle)
                mainHandler.postDelayed(returnToIdle, IDLE_TIMEOUT_MS)
            }
        }

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
                        syncMediaObserver()
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
                    launch {
                        DebugIslandBus.payloads.collect { payload ->
                            debugLiveActivities = payload.activities
                            liveActivities = payload.activities
                            selectedLiveIndex = 0
                            clockView.setLiveActivities(payload.activities, albumArt = null, selectedIndex = 0)
                            if (payload.expandImmediately && payload.activities.isNotEmpty()) {
                                expandCurrentLiveActivity()
                            } else if (payload.activities.isEmpty()) {
                                collapseLiveActivity()
                                refreshWidgets()
                            }
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
                    dragOriginX = event.x
                    dragOriginY = event.y
                    isTouchDragging = false
                    velocityTracker?.recycle()
                    velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
                    touchDownOnUnreadPill = activeAlert == null && expandedLiveActivity == null &&
                        clockView.hitTestUnreadPill(event.x, event.y)
                    touchDownOnLiveIsland = activeAlert == null && expandedLiveActivity == null &&
                        !touchDownOnUnreadPill && clockView.hitTestLiveIslandPill(event.x, event.y)
                    swipeConsumed = false
                    enterInteractive(flashHint = false)
                }

                MotionEvent.ACTION_MOVE -> {
                    velocityTracker?.addMovement(event)
                    if (!touchDownOnUnreadPill && !touchDownOnLiveIsland) {
                        val rawDx = event.x - touchDownX
                        val rawDy = event.y - touchDownY
                        if (!isTouchDragging && (abs(rawDx) > touchSlopPx || abs(rawDy) > touchSlopPx)) {
                            // Anchor drag origin at exact slop crossing for zero breakaway jump
                            isTouchDragging = true
                            dragOriginX = event.x
                            dragOriginY = event.y
                        }
                        if (isTouchDragging) {
                            clockView.setInteractiveDrag(event.x - dragOriginX, event.y - dragOriginY)
                        }
                    }
                }

                MotionEvent.ACTION_CANCEL -> {
                    touchDownOnUnreadPill = false
                    touchDownOnLiveIsland = false
                    isTouchDragging = false
                    velocityTracker?.recycle()
                    velocityTracker = null
                    clockView.releaseInteractiveDrag()
                }

                MotionEvent.ACTION_UP -> {
                    velocityTracker?.addMovement(event)
                    velocityTracker?.computeCurrentVelocity(1000, 6_000f)
                    val vx = velocityTracker?.xVelocity ?: 0f
                    val vy = velocityTracker?.yVelocity ?: 0f
                    velocityTracker?.recycle()
                    velocityTracker = null

                    val dx = event.x - touchDownX
                    val dy = event.y - touchDownY
                    val absDx = abs(dx)
                    val absDy = abs(dy)
                    val absVx = abs(vx)
                    val absVy = abs(vy)
                    val wasDragging = isTouchDragging
                    isTouchDragging = false

                    val currentAlert = activeAlert
                    val currentExpandedLive = expandedLiveActivity
                    val startedOnUnreadPill = touchDownOnUnreadPill
                    val startedOnLiveIsland = touchDownOnLiveIsland
                    touchDownOnUnreadPill = false
                    touchDownOnLiveIsland = false

                    val horizontalSwipe = (absDx > SWIPE_THRESHOLD_PX || (absVx > FLICK_VELOCITY_PX && absDx > FLICK_MIN_DIST_PX)) &&
                        absDx > absDy * 1.15f
                    val verticalSwipe = (absDy > SWIPE_THRESHOLD_PX || (absVy > FLICK_VELOCITY_PX && absDy > FLICK_MIN_DIST_PX)) &&
                        absDy > absDx * 1.15f
                    val isTap = absDx < TAP_SLOP_PX && absDy < TAP_SLOP_PX && absVx < 420f && absVy < 420f

                    if (currentAlert != null) {
                        // Notification takeover is visible:
                        // - Swipe or quick flick left/right clears permanently with thumb velocity handoff.
                        // - Swipe or quick flick up/down dissolves calmly back to clock.
                        // - Partial drag springs smoothly back to center with exact release momentum.
                        if (horizontalSwipe) {
                            swipeConsumed = true
                            lastTapAt = 0L
                            tickHaptic()
                            clearCurrentNotification(
                                exitMode = if (dx > 0f) AlertExitMode.FLING_RIGHT else AlertExitMode.FLING_LEFT,
                                velocityX = vx,
                                velocityY = vy,
                            )
                        } else if (verticalSwipe) {
                            swipeConsumed = true
                            lastTapAt = 0L
                            dismissNotificationAlert(AlertExitMode.TUCK_TO_PILL, vx, vy)
                        } else if (isTap) {
                            clockView.releaseInteractiveDrag(vx, vy)
                            lastTapAt = 0L
                            when {
                                clockView.hitTestAlertClear(event.x, event.y) -> {
                                    tickHaptic()
                                    clearCurrentNotification(AlertExitMode.CLEAR_DISSOLVE)
                                }
                                clockView.hitTestAlertClearAll(event.x, event.y) -> {
                                    tickHaptic()
                                    clearAllNotifications(AlertExitMode.CLEAR_DISSOLVE)
                                }
                                currentAlert.tapToReveal && !currentAlert.revealed && !currentAlert.isPrivateRedacted -> {
                                    tickHaptic()
                                    showNotificationAlert(currentAlert.copy(revealed = true))
                                }
                                clockView.canExpandAlertDetailNow() -> {
                                    tickHaptic()
                                    enterInteractive(flashHint = false)
                                    clockView.expandAlertDetailNow()
                                    mainHandler.removeCallbacks(dismissAlertRunnable)
                                    mainHandler.postDelayed(dismissAlertRunnable, ALERT_VISIBLE_MS)
                                }
                                else ->
                                    dismissNotificationAlert(AlertExitMode.TUCK_TO_PILL)
                            }
                        } else {
                            clockView.releaseInteractiveDrag(vx, vy)
                        }
                    } else if (currentExpandedLive != null) {
                        if (horizontalSwipe) {
                            swipeConsumed = true
                            lastTapAt = 0L
                            clockView.releaseInteractiveDrag(vx, vy)
                            tickHaptic()
                            if (liveActivities.size > 1) {
                                cycleLiveActivity(step = if (dx < 0f) 1 else -1, keepExpanded = true)
                            } else {
                                collapseLiveActivity()
                            }
                        } else if (verticalSwipe) {
                            swipeConsumed = true
                            lastTapAt = 0L
                            collapseLiveActivity()
                        } else if (isTap) {
                            clockView.releaseInteractiveDrag(vx, vy)
                            lastTapAt = 0L
                            when {
                                clockView.hitTestLiveCardCycle(event.x, event.y) -> {
                                    tickHaptic()
                                    cycleLiveActivity(step = 1, keepExpanded = true)
                                }
                                clockView.hitTestLiveCardCollapse(event.x, event.y) -> {
                                    tickHaptic()
                                    collapseLiveActivity()
                                }
                                else ->
                                    collapseLiveActivity()
                            }
                        } else {
                            clockView.releaseInteractiveDrag(vx, vy)
                        }
                    } else if (startedOnLiveIsland) {
                        lastTapAt = 0L
                        if (horizontalSwipe && liveActivities.size > 1) {
                            swipeConsumed = true
                            tickHaptic()
                            cycleLiveActivity(step = if (dx < 0f) 1 else -1, keepExpanded = false)
                        } else if (isTap) {
                            tickHaptic()
                            expandCurrentLiveActivity()
                        }
                    } else if (startedOnUnreadPill) {
                        lastTapAt = 0L
                        if (horizontalSwipe || verticalSwipe) {
                            swipeConsumed = true
                            tickHaptic()
                            clearAllNotifications(AlertExitMode.CLEAR_DISSOLVE)
                            clockView.flashBanner(getString(R.string.notif_cleared_banner))
                        } else if (isTap) {
                            tickHaptic()
                            openLatestNotificationAlert()
                        }
                    } else if (verticalSwipe && absDy > absDx * 1.25f) {
                        swipeConsumed = true
                        lastTapAt = 0L
                        tickHaptic()
                        cycleFaceStyle(step = if (dy < 0f) 1 else -1, velocityX = vx, velocityY = vy)
                    } else if (horizontalSwipe && absDx > absDy * 1.25f) {
                        swipeConsumed = true
                        lastTapAt = 0L
                        tickHaptic()
                        cycleIosPalette(step = if (dx < 0f) 1 else -1, velocityX = vx, velocityY = vy)
                    } else if (!swipeConsumed && isTap) {
                        clockView.releaseInteractiveDrag(vx, vy)
                        when {
                            clockView.hitTestLiveIslandPill(event.x, event.y) -> {
                                lastTapAt = 0L
                                tickHaptic()
                                expandCurrentLiveActivity()
                            }
                            clockView.hitTestUnreadPill(event.x, event.y) -> {
                                lastTapAt = 0L
                                tickHaptic()
                                openLatestNotificationAlert()
                            }
                            else -> {
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
                        }
                    } else {
                        clockView.releaseInteractiveDrag(vx, vy)
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
        syncMediaObserver()
        enterInteractive(flashHint = false)
    }

    override fun onPause() {
        mainHandler.removeCallbacks(returnToIdle)
        clockView.pauseTicking()
        headsUpSuppressor.restore()
        stopObservingMedia?.invoke()
        stopObservingMedia = null
        clearSensitiveState()
        // The brightness override is window-scoped, but clear it explicitly so a
        // re-shown window never inherits a stale night value.
        brightnessController.restore(window)
        super.onPause()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(returnToIdle)
        mainHandler.removeCallbacks(dismissAlertRunnable)
        velocityTracker?.recycle()
        velocityTracker = null
        headsUpSuppressor.restore()
        stopObservingMedia?.invoke()
        stopObservingMedia = null
        stopObservingBattery?.invoke()
        stopObservingBattery = null
        if (receiverRegistered) unregisterReceiver(screenEvents)
        weatherStore.shutdown()
        super.onDestroy()
    }

    private fun syncHeadsUpSuppression() {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
            settings.notificationAlerts &&
            settings.mode != DisplayMode.ULTRA_LOW_POWER &&
            settings.suppressSystemHeadsUp
        ) {
            headsUpSuppressor.suppress()
        } else {
            headsUpSuppressor.restore()
        }
    }

    private fun syncMediaObserver() {
        stopObservingMedia?.invoke()
        stopObservingMedia = null
        val wantMedia = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
            settings.mode != DisplayMode.ULTRA_LOW_POWER &&
            (settings.liveActivities || settings.widgetPlacements.any { it.widgetId == "media" })
        if (wantMedia) {
            stopObservingMedia = widgetCollector.observeMedia(enabled = true) {
                refreshWidgets()
            }
        }
    }

    private fun clearSensitiveState() {
        mainHandler.removeCallbacks(dismissAlertRunnable)
        stopObservingMedia?.invoke()
        stopObservingMedia = null
        activeAlert = null
        latestNotifications = emptyList()
        liveActivities = emptyList()
        debugLiveActivities = emptyList()
        selectedLiveIndex = 0
        expandedLiveActivity = null
        alertTracker.reset()
        LumaNotificationListener.setPreviewEnabled(false)
        LumaNotificationListener.setLiveActivitiesEnabled(false)
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
        val liveWanted = settings.liveActivities && settings.mode != DisplayMode.ULTRA_LOW_POWER

        if (enabled.isEmpty() && !alertsWanted && !liveWanted) {
            LumaNotificationListener.setLiveActivitiesEnabled(false)
            LumaNotificationListener.setCollectionEnabled(false)
            liveActivities = emptyList()
            expandedLiveActivity = null
            clockView.setWidgets(emptyList())
            clockView.setUnreadSummary(null)
            clockView.setLiveActivities(emptyList())
            return
        }

        val weather = if (enabled.contains("weather")) weatherStore.cached(settings.weatherLat, settings.weatherLon) else null
        val data = widgetCollector.collect(
            enabledIds = enabled,
            weather = weather,
            notificationAlertsEnabled = alertsWanted,
            notificationPreviewEnabled = settings.notificationPreview,
            liveActivitiesEnabled = liveWanted,
        )
        clockView.setWidgets(WidgetRenderer.tiles(settings, data, context = this))

        if (liveWanted) {
            val bundle = widgetCollector.collectLiveActivities(enabled = true)
            val effective = if (bundle.activities.isNotEmpty()) bundle.activities else debugLiveActivities
            liveActivities = effective
            selectedLiveIndex = if (effective.isEmpty()) 0 else selectedLiveIndex.coerceIn(0, effective.lastIndex)
            clockView.setLiveActivities(effective, bundle.musicAlbumArt, selectedLiveIndex)
            val currentExp = expandedLiveActivity
            if (currentExp != null) {
                val updated = effective.firstOrNull { it.key == currentExp.key }
                if (updated != null) {
                    expandedLiveActivity = updated
                } else {
                    collapseLiveActivity()
                }
            }
        } else {
            liveActivities = emptyList()
            selectedLiveIndex = 0
            collapseLiveActivity()
            clockView.setLiveActivities(emptyList())
        }

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
            if (activeAlert?.key?.startsWith("debug-") == true) return
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
        collapseLiveActivity()
        showNotificationAlert(buildAlert(top, latestNotifications.size, expandImmediately = true))
    }

    private fun expandCurrentLiveActivity() {
        val target = liveActivities.getOrNull(selectedLiveIndex) ?: liveActivities.firstOrNull() ?: return
        enterInteractive(flashHint = false)
        dismissNotificationAlert(AlertExitMode.TUCK_TO_PILL)
        expandedLiveActivity = target
        clockView.setExpandedLiveActivity(target)
    }

    private fun collapseLiveActivity() {
        if (expandedLiveActivity != null) {
            enterInteractive(flashHint = false)
            expandedLiveActivity = null
            clockView.setExpandedLiveActivity(null)
        }
    }

    private fun cycleLiveActivity(step: Int, keepExpanded: Boolean) {
        if (liveActivities.size <= 1) return
        val size = liveActivities.size
        selectedLiveIndex = ((selectedLiveIndex + step) % size + size) % size
        val target = liveActivities[selectedLiveIndex]
        val art = if (target.isPlayingMedia) {
            widgetCollector.collectLiveActivities(enabled = true).musicAlbumArt
        } else null
        clockView.setLiveActivities(liveActivities, art, selectedLiveIndex)
        if (keepExpanded) {
            expandedLiveActivity = target
            clockView.setExpandedLiveActivity(target)
        }
        enterInteractive(flashHint = false)
    }

    private fun buildAlert(
        info: NotificationInfo,
        count: Int,
        expandImmediately: Boolean = false,
    ): StandbyNotificationAlert {
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
            expandImmediately = expandImmediately,
        )
    }

    private fun showNotificationAlert(alert: StandbyNotificationAlert) {
        // Pre-warm 120Hz LTPO BEFORE starting the alert animation so frame 1 never drops!
        enterInteractive(flashHint = false)
        collapseLiveActivity()
        activeAlert = alert
        clockView.setActiveAlert(alert)
        mainHandler.removeCallbacks(dismissAlertRunnable)
        mainHandler.postDelayed(dismissAlertRunnable, ALERT_VISIBLE_MS)
    }

    private fun dismissNotificationAlert(
        exitMode: AlertExitMode = AlertExitMode.TUCK_TO_PILL,
        velocityX: Float = 0f,
        velocityY: Float = 0f,
    ) {
        mainHandler.removeCallbacks(dismissAlertRunnable)
        if (activeAlert != null) {
            enterInteractive(flashHint = false)
            activeAlert = null
            clockView.setActiveAlert(null, exitMode, velocityX, velocityY)
        }
    }

    private fun clearCurrentNotification(
        exitMode: AlertExitMode,
        velocityX: Float = 0f,
        velocityY: Float = 0f,
    ) {
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
        dismissNotificationAlert(exitMode, velocityX, velocityY)
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

    /** Feather-light X-axis linear motor micro-tick on direct user gestures (never on passive alerts). */
    private fun tickHaptic() {
        runCatching { clockView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
    }

    private fun cycleFaceStyle(step: Int, velocityX: Float = 0f, velocityY: Float = 0f) {
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
                FaceStyle.POSTER -> R.string.style_poster
            },
        )
        clockView.prepareTransition(directionX = 0, directionY = step, velocityX = velocityX, velocityY = velocityY)
        clockView.flashBanner(label)
        lifecycleScope.launch {
            repository.update { current ->
                val updated = ThemeEditing.withFaceStyle(current, nextStyle, System.currentTimeMillis())
                ThemeEditing.upsert(current, updated)
            }
        }
    }

    private fun cycleIosPalette(step: Int, velocityX: Float = 0f, velocityY: Float = 0f) {
        val currentTheme = settings.currentTheme()
        val palettes = if (currentTheme.faceStyle == FaceStyle.POSTER) {
            PalettePresets.groupPoster
        } else {
            PalettePresets.groupIos
        }
        val idx = palettes.indexOfFirst {
            it.background.equals(currentTheme.background, ignoreCase = true) &&
                it.primary.equals(currentTheme.primary, ignoreCase = true) &&
                it.accent.equals(currentTheme.accent, ignoreCase = true)
        }.let { if (it < 0) 0 else it }
        val nextPalette = palettes[(idx + step + palettes.size) % palettes.size]
        clockView.prepareTransition(directionX = step, directionY = 0, velocityX = velocityX, velocityY = velocityY)
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
        val effectiveState = if (::clockView.isInitialized && clockView.hasActiveMotion()) {
            RefreshState.INTERACTIVE
        } else {
            state
        }
        val powerSave = powerController.isPowerSaveMode()
        val showSeconds = if (powerSave) {
            false
        } else {
            settings.showSeconds ?: settings.currentTheme().showSeconds
        }
        val intent = RefreshRatePolicy.intentFor(effectiveState, showSeconds)
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
        const val SWIPE_THRESHOLD_PX = 92f
        const val FLICK_VELOCITY_PX = 680f
        const val FLICK_MIN_DIST_PX = 34f
        const val TAP_SLOP_PX = 56f
    }
}
