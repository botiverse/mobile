package build.raft.app

import build.raft.app.resources.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * iOS plays the containers AVPlayer cannot demux (WebM, MKV, MPEG-TS/PS, FLV) through VLC, the
 * rest through AVPlayer. When VLC cannot open a file, the file page falls back to the download
 * card with its "can't play on iPhone" note. Android (Media3) and the desktop are unchanged.
 */
class RaftVideoPlaybackEngineRoutingTest {

    private val ios = RaftVideoPlayerFormatProfile.IosAvPlayer
    private val vlcContainers =
        listOf(
            RaftPreviewableVideoContainer.WebM,
            RaftPreviewableVideoContainer.Matroska,
            RaftPreviewableVideoContainer.MpegTs,
            RaftPreviewableVideoContainer.MpegPs,
            RaftPreviewableVideoContainer.Flv
        )

    private fun videoPayload(name: String, mimeType: String, url: String = "https://files.example/signed?sig=1") =
        RaftThreadAttachmentItem(id = "att-video", name = name, mimeType = mimeType)
            .toThreadAttachmentLazyPreviewPayload(kind = "video")
            .copy(kind = "video", videoUrl = url)

    @Test
    fun androidAndDesktopNeverUseVlc() {
        RaftPreviewableVideoContainer.entries.forEach { container ->
            assertEquals(
                RaftVideoPlaybackEngine.Platform,
                raftVideoPlaybackEngineFor(RaftVideoPlayerFormatProfile.AndroidMedia3, vlcAvailable = false, container),
                container.name
            )
        }
        vlcContainers.forEach { container ->
            assertNull(raftVideoPlaybackEngineFor(RaftVideoPlayerFormatProfile.DesktopMacAvFoundation, vlcAvailable = false, container))
            assertNull(raftVideoPlaybackEngineFor(profile = null, vlcAvailable = false, container = container))
        }
    }

    @Test
    fun attachmentsRouteByNameAndMimeType() {
        listOf(
            Triple("screen-recording.webm", "video/webm", RaftVideoPlaybackEngine.Vlc),
            Triple("talk.mkv", "", RaftVideoPlaybackEngine.Vlc),
            Triple("broadcast.m2ts", "video/mp2t", RaftVideoPlaybackEngine.Vlc),
            Triple("old.flv", "video/x-flv", RaftVideoPlaybackEngine.Vlc),
            Triple("camera.mov", "video/quicktime", RaftVideoPlaybackEngine.Platform),
            Triple("clip.mp4", "video/mp4", RaftVideoPlaybackEngine.Platform)
        ).forEach { (name, mime, engine) ->
            assertEquals(engine, raftFilePreviewVideoPlaybackEngine(name, mime, profile = ios, vlcAvailable = true), name)
        }
        // TypeScript tagged video/mp2t is source, not video (task #511): no player at all.
        assertNull(raftFilePreviewVideoPlaybackEngine("index.ts", "video/mp2t", profile = ios, vlcAvailable = true))
        assertNull(raftFilePreviewVideoPlaybackEngine("notes.pdf", "application/pdf", profile = ios, vlcAvailable = true))

        assertEquals(RaftVideoPlaybackEngine.Vlc, videoPayload("clip.webm", "video/webm").videoPlaybackEngine(ios, vlcAvailable = true))
        // Audio-only previews stay on AVPlayer.
        assertEquals(
            RaftVideoPlaybackEngine.Platform,
            videoPayload("voice.webm", "audio/webm").copy(audioOnly = true).videoPlaybackEngine(ios, vlcAvailable = true)
        )
    }

    @Test
    fun aWebmVlcCannotOpenFallsBackToTheDownloadCardWithTheIphoneNote() {
        val url = "https://files.example/signed?sig=1"
        val webm = videoPayload("screen-recording.webm", "video/webm", url)
        val vlc = RaftVideoPlaybackEngine.Vlc

        assertEquals("video", raftFilePreviewContentKind(webm, vlc, vlcOpenFailedSource = null))
        assertEquals("file", raftFilePreviewContentKind(webm, vlc, vlcOpenFailedSource = url))
        // A fresh link (Retry, refreshed preview) tries the player again.
        assertEquals("video", raftFilePreviewContentKind(webm.copy(videoUrl = "$url&r=2"), vlc, vlcOpenFailedSource = url))
        // Only the VLC engine falls back; an MP4 on AVPlayer keeps its player.
        assertEquals("video", raftFilePreviewContentKind(videoPayload("clip.mp4", "video/mp4", url), RaftVideoPlaybackEngine.Platform, url))

        // The download card it falls back to is #4882's: the iPhone note and Open in another app.
        assertEquals(
            Res.string.file_preview_video_format_unplayable_ios.desc(),
            raftFilePreviewUnsupportedFileMessage(webm, downloadable = true, videoProfile = ios)
        )
        assertTrue(
            raftFilePreviewFallbackOffersOpenInOtherApp(
                hasOpenInOtherApp = true,
                hasPrimaryDownloadAction = true,
                downloadState = RaftFilePreviewDownloadUiState.NotDownloaded,
                hasSavedFileActions = false
            )
        )
    }
}
