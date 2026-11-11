package build.raft.app

import build.raft.app.data.AgentMigrationInitiator
import build.raft.app.data.AgentMigrationNotice
import build.raft.app.data.AgentMigrationRetryComparison
import build.raft.app.data.AgentMigrationRetryPacked
import build.raft.app.data.AgentMigrationRetryPreviousFailure
import build.raft.app.data.AgentMigrationSourceBuildProgress
import build.raft.app.data.AgentMigrationSourceDrain
import build.raft.app.data.RaftProGateDecision

/**
 * Migrations as botiverse/slock staging 0e8b4fab's owner status reports them, after the web's
 * behaviour tests (`migrationSourceDrain.behavior.test.tsx`, `agentMigrationRetry.behavior.test.tsx`):
 * a source waiting for its agent before it stops it (#14221/#14230), and the agent's own retry of a
 * migration that failed for size (#14275), in progress and failed again.
 */
internal object AgentMigrationDrainRetryFixtures {
    const val AGENT = "agent-1"
    const val VIEWER = "owner-user"
    private const val MIB = 1024L * 1024L
    private const val GIB = 1024L * MIB

    /** 2026-11-11T08:00:00Z: the drain below runs until 08:05. */
    const val NOW = 1_794_384_000_000L

    val computers =
        listOf(
            AgentMigrationComputer("source-machine", "Studio Mac", isComputer = true, online = true),
            AgentMigrationComputer("target-machine", "Build box", isComputer = true, online = true),
        )

    fun inputs(viewer: String? = VIEWER) =
        AgentMigrationSectionInputs(
            sourceMachineId = "source-machine",
            computers = computers,
            hostedAvailable = false,
            proGate = RaftProGateDecision.Allowed,
            proGateChecking = false,
            onIntent = {},
            onOpenBilling = {},
            onCopy = {},
            formatTimestamp = { "Nov 11, 08:12" },
            onAgentPlacementChanged = {},
            agentName = "Migration Agent",
            viewerUserId = viewer,
            // The viewer's clock: the wire time's HH:mm (UTC here).
            formatClock = { value -> value.substringAfter('T').take(5) },
            nowMillis = { NOW },
        )

    private fun notice(state: String, sourceQuiescedAt: String? = "2026-11-11T08:04:00.000Z") =
        AgentMigrationNotice(
            agentId = AGENT,
            migrationRef = "mig_retry",
            state = state,
            revision = 3,
            sourceMachineId = "source-machine",
            targetKind = "computer",
            targetMachineId = "target-machine",
            sourceQuiescedAt = sourceQuiescedAt,
            initiatedBy = AgentMigrationInitiator(VIEWER, "owner", "Owner"),
            createdAt = "2026-11-11T07:59:00.000Z",
            updatedAt = "2026-11-11T08:00:00.000Z",
        )

    fun draining(waitingFor: String? = "turn", until: String = "2026-11-11T08:05:00.000Z") =
        notice("provisioning", sourceQuiescedAt = null)
            .copy(sourceDrain = AgentMigrationSourceDrain(until = until, waitingFor = waitingFor, reportedAt = "2026-11-11T07:59:30.000Z"))

    private val tooLarge = AgentMigrationRetryPreviousFailure("MIGRATION_OBJECT_STORE_BUNDLE_TOO_LARGE", entries = null, bytes = Math.round(10.4 * GIB), bytesIsLowerBound = true)
    private val tooManyEntries = AgentMigrationRetryPreviousFailure("MIGRATION_OBJECT_STORE_ENTRY_COUNT_LIMIT_EXCEEDED", entries = 48_210, bytes = null)

    /** Packing again after a too-large bundle: only the failed attempt's size shows. */
    val retryPacking =
        notice("provisioning").copy(
            sourceBuildProgress = AgentMigrationSourceBuildProgress("packing", files = 1203, bytes = 812 * MIB),
            retryOfMigrationId = "0f1e2d3c-4b5a-4968-8776-655443322110",
            triggeredByAgentId = AGENT,
            retryComparison = AgentMigrationRetryComparison(tooLarge, AgentMigrationRetryPacked(files = 1203, bytes = 812 * MIB, final = false)),
        )

    /** The retry of a too-many-files move stopped too: what it had packed then, and the next move is a person's. */
    val retryFailed =
        notice("failed").copy(
            transportErrorCode = "TARGET_OFFLINE",
            abortedAt = "2026-11-11T08:20:00.000Z",
            retryOfMigrationId = "0f1e2d3c-4b5a-4968-8776-655443322110",
            triggeredByAgentId = AGENT,
            retryComparison = AgentMigrationRetryComparison(tooManyEntries, AgentMigrationRetryPacked(files = 31_002, bytes = 3 * GIB, final = false)),
        )
}
