package app.luma.standby.ui.clock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import app.luma.standby.domain.BurnInDrift
import app.luma.standby.domain.model.LumaTheme
import app.luma.standby.domain.model.ResolvedColors
import app.luma.standby.domain.model.resolveColors
import app.luma.standby.domain.theme.ThemePresets
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * The whole standby face. A plain View drawing on a Canvas: no Compose, no
 * animation loop, no allocation per frame beyond a couple of strings.
 *
 * Redraw policy: exactly one draw per minute boundary (or per second when
 * seconds are requested), plus one after any time/timezone/date/locale change.
 */
class ClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        isSubpixelText = true
        fontFeatureSettings = "tnum"
    }
    private val suffixPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        isSubpixelText = true
    }
    private val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isSubpixelText = true
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var theme: LumaTheme = ThemePresets.atlanticRose
    private var colors: ResolvedColors = theme.resolveColors()
    private var use24Hour: Boolean = false
    private var showSeconds: Boolean = false
    private var burnInDrift: Boolean = true
    private var hintText: String? = null
    private var hintAlpha: Float = 0f

    private val density = resources.displayMetrics.density

    /** Called once per redraw, before the frame is drawn. */
    var onTick: (() -> Unit)? = null

    private val scheduler = MinuteScheduler {
        onTick?.invoke()
        invalidate()
    }
    private val hintHandler = Handler(Looper.getMainLooper())
    private val hideHint = Runnable {
        hintAlpha = 0f
        invalidate()
    }

    private var dateFormatter: SimpleDateFormat = buildDateFormatter()

    // Gradient shader is rebuilt only when geometry or theme actually changes.
    private var shaderWidth = -1f
    private var shaderHeight = -1f
    private var shaderKey: String? = null

    private var receiverRegistered = false

    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_LOCALE_CHANGED) {
                dateFormatter = buildDateFormatter()
            }
            scheduler.reschedule()
            invalidate()
        }
    }

    fun applyState(
        theme: LumaTheme,
        use24HourOverride: Boolean?,
        showSecondsOverride: Boolean?,
        burnInShift: Boolean,
        hint: String? = null,
    ) {
        this.theme = theme
        this.colors = theme.resolveColors()
        this.use24Hour = use24HourOverride ?: DateFormat.is24HourFormat(context)
        this.showSeconds = showSecondsOverride ?: theme.showSeconds
        this.burnInDrift = burnInShift
        this.hintText = hint
        scheduler.secondsEnabled = this.showSeconds
        if (hint.isNullOrEmpty()) hintAlpha = 0f
        invalidate()
    }

    /** Show the exit hint for a few seconds, then get out of the way again. */
    fun flashHint() {
        if (hintText.isNullOrEmpty()) return
        hintAlpha = 1f
        invalidate()
        hintHandler.removeCallbacks(hideHint)
        hintHandler.postDelayed(hideHint, HINT_VISIBLE_MS)
    }

    fun resumeTicking() {
        scheduler.start()
    }

    fun pauseTicking() {
        scheduler.stop()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        registerTimeReceiver()
        scheduler.start()
    }

    override fun onDetachedFromWindow() {
        scheduler.stop()
        hintHandler.removeCallbacks(hideHint)
        unregisterTimeReceiver()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        shaderWidth = -1f
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        drawBackground(canvas, w, h)

        val calendar = Calendar.getInstance()
        val minuteOfDay = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
        val drift = driftFor(minuteOfDay)

        canvas.save()
        canvas.translate(drift.first, drift.second)

        val hour24 = calendar.get(Calendar.HOUR_OF_DAY)
        val hour = if (use24Hour) hour24 else {
            val h12 = hour24 % 12
            if (h12 == 0) 12 else h12
        }
        val minute = calendar.get(Calendar.MINUTE)
        val second = calendar.get(Calendar.SECOND)

        val timeText = if (showSeconds) {
            String.format(Locale.US, "%02d:%02d:%02d", hour, minute, second)
        } else {
            String.format(Locale.US, "%02d:%02d", hour, minute)
        }
        val suffix = if (use24Hour) "" else if (hour24 < 12) "AM" else "PM"

        drawTime(canvas, w, h, timeText, suffix)
        drawDate(canvas, w, h)
        drawHint(canvas, w, h)

        canvas.restore()
    }

    private fun drawBackground(canvas: Canvas, w: Float, h: Float) {
        if (theme.gradient) {
            val key = theme.id + ":" + theme.background + ":" + theme.gradientEnd
            if (shaderKey != key || shaderWidth != w || shaderHeight != h) {
                backgroundPaint.shader = LinearGradient(
                    0f,
                    0f,
                    w,
                    h,
                    colors.background,
                    colors.backgroundEnd,
                    Shader.TileMode.CLAMP,
                )
                shaderKey = key
                shaderWidth = w
                shaderHeight = h
            }
        } else {
            if (shaderKey != null) {
                backgroundPaint.shader = null
                shaderKey = null
            }
            backgroundPaint.color = colors.background
        }
        canvas.drawRect(0f, 0f, w, h, backgroundPaint)
    }

    private fun drawTime(canvas: Canvas, w: Float, h: Float, timeText: String, suffix: String) {
        val typeface = typefaceFor(theme)
        timePaint.typeface = typeface
        timePaint.color = colors.primary
        suffixPaint.typeface = typeface
        suffixPaint.color = colors.secondary

        var size = h * 0.42f
        timePaint.textSize = size
        suffixPaint.textSize = size * 0.16f

        // A portrait face has much less horizontal room to waste.
        val maxWidth = w * if (h > w) 0.86f else 0.78f
        val gap = if (suffix.isEmpty()) 0f else size * 0.10f
        var timeWidth = timePaint.measureText(timeText)
        var suffixWidth = if (suffix.isEmpty()) 0f else suffixPaint.measureText(suffix)

        val total = timeWidth + gap + suffixWidth
        if (total > maxWidth && total > 0f) {
            val scale = maxWidth / total
            size *= scale
            timePaint.textSize = size
            suffixPaint.textSize = size * 0.16f
            timeWidth = timePaint.measureText(timeText)
            suffixWidth = if (suffix.isEmpty()) 0f else suffixPaint.measureText(suffix)
        }

        val startX = (w - (timeWidth + gap + suffixWidth)) / 2f
        val baseline = h * 0.54f
        canvas.drawText(timeText, startX, baseline, timePaint)
        if (suffix.isNotEmpty()) {
            canvas.drawText(suffix, startX + timeWidth + gap, baseline - size * 0.06f, suffixPaint)
        }
    }

    private fun drawDate(canvas: Canvas, w: Float, h: Float) {
        val text = dateFormatter.format(Date())
        datePaint.typeface = typefaceFor(theme)
        datePaint.color = colors.secondary

        var size = h * 0.042f
        datePaint.textSize = size
        val measured = datePaint.measureText(text)
        val maxWidth = w * 0.86f
        if (measured > maxWidth && measured > 0f) {
            size *= maxWidth / measured
            datePaint.textSize = size
        }
        val baseline = if (h > w) h * 0.665f else h * 0.685f
        canvas.drawText(text, w / 2f, baseline, datePaint)
    }

    private fun drawHint(canvas: Canvas, w: Float, h: Float) {
        val hint = hintText
        if (hint.isNullOrEmpty() || hintAlpha <= 0f) return
        hintPaint.color = withAlpha(colors.secondary, 0.45f * hintAlpha)
        hintPaint.textSize = h * 0.020f
        hintPaint.typeface = typefaceFor(theme)
        canvas.drawText(hint, w / 2f, h * 0.90f, hintPaint)
    }

    private fun typefaceFor(theme: LumaTheme): Typeface =
        Typeface.create(theme.font.family, Typeface.NORMAL)

    /**
     * A slow four-position drift, one step per minute. It is a burn-in
     * mitigation, not a guarantee, and it never moves more than a few dp.
     */
    private fun driftFor(minuteOfDay: Int): Pair<Float, Float> {
        if (!burnInDrift) return Pair(0f, 0f)
        // One slow step every 20 minutes, 1-2 physical pixels, deterministic so a
        // reboot does not visibly jump. See BurnInDrift for the sequence.
        val elapsedMinutes = System.currentTimeMillis() / 60_000L
        return BurnInDrift.offsetFor(elapsedMinutes, amplitudePx = 2f)
    }

    private fun buildDateFormatter(): SimpleDateFormat {
        val locale = Locale.getDefault()
        val pattern = DateFormat.getBestDateTimePattern(locale, "EEEEMMMMd")
        return SimpleDateFormat(pattern, locale)
    }

    private fun withAlpha(color: Int, alpha: Float): Int {
        val a = (Color.alpha(color) * alpha).toInt().coerceIn(0, 255)
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
    }

    private fun registerTimeReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_LOCALE_CHANGED)
        }
        runCatching {
            ContextCompat.registerReceiver(
                context,
                timeReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
        }
    }

    private fun unregisterTimeReceiver() {
        if (!receiverRegistered) return
        runCatching { context.unregisterReceiver(timeReceiver) }
        receiverRegistered = false
    }

    private companion object {
        const val HINT_VISIBLE_MS = 4_000L
    }
}
