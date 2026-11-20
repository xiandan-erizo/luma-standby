package app.luma.standby.platform.widgets

import app.luma.standby.domain.widget.LiveActivityInfo
import app.luma.standby.domain.widget.LiveActivitySource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * Result of inspecting a notification for Xiaomi HyperOS Super Island (`miui.focus.param`)
 * or Android 16 Live Update (`EXTRA_SHORT_CRITICAL_TEXT` / promoted ongoing) metadata.
 */
internal sealed interface LiveActivityParseResult {
    /** Notification is not a live activity / super island. */
    data object NotALiveActivity : LiveActivityParseResult

    /** Notification explicitly ended its Super Island (`cancel: true`). */
    data object Cancelled : LiveActivityParseResult

    /** Parsed live activity ready for in-memory Standby Dynamic Island rendering. */
    data class Active(val info: LiveActivityInfo, val focusIconKey: String? = null) : LiveActivityParseResult
}

/**
 * Pure, dependency-free parser for Xiaomi HyperOS 2/3/4 Focus Notifications / Super Island
 * (`miui.focus.param` JSON) and Android 16 Live Updates.
 *
 * Always bounds extracted strings and handles malformed or partial payloads safely so no
 * third-party notification can ever throw or allocate unbounded memory.
 */
internal object XiaomiFocusParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private const val MAX_TITLE = 80
    private const val MAX_SUBTITLE = 140
    private const val MAX_COMPACT = 34
    private const val MAX_METRIC = 20

    fun parseXiaomiFocusJson(
        rawJson: String?,
        key: String,
        appLabel: String,
        packageName: String?,
        fallbackTitle: String?,
        fallbackText: String?,
        fallbackProgress: Float?,
        postTimeMillis: Long,
    ): LiveActivityParseResult {
        if (rawJson.isNullOrBlank()) return LiveActivityParseResult.NotALiveActivity
        val root = runCatching { json.parseToJsonElement(rawJson).jsonObject }.getOrNull()
            ?: return LiveActivityParseResult.NotALiveActivity
        val v2 = root.obj("param_v2") ?: root

        if (v2.bool("cancel") == true) return LiveActivityParseResult.Cancelled

        val business = v2.str("business")?.lowercase()
        val ticker = v2.str("ticker")
        val aodTitle = v2.str("aodTitle")

        val island = v2.obj("param_island")
        val bigIsland = island?.obj("bigIslandArea")
        val smallIsland = island?.obj("smallIslandArea")

        val leftInfo = bigIsland?.obj("imageTextInfoLeft")
        val rightInfo = bigIsland?.obj("imageTextInfoRight")
        val leftText = leftInfo?.obj("textInfo")
        val rightText = rightInfo?.obj("textInfo")
        val digitInfo = bigIsland?.obj("fixedWidthDigitInfo") ?: bigIsland?.obj("sameWidthDigitInfo")
        val bigTextInfo = bigIsland?.obj("textInfo")
        val smallTextInfo = smallIsland?.obj("textInfo") ?: smallIsland?.obj("combinePicInfo")?.obj("textInfo")

        val baseInfo = v2.obj("baseInfo")
        val highlightInfo = v2.obj("highlightInfoV3") ?: v2.obj("highlightInfo")
        val hintInfo = v2.obj("hintInfo")
        val chatInfo = v2.obj("chatInfo")
        val progressInfo = v2.obj("progressInfo") ?: v2.obj("multiProgressInfo")
        val animTextInfo = v2.obj("animTextInfo") ?: v2.obj("coverInfo")

        val title = firstNonBlank(
            baseInfo?.str("title"),
            highlightInfo?.str("title"),
            hintInfo?.str("title"),
            chatInfo?.str("title"),
            animTextInfo?.str("title"),
            leftText?.str("title")?.let { t ->
                val front = leftText.str("frontTitle")
                if (!front.isNullOrBlank() && !t.contains(front)) "$front$t" else t
            },
            bigTextInfo?.str("title"),
            aodTitle,
            ticker,
            fallbackTitle,
            appLabel,
        )?.clean(MAX_TITLE) ?: appLabel.clean(MAX_TITLE)

        val subtitle = firstNonBlank(
            baseInfo?.str("content"),
            baseInfo?.str("subContent"),
            highlightInfo?.str("content"),
            highlightInfo?.str("subContent"),
            hintInfo?.str("content"),
            chatInfo?.str("content"),
            animTextInfo?.str("content"),
            leftText?.str("content"),
            rightText?.str("content"),
            bigTextInfo?.str("content"),
            fallbackText,
            ticker,
        )?.clean(MAX_SUBTITLE)?.takeIf { !it.equals(title, ignoreCase = true) }

        val timerInfo = digitInfo?.obj("timerInfo") ?: v2.obj("timerInfo") ?: baseInfo?.obj("timerInfo")
        val timerMetric = timerInfo?.let { ti ->
            val targetMs = ti.long("timerWhen") ?: 0L
            val nowMs = ti.long("timerSystemCurrent")?.takeIf { it > 0L } ?: System.currentTimeMillis()
            val deltaSec = ((targetMs - nowMs) / 1000L)
            when {
                targetMs <= 0L -> null
                deltaSec >= 3600L -> "${deltaSec / 3600}h ${(deltaSec % 3600) / 60}m"
                deltaSec >= 60L -> "${(deltaSec + 30) / 60} min"
                deltaSec > 0L -> "${deltaSec}s"
                else -> null
            }
        }

        val highlightMetric = firstNonBlank(
            digitInfo?.str("digit"),
            rightText?.str("title")?.let { rt ->
                val front = rightText.str("frontTitle")
                if (!front.isNullOrBlank() && (front.length + rt.length) <= 12) "$front$rt" else rt
            },
            highlightInfo?.str("highLightText"),
            baseInfo?.str("extraTitle"),
            baseInfo?.str("subTitle"),
            smallTextInfo?.str("title"),
            rightText?.str("content"),
            digitInfo?.str("content"),
            timerMetric,
        )?.clean(MAX_METRIC)?.takeIf { !it.equals(title, ignoreCase = true) }

        val compactText = firstNonBlank(
            smallTextInfo?.str("content"),
            ticker,
            leftText?.str("title"),
            aodTitle,
            title,
        )?.clean(MAX_COMPACT) ?: title.clean(MAX_COMPACT)

        val progress = progressInfo?.float("progress")?.let { raw ->
            if (raw > 1f) (raw / 100f).coerceIn(0f, 1f) else raw.coerceIn(0f, 1f)
        } ?: fallbackProgress

        val parsedColor = firstNonBlank(
            progressInfo?.str("colorProgress"),
            highlightInfo?.str("colorTitle"),
            baseInfo?.str("colorTitle"),
            baseInfo?.str("colorContent"),
        )?.let(::parseHexColor)

        val focusIconKey = firstNonBlank(
            leftInfo?.obj("picInfo")?.str("pic"),
            smallIsland?.obj("picInfo")?.str("pic"),
            v2.str("tickerPic"),
            v2.str("aodPic"),
        )

        val info = LiveActivityInfo(
            key = key,
            source = LiveActivitySource.XIAOMI_SUPER_ISLAND,
            appLabel = appLabel.clean(40),
            packageName = packageName,
            title = title,
            subtitle = subtitle,
            compactText = compactText,
            highlightMetric = highlightMetric,
            progress = progress,
            accentColor = parsedColor ?: defaultBusinessAccent(business),
            business = business,
            postTimeMillis = postTimeMillis,
        )
        return LiveActivityParseResult.Active(info, focusIconKey)
    }

    fun parseAndroidLiveUpdate(
        key: String,
        appLabel: String,
        packageName: String?,
        shortCriticalText: String?,
        promotedOngoing: Boolean,
        category: String?,
        isOngoing: Boolean,
        showChronometer: Boolean,
        title: String?,
        text: String?,
        subText: String?,
        progress: Int,
        progressMax: Int,
        progressIndeterminate: Boolean,
        postTimeMillis: Long,
    ): LiveActivityParseResult {
        val cleanShort = shortCriticalText?.clean(MAX_METRIC)?.ifEmpty { null }
        val hasNumericProgress = progressMax > 0 && !progressIndeterminate && progress in 0..progressMax
        val isJourneyCategory = category == "navigation" || category == "stopwatch" ||
            (category == "alarm" && showChronometer)
        val isSystemNoisePkg = packageName == "android" || packageName == "com.android.systemui"

        val qualifies = cleanShort != null ||
            promotedOngoing ||
            (isOngoing && isJourneyCategory) ||
            (isOngoing && hasNumericProgress && !isSystemNoisePkg && category != "service")

        if (!qualifies) return LiveActivityParseResult.NotALiveActivity

        val cleanTitle = firstNonBlank(title, text, appLabel)?.clean(MAX_TITLE) ?: appLabel.clean(MAX_TITLE)
        val cleanSubtitle = firstNonBlank(text, subText)?.clean(MAX_SUBTITLE)
            ?.takeIf { !it.equals(cleanTitle, ignoreCase = true) }
        val progress01 = if (hasNumericProgress) (progress.toFloat() / progressMax.toFloat()).coerceIn(0f, 1f) else null
        val metric = cleanShort ?: progress01?.let { "${(it * 100).toInt()}%" }
        val compact = firstNonBlank(cleanTitle, cleanSubtitle, appLabel)?.clean(MAX_COMPACT) ?: appLabel.clean(MAX_COMPACT)

        val business = when {
            category == "navigation" -> "navigation"
            category == "stopwatch" || (category == "alarm" && showChronometer) -> "timer"
            else -> null
        }

        val info = LiveActivityInfo(
            key = key,
            source = LiveActivitySource.ANDROID_LIVE_UPDATE,
            appLabel = appLabel.clean(40),
            packageName = packageName,
            title = cleanTitle,
            subtitle = cleanSubtitle,
            compactText = compact,
            highlightMetric = metric,
            progress = progress01,
            accentColor = defaultBusinessAccent(business),
            business = business,
            postTimeMillis = postTimeMillis,
        )
        return LiveActivityParseResult.Active(info)
    }

    internal fun defaultBusinessAccent(business: String?): Int = when (business?.lowercase()) {
        "delivery", "food", "takeout", "meituan" -> 0xFFFFB000.toInt() // Warm delivery amber
        "taxi", "ride", "car", "didi" -> 0xFFFF7A2F.toInt()            // Electric ride orange
        "flight", "train", "travel", "trip" -> 0xFF38BDF8.toInt()      // Crisp boarding sky blue
        "navigation", "map", "nav" -> 0xFF34D399.toInt()               // Luminous emerald nav
        "timer", "countdown", "stopwatch" -> 0xFFFF5E3A.toInt()        // Apple StandBy timer orange-red
        "express", "parcel", "package" -> 0xFF60A5FA.toInt()           // Parcel blue
        "media", "music" -> 0xFFFF375F.toInt()                         // Apple Music rose
        else -> 0xFF60A5FA.toInt()
    }

    internal fun parseHexColor(raw: String): Int? {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("#")) return null
        val hex = trimmed.removePrefix("#")
        if (hex.length != 6 && hex.length != 8) return null
        val parsed = hex.toLongOrNull(16) ?: return null
        return if (hex.length == 6) (0xFF000000L or parsed).toInt() else parsed.toInt()
    }

    private fun JsonObject.obj(key: String): JsonObject? =
        runCatching { this[key]?.jsonObject }.getOrNull()

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.ifEmpty { null }

    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.float(key: String): Float? =
        (this[key] as? JsonPrimitive)?.let { it.floatOrNull ?: it.intOrNull?.toFloat() }

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.longOrNull

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }

    private fun String.clean(maxLen: Int): String =
        replace(Regex("[\r\n\t]+"), " ").trim().take(maxLen)
}
