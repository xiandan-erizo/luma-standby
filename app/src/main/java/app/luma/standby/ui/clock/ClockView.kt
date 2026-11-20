package app.luma.standby.ui.clock

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.text.format.DateFormat
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import app.luma.standby.R
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
import app.luma.standby.platform.widgets.LumaNotificationListener
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Pure Canvas standby face modelled 1:1 after iOS 26/27 StandBy & Liquid Glass:
 * - [FaceStyle.ANALOG]: Symmetric dual squircle — sculpted circular watch dial + Calendar/Widget Hero.
 * - [FaceStyle.SPLIT]: Symmetric dual squircle — stacked Liquid Glass digital clock + Calendar/Widget Hero.
 * - [FaceStyle.FLOAT]: Fullscreen oversized bubbly duo-tone overlapping numerals (bundled Fredoka Bold).
 * - [FaceStyle.SOLAR]: Fullscreen Liquid Glass translucent numerals over atmospheric solar horizon glow.
 *
 * Strict low-power contract: plain Android View, zero Compose, zero animation loop,
 * exactly one redraw per minute boundary (or per second when seconds are enabled).
 */
class ClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    // Dedicated paints — never leak alpha/xfermode across passes.
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cardFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val cardHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val cardRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val dialStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val dialFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val numeralFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textAlign = Paint.Align.LEFT
        isSubpixelText = true
    }
    private val numeralOverlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textAlign = Paint.Align.LEFT
        isSubpixelText = true
    }
    private val numeralRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        textAlign = Paint.Align.LEFT
        isSubpixelText = true
    }
    private val colonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dateHeaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        isSubpixelText = true
    }
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
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isSubpixelText = true
    }

    private val screenBlendMode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
    private val dstOutBlendMode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    private val rectScratch = RectF()
    private val innerScratch = RectF()
    private val textBounds = Rect()
    private val clipPath = Path()
    private val layerBounds = RectF()

    private val roundedExtraBold: Typeface by lazy { LumaFonts.roundedExtraBold(context) }
    private val roundedBold: Typeface by lazy { LumaFonts.roundedBold(context) }
    private val floatFace: Typeface by lazy { LumaFonts.floatDisplay(context) }

    private var theme: LumaTheme = ThemePresets.iosFloatBlue
    private var colors: ResolvedColors = theme.resolveColors()
    private var use24Hour: Boolean = false
    private var use24HourOverride: Boolean? = null
    private var showSeconds: Boolean = false
    private var burnInDrift: Boolean = true
    private var hintText: String? = null
    private var hintAlpha: Float = 0f
    private var bannerText: String? = null

    private var widgetTiles: List<WidgetTile> = emptyList()
    private var activeAlert: StandbyNotificationAlert? = null
    private var renderedAlert: StandbyNotificationAlert? = null
    private var unreadSummary: UnreadNotificationSummary? = null
    private var renderedUnread: UnreadNotificationSummary? = null
    private val unreadPillBounds = RectF()
    private val appIconCache = LinkedHashMap<String, Drawable?>(8, 0.75f, true)
    private val nightRedIconFilter = ColorMatrixColorFilter(
        ColorMatrix(
            floatArrayOf(
                0.35f, 0.55f, 0.10f, 0f, 32f,
                0.04f, 0.06f, 0.02f, 0f, 0f,
                0.03f, 0.05f, 0.02f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            ),
        ),
    )

    // Finite, event-driven iOS StandBy physics motion (zero idle timers/loops).
    var animationsEnabled: Boolean = true
    private val linearInterpolator = LinearInterpolator()
    private val iosFluidEase = PathInterpolator(0.22f, 1.0f, 0.36f, 1.0f)
    private val iosEaseOut = PathInterpolator(0.16f, 1.0f, 0.30f, 1.0f)
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    // Master choreographed notification timeline (0..1 linear; sub-beats evaluate custom springs).
    private var alertTimeline: Float = 0f
    private var alertExiting: Boolean = false
    private var alertExitStartDragX: Float = 0f
    private var alertExitStartDragY: Float = 0f
    private var alertRevealProgress: Float = 1f
    private var alertAnimator: ValueAnimator? = null
    private var alertRevealAnimator: ValueAnimator? = null

    private var unreadPillProgress: Float = 0f
    private var unreadPillAnimator: ValueAnimator? = null

    private var hintAnimator: ValueAnimator? = null

    // Live finger-following drag & smooth spring-back / face-switch momentum
    private var dragOffsetX: Float = 0f
    private var dragOffsetY: Float = 0f
    private var dragReturnAnimator: ValueAnimator? = null

    private var outgoingBitmap: Bitmap? = null
    private var transitionProgress: Float = 1f
    private var transitionDirX: Int = 0
    private var transitionDirY: Int = 0
    private var transitionStartOffsetX: Float = 0f
    private var transitionStartOffsetY: Float = 0f
    private var transitionArmed: Boolean = false
    private var transitionAnimator: ValueAnimator? = null
    private var capturingSnapshot: Boolean = false

    /** Called once per redraw, before the frame is drawn. */
    var onTick: (() -> Unit)? = null

    private val scheduler = MinuteScheduler {
        onTick?.invoke()
        invalidate()
    }
    private val hintHandler = Handler(Looper.getMainLooper())
    private val hideHint = Runnable {
        animateHintTo(0f, clearBannerOnEnd = true)
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
        val visualChanged = theme != this.theme
        this.theme = theme
        this.colors = theme.resolveColors()
        this.use24HourOverride = use24HourOverride
        this.use24Hour = use24HourOverride ?: DateFormat.is24HourFormat(context)
        this.showSeconds = showSecondsOverride ?: theme.showSeconds
        this.burnInDrift = burnInShift
        this.hintText = hint
        scheduler.secondsEnabled = this.showSeconds
        if (hint.isNullOrEmpty() && bannerText.isNullOrEmpty()) {
            hintAnimator?.cancel()
            hintAlpha = 0f
        }
        if (transitionArmed && visualChanged) {
            transitionArmed = false
            startFaceTransitionAnimator()
        } else {
            invalidate()
        }
    }

    /**
     * Live interactive finger-following drag with soft rubber-band resistance so the
     * clock face or notification card feels physically attached to the user's touch.
     */
    fun setInteractiveDrag(rawDx: Float, rawDy: Float) {
        if (!shouldAnimate()) return
        dragReturnAnimator?.cancel()
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)
        if (activeAlert != null) {
            dragOffsetX = rubberBand(rawDx, w * 0.16f)
            dragOffsetY = rubberBand(rawDy, h * 0.28f)
        } else {
            // Lock subtly toward dominant drag axis for clean iOS horizontal/vertical feel
            val absX = kotlin.math.abs(rawDx)
            val absY = kotlin.math.abs(rawDy)
            if (absY >= absX) {
                dragOffsetX = rubberBand(rawDx * 0.18f, w * 0.04f)
                dragOffsetY = rubberBand(rawDy, h * 0.22f)
            } else {
                dragOffsetX = rubberBand(rawDx, w * 0.20f)
                dragOffsetY = rubberBand(rawDy * 0.18f, h * 0.04f)
            }
        }
        invalidate()
    }

    /** Springs the clock face or notification card smoothly back to center on cancelled drag. */
    fun releaseInteractiveDrag() {
        if (kotlin.math.abs(dragOffsetX) < 0.5f && kotlin.math.abs(dragOffsetY) < 0.5f) {
            dragOffsetX = 0f
            dragOffsetY = 0f
            return
        }
        if (!shouldAnimate()) {
            dragOffsetX = 0f
            dragOffsetY = 0f
            invalidate()
            return
        }
        val startX = dragOffsetX
        val startY = dragOffsetY
        dragReturnAnimator?.cancel()
        dragReturnAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 400L
            interpolator = linearInterpolator
            addUpdateListener {
                val p = springCurve(it.animatedValue as Float, damping = 0.78f, frequency = 1.65f)
                dragOffsetX = startX * (1f - p)
                dragOffsetY = startY * (1f - p)
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    dragOffsetX = 0f
                    dragOffsetY = 0f
                    invalidate()
                }
            })
            start()
        }
    }

    /**
     * Captures a lightweight snapshot of the outgoing face right before a vertical
     * face switch or horizontal palette switch, continuing seamlessly from the user's
     * current interactive drag offset.
     */
    fun prepareTransition(directionX: Int, directionY: Int) {
        dragReturnAnimator?.cancel()
        val capturedDragX = dragOffsetX
        val capturedDragY = dragOffsetY
        dragOffsetX = 0f
        dragOffsetY = 0f

        if (!shouldAnimate() || width <= 0 || height <= 0) {
            releaseOutgoingBitmap()
            return
        }
        transitionAnimator?.cancel()
        releaseOutgoingBitmap()
        val snapW = (width / 2).coerceAtLeast(1)
        val snapH = (height / 2).coerceAtLeast(1)
        val snap = runCatching {
            Bitmap.createBitmap(snapW, snapH, Bitmap.Config.ARGB_8888).also { bmp ->
                val c = Canvas(bmp)
                c.scale(snapW.toFloat() / width.toFloat(), snapH.toFloat() / height.toFloat())
                capturingSnapshot = true
                try {
                    drawBaseFace(c, width.toFloat(), height.toFloat())
                } finally {
                    capturingSnapshot = false
                }
            }
        }.getOrNull()
        outgoingBitmap = snap
        transitionDirX = directionX
        transitionDirY = directionY
        transitionStartOffsetX = capturedDragX
        transitionStartOffsetY = capturedDragY
        transitionProgress = 0f
        transitionArmed = snap != null
    }

    /** Show the exit hint briefly on touch with smooth fade-in and fade-out. */
    fun flashHint() {
        if (hintText.isNullOrEmpty()) return
        bannerText = null
        animateHintTo(1f, clearBannerOnEnd = false)
        hintHandler.removeCallbacks(hideHint)
        hintHandler.postDelayed(hideHint, HINT_VISIBLE_MS)
    }

    /** Show a brief Liquid Glass style-switch pill with smooth glide and fade. */
    fun flashBanner(label: String) {
        if (label.isBlank()) return
        bannerText = label
        animateHintTo(1f, clearBannerOnEnd = false)
        hintHandler.removeCallbacks(hideHint)
        hintHandler.postDelayed(hideHint, BANNER_VISIBLE_MS)
    }

    /** Replace the widget tiles; callers compute content off the redraw path. */
    fun setWidgets(tiles: List<WidgetTile>) {
        val changed = tiles != widgetTiles
        widgetTiles = tiles
        if (changed) invalidate()
    }

    /** Show, update (e.g. tap-to-reveal), or smoothly morph-dismiss the full-screen iOS alert. */
    fun setActiveAlert(alert: StandbyNotificationAlert?) {
        if (alert == activeAlert) return
        val previous = activeAlert
        activeAlert = alert
        dragReturnAnimator?.cancel()

        if (alert != null) {
            val isRevealUpdate = previous != null && !previous.revealed && alert.revealed &&
                previous.appLabel == alert.appLabel && previous.title == alert.title
            renderedAlert = alert
            dragOffsetX = 0f
            dragOffsetY = 0f

            if (!shouldAnimate()) {
                alertAnimator?.cancel()
                alertRevealAnimator?.cancel()
                alertExiting = false
                alertTimeline = 1f
                alertRevealProgress = 1f
                invalidate()
                return
            }

            if (isRevealUpdate) {
                alertRevealAnimator?.cancel()
                alertRevealProgress = 0f
                alertRevealAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 360L
                    interpolator = iosFluidEase
                    addUpdateListener {
                        alertRevealProgress = it.animatedValue as Float
                        invalidate()
                    }
                    start()
                }
            } else {
                alertRevealAnimator?.cancel()
                alertRevealProgress = 1f
                animateAlertTransition(entering = true)
            }
        } else {
            alertExitStartDragX = dragOffsetX
            alertExitStartDragY = dragOffsetY
            dragOffsetX = 0f
            dragOffsetY = 0f
            if (!shouldAnimate() || renderedAlert == null) {
                alertAnimator?.cancel()
                alertRevealAnimator?.cancel()
                renderedAlert = null
                alertExiting = false
                alertTimeline = 0f
                alertRevealProgress = 1f
                invalidate()
                return
            }
            animateAlertTransition(entering = false)
        }
    }

    /** Show or hide the sleek top-center Liquid Glass unread pill with smooth slide/fade. */
    fun setUnreadSummary(summary: UnreadNotificationSummary?) {
        if (summary == unreadSummary) return
        unreadSummary = summary
        if (summary != null) {
            renderedUnread = summary
            animateUnreadPillTo(1f)
        } else {
            unreadPillBounds.setEmpty()
            animateUnreadPillTo(0f)
        }
    }

    /** Returns true if a tap lands on the top unread notification pill (with generous touch pad). */
    fun hitTestUnreadPill(x: Float, y: Float): Boolean {
        if (activeAlert != null || unreadSummary == null || unreadPillBounds.isEmpty) return false
        val pad = 28f
        return x >= unreadPillBounds.left - pad &&
            x <= unreadPillBounds.right + pad &&
            y >= unreadPillBounds.top - pad &&
            y <= unreadPillBounds.bottom + pad
    }

    /** Drops all in-memory notification content, transition snapshots & cached app icons. */
    fun clearSensitiveMemory() {
        cancelAllAnimations()
        activeAlert = null
        renderedAlert = null
        unreadSummary = null
        renderedUnread = null
        alertExiting = false
        alertTimeline = 0f
        alertRevealProgress = 1f
        unreadPillProgress = 0f
        dragOffsetX = 0f
        dragOffsetY = 0f
        unreadPillBounds.setEmpty()
        appIconCache.clear()
        invalidate()
    }

    fun resumeTicking() {
        registerTimeReceiver()
        scheduler.start()
    }

    fun pauseTicking() {
        scheduler.stop()
        hintHandler.removeCallbacks(hideHint)
        cancelAllAnimations()
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
        cancelAllAnimations()
        unregisterTimeReceiver()
        appIconCache.clear()
        super.onDetachedFromWindow()
    }

    private fun shouldAnimate(): Boolean =
        animationsEnabled && isAttachedToWindow && ValueAnimator.areAnimatorsEnabled()

    private fun cancelAllAnimations() {
        alertAnimator?.cancel()
        alertRevealAnimator?.cancel()
        unreadPillAnimator?.cancel()
        hintAnimator?.cancel()
        dragReturnAnimator?.cancel()
        transitionAnimator?.cancel()
        transitionArmed = false
        transitionProgress = 1f
        dragOffsetX = 0f
        dragOffsetY = 0f
        releaseOutgoingBitmap()
    }

    private fun releaseOutgoingBitmap() {
        outgoingBitmap?.let { if (!it.isRecycled) it.recycle() }
        outgoingBitmap = null
    }

    private fun startFaceTransitionAnimator() {
        transitionAnimator?.cancel()
        if (!shouldAnimate() || outgoingBitmap == null) {
            transitionProgress = 1f
            releaseOutgoingBitmap()
            invalidate()
            return
        }
        val durationMs = if (transitionDirY != 0) 580L else 500L
        transitionProgress = 0f
        transitionAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = linearInterpolator
            addUpdateListener {
                transitionProgress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    transitionProgress = 1f
                    transitionStartOffsetX = 0f
                    transitionStartOffsetY = 0f
                    releaseOutgoingBitmap()
                    invalidate()
                }
            })
            start()
        }
    }

    private fun animateAlertTransition(entering: Boolean) {
        alertAnimator?.cancel()
        alertExiting = !entering
        alertTimeline = 0f

        alertAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (entering) 740L else 480L
            interpolator = linearInterpolator
            addUpdateListener {
                alertTimeline = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (!entering && activeAlert == null) {
                        renderedAlert = null
                        alertExiting = false
                        alertTimeline = 0f
                        alertExitStartDragX = 0f
                        alertExitStartDragY = 0f
                        invalidate()
                    }
                }
            })
            start()
        }
    }

    private fun animateUnreadPillTo(target: Float) {
        unreadPillAnimator?.cancel()
        if (!shouldAnimate()) {
            unreadPillProgress = target
            if (target <= 0f) renderedUnread = null
            invalidate()
            return
        }
        unreadPillAnimator = ValueAnimator.ofFloat(unreadPillProgress, target).apply {
            duration = if (target > 0f) 420L else 280L
            interpolator = iosFluidEase
            addUpdateListener {
                unreadPillProgress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (target <= 0f && unreadSummary == null) {
                        renderedUnread = null
                        unreadPillProgress = 0f
                        invalidate()
                    }
                }
            })
            start()
        }
    }

    private fun animateHintTo(target: Float, clearBannerOnEnd: Boolean) {
        hintAnimator?.cancel()
        if (!shouldAnimate()) {
            hintAlpha = target
            if (clearBannerOnEnd && target <= 0f) bannerText = null
            invalidate()
            return
        }
        hintAnimator = ValueAnimator.ofFloat(hintAlpha, target).apply {
            duration = if (target > 0f) 280L else 420L
            interpolator = if (target > 0f) iosFluidEase else iosEaseOut
            addUpdateListener {
                hintAlpha = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (clearBannerOnEnd && target <= 0f && hintAlpha <= 0.01f) {
                        hintAlpha = 0f
                        bannerText = null
                        invalidate()
                    }
                }
            })
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        shaderWidth = -1f
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val nightRed = isNightRed()
        val darkSurface = isDark(colors.background) || nightRed

        val alertVisible = renderedAlert != null
        val tAlert = alertTimeline.coerceIn(0f, 1f)
        val alertDragFrac = if (activeAlert != null) {
            (kotlin.math.hypot(dragOffsetX.toDouble(), dragOffsetY.toDouble()).toFloat() / (h * 0.35f)).coerceIn(0f, 0.55f)
        } else 0f
        val alertScrimP = when {
            !alertVisible -> 0f
            alertExiting -> (1f - easeOutCubic(tAlert)).coerceIn(0f, 1f)
            else -> (easeOutCubic(subTimeline(tAlert, 0.0f, 0.62f)) * (1f - alertDragFrac * 0.45f)).coerceIn(0f, 1f)
        }

        // Underlying clock pulls back smoothly into spatial depth (1.0 -> 0.90) as notification blooms
        val depthScale = 1f - 0.095f * alertScrimP

        canvas.save()
        if (alertScrimP > 0.001f) {
            canvas.scale(depthScale, depthScale, w * 0.5f, h * 0.5f)
        }

        val outBmp = outgoingBitmap
        if (!capturingSnapshot && outBmp != null && !outBmp.isRecycled && transitionProgress < 0.999f) {
            val rawP = transitionProgress.coerceIn(0f, 1f)
            val springP = springCurve(rawP, damping = 0.88f, frequency = 1.48f)
            val fadeOut = (1f - easeOutCubic(subTimeline(rawP, 0.0f, 0.68f))).coerceIn(0f, 1f)
            val fadeIn = easeOutCubic(subTimeline(rawP, 0.08f, 0.82f)).coerceIn(0f, 1f)

            backgroundPaint.shader = null
            backgroundPaint.color = if (darkSurface) Color.BLACK else colors.background
            canvas.drawRect(0f, 0f, w, h, backgroundPaint)

            // 1. Outgoing face glides away from the exact drag offset with velvety weight
            val outAlpha = (fadeOut * 255f).toInt()
            if (outAlpha > 0) {
                val outScale = 1f - 0.075f * springP
                val outTx = transitionStartOffsetX * (1f - springP) - transitionDirX * w * 0.22f * springP
                val outTy = transitionStartOffsetY * (1f - springP) - transitionDirY * h * 0.28f * springP
                canvas.save()
                canvas.translate(outTx, outTy)
                canvas.scale(outScale, outScale, w * 0.5f, h * 0.5f)
                bitmapPaint.alpha = outAlpha
                rectScratch.set(0f, 0f, w, h)
                canvas.drawBitmap(outBmp, null, rectScratch, bitmapPaint)
                bitmapPaint.alpha = 255
                canvas.restore()
            }

            // 2. Incoming new face glides in with weighted iOS spring momentum
            val inAlpha = (fadeIn * 255f).toInt()
            if (inAlpha > 0) {
                val inScale = 0.925f + 0.075f * springP
                val inTx = transitionDirX * w * 0.24f * (1f - springP)
                val inTy = transitionDirY * h * 0.30f * (1f - springP)
                layerBounds.set(0f, 0f, w, h)
                val inLayer = canvas.saveLayerAlpha(layerBounds, inAlpha)
                canvas.translate(inTx, inTy)
                canvas.scale(inScale, inScale, w * 0.5f, h * 0.5f)
                drawBaseFace(canvas, w, h)
                canvas.restoreToCount(inLayer)
            }
        } else {
            // Apply live finger-following rubber-band drag on the clock face
            val clockDragX = if (activeAlert == null) dragOffsetX else 0f
            val clockDragY = if (activeAlert == null) dragOffsetY else 0f
            if (kotlin.math.abs(clockDragX) > 0.5f || kotlin.math.abs(clockDragY) > 0.5f) {
                val dragMag = (kotlin.math.hypot(clockDragX.toDouble(), clockDragY.toDouble()).toFloat() / (min(w, h) * 0.45f)).coerceIn(0f, 1f)
                val dragScale = 1f - 0.032f * dragMag
                canvas.translate(clockDragX, clockDragY)
                canvas.scale(dragScale, dragScale, w * 0.5f, h * 0.5f)
            }
            drawBaseFace(canvas, w, h)
        }
        canvas.restore()

        val drift = driftFor()
        canvas.save()
        canvas.translate(drift.first, drift.second)

        // Top-center Liquid Glass unread pill: hides smoothly when card morphs open,
        // and blooms back right as the shrinking card docks into the top pill!
        val pillMask = when {
            !alertVisible -> 1f
            alertExiting -> easeOutCubic(subTimeline(tAlert, 0.58f, 1.0f))
            else -> (1f - easeOutCubic(subTimeline(tAlert, 0.0f, 0.28f)))
        }
        val pillVis = (unreadPillProgress * pillMask).coerceIn(0f, 1f)
        if (pillVis > 0.01f && renderedUnread != null) {
            drawUnreadNotificationPill(canvas, w, h, nightRed, darkSurface, pillVis)
        } else if (activeAlert != null || renderedUnread == null) {
            unreadPillBounds.setEmpty()
        }

        // Full-screen iOS StandBy Notification Card (multi-stage pill-to-card spring morph)
        val alert = renderedAlert
        if (alert != null && (alertExiting || tAlert > 0.001f)) {
            drawNotificationOverlay(canvas, w, h, alert, nightRed, darkSurface, tAlert, alertExiting, alertScrimP)
        } else {
            drawTransientIndicator(canvas, w, h, nightRed, darkSurface)
        }
        canvas.restore()
    }

    private fun drawBaseFace(canvas: Canvas, w: Float, h: Float) {
        val now = Calendar.getInstance()
        val nightRed = isNightRed()
        val darkSurface = isDark(colors.background) || nightRed

        drawBackground(canvas, w, h, nightRed, darkSurface)

        val rightWidgets = widgetTiles.filter {
            it.slot == WidgetSlot.TOP_RIGHT || it.slot == WidgetSlot.BOTTOM_RIGHT
        }
        val leftWidgets = widgetTiles.filter { it.slot == WidgetSlot.BOTTOM_LEFT }
        val hasRightWidgets = rightWidgets.isNotEmpty()
        val layout = FaceLayout.create(w, h, hasRightWidgets || leftWidgets.isNotEmpty(), theme.faceStyle)
        val landscape = w >= h

        val drift = driftFor()
        canvas.save()
        canvas.translate(drift.first, drift.second)

        if (!landscape) {
            drawPortraitFace(canvas, layout, now, rightWidgets, leftWidgets, nightRed, darkSurface)
        } else {
            when (theme.faceStyle) {
                FaceStyle.ANALOG -> {
                    drawAnalogWatch(canvas, layout.clock, now, nightRed, darkSurface)
                    drawDualRightStage(canvas, layout, now, rightWidgets, nightRed, darkSurface)
                    drawOptionalFooterTile(canvas, layout, leftWidgets, nightRed, darkSurface)
                }

                FaceStyle.SPLIT -> {
                    drawSplitDigitalCard(canvas, layout.clock, now, nightRed, darkSurface)
                    drawDualRightStage(canvas, layout, now, rightWidgets, nightRed, darkSurface)
                    drawOptionalFooterTile(canvas, layout, leftWidgets, nightRed, darkSurface)
                }

                FaceStyle.FLOAT -> {
                    drawFullscreenTopBar(canvas, layout.date, now, widgetTiles, nightRed, darkSurface, rightAlignedDate = true)
                    drawFloatFace(canvas, layout.clock, now, nightRed, darkSurface)
                }

                FaceStyle.SOLAR -> {
                    drawFullscreenTopBar(canvas, layout.date, now, widgetTiles, nightRed, darkSurface, rightAlignedDate = false)
                    drawSolarGlassFace(canvas, layout.clock, now, nightRed, darkSurface)
                }
            }
        }
        canvas.restore()
    }

    // -------------------------------------------------------------------------
    // Background + Solar Atmospheric Horizon
    // -------------------------------------------------------------------------

    private fun drawBackground(canvas: Canvas, w: Float, h: Float, nightRed: Boolean, darkSurface: Boolean) {
        if (nightRed) {
            backgroundPaint.shader = null
            backgroundPaint.color = Color.BLACK
            canvas.drawRect(0f, 0f, w, h, backgroundPaint)
        } else if (theme.gradient) {
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
            canvas.drawRect(0f, 0f, w, h, backgroundPaint)
        } else {
            if (shaderKey != null) {
                backgroundPaint.shader = null
                shaderKey = null
            }
            backgroundPaint.color = colors.background
            canvas.drawRect(0f, 0f, w, h, backgroundPaint)
        }

        // iOS StandBy Solar atmospheric glow along the lower horizon.
        if (theme.faceStyle == FaceStyle.SOLAR) {
            drawSolarAtmosphere(canvas, w, h, nightRed, darkSurface)
        }
    }

    private fun drawSolarAtmosphere(canvas: Canvas, w: Float, h: Float, nightRed: Boolean, darkSurface: Boolean) {
        if (!darkSurface) return
        val (horizonCore, horizonMid) = solarHorizonColors(nightRed)

        // Wide warm horizon bloom centered near bottom (rich sunset/aurora, zero muddy olive)
        val r1 = max(w, h) * 0.78f
        val p1 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                w * 0.50f,
                h * 1.12f,
                r1,
                intArrayOf(
                    withAlpha(horizonCore, if (nightRed) 0.34f else 0.72f),
                    withAlpha(horizonMid, if (nightRed) 0.14f else 0.34f),
                    Color.TRANSPARENT,
                ),
                floatArrayOf(0.0f, 0.44f, 1.0f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, w, h, p1)

        // Asymmetric secondary light flare (signature iOS StandBy Solar horizon depth)
        val r2 = max(w, h) * 0.50f
        val p2 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                w * 0.78f,
                h * 0.94f,
                r2,
                intArrayOf(
                    withAlpha(horizonCore, if (nightRed) 0.16f else 0.36f),
                    Color.TRANSPARENT,
                ),
                floatArrayOf(0.0f, 1.0f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, w, h, p2)
    }

    /**
     * Maps palette hues to rich luminous sunset/aurora horizon colors so yellow/gold
     * themes glow warm amber-vermilion on OLED black instead of turning muddy olive.
     */
    private fun solarHorizonColors(nightRed: Boolean): Pair<Int, Int> {
        if (nightRed) return Color.parseColor("#C41E16") to Color.parseColor("#5C0A07")
        val base = vibrantGlowAccent()
        val hsv = FloatArray(3)
        Color.colorToHSV(base, hsv)
        val hue = hsv[0]
        return when {
            // Yellow / Gold / Amber -> warm California sunset tangerine + deep vermilion
            hue in 28f..70f -> Color.parseColor("#FF6A1A") to Color.parseColor("#B8250A")
            // Cyan / Blue -> rich deep twilight sapphire + electric cyan
            hue in 180f..250f -> Color.parseColor("#0A84FF") to Color.parseColor("#1D2E7A")
            // Pink / Coral / Red -> vivid magenta-coral dusk
            hue >= 320f || hue < 28f -> Color.parseColor("#FF375F") to Color.parseColor("#7A1648")
            // Green / Teal -> aurora emerald + deep teal
            else -> Color.parseColor("#24C978") to Color.parseColor("#0D4A42")
        }
    }

    // -------------------------------------------------------------------------
    // Face 1: iOS StandBy Classic Analog Watch Dial
    // -------------------------------------------------------------------------

    private fun drawAnalogWatch(
        canvas: Canvas,
        box: FaceBox,
        now: Calendar,
        nightRed: Boolean,
        darkSurface: Boolean,
    ) {
        val diameter = min(box.width, box.height) * 0.98f
        val r = diameter * 0.5f
        val cx = box.centerX
        val cy = box.centerY
        if (r < 24f) return

        // Subtle sculpted dial base
        val dialTop = when {
            nightRed -> Color.parseColor("#0A0202")
            darkSurface -> Color.parseColor("#16171C")
            else -> Color.parseColor("#F5F6F8")
        }
        val dialBot = when {
            nightRed -> Color.BLACK
            darkSurface -> Color.parseColor("#0A0B0E")
            else -> Color.parseColor("#E6E8EE")
        }
        dialFillPaint.shader = LinearGradient(cx, cy - r, cx, cy + r, dialTop, dialBot, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r, dialFillPaint)
        dialFillPaint.shader = null

        // Liquid Glass circular specular bezel rim
        val rimStroke = (r * 0.0065f).coerceIn(1.2f, 2.6f)
        cardRimPaint.strokeWidth = rimStroke
        cardRimPaint.shader = LinearGradient(
            cx - r, cy - r, cx + r, cy + r,
            intArrayOf(
                if (nightRed) Color.argb(66, 255, 69, 58) else if (darkSurface) Color.argb(78, 255, 255, 255) else Color.argb(46, 0, 0, 0),
                if (nightRed) Color.argb(18, 255, 69, 58) else if (darkSurface) Color.argb(16, 255, 255, 255) else Color.argb(14, 0, 0, 0),
                if (nightRed) Color.argb(42, 255, 69, 58) else if (darkSurface) Color.argb(40, 255, 255, 255) else Color.argb(32, 0, 0, 0),
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, r - rimStroke * 0.5f, cardRimPaint)
        cardRimPaint.shader = null

        // 60 precision radial tick marks
        val majorColor = when {
            nightRed -> Color.parseColor("#FF3B30")
            darkSurface -> Color.argb(242, 255, 255, 255)
            else -> Color.parseColor("#1D1D1F")
        }
        val minorColor = when {
            nightRed -> Color.argb(90, 255, 59, 48)
            darkSurface -> Color.argb(78, 255, 255, 255)
            else -> Color.argb(68, 29, 29, 31)
        }
        val outerTickR = r * 0.922f
        for (i in 0 until 60) {
            val angle = Math.toRadians((i * 6.0) - 90.0)
            val cosA = cos(angle).toFloat()
            val sinA = sin(angle).toFloat()
            val major = i % 5 == 0
            val innerTickR = if (major) r * 0.832f else r * 0.872f
            dialStrokePaint.color = if (major) majorColor else minorColor
            dialStrokePaint.strokeWidth = (if (major) r * 0.022f else r * 0.0095f).coerceAtLeast(1.2f)
            canvas.drawLine(
                cx + cosA * innerTickR,
                cy + sinA * innerTickR,
                cx + cosA * outerTickR,
                cy + sinA * outerTickR,
                dialStrokePaint,
            )
        }

        // 12 crisp SF-Rounded-style hour numerals (1..12)
        numeralFillPaint.shader = null
        numeralFillPaint.alpha = 255
        numeralFillPaint.xfermode = null
        numeralFillPaint.typeface = roundedExtraBold
        numeralFillPaint.textSize = r * 0.185f
        numeralFillPaint.color = majorColor
        val numR = r * 0.680f
        for (hour in 1..12) {
            val angle = Math.toRadians((hour * 30.0) - 90.0)
            val nx = cx + cos(angle).toFloat() * numR
            val ny = cy + sin(angle).toFloat() * numR
            val label = hour.toString()
            numeralFillPaint.getTextBounds(label, 0, label.length, textBounds)
            val tx = nx - textBounds.exactCenterX()
            val ty = ny - textBounds.exactCenterY()
            canvas.drawText(label, tx, ty, numeralFillPaint)
        }

        val hour12 = now.get(Calendar.HOUR)
        val minute = now.get(Calendar.MINUTE)
        val second = now.get(Calendar.SECOND)
        val hourDeg = (hour12 + minute / 60f) * 30f
        val minuteDeg = (minute + if (showSeconds) second / 60f else 0f) * 6f
        val secondDeg = second * 6f

        val handColor = when {
            nightRed -> Color.parseColor("#FF3B30")
            darkSurface -> Color.WHITE
            else -> Color.parseColor("#1D1D1F")
        }
        val hubAccent = when {
            nightRed -> Color.parseColor("#FF453A")
            !darkSurface -> colors.accent
            colors.accent == Color.WHITE || isNearWhite(colors.accent) -> Color.parseColor("#FF9F0A")
            else -> Color.parseColor("#FF9F0A")
        }

        // Sculpted Apple Watch baton hour hand
        drawWatchBatonHand(
            canvas = canvas,
            cx = cx,
            cy = cy,
            degrees = hourDeg,
            stemLen = r * 0.165f,
            bladeLen = r * 0.515f,
            stemW = r * 0.024f,
            bladeW = r * 0.062f,
            color = handColor,
            shadow = darkSurface && !nightRed,
        )

        // Sculpted Apple Watch baton minute hand
        drawWatchBatonHand(
            canvas = canvas,
            cx = cx,
            cy = cy,
            degrees = minuteDeg,
            stemLen = r * 0.165f,
            bladeLen = r * 0.785f,
            stemW = r * 0.022f,
            bladeW = r * 0.046f,
            color = handColor,
            shadow = darkSurface && !nightRed,
        )

        // Optional crisp second hand (ONLY when user enables seconds; preserves 1 draw/min default)
        if (showSeconds) {
            canvas.save()
            canvas.translate(cx, cy)
            canvas.rotate(secondDeg)
            dialStrokePaint.color = hubAccent
            dialStrokePaint.strokeWidth = (r * 0.012f).coerceAtLeast(1.5f)
            canvas.drawLine(0f, r * 0.15f, 0f, -r * 0.855f, dialStrokePaint)
            canvas.restore()
        }

        // Iconic Apple Watch orange center hub + recessed axle pinhole
        dialFillPaint.shader = null
        dialFillPaint.color = hubAccent
        canvas.drawCircle(cx, cy, r * 0.046f, dialFillPaint)
        dialFillPaint.color = dialBot
        canvas.drawCircle(cx, cy, r * 0.018f, dialFillPaint)
    }

    private fun drawWatchBatonHand(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        degrees: Float,
        stemLen: Float,
        bladeLen: Float,
        stemW: Float,
        bladeW: Float,
        color: Int,
        shadow: Boolean,
    ) {
        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(degrees)

        if (shadow) {
            dialFillPaint.shader = null
            dialFillPaint.color = Color.argb(95, 0, 0, 0)
            val sOff = bladeW * 0.18f
            rectScratch.set(-bladeW * 0.5f + sOff, -bladeLen + sOff, bladeW * 0.5f + sOff, -stemLen + sOff)
            canvas.drawRoundRect(rectScratch, bladeW * 0.5f, bladeW * 0.5f, dialFillPaint)
        }

        // Slender stem from hub to baton
        dialStrokePaint.color = color
        dialStrokePaint.strokeWidth = stemW.coerceAtLeast(2f)
        canvas.drawLine(0f, 0f, 0f, -stemLen - bladeW * 0.35f, dialStrokePaint)

        // Rounded baton paddle
        dialFillPaint.shader = null
        dialFillPaint.color = color
        rectScratch.set(-bladeW * 0.5f, -bladeLen, bladeW * 0.5f, -stemLen)
        canvas.drawRoundRect(rectScratch, bladeW * 0.5f, bladeW * 0.5f, dialFillPaint)
        canvas.restore()
    }

    // -------------------------------------------------------------------------
    // Face 2: iOS StandBy Stacked Digital Liquid Glass Card (Left of Dual View)
    // -------------------------------------------------------------------------

    private fun drawSplitDigitalCard(
        canvas: Canvas,
        box: FaceBox,
        now: Calendar,
        nightRed: Boolean,
        darkSurface: Boolean,
    ) {
        val cornerR = min(box.width, box.height) * 0.175f
        val topFill = when {
            nightRed -> Color.parseColor("#0D0202")
            darkSurface -> Color.parseColor("#1C1E24")
            else -> Color.parseColor("#F5F6F9")
        }
        val botFill = when {
            nightRed -> Color.BLACK
            darkSurface -> Color.parseColor("#0F1014")
            else -> Color.parseColor("#E6E8EF")
        }
        drawGlassCard(canvas, box, cornerR, topFill, botFill, nightRed, darkSurface)

        val hour24 = now.get(Calendar.HOUR_OF_DAY)
        val hour = if (use24Hour) hour24 else (hour24 % 12).let { if (it == 0) 12 else it }
        val minute = now.get(Calendar.MINUTE)
        val second = now.get(Calendar.SECOND)
        val hourText = if (use24Hour) String.format(Locale.US, "%02d", hour) else hour.toString()
        val minuteText = String.format(Locale.US, "%02d", minute)
        val suffix = if (use24Hour) "" else if (hour24 < 12) "AM" else "PM"

        val face = clockTypefaceForSplit()
        val padX = box.width * 0.105f
        val padY = box.height * 0.11f
        val availW = (box.width - padX * 2f).coerceAtLeast(1f)
        val availH = (box.height - padY * 2f).coerceAtLeast(1f)
        val lineGap = availH * 0.048f
        val targetLineH = (availH - lineGap) * 0.5f

        // Measure tight glyph ink bounds on "88" so digits stay rock-steady across minutes
        val refSize = 200f
        numeralFillPaint.typeface = face
        numeralFillPaint.textSize = refSize
        numeralFillPaint.getTextBounds("88", 0, 2, textBounds)
        val refH = textBounds.height().toFloat().coerceAtLeast(1f)
        val refW = max(
            numeralFillPaint.measureText("88"),
            max(numeralFillPaint.measureText(hourText), numeralFillPaint.measureText(minuteText)),
        ).coerceAtLeast(1f)

        val scaleByH = targetLineH / refH
        val scaleByW = (availW * 0.96f) / refW
        val textSize = refSize * min(scaleByH, scaleByW)

        numeralFillPaint.textSize = textSize
        numeralFillPaint.getTextBounds("88", 0, 2, textBounds)
        val inkH = textBounds.height().toFloat()
        val blockH = inkH * 2f + lineGap
        val topY = box.centerY - blockH * 0.5f
        val hourCenterY = topY + inkH * 0.5f
        val minCenterY = topY + inkH + lineGap + inkH * 0.5f

        // Hour row (luminous crisp white / light sheen)
        val hourTopColor = when {
            nightRed -> Color.parseColor("#FF453A")
            darkSurface -> Color.WHITE
            else -> colors.primary
        }
        val hourBotColor = when {
            nightRed -> Color.parseColor("#D62820")
            darkSurface -> Color.parseColor("#DCE3EE")
            else -> shiftLuminance(colors.primary, 0.08f)
        }
        drawCenteredTightText(
            canvas = canvas,
            text = hourText,
            cx = box.centerX,
            cy = hourCenterY,
            paint = numeralFillPaint,
            topColor = hourTopColor,
            bottomColor = hourBotColor,
            inkHeight = inkH,
        )

        // Minute row (vibrant duo-tone accent sheen — classic iOS StandBy color pop)
        val accentBase = when {
            nightRed -> Color.parseColor("#CC2820")
            !darkSurface -> colors.accent
            isNearWhite(colors.accent) -> Color.parseColor("#64D2FF")
            else -> colors.accent
        }
        val minTopColor = shiftLuminance(accentBase, if (darkSurface) 0.14f else -0.06f)
        val minBotColor = shiftLuminance(accentBase, if (darkSurface) -0.10f else 0.08f)
        drawCenteredTightText(
            canvas = canvas,
            text = minuteText,
            cx = box.centerX,
            cy = minCenterY,
            paint = numeralFillPaint,
            topColor = minTopColor,
            bottomColor = minBotColor,
            inkHeight = inkH,
        )

        // Optional AM/PM indicator top-left & seconds pill bottom-right
        if (suffix.isNotEmpty()) {
            dateHeaderPaint.typeface = roundedBold
            dateHeaderPaint.textAlign = Paint.Align.LEFT
            dateHeaderPaint.textSize = (box.height * 0.068f).coerceAtLeast(11f)
            dateHeaderPaint.color = withAlpha(if (nightRed) Color.parseColor("#FF453A") else colors.secondary, 0.82f)
            canvas.drawText(suffix, box.left + padX * 0.85f, box.top + padY * 1.15f, dateHeaderPaint)
        }
        if (showSeconds) {
            val secText = String.format(Locale.US, "%02d", second)
            dateHeaderPaint.typeface = roundedBold
            dateHeaderPaint.textAlign = Paint.Align.RIGHT
            dateHeaderPaint.textSize = (box.height * 0.068f).coerceAtLeast(11f)
            dateHeaderPaint.color = withAlpha(accentBase, 0.86f)
            canvas.drawText(secText, box.right - padX * 0.85f, box.bottom - padY * 0.65f, dateHeaderPaint)
            dateHeaderPaint.textAlign = Paint.Align.LEFT
        }
    }

    // -------------------------------------------------------------------------
    // Face 3: iOS StandBy Fullscreen FLOAT (Bundled Fredoka Bold, Zero Alpha Bug)
    // -------------------------------------------------------------------------

    private fun drawFloatFace(
        canvas: Canvas,
        box: FaceBox,
        now: Calendar,
        nightRed: Boolean,
        darkSurface: Boolean,
    ) {
        val hour24 = now.get(Calendar.HOUR_OF_DAY)
        val hour = if (use24Hour) hour24 else (hour24 % 12).let { if (it == 0) 12 else it }
        val minute = now.get(Calendar.MINUTE)
        val second = now.get(Calendar.SECOND)
        val hourText = if (use24Hour) String.format(Locale.US, "%02d", hour) else hour.toString()
        val text = if (showSeconds) {
            String.format(Locale.US, "%s:%02d:%02d", hourText, minute, second)
        } else {
            String.format(Locale.US, "%s:%02d", hourText, minute)
        }

        // Harmonized iOS StandBy Float duo-tone palette (never flat gray-white vs harsh red)
        val (pairATop, pairABot, pairBTop, pairBBot) = floatPairColors(nightRed, darkSurface)

        // Measure exact tight glyph ink widths at reference size using Fredoka Bold
        val refSize = 300f
        numeralFillPaint.typeface = floatFace
        numeralFillPaint.textSize = refSize
        numeralFillPaint.shader = null
        numeralFillPaint.xfermode = null
        numeralFillPaint.alpha = 255

        numeralFillPaint.getTextBounds("8", 0, 1, textBounds)
        val refInkH = textBounds.height().toFloat().coerceAtLeast(1f)
        val refDigitW = textBounds.width().toFloat().coerceAtLeast(1f)
        val overlapPxRef = -refDigitW * 0.10f
        val colonSlotRef = refDigitW * 0.30f

        val glyphWidths = FloatArray(text.length)
        var totalRefW = 0f
        for (i in text.indices) {
            val ch = text[i]
            if (ch == ':') {
                glyphWidths[i] = colonSlotRef
                totalRefW += colonSlotRef
            } else {
                val s = ch.toString()
                numeralFillPaint.getTextBounds(s, 0, 1, textBounds)
                val gw = max(textBounds.width().toFloat(), refDigitW * 0.54f)
                glyphWidths[i] = gw
                totalRefW += gw
                val next = text.getOrNull(i + 1)
                if (next != null && next != ':') {
                    totalRefW += overlapPxRef
                }
            }
        }

        val scaleByH = (box.height * 0.87f) / refInkH
        val scaleByW = (box.width * 0.95f) / totalRefW.coerceAtLeast(1f)
        val scale = min(scaleByH, scaleByW)
        val textSize = refSize * scale
        val inkH = refInkH * scale
        val overlapPx = overlapPxRef * scale
        val colonSlotW = colonSlotRef * scale
        val totalW = totalRefW * scale

        numeralFillPaint.textSize = textSize
        numeralOverlayPaint.typeface = floatFace
        numeralOverlayPaint.textSize = textSize
        numeralRimPaint.typeface = floatFace
        numeralRimPaint.textSize = textSize

        var cursorX = box.centerX - totalW * 0.5f
        val centerY = box.centerY
        val colonCenters = ArrayList<Float>(2)

        // Subtle organic vertical bounce & playful tilt — signature iOS StandBy Float feel
        val dyPattern = floatArrayOf(-0.028f, 0.026f, -0.022f, 0.028f, -0.020f, 0.022f)
        val rotPattern = floatArrayOf(-2.4f, 2.0f, -1.8f, 2.3f, -1.6f, 1.8f)

        layerBounds.set(0f, 0f, width.toFloat(), height.toFloat())
        val layerId = canvas.saveLayer(layerBounds, null)

        var digitIdx = 0
        for (i in text.indices) {
            val ch = text[i]
            if (ch == ':') {
                colonCenters.add(cursorX + colonSlotW * 0.5f)
                cursorX += colonSlotW
                continue
            }

            val s = ch.toString()
            val slotW = glyphWidths[i] * scale
            numeralFillPaint.getTextBounds(s, 0, 1, textBounds)
            val gx = cursorX + slotW * 0.5f - textBounds.exactCenterX()
            val dy = inkH * dyPattern[digitIdx % dyPattern.size]
            val gy = centerY + dy - textBounds.exactCenterY()
            val rot = rotPattern[digitIdx % rotPattern.size]

            val isEven = digitIdx % 2 == 0
            val topColor = if (isEven) pairATop else pairBTop
            val botColor = if (isEven) pairABot else pairBBot
            val bodyAlpha = if (isEven) 246 else 224

            canvas.save()
            canvas.rotate(rot, cursorX + slotW * 0.5f, centerY + dy)

            // 1. Primary sculpted 3-stop diagonal gradient body (alpha ALWAYS reset to 255!)
            val midColor = blendColor(topColor, botColor, 0.45f)
            numeralFillPaint.alpha = 255
            numeralFillPaint.xfermode = null
            numeralFillPaint.shader = LinearGradient(
                cursorX, centerY - inkH * 0.56f,
                cursorX + slotW * 0.45f, centerY + inkH * 0.56f,
                intArrayOf(
                    withExactAlpha(topColor, bodyAlpha),
                    withExactAlpha(midColor, bodyAlpha),
                    withExactAlpha(botColor, bodyAlpha),
                ),
                floatArrayOf(0f, 0.42f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawText(s, gx, gy, numeralFillPaint)
            numeralFillPaint.shader = null

            // 2. Luminous translucent overlap bloom on companion digits (SEPARATE paint!)
            if (darkSurface && !nightRed && !isEven) {
                numeralOverlayPaint.xfermode = screenBlendMode
                numeralOverlayPaint.shader = null
                numeralOverlayPaint.color = withExactAlpha(topColor, 76)
                canvas.drawText(s, gx, gy, numeralOverlayPaint)
                numeralOverlayPaint.xfermode = null
                numeralOverlayPaint.alpha = 255
            }

            canvas.restore()

            val next = text.getOrNull(i + 1)
            cursorX += slotW + if (next != null && next != ':') overlapPx else 0f
            digitIdx++
        }

        // Refined pearlescent frosted colon dots
        val dotR = (inkH * 0.052f).coerceIn(5f, 40f)
        val dotTop = when {
            nightRed -> Color.argb(235, 255, 82, 72)
            darkSurface -> blendColor(Color.argb(246, 255, 255, 255), withExactAlpha(pairBTop, 246), 0.22f)
            else -> withAlpha(colors.secondary, 0.88f)
        }
        val dotBot = when {
            nightRed -> Color.argb(210, 210, 34, 26)
            darkSurface -> blendColor(Color.argb(220, 215, 224, 238), withExactAlpha(pairBBot, 220), 0.30f)
            else -> withAlpha(colors.secondary, 0.72f)
        }
        for (cx in colonCenters) {
            val tilt = dotR * 0.14f
            val y1 = centerY - inkH * 0.145f
            val y2 = centerY + inkH * 0.145f
            colonPaint.shader = LinearGradient(cx, y1 - dotR, cx, y1 + dotR, dotTop, dotBot, Shader.TileMode.CLAMP)
            canvas.drawCircle(cx + tilt, y1, dotR, colonPaint)
            colonPaint.shader = LinearGradient(cx, y2 - dotR, cx, y2 + dotR, dotTop, dotBot, Shader.TileMode.CLAMP)
            canvas.drawCircle(cx - tilt, y2, dotR, colonPaint)
        }
        colonPaint.shader = null

        canvas.restoreToCount(layerId)
    }

    /**
     * Curated luminous duo-tone gradients for [FaceStyle.FLOAT].
     * Ensures white-primary themes (StandBy Coral, StandBy Mint) blossom into rich
     * harmonious Apple pastel/glass companions instead of flat gray-white vs red.
     */
    private fun floatPairColors(nightRed: Boolean, darkSurface: Boolean): FloatColorQuad {
        if (nightRed) {
            return FloatColorQuad(
                aTop = Color.parseColor("#FF4A3F"),
                aBot = Color.parseColor("#C72219"),
                bTop = Color.parseColor("#C92A22"),
                bBot = Color.parseColor("#85120C"),
            )
        }
        if (!darkSurface) {
            return FloatColorQuad(
                aTop = shiftLuminance(colors.primary, -0.05f),
                aBot = shiftLuminance(colors.primary, 0.08f),
                bTop = shiftLuminance(colors.accent, -0.04f),
                bBot = shiftLuminance(colors.accent, 0.10f),
            )
        }
        // When primary is near-white (e.g. StandBy Coral / StandBy Mint), harmonize with a vibrant warm/cool companion
        if (isNearWhite(colors.primary) && !isNearWhite(colors.accent)) {
            val accentTop = blendColor(shiftLuminance(colors.accent, 0.16f), Color.WHITE, 0.18f)
            val accentBot = shiftLuminance(colors.accent, -0.16f)
            val pastelTop = blendColor(Color.WHITE, colors.accent, 0.22f)
            val pastelBot = blendColor(Color.WHITE, colors.accent, 0.52f)
            return FloatColorQuad(accentTop, accentBot, pastelTop, pastelBot)
        }
        val baseA = if (colors.accent == colors.primary) Color.parseColor("#0A84FF") else colors.accent
        val baseB = if (colors.accent == colors.primary) Color.parseColor("#64D2FF") else colors.primary
        return FloatColorQuad(
            aTop = blendColor(shiftLuminance(baseA, 0.16f), Color.WHITE, 0.12f),
            aBot = shiftLuminance(baseA, -0.12f),
            bTop = blendColor(shiftLuminance(baseB, 0.16f), Color.WHITE, 0.18f),
            bBot = shiftLuminance(baseB, -0.08f),
        )
    }

    private data class FloatColorQuad(val aTop: Int, val aBot: Int, val bTop: Int, val bBot: Int)

    // -------------------------------------------------------------------------
    // Face 4: iOS 26/27 Liquid Glass + Solar Horizon Fullscreen Digital Clock
    // -------------------------------------------------------------------------

    private fun drawSolarGlassFace(
        canvas: Canvas,
        box: FaceBox,
        now: Calendar,
        nightRed: Boolean,
        darkSurface: Boolean,
    ) {
        val hour24 = now.get(Calendar.HOUR_OF_DAY)
        val hour = if (use24Hour) hour24 else (hour24 % 12).let { if (it == 0) 12 else it }
        val minute = now.get(Calendar.MINUTE)
        val second = now.get(Calendar.SECOND)
        val hourText = if (use24Hour) String.format(Locale.US, "%02d", hour) else hour.toString()
        val minuteText = String.format(Locale.US, "%02d", minute)
        val secText = if (showSeconds) String.format(Locale.US, ":%02d", second) else ""
        val fullSample = "$hourText:$minuteText$secText"

        val face = clockTypefaceForSplit()
        val refSize = 300f
        numeralFillPaint.typeface = face
        numeralFillPaint.textSize = refSize
        numeralFillPaint.shader = null
        numeralFillPaint.alpha = 255
        numeralFillPaint.getTextBounds("88", 0, 2, textBounds)
        val refInkH = textBounds.height().toFloat().coerceAtLeast(1f)
        val refTotalW = numeralFillPaint.measureText(fullSample).coerceAtLeast(1f)

        val scaleByH = (box.height * 0.72f) / refInkH
        val scaleByW = (box.width * 0.90f) / refTotalW
        val textSize = refSize * min(scaleByH, scaleByW)

        numeralFillPaint.textSize = textSize
        numeralRimPaint.typeface = face
        numeralRimPaint.textSize = textSize
        numeralOverlayPaint.typeface = face
        numeralOverlayPaint.textSize = textSize

        val hourW = numeralFillPaint.measureText(hourText)
        val minW = numeralFillPaint.measureText(minuteText)
        val secW = if (secText.isNotEmpty()) numeralFillPaint.measureText(secText) else 0f
        val colonGap = textSize * 0.24f
        val totalW = hourW + colonGap + minW + secW

        numeralFillPaint.getTextBounds("88", 0, 2, textBounds)
        val inkH = textBounds.height().toFloat()
        val centerY = box.centerY - box.height * 0.02f
        val baseline = centerY - textBounds.exactCenterY()
        val startX = box.centerX - totalW * 0.5f

        val topY = centerY - inkH * 0.52f
        val botY = centerY + inkH * 0.52f

        // Render Liquid Glass numerals (soft depth shadow -> frosted translucent glass fill ->
        // pure exterior specular rim punched out via DST_OUT so overlapping TTF contours never show!).
        fun drawGlassRun(runText: String, x: Float) {
            // 1. Subtle elevation shadow
            if (darkSurface && !nightRed) {
                numeralOverlayPaint.xfermode = null
                numeralOverlayPaint.shader = null
                numeralOverlayPaint.color = Color.argb(86, 0, 0, 0)
                canvas.drawText(runText, x, baseline + inkH * 0.026f, numeralOverlayPaint)
            }

            // 2. Frosted translucent Liquid Glass body gradient
            val glassTop = when {
                nightRed -> Color.argb(228, 255, 69, 58)
                darkSurface -> Color.argb(220, 255, 255, 255)
                else -> colors.primary
            }
            val glassBot = when {
                nightRed -> Color.argb(145, 190, 28, 22)
                darkSurface -> Color.argb(118, 255, 240, 226)
                else -> shiftLuminance(colors.primary, 0.14f)
            }
            numeralFillPaint.alpha = 255
            numeralFillPaint.xfermode = null
            numeralFillPaint.shader = LinearGradient(x, topY, x, botY, glassTop, glassBot, Shader.TileMode.CLAMP)
            canvas.drawText(runText, x, baseline, numeralFillPaint)
            numeralFillPaint.shader = null

            // 3. Pure exterior Liquid Glass specular refraction rim (zero internal TTF triangles)
            if (darkSurface) {
                val rimW = (inkH * 0.011f).coerceIn(1.6f, 4.0f)
                layerBounds.set(box.left, box.top, box.right, box.bottom)
                val rimLayer = canvas.saveLayer(layerBounds, null)
                numeralRimPaint.alpha = 255
                numeralRimPaint.xfermode = null
                numeralRimPaint.strokeWidth = rimW * 2f
                numeralRimPaint.shader = LinearGradient(
                    x, topY, x + inkH * 0.75f, botY,
                    intArrayOf(
                        if (nightRed) Color.argb(190, 255, 115, 105) else Color.argb(235, 255, 255, 255),
                        if (nightRed) Color.argb(35, 255, 69, 58) else Color.argb(42, 255, 255, 255),
                        if (nightRed) Color.argb(105, 255, 69, 58) else Color.argb(150, 255, 240, 225),
                    ),
                    floatArrayOf(0f, 0.48f, 1f),
                    Shader.TileMode.CLAMP,
                )
                canvas.drawText(runText, x, baseline, numeralRimPaint)
                numeralRimPaint.shader = null

                // Punch out the interior fill so ONLY the crisp exterior glass edge remains
                numeralOverlayPaint.shader = null
                numeralOverlayPaint.color = Color.BLACK
                numeralOverlayPaint.xfermode = dstOutBlendMode
                canvas.drawText(runText, x, baseline, numeralOverlayPaint)
                numeralOverlayPaint.xfermode = null
                numeralOverlayPaint.alpha = 255
                canvas.restoreToCount(rimLayer)
            }
        }

        drawGlassRun(hourText, startX)
        drawGlassRun(minuteText, startX + hourW + colonGap)
        if (secText.isNotEmpty()) {
            drawGlassRun(secText, startX + hourW + colonGap + minW)
        }

        // Optical frosted glass colon dots centered on ink vertical center
        val colonX = startX + hourW + colonGap * 0.5f
        val dotR = (inkH * 0.052f).coerceIn(4f, 26f)
        colonPaint.shader = LinearGradient(
            colonX, centerY - inkH * 0.22f, colonX, centerY + inkH * 0.22f,
            if (nightRed) Color.argb(220, 255, 69, 58) else if (darkSurface) Color.argb(225, 255, 255, 255) else colors.primary,
            if (nightRed) Color.argb(150, 200, 32, 24) else if (darkSurface) Color.argb(155, 210, 226, 245) else colors.secondary,
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(colonX, centerY - inkH * 0.16f, dotR, colonPaint)
        canvas.drawCircle(colonX, centerY + inkH * 0.16f, dotR, colonPaint)
        colonPaint.shader = null
    }

    // -------------------------------------------------------------------------
    // Top Bar for Fullscreen Clocks (FLOAT & SOLAR): Date + Compact Glass Pills
    // -------------------------------------------------------------------------

    private fun drawFullscreenTopBar(
        canvas: Canvas,
        box: FaceBox,
        now: Calendar,
        tiles: List<WidgetTile>,
        nightRed: Boolean,
        darkSurface: Boolean,
        rightAlignedDate: Boolean,
    ) {
        val date = now.time
        val locale = Locale.getDefault()
        val weekday = weekdayFormatter.format(date).uppercase(locale)
        val dayNum = now.get(Calendar.DAY_OF_MONTH).toString()
        val fullDate = fullDateFormatter.format(date)

        val accentColor = if (nightRed) Color.parseColor("#FF453A") else colors.accent
        val textColor = when {
            nightRed -> Color.parseColor("#FF3B30")
            darkSurface -> Color.argb(235, 255, 255, 255)
            else -> colors.primary
        }

        dateHeaderPaint.typeface = roundedBold
        val fontSize = (box.height * 0.44f).coerceIn(13f, 52f)
        dateHeaderPaint.textSize = fontSize
        val baseline = box.centerY - (dateHeaderPaint.ascent() + dateHeaderPaint.descent()) * 0.5f

        if (rightAlignedDate) {
            // Top-right crisp `周五 20` (iconic Float badge)
            dateHeaderPaint.textAlign = Paint.Align.RIGHT
            val dayW = dateHeaderPaint.measureText("  $dayNum")
            dateHeaderPaint.color = textColor
            canvas.drawText(dayNum, box.right, baseline, dateHeaderPaint)
            dateHeaderPaint.color = accentColor
            canvas.drawText(weekday, box.right - dayW, baseline, dateHeaderPaint)
            dateHeaderPaint.textAlign = Paint.Align.LEFT

            // Compact Liquid Glass widget pills aligned top-left (if widgets are active)
            if (tiles.isNotEmpty()) {
                val maxW = (box.width - dateHeaderPaint.measureText("$weekday  $dayNum") - box.height * 0.5f).coerceAtLeast(0f)
                drawCompactGlassPills(canvas, box.left, box.top, maxW, box.height, tiles.take(3), nightRed, darkSurface, alignRight = false)
            }
        } else {
            // Left-aligned full date (`11月20日 星期五`) + right-aligned compact glass widget pills
            dateHeaderPaint.textAlign = Paint.Align.LEFT
            dateHeaderPaint.color = withAlpha(textColor, 0.88f)
            val dateW = dateHeaderPaint.measureText(fullDate)
            canvas.drawText(fullDate, box.left, baseline, dateHeaderPaint)

            if (tiles.isNotEmpty()) {
                val maxW = (box.width - dateW - box.height * 0.5f).coerceAtLeast(0f)
                drawCompactGlassPills(canvas, box.right - maxW, box.top, maxW, box.height, tiles.take(3), nightRed, darkSurface, alignRight = true)
            }
        }
    }

    private fun drawCompactGlassPills(
        canvas: Canvas,
        areaLeft: Float,
        areaTop: Float,
        maxWidth: Float,
        areaHeight: Float,
        tiles: List<WidgetTile>,
        nightRed: Boolean,
        darkSurface: Boolean,
        alignRight: Boolean,
    ) {
        if (maxWidth < areaHeight * 1.4f || tiles.isEmpty()) return
        val pillH = areaHeight * 0.72f
        val pillTop = areaTop + (areaHeight - pillH) * 0.5f
        val pillGap = pillH * 0.24f
        val padX = pillH * 0.36f
        val iconSize = pillH * 0.50f
        val iconGap = pillH * 0.20f

        widgetSecondaryPaint.typeface = roundedBold
        widgetSecondaryPaint.textSize = pillH * 0.46f

        data class PillSpec(val tile: WidgetTile, val label: String, val width: Float)
        val specs = ArrayList<PillSpec>(tiles.size)
        var usedW = 0f
        for (tile in tiles) {
            val c = tile.content
            val shortLabel = when (c.kind) {
                WidgetKind.WEATHER_HERO -> {
                    val city = c.secondary?.substringBefore(" · ")?.trim().orEmpty()
                    if (city.isNotEmpty()) "$city ${c.primary}" else c.primary
                }
                else -> c.primary
            }
            val textW = widgetSecondaryPaint.measureText(shortLabel).coerceAtMost(maxWidth * 0.45f)
            val glyphW = if (c.glyph != WidgetGlyph.NONE) iconSize + iconGap else 0f
            val pw = padX * 2f + glyphW + textW
            val nextUsed = usedW + (if (specs.isNotEmpty()) pillGap else 0f) + pw
            if (nextUsed > maxWidth) break
            specs.add(PillSpec(tile, shortLabel, pw))
            usedW = nextUsed
        }

        var cursorX = if (alignRight) areaLeft + maxWidth - usedW else areaLeft
        for (spec in specs) {
            val pillBox = FaceBox(cursorX, pillTop, cursorX + spec.width, pillTop + pillH)
            val fillTop = when {
                nightRed -> Color.argb(38, 255, 59, 48)
                darkSurface -> Color.argb(34, 255, 255, 255)
                else -> Color.argb(18, 0, 0, 0)
            }
            val fillBot = when {
                nightRed -> Color.argb(22, 255, 59, 48)
                darkSurface -> Color.argb(18, 255, 255, 255)
                else -> Color.argb(12, 0, 0, 0)
            }
            drawGlassCard(canvas, pillBox, pillH * 0.5f, fillTop, fillBot, nightRed, darkSurface)

            var tx = pillBox.left + padX
            val c = spec.tile.content
            if (c.glyph != WidgetGlyph.NONE) {
                val iconColor = glyphAccentColor(c, nightRed, darkSurface)
                WidgetIcons.draw(
                    canvas = canvas,
                    glyph = c.glyph,
                    x = tx,
                    y = pillBox.centerY - iconSize * 0.5f,
                    size = iconSize,
                    color = iconColor,
                    secondaryColor = if (nightRed) Color.parseColor("#FF453A") else Color.WHITE,
                    nightRed = nightRed,
                )
                tx += iconSize + iconGap
            }
            val avail = (pillBox.right - padX - tx).coerceAtLeast(1f)
            widgetSecondaryPaint.color = when {
                nightRed -> Color.parseColor("#FF453A")
                darkSurface -> Color.argb(235, 255, 255, 255)
                else -> colors.primary
            }
            val clipped = TextUtils.ellipsize(spec.label, widgetSecondaryPaint, avail, TextUtils.TruncateAt.END).toString()
            val y = pillBox.centerY - (widgetSecondaryPaint.ascent() + widgetSecondaryPaint.descent()) * 0.5f
            canvas.drawText(clipped, tx, y, widgetSecondaryPaint)

            cursorX += spec.width + pillGap
        }
    }

    // -------------------------------------------------------------------------
    // Dual-View Right Stage (ANALOG & SPLIT): Calendar Hero OR 1/2/3 Cards
    // -------------------------------------------------------------------------

    private fun drawDualRightStage(
        canvas: Canvas,
        layout: FaceLayout,
        now: Calendar,
        rightWidgets: List<WidgetTile>,
        nightRed: Boolean,
        darkSurface: Boolean,
    ) {
        if (rightWidgets.isEmpty()) {
            // Iconic iOS StandBy Calendar Hero Square (with mini month calendar grid!)
            drawCalendarHeroSquare(canvas, layout.date, now, nightRed, darkSurface)
            return
        }

        val visible = rightWidgets.take(3)
        val boxes = distributeRightBoxes(layout.rightTiles, visible.size)
        val heroSquare = visible.size == 1
        visible.forEachIndexed { i, tile ->
            drawWidgetCard(canvas, tile, boxes[i], now, heroSquare, nightRed, darkSurface)
        }
    }

    /**
     * 1:1 iOS StandBy Calendar Hero Square:
     * Top row pairs oversized crisp day numeral with accent weekday & month;
     * Bottom 56% renders an authentic 7-column current-month grid with today circled.
     */
    private fun drawCalendarHeroSquare(
        canvas: Canvas,
        box: FaceBox,
        now: Calendar,
        nightRed: Boolean,
        darkSurface: Boolean,
    ) {
        val cornerR = min(box.width, box.height) * 0.175f
        val topFill = when {
            nightRed -> Color.parseColor("#0D0202")
            darkSurface -> Color.parseColor("#1D1E24")
            else -> Color.parseColor("#F5F6F9")
        }
        val botFill = when {
            nightRed -> Color.BLACK
            darkSurface -> Color.parseColor("#101116")
            else -> Color.parseColor("#E6E8EF")
        }
        drawGlassCard(canvas, box, cornerR, topFill, botFill, nightRed, darkSurface)

        val padX = box.width * 0.095f
        val padTop = box.height * 0.085f
        val padBot = box.height * 0.075f
        val innerLeft = box.left + padX
        val innerRight = box.right - padX
        val innerW = (innerRight - innerLeft).coerceAtLeast(1f)

        val locale = Locale.getDefault()
        val isZh = locale.language.startsWith("zh")
        val weekday = weekdayFormatter.format(now.time).uppercase(locale)
        val month = monthFormatter.format(now.time).uppercase(locale)
        val dayNum = now.get(Calendar.DAY_OF_MONTH).toString()

        val accentColor = when {
            nightRed -> Color.parseColor("#FF453A")
            !darkSurface -> colors.accent
            isNearWhite(colors.accent) -> Color.parseColor("#FF453A")
            else -> colors.accent
        }
        val primaryText = when {
            nightRed -> Color.parseColor("#FF3B30")
            darkSurface -> Color.WHITE
            else -> colors.primary
        }
        val mutedText = when {
            nightRed -> Color.argb(135, 255, 59, 48)
            darkSurface -> Color.argb(135, 255, 255, 255)
            else -> withAlpha(colors.secondary, 0.78f)
        }

        // Top Header Band (0..36%): Huge day numeral on left + stacked accent Weekday & Month on right
        val headerH = box.height * 0.275f
        val headerTop = box.top + padTop
        val headerMidY = headerTop + headerH * 0.5f

        numeralFillPaint.shader = null
        numeralFillPaint.alpha = 255
        numeralFillPaint.xfermode = null
        numeralFillPaint.typeface = roundedExtraBold
        numeralFillPaint.textSize = headerH * 1.02f
        numeralFillPaint.getTextBounds(dayNum, 0, dayNum.length, textBounds)
        val dayScale = min((headerH * 0.88f) / textBounds.height().toFloat().coerceAtLeast(1f), (innerW * 0.44f) / textBounds.width().toFloat().coerceAtLeast(1f))
        numeralFillPaint.textSize = headerH * 1.02f * dayScale
        numeralFillPaint.getTextBounds(dayNum, 0, dayNum.length, textBounds)
        numeralFillPaint.color = primaryText
        val dayDrawX = innerLeft - textBounds.left
        val dayDrawY = headerMidY - textBounds.exactCenterY()
        canvas.drawText(dayNum, dayDrawX, dayDrawY, numeralFillPaint)

        val metaLeft = innerLeft + textBounds.width() + innerW * 0.075f
        val metaAvail = (innerRight - metaLeft).coerceAtLeast(1f)
        dateHeaderPaint.typeface = roundedExtraBold
        dateHeaderPaint.textAlign = Paint.Align.LEFT
        var wkSize = headerH * 0.34f
        dateHeaderPaint.textSize = wkSize
        wkSize *= ClockTypography.shrinkFactor(dateHeaderPaint.measureText(weekday), metaAvail)
        dateHeaderPaint.textSize = wkSize
        dateHeaderPaint.color = accentColor
        canvas.drawText(weekday, metaLeft, headerMidY - headerH * 0.06f, dateHeaderPaint)

        dateHeaderPaint.typeface = roundedBold
        var moSize = headerH * 0.24f
        dateHeaderPaint.textSize = moSize
        moSize *= ClockTypography.shrinkFactor(dateHeaderPaint.measureText(month), metaAvail)
        dateHeaderPaint.textSize = moSize
        dateHeaderPaint.color = mutedText
        canvas.drawText(month, metaLeft, headerMidY + headerH * 0.30f, dateHeaderPaint)

        // Subtle translucent divider
        val divY = box.top + box.height * 0.395f
        dialStrokePaint.strokeWidth = 1.4f
        dialStrokePaint.color = if (nightRed) Color.argb(42, 255, 59, 48) else if (darkSurface) Color.argb(26, 255, 255, 255) else Color.argb(24, 0, 0, 0)
        canvas.drawLine(innerLeft, divY, innerRight, divY, dialStrokePaint)

        // Lower Mini Month Calendar Grid (7 columns x 7 rows: weekday headers + up to 6 weeks)
        val gridTop = divY + box.height * 0.032f
        val gridBottom = box.bottom - padBot
        val gridH = (gridBottom - gridTop).coerceAtLeast(1f)
        val colW = innerW / 7f

        val cal = (now.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, 1) }
        val firstDayOffset = cal.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY // 0=Sun..6=Sat
        val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val today = now.get(Calendar.DAY_OF_MONTH)
        val totalWeeks = ((firstDayOffset + daysInMonth + 6) / 7).coerceIn(4, 6)
        val rowCount = totalWeeks + 1
        val rowH = gridH / rowCount

        val dayLabels = if (isZh) arrayOf("日", "一", "二", "三", "四", "五", "六") else arrayOf("S", "M", "T", "W", "T", "F", "S")
        dateHeaderPaint.typeface = roundedBold
        dateHeaderPaint.textAlign = Paint.Align.CENTER
        dateHeaderPaint.textSize = min(rowH * 0.44f, colW * 0.42f)
        val headY = gridTop + rowH * 0.5f - (dateHeaderPaint.ascent() + dateHeaderPaint.descent()) * 0.5f
        for (col in 0 until 7) {
            dateHeaderPaint.color = if (col == 0 || col == 6) withAlpha(accentColor, 0.72f) else mutedText
            canvas.drawText(dayLabels[col], innerLeft + colW * (col + 0.5f), headY, dateHeaderPaint)
        }

        val cellFontSize = min(rowH * 0.50f, colW * 0.46f)
        dateHeaderPaint.textSize = cellFontSize
        val pillR = min(rowH, colW) * 0.41f
        for (d in 1..daysInMonth) {
            val slot = firstDayOffset + (d - 1)
            val row = 1 + slot / 7
            val col = slot % 7
            val cx = innerLeft + colW * (col + 0.5f)
            val cy = gridTop + rowH * (row + 0.5f)
            val isToday = d == today

            if (isToday) {
                dialFillPaint.shader = null
                dialFillPaint.color = accentColor
                canvas.drawCircle(cx, cy, pillR, dialFillPaint)
                dateHeaderPaint.typeface = roundedExtraBold
                dateHeaderPaint.color = if (nightRed) Color.BLACK else Color.WHITE
            } else {
                dateHeaderPaint.typeface = roundedBold
                dateHeaderPaint.color = if (col == 0 || col == 6) withAlpha(primaryText, 0.56f) else withAlpha(primaryText, 0.86f)
            }
            val label = d.toString()
            dateHeaderPaint.getTextBounds(label, 0, label.length, textBounds)
            canvas.drawText(label, cx, cy - textBounds.exactCenterY(), dateHeaderPaint)
        }
        dateHeaderPaint.textAlign = Paint.Align.LEFT
    }

    // -------------------------------------------------------------------------
    // Widget Cards (Full Hero Square OR Stacked Medium/Compact Cards)
    // -------------------------------------------------------------------------

    private fun distributeRightBoxes(template: List<FaceBox>, count: Int): List<FaceBox> {
        if (count <= 0 || template.isEmpty()) return emptyList()
        val left = template.first().left
        val right = template.first().right
        val top = template.first().top
        val bottom = template.last().bottom
        val span = bottom - top

        return when (count) {
            1 -> listOf(FaceBox(left, top, right, bottom)) // Full-size Hero Square flush with left clock!
            2 -> {
                val gap = span * 0.055f
                val cardH = (span - gap) * 0.5f
                listOf(
                    FaceBox(left, top, right, top + cardH),
                    FaceBox(left, top + cardH + gap, right, bottom),
                )
            }
            else -> template.take(count)
        }
    }

    private fun drawWidgetCard(
        canvas: Canvas,
        tile: WidgetTile,
        box: FaceBox,
        now: Calendar,
        heroSquare: Boolean,
        nightRed: Boolean,
        darkSurface: Boolean,
    ) {
        when (tile.content.kind) {
            WidgetKind.WEATHER_HERO -> drawWeatherCard(canvas, tile, box, now, heroSquare, nightRed, darkSurface)
            WidgetKind.BATTERY_RING -> drawBatteryCard(canvas, tile, box, heroSquare, nightRed, darkSurface)
            else -> drawComplicationCard(canvas, tile, box, heroSquare, nightRed, darkSurface)
        }
    }

    /**
     * 1:1 iOS StandBy Weather Card:
     * Atmospheric sky gradient (condition + day/night aware), soft radial sun glow,
     * filled vector iOS location pointer, oversized crisp temperature, and SF-Symbols weather graphic.
     */
    private fun drawWeatherCard(
        canvas: Canvas,
        tile: WidgetTile,
        box: FaceBox,
        now: Calendar,
        heroSquare: Boolean,
        nightRed: Boolean,
        darkSurface: Boolean,
    ) {
        val c = tile.content
        val cornerR = if (heroSquare) min(box.width, box.height) * 0.175f else min(box.height * 0.24f, box.width * 0.10f)
        val hour24 = now.get(Calendar.HOUR_OF_DAY)
        val isNightSky = hour24 >= 19 || hour24 < 6

        val (skyTop, skyBot) = weatherSkyGradient(c.glyph, isNightSky, nightRed, darkSurface)
        drawGlassCard(canvas, box, cornerR, skyTop, skyBot, nightRed, darkSurface, skyCard = !nightRed && darkSurface)

        // Subtle atmospheric sun/moon glow inside the card clipped to rounded corners
        if (!nightRed && darkSurface && (c.glyph == WidgetGlyph.SUN || c.glyph == WidgetGlyph.PARTLY_CLOUDY)) {
            canvas.save()
            clipPath.reset()
            rectScratch.set(box.left, box.top, box.right, box.bottom)
            clipPath.addRoundRect(rectScratch, cornerR, cornerR, Path.Direction.CW)
            canvas.clipPath(clipPath)
            val glowColor = if (isNightSky) Color.parseColor("#A5C8FF") else Color.parseColor("#FFF4D4")
            val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    box.right - box.width * 0.14f,
                    box.top + box.height * 0.16f,
                    max(box.width, box.height) * 0.54f,
                    intArrayOf(withAlpha(glowColor, 0.26f), withAlpha(glowColor, 0.06f), Color.TRANSPARENT),
                    floatArrayOf(0f, 0.52f, 1f),
                    Shader.TileMode.CLAMP,
                )
            }
            canvas.drawRect(rectScratch, glowPaint)
            canvas.restore()
        }

        val parts = c.secondary?.split(" · ")?.filter { it.isNotBlank() }.orEmpty()
        val cityLabel = parts.firstOrNull() ?: ""
        val condLabel = if (parts.size >= 2) parts.drop(1).joinToString(" · ") else ""

        val onSkyPrimary = if (nightRed) Color.parseColor("#FF3B30") else if (darkSurface) Color.WHITE else colors.primary
        val onSkySecondary = if (nightRed) Color.argb(185, 255, 69, 58) else if (darkSurface) Color.argb(215, 236, 244, 255) else colors.secondary
        val iconPrimary = when {
            nightRed -> Color.parseColor("#FF453A")
            c.glyph == WidgetGlyph.SUN || c.glyph == WidgetGlyph.THUNDER -> Color.parseColor("#FFD60A")
            c.glyph == WidgetGlyph.RAIN || c.glyph == WidgetGlyph.SNOW -> Color.parseColor("#70D7FF")
            else -> Color.WHITE
        }
        val iconSecondary = if (nightRed) Color.parseColor("#FF453A") else Color.WHITE

        if (heroSquare) {
            val padX = box.width * 0.095f
            val padY = box.height * 0.090f
            val innerLeft = box.left + padX
            val innerRight = box.right - padX
            val avail = (innerRight - innerLeft).coerceAtLeast(1f)

            // Top row: City + filled vector location arrow on left; subtle weekday + day on right
            val citySize = box.height * 0.088f
            widgetSecondaryPaint.typeface = roundedExtraBold
            widgetSecondaryPaint.textSize = citySize
            widgetSecondaryPaint.color = onSkyPrimary

            val dateBadge = "${weekdayFormatter.format(now.time).uppercase(Locale.getDefault())} ${now.get(Calendar.DAY_OF_MONTH)}"
            dateHeaderPaint.typeface = roundedBold
            dateHeaderPaint.textSize = box.height * 0.062f
            dateHeaderPaint.textAlign = Paint.Align.RIGHT
            dateHeaderPaint.color = withAlpha(onSkySecondary, 0.80f)
            val dateW = dateHeaderPaint.measureText(dateBadge)
            val topBaseline = box.top + padY - widgetSecondaryPaint.ascent()
            canvas.drawText(dateBadge, innerRight, topBaseline, dateHeaderPaint)
            dateHeaderPaint.textAlign = Paint.Align.LEFT

            if (cityLabel.isNotEmpty()) {
                val arrowSize = citySize * 0.64f
                val maxCityW = (avail - dateW - arrowSize - padX * 0.45f).coerceAtLeast(avail * 0.35f)
                val clippedCity = TextUtils.ellipsize(cityLabel, widgetSecondaryPaint, maxCityW, TextUtils.TruncateAt.END).toString()
                canvas.drawText(clippedCity, innerLeft, topBaseline, widgetSecondaryPaint)
                if (parts.size >= 2) {
                    val arrowX = innerLeft + widgetSecondaryPaint.measureText(clippedCity) + citySize * 0.22f
                    val arrowY = topBaseline - arrowSize * 0.92f
                    WidgetIcons.drawLocationArrow(canvas, arrowX, arrowY, arrowSize, onSkyPrimary)
                }
            }

            // Middle: Massive crisp iOS temperature on left + rich SF-Symbols illustration on right
            val tempSize = box.height * 0.34f
            numeralFillPaint.shader = null
            numeralFillPaint.alpha = 255
            numeralFillPaint.xfermode = null
            numeralFillPaint.typeface = roundedBold
            numeralFillPaint.textSize = tempSize
            val maxTempW = avail * 0.60f
            val measuredTempW = numeralFillPaint.measureText(c.primary)
            val finalTempSize = tempSize * ClockTypography.shrinkFactor(measuredTempW, maxTempW)
            numeralFillPaint.textSize = finalTempSize
            numeralFillPaint.getTextBounds(c.primary, 0, c.primary.length, textBounds)
            numeralFillPaint.color = onSkyPrimary
            val tempCy = box.top + box.height * 0.48f
            canvas.drawText(c.primary, innerLeft - textBounds.left, tempCy - textBounds.exactCenterY(), numeralFillPaint)

            val iconSize = min(box.height * 0.32f, box.width * 0.32f)
            WidgetIcons.draw(
                canvas = canvas,
                glyph = c.glyph,
                x = innerRight - iconSize,
                y = tempCy - iconSize * 0.52f,
                size = iconSize,
                color = iconPrimary,
                secondaryColor = iconSecondary,
                nightRed = nightRed,
            )

            // Bottom: Bold condition label anchored gracefully at bottom-left
            if (condLabel.isNotEmpty()) {
                widgetSecondaryPaint.typeface = roundedBold
                widgetSecondaryPaint.textSize = box.height * 0.086f
                widgetSecondaryPaint.color = onSkyPrimary
                val clippedCond = TextUtils.ellipsize(condLabel, widgetSecondaryPaint, avail, TextUtils.TruncateAt.END).toString()
                canvas.drawText(clippedCond, innerLeft, box.bottom - padY - widgetSecondaryPaint.descent(), widgetSecondaryPaint)
            }
            return
        }

        // Medium / Compact Horizontal Weather Card (2-up or 3-up stack)
        val padX = box.width * 0.075f
        val padY = box.height * 0.14f
        val innerLeft = box.left + padX
        val innerRight = box.right - padX
        val avail = (innerRight - innerLeft).coerceAtLeast(1f)

        val iconSize = min(box.height * 0.48f, box.width * 0.22f)
        WidgetIcons.draw(
            canvas = canvas,
            glyph = c.glyph,
            x = innerRight - iconSize,
            y = box.top + padY * 0.85f,
            size = iconSize,
            color = iconPrimary,
            secondaryColor = iconSecondary,
            nightRed = nightRed,
        )

        if (cityLabel.isNotEmpty()) {
            val citySize = box.height * 0.195f
            widgetSecondaryPaint.typeface = roundedExtraBold
            widgetSecondaryPaint.textSize = citySize
            widgetSecondaryPaint.color = onSkyPrimary
            val arrowSize = citySize * 0.65f
            val clippedCity = TextUtils.ellipsize(cityLabel, widgetSecondaryPaint, avail - iconSize - arrowSize - padX * 0.5f, TextUtils.TruncateAt.END).toString()
            val y = box.top + padY - widgetSecondaryPaint.ascent()
            canvas.drawText(clippedCity, innerLeft, y, widgetSecondaryPaint)
            if (parts.size >= 2) {
                val ax = innerLeft + widgetSecondaryPaint.measureText(clippedCity) + citySize * 0.22f
                WidgetIcons.drawLocationArrow(canvas, ax, y - arrowSize * 0.90f, arrowSize, onSkyPrimary)
            }
        }

        // Large temperature bottom-left
        numeralFillPaint.shader = null
        numeralFillPaint.alpha = 255
        numeralFillPaint.xfermode = null
        numeralFillPaint.typeface = roundedBold
        var tempSize = box.height * 0.48f
        numeralFillPaint.textSize = tempSize
        tempSize *= ClockTypography.shrinkFactor(numeralFillPaint.measureText(c.primary), avail * 0.56f)
        numeralFillPaint.textSize = tempSize
        numeralFillPaint.color = onSkyPrimary
        canvas.drawText(c.primary, innerLeft, box.bottom - padY * 0.85f, numeralFillPaint)

        if (condLabel.isNotEmpty()) {
            widgetSecondaryPaint.typeface = roundedBold
            widgetSecondaryPaint.textSize = box.height * 0.18f
            widgetSecondaryPaint.color = onSkySecondary
            val condMax = (avail - numeralFillPaint.measureText(c.primary) - padX * 0.5f).coerceAtLeast(1f)
            val clipped = TextUtils.ellipsize(condLabel, widgetSecondaryPaint, condMax, TextUtils.TruncateAt.END).toString()
            val cw = widgetSecondaryPaint.measureText(clipped)
            canvas.drawText(clipped, innerRight - cw, box.bottom - padY * 0.95f, widgetSecondaryPaint)
        }
    }

    /**
     * 1:1 Apple Watch / iOS StandBy Circular Battery Card:
     * Thick glowing emerald progress ring + centered rounded phone & charging bolt.
     */
    private fun drawBatteryCard(
        canvas: Canvas,
        tile: WidgetTile,
        box: FaceBox,
        heroSquare: Boolean,
        nightRed: Boolean,
        darkSurface: Boolean,
    ) {
        val c = tile.content
        val cornerR = if (heroSquare) min(box.width, box.height) * 0.175f else min(box.height * 0.24f, box.width * 0.10f)
        val topFill = if (nightRed) Color.parseColor("#0D0202") else if (darkSurface) Color.parseColor("#1D1E24") else Color.parseColor("#F5F6F9")
        val botFill = if (nightRed) Color.BLACK else if (darkSurface) Color.parseColor("#101116") else Color.parseColor("#E6E8EF")
        drawGlassCard(canvas, box, cornerR, topFill, botFill, nightRed, darkSurface)

        val progress = (c.progress ?: 0f).coerceIn(0f, 1f)
        val ringColor = when {
            nightRed -> Color.parseColor("#FF3B30")
            !darkSurface -> colors.accent
            progress <= 0.12f -> Color.parseColor("#FF453A")
            progress <= 0.22f -> Color.parseColor("#FFD60A")
            else -> Color.parseColor("#30D158")
        }
        val ringBright = shiftLuminance(ringColor, 0.16f)
        val primaryText = if (nightRed) Color.parseColor("#FF3B30") else if (darkSurface) Color.WHITE else colors.primary

        fun drawBatteryRing(cx: Float, cy: Float, diameter: Float) {
            val radius = diameter * 0.5f
            val stroke = (radius * 0.22f).coerceAtLeast(3.0f)
            val r = radius - stroke * 0.5f

            ringPaint.shader = null
            ringPaint.strokeWidth = stroke
            ringPaint.color = withAlpha(ringColor, if (darkSurface) 0.20f else 0.18f)
            canvas.drawCircle(cx, cy, r, ringPaint)

            rectScratch.set(cx - r, cy - r, cx + r, cy + r)
            ringPaint.color = ringColor
            val sweep = (progress * 360f).coerceAtLeast(if (progress > 0f) 8f else 0f)
            if (!nightRed) {
                canvas.save()
                canvas.rotate(-90f, cx, cy)
                ringPaint.shader = SweepGradient(cx, cy, intArrayOf(ringColor, ringBright, ringColor), floatArrayOf(0f, 0.55f, 1f))
                canvas.drawArc(rectScratch, 0f, sweep, false, ringPaint)
                ringPaint.shader = null
                canvas.restore()
            } else {
                canvas.drawArc(rectScratch, -90f, sweep, false, ringPaint)
            }

            // Crisp rounded phone outline inside ring
            val phoneW = radius * 0.48f
            val phoneH = radius * 0.78f
            innerScratch.set(cx - phoneW * 0.5f, cy - phoneH * 0.5f, cx + phoneW * 0.5f, cy + phoneH * 0.5f)
            dialStrokePaint.strokeWidth = (stroke * 0.30f).coerceAtLeast(1.8f)
            dialStrokePaint.color = withAlpha(primaryText, 0.90f)
            canvas.drawRoundRect(innerScratch, phoneW * 0.24f, phoneW * 0.24f, dialStrokePaint)

            if (c.charging) {
                val boltSize = radius * 0.52f
                WidgetIcons.draw(
                    canvas = canvas,
                    glyph = WidgetGlyph.BOLT,
                    x = cx - boltSize * 0.5f,
                    y = cy - boltSize * 0.5f,
                    size = boltSize,
                    color = ringBright,
                    nightRed = nightRed,
                )
            }
        }

        if (heroSquare) {
            val ringD = min(box.width, box.height) * 0.48f
            val ringCx = box.centerX
            val ringCy = box.top + box.height * 0.39f
            drawBatteryRing(ringCx, ringCy, ringD)

            // Large centered percentage + status below ring
            numeralFillPaint.shader = null
            numeralFillPaint.alpha = 255
            numeralFillPaint.typeface = roundedExtraBold
            numeralFillPaint.textSize = box.height * 0.165f
            numeralFillPaint.color = primaryText
            numeralFillPaint.getTextBounds(c.primary, 0, c.primary.length, textBounds)
            val pctY = box.top + box.height * 0.77f
            canvas.drawText(c.primary, box.centerX - textBounds.exactCenterX(), pctY - textBounds.exactCenterY(), numeralFillPaint)

            val sub = c.secondary?.takeIf { it.isNotBlank() }
            if (sub != null) {
                dateHeaderPaint.typeface = roundedBold
                dateHeaderPaint.textAlign = Paint.Align.CENTER
                dateHeaderPaint.textSize = box.height * 0.068f
                dateHeaderPaint.color = if (c.charging) ringBright else withAlpha(primaryText, 0.65f)
                canvas.drawText(sub, box.centerX, box.top + box.height * 0.90f, dateHeaderPaint)
                dateHeaderPaint.textAlign = Paint.Align.LEFT
            }
            return
        }

        // Medium / Compact Horizontal Battery Card
        val padX = box.width * 0.075f
        val ringD = min(box.height * 0.68f, box.width * 0.30f)
        val ringCx = box.left + padX + ringD * 0.5f
        val ringCy = box.centerY
        drawBatteryRing(ringCx, ringCy, ringD)

        val textLeft = ringCx + ringD * 0.5f + padX * 0.85f
        val avail = (box.right - padX - textLeft).coerceAtLeast(1f)
        widgetPrimaryPaint.typeface = roundedExtraBold
        widgetPrimaryPaint.color = primaryText
        var numSize = box.height * 0.38f
        widgetPrimaryPaint.textSize = numSize
        numSize *= ClockTypography.shrinkFactor(widgetPrimaryPaint.measureText(c.primary), avail)
        widgetPrimaryPaint.textSize = numSize

        val sub = c.secondary?.takeIf { it.isNotBlank() }
        if (sub != null) {
            canvas.drawText(c.primary, textLeft, box.top + box.height * 0.53f, widgetPrimaryPaint)
            widgetSecondaryPaint.typeface = roundedBold
            widgetSecondaryPaint.color = if (c.charging) ringBright else withAlpha(primaryText, 0.62f)
            widgetSecondaryPaint.textSize = box.height * 0.18f
            val clipped = TextUtils.ellipsize(sub, widgetSecondaryPaint, avail, TextUtils.TruncateAt.END).toString()
            canvas.drawText(clipped, textLeft, box.top + box.height * 0.77f, widgetSecondaryPaint)
        } else {
            val y = box.centerY - (widgetPrimaryPaint.ascent() + widgetPrimaryPaint.descent()) * 0.5f
            canvas.drawText(c.primary, textLeft, y, widgetPrimaryPaint)
        }
    }

    /** Alarm / Media / Notification Liquid Glass Card. */
    private fun drawComplicationCard(
        canvas: Canvas,
        tile: WidgetTile,
        box: FaceBox,
        heroSquare: Boolean,
        nightRed: Boolean,
        darkSurface: Boolean,
    ) {
        val c = tile.content
        val cornerR = if (heroSquare) min(box.width, box.height) * 0.175f else min(box.height * 0.24f, box.width * 0.10f)
        val topFill = if (nightRed) Color.parseColor("#0D0202") else if (darkSurface) Color.parseColor("#1D1E24") else Color.parseColor("#F5F6F9")
        val botFill = if (nightRed) Color.BLACK else if (darkSurface) Color.parseColor("#101116") else Color.parseColor("#E6E8EF")
        drawGlassCard(canvas, box, cornerR, topFill, botFill, nightRed, darkSurface)

        val badgeTint = glyphAccentColor(c, nightRed, darkSurface)
        val primaryText = if (nightRed) Color.parseColor("#FF3B30") else if (darkSurface) Color.WHITE else colors.primary

        if (heroSquare) {
            val padX = box.width * 0.095f
            val padY = box.height * 0.095f
            val innerLeft = box.left + padX
            val avail = (box.right - padX - innerLeft).coerceAtLeast(1f)

            val badgeD = box.height * 0.22f
            if (c.glyph != WidgetGlyph.NONE) {
                cardFillPaint.shader = null
                cardFillPaint.color = withAlpha(badgeTint, if (darkSurface) 0.22f else 0.15f)
                canvas.drawCircle(innerLeft + badgeD * 0.5f, box.top + padY + badgeD * 0.5f, badgeD * 0.5f, cardFillPaint)
                val gSize = badgeD * 0.54f
                WidgetIcons.draw(
                    canvas, c.glyph,
                    innerLeft + (badgeD - gSize) * 0.5f,
                    box.top + padY + (badgeD - gSize) * 0.5f,
                    gSize, badgeTint, nightRed = nightRed,
                )
            }

            val sub = c.secondary?.takeIf { it.isNotBlank() }
            if (sub != null) {
                widgetSecondaryPaint.typeface = roundedExtraBold
                widgetSecondaryPaint.textSize = box.height * 0.075f
                widgetSecondaryPaint.color = badgeTint
                val over = TextUtils.ellipsize(sub, widgetSecondaryPaint, avail, TextUtils.TruncateAt.END).toString()
                canvas.drawText(over, innerLeft, box.top + box.height * 0.52f, widgetSecondaryPaint)
            }

            widgetPrimaryPaint.typeface = roundedExtraBold
            widgetPrimaryPaint.color = primaryText
            var pSize = box.height * (if (c.kind == WidgetKind.ALARM_BADGE) 0.21f else 0.135f)
            widgetPrimaryPaint.textSize = pSize
            pSize *= ClockTypography.shrinkFactor(widgetPrimaryPaint.measureText(c.primary), avail)
            widgetPrimaryPaint.textSize = pSize
            val mainLine = TextUtils.ellipsize(c.primary, widgetPrimaryPaint, avail, TextUtils.TruncateAt.END).toString()
            canvas.drawText(mainLine, innerLeft, box.top + box.height * 0.74f, widgetPrimaryPaint)
            return
        }

        // Medium / Compact horizontal layout
        val padX = box.width * 0.075f
        val badgeSize = min(box.height * 0.52f, box.width * 0.22f)
        val badgeCx = box.left + padX + badgeSize * 0.5f
        val badgeCy = box.centerY

        if (c.glyph != WidgetGlyph.NONE) {
            cardFillPaint.shader = null
            cardFillPaint.color = withAlpha(badgeTint, if (darkSurface) 0.22f else 0.14f)
            canvas.drawCircle(badgeCx, badgeCy, badgeSize * 0.5f, cardFillPaint)
            val glyphSize = badgeSize * 0.54f
            WidgetIcons.draw(
                canvas, c.glyph,
                badgeCx - glyphSize * 0.5f,
                badgeCy - glyphSize * 0.5f,
                glyphSize, badgeTint, nightRed = nightRed,
            )
        }

        val textLeft = if (c.glyph == WidgetGlyph.NONE) box.left + padX else badgeCx + badgeSize * 0.5f + padX * 0.75f
        val avail = (box.right - padX - textLeft).coerceAtLeast(1f)
        val hasSecondary = !c.secondary.isNullOrBlank()

        if (hasSecondary) {
            widgetSecondaryPaint.typeface = roundedExtraBold
            widgetSecondaryPaint.color = badgeTint
            widgetSecondaryPaint.textSize = box.height * 0.175f
            val overline = TextUtils.ellipsize(c.secondary.orEmpty(), widgetSecondaryPaint, avail, TextUtils.TruncateAt.END).toString()
            canvas.drawText(overline, textLeft, box.top + box.height * 0.37f, widgetSecondaryPaint)

            widgetPrimaryPaint.typeface = roundedExtraBold
            widgetPrimaryPaint.color = primaryText
            widgetPrimaryPaint.textSize = box.height * (if (c.kind == WidgetKind.ALARM_BADGE) 0.35f else 0.27f)
            val mainLine = TextUtils.ellipsize(c.primary, widgetPrimaryPaint, avail, TextUtils.TruncateAt.END).toString()
            canvas.drawText(mainLine, textLeft, box.top + box.height * 0.73f, widgetPrimaryPaint)
        } else {
            widgetPrimaryPaint.typeface = roundedExtraBold
            widgetPrimaryPaint.color = primaryText
            widgetPrimaryPaint.textSize = box.height * 0.32f
            val mainLine = TextUtils.ellipsize(c.primary, widgetPrimaryPaint, avail, TextUtils.TruncateAt.END).toString()
            val y = box.centerY - (widgetPrimaryPaint.ascent() + widgetPrimaryPaint.descent()) * 0.5f
            canvas.drawText(mainLine, textLeft, y, widgetPrimaryPaint)
        }
    }

    private fun drawOptionalFooterTile(
        canvas: Canvas,
        layout: FaceLayout,
        leftWidgets: List<WidgetTile>,
        nightRed: Boolean,
        darkSurface: Boolean,
    ) {
        val box = layout.leftTile ?: return
        val tile = leftWidgets.firstOrNull() ?: return
        val c = tile.content
        val iconSize = box.height * 0.56f
        var textLeft = box.left + box.height * 0.22f
        if (c.glyph != WidgetGlyph.NONE) {
            WidgetIcons.draw(
                canvas, c.glyph,
                textLeft, box.centerY - iconSize * 0.5f,
                iconSize, glyphAccentColor(c, nightRed, darkSurface),
                nightRed = nightRed,
            )
            textLeft += iconSize + box.height * 0.24f
        }
        val avail = (box.right - textLeft).coerceAtLeast(1f)
        val combined = if (c.secondary.isNullOrBlank()) c.primary else "${c.primary} · ${c.secondary}"
        widgetPrimaryPaint.typeface = roundedBold
        widgetPrimaryPaint.color = withAlpha(if (nightRed) Color.parseColor("#FF453A") else colors.primary, 0.85f)
        widgetPrimaryPaint.textSize = box.height * 0.46f
        val line = TextUtils.ellipsize(combined, widgetPrimaryPaint, avail, TextUtils.TruncateAt.END).toString()
        val y = box.centerY - (widgetPrimaryPaint.ascent() + widgetPrimaryPaint.descent()) * 0.5f
        canvas.drawText(line, textLeft, y, widgetPrimaryPaint)
    }

    // -------------------------------------------------------------------------
    // Portrait Fallback (for portrait settings previews or vertical hold)
    // -------------------------------------------------------------------------

    private fun drawPortraitFace(
        canvas: Canvas,
        layout: FaceLayout,
        now: Calendar,
        rightWidgets: List<WidgetTile>,
        leftWidgets: List<WidgetTile>,
        nightRed: Boolean,
        darkSurface: Boolean,
    ) {
        if (theme.faceStyle == FaceStyle.ANALOG) {
            drawAnalogWatch(canvas, layout.clock, now, nightRed, darkSurface)
        } else if (theme.faceStyle == FaceStyle.FLOAT) {
            drawFloatFace(canvas, layout.clock, now, nightRed, darkSurface)
        } else {
            drawSolarGlassFace(canvas, layout.clock, now, nightRed, darkSurface)
        }

        val fullText = fullDateFormatter.format(now.time)
        dateHeaderPaint.typeface = roundedBold
        dateHeaderPaint.textAlign = Paint.Align.CENTER
        var size = layout.date.height * 0.44f
        dateHeaderPaint.textSize = size
        size *= ClockTypography.shrinkFactor(dateHeaderPaint.measureText(fullText), layout.date.width)
        dateHeaderPaint.textSize = size
        dateHeaderPaint.color = if (nightRed) Color.parseColor("#FF453A") else colors.accent
        val y = layout.date.centerY - (dateHeaderPaint.ascent() + dateHeaderPaint.descent()) * 0.5f
        canvas.drawText(fullText, layout.date.centerX, y, dateHeaderPaint)
        dateHeaderPaint.textAlign = Paint.Align.LEFT

        val visible = rightWidgets.take(layout.rightTiles.size)
        visible.forEachIndexed { i, tile ->
            drawWidgetCard(canvas, tile, layout.rightTiles[i], now, heroSquare = false, nightRed = nightRed, darkSurface = darkSurface)
        }
        drawOptionalFooterTile(canvas, layout, leftWidgets, nightRed, darkSurface)
    }

    // -------------------------------------------------------------------------
    // Liquid Glass Card Surface + Specular Directional Rim Helper
    // -------------------------------------------------------------------------

    private fun drawGlassCard(
        canvas: Canvas,
        box: FaceBox,
        cornerR: Float,
        topColor: Int,
        bottomColor: Int,
        nightRed: Boolean,
        darkSurface: Boolean,
        skyCard: Boolean = false,
    ) {
        rectScratch.set(box.left, box.top, box.right, box.bottom)
        cardFillPaint.shader = LinearGradient(box.left, box.top, box.left, box.bottom, topColor, bottomColor, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(rectScratch, cornerR, cornerR, cardFillPaint)
        cardFillPaint.shader = null

        // Subtle top-edge Liquid Glass inner luminance sheen
        if (darkSurface && !nightRed) {
            canvas.save()
            clipPath.reset()
            clipPath.addRoundRect(rectScratch, cornerR, cornerR, Path.Direction.CW)
            canvas.clipPath(clipPath)
            val sheenH = box.height * 0.30f
            cardHighlightPaint.shader = LinearGradient(
                box.left, box.top, box.left, box.top + sheenH,
                Color.argb(if (skyCard) 28 else 18, 255, 255, 255),
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(box.left, box.top, box.right, box.top + sheenH, cardHighlightPaint)
            cardHighlightPaint.shader = null
            canvas.restore()
        }

        // Directional Liquid Glass specular border (top-left bright catch -> soft middle -> subtle bottom-right)
        val strokeW = (min(box.width, box.height) * 0.0032f).coerceIn(1.2f, 2.2f)
        val half = strokeW * 0.5f
        innerScratch.set(box.left + half, box.top + half, box.right - half, box.bottom - half)
        cardRimPaint.strokeWidth = strokeW
        cardRimPaint.shader = LinearGradient(
            box.left, box.top, box.right, box.bottom,
            intArrayOf(
                when {
                    nightRed -> Color.argb(72, 255, 69, 58)
                    skyCard -> Color.argb(110, 255, 255, 255)
                    darkSurface -> Color.argb(74, 255, 255, 255)
                    else -> Color.argb(38, 0, 0, 0)
                },
                when {
                    nightRed -> Color.argb(18, 255, 69, 58)
                    skyCard -> Color.argb(24, 255, 255, 255)
                    darkSurface -> Color.argb(14, 255, 255, 255)
                    else -> Color.argb(10, 0, 0, 0)
                },
                when {
                    nightRed -> Color.argb(44, 255, 69, 58)
                    skyCard -> Color.argb(56, 255, 255, 255)
                    darkSurface -> Color.argb(38, 255, 255, 255)
                    else -> Color.argb(24, 0, 0, 0)
                },
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(innerScratch, (cornerR - half).coerceAtLeast(1f), (cornerR - half).coerceAtLeast(1f), cardRimPaint)
        cardRimPaint.shader = null
    }

    private fun weatherSkyGradient(
        glyph: WidgetGlyph,
        nightSky: Boolean,
        nightRed: Boolean,
        darkSurface: Boolean,
    ): Pair<Int, Int> {
        if (nightRed) return Color.parseColor("#110202") to Color.BLACK
        if (!darkSurface) return Color.parseColor("#E3EFFB") to Color.parseColor("#CBE0F6")
        return when (glyph) {
            WidgetGlyph.SUN -> if (nightSky) {
                Color.parseColor("#0D1B3E") to Color.parseColor("#1E3C72")
            } else {
                Color.parseColor("#164A8A") to Color.parseColor("#4793DB")
            }
            WidgetGlyph.PARTLY_CLOUDY -> if (nightSky) {
                Color.parseColor("#15233E") to Color.parseColor("#2A426B")
            } else {
                Color.parseColor("#21538C") to Color.parseColor("#548FC9")
            }
            WidgetGlyph.CLOUD -> Color.parseColor("#2B3B4E") to Color.parseColor("#4A5F78")
            WidgetGlyph.RAIN, WidgetGlyph.THUNDER -> Color.parseColor("#192536") to Color.parseColor("#324761")
            WidgetGlyph.SNOW, WidgetGlyph.FOG -> Color.parseColor("#334456") to Color.parseColor("#586F85")
            else -> Color.parseColor("#1B4375") to Color.parseColor("#3A75B5")
        }
    }

    // -------------------------------------------------------------------------
    // iOS 26/27 StandBy Notifications: Full-Screen Ambient Alert + Top Pill
    // -------------------------------------------------------------------------

    /**
     * Sleek top-center Liquid Glass notification capsule visible on all 4 faces
     * when active unread notifications exist. Tapping it opens the full-screen alert.
     */
    private fun drawUnreadNotificationPill(
        canvas: Canvas,
        w: Float,
        h: Float,
        nightRed: Boolean,
        darkSurface: Boolean,
        visibility: Float,
    ) {
        val summary = renderedUnread
        if (summary == null || summary.count <= 0 || visibility <= 0.01f) {
            unreadPillBounds.setEmpty()
            return
        }

        val pillH = (h * 0.056f).coerceIn(28f, 72f)
        val slideOffset = -pillH * 0.45f * (1f - visibility)
        val pillTop = (h * 0.036f).coerceAtLeast(10f) + slideOffset
        val padX = pillH * 0.42f
        val iconSize = pillH * 0.56f
        val iconGap = pillH * 0.24f

        val label = if (summary.count > 1) {
            "${summary.appLabel} · ${context.getString(R.string.notif_count_pill, summary.count)}"
        } else {
            summary.appLabel
        }

        widgetSecondaryPaint.typeface = roundedBold
        widgetSecondaryPaint.textSize = pillH * 0.44f
        val maxTextW = (w * 0.34f).coerceAtLeast(pillH * 2f)
        val clipped = TextUtils.ellipsize(label, widgetSecondaryPaint, maxTextW, TextUtils.TruncateAt.END).toString()
        val textW = widgetSecondaryPaint.measureText(clipped)

        val pillW = padX * 2f + iconSize + iconGap + textW
        val pillLeft = w * 0.5f - pillW * 0.5f
        val pillBox = FaceBox(pillLeft, pillTop, pillLeft + pillW, pillTop + pillH)
        if (unreadSummary != null && activeAlert == null) {
            unreadPillBounds.set(pillBox.left, pillBox.top, pillBox.right, pillBox.bottom)
        }

        val layerAlpha = (visibility * 255f).toInt().coerceIn(0, 255)
        layerBounds.set(pillBox.left - 8f, pillBox.top - 8f, pillBox.right + 8f, pillBox.bottom + 8f)
        val pillLayer = canvas.saveLayerAlpha(layerBounds, layerAlpha)

        val fillTop = when {
            nightRed -> Color.argb(175, 38, 6, 6)
            darkSurface -> Color.argb(170, 32, 34, 42)
            else -> Color.argb(200, 242, 244, 248)
        }
        val fillBot = when {
            nightRed -> Color.argb(190, 20, 2, 2)
            darkSurface -> Color.argb(185, 18, 20, 26)
            else -> Color.argb(215, 228, 232, 238)
        }
        drawGlassCard(canvas, pillBox, pillH * 0.5f, fillTop, fillBot, nightRed, darkSurface)

        val iconLeft = pillBox.left + padX
        val iconTop = pillBox.centerY - iconSize * 0.5f
        val appDrawable = loadAppIcon(summary.packageName)
        if (appDrawable != null) {
            drawSquircleAppIcon(canvas, appDrawable, iconLeft, iconTop, iconSize, nightRed)
        } else {
            val dotColor = if (nightRed) Color.parseColor("#FF453A") else vibrantGlowAccent()
            WidgetIcons.draw(canvas, WidgetGlyph.BELL, iconLeft, iconTop, iconSize, dotColor, nightRed = nightRed)
        }

        widgetSecondaryPaint.color = when {
            nightRed -> Color.parseColor("#FF453A")
            darkSurface -> Color.argb(242, 255, 255, 255)
            else -> colors.primary
        }
        val textY = pillBox.centerY - (widgetSecondaryPaint.ascent() + widgetSecondaryPaint.descent()) * 0.5f
        canvas.drawText(clipped, iconLeft + iconSize + iconGap, textY, widgetSecondaryPaint)
        canvas.restoreToCount(pillLayer)
    }

    /**
     * 1:1 iOS 26/27 StandBy Choreographed Full-Screen Notification Overlay:
     * - Enter (740 ms): spatial clock pullback + ambient aura bloom + Liquid Glass card
     *   morphing from the top pill via Apple damped spring + staggered icon/meta/title
     *   entrances + single diagonal Liquid Glass specular light sheen sweep.
     * - Exit (480 ms): content softens & lifts while the glass card physically morphs
     *   back up into the top-center unread pill.
     */
    private fun drawNotificationOverlay(
        canvas: Canvas,
        w: Float,
        h: Float,
        alert: StandbyNotificationAlert,
        nightRed: Boolean,
        darkSurface: Boolean,
        timeline: Float,
        exiting: Boolean,
        scrimP: Float,
    ) {
        // 1. Frosted ambient backdrop smoothly dimming the underlying clock
        cardFillPaint.shader = null
        cardFillPaint.color = when {
            nightRed -> Color.argb((236 * scrimP).toInt(), 0, 0, 0)
            darkSurface -> Color.argb((224 * scrimP).toInt(), 4, 5, 8)
            else -> Color.argb((205 * scrimP).toInt(), 228, 231, 238)
        }
        canvas.drawRect(0f, 0f, w, h, cardFillPaint)

        // 2. Ambient backlight halo swelling gently behind the Liquid Glass card
        val accentGlow = if (nightRed) Color.parseColor("#B81D16") else vibrantGlowAccent()
        if (darkSurface && scrimP > 0.01f) {
            val haloScale = 0.72f + 0.30f * scrimP
            val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    w * 0.5f, h * 0.5f, max(w, h) * 0.46f * haloScale,
                    intArrayOf(withAlpha(accentGlow, (if (nightRed) 0.24f else 0.30f) * scrimP), Color.TRANSPARENT),
                    floatArrayOf(0f, 1f),
                    Shader.TileMode.CLAMP,
                )
            }
            canvas.drawRect(0f, 0f, w, h, haloPaint)
        }

        // 3. Target Hero Card & Top-Center Pill anchor boxes for seamless morph
        val landscape = w >= h
        val heroW = if (landscape) min(w * 0.76f, h * 1.66f) else w * 0.90f
        val heroH = if (landscape) min(h * 0.58f, heroW * 0.44f) else min(h * 0.34f, w * 0.62f)
        val heroLeft = w * 0.5f - heroW * 0.5f
        val heroTop = h * 0.5f - heroH * 0.5f
        val heroCornerR = min(heroW, heroH) * 0.145f

        val pillH = (h * 0.056f).coerceIn(28f, 72f)
        val pillW = (w * 0.20f).coerceIn(pillH * 3.2f, heroW * 0.48f)
        val pillTop = (h * 0.036f).coerceAtLeast(10f)
        val pillLeft = w * 0.5f - pillW * 0.5f
        val pillCornerR = pillH * 0.5f

        // Compute morph geometry + staggered element timelines
        val morphP: Float
        val shellAlpha: Float
        val iconScaleP: Float
        val iconAlpha: Float
        val metaP: Float
        val bodyP: Float
        val shimmerT: Float

        if (!exiting) {
            morphP = springCurve(subTimeline(timeline, 0.0f, 0.76f), damping = 0.80f, frequency = 1.56f)
            shellAlpha = easeOutCubic(subTimeline(timeline, 0.0f, 0.22f))
            iconScaleP = springCurve(subTimeline(timeline, 0.10f, 0.68f), damping = 0.73f, frequency = 1.72f)
            iconAlpha = easeOutCubic(subTimeline(timeline, 0.10f, 0.34f))
            metaP = easeOutCubic(subTimeline(timeline, 0.22f, 0.70f))
            bodyP = easeOutCubic(subTimeline(timeline, 0.30f, 0.84f))
            shimmerT = subTimeline(timeline, 0.34f, 0.98f)
        } else {
            val backP = easeInOutCubic(timeline)
            morphP = 1f - backP
            shellAlpha = (1f - easeOutCubic(subTimeline(timeline, 0.64f, 1.0f))).coerceIn(0f, 1f)
            val contentOut = (1f - easeOutCubic(subTimeline(timeline, 0.0f, 0.38f))).coerceIn(0f, 1f)
            iconScaleP = 0.84f + 0.16f * contentOut
            iconAlpha = contentOut
            metaP = contentOut
            bodyP = contentOut
            shimmerT = 0f
        }

        val shellAlphaInt = (shellAlpha * 255f).toInt().coerceIn(0, 255)
        if (shellAlphaInt <= 2) return

        // Interpolate card box smoothly between Top Pill and Hero Card (+ live finger drag)
        val liveDragX = if (!exiting) dragOffsetX else alertExitStartDragX * morphP
        val liveDragY = if (!exiting) dragOffsetY else alertExitStartDragY * morphP
        val dragShrink = if (!exiting) {
            1f - 0.07f * (kotlin.math.hypot(liveDragX.toDouble(), liveDragY.toDouble()).toFloat() / (h * 0.30f)).coerceIn(0f, 1f)
        } else 1f

        val curW = lerp(pillW, heroW, morphP) * dragShrink
        val curH = lerp(pillH, heroH, morphP) * dragShrink
        val curCx = lerp(pillLeft + pillW * 0.5f, heroLeft + heroW * 0.5f, morphP) + liveDragX
        val curCy = lerp(pillTop + pillH * 0.5f, heroTop + heroH * 0.5f, morphP) + liveDragY
        val cardBox = FaceBox(curCx - curW * 0.5f, curCy - curH * 0.5f, curCx + curW * 0.5f, curCy + curH * 0.5f)
        val cornerR = lerp(pillCornerR, heroCornerR, morphP.coerceIn(0f, 1f))

        layerBounds.set(0f, 0f, w, h)
        val shellLayer = canvas.saveLayerAlpha(layerBounds, shellAlphaInt)

        val cardTop = when {
            nightRed -> Color.argb(242, 20, 4, 4)
            darkSurface -> Color.argb(232, 34, 36, 45)
            else -> Color.argb(242, 250, 251, 253)
        }
        val cardBot = when {
            nightRed -> Color.argb(248, 8, 1, 1)
            darkSurface -> Color.argb(242, 18, 20, 26)
            else -> Color.argb(248, 236, 239, 245)
        }
        drawGlassCard(canvas, cardBox, cornerR, cardTop, cardBot, nightRed, darkSurface)

        // Clip inner staggered content + diagonal Liquid Glass specular shimmer to the morphing card
        canvas.save()
        clipPath.reset()
        rectScratch.set(cardBox.left, cardBox.top, cardBox.right, cardBox.bottom)
        clipPath.addRoundRect(rectScratch, cornerR, cornerR, Path.Direction.CW)
        canvas.clipPath(clipPath)

        // Anchor inner typography to steady hero proportions centered inside the morphing shell
        val contentBox = FaceBox(curCx - heroW * 0.5f, curCy - heroH * 0.5f, curCx + heroW * 0.5f, curCy + heroH * 0.5f)
        val padX = heroH * 0.14f
        val iconSize = (heroH * 0.44f).coerceAtMost(heroW * 0.24f)
        val iconLeft = contentBox.left + padX
        val iconTop = contentBox.centerY - iconSize * 0.5f

        // Beat 2: Giant Squircle App Icon with its own poised micro-spring
        val iconAlphaInt = (iconAlpha * 255f).toInt().coerceIn(0, 255)
        if (iconAlphaInt > 2) {
            val iconScale = 0.64f + 0.36f * iconScaleP
            val iconCx = iconLeft + iconSize * 0.5f
            val iconCy = iconTop + iconSize * 0.5f
            layerBounds.set(iconLeft - iconSize * 0.3f, iconTop - iconSize * 0.3f, iconLeft + iconSize * 1.3f, iconTop + iconSize * 1.3f)
            val iconLayer = canvas.saveLayerAlpha(layerBounds, iconAlphaInt)
            canvas.scale(iconScale, iconScale, iconCx, iconCy)
            val appDrawable = loadAppIcon(alert.packageName)
            if (appDrawable != null) {
                drawSquircleAppIcon(canvas, appDrawable, iconLeft, iconTop, iconSize, nightRed)
            } else {
                val badgeTint = if (nightRed) Color.parseColor("#FF3B30") else vibrantGlowAccent()
                val iconBox = FaceBox(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize)
                drawGlassCard(
                    canvas, iconBox, iconSize * 0.23f,
                    shiftLuminance(badgeTint, 0.10f),
                    shiftLuminance(badgeTint, -0.16f),
                    nightRed, darkSurface, skyCard = !nightRed,
                )
                val glyphSize = iconSize * 0.54f
                WidgetIcons.draw(
                    canvas, WidgetGlyph.BELL,
                    iconLeft + (iconSize - glyphSize) * 0.5f,
                    iconTop + (iconSize - glyphSize) * 0.5f,
                    glyphSize,
                    if (nightRed) Color.BLACK else Color.WHITE,
                    nightRed = nightRed,
                )
            }
            canvas.restoreToCount(iconLayer)
        }

        val textLeft = iconLeft + iconSize + padX * 0.85f
        val textRight = contentBox.right - padX
        val availW = (textRight - textLeft).coerceAtLeast(1f)
        val primaryText = when {
            nightRed -> Color.parseColor("#FF3B30")
            darkSurface -> Color.WHITE
            else -> Color.parseColor("#1C1C1E")
        }
        val secondaryText = when {
            nightRed -> Color.argb(185, 255, 69, 58)
            darkSurface -> Color.argb(175, 235, 240, 250)
            else -> Color.argb(165, 28, 28, 30)
        }

        // Beat 3: Staggered App Name · now + "+N more" pill
        if (metaP > 0.01f) {
            val metaDy = if (!exiting) heroH * 0.045f * (1f - metaP) else -heroH * 0.028f * (1f - metaP)
            val metaY = contentBox.top + heroH * 0.28f + metaDy
            val metaText = "${alert.appLabel}  ·  ${context.getString(R.string.notif_just_now)}"
            widgetSecondaryPaint.typeface = roundedBold
            widgetSecondaryPaint.textSize = (heroH * 0.095f).coerceIn(14f, 44f)
            widgetSecondaryPaint.color = withAlpha(secondaryText, metaP)

            var metaAvail = availW
            if (alert.unreadCount > 1) {
                val moreLabel = context.getString(R.string.notif_more_badge, alert.unreadCount - 1)
                val moreSize = (heroH * 0.072f).coerceIn(12f, 32f)
                dateHeaderPaint.typeface = roundedBold
                dateHeaderPaint.textSize = moreSize
                val moreTw = dateHeaderPaint.measureText(moreLabel)
                val morePadX = moreSize * 0.65f
                val moreH = moreSize * 1.75f
                val moreW = moreTw + morePadX * 2f
                val moreBox = FaceBox(textRight - moreW, metaY - moreH * 0.72f, textRight, metaY + moreH * 0.28f)
                layerBounds.set(moreBox.left - 4f, moreBox.top - 4f, moreBox.right + 4f, moreBox.bottom + 4f)
                val moreLayer = canvas.saveLayerAlpha(layerBounds, (metaP * 255f).toInt().coerceIn(0, 255))
                drawGlassCard(
                    canvas, moreBox, moreH * 0.5f,
                    if (nightRed) Color.argb(60, 255, 59, 48) else if (darkSurface) Color.argb(40, 255, 255, 255) else Color.argb(20, 0, 0, 0),
                    if (nightRed) Color.argb(36, 255, 59, 48) else if (darkSurface) Color.argb(24, 255, 255, 255) else Color.argb(14, 0, 0, 0),
                    nightRed, darkSurface,
                )
                dateHeaderPaint.textAlign = Paint.Align.CENTER
                dateHeaderPaint.color = secondaryText
                val my = moreBox.centerY - (dateHeaderPaint.ascent() + dateHeaderPaint.descent()) * 0.5f
                canvas.drawText(moreLabel, moreBox.centerX, my, dateHeaderPaint)
                dateHeaderPaint.textAlign = Paint.Align.LEFT
                canvas.restoreToCount(moreLayer)
                metaAvail = (availW - moreW - padX * 0.35f).coerceAtLeast(1f)
            }
            val clippedMeta = TextUtils.ellipsize(metaText, widgetSecondaryPaint, metaAvail, TextUtils.TruncateAt.END).toString()
            canvas.drawText(clippedMeta, textLeft, metaY, widgetSecondaryPaint)
        }

        // Beat 4: Staggered Hero Title / Privacy Shield (plus smooth reveal bloom)
        val revealP = alertRevealProgress.coerceIn(0f, 1f)
        val combinedBodyAlpha = (bodyP * revealP).coerceIn(0f, 1f)
        if (combinedBodyAlpha > 0.01f) {
            val bodyDy = if (!exiting) {
                heroH * 0.062f * (1f - bodyP) + heroH * 0.032f * (1f - revealP)
            } else {
                -heroH * 0.032f * (1f - bodyP)
            }

            if (alert.isMasked) {
                widgetPrimaryPaint.typeface = roundedExtraBold
                widgetPrimaryPaint.textSize = (heroH * 0.175f).coerceIn(22f, 82f)
                widgetPrimaryPaint.color = withAlpha(primaryText, combinedBodyAlpha)
                val maskedTitle = context.getString(R.string.notif_new)
                val clippedTitle = TextUtils.ellipsize(maskedTitle, widgetPrimaryPaint, availW, TextUtils.TruncateAt.END).toString()
                canvas.drawText(clippedTitle, textLeft, contentBox.top + heroH * 0.56f + bodyDy, widgetPrimaryPaint)

                if (alert.tapToReveal && !alert.isPrivateRedacted) {
                    val tapLabel = context.getString(R.string.notif_tap_to_view)
                    val tapSize = (heroH * 0.082f).coerceIn(13f, 36f)
                    dateHeaderPaint.typeface = roundedBold
                    dateHeaderPaint.textSize = tapSize
                    val tw = dateHeaderPaint.measureText(tapLabel)
                    val pPadX = tapSize * 0.9f
                    val pH = tapSize * 1.95f
                    val pW = min(tw + pPadX * 2f, availW)
                    val pTop = contentBox.top + heroH * 0.65f + bodyDy * 1.2f
                    val pillBox = FaceBox(textLeft, pTop, textLeft + pW, pTop + pH)
                    layerBounds.set(pillBox.left - 4f, pillBox.top - 4f, pillBox.right + 4f, pillBox.bottom + 4f)
                    val tapLayer = canvas.saveLayerAlpha(layerBounds, (combinedBodyAlpha * 255f).toInt().coerceIn(0, 255))
                    drawGlassCard(
                        canvas, pillBox, pH * 0.5f,
                        if (nightRed) Color.argb(80, 255, 59, 48) else if (darkSurface) Color.argb(56, 255, 255, 255) else Color.argb(26, 0, 0, 0),
                        if (nightRed) Color.argb(46, 255, 59, 48) else if (darkSurface) Color.argb(30, 255, 255, 255) else Color.argb(16, 0, 0, 0),
                        nightRed, darkSurface,
                    )
                    dateHeaderPaint.textAlign = Paint.Align.CENTER
                    dateHeaderPaint.color = primaryText
                    val py = pillBox.centerY - (dateHeaderPaint.ascent() + dateHeaderPaint.descent()) * 0.5f
                    canvas.drawText(tapLabel, pillBox.centerX, py, dateHeaderPaint)
                    dateHeaderPaint.textAlign = Paint.Align.LEFT
                    canvas.restoreToCount(tapLayer)
                }
            } else {
                val cleanBody = alert.body?.takeIf { it.isNotBlank() && !it.equals(alert.title, ignoreCase = true) }
                val titleMatchesApp = alert.title.equals(alert.appLabel, ignoreCase = true)
                if (cleanBody != null && !titleMatchesApp) {
                    // Authentic 2-level iOS StandBy layout: Bold Sender + 2-line luminous preview body
                    widgetPrimaryPaint.typeface = roundedExtraBold
                    widgetPrimaryPaint.color = withAlpha(primaryText, combinedBodyAlpha)
                    widgetPrimaryPaint.textSize = (heroH * 0.145f).coerceIn(20f, 66f)
                    val clippedSender = TextUtils.ellipsize(alert.title, widgetPrimaryPaint, availW, TextUtils.TruncateAt.END).toString()
                    val senderBaseline = contentBox.top + heroH * 0.48f + bodyDy
                    canvas.drawText(clippedSender, textLeft, senderBaseline, widgetPrimaryPaint)

                    widgetSecondaryPaint.typeface = roundedBold
                    widgetSecondaryPaint.color = withAlpha(secondaryText, combinedBodyAlpha * 0.96f)
                    widgetSecondaryPaint.textSize = (heroH * 0.112f).coerceIn(16f, 50f)
                    val bodyLayout = StaticLayout.Builder
                        .obtain(cleanBody, 0, cleanBody.length, widgetSecondaryPaint, availW.toInt().coerceAtLeast(1))
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                        .setMaxLines(2)
                        .setEllipsize(TextUtils.TruncateAt.END)
                        .setIncludePad(false)
                        .setLineSpacing(0f, 1.15f)
                        .build()
                    canvas.save()
                    canvas.translate(textLeft, contentBox.top + heroH * 0.54f + bodyDy * 1.15f)
                    bodyLayout.draw(canvas)
                    canvas.restore()
                } else {
                    // Single hero message (uses body if title just repeated app name, e.g. WeChat "你有一条新消息")
                    val displayText = if (titleMatchesApp && cleanBody != null) cleanBody else alert.title
                    widgetPrimaryPaint.typeface = roundedExtraBold
                    widgetPrimaryPaint.color = withAlpha(primaryText, combinedBodyAlpha)
                    val baseSize = (heroH * 0.165f).coerceIn(22f, 78f)
                    widgetPrimaryPaint.textSize = baseSize
                    val staticLayout = StaticLayout.Builder
                        .obtain(displayText, 0, displayText.length, widgetPrimaryPaint, availW.toInt().coerceAtLeast(1))
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                        .setMaxLines(2)
                        .setEllipsize(TextUtils.TruncateAt.END)
                        .setIncludePad(false)
                        .setLineSpacing(0f, 1.12f)
                        .build()
                    val textTop = contentBox.top + heroH * 0.38f + bodyDy
                    canvas.save()
                    canvas.translate(textLeft, textTop)
                    staticLayout.draw(canvas)
                    canvas.restore()
                }
            }
        }

        // Subtle iOS home-grabber dismiss bar along the card bottom
        val barAlpha = bodyP.coerceIn(0f, 1f)
        if (barAlpha > 0.05f) {
            val barW = curW * 0.12f
            val barH = (heroH * 0.016f).coerceIn(3f, 7f)
            val barY = cardBox.bottom - heroH * 0.068f
            cardFillPaint.shader = null
            val barColor = if (nightRed) Color.argb(82, 255, 69, 58) else if (darkSurface) Color.argb(64, 255, 255, 255) else Color.argb(46, 0, 0, 0)
            cardFillPaint.color = withAlpha(barColor, barAlpha)
            rectScratch.set(cardBox.centerX - barW * 0.5f, barY, cardBox.centerX + barW * 0.5f, barY + barH)
            canvas.drawRoundRect(rectScratch, barH * 0.5f, barH * 0.5f, cardFillPaint)
        }

        // Beat 5: Single gossamer diagonal Liquid Glass specular light sheen sweep on entrance
        if (!exiting && darkSurface && shimmerT > 0.001f && shimmerT < 0.999f) {
            val bell = kotlin.math.sin(shimmerT * Math.PI).toFloat()
            val gleamAlpha = ((if (nightRed) 26f else 36f) * bell).toInt().coerceIn(0, 255)
            if (gleamAlpha > 0) {
                val bandW = cardBox.width * 0.42f
                val sweepX = lerp(cardBox.left - bandW, cardBox.right + bandW, easeInOutCubic(shimmerT))
                val gleamColor = if (nightRed) Color.argb(gleamAlpha, 255, 95, 85) else Color.argb(gleamAlpha, 255, 255, 255)
                cardHighlightPaint.shader = LinearGradient(
                    sweepX - bandW * 0.5f, cardBox.top,
                    sweepX + bandW * 0.5f, cardBox.bottom,
                    intArrayOf(Color.TRANSPARENT, gleamColor, Color.TRANSPARENT),
                    floatArrayOf(0f, 0.5f, 1f),
                    Shader.TileMode.CLAMP,
                )
                canvas.drawRect(cardBox.left, cardBox.top, cardBox.right, cardBox.bottom, cardHighlightPaint)
                cardHighlightPaint.shader = null
            }
        }

        canvas.restore() // clipPath
        canvas.restoreToCount(shellLayer)
    }

    // -------------------------------------------------------------------------
    // Apple Physics Spring & Choreography Curve Helpers (Pure Math)
    // -------------------------------------------------------------------------

    private fun subTimeline(t: Float, start: Float, end: Float): Float =
        ((t - start) / (end - start).coerceAtLeast(0.001f)).coerceIn(0f, 1f)

    private fun easeOutCubic(t: Float): Float {
        val u = 1f - t.coerceIn(0f, 1f)
        return 1f - u * u * u
    }

    private fun easeInOutCubic(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return if (x < 0.5f) 4f * x * x * x else 1f - ((-2f * x + 2f) * (-2f * x + 2f) * (-2f * x + 2f)) * 0.5f
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private fun rubberBand(delta: Float, maxLimit: Float): Float {
        if (maxLimit <= 0f) return 0f
        val sign = if (delta < 0f) -1f else 1f
        val absD = kotlin.math.abs(delta)
        val banded = maxLimit * (1f - 1f / (1f + absD / (maxLimit * 1.45f)))
        return sign * banded
    }

    /** Analytical Apple CASpringAnimation-style damped harmonic oscillator normalized to [0, 1]. */
    private fun springCurve(t: Float, damping: Float, frequency: Float): Float {
        val x = t.coerceIn(0f, 1f)
        if (x <= 0f) return 0f
        if (x >= 1f) return 1f
        val omega = frequency * (2.0 * Math.PI)
        fun eval(s: Double): Double {
            val decay = kotlin.math.exp(-damping * omega * s)
            return if (damping < 1f) {
                val wd = omega * kotlin.math.sqrt((1f - damping * damping).toDouble())
                1.0 - decay * (kotlin.math.cos(wd * s) + (damping * omega / wd) * kotlin.math.sin(wd * s))
            } else {
                1.0 - decay * (1.0 + omega * s)
            }
        }
        val endVal = eval(1.0).coerceAtLeast(0.001)
        return (eval(x.toDouble()) / endVal).toFloat()
    }

    private fun drawSquircleAppIcon(
        canvas: Canvas,
        drawable: Drawable,
        left: Float,
        top: Float,
        size: Float,
        nightRed: Boolean,
    ) {
        val cornerR = size * 0.225f
        canvas.save()
        clipPath.reset()
        rectScratch.set(left, top, left + size, top + size)
        clipPath.addRoundRect(rectScratch, cornerR, cornerR, Path.Direction.CW)
        canvas.clipPath(clipPath)
        drawable.setBounds(left.toInt(), top.toInt(), (left + size).toInt(), (top + size).toInt())
        drawable.colorFilter = if (nightRed) nightRedIconFilter else null
        drawable.draw(canvas)
        drawable.colorFilter = null
        canvas.restore()

        // Hairline Liquid Glass specular edge around the app icon squircle
        val rimW = (size * 0.014f).coerceIn(1.2f, 2.4f)
        cardRimPaint.strokeWidth = rimW
        cardRimPaint.shader = LinearGradient(
            left, top, left + size, top + size,
            intArrayOf(
                if (nightRed) Color.argb(95, 255, 69, 58) else Color.argb(115, 255, 255, 255),
                Color.TRANSPARENT,
                if (nightRed) Color.argb(50, 255, 69, 58) else Color.argb(58, 255, 255, 255),
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
        innerScratch.set(left + rimW * 0.5f, top + rimW * 0.5f, left + size - rimW * 0.5f, top + size - rimW * 0.5f)
        canvas.drawRoundRect(innerScratch, cornerR, cornerR, cardRimPaint)
        cardRimPaint.shader = null
    }

    private fun loadAppIcon(packageName: String?): Drawable? {
        if (packageName.isNullOrBlank()) return null
        LumaNotificationListener.appIconFor(packageName)?.let { return it }
        if (appIconCache.containsKey(packageName)) return appIconCache[packageName]
        val icon = runCatching { context.packageManager.getApplicationIcon(packageName) }.getOrNull()
        if (appIconCache.size >= 8) {
            val eldest = appIconCache.keys.firstOrNull()
            if (eldest != null) appIconCache.remove(eldest)
        }
        appIconCache[packageName] = icon
        return icon
    }

    // -------------------------------------------------------------------------
    // Transient Hint / Style-Switch Liquid Glass Pill
    // -------------------------------------------------------------------------

    private fun drawTransientIndicator(canvas: Canvas, w: Float, h: Float, nightRed: Boolean, darkSurface: Boolean) {
        val alpha = hintAlpha.coerceIn(0f, 1f)
        if (alpha <= 0.01f) return
        val banner = bannerText
        if (!banner.isNullOrEmpty()) {
            hintPaint.typeface = roundedBold
            hintPaint.textSize = (h * 0.032f).coerceIn(14f, 40f)
            val tw = hintPaint.measureText(banner)
            val padX = hintPaint.textSize * 1.0f
            val pillH = hintPaint.textSize * 2.0f
            val pillW = tw + padX * 2f
            val riseDy = pillH * 0.35f * (1f - alpha)
            val pillCy = h * 0.90f + riseDy
            val pillBox = FaceBox(w * 0.5f - pillW * 0.5f, pillCy - pillH * 0.5f, w * 0.5f + pillW * 0.5f, pillCy + pillH * 0.5f)
            layerBounds.set(pillBox.left - 8f, pillBox.top - 8f, pillBox.right + 8f, pillBox.bottom + 8f)
            val bannerLayer = canvas.saveLayerAlpha(layerBounds, (alpha * 255f).toInt().coerceIn(0, 255))
            drawGlassCard(
                canvas, pillBox, pillH * 0.5f,
                if (nightRed) Color.argb(180, 35, 6, 6) else Color.argb(190, 28, 30, 36),
                if (nightRed) Color.argb(195, 18, 2, 2) else Color.argb(210, 18, 19, 24),
                nightRed, darkSurface,
            )
            hintPaint.color = withAlpha(if (nightRed) Color.parseColor("#FF453A") else Color.WHITE, 0.94f)
            val y = pillBox.centerY - (hintPaint.ascent() + hintPaint.descent()) * 0.5f
            canvas.drawText(banner, pillBox.centerX, y, hintPaint)
            canvas.restoreToCount(bannerLayer)
            return
        }

        val hint = hintText
        if (hint.isNullOrEmpty()) return
        hintPaint.typeface = roundedBold
        hintPaint.color = withAlpha(if (nightRed) Color.parseColor("#FF453A") else colors.secondary, 0.52f * alpha)
        hintPaint.textSize = (h * 0.022f).coerceIn(11f, 28f)
        canvas.drawText(hint, w * 0.5f, h * 0.962f, hintPaint)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun drawCenteredTightText(
        canvas: Canvas,
        text: String,
        cx: Float,
        cy: Float,
        paint: Paint,
        topColor: Int,
        bottomColor: Int,
        inkHeight: Float,
    ) {
        paint.getTextBounds(text, 0, text.length, textBounds)
        val x = cx - textBounds.exactCenterX()
        val y = cy - textBounds.exactCenterY()
        paint.alpha = 255
        paint.xfermode = null
        paint.shader = LinearGradient(cx, cy - inkHeight * 0.52f, cx, cy + inkHeight * 0.52f, topColor, bottomColor, Shader.TileMode.CLAMP)
        canvas.drawText(text, x, y, paint)
        paint.shader = null
    }

    private fun clockTypefaceForSplit(): Typeface = when (theme.font) {
        FontChoice.ROUNDED -> roundedExtraBold
        else -> LumaFonts.resolve(context, theme.font)
    }

    private fun glyphAccentColor(c: app.luma.standby.domain.widget.WidgetContent, nightRed: Boolean, darkSurface: Boolean): Int = when {
        nightRed -> Color.parseColor("#FF453A")
        !darkSurface -> colors.accent
        c.kind == WidgetKind.BATTERY_RING -> Color.parseColor("#30D158")
        c.kind == WidgetKind.ALARM_BADGE -> Color.parseColor("#FF9F0A")
        c.kind == WidgetKind.MEDIA_NOW -> Color.parseColor("#FF375F")
        c.glyph == WidgetGlyph.SUN || c.glyph == WidgetGlyph.THUNDER -> Color.parseColor("#FFD60A")
        c.glyph == WidgetGlyph.RAIN || c.glyph == WidgetGlyph.SNOW -> Color.parseColor("#64D2FF")
        else -> colors.accent
    }

    private fun vibrantGlowAccent(): Int =
        if (isNearWhite(colors.accent)) Color.parseColor("#FF9F0A") else colors.accent

    private fun vibrantGlowPrimary(): Int =
        if (isNearWhite(colors.primary)) Color.parseColor("#FF453A") else colors.primary

    private fun isNightRed(): Boolean = theme.brightnessProfile == BrightnessProfile.NIGHT

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

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

    private fun buildWeekdayFormatter(): SimpleDateFormat =
        SimpleDateFormat("EEE", Locale.getDefault())

    private fun buildMonthFormatter(): SimpleDateFormat =
        SimpleDateFormat("MMM", Locale.getDefault())

    private fun isDark(color: Int): Boolean {
        val r = Color.red(color) / 255f
        val g = Color.green(color) / 255f
        val b = Color.blue(color) / 255f
        return (0.2126f * r + 0.7152f * g + 0.0722f * b) < 0.45f
    }

    private fun isNearWhite(color: Int): Boolean {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        return hsv[1] < 0.14f && hsv[2] > 0.85f
    }

    private fun shiftLuminance(color: Int, delta: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[2] = (hsv[2] + delta).coerceIn(0f, 1f)
        return Color.HSVToColor(Color.alpha(color), hsv)
    }

    private fun blendColor(a: Int, b: Int, ratio: Float): Int {
        val inv = 1f - ratio
        return Color.argb(
            (Color.alpha(a) * inv + Color.alpha(b) * ratio).toInt().coerceIn(0, 255),
            (Color.red(a) * inv + Color.red(b) * ratio).toInt().coerceIn(0, 255),
            (Color.green(a) * inv + Color.green(b) * ratio).toInt().coerceIn(0, 255),
            (Color.blue(a) * inv + Color.blue(b) * ratio).toInt().coerceIn(0, 255),
        )
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
        const val BANNER_VISIBLE_MS = 2_200L
    }
}
