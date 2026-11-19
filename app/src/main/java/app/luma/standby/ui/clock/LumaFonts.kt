package app.luma.standby.ui.clock

import android.content.Context
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import app.luma.standby.R
import app.luma.standby.domain.model.FontChoice

/**
 * Resolves a [FontChoice] to a real Typeface.
 *
 * DISPLAY is the bundled Oswald (SIL OFL 1.1) at weight 700. It is a variable font,
 * so the weight is requested through the family builder rather than Typeface.create,
 * which would silently return the default instance.
 */
object LumaFonts {

    @Volatile
    private var display: Typeface? = null

    fun resolve(context: Context, choice: FontChoice): Typeface =
        if (choice == FontChoice.DISPLAY) displayLater(context) else Typeface.create(choice.family, choice.style)

    private fun displayLater(context: Context): Typeface =
        display ?: synchronized(this) {
            display ?: buildDisplay(context).also { display = it }
        }

    private fun buildDisplay(context: Context): Typeface {
        val base = ResourcesCompat.getFont(context, R.font.oswald) ?: return Typeface.DEFAULT
        return runCatching {
            Typeface.create(base, 700, false)
        }.getOrDefault(base)
    }
}
