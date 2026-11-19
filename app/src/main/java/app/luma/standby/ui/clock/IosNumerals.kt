package app.luma.standby.ui.clock

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import kotlin.math.min

/**
 * Isotropic, round-terminal vector numerals and tick rings modelled after iOS StandBy
 * (both heavy bubbly Float numerals and bold rounded Digital Split numerals).
 *
 * Every digit is constructed directly in pixel coordinates inset by half the stroke
 * width, so terminals stay circular (`Paint.Cap.ROUND`) with zero X/Y non-uniform
 * scale distortion across any screen aspect ratio.
 */
object IosNumerals {

    /** Relative advance width of a digit so slim '1' sits naturally without dead air. */
    fun widthFactor(digit: Char, bubbly: Boolean): Float = when {
        digit == '1' && bubbly -> 0.56f
        digit == '1' -> 0.60f
        else -> 1.0f
    }

    /** Builds the centerline path for [digit] inside the outer ink box. */
    fun buildDigitPath(
        path: Path,
        digit: Char,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        strokeWidth: Float,
    ) {
        path.reset()
        val r = strokeWidth * 0.5f
        val xL = left + r
        val xR = (right - r).coerceAtLeast(xL + 1f)
        val yT = top + r
        val yB = (bottom - r).coerceAtLeast(yT + 1f)
        val cw = xR - xL
        val ch = yB - yT
        val cx = (xL + xR) * 0.5f
        val cy = (yT + yB) * 0.5f

        when (digit) {
            '0' -> addSuperOval(path, xL, yT, xR, yB)

            '1' -> {
                val stemX = xR - cw * 0.12f
                val flagStartX = xL + cw * 0.04f
                val flagStartY = yT + ch * 0.175f
                path.moveTo(flagStartX, flagStartY)
                path.quadTo(xL + cw * 0.52f, yT + ch * 0.09f, stemX, yT)
                path.lineTo(stemX, yB)
            }

            '2' -> {
                path.moveTo(xL + cw * 0.05f, yT + ch * 0.23f)
                path.cubicTo(
                    xL + cw * 0.05f, yT + ch * 0.05f,
                    cx - cw * 0.28f, yT,
                    cx + cw * 0.02f, yT,
                )
                path.cubicTo(
                    cx + cw * 0.34f, yT,
                    xR, yT + ch * 0.08f,
                    xR, yT + ch * 0.255f,
                )
                path.cubicTo(
                    xR, yT + ch * 0.455f,
                    cx + cw * 0.14f, yT + ch * 0.64f,
                    xL, yB,
                )
                path.lineTo(xR, yB)
            }

            '3' -> {
                val yMid = yT + ch * 0.468f
                val waistX = cx - cw * 0.08f
                path.moveTo(xL + cw * 0.06f, yT + ch * 0.185f)
                path.cubicTo(
                    xL + cw * 0.08f, yT + ch * 0.04f,
                    cx - cw * 0.26f, yT,
                    cx + cw * 0.02f, yT,
                )
                path.cubicTo(
                    cx + cw * 0.32f, yT,
                    xR - cw * 0.03f, yT + ch * 0.08f,
                    xR - cw * 0.03f, yT + ch * 0.235f,
                )
                path.cubicTo(
                    xR - cw * 0.03f, yT + ch * 0.385f,
                    cx + cw * 0.22f, yMid,
                    waistX, yMid,
                )
                path.moveTo(waistX, yMid)
                path.cubicTo(
                    cx + cw * 0.28f, yMid,
                    xR, yMid + ch * 0.11f,
                    xR, yT + ch * 0.735f,
                )
                path.cubicTo(
                    xR, yB - ch * 0.075f,
                    cx + cw * 0.32f, yB,
                    cx, yB,
                )
                path.cubicTo(
                    cx - cw * 0.28f, yB,
                    xL + cw * 0.06f, yB - ch * 0.04f,
                    xL + cw * 0.03f, yB - ch * 0.19f,
                )
            }

            '4' -> {
                val stemX = xL + cw * 0.71f
                val barY = yT + ch * 0.66f
                path.moveTo(stemX, yB)
                path.lineTo(stemX, yT)
                path.lineTo(xL, barY)
                path.lineTo(xR, barY)
            }

            '5' -> {
                val bowlTop = yT + ch * 0.40f
                path.moveTo(xR - cw * 0.05f, yT)
                path.lineTo(xL + cw * 0.08f, yT)
                path.lineTo(xL + cw * 0.04f, yT + ch * 0.45f)
                path.cubicTo(
                    xL + cw * 0.18f, bowlTop,
                    cx - cw * 0.14f, bowlTop,
                    cx + cw * 0.04f, bowlTop,
                )
                path.cubicTo(
                    cx + cw * 0.34f, bowlTop,
                    xR, bowlTop + ch * 0.11f,
                    xR, yT + ch * 0.71f,
                )
                path.cubicTo(
                    xR, yB - ch * 0.08f,
                    cx + cw * 0.32f, yB,
                    cx - cw * 0.02f, yB,
                )
                path.cubicTo(
                    cx - cw * 0.28f, yB,
                    xL + cw * 0.06f, yB - ch * 0.04f,
                    xL + cw * 0.03f, yB - ch * 0.17f,
                )
            }

            '6' -> buildSixOrNine(path, xL, yT, xR, yB, cw, ch, cx, cy, inverted = false)

            '7' -> {
                path.moveTo(xL + cw * 0.02f, yT)
                path.lineTo(xR, yT)
                path.cubicTo(
                    xR - cw * 0.17f, yT + ch * 0.32f,
                    cx + cw * 0.02f, yT + ch * 0.65f,
                    cx - cw * 0.08f, yB,
                )
            }

            '8' -> {
                val yMid = yT + ch * 0.468f
                val inset = cw * 0.055f
                addClosedLoop(path, xL + inset, yT, xR - inset, yMid)
                addClosedLoop(path, xL, yMid, xR, yB)
            }

            '9' -> buildSixOrNine(path, xL, yT, xR, yB, cw, ch, cx, cy, inverted = true)
        }
    }

    private fun buildSixOrNine(
        path: Path,
        xL: Float,
        yT: Float,
        xR: Float,
        yB: Float,
        cw: Float,
        ch: Float,
        cx: Float,
        cy: Float,
        inverted: Boolean,
    ) {
        fun mx(x: Float) = if (inverted) 2f * cx - x else x
        fun my(y: Float) = if (inverted) 2f * cy - y else y

        val bowlTop = yT + ch * 0.43f
        addClosedLoop(
            path,
            left = xL,
            top = if (inverted) yT else bowlTop,
            right = xR,
            bottom = if (inverted) 2f * cy - bowlTop else yB,
        )
        // Taut convex outer arch with zero S-inflection
        path.moveTo(mx(xR - cw * 0.08f), my(yT + ch * 0.16f))
        path.cubicTo(
            mx(xR - cw * 0.12f), my(yT + ch * 0.02f),
            mx(cx + cw * 0.28f), my(yT),
            mx(cx + cw * 0.04f), my(yT),
        )
        path.cubicTo(
            mx(cx - cw * 0.30f), my(yT),
            mx(xL), my(yT + ch * 0.22f),
            mx(xL), my(yT + ch * 0.71f),
        )
    }

    private fun addSuperOval(path: Path, left: Float, top: Float, right: Float, bottom: Float) {
        val w = right - left
        val h = bottom - top
        val cx = (left + right) * 0.5f
        val sideInset = min(h * 0.36f, w * 0.62f)
        val kx = w * 0.29f
        val ky = sideInset * 0.56f

        path.moveTo(cx, top)
        path.cubicTo(cx + kx, top, right, top + sideInset - ky, right, top + sideInset)
        path.lineTo(right, bottom - sideInset)
        path.cubicTo(right, bottom - sideInset + ky, cx + kx, bottom, cx, bottom)
        path.cubicTo(cx - kx, bottom, left, bottom - sideInset + ky, left, bottom - sideInset)
        path.lineTo(left, top + sideInset)
        path.cubicTo(left, top + sideInset - ky, cx - kx, top, cx, top)
        path.close()
    }

    private fun addClosedLoop(path: Path, left: Float, top: Float, right: Float, bottom: Float) {
        val w = right - left
        val h = bottom - top
        val cx = (left + right) * 0.5f
        val cy = (top + bottom) * 0.5f
        // 0.55228 * radius = 0.27614 * diameter for an exact cubic Bezier ellipse
        val kx = w * 0.276f
        val ky = h * 0.276f

        path.moveTo(cx, top)
        path.cubicTo(cx + kx, top, right, cy - ky, right, cy)
        path.cubicTo(right, cy + ky, cx + kx, bottom, cx, bottom)
        path.cubicTo(cx - kx, bottom, left, cy + ky, left, cy)
        path.cubicTo(left, cy - ky, cx - kx, top, cx, top)
        path.close()
    }

    /**
     * Draws a subtle 60-tick rounded-squircle bezel around [box] (like the iOS StandBy
     * Digital clock frame), lighting ticks up to [activeTick] (0..59).
     */
    fun drawSquircleTickRing(
        canvas: Canvas,
        box: FaceBox,
        activeTick: Int,
        litColor: Int,
        dimColor: Int,
        paint: Paint,
        ringPath: Path,
        rectScratch: RectF,
    ) {
        val minDim = min(box.width, box.height)
        if (minDim < 60f) return
        val inset = minDim * 0.025f
        val tickLen = minDim * 0.036f
        val cornerR = minDim * 0.24f

        rectScratch.set(
            box.left + inset,
            box.top + inset,
            box.right - inset,
            box.bottom - inset,
        )
        val cx = rectScratch.centerX()
        val top = rectScratch.top
        val right = rectScratch.right
        val bottom = rectScratch.bottom
        val left = rectScratch.left
        val r = min(cornerR, min(rectScratch.width(), rectScratch.height()) * 0.45f)

        ringPath.reset()
        ringPath.moveTo(cx, top)
        ringPath.lineTo(right - r, top)
        ringPath.arcTo(RectF(right - 2f * r, top, right, top + 2f * r), -90f, 90f, false)
        ringPath.lineTo(right, bottom - r)
        ringPath.arcTo(RectF(right - 2f * r, bottom - 2f * r, right, bottom), 0f, 90f, false)
        ringPath.lineTo(left + r, bottom)
        ringPath.arcTo(RectF(left, bottom - 2f * r, left + 2f * r, bottom), 90f, 90f, false)
        ringPath.lineTo(left, top + r)
        ringPath.arcTo(RectF(left, top, left + 2f * r, top + 2f * r), 180f, 90f, false)
        ringPath.lineTo(cx, top)
        ringPath.close()

        val measure = PathMeasure(ringPath, true)
        val total = measure.length
        if (total <= 0f) return

        val pos = FloatArray(2)
        val tan = FloatArray(2)
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        val baseStroke = (minDim * 0.0055f).coerceAtLeast(1.6f)

        for (i in 0 until 60) {
            val distance = (total * i) / 60f
            if (!measure.getPosTan(distance, pos, tan)) continue
            val nx = -tan[1]
            val ny = tan[0]
            val major = i % 5 == 0
            val len = if (major) tickLen * 1.18f else tickLen * 0.85f
            paint.strokeWidth = if (major) baseStroke * 1.25f else baseStroke
            paint.color = if (i <= activeTick) litColor else dimColor
            canvas.drawLine(
                pos[0],
                pos[1],
                pos[0] + nx * len,
                pos[1] + ny * len,
                paint,
            )
        }
    }
}
