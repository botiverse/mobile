package build.raft.app

import build.raft.app.data.ThreadApiResult
import build.raft.app.data.ThreadAttachmentOpenTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull

class ThreadInlineVideoPreviewRetryTest {
    private val transient = ThreadInlineVideoFetchOutcome.Failed(404, ThreadInlineVideoFailureKind.Transient)
    private val ready = ThreadInlineVideoFetchOutcome.Ready("https://cdn.example/video.mp4", "2026-10-20T10:00:00Z")

    @Test
    fun defaultPolicyBacksOffExponentiallyWithinAboutThirtySeconds() {
        val policy = ThreadInlineVideoRetryPolicy.Default
        assertEquals(0L, policy.delayBeforeAttempt(1))
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L), (2..5).map { policy.delayBeforeAttempt(it) })
        assertNull(policy.delayBeforeAttempt(6))
        assertEquals(30_000L, (2..5).sumOf { policy.delayBeforeAttempt(it)!! })
    }

    @Test
    fun delaysAreCappedAtMaxDelay() {
        val policy = ThreadInlineVideoRetryPolicy(maxAttempts = 8, initialDelayMs = 1_000L, maxDelayMs = 3_000L)
        assertEquals(listOf(1_000L, 2_000L, 3_000L, 3_000L), (2..5).map { policy.delayBeforeAttempt(it) })
    }

    @Test
    fun classifiesTransientAndHardFailures() {
        listOf(0, -1, 200, 404, 408, 409, 423, 425, 429, 500, 502, 503).forEach {
            assertEquals(ThreadInlineVideoFailureKind.Transient, threadInlineVideoFailureKind(it), "code $it")
        }
        listOf(400, 401, 403, 410, 415).forEach {
            assertEquals(ThreadInlineVideoFailureKind.Hard, threadInlineVideoFailureKind(it), "code $it")
        }
    }

    @Test
    fun blankUrlSuccessIsTransient() {
        val outcome = ThreadApiResult.Success(ThreadAttachmentOpenTarget(url = "  ", expiresAt = null)).toInlineVideoFetchOutcome()
        assertEquals(ThreadInlineVideoFailureKind.Transient, (outcome as ThreadInlineVideoFetchOutcome.Failed).kind)
    }

    @Test
    fun showsProcessingWhileRetryingAndBecomesReadyWithoutUserAction() = runTest {
        val results = ArrayDeque(listOf(transient, transient, ready))
        val states = mutableListOf<ThreadInlineVideoPreviewUiState>()
        val fetchTimes = mutableListOf<Long>()

        val outcome =
            runThreadInlineVideoPreviewFetch(
                policy = ThreadInlineVideoRetryPolicy.Default,
                fetch = {
                    fetchTimes += currentTime
                    results.removeFirst()
                },
                awaitRetry = { delay(it) },
                onState = { states += it }
            )

        assertEquals(ready, outcome)
        assertEquals(listOf(0L, 2_000L, 6_000L), fetchTimes)
        assertEquals(
            listOf(
                ThreadInlineVideoPreviewStatus.Loading,
                ThreadInlineVideoPreviewStatus.Processing,
                ThreadInlineVideoPreviewStatus.Processing,
                ThreadInlineVideoPreviewStatus.Ready
            ),
            states.map { it.status }
        )
        assertFalse(states.any { it.renderMode() == ThreadInlineVideoRenderMode.Error })
        assertEquals("https://cdn.example/video.mp4", states.last().url)
    }

    @Test
    fun showsErrorOnlyAfterRetriesAreExhausted() = runTest {
        var fetches = 0
        val states = mutableListOf<ThreadInlineVideoPreviewUiState>()

        runThreadInlineVideoPreviewFetch(
            policy = ThreadInlineVideoRetryPolicy.Default,
            fetch = {
                fetches += 1
                transient
            },
            awaitRetry = { delay(it) },
            onState = { states += it }
        )

        assertEquals(5, fetches)
        assertEquals(30_000L, currentTime)
        assertEquals(ThreadInlineVideoPreviewStatus.Error, states.last().status)
        assertEquals(1, states.count { it.status == ThreadInlineVideoPreviewStatus.Error })
    }

    @Test
    fun hardFailureShowsErrorImmediately() = runTest {
        var fetches = 0
        val states = mutableListOf<ThreadInlineVideoPreviewUiState>()

        val outcome =
            runThreadInlineVideoPreviewFetch(
                policy = ThreadInlineVideoRetryPolicy.Default,
                fetch = {
                    fetches += 1
                    ThreadInlineVideoFetchOutcome.Failed(403, ThreadInlineVideoFailureKind.Hard)
                },
                awaitRetry = { delay(it) },
                onState = { states += it }
            )

        assertEquals(1, fetches)
        assertEquals(0L, currentTime)
        assertEquals(403, (outcome as ThreadInlineVideoFetchOutcome.Failed).code)
        assertEquals(
            listOf(ThreadInlineVideoPreviewStatus.Loading, ThreadInlineVideoPreviewStatus.Error),
            states.map { it.status }
        )
    }

    @Test
    fun realtimeNudgeRunsTheWaitingRetryEarly() = runTest {
        val nudges = MutableSharedFlow<String?>(extraBufferCapacity = 4)
        val results = ArrayDeque(listOf(transient, ready))
        val fetchTimes = mutableListOf<Long>()

        val run =
            async {
                runThreadInlineVideoPreviewFetch(
                    policy = ThreadInlineVideoRetryPolicy.Default,
                    fetch = {
                        fetchTimes += currentTime
                        results.removeFirst()
                    },
                    awaitRetry = { delayMs -> withTimeoutOrNull(delayMs) { nudges.first { it == null || it == "a1" } } },
                    onState = {}
                )
            }
        runCurrent()
        advanceTimeBy(500L)
        nudges.emit("other")
        runCurrent()
        assertEquals(1, fetchTimes.size)
        nudges.emit(null)
        runCurrent()

        assertEquals(ready, run.await())
        assertEquals(listOf(0L, 500L), fetchTimes)
    }

    @Test
    fun processingStateBlocksDuplicateRequestsAndRetriesOnAttachmentUpdate() {
        val processing = ThreadInlineVideoPreviewUiState(status = ThreadInlineVideoPreviewStatus.Processing, attempt = 2)
        assertFalse(mapOf("a1" to processing).canRequestInlineVideoPreview("a1"))
        assertEquals(ThreadInlineVideoRenderMode.Processing, processing.renderMode())
        assertTrue(processing.shouldRetryOnAttachmentUpdate())
        assertTrue(ThreadInlineVideoPreviewUiState(status = ThreadInlineVideoPreviewStatus.Error).shouldRetryOnAttachmentUpdate())
        assertFalse(
            ThreadInlineVideoPreviewUiState(status = ThreadInlineVideoPreviewStatus.Ready, url = "u").shouldRetryOnAttachmentUpdate()
        )
        assertFalse(null.shouldRetryOnAttachmentUpdate())
    }
}
