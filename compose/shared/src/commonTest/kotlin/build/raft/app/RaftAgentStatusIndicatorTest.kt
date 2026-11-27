package build.raft.app

import build.raft.app.data.NeutralAgentStatusLabel
import build.raft.app.data.SharedServerMemberStatus
import build.raft.app.model.AgentAttention
import build.raft.app.model.AgentReadiness
import build.raft.app.model.AgentReadinessFact
import build.raft.app.model.AgentStatus
import build.raft.app.model.MemberKind
import build.raft.app.model.Presence
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RaftAgentStatusIndicatorTest {
    @Test
    fun workingStatusUsesCanonicalWorkingIndicator() {
        val state =
            raftAgentStatusIndicatorState(
                status = AgentStatus.Working,
                presence = Presence.Offline,
                external = false
            )

        assertEquals(AgentStatus.Working, state.status)
        assertEquals(RaftColor.BrandYellow, state.color)
    }

    @Test
    fun missingServerScopeReturnsNeutralResolver() = runBlocking {
        assertEquals(null, raftAgentStatusResolver(null, null)("agent-1").first())
        assertEquals(null, raftScopedAgentStatusResolver(null, "")("agent-1").first())
    }

    @Test
    fun homeDmAndSearchConsumersKeepUnknownAbsentUntilAuthoritativeResolverValueExists() {
        assertEquals(null, projectHomeAgentStatusIndicatorState(true, "agent-1", null))

        val ready = raftAgentStatusIndicatorState(AgentStatus.Offline, Presence.Offline, external = false)
        assertEquals(ready, projectHomeAgentStatusIndicatorState(true, "agent-1", ready))
        assertEquals(null, projectHomeAgentStatusIndicatorState(false, "agent-1", ready))
        assertEquals(null, projectHomeAgentStatusIndicatorState(true, "", ready))
    }

    // task #1119: external agents have no managed liveness. React StatusDot renders
    // `external` unconditionally as the cyan token; the mobile mapping must do the same
    // for every managed status, and color / label must move together.
    @Test
    fun externalAgentRendersExternalTokenForEveryManagedStatus() {
        val inputs: List<AgentStatus?> = AgentStatus.values().toList() + null
        inputs.forEach { status ->
            val state =
                raftAgentStatusIndicatorState(
                    status = status,
                    presence = Presence.Active,
                    external = true
                )
            assertEquals(status, state.status, "status is preserved for $status")
            assertEquals(RaftColor.AccentCyan, state.color, "external color for $status")
            assertEquals(ExternalAgentStatusLabel, state.label, "external label for $status")
            assertTrue(state.external)
        }
    }

    @Test
    fun hasKnownPresenceDotOmitsNeutralUnknownSentinelAndKeepsKnownStatuses() {
        val neutral =
            SharedServerMemberStatus(
                status = null,
                presence = Presence.Offline,
                statusLabel = NeutralAgentStatusLabel,
                external = false
            ).toRaftAgentStatusIndicatorState()
        assertFalse(neutral.hasKnownPresenceDot(), "neutral unknown sentinel must not paint an offline dot")
        assertNull(neutral.knownPresenceDotOrNull())

        val offline = raftAgentStatusIndicatorState(AgentStatus.Offline, Presence.Offline, external = false)
        assertTrue(offline.hasKnownPresenceDot(), "known offline agent keeps its gray dot")

        val online = raftAgentStatusIndicatorState(AgentStatus.Online, Presence.Active, external = false)
        assertTrue(online.hasKnownPresenceDot())

        val working = raftAgentStatusIndicatorState(AgentStatus.Working, Presence.Active, external = false)
        assertTrue(working.hasKnownPresenceDot())

        val likelyOnline =
            SharedServerMemberStatus(
                status = null,
                presence = Presence.Active,
                statusLabel = "Likely online",
                external = false,
                likelyOnline = true
            ).toRaftAgentStatusIndicatorState()
        assertTrue(likelyOnline.hasKnownPresenceDot())

        val external = raftAgentStatusIndicatorState(null, Presence.Offline, external = true)
        assertTrue(external.hasKnownPresenceDot())

        val externalSeenBeforeProfile =
            SharedServerMemberStatus(
                status = null,
                presence = Presence.Offline,
                statusLabel = NeutralAgentStatusLabel,
                external = false,
                lastSeenAt = "2026-11-27T18:00:00Z"
            ).toRaftAgentStatusIndicatorState()
        assertTrue(externalSeenBeforeProfile.hasKnownPresenceDot())

        val withAttention =
            neutral.copy(
                attention =
                    AgentAttention.Readiness(
                        readiness = AgentReadiness(state = "blocked", primary = AgentReadinessFact(code = "runtime_not_installed")),
                        runtimeId = "cursor",
                    )
            )
        assertTrue(withAttention.hasKnownPresenceDot())
    }

    @Test
    fun resolveComposerMentionAgentStatusIndicatorStateOmitsUnknownMutedAppBotAndHumans() {
        val neutral =
            SharedServerMemberStatus(
                status = null,
                presence = Presence.Offline,
                statusLabel = NeutralAgentStatusLabel,
                external = false
            ).toRaftAgentStatusIndicatorState()
        val working =
            raftAgentStatusIndicatorState(AgentStatus.Working, Presence.Active, external = false)
                .copy(runningWorkers = 3, runningWorkersPresenceOnly = false)

        assertNull(resolveComposerMentionAgentStatusIndicatorState(true, MemberKind.Agent, false, false, null))
        assertNull(resolveComposerMentionAgentStatusIndicatorState(true, MemberKind.Agent, false, false, neutral))
        assertNull(resolveComposerMentionAgentStatusIndicatorState(false, MemberKind.Agent, false, false, working))
        assertNull(resolveComposerMentionAgentStatusIndicatorState(true, MemberKind.Agent, true, false, working))
        assertNull(resolveComposerMentionAgentStatusIndicatorState(true, MemberKind.Human, false, false, working))

        val local = resolveComposerMentionAgentStatusIndicatorState(true, MemberKind.Agent, false, false, working)
        assertEquals(working, local)

        val remote = resolveComposerMentionAgentStatusIndicatorState(true, MemberKind.Agent, false, true, working)
        assertEquals(1, remote?.runningWorkers)
        assertEquals(true, remote?.runningWorkersPresenceOnly)
    }
}
