package app.luma.standby.ui.clock

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import app.luma.standby.domain.widget.WidgetGlyph
import kotlin.math.cos
import kotlin.math.sin

/** Crisp SF-Symbols-inspired vector glyphs: no emoji, icon font, image decode or animation. */
object WidgetIcons {

    fun draw(
        canvas: Canvas,
        glyph: WidgetGlyph,
        x: Float,
        y: Float,
        size: Float,
        color: Int,
        secondaryColor: Int = color,
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

        fun cloudPath(): Path = Path().apply {
            moveTo(6.2f, 18.2f)
            cubicTo(1.8f, 18.2f, 1.5f, 11.6f, 6.2f, 11.0f)
            cubicTo(6.8f, 4.8f, 16.5f, 4.6f, 17.8f, 10.6f)
            cubicTo(22.6f, 10.6f, 22.8f, 18.2f, 18.2f, 18.2f)
            close()
        }

        when (glyph) {
            WidgetGlyph.SUN -> {
                fill.color = color
                canvas.drawCircle(12f, 12f, 4.6f, fill)
                stroke.color = color
                for (i in 0..7) {
                    val a = i * Math.PI / 4.0
                    line(
                        (12 + cos(a) * 7.4).toFloat(),
                        (12 + sin(a) * 7.4).toFloat(),
                        (12 + cos(a) * 10.2).toFloat(),
                        (12 + sin(a) * 10.2).toFloat(),
                    )
                }
            }

            WidgetGlyph.CLOUD -> {
                stroke.color = secondaryColor
                canvas.drawPath(cloudPath(), stroke)
            }

            WidgetGlyph.RAIN -> {
                stroke.color = secondaryColor
                canvas.save()
                canvas.translate(0f, -1.5f)
                canvas.drawPath(cloudPath(), stroke)
                canvas.restore()
                stroke.color = color
                line(8f, 18.5f, 6.5f, 22f)
                line(12.5f, 18.5f, 11f, 22f)
                line(17f, 18.5f, 15.5f, 22f)
            }

            WidgetGlyph.FOG -> {
                line(3f, 7.5f, 21f, 7.5f)
                line(2f, 12f, 19f, 12f)
                line(5f, 16.5f, 22f, 16.5f)
            }

            WidgetGlyph.SNOW -> {
                for (i in 0..2) {
                    val a = i * Math.PI / 3.0
                    line(
                        (12 - cos(a) * 8.5).toFloat(),
                        (12 - sin(a) * 8.5).toFloat(),
                        (12 + cos(a) * 8.5).toFloat(),
                        (12 + sin(a) * 8.5).toFloat(),
                    )
                }
            }

            WidgetGlyph.BOLT, WidgetGlyph.THUNDER -> {
                val path = Path().apply {
                    moveTo(13.8f, 2.2f)
                    lineTo(5.8f, 13.4f)
                    lineTo(11.4f, 13.4f)
                    lineTo(9.8f, 21.8f)
                    lineTo(18.6f, 10.2f)
                    lineTo(12.8f, 10.2f)
                    close()
                }
                fill.color = color
                canvas.drawPath(path, fill)
            }

            WidgetGlyph.BATTERY -> {
                canvas.drawRoundRect(RectF(2f, 6.5f, 19.5f, 17.5f), 3.2f, 3.2f, stroke)
                line(22f, 10f, 22f, 14f)
                fill.color = color
                canvas.drawRoundRect(RectF(4.8f, 9.2f, 14.5f, 14.8f), 1.6f, 1.6f, fill)
            }

            WidgetGlyph.ALARM -> {
                canvas.drawCircle(12f, 13f, 7.8f, stroke)
                line(12f, 8.5f, 12f, 13f)
                line(12f, 13f, 15.5f, 15f)
                line(3.5f, 4.8f, 6.8f, 2.4f)
                line(17.2f, 2.4f, 20.5f, 4.8f)
            }

            WidgetGlyph.CALENDAR -> {
                canvas.drawRoundRect(RectF(3f, 5f, 21f, 21.5f), 3.2f, 3.2f, stroke)
                line(3f, 10f, 21f, 10f)
                line(7.5f, 2.5f, 7.5f, 6.8f)
                line(16.5f, 2.5f, 16.5f, 6.8f)
            }

            WidgetGlyph.MUSIC -> {
                line(9f, 5.5f, 20.5f, 3f)
                line(9f, 5.5f, 9f, 18.2f)
                line(20.5f, 3f, 20.5f, 15.8f)
                fill.color = color
                canvas.drawOval(RectF(3.5f, 15.8f, 9.5f, 20.8f), fill)
                canvas.drawOval(RectF(15f, 13.4f, 21f, 18.4f), fill)
            }

            WidgetGlyph.BELL -> {
                val path = Path().apply {
                    moveTo(4f, 17.5f)
                    lineTo(5.8f, 15f)
                    lineTo(5.8f, 9.5f)
                    cubicTo(5.8f, 2.5f, 18.2f, 2.5f, 18.2f, 9.5f)
                    lineTo(18.2f, 15f)
                    lineTo(20f, 17.5f)
                    close()
                }
                canvas.drawPath(path, stroke)
                canvas.drawArc(RectF(9.5f, 18.2f, 14.5f, 22.5f), 0f, 180f, false, stroke)
            }

            WidgetGlyph.NONE -> Unit
        }
        canvas.restore()
    }
}
