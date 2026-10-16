package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * task #511 — ".ts" is ambiguous: OS MIME tables (Android MimeTypeMap) auto-tag
 * it video/mp2t, and SlockFilePreview's video-container extension map had "ts"
 * alongside m2ts/mts. A TypeScript attachment therefore rendered as a video
 * player with "视频播放失败". Fix: ".ts" resolves to text/code, not MpegTs.
 */
class SlockFilePreviewTsAttachmentTest {

    @Test
    fun tsExtensionWithGenericMimeIsNotVideo() {
        assertNull(slockFilePreviewVideoContainer(fileName = "cross.storage.test.ts", mimeType = ""))
        assertNull(slockFilePreviewVideoContainer(fileName = "review.ts", mimeType = "application/octet-stream"))
    }

    @Test
    fun tsExtensionWithMp2tMimeIsNotVideo() {
        // Android MimeTypeMap guesses .ts -> video/mp2t on upload; must not win.
        assertNull(slockFilePreviewVideoContainer(fileName = "app.ts", mimeType = "video/mp2t"))
        assertNull(slockFilePreviewVideoContainer(fileName = "cross.storage-r6-review.test.ts", mimeType = "video/mp2t"))
    }

    @Test
    fun tsExtensionWithTextMimeIsNotVideo() {
        assertNull(slockFilePreviewVideoContainer(fileName = "app.ts", mimeType = "text/plain"))
        assertNull(slockFilePreviewVideoContainer(fileName = "app.ts", mimeType = "application/typescript"))
    }

    @Test
    fun tsFileFallsToTextLike() {
        assertTrue(slockFilePreviewIsTextLikeAttachment(fileName = "app.ts", mimeType = ""))
        assertTrue(slockFilePreviewIsTextLikeAttachment(fileName = "app.ts", mimeType = "application/typescript"))
        assertFalse(slockFilePreviewIsPreviewableVideoAttachment(fileName = "app.ts", mimeType = "video/mp2t"))
    }

    @Test
    fun realMpegTsExtensionsStillResolve() {
        // .m2ts / .mts are unambiguous video containers — keep them.
        assertEquals(
            SlockPreviewableVideoContainer.MpegTs,
            slockFilePreviewVideoContainer(fileName = "clip.m2ts", mimeType = "")
        )
        assertEquals(
            SlockPreviewableVideoContainer.MpegTs,
            slockFilePreviewVideoContainer(fileName = "movie.mts", mimeType = "video/mp2t")
        )
    }

    @Test
    fun mp2tMimeWithNoExtensionStillResolvesToVideo() {
        // An extensionless or non-text-extension file with video/mp2t is still MPEG-TS.
        assertEquals(
            SlockPreviewableVideoContainer.MpegTs,
            slockFilePreviewVideoContainer(fileName = "clip", mimeType = "video/mp2t")
        )
        assertEquals(
            SlockPreviewableVideoContainer.MpegTs,
            slockFilePreviewVideoContainer(fileName = "clip.bin", mimeType = "video/mp2t")
        )
    }

    @Test
    fun tsxIsNotVideoEither() {
        assertNull(slockFilePreviewVideoContainer(fileName = "Component.tsx", mimeType = ""))
        assertTrue(slockFilePreviewIsTextLikeAttachment(fileName = "Component.tsx", mimeType = ""))
    }
}
