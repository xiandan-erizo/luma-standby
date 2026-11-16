package app.luma.standby.platform.display

import android.view.Window

/** The three display states from the PRD, section 5.3. */
enum class RefreshState {
    INTERACTIVE,
    STATIC,
    ULTRA_LOW_POWER,
}

/**
 * Everything we intend to ask the platform for, plus how often the app itself
 * should redraw. The draw interval is the part we truly control.
 */
data class RefreshIntent(
    val state: RefreshState,
    val targetFps: Float,
    val drawIntervalMillis: Long,
)

object RefreshRatePolicy {

    fun intentFor(state: RefreshState, showSeconds: Boolean): RefreshIntent = when (state) {
        RefreshState.INTERACTIVE -> RefreshIntent(
            state = state,
            targetFps = 120f,
            drawIntervalMillis = if (showSeconds) 1_000L else 60_000L,
        )

        RefreshState.STATIC -> RefreshIntent(
            state = state,
            targetFps = 1f,
            drawIntervalMillis = if (showSeconds) 1_000L else 60_000L,
        )

        RefreshState.ULTRA_LOW_POWER -> RefreshIntent(
            state = state,
            targetFps = 1f,
            drawIntervalMillis = 60_000L,
        )
    }

    fun apply(window: Window?, controller: DisplayController, intent: RefreshIntent) {
        controller.requestPreferredRefreshRate(window, intent.targetFps)
    }
}
