package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.network.MultipartFile
import build.raft.app.network.RAFT_UPLOAD_TOTAL_TIMEOUT_MILLIS
import build.raft.app.network.RaftHttpExecutor
import build.raft.app.network.RaftHttpExecutorResponse
import build.raft.app.network.RaftRequestPriority
import build.raft.app.network.RaftRequestScheduler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * task #788 production-chain teeth: the request scheduler's outer 30s
 * wall-clock cancelled streaming fileRef uploads before the transport's
 * authoritative 180s per-attempt budget could run (task #787 device
 * evidence: abort at exactly 30,000ms with `totalTimeoutMs=180000` already
 * logged). Streaming uploads now carry the checked-derived scheduler
 * backstop; everything else keeps 30s. All teeth run the real
 * `uploadAttachments → preparePlatformFileStreams → requestScheduler.run →
 * authMiddleware → executor.executeMultipart` chain on a virtual clock —
 * no large buffers, no real sleeps.
 */
class SharedRaftUploadTimeoutChainTest {

    private companion object {
        const val STREAMING_SIZE_BYTES = 199_229_440L
    }

    private fun sampleSession(): StoredAuthSession =
        StoredAuthSession(
            user = StoredAuthUser(id = "user-1", email = "user@example.com", name = "User"),
            accessToken = "access-token",
            refreshToken = "refresh-token",
            selectedServerId = "server-1",
            servers = listOf(StoredAuthServer(id = "server-1", name = "Slock"))
        )

    private fun streamingFile() =
        MultipartFile(
            name = "files",
            fileName = "large.bin",
            mimeType = "application/octet-stream",
            fileRef = "native://large.bin"
        )

    private fun bytesFile() =
        MultipartFile(
            name = "files",
            fileName = "small.txt",
            mimeType = "text/plain",
            bytes = "hello".encodeToByteArray()
        )

    private class StreamingStatResolver(
        private val sizeBytes: Long = STREAMING_SIZE_BYTES
    ) : RaftPlatformFileResolver {
        override suspend fun readFile(fileRef: String): RaftPlatformFileReadResult =
            RaftPlatformFileReadResult(code = 200, bytes = byteArrayOf(1), sizeBytes = 1)

        override suspend fun statFile(fileRef: String): RaftPlatformFileStatResult =
            RaftPlatformFileStatResult(code = 200, sizeBytes = sizeBytes)

        override suspend fun openFileStream(
            fileRef: String,
            expectedSizeBytes: Long
        ): RaftPlatformFileChunkSource =
            object : RaftPlatformFileChunkSource {
                override suspend fun read(maxBytes: Int): ByteArray = ByteArray(0)

                override fun close() = Unit
            }
    }

    private class ScriptedExecutor(
        private val onMultipart: suspend (attempt: Int) -> RaftHttpExecutorResponse,
        private val onExecute: suspend (url: String) -> RaftHttpExecutorResponse = { url ->
            error("unexpected non-multipart request in this tooth: $url")
        }
    ) : RaftHttpExecutor {
        var multipartAttempts = 0
            private set
        var multipartCancelled = false
            private set

        override suspend fun execute(
            priority: RaftRequestPriority,
            url: String,
            method: String,
            headers: Map<String, String>,
            bodyText: String?,
            jsonBody: Boolean
        ): RaftHttpExecutorResponse = onExecute(url)

        override suspend fun executeMultipart(
            priority: RaftRequestPriority,
            url: String,
            fields: Map<String, String>,
            files: List<MultipartFile>,
            headers: Map<String, String>,
            onUploadProgress: ((build.raft.app.network.RaftTransferProgress) -> Unit)?
        ): RaftHttpExecutorResponse {
            multipartAttempts += 1
            try {
                return onMultipart(multipartAttempts)
            } catch (cancellation: CancellationException) {
                multipartCancelled = true
                throw cancellation
            }
        }
    }

    private fun client(
        executor: RaftHttpExecutor,
        sessionStore: RaftAuthSessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
        logs: MutableList<String> = mutableListOf()
    ): SharedRaftNetworkClient =
        SharedRaftNetworkClient(
            sessionStore = sessionStore,
            baseUrl = "https://api.slock.test/api",
            warningLogger = logs::add,
            requestScheduler = RaftRequestScheduler(),
            platformFileResolver = StreamingStatResolver(),
            executor = executor
        )

    private suspend fun SharedRaftNetworkClient.uploadStreaming(): HomeNetworkResponse =
        uploadAttachments(
            priority = DataRequestPriority.Foreground,
            channelId = ChannelId("c1"),
            files = listOf(streamingFile())
        )

    private fun ok(): RaftHttpExecutorResponse =
        RaftHttpExecutorResponse(code = 200, bodyText = """{"attachments":[]}""", errorBodyText = null)

    private fun unauthorized(): RaftHttpExecutorResponse =
        RaftHttpExecutorResponse(code = 401, bodyText = null, errorBodyText = """{"error":"unauthorized"}""")

    @Test
    fun derivedBackstopSitsStrictlyAboveTheFullLegalChain() {
        assertEquals(
            RAFT_AUTH_MAX_REQUEST_ATTEMPTS * RAFT_UPLOAD_TOTAL_TIMEOUT_MILLIS +
                AUTH_REFRESH_MAX_ATTEMPTS * AUTH_REFRESH_ATTEMPT_TIMEOUT_MILLIS +
                RAFT_UPLOAD_SCHEDULER_DISPATCH_MARGIN_MILLIS,
            RAFT_UPLOAD_SCHEDULER_BACKSTOP_MILLIS,
            "backstop must be checked-derived from the authoritative chain constants"
        )
        assertEquals(383_000L, RAFT_UPLOAD_SCHEDULER_BACKSTOP_MILLIS)
        assertTrue(RAFT_UPLOAD_SCHEDULER_BACKSTOP_MILLIS > RAFT_UPLOAD_TOTAL_TIMEOUT_MILLIS)
        assertTrue(
            RAFT_UPLOAD_SCHEDULER_BACKSTOP_MILLIS >
                RAFT_AUTH_MAX_REQUEST_ATTEMPTS * RAFT_UPLOAD_TOTAL_TIMEOUT_MILLIS +
                AUTH_REFRESH_MAX_ATTEMPTS * AUTH_REFRESH_ATTEMPT_TIMEOUT_MILLIS,
            "the scheduler bound must never race a legal terminal"
        )
    }

    @Test
    fun streamingUploadRunningTwoMinutesSucceeds() = runTest {
        val executor =
            ScriptedExecutor(onMultipart = {
                delay(120_000L)
                ok()
            })

        val response = client(executor).uploadStreaming()

        assertEquals(200, response.code, "a 120s streaming upload must outlive the old 30s scheduler bound")
        assertEquals(1, executor.multipartAttempts)
        assertFalse(executor.multipartCancelled)
        assertTrue(testScheduler.currentTime >= 120_000L)
    }

    /**
     * The 401→refresh→second-attempt chain runs the auth refresh on its real
     * coordinator dispatcher, so this tooth uses short real delays instead of
     * the virtual clock (a virtual 4s refresh timeout would race the real
     * thread nondeterministically). The near-budget duration property of the
     * same chain is covered by [outerBoundAdmitsFullLegalChainDuration] at
     * the scheduler layer, where the whole flow stays on the virtual clock.
     */
    @Test
    fun refreshRetryChainCompletesWithoutOuterTruncation() = runBlocking {
        val executor =
            ScriptedExecutor(
                onMultipart = { attempt ->
                    if (attempt == 1) {
                        unauthorized()
                    } else {
                        delay(100L)
                        ok()
                    }
                },
                onExecute = { url ->
                    assertTrue(url.contains("auth/refresh"), "only the refresh call may use execute: $url")
                    RaftHttpExecutorResponse(
                        code = 200,
                        bodyText =
                            """{"accessToken":"access-token-2","refreshToken":"refresh-token-2"}""",
                        errorBodyText = null
                    )
                }
            )

        val response = client(executor).uploadStreaming()

        assertEquals(200, response.code, "the post-refresh second attempt must not be truncated")
        assertEquals(RAFT_AUTH_MAX_REQUEST_ATTEMPTS, executor.multipartAttempts)
    }

    @Test
    fun outerBoundAdmitsFullLegalChainDuration() = runTest {
        val executor =
            ScriptedExecutor(onMultipart = {
                // The scheduler cannot distinguish attempts inside the
                // middleware block; its contract is only the derived bound.
                // A single virtual block running the worst legal chain
                // duration (2×180s + 2×4s = 368s) must complete untouched.
                delay(368_000L)
                ok()
            })

        val response = client(executor).uploadStreaming()

        assertEquals(200, response.code, "the outer bound must admit the full legal chain duration")
        assertTrue(testScheduler.currentTime >= 368_000L)
        assertTrue(testScheduler.currentTime < RAFT_UPLOAD_SCHEDULER_BACKSTOP_MILLIS)
    }

    @Test
    fun transportTimeoutFailureMapsStablyThroughTheChain() = runTest {
        val executor =
            ScriptedExecutor(onMultipart = {
                delay(RAFT_UPLOAD_TOTAL_TIMEOUT_MILLIS)
                RaftHttpExecutorResponse(
                    code = 0,
                    bodyText = null,
                    errorBodyText =
                        "[timeout] SocketTimeoutException: Request wall-clock deadline exceeded " +
                            "[configuredTimeoutMs=180000, deadlineElapsedMs=180002]"
                )
            })
        val logs = mutableListOf<String>()

        val response = client(executor, logs = logs).uploadStreaming()

        assertEquals(0, response.code)
        assertTrue(
            response.errorBodyText.orEmpty().contains("[timeout]"),
            "the inner transport terminal must own the timeout attribution"
        )
        assertTrue(
            testScheduler.currentTime < RAFT_UPLOAD_SCHEDULER_BACKSTOP_MILLIS,
            "the inner 180s terminal must land before the scheduler backstop"
        )
        val endpointLog = logs.single { it.startsWith("endpoint category=attachments_upload") }
        assertTrue(endpointLog.contains("result=network_error"))
    }

    @Test
    fun backstopReclaimsPermitOnlyWhenInnerMachineryIsAbsent() = runTest {
        var cancelled = false
        val executor =
            ScriptedExecutor(onMultipart = {
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            })
        val logs = mutableListOf<String>()

        val response = client(executor, logs = logs).uploadStreaming()

        assertTrue(cancelled, "the backstop must cancel the hung attempt and reclaim the permit")
        assertEquals(0, response.code)
        assertTrue(
            response.errorBodyText.orEmpty().contains("network_timeout"),
            "backstop timeout maps to the standard timeout response, got: ${response.errorBodyText}"
        )
        assertEquals(
            RAFT_UPLOAD_SCHEDULER_BACKSTOP_MILLIS,
            testScheduler.currentTime,
            "the backstop must fire exactly at the derived bound"
        )
        assertTrue(
            logs.single { it.startsWith("endpoint category=attachments_upload") }
                .contains("result=network_error")
        )
    }

    @Test
    fun bytesMultipartKeepsOrdinaryThirtySecondBound() = runTest {
        val executor =
            ScriptedExecutor(onMultipart = {
                awaitCancellation()
            })

        val response =
            client(executor).uploadAttachments(
                priority = DataRequestPriority.Foreground,
                channelId = ChannelId("c1"),
                files = listOf(bytesFile())
            )

        assertEquals(0, response.code)
        assertTrue(response.errorBodyText.orEmpty().contains("network_timeout"))
        assertEquals(30_000L, testScheduler.currentTime, "bytes multipart must keep the ordinary 30s bound")
    }

    @Test
    fun ordinaryRequestKeepsThirtySecondBound() = runTest {
        val executor =
            ScriptedExecutor(
                onMultipart = { error("no multipart in this tooth") },
                onExecute = {
                    delay(31_000L)
                    ok()
                }
            )

        val response =
            client(executor).request(
                priority = DataRequestPriority.Foreground,
                path = "channels/inbox",
                method = "GET",
                query = emptyList(),
                serverIdOverride = null,
                bodyText = null
            )

        assertEquals(0, response.code)
        assertTrue(response.errorBodyText.orEmpty().contains("network_timeout"))
        assertEquals(30_000L, testScheduler.currentTime)
    }

    @Test
    fun externalCancellationPropagatesWithoutTimeoutConversion() = runTest {
        var transportCancelled = false
        val executor =
            ScriptedExecutor(onMultipart = {
                try {
                    awaitCancellation()
                } finally {
                    transportCancelled = true
                }
            })
        var cancellationPropagated = false
        var convertedToResponse: HomeNetworkResponse? = null

        val job =
            launch {
                try {
                    convertedToResponse = client(executor).uploadStreaming()
                } catch (cancellation: CancellationException) {
                    cancellationPropagated = true
                    throw cancellation
                }
            }
        advanceTimeBy(5_000L)
        runCurrent()
        job.cancel()
        job.join()

        assertTrue(transportCancelled, "explicit cancel must reach the transport attempt immediately")
        assertTrue(cancellationPropagated, "cancel must propagate as CancellationException")
        assertNull(convertedToResponse, "cancel must not be rewritten into a timeout response")
    }

    @Test
    fun authMiddlewareNeverExceedsDeclaredAttemptCeiling() = runBlocking {
        val executor =
            ScriptedExecutor(
                onMultipart = { unauthorized() },
                onExecute = { url ->
                    assertTrue(url.contains("auth/refresh"), "only refresh may use execute: $url")
                    RaftHttpExecutorResponse(
                        code = 200,
                        bodyText =
                            """{"accessToken":"access-token-2","refreshToken":"refresh-token-2"}""",
                        errorBodyText = null
                    )
                }
            )

        val response = client(executor).uploadStreaming()

        assertEquals(
            RAFT_AUTH_MAX_REQUEST_ATTEMPTS,
            executor.multipartAttempts,
            "middleware must invoke the block at most the declared attempt ceiling"
        )
        assertEquals(401, response.code, "a still-unauthorized retry must surface as session expired")
    }
}
