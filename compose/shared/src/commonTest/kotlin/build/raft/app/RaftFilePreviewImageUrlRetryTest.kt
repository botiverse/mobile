package build.raft.app

import build.raft.app.data.AuthenticatedRouteOwner
import build.raft.app.data.ThreadApiResult
import build.raft.app.data.ThreadAttachmentOpenTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlinx.coroutines.test.runTest

class RaftFilePreviewImageUrlRetryTest {
    private val transport = ThreadApiResult.Failure(code = 0, errorBodyText = "timeout")
    private val ok = ThreadApiResult.Success(ThreadAttachmentOpenTarget(url = "https://signed.example/full.jpg", expiresAt = null))

    private class FakeNetwork(private val answers: List<ThreadApiResult<ThreadAttachmentOpenTarget>>) {
        var calls = 0
        val waits = mutableListOf<Long>()

        suspend fun request(): ThreadApiResult<ThreadAttachmentOpenTarget> = answers[minOf(calls++, answers.lastIndex)]

        suspend fun fetch() = fetchAttachmentUrlWithRetry(wait = { waits += it }) { request() }
    }

    @Test
    fun onlyRequestsWithoutAnHttpStatusAreRetryable() {
        assertTrue(raftFilePreviewUrlFailureIsRetryable(ThreadApiResult.Failure(code = 0)))
        assertTrue(raftFilePreviewUrlFailureIsRetryable(ThreadApiResult.Failure(code = 0, errorBodyText = "host_unreachable_backoff")))
        assertFalse(raftFilePreviewUrlFailureIsRetryable(ThreadApiResult.Failure(code = 0, errorBodyText = "network_request_lifecycle_retired")))
        for (code in listOf(400, 401, 403, 404, 408, 410, 429)) {
            assertFalse(raftFilePreviewUrlFailureIsRetryable(ThreadApiResult.Failure(code = code)), "4xx $code")
        }
        assertFalse(raftFilePreviewUrlFailureIsRetryable(ThreadApiResult.Failure(code = 500)))
    }

    @Test
    fun transportFailureRetriesTwiceWithBackoffThenGivesUp() = runTest {
        val network = FakeNetwork(listOf(transport))
        val result = network.fetch()
        assertEquals(transport, result)
        assertEquals(3, network.calls)
        assertEquals(listOf(500L, 1_500L), network.waits)
    }

    @Test
    fun successAfterOneTransportFailureStopsRetrying() = runTest {
        val network = FakeNetwork(listOf(transport, ok))
        assertEquals(ok, network.fetch())
        assertEquals(2, network.calls)
        assertEquals(listOf(500L), network.waits)
    }

    @Test
    fun clientErrorIsNeverRetried() = runTest {
        val forbidden = ThreadApiResult.Failure(code = 403)
        val network = FakeNetwork(listOf(forbidden, ok))
        assertEquals(forbidden, network.fetch())
        assertEquals(1, network.calls)
        assertTrue(network.waits.isEmpty())
    }

    @Test
    fun successOnFirstTryNeverWaits() = runTest {
        val network = FakeNetwork(listOf(ok))
        assertEquals(ok, network.fetch())
        assertEquals(1, network.calls)
        assertTrue(network.waits.isEmpty())
    }

    private val loadingWithThumbnail =
        RaftFilePreviewPayload(
            kind = "loading",
            requestedKind = "image",
            source = RaftFilePreviewSourceThreadAttachment,
            attachmentId = "att-1",
            imageUrl = "https://thumb.example/t.jpg"
        )

    @Test
    fun failedUrlKeepsThumbnailAndFlagsFullLoadFailure() {
        val failed = loadingWithThumbnail.withImageUrlResult(transport)
        assertEquals("image", failed.kind)
        assertEquals("https://thumb.example/t.jpg", failed.imageUrl)
        assertTrue(failed.imageFullLoadFailed)
        assertNull(failed.error)
        assertNull(failed.messageDesc)
        assertFalse(failed.raftFilePreviewImageMayAnimate())
    }

    @Test
    fun failedUrlFallsBackToTheGalleryThumbnail() {
        val gallery =
            RaftFilePreviewPayload(
                kind = "loading",
                requestedKind = "image",
                source = RaftFilePreviewSourceThreadAttachment,
                attachmentId = "att-1",
                imageGallery = listOf(RaftFilePreviewGalleryImage(attachmentId = "att-1", thumbnailUrl = "https://thumb.example/g.jpg"))
            )
        val failed = gallery.withImageUrlResult(transport)
        assertEquals("https://thumb.example/g.jpg", failed.imageUrl)
        assertTrue(failed.imageFullLoadFailed)
    }

    @Test
    fun failedUrlWithoutThumbnailIsTheUnavailableState() {
        val failed = loadingWithThumbnail.copy(imageUrl = null).withImageUrlResult(transport)
        assertNull(failed.imageUrl)
        assertFalse(failed.imageFullLoadFailed)
        assertEquals("image_preview_unavailable", failed.error)
    }

    @Test
    fun resolvedUrlReplacesThumbnailAndReportsExpiry() {
        var expiresAt: String? = "unset"
        val resolved =
            loadingWithThumbnail.withImageUrlResult(
                ThreadApiResult.Success(ThreadAttachmentOpenTarget("https://signed.example/full.jpg", "2026-10-23T12:00:00Z"))
            ) { expiresAt = it }
        assertEquals("https://signed.example/full.jpg", resolved.imageUrl)
        assertFalse(resolved.imageFullLoadFailed)
        assertEquals("2026-10-23T12:00:00Z", expiresAt)
    }

    @Test
    fun retryReturnsToLoadingStillShowingTheThumbnail() {
        val retried = loadingWithThumbnail.withImageUrlResult(transport).withImageFullLoadRetry()
        assertEquals("loading", retried.kind)
        assertEquals("image", retried.requestedKind)
        assertFalse(retried.imageFullLoadFailed)
        assertEquals("https://thumb.example/t.jpg", retried.currentImageThumbnailUrl())
        assertTrue(retried.raftFilePreviewShowsImage())
    }

    @Test
    fun cachedSignedUrlLivesUntilExpiresAtMinusMargin() {
        val owner = AuthenticatedRouteOwner(environmentId = "env", accountId = "acct", serverId = "srv")
        val key = RaftFilePreviewAttachmentUrlCacheKey(owner, "att-1", "inline")
        val clock = TestTimeSource()
        val cache = RaftFilePreviewAttachmentUrlCache(timeSource = clock)
        val wallNow = 10_000_000L
        cache.currentEpochMillis = { wallNow }

        assertNull(cache.get(key))
        // Expires in 5 min; the 60 s safety margin leaves 4 min.
        cache.put(key, "https://signed.example/full.jpg", expiresAtEpochMillis = wallNow + 5.minutes.inWholeMilliseconds)
        assertEquals("https://signed.example/full.jpg", cache.get(key))
        clock += 3.minutes
        assertEquals("https://signed.example/full.jpg", cache.get(key))
        clock += 59.seconds
        assertEquals("https://signed.example/full.jpg", cache.get(key))
        clock += 1.seconds
        assertNull(cache.get(key))
    }
}
