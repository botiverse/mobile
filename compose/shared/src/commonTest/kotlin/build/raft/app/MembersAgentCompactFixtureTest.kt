package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MembersAgentCompactFixtureTest {
    @Test
    fun fourNamedScenariosSelectClosedConsumerStates() {
        val scenarios = MembersAgentCompactFixtureScenario.entries
        assertEquals(7, scenarios.size)
        assertTrue(scenarios.single { it.wireId == "compact_supported_idle" }.capabilityEnabled)
        assertTrue(scenarios.single { it.wireId == "compact_supported_in_flight" }.requestInFlight)
        assertFalse(scenarios.single { it.wireId == "compact_unsupported" }.capabilityEnabled)
        assertTrue(scenarios.single { it.wireId == "compact_success_manual_resend" }.success)
    }

    @Test
    fun unknownAndMissingScenarioIdsFailClosed() {
        assertNull(membersAgentCompactFixtureScenarioOrNull(null))
        assertNull(membersAgentCompactFixtureScenarioOrNull(""))
        assertNull(membersAgentCompactFixtureScenarioOrNull("compact_typo"))
        assertNotNull(membersAgentCompactFixtureScenarioOrNull("compact_supported_idle"))
    }

    @Test
    fun missingNullAndFalseCapabilityEncodingsAreDistinctHiddenCases() {
        listOf(
            "compact_missing_capability",
            "compact_null_capability",
            "compact_false_capability"
        ).forEach { id ->
            val scenario = membersAgentCompactFixtureScenarioOrNull(id)
            assertNotNull(scenario)
            assertFalse(scenario.capabilityEnabled)
        }
    }

    @Test
    fun fixtureActionsNeverDispatchControl() {
        MembersAgentCompactFixtureScenario.entries.forEach {
            val actions = MembersAgentCompactFixtureActions()
            assertEquals(0, actions.controlDispatchCount, it.wireId)
        }
    }
}
