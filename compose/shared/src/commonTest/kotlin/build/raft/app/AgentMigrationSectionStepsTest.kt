package build.raft.app

import build.raft.app.data.AgentMigrationHostedReview
import build.raft.app.data.AgentMigrationNotice
import build.raft.app.data.RaftProGateDecision
import build.raft.designsystem.components.RaftStepStatus
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The migration progress Steps (web MigrationProgressPanel -> raft-ui Steps): which step is
 * current, its status, and that the step the move is working on spins (a still screenshot
 * can't show it). States and timestamps follow botiverse/slock `serializeOwnerMigrationStatus`.
 */
class AgentMigrationSectionStepsTest {
    private fun notice(
        state: String,
        targetKind: String = "computer",
        sourceQuiescedAt: String? = null,
        cancelNeedsAttention: Boolean = false,
        hosted: AgentMigrationHostedReview? = null
    ) = AgentMigrationNotice(
        agentId = "agent-1",
        migrationRef = "mig_AbCdEfGhIjKlMnOpQrStUv",
        state = state,
        revision = 3,
        sourceMachineId = "m-source",
        targetKind = targetKind,
        targetMachineId = if (targetKind == "hosted") null else "m-target",
        sourceQuiescedAt = sourceQuiescedAt,
        cancelNeedsAttention = cancelNeedsAttention,
        hosted = hosted
    )

    private fun markers(notice: AgentMigrationNotice): List<AgentMigrationStepMarker> {
        val model = agentMigrationStepsModel(notice)
        return (1..5).map(model::marker)
    }

    @Test
    fun theStepInProgressSpinsAndNoOtherDoes() {
        // Packing: the agent stopped (sourceQuiescedAt), so step 2 "Pack workspace" runs.
        val packing = notice("provisioning", sourceQuiescedAt = "2026-10-21T08:00:05.000Z")
        val model = agentMigrationStepsModel(packing)
        assertEquals(2, model.current)
        assertEquals(RaftStepStatus.Process, model.status)
        assertEquals(
            listOf(
                AgentMigrationStepMarker.Number,
                AgentMigrationStepMarker.Spinner,
                AgentMigrationStepMarker.Number,
                AgentMigrationStepMarker.Number,
                AgentMigrationStepMarker.Number
            ),
            markers(packing)
        )
    }

    @Test
    fun anEndedOrStuckMoveHasNoSpinner() {
        data class Case(val notice: AgentMigrationNotice, val current: Int, val status: RaftStepStatus)
        listOf(
            Case(notice("failed", sourceQuiescedAt = "2026-10-21T08:00:05.000Z"), 2, RaftStepStatus.Error),
            Case(notice("canceled_pre_flip", sourceQuiescedAt = "2026-10-21T08:00:05.000Z"), 2, RaftStepStatus.Wait),
            Case(
                notice("canceled_pre_flip", sourceQuiescedAt = "2026-10-21T08:00:05.000Z", cancelNeedsAttention = true),
                2,
                RaftStepStatus.Error
            ),
            Case(notice("completed"), 5, RaftStepStatus.Finish)
        ).forEach { (notice, current, status) ->
            val model = agentMigrationStepsModel(notice)
            assertEquals(current, model.current, notice.state)
            assertEquals(status, model.status, notice.state)
            assertEquals(List(5) { AgentMigrationStepMarker.Number }, markers(notice), notice.state)
        }
    }

    @Test
    fun aCloudMoveWaitingForReviewShowsItsNumber() {
        val review =
            notice(
                "in_transit",
                targetKind = "hosted",
                hosted = AgentMigrationHostedReview(reviewReadyAt = "2026-10-21T08:05:00.000Z")
            )
        val model = agentMigrationStepsModel(review)
        assertEquals(3, model.current)
        assertEquals(RaftStepStatus.Process, model.status)
        assertEquals(List(5) { AgentMigrationStepMarker.Number }, markers(review))
    }

    @Test
    fun thePickerFallsBackLikeTheWebDialog() {
        val computers =
            listOf(
                AgentMigrationComputer("m-source", "artin-debian-agent", isComputer = true),
                AgentMigrationComputer("m-a", "Maria", isComputer = true),
                AgentMigrationComputer("m-b", "Cindy MacAir", isComputer = true),
                AgentMigrationComputer("m-hosted", "Cloud runner", isComputer = false)
            )
        val targets = agentMigrationTargets(inputs(computers))
        assertEquals(listOf("m-a", "m-b"), targets.map { it.id })
        assertEquals("m-b", agentMigrationResolvedTarget("m-b", targets, hostedAvailable = false))
        // A target no longer offered falls back to the first computer.
        assertEquals("m-a", agentMigrationResolvedTarget("m-gone", targets, hostedAvailable = true))
        assertEquals(HOSTED_TARGET_CHOICE, agentMigrationResolvedTarget(HOSTED_TARGET_CHOICE, targets, hostedAvailable = true))
        // No other computer: the cloud when offered, else nothing to start.
        assertEquals(HOSTED_TARGET_CHOICE, agentMigrationResolvedTarget(null, emptyList(), hostedAvailable = true))
        assertEquals(null, agentMigrationResolvedTarget(null, emptyList(), hostedAvailable = false))
    }

    private fun inputs(computers: List<AgentMigrationComputer>) =
        AgentMigrationSectionInputs(
            sourceMachineId = "m-source",
            computers = computers,
            hostedAvailable = false,
            proGate = RaftProGateDecision.Allowed,
            proGateChecking = false,
            onIntent = {},
            onOpenBilling = {},
            onCopy = {},
            formatTimestamp = { null },
            onAgentPlacementChanged = {}
        )
}
