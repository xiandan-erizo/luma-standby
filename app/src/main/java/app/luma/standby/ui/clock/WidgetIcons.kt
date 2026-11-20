package app.luma.standby.ui.clock

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import app.luma.standby.domain.widget.WidgetGlyph
import kotlin.math.cos
import kotlin.math.sin

/**
 * SF-Symbols-inspired multi-tone filled vector glyphs for iOS StandBy cards:
 * luminous sun, plump frosted clouds, sun-behind-cloud partly cloudy, filled
 * navigation pointer, and crisp status glyphs. Zero emoji, bitmap, or animation.
 */
object WidgetIcons {

    fun draw(
        canvas: Canvas,
        glyph: WidgetGlyph,
        x: Float,
        y: Float,
        size: Float,
        color: Int,
        secondaryColor: Int = color,
        nightRed: Boolean = false,
    ) {
        if (glyph == WidgetGlyph.NONE || size <= 0f) return

        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = 2.0f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
        }

        canvas.save()
        canvas.translate(x, y)
        canvas.scale(size / 24f, size / 24f)

        fun line(a: Float, b: Float, c: Float, d: Float, p: Paint = stroke) =
            canvas.drawLine(a, b, c, d, p)

        fun drawFilledCloud(cx: Float, cy: Float, scale: Float, tint: Int) {
            canvas.save()
            canvas.translate(cx, cy)
            canvas.scale(scale, scale)
            val path = Path().apply {
                // Plump multi-lobe Apple Weather SF Symbol cloud silhouette
                moveTo(-6.8f, 4.4f)
                cubicTo(-10.2f, 4.4f, -10.8f, -0.4f, -7.2f, -1.2f)
                cubicTo(-6.8f, -5.8f, -1.0f, -7.2f, 1.8f, -4.0f)
                cubicTo(3.8f, -5.2f, 6.8f, -3.6f, 6.6f, -1.0f)
                cubicTo(10.0f, -0.6f, 10.2f, 4.4f, 6.6f, 4.4f)
                close()
            }
            val topColor = if (nightRed) tint else blendWhite(tint, 0.25f)
            val botColor = if (nightRed) withAlpha(tint, 0.72f) else blendColor(tint, Color.parseColor("#B8CCE0"), 0.35f)
            val cloudPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                shader = LinearGradient(0f, -6.5f, 0f, 4.6f, topColor, botColor, Shader.TileMode.CLAMP)
            }
            canvas.drawPath(path, cloudPaint)
            canvas.restore()
        }

        fun drawLuminousSun(cx: Float, cy: Float, radius: Float, sunColor: Int) {
            // Soft outer halo (skipped in night red for strict OLED dimness)
            if (!nightRed) {
                val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = RadialGradient(
                        cx, cy, radius * 2.15f,
                        intArrayOf(withAlpha(sunColor, 0.38f), withAlpha(sunColor, 0.08f), Color.TRANSPARENT),
                        floatArrayOf(0.25f, 0.65f, 1f),
                        Shader.TileMode.CLAMP,
                    )
                }
                canvas.drawCircle(cx, cy, radius * 2.15f, glowPaint)
            }
            val discTop = if (nightRed) sunColor else Color.parseColor("#FFF07C")
            val discBot = if (nightRed) withAlpha(sunColor, 0.85f) else Color.parseColor("#FFB300")
            val discPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                shader = LinearGradient(cx, cy - radius, cx, cy + radius, discTop, discBot, Shader.TileMode.CLAMP)
            }
            canvas.drawCircle(cx, cy, radius, discPaint)

            stroke.color = if (nightRed) sunColor else Color.parseColor("#FFD60A")
            stroke.strokeWidth = (radius * 0.34f).coerceIn(1.5f, 2.2f)
            val rIn = radius * 1.38f
            val rOut = radius * 1.88f
            for (i in 0..7) {
                val a = i * Math.PI / 4.0
                line(
                    (cx + cos(a) * rIn).toFloat(),
                    (cy + sin(a) * rIn).toFloat(),
                    (cx + cos(a) * rOut).toFloat(),
                    (cy + sin(a) * rOut).toFloat(),
                )
            }
        }

        when (glyph) {
            WidgetGlyph.SUN -> {
                drawLuminousSun(12f, 12f, 5.2f, color)
            }

            WidgetGlyph.PARTLY_CLOUDY -> {
                drawLuminousSun(15.2f, 8.6f, 3.6f, if (nightRed) color else Color.parseColor("#FFD60A"))
                drawFilledCloud(10.6f, 14.2f, 0.92f, secondaryColor)
            }

            WidgetGlyph.CLOUD -> {
                drawFilledCloud(12f, 12.8f, 1.05f, secondaryColor)
            }

            WidgetGlyph.RAIN -> {
                drawFilledCloud(12f, 9.6f, 0.94f, secondaryColor)
                stroke.color = color
                stroke.strokeWidth = 1.85f
                line(8.2f, 16.4f, 6.8f, 20.6f)
                line(12.6f, 16.8f, 11.2f, 21.2f)
                line(17.0f, 16.4f, 15.6f, 20.6f)
            }

            WidgetGlyph.FOG -> {
                drawFilledCloud(12f, 8.6f, 0.84f, secondaryColor)
                stroke.color = withAlpha(secondaryColor, 0.78f)
                stroke.strokeWidth = 1.9f
                line(4.5f, 16.2f, 19.5f, 16.2f)
                line(6.5f, 19.8f, 17.5f, 19.8f)
            }

            WidgetGlyph.SNOW -> {
                stroke.color = color
                stroke.strokeWidth = 1.8f
                for (i in 0..2) {
                    val a = i * Math.PI / 3.0
                    val dx = (cos(a) * 8.2).toFloat()
                    val dy = (sin(a) * 8.2).toFloat()
                    line(12f - dx, 12f - dy, 12f + dx, 12f + dy)
                }
                fill.color = color
                canvas.drawCircle(12f, 12f, 2.0f, fill)
            }

            WidgetGlyph.THUNDER -> {
                drawFilledCloud(12f, 8.6f, 0.92f, secondaryColor)
                val bolt = Path().apply {
                    moveTo(13.2f, 13.0f)
                    lineTo(9.0f, 18.2f)
                    lineTo(12.1f, 18.2f)
                    lineTo(10.8f, 22.8f)
                    lineTo(16.0f, 16.8f)
                    lineTo(12.7f, 16.8f)
                    close()
                }
                fill.color = color
                canvas.drawPath(bolt, fill)
            }

            WidgetGlyph.BOLT -> {
                val path = Path().apply {
                    moveTo(13.6f, 2.0f)
                    lineTo(5.6f, 13.4f)
                    lineTo(11.3f, 13.4f)
                    lineTo(9.8f, 22.0f)
                    lineTo(18.6f, 10.2f)
                    lineTo(12.8f, 10.2f)
                    close()
                }
                fill.color = color
                canvas.drawPath(path, fill)
            }

            WidgetGlyph.BATTERY -> {
                stroke.strokeWidth = 1.8f
                canvas.drawRoundRect(RectF(2f, 6.5f, 19.5f, 17.5f), 3.4f, 3.4f, stroke)
                fill.color = withAlpha(color, 0.85f)
                canvas.drawRoundRect(RectF(20.8f, 9.8f, 22.6f, 14.2f), 0.9f, 0.9f, fill)
                fill.color = color
                canvas.drawRoundRect(RectF(4.4f, 8.9f, 15.2f, 15.1f), 1.6f, 1.6f, fill)
            }

            WidgetGlyph.ALARM -> {
                stroke.strokeWidth = 2.0f
                canvas.drawCircle(12f, 13.2f, 7.6f, stroke)
                line(12f, 8.6f, 12f, 13.2f)
                line(12f, 13.2f, 15.4f, 15.2f)
                line(3.6f, 5.2f, 7.0f, 2.6f)
                line(17.0f, 2.6f, 20.4f, 5.2f)
            }

            WidgetGlyph.CALENDAR -> {
                canvas.drawRoundRect(RectF(3f, 5f, 21f, 21.5f), 3.4f, 3.4f, stroke)
                line(3f, 10.2f, 21f, 10.2f)
                line(7.5f, 2.5f, 7.5f, 6.8f)
                line(16.5f, 2.5f, 16.5f, 6.8f)
            }

            WidgetGlyph.MUSIC -> {
                val beam = Path().apply {
                    moveTo(9.2f, 5.4f)
                    lineTo(20.2f, 3.0f)
                    lineTo(20.2f, 6.2f)
                    lineTo(9.2f, 8.6f)
                    close()
                }
                fill.color = color
                canvas.drawPath(beam, fill)
                stroke.strokeWidth = 2.0f
                line(9.2f, 6.0f, 9.2f, 17.8f)
                line(20.2f, 3.8f, 20.2f, 15.4f)
                canvas.drawOval(RectF(4.0f, 15.6f, 10.0f, 20.4f), fill)
                canvas.drawOval(RectF(15.0f, 13.2f, 21.0f, 18.0f), fill)
            }

            WidgetGlyph.BELL -> {
                val path = Path().apply {
                    moveTo(4.5f, 17.2f)
                    cubicTo(5.6f, 16.0f, 6.2f, 14.5f, 6.2f, 10.2f)
                    cubicTo(6.2f, 4.6f, 17.8f, 4.6f, 17.8f, 10.2f)
                    cubicTo(17.8f, 14.5f, 18.4f, 16.0f, 19.5f, 17.2f)
                    close()
                }
                fill.color = color
                canvas.drawPath(path, fill)
                canvas.drawArc(RectF(9.6f, 18.0f, 14.4f, 21.8f), 0f, 180f, true, fill)
            }

            WidgetGlyph.NONE -> Unit
        }
        canvas.restore()
    }

    /** Crisp filled iOS Location Services navigation arrow (replaces thin Unicode ↗). */
    fun drawLocationArrow(canvas: Canvas, x: Float, y: Float, size: Float, color: Int) {
        if (size <= 0f) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
        }
        val path = Path().apply {
            moveTo(21.0f, 3.0f)
            lineTo(3.2f, 10.6f)
            lineTo(10.8f, 13.2f)
            lineTo(13.4f, 20.8f)
            close()
        }
        canvas.save()
        canvas.translate(x, y)
        canvas.scale(size / 24f, size / 24f)
        canvas.drawPath(path, paint)
        canvas.restore()
    }

    private fun withAlpha(color: Int, alpha: Float): Int =
        Color.argb((Color.alpha(color) * alpha).toInt().coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    private fun blendWhite(color: Int, ratio: Float): Int = blendColor(color, Color.WHITE, ratio)

    private fun blendColor(a: Int, b: Int, ratio: Float): Int {
        val inv = 1f - ratio
        return Color.argb(
            (Color.alpha(a) * inv + Color.alpha(b) * ratio).toInt().coerceIn(0, 255),
            (Color.red(a) * inv + Color.red(b) * ratio).toInt().coerceIn(0, 255),
            (Color.green(a) * inv + Color.green(b) * ratio).toInt().coerceIn(0, 255),
            (Color.blue(a) * inv + Color.blue(b) * ratio).toInt().coerceIn(0, 255),
        )
    }
}
