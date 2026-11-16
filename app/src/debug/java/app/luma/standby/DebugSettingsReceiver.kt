package app.luma.standby

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.luma.standby.domain.model.DisplayMode
import app.luma.standby.domain.theme.ThemeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * DEBUG BUILDS ONLY. Lets the power-test harness pin an exact configuration
 * without driving the UI with taps, so group A and group C differ only in the
 * parameter we mean to vary.
 *
 *   adb shell am broadcast -a app.luma.standby.DEBUG_SETTINGS \
 *     --es theme black-amber --ef brightness 0.15 --ez autoNight true \
 *     --ei nightStart 1320 --ei nightEnd 420
 */
class DebugSettingsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val repository = ThemeRepository(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            repository.update { current ->
                var next = current
                intent.getStringExtra("theme")?.let { next = next.copy(themeId = it) }
                if (intent.hasExtra("brightness")) {
                    next = next.copy(brightness = intent.getFloatExtra("brightness", current.brightness))
                }
                if (intent.hasExtra("nightBrightness")) {
                    next = next.copy(
                        nightBrightness = intent.getFloatExtra("nightBrightness", current.nightBrightness),
                    )
                }
                if (intent.hasExtra("autoNight")) {
                    next = next.copy(autoNight = intent.getBooleanExtra("autoNight", current.autoNight))
                }
                if (intent.hasExtra("nightStart")) {
                    next = next.copy(nightStartMinutes = intent.getIntExtra("nightStart", current.nightStartMinutes))
                }
                if (intent.hasExtra("nightEnd")) {
                    next = next.copy(nightEndMinutes = intent.getIntExtra("nightEnd", current.nightEndMinutes))
                }
                intent.getStringExtra("mode")?.let { name ->
                    val mode = runCatching { DisplayMode.valueOf(name) }.getOrNull()
                    if (mode != null) next = next.copy(mode = mode)
                }
                if (intent.hasExtra("showSeconds")) {
                    next = next.copy(showSeconds = intent.getBooleanExtra("showSeconds", false))
                }
                next
            }
        }
    }
}
