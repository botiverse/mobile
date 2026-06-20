package ai.slock.compose.data

import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.MessageId
import ai.slock.compose.model.ServerId
import ai.slock.compose.model.ThreadId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThreadCandidateProofGateTest {
    @Test
    fun acceptsReplyCandidateWithMatchingParentPair() {
        val proof =
            ThreadCandidateProofGate.prove(
                route =
                    ThreadCandidateIdentity(
                        threadId = ThreadId("thread-1"),
                        streamChannelId = ChannelId("thread-1"),
                        parentChannelId = ChannelId("channel-1"),
                        parentMessageId = MessageId("message-1"),
                        replyThread = true
                    ),
                candidate =
                    ThreadCandidateIdentity(
                        threadId = ThreadId("candidate-thread"),
                        streamChannelId = ChannelId("thread-1"),
                        parentChannelId = ChannelId("channel-1"),
                        parentMessageId = MessageId("message-1"),
                        replyThread = true
                    )
            )

        assertTrue(proof.accepted)
        assertEquals("parent_pair", proof.reason)
    }

    @Test
    fun rejectsReplyCandidateWithWrongParentEvenWhenBadStreamMatches() {
        val proof =
            ThreadCandidateProofGate.prove(
                route =
                    ThreadCandidateIdentity(
                        threadId = ThreadId("wrong-thread"),
                        streamChannelId = ChannelId("wrong-thread"),
                        parentChannelId = ChannelId("channel-1"),
                        parentMessageId = MessageId("message-1"),
                        replyThread = true
                    ),
                candidate =
                    ThreadCandidateIdentity(
                        threadId = ThreadId("wrong-thread"),
                        streamChannelId = ChannelId("wrong-thread"),
                        parentChannelId = ChannelId("other-channel"),
                        parentMessageId = MessageId("other-message"),
                        replyThread = true
                    )
            )

        assertFalse(proof.accepted)
        assertEquals("reply_identity_mismatch", proof.reason)
    }

    @Test
    fun acceptsChannelCandidateWithMatchingThreadIdentity() {
        val proof =
            ThreadCandidateProofGate.prove(
                route = ThreadCandidateIdentity(threadId = ThreadId("channel-1")),
                candidate = ThreadCandidateIdentity(threadId = ThreadId("channel-1"))
            )

        assertTrue(proof.accepted)
        assertEquals("thread_id", proof.reason)
    }

    @Test
    fun rejectsCandidateFromDifferentServer() {
        val proof =
            ThreadCandidateProofGate.prove(
                route =
                    ThreadCandidateIdentity(
                        serverId = ServerId("server-1"),
                        threadId = ThreadId("channel-1")
                    ),
                candidate =
                    ThreadCandidateIdentity(
                        serverId = ServerId("server-2"),
                        threadId = ThreadId("channel-1")
                    )
            )

        assertFalse(proof.accepted)
        assertEquals("server_mismatch", proof.reason)
    }
}
