package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import build.raft.app.data.AgentMigrationNotice
import build.raft.app.data.AgentMigrationSourceDrain
import build.raft.app.data.AgentMigrationSourceDrainWaitingFor
import build.raft.app.data.isCompleted
import build.raft.app.data.isFailed
import build.raft.app.data.migrationFormatGib
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull
import build.raft.app.resources.*
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme

/*
 * The migration card's newer lines (botiverse/slock staging 0e8b4fab):
 * - #14230: while the source waits for the agent before stopping it (`sourceDrain`), what it waits
 *   for and by when (web presentation.ts `migrationSourceDrainMessage`);
 * - #14275: the agent's own retry of a migration that failed for size: who it retried for, the
 *   failed attempt's size next to this one's (web MigrationRetry.tsx, retryComparison.ts), and,
 *   when that retry failed too, that the next move is a person's;
 * - #14275 / #14277: sizes in binary units like the size-limit copy (web `formatMigrationSize`).
 */

private const val KIB = 1024L
private const val MIB = 1024L * 1024L
private const val GIB_BYTES = 1024L * 1024L * 1024L

/** Web `formatBinaryUnit`: a whole value without a decimal, otherwise one. */
private fun formatBinaryUnit(bytes: Long, unitBytes: Long, unit: String): String =
    if (bytes % unitBytes == 0L) {
        "${bytes / unitBytes} $unit"
    } else {
        val tenths = kotlin.math.round(bytes * 10.0 / unitBytes).toLong()
        "${tenths / 10}.${tenths % 10} $unit"
    }

/** Web `formatMigrationSize`: GiB from 1 GiB (as `formatGib`), MiB or KiB below, bytes under 1 KiB. */
internal fun formatAgentMigrationSize(bytes: Long): String =
    when {
        bytes < KIB -> "${bytes.coerceAtLeast(0)} B"
        bytes < MIB -> formatBinaryUnit(bytes, KIB, "KiB")
        bytes < GIB_BYTES -> formatBinaryUnit(bytes, MIB, "MiB")
        else -> migrationFormatGib(bytes)
    }

/** A count as the web's `#` writes it in English: digits grouped by thousands. */
internal fun agentMigrationCount(value: Long): String =
    value.coerceAtLeast(0).toString().reversed().chunked(3).joinToString(",").reversed()

/** Web `isAgentMigrationRetry`. */
internal val AgentMigrationNotice.isAgentRetry: Boolean
    get() = !retryOfMigrationId.isNullOrBlank()

/** Web `MigrationRetryComparisonRows`: what the failed attempt measured, what this one packed (null: not shown). */
internal data class AgentMigrationRetryRows(val failedAttempt: String?, val thisAttempt: String?) {
    val isEmpty: Boolean get() = failedAttempt == null && thisAttempt == null
}

/** Web `migrationRetryComparisonRows`: while this attempt still packs, only the failed attempt shows. */
@Composable
internal fun agentMigrationRetryRows(notice: AgentMigrationNotice): AgentMigrationRetryRows {
    val comparison = notice.retryComparison ?: return AgentMigrationRetryRows(null, null)
    val failure = comparison.previousFailure
    val failedAttempt =
        when {
            failure?.entries != null ->
                stringResource(
                    if (failure.entries == 1L) Res.string.agent_migration_retry_files_one else Res.string.agent_migration_retry_files_other,
                    agentMigrationCount(failure.entries),
                )
            failure?.bytes != null ->
                if (failure.bytesIsLowerBound) {
                    stringResource(Res.string.agent_migration_retry_at_least, formatAgentMigrationSize(failure.bytes))
                } else {
                    formatAgentMigrationSize(failure.bytes)
                }
            else -> null
        }
    val packed = comparison.packed
    val thisAttempt =
        when {
            packed == null -> null
            packed.final || notice.isCompleted ->
                stringResource(
                    if (packed.files == 1L) Res.string.agent_migration_retry_packed_one else Res.string.agent_migration_retry_packed_other,
                    agentMigrationCount(packed.files),
                    formatAgentMigrationSize(packed.bytes),
                )
            // It stopped before its bundle was complete: what it had packed then.
            notice.isFailed ->
                stringResource(
                    if (packed.files == 1L) Res.string.agent_migration_retry_packed_when_stopped_one else Res.string.agent_migration_retry_packed_when_stopped_other,
                    agentMigrationCount(packed.files),
                    formatAgentMigrationSize(packed.bytes),
                )
            else -> null
        }
    return AgentMigrationRetryRows(failedAttempt, thisAttempt)
}

/** Web `MigrationRetryNote`: the agent retried this migration, on behalf of whom (selected on who, never on the name). */
@Composable
internal fun AgentMigrationRetryNote(notice: AgentMigrationNotice, inputs: AgentMigrationSectionInputs, modifier: Modifier = Modifier) {
    val starter = notice.initiatedBy
    val text =
        when {
            starter == null -> stringResource(Res.string.agent_migration_retry_note_none, inputs.agentName)
            starter.id == inputs.viewerUserId -> stringResource(Res.string.agent_migration_retry_note_you, inputs.agentName)
            else -> stringResource(Res.string.agent_migration_retry_note_named, inputs.agentName, starter.label)
        }
    RaftText(
        text = text,
        modifier = modifier.testTag(AgentMigrationRetryTestTags.Note),
        style = RaftTheme.typography.callout,
        color = RaftTheme.colors.foregroundMuted,
    )
}

/** Web `migrationSourceDrainMessage`: why the source has not stopped the agent yet, and by when it will. */
@Composable
internal fun agentMigrationSourceDrainMessage(drain: AgentMigrationSourceDrain, inputs: AgentMigrationSectionInputs): String {
    val waitingFor = AgentMigrationSourceDrainWaitingFor.of(drain.waitingFor)
    val untilMs = parseRaftTimestampEpochMillisOrNull(drain.until)
    // An unparseable `until` reads as passed: the source stops at its deadline anyway.
    val time = untilMs?.takeIf { it > inputs.nowMillis() }?.let { inputs.formatClock(drain.until) }
    return if (time == null) {
        stringResource(
            when (waitingFor) {
                AgentMigrationSourceDrainWaitingFor.Turn -> Res.string.agent_migration_drain_turn_now
                AgentMigrationSourceDrainWaitingFor.BackgroundTasks -> Res.string.agent_migration_drain_background_tasks_now
                AgentMigrationSourceDrainWaitingFor.QueuedInput -> Res.string.agent_migration_drain_queued_input_now
                AgentMigrationSourceDrainWaitingFor.Other -> Res.string.agent_migration_drain_other_now
            },
            inputs.agentName,
        )
    } else {
        stringResource(
            when (waitingFor) {
                AgentMigrationSourceDrainWaitingFor.Turn -> Res.string.agent_migration_drain_turn
                AgentMigrationSourceDrainWaitingFor.BackgroundTasks -> Res.string.agent_migration_drain_background_tasks
                AgentMigrationSourceDrainWaitingFor.QueuedInput -> Res.string.agent_migration_drain_queued_input
                AgentMigrationSourceDrainWaitingFor.Other -> Res.string.agent_migration_drain_other
            },
            inputs.agentName,
            time,
        )
    }
}

internal object AgentMigrationRetryTestTags {
    /** Web `agent-migration-retry-note`. */
    const val Note = "agent-migration-retry-note"
}
