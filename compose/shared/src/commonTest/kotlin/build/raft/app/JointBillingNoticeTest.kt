package build.raft.app

import build.raft.app.data.SharedConversationDirectoryProjection
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationPermissions
import kotlin.test.Test
import kotlin.test.assertEquals

// contract #contracts:97dbd451 v0.3 §18.8 — joint channel over-limit notice:
// grace (deadline in future per server-provided jointOverLimitGraceEndsAt) ->
// writable + warning banner; jointBillingLocked -> read-only banner, composer off.
class JointBillingNoticeTest {
    private fun jointProjection(
        id: String,
        locked: Boolean? = null,
        graceEndsAt: String? = null
    ): SharedConversationDirectoryProjection =
        SharedConversationDirectoryProjection(
            serverId = "server-1",
            canonicalChannelId = id,
            aliases = setOf(id),
            conversationKind = ConversationKind.Joint,
            surfaceKind = RaftThreadSurfaceKind.Channel,
            channelKind = "joint",
            title = "joint",
            permissions = ConversationPermissions(),
            jointBillingLocked = locked,
            jointOverLimitGraceEndsAt = graceEndsAt
        )

    @Test
    fun lockedProjectionYieldsLockedNotice() {
        val projections = mapOf("joint-1" to jointProjection("joint-1", locked = true))
        assertEquals(
            JointBillingNotice.Locked,
            jointBillingNoticeState(
                surfaceKind = RaftThreadSurfaceKind.Channel,
                parentChannelId = null,
                lookupIds = listOf("joint-1"),
                projections = projections
            )
        )
    }

    @Test
    fun graceProjectionWithoutLockYieldsGraceNotice() {
        val projections =
            mapOf("joint-1" to jointProjection("joint-1", graceEndsAt = "2026-10-01T00:00:00Z"))
        assertEquals(
            JointBillingNotice.Grace,
            jointBillingNoticeState(
                surfaceKind = RaftThreadSurfaceKind.Channel,
                parentChannelId = null,
                lookupIds = listOf("joint-1"),
                projections = projections
            )
        )
    }

    @Test
    fun replyThreadInheritsParentJointLockAndGrace() {
        val projections =
            mapOf("joint-parent" to jointProjection("joint-parent", locked = true))
        assertEquals(
            JointBillingNotice.Locked,
            jointBillingNoticeState(
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                parentChannelId = "joint-parent",
                lookupIds = listOf("joint-parent", "thread-1"),
                projections = projections
            )
        )
        val graceProjections =
            mapOf("joint-parent" to jointProjection("joint-parent", graceEndsAt = "2026-10-01T00:00:00Z"))
        assertEquals(
            JointBillingNotice.Grace,
            jointBillingNoticeState(
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                parentChannelId = "joint-parent",
                lookupIds = listOf("joint-parent", "thread-1"),
                projections = graceProjections
            )
        )
    }

    @Test
    fun expiredGraceFallsBackToLocked() {
        val projections =
            mapOf("joint-1" to jointProjection("joint-1", graceEndsAt = "2026-10-01T00:00:00Z"))
        // now < deadline (2026-09-29) -> still writable grace
        assertEquals(
            JointBillingNotice.Grace,
            jointBillingNoticeState(
                surfaceKind = RaftThreadSurfaceKind.Channel,
                parentChannelId = null,
                lookupIds = listOf("joint-1"),
                projections = projections,
                nowEpochMillis = 1790640000000L
            )
        )
        // now >= deadline (2026-10-02) -> locally flips to Locked without waiting for jointBillingLocked
        assertEquals(
            JointBillingNotice.Locked,
            jointBillingNoticeState(
                surfaceKind = RaftThreadSurfaceKind.Channel,
                parentChannelId = null,
                lookupIds = listOf("joint-1"),
                projections = projections,
                nowEpochMillis = 1790899200000L
            )
        )
        // now exactly at the deadline -> Locked
        assertEquals(
            JointBillingNotice.Locked,
            jointBillingNoticeState(
                surfaceKind = RaftThreadSurfaceKind.Channel,
                parentChannelId = null,
                lookupIds = listOf("joint-1"),
                projections = projections,
                nowEpochMillis = 1790812800000L
            )
        )
    }

    @Test
    fun blankGraceEndsAtYieldsNoNotice() {
        val projections = mapOf("joint-1" to jointProjection("joint-1", graceEndsAt = ""))
        assertEquals(
            JointBillingNotice.None,
            jointBillingNoticeState(
                surfaceKind = RaftThreadSurfaceKind.Channel,
                parentChannelId = null,
                lookupIds = listOf("joint-1"),
                projections = projections
            )
        )
    }

    @Test
    fun regularChannelAndDirectMessageNeverShowNotice() {
        val regular =
            SharedConversationDirectoryProjection(
                serverId = "server-1",
                canonicalChannelId = "ch-1",
                aliases = setOf("ch-1"),
                conversationKind = ConversationKind.Channel,
                surfaceKind = RaftThreadSurfaceKind.Channel,
                channelKind = "public",
                title = "general",
                permissions = ConversationPermissions(),
                // Server must not emit these on non-joint rows; defensive anyway.
                jointBillingLocked = true
            )
        assertEquals(
            JointBillingNotice.None,
            jointBillingNoticeState(
                surfaceKind = RaftThreadSurfaceKind.Channel,
                parentChannelId = null,
                lookupIds = listOf("ch-1"),
                projections = mapOf("ch-1" to regular)
            )
        )
        assertEquals(
            JointBillingNotice.None,
            jointBillingNoticeState(
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                parentChannelId = "missing-parent",
                lookupIds = listOf("missing-parent", "thread-1"),
                projections = emptyMap()
            )
        )
    }

    @Test
    fun messageRowsResolveOtherServerSendersOnlyOnJointSurfaces() {
        val projections = mapOf("joint-1" to jointProjection("joint-1"))
        fun joint(payload: RaftThreadScreenPayload, lookupIds: List<String> = emptyList()) =
            threadMessageSurfaceIsJoint(payload, lookupIds, projections)

        assertEquals(true, joint(RaftThreadScreenPayload(surfaceKind = RaftThreadSurfaceKind.Channel, channelKind = "joint")))
        assertEquals(true, joint(RaftThreadScreenPayload(surfaceKind = RaftThreadSurfaceKind.Channel), listOf("joint-1")))
        assertEquals(
            true,
            joint(RaftThreadScreenPayload(surfaceKind = RaftThreadSurfaceKind.ReplyThread, parentChannelKind = "joint"))
        )
        assertEquals(
            true,
            joint(RaftThreadScreenPayload(surfaceKind = RaftThreadSurfaceKind.ReplyThread, parentChannelId = "joint-1"))
        )
        assertEquals(false, joint(RaftThreadScreenPayload(surfaceKind = RaftThreadSurfaceKind.Channel), listOf("chan-1")))
        assertEquals(false, joint(RaftThreadScreenPayload(surfaceKind = RaftThreadSurfaceKind.DirectMessage), listOf("joint-1")))
    }
}
