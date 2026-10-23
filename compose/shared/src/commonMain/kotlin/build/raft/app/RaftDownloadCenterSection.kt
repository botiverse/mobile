package build.raft.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.downloads.RaftDownloadEntry
import build.raft.app.downloads.RaftDownloadErrorKind
import build.raft.app.downloads.RaftDownloadStatus
import build.raft.app.downloads.raftDownloadSizeLabel
import build.raft.app.downloads.raftDownloadSpeedLabel
import build.raft.app.downloads.rememberThrottledSpeed
import build.raft.app.resources.MR
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.raftCopyable
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftIconButtonSize
import build.raft.designsystem.components.RaftIconTile
import build.raft.designsystem.components.RaftIconTileSize
import build.raft.designsystem.components.RaftIconTileVariant
import build.raft.designsystem.components.RaftListRowGroup
import build.raft.designsystem.components.RaftProgressBar
import build.raft.designsystem.components.RaftSection
import build.raft.designsystem.components.RaftSeparator
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import com.tencent.tmm.kmmresource.compose.stringResource

/** Intents of the download centre list (Settings › Storage › Downloads). */
internal sealed interface RaftDownloadCenterAction {
    val id: String

    data class Open(override val id: String) : RaftDownloadCenterAction
    data class Pause(override val id: String) : RaftDownloadCenterAction
    data class Resume(override val id: String) : RaftDownloadCenterAction
    data class Cancel(override val id: String) : RaftDownloadCenterAction
    data class Retry(override val id: String) : RaftDownloadCenterAction
    data class Delete(override val id: String) : RaftDownloadCenterAction
}

/**
 * The download centre: in-progress downloads (progress, bytes, speed, pause / cancel) and
 * finished ones (state, size, time; open, retry, remove), newest first, with Clear Finished.
 * Design-system only; call inside [RaftDesignSystemTheme] (the section does it itself).
 * Each row leads with the file's type icon; a long name keeps its end (the extension) and
 * gives way in the middle. [timeLabel] is the app's relative time ("5 min ago"), read from
 * [LocalRaftTimeContextProvider].
 *
 * [canRetry] is false for rows restored from a previous app session: their retry needs the
 * message again, which the row says instead of offering a dead button.
 */
@Composable
internal fun RaftDownloadCenterSection(
    entries: List<RaftDownloadEntry>,
    canRetry: (String) -> Boolean,
    onAction: (RaftDownloadCenterAction) -> Unit,
    onClearFinished: () -> Unit,
    modifier: Modifier = Modifier,
    animated: Boolean = true,
    timeLabel: @Composable (Long) -> String = { millis -> raftDownloadCenterTimeLabel(millis) }
) {
    RaftDesignSystemTheme {
        val colors = RaftTheme.colors
        val typography = RaftTheme.typography
        RaftSection(
            title = stringResource(MR.strings.download_center_title),
            modifier = modifier.fillMaxWidth(),
            count = entries.size.takeIf { it > 0 }?.toString(),
            separator = false,
            action =
                if (entries.any { !it.status.isActive }) {
                    {
                        RaftButton(
                            text = stringResource(MR.strings.download_center_clear_finished),
                            onClick = onClearFinished,
                            variant = RaftButtonVariant.Ghost
                        )
                    }
                } else {
                    null
                }
        ) {
            if (entries.isEmpty()) {
                RaftText(
                    text = stringResource(MR.strings.download_center_empty),
                    style = typography.callout,
                    color = colors.foregroundMuted
                )
            } else {
                RaftListRowGroup(Modifier.fillMaxWidth()) {
                    entries.forEachIndexed { index, entry ->
                        if (index > 0) RaftSeparator()
                        RaftDownloadCenterRow(
                            entry = entry,
                            canRetry = canRetry(entry.id),
                            onAction = onAction,
                            animated = animated,
                            timeLabel = timeLabel
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RaftDownloadCenterRow(
    entry: RaftDownloadEntry,
    canRetry: Boolean,
    onAction: (RaftDownloadCenterAction) -> Unit,
    animated: Boolean,
    timeLabel: @Composable (Long) -> String
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RaftIconTile(
                size = RaftIconTileSize.Md,
                variant = if (entry.status == RaftDownloadStatus.Failed) RaftIconTileVariant.Warning else RaftIconTileVariant.Default
            ) {
                RaftDownloadIcon(raftFileTypeIconName(entry.fileName, entry.mimeType.orEmpty()), size = RaftIconTileSize.Md.icon)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                RaftText(
                    text = entry.fileName.ifBlank { stringResource(MR.strings.file_preview_fallback_file) },
                    // A long press copies the file name (not the "File" stand-in).
                    modifier = Modifier.raftCopyable(entry.fileName),
                    style = typography.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                    color = colors.foreground,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis
                )
                RaftText(
                    text = raftDownloadCenterMeta(entry, timeLabel),
                    style = typography.monoMeta,
                    color = colors.foregroundHint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            RaftDownloadCenterTrailing(entry, canRetry, onAction)
        }
        if (entry.status.isActive) {
            val percent = entry.percent
            RaftProgressBar(
                value = percent?.let { it / 100f },
                valueText = percent?.let { "$it%" },
                label =
                    stringResource(
                        when {
                            entry.status == RaftDownloadStatus.Paused -> MR.strings.file_preview_download_paused
                            entry.saving -> MR.strings.download_center_status_saving
                            else -> MR.strings.file_preview_download_downloading
                        }
                    ),
                animated = animated && entry.status == RaftDownloadStatus.Downloading
            )
        } else if (entry.status == RaftDownloadStatus.Failed) {
            val detail =
                when {
                    entry.errorKind == RaftDownloadErrorKind.Interrupted || !canRetry ->
                        stringResource(MR.strings.download_center_retry_unavailable)
                    else -> entry.errorMessage
                }
            if (!detail.isNullOrBlank()) {
                RaftText(detail, style = typography.footnote, color = colors.foregroundMuted)
            }
        }
    }
}

@Composable
private fun RaftDownloadCenterTrailing(
    entry: RaftDownloadEntry,
    canRetry: Boolean,
    onAction: (RaftDownloadCenterAction) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        when (entry.status) {
            RaftDownloadStatus.Downloading, RaftDownloadStatus.Paused -> {
                val paused = entry.status == RaftDownloadStatus.Paused
                if (!entry.saving) {
                    val label = stringResource(if (paused) MR.strings.file_preview_download_resume else MR.strings.file_preview_download_pause)
                    RaftIconButton(
                        onClick = { onAction(if (paused) RaftDownloadCenterAction.Resume(entry.id) else RaftDownloadCenterAction.Pause(entry.id)) },
                        tooltip = label,
                        variant = RaftButtonVariant.Ghost
                    ) { RaftDownloadIcon(if (paused) RaftLucideIconName.Play else RaftLucideIconName.Pause) }
                }
                RaftIconButton(
                    onClick = { onAction(RaftDownloadCenterAction.Cancel(entry.id)) },
                    tooltip = stringResource(MR.strings.common_cancel),
                    variant = RaftButtonVariant.Ghost
                ) { RaftDownloadIcon(RaftLucideIconName.X) }
            }
            RaftDownloadStatus.Completed -> {
                RaftIconButton(
                    onClick = { onAction(RaftDownloadCenterAction.Open(entry.id)) },
                    tooltip = stringResource(MR.strings.common_open),
                    variant = RaftButtonVariant.Ghost
                ) { RaftDownloadIcon(RaftLucideIconName.ExternalLink) }
                RaftDownloadCenterRemoveButton(entry, onAction)
            }
            RaftDownloadStatus.Failed, RaftDownloadStatus.Cancelled -> {
                if (canRetry) {
                    RaftIconButton(
                        onClick = { onAction(RaftDownloadCenterAction.Retry(entry.id)) },
                        tooltip = stringResource(MR.strings.common_retry),
                        variant = RaftButtonVariant.Default
                    ) { RaftDownloadIcon(RaftLucideIconName.RotateCcw) }
                }
                RaftDownloadCenterRemoveButton(entry, onAction)
            }
        }
    }
}

@Composable
private fun RaftDownloadCenterRemoveButton(entry: RaftDownloadEntry, onAction: (RaftDownloadCenterAction) -> Unit) {
    RaftIconButton(
        onClick = { onAction(RaftDownloadCenterAction.Delete(entry.id)) },
        tooltip = stringResource(MR.strings.common_remove),
        variant = RaftButtonVariant.Ghost,
        size = RaftIconButtonSize.Md
    ) { RaftDownloadIcon(RaftLucideIconName.Trash2) }
}

/** `Downloaded · 3.4 MB · 2m`, `1.2 MB of 3.4 MB · 850.0 KB/s`, `Failed · 5m`. */
@Composable
private fun raftDownloadCenterMeta(entry: RaftDownloadEntry, timeLabel: @Composable (Long) -> String): String {
    val parts = mutableListOf<String>()
    val shownSpeed = rememberThrottledSpeed(entry.bytesPerSecond.takeIf { entry.status == RaftDownloadStatus.Downloading })
    when (entry.status) {
        RaftDownloadStatus.Downloading, RaftDownloadStatus.Paused -> {
            raftDownloadBytesLabel(entry.downloadedBytes, entry.totalBytes)?.let(parts::add)
            if (entry.status == RaftDownloadStatus.Downloading) raftDownloadSpeedLabel(shownSpeed)?.let(parts::add)
        }
        RaftDownloadStatus.Completed -> {
            // The Open button already says "downloaded": keep the line for size and time.
            (entry.totalBytes ?: entry.downloadedBytes.takeIf { it > 0L })?.let { parts += raftDownloadSizeLabel(it) }
            parts += timeLabel(entry.updatedAtMillis)
        }
        RaftDownloadStatus.Failed -> {
            parts +=
                stringResource(
                    if (entry.errorKind == RaftDownloadErrorKind.Interrupted) {
                        MR.strings.download_center_status_interrupted
                    } else {
                        MR.strings.download_center_status_failed
                    }
                )
            parts += timeLabel(entry.updatedAtMillis)
        }
        RaftDownloadStatus.Cancelled -> {
            parts += stringResource(MR.strings.download_center_status_cancelled)
            parts += timeLabel(entry.updatedAtMillis)
        }
    }
    return parts.filter { it.isNotBlank() }.joinToString(" · ")
}

/**
 * A download's time as the app's relative time ("5 min ago", Saved and Files use the same
 * labels). The epoch milliseconds go in as they are: the parser reads a 13-digit epoch.
 */
@Composable
internal fun raftDownloadCenterTimeLabel(epochMillis: Long): String =
    raftRelativeTimestampLabel(epochMillis.toString(), clampFuture = true)
