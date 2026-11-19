package app.luma.standby.ui.clock

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import app.luma.standby.domain.widget.WidgetGlyph
import kotlin.math.cos
import kotlin.math.sin

/** Hand-drawn monochrome symbols: no emoji, icon font, image decode or animation. */
object WidgetIcons {
    fun draw(canvas: Canvas, glyph: WidgetGlyph, x: Float, y: Float, size: Float, color: Int) {
        if (glyph == WidgetGlyph.NONE) return
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.STROKE; strokeWidth = 1.7f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        canvas.save(); canvas.translate(x, y); canvas.scale(size / 24f, size / 24f)
        fun line(a: Float, b: Float, c: Float, d: Float) = canvas.drawLine(a,b,c,d,p)
        fun cloud() { val path = Path().apply { moveTo(5f,18f); cubicTo(-1f,18f,0f,10f,6f,10f); cubicTo(6f,2f,17f,2f,18f,10f); cubicTo(25f,9f,26f,18f,19f,18f); close() }; canvas.drawPath(path,p) }
        when (glyph) {
            WidgetGlyph.SUN -> { canvas.drawCircle(12f,12f,4f,p); for (i in 0..7) { val a=i*Math.PI/4; line((12+cos(a)*7).toFloat(),(12+sin(a)*7).toFloat(),(12+cos(a)*10).toFloat(),(12+sin(a)*10).toFloat()) } }
            WidgetGlyph.CLOUD -> cloud()
            WidgetGlyph.RAIN -> { canvas.save(); canvas.scale(.85f,.85f); cloud(); canvas.restore(); line(7f,18f,5f,22f); line(13f,18f,11f,22f); line(19f,18f,17f,22f) }
            WidgetGlyph.FOG -> { line(3f,7f,21f,7f); line(1f,12f,19f,12f); line(5f,17f,23f,17f) }
            WidgetGlyph.SNOW -> { for (i in 0..2) { val a=i*Math.PI/3; line((12-cos(a)*9).toFloat(),(12-sin(a)*9).toFloat(),(12+cos(a)*9).toFloat(),(12+sin(a)*9).toFloat()) } }
            WidgetGlyph.BOLT, WidgetGlyph.THUNDER -> { val path=Path().apply { moveTo(14f,2f); lineTo(5f,14f); lineTo(11f,14f); lineTo(9f,22f); lineTo(20f,9f); lineTo(13f,9f); close() }; canvas.drawPath(path,p) }
            WidgetGlyph.BATTERY -> { canvas.drawRoundRect(2f,6f,20f,18f,3f,3f,p); line(23f,10f,23f,14f); line(6f,10f,6f,14f); line(10f,10f,10f,14f) }
            WidgetGlyph.ALARM -> { canvas.drawCircle(12f,13f,8f,p); line(12f,8f,12f,13f); line(12f,13f,16f,15f); line(3f,4f,6f,2f); line(18f,2f,21f,4f); line(6f,20f,4f,23f); line(18f,20f,20f,23f) }
            WidgetGlyph.CALENDAR -> { canvas.drawRoundRect(3f,5f,21f,22f,3f,3f,p); line(3f,10f,21f,10f); line(7f,2f,7f,7f); line(17f,2f,17f,7f) }
            WidgetGlyph.MUSIC -> { line(9f,5f,21f,2f); line(9f,5f,9f,19f); line(21f,2f,21f,16f); canvas.drawOval(3f,17f,9f,22f,p); canvas.drawOval(15f,14f,21f,19f,p) }
            WidgetGlyph.BELL -> { val path=Path().apply { moveTo(3f,18f); lineTo(5f,15f); lineTo(5f,9f); cubicTo(5f,1f,19f,1f,19f,9f); lineTo(19f,15f); lineTo(21f,18f); close() }; canvas.drawPath(path,p); canvas.drawArc(9f,19f,15f,24f,0f,180f,false,p) }
            WidgetGlyph.NONE -> Unit
        }
        canvas.restore()
    }
}
