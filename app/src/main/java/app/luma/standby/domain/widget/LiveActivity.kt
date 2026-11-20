package app.luma.standby.domain.widget

/** Origin of a live activity surfaced inside Standby's Dynamic Island. */
enum class LiveActivitySource {
    XIAOMI_SUPER_ISLAND,
    ANDROID_LIVE_UPDATE,
    MEDIA_PLAYBACK,
}

/**
 * Process-memory-only snapshot of an active real-time journey or playback session
 * (Xiaomi HyperOS Focus Notification / Super Island, Android 16 Live Update, or
 * active MediaSession). Never persisted to disk and erased immediately on lock/pause.
 */
data class LiveActivityInfo(
    val key: String,
    val source: LiveActivitySource,
    val appLabel: String,
    val packageName: String? = null,
    val title: String,
    val subtitle: String? = null,
    val compactText: String,
    val highlightMetric: String? = null,
    val progress: Float? = null,
    val accentColor: Int? = null,
    val business: String? = null,
    val postTimeMillis: Long = 0L,
    val isPlayingMedia: Boolean = false,
)
