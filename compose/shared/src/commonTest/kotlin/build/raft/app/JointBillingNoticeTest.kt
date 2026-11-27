package build.raft.app

import build.raft.app.data.SharedConversationDirectoryProjection
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationPermissions
import build.raft.app.thread.ExternalFreezeActionKind
import build.raft.app.thread.ExternalFreezeState
import build.raft.app.thread.JointBillingNotice
import build.raft.app.thread.RaftThreadScreenPayload
import build.raft.app.thread.RaftThreadSettingsAction
import build.raft.app.thread.externalFreezeActionKind
import build.raft.app.thread.externalFreezeState
import build.raft.app.thread.externalFrozenUsesManagerCopy
import build.raft.app.thread.jointBillingNoticeState
import build.raft.app.thread.threadEventAllowed
import build.raft.app.thread.threadLimitLocksWrites
import build.raft.app.thread.withThreadWriteActionsEnabled
import kotlin.test.Test
import kotlin.test.assertEquals

// contract #contracts:97dbd451 v0.3 §18.8 — joint channel over-limit notice:
// grace (deadline in future per server-provided jointOverLimitGraceEndsAt) ->
// writable + warning banner; jointBillingLocked -> read-only banner, composer off.
class JointBillingNoticeTest {
    private fun jointProjection(
        id: String,
        locked: Boolean? = null,
        graceEndsAt: String? = null,
        externalFreezeAt: String? = null,
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
            jointOverLimitGraceEndsAt = graceEndsAt,
            externalFreezeAt = externalFreezeAt,
        )

    private fun channelProjection(
        id: String,
        externalFreezeAt: String? = null,
    ): SharedConversationDirectoryProjection =
        SharedConversationDirectoryProjection(
            serverId = "server-1",
            canonicalChannelId = id,
            aliases = setOf(id),
            conversationKind = ConversationKind.Channel,
            surfaceKind = RaftThreadSurfaceKind.Channel,
            channelKind = "public",
            title = "general",
            permissions = ConversationPermissions(),
            externalFreezeAt = externalFreezeAt,
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
    fun externalFreezeAppliesToAnyChannelTypeAndFlipsActiveAtDeadline() {
        val freezeIso = "2026-10-01T00:00:00Z"
        val projections = mapOf("ch-1" to channelProjection("ch-1", externalFreezeAt = freezeIso))
        // Before deadline -> Upcoming
        assertEquals(
            ExternalFreezeState.Upcoming(freezeIso),
            externalFreezeState(
                surfaceKind = RaftThreadSurfaceKind.Channel,
                parentChannelId = null,
                lookupIds = listOf("ch-1"),
                projections = projections,
                nowEpochMillis = 1790640000000L,
            ),
        )
        // Exactly at deadline -> Active (read-only)
        assertEquals(
            ExternalFreezeState.Active,
            externalFreezeState(
                surfaceKind = RaftThreadSurfaceKind.Channel,
                parentChannelId = null,
                lookupIds = listOf("ch-1"),
                projections = projections,
                nowEpochMillis = 1790812800000L,
            ),
        )
        // Reply thread inherits parent channel externalFreezeAt
        assertEquals(
            ExternalFreezeState.Active,
            externalFreezeState(
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                parentChannelId = "ch-1",
                lookupIds = listOf("ch-1", "thread-1"),
                projections = projections,
                nowEpochMillis = 1790899200000L,
            ),
        )
        // Task conversation inherits parent channel externalFreezeAt
        assertEquals(
            ExternalFreezeState.Active,
            externalFreezeState(
                surfaceKind = RaftThreadSurfaceKind.Channel,
                parentChannelId = null,
                lookupIds = listOf("task-conv-1"),
                projections = projections,
                nowEpochMillis = 1790899200000L,
                taskParentChannelId = "ch-1",
            ),
        )
        // Direct messages never carry an external freeze
        assertEquals(
            ExternalFreezeState.None,
            externalFreezeState(
                surfaceKind = RaftThreadSurfaceKind.DirectMessage,
                parentChannelId = null,
                lookupIds = listOf("ch-1"),
                projections = projections,
                nowEpochMillis = 1790899200000L,
            ),
        )
        // Unparseable freezeAt yields None
        assertEquals(
            ExternalFreezeState.None,
            externalFreezeState(
                surfaceKind = RaftThreadSurfaceKind.Channel,
                parentChannelId = null,
                lookupIds = listOf("bad"),
                projections = mapOf("bad" to channelProjection("bad", externalFreezeAt = "not-a-date")),
                nowEpochMillis = 1790899200000L,
            ),
        )
    }

    @Test
    fun limitLockedPreservesChannelSettingsAndBlocksChatWrites() {
        val payload =
            RaftThreadScreenPayload(
                showSettingsAction = true,
                settingsActions = listOf(RaftThreadSettingsAction("archive", enabled = true)),
                canAddMembers = true,
                showReactionActions = true,
                showTaskStatusActions = true,
                showMessageSaveAction = true,
            )
        val locked = payload.withThreadWriteActionsEnabled(enabled = false, preserveSettingsAccess = true)
        assertEquals(true, locked.showSettingsAction)
        assertEquals(1, locked.settingsActions.size)
        assertEquals(false, locked.canAddMembers)
        assertEquals(false, locked.showReactionActions)
        assertEquals(false, locked.showTaskStatusActions)
        assertEquals(false, locked.showMessageSaveAction)

        assertEquals(false, threadEventAllowed(writeActionsEnabled = true, action = "toggleReaction", limitLocked = true))
        assertEquals(false, threadEventAllowed(writeActionsEnabled = true, action = "convertMessageToTask", limitLocked = true))
        assertEquals(true, threadEventAllowed(writeActionsEnabled = true, action = "openChannelSettings", limitLocked = true))
        assertEquals(true, threadEventAllowed(writeActionsEnabled = true, action = "removeMember", limitLocked = true))
        assertEquals(true, threadEventAllowed(writeActionsEnabled = true, action = "settingsAction", limitLocked = true))
        assertEquals(false, threadEventAllowed(writeActionsEnabled = false, action = "removeMember", limitLocked = true))
    }

    @Test
    fun externalFreezeRoleAndPlanRulesMatchWebChannelLimitBanners() {
        assertEquals(ExternalFreezeActionKind.ViewBilling, externalFreezeActionKind(plan = "pro", role = "owner"))
        assertEquals(ExternalFreezeActionKind.ViewBilling, externalFreezeActionKind(plan = "pro", role = "member"))
        assertEquals(ExternalFreezeActionKind.UpgradeToPro, externalFreezeActionKind(plan = "free", role = "owner"))
        assertEquals(ExternalFreezeActionKind.UpgradeToPro, externalFreezeActionKind(plan = null, role = "owner"))
        assertEquals(ExternalFreezeActionKind.AskOwner, externalFreezeActionKind(plan = "free", role = "admin"))
        assertEquals(ExternalFreezeActionKind.AskOwner, externalFreezeActionKind(plan = "free", role = "member"))
        assertEquals(true, externalFrozenUsesManagerCopy("owner"))
        assertEquals(true, externalFrozenUsesManagerCopy("admin"))
        assertEquals(false, externalFrozenUsesManagerCopy("member"))
        assertEquals(false, externalFrozenUsesManagerCopy(null))
        assertEquals(true, threadLimitLocksWrites(JointBillingNotice.None, ExternalFreezeState.Active))
        assertEquals(false, threadLimitLocksWrites(JointBillingNotice.None, ExternalFreezeState.Upcoming("2026-10-01T00:00:00Z")))
        assertEquals(true, threadLimitLocksWrites(JointBillingNotice.Locked, ExternalFreezeState.None))
    }
}
