package app.luma.standby.platform.display

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.Surface
import android.view.Window

data class DisplayModeInfo(
    val id: Int,
    val width: Int,
    val height: Int,
    val refreshRate: Float,
)

data class DisplaySnapshot(
    val width: Int,
    val height: Int,
    val densityDpi: Int,
    val rotation: Int,
    val state: Int,
    val currentRefreshRate: Float,
    val supportedRefreshRates: List<Float>,
    val modes: List<DisplayModeInfo>,
    val arrSupported: Boolean?,
    val suggestedFrameRate: Float?,
)

/**
 * Thin, read-mostly wrapper over DisplayManager. Everything here is a *request*
 * or an observation, never a guarantee: the panel decides.
 */
class DisplayController(context: Context) {

    private val densityDpi: Int = context.resources.displayMetrics.densityDpi
    private val displayManager: DisplayManager? =
        context.getSystemService(DisplayManager::class.java)

    fun defaultDisplay(): Display? = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)

    fun snapshot(): DisplaySnapshot? {
        val display = defaultDisplay() ?: return null
        val modes = display.supportedModes.map {
            DisplayModeInfo(
                id = it.modeId,
                width = it.physicalWidth,
                height = it.physicalHeight,
                refreshRate = it.refreshRate,
            )
        }
        // The platform list can repeat a rate (Xiaomi returns 90 twice); dedupe
        // and sort so the diagnostics page reads like a capability list.
        val refreshRates: List<Float> = if (Build.VERSION.SDK_INT >= 36) {
            display.supportedRefreshRates?.toList().orEmpty()
                .ifEmpty { modes.map { m -> m.refreshRate } }
                .map { roundToHundredths(it) }
                .distinct()
                .sortedDescending()
        } else {
            modes.map { m -> roundToHundredths(m.refreshRate) }.distinct().sortedDescending()
        }
        val arr: Boolean? = if (Build.VERSION.SDK_INT >= 36) display.hasArrSupport() else null
        // getSuggestedFrameRate(int) takes a content category: NORMAL for a clock,
        // HIGH for video-like content we never render.
        val suggested: Float? = if (Build.VERSION.SDK_INT >= 36) {
            display.getSuggestedFrameRate(Display.FRAME_RATE_CATEGORY_NORMAL)
        } else {
            null
        }

        return DisplaySnapshot(
            width = display.mode?.physicalWidth ?: 0,
            height = display.mode?.physicalHeight ?: 0,
            densityDpi = densityDpi,
            rotation = display.rotation,
            state = display.state,
            currentRefreshRate = display.refreshRate,
            supportedRefreshRates = refreshRates,
            modes = modes,
            arrSupported = arr,
            suggestedFrameRate = suggested,
        )
    }

    /**
     * Window-level intent. Android may ignore it, throttle it, or round it to a
     * supported mode; we log what actually happened via the DisplayListener.
     */
    fun requestPreferredRefreshRate(window: Window?, fps: Float) {
        if (window == null) return
        runCatching {
            val attrs = window.attributes
            if (attrs.preferredRefreshRate != fps) {
                attrs.preferredRefreshRate = fps
                window.attributes = attrs
            }
        }
    }

    /**
     * Surface-level intent for views that own a SurfaceView. Only meaningful
     * when the Surface is valid; CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS keeps the
     * system from producing an ugly mode-set flash on a static clock.
     */
    fun applySurfaceFrameRate(surface: Surface?, fps: Float) {
        if (surface == null || !surface.isValid) return
        if (Build.VERSION.SDK_INT < 31) return
        runCatching {
            surface.setFrameRate(
                fps,
                Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS,
            )
        }
    }

    private fun roundToHundredths(value: Float): Float =
        kotlin.math.round(value * 100f) / 100f

    fun registerDisplayListener(listener: DisplayManager.DisplayListener) {
        displayManager?.registerDisplayListener(listener, null)
    }

    fun unregisterDisplayListener(listener: DisplayManager.DisplayListener) {
        displayManager?.unregisterDisplayListener(listener)
    }
}
