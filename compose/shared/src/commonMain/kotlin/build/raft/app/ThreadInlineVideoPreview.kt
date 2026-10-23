package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import build.raft.app.components.RaftBrutalActionButton
import build.raft.app.components.RaftBrutalIconButton
import build.raft.app.resources.MR
import com.tencent.tmm.kmmresource.compose.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import build.raft.app.compat.ui.PlayState
import build.raft.app.compat.ui.VideoPlayControl

internal enum class ThreadInlineVideoPreviewStatus {
    Loading,

    /** A fetch failed transiently and an automatic retry is scheduled (video likely still processing). */
    Processing,
    Ready,
    Error
}

internal data class ThreadInlineVideoPreviewUiState(
    val status: ThreadInlineVideoPreviewStatus,
    val url: String = "",
    val expiresAt: String? = null,
    /** 1-based fetch attempt that produced this state. */
    val attempt: Int = 0
)

internal enum class ThreadInlineVideoRenderMode {
    Loading,
    Processing,
    Ready,
    Error
}

internal fun ThreadInlineVideoPreviewUiState?.renderMode(): ThreadInlineVideoRenderMode =
    when (this?.status) {
        null -> ThreadInlineVideoRenderMode.Loading
        ThreadInlineVideoPreviewStatus.Loading -> ThreadInlineVideoRenderMode.Loading
        ThreadInlineVideoPreviewStatus.Processing -> ThreadInlineVideoRenderMode.Processing
        ThreadInlineVideoPreviewStatus.Ready ->
            if (url.isBlank()) ThreadInlineVideoRenderMode.Error else ThreadInlineVideoRenderMode.Ready
        ThreadInlineVideoPreviewStatus.Error -> ThreadInlineVideoRenderMode.Error
    }

internal fun Map<String, ThreadInlineVideoPreviewUiState>.canRequestInlineVideoPreview(
    attachmentId: String
): Boolean {
    if (attachmentId.isBlank()) return false
    return when (this[attachmentId]?.status) {
        ThreadInlineVideoPreviewStatus.Loading,
        ThreadInlineVideoPreviewStatus.Processing,
        ThreadInlineVideoPreviewStatus.Ready -> false
        ThreadInlineVideoPreviewStatus.Error,
        null -> true
    }
}

internal fun ThreadInlineVideoPreviewUiState?.shouldRetryOnAttachmentUpdate(): Boolean =
    this?.status == ThreadInlineVideoPreviewStatus.Processing || this?.status == ThreadInlineVideoPreviewStatus.Error

internal data class ThreadInlineVideoPreviewHost(
    val states: Map<String, ThreadInlineVideoPreviewUiState>,
    val requestPreview: (attachmentId: String, force: Boolean) -> Unit
)

internal val LocalThreadInlineVideoPreviewHost =
    compositionLocalOf<ThreadInlineVideoPreviewHost?> { null }

/** Draws the inline video itself: the platform player, or a still frame in screenshot fixtures. */
internal typealias ThreadInlineVideoPlayer =
    @Composable (
        source: String,
        playControl: VideoPlayControl,
        onPlayStateChanged: (PlayState) -> Unit,
        onFirstFrame: () -> Unit,
        modifier: Modifier
    ) -> Unit

internal val LocalThreadInlineVideoPlayer =
    staticCompositionLocalOf<ThreadInlineVideoPlayer> {
        { source, playControl, onPlayStateChanged, onFirstFrame, modifier ->
            RaftFilePreviewKuiklyVideo(
                source = source,
                playControl = playControl,
                seekRequest = null,
                onPlayStateChanged = onPlayStateChanged,
                onPlayTimeChanged = { _, _ -> },
                onFirstFrame = onFirstFrame,
                modifier = modifier
            )
        }
    }

@Composable
internal fun ThreadInlineVideoAttachmentCard(
    attachment: RaftThreadAttachmentItem,
    onDownload: () -> Unit
) {
    val host = LocalThreadInlineVideoPreviewHost.current
    val previewState = host?.states?.get(attachment.id)

    LaunchedEffect(attachment.id, host != null) {
        if (previewState == null) {
            host?.requestPreview?.invoke(attachment.id, false)
        }
    }
    // A realtime update to this attachment (metadata filled in once the server finishes processing)
    // retries at once instead of waiting for the backoff, and revives an exhausted preview.
    var lastSeenAttachment by remember(attachment.id) { mutableStateOf(attachment) }
    LaunchedEffect(attachment) {
        if (attachment == lastSeenAttachment) return@LaunchedEffect
        lastSeenAttachment = attachment
        if (previewState.shouldRetryOnAttachmentUpdate()) {
            host?.requestPreview?.invoke(attachment.id, true)
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth().widthIn(max = 360.dp),
        verticalArrangement = Arrangement.spacedBy(RaftAppSpacing.Xs)
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(RaftColor.SurfaceWhite)
                    .border(RaftAppStroke.Default, RaftColor.BorderBlack)
        ) {
            ThreadInlineVideoSurface(
                attachment = attachment,
                previewState = previewState,
                previewEnabled = host != null,
                onRetry = { host?.requestPreview?.invoke(attachment.id, true) },
                onDownload = onDownload
            )
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = RaftAppSpacing.Sm, vertical = RaftAppSpacing.Xs),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = attachment.name.ifBlank { stringResource(MR.strings.thread_video_attachment_fallback_name) },
                    style = RaftType.Body.withRaftFontWeight(FontWeight.Bold),
                    color = RaftColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                attachment.threadAttachmentChipMetaLine(stringResource(MR.strings.file_preview_html)).takeIf { it.isNotBlank() }?.let { meta ->
                    Text(
                        text = meta,
                        style = RaftType.Caption,
                        color = RaftColor.TextMutedGray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun ThreadInlineVideoSurface(
    attachment: RaftThreadAttachmentItem,
    previewState: ThreadInlineVideoPreviewUiState?,
    previewEnabled: Boolean,
    onRetry: () -> Unit,
    onDownload: () -> Unit
) {
    val source = previewState?.url.orEmpty()
    val renderMode = previewState.renderMode()
    var playControl by remember(source) { mutableStateOf(VideoPlayControl.PREPLAY) }
    var playState by remember(source) { mutableStateOf(PlayState.NONE) }
    var firstFrameVisible by remember(source) { mutableStateOf(false) }
    val nativeController = raftPlatformUsesNativeVideoController()
    // Like the web client: a media error right after a link was issued refreshes the link once
    // before showing "playback failed".
    var playbackAutoRefreshed by remember(attachment.id) { mutableStateOf(false) }
    LaunchedEffect(playState, source) {
        if (playState == PlayState.ERROR && previewEnabled && !playbackAutoRefreshed) {
            playbackAutoRefreshed = true
            onRetry()
        }
    }

    Box(
        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color(0xFF000000)), // raft-color: video letterbox black
        contentAlignment = Alignment.Center
    ) {
        if (renderMode == ThreadInlineVideoRenderMode.Ready) {
            LocalThreadInlineVideoPlayer.current(
                source,
                playControl,
                { playState = it },
                { firstFrameVisible = true },
                Modifier.fillMaxSize()
            )
        }

        when {
            !previewEnabled ->
                ThreadInlineVideoOverlay(
                    iconName = RaftLucideIconName.TriangleAlert,
                    label = stringResource(MR.strings.thread_video_preview_unavailable),
                    secondaryActionIcon = RaftLucideIconName.Download,
                    secondaryActionLabel = stringResource(MR.strings.file_preview_action_download),
                    onSecondaryAction = onDownload
                )
            renderMode == ThreadInlineVideoRenderMode.Error ->
                ThreadInlineVideoOverlay(
                    iconName = RaftLucideIconName.TriangleAlert,
                    label = stringResource(MR.strings.thread_video_preview_unavailable),
                    actionIcon = RaftLucideIconName.RefreshCw,
                    actionLabel = stringResource(MR.strings.common_retry),
                    onAction = onRetry,
                    secondaryActionIcon = RaftLucideIconName.Download,
                    secondaryActionLabel = stringResource(MR.strings.file_preview_action_download),
                    onSecondaryAction = onDownload
                )
            renderMode == ThreadInlineVideoRenderMode.Loading ->
                ThreadInlineVideoOverlay(
                    iconName = RaftLucideIconName.LoaderCircle,
                    label = stringResource(MR.strings.thread_video_preview_loading),
                    spinning = true
                )
            renderMode == ThreadInlineVideoRenderMode.Processing ->
                ThreadInlineVideoOverlay(
                    iconName = RaftLucideIconName.LoaderCircle,
                    label = stringResource(MR.strings.thread_video_preview_processing),
                    spinning = true
                )
            playState == PlayState.ERROR && playbackAutoRefreshed ->
                ThreadInlineVideoOverlay(
                    iconName = RaftLucideIconName.TriangleAlert,
                    label = stringResource(MR.strings.thread_video_playback_failed),
                    actionIcon = RaftLucideIconName.RefreshCw,
                    actionLabel = stringResource(MR.strings.common_retry),
                    onAction = onRetry,
                    secondaryActionIcon = RaftLucideIconName.Download,
                    secondaryActionLabel = stringResource(MR.strings.file_preview_action_download),
                    onSecondaryAction = onDownload
                )
            !nativeController ->
                RaftBrutalIconButton(
                    iconName = if (playState == PlayState.PLAYING) RaftLucideIconName.Pause else RaftLucideIconName.Play,
                    onClick = {
                        playControl =
                            if (playState == PlayState.PLAYING) {
                                VideoPlayControl.PAUSE
                            } else {
                                VideoPlayControl.PLAY
                            }
                    },
                    contentDescription =
                        stringResource(if (playState == PlayState.PLAYING) MR.strings.common_pause else MR.strings.common_play),
                    modifier = Modifier.align(Alignment.BottomEnd).padding(RaftAppSpacing.Sm),
                    backgroundColor = RaftColor.SurfaceWhite,
                    size = 34.dp
                )
            !firstFrameVisible ->
                ThreadInlineVideoOverlay(
                    iconName = RaftLucideIconName.LoaderCircle,
                    label = attachment.name.ifBlank { "Preparing video" },
                    spinning = true
                )
        }
    }
}

@Composable
private fun ThreadInlineVideoOverlay(
    iconName: RaftLucideIconName,
    label: String,
    spinning: Boolean = false,
    actionIcon: RaftLucideIconName? = null,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
    secondaryActionIcon: RaftLucideIconName? = null,
    secondaryActionLabel: String? = null,
    onSecondaryAction: () -> Unit = {}
) {
    Column(
        modifier = Modifier.background(Color(0xCC000000)).padding(RaftAppSpacing.Sm), // raft-color: video scrim
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(RaftAppSpacing.Xs)
    ) {
        RaftLucideIcon(
            iconName,
            modifier = Modifier.size(28.dp),
            color = raftArgbHex(RaftColor.StickerPaper),
            spinning = spinning
        )
        Text(
            text = label,
            style = RaftType.Caption.withRaftFontWeight(FontWeight.Bold),
            color = RaftColor.StickerPaper,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (actionIcon != null || secondaryActionIcon != null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(RaftAppSpacing.Xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                actionIcon?.let { icon ->
                    RaftBrutalIconButton(
                        iconName = icon,
                        onClick = onAction,
                        contentDescription = actionLabel,
                        backgroundColor = RaftColor.SurfaceWhite,
                        size = 34.dp
                    )
                }
                secondaryActionIcon?.let { icon ->
                    RaftBrutalIconButton(
                        iconName = icon,
                        onClick = onSecondaryAction,
                        contentDescription = secondaryActionLabel,
                        backgroundColor = RaftColor.SurfaceWhite,
                        size = 34.dp
                    )
                }
            }
        }
    }
}
