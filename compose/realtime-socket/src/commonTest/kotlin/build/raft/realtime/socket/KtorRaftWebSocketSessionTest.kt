package build.raft.realtime.socket

import io.ktor.utils.io.InternalAPI
import io.ktor.websocket.CloseReason
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketExtension
import io.ktor.websocket.readReason
import io.ktor.websocket.readText
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class, InternalAPI::class)
class KtorRaftWebSocketSessionTest {
    @Test
    fun closeFlushesNormalCloseFrameBeforeCancellingSessionNeverSending1011() = runTest {
        val fake = FakeOkHttpLikeWebSocketSession(parentContext = coroutineContext)
        val wrapped = KtorRaftWebSocketSession(fake)

        wrapped.send("""{"deck":"stop"}""")
        wrapped.close(RaftWebSocketSession.NORMAL_CLOSURE, "done")

        assertEquals(listOf("""{"deck":"stop"}"""), fake.sentTextFrames)
        val wireClose = assertNotNull(fake.wireCloseReason)
        assertEquals(1000.toShort(), wireClose.code)
        assertEquals("done", wireClose.message)
        assertTrue(fake.outgoingClosed)
        assertTrue(fake.sessionJob.isCancelled)
        assertTrue(wrapped.incoming.receiveCatching().isClosed)
    }

    @Test
    fun closeImmediatelyBeforeIncomingJobDispatchesStillClosesIncomingCleanly() = runTest {
        val fake = FakeOkHttpLikeWebSocketSession(parentContext = coroutineContext)
        val wrapped = KtorRaftWebSocketSession(fake)

        // Close immediately without running incomingJob first: incoming channel must still be closed.
        wrapped.close(RaftWebSocketSession.NORMAL_CLOSURE, "user_cancel")

        val wireClose = assertNotNull(fake.wireCloseReason)
        assertEquals(1000.toShort(), wireClose.code)
        assertEquals("user_cancel", wireClose.message)
        assertTrue(wrapped.incoming.receiveCatching().isClosed)
    }

    @Test
    fun closeWaitsForWriterEvenWhenPeerCloseReasonAlreadyCompleted() = runTest {
        val fake = FakeOkHttpLikeWebSocketSession(parentContext = coroutineContext)
        val wrapped = KtorRaftWebSocketSession(fake)

        // Simulate OkHttp onClosing completing closeReason and queueing Frame.Close before writeJob runs.
        fake.simulateServerClosingFirst(CloseReason(CloseReason.Codes.NORMAL, "server_done"))
        wrapped.close(RaftWebSocketSession.NORMAL_CLOSURE, "done")

        val wireClose = assertNotNull(fake.wireCloseReason)
        assertEquals(1000.toShort(), wireClose.code)
        assertEquals("server_done", wireClose.message)
    }

    @Test
    fun closeIsIdempotentUnderConcurrentCallersAndPreservesFirstReason() = runTest {
        val writerGate = CompletableDeferred<Unit>()
        val fake =
            FakeOkHttpLikeWebSocketSession(
                parentContext = coroutineContext,
                beforeProcessFrameGate = writerGate,
            )
        val wrapped = KtorRaftWebSocketSession(fake)

        val firstClose = async { wrapped.close(RaftWebSocketSession.NORMAL_CLOSURE, "user_cancel") }
        val secondClose = async { wrapped.close(RaftWebSocketSession.NORMAL_CLOSURE, "owner_changed") }
        runCurrent()
        assertFalse(firstClose.isCompleted)
        assertFalse(secondClose.isCompleted)

        writerGate.complete(Unit)
        firstClose.await()
        secondClose.await()

        val wireClose = assertNotNull(fake.wireCloseReason)
        assertEquals(1000.toShort(), wireClose.code)
        assertEquals("user_cancel", wireClose.message)
        assertEquals(1, fake.wireCloseCount)
    }

    @Test
    fun closeBoundsHungWriterWithTimeoutAndStillCleansUpSession() = runTest {
        val neverCompletes = CompletableDeferred<Unit>()
        val fake =
            FakeOkHttpLikeWebSocketSession(
                parentContext = coroutineContext,
                beforeProcessFrameGate = neverCompletes,
            )
        val wrapped = KtorRaftWebSocketSession(fake, closeFlushTimeoutMillis = 500L)

        val closeJob = launch { wrapped.close(RaftWebSocketSession.NORMAL_CLOSURE, "drain_timeout") }
        runCurrent()
        assertFalse(closeJob.isCompleted)

        advanceTimeBy(499L)
        runCurrent()
        assertFalse(closeJob.isCompleted)

        advanceTimeBy(1L)
        runCurrent()
        assertTrue(closeJob.isCompleted)
        assertTrue(fake.outgoingClosed)
        assertTrue(fake.sessionJob.isCancelled)
        assertTrue(wrapped.incoming.receiveCatching().isClosed)
    }

    @Test
    fun closeSanitizesReservedCodesAndTruncatesUtf8ReasonTo123Bytes() = runTest {
        assertEquals(1000.toShort(), sanitizeWebSocketCloseCode(1000))
        assertEquals(1001.toShort(), sanitizeWebSocketCloseCode(1001))
        assertEquals(4003.toShort(), sanitizeWebSocketCloseCode(4003))
        assertEquals(1000.toShort(), sanitizeWebSocketCloseCode(1005))
        assertEquals(1000.toShort(), sanitizeWebSocketCloseCode(1006))
        assertEquals(1000.toShort(), sanitizeWebSocketCloseCode(1015))
        assertEquals(1000.toShort(), sanitizeWebSocketCloseCode(999))
        assertEquals(1000.toShort(), sanitizeWebSocketCloseCode(5000))

        val longAscii = "a".repeat(200)
        val truncatedAscii = truncateWebSocketCloseReasonUtf8(longAscii)
        assertEquals(123, truncatedAscii.encodeToByteArray().size)
        assertEquals("a".repeat(123), truncatedAscii)

        // Each CJK char is 3 UTF-8 bytes -> 41 chars = 123 bytes; 42nd char must not be split.
        val cjk = "你".repeat(50)
        val truncatedCjk = truncateWebSocketCloseReasonUtf8(cjk)
        assertEquals(41, truncatedCjk.length)
        assertEquals(123, truncatedCjk.encodeToByteArray().size)

        // Each emoji is 4 UTF-8 bytes (UTF-16 surrogate pair) -> 30 emoji = 120 bytes (never split surrogate).
        val emoji = "\uD83D\uDE80".repeat(40)
        val truncatedEmoji = truncateWebSocketCloseReasonUtf8(emoji)
        assertEquals(60, truncatedEmoji.length)
        assertEquals(120, truncatedEmoji.encodeToByteArray().size)

        val fake = FakeOkHttpLikeWebSocketSession(parentContext = coroutineContext)
        val wrapped = KtorRaftWebSocketSession(fake)
        wrapped.close(code = 1006, reason = longAscii)
        val wireClose = assertNotNull(fake.wireCloseReason)
        assertEquals(1000.toShort(), wireClose.code)
        assertEquals(123, wireClose.message.encodeToByteArray().size)
    }

    @Test
    fun incomingForwardsTextAndBinaryAndPropagatesTransportFailure() = runTest {
        val fake = FakeOkHttpLikeWebSocketSession(parentContext = coroutineContext)
        val wrapped = KtorRaftWebSocketSession(fake)

        fake.pushIncoming(Frame.Text("hello"))
        fake.pushIncoming(Frame.Binary(true, byteArrayOf(1, 2, 3)))
        fake.failIncoming(IllegalStateException("network drop"))
        runCurrent()

        val first = assertIs<RaftWebSocketFrame.Text>(wrapped.incoming.receive())
        assertEquals("hello", first.text)
        val second = assertIs<RaftWebSocketFrame.Binary>(wrapped.incoming.receive())
        assertContentEquals(byteArrayOf(1, 2, 3), second.bytes)
        val error = assertFailsWith<IllegalStateException> { wrapped.incoming.receive() }
        assertEquals("network drop", error.message)

        wrapped.close(RaftWebSocketSession.NORMAL_CLOSURE, "error")
    }

    /**
     * Faithful KMP double of Ktor's `OkHttpWebsocketSession`:
     * - `flush()` is a no-op (just like OkHttp and Darwin engines).
     * - `writeJob` is launched as a child of `coroutineContext` and initializes `closeReason` to
     *   `CloseReason(INTERNAL_ERROR, "Client failure")` (`1011`). If cancelled before dequeuing
     *   `Frame.Close` from `_outgoing`, its `finally` block sends `1011 Client failure`.
     */
    private class FakeOkHttpLikeWebSocketSession(
        parentContext: CoroutineContext,
        private val beforeProcessFrameGate: CompletableDeferred<Unit>? = null,
    ) : DefaultWebSocketSession {
        val sessionJob: Job = Job(parentContext[Job])
        override val coroutineContext: CoroutineContext = parentContext + sessionJob

        private val _incoming = Channel<Frame>(Channel.UNLIMITED)
        private val _outgoing = Channel<Frame>(Channel.UNLIMITED)
        private val _closeReason = CompletableDeferred<CloseReason>()

        override var masking: Boolean = true
        override var maxFrameSize: Long = Long.MAX_VALUE
        override var pingIntervalMillis: Long = 0L
        override var timeoutMillis: Long = 0L
        override val incoming: ReceiveChannel<Frame> = _incoming
        override val outgoing: SendChannel<Frame> = _outgoing
        override val extensions: List<WebSocketExtension<*>> = emptyList()
        override val closeReason: Deferred<CloseReason> = _closeReason

        val sentTextFrames = mutableListOf<String>()
        var wireCloseReason: CloseReason? = null
            private set
        var wireCloseCount: Int = 0
            private set
        var outgoingClosed: Boolean = false
            private set

        private val writeJob: Job =
            launch {
                var closeReasonToSend = CloseReason(CloseReason.Codes.INTERNAL_ERROR, "Client failure")
                try {
                    for (frame in _outgoing) {
                        beforeProcessFrameGate?.await()
                        when (frame) {
                            is Frame.Text -> sentTextFrames += frame.readText()
                            is Frame.Binary -> Unit
                            is Frame.Close -> {
                                val parsed = frame.readReason()
                                if (parsed != null && !isReservedCode(parsed.code)) {
                                    closeReasonToSend = parsed
                                }
                                return@launch
                            }
                            else -> Unit
                        }
                    }
                } finally {
                    wireCloseCount += 1
                    wireCloseReason = closeReasonToSend
                    _closeReason.complete(closeReasonToSend)
                }
            }

        init {
            _outgoing.invokeOnClose { outgoingClosed = true }
        }

        override suspend fun flush() {
            // Intentional no-op matching Ktor's OkHttpWebsocketSession & DarwinWebsocketSession.
        }

        override fun start(negotiatedExtensions: List<WebSocketExtension<*>>) = Unit

        @Suppress("OVERRIDE_DEPRECATION")
        override fun terminate() {
            coroutineContext.cancel()
        }

        fun pushIncoming(frame: Frame) {
            _incoming.trySend(frame)
        }

        fun failIncoming(cause: Throwable) {
            _incoming.close(cause)
        }

        fun simulateServerClosingFirst(reason: CloseReason) {
            _closeReason.complete(reason)
            _outgoing.trySend(Frame.Close(reason))
            _incoming.close()
        }

        private fun isReservedCode(code: Short): Boolean =
            code in 1004..1006 || code in 1015..2999
    }
}
