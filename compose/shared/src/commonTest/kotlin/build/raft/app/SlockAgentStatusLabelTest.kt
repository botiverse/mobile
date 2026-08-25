package build.raft.app

import build.raft.app.model.AgentStatus
import build.raft.app.model.Presence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SlockAgentStatusLabelTest {
    @Test
    fun indicatorStateDerivesLabelFromSameResolvedStatusAsColorAndPulse() {
        val working = slockAgentStatusIndicatorState(AgentStatus.Working, Presence.Active)
        assertEquals("Working", working.label)
        assertEquals(SlockColor.BrandYellow, working.color)
        assertTrue(working.pulsing)

        val online = slockAgentStatusIndicatorState(AgentStatus.Online, Presence.Active)
        assertEquals("Online", online.label)
        assertEquals(SlockColor.AccentLime, online.color)
        assertFalse(online.pulsing)

        val presenceOnly = slockAgentStatusIndicatorState(status = null, presence = Presence.Away)
        assertEquals("Away", presenceOnly.label)
        assertEquals(SlockColor.BrandYellow, presenceOnly.color)
    }

    @Test
    fun staleSummaryDetailCannotCaptionALiveDotWithADifferentState() {
        // Live store says Online (green, no pulse); stale summary still says
        // Working with free-text detail. The label must follow the live dot.
        val live = slockAgentStatusIndicatorState(AgentStatus.Online, Presence.Active)

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
        val live = slockAgentStatusIndicatorState(AgentStatus.Working, Presence.Active)

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
        assertEquals(SlockColor.StatusNeutralGray, unknown.color)
        assertFalse(unknown.pulsing)
    }

    @Test
    fun fixtureStatusFallbackIsExplicitAndCanonicalStateStillWins() {
        val fixture = slockAgentStatusIndicatorState(AgentStatus.Online, Presence.Active)
        val canonical = slockAgentStatusIndicatorState(AgentStatus.Working, Presence.Active)

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
        val state = slockAgentStatusIndicatorState(AgentStatus.Working, Presence.Active)
        assertEquals(SlockColor.BrandYellow, state.color)

        val error = slockAgentStatusIndicatorState(AgentStatus.Error, Presence.Offline)
        assertEquals(SlockColor.AccentOrange, error.color)
    }
}
