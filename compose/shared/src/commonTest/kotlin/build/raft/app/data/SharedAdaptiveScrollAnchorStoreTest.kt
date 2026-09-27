package build.raft.app.data

import build.raft.app.KuiklyThreadSurfaceKind
import build.raft.app.SlockAdaptiveDetailIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SharedAdaptiveScrollAnchorStoreTest {

    @Test
    fun stagedAnchorPeeksWithoutConsuming() {
        val store = SharedAdaptiveScrollAnchorStore()
        store.stage("shell-a", identity, anchor("req-1"))

        assertEquals("req-1", store.peek("shell-a", identity)?.requestId)
        // Peek does not consume.
        assertEquals("req-1", store.peek("shell-a", identity)?.requestId)
    }

    @Test
    fun consumeIsFencedToItsRequest() {
        val store = SharedAdaptiveScrollAnchorStore()
        store.stage("shell-a", identity, anchor("req-1"))

        // A superseded/older request must not consume the newer record.
        assertNull(store.consume("shell-a", identity, "req-0"))
        assertEquals("req-1", store.peek("shell-a", identity)?.requestId)

        assertEquals("req-1", store.consume("shell-a", identity, "req-1")?.requestId)
        assertNull(store.peek("shell-a", identity))
        // Second consume of the same request id is a no-op (idempotent).
        assertNull(store.consume("shell-a", identity, "req-1"))
    }

    @Test
    fun discardOnlyDropsItsOwnRequest() {
        val store = SharedAdaptiveScrollAnchorStore()
        store.stage("shell-a", identity, anchor("req-1"))

        store.discard("shell-a", identity, "req-0")
        assertEquals("req-1", store.peek("shell-a", identity)?.requestId)

        store.discard("shell-a", identity, "req-1")
        assertNull(store.peek("shell-a", identity))
    }

    @Test
    fun anchorsAreScopedByShellAndIdentity() {
        val store = SharedAdaptiveScrollAnchorStore()
        store.stage("shell-a", identity, anchor("req-1"))
        store.stage("shell-b", identity, anchor("req-2"))

        assertEquals("req-1", store.peek("shell-a", identity)?.requestId)
        assertEquals("req-2", store.peek("shell-b", identity)?.requestId)
        assertNull(store.peek("shell-c", identity))
        assertNull(store.peek("shell-a", otherIdentity))
    }

    private val identity =
        SlockAdaptiveDetailIdentity(
            serverId = "server-a",
            threadId = "stream-a",
            surfaceKind = KuiklyThreadSurfaceKind.Channel,
            channelId = "channel-a",
        )

    private val otherIdentity =
        SlockAdaptiveDetailIdentity(
            serverId = "server-a",
            threadId = "stream-b",
            surfaceKind = KuiklyThreadSurfaceKind.Channel,
            channelId = "channel-b",
        )

    private fun anchor(requestId: String) =
        SharedAdaptiveScrollAnchor(
            requestId = requestId,
            messageId = "message-1",
            messageOffsetPx = 12,
            fallbackIndex = 7,
            atLatest = false,
            capturedAtMillis = 1000L,
        )
}
