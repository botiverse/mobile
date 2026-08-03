package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThreadInlineImageFallbackTest {
    @Test
    fun repeatedThumbnailFailuresStartOneFallbackRequest() {
        val initial = ThreadInlineImageFallbackState()

        val (started, firstShouldRequest) = initial.beginRequest("attachment-1")
        val (unchanged, secondShouldRequest) = started.beginRequest("attachment-1")

        assertTrue(firstShouldRequest)
        assertFalse(secondShouldRequest)
        assertEquals(started, unchanged)
    }

    @Test
    fun successfulFallbackResolutionPublishesTrimmedUrl() {
        val (started, shouldRequest) =
            ThreadInlineImageFallbackState().beginRequest("attachment-1")

        val resolved = started.resolve("attachment-1", "  https://files.example/image.png  ")

        assertTrue(shouldRequest)
        assertEquals("https://files.example/image.png", resolved.resolvedUrls["attachment-1"])
        assertFalse(resolved.beginRequest("attachment-1").second)
    }

    @Test
    fun blankAttachmentIdentityNeverStartsNetworkWork() {
        val (state, shouldRequest) = ThreadInlineImageFallbackState().beginRequest("  ")

        assertFalse(shouldRequest)
        assertEquals(ThreadInlineImageFallbackState(), state)
    }

    @Test
    fun missingThumbnailRequestsAuthenticatedInlineFallback() {
        assertTrue(
            shouldRequestThreadInlineImageFallbackForMissingThumbnail(
                thumbnailUrl = null,
                fallbackUrl = null
            )
        )
        assertTrue(
            shouldRequestThreadInlineImageFallbackForMissingThumbnail(
                thumbnailUrl = "   ",
                fallbackUrl = ""
            )
        )
    }

    @Test
    fun availableThumbnailOrResolvedFallbackSkipsMissingThumbnailRequest() {
        assertFalse(
            shouldRequestThreadInlineImageFallbackForMissingThumbnail(
                thumbnailUrl = "https://cdn.example/thumb.png",
                fallbackUrl = null
            )
        )
        assertFalse(
            shouldRequestThreadInlineImageFallbackForMissingThumbnail(
                thumbnailUrl = null,
                fallbackUrl = "https://api.example/inline"
            )
        )
    }
}
