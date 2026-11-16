package app.luma.standby.platform.display

import android.os.Handler
import android.os.Looper

data class RefreshSample(
    val durationMs: Long,
    val samples: Int,
    val minHz: Float,
    val maxHz: Float,
    val avgHz: Float,
    val histogram: List<Pair<Float, Int>>,
)

/**
 * Diagnostic only: polls Display.getRefreshRate() while the user watches. This is
 * deliberately NOT part of the standby path - it keeps the device busy and would
 * defeat the point of a static face.
 */
class RefreshRateSampler(private val controller: DisplayController) {

    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var values = mutableListOf<Float>()

    fun isRunning(): Boolean = running

    fun start(
        durationMs: Long,
        intervalMs: Long = 500L,
        onProgress: (Float) -> Unit,
        onDone: (RefreshSample?) -> Unit,
    ) {
        stop()
        running = true
        values = mutableListOf()
        val startedAt = System.currentTimeMillis()

        val poll = object : Runnable {
            override fun run() {
                if (!running) return
                val hz = controller.snapshot()?.currentRefreshRate
                if (hz != null && hz > 0f) values.add(hz)

                val elapsed = System.currentTimeMillis() - startedAt
                onProgress((elapsed.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f))

                if (elapsed >= durationMs) {
                    running = false
                    onDone(summarize(durationMs))
                } else {
                    handler.postDelayed(this, intervalMs)
                }
            }
        }
        handler.postDelayed(poll, intervalMs)
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
    }

    private fun summarize(durationMs: Long): RefreshSample? {
        if (values.isEmpty()) return null
        val min = values.min()
        val max = values.max()
        val avg = values.sum() / values.size
        val histogram = values.groupingBy { it }.eachCount().toList().sortedBy { it.first }
        return RefreshSample(
            durationMs = durationMs,
            samples = values.size,
            minHz = min,
            maxHz = max,
            avgHz = avg,
            histogram = histogram,
        )
    }
}
