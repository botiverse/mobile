package build.raft.app

import build.raft.app.model.AgentStatus
import build.raft.app.model.Presence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RaftAgentStatusLabelTest {
    @Test
    fun indicatorStateDerivesLabelFromSameResolvedStatusAsColorAndPulse() {
        val working = raftAgentStatusIndicatorState(AgentStatus.Working, Presence.Active, external = false)
        assertEquals("Working", working.label)
        assertEquals(RaftColor.BrandYellow, working.color)
        assertTrue(working.pulsing)

        val online = raftAgentStatusIndicatorState(AgentStatus.Online, Presence.Active, external = false)
        assertEquals("Online", online.label)
        assertEquals(RaftColor.AccentLime, online.color)
        assertFalse(online.pulsing)

        val presenceOnly = raftAgentStatusIndicatorState(status = null, presence = Presence.Away, external = false)
        assertEquals("Away", presenceOnly.label)
        assertEquals(RaftColor.BrandYellow, presenceOnly.color)
    }

    @Test
    fun staleSummaryDetailCannotCaptionALiveDotWithADifferentState() {
        // Live store says Online (green, no pulse); stale summary still says
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
    fun agreeingSummaryKeepsRicherActivityDetailText() {
        val live = raftAgentStatusIndicatorState(AgentStatus.Working, Presence.Active, external = false)

        val label =
            membersLiveAgentStatusLabel(
                indicatorState = live,
                summaryStatus = "working",
                summaryActivity = "working",
                summaryActivityDetail = "Running tests",
                fallback = "Agent"
            )

        assertEquals("Running tests", label)
    }

    @Test
    fun missingLiveStateFallsBackToSummaryChain() {
        val label =
            membersLiveAgentStatusLabel(
                indicatorState = null,
                summaryStatus = null,
                summaryActivity = "thinking",
                summaryActivityDetail = null,
                fallback = "Agent"
            )

        assertEquals("Thinking", label)

        val fallbackOnly =
            membersLiveAgentStatusLabel(
                indicatorState = null,
                summaryStatus = null,
                summaryActivity = null,
                summaryActivityDetail = null,
                fallback = "Agent"
            )

        assertEquals("Agent", fallbackOnly)
    }

    @Test
    fun explicitCanonicalUnknownCannotBeCaptionedByStaleSummaryActivity() {
        val unknown =
            resolveMembersAgentStatusIndicatorState(
                canonicalState = null,
                fixtureFallback = null,
                fixtureStatusFallbackEnabled = false
            )

        assertEquals(
            "—",
            membersLiveAgentStatusLabel(
                indicatorState = unknown,
                summaryStatus = "active",
                summaryActivity = "working",
                summaryActivityDetail = "Running stale work",
                fallback = "Agent"
            )
        )
        assertEquals(null, unknown.status)
        assertEquals(RaftColor.StatusNeutralGray, unknown.color)
        assertFalse(unknown.pulsing)
    }

    @Test
    fun observedResolutionIsNullWhenOnlyTheUnknownPlaceholderWouldApply() {
        // The dot must come from this function, not from the label-facing one: that one
        // substitutes the unknown placeholder, whose colour maps through Presence.Offline to
        // StatusNeutralGray, so an unknown agent would paint the same dot as an offline one
        // (task #256).
        assertEquals(
            null,
            resolveMembersAgentStatusIndicatorStateOrNull(
                canonicalState = null,
                fixtureFallback = null,
                fixtureStatusFallbackEnabled = false
            )
        )
        // ...while the label-facing resolution still yields a non-null placeholder, because an
        // explicit unknown has to outrank a stale summary caption.
        assertEquals(
            RaftColor.StatusNeutralGray,
            resolveMembersAgentStatusIndicatorState(
                canonicalState = null,
                fixtureFallback = null,
                fixtureStatusFallbackEnabled = false
            ).color
        )

        val fixture = raftAgentStatusIndicatorState(AgentStatus.Online, Presence.Active, external = false)
        val canonical = raftAgentStatusIndicatorState(AgentStatus.Working, Presence.Active, external = false)
        // A disabled fixture fallback is not an observation.
        assertEquals(
            null,
            resolveMembersAgentStatusIndicatorStateOrNull(
                canonicalState = null,
                fixtureFallback = fixture,
                fixtureStatusFallbackEnabled = false
            )
        )
        // An enabled one is: visual fixtures must still paint their dot.
        assertEquals(
            AgentStatus.Online,
            resolveMembersAgentStatusIndicatorStateOrNull(
                canonicalState = null,
                fixtureFallback = fixture,
                fixtureStatusFallbackEnabled = true
            )?.status
        )
        assertEquals(
            AgentStatus.Working,
            resolveMembersAgentStatusIndicatorStateOrNull(
                canonicalState = canonical,
                fixtureFallback = fixture,
                fixtureStatusFallbackEnabled = true
            )?.status
        )
    }

    @Test
    fun fixtureStatusFallbackIsExplicitAndCanonicalStateStillWins() {
        val fixture = raftAgentStatusIndicatorState(AgentStatus.Online, Presence.Active, external = false)
        val canonical = raftAgentStatusIndicatorState(AgentStatus.Working, Presence.Active, external = false)

        assertEquals(
            null,
            resolveMembersAgentStatusIndicatorState(
                canonicalState = null,
                fixtureFallback = fixture,
                fixtureStatusFallbackEnabled = false
            ).status
        )
        assertEquals(
            AgentStatus.Online,
            resolveMembersAgentStatusIndicatorState(
                canonicalState = null,
                fixtureFallback = fixture,
                fixtureStatusFallbackEnabled = true
            ).status
        )
        assertEquals(
            AgentStatus.Working,
            resolveMembersAgentStatusIndicatorState(
                canonicalState = canonical,
                fixtureFallback = fixture,
                fixtureStatusFallbackEnabled = true
            ).status
        )
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
        assertFalse(external.pulsing)
    }
}
