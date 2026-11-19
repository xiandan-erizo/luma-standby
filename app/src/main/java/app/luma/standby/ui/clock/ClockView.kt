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
import app.luma.standby.domain.widget.WidgetGlyph
import app.luma.standby.domain.widget.WidgetSlot
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
    private var use24HourOverride: Boolean? = null
    private var showSeconds: Boolean = false
    private var burnInDrift: Boolean = true
    private var hintText: String? = null
    private var hintAlpha: Float = 0f

    /** Compact date belongs to the rail layout, not to "widgets happen to exist". */
    private var showCompactDate: Boolean = true

    /** Widget tiles, already computed by the caller: layout here is pure drawing. */
    private var widgetTiles: List<WidgetTile> = emptyList()
    private val widgetPrimaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.RIGHT
        isSubpixelText = true
        fontFeatureSettings = "tnum"
    }
    private val widgetSecondaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.RIGHT
        isSubpixelText = true
        fontFeatureSettings = "tnum"
    }

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
            if (intent?.action == Intent.ACTION_LOCALE_CHANGED || intent?.action == Intent.ACTION_TIMEZONE_CHANGED) {
                dateFormatter = buildDateFormatter()
            }
            use24Hour = use24HourOverride ?: DateFormat.is24HourFormat(context)
            scheduler.reschedule()
            onTick?.invoke()
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
        this.use24HourOverride = use24HourOverride
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

    /** Replace the widget tiles; callers compute content off the redraw path. */
    fun setWidgets(tiles: List<WidgetTile>) {
        val changed = tiles != widgetTiles
        widgetTiles = tiles
        if (changed) invalidate()
    }

    fun resumeTicking() {
        registerTimeReceiver()
        scheduler.start()
    }

    fun pauseTicking() {
        scheduler.stop()
        hintHandler.removeCallbacks(hideHint)
        unregisterTimeReceiver()
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

        val now = Calendar.getInstance()
        val layout = FaceLayout.create(w, h, widgetTiles.isNotEmpty())
        showCompactDate = w >= h
        val drift = driftFor(now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE))
        canvas.save()
        canvas.translate(drift.first, drift.second)
        drawTime(canvas, layout.clock, now)
        drawDate(canvas, layout.date)
        drawWidgets(canvas, layout)
        drawHint(canvas, w, h)
        canvas.restore()
    }

    private fun drawBackground(canvas: Canvas, w: Float, h: Float) {
        if (theme.gradient) {
            val key = theme.id + ":" + theme.background + ":" + theme.gradientEnd
            if (shaderKey != key || shaderWidth != w || shaderHeight != h) {
                backgroundPaint.shader = LinearGradient(0f, 0f, w, h, colors.background, colors.backgroundEnd, Shader.TileMode.CLAMP)
                shaderKey = key; shaderWidth = w; shaderHeight = h
            }
        } else {
            if (shaderKey != null) { backgroundPaint.shader = null; shaderKey = null }
            backgroundPaint.color = colors.background
        }
        canvas.drawRect(0f, 0f, w, h, backgroundPaint)
    }

    private fun drawTime(canvas: Canvas, box: FaceBox, calendar: Calendar) {
        val hour24 = calendar.get(Calendar.HOUR_OF_DAY)
        val hour = if (use24Hour) hour24 else (hour24 % 12).let { if (it == 0) 12 else it }
        val minute = calendar.get(Calendar.MINUTE)
        val second = calendar.get(Calendar.SECOND)
        val text = if (showSeconds) String.format(Locale.US, "%02d:%02d:%02d", hour, minute, second)
                   else String.format(Locale.US, "%02d:%02d", hour, minute)
        val suffix = if (use24Hour) "" else if (hour24 < 12) "AM" else "PM"
        if (theme.font == app.luma.standby.domain.model.FontChoice.DISPLAY) {
            drawDisplayDigits(canvas, box, text, suffix)
            return
        }
        val face = typefaceFor(theme)
        timePaint.typeface = face; timePaint.color = colors.primary
        suffixPaint.typeface = face; suffixPaint.color = withAlpha(colors.secondary, .82f)
        var size = box.height * .90f
        fun measure(): Triple<Float,Float,Float> {
            timePaint.textSize=size; suffixPaint.textSize=size*.13f
            val tw=timePaint.measureText(text); val sw=if(suffix.isEmpty())0f else suffixPaint.measureText(suffix)
            return Triple(tw,sw,if(suffix.isEmpty())0f else size*.06f)
        }
        var measured=measure(); val scale=ClockTypography.shrinkFactor(measured.first+measured.second+measured.third,box.width)
        size*=scale; measured=measure()
        val total=measured.first+measured.second+measured.third
        val x=box.left+(box.width-total)/2f
        val baseline=box.top+(box.height-(timePaint.ascent()+timePaint.descent()))/2f
        canvas.drawText(text,x,baseline,timePaint)
        if(suffix.isNotEmpty()) canvas.drawText(suffix,x+measured.first+measured.third,baseline-size*.03f,suffixPaint)
    }

    private fun drawDisplayDigits(canvas: Canvas, box: FaceBox, text: String, suffix: String) {
        timePaint.typeface = LumaFonts.resolve(context, theme.font)
        timePaint.color = colors.primary; timePaint.textSize = 1000f
        val digits = text.count { it.isDigit() }
        val colons = text.count { it == ':' }
        val digitWidth = box.width / (digits + colons * .45f + (digits - 1) * .055f)
        val spacing = digitWidth * .055f
        val bounds = android.graphics.Rect()
        timePaint.getTextBounds("0", 0, 1, bounds)
        val referenceWidth = bounds.width().coerceAtLeast(1).toFloat()
        val referenceHeight = bounds.height().coerceAtLeast(1).toFloat()
        val referenceTop = bounds.top.toFloat()
        var x = box.left
        for (char in text) {
            if (char == ':') {
                val cx = x + digitWidth * .225f
                canvas.drawCircle(cx, box.top + box.height * .27f, digitWidth * .15f, timePaint)
                canvas.drawCircle(cx, box.top + box.height * .73f, digitWidth * .15f, timePaint)
                x += digitWidth * .45f
            } else {
                val glyph = char.toString()
                timePaint.getTextBounds(glyph, 0, 1, bounds)
                canvas.save(); canvas.translate(x, box.top)
                // Uniform glyph scale: stretching each glyph to its own bounds turns
                // a slim 1 into a wide slab. Keep a shared tabular cell and centre it.
                canvas.scale(digitWidth / referenceWidth, box.height / referenceHeight)
                val textX = (referenceWidth - bounds.width()) / 2f - bounds.left
                val path = android.graphics.Path()
                timePaint.getTextPath(glyph, 0, 1, textX, -referenceTop, path)
                timePaint.pathEffect = android.graphics.CornerPathEffect(12f)
                canvas.drawPath(path, timePaint)
                timePaint.pathEffect = null
                canvas.restore(); x += digitWidth + spacing
            }
        }
        if (suffix.isNotEmpty()) {
            suffixPaint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            suffixPaint.textSize = box.height * .055f; suffixPaint.color = colors.secondary
            canvas.drawText(suffix, box.left, box.bottom + suffixPaint.textSize * 1.4f, suffixPaint)
        }
    }

    private fun drawDate(canvas: Canvas, box: FaceBox) {
        val text = if (showCompactDate) {
            DateFormat.format(if (Locale.getDefault().language == "zh") "d EEE" else "EEE d", Date()).toString()
        } else {
            dateFormatter.format(Date())
        }
        datePaint.typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL); datePaint.color=colors.primary
        var size=box.height*.34f; datePaint.textSize=size
        size*=ClockTypography.shrinkFactor(datePaint.measureText(text),box.width); datePaint.textSize=size
        // Left-aligned on the rail's shared margin so the date sits on the same
        // vertical line as the icon column and the value column below it.
        datePaint.textAlign = Paint.Align.LEFT
        canvas.drawText(text,box.left,box.top+(box.height-(datePaint.ascent()+datePaint.descent()))/2f,datePaint)
    }

    private fun drawWidgets(canvas: Canvas, layout: FaceLayout) {
        if(widgetTiles.isEmpty()) return
        val right=widgetTiles.filter{it.slot==WidgetSlot.TOP_RIGHT||it.slot==WidgetSlot.BOTTOM_RIGHT}
        val left=widgetTiles.filter{it.slot==WidgetSlot.BOTTOM_LEFT}
        right.take(layout.rightTiles.size).forEachIndexed{i,tile->drawTile(canvas,tile,layout.rightTiles[i])}
        layout.leftTile?.let { box -> left.firstOrNull()?.let { drawTile(canvas,it,box) } }
    }

    private fun drawTile(canvas: Canvas, tile: WidgetTile, box: FaceBox) {
        val c=tile.content; val face=Typeface.create("sans-serif-medium",Typeface.NORMAL)
        val iconSize=box.height*.30f; val iconX=box.left+iconSize*.55f; val centerY=box.top+box.height/2f
        WidgetIcons.draw(canvas,c.glyph,iconX-iconSize/2f,centerY-iconSize/2f,iconSize,withAlpha(colors.accent,.92f))
        val textLeft=if(c.glyph==WidgetGlyph.NONE) box.left else box.left+iconSize*1.25f
        val available=(box.right-textLeft).coerceAtLeast(1f)
        widgetPrimaryPaint.textAlign=Paint.Align.LEFT; widgetPrimaryPaint.typeface=face; widgetPrimaryPaint.color=colors.primary
        widgetSecondaryPaint.textAlign=Paint.Align.LEFT; widgetSecondaryPaint.typeface=face; widgetSecondaryPaint.color=withAlpha(colors.secondary,.62f)
        var primarySize=box.height*(if(c.secondary==null).38f else .31f); widgetPrimaryPaint.textSize=primarySize
        widgetPrimaryPaint.textSize=primarySize
        val primary=android.text.TextUtils.ellipsize(c.primary, android.text.TextPaint(widgetPrimaryPaint), available, android.text.TextUtils.TruncateAt.END).toString()
        val hasSecondary=!c.secondary.isNullOrBlank(); val primaryY=if(hasSecondary)box.top+box.height*.46f else box.top+box.height*.62f
        canvas.drawText(primary,textLeft,primaryY,widgetPrimaryPaint)
        c.secondary?.takeIf{it.isNotBlank()}?.let { secondary ->
            var s=box.height*.19f; widgetSecondaryPaint.textSize=s
            val line=android.text.TextUtils.ellipsize(secondary, android.text.TextPaint(widgetSecondaryPaint), available, android.text.TextUtils.TruncateAt.END).toString()
            canvas.drawText(line,textLeft,box.top+box.height*.77f,widgetSecondaryPaint)
        }
    }

    private fun drawHint(canvas: Canvas, w: Float, h: Float) {
        val hint=hintText
        if(hint.isNullOrEmpty()||hintAlpha<=0f)return
        hintPaint.color=withAlpha(colors.secondary,.35f*hintAlpha); hintPaint.textSize=h*.017f; hintPaint.typeface=typefaceFor(theme)
        canvas.drawText(hint,w/2f,h*.95f,hintPaint)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun typefaceFor(theme: LumaTheme): Typeface =
        LumaFonts.resolve(context, theme.font)

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