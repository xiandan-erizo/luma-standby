package app.luma.standby.ui.clock

/**
 * The face's proportions in one place, as pure arithmetic.
 *
 * Keeping the numbers here means the layout contract (date under the time, hint
 * under the date, nothing wider than the view) can be unit-tested on the JVM
 * without a device or a Robolectric setup.
 */
object ClockTypography {

    const val PORTRAIT_WIDTH_FRACTION = 0.86f
    const val LANDSCAPE_WIDTH_FRACTION = 0.78f

    fun isPortrait(width: Float, height: Float): Boolean = height > width

    fun maxWidthFraction(portrait: Boolean): Float =
        if (portrait) PORTRAIT_WIDTH_FRACTION else LANDSCAPE_WIDTH_FRACTION

    fun timeTextSize(height: Float): Float = height * 0.42f

    fun timeBaseline(height: Float): Float = height * 0.54f

    fun dateTextSize(height: Float): Float = height * 0.042f

    fun dateBaseline(height: Float, portrait: Boolean): Float =
        if (portrait) height * 0.665f else height * 0.685f

    fun hintTextSize(height: Float): Float = height * 0.020f

    fun hintBaseline(height: Float): Float = height * 0.90f

    fun suffixScale(): Float = 0.16f

    fun suffixGapScale(): Float = 0.10f

    /**
     * Scale factor needed to fit [measured] into [maxWidth]. 1 means it already
     * fits; the caller multiplies its text size by the result.
     */
    fun shrinkFactor(measured: Float, maxWidth: Float): Float {
        if (measured <= 0f || maxWidth <= 0f) return 1f
        return if (measured > maxWidth) maxWidth / measured else 1f
    }
}
