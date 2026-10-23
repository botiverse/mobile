package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import build.raft.app.compat.ui.PlayState
import build.raft.app.compat.ui.VideoPlayControl
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.components.RaftSlotIcon
import build.raft.app.resources.MR
import build.raft.designsystem.components.RaftEmbeddedControls
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftSpinner
import build.raft.designsystem.components.RaftSpinnerSize
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftSpacing
import build.raft.designsystem.tokens.RaftStroke
import build.raft.designsystem.tokens.RaftUiPalette
import com.tencent.tmm.kmmresource.compose.stringResource


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


/**
 * The inline video of a message (raft-ui `MessageVideoPreview`, web `InlineVideoPreview`): a
 * 16:9 black box at most 360dp wide, `rounded-container` with a `line-hairline` edge outside the
 * black (`bg-clip-padding`). There is no name or size strip; the file opens in the preview from
 * the corner button.
 */
@Composable
internal fun ThreadInlineVideoAttachmentCard(
    attachment: RaftThreadAttachmentItem,
    onDownload: () -> Unit,
    onOpenPreview: (() -> Unit)? = null
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

    val name = attachment.name.ifBlank { stringResource(MR.strings.thread_video_attachment_fallback_name) }
    RaftDesignSystemTheme {
        val outer = RoundedCornerShape(RaftRadius.Container)
        Box(
            modifier =
                Modifier
                    .widthIn(max = ThreadInlineVideoMaxWidth)
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .border(RaftStroke.Hairline, RaftTheme.colors.lineHairline, outer)
                    .padding(RaftStroke.Hairline)
                    .clip(RoundedCornerShape(RaftRadius.Container - RaftStroke.Hairline))
                    .background(RaftUiPalette.black),
            contentAlignment = Alignment.Center
        ) {
            ThreadInlineVideoSurface(
                attachment = attachment,
                previewState = previewState,
                previewEnabled = host != null,
                onRetry = { host?.requestPreview?.invoke(attachment.id, true) },
                onDownload = onDownload
            )
            if (onOpenPreview != null) {
                RaftEmbeddedControls {
                    RaftIconButton(
                        onClick = onOpenPreview,
                        tooltip = stringResource(MR.strings.thread_video_open_in_preview, name),
                        modifier = Modifier.align(Alignment.TopEnd).padding(ThreadInlineVideoControlInset)
                    ) { RaftSlotIcon(RaftLucideIconName.Eye, size = 16.dp) }
                }
            }
        }
    }
}

private val ThreadInlineVideoMaxWidth = 360.dp

/** raft-ui `--control-embedded-inset` (6px) from the outer edge, less the 1dp hairline. */
private val ThreadInlineVideoControlInset = 5.dp

/** The media's white ink and its muted (`white/75`) actions; the box is black in both themes. */
private val ThreadInlineVideoInk = RaftUiPalette.white
private val ThreadInlineVideoMutedInk = RaftUiPalette.white.copy(alpha = 0.75f)

@Composable
private fun BoxScope.ThreadInlineVideoSurface(
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
            ThreadInlineVideoFailure(
                title = stringResource(MR.strings.thread_video_preview_unavailable),
                onDownload = onDownload
            )
        renderMode == ThreadInlineVideoRenderMode.Error ->
            ThreadInlineVideoFailure(
                title = stringResource(MR.strings.thread_video_preview_unavailable),
                onRetry = onRetry,
                onDownload = onDownload
            )
        // Web: a white `Spinner md` alone while the link loads, no name or caption.
        renderMode == ThreadInlineVideoRenderMode.Loading -> ThreadInlineVideoSpinner()
        // Still processing on the server: the spinner, and a muted line saying why it waits.
        renderMode == ThreadInlineVideoRenderMode.Processing ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(RaftSpacing.Sm)
            ) {
                ThreadInlineVideoSpinner()
                RaftText(
                    text = stringResource(MR.strings.thread_video_preview_processing),
                    style = RaftTheme.typography.caption.copy(fontWeight = FontWeight.SemiBold),
                    color = ThreadInlineVideoMutedInk,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = RaftSpacing.Md)
                )
            }
        playState == PlayState.ERROR && playbackAutoRefreshed ->
            ThreadInlineVideoFailure(
                title = stringResource(MR.strings.thread_video_playback_failed),
                onRetry = onRetry,
                onDownload = onDownload
            )
        !nativeController ->
            RaftEmbeddedControls {
                val playing = playState == PlayState.PLAYING
                RaftIconButton(
                    onClick = { playControl = if (playing) VideoPlayControl.PAUSE else VideoPlayControl.PLAY },
                    tooltip = stringResource(if (playing) MR.strings.common_pause else MR.strings.common_play),
                    modifier = Modifier.align(Alignment.BottomEnd).padding(ThreadInlineVideoControlInset)
                ) { RaftSlotIcon(if (playing) RaftLucideIconName.Pause else RaftLucideIconName.Play, size = 16.dp) }
            }
        // The player is preparing its first frame.
        !firstFrameVisible -> ThreadInlineVideoSpinner()
    }
}

@Composable
private fun ThreadInlineVideoSpinner() {
    RaftSpinner(size = RaftSpinnerSize.Md, color = ThreadInlineVideoInk)
}

/**
 * raft-ui `MessageVideoPreviewFailureContent`: a 24dp TriangleAlert, a white caption-semibold
 * title, then text actions in `white/75` with 13dp icons, 12dp apart.
 */
@Composable
private fun ThreadInlineVideoFailure(
    title: String,
    onDownload: () -> Unit,
    onRetry: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier.widthIn(max = 256.dp).padding(horizontal = RaftSpacing.Md),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(RaftSpacing.Sm)
    ) {
        RaftLucideIcon(
            RaftLucideIconName.TriangleAlert,
            modifier = Modifier.size(24.dp),
            color = raftArgbHex(ThreadInlineVideoInk)
        )
        RaftText(
            text = title,
            style = RaftTheme.typography.caption.copy(fontWeight = FontWeight.SemiBold),
            color = ThreadInlineVideoInk,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onRetry != null) {
                ThreadInlineVideoFailureAction(
                    icon = RaftLucideIconName.RotateCcw,
                    label = stringResource(MR.strings.common_retry),
                    onClick = onRetry
                )
            }
            ThreadInlineVideoFailureAction(
                icon = RaftLucideIconName.Download,
                label = stringResource(MR.strings.thread_video_download_to_view),
                onClick = onDownload
            )
        }
    }
}

/**
 * raft-ui `MessageVideoPreviewFailureAction`: icon and words in `white/75`, white and underlined
 * while pressed. RaftTextLink draws in the theme's ink, which the black box does not follow.
 */
@Composable
private fun ThreadInlineVideoFailureAction(
    icon: RaftLucideIconName,
    label: String,
    onClick: () -> Unit
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val ink = if (pressed) ThreadInlineVideoInk else ThreadInlineVideoMutedInk
    Row(
        modifier = Modifier.clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(RaftSpacing.Xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RaftLucideIcon(icon, modifier = Modifier.size(13.dp), color = raftArgbHex(ink))
        RaftText(
            text = label,
            style =
                RaftTheme.typography.caption.copy(
                    fontWeight = FontWeight.SemiBold,
                    textDecoration = if (pressed) TextDecoration.Underline else null
                ),
            color = ink,
            maxLines = 1
        )
    }
}
