package app.luma.standby.platform.widgets

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import app.luma.standby.domain.widget.LiveActivityInfo
import app.luma.standby.domain.widget.LiveActivitySource
import app.luma.standby.domain.widget.MediaInfo
import kotlin.math.max

/** Read-only public MediaController API. No transport control or disk cache. */
internal class MediaSessionWidgetSource(private val context: Context) {

    data class LiveMusicSnapshot(
        val activity: LiveActivityInfo,
        val albumArt: Bitmap?,
    )

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

    /**
     * Reads the active playing music session as a Dynamic Island Live Activity
     * (in-memory only, including safely bounded album art <= 192 px).
     */
    fun currentLiveActivity(enabled: Boolean): LiveMusicSnapshot? = SensitiveWidgetReadPolicy.read(
        enabled = enabled,
        isUnlocked = { SensitiveWidgetAccess.isUnlocked(context) },
        hasAccess = { LumaNotificationListener.isEnabled(context) },
    ) {
        val manager = context.getSystemService(MediaSessionManager::class.java)
            ?: return@read null
        for (controller in manager.getActiveSessions(LumaNotificationListener.componentName(context))) {
            val state = controller.playbackState ?: continue
            if (state.state != PlaybackState.STATE_PLAYING) continue
            val metadata = controller.metadata ?: continue
            val media = MediaPlaybackPolicy.playing(
                isPlaying = true,
                title = {
                    metadata.getText(MediaMetadata.METADATA_KEY_TITLE)
                        ?: metadata.getText(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
                },
                artist = { metadata.getText(MediaMetadata.METADATA_KEY_ARTIST) },
            ) ?: continue
            if (controller.playbackState?.state != PlaybackState.STATE_PLAYING) continue

            val pkg = controller.packageName
            val appLabel = runCatching {
                val ai = context.packageManager.getApplicationInfo(pkg, 0)
                context.packageManager.getApplicationLabel(ai).toString()
            }.getOrDefault("Music")

            val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
            val position = state.position
            val progress = if (duration > 0L && position in 0L..duration) {
                (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
            } else null

            val rawArt = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
            val boundedArt = rawArt?.let(::downsampleArt)
            val accent = boundedArt?.let(::extractVibrantAccent)
                ?: XiaomiFocusParser.defaultBusinessAccent("media")

            val compact = if (!media.artist.isNullOrBlank()) {
                "${media.title} · ${media.artist}".take(34)
            } else {
                media.title.take(34)
            }

            return@read LiveMusicSnapshot(
                activity = LiveActivityInfo(
                    key = "media:$pkg",
                    source = LiveActivitySource.MEDIA_PLAYBACK,
                    appLabel = appLabel.take(40),
                    packageName = pkg,
                    title = media.title,
                    subtitle = media.artist,
                    compactText = compact,
                    highlightMetric = null,
                    progress = progress,
                    accentColor = accent,
                    business = "media",
                    postTimeMillis = System.currentTimeMillis(),
                    isPlayingMedia = true,
                ),
                albumArt = boundedArt,
            )
        }
        null
    }

    /**
     * Event-driven observer for track changes & play/pause state transitions while Standby
     * is visible. Returns an unregister callback that detaches everything immediately on pause.
     */
    fun observe(enabled: Boolean, onChanged: () -> Unit): () -> Unit {
        if (!enabled || !SensitiveWidgetAccess.isUnlocked(context) || !LumaNotificationListener.isEnabled(context)) {
            return {}
        }
        val manager = context.getSystemService(MediaSessionManager::class.java) ?: return {}
        val handler = Handler(Looper.getMainLooper())
        val component = LumaNotificationListener.componentName(context)
        val registered = mutableListOf<Pair<MediaController, MediaController.Callback>>()

        fun detachControllers() {
            for ((controller, cb) in registered) {
                runCatching { controller.unregisterCallback(cb) }
            }
            registered.clear()
        }

        fun attachControllers(controllers: List<MediaController>?) {
            detachControllers()
            controllers.orEmpty().take(3).forEach { controller ->
                val cb = object : MediaController.Callback() {
                    override fun onMetadataChanged(metadata: MediaMetadata?) = onChanged()
                    override fun onPlaybackStateChanged(state: PlaybackState?) = onChanged()
                    override fun onSessionDestroyed() = onChanged()
                }
                runCatching {
                    controller.registerCallback(cb, handler)
                    registered.add(controller to cb)
                }
            }
        }

        val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            attachControllers(controllers)
            onChanged()
        }

        val active = runCatching {
            val current = manager.getActiveSessions(component)
            attachControllers(current)
            manager.addOnActiveSessionsChangedListener(sessionListener, component, handler)
            true
        }.getOrDefault(false)

        if (!active) {
            detachControllers()
            return {}
        }

        return {
            runCatching { manager.removeOnActiveSessionsChangedListener(sessionListener) }
            detachControllers()
        }
    }

    private fun downsampleArt(src: Bitmap): Bitmap? {
        if (src.isRecycled || src.width <= 0 || src.height <= 0) return null
        val maxSide = 192
        val scale = maxSide.toFloat() / max(src.width, src.height).toFloat()
        return if (scale >= 1f) {
            runCatching { src.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
        } else {
            val w = (src.width * scale).toInt().coerceAtLeast(1)
            val h = (src.height * scale).toInt().coerceAtLeast(1)
            runCatching { Bitmap.createScaledBitmap(src, w, h, true) }.getOrNull()
        }
    }

    private fun extractVibrantAccent(bitmap: Bitmap): Int {
        var bestScore = 0f
        var bestColor = 0xFFFF375F.toInt()
        val hsv = FloatArray(3)
        val stepX = (bitmap.width / 8).coerceAtLeast(1)
        val stepY = (bitmap.height / 8).coerceAtLeast(1)
        var y = stepY / 2
        while (y < bitmap.height) {
            var x = stepX / 2
            while (x < bitmap.width) {
                val px = bitmap.getPixel(x, y)
                if (Color.alpha(px) > 180) {
                    Color.colorToHSV(px, hsv)
                    val score = hsv[1] * (0.4f + 0.6f * hsv[2])
                    if (hsv[2] > 0.25f && score > bestScore) {
                        bestScore = score
                        hsv[1] = hsv[1].coerceAtLeast(0.62f)
                        hsv[2] = hsv[2].coerceAtLeast(0.78f)
                        bestColor = Color.HSVToColor(hsv)
                    }
                }
                x += stepX
            }
            y += stepY
        }
        return bestColor
    }
}
