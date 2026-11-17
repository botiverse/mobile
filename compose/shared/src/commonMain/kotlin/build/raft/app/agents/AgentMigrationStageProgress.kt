package build.raft.app

import androidx.compose.runtime.Composable
import build.raft.app.data.AgentMigrationNotice
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull
import build.raft.app.resources.*

/*
 * The sizes on the migration card's status line while a move between computers (or the upload
 * to the cloud) runs, as the web writes them (botiverse/slock staging 6935917a0f,
 * web components/agentMigration/presentation.ts `migrationSourceStageMessage` and
 * `migrationDownloadStageMessage`):
 * - packing: the files and (uncompressed) bytes the source has packed so far
 *   (owner status `sourceBuildProgress`);
 * - downloading: the target's share of the compressed bundle, as a percent and "received of
 *   total" (owner status `transferProgress`, bytes of the bundle the target has downloaded).
 * Sizes use web `formatMigrationSize` (binary units, [formatAgentMigrationSize]).
 *
 * The times on that line (botiverse/slock staging 7a631c0ffe, the same two functions): before the
 * source stopped the agent, how long at most it still waits for it (owner status
 * `sourceDrainUntil`); once it stopped it and packs without reporting sizes yet, how long packing
 * has run (`sourceQuiescedAt`). Both against the caller's clock ([AgentMigrationSectionInputs.nowMillis]).
 */

private const val MinuteMillis = 60_000L

/**
 * What the source says before it stopped the agent, when it did not say what it waits for
 * (`sourceDrain` null; web `migrationSourceStageMessage` without `sourceQuiescedAt`).
 */
internal sealed interface AgentMigrationStoppingLine {
    /** Web `agent.detail.migrationWaitingForAgent`: [minutes] at most, rounded up, at least 1. */
    data class Waiting(val minutes: Long) : AgentMigrationStoppingLine

    /** Web `agent.detail.migrationStoppingSource`: no wait (no `sourceDrainUntil`, unreadable, or passed). */
    data object Stopping : AgentMigrationStoppingLine
}

internal fun AgentMigrationNotice.stoppingLine(nowMillis: Long): AgentMigrationStoppingLine {
    // Web: `Date.parse(sourceDrainUntil) - currentTimeMs()`; NaN (absent or unreadable) is not > 0.
    val untilMs = parseRaftTimestampEpochMillisOrNull(sourceDrainUntil) ?: return AgentMigrationStoppingLine.Stopping
    val remainingMs = untilMs - nowMillis
    if (remainingMs <= 0L) return AgentMigrationStoppingLine.Stopping
    // Web `Math.max(1, Math.ceil(remainingMs / 60_000))`.
    return AgentMigrationStoppingLine.Waiting(maxOf(1L, (remainingMs + MinuteMillis - 1) / MinuteMillis))
}

/** What the source says once it stopped the agent and packs its workspace (web `migrationSourceStageMessage`). */
internal sealed interface AgentMigrationPackingLine {
    /** Web `agent.detail.migrationPackingProgress`: shown once the source reported any file or byte. */
    data class Progress(val files: Long, val bytes: Long) : AgentMigrationPackingLine

    /** Web `agent.detail.migrationPackingElapsed`: nothing packed reported yet, packing for [minutes] (floored, at least 1). */
    data class Elapsed(val minutes: Long) : AgentMigrationPackingLine

    /** Web `agent.detail.migrationPacking`: nothing packed reported yet, under a minute in (or no readable `sourceQuiescedAt`). */
    data object Plain : AgentMigrationPackingLine
}

internal fun AgentMigrationNotice.packingLine(nowMillis: Long): AgentMigrationPackingLine {
    val progress = sourceBuildProgress
    if (progress != null && (progress.files > 0 || progress.bytes > 0)) {
        return AgentMigrationPackingLine.Progress(progress.files, progress.bytes)
    }
    // Web `Math.floor((currentTimeMs() - Date.parse(sourceQuiescedAt)) / 60_000)`, shown from 1.
    val quiescedMs = parseRaftTimestampEpochMillisOrNull(sourceQuiescedAt) ?: return AgentMigrationPackingLine.Plain
    val elapsedMinutes = (nowMillis - quiescedMs).floorDiv(MinuteMillis)
    return if (elapsedMinutes >= 1L) AgentMigrationPackingLine.Elapsed(elapsedMinutes) else AgentMigrationPackingLine.Plain
}

/** State `ready`: the upload is done and the target downloads chunk by chunk (web `migrationDownloadStageMessage`). */
internal sealed interface AgentMigrationDownloadLine {
    /** Web `agent.detail.migrationReady`: no download progress reported yet. */
    data object Ready : AgentMigrationDownloadLine

    /** Web `agent.detail.migrationDownloading`: [percent] floored and kept under 100 until the last chunk lands. */
    data class Downloading(val percent: Int, val receivedBytes: Long, val totalBytes: Long) : AgentMigrationDownloadLine

    /** Web `agent.detail.migrationUnpacking`: every chunk is in. */
    data object Unpacking : AgentMigrationDownloadLine
}

internal fun AgentMigrationNotice.downloadLine(): AgentMigrationDownloadLine {
    val progress = transferProgress
    if (progress == null || progress.totalChunks == 0L || progress.totalBytes <= 0L) return AgentMigrationDownloadLine.Ready
    if (progress.receivedChunks >= progress.totalChunks) return AgentMigrationDownloadLine.Unpacking
    // Floor, so it never reads 100% before the last chunk lands.
    val percent = minOf(99L, progress.receivedBytes.coerceAtLeast(0L) * 100L / progress.totalBytes).toInt()
    return AgentMigrationDownloadLine.Downloading(percent, progress.receivedBytes, progress.totalBytes)
}

@Composable
internal fun agentMigrationStoppingMessage(notice: AgentMigrationNotice, source: String, nowMillis: Long): String =
    when (val line = notice.stoppingLine(nowMillis)) {
        is AgentMigrationStoppingLine.Waiting ->
            stringResource(
                if (line.minutes == 1L) {
                    Res.string.agent_migration_detail_migration_waiting_for_agent_one
                } else {
                    Res.string.agent_migration_detail_migration_waiting_for_agent_other
                },
                source,
                line.minutes.toInt(),
            )
        AgentMigrationStoppingLine.Stopping -> stringResource(Res.string.agent_migration_detail_migration_stopping_source, source)
    }

@Composable
internal fun agentMigrationPackingMessage(notice: AgentMigrationNotice, source: String, nowMillis: Long): String =
    when (val line = notice.packingLine(nowMillis)) {
        is AgentMigrationPackingLine.Progress ->
            stringResource(
                Res.string.agent_migration_detail_migration_packing_progress,
                source,
                agentMigrationCount(line.files),
                formatAgentMigrationSize(line.bytes),
            )
        is AgentMigrationPackingLine.Elapsed ->
            stringResource(
                if (line.minutes == 1L) {
                    Res.string.agent_migration_detail_migration_packing_elapsed_one
                } else {
                    Res.string.agent_migration_detail_migration_packing_elapsed_other
                },
                source,
                line.minutes.toInt(),
            )
        AgentMigrationPackingLine.Plain -> stringResource(Res.string.agent_migration_detail_migration_packing, source)
    }

@Composable
internal fun agentMigrationDownloadMessage(notice: AgentMigrationNotice, source: String, target: String): String =
    when (val line = notice.downloadLine()) {
        AgentMigrationDownloadLine.Ready -> stringResource(Res.string.agent_migration_detail_migration_ready, source, target)
        AgentMigrationDownloadLine.Unpacking -> stringResource(Res.string.agent_migration_detail_migration_unpacking, source, target)
        is AgentMigrationDownloadLine.Downloading ->
            stringResource(
                Res.string.agent_migration_detail_migration_downloading,
                target,
                // The sign rides with the number: catalogs hold no literal percent (ComposeResourcesCatalogParityTest).
                "${line.percent}%",
                formatAgentMigrationSize(line.receivedBytes),
                formatAgentMigrationSize(line.totalBytes),
            )
    }
