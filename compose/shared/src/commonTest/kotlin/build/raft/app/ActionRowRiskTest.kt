package build.raft.app

import build.raft.app.members.membersAgentProfileActionRisk
import build.raft.designsystem.components.RaftRiskTone
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Action rows color only what can't be undone (botiverse/slock #15515, cindyz #triage:f2e107f7):
 * red for Delete / Disconnect channel and Archive agent; every other action row is ink, the
 * reversible ones warning only in their confirmation's button.
 */
class ActionRowRiskTest {
    @Test
    fun channelActionRowsAreRedOnlyForDeleteAndDisconnect() {
        val red = ChannelSettingsActionRow.entries.filter { channelSettingsActionRisk(it) == RaftRiskTone.Danger }
        assertEquals(setOf(ChannelSettingsActionRow.Delete, ChannelSettingsActionRow.Disconnect), red.toSet())
        // Archive, Leave and Stop all agents are no longer orange.
        assertEquals(emptyList<ChannelSettingsActionRow>(), ChannelSettingsActionRow.entries.filter { channelSettingsActionRisk(it) == RaftRiskTone.Warning })
        assertEquals(null, channelSettingsActionRisk(ChannelSettingsActionRow.Archive))
        assertEquals(null, channelSettingsActionRisk(ChannelSettingsActionRow.Leave))
        assertEquals(null, channelSettingsActionRisk(ChannelSettingsActionRow.StopAllAgents))
    }

    @Test
    fun agentProfileActionRowsAreRedOnlyForArchiveAgent() {
        assertEquals(RaftRiskTone.Danger, membersAgentProfileActionRisk(MembersAgentLifecycleAction.Delete))
        listOf(
            MembersAgentLifecycleAction.Start,
            MembersAgentLifecycleAction.Stop,
            MembersAgentLifecycleAction.Restart,
            MembersAgentLifecycleAction.ResetSession,
            MembersAgentLifecycleAction.ResetFull,
        ).forEach { assertEquals(null, membersAgentProfileActionRisk(it), "$it") }
    }
}
