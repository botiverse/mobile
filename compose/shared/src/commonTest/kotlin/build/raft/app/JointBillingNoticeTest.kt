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
            surfaceKind = KuiklyThreadSurfaceKind.Channel,
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
                surfaceKind = KuiklyThreadSurfaceKind.Channel,
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
                surfaceKind = KuiklyThreadSurfaceKind.Channel,
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
                surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
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
                surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
                parentChannelId = "joint-parent",
                lookupIds = listOf("joint-parent", "thread-1"),
                projections = graceProjections
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
                surfaceKind = KuiklyThreadSurfaceKind.Channel,
                channelKind = "public",
                title = "general",
                permissions = ConversationPermissions(),
                // Server must not emit these on non-joint rows; defensive anyway.
                jointBillingLocked = true
            )
        assertEquals(
            JointBillingNotice.None,
            jointBillingNoticeState(
                surfaceKind = KuiklyThreadSurfaceKind.Channel,
                parentChannelId = null,
                lookupIds = listOf("ch-1"),
                projections = mapOf("ch-1" to regular)
            )
        )
        assertEquals(
            JointBillingNotice.None,
            jointBillingNoticeState(
                surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
                parentChannelId = "missing-parent",
                lookupIds = listOf("missing-parent", "thread-1"),
                projections = emptyMap()
            )
        )
    }
}
