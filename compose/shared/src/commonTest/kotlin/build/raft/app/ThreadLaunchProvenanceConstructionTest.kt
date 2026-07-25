package build.raft.app

import build.raft.app.data.ThreadCandidateProofGate
import build.raft.app.data.ThreadIdentityError
import build.raft.app.data.ThreadIdentityProof
import build.raft.app.data.ThreadRouteId
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.ServerId
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class ThreadLaunchProvenanceConstructionTest {
    @Test
    fun topLevelLegacyVectorsConstructExistingTypedIdentityAndNormalize() {
        val dmPeer = MemberDetailIdentity(MemberId("human-1"), MemberKind.Human, ServerId("server-1"))
        val vectors =
            listOf(
                TopLevelVector(
                    surfaceKind = KuiklyThreadSurfaceKind.Channel,
                    channelKind = KuiklyThreadSurfaceKind.Channel,
                    expectedKind = ConversationKind.Channel,
                    expectedNormalizedChannelKind = KuiklyThreadSurfaceKind.Channel,
                    dmPeerIdentity = null
                ),
                TopLevelVector(
                    surfaceKind = KuiklyThreadSurfaceKind.Channel,
                    channelKind = "private",
                    expectedKind = ConversationKind.Channel,
                    expectedNormalizedChannelKind = "private",
                    dmPeerIdentity = null
                ),
                TopLevelVector(
                    surfaceKind = KuiklyThreadSurfaceKind.Channel,
                    channelKind = JointChannelKindWireValue,
                    expectedKind = ConversationKind.Joint,
                    expectedNormalizedChannelKind = JointChannelKindWireValue,
                    dmPeerIdentity = null
                ),
                TopLevelVector(
                    surfaceKind = KuiklyThreadSurfaceKind.DirectMessage,
                    // The old screen DTO commonly left its render default on the DM wire.
                    channelKind = KuiklyThreadSurfaceKind.Channel,
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
    fun replyParentKindAndStreamVectorsRoundTripWithoutSentinelLeakage() {
        val vectors =
            listOf(
                ReplyVector(ConversationKind.Channel, resolvedStream = null),
                ReplyVector(ConversationKind.Channel, resolvedStream = "reply-stream-1"),
                ReplyVector(ConversationKind.Joint, resolvedStream = "reply-stream-1"),
                ReplyVector(ConversationKind.DirectMessage, resolvedStream = "reply-stream-1")
            )

        vectors.forEach { vector ->
            val wire =
                WireThreadLaunchPayload(
                    instanceId = "reply-instance",
                    surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
                    channelKind = KuiklyThreadSurfaceKind.Channel,
                    streamChannelId = vector.resolvedStream,
                    parentChannelId = "parent-1",
                    parentMessageId = "message-1",
                    parentSurfaceKind = vector.parentKind.threadParentSurfaceKindOrNull(),
                    parentChannelKind = vector.parentKind.threadChannelKindWireValue(),
                    selectedTab = "chat"
                )
            val accepted = assertAccepted(wire.toThreadLaunchIdentityConstruction())
            val normalized = accepted.toNormalizedLegacyWirePayload(wire)
            val roundTrip = assertAccepted(normalized.toThreadLaunchIdentityConstruction())

            assertEquals(ConversationKind.Thread, accepted.identity.kind)
            assertEquals(vector.parentKind, accepted.parentConversationKind)
            assertEquals(
                vector.resolvedStream?.let { ThreadLaunchReplyStream.Resolved(ChannelId(it)) }
                    ?: ThreadLaunchReplyStream.Unresolved,
                accepted.replyStream
            )
            assertEquals(vector.resolvedStream, accepted.identity.thread?.streamChannelId?.value)
            assertEquals(vector.resolvedStream, normalized.streamChannelId)
            assertNull(normalized.threadId)
            assertNull(normalized.sourceThreadId)
            assertEquals(accepted.identity, roundTrip.identity)
            assertEquals(accepted.digest, roundTrip.digest)
        }
    }

    @Test
    fun malformedMissingAndConflictingLegacyVectorsRejectWithTypedErrors() {
        val vectors =
            listOf(
                RejectionVector(
                    WireThreadLaunchPayload(
                        surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
                        parentChannelId = "parent-1",
                        parentSurfaceKind = KuiklyThreadSurfaceKind.Channel,
                        parentChannelKind = KuiklyThreadSurfaceKind.Channel
                    ),
                    ThreadLaunchIdentityConstructionError.MissingReplyParentPair
                ),
                RejectionVector(
                    WireThreadLaunchPayload(
                        surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
                        parentChannelId = "parent-1",
                        parentMessageId = "message-1",
                        parentSurfaceKind = "unknown",
                        parentChannelKind = KuiklyThreadSurfaceKind.Channel
                    ),
                    ThreadLaunchIdentityConstructionError.MalformedLegacyKind
                ),
                RejectionVector(
                    WireThreadLaunchPayload(
                        surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
                        parentChannelId = "parent-1",
                        parentMessageId = "message-1",
                        parentSurfaceKind = KuiklyThreadSurfaceKind.DirectMessage,
                        parentChannelKind = JointChannelKindWireValue
                    ),
                    ThreadLaunchIdentityConstructionError.ConflictingLegacyIdentity
                ),
                RejectionVector(
                    WireThreadLaunchPayload(
                        surfaceKind = KuiklyThreadSurfaceKind.Channel,
                        channelKind = KuiklyThreadSurfaceKind.Channel,
                        threadId = "channel-1",
                        streamChannelId = "channel-2"
                    ),
                    ThreadLaunchIdentityConstructionError.ConflictingLegacyIdentity
                ),
                RejectionVector(
                    WireThreadLaunchPayload(
                        surfaceKind = KuiklyThreadSurfaceKind.DirectMessage,
                        channelKind = JointChannelKindWireValue,
                        threadId = "dm-1"
                    ),
                    ThreadLaunchIdentityConstructionError.ConflictingLegacyIdentity
                ),
                RejectionVector(
                    WireThreadLaunchPayload(
                        surfaceKind = KuiklyThreadSurfaceKind.Channel,
                        channelKind = KuiklyThreadSurfaceKind.DirectMessage,
                        threadId = "dm-1"
                    ),
                    ThreadLaunchIdentityConstructionError.ConflictingLegacyIdentity
                ),
                RejectionVector(
                    WireThreadLaunchPayload(
                        surfaceKind = KuiklyThreadSurfaceKind.Channel,
                        channelKind = KuiklyThreadSurfaceKind.Channel
                    ),
                    ThreadLaunchIdentityConstructionError.BlankRouteIdentity
                )
            )

        vectors.forEach { vector ->
            val rejected = assertIs<ThreadLaunchIdentityConstructionResult.Rejected>(
                vector.payload.toThreadLaunchIdentityConstruction()
            )
            assertEquals(vector.error, rejected.error)
        }
    }

    @Test
    fun typedConstructionRejectsStreamStateThatDisagreesWithProofRoute() {
        val result =
            constructThreadLaunchConversationIdentity(
                routeId =
                    ThreadRouteId.ReplyThread(
                        parentChannelId = ChannelId("parent-1"),
                        parentMessageId = build.raft.app.model.MessageId("message-1"),
                        threadChannelId = ChannelId("reply-stream-1")
                    ),
                kind = ConversationKind.Thread,
                parentConversationKind = ConversationKind.Channel,
                replyStream = ThreadLaunchReplyStream.Unresolved
            )

        val rejected = assertIs<ThreadLaunchIdentityConstructionResult.Rejected>(result)
        assertEquals(ThreadLaunchIdentityConstructionError.ReplyStreamMismatch, rejected.error)
    }

    @Test
    fun digestIsStableAcrossLegacyNormalizationAndChangesWithAuthorityCandidate() {
        val base =
            assertAccepted(
                constructThreadLaunchConversationIdentity(
                    routeId =
                        ThreadRouteId.ChannelThread(
                            channelId = ChannelId("channel-1"),
                            serverId = ServerId("server-1")
                        ),
                    kind = ConversationKind.Channel
                )
            )
        val otherServer =
            assertAccepted(
                constructThreadLaunchConversationIdentity(
                    routeId =
                        ThreadRouteId.ChannelThread(
                            channelId = ChannelId("channel-1"),
                            serverId = ServerId("server-2")
                        ),
                    kind = ConversationKind.Channel
                )
            )

        assertNotEquals(base.digest, otherServer.digest)
        assertFalse(base.digest.value.contains("channel-1"))
        assertFalse(base.digest.value.contains("server-1"))
        assertEquals(16, base.digest.value.length)
        assertEquals(
            ThreadIdentityProof.Rejected(ThreadIdentityError.ServerMismatch),
            ThreadCandidateProofGate.prove(base.routeId, otherServer.routeId)
        )
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun wireDescriptorContainsNoStoreRuntimeAuthorityFields() {
        val fields = WireThreadLaunchPayload.serializer().descriptor.elementNames()

        assertEquals(
            emptySet(),
            fields.intersect(
                setOf(
                    "environmentId",
                    "principalId",
                    "serverId",
                    "authGeneration",
                    "selectionGeneration",
                    "runtimeAuthority",
                    "authority"
                )
            )
        )
    }

    private fun assertAccepted(
        result: ThreadLaunchIdentityConstructionResult
    ): ThreadLaunchIdentityConstructionResult.Accepted =
        assertIs<ThreadLaunchIdentityConstructionResult.Accepted>(result)

    @OptIn(ExperimentalSerializationApi::class)
    private fun SerialDescriptor.elementNames(): Set<String> =
        (0 until elementsCount).mapTo(mutableSetOf(), ::getElementName)

    private data class TopLevelVector(
        val surfaceKind: String,
        val channelKind: String?,
        val expectedKind: ConversationKind,
        val expectedNormalizedChannelKind: String?,
        val dmPeerIdentity: MemberDetailIdentity?
    )

    private data class ReplyVector(
        val parentKind: ConversationKind,
        val resolvedStream: String?
    )

    private data class RejectionVector(
        val payload: WireThreadLaunchPayload,
        val error: ThreadLaunchIdentityConstructionError
    )
}
