package build.raft.app

import build.raft.app.thread.RaftThreadAttachmentItem
import build.raft.app.thread.RaftThreadMessageItem
import build.raft.app.thread.RaftThreadPendingSendContext
import build.raft.app.thread.ThreadMessageDeliveryFailed
import build.raft.app.thread.ThreadMessageDeliverySending
import build.raft.app.thread.ThreadTransientLocalSendIdPrefix
import build.raft.app.thread.absorbingCanonicalSendEchoes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ThreadSendEchoAbsorptionTest {

    private fun pendingShadow(
        id: String = "${ThreadTransientLocalSendIdPrefix}1",
        body: String = "hello",
        asTask: Boolean = false,
        attachmentIds: List<String> = emptyList(),
        deliveryState: String = ThreadMessageDeliverySending,
        authorId: String = "user-1",
        randomId: String? = "rid-1"
    ): RaftThreadMessageItem =
        RaftThreadMessageItem(
            id = id,
            authorId = authorId,
            authorName = "Me",
            isOwn = true,
            body = body,
            randomId = randomId,
            deliveryState = deliveryState,
            pendingSendContext =
                RaftThreadPendingSendContext(
                    channelId = "channel-1",
                    body = body,
                    asTask = asTask,
                    attachmentIds = attachmentIds,
                    randomId = randomId
                )
        )

    private fun canonicalEcho(
        id: String = "canonical-1",
        body: String = "hello",
        taskId: String? = null,
        attachmentIds: List<String> = emptyList(),
        isOwn: Boolean = true,
        authorId: String = "user-1",
        randomId: String? = "rid-1"
    ): RaftThreadMessageItem =
        RaftThreadMessageItem(
            id = id,
            authorId = authorId,
            authorName = "Me",
            isOwn = isOwn,
            body = body,
            randomId = randomId,
            taskId = taskId,
            attachments =
                attachmentIds.map { attachmentId ->
                    RaftThreadAttachmentItem(id = attachmentId, name = "file-$attachmentId")
                }
        )

    @Test
    fun matchingRandomIdCanonicalEchoAbsorbsPendingShadowExactlyOnce() {
        val shadow = pendingShadow()
        val echo = canonicalEcho(body = "server canonical body")
        val other = canonicalEcho(id = "canonical-2", body = "unrelated")

        val absorbed = listOf(other, shadow).absorbingCanonicalSendEchoes(listOf(echo))

        assertEquals(listOf(other), absorbed)
        // Second application is a no-op: the shadow is gone and nothing else matches.
        val again = absorbed.absorbingCanonicalSendEchoes(listOf(echo))
        assertSame(absorbed, again)
    }

    @Test
    fun canonicalWithoutRandomIdFallsBackToLegacyHeuristic() {
        val shadow = pendingShadow(body = "offline send")
        val pushEcho = canonicalEcho(body = "offline send", randomId = null)

        val absorbed = listOf(shadow).absorbingCanonicalSendEchoes(listOf(pushEcho))

        assertTrue(absorbed.isEmpty())
    }

    @Test
    fun randomIdMismatchDoesNotAbsorbEvenWhenContentMatches() {
        val shadow = pendingShadow(body = "same", randomId = "rid-local")
        val echo = canonicalEcho(body = "same", randomId = "rid-other")

        val absorbed = listOf(shadow).absorbingCanonicalSendEchoes(listOf(echo))

        assertEquals(listOf(shadow), absorbed)
    }

    @Test
    fun failedShadowWithExactRandomIdIsConsumedByCanonicalEcho() {
        // A definite local failure is retryable only until canonical proof for
        // the same idempotency key arrives.  The canonical row then wins and
        // the stale failed shadow must not remain beside it.
        val failed = pendingShadow(deliveryState = ThreadMessageDeliveryFailed)

        val absorbed = listOf(failed).absorbingCanonicalSendEchoes(listOf(canonicalEcho()))

        assertEquals(emptyList(), absorbed)
    }

    @Test
    fun failedShadowWithoutRandomIdIsRetainedForRetry() {
        val failed = pendingShadow(deliveryState = ThreadMessageDeliveryFailed)
        val legacyEcho = canonicalEcho(randomId = null)

        assertEquals(
            listOf(failed),
            listOf(failed).absorbingCanonicalSendEchoes(listOf(legacyEcho))
        )
    }

    @Test
    fun canonicalRowsAreNeverAbsorbed() {
        // Only transient local shadows may be removed; canonical rows with the
        // same body are untouched (dedup by id happens at the merge layer).
        val canonicalA = canonicalEcho(id = "canonical-a")
        val canonicalB = canonicalEcho(id = "canonical-b")

        val absorbed = listOf(canonicalA).absorbingCanonicalSendEchoes(listOf(canonicalB))

        assertEquals(listOf(canonicalA), absorbed)
    }
}
