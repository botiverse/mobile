package build.raft.app

import build.raft.app.data.ThreadApiResult
import build.raft.app.data.ThreadAttachmentOpenTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest

class RaftFilePreviewImageUrlRetryTest {
    private val transport = ThreadApiResult.Failure(code = 0, errorBodyText = "timeout")
    private val ok = ThreadApiResult.Success(ThreadAttachmentOpenTarget(url = "https://signed.example/full.jpg", expiresAt = null))

    /** Answers in order (the last repeats); [wait] advances [clock] instead of sleeping. */
    private class FakeNetwork(private val answers: List<ThreadApiResult<ThreadAttachmentOpenTarget>>) {
        var calls = 0
        val waits = mutableListOf<Long>()
        val clock = TestTimeSource()

        suspend fun request(): ThreadApiResult<ThreadAttachmentOpenTarget> = answers[minOf(calls++, answers.lastIndex)]

        suspend fun fetch() =
            fetchAttachmentUrlWithRetry(timeSource = clock, wait = { waits += it; clock += it.milliseconds }) { request() }
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
    fun instantTransportFailuresRetryWithBackoffUntilTheBudgetThenGiveUp() = runTest {
        val network = FakeNetwork(listOf(transport))
        val result = network.fetch()
        assertEquals(transport, result)
        // Attempts at 0, 0.5, 1.5, 3.5, 7.5, 11.5, 15.5 and 19.5 s; one more would start past 20 s.
        assertEquals(listOf(500L, 1_000L, 2_000L, 4_000L, 4_000L, 4_000L, 4_000L), network.waits)
        assertEquals(8, network.calls)
    }

    /**
     * Hands 43837e84 / Android repro: tapped right after a network drop, the reachability gate answers
     * every request at once with its cached CONNECT failure for its cooldown. The viewer used to give up
     * after 0.5 + 1.5 s; it now keeps the loading state until the host answers (virtual time).
     */
    @Test
    fun imageTappedWhileTheGateCoolsDownOpensOnceTheNetworkIsBack() = runTest {
        val gateBlocked =
            ThreadApiResult.Failure(code = 0, errorBodyText = "host_unreachable_backoff")
        val networkBackAtMillis = 6_000L
        var calls = 0
        val result =
            fetchAttachmentUrlWithRetry(timeSource = testScheduler.timeSource) {
                calls += 1
                if (currentTime < networkBackAtMillis) gateBlocked else ok
            }
        assertEquals(ok, result)
        // Attempts at 0, 0.5, 1.5, 3.5 s fail; the one at 7.5 s succeeds.
        assertEquals(5, calls)
        assertEquals(7_500L, currentTime)
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
}
