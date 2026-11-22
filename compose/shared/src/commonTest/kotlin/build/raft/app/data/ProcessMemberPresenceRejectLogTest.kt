package build.raft.app.data

import build.raft.app.model.AgentStatus
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberPresenceUpdated
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * #proj-mobile:#200: a message-row status dot stayed "online" while the agent worked, and the
 * diagnostics could not say whether the `agent:activity_state` arrived and was refused. Every write
 * the registry does not take now leaves a `presence_write_rejected` line with a reason code.
 */
class ProcessMemberPresenceRejectLogTest {
    private val owner = ProcessServerMembersOwner(environmentId = "env-1", accountId = "acct-1", serverId = "server-a")
    private val agentId = "agent-cachet-0001"

    private fun state(status: AgentStatus, seq: Long) =
        MemberPresenceUpdated(
            memberId = MemberId(agentId),
            presence = status.presence,
            kind = MemberKind.Agent,
            agentStatus = status,
            serverSeq = seq,
            coarseState = true,
        )

    @Test
    fun aThrowingLoggerNeverBreaksTheWrite() {
        val registry = ProcessMemberPresenceRegistry(rejectLogger = { error("logger down") })
        registry.apply(owner, state(AgentStatus.Working, seq = 2), 0L)
        registry.apply(owner, state(AgentStatus.Online, seq = 1), 0L)
        registry.apply(owner, state(AgentStatus.Online, seq = 3), 0L)
        assertEquals(AgentStatus.Online, registry.factFor(owner, agentId)?.agentStatus)
    }
}
