package app.luma.standby

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.luma.standby.domain.model.DisplayMode
import app.luma.standby.domain.model.FaceStyle
import app.luma.standby.domain.model.StoredPlacement
import app.luma.standby.domain.theme.ThemeEditing
import app.luma.standby.domain.theme.ThemeRepository
import app.luma.standby.ui.clock.DebugAlertBus
import app.luma.standby.ui.clock.StandbyNotificationAlert
import kotlinx.coroutines.runBlocking

/**
 * DEBUG BUILDS ONLY. Lets the power-test harness pin an exact configuration
 * without driving the UI with taps, so group A and group C differ only in the
 * parameter we mean to vary.
 *
 *   adb shell am broadcast --include-stopped-packages \
 *     -a app.luma.standby.DEBUG_SETTINGS \
 *     --es theme black-amber --ef brightness 0.15 --ez autoNight true \
 *     --ei nightStart 1320 --ei nightEnd 420
 *
 * The write is synchronous on purpose: the harness needs a trustworthy result
 * code, and a detached coroutine would let the broadcast report success before
 * DataStore had actually been updated. This is debug-only code; a few
 * milliseconds on the receiver thread is the right trade here.
 *
 * Result: resultCode 1 on success, 0 on failure, resultData carries "ok" or the
 * exception message.
 */
class DebugSettingsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val repository = ThemeRepository(context)
        val outcome = runCatching {
            runBlocking {
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
                        next = next.copy(
                            nightStartMinutes = intent.getIntExtra("nightStart", current.nightStartMinutes),
                        )
                    }
                    if (intent.hasExtra("nightEnd")) {
                        next = next.copy(
                            nightEndMinutes = intent.getIntExtra("nightEnd", current.nightEndMinutes),
                        )
                    }
                    if (intent.hasExtra("nightRamp")) {
                        next = next.copy(
                            nightRampMinutes = intent.getIntExtra("nightRamp", current.nightRampMinutes),
                        )
                    }
                    if (intent.hasExtra("burnIn")) {
                        next = next.copy(burnInShift = intent.getBooleanExtra("burnIn", current.burnInShift))
                    }
                    intent.getStringExtra("mode")?.let { name ->
                        val mode = runCatching { DisplayMode.valueOf(name) }.getOrNull()
                        if (mode != null) next = next.copy(mode = mode)
                    }
                    if (intent.hasExtra("showSeconds")) {
                        next = next.copy(showSeconds = intent.getBooleanExtra("showSeconds", false))
                    }
                    intent.getStringExtra("style")?.let { name ->
                        val style = runCatching { FaceStyle.valueOf(name.uppercase()) }.getOrNull()
                        if (style != null) {
                            val updated = ThemeEditing.withFaceStyle(next, style, System.currentTimeMillis())
                            next = ThemeEditing.upsert(next, updated)
                        }
                    }
                    intent.getStringExtra("widgets")?.let { raw ->
                        val list = if (raw.equals("none", ignoreCase = true) || raw.isBlank()) {
                            emptyList()
                        } else {
                            raw.split(",").mapNotNull { id ->
                                val clean = id.trim()
                                if (clean.isEmpty()) null else StoredPlacement(clean, "TOP_RIGHT")
                            }
                        }
                        next = next.copy(widgetPlacements = list)
                    }
                    if (intent.hasExtra("tapToReveal")) {
                        next = next.copy(notificationTapToReveal = intent.getBooleanExtra("tapToReveal", false))
                    }
                    next
                }
            }
            // In-memory debug-only notification preview (never persisted).
            intent.getStringExtra("mockAlert")?.let { raw ->
                val parts = raw.split("|")
                val appLabel = parts.getOrNull(0)?.trim().orEmpty().ifEmpty { "Messages" }
                val title = parts.getOrNull(1)?.trim().orEmpty().ifEmpty { appLabel }
                val body = parts.getOrNull(2)?.trim()?.ifEmpty { null }
                val pkg = intent.getStringExtra("mockPkg")
                val count = intent.getIntExtra("mockCount", 1).coerceAtLeast(1)
                val tapReveal = intent.getBooleanExtra("mockTapReveal", false)
                DebugAlertBus.emit(
                    StandbyNotificationAlert(
                        appLabel = appLabel,
                        title = title,
                        body = body,
                        packageName = pkg,
                        unreadCount = count,
                        tapToReveal = tapReveal,
                        revealed = false,
                        isPrivateRedacted = title.equals(appLabel, ignoreCase = true) && body == null,
                    ),
                )
            }
        }

        if (outcome.isSuccess) {
            setResultCode(1)
            setResultData("ok")
        } else {
            setResultCode(0)
            setResultData(outcome.exceptionOrNull()?.message ?: "error")
        }
    }
}
