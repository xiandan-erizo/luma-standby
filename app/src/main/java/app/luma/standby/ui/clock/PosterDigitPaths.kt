package app.luma.standby.ui.clock

import android.graphics.Canvas
import android.graphics.CornerPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * Bespoke ultra-condensed, softly-rounded poster numerals (`0`–`9` + plump `:`)
 * modelled 1:1 on the iconic iOS StandBy "Digital" poster face (`16:25`).
 *
 * Uses `Paint.getFillPath` on mathematically pure centerlines paired with a subtle
 * `CornerPathEffect` on the resulting closed outlines so vertical and horizontal
 * strokes stay 100% uniform in weight while every terminal has crisp Apple
 * flat-cut softly-beveled corners. Outlines are cached by `digitHeight` so
 * steady-state redraws perform zero path or paint allocations.
 */
internal class PosterDigitPaths {

    private var cachedHeight: Float = -1f
    private var cachedStrokeW: Float = 0f
    private val digitWidths = FloatArray(10)
    private val digitPaths = Array<List<Path>>(10) { emptyList() }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val scratchRect = RectF()
    private val rotateMatrix = Matrix()

    /** Returns the rendered pixel width of [text] (digits & `:`) at height [digitH]. */
    fun measureTime(text: String, digitH: Float): Float {
        ensureCache(digitH)
        var total = 0f
        for (i in text.indices) {
            val ch = text[i]
            val prev = if (i > 0) text[i - 1] else null
            if (prev != null) total += kerning(prev, ch, digitH)
            total += charWidth(ch, digitH)
        }
        return total
    }

    /** Draws [text] with its top edge at [top] and left edge at [left] in [color]. */
    fun drawTime(canvas: Canvas, text: String, left: Float, top: Float, digitH: Float, color: Int) {
        if (digitH <= 1f || text.isEmpty()) return
        ensureCache(digitH)
        fillPaint.color = color
        fillPaint.alpha = (color ushr 24) and 0xFF
        fillPaint.pathEffect = CornerPathEffect(cachedStrokeW * 0.19f)

        var x = left
        for (i in text.indices) {
            val ch = text[i]
            val prev = if (i > 0) text[i - 1] else null
            if (prev != null) x += kerning(prev, ch, digitH)

            when (ch) {
                ':' -> {
                    val slotW = colonSlotWidth(digitH)
                    val cx = x + slotW * 0.5f
                    val dotR = cachedStrokeW * 0.65f
                    val savedEffect = fillPaint.pathEffect
                    fillPaint.pathEffect = null
                    canvas.drawCircle(cx, top + digitH * 0.305f, dotR, fillPaint)
                    canvas.drawCircle(cx, top + digitH * 0.695f, dotR, fillPaint)
                    fillPaint.pathEffect = savedEffect
                    x += slotW
                }
                in '0'..'9' -> {
                    val idx = ch - '0'
                    canvas.save()
                    canvas.translate(x, top)
                    for (p in digitPaths[idx]) {
                        canvas.drawPath(p, fillPaint)
                    }
                    canvas.restore()
                    x += digitWidths[idx]
                }
                else -> x += digitH * 0.16f
            }
        }
    }

    private fun charWidth(ch: Char, h: Float): Float = when (ch) {
        ':' -> colonSlotWidth(h)
        in '0'..'9' -> digitWidths[ch - '0']
        else -> h * 0.16f
    }

    private fun colonSlotWidth(h: Float): Float = h * 0.195f

    private fun kerning(prev: Char, next: Char, h: Float): Float = when {
        prev == ':' || next == ':' -> h * 0.036f
        prev == '1' || next == '1' -> h * 0.052f
        else -> h * 0.062f
    }

    private fun ensureCache(h: Float) {
        if (kotlin.math.abs(h - cachedHeight) < 0.5f) return
        cachedHeight = h
        val t = (h * 0.105f).coerceAtLeast(2f)
        cachedStrokeW = t
        val stdW = h * 0.365f
        val oneW = h * 0.255f

        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = t
            strokeCap = Paint.Cap.BUTT
            strokeJoin = Paint.Join.ROUND
        }

        for (d in 0..9) {
            val w = if (d == 1) oneW else stdW
            digitWidths[d] = w
            val centerlines = buildCenterlines(d, w, h, t)
            digitPaths[d] = centerlines.map { line ->
                if (d == 1) {
                    // "1" is built directly as a razor-clean closed polygon matching Apple's flag & stem!
                    line
                } else {
                    Path().also { outline -> strokePaint.getFillPath(line, outline) }
                }
            }
        }
    }

    private fun buildCenterlines(digit: Int, w: Float, h: Float, t: Float): List<Path> {
        val half = t * 0.5f
        val l = half
        val r = w - half
        val top = half
        val bot = h - half
        val spanW = (r - l).coerceAtLeast(1f)
        val cx = (l + r) * 0.5f
        val rx = spanW * 0.5f
        val ry = (rx * 1.18f).coerceAtMost((bot - top) * 0.27f)

        return when (digit) {
            0 -> listOf(
                Path().apply {
                    scratchRect.set(l, top, r, bot)
                    addRoundRect(scratchRect, rx, ry, Path.Direction.CW)
                },
            )

            1 -> {
                // Closed contour for "1" matching Apple StandBy Digital's exact angled top-left flag
                val stemLeft = w - t
                val stemRight = w
                val flagTopY = h * 0.105f
                val flagBotY = flagTopY + t * 1.12f
                listOf(
                    Path().apply {
                        moveTo(stemLeft, 0f)
                        lineTo(stemRight, 0f)
                        lineTo(stemRight, h)
                        lineTo(stemLeft, h)
                        lineTo(stemLeft, flagBotY * 0.72f)
                        lineTo(0f, flagBotY)
                        lineTo(0f, flagTopY)
                        close()
                    },
                )
            }

            2 -> listOf(
                Path().apply {
                    val archBot = top + ry * 2f
                    moveTo(l, top + ry + h * 0.075f)
                    lineTo(l, top + ry)
                    scratchRect.set(l, top, r, archBot)
                    arcTo(scratchRect, 180f, 180f, false)
                    lineTo(r, top + ry + h * 0.080f)
                    cubicTo(
                        r, top + ry + h * 0.22f,
                        l + spanW * 0.22f, bot - h * 0.26f,
                        l + spanW * 0.02f, bot - half * 0.12f,
                    )
                    lineTo(l + spanW * 0.02f, bot)
                    lineTo(r, bot)
                },
            )

            3 -> listOf(
                Path().apply {
                    val midY = h * 0.465f
                    val upperRy = ry * 0.94f
                    val lowerRy = ry * 1.02f
                    moveTo(l + spanW * 0.04f, top + upperRy + h * 0.055f)
                    lineTo(l + spanW * 0.04f, top + upperRy)
                    scratchRect.set(l + spanW * 0.04f, top, r, top + upperRy * 2f)
                    arcTo(scratchRect, 180f, 180f, false)
                    lineTo(r, midY - upperRy)
                    scratchRect.set(l + spanW * 0.08f, midY - upperRy * 2f, r, midY)
                    arcTo(scratchRect, 0f, 90f, false)
                    lineTo(l + spanW * 0.42f, midY)
                    moveTo(l + spanW * 0.42f, midY)
                    scratchRect.set(l, midY, r, midY + lowerRy * 2f)
                    arcTo(scratchRect, -90f, 90f, false)
                    lineTo(r, bot - lowerRy)
                    scratchRect.set(l, bot - lowerRy * 2f, r, bot)
                    arcTo(scratchRect, 0f, 180f, false)
                    lineTo(l, bot - lowerRy - h * 0.055f)
                },
            )

            4 -> {
                val stemX = r - spanW * 0.18f
                val crossY = h * 0.675f
                val stem = Path().apply {
                    moveTo(stemX, top)
                    lineTo(stemX, bot)
                }
                val diagAndBar = Path().apply {
                    moveTo(stemX - half * 0.22f, top + h * 0.028f)
                    lineTo(l, crossY - half * 0.10f)
                    lineTo(l, crossY)
                    lineTo(r, crossY)
                }
                listOf(stem, diagAndBar)
            }

            5 -> listOf(
                Path().apply {
                    val bowlTop = h * 0.375f
                    val bowlRy = ry * 1.02f
                    val leftX = l + spanW * 0.04f
                    // Top horizontal bar -> left vertical stem -> seamless shoulder into lower bowl
                    moveTo(r, top)
                    lineTo(leftX, top)
                    lineTo(leftX, bowlTop + bowlRy * 0.62f)
                    cubicTo(
                        leftX, bowlTop + bowlRy * 0.14f,
                        leftX + (cx - leftX) * 0.48f, bowlTop,
                        cx, bowlTop,
                    )
                    scratchRect.set(l, bowlTop, r, bowlTop + bowlRy * 2f)
                    arcTo(scratchRect, -90f, 90f, false)
                    lineTo(r, bot - ry)
                    scratchRect.set(l, bot - ry * 2f, r, bot)
                    arcTo(scratchRect, 0f, 180f, false)
                    lineTo(l, bot - ry - h * 0.065f)
                },
            )

            6 -> {
                val loopTop = h * 0.385f
                listOf(
                    Path().apply {
                        moveTo(r, top + ry + h * 0.060f)
                        lineTo(r, top + ry)
                        scratchRect.set(l, top, r, top + ry * 2f)
                        arcTo(scratchRect, 0f, -180f, false)
                        lineTo(l, bot - ry)
                        scratchRect.set(l, bot - ry * 2f, r, bot)
                        arcTo(scratchRect, 180f, -180f, false)
                        lineTo(r, loopTop + ry)
                        scratchRect.set(l, loopTop, r, loopTop + ry * 2f)
                        arcTo(scratchRect, 0f, -180f, false)
                    },
                )
            }

            7 -> listOf(
                Path().apply {
                    val footX = l + spanW * 0.32f
                    moveTo(l, top)
                    lineTo(r, top)
                    lineTo(r, top + h * 0.055f)
                    cubicTo(
                        r - spanW * 0.06f, h * 0.34f,
                        footX, h * 0.66f,
                        footX, bot - h * 0.10f,
                    )
                    lineTo(footX, bot)
                },
            )

            8 -> {
                // Share exact center stroke at midY so waist thickness equals single stroke weight `t`
                val midY = h * 0.465f
                val insetTop = spanW * 0.03f
                val upperRy = ry * 0.92f
                val lowerRy = ry * 1.00f
                listOf(
                    Path().apply {
                        scratchRect.set(l + insetTop, top, r - insetTop, midY)
                        addRoundRect(scratchRect, rx - insetTop, upperRy, Path.Direction.CW)
                    },
                    Path().apply {
                        scratchRect.set(l, midY, r, bot)
                        addRoundRect(scratchRect, rx, lowerRy, Path.Direction.CW)
                    },
                )
            }

            9 -> {
                val six = buildCenterlines(6, w, h, t)
                rotateMatrix.reset()
                rotateMatrix.setRotate(180f, w * 0.5f, h * 0.5f)
                six.map { p -> Path(p).apply { transform(rotateMatrix) } }
            }

            else -> emptyList()
        }
    }
}
