package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import build.raft.app.components.SlockBrutalActionButton
import build.raft.app.components.SlockBrutalIconButton
import build.raft.app.resources.MR
import com.tencent.tmm.kmmresource.compose.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import build.raft.app.compat.ui.PlayState

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
    onDownload: () -> Unit
) {
    val host = LocalThreadInlineAudioPreviewHost.current
    val previewState = host?.states?.get(attachment.id)

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
            var playback by remember(source) { mutableStateOf(ThreadInlineAudioPlayback()) }
            var scrubTimeMs by remember(source) { mutableStateOf<Float?>(null) }
            val playState = playback.state
            val currentTimeMs = scrubTimeMs?.toInt() ?: playback.currentTimeMs
            val totalTimeMs = playback.totalTimeMs
            val isPlaying = playState == PlayState.PLAYING
            val canPlay = source.isNotBlank()
            val playerGeneration = playback.generation

            key(source, playerGeneration) {
                Box(modifier = Modifier.size(1.dp).alpha(0f)) {
                    SlockFilePreviewKuiklyVideo(
                        source = source,
                        playControl = playback.control,
                        seekRequest = playback.seekRequest,
                        onPlayStateChanged = {
                            playback = playback.withState(playerGeneration, it)
                        },
                        onPlayTimeChanged = { current, total ->
                            playback = playback.withTime(playerGeneration, current, total)
                        },
                        onFirstFrame = {},
                        modifier = Modifier.fillMaxSize()
                    )
                }
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
                val positionLabel = stringResource(MR.strings.thread_audio_playback_position)
                ThreadInlineAudioControls(
                    isPlaying = isPlaying,
                    canPlay = canPlay,
                    currentTimeMs = currentTimeMs,
                    totalTimeMs = totalTimeMs,
                    seekContentDescription = positionLabel,
                    onTogglePlay = { playback = playback.toggle() },
                    // Scrubbing previews the time label; the player only seeks on release.
                    onScrub = { scrubTimeMs = it?.toFloat() },
                    onSeek = { targetMs ->
                        scrubTimeMs = null
                        playback = playback.seekTo(targetMs)
                    }
                )
            }
        }
    }
}

/**
 * Web InlineAudioPlayer row: play button, seekable rail with a square thumb, and a
 * fixed-width "current / total" mono label.
 */
@Composable
internal fun ThreadInlineAudioControls(
    isPlaying: Boolean,
    canPlay: Boolean,
    currentTimeMs: Int,
    totalTimeMs: Int,
    onTogglePlay: () -> Unit,
    onSeek: (Int) -> Unit,
    modifier: Modifier = Modifier,
    seekContentDescription: String? = null,
    onScrub: (Int?) -> Unit = {}
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(SlockColor.SurfaceCream)
            .border(SlockStroke.Thin, SlockColor.BorderBlack)
            .padding(SlockSpacing.Sm),
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SlockBrutalIconButton(
            iconName = if (isPlaying) SlockLucideIconName.Pause else SlockLucideIconName.Play,
            onClick = onTogglePlay,
            enabled = canPlay,
            size = 28.dp,
            iconSize = 14.dp,
            shadowOffset = 0.dp,
            backgroundColor = SlockColor.SurfaceWhite
        )
        ThreadInlineAudioSeekBar(
            currentTimeMs = currentTimeMs,
            totalTimeMs = totalTimeMs,
            seekEnabled = canPlay && totalTimeMs > 0,
            onScrub = onScrub,
            onSeek = onSeek,
            modifier = Modifier
                .weight(1f)
                .semantics {
                    seekContentDescription?.let { contentDescription = it }
                    // Same accessibility seek action the Material Slider exposed; like the
                    // Slider's valueRange, the target is in milliseconds (0..totalTimeMs).
                    setProgress { target ->
                        if (canPlay && totalTimeMs > 0) {
                            onSeek(target.roundToInt().coerceIn(0, totalTimeMs))
                            true
                        } else {
                            false
                        }
                    }
                }
        )
        Text(
            text = formatAudioTimeLabel(currentTimeMs, totalTimeMs),
            style = SlockType.Caption.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 14.sp
            ).withSlockFontWeight(FontWeight.Bold),
            color = SlockColor.TextMutedGray,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(76.dp)
        )
    }
}

/**
 * Seekable progress rail matching web's audio-seek-rail: an 8dp bordered white
 * rail with a cyan fill and a 12dp square thumb, inside a 28dp touch target. The
 * rail is inset by half the thumb so the thumb never leaves the seek area. Tap
 * seeks; a horizontal drag follows the finger (onScrub) and seeks on release.
 * Pointer modifiers are always attached and gated by seekEnabled.
 */
@Composable
private fun ThreadInlineAudioSeekBar(
    currentTimeMs: Int,
    totalTimeMs: Int,
    seekEnabled: Boolean,
    onScrub: (Int?) -> Unit,
    onSeek: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var widthPx by remember { mutableStateOf(0f) }
    var dragX by remember { mutableStateOf<Float?>(null) }
    val thumbHalfPx = with(LocalDensity.current) { 6.dp.toPx() }
    val trackPx = (widthPx - 2 * thumbHalfPx).coerceAtLeast(0f)
    fun timeAt(x: Float): Int =
        (threadInlineAudioSeekFraction(x, widthPx, thumbHalfPx) * totalTimeMs).roundToInt().coerceIn(0, totalTimeMs.coerceAtLeast(0))
    val playedFraction =
        if (totalTimeMs > 0) (currentTimeMs.toFloat() / totalTimeMs.toFloat()).coerceIn(0f, 1f) else 0f
    val fraction = dragX?.let { threadInlineAudioSeekFraction(it, widthPx, thumbHalfPx) } ?: playedFraction
    Box(
        modifier = modifier
            .height(28.dp)
            .onGloballyPositioned { coordinates -> widthPx = coordinates.size.width.toFloat() }
            .pointerInput(seekEnabled, totalTimeMs) {
                detectTapGestures(onTap = { offset -> if (seekEnabled) onSeek(timeAt(offset.x)) })
            }
            .pointerInput(seekEnabled, totalTimeMs) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        if (seekEnabled) {
                            dragX = offset.x
                            onScrub(timeAt(offset.x))
                        }
                    },
                    onDragEnd = {
                        val x = dragX
                        dragX = null
                        if (seekEnabled && x != null) onSeek(timeAt(x)) else onScrub(null)
                    },
                    onDragCancel = {
                        dragX = null
                        onScrub(null)
                    }
                ) { change, _ ->
                    // Follow the finger's absolute position; accumulating dragAmount on
                    // top of onDragStart's offset double-counts the touch-slop distance.
                    if (seekEnabled) {
                        val x = change.position.x.coerceIn(0f, widthPx)
                        dragX = x
                        onScrub(timeAt(x))
                    }
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = 6.dp)
                .fillMaxWidth()
                .height(8.dp)
                .background(SlockColor.SurfaceWhite)
                .border(SlockStroke.Thin, SlockColor.BorderBlack)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(8.dp)
                    .background(SlockColor.AccentCyan)
            )
        }
        Box(
            modifier = Modifier
                .offset { IntOffset((fraction * trackPx).roundToInt(), 0) }
                .size(12.dp)
                .background(SlockColor.SurfaceWhite)
                .border(SlockStroke.Thin, SlockColor.BorderBlack)
        )
    }
}

/** Maps a touch x within the seek box to a 0..1 fraction of the thumb-inset track. */
internal fun threadInlineAudioSeekFraction(x: Float, widthPx: Float, thumbHalfPx: Float): Float {
    val trackPx = widthPx - 2 * thumbHalfPx
    if (trackPx <= 0f) return 0f
    return ((x - thumbHalfPx) / trackPx).coerceIn(0f, 1f)
}
