package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationSummary
import build.raft.app.model.MessageId
import build.raft.app.model.ServerId
import build.raft.app.model.ThreadConversation
import build.raft.app.model.ThreadId
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
    fun rejectsReplyCandidateWithMatchingParentPairWhenResolvedStreamsDiffer() {
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

        assertEquals(
            ThreadIdentityProof.Rejected(ThreadIdentityError.ReplyIdentityMismatch.StreamMismatch),
            proof
        )
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
    fun channelSummaryReplyMetadataDoesNotBecomeReplyThreadRouteProof() {
        val route =
            ThreadSnapshot(
                conversation =
                    ConversationSummary(
                        threadId = ThreadId("channel-1"),
                        channelId = ChannelId("channel-1"),
                        kind = ConversationKind.Channel,
                        name = "general",
                        thread =
                            ThreadConversation(
                                parentChannelId = ChannelId("channel-1"),
                                parentMessageId = MessageId("parent-1"),
                                streamChannelId = ChannelId("reply-thread-1")
                            )
                    ),
                messages = emptyList()
            ).toThreadRouteId()

        assertEquals(ThreadRouteId.ChannelThread(channelId = ChannelId("channel-1")), route)
    }
}
