package build.raft.app

import build.raft.app.members.membersHeroAgentStatusLabel
import build.raft.app.members.membersLiveAgentStatusLabel
import build.raft.app.model.AgentStatus
import build.raft.app.model.Presence
import kotlin.test.Test
import kotlin.test.assertEquals

class RaftAgentStatusLabelTest {
    @Test
    fun indicatorStateDerivesLabelFromSameResolvedStatusAsColor() {
        val working = raftAgentStatusIndicatorState(AgentStatus.Working, Presence.Active, external = false)
        assertEquals("Working", working.label)
        assertEquals(RaftColor.BrandYellow, working.color)

        val online = raftAgentStatusIndicatorState(AgentStatus.Online, Presence.Active, external = false)
        assertEquals("Online", online.label)
        assertEquals(RaftColor.AccentLime, online.color)

        val presenceOnly = raftAgentStatusIndicatorState(status = null, presence = Presence.Away, external = false)
        assertEquals("Away", presenceOnly.label)
        assertEquals(RaftColor.BrandYellow, presenceOnly.color)
    }

    @Test
    fun membersTabCoarseStateDropsTheDetailedActivityText() {
        // The Members tab does not subscribe to detailed agent:activity, so a last label could be stale.
        val detailed =
            raftAgentStatusIndicatorState(AgentStatus.Working, Presence.Active, statusLabel = "Running bash", external = false)
                .copy(detailKind = "tool_start", activityToolName = "bash")
        val coarse = detailed.withoutActivityDetail()
        assertEquals("Working", coarse.label)
        assertEquals(detailed.color, coarse.color)
        assertEquals(AgentStatus.Working, coarse.status)
        assertEquals(null, coarse.detailKind)
        assertEquals(null, coarse.activityToolName)
        assertEquals(null, coarse.toolStuck)
        // External agents keep their caption.
        val external = raftAgentStatusIndicatorState(AgentStatus.Working, Presence.Active, external = true)
        assertEquals(ExternalAgentStatusLabel, external.withoutActivityDetail().label)
    }

    @Test
    fun profileHeroChipCarriesTheActivityTextForAPrivateViewer() {
        // Web AgentStatusBadge showDetail: the live activity text ("Capturing …"), not "Working".
        val live =
            raftAgentStatusIndicatorState(
                AgentStatus.Working,
                Presence.Active,
                statusLabel = "Capturing deterministic profile state",
                external = false
            )
        assertEquals(
            "Capturing deterministic profile state",
            membersHeroAgentStatusLabel(live, "active", "working", "Older summary detail", showActivityDetail = true)
        )
        // No live detail: the summary's detail while it agrees with the live status.
        val coarse = raftAgentStatusIndicatorState(AgentStatus.Working, Presence.Active, external = false)
        assertEquals(
            "Summary detail",
            membersHeroAgentStatusLabel(coarse, "active", "working", "Summary detail", showActivityDetail = true)
        )
        // A viewer without private surfaces sees the coarse status words only.
        assertEquals(
            "Working",
            membersHeroAgentStatusLabel(live, "active", "working", "Summary detail", showActivityDetail = false)
        )
        // A bare status word on the live state is not an activity text.
        val idleWord =
            raftAgentStatusIndicatorState(AgentStatus.Idle, Presence.Active, statusLabel = "online", external = false)
        assertEquals("online", membersHeroAgentStatusLabel(idleWord, null, null, null, showActivityDetail = true))
    }

    @Test
    fun staleSummaryDetailCannotCaptionALiveDotWithADifferentState() {
        // Live store says Online (green); stale summary still says
        // Working with free-text detail. The label must follow the live dot.
        val live = raftAgentStatusIndicatorState(AgentStatus.Online, Presence.Active, external = false)

        val label =
            membersLiveAgentStatusLabel(
                indicatorState = live,
                summaryStatus = "working",
                summaryActivity = "working",
                summaryActivityDetail = "Running tests",
                fallback = "Agent"
            )

        assertEquals("Online", label)
    }

    @Test
    fun agentStatusBeatsRawPresenceInColorMapping() {
        // A Working agent whose presence maps Active must render yellow via
        // the shared resolver on every surface (settings picker regression).
        val state = raftAgentStatusIndicatorState(AgentStatus.Working, Presence.Active, external = false)
        assertEquals(RaftColor.BrandYellow, state.color)

        val error = raftAgentStatusIndicatorState(AgentStatus.Error, Presence.Offline, external = false)
        assertEquals(RaftColor.AccentOrange, error.color)
    }

    @Test
    fun externalDotAndCaptionMoveTogether() {
        // task #1119: a cyan external dot must never sit beside a managed caption
        // such as "Online" or "Working" (the invariant documented on the mapping).
        val external =
            raftAgentStatusIndicatorState(
                status = AgentStatus.Online,
                presence = Presence.Active,
                statusLabel = "Online",
                external = true
            )
        assertEquals(ExternalAgentStatusLabel, external.label)
        assertEquals(RaftColor.AccentCyan, external.color)
    }
}
