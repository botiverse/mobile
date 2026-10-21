package build.raft.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Agent migration (move an agent to another computer or to the cloud).
// botiverse/slock 7d349b509d6511eb922f7870099017fdda0d3f61:
//  - routes/agents.ts: GET /agents/:id/migration (serializeOwnerMigrationStatus
//    + `hosted` from getHostedMigrationReview), POST /agents/:id/migrate,
//    POST /agents/:id/migration/cancel, POST /agents/:id/migration/confirm-hosted
//  - shared/src/agentMigration.ts: AGENT_MIGRATION_STATES, progress types
//  - web components/agentMigration/{state,realtime,presentation}.ts
private val migrationJson = Json { ignoreUnknownKeys = true }
private val migrationRequestJson = Json { explicitNulls = false }

@Serializable
internal data class AgentMigrationSourceBuildProgress(
    val phase: String? = null,
    val files: Long = 0,
    val bytes: Long = 0
)

@Serializable
internal data class AgentMigrationTransferProgress(
    val receivedBytes: Long = 0,
    val totalBytes: Long = 0,
    val receivedChunks: Long = 0,
    val totalChunks: Long = 0
)

@Serializable
internal data class AgentMigrationHostedSkipGroup(
    val reason: String,
    val count: Int = 0,
    val bytes: Long = 0,
    val largestPaths: List<String> = emptyList()
)

@Serializable
internal data class AgentMigrationHostedImportSummary(
    val importedFileCount: Int = 0,
    val importedBytes: Long = 0,
    val skipped: List<AgentMigrationHostedSkipGroup> = emptyList()
)

@Serializable
internal data class AgentMigrationHostedReview(
    val provider: String? = null,
    val hostedModel: String? = null,
    val reviewReadyAt: String? = null,
    val reviewConfirmedAt: String? = null,
    val importSummary: AgentMigrationHostedImportSummary? = null
)

/** One migration as `GET /agents/:id/migration` reports it (web `AgentMigrationNotice`). */
@Serializable
internal data class AgentMigrationNotice(
    val agentId: String,
    val migrationRef: String,
    val state: String,
    val revision: Int = 0,
    val sourceMachineId: String? = null,
    val targetKind: String? = null,
    val targetMachineId: String? = null,
    val failureReason: String? = null,
    val abortReason: String? = null,
    val transportErrorCode: String? = null,
    val transportErrorMessage: String? = null,
    val sourceQuiescedAt: String? = null,
    val sourceBuildProgress: AgentMigrationSourceBuildProgress? = null,
    val transferProgress: AgentMigrationTransferProgress? = null,
    val transferDeadlineAt: String? = null,
    val transportControlRegisteredAt: String? = null,
    val readyAt: String? = null,
    val flippedAt: String? = null,
    val arrivedAt: String? = null,
    val completedAt: String? = null,
    val abortedAt: String? = null,
    val canceledAt: String? = null,
    val cancelNeedsAttention: Boolean = false,
    val cancelErrorCode: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val hosted: AgentMigrationHostedReview? = null
)

@Serializable
private data class AgentMigrationStatusResponseDto(val migration: AgentMigrationNotice? = null)

@Serializable
private data class AgentMigrationStartResponseDto(
    val migrationRef: String,
    val state: String
)

@Serializable
private data class AgentMigrationHostedTargetDto(
    val kind: String,
    val hostedModel: String? = null
)

@Serializable
private data class AgentMigrationStartRequestDto(
    val targetComputer: String? = null,
    val target: AgentMigrationHostedTargetDto? = null
)

@Serializable
private data class AgentMigrationRevisionRequestDto(
    val migrationRef: String,
    val expectedRevision: Int
)

/** Where a migration goes: another computer, or the cloud (`target.kind: "hosted"`). */
internal sealed interface AgentMigrationTarget {
    data class Computer(val machineId: String) : AgentMigrationTarget
    data object Cloud : AgentMigrationTarget
}

/** Parses a 2xx status body; `{ "migration": null }` is a valid "no migration". */
internal fun parseSharedAgentMigrationStatus(bodyText: String): AgentMigrationStatusSnapshot? =
    runCatching {
        val dto = migrationJson.decodeFromString(AgentMigrationStatusResponseDto.serializer(), bodyText)
        AgentMigrationStatusSnapshot(dto.migration)
    }.getOrNull()

internal data class AgentMigrationStatusSnapshot(val migration: AgentMigrationNotice?)

internal fun parseSharedAgentMigrationStart(bodyText: String): String? =
    runCatching {
        migrationJson.decodeFromString(AgentMigrationStartResponseDto.serializer(), bodyText).migrationRef
    }.getOrNull()?.takeIf { it.isNotBlank() }

/** Web AgentMigrationDialog: `{ targetComputer }` or `{ target: { kind: "hosted" } }` (server default model). */
internal fun agentMigrationStartRequestBody(target: AgentMigrationTarget): String =
    migrationRequestJson.encodeToString(
        AgentMigrationStartRequestDto.serializer(),
        when (target) {
            is AgentMigrationTarget.Computer -> AgentMigrationStartRequestDto(targetComputer = target.machineId)
            AgentMigrationTarget.Cloud -> AgentMigrationStartRequestDto(target = AgentMigrationHostedTargetDto(kind = "hosted"))
        }
    )

internal fun agentMigrationRevisionRequestBody(migrationRef: String, expectedRevision: Int): String =
    migrationRequestJson.encodeToString(
        AgentMigrationRevisionRequestDto.serializer(),
        AgentMigrationRevisionRequestDto(migrationRef = migrationRef, expectedRevision = expectedRevision)
    )

// ── State machine (web components/agentMigration/state.ts) ──

internal enum class AgentMigrationPhase { Active, Completed, Failed, Canceled }

private data class AgentMigrationStateInfo(val phase: AgentMigrationPhase, val step: Int, val cancellable: Boolean)

internal const val AGENT_MIGRATION_STEP_COUNT = 5

private val AGENT_MIGRATION_STATE_INFO: Map<String, AgentMigrationStateInfo> =
    mapOf(
        "provisioning" to AgentMigrationStateInfo(AgentMigrationPhase.Active, 0, true),
        "prep" to AgentMigrationStateInfo(AgentMigrationPhase.Active, 1, true),
        "ready" to AgentMigrationStateInfo(AgentMigrationPhase.Active, 2, true),
        "in_transit" to AgentMigrationStateInfo(AgentMigrationPhase.Active, 2, true),
        "arriving" to AgentMigrationStateInfo(AgentMigrationPhase.Active, 3, true),
        "starting" to AgentMigrationStateInfo(AgentMigrationPhase.Active, 3, true),
        "cancel_requested_pre_flip" to AgentMigrationStateInfo(AgentMigrationPhase.Active, 0, false),
        "cancel_requested_post_flip" to AgentMigrationStateInfo(AgentMigrationPhase.Active, 0, false),
        "canceled_pre_flip" to AgentMigrationStateInfo(AgentMigrationPhase.Canceled, 0, false),
        "canceled_post_flip" to AgentMigrationStateInfo(AgentMigrationPhase.Canceled, 0, false),
        "completed" to AgentMigrationStateInfo(AgentMigrationPhase.Completed, AGENT_MIGRATION_STEP_COUNT, false),
        "aborted" to AgentMigrationStateInfo(AgentMigrationPhase.Failed, 0, false),
        "failed" to AgentMigrationStateInfo(AgentMigrationPhase.Failed, 0, false)
    )

internal val AgentMigrationNotice.phase: AgentMigrationPhase?
    get() = AGENT_MIGRATION_STATE_INFO[state]?.phase

internal val AgentMigrationNotice.isActive: Boolean get() = phase == AgentMigrationPhase.Active
internal val AgentMigrationNotice.isCompleted: Boolean get() = phase == AgentMigrationPhase.Completed
internal val AgentMigrationNotice.isFailed: Boolean get() = phase == AgentMigrationPhase.Failed
internal val AgentMigrationNotice.isCanceled: Boolean get() = phase == AgentMigrationPhase.Canceled
internal val AgentMigrationNotice.isHosted: Boolean get() = targetKind == "hosted"
internal val AgentMigrationNotice.isCancelRequested: Boolean get() = state.startsWith("cancel_requested_")

/** Web `canCancelMigration`: a hosted move can't be canceled once it switched. */
internal fun AgentMigrationNotice.canCancel(): Boolean {
    if (isHosted && (flippedAt != null || state == "arriving" || state == "starting")) return false
    return AGENT_MIGRATION_STATE_INFO[state]?.cancellable == true && revision > 0
}

/** Web `isHostedReviewPending`: the review is built and waits for confirm or cancel. */
internal fun AgentMigrationNotice.isHostedReviewPending(): Boolean =
    isHosted && state == "in_transit" && hosted?.reviewReadyAt != null && hosted.reviewConfirmedAt == null

/** 0-based step; [AGENT_MIGRATION_STEP_COUNT] once completed (web migrationProgressStep / hostedMigrationProgressStep). */
internal fun AgentMigrationNotice.progressStep(): Int {
    if (isHosted) {
        if (isCompleted) return AGENT_MIGRATION_STEP_COUNT
        if (flippedAt != null || state == "arriving" || state == "starting") return 3
        if (hosted?.reviewReadyAt != null) return 2
        if (readyAt != null || state == "ready" || state == "in_transit") return 1
        return 0
    }
    val step = AGENT_MIGRATION_STATE_INFO[state]?.step ?: 0
    if (step == AGENT_MIGRATION_STEP_COUNT) return step
    val observed =
        when {
            arrivedAt != null || flippedAt != null -> 3
            readyAt != null || transportControlRegisteredAt != null -> 2
            sourceQuiescedAt != null -> 1
            else -> 0
        }
    return maxOf(step, observed)
}

/** Web `endedComputerMigration`: a finished move between computers collapses to one line. */
internal enum class AgentMigrationEnding { Completed, Failed, Canceled }

internal fun AgentMigrationNotice.endedComputerMigration(): AgentMigrationEnding? {
    if (isHosted || cancelNeedsAttention) return null
    return when {
        isCompleted -> AgentMigrationEnding.Completed
        isFailed -> AgentMigrationEnding.Failed
        isCanceled -> AgentMigrationEnding.Canceled
        else -> null
    }
}

/** Hosted arrival stalls that keep `arriving`/`starting` but need attention (web R5). */
internal val HOSTED_ARRIVAL_ATTENTION_REASONS =
    setOf(
        "HOSTED_IMPORT_FAILED",
        "HOSTED_IMPORT_STALLED",
        "HOSTED_PROVIDER_FAILED",
        "HOSTED_AGENT_DELETED",
        "HOSTED_PROVIDER_MISMATCH"
    )

/** The progress panel paints its error look (web MigrationProgressPanel `failed`). */
internal fun AgentMigrationNotice.needsAttention(): Boolean =
    isFailed ||
        (state == "starting" && failureReason == "auto_start_failed") ||
        (isHosted && (state == "arriving" || state == "starting") && failureReason in HOSTED_ARRIVAL_ATTENTION_REASONS) ||
        cancelNeedsAttention

// ── Errors ──

internal object AgentMigrationErrorCodes {
    const val RevisionStale = "MIGRATION_REVISION_STALE"
    const val ConcurrentUpdate = "MIGRATION_CONCURRENT_UPDATE"
    const val UiDisabled = "agent_migration_ui_disabled"
}
