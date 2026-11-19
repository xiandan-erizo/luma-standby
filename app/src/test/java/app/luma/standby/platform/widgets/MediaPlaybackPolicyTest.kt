package app.luma.standby.platform.widgets

import app.luma.standby.domain.widget.MediaInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaPlaybackPolicyTest {
    @Test fun notPlayingNeverReadsMetadata() {
        assertNull(MediaPlaybackPolicy.playing(false,
            { error("Title read for non-playing session") },
            { error("Artist read for non-playing session") },
        ))
    }

    @Test fun actualPlayingMetadataProducesTruthfulInfo() {
        assertEquals(MediaInfo("Song", "Artist", true),
            MediaPlaybackPolicy.playing(true, { " Song " }, { " Artist " }))
    }

    @Test fun missingTitleDoesNotGuessFromAnAppName() {
        assertNull(MediaPlaybackPolicy.playing(true, { null }, { error("Unneeded artist read") }))
        assertNull(MediaPlaybackPolicy.playing(true, { " " }, { error("Unneeded artist read") }))
    }

    @Test fun absentArtistIsOptionalAndMetadataIsBounded() {
        val info = MediaPlaybackPolicy.playing(true, { "x".repeat(200) }, { " " })
        assertEquals(160, info?.title?.length)
        assertNull(info?.artist)
    }
}
