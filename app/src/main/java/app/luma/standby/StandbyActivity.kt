package app.luma.standby

import android.graphics.Color
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        clockView = ClockView(this)
        clockView.setBackgroundColor(Color.BLACK)
        setContentView(clockView)

        setShowWhenLocked(true)
        setTurnScreenOn(true)
        hideSystemBars()

        // Live updates: changing theme/brightness/mode in the app while the clock
        // is on screen should take effect without relaunching the activity.
        // STARTED-scoped: a paused clock must not keep applying window brightness.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.settings.collect { latest ->
                    settings = latest
                    applySettings(latest)
                    refreshWidgets()
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
            applyBrightnessIfMinuteChanged()
            refreshWidgets()
        }

        stopObservingBattery = batteryController.observe { snapshot ->
            setKeepScreenOn(snapshot.charging)
        }
        setKeepScreenOn(batteryController.snapshot().charging)
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        enterInteractive()
    }

    override fun onPause() {
        mainHandler.removeCallbacks(returnToIdle)
        // The brightness override is window-scoped, but clear it explicitly so a
        // re-shown window never inherits a stale night value.
        brightnessController.restore(window)
        super.onPause()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(returnToIdle)
        stopObservingBattery?.invoke()
        stopObservingBattery = null
        super.onDestroy()
    }

    private fun applySettings(settings: LumaSettings) {
        val theme = settings.currentTheme()
        clockView.applyState(
            theme = theme,
            use24HourOverride = settings.use24Hour,
            // Battery saver silently drops seconds: a per-second redraw is exactly
            // the thing the user asked the platform to stop doing.
            showSecondsOverride = if (powerController.isPowerSaveMode()) false else settings.showSeconds,
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
        if (enabled.isEmpty()) {
            clockView.setWidgets(emptyList())
            return
        }

        val weather = if (enabled.contains("weather")) {
            val lat = settings.weatherLat
            val lon = settings.weatherLon
            if (lat != null && lon != null) {
                val store = weatherStore
                val cached = store.cached(lat, lon)
                if (store.isStale() || cached == null) store.refresh(lat, lon, settings.weatherCity)
                cached
            } else {
                null
            }
        } else {
            null
        }

        val data = widgetCollector.collect(
            enabledIds = enabled,
            weather = weather,
        )
        clockView.setWidgets(WidgetRenderer.tiles(settings, data))
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
            clockView.setRequestedFrameRate(intent.targetFps)
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
