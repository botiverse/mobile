package build.raft.app

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
    ): KuiklyThreadMessageItem =
        KuiklyThreadMessageItem(
            id = id,
            authorId = authorId,
            authorName = "Me",
            isOwn = true,
            body = body,
            randomId = randomId,
            deliveryState = deliveryState,
            pendingSendContext =
                KuiklyThreadPendingSendContext(
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
    ): KuiklyThreadMessageItem =
        KuiklyThreadMessageItem(
            id = id,
            authorId = authorId,
            authorName = "Me",
            isOwn = isOwn,
            body = body,
            randomId = randomId,
            taskId = taskId,
            attachments =
                attachmentIds.map { attachmentId ->
                    KuiklyThreadAttachmentItem(id = attachmentId, name = "file-$attachmentId")
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
    fun exactRandomIdPolicyDoesNotUseLegacyContentFallback() {
        val shadow = pendingShadow(body = "offline send")
        val pushEcho = canonicalEcho(body = "offline send", randomId = null)

        val retained =
            listOf(shadow).absorbingCanonicalSendEchoes(
                canonicalMessages = listOf(pushEcho),
                matchPolicy = ThreadSendEchoMatchPolicy.ExactRandomId
            )

        assertEquals(listOf(shadow), retained)
    }

    @Test
    fun randomIdMismatchDoesNotAbsorbEvenWhenContentMatches() {
        val shadow = pendingShadow(body = "same", randomId = "rid-local")
        val echo = canonicalEcho(body = "same", randomId = "rid-other")

        val absorbed = listOf(shadow).absorbingCanonicalSendEchoes(listOf(echo))

        assertEquals(listOf(shadow), absorbed)
    }

    @Test
    fun rpcLostButPushEchoWithRandomIdDeliveredStillAbsorbsShadow() {
        // The send RPC response never arrived, so the shadow is still "sending".
        // The canonical message arrives later via push; absorption must not
        // depend on the RPC ack path.
        val shadow = pendingShadow(body = "offline send")
        val pushEcho = canonicalEcho(body = "offline send")

        val absorbed = listOf(shadow).absorbingCanonicalSendEchoes(listOf(pushEcho))

        assertTrue(absorbed.isEmpty())
    }

    @Test
    fun noMatchingEchoKeepsListReferenceIdentity() {
        val shadow = pendingShadow(body = "hello")
        val messages = listOf(shadow)

        assertSame(messages, messages.absorbingCanonicalSendEchoes(listOf(canonicalEcho(body = "different", randomId = "rid-other"))))
        assertSame(messages, messages.absorbingCanonicalSendEchoes(emptyList()))
    }

    @Test
    fun mismatchedContentIsNotAbsorbed() {
        val shadow = pendingShadow(body = "hello", attachmentIds = listOf("a1"))

        val differentBody = canonicalEcho(body = "hello!", attachmentIds = listOf("a1"), randomId = null)
        val differentAttachments = canonicalEcho(body = "hello", attachmentIds = listOf("a2"), randomId = null)
        val taskEcho = canonicalEcho(body = "hello", attachmentIds = listOf("a1"), taskId = "task-1", randomId = null)
        val otherAuthor = canonicalEcho(body = "hello", attachmentIds = listOf("a1"), authorId = "user-2", randomId = null)
        val notOwn = canonicalEcho(body = "hello", attachmentIds = listOf("a1"), isOwn = false, randomId = null)

        for (echo in listOf(differentBody, differentAttachments, taskEcho, otherAuthor, notOwn)) {
            assertEquals(listOf(shadow), listOf(shadow).absorbingCanonicalSendEchoes(listOf(echo)))
        }
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
