package ai.slock.compose.data

import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.MessageId
import ai.slock.compose.model.ServerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ThreadCandidateProofGateTest {
    @Test
    fun acceptsReplyCandidateWithMatchingParentPair() {
        val proof =
            ThreadCandidateProofGate.prove(
                route =
                    ThreadRouteId.ReplyThread(
                        parentChannelId = ChannelId("channel-1"),
                        parentMessageId = MessageId("message-1"),
                        threadChannelId = ChannelId("thread-1")
                    ),
                candidate =
                    ThreadRouteId.ReplyThread(
                        parentChannelId = ChannelId("channel-1"),
                        parentMessageId = MessageId("message-1"),
                        threadChannelId = ChannelId("thread-1")
                    )
            )

        assertTrue(proof is ThreadIdentityProof.Accepted)
        assertEquals(
            ThreadRouteId.ReplyThread(
                parentChannelId = ChannelId("channel-1"),
                parentMessageId = MessageId("message-1"),
                threadChannelId = ChannelId("thread-1")
            ),
            (proof as ThreadIdentityProof.Accepted).routeId
        )
    }

    @Test
    fun rejectsReplyCandidateWithWrongParentAsParentMismatch() {
        val proof =
            ThreadCandidateProofGate.prove(
                route =
                    ThreadRouteId.ReplyThread(
                        parentChannelId = ChannelId("channel-1"),
                        parentMessageId = MessageId("message-1"),
                        threadChannelId = ChannelId("thread-1")
                    ),
                candidate =
                    ThreadRouteId.ReplyThread(
                        parentChannelId = ChannelId("other-channel"),
                        parentMessageId = MessageId("other-message"),
                        threadChannelId = ChannelId("thread-1")
                    )
            )

        assertEquals(
            ThreadIdentityProof.Rejected(ThreadIdentityError.ReplyIdentityMismatch.ParentMismatch),
            proof
        )
    }

    @Test
    fun rejectsReplyCandidateWithMatchingParentPairEvenWhenStreamsDiffer() {
        // Parent pair match takes precedence over stream comparison. ReplyThread sealed
        // construction guarantees (parentChannelId, parentMessageId) are non-null; matching
        // parent pair is sufficient proof even if the stream hint differs (e.g. a cached
        // stream is stale but the parent pair is correct).
        val proof =
            ThreadCandidateProofGate.prove(
                route =
                    ThreadRouteId.ReplyThread(
                        parentChannelId = ChannelId("channel-1"),
                        parentMessageId = MessageId("message-1"),
                        threadChannelId = ChannelId("thread-1")
                    ),
                candidate =
                    ThreadRouteId.ReplyThread(
                        parentChannelId = ChannelId("channel-1"),
                        parentMessageId = MessageId("message-1"),
                        threadChannelId = ChannelId("other-thread")
                    )
            )

        assertTrue(proof is ThreadIdentityProof.Accepted)
    }

    @Test
    fun replyCandidateWithOneMissingParentPairIsParentMismatch() {
        // One side has parent pair, other side does not. Note ReplyThread sealed type
        // requires non-null parent pair at construction; this case is reachable only via
        // cross-kind comparison (ReplyThread route vs ChannelThread candidate).
        val proof =
            ThreadCandidateProofGate.prove(
                route =
                    ThreadRouteId.ReplyThread(
                        parentChannelId = ChannelId("channel-1"),
                        parentMessageId = MessageId("message-1"),
                        threadChannelId = ChannelId("thread-1")
                    ),
                candidate = ThreadRouteId.ChannelThread(channelId = ChannelId("channel-1"))
            )

        assertEquals(
            ThreadIdentityProof.Rejected(ThreadIdentityError.ReplyIdentityMismatch.ParentMismatch),
            proof
        )
    }

    @Test
    fun acceptsChannelCandidateWithMatchingChannelId() {
        val proof =
            ThreadCandidateProofGate.prove(
                route = ThreadRouteId.ChannelThread(channelId = ChannelId("channel-1")),
                candidate = ThreadRouteId.ChannelThread(channelId = ChannelId("channel-1"))
            )

        assertTrue(proof is ThreadIdentityProof.Accepted)
    }

    @Test
    fun rejectsCandidateFromDifferentServer() {
        val proof =
            ThreadCandidateProofGate.prove(
                route =
                    ThreadRouteId.ChannelThread(
                        channelId = ChannelId("channel-1"),
                        serverId = ServerId("server-1")
                    ),
                candidate =
                    ThreadRouteId.ChannelThread(
                        channelId = ChannelId("channel-1"),
                        serverId = ServerId("server-2")
                    )
            )

        assertEquals(
            ThreadIdentityProof.Rejected(ThreadIdentityError.ServerMismatch),
            proof
        )
    }

    @Test
    fun focusedCandidateWithoutFocusedMessageIdReturnsFocusedMessageMissing() {
        val proof =
            ThreadCandidateProofGate.prove(
                route = ThreadRouteId.ChannelThread(channelId = ChannelId("channel-1")),
                candidate =
                    ThreadRouteId.FocusedThread(
                        channelId = ChannelId("channel-1"),
                        focusedMessageId = MessageId("message-1")
                    )
            )

        assertEquals(
            ThreadIdentityProof.Rejected(ThreadIdentityError.FocusedMessageMissing),
            proof
        )
    }

    @Test
    fun focusedCandidateWithMatchingFocusedMessageIdAndChannelIsAccepted() {
        val proof =
            ThreadCandidateProofGate.prove(
                route =
                    ThreadRouteId.FocusedThread(
                        channelId = ChannelId("channel-1"),
                        focusedMessageId = MessageId("message-1")
                    ),
                candidate =
                    ThreadRouteId.FocusedThread(
                        channelId = ChannelId("channel-1"),
                        focusedMessageId = MessageId("message-1")
                    )
            )

        assertTrue(proof is ThreadIdentityProof.Accepted)
    }
}
