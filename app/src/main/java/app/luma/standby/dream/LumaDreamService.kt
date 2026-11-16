package app.luma.standby.dream

import android.graphics.Color
import android.service.dreams.DreamService
import app.luma.standby.domain.model.DisplayMode
import app.luma.standby.domain.theme.ThemeRepository
import app.luma.standby.domain.theme.currentTheme
import app.luma.standby.ui.clock.ClockView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The only *system-sanctioned* way to appear automatically while charging: the
 * user picks Luma in Settings > Display > Screen saver, and the platform starts
 * this DreamService when the device is docked/idle/charging.
 *
 * Nothing here forces a full-screen Activity from a broadcast - Android does not
 * allow that, and we do not pretend otherwise.
 *
 * Hardening note: everything that touches the platform is individually guarded.
 * A dream runs inside a window the system owns, and a thrown exception here is
 * at best a black dream and at worst a restart of the user's UI. We would rather
 * show a plain black face than take the phone down with us.
 */
class LumaDreamService : DreamService() {

    private var clockView: ClockView? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val repository by lazy { ThemeRepository(applicationContext) }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        runCatching {
            setFullscreen(true)
            setScreenBright(false)
            setInteractive(false)
        }

        val view = ClockView(this)
        view.setBackgroundColor(Color.BLACK)
        clockView = view
        runCatching { setContentView(view) }

        scope.launch {
            runCatching {
                val settings = repository.current()
                view.applyState(
                    theme = settings.currentTheme(),
                    use24HourOverride = settings.use24Hour,
                    showSecondsOverride = settings.showSeconds,
                    burnInShift = settings.burnInShift,
                    hint = null,
                )
                applyDreamBrightness(settings.brightness)
                setInteractive(settings.mode == DisplayMode.SHOWCASE)
            }
        }
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        runCatching { clockView?.resumeTicking() }
    }

    override fun onDreamingStopped() {
        runCatching { clockView?.pauseTicking() }
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        clockView = null
        scope.cancel()
        super.onDetachedFromWindow()
    }

    /**
     * Only the brightness scalar. We deliberately do not touch window flags here:
     * a dream window is already kept awake by the system, and every extra
     * WindowManager mutation is another way to upset an OEM's dream stack.
     */
    private fun applyDreamBrightness(brightness: Float) {
        val window = window ?: return
        if (brightness < 0f) return
        val target = brightness.coerceIn(0.01f, 1f)
        runCatching {
            val attrs = window.attributes
            attrs.screenBrightness = target
            window.attributes = attrs
        }
    }
}
