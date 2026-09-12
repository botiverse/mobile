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
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.foundation.layout.widthIn
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.draw.alpha
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.core.views.PlayState
import com.tencent.kuikly.core.views.VideoPlayControl

internal enum class ThreadInlineAudioPreviewStatus {
    Loading,
    Ready,
    Error
}

internal data class ThreadInlineAudioPreviewUiState(
    val status: ThreadInlineAudioPreviewStatus,
    val url: String = "",
    val expiresAt: String? = null
)

internal enum class ThreadInlineAudioRenderMode {
    Loading,
    Ready,
    Error
}

internal fun ThreadInlineAudioPreviewUiState?.audioRenderMode(): ThreadInlineAudioRenderMode =
    when (this?.status) {
        null -> ThreadInlineAudioRenderMode.Loading
        ThreadInlineAudioPreviewStatus.Loading -> ThreadInlineAudioRenderMode.Loading
        ThreadInlineAudioPreviewStatus.Ready ->
            if (url.isBlank()) ThreadInlineAudioRenderMode.Error else ThreadInlineAudioRenderMode.Ready
        ThreadInlineAudioPreviewStatus.Error -> ThreadInlineAudioRenderMode.Error
    }

internal fun Map<String, ThreadInlineAudioPreviewUiState>.canRequestInlineAudioPreview(
    attachmentId: String
): Boolean {
    if (attachmentId.isBlank()) return false
    return when (this[attachmentId]?.status) {
        ThreadInlineAudioPreviewStatus.Loading,
        ThreadInlineAudioPreviewStatus.Ready -> false
        ThreadInlineAudioPreviewStatus.Error,
        null -> true
    }
}

internal data class ThreadInlineAudioPreviewHost(
    val states: Map<String, ThreadInlineAudioPreviewUiState>,
    val requestPreview: (attachmentId: String, force: Boolean) -> Unit
)

internal val LocalThreadInlineAudioPreviewHost =
    compositionLocalOf<ThreadInlineAudioPreviewHost?> { null }

internal fun formatAudioDuration(ms: Int): String {
    val totalSeconds = (ms.coerceAtLeast(0) / 1000)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

internal fun formatAudioTimeLabel(currentTimeMs: Int, totalTimeMs: Int): String {
    val current = formatAudioDuration(currentTimeMs)
    return if (totalTimeMs > 0) {
        "$current / ${formatAudioDuration(totalTimeMs)}"
    } else {
        current
    }
}

@Composable
internal fun ThreadInlineAudioAttachmentCard(
    attachment: KuiklyThreadAttachmentItem,
    commentsEnabled: Boolean,
    onDownload: () -> Unit,
    onOpenComments: () -> Unit
) {
    val host = LocalThreadInlineAudioPreviewHost.current
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
                    .padding(SlockSpacing.Sm),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(SlockColor.SurfaceCream)
                        .border(SlockStroke.Default, SlockColor.BorderBlack),
                    contentAlignment = Alignment.Center
                ) {
                    SlockLucideIcon(
                        name = SlockLucideIconName.FileMusic,
                        modifier = Modifier.size(20.dp),
                        color = "#000000"
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = attachment.name.ifBlank { stringResource(MR.strings.thread_audio_attachment_fallback_name) },
                        style = SlockType.Body.withSlockFontWeight(FontWeight.Bold),
                        color = SlockColor.TextBlack,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val sizeLabel = attachment.displaySizeLabel()
                    val subtitle = if (sizeLabel.isNotBlank()) {
                        "${stringResource(MR.strings.thread_audio_file_label)} · $sizeLabel"
                    } else {
                        stringResource(MR.strings.thread_audio_file_label)
                    }
                    Text(
                        text = subtitle,
                        style = SlockType.Caption,
                        color = SlockColor.TextMutedGray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                SlockBrutalIconButton(
                    iconName = SlockLucideIconName.Download,
                    onClick = onDownload,
                    contentDescription = stringResource(MR.strings.file_preview_action_download),
                    backgroundColor = SlockColor.SurfaceWhite,
                    size = 32.dp,
                    shadowOffset = 0.dp
                )
            }

            ThreadInlineAudioSurface(
                previewState = previewState,
                previewEnabled = host != null,
                onRetry = { host?.requestPreview?.invoke(attachment.id, true) },
                onDownload = onDownload
            )
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
private fun ThreadInlineAudioSurface(
    previewState: ThreadInlineAudioPreviewUiState?,
    previewEnabled: Boolean,
    onRetry: () -> Unit,
    onDownload: () -> Unit
) {
    val renderMode = previewState.audioRenderMode()

    when {
        !previewEnabled || renderMode == ThreadInlineAudioRenderMode.Error -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SlockColor.SurfaceCream)
                    .border(SlockStroke.Thin, SlockColor.BorderBlack)
                    .padding(SlockSpacing.Xs),
                horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SlockBrutalActionButton(
                    label = stringResource(MR.strings.common_retry),
                    onClick = onRetry,
                    modifier = Modifier.weight(1f),
                    backgroundColor = SlockColor.SurfaceWhite,
                    minWidth = 0.dp,
                    minHeight = 32.dp,
                    leadingIcon = SlockLucideIconName.RefreshCw,
                    shadowOffset = 0.dp,
                    textStyle = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(color = SlockColor.TextBlack)
                )
                SlockBrutalActionButton(
                    label = stringResource(MR.strings.file_preview_action_download),
                    onClick = onDownload,
                    modifier = Modifier.weight(1f),
                    backgroundColor = SlockColor.SurfaceWhite,
                    minWidth = 0.dp,
                    minHeight = 32.dp,
                    leadingIcon = SlockLucideIconName.Download,
                    shadowOffset = 0.dp,
                    textStyle = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(color = SlockColor.TextBlack)
                )
            }
        }
        renderMode == ThreadInlineAudioRenderMode.Loading -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .background(SlockColor.SurfaceCream)
                    .border(SlockStroke.Thin, SlockColor.BorderBlack)
                    .padding(horizontal = SlockSpacing.Sm),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SlockLucideIcon(
                    name = SlockLucideIconName.LoaderCircle,
                    modifier = Modifier.size(16.dp),
                    color = "#000000",
                    spinning = true
                )
                Text(
                    text = stringResource(MR.strings.thread_audio_preview_loading),
                    style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold),
                    color = SlockColor.TextMutedGray,
                    modifier = Modifier.padding(start = SlockSpacing.Xs)
                )
            }
        }
        renderMode == ThreadInlineAudioRenderMode.Ready -> {
            val source = previewState?.url.orEmpty()
            var playControl by remember(source) { mutableStateOf(VideoPlayControl.PREPLAY) }
            var playState by remember(source) { mutableStateOf(PlayState.NONE) }
            var currentTimeMs by remember(source) { mutableStateOf(0) }
            var totalTimeMs by remember(source) { mutableStateOf(0) }
            val isPlaying = playState == PlayState.PLAYING
            val canPlay = source.isNotBlank()

            Box(modifier = Modifier.size(1.dp).alpha(0f)) {
                SlockFilePreviewKuiklyVideo(
                    source = source,
                    playControl = playControl,
                    seekRequest = null,
                    onPlayStateChanged = { playState = it },
                    onPlayTimeChanged = { current, total ->
                        currentTimeMs = current
                        totalTimeMs = total
                    },
                    onFirstFrame = {},
                    modifier = Modifier.fillMaxSize()
                )
            }

            if (playState == PlayState.ERROR) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SlockColor.SurfaceCream)
                        .border(SlockStroke.Thin, SlockColor.BorderBlack)
                        .padding(SlockSpacing.Xs),
                    horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SlockBrutalActionButton(
                        label = stringResource(MR.strings.common_retry),
                        onClick = onRetry,
                        modifier = Modifier.weight(1f),
                        backgroundColor = SlockColor.SurfaceWhite,
                        minWidth = 0.dp,
                        minHeight = 32.dp,
                        leadingIcon = SlockLucideIconName.RefreshCw,
                        shadowOffset = 0.dp,
                        textStyle = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(color = SlockColor.TextBlack)
                    )
                    SlockBrutalActionButton(
                        label = stringResource(MR.strings.file_preview_action_download),
                        onClick = onDownload,
                        modifier = Modifier.weight(1f),
                        backgroundColor = SlockColor.SurfaceWhite,
                        minWidth = 0.dp,
                        minHeight = 32.dp,
                        leadingIcon = SlockLucideIconName.Download,
                        shadowOffset = 0.dp,
                        textStyle = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(color = SlockColor.TextBlack)
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SlockColor.SurfaceCream)
                        .border(SlockStroke.Thin, SlockColor.BorderBlack)
                        .padding(horizontal = SlockSpacing.Sm, vertical = SlockSpacing.Xs),
                    verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SlockBrutalIconButton(
                            iconName = if (isPlaying) SlockLucideIconName.Pause else SlockLucideIconName.Play,
                            onClick = {
                                playControl =
                                    if (isPlaying) {
                                        VideoPlayControl.PAUSE
                                    } else {
                                        VideoPlayControl.PLAY
                                    }
                            },
                            enabled = canPlay,
                            size = 32.dp,
                            shadowOffset = 0.dp,
                            backgroundColor = SlockColor.SurfaceWhite
                        )
                        Text(
                            text = formatAudioTimeLabel(currentTimeMs, totalTimeMs),
                            style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold),
                            color = SlockColor.TextBlack,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    val progressFraction =
                        if (totalTimeMs > 0) {
                            (currentTimeMs.toFloat() / totalTimeMs.toFloat()).coerceIn(0f, 1f)
                        } else {
                            0f
                        }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .background(SlockColor.EmptyStateGray)
                    ) {
                        if (progressFraction > 0f) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(progressFraction)
                                    .height(4.dp)
                                    .background(SlockColor.TextBlack)
                            )
                        }
                    }
                }
            }
        }
    }
}
