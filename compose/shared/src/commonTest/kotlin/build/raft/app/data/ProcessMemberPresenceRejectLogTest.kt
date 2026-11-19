package build.raft.app.data

import build.raft.app.RaftDiagnosticsVolumePolicy
import build.raft.app.model.AgentStatus
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberPresenceUpdated
import build.raft.app.model.Presence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    private fun activity(status: AgentStatus, seq: Long) = state(status, seq).copy(coarseState = false)

    private fun epoch(n: Long) = ProcessPresenceTransportEpoch(connectionFamilyId = "family-1", epoch = n)

    @Test
    fun agentStateAfterSendSequenceLogsEveryRefusedFrameWithItsReason() {
        val lines = mutableListOf<String>()
        val registry = ProcessMemberPresenceRegistry(rejectLogger = lines::add)

        // Turn starts (busy), ends (online), background tasks keep it busy: all applied, nothing logged.
        registry.apply(owner, state(AgentStatus.Working, seq = 10), 0L, epoch(1))
        registry.apply(owner, state(AgentStatus.Online, seq = 11), 0L, epoch(1))
        registry.apply(owner, state(AgentStatus.Working, seq = 12), 0L, epoch(1))
        assertEquals(emptyList<String>(), lines)
        assertEquals(AgentStatus.Working, registry.factFor(owner, agentId)?.agentStatus)

        // The coarse state repeats the seq of the detailed frame it came from: equal is refused.
        registry.apply(owner, state(AgentStatus.Working, seq = 12), 0L, epoch(1))
        // A late lower frame cannot roll the status back.
        registry.apply(owner, activity(AgentStatus.Online, seq = 11), 0L, epoch(1))
        // A frame from a connection older than the current one.
        registry.apply(owner, state(AgentStatus.Online, seq = 13), 0L, ProcessPresenceTransportEpoch("family-1", epoch = 2))
        registry.apply(owner, state(AgentStatus.Online, seq = 14), 0L, epoch(1))
        // A frame that names no connection while one is live.
        registry.apply(owner, state(AgentStatus.Online, seq = 15), 0L, transport = null)
        // A write under a retired auth binding.
        registry.apply(owner, state(AgentStatus.Online, seq = 16), writeGeneration = 7L, transport = epoch(2))

        assertEquals(
            listOf(
                "presence_write_rejected reason=equal_seq serverId=server-a memberId=agent-ca kind=state seq=12 prevSeq=12 afterProjection=false",
                "presence_write_rejected reason=stale_seq serverId=server-a memberId=agent-ca kind=activity seq=11 prevSeq=12 afterProjection=false",
                "presence_write_rejected reason=older_epoch serverId=server-a memberId=agent-ca kind=state seq=14 prevSeq=13 " +
                    "epoch=1 currentEpoch=2 sameFamily=true",
                "presence_write_rejected reason=missing_epoch serverId=server-a memberId=agent-ca kind=state seq=15 prevSeq=13 " +
                    "epoch=none currentEpoch=2 sameFamily=false",
                "presence_write_rejected reason=retired_generation serverId=server-a memberId=agent-ca kind=state seq=16 prevSeq=none " +
                    "writeGen=7 currentGen=0",
            ),
            lines
        )
        // The epoch-2 frame was applied; nothing refused changed the fact.
        assertEquals(13L, registry.factFor(owner, agentId)?.serverSeq)
        assertEquals(AgentStatus.Online, registry.factFor(owner, agentId)?.agentStatus)
    }

    @Test
    fun seqLessWriteOverASequencedFactAndBlankMemberAreLogged() {
        val lines = mutableListOf<String>()
        val registry = ProcessMemberPresenceRegistry(rejectLogger = lines::add)
        registry.apply(owner, activity(AgentStatus.Working, seq = 3), 0L)
        registry.apply(
            owner,
            MemberPresenceUpdated(memberId = MemberId(agentId), presence = Presence.Active, kind = MemberKind.Agent, agentStatus = AgentStatus.Online),
            0L
        )
        registry.apply(owner, activity(AgentStatus.Online, seq = 4).copy(memberId = MemberId("  ")), 0L)

        assertEquals(2, lines.size)
        assertTrue(lines[0].startsWith("presence_write_rejected reason=seqless_after_seq serverId=server-a memberId=agent-ca kind=seqless seq=none prevSeq=3"), lines[0])
        assertTrue(lines[1].startsWith("presence_write_rejected reason=no_member serverId=server-a memberId=none"), lines[1])
        assertEquals(AgentStatus.Working, registry.factFor(owner, agentId)?.agentStatus)
    }

    @Test
    fun aThrowingLoggerNeverBreaksTheWrite() {
        val registry = ProcessMemberPresenceRegistry(rejectLogger = { error("logger down") })
        registry.apply(owner, state(AgentStatus.Working, seq = 2), 0L)
        registry.apply(owner, state(AgentStatus.Online, seq = 1), 0L)
        registry.apply(owner, state(AgentStatus.Online, seq = 3), 0L)
        assertEquals(AgentStatus.Online, registry.factFor(owner, agentId)?.agentStatus)
    }

    @Test
    fun rejectedLinesReachTheFeedbackLogUpToTwentyAMinuteThenAreCounted() {
        val policy = RaftDiagnosticsVolumePolicy()
        val line = "presence_write_rejected reason=equal_seq serverId=server-a memberId=agent-ca kind=state seq=12 prevSeq=12"
        val persisted = (0 until 25).count { i -> policy.admit("info", "SlockPresence", line, nowMillis = i * 100L, verbose = false).persist }
        assertEquals(20, persisted)
        val summary = policy.drain(10_000L).single()
        assertTrue(summary.contains("presence_write_rejected=5/25"), summary)
    }
}
