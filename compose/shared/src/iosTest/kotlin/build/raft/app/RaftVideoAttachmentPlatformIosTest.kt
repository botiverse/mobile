package build.raft.app

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RaftVideoAttachmentPlatformIosTest {
    private val avPlayerFiles =
        listOf(
            "clip.mp4" to "video/mp4",
            "camera.mov" to "video/quicktime",
            "clip.m4v" to "video/x-m4v",
            "clip.3gp" to "video/3gpp",
            "clip.3g2" to "video/3gpp2"
        )
    private val vlcFiles =
        listOf(
            "clip.webm" to "video/webm",
            "clip.mkv" to "video/x-matroska",
            "clip.m2ts" to "video/mp2t",
            "clip.mpg" to "video/mpeg",
            "clip.flv" to "video/x-flv"
        )

    @AfterTest
    fun removeVlcHost() {
        RaftIosVlcVideoHost.factory = null
    }

    @Test
    fun avPlayerFileVideoFamiliesArePreviewable() {
        avPlayerFiles.forEach { (fileName, mimeType) ->
            assertTrue(raftFilePreviewIsPreviewableVideoAttachment(fileName, mimeType), fileName)
        }
        // No VLC player registered: AVPlayer alone, so these keep the download card.
        assertFalse(raftPlatformVlcVideoEngineAvailable())
        vlcFiles.forEach { (fileName, mimeType) ->
            assertFalse(raftFilePreviewIsPreviewableVideoAttachment(fileName, mimeType), fileName)
        }
    }

    @Test
    fun withTheAppsVlcPlayerTheOtherContainersPlayThroughVlc() {
        RaftIosVlcVideoHost.factory = UnusedVlcPlayerFactory

        assertTrue(raftPlatformVlcVideoEngineAvailable())
        assertEquals(RaftVideoPlayerFormatProfile.IosAvPlayer, raftPlatformVideoPlayerFormatProfile())
        vlcFiles.forEach { (fileName, mimeType) ->
            assertTrue(raftFilePreviewIsPreviewableVideoAttachment(fileName, mimeType), fileName)
            assertEquals(RaftVideoPlaybackEngine.Vlc, raftFilePreviewVideoPlaybackEngine(fileName, mimeType), fileName)
        }
        avPlayerFiles.forEach { (fileName, mimeType) ->
            assertEquals(RaftVideoPlaybackEngine.Platform, raftFilePreviewVideoPlaybackEngine(fileName, mimeType), fileName)
        }
        // TypeScript tagged video/mp2t is still source text.
        assertFalse(raftFilePreviewIsPreviewableVideoAttachment("index.ts", "video/mp2t"))
    }

    @Test
    fun iosForwardBufferCapTightensInlineCardsRelativeToFilePreview() {
        val inlineVideo = raftIosVideoPreferredForwardBufferSeconds(RaftMediaPlayerRole.InlineVideo, audioOnly = false)
        val inlineAudio = raftIosVideoPreferredForwardBufferSeconds(RaftMediaPlayerRole.InlineAudio, audioOnly = true)
        val filePreview = raftIosVideoPreferredForwardBufferSeconds(RaftMediaPlayerRole.FilePreview, audioOnly = false)
        assertEquals(RAFT_IOS_INLINE_FORWARD_BUFFER_SECONDS, inlineVideo)
        assertEquals(RAFT_IOS_INLINE_FORWARD_BUFFER_SECONDS, inlineAudio)
        assertEquals(RAFT_IOS_PREVIEW_FORWARD_BUFFER_SECONDS, filePreview)
        assertTrue(inlineVideo < filePreview)
    }

    private object UnusedVlcPlayerFactory : RaftIosVlcVideoPlayerFactory {
        override fun createPlayer(url: String, labels: RaftIosVlcVideoLabels): RaftIosVlcVideoPlayer =
            error("routing only: no player is created")
    }
}
