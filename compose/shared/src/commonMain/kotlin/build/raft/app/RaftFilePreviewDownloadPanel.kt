package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.base.BridgeAttachmentDownloadProgress
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.downloads.RaftDownloadEntry
import build.raft.app.downloads.RaftDownloadStatus
import build.raft.app.downloads.raftDownloadSizeLabel
import build.raft.app.downloads.raftDownloadSpeedLabel
import build.raft.app.resources.MR
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftCard
import build.raft.designsystem.components.RaftIconTile
import build.raft.designsystem.components.RaftIconTileSize
import build.raft.designsystem.components.RaftProgressBar
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.designsystem.theme.RaftTheme
import com.tencent.tmm.kmmresource.compose.stringResource

/** What the download part of the file page shows. */
internal enum class RaftFilePreviewDownloadPhase { Idle, Active, Paused, Saving, Failed }

/** Render model of one download on the file page (from the page's own progress or the centre row). */
internal data class RaftFilePreviewDownloadView(
    val phase: RaftFilePreviewDownloadPhase,
    val percent: Int? = null,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long? = null,
    val bytesPerSecond: Long? = null,
    val errorMessage: String? = null
) {
    val inProgress: Boolean
        get() = phase == RaftFilePreviewDownloadPhase.Active ||
            phase == RaftFilePreviewDownloadPhase.Paused ||
            phase == RaftFilePreviewDownloadPhase.Saving
}

internal fun raftFilePreviewDownloadView(
    status: String,
    progress: BridgeAttachmentDownloadProgress?,
    errorMessage: String?
): RaftFilePreviewDownloadView {
    val phase =
        when {
            status == "failed" -> RaftFilePreviewDownloadPhase.Failed
            status == "paused" || progress?.status == "paused" -> RaftFilePreviewDownloadPhase.Paused
            status == "downloading" && progress?.phase?.startsWith("export_") == true -> RaftFilePreviewDownloadPhase.Saving
            status == "downloading" -> RaftFilePreviewDownloadPhase.Active
            else -> RaftFilePreviewDownloadPhase.Idle
        }
    return RaftFilePreviewDownloadView(
        phase = phase,
        percent = progress?.percent,
        downloadedBytes = progress?.downloadedBytes ?: 0L,
        totalBytes = progress?.totalBytes,
        bytesPerSecond = progress?.bytesPerSecond,
        errorMessage = errorMessage?.takeIf { phase == RaftFilePreviewDownloadPhase.Failed && it.isNotBlank() }
    )
}

internal fun RaftDownloadEntry.toFilePreviewDownloadView(): RaftFilePreviewDownloadView =
    RaftFilePreviewDownloadView(
        phase =
            when {
                status == RaftDownloadStatus.Paused -> RaftFilePreviewDownloadPhase.Paused
                status == RaftDownloadStatus.Downloading && saving -> RaftFilePreviewDownloadPhase.Saving
                status == RaftDownloadStatus.Downloading -> RaftFilePreviewDownloadPhase.Active
                status == RaftDownloadStatus.Failed -> RaftFilePreviewDownloadPhase.Failed
                else -> RaftFilePreviewDownloadPhase.Idle
            },
        percent = percent,
        downloadedBytes = downloadedBytes,
        totalBytes = totalBytes,
        bytesPerSecond = bytesPerSecond,
        errorMessage = errorMessage
    )

/** `1.2 MB of 3.4 MB`, `1.2 MB` (unknown total), or null before the first byte. */
@Composable
internal fun raftDownloadBytesLabel(downloadedBytes: Long, totalBytes: Long?): String? {
    val total = totalBytes?.takeIf { it > 0L }
    return when {
        total != null ->
            stringResource(
                MR.strings.download_center_progress_bytes,
                raftDownloadSizeLabel(downloadedBytes.coerceIn(0L, total)),
                raftDownloadSizeLabel(total)
            )
        downloadedBytes > 0L -> raftDownloadSizeLabel(downloadedBytes)
        else -> null
    }
}

/**
 * The progress block of a running download, design-system only: a [RaftProgressBar] headed by
 * the phase and the percentage, the bytes and speed under it, then Pause / Resume and Cancel.
 * Call inside [RaftDesignSystemTheme].
 */
@Composable
internal fun RaftFilePreviewDownloadProgressBlock(
    view: RaftFilePreviewDownloadView,
    onPause: (() -> Unit)?,
    onResume: (() -> Unit)?,
    onCancel: (() -> Unit)?,
    modifier: Modifier = Modifier,
    animated: Boolean = true
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val label =
        when (view.phase) {
            RaftFilePreviewDownloadPhase.Paused -> stringResource(MR.strings.file_preview_download_paused)
            RaftFilePreviewDownloadPhase.Saving -> stringResource(MR.strings.download_center_status_saving)
            else -> stringResource(MR.strings.file_preview_download_downloading)
        }
    val percent = view.percent?.coerceIn(0, 100)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        RaftProgressBar(
            value = percent?.let { it / 100f },
            label = label,
            valueText = percent?.let { "$it%" },
            animated = animated && view.phase != RaftFilePreviewDownloadPhase.Paused
        )
        val bytes = raftDownloadBytesLabel(view.downloadedBytes, view.totalBytes)
        val speed = raftDownloadSpeedLabel(view.bytesPerSecond).takeIf { view.phase == RaftFilePreviewDownloadPhase.Active }
        if (bytes != null || speed != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                RaftText(bytes.orEmpty(), style = typography.monoMeta, color = colors.foregroundMuted, maxLines = 1)
                if (speed != null) {
                    RaftText(speed, style = typography.monoMeta, color = colors.foregroundMuted, maxLines = 1)
                }
            }
        }
        if (onCancel != null || onPause != null || onResume != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val paused = view.phase == RaftFilePreviewDownloadPhase.Paused
                val toggle = if (paused) onResume else onPause
                // The MediaStore copy after the transfer is cancellable but not resumable.
                if (toggle != null && view.phase != RaftFilePreviewDownloadPhase.Saving) {
                    RaftButton(
                        text = stringResource(if (paused) MR.strings.file_preview_download_resume else MR.strings.file_preview_download_pause),
                        onClick = toggle,
                        modifier = Modifier.weight(1f),
                        leadingIcon = { RaftDownloadIcon(if (paused) RaftLucideIconName.Play else RaftLucideIconName.Pause) }
                    )
                }
                if (onCancel != null) {
                    RaftButton(
                        text = stringResource(MR.strings.common_cancel),
                        onClick = onCancel,
                        modifier = Modifier.weight(1f),
                        leadingIcon = { RaftDownloadIcon(RaftLucideIconName.X) }
                    )
                }
            }
        }
    }
}

/**
 * The file page of an attachment that has no in-app preview (raft-ui: IconTile, headline name,
 * mono meta, a Card holding the download progress, a Banner for a failure, then the actions).
 * Replaces the retired brutal card; every part is a design-system component.
 */
@Composable
internal fun RaftFilePreviewFileDownloadCard(
    iconName: RaftLucideIconName,
    name: String,
    meta: String,
    message: String,
    view: RaftFilePreviewDownloadView,
    actionLabel: String?,
    actionIcon: RaftLucideIconName?,
    actionEnabled: Boolean,
    onAction: (() -> Unit)?,
    secondaryActionLabel: String?,
    secondaryActionIcon: RaftLucideIconName?,
    secondaryActionEnabled: Boolean,
    onSecondaryAction: (() -> Unit)?,
    onPause: (() -> Unit)?,
    onResume: (() -> Unit)?,
    onCancel: (() -> Unit)?,
    modifier: Modifier = Modifier,
    animated: Boolean = true
) {
    RaftDesignSystemTheme {
        val colors = RaftTheme.colors
        val typography = RaftTheme.typography
        Box(modifier.background(colors.layerCanvas), contentAlignment = Alignment.Center) {
            Column(
                Modifier
                    .widthIn(max = 440.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                RaftIconTile(size = RaftIconTileSize.Lg) {
                    RaftDownloadIcon(iconName, size = RaftIconTileSize.Lg.icon)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    RaftText(
                        text = name.ifBlank { stringResource(MR.strings.file_preview_fallback_file) },
                        style = typography.headline,
                        color = colors.foreground,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (meta.isNotBlank()) {
                        RaftText(
                            text = meta,
                            style = typography.monoMeta,
                            color = colors.foregroundHint,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                when {
                    view.inProgress -> {
                        RaftCard(Modifier.fillMaxWidth()) {
                            RaftFilePreviewDownloadProgressBlock(
                                view = view,
                                onPause = onPause,
                                onResume = onResume,
                                onCancel = onCancel,
                                animated = animated
                            )
                        }
                        RaftText(
                            text = stringResource(MR.strings.file_preview_download_background_hint),
                            style = typography.footnote,
                            color = colors.foregroundHint,
                            textAlign = TextAlign.Center
                        )
                    }
                    view.phase == RaftFilePreviewDownloadPhase.Failed ->
                        RaftBanner(
                            title = stringResource(MR.strings.file_preview_download_failed),
                            description = view.errorMessage ?: message,
                            status = RaftBannerStatus.Warning,
                            modifier = Modifier.fillMaxWidth()
                        )
                    else ->
                        RaftText(
                            text = message,
                            style = typography.callout,
                            color = colors.foregroundMuted,
                            textAlign = TextAlign.Center,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis
                        )
                }
                if (!view.inProgress && actionLabel != null && onAction != null) {
                    val failed = view.phase == RaftFilePreviewDownloadPhase.Failed
                    RaftButton(
                        text = if (failed) stringResource(MR.strings.common_retry) else actionLabel,
                        onClick = onAction,
                        modifier = Modifier.fillMaxWidth(),
                        variant = RaftButtonVariant.Primary,
                        size = RaftButtonSize.Lg,
                        enabled = actionEnabled,
                        leadingIcon =
                            (if (failed) RaftLucideIconName.RotateCcw else actionIcon)?.let { icon -> { RaftDownloadIcon(icon) } }
                    )
                }
                if (secondaryActionLabel != null && onSecondaryAction != null && !view.inProgress) {
                    RaftButton(
                        text = secondaryActionLabel,
                        onClick = onSecondaryAction,
                        modifier = Modifier.fillMaxWidth(),
                        size = RaftButtonSize.Lg,
                        enabled = secondaryActionEnabled,
                        leadingIcon = secondaryActionIcon?.let { icon -> { RaftDownloadIcon(icon) } }
                    )
                }
            }
        }
    }
}

/** A Lucide icon in the enclosing design-system slot's ink. */
@Composable
internal fun RaftDownloadIcon(name: RaftLucideIconName, size: Dp = 18.dp) {
    val ink = LocalRaftContentColor.current.takeIf { it.isSpecified } ?: RaftTheme.colors.foreground
    RaftLucideIcon(name, modifier = Modifier.size(size), color = raftArgbHex(ink))
}
