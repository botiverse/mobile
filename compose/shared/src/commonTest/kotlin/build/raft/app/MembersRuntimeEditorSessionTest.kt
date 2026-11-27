package build.raft.app

import build.raft.app.model.MemberId
import build.raft.app.model.RuntimeConfig
import build.raft.app.model.RuntimeModelConfig
import build.raft.app.model.ServerId
import build.raft.app.data.AgentDetailSnapshot
import build.raft.app.data.AgentDetailSummary
import build.raft.app.members.MembersAgentProfileEditAuthority
import build.raft.app.members.MembersRuntimeEditorDialog
import build.raft.app.members.MembersRuntimeEditorLiveFacts
import build.raft.app.members.membersRouteCanManageAgent
import build.raft.app.members.membersRuntimeEditorCanManage
import build.raft.app.members.membersRuntimeEditorDialog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * artin 612602c0: returning from background reconnects presence, which publishes status=null
 * until the forced roster refresh lands, and refreshes detail. The runtime editor used to be
 * gated on those live values, so it unmounted (and lost what the user typed) on every resume.
 */
class MembersRuntimeEditorSessionTest {
    private val opened =
        MembersAgentProfileEditAuthority(
            memberId = MemberId("agent-1"),
            environmentId = "prod",
            accountId = "account-1",
            serverId = ServerId("server-1"),
            profileGeneration = 3L
        )
    private val config = RuntimeConfig(runtime = "codex", model = RuntimeModelConfig.Preset("gpt-5"))

    private fun steady() =
        MembersRuntimeEditorLiveFacts(
            authority = opened,
            canManage = true,
            deleted = false,
            runtimeStatusResolved = true,
            config = config,
            formV2Available = true
        )

    private fun dialog(
        live: MembersRuntimeEditorLiveFacts,
        openedFormV2: Boolean = true,
        editing: Boolean = true
    ) = membersRuntimeEditorDialog(
        editing = editing,
        opened = opened,
        openedFormV2 = openedFormV2,
        live = live
    )

    @Test
    fun resumeReconnectTransientStateKeepsTheOpenEditor() {
        assertEquals(MembersRuntimeEditorDialog.FormV2, dialog(steady()))
        // Presence reconnect: status unknown, detail reloading, flag snapshot not yet re-read.
        val reconnecting =
            steady().copy(
                authority = null,
                runtimeStatusResolved = false,
                config = null,
                formV2Available = false
            )
        assertEquals(MembersRuntimeEditorDialog.FormV2, dialog(reconnecting))
        assertEquals(MembersRuntimeEditorDialog.None, dialog(reconnecting, openedFormV2 = false))
        // Detail refreshed: a new, value-equal config instance and a bumped profile generation.
        val refreshed =
            steady().copy(
                authority = opened.copy(profileGeneration = 4L),
                config = config.copy()
            )
        assertEquals(MembersRuntimeEditorDialog.FormV2, dialog(refreshed))
    }

    @Test
    fun realCloseConditionsStillCloseTheEditor() {
        assertEquals(MembersRuntimeEditorDialog.None, dialog(steady(), editing = false))
        assertEquals(MembersRuntimeEditorDialog.None, dialog(steady().copy(deleted = true)))
        assertEquals(MembersRuntimeEditorDialog.None, dialog(steady().copy(canManage = false)))
    }

    private fun agentDetail(creatorId: String?, deletedAt: String? = null) =
        AgentDetailSnapshot(
            agentId = MemberId("agent-1"),
            summary = AgentDetailSummary(id = "agent-1", name = "Agent One", creatorId = creatorId, deletedAt = deletedAt),
            directMessages = emptyList(),
            reminders = emptyList(),
            activity = emptyList()
        )

    /** The creator (not a server admin) — the right comes from the detail's creatorId. */
    private fun creatorCanManage(
        currentDetail: AgentDetailSnapshot?,
        lastContentDetail: AgentDetailSnapshot?,
        refreshTransient: Boolean
    ) = membersRuntimeEditorCanManage(
        currentDetail = currentDetail,
        lastContentDetail = lastContentDetail,
        refreshTransient = refreshTransient
    ) { detail ->
        membersRouteCanManageAgent(
            allowsPrivateAgentSurfaces = true,
            canManageSettings = false,
            currentUserId = "creator-1",
            detail = detail
        )
    }

    @Test
    fun freshDetailWithoutTheManageRightClosesTheEditor() {
        val lastGood = agentDetail(creatorId = "creator-1")
        val otherCreator =
            creatorCanManage(currentDetail = agentDetail(creatorId = "someone-else"), lastContentDetail = lastGood, refreshTransient = false)
        assertFalse(otherCreator)
        assertEquals(MembersRuntimeEditorDialog.None, dialog(steady().copy(canManage = otherCreator)))
        // A fresh detail wins over the remembered one even mid-refresh.
        assertFalse(
            creatorCanManage(currentDetail = agentDetail(creatorId = "someone-else"), lastContentDetail = lastGood, refreshTransient = true)
        )
        // Deleted in the fresh detail.
        assertFalse(
            creatorCanManage(currentDetail = agentDetail(creatorId = "creator-1", deletedAt = "2026-10-27T00:00:00Z"), lastContentDetail = lastGood, refreshTransient = false)
        )
        // A terminal (non-transient) state with no detail does not reuse the remembered one.
        assertFalse(creatorCanManage(currentDetail = null, lastContentDetail = lastGood, refreshTransient = false))
    }
}
