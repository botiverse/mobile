package build.raft.app

import build.raft.app.model.MemberId
import build.raft.app.model.RuntimeConfig
import build.raft.app.model.RuntimeModelConfig
import build.raft.app.model.ServerId
import kotlin.test.Test
import kotlin.test.assertEquals

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
        formV2Declined: Boolean = false,
        editing: Boolean = true
    ) = membersRuntimeEditorDialog(
        editing = editing,
        opened = opened,
        openedFormV2 = openedFormV2,
        formV2Declined = formV2Declined,
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
        assertEquals(MembersRuntimeEditorDialog.Legacy, dialog(reconnecting, openedFormV2 = false))
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
        assertEquals(
            MembersRuntimeEditorDialog.None,
            dialog(steady().copy(authority = opened.copy(accountId = "account-2")))
        )
        assertEquals(
            MembersRuntimeEditorDialog.None,
            dialog(steady().copy(authority = opened.copy(serverId = ServerId("server-2"))))
        )
        assertEquals(
            MembersRuntimeEditorDialog.None,
            dialog(steady().copy(authority = opened.copy(memberId = MemberId("agent-2"))))
        )
    }

    @Test
    fun declinedFormV2HandsTheSameSessionToTheLegacyEditor() {
        assertEquals(MembersRuntimeEditorDialog.Legacy, dialog(steady(), formV2Declined = true))
        assertEquals(
            MembersRuntimeEditorDialog.Legacy,
            dialog(steady().copy(runtimeStatusResolved = false), formV2Declined = true)
        )
    }
}
