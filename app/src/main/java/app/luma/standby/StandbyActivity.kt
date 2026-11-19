package app.luma.standby

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.luma.standby.domain.NightMode
import app.luma.standby.domain.model.DisplayMode
import app.luma.standby.domain.model.LumaSettings
import app.luma.standby.domain.theme.ThemeRepository
import app.luma.standby.domain.theme.currentTheme
import app.luma.standby.platform.battery.BatteryController
import app.luma.standby.platform.display.BrightnessController
import app.luma.standby.platform.display.DisplayController
import app.luma.standby.platform.display.RefreshRatePolicy
import app.luma.standby.platform.display.RefreshState
import app.luma.standby.platform.power.PowerController
import app.luma.standby.platform.widgets.WeatherStore
import app.luma.standby.platform.widgets.WidgetDataCollector
import app.luma.standby.platform.widgets.WidgetRenderer
import app.luma.standby.ui.clock.ClockView
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableSharedFlow
import app.luma.standby.platform.widgets.LumaNotificationListener

/**
 * The in-app standby surface. Works in whatever orientation the phone is held,
 * goes fullscreen and dim, and idles back to the static refresh state after a
 * few seconds of no touching.
 *
 * The screen is *not* kept awake forever: KEEP_SCREEN_ON is only added while a
 * charger is attached, which is exactly the StandBy use case.
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

    private val mainHandler = Handler(Looper.getMainLooper())
    private val returnToIdle = Runnable { applyRefreshIntent(baselineState()) }

    private var stopObservingBattery: (() -> Unit)? = null
    private var settings: LumaSettings = LumaSettings()
    private var lastTapAt = 0L
    private val widgetTicks = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var receiverRegistered = false
    private val screenEvents = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                LumaNotificationListener.setCollectionEnabled(false)
                clockView.setWidgets(emptyList())
            } else {
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    applySettings(settings)
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
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                launch {
                    repository.settings.collect { latest ->
                        settings = latest
                        applySettings(latest)
                        applyRefreshIntent(baselineState())
                        refreshWidgets()
                        widgetTicks.tryEmit(Unit)
                    }
                }
                launch { weatherStore.snapshot.collect { refreshWidgets() } }
                launch { LumaNotificationListener.changes.collect { refreshWidgets() } }
                launch {
                    widgetTicks.collectLatest {
                        weatherStore.update(
                            settings.mode != DisplayMode.ULTRA_LOW_POWER && settings.widgetPlacements.any { it.widgetId == "weather" },
                            settings.weatherLat, settings.weatherLon, settings.weatherCity,
                        )
                    }
                }
            }
        }

        // Hand-rolled double tap instead of GestureDetector: the detector's window
        // is 300 ms and it silently drops taps when the first one lands while the
        // window is still settling. A clock face has no other competing gestures.
        clockView.isClickable = true
        clockView.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                enterInteractive()
                val now = event.eventTime
                if (now - lastTapAt <= DOUBLE_TAP_WINDOW_MS) {
                    lastTapAt = 0L
                    clockView.performClick()
                    finish()
                } else {
                    lastTapAt = now
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
        enterInteractive()
    }

    override fun onPause() {
        mainHandler.removeCallbacks(returnToIdle)
        clockView.pauseTicking()
        clockView.setWidgets(emptyList())
        LumaNotificationListener.setCollectionEnabled(false)
        // The brightness override is window-scoped, but clear it explicitly so a
        // re-shown window never inherits a stale night value.
        brightnessController.restore(window)
        super.onPause()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(returnToIdle)
        stopObservingBattery?.invoke()
        stopObservingBattery = null
        if (receiverRegistered) unregisterReceiver(screenEvents)
        weatherStore.shutdown()
        super.onDestroy()
    }

    private fun applySettings(settings: LumaSettings) {
        val theme = settings.currentTheme()
        clockView.applyState(
            theme = theme,
            use24HourOverride = settings.use24Hour,
            // Battery saver silently drops seconds: a per-second redraw is exactly
            // the thing the user asked the platform to stop doing.
            showSecondsOverride = if (powerController.isPowerSaveMode() || settings.mode == DisplayMode.ULTRA_LOW_POWER) false else settings.showSeconds,
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
        // A hot panel is dimmed rather than left to bake; this is a mitigation,
        // not a thermal policy of our own.
        val thermalFactor = if (powerController.shouldDim()) 0.6f else 1f
        val target = if (requested < 0f) -1f else (requested * thermalFactor).coerceIn(0.01f, 1f)
        brightnessController.apply(window, target)
    }

    /**
     * Widget data is recomputed once per redraw, on the same minute boundary as the
     * clock. Notification access is the only expensive read and only happens when a
     * widget actually needs it.
     */
    private fun refreshWidgets() {
        val enabled = settings.widgetPlacements.map { it.widgetId }.toSet()
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        if (enabled.isEmpty()) {
            LumaNotificationListener.setCollectionEnabled(false)
            clockView.setWidgets(emptyList())
            return
        }

        val weather = if (enabled.contains("weather")) weatherStore.cached(settings.weatherLat, settings.weatherLon) else null

        val data = widgetCollector.collect(
            enabledIds = enabled,
            weather = weather,
        )
        clockView.setWidgets(WidgetRenderer.tiles(settings, data, context = this))
    }

    private fun applyBrightnessIfMinuteChanged() {
        if (currentMinuteOfDay() != lastBrightnessMinute) applyBrightness(settings)
    }

    private fun currentMinuteOfDay(): Int {
        val calendar = java.util.Calendar.getInstance()
        return calendar.get(java.util.Calendar.HOUR_OF_DAY) * 60 + calendar.get(java.util.Calendar.MINUTE)
    }

    /** A touch buys a short window of normal refresh rate, then we idle again. */
    private fun enterInteractive() {
        clockView.flashHint()
        applyRefreshIntent(RefreshState.INTERACTIVE)
        mainHandler.removeCallbacks(returnToIdle)
        mainHandler.postDelayed(returnToIdle, IDLE_TIMEOUT_MS)
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

        // Second, independent hint: the view-level request is the one SurfaceFlinger
        // records as the layer's requestedFrameRate. Measured on Xiaomi 17 /
        // HyperOS 4: the layer records 1.00 Hz, but the platform's per-uid rate
        // override stays at 60 Hz. See docs/display-api.md.
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

        /** Generous enough for a deliberate double tap, short enough not to fire on two separate taps. */
        const val DOUBLE_TAP_WINDOW_MS = 600L
    }
}