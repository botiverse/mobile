package build.raft.app

import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.ServerId
import build.raft.app.thread.ThreadLaunchIdentityConstructionError
import build.raft.app.thread.ThreadLaunchIdentityConstructionResult
import build.raft.app.thread.toNormalizedLegacyWirePayload
import build.raft.app.thread.toThreadLaunchIdentityConstruction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ThreadLaunchProvenanceConstructionTest {
    @Test
    fun topLevelLegacyVectorsConstructExistingTypedIdentityAndNormalize() {
        val dmPeer = MemberDetailIdentity(MemberId("human-1"), MemberKind.Human, ServerId("server-1"))
        val vectors =
            listOf(
                TopLevelVector(
                    surfaceKind = RaftThreadSurfaceKind.Channel,
                    channelKind = RaftThreadSurfaceKind.Channel,
                    expectedKind = ConversationKind.Channel,
                    expectedNormalizedChannelKind = RaftThreadSurfaceKind.Channel,
                    dmPeerIdentity = null
                ),
                TopLevelVector(
                    surfaceKind = RaftThreadSurfaceKind.Channel,
                    channelKind = "private",
                    expectedKind = ConversationKind.Channel,
                    expectedNormalizedChannelKind = "private",
                    dmPeerIdentity = null
                ),
                TopLevelVector(
                    surfaceKind = RaftThreadSurfaceKind.Channel,
                    channelKind = JointChannelKindWireValue,
                    expectedKind = ConversationKind.Joint,
                    expectedNormalizedChannelKind = JointChannelKindWireValue,
                    dmPeerIdentity = null
                ),
                TopLevelVector(
                    surfaceKind = RaftThreadSurfaceKind.DirectMessage,
                    // The old screen DTO commonly left its render default on the DM wire.
                    channelKind = RaftThreadSurfaceKind.Channel,
                    expectedKind = ConversationKind.DirectMessage,
                    expectedNormalizedChannelKind = null,
                    dmPeerIdentity = dmPeer
                )
            )

        vectors.forEach { vector ->
            val wire =
                WireThreadLaunchPayload(
                    instanceId = "route-instance",
                    surfaceKind = vector.surfaceKind,
                    channelKind = vector.channelKind,
                    threadId = "conversation-1",
                    dmPeerIdentity = vector.dmPeerIdentity,
                    focusedMessageId = "focus-1",
                    selectedTab = "files"
                )
            val accepted = assertAccepted(wire.toThreadLaunchIdentityConstruction())
            val normalized = accepted.toNormalizedLegacyWirePayload(wire)
            val roundTrip = assertAccepted(normalized.toThreadLaunchIdentityConstruction())

            assertEquals(vector.expectedKind, accepted.identity.kind)
            assertEquals(ChannelId("conversation-1"), accepted.identity.channelId)
            assertEquals(vector.dmPeerIdentity, accepted.identity.dmPeerIdentity)
            assertEquals(accepted.identity, roundTrip.identity)
            assertEquals(accepted.digest, roundTrip.digest)
            assertEquals("route-instance", normalized.instanceId)
            assertEquals("focus-1", normalized.focusedMessageId)
            assertEquals("files", normalized.selectedTab)
            assertEquals("conversation-1", normalized.threadId)
            assertEquals(vector.expectedNormalizedChannelKind, normalized.channelKind)
            assertNull(normalized.sourceThreadId)
            assertNull(normalized.streamChannelId)
        }
    }

    @Test
    fun malformedMissingAndConflictingLegacyVectorsRejectWithTypedErrors() {
        val vectors =
            listOf(
                RejectionVector(
                    WireThreadLaunchPayload(
                        surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                        parentChannelId = "parent-1",
                        parentSurfaceKind = RaftThreadSurfaceKind.Channel,
                        parentChannelKind = RaftThreadSurfaceKind.Channel
                    ),
                    ThreadLaunchIdentityConstructionError.MissingReplyParentPair
                ),
                RejectionVector(
                    WireThreadLaunchPayload(
                        surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                        parentChannelId = "parent-1",
                        parentMessageId = "message-1",
                        parentSurfaceKind = "unknown",
                        parentChannelKind = RaftThreadSurfaceKind.Channel
                    ),
                    ThreadLaunchIdentityConstructionError.MalformedLegacyKind
                ),
                RejectionVector(
                    WireThreadLaunchPayload(
                        surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                        parentChannelId = "parent-1",
                        parentMessageId = "message-1",
                        parentSurfaceKind = RaftThreadSurfaceKind.DirectMessage,
                        parentChannelKind = JointChannelKindWireValue
                    ),
                    ThreadLaunchIdentityConstructionError.ConflictingLegacyIdentity
                ),
                RejectionVector(
                    WireThreadLaunchPayload(
                        surfaceKind = RaftThreadSurfaceKind.Channel,
                        channelKind = RaftThreadSurfaceKind.Channel,
                        threadId = "channel-1",
                        streamChannelId = "channel-2"
                    ),
                    ThreadLaunchIdentityConstructionError.ConflictingLegacyIdentity
                ),
                RejectionVector(
                    WireThreadLaunchPayload(
                        surfaceKind = RaftThreadSurfaceKind.DirectMessage,
                        channelKind = JointChannelKindWireValue,
                        threadId = "dm-1"
                    ),
                    ThreadLaunchIdentityConstructionError.ConflictingLegacyIdentity
                ),
                RejectionVector(
                    WireThreadLaunchPayload(
                        surfaceKind = RaftThreadSurfaceKind.Channel,
                        channelKind = RaftThreadSurfaceKind.DirectMessage,
                        threadId = "dm-1"
                    ),
                    ThreadLaunchIdentityConstructionError.ConflictingLegacyIdentity
                ),
                RejectionVector(
                    WireThreadLaunchPayload(
                        surfaceKind = RaftThreadSurfaceKind.Channel,
                        channelKind = RaftThreadSurfaceKind.ReplyThread,
                        threadId = "reply-stream-1",
                        parentChannelId = "parent-1",
                        parentMessageId = "message-1",
                        parentSurfaceKind = RaftThreadSurfaceKind.Channel,
                        parentChannelKind = "private"
                    ),
                    ThreadLaunchIdentityConstructionError.ConflictingLegacyIdentity
                ),
                RejectionVector(
                    WireThreadLaunchPayload(
                        surfaceKind = RaftThreadSurfaceKind.Channel,
                        channelKind = RaftThreadSurfaceKind.Channel
                    ),
                    ThreadLaunchIdentityConstructionError.BlankRouteIdentity
                ),
                RejectionVector(
                    WireThreadLaunchPayload(
                        surfaceKind = RaftThreadSurfaceKind.Channel,
                        threadId = "channel-1",
                        parentChannelId = "parent-1",
                        parentMessageId = "message-1",
                        parentSurfaceKind = RaftThreadSurfaceKind.Channel,
                        parentChannelKind = RaftThreadSurfaceKind.Channel
                    ),
                    ThreadLaunchIdentityConstructionError.UnexpectedReplyMetadata
                ),
                RejectionVector(
                    WireThreadLaunchPayload(
                        surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                        streamChannelId = "reply-stream-1",
                        parentChannelId = "parent-1",
                        parentMessageId = "message-1",
                        parentSurfaceKind = RaftThreadSurfaceKind.DirectMessage,
                        parentChannelKind = RaftThreadSurfaceKind.DirectMessage,
                        dmPeerIdentity =
                            MemberDetailIdentity(
                                memberId = MemberId("human-1"),
                                kind = MemberKind.Human
                            )
                    ),
                    ThreadLaunchIdentityConstructionError.UnexpectedDirectMessagePeer
                )
            )

        vectors.forEach { vector ->
            val rejected = assertIs<ThreadLaunchIdentityConstructionResult.Rejected>(
                vector.payload.toThreadLaunchIdentityConstruction()
            )
            assertEquals(vector.error, rejected.error)
        }
    }

    private fun assertAccepted(
        result: ThreadLaunchIdentityConstructionResult
    ): ThreadLaunchIdentityConstructionResult.Accepted =
        assertIs<ThreadLaunchIdentityConstructionResult.Accepted>(result)

    private data class TopLevelVector(
        val surfaceKind: String,
        val channelKind: String?,
        val expectedKind: ConversationKind,
        val expectedNormalizedChannelKind: String?,
        val dmPeerIdentity: MemberDetailIdentity?
    )

    private data class RejectionVector(
        val payload: WireThreadLaunchPayload,
        val error: ThreadLaunchIdentityConstructionError
    )
}
