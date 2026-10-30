package build.raft.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import build.raft.app.compat.ui.PlayState
import build.raft.app.compat.ui.VideoPlayControl
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.components.RaftSlotIcon
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftSpacing
import build.raft.designsystem.tokens.RaftUiPalette
import io.github.kdroidfilter.composemediaplayer.InitialPlayerState
import io.github.kdroidfilter.composemediaplayer.VideoPlayerError
import io.github.kdroidfilter.composemediaplayer.VideoPlayerState
import io.github.kdroidfilter.composemediaplayer.VideoPlayerSurface
import io.github.kdroidfilter.composemediaplayer.createVideoPlayerState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Desktop video / audio player: ComposeMediaPlayer (kdroidFilter, MIT) drawing AVFoundation frames
 * into Compose on macOS 14+, with Raft's own controller over the video (play / pause, seek bar, time,
 * mute and volume, full screen; Space toggles play once the video has focus, a click focuses it;
 * Escape is left to the page, which closes the preview, or to the full-screen window, which leaves
 * full screen). The shared minimal controls hide themselves via raftPlatformUsesNativeVideoController().
 *
 * Where the player cannot run ([RaftDesktopVideoSupport.available] false) video attachments are
 * download cards, so this falls back to a black box only if a page reaches it anyway.
 */
@Composable
internal actual fun RaftVideoPlayer(
    source: String,
    playControl: VideoPlayControl,
    seekRequestId: Int?,
    seekTimeMs: Int,
    muted: Boolean,
    rate: Float,
    onPlayStateChanged: (PlayState) -> Unit,
    onPlayTimeChanged: (currentMs: Int, totalMs: Int) -> Unit,
    onFirstFrame: () -> Unit,
    modifier: Modifier,
) {
    val audioOnly = LocalRaftVideoPlayerAudioOnly.current
    if (RaftDesktopVideoSupport.route(source, audioOnly) == RaftDesktopMediaRoute.NotAvailableNotice) {
        RaftDesktopVideoNotAvailable(modifier)
        return
    }
    val stateCallback by rememberUpdatedState(onPlayStateChanged)
    val timeCallback by rememberUpdatedState(onPlayTimeChanged)
    val firstFrameCallback by rememberUpdatedState(onFirstFrame)
    key(source) {
        val playerState = remember { createVideoPlayerState() }
        val controller = remember { RaftDesktopVideoController(source, RaftComposeMediaPlayerEngine(playerState)) }
        DisposableEffect(controller) {
            playerState.loop = false
            playerState.onPlaybackEnded = { controller.onEnded() }
            onDispose {
                playerState.onPlaybackEnded = null
                controller.release()
            }
        }
        LaunchedEffect(controller) {
            var lastState: PlayState? = null
            var lastTime = -1 to -1
            var firstFrameReported = false
            while (isActive) {
                val snapshot = controller.snapshot()
                if (snapshot.state != lastState) {
                    lastState = snapshot.state
                    stateCallback(snapshot.state)
                }
                if (snapshot.currentMs to snapshot.totalMs != lastTime) {
                    lastTime = snapshot.currentMs to snapshot.totalMs
                    timeCallback(snapshot.currentMs, snapshot.totalMs)
                }
                if (snapshot.firstFrame && !firstFrameReported) {
                    firstFrameReported = true
                    firstFrameCallback()
                }
                delay(RAFT_DESKTOP_VIDEO_TICK_MS)
            }
        }
        LaunchedEffect(controller, playControl) { controller.applyPlayControl(playControl) }
        LaunchedEffect(controller, seekRequestId) {
            if (seekRequestId != null) controller.applySeekRequest(seekRequestId, seekTimeMs)
        }
        LaunchedEffect(controller, muted) { controller.setHostMuted(muted) }
        LaunchedEffect(controller, rate) { controller.setRate(rate) }
        // A source with no video track (audio-only behind a video name or MIME) gets the notice.
        if (controller.noVideoTrack) {
            RaftDesktopVideoNotAvailable(modifier)
        } else {
            VideoPlayerSurface(
                playerState = playerState,
                modifier = modifier.background(RaftUiPalette.black), // raft-color: video surface is black in both modes
                overlay = { RaftDesktopVideoControls(controller, playerState) },
            )
        }
    }
}

private const val RAFT_DESKTOP_VIDEO_TICK_MS = 250L

/** How long the controls stay up after the pointer last moved while the video plays. */
private const val RAFT_DESKTOP_VIDEO_CONTROLS_IDLE_MS = 2500L

/** ComposeMediaPlayer's state behind [RaftDesktopVideoEngine]. */
private class RaftComposeMediaPlayerEngine(private val state: VideoPlayerState) : RaftDesktopVideoEngine {
    override val hasMedia: Boolean get() = state.hasMedia
    override val isPlaying: Boolean get() = state.isPlaying
    override val isLoading: Boolean get() = state.isLoading
    override val failed: Boolean get() = state.error != null
    override val failedWithoutVideo: Boolean get() = raftComposeMediaPlayerNoVideoTrack(state.error)
    override val positionSeconds: Double get() = state.currentTime
    override val durationSeconds: Double get() = state.duration
    override var volume: Float
        get() = state.volume
        set(value) {
            state.volume = value
        }
    override var speed: Float
        get() = state.playbackSpeed
        set(value) {
            state.playbackSpeed = value
        }

    override fun open(uri: String, autoplay: Boolean) =
        state.openUri(uri, if (autoplay) InitialPlayerState.PLAY else InitialPlayerState.PAUSE)

    override fun play() = state.play()

    override fun pause() = state.pause()

    // The library's seek position runs 0..1000.
    override fun seekToFraction(fraction: Float) = state.seekTo(fraction * 1000f)

    override fun stop() = state.stop()

    override fun clearError() = state.clearError()

    override fun release() = state.dispose()
}

/** Desktop's notice from before it had a video player, unchanged (audio still cannot play). */
@Composable
private fun RaftDesktopVideoNotAvailable(modifier: Modifier) {
    RaftDesktopUnsupportedNotice("Video playback is not available on desktop yet.", modifier)
}

// ComposeMediaPlayer 0.11.4 (MacVideoPlayerState.openUriInternal) reports media whose video size
// never resolves, i.e. no video track, with this message. Re-check when bumping the library.
private const val RAFT_COMPOSE_MEDIA_PLAYER_NO_VIDEO_MESSAGE = "Failed to retrieve video size"

private fun raftComposeMediaPlayerNoVideoTrack(error: VideoPlayerError?): Boolean =
    error is VideoPlayerError.UnknownError && error.message.startsWith(RAFT_COMPOSE_MEDIA_PLAYER_NO_VIDEO_MESSAGE)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun RaftDesktopVideoControls(controller: RaftDesktopVideoController, playerState: VideoPlayerState) {
    val focusRequester = remember { FocusRequester() }
    var hovering by remember { mutableStateOf(false) }
    var pointerMoves by remember { mutableIntStateOf(0) }
    var idle by remember { mutableStateOf(false) }
    LaunchedEffect(pointerMoves) {
        idle = false
        delay(RAFT_DESKTOP_VIDEO_CONTROLS_IDLE_MS)
        idle = true
    }
    // Full screen opens its own window with this overlay in it: take the keys there.
    LaunchedEffect(playerState.isFullscreen) {
        if (playerState.isFullscreen) runCatching { focusRequester.requestFocus() }
    }
    val showControls = !controller.isPlaying || controller.failed || (hovering && !idle)
    RaftDesignSystemTheme(forceDark = true) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .focusRequester(focusRequester)
                    .focusable()
                    .onKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                        when (raftDesktopVideoKeyAction(event.key)) {
                            RaftDesktopVideoKeyAction.TogglePlay -> {
                                controller.togglePlay()
                                pointerMoves++
                                true
                            }
                            null -> false
                        }
                    }
                    .onPointerEvent(PointerEventType.Enter) { hovering = true; pointerMoves++ }
                    .onPointerEvent(PointerEventType.Move) { hovering = true; pointerMoves++ }
                    .onPointerEvent(PointerEventType.Exit) { hovering = false }
                    .pointerInput(controller) {
                        detectTapGestures {
                            runCatching { focusRequester.requestFocus() }
                            controller.togglePlay()
                        }
                    }
        ) {
            if (showControls) {
                RaftDesktopVideoControlBar(
                    controller = controller,
                    fullscreen = playerState.isFullscreen,
                    onToggleFullscreen = { playerState.toggleFullscreen() },
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }
    }
}

@Composable
private fun RaftDesktopVideoControlBar(
    controller: RaftDesktopVideoController,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val playing = controller.isPlaying
    val failed = controller.failed
    val total = controller.durationMs
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(RaftUiPalette.black.copy(alpha = 0.6f)) // raft-color: scrim over video, black in both modes
                .padding(horizontal = RaftSpacing.Sm, vertical = RaftSpacing.Xs)
    ) {
        RaftDesktopVideoScrubber(
            fraction = controller.progress,
            enabled = total > 0 && !failed,
            contentDescription = stringResource(Res.string.video_player_position),
            onChange = controller::seekToFraction,
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(RaftSpacing.Xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val playLabel =
                stringResource(
                    when {
                        failed -> Res.string.common_retry
                        playing -> Res.string.common_pause
                        else -> Res.string.common_play
                    }
                )
            RaftIconButton(onClick = controller::togglePlay, tooltip = playLabel, variant = RaftButtonVariant.Ghost) {
                RaftSlotIcon(
                    when {
                        failed -> RaftLucideIconName.RotateCcw
                        playing -> RaftLucideIconName.Pause
                        else -> RaftLucideIconName.Play
                    }
                )
            }
            RaftText(
                text = "${raftDesktopVideoTimeLabel(controller.positionMs, total)} / ${raftDesktopVideoTimeLabel(total, total)}",
                style = RaftTheme.typography.monoMeta,
                color = RaftUiPalette.white, // raft-color: ink on the video scrim
                maxLines = 1,
                overflow = TextOverflow.Clip
            )
            Spacer(Modifier.weight(1f))
            val muted = controller.muted
            RaftIconButton(
                onClick = controller::toggleMuted,
                tooltip = stringResource(if (muted) Res.string.video_player_unmute else Res.string.video_player_mute),
                variant = RaftButtonVariant.Ghost
            ) { RaftSlotIcon(if (muted) RaftLucideIconName.VolumeX else RaftLucideIconName.Volume2) }
            RaftDesktopVideoScrubber(
                fraction = if (muted) 0f else controller.volume,
                enabled = true,
                contentDescription = stringResource(Res.string.video_player_volume),
                onChange = controller::changeVolume,
                modifier = Modifier.width(72.dp)
            )
            RaftIconButton(
                onClick = onToggleFullscreen,
                tooltip =
                    stringResource(
                        if (fullscreen) Res.string.video_player_exit_fullscreen else Res.string.video_player_fullscreen
                    ),
                variant = RaftButtonVariant.Ghost
            ) { RaftSlotIcon(if (fullscreen) RaftLucideIconName.Minimize2 else RaftLucideIconName.Maximize2) }
        }
    }
}

/**
 * A thin media track (seek position, volume): click or drag to set [fraction]. The design system
 * has no slider yet; this one draws with its palette (`info` fill, as RaftProgressBar) on the scrim.
 */
@Composable
private fun RaftDesktopVideoScrubber(
    fraction: Float,
    enabled: Boolean,
    contentDescription: String,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnChange by rememberUpdatedState(onChange)
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val shown = (dragFraction ?: fraction).coerceIn(0f, 1f)
    Box(
        modifier =
            modifier
                .height(16.dp)
                .semantics {
                    this.contentDescription = contentDescription
                    progressBarRangeInfo = ProgressBarRangeInfo(shown, 0f..1f)
                    if (enabled) {
                        setProgress { value ->
                            currentOnChange(value.coerceIn(0f, 1f))
                            true
                        }
                    }
                }
                .then(
                    if (enabled) {
                        Modifier
                            .pointerInput(Unit) {
                                detectTapGestures { offset ->
                                    currentOnChange(raftDesktopVideoTrackFraction(offset.x, size.width.toFloat()))
                                }
                            }
                            .pointerInput(Unit) {
                                detectHorizontalDragGestures(
                                    onDragStart = { offset ->
                                        dragFraction = raftDesktopVideoTrackFraction(offset.x, size.width.toFloat())
                                    },
                                    onDragEnd = {
                                        dragFraction?.let(currentOnChange)
                                        dragFraction = null
                                    },
                                    onDragCancel = { dragFraction = null },
                                ) { change, _ ->
                                    change.consume()
                                    val next = raftDesktopVideoTrackFraction(change.position.x, size.width.toFloat())
                                    dragFraction = next
                                    currentOnChange(next)
                                }
                            }
                    } else {
                        Modifier
                    }
                ),
        contentAlignment = Alignment.Center
    ) {
        val track = RaftUiPalette.white.copy(alpha = if (enabled) 0.3f else 0.15f) // raft-color: track on the video scrim
        val fill = RaftUiPalette.info
        val thumb = RaftUiPalette.white // raft-color: ink on the video scrim
        Canvas(Modifier.fillMaxWidth().height(12.dp)) {
            val trackHeight = 4.dp.toPx()
            val top = (size.height - trackHeight) / 2f
            val radius = CornerRadius(trackHeight / 2f)
            drawRoundRect(track, Offset(0f, top), Size(size.width, trackHeight), radius)
            if (shown > 0f) drawRoundRect(fill, Offset(0f, top), Size(size.width * shown, trackHeight), radius)
            if (enabled) {
                drawCircle(thumb, radius = 5.dp.toPx(), center = Offset(size.width * shown, size.height / 2f))
            }
        }
    }
}

/** Where on a track of [widthPx] a pointer at [x] points, 0..1. */
fun raftDesktopVideoTrackFraction(x: Float, widthPx: Float): Float =
    if (widthPx <= 0f) 0f else (x / widthPx).coerceIn(0f, 1f)
