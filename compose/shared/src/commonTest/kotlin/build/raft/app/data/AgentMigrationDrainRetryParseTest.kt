package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `GET /agents/:id/migration` fields of botiverse/slock staging 0e8b4fab: `sourceDrain`
 * (routes/agents.ts `sourceDrainOf`, shared `AgentMigrationSourceDrain`, #14221) and the agent's
 * own retry (`retryOfMigrationId`, `triggeredByAgentId`, `retryComparison` from
 * services/agentMigrationRetry.ts, #14275), read through the shared parser.
 */
class AgentMigrationDrainRetryParseTest {
    private fun status(extra: String) =
        """{"migration":{"migrationRef":"mig_retry","agentId":"agent-1","state":"provisioning","revision":3,""" +
            """"sourceMachineId":"source-machine","targetKind":"computer","targetMachineId":"target-machine",""" +
            """"sourceQuiescedAt":null,"sourceBuildProgress":null,$extra}}"""

    @Test
    fun aDrainingSourceReadsWhatItWaitsForAndUntilWhen() {
        val notice =
            parseSharedAgentMigrationStatus(
                status(""""sourceDrain":{"until":"2026-11-11T08:05:00.000Z","waitingFor":"background_tasks","reportedAt":"2026-11-11T07:59:30.000Z"},"retryOfMigrationId":null,"triggeredByAgentId":null,"retryComparison":null"""),
            )!!.migration!!
        assertEquals(AgentMigrationSourceDrain("2026-11-11T08:05:00.000Z", "background_tasks", "2026-11-11T07:59:30.000Z"), notice.sourceDrain)
        assertEquals(AgentMigrationSourceDrainWaitingFor.BackgroundTasks, AgentMigrationSourceDrainWaitingFor.of(notice.sourceDrain?.waitingFor))
        assertNull(notice.retryComparison)
        // Null and anything outside the fixed set read as `other` (web `waitingFor ?? "other"`; the server folds unknowns).
        assertEquals(AgentMigrationSourceDrainWaitingFor.Other, AgentMigrationSourceDrainWaitingFor.of(null))
        assertEquals(AgentMigrationSourceDrainWaitingFor.Other, AgentMigrationSourceDrainWaitingFor.of("compacting"))
        // An older server without the field.
        assertNull(parseSharedAgentMigrationStatus(status(""""prepDeadlineAt":null"""))!!.migration!!.sourceDrain)
    }

    @Test
    fun anAgentsRetryReadsTheFailedAttemptNextToThisOne() {
        val notice =
            parseSharedAgentMigrationStatus(
                status(
                    """"sourceDrain":null,"retryOfMigrationId":"0f1e2d3c-4b5a-4968-8776-655443322110","triggeredByAgentId":"agent-1",""" +
                        """"retryComparison":{"previousFailure":{"code":"MIGRATION_OBJECT_STORE_ENTRY_COUNT_LIMIT_EXCEEDED","entries":48210,"bytes":null,"bytesIsLowerBound":false},""" +
                        """"packed":{"files":1203,"bytes":851443712,"final":false}}""",
                ),
            )!!.migration!!
        assertEquals("0f1e2d3c-4b5a-4968-8776-655443322110", notice.retryOfMigrationId)
        assertEquals("agent-1", notice.triggeredByAgentId)
        assertEquals(
            AgentMigrationRetryComparison(
                AgentMigrationRetryPreviousFailure("MIGRATION_OBJECT_STORE_ENTRY_COUNT_LIMIT_EXCEEDED", entries = 48_210, bytes = null, bytesIsLowerBound = false),
                AgentMigrationRetryPacked(files = 1203, bytes = 851_443_712, final = false),
            ),
            notice.retryComparison,
        )
        // Nothing measured on either side.
        assertEquals(
            AgentMigrationRetryComparison(null, null),
            parseSharedAgentMigrationStatus(status(""""retryOfMigrationId":"x","retryComparison":{"previousFailure":null,"packed":null}"""))!!.migration!!.retryComparison,
        )
    }
}
