package build.raft.app

import build.raft.app.thread.ThreadInlineImageFallbackState
import build.raft.app.thread.shouldRequestThreadInlineImageFallbackForMissingThumbnail
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
}
