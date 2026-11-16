package app.luma.standby.ui.clock

import android.os.Handler
import android.os.Looper

/**
 * Wakes only on the boundary it needs: next minute (or next second). Never a
 * fixed 1s ticker, never a Choreographer loop, never an AlarmManager alarm.
 */
class MinuteScheduler(private val onTick: () -> Unit) {

    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    var secondsEnabled: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (running) reschedule()
        }

    private val runnable = object : Runnable {
        override fun run() {
            onTick()
            scheduleNext()
        }
    }

    fun start() {
        if (running) return
        running = true
        scheduleNext()
    }

    fun stop() {
        running = false
        handler.removeCallbacks(runnable)
    }

    /** Call after a time/timezone change so the boundary is recomputed. */
    fun reschedule() {
        handler.removeCallbacks(runnable)
        if (running) scheduleNext()
    }

    private fun scheduleNext() {
        val now = System.currentTimeMillis()
        handler.postDelayed(runnable, ClockTiming.delayToNextBoundary(now, secondsEnabled))
    }
}
