package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import build.raft.app.components.SlockBrutalActionButton
import build.raft.app.components.SlockBrutalIconButton
import build.raft.app.resources.MR
import com.tencent.tmm.kmmresource.compose.stringResource
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.aspectRatio
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.foundation.layout.widthIn
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.core.views.PlayState
import com.tencent.kuikly.core.views.VideoPlayControl

internal enum class ThreadInlineVideoPreviewStatus {
    Loading,
    Ready,
    Error
}

internal data class ThreadInlineVideoPreviewUiState(
    val status: ThreadInlineVideoPreviewStatus,
    val url: String = "",
    val expiresAt: String? = null
)

internal enum class ThreadInlineVideoRenderMode {
    Loading,
    Ready,
    Error
}

internal fun ThreadInlineVideoPreviewUiState?.renderMode(): ThreadInlineVideoRenderMode =
    when (this?.status) {
        null -> ThreadInlineVideoRenderMode.Loading
        ThreadInlineVideoPreviewStatus.Loading -> ThreadInlineVideoRenderMode.Loading
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
        ThreadInlineVideoPreviewStatus.Ready -> false
        ThreadInlineVideoPreviewStatus.Error,
        null -> true
    }
}

internal data class ThreadInlineVideoPreviewHost(
    val states: Map<String, ThreadInlineVideoPreviewUiState>,
    val requestPreview: (attachmentId: String, force: Boolean) -> Unit
)

internal val LocalThreadInlineVideoPreviewHost =
    compositionLocalOf<ThreadInlineVideoPreviewHost?> { null }

@Composable
internal fun ThreadInlineVideoAttachmentCard(
    attachment: KuiklyThreadAttachmentItem,
    commentsEnabled: Boolean,
    onDownload: () -> Unit,
    onOpenComments: () -> Unit
) {
    val host = LocalThreadInlineVideoPreviewHost.current
    val previewState = host?.states?.get(attachment.id)
    val canOpenComments = commentsEnabled

    LaunchedEffect(attachment.id, host != null) {
        if (previewState == null) {
            host?.requestPreview?.invoke(attachment.id, false)
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth().widthIn(max = 360.dp),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(SlockColor.SurfaceWhite)
                    .border(SlockStroke.Default, SlockColor.BorderBlack)
        ) {
            ThreadInlineVideoSurface(
                attachment = attachment,
                previewState = previewState,
                previewEnabled = host != null,
                onRetry = { host?.requestPreview?.invoke(attachment.id, true) },
                onDownload = onDownload
            )
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = SlockSpacing.Sm, vertical = SlockSpacing.Xs),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = attachment.name.ifBlank { stringResource(MR.strings.thread_video_attachment_fallback_name) },
                    style = SlockType.Body.withSlockFontWeight(FontWeight.Bold),
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                attachment.threadAttachmentChipMetaLine(stringResource(MR.strings.file_preview_html)).takeIf { it.isNotBlank() }?.let { meta ->
                    Text(
                        text = meta,
                        style = SlockType.Caption,
                        color = SlockColor.TextMutedGray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        if (canOpenComments) {
            SlockBrutalActionButton(
                label = attachment.threadAttachmentCommentCountLabel() ?: "Comments",
                onClick = onOpenComments,
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = SlockColor.SurfaceCream,
                minWidth = 0.dp,
                minHeight = 30.dp,
                leadingIcon = SlockLucideIconName.MessageCircle,
                shadowOffset = 0.dp,
                textStyle = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(color = SlockColor.TextBlack)
            )
        }
    }
}

@Composable
private fun ThreadInlineVideoSurface(
    attachment: KuiklyThreadAttachmentItem,
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
    val nativeController = slockPlatformUsesNativeVideoController()

    Box(
        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color(0xFF000000)),
        contentAlignment = Alignment.Center
    ) {
        if (renderMode == ThreadInlineVideoRenderMode.Ready) {
            SlockFilePreviewKuiklyVideo(
                source = source,
                playControl = playControl,
                seekRequest = null,
                onPlayStateChanged = { playState = it },
                onPlayTimeChanged = { _, _ -> },
                onFirstFrame = { firstFrameVisible = true },
                modifier = Modifier.fillMaxSize()
            )
        }

        when {
            !previewEnabled ->
                ThreadInlineVideoOverlay(
                    iconName = SlockLucideIconName.TriangleAlert,
                    label = stringResource(MR.strings.thread_video_preview_unavailable),
                    secondaryActionIcon = SlockLucideIconName.Download,
                    secondaryActionLabel = stringResource(MR.strings.file_preview_action_download),
                    onSecondaryAction = onDownload
                )
            renderMode == ThreadInlineVideoRenderMode.Error ->
                ThreadInlineVideoOverlay(
                    iconName = SlockLucideIconName.TriangleAlert,
                    label = stringResource(MR.strings.thread_video_preview_unavailable),
                    actionIcon = SlockLucideIconName.RefreshCw,
                    actionLabel = stringResource(MR.strings.common_retry),
                    onAction = onRetry,
                    secondaryActionIcon = SlockLucideIconName.Download,
                    secondaryActionLabel = stringResource(MR.strings.file_preview_action_download),
                    onSecondaryAction = onDownload
                )
            renderMode == ThreadInlineVideoRenderMode.Loading ->
                ThreadInlineVideoOverlay(
                    iconName = SlockLucideIconName.LoaderCircle,
                    label = stringResource(MR.strings.thread_video_preview_loading),
                    spinning = true
                )
            playState == PlayState.ERROR ->
                ThreadInlineVideoOverlay(
                    iconName = SlockLucideIconName.TriangleAlert,
                    label = stringResource(MR.strings.thread_video_playback_failed),
                    actionIcon = SlockLucideIconName.RefreshCw,
                    actionLabel = stringResource(MR.strings.common_retry),
                    onAction = onRetry,
                    secondaryActionIcon = SlockLucideIconName.Download,
                    secondaryActionLabel = stringResource(MR.strings.file_preview_action_download),
                    onSecondaryAction = onDownload
                )
            !nativeController ->
                SlockBrutalIconButton(
                    iconName = if (playState == PlayState.PLAYING) SlockLucideIconName.Pause else SlockLucideIconName.Play,
                    onClick = {
                        playControl =
                            if (playState == PlayState.PLAYING) {
                                VideoPlayControl.PAUSE
                            } else {
                                VideoPlayControl.PLAY
                            }
                    },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(SlockSpacing.Sm),
                    backgroundColor = SlockColor.SurfaceWhite,
                    size = 34.dp,
                    shadowOffset = 0.dp
                )
            !firstFrameVisible ->
                ThreadInlineVideoOverlay(
                    iconName = SlockLucideIconName.LoaderCircle,
                    label = attachment.name.ifBlank { "Preparing video" },
                    spinning = true
                )
        }
    }
}

@Composable
private fun ThreadInlineVideoOverlay(
    iconName: SlockLucideIconName,
    label: String,
    spinning: Boolean = false,
    actionIcon: SlockLucideIconName? = null,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
    secondaryActionIcon: SlockLucideIconName? = null,
    secondaryActionLabel: String? = null,
    onSecondaryAction: () -> Unit = {}
) {
    Column(
        modifier = Modifier.background(Color(0xCC000000)).padding(SlockSpacing.Sm),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
    ) {
        SlockLucideIcon(
            iconName,
            modifier = Modifier.size(28.dp),
            color = "#FFFFFFFF",
            spinning = spinning
        )
        Text(
            text = label,
            style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold),
            color = SlockColor.SurfaceWhite,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (actionIcon != null || secondaryActionIcon != null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                actionIcon?.let { icon ->
                    SlockBrutalIconButton(
                        iconName = icon,
                        onClick = onAction,
                        contentDescription = actionLabel,
                        backgroundColor = SlockColor.SurfaceWhite,
                        size = 34.dp,
                        shadowOffset = 0.dp
                    )
                }
                secondaryActionIcon?.let { icon ->
                    SlockBrutalIconButton(
                        iconName = icon,
                        onClick = onSecondaryAction,
                        contentDescription = secondaryActionLabel,
                        backgroundColor = SlockColor.SurfaceWhite,
                        size = 34.dp,
                        shadowOffset = 0.dp
                    )
                }
            }
        }
    }
}
