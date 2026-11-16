package app.luma.standby.ui.clock

/**
 * Pure arithmetic behind the redraw schedule, kept free of Handler/Context so
 * it can be unit-tested on the JVM.
 */
object ClockTiming {

    const val SECOND_MILLIS = 1_000L
    const val MINUTE_MILLIS = 60_000L

    /** Small guard so the draw lands just *after* the boundary, never just before it. */
    const val GUARD_MILLIS = 40L

    fun delayToNextBoundary(now: Long, secondsEnabled: Boolean): Long =
        if (secondsEnabled) {
            SECOND_MILLIS - (now % SECOND_MILLIS) + 20L
        } else {
            MINUTE_MILLIS - (now % MINUTE_MILLIS) + GUARD_MILLIS
        }
}
