package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import build.raft.app.compat.ui.PlayState
import build.raft.app.compat.ui.VideoPlayControl
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.components.RaftSlotIcon
import build.raft.app.resources.MR
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftSpinner
import build.raft.designsystem.components.RaftSpinnerSize
import build.raft.designsystem.gesture.raftKeepsBackSwipe
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftSpacing
import build.raft.designsystem.tokens.RaftStroke
import build.raft.designsystem.tokens.RaftUiPalette
import com.tencent.tmm.kmmresource.compose.stringResource
import kotlin.math.roundToInt

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

/** Plays the inline audio off screen: the platform player, or a scripted one in screenshot fixtures. */
internal typealias ThreadInlineAudioPlayer =
    @Composable (
        source: String,
        playControl: VideoPlayControl,
        seekRequest: RaftFilePreviewVideoSeekRequest?,
        onPlayStateChanged: (PlayState) -> Unit,
        onPlayTimeChanged: (currentMs: Int, totalMs: Int) -> Unit,
        modifier: Modifier
    ) -> Unit

internal val LocalThreadInlineAudioPlayer =
    staticCompositionLocalOf<ThreadInlineAudioPlayer> {
        { source, playControl, seekRequest, onPlayStateChanged, onPlayTimeChanged, modifier ->
            RaftFilePreviewKuiklyVideo(
                source = source,
                playControl = playControl,
                seekRequest = seekRequest,
                onPlayStateChanged = onPlayStateChanged,
                onPlayTimeChanged = onPlayTimeChanged,
                onFirstFrame = {},
                modifier = modifier
            )
        }
    }

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


/**
 * The inline audio of a message (web `InlineAudioAttachmentCard`): a card (`rounded-container`,
 * `line-hairline`, `layer-card`, `p-2.5`) with a header (a muted icon tile, the name in callout
 * semibold over a mono-meta hint line, a ghost download button) above the player well.
 */
@Composable
internal fun ThreadInlineAudioAttachmentCard(
    attachment: RaftThreadAttachmentItem,
    onDownload: () -> Unit
) {
    val host = LocalThreadInlineAudioPreviewHost.current
    val previewState = host?.states?.get(attachment.id)

    LaunchedEffect(attachment.id, host != null) {
        if (previewState == null) {
            host?.requestPreview?.invoke(attachment.id, false)
        }
    }

    RaftDesignSystemTheme {
        val colors = RaftTheme.colors
        val shape = RoundedCornerShape(RaftRadius.Container)
        // The surface reports a failed preview or playback so the header drops its download
        // button, as the web's does: the well's "Download to View" takes its place.
        var failed by remember(attachment.id) { mutableStateOf(false) }
        Column(
            modifier =
                Modifier
                    .widthIn(max = ThreadInlineAudioMaxWidth)
                    .fillMaxWidth()
                    .clip(shape)
                    .background(colors.layerCard)
                    .border(RaftStroke.Hairline, colors.lineHairline, shape)
                    .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(RaftSpacing.Sm)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(RaftSpacing.Sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Web: a 36dp `rounded-chip` tile on `fill-muted` with Music 18 in
                // `foreground-muted` (not raft-ui IconTile, which is 35dp with the sticker radius).
                Box(
                    modifier =
                        Modifier
                            .size(ThreadInlineAudioIconTile)
                            .background(colors.fillMuted, RoundedCornerShape(RaftRadius.Chip)),
                    contentAlignment = Alignment.Center
                ) { RaftSlotIcon(RaftLucideIconName.Music, size = 18.dp, color = colors.foregroundMuted) }
                Column(modifier = Modifier.weight(1f)) {
                    RaftText(
                        text = attachment.name.ifBlank { stringResource(MR.strings.thread_audio_attachment_fallback_name) },
                        style = RaftTheme.typography.callout.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.foreground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    // Web: "Audio file" alone, without the size.
                    RaftText(
                        text = stringResource(MR.strings.thread_audio_file_label),
                        style = RaftTheme.typography.monoMeta,
                        color = colors.foregroundHint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (!failed) {
                    RaftIconButton(
                        onClick = onDownload,
                        tooltip = stringResource(MR.strings.file_preview_action_download),
                        variant = RaftButtonVariant.Ghost
                    ) { RaftSlotIcon(RaftLucideIconName.Download, size = 18.dp) }
                }
            }

            ThreadInlineAudioSurface(
                previewState = previewState,
                previewEnabled = host != null,
                onRetry = { host?.requestPreview?.invoke(attachment.id, true) },
                onDownload = onDownload,
                onFailedChange = { failed = it }
            )
        }
    }
}

/** Web `max-w-[min(28rem, …)]`; the message column is narrower on a phone. */
private val ThreadInlineAudioMaxWidth = 448.dp

private val ThreadInlineAudioIconTile = 36.dp

@Composable
private fun ThreadInlineAudioSurface(
    previewState: ThreadInlineAudioPreviewUiState?,
    previewEnabled: Boolean,
    onRetry: () -> Unit,
    onDownload: () -> Unit,
    onFailedChange: (Boolean) -> Unit
) {
    val renderMode = previewState.audioRenderMode()
    val previewFailed = !previewEnabled || renderMode == ThreadInlineAudioRenderMode.Error

    when {
        previewFailed -> ThreadInlineAudioFailedWell(onRetry = onRetry.takeIf { previewEnabled }, onDownload = onDownload)
        renderMode == ThreadInlineAudioRenderMode.Loading -> ThreadInlineAudioLoadingWell()
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
                    LocalThreadInlineAudioPlayer.current(
                        source,
                        playback.control,
                        playback.seekRequest,
                        { playback = playback.withState(playerGeneration, it) },
                        { current, total -> playback = playback.withTime(playerGeneration, current, total) },
                        Modifier.fillMaxSize()
                    )
                }
            }

            if (playState == PlayState.ERROR) {
                ThreadInlineAudioFailedWell(onRetry = onRetry, onDownload = onDownload)
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
            LaunchedEffect(playState == PlayState.ERROR) { onFailedChange(playState == PlayState.ERROR) }
        }
    }
    LaunchedEffect(previewFailed) { if (renderMode != ThreadInlineAudioRenderMode.Ready) onFailedChange(previewFailed) }
}

/** The player well: `fill-muted`, `rounded-control`, no edge. */
@Composable
private fun ThreadInlineAudioWell(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 32.dp)
                .background(RaftTheme.colors.fillMuted, RoundedCornerShape(RaftRadius.Control)),
        contentAlignment = Alignment.Center
    ) { content() }
}

/** Web: the well as a 32dp ghost button holding a spinner and the loading line. */
@Composable
private fun ThreadInlineAudioLoadingWell() {
    ThreadInlineAudioWell {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(RaftSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RaftSpinner(size = RaftSpinnerSize.Xs, color = RaftTheme.colors.foregroundMuted)
            RaftText(
                text = stringResource(MR.strings.thread_audio_preview_loading),
                style = RaftTheme.typography.footnote.copy(fontWeight = FontWeight.Bold),
                color = RaftTheme.colors.foregroundMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Web: a failed preview turns the well into a ghost "Download to View" button. Mobile keeps
 * a Retry beside it ([onRetry], when the preview can be fetched again).
 */
@Composable
private fun ThreadInlineAudioFailedWell(
    onRetry: (() -> Unit)?,
    onDownload: () -> Unit
) {
    ThreadInlineAudioWell {
        Row(modifier = Modifier.fillMaxWidth()) {
            if (onRetry != null) {
                RaftButton(
                    text = stringResource(MR.strings.common_retry),
                    onClick = onRetry,
                    variant = RaftButtonVariant.Ghost,
                    modifier = Modifier.weight(1f),
                    leadingIcon = { RaftSlotIcon(RaftLucideIconName.RotateCcw) }
                )
            }
            RaftButton(
                text = stringResource(MR.strings.thread_audio_download_to_view),
                onClick = onDownload,
                variant = RaftButtonVariant.Ghost,
                modifier = Modifier.weight(1f),
                leadingIcon = { RaftSlotIcon(RaftLucideIconName.Download) }
            )
        }
    }
}

/**
 * Web `InlineAudioPlayer`, in the muted well (`p-2`, gap 8): a 32dp outline play button, the
 * seek rail, and a fixed-width (4.75rem) "current / total" in mono-meta hint.
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
    ThreadInlineAudioWell(modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(RaftSpacing.Sm),
            horizontalArrangement = Arrangement.spacedBy(RaftSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RaftIconButton(
                onClick = onTogglePlay,
                tooltip = stringResource(if (isPlaying) MR.strings.common_pause else MR.strings.common_play),
                enabled = canPlay
            ) { RaftSlotIcon(if (isPlaying) RaftLucideIconName.Pause else RaftLucideIconName.Play, size = 16.dp) }
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
            RaftText(
                text = formatAudioTimeLabel(currentTimeMs, totalTimeMs),
                style = RaftTheme.typography.monoMeta,
                color = RaftTheme.colors.foregroundHint,
                textAlign = TextAlign.End,
                maxLines = 1,
                modifier = Modifier.width(76.dp)
            )
        }
    }
}

/** The thumb: 14dp, radius 2, a 1.5dp `line-strong` edge on `layer-card`. */
private val ThreadInlineAudioThumb = 14.dp

/**
 * Web `audio-seek-rail`: an 8dp `layer-card` rail (radius 4, hairline edge) with an `info`
 * fill and the square thumb, inside a 32dp touch target. The rail is inset by half the thumb
 * so the thumb never leaves the seek area. Tap seeks; a horizontal drag follows the finger
 * (onScrub) and seeks on release. Pointer modifiers are always attached and gated by
 * seekEnabled.
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
    val colors = RaftTheme.colors
    var widthPx by remember { mutableStateOf(0f) }
    var dragX by remember { mutableStateOf<Float?>(null) }
    val thumbHalfPx = with(LocalDensity.current) { (ThreadInlineAudioThumb / 2).toPx() }
    val trackPx = (widthPx - 2 * thumbHalfPx).coerceAtLeast(0f)
    fun timeAt(x: Float): Int =
        (threadInlineAudioSeekFraction(x, widthPx, thumbHalfPx) * totalTimeMs).roundToInt().coerceIn(0, totalTimeMs.coerceAtLeast(0))
    val playedFraction =
        if (totalTimeMs > 0) (currentTimeMs.toFloat() / totalTimeMs.toFloat()).coerceIn(0f, 1f) else 0f
    val fraction = dragX?.let { threadInlineAudioSeekFraction(it, widthPx, thumbHalfPx) } ?: playedFraction
    Box(
        modifier = modifier
            .height(32.dp)
            .onGloballyPositioned { coordinates -> widthPx = coordinates.size.width.toFloat() }
            .pointerInput(seekEnabled, totalTimeMs) {
                detectTapGestures(onTap = { offset -> if (seekEnabled) onSeek(timeAt(offset.x)) })
            }
            // The seek bar keeps horizontal drags from the host back swipe.
            .raftKeepsBackSwipe(seekEnabled)
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
        val railShape = RoundedCornerShape(4.dp)
        Box(
            modifier = Modifier
                .padding(horizontal = ThreadInlineAudioThumb / 2)
                .fillMaxWidth()
                .height(8.dp)
                .clip(railShape)
                .background(colors.layerCard)
                .border(RaftStroke.Hairline, colors.lineHairline, railShape)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(8.dp)
                    .background(RaftUiPalette.info)
            )
        }
        val thumbShape = RoundedCornerShape(RaftRadius.Xs)
        Box(
            modifier = Modifier
                .offset { IntOffset((fraction * trackPx).roundToInt(), 0) }
                .size(ThreadInlineAudioThumb)
                .background(colors.layerCard, thumbShape)
                .border(RaftStroke.Control, colors.lineStrong, thumbShape)
        )
    }
}

/** Maps a touch x within the seek box to a 0..1 fraction of the thumb-inset track. */
internal fun threadInlineAudioSeekFraction(x: Float, widthPx: Float, thumbHalfPx: Float): Float {
    val trackPx = widthPx - 2 * thumbHalfPx
    if (trackPx <= 0f) return 0f
    return ((x - thumbHalfPx) / trackPx).coerceIn(0f, 1f)
}
