package app.luma.standby.ui.clock

import android.content.Context
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import app.luma.standby.R
import app.luma.standby.domain.model.FontChoice

/**
 * Resolves bundled SIL OFL 1.1 display fonts and system typefaces.
 *
 * - [roundedExtraBold] / [FontChoice.ROUNDED]: bundled Nunito ExtraBold (800),
 *   a crisp rounded geometric face aligned with Apple's SF Pro Rounded.
 * - [roundedBold]: bundled Nunito Bold (700) for watch markers and widget hierarchy.
 * - [floatDisplay]: bundled Fredoka Bold (700), plump rounded display numerals for
 *   the iOS StandBy Float face.
 * - [FontChoice.DISPLAY]: bundled Oswald (700) condensed display face.
 */
object LumaFonts {

    @Volatile
    private var display: Typeface? = null

    @Volatile
    private var rounded800: Typeface? = null

    @Volatile
    private var rounded700: Typeface? = null

    @Volatile
    private var float700: Typeface? = null

    fun resolve(context: Context, choice: FontChoice): Typeface = when (choice) {
        FontChoice.DISPLAY -> displayLater(context)
        FontChoice.ROUNDED -> roundedExtraBold(context)
        else -> Typeface.create(choice.family, choice.style)
    }

    fun roundedExtraBold(context: Context): Typeface =
        rounded800 ?: synchronized(this) {
            rounded800 ?: loadStatic(context, R.font.nunito_extrabold, Typeface.DEFAULT_BOLD)
                .also { rounded800 = it }
        }

    fun roundedBold(context: Context): Typeface =
        rounded700 ?: synchronized(this) {
            rounded700 ?: loadStatic(context, R.font.nunito_bold, Typeface.DEFAULT_BOLD)
                .also { rounded700 = it }
        }

    fun floatDisplay(context: Context): Typeface =
        float700 ?: synchronized(this) {
            float700 ?: loadStatic(context, R.font.fredoka_bold, roundedExtraBold(context))
                .also { float700 = it }
        }

    private fun displayLater(context: Context): Typeface =
        display ?: synchronized(this) {
            display ?: buildDisplay(context).also { display = it }
        }

    private fun loadStatic(context: Context, resId: Int, fallback: Typeface): Typeface =
        runCatching { ResourcesCompat.getFont(context, resId) }.getOrNull() ?: fallback

    private fun buildDisplay(context: Context): Typeface {
        val base = runCatching { ResourcesCompat.getFont(context, R.font.oswald) }.getOrNull()
            ?: return Typeface.DEFAULT_BOLD
        return runCatching {
            Typeface.create(base, 700, false)
        }.getOrDefault(base)
    }
}
