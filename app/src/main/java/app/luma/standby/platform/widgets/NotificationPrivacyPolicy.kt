package app.luma.standby.platform.widgets

import app.luma.standby.domain.widget.NotificationInfo

/** Unknown visibility is never treated as permission to read a title. */
internal enum class NotificationVisibility { PUBLIC, PRIVATE, SECRET, UNKNOWN }
internal enum class NotificationTitleMode { OMIT, APP_LABEL, PUBLIC_TITLE }

/** Pure policy. Call this BEFORE reading Notification.extras, not after caching them. */
internal object NotificationPrivacyPolicy {
    fun visibility(
        declared: NotificationVisibility,
        visibilityOverride: NotificationVisibility?,
    ): NotificationVisibility = when {
        declared == NotificationVisibility.SECRET || visibilityOverride == NotificationVisibility.SECRET ->
            NotificationVisibility.SECRET
        declared == NotificationVisibility.PRIVATE || visibilityOverride == NotificationVisibility.PRIVATE ->
            NotificationVisibility.PRIVATE
        declared == NotificationVisibility.UNKNOWN || visibilityOverride == NotificationVisibility.UNKNOWN ->
            NotificationVisibility.UNKNOWN
        else -> NotificationVisibility.PUBLIC
    }

    fun titleMode(
        visibility: NotificationVisibility,
        ongoing: Boolean = false,
        groupSummary: Boolean = false,
        ownNotification: Boolean = false,
    ): NotificationTitleMode = when {
        ongoing || groupSummary || ownNotification || visibility == NotificationVisibility.SECRET ->
            NotificationTitleMode.OMIT
        visibility != NotificationVisibility.PUBLIC -> NotificationTitleMode.APP_LABEL
        else -> NotificationTitleMode.PUBLIC_TITLE
    }

    fun info(
        mode: NotificationTitleMode,
        appLabel: String,
        packageName: String? = null,
        postTimeMillis: Long = 0L,
        key: String? = null,
        publicTitle: () -> CharSequence?,
    ): NotificationInfo? {
        if (mode == NotificationTitleMode.OMIT) return null
        val label = appLabel.trim().take(MAX_TEXT_LENGTH).ifEmpty { return null }
        val title = if (mode == NotificationTitleMode.PUBLIC_TITLE) {
            publicTitle()?.toString()?.trim()?.take(MAX_TEXT_LENGTH).orEmpty().ifEmpty { label }
        } else label
        return NotificationInfo(
            appLabel = label,
            title = title,
            packageName = packageName,
            postTimeMillis = postTimeMillis,
            key = key,
        )
    }

    private const val MAX_TEXT_LENGTH = 160
}

/** Only sanitized values live here; no Android notifications, Bundles, body text or disk. */
internal class NotificationMemorySnapshot(private val capacity: Int = 64) {
    private data class Entry(
        val postTime: Long,
        val info: NotificationInfo,
        val visibility: NotificationVisibility,
    )

    private val entries = mutableMapOf<String, Entry>()
    private var enabled = false

    init { require(capacity > 0) }

    fun setEnabled(value: Boolean) {
        enabled = value
        if (!value) clear()
    }

    fun clear() = entries.clear()

    fun posted(key: String, postTime: Long, info: NotificationInfo?, visibility: NotificationVisibility) {
        // A replacement can become secret/ongoing, so erase its older public title first.
        entries.remove(key)
        if (!enabled || info == null) return
        entries[key] = Entry(postTime, info, visibility)
        if (entries.size > capacity) {
            entries.minByOrNull { it.value.postTime }?.key?.let(entries::remove)
        }
    }

    fun removed(key: String) { entries.remove(key) }
    fun keys(): List<String> = entries.keys.toList()

    /** Privacy changes may erase a title but never restore one from an older event. */
    fun restrictVisibility(
        key: String,
        visibilityOverride: NotificationVisibility,
        allowUnlockedPreview: Boolean = false,
    ) {
        val entry = entries[key] ?: return
        val visibility = NotificationPrivacyPolicy.visibility(entry.visibility, visibilityOverride)
        val baseMode = NotificationPrivacyPolicy.titleMode(visibility)
        val effectiveMode = if (allowUnlockedPreview && baseMode == NotificationTitleMode.APP_LABEL) {
            NotificationTitleMode.PUBLIC_TITLE
        } else {
            baseMode
        }
        val info = NotificationPrivacyPolicy.info(
            mode = effectiveMode,
            appLabel = entry.info.appLabel,
            packageName = entry.info.packageName,
            postTimeMillis = entry.info.postTimeMillis,
            key = entry.info.key,
        ) { entry.info.title }?.let { rebuilt ->
            if (allowUnlockedPreview && visibility != NotificationVisibility.SECRET) {
                rebuilt.copy(body = entry.info.body)
            } else {
                rebuilt
            }
        }
        posted(key, entry.postTime, info, visibility)
    }

    fun latest(limit: Int): List<NotificationInfo> = if (!enabled) emptyList() else {
        entries.values.sortedByDescending { it.postTime }.take(limit.coerceAtLeast(0)).map { it.info }
    }
}
