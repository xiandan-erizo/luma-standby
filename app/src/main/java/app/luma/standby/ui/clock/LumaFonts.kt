package app.luma.standby.ui.clock
import android.content.Context
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import app.luma.standby.R
import app.luma.standby.domain.model.FontChoice
object LumaFonts {
    @Volatile private var display: Typeface? = null
    fun resolve(context: Context, choice: FontChoice): Typeface =
        if (choice == FontChoice.DISPLAY) display ?: synchronized(this) {
            display ?: (ResourcesCompat.getFont(context, R.font.anton) ?: Typeface.DEFAULT).also { display = it }
        } else Typeface.create(choice.family, choice.style)
}
