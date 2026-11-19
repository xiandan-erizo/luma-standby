package app.luma.standby.platform.widgets

import app.luma.standby.domain.widget.MediaInfo

/** Metadata is accessed lazily, only after an actual PLAYING state was observed. */
internal object MediaPlaybackPolicy {
    fun playing(
        isPlaying: Boolean,
        title: () -> CharSequence?,
        artist: () -> CharSequence?,
    ): MediaInfo? {
        if (!isPlaying) return null
        val name = title()?.toString()?.trim()?.take(160).orEmpty()
        if (name.isEmpty()) return null
        val performer = artist()?.toString()?.trim()?.take(160)?.takeIf { it.isNotEmpty() }
        return MediaInfo(title = name, artist = performer, playing = true)
    }
}
