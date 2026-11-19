package app.luma.standby.platform.widgets

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import app.luma.standby.domain.widget.MediaInfo

/** Read-only public MediaController API. No app-name guesses, transport control or disk cache. */
internal class MediaSessionWidgetSource(private val context: Context) {
    fun current(enabled: Boolean): MediaInfo? = SensitiveWidgetReadPolicy.read(
        enabled = enabled,
        isUnlocked = { SensitiveWidgetAccess.isUnlocked(context) },
        hasAccess = { LumaNotificationListener.isEnabled(context) },
    ) {
        val manager = context.getSystemService(MediaSessionManager::class.java)
            ?: return@read null
        // The exact enabled listener ComponentName authorizes this public API.
        for (controller in manager.getActiveSessions(LumaNotificationListener.componentName(context))) {
            if (controller.playbackState?.state != PlaybackState.STATE_PLAYING) continue
            val metadata = controller.metadata ?: continue
            val media = MediaPlaybackPolicy.playing(
                isPlaying = true,
                title = {
                    metadata.getText(MediaMetadata.METADATA_KEY_TITLE)
                        ?: metadata.getText(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
                },
                artist = { metadata.getText(MediaMetadata.METADATA_KEY_ARTIST) },
            ) ?: continue
            // Do not publish a paused session if it changed while reading its metadata.
            if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) return@read media
        }
        null
    }
}
