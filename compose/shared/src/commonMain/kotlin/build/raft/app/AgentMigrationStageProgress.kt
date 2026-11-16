package build.raft.app

import androidx.compose.runtime.Composable
import build.raft.app.data.AgentMigrationNotice
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
 */

/** What the source says once it stopped the agent and packs its workspace (web `migrationSourceStageMessage`). */
internal sealed interface AgentMigrationPackingLine {
    /** Web `agent.detail.migrationPackingProgress`: shown once the source reported any file or byte. */
    data class Progress(val files: Long, val bytes: Long) : AgentMigrationPackingLine

    /** Web `agent.detail.migrationPacking`: nothing packed reported yet. */
    data object Plain : AgentMigrationPackingLine
}

internal fun AgentMigrationNotice.packingLine(): AgentMigrationPackingLine {
    val progress = sourceBuildProgress
    return if (progress != null && (progress.files > 0 || progress.bytes > 0)) {
        AgentMigrationPackingLine.Progress(progress.files, progress.bytes)
    } else {
        AgentMigrationPackingLine.Plain
    }
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
internal fun agentMigrationPackingMessage(notice: AgentMigrationNotice, source: String): String =
    when (val line = notice.packingLine()) {
        is AgentMigrationPackingLine.Progress ->
            stringResource(
                Res.string.agent_migration_detail_migration_packing_progress,
                source,
                agentMigrationCount(line.files),
                formatAgentMigrationSize(line.bytes),
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
                line.percent.toString(),
                formatAgentMigrationSize(line.receivedBytes),
                formatAgentMigrationSize(line.totalBytes),
            )
    }
