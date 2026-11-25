package build.raft.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import build.raft.app.compat.ui.PlayState
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.components.RaftSlotIcon
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftSpinner
import build.raft.designsystem.components.RaftSpinnerSize
import build.raft.designsystem.gesture.LocalRaftScrollInProgress
import build.raft.designsystem.gesture.raftClaimsHorizontalDragFirst
import build.raft.designsystem.gesture.raftKeepsBackSwipe
import build.raft.designsystem.gesture.raftKeepsHorizontalDrags
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftSpacing
import build.raft.designsystem.tokens.RaftUiPalette
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

@Stable
internal class RaftAndroidVideoUiState {
    var playState by mutableStateOf(PlayState.NONE)
    var playWhenReady by mutableStateOf(false)
    var isBuffering by mutableStateOf(false)
    var isEnded by mutableStateOf(false)
    var hasError by mutableStateOf(false)
    var hasFirstFrame by mutableStateOf(false)
    /** True when the underlying ExoPlayer was deferred or evicted by the concurrency budget and can wake on tap. */
    var isDormant by mutableStateOf(false)
    var positionMs by mutableLongStateOf(0L)
    var bufferedPositionMs by mutableLongStateOf(0L)
    var durationMs by mutableLongStateOf(0L)
    var muted by mutableStateOf(false)
    var rate by mutableFloatStateOf(1f)
    var isFullscreen by mutableStateOf(false)

    val isPlaying: Boolean
        get() = playState == PlayState.PLAYING || (playWhenReady && !isEnded && !hasError && isBuffering)
}

internal interface RaftAndroidVideoActions {
    fun togglePlayOrReplay()

    fun seekToUserTime(targetMs: Long)

    fun seekRelative(deltaMs: Long)

    fun toggleMuted()

    fun cycleRate()

    fun enterFullscreen()

    fun exitFullscreen()

    fun retryAfterError()
}

private data class DoubleTapSeekFeedback(
    val deltaMs: Long,
    val zone: RaftAndroidVideoTapZone,
    val token: Int,
)

private data class SurfaceDragScrubState(
    val startPositionMs: Long,
    val targetPositionMs: Long,
)

@Composable
internal fun RaftAndroidVideoControlsOverlay(
    state: RaftAndroidVideoUiState,
    actions: RaftAndroidVideoActions,
    fullscreen: Boolean,
    audioOnly: Boolean,
    modifier: Modifier = Modifier,
) {
    val latestActions by rememberUpdatedState(actions)
    val isListScrolling = LocalRaftScrollInProgress.current
    val currentIsListScrolling by rememberUpdatedState(isListScrolling)
    val layoutDirection = LocalLayoutDirection.current
    val viewConfiguration = LocalViewConfiguration.current

    var controlsVisible by remember { mutableStateOf(true) }
    var interactionNonce by remember { mutableIntStateOf(0) }
    var barScrubFraction by remember { mutableStateOf<Float?>(null) }
    var surfaceDragState by remember { mutableStateOf<SurfaceDragScrubState?>(null) }
    var doubleTapFeedback by remember { mutableStateOf<DoubleTapSeekFeedback?>(null) }
    var doubleTapToken by remember { mutableIntStateOf(0) }

    val isScrubbing = barScrubFraction != null || surfaceDragState != null
    val shouldAutoHide =
        raftAndroidVideoShouldAutoHideControls(
            isPlaying = state.isPlaying,
            isScrubbing = isScrubbing,
            isAudioOnly = audioOnly,
            hasError = state.hasError,
        )

    // Keep controls visible when audio-only, paused/ended, dormant, or errored; auto-hide 3s after
    // the last interaction once playback is running.
    LaunchedEffect(audioOnly, state.hasError, state.isEnded, state.isDormant) {
        if (audioOnly || state.hasError || state.isEnded || state.isDormant) {
            controlsVisible = true
        }
    }
    LaunchedEffect(controlsVisible, shouldAutoHide, interactionNonce) {
        if (controlsVisible && shouldAutoHide) {
            delay(RaftAndroidVideoControlsAutoHideMs)
            controlsVisible = false
        }
    }
    LaunchedEffect(doubleTapFeedback?.token) {
        if (doubleTapFeedback != null) {
            delay(RaftAndroidVideoSeekBadgeDurationMs)
            doubleTapFeedback = null
        }
    }

    val effectiveDurationMs = state.durationMs.coerceAtLeast(0L)
    val currentDurationMs by rememberUpdatedState(effectiveDurationMs)
    val displayedPositionMs =
        when {
            barScrubFraction != null -> raftAndroidVideoScrubTargetMs(barScrubFraction!!, effectiveDurationMs)
            surfaceDragState != null -> surfaceDragState!!.targetPositionMs
            else -> state.positionMs.coerceIn(0L, effectiveDurationMs.coerceAtLeast(state.positionMs.coerceAtLeast(0L)))
        }
    val displayedFraction =
        when {
            barScrubFraction != null -> barScrubFraction!!.coerceIn(0f, 1f)
            effectiveDurationMs > 0L -> (displayedPositionMs.toFloat() / effectiveDurationMs.toFloat()).coerceIn(0f, 1f)
            else -> 0f
        }
    val bufferedFraction =
        if (effectiveDurationMs > 0L) {
            (state.bufferedPositionMs.toFloat() / effectiveDurationMs.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
    val canSeek = effectiveDurationMs > 0L && !state.hasError
    val surfaceClaimSlopPx = viewConfiguration.touchSlop * 1.5f

    RaftDesignSystemTheme(forceDark = true) {
        BoxWithConstraints(modifier = modifier.fillMaxSize()) {
            val showSpeedButton = fullscreen || maxWidth >= 280.dp
            val cutoutPadding = if (fullscreen) WindowInsets.displayCutout.asPaddingValues() else null

            // Video gesture surface: single-tap toggles controls, double-tap left/center/right
            // seeks -10s / play-pause / +10s, horizontal drag scrubs cleanly while vertical drags
            // pass straight through to the conversation list.
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .then(if (canSeek && !audioOnly) Modifier.raftKeepsHorizontalDrags() else Modifier)
                        .raftClaimsHorizontalDragFirst(enabled = canSeek && !audioOnly) { _, _ -> surfaceClaimSlopPx }
                        .pointerInput(audioOnly, canSeek) {
                            detectTapGestures(
                                onTap = {
                                    interactionNonce += 1
                                    if (audioOnly) {
                                        if (state.hasError) latestActions.retryAfterError() else latestActions.togglePlayOrReplay()
                                    } else {
                                        controlsVisible = !controlsVisible
                                    }
                                },
                                onDoubleTap =
                                    if (audioOnly) {
                                        null
                                    } else {
                                        { offset ->
                                            if (!state.hasError) {
                                                interactionNonce += 1
                                                when (val zone = raftAndroidVideoDoubleTapZone(offset.x, size.width.toFloat())) {
                                                    RaftAndroidVideoTapZone.Center -> latestActions.togglePlayOrReplay()
                                                    RaftAndroidVideoTapZone.Rewind -> {
                                                        if (canSeek) {
                                                            latestActions.seekRelative(-RaftAndroidVideoSeekJumpMs)
                                                            val accumulated =
                                                                raftAndroidVideoAccumulateDoubleTapDelta(
                                                                    previousDeltaMs = doubleTapFeedback?.deltaMs ?: 0L,
                                                                    stepMs = -RaftAndroidVideoSeekJumpMs,
                                                                )
                                                            doubleTapToken += 1
                                                            doubleTapFeedback = DoubleTapSeekFeedback(accumulated, zone, doubleTapToken)
                                                        }
                                                    }
                                                    RaftAndroidVideoTapZone.Forward -> {
                                                        if (canSeek) {
                                                            latestActions.seekRelative(RaftAndroidVideoSeekJumpMs)
                                                            val accumulated =
                                                                raftAndroidVideoAccumulateDoubleTapDelta(
                                                                    previousDeltaMs = doubleTapFeedback?.deltaMs ?: 0L,
                                                                    stepMs = RaftAndroidVideoSeekJumpMs,
                                                                )
                                                            doubleTapToken += 1
                                                            doubleTapFeedback = DoubleTapSeekFeedback(accumulated, zone, doubleTapToken)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    },
                            )
                        }
                        .pointerInput(audioOnly, canSeek) {
                            if (audioOnly || !canSeek) return@pointerInput
                            val touchSlop = viewConfiguration.touchSlop
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                if (currentIsListScrolling()) return@awaitEachGesture
                                var claimed = false
                                var committed = false
                                var startPosMs = 0L
                                var latestTargetMs = 0L
                                try {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                        val duration = currentDurationMs
                                        if (!change.pressed) {
                                            if (claimed) {
                                                change.consume()
                                                committed = true
                                                surfaceDragState = null
                                                interactionNonce += 1
                                                latestActions.seekToUserTime(latestTargetMs)
                                            }
                                            return@awaitEachGesture
                                        }
                                        if (event.changes.count { it.pressed } > 1 || duration <= 0L) {
                                            return@awaitEachGesture
                                        }
                                        val total = change.position - down.position
                                        if (!claimed) {
                                            if (change.isConsumed || raftAndroidVideoHorizontalDragShouldRelease(total.x, total.y, touchSlop)) {
                                                return@awaitEachGesture
                                            }
                                            if (raftAndroidVideoHorizontalDragShouldClaim(total.x, total.y, touchSlop)) {
                                                claimed = true
                                                startPosMs = state.positionMs.coerceIn(0L, duration)
                                                latestTargetMs =
                                                    raftAndroidVideoHorizontalDragTargetMs(
                                                        startPositionMs = startPosMs,
                                                        dragDeltaPx = total.x,
                                                        surfaceWidthPx = size.width.toFloat(),
                                                        durationMs = duration,
                                                    )
                                                change.consume()
                                                interactionNonce += 1
                                                surfaceDragState = SurfaceDragScrubState(startPosMs, latestTargetMs)
                                            }
                                        } else {
                                            change.consume()
                                            latestTargetMs =
                                                raftAndroidVideoHorizontalDragTargetMs(
                                                    startPositionMs = startPosMs,
                                                    dragDeltaPx = total.x,
                                                    surfaceWidthPx = size.width.toFloat(),
                                                    durationMs = duration,
                                                )
                                            surfaceDragState = SurfaceDragScrubState(startPosMs, latestTargetMs)
                                        }
                                    }
                                } finally {
                                    if (claimed && !committed) {
                                        surfaceDragState = null
                                    }
                                }
                            }
                        },
            )

            // Fullscreen top bar: back button clear of camera cutouts.
            if (fullscreen && (controlsVisible || isScrubbing)) {
                val topScrim =
                    Brush.verticalGradient(
                        colors =
                            listOf(
                                RaftUiPalette.black.copy(alpha = 0.65f), // raft-color: fullscreen top scrim over video
                                Color.Transparent,
                            ),
                    )
                Row(
                    modifier =
                        Modifier
                            .align(Alignment.TopStart)
                            .fillMaxWidth()
                            .background(topScrim)
                            .then(
                                if (cutoutPadding != null) {
                                    Modifier.padding(
                                        start = cutoutPadding.calculateStartPadding(layoutDirection),
                                        top = cutoutPadding.calculateTopPadding(),
                                        end = cutoutPadding.calculateEndPadding(layoutDirection),
                                    )
                                } else {
                                    Modifier
                                },
                            )
                            .padding(horizontal = RaftSpacing.Sm, vertical = RaftSpacing.Sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RaftIconButton(
                        onClick = { latestActions.exitFullscreen() },
                        tooltip = stringResource(Res.string.video_player_exit_fullscreen),
                        variant = RaftButtonVariant.Ghost,
                    ) {
                        RaftSlotIcon(
                            RaftLucideIconName.ArrowLeft,
                            size = 20.dp,
                            color = RaftUiPalette.white, // raft-color: white ink on dark video scrim
                        )
                    }
                }
            }

            // Center visual feedback: horizontal scrub HUD, double-tap seek pill, buffering spinner,
            // audio-only music emblem, or a single clean Play/Replay/Retry button when paused.
            when {
                surfaceDragState != null -> {
                    val drag = surfaceDragState!!
                    val deltaMs = drag.targetPositionMs - drag.startPositionMs
                    RaftAndroidVideoCenterPill(modifier = Modifier.align(Alignment.Center)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            RaftText(
                                text =
                                    "${raftAndroidVideoFormatTime(drag.targetPositionMs, effectiveDurationMs)} / " +
                                        raftAndroidVideoFormatTime(effectiveDurationMs, effectiveDurationMs),
                                style = RaftTheme.typography.monoMeta.copy(fontWeight = FontWeight.SemiBold),
                                color = RaftUiPalette.white, // raft-color: scrub HUD text on dark pill
                                maxLines = 1,
                            )
                            RaftText(
                                text = raftAndroidVideoFormatSeekDelta(deltaMs),
                                style = RaftTheme.typography.caption.copy(fontWeight = FontWeight.SemiBold),
                                color = RaftUiPalette.info,
                                maxLines = 1,
                            )
                        }
                    }
                }
                doubleTapFeedback != null -> {
                    val feedback = doubleTapFeedback!!
                    val alignment =
                        if (feedback.zone == RaftAndroidVideoTapZone.Rewind) Alignment.CenterStart else Alignment.CenterEnd
                    RaftAndroidVideoCenterPill(
                        modifier =
                            Modifier
                                .align(alignment)
                                .padding(horizontal = if (fullscreen) 48.dp else 20.dp),
                    ) {
                        RaftText(
                            text = raftAndroidVideoFormatSeekDelta(feedback.deltaMs),
                            style = RaftTheme.typography.callout.copy(fontWeight = FontWeight.SemiBold),
                            color = RaftUiPalette.white, // raft-color: seek badge text on dark pill
                            maxLines = 1,
                        )
                    }
                }
                state.isBuffering && (state.hasFirstFrame || state.playWhenReady || fullscreen || audioOnly) -> {
                    RaftSpinner(
                        size = RaftSpinnerSize.Md,
                        color = RaftUiPalette.white, // raft-color: buffering spinner on dark video surface
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                audioOnly -> {
                    RaftSlotIcon(
                        name = RaftLucideIconName.FileMusic,
                        size = 36.dp,
                        color = RaftUiPalette.white.copy(alpha = 0.72f), // raft-color: audio emblem on dark surface
                        modifier = Modifier.align(Alignment.Center).padding(bottom = 52.dp),
                    )
                }
                controlsVisible &&
                    !state.isPlaying &&
                    barScrubFraction == null &&
                    ((!state.hasError && (state.hasFirstFrame || state.isDormant || fullscreen)) || (state.hasError && fullscreen)) -> {
                    val centerButtonSize = if (fullscreen) 56.dp else 48.dp
                    val centerIconSize = if (fullscreen) 26.dp else 22.dp
                    val centerIcon =
                        when {
                            state.hasError -> RaftLucideIconName.RotateCcw
                            state.isEnded -> RaftLucideIconName.RotateCcw
                            else -> RaftLucideIconName.Play
                        }
                    val centerLabel =
                        stringResource(
                            when {
                                state.hasError -> Res.string.common_retry
                                state.isEnded -> Res.string.video_player_replay
                                else -> Res.string.common_play
                            },
                        )
                    Box(
                        modifier =
                            Modifier
                                .align(Alignment.Center)
                                // Lift inline button completely clear of the 76dp bottom control bar
                                // (40dp scrub bar + 32dp row + 4dp padding) so its hit box never
                                // overlaps the scrub bar on compact 280dp-320dp chat cards.
                                .padding(bottom = if (fullscreen) 0.dp else 72.dp)
                                .size(centerButtonSize)
                                .clip(CircleShape)
                                .background(RaftUiPalette.black.copy(alpha = 0.56f)) // raft-color: translucent center play pill
                                .clickable(role = Role.Button) {
                                    interactionNonce += 1
                                    if (state.hasError) latestActions.retryAfterError() else latestActions.togglePlayOrReplay()
                                }
                                .semantics { contentDescription = centerLabel },
                        contentAlignment = Alignment.Center,
                    ) {
                        RaftSlotIcon(
                            name = centerIcon,
                            size = centerIconSize,
                            color = RaftUiPalette.white, // raft-color: play icon on dark pill
                        )
                    }
                }
            }

            // Bottom control bar (or slim progress indicator during surface horizontal scrub when controls hidden).
            if (controlsVisible || barScrubFraction != null || audioOnly) {
                val bottomScrim =
                    Brush.verticalGradient(
                        colors =
                            listOf(
                                Color.Transparent,
                                RaftUiPalette.black.copy(alpha = 0.78f), // raft-color: bottom control gradient scrim
                            ),
                    )
                Column(
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(bottomScrim)
                            .then(
                                if (cutoutPadding != null) {
                                    Modifier.padding(
                                        start = cutoutPadding.calculateStartPadding(layoutDirection),
                                        end = cutoutPadding.calculateEndPadding(layoutDirection),
                                        bottom = cutoutPadding.calculateBottomPadding(),
                                    )
                                } else {
                                    Modifier
                                },
                            )
                            .padding(
                                start = if (fullscreen) RaftSpacing.Md else RaftSpacing.Sm,
                                end = if (fullscreen) RaftSpacing.Md else RaftSpacing.Sm,
                                bottom = if (fullscreen) RaftSpacing.Md else RaftSpacing.Xs,
                            ),
                ) {
                    RaftAndroidVideoScrubBar(
                        fraction = displayedFraction,
                        bufferedFraction = bufferedFraction,
                        displayedPositionMs = displayedPositionMs,
                        durationMs = effectiveDurationMs,
                        enabled = canSeek,
                        scrubbing = barScrubFraction != null,
                        contentDescription = stringResource(Res.string.video_player_position),
                        onScrubStart = { fraction ->
                            interactionNonce += 1
                            barScrubFraction = fraction
                        },
                        onScrubMove = { fraction ->
                            barScrubFraction = fraction
                        },
                        onScrubEnd = { fraction ->
                            barScrubFraction = null
                            interactionNonce += 1
                            latestActions.seekToUserTime(raftAndroidVideoScrubTargetMs(fraction, currentDurationMs))
                        },
                        onScrubCancel = {
                            barScrubFraction = null
                            interactionNonce += 1
                        },
                        onAccessibilitySeek = { fraction ->
                            interactionNonce += 1
                            latestActions.seekToUserTime(raftAndroidVideoScrubTargetMs(fraction, currentDurationMs))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val playLabel =
                            stringResource(
                                when {
                                    state.hasError -> Res.string.common_retry
                                    state.isEnded -> Res.string.video_player_replay
                                    state.isPlaying -> Res.string.common_pause
                                    else -> Res.string.common_play
                                },
                            )
                        RaftIconButton(
                            onClick = {
                                interactionNonce += 1
                                if (state.hasError) latestActions.retryAfterError() else latestActions.togglePlayOrReplay()
                            },
                            tooltip = playLabel,
                            variant = RaftButtonVariant.Ghost,
                            hitSize = 36.dp,
                        ) {
                            RaftSlotIcon(
                                name =
                                    when {
                                        state.hasError -> RaftLucideIconName.RotateCcw
                                        state.isEnded -> RaftLucideIconName.RotateCcw
                                        state.isPlaying -> RaftLucideIconName.Pause
                                        else -> RaftLucideIconName.Play
                                    },
                                size = 18.dp,
                                color = RaftUiPalette.white, // raft-color: control icon on dark scrim
                            )
                        }
                        RaftText(
                            text =
                                "${raftAndroidVideoFormatTime(displayedPositionMs, effectiveDurationMs)} / " +
                                    raftAndroidVideoFormatTime(effectiveDurationMs, effectiveDurationMs),
                            style = RaftTheme.typography.monoMeta,
                            color = RaftUiPalette.white, // raft-color: timestamp text on dark scrim
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                        )
                        if (showSpeedButton) {
                            val speedLabel = raftAndroidVideoSpeedLabel(state.rate)
                            val speedDescription = "${stringResource(Res.string.video_player_speed)}: $speedLabel"
                            Box(
                                modifier =
                                    Modifier
                                        .clip(RoundedCornerShape(RaftRadius.Chip))
                                        .clickable(role = Role.Button) {
                                            interactionNonce += 1
                                            latestActions.cycleRate()
                                        }
                                        .semantics { contentDescription = speedDescription }
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                RaftText(
                                    text = speedLabel,
                                    style = RaftTheme.typography.monoMeta.copy(fontWeight = FontWeight.SemiBold),
                                    color = RaftUiPalette.white, // raft-color: speed label on dark scrim
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                        }
                        RaftIconButton(
                            onClick = {
                                interactionNonce += 1
                                latestActions.toggleMuted()
                            },
                            tooltip = stringResource(if (state.muted) Res.string.video_player_unmute else Res.string.video_player_mute),
                            variant = RaftButtonVariant.Ghost,
                            hitSize = 36.dp,
                        ) {
                            RaftSlotIcon(
                                name = if (state.muted) RaftLucideIconName.VolumeX else RaftLucideIconName.Volume2,
                                size = 18.dp,
                                color = RaftUiPalette.white, // raft-color: mute icon on dark scrim
                            )
                        }
                        if (!audioOnly) {
                            RaftIconButton(
                                onClick = {
                                    interactionNonce += 1
                                    if (fullscreen) latestActions.exitFullscreen() else latestActions.enterFullscreen()
                                },
                                tooltip =
                                    stringResource(
                                        if (fullscreen) Res.string.video_player_exit_fullscreen else Res.string.video_player_fullscreen,
                                    ),
                                variant = RaftButtonVariant.Ghost,
                                hitSize = 36.dp,
                            ) {
                                RaftSlotIcon(
                                    name = if (fullscreen) RaftLucideIconName.Minimize2 else RaftLucideIconName.Maximize2,
                                    size = 18.dp,
                                    color = RaftUiPalette.white, // raft-color: fullscreen icon on dark scrim
                                )
                            }
                        }
                    }
                }
            } else if (surfaceDragState != null && effectiveDurationMs > 0L) {
                // Thin progress rail at the bottom edge while dragging across the video with chrome hidden.
                Canvas(
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .then(
                                if (cutoutPadding != null) {
                                    Modifier.padding(
                                        start = cutoutPadding.calculateStartPadding(layoutDirection),
                                        end = cutoutPadding.calculateEndPadding(layoutDirection),
                                        bottom = cutoutPadding.calculateBottomPadding(),
                                    )
                                } else {
                                    Modifier
                                },
                            )
                            .padding(horizontal = RaftSpacing.Md, vertical = RaftSpacing.Sm)
                            .height(4.dp),
                ) {
                    val radius = CornerRadius(size.height / 2f)
                    drawRoundRect(
                        color = RaftUiPalette.white.copy(alpha = 0.28f), // raft-color: slim drag rail background
                        cornerRadius = radius,
                    )
                    if (displayedFraction > 0f) {
                        drawRoundRect(
                            color = RaftUiPalette.info,
                            size = Size(size.width * displayedFraction, size.height),
                            cornerRadius = radius,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RaftAndroidVideoCenterPill(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier =
            modifier
                .clip(RoundedCornerShape(RaftRadius.Chip))
                .background(RaftUiPalette.black.copy(alpha = 0.72f)) // raft-color: translucent video HUD pill
                .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/**
 * High-target (40dp tall) touch-friendly video scrub bar.
 *
 * Claims pointer input immediately on `down` (unless the enclosing list is still flinging) and
 * registers with Android's system-gesture exclusion, Raft's back-swipe host, the thread time-reveal
 * swipe, and the thread tab pager so dragging the progress bar inside a conversation is never
 * stolen by list scrolling or horizontal swipes.
 */
@Composable
private fun RaftAndroidVideoScrubBar(
    fraction: Float,
    bufferedFraction: Float,
    displayedPositionMs: Long,
    durationMs: Long,
    enabled: Boolean,
    scrubbing: Boolean,
    contentDescription: String,
    onScrubStart: (Float) -> Unit,
    onScrubMove: (Float) -> Unit,
    onScrubEnd: (Float) -> Unit,
    onScrubCancel: () -> Unit,
    onAccessibilitySeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnStart by rememberUpdatedState(onScrubStart)
    val currentOnMove by rememberUpdatedState(onScrubMove)
    val currentOnEnd by rememberUpdatedState(onScrubEnd)
    val currentOnCancel by rememberUpdatedState(onScrubCancel)
    val currentOnA11ySeek by rememberUpdatedState(onAccessibilitySeek)
    val currentDurationMs by rememberUpdatedState(durationMs)
    val isListScrolling = LocalRaftScrollInProgress.current
    val currentIsListScrolling by rememberUpdatedState(isListScrolling)
    val density = LocalDensity.current
    val thumbInsetPx = with(density) { 10.dp.toPx() }
    var barWidthPx by remember { mutableIntStateOf(0) }
    var pillWidthPx by remember { mutableIntStateOf(0) }
    val clampedFraction = fraction.coerceIn(0f, 1f)
    val clampedBuffered = bufferedFraction.coerceIn(0f, 1f)

    Box(modifier = modifier) {
        if (scrubbing && durationMs > 0L && barWidthPx > 0) {
            val trackPx = (barWidthPx.toFloat() - thumbInsetPx * 2f).coerceAtLeast(0f)
            val thumbCenterX = thumbInsetPx + trackPx * clampedFraction
            val maxPillX = (barWidthPx - pillWidthPx).coerceAtLeast(0)
            val pillX = (thumbCenterX - pillWidthPx / 2f).roundToInt().coerceIn(0, maxPillX)
            val pillBottomLiftPx = with(density) { 30.dp.roundToPx() }
            Box(
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .offset { IntOffset(pillX, -pillBottomLiftPx) }
                        .onSizeChanged { pillWidthPx = it.width }
                        .clip(RoundedCornerShape(RaftRadius.Chip))
                        .background(RaftUiPalette.black.copy(alpha = 0.82f)) // raft-color: scrub preview badge
                        .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                RaftText(
                    text =
                        "${raftAndroidVideoFormatTime(displayedPositionMs, durationMs)} / " +
                            raftAndroidVideoFormatTime(durationMs, durationMs),
                    style = RaftTheme.typography.monoMeta.copy(fontWeight = FontWeight.SemiBold),
                    color = RaftUiPalette.white, // raft-color: scrub preview timestamp
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .onSizeChanged { barWidthPx = it.width }
                    .systemGestureExclusion()
                    .raftKeepsBackSwipe(enabled)
                    .then(if (enabled) Modifier.raftKeepsHorizontalDrags() else Modifier)
                    .raftClaimsHorizontalDragFirst(enabled) { _, _ -> Float.POSITIVE_INFINITY }
                    .semantics {
                        this.contentDescription = contentDescription
                        progressBarRangeInfo = ProgressBarRangeInfo(clampedFraction, 0f..1f)
                        if (enabled) {
                            setProgress { target ->
                                currentOnA11ySeek(target.coerceIn(0f, 1f))
                                true
                            }
                        }
                    }
                    .pointerInput(enabled) {
                        if (!enabled) return@pointerInput
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            if (currentIsListScrolling() || currentDurationMs <= 0L) return@awaitEachGesture
                            var started = false
                            var committed = false
                            try {
                                down.consume()
                                var latest = raftAndroidVideoScrubFraction(down.position.x, size.width.toFloat(), thumbInsetPx)
                                started = true
                                currentOnStart(latest)
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    change.consume()
                                    if (!change.pressed) {
                                        committed = true
                                        currentOnEnd(latest)
                                        return@awaitEachGesture
                                    }
                                    latest = raftAndroidVideoScrubFraction(change.position.x, size.width.toFloat(), thumbInsetPx)
                                    currentOnMove(latest)
                                }
                            } finally {
                                if (started && !committed) {
                                    currentOnCancel()
                                }
                            }
                        }
                    },
            contentAlignment = Alignment.Center,
        ) {
            val baseTrackColor = RaftUiPalette.white.copy(alpha = if (enabled) 0.28f else 0.14f) // raft-color: scrub rail track
            val bufferedColor = RaftUiPalette.white.copy(alpha = 0.45f) // raft-color: buffered track fill
            val playedColor = RaftUiPalette.info
            val thumbHaloColor = RaftUiPalette.white.copy(alpha = 0.26f) // raft-color: active scrub thumb halo
            val thumbColor = RaftUiPalette.white // raft-color: scrub thumb dot
            Canvas(modifier = Modifier.fillMaxWidth().height(24.dp)) {
                val trackHeight = (if (scrubbing) 6.dp else 4.dp).toPx()
                val startX = thumbInsetPx.coerceAtMost(size.width / 2f)
                val trackWidth = (size.width - startX * 2f).coerceAtLeast(0f)
                val top = (size.height - trackHeight) / 2f
                val corner = CornerRadius(trackHeight / 2f)
                drawRoundRect(
                    color = baseTrackColor,
                    topLeft = Offset(startX, top),
                    size = Size(trackWidth, trackHeight),
                    cornerRadius = corner,
                )
                if (clampedBuffered > 0f && trackWidth > 0f) {
                    drawRoundRect(
                        color = bufferedColor,
                        topLeft = Offset(startX, top),
                        size = Size(trackWidth * clampedBuffered, trackHeight),
                        cornerRadius = corner,
                    )
                }
                if (clampedFraction > 0f && trackWidth > 0f) {
                    drawRoundRect(
                        color = playedColor,
                        topLeft = Offset(startX, top),
                        size = Size(trackWidth * clampedFraction, trackHeight),
                        cornerRadius = corner,
                    )
                }
                if (enabled && trackWidth > 0f) {
                    val center = Offset(startX + trackWidth * clampedFraction, size.height / 2f)
                    val radius = (if (scrubbing) 8.dp else 6.dp).toPx()
                    if (scrubbing) {
                        drawCircle(color = thumbHaloColor, radius = 12.dp.toPx(), center = center)
                    }
                    drawCircle(color = thumbColor, radius = radius, center = center)
                }
            }
        }
    }
}
