package app.luma.standby.domain.theme

import app.luma.standby.domain.model.FontChoice
import app.luma.standby.domain.model.LumaTheme
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Theme import is a trust boundary: the JSON comes from a clipboard or a file the
 * user picked, so it is validated field by field before it is allowed anywhere
 * near the persisted settings. A rejected import must leave the existing themes
 * untouched.
 */
object ThemeImport {

    private val hexPattern = Regex("^#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")

    sealed interface Result {
        data class Ok(val theme: LumaTheme) : Result
        data class Rejected(val reason: String) : Result
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = false }

    fun parse(raw: String): Result {
        val text = raw.trim()
        if (text.isEmpty()) return Result.Rejected("empty")

        val element = runCatching { json.parseToJsonElement(text) }.getOrNull()
            ?: return Result.Rejected("not-json")
        val obj: JsonObject = element as? JsonObject ?: return Result.Rejected("not-an-object")

        val id = obj.string("id") ?: return Result.Rejected("missing-id")
        val name = obj.string("name") ?: return Result.Rejected("missing-name")
        if (name.isBlank()) return Result.Rejected("blank-name")

        val background = obj.string("background") ?: return Result.Rejected("missing-background")
        val primary = obj.string("primary") ?: return Result.Rejected("missing-primary")
        val secondary = obj.string("secondary") ?: return Result.Rejected("missing-secondary")
        val accent = obj.string("accent") ?: primary

        listOf(
            "background" to background,
            "primary" to primary,
            "secondary" to secondary,
            "accent" to accent,
        ).forEach { (field, value) ->
            if (!hexPattern.matches(value)) return Result.Rejected("bad-color-" + field)
        }

        val gradientEnd = obj.string("gradientEnd") ?: background
        if (!hexPattern.matches(gradientEnd)) return Result.Rejected("bad-color-gradientEnd")

        val fontName = obj.string("font")
        val font = if (fontName == null) {
            FontChoice.ROUNDED
        } else {
            runCatching { FontChoice.valueOf(fontName) }.getOrNull()
                ?: return Result.Rejected("bad-font")
        }

        val theme = LumaTheme(
            id = id,
            name = name,
            background = background,
            primary = primary,
            secondary = secondary,
            accent = accent,
            gradient = obj.boolean("gradient") ?: false,
            gradientEnd = gradientEnd,
            font = font,
            showSeconds = obj.boolean("showSeconds") ?: false,
            brightnessProfile = app.luma.standby.domain.model.BrightnessProfile.STANDARD,
            builtIn = false,
        )
        return Result.Ok(theme)
    }

    private fun JsonObject.string(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()

    private fun JsonObject.boolean(key: String): Boolean? =
        runCatching { this[key]?.jsonPrimitive?.content?.toBooleanStrict() }.getOrNull()
}
