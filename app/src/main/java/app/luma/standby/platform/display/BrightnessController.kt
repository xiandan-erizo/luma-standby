package app.luma.standby.platform.display

import android.content.Context
import android.provider.Settings
import android.view.Window

/**
 * Window-scoped brightness only. We never write Settings.System: the user's
 * global brightness belongs to the user, and an app that silently changes it is
 * indistinguishable from a bug.
 */
class BrightnessController(private val context: Context) {

    /**
     * Current system brightness scaled to 0..1, or null when it cannot be read.
     * Only used as the "day" end of a progressive night ramp.
     */
    fun systemBrightness01(): Float? {
        val raw = runCatching {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1)
        }.getOrDefault(-1)
        if (raw < 0) return null
        return (raw / 255f).coerceIn(0f, 1f)
    }

    /** -1 restores "follow the system" for this window. */
    fun apply(window: Window?, value: Float) {
        if (window == null) return
        val target = if (value < 0f) -1f else value.coerceIn(0.01f, 1f)
        runCatching {
            val attrs = window.attributes
            if (attrs.screenBrightness == target) return
            attrs.screenBrightness = target
            window.attributes = attrs
        }
    }

    fun restore(window: Window?) {
        apply(window, -1f)
    }
}
