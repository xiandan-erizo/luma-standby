package app.luma.standby.domain

/**
 * OLED burn-in mitigation: a tiny deterministic walk over a fixed set of
 * positions, one step every [intervalMinutes].
 *
 * Design constraints from the product spec:
 * * 1-2 physical pixels, so it is invisible on a 460+ dpi panel.
 * * minutes, not seconds: moving is a power cost and a visual tic.
 * * never the same position twice in a row, and no fixed direction pattern that
 *   could itself become a visible streak.
 *
 * Pure function of elapsed minutes so it can be unit-tested and so a device that
 * reboots lands on the same offsets instead of jumping.
 */
object BurnInDrift {

    /** Offsets in pixels, symmetric, no two neighbours identical. */
    private val STEPS: List<Pair<Float, Float>> = listOf(
        0f to 0f,
        1f to 0f,
        1f to 1f,
        2f to 1f,
        2f to 2f,
        1f to 2f,
        0f to 2f,
        -1f to 2f,
        -2f to 1f,
        -2f to 0f,
        -1f to -1f,
        0f to -1f,
    )

    const val DEFAULT_INTERVAL_MINUTES = 20

    fun offsetFor(
        elapsedMinutes: Long,
        amplitudePx: Float = 2f,
        intervalMinutes: Int = DEFAULT_INTERVAL_MINUTES,
    ): Pair<Float, Float> {
        if (elapsedMinutes <= 0L) return 0f to 0f
        val step = intervalMinutes.coerceAtLeast(1)
        val index = ((elapsedMinutes / step) % STEPS.size).toInt()
        val scale = (amplitudePx / 2f).coerceIn(0f, 2f)
        val raw = STEPS[index]
        return Pair(raw.first * scale, raw.second * scale)
    }

    fun stepCount(): Int = STEPS.size
}
