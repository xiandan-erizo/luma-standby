package app.luma.standby.ui.clock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.TextPaint
import android.text.TextUtils
import android.text.format.DateFormat
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import app.luma.standby.domain.BurnInDrift
import app.luma.standby.domain.model.BrightnessProfile
import app.luma.standby.domain.model.FaceStyle
import app.luma.standby.domain.model.FontChoice
import app.luma.standby.domain.model.LumaTheme
import app.luma.standby.domain.model.ResolvedColors
import app.luma.standby.domain.model.resolveColors
import app.luma.standby.domain.theme.ThemePresets
import app.luma.standby.domain.widget.WidgetGlyph
import app.luma.standby.domain.widget.WidgetKind
import app.luma.standby.domain.widget.WidgetSlot
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.min

/**
 * The whole standby face. A plain View drawing on a Canvas: no Compose, no
 * animation loop, no allocation per frame beyond short formatted strings.
 *
 * Supports two iOS StandBy-aligned presentation styles:
 * - [FaceStyle.FLOAT]: Heavy rounded bubbly duo-tone numerals with translucent overlap.
 * - [FaceStyle.SPLIT]: Crisp rounded digital clock inside a 60-tick squircle bezel
 *   paired with graphical complications (circular battery ring, accent weekday +
 *   oversized date, and sky-header hero temperature).
 *
 * Redraw policy: exactly one draw per minute boundary (or per second when
 * seconds are requested), plus one after any time/timezone/date/locale change.
 */
class ClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val strokeDigitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val screenBlendMode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
    private val colonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val cardFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val cardStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        isSubpixelText = true
        fontFeatureSettings = "tnum"
    }
    private val suffixPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        isSubpixelText = true
    }
    private val dateHeaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        isSubpixelText = true
    }
    private val dateNumberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        isSubpixelText = true
        fontFeatureSettings = "tnum"
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val widgetPrimaryPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        isSubpixelText = true
        fontFeatureSettings = "tnum"
    }
    private val widgetSecondaryPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        isSubpixelText = true
        fontFeatureSettings = "tnum"
    }

    private val digitPath = Path()
    private val ringPath = Path()
    private val rectScratch = RectF()
    private val layerBounds = RectF()

    private var theme: LumaTheme = ThemePresets.iosFloatBlue
    private var colors: ResolvedColors = theme.resolveColors()
    private var use24Hour: Boolean = false
    private var use24HourOverride: Boolean? = null
    private var showSeconds: Boolean = false
    private var burnInDrift: Boolean = true
    private var hintText: String? = null
    private var hintAlpha: Float = 0f

    private var widgetTiles: List<WidgetTile> = emptyList()

    private val mediumSans: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val boldSans: Typeface = Typeface.create("sans-serif", Typeface.BOLD)

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

    private var fullDateFormatter: SimpleDateFormat = buildFullDateFormatter()
    private var weekdayFormatter: SimpleDateFormat = buildWeekdayFormatter()
    private var monthFormatter: SimpleDateFormat = buildMonthFormatter()

    private var shaderWidth = -1f
    private var shaderHeight = -1f
    private var shaderKey: String? = null

    private var receiverRegistered = false

    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_LOCALE_CHANGED ||
                intent?.action == Intent.ACTION_TIMEZONE_CHANGED
            ) {
                fullDateFormatter = buildFullDateFormatter()
                weekdayFormatter = buildWeekdayFormatter()
                monthFormatter = buildMonthFormatter()
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
        val withWidgets = widgetTiles.isNotEmpty()
        val layout = FaceLayout.create(w, h, withWidgets, theme.faceStyle)
        val landscape = w >= h
        val rightWidgets = widgetTiles.filter {
            it.slot == WidgetSlot.TOP_RIGHT || it.slot == WidgetSlot.BOTTOM_RIGHT
        }

        val drift = driftFor()
        canvas.save()
        canvas.translate(drift.first, drift.second)
        drawTime(canvas, layout.clock, now, landscape)
        drawDate(canvas, layout, now, landscape, rightWidgets.isNotEmpty())
        drawWidgets(canvas, layout, rightWidgets)
        drawHint(canvas, w, h)
        canvas.restore()
    }

    private fun drawBackground(canvas: Canvas, w: Float, h: Float) {
        if (theme.gradient) {
            val key = theme.id + ":" + theme.background + ":" + theme.gradientEnd
            if (shaderKey != key || shaderWidth != w || shaderHeight != h) {
                backgroundPaint.shader = LinearGradient(
                    0f, 0f, w, h,
                    colors.background, colors.backgroundEnd,
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

    private fun drawTime(canvas: Canvas, box: FaceBox, calendar: Calendar, landscape: Boolean) {
        val hour24 = calendar.get(Calendar.HOUR_OF_DAY)
        val hour = if (use24Hour) hour24 else (hour24 % 12).let { if (it == 0) 12 else it }
        val minute = calendar.get(Calendar.MINUTE)
        val second = calendar.get(Calendar.SECOND)
        val hourText = if (use24Hour) String.format(Locale.US, "%02d", hour) else hour.toString()
        val text = if (showSeconds) {
            String.format(Locale.US, "%s:%02d:%02d", hourText, minute, second)
        } else {
            String.format(Locale.US, "%s:%02d", hourText, minute)
        }
        val suffix = if (use24Hour) "" else if (hour24 < 12) "AM" else "PM"

        if (theme.faceStyle == FaceStyle.FLOAT) {
            drawFloatTime(canvas, box, text, suffix)
        } else {
            drawSplitTime(canvas, box, text, suffix, minute, second, landscape)
        }
    }

    /**
     * iOS StandBy Float style: plump rounded monoline numerals with negative intra-pair
     * kerning, alternating primary/accent duo-tone vertical gradients, and a subtle
     * additive glow where adjacent digits overlap on dark OLED backgrounds.
     */
    private fun drawFloatTime(canvas: Canvas, box: FaceBox, text: String, suffix: String) {
        val overlapRatio = -0.145f
        val colonRatio = 0.20f
        val postColonGap = -0.045f

        var totalUnits = 0f
        text.forEachIndexed { idx, ch ->
            if (ch == ':') {
                totalUnits += colonRatio
            } else {
                val wf = IosNumerals.widthFactor(ch, bubbly = true)
                totalUnits += wf
                val next = text.getOrNull(idx + 1)
                if (next != null && next != ':') {
                    totalUnits += overlapRatio
                } else if (next == ':') {
                    totalUnits += postColonGap
                }
            }
            if (ch == ':' && idx + 1 < text.length) {
                totalUnits += postColonGap
            }
        }

        val maxDigitHeight = box.height * 0.94f
        val unitByWidth = box.width / totalUnits.coerceAtLeast(1f)
        val unitByHeight = maxDigitHeight / 1.40f
        val unitW = min(unitByWidth, unitByHeight)
        val digitH = unitW * 1.40f
        val strokeW = min(unitW * 0.33f, digitH * 0.235f)

        val totalWidth = unitW * totalUnits
        val startX = box.left + (box.width - totalWidth) * 0.5f
        val topY = box.top + (box.height - digitH) * 0.5f
        val bottomY = topY + digitH

        val darkSurface = isDark(colors.background)
        layerBounds.set(box.left, box.top, box.right, box.bottom)
        val layerId = canvas.saveLayer(layerBounds, null)

        var cursorX = startX
        var digitIndex = 0
        val colonPositions = ArrayList<Float>(2)

        text.forEachIndexed { idx, ch ->
            if (ch == ':') {
                colonPositions.add(cursorX + unitW * colonRatio * 0.5f)
                cursorX += unitW * (colonRatio + postColonGap)
            } else {
                val wf = IosNumerals.widthFactor(ch, bubbly = true)
                val cellW = unitW * wf
                IosNumerals.buildDigitPath(
                    path = digitPath,
                    digit = ch,
                    left = cursorX,
                    top = topY,
                    right = cursorX + cellW,
                    bottom = bottomY,
                    strokeWidth = strokeW,
                )

                val baseColor = if (digitIndex % 2 == 0) colors.accent else colors.primary
                val topTint = shiftLuminance(baseColor, if (darkSurface) 0.14f else -0.06f)
                val bottomTint = shiftLuminance(baseColor, if (darkSurface) -0.10f else 0.08f)
                val baseAlpha = if (digitIndex % 2 == 0) 238 else 216

                strokeDigitPaint.xfermode = null
                strokeDigitPaint.strokeWidth = strokeW
                strokeDigitPaint.shader = LinearGradient(
                    cursorX,
                    topY,
                    cursorX,
                    bottomY,
                    withExactAlpha(topTint, baseAlpha),
                    withExactAlpha(bottomTint, baseAlpha),
                    Shader.TileMode.CLAMP,
                )
                canvas.drawPath(digitPath, strokeDigitPaint)

                // Screen-blended highlight pass produces the translucent overlap shimmer
                // seen on iOS Float numerals without washing out light themes.
                if (darkSurface && digitIndex % 2 == 1) {
                    strokeDigitPaint.xfermode = screenBlendMode
                    strokeDigitPaint.shader = null
                    strokeDigitPaint.color = withExactAlpha(topTint, 68)
                    canvas.drawPath(digitPath, strokeDigitPaint)
                    strokeDigitPaint.xfermode = null
                }

                strokeDigitPaint.shader = null
                val next = text.getOrNull(idx + 1)
                cursorX += cellW + when {
                    next == null -> 0f
                    next == ':' -> unitW * postColonGap
                    else -> unitW * overlapRatio
                }
                digitIndex++
            }
        }

        // Plump frosted colon dots floating slightly tilted over the inner edges
        val dotRadius = strokeW * 0.49f
        val dotColor = when {
            theme.brightnessProfile == BrightnessProfile.NIGHT -> withAlpha(colors.accent, 0.88f)
            darkSurface -> Color.argb(226, 244, 247, 252)
            else -> withAlpha(colors.secondary, 0.85f)
        }
        colonPaint.color = dotColor
        for (cx in colonPositions) {
            val tiltOffset = strokeW * 0.11f
            canvas.drawCircle(cx + tiltOffset, topY + digitH * 0.36f, dotRadius, colonPaint)
            canvas.drawCircle(cx - tiltOffset, topY + digitH * 0.64f, dotRadius, colonPaint)
        }

        canvas.restoreToCount(layerId)

        if (suffix.isNotEmpty()) {
            drawCompactSuffix(canvas, startX + totalWidth, topY, digitH, suffix, insideRight = box.right)
        }
    }

    /**
     * iOS StandBy Classic Digital Split style: 60-tick rounded-squircle bezel around
     * crisp, tall, isotropically proportioned rounded numerals with balanced colon dots.
     */
    private fun drawSplitTime(
        canvas: Canvas,
        box: FaceBox,
        text: String,
        suffix: String,
        minute: Int,
        second: Int,
        landscape: Boolean,
    ) {
        val innerBox = if (landscape) {
            val activeTick = if (showSeconds) second else minute
            val darkSurface = isDark(colors.background)
            val litTick = withAlpha(
                colors.primary,
                if (darkSurface) 0.50f else 0.42f,
            )
            val dimTick = withAlpha(
                colors.secondary,
                if (darkSurface) 0.18f else 0.16f,
            )
            IosNumerals.drawSquircleTickRing(
                canvas = canvas,
                box = box,
                activeTick = activeTick,
                litColor = litTick,
                dimColor = dimTick,
                paint = tickPaint,
                ringPath = ringPath,
                rectScratch = rectScratch,
            )
            val padX = box.width * 0.115f
            val padY = box.height * 0.155f
            FaceBox(box.left + padX, box.top + padY, box.right - padX, box.bottom - padY)
        } else {
            box
        }

        if (theme.font == FontChoice.DISPLAY || theme.font == FontChoice.ROUNDED) {
            drawRoundedDigitalDigits(canvas, innerBox, text, suffix)
            return
        }

        // Explicit user-chosen system fonts (Condensed, Sans, Serif, Mono, Light):
        // strictly uniform scaling with sleek optical colon dots.
        val face = typefaceFor(theme)
        timePaint.typeface = face
        timePaint.color = colors.primary
        var size = innerBox.height * 0.84f
        timePaint.textSize = size
        val measuredW = timePaint.measureText(text)
        size *= ClockTypography.shrinkFactor(measuredW, innerBox.width * 0.94f)
        timePaint.textSize = size
        val finalW = timePaint.measureText(text)
        val x = innerBox.left + (innerBox.width - finalW) * 0.5f
        val baseline = innerBox.top + (innerBox.height - (timePaint.ascent() + timePaint.descent())) * 0.5f
        canvas.drawText(text, x, baseline, timePaint)

        if (suffix.isNotEmpty()) {
            drawCompactSuffix(
                canvas,
                x + finalW,
                innerBox.top + innerBox.height * 0.12f,
                innerBox.height * 0.76f,
                suffix,
                insideRight = box.right - box.width * 0.06f,
            )
        }
    }

    private fun drawRoundedDigitalDigits(
        canvas: Canvas,
        box: FaceBox,
        text: String,
        suffix: String,
    ) {
        val gapRatio = 0.085f
        val colonRatio = 0.28f

        var totalUnits = 0f
        text.forEachIndexed { idx, ch ->
            totalUnits += if (ch == ':') colonRatio else IosNumerals.widthFactor(ch, bubbly = false)
            if (idx + 1 < text.length) totalUnits += gapRatio
        }

        val maxDigitH = box.height * 0.90f
        val unitByWidth = box.width / totalUnits.coerceAtLeast(1f)
        val unitByHeight = maxDigitH / 1.64f
        val unitW = min(unitByWidth, unitByHeight)
        val digitH = unitW * 1.64f
        val strokeW = min(unitW * 0.275f, digitH * 0.168f)

        val totalW = unitW * totalUnits
        var cursorX = box.left + (box.width - totalW) * 0.5f
        val topY = box.top + (box.height - digitH) * 0.5f
        val bottomY = topY + digitH

        strokeDigitPaint.xfermode = null
        strokeDigitPaint.shader = null
        strokeDigitPaint.color = colors.primary
        strokeDigitPaint.strokeWidth = strokeW
        colonPaint.color = colors.primary
        val dotRadius = strokeW * 0.52f

        text.forEachIndexed { idx, ch ->
            if (ch == ':') {
                val cx = cursorX + unitW * colonRatio * 0.5f
                canvas.drawCircle(cx, topY + digitH * 0.36f, dotRadius, colonPaint)
                canvas.drawCircle(cx, topY + digitH * 0.64f, dotRadius, colonPaint)
                cursorX += unitW * colonRatio
            } else {
                val wf = IosNumerals.widthFactor(ch, bubbly = false)
                val cellW = unitW * wf
                IosNumerals.buildDigitPath(
                    path = digitPath,
                    digit = ch,
                    left = cursorX,
                    top = topY,
                    right = cursorX + cellW,
                    bottom = bottomY,
                    strokeWidth = strokeW,
                )
                canvas.drawPath(digitPath, strokeDigitPaint)
                cursorX += cellW
            }
            if (idx + 1 < text.length) cursorX += unitW * gapRatio
        }

        if (suffix.isNotEmpty()) {
            drawCompactSuffix(canvas, cursorX, topY, digitH, suffix, insideRight = box.right)
        }
    }

    private fun drawCompactSuffix(
        canvas: Canvas,
        anchorRight: Float,
        topY: Float,
        digitH: Float,
        suffix: String,
        insideRight: Float,
    ) {
        suffixPaint.typeface = mediumSans
        suffixPaint.textSize = (digitH * 0.11f).coerceAtLeast(11f)
        suffixPaint.color = withAlpha(colors.secondary, 0.78f)
        val sw = suffixPaint.measureText(suffix)
        val x = (anchorRight + suffixPaint.textSize * 0.25f).coerceAtMost(insideRight - sw)
        val y = topY + suffixPaint.textSize * 0.95f
        canvas.drawText(suffix, x, y, suffixPaint)
    }

    /**
     * iOS StandBy date typography:
     * - Accent-coloured weekday/month header (`THU · NOV` / `周四 · 11月`)
     * - Hero rounded day numeral in Split mode when the right column has no widgets,
     *   or a clean two-tone rail header above placed widget cards.
     */
    private fun drawDate(
        canvas: Canvas,
        layout: FaceLayout,
        now: Calendar,
        landscape: Boolean,
        hasRightWidgets: Boolean,
    ) {
        val date = now.time
        val locale = Locale.getDefault()
        val weekday = weekdayFormatter.format(date).uppercase(locale)
        val month = monthFormatter.format(date).uppercase(locale)
        val dayNum = now.get(Calendar.DAY_OF_MONTH).toString()

        if (!landscape) {
            val box = layout.date
            val fullText = fullDateFormatter.format(date)
            dateHeaderPaint.typeface = mediumSans
            dateHeaderPaint.textAlign = Paint.Align.CENTER
            var size = box.height * 0.42f
            dateHeaderPaint.textSize = size
            size *= ClockTypography.shrinkFactor(dateHeaderPaint.measureText(fullText), box.width)
            dateHeaderPaint.textSize = size
            dateHeaderPaint.color = colors.accent
            val y = box.top + (box.height - (dateHeaderPaint.ascent() + dateHeaderPaint.descent())) * 0.5f
            canvas.drawText(fullText, box.left + box.width * 0.5f, y, dateHeaderPaint)
            dateHeaderPaint.textAlign = Paint.Align.LEFT
            return
        }

        // Float without widgets: sleek floating accent badge in top-right.
        if (theme.faceStyle == FaceStyle.FLOAT && !hasRightWidgets && widgetTiles.isEmpty()) {
            val box = layout.date
            dateHeaderPaint.typeface = boldSans
            dateHeaderPaint.textAlign = Paint.Align.RIGHT
            var size = box.height * 0.48f
            val label = "$weekday  $dayNum"
            dateHeaderPaint.textSize = size
            size *= ClockTypography.shrinkFactor(dateHeaderPaint.measureText(label), box.width)
            dateHeaderPaint.textSize = size

            val dayW = dateHeaderPaint.measureText(" $dayNum")
            val y = box.top + (box.height - (dateHeaderPaint.ascent() + dateHeaderPaint.descent())) * 0.5f
            dateHeaderPaint.color = colors.primary
            canvas.drawText(dayNum, box.right, y, dateHeaderPaint)
            dateHeaderPaint.color = colors.accent
            canvas.drawText(weekday, box.right - dayW, y, dateHeaderPaint)
            dateHeaderPaint.textAlign = Paint.Align.LEFT
            return
        }

        // Split (or Float with left-only widget) when right rail has NO widgets:
        // render the iconic iOS StandBy Calendar Hero (accent `THU · NOV` + giant rounded `19`).
        if (!hasRightWidgets) {
            val railLeft = layout.date.left
            val railRight = layout.date.right
            val railWidth = railRight - railLeft
            val stageTop = layout.clock.top + layout.clock.height * 0.12f
            val stageBottom = layout.clock.bottom - layout.clock.height * 0.08f
            val stageH = (stageBottom - stageTop).coerceAtLeast(1f)

            val header = "$weekday · $month"
            dateHeaderPaint.typeface = boldSans
            dateHeaderPaint.textAlign = Paint.Align.LEFT
            var headerSize = stageH * 0.145f
            dateHeaderPaint.textSize = headerSize
            headerSize *= ClockTypography.shrinkFactor(dateHeaderPaint.measureText(header), railWidth)
            dateHeaderPaint.textSize = headerSize
            dateHeaderPaint.color = colors.accent
            val headerBaseline = stageTop - dateHeaderPaint.ascent()
            canvas.drawText(header, railLeft, headerBaseline, dateHeaderPaint)

            // Giant rounded day numeral below the coloured weekday header
            val numTop = headerBaseline + dateHeaderPaint.descent() + stageH * 0.06f
            val numH = (stageBottom - numTop).coerceAtLeast(16f)
            drawRoundedDayNumber(canvas, railLeft, numTop, railWidth * 0.88f, numH, dayNum)
            return
        }

        // Right rail WITH widgets: compact two-tone iOS StandBy header inside layout.date
        val box = layout.date
        val header = "$weekday · $month"
        dateNumberPaint.typeface = boldSans
        dateNumberPaint.textSize = box.height * 0.68f
        dateNumberPaint.color = colors.primary
        val numWidth = dateNumberPaint.measureText(dayNum)
        val numBaseline = box.top + (box.height - (dateNumberPaint.ascent() + dateNumberPaint.descent())) * 0.5f
        canvas.drawText(dayNum, box.left, numBaseline, dateNumberPaint)

        val textLeft = box.left + numWidth + box.height * 0.22f
        val avail = (box.right - textLeft).coerceAtLeast(1f)
        dateHeaderPaint.typeface = boldSans
        dateHeaderPaint.textAlign = Paint.Align.LEFT
        var labelSize = box.height * 0.29f
        dateHeaderPaint.textSize = labelSize
        labelSize *= ClockTypography.shrinkFactor(dateHeaderPaint.measureText(header), avail)
        dateHeaderPaint.textSize = labelSize
        dateHeaderPaint.color = colors.accent
        val headerY = box.top + box.height * 0.58f
        canvas.drawText(header, textLeft, headerY, dateHeaderPaint)
    }

    private fun drawRoundedDayNumber(
        canvas: Canvas,
        left: Float,
        top: Float,
        maxWidth: Float,
        height: Float,
        dayText: String,
    ) {
        val gapRatio = 0.11f
        var units = 0f
        dayText.forEachIndexed { idx, ch ->
            units += IosNumerals.widthFactor(ch, bubbly = false)
            if (idx + 1 < dayText.length) units += gapRatio
        }
        val unitW = min(maxWidth / units.coerceAtLeast(1f), height / 1.52f)
        val digitH = unitW * 1.52f
        val strokeW = min(unitW * 0.24f, digitH * 0.155f)

        strokeDigitPaint.xfermode = null
        strokeDigitPaint.shader = null
        strokeDigitPaint.color = colors.primary
        strokeDigitPaint.strokeWidth = strokeW

        var cx = left
        dayText.forEachIndexed { idx, ch ->
            val cellW = unitW * IosNumerals.widthFactor(ch, bubbly = false)
            IosNumerals.buildDigitPath(
                path = digitPath,
                digit = ch,
                left = cx,
                top = top,
                right = cx + cellW,
                bottom = top + digitH,
                strokeWidth = strokeW,
            )
            canvas.drawPath(digitPath, strokeDigitPaint)
            cx += cellW + if (idx + 1 < dayText.length) unitW * gapRatio else 0f
        }
    }

    private fun drawWidgets(
        canvas: Canvas,
        layout: FaceLayout,
        rightWidgets: List<WidgetTile>,
    ) {
        if (widgetTiles.isEmpty()) return
        val leftWidgets = widgetTiles.filter { it.slot == WidgetSlot.BOTTOM_LEFT }

        if (rightWidgets.isNotEmpty() && layout.rightTiles.isNotEmpty()) {
            val visibleRight = rightWidgets.take(layout.rightTiles.size)
            val boxes = distributeRightBoxes(layout.rightTiles, visibleRight.size)
            visibleRight.forEachIndexed { i, tile ->
                drawTile(canvas, tile, boxes[i], compactFooter = false)
            }
        }
        layout.leftTile?.let { box ->
            leftWidgets.firstOrNull()?.let { tile ->
                drawTile(canvas, tile, box, compactFooter = true)
            }
        }
    }

    /**
     * Gives 1 or 2 placed right-rail widgets proportional breathing room inside the
     * reserved right-rail envelope while strictly staying inside `rightTiles` bounds.
     */
    private fun distributeRightBoxes(template: List<FaceBox>, count: Int): List<FaceBox> {
        if (count <= 0 || template.isEmpty()) return emptyList()
        if (count >= template.size) return template.take(count)

        val left = template.first().left
        val right = template.first().right
        val top = template.first().top
        val bottom = template.last().bottom
        val span = bottom - top

        return if (count == 1) {
            val cardH = min(span * 0.56f, (right - left) * 0.52f)
            listOf(FaceBox(left, top + span * 0.04f, right, top + span * 0.04f + cardH))
        } else {
            val gap = span * 0.06f
            val cardH = (span - gap) * 0.5f
            listOf(
                FaceBox(left, top, right, top + cardH),
                FaceBox(left, top + cardH + gap, right, bottom),
            )
        }
    }

    private fun drawTile(
        canvas: Canvas,
        tile: WidgetTile,
        box: FaceBox,
        compactFooter: Boolean,
    ) {
        if (compactFooter) {
            drawCompactFooterTile(canvas, tile, box)
            return
        }

        val darkSurface = isDark(colors.background)
        val cornerR = min(box.height * 0.26f, box.width * 0.08f)
        rectScratch.set(box.left, box.top, box.right, box.bottom)
        cardFillPaint.color = if (darkSurface) {
            Color.argb(22, 255, 255, 255)
        } else {
            Color.argb(14, 0, 0, 0)
        }
        cardStrokePaint.color = if (darkSurface) {
            Color.argb(28, 255, 255, 255)
        } else {
            Color.argb(20, 0, 0, 0)
        }
        canvas.drawRoundRect(rectScratch, cornerR, cornerR, cardFillPaint)
        canvas.drawRoundRect(rectScratch, cornerR, cornerR, cardStrokePaint)

        when (tile.content.kind) {
            WidgetKind.BATTERY_RING -> drawBatteryRingCard(canvas, tile, box, darkSurface)
            WidgetKind.WEATHER_HERO -> drawWeatherHeroCard(canvas, tile, box, darkSurface)
            else -> drawStandardComplicationCard(canvas, tile, box, darkSurface)
        }
    }

    /**
     * Apple Watch / iOS StandBy Circular Battery Complication:
     * Vivid progress ring + centered phone/bolt glyph on the left, large tabular
     * percentage and charging label on the right.
     */
    private fun drawBatteryRingCard(
        canvas: Canvas,
        tile: WidgetTile,
        box: FaceBox,
        darkSurface: Boolean,
    ) {
        val c = tile.content
        val progress = (c.progress ?: 0f).coerceIn(0f, 1f)
        val nightMode = theme.brightnessProfile == BrightnessProfile.NIGHT

        val ringColor = when {
            nightMode -> colors.accent
            !darkSurface -> colors.accent
            progress <= 0.12f -> Color.parseColor("#FF453A")
            progress <= 0.22f -> Color.parseColor("#FFD60A")
            else -> Color.parseColor("#30D158")
        }

        val padX = box.height * 0.18f
        val ringDiameter = min(box.height * 0.68f, box.width * 0.34f)
        val ringRadius = ringDiameter * 0.5f
        val ringCx = box.left + padX + ringRadius
        val ringCy = box.top + box.height * 0.5f
        val ringStroke = (ringRadius * 0.23f).coerceAtLeast(2.2f)

        ringPaint.strokeWidth = ringStroke
        ringPaint.color = withAlpha(ringColor, if (darkSurface) 0.22f else 0.18f)
        canvas.drawCircle(ringCx, ringCy, ringRadius - ringStroke * 0.5f, ringPaint)

        val r = ringRadius - ringStroke * 0.5f
        rectScratch.set(ringCx - r, ringCy - r, ringCx + r, ringCy + r)
        ringPaint.color = ringColor
        val sweep = (progress * 360f).coerceAtLeast(if (progress > 0f) 6f else 0f)
        canvas.drawArc(rectScratch, -90f, sweep, false, ringPaint)

        // Inner phone silhouette + charging bolt
        val phoneW = ringRadius * 0.54f
        val phoneH = ringRadius * 0.86f
        rectScratch.set(
            ringCx - phoneW * 0.5f,
            ringCy - phoneH * 0.5f,
            ringCx + phoneW * 0.5f,
            ringCy + phoneH * 0.5f,
        )
        tickPaint.strokeWidth = (ringStroke * 0.34f).coerceAtLeast(1.4f)
        tickPaint.color = withAlpha(colors.primary, 0.88f)
        canvas.drawRoundRect(rectScratch, phoneW * 0.24f, phoneW * 0.24f, tickPaint)

        if (c.charging) {
            val boltSize = ringRadius * 0.58f
            WidgetIcons.draw(
                canvas,
                WidgetGlyph.BOLT,
                ringCx - boltSize * 0.5f,
                ringCy - boltSize * 0.5f,
                boltSize,
                ringColor,
            )
        }

        val textLeft = ringCx + ringRadius + padX * 0.95f
        val avail = (box.right - padX - textLeft).coerceAtLeast(1f)

        widgetPrimaryPaint.typeface = boldSans
        widgetPrimaryPaint.color = colors.primary
        var numSize = box.height * 0.38f
        widgetPrimaryPaint.textSize = numSize
        numSize *= ClockTypography.shrinkFactor(widgetPrimaryPaint.measureText(c.primary), avail)
        widgetPrimaryPaint.textSize = numSize

        val sub = c.secondary?.takeIf { it.isNotBlank() }
        if (sub != null) {
            canvas.drawText(c.primary, textLeft, box.top + box.height * 0.52f, widgetPrimaryPaint)
            widgetSecondaryPaint.typeface = mediumSans
            widgetSecondaryPaint.color = if (c.charging) ringColor else withAlpha(colors.secondary, 0.78f)
            widgetSecondaryPaint.textSize = box.height * 0.17f
            val clipped = TextUtils.ellipsize(sub, widgetSecondaryPaint, avail, TextUtils.TruncateAt.END).toString()
            canvas.drawText(clipped, textLeft, box.top + box.height * 0.76f, widgetSecondaryPaint)
        } else {
            val y = box.top + (box.height - (widgetPrimaryPaint.ascent() + widgetPrimaryPaint.descent())) * 0.5f
            canvas.drawText(c.primary, textLeft, y, widgetPrimaryPaint)
        }
    }

    /**
     * iOS StandBy Weather Hero Card:
     * Sky-cyan/accent location header on top, oversized rounded temperature numeral,
     * and crisp multi-tone weather glyph + condition.
     */
    private fun drawWeatherHeroCard(
        canvas: Canvas,
        tile: WidgetTile,
        box: FaceBox,
        darkSurface: Boolean,
    ) {
        val c = tile.content
        val padX = box.height * 0.18f
        val innerLeft = box.left + padX
        val innerRight = box.right - padX
        val avail = (innerRight - innerLeft).coerceAtLeast(1f)

        val parts = c.secondary?.split(" · ")?.filter { it.isNotBlank() }.orEmpty()
        val headerLabel = parts.firstOrNull() ?: ""
        val condLabel = if (parts.size >= 2) parts.drop(1).joinToString(" · ") else ""

        val nightMode = theme.brightnessProfile == BrightnessProfile.NIGHT
        val cityColor = when {
            nightMode -> colors.accent
            darkSurface -> Color.parseColor("#5AC8FA")
            else -> colors.accent
        }

        // Top location header ("北京 ↗" or condition)
        if (headerLabel.isNotEmpty()) {
            widgetSecondaryPaint.typeface = boldSans
            widgetSecondaryPaint.color = cityColor
            widgetSecondaryPaint.textSize = box.height * 0.18f
            val headerWithArrow = if (parts.size >= 2) "$headerLabel ↗" else headerLabel
            val clippedHeader = TextUtils.ellipsize(
                headerWithArrow,
                widgetSecondaryPaint,
                avail * 0.72f,
                TextUtils.TruncateAt.END,
            ).toString()
            canvas.drawText(clippedHeader, innerLeft, box.top + box.height * 0.29f, widgetSecondaryPaint)
        }

        // Weather icon in top-right / right-center
        val iconSize = min(box.height * 0.42f, box.width * 0.22f)
        val iconPrimary = when {
            nightMode -> colors.accent
            c.glyph == WidgetGlyph.SUN || c.glyph == WidgetGlyph.THUNDER -> Color.parseColor("#FFD60A")
            c.glyph == WidgetGlyph.RAIN || c.glyph == WidgetGlyph.SNOW -> Color.parseColor("#64D2FF")
            else -> colors.primary
        }
        val iconSecondary = if (nightMode) colors.secondary else withAlpha(colors.primary, 0.85f)
        WidgetIcons.draw(
            canvas = canvas,
            glyph = c.glyph,
            x = innerRight - iconSize,
            y = box.top + box.height * 0.16f,
            size = iconSize,
            color = iconPrimary,
            secondaryColor = iconSecondary,
        )

        // Oversized temperature on bottom-left
        widgetPrimaryPaint.typeface = boldSans
        widgetPrimaryPaint.color = colors.primary
        var tempSize = box.height * 0.46f
        widgetPrimaryPaint.textSize = tempSize
        val tempMaxW = (avail - iconSize - padX * 0.4f).coerceAtLeast(avail * 0.45f)
        tempSize *= ClockTypography.shrinkFactor(widgetPrimaryPaint.measureText(c.primary), tempMaxW)
        widgetPrimaryPaint.textSize = tempSize
        canvas.drawText(c.primary, innerLeft, box.top + box.height * 0.81f, widgetPrimaryPaint)

        // Condition subtitle aligned bottom-right
        if (condLabel.isNotEmpty()) {
            widgetSecondaryPaint.typeface = mediumSans
            widgetSecondaryPaint.color = withAlpha(colors.secondary, 0.86f)
            widgetSecondaryPaint.textSize = box.height * 0.165f
            val condMax = (avail - widgetPrimaryPaint.measureText(c.primary) - padX * 0.6f).coerceAtLeast(1f)
            val clippedCond = TextUtils.ellipsize(
                condLabel,
                widgetSecondaryPaint,
                condMax,
                TextUtils.TruncateAt.END,
            ).toString()
            val cw = widgetSecondaryPaint.measureText(clippedCond)
            canvas.drawText(clippedCond, innerRight - cw, box.top + box.height * 0.80f, widgetSecondaryPaint)
        }
    }

    private fun drawStandardComplicationCard(
        canvas: Canvas,
        tile: WidgetTile,
        box: FaceBox,
        darkSurface: Boolean,
    ) {
        val c = tile.content
        val nightMode = theme.brightnessProfile == BrightnessProfile.NIGHT
        val badgeTint = when {
            nightMode -> colors.accent
            !darkSurface -> colors.accent
            c.kind == WidgetKind.ALARM_BADGE -> Color.parseColor("#FF9F0A")
            c.kind == WidgetKind.MEDIA_NOW -> Color.parseColor("#FF375F")
            else -> colors.accent
        }

        val padX = box.height * 0.18f
        val badgeSize = min(box.height * 0.50f, box.width * 0.24f)
        val badgeCx = box.left + padX + badgeSize * 0.5f
        val badgeCy = box.top + box.height * 0.5f

        if (c.glyph != WidgetGlyph.NONE) {
            cardFillPaint.color = withAlpha(badgeTint, if (darkSurface) 0.20f else 0.14f)
            canvas.drawCircle(badgeCx, badgeCy, badgeSize * 0.5f, cardFillPaint)
            val glyphSize = badgeSize * 0.56f
            WidgetIcons.draw(
                canvas,
                c.glyph,
                badgeCx - glyphSize * 0.5f,
                badgeCy - glyphSize * 0.5f,
                glyphSize,
                badgeTint,
            )
        }

        val textLeft = if (c.glyph == WidgetGlyph.NONE) {
            box.left + padX
        } else {
            badgeCx + badgeSize * 0.5f + padX * 0.85f
        }
        val avail = (box.right - padX - textLeft).coerceAtLeast(1f)
        val hasSecondary = !c.secondary.isNullOrBlank()

        if (hasSecondary) {
            // Put the category/app/artist header on top in accent tint, primary content below
            widgetSecondaryPaint.typeface = boldSans
            widgetSecondaryPaint.color = badgeTint
            widgetSecondaryPaint.textSize = box.height * 0.165f
            val overline = TextUtils.ellipsize(
                c.secondary.orEmpty(),
                widgetSecondaryPaint,
                avail,
                TextUtils.TruncateAt.END,
            ).toString()
            canvas.drawText(overline, textLeft, box.top + box.height * 0.36f, widgetSecondaryPaint)

            widgetPrimaryPaint.typeface = boldSans
            widgetPrimaryPaint.color = colors.primary
            widgetPrimaryPaint.textSize = box.height * (if (c.kind == WidgetKind.ALARM_BADGE) 0.34f else 0.26f)
            val mainLine = TextUtils.ellipsize(
                c.primary,
                widgetPrimaryPaint,
                avail,
                TextUtils.TruncateAt.END,
            ).toString()
            canvas.drawText(mainLine, textLeft, box.top + box.height * 0.72f, widgetPrimaryPaint)
        } else {
            widgetPrimaryPaint.typeface = boldSans
            widgetPrimaryPaint.color = colors.primary
            widgetPrimaryPaint.textSize = box.height * 0.31f
            val mainLine = TextUtils.ellipsize(
                c.primary,
                widgetPrimaryPaint,
                avail,
                TextUtils.TruncateAt.END,
            ).toString()
            val y = box.top + (box.height - (widgetPrimaryPaint.ascent() + widgetPrimaryPaint.descent())) * 0.5f
            canvas.drawText(mainLine, textLeft, y, widgetPrimaryPaint)
        }
    }

    private fun drawCompactFooterTile(canvas: Canvas, tile: WidgetTile, box: FaceBox) {
        val c = tile.content
        val iconSize = box.height * 0.54f
        val cy = box.top + box.height * 0.5f
        var textLeft = box.left + box.height * 0.2f
        if (c.glyph != WidgetGlyph.NONE) {
            WidgetIcons.draw(
                canvas,
                c.glyph,
                textLeft,
                cy - iconSize * 0.5f,
                iconSize,
                colors.accent,
            )
            textLeft += iconSize + box.height * 0.24f
        }
        val avail = (box.right - textLeft).coerceAtLeast(1f)
        val combined = if (c.secondary.isNullOrBlank()) c.primary else "${c.primary} · ${c.secondary}"
        widgetPrimaryPaint.typeface = mediumSans
        widgetPrimaryPaint.color = withAlpha(colors.primary, 0.88f)
        widgetPrimaryPaint.textSize = box.height * 0.44f
        val line = TextUtils.ellipsize(combined, widgetPrimaryPaint, avail, TextUtils.TruncateAt.END).toString()
        val y = box.top + (box.height - (widgetPrimaryPaint.ascent() + widgetPrimaryPaint.descent())) * 0.5f
        canvas.drawText(line, textLeft, y, widgetPrimaryPaint)
    }

    private fun drawHint(canvas: Canvas, w: Float, h: Float) {
        val hint = hintText
        if (hint.isNullOrEmpty() || hintAlpha <= 0f) return
        hintPaint.color = withAlpha(colors.secondary, 0.40f * hintAlpha)
        hintPaint.textSize = h * 0.018f
        hintPaint.typeface = mediumSans
        canvas.drawText(hint, w * 0.5f, h * 0.955f, hintPaint)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun typefaceFor(theme: LumaTheme): Typeface =
        LumaFonts.resolve(context, theme.font)

    private fun driftFor(): Pair<Float, Float> {
        if (!burnInDrift) return Pair(0f, 0f)
        val elapsedMinutes = System.currentTimeMillis() / 60_000L
        return BurnInDrift.offsetFor(elapsedMinutes, amplitudePx = 2f)
    }

    private fun buildFullDateFormatter(): SimpleDateFormat {
        val locale = Locale.getDefault()
        val pattern = DateFormat.getBestDateTimePattern(locale, "EEEEMMMMd")
        return SimpleDateFormat(pattern, locale)
    }

    private fun buildWeekdayFormatter(): SimpleDateFormat {
        val locale = Locale.getDefault()
        return SimpleDateFormat("EEE", locale)
    }

    private fun buildMonthFormatter(): SimpleDateFormat {
        val locale = Locale.getDefault()
        return SimpleDateFormat("MMM", locale)
    }

    private fun isDark(color: Int): Boolean {
        val r = Color.red(color) / 255f
        val g = Color.green(color) / 255f
        val b = Color.blue(color) / 255f
        val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
        return luma < 0.45f
    }

    private fun shiftLuminance(color: Int, delta: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[2] = (hsv[2] + delta).coerceIn(0f, 1f)
        return Color.HSVToColor(Color.alpha(color), hsv)
    }

    private fun withExactAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

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
