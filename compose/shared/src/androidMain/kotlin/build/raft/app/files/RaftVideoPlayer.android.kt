package build.raft.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import build.raft.app.compat.ui.PlayState
import build.raft.app.compat.ui.VideoPlayControl
import build.raft.app.shared.R

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
    val stateCallback by rememberUpdatedState(onPlayStateChanged)
    val timeCallback by rememberUpdatedState(onPlayTimeChanged)
    val firstFrameCallback by rememberUpdatedState(onFirstFrame)
    val role = LocalRaftVideoPlayerRole.current
    val audioOnly = LocalRaftVideoPlayerAudioOnly.current || role == RaftMediaPlayerRole.InlineAudio
    val lifecycleOwner = LocalLifecycleOwner.current
    key(source) {
        val viewHolder = remember { RaftAndroidVideoViewHolder() }
        var attachedView by remember { mutableStateOf<RaftAndroidVideoView?>(null) }
        DisposableEffect(lifecycleOwner, viewHolder) {
            // Playback must not continue once the host is in the background (the Activity is
            // stopped). ON_STOP rather than ON_PAUSE: a visible multi-window / PiP-adjacent Activity
            // is paused but on screen and keeps playing, as Android's media guidance recommends.
            val observer =
                LifecycleEventObserver { _, event ->
                    if (raftVideoPausesOnLifecycleEvent(event)) viewHolder.view?.pauseForHostBackground()
                }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
        BoxWithConstraints(modifier = modifier) {
            AndroidView(
                factory = { context ->
                    RaftAndroidVideoView(
                        context = context,
                        src = source,
                        audioOnly = audioOnly,
                        callbacks =
                            object : RaftAndroidVideoView.Callbacks {
                                override fun onPlayState(state: PlayState) = stateCallback(state)

                                override fun onPlayTime(currentMs: Long, totalMs: Long) =
                                    timeCallback(currentMs.toInt(), totalMs.toInt())

                                override fun onFirstFrame() = firstFrameCallback()
                            },
                        role = role,
                    ).also { view ->
                        viewHolder.view = view
                        attachedView = view
                    }
                },
                modifier = Modifier.fillMaxSize(),
                update = { view ->
                    view.setAudioOnly(audioOnly)
                    view.applyHostMuted(muted)
                    view.applyHostRate(rate)
                    view.applyPlayControl(playControl)
                    if (seekRequestId != null) view.applySeekRequest(seekRequestId, seekTimeMs.toLong())
                },
                onRelease = { view ->
                    if (viewHolder.view === view) viewHolder.view = null
                    if (attachedView === view) attachedView = null
                    view.stop()
                },
            )
            val currentView = attachedView
            if (
                currentView != null &&
                !currentView.uiState.isFullscreen &&
                raftAndroidVideoShouldShowInlineControls(maxWidth.value, maxHeight.value)
            ) {
                RaftAndroidVideoControlsOverlay(
                    state = currentView.uiState,
                    actions = currentView,
                    fullscreen = false,
                    audioOnly = audioOnly,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

private class RaftAndroidVideoViewHolder {
    var view: RaftAndroidVideoView? = null
}

/** The host lifecycle events that pause playback: the app went to the background. */
internal fun raftVideoPausesOnLifecycleEvent(event: Lifecycle.Event): Boolean = event == Lifecycle.Event.ON_STOP

@androidx.annotation.OptIn(UnstableApi::class)
internal fun buildRaftAndroidLoadControl(policy: RaftAndroidVideoBufferPolicy): DefaultLoadControl =
    DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            policy.minBufferMs,
            policy.maxBufferMs,
            policy.bufferForPlaybackMs,
            policy.bufferForPlaybackAfterRebufferMs,
        )
        .setTargetBufferBytes(policy.targetBufferBytes)
        .setPrioritizeTimeOverSizeThresholds(false)
        .setBackBuffer(policy.backBufferDurationMs, policy.retainBackBufferFromKeyframe)
        .build()

/**
 * Media3 (ExoPlayer) backed video view with Raft's Compose touch controls:
 * - `PlayerView` uses `TextureView` (`raft_video_player_view.xml`) with `useController = false`,
 *   rendering frames in lockstep with the message list while [RaftAndroidVideoControlsOverlay]
 *   owns touch scrubbing, gestures, mute, speed, and fullscreen chrome.
 * - Fullscreen reparents `playerView` + a dedicated fullscreen `ComposeView` onto the activity's
 *   `android.R.id.content` so rotation or list recycling never tears down fullscreen, and chooses
 *   portrait vs landscape orientation from the video's display dimensions.
 * - [ExoPlayer] allocation is guarded by [RaftProcessMediaPlayerBudget] with tight role-scoped
 *   [DefaultLoadControl] thresholds and dormant eviction/recovery: idle inline audio cards hold
 *   zero decoders until Play/Seek, inline cards cap process-wide concurrent decoders, and
 *   evicted or background-released cards re-create their player seamlessly on tap.
 */
@SuppressLint("ViewConstructor")
@androidx.annotation.OptIn(UnstableApi::class)
internal class RaftAndroidVideoView(
    context: Context,
    private val src: String,
    private var audioOnly: Boolean,
    private val callbacks: Callbacks,
    private val role: RaftMediaPlayerRole = RaftMediaPlayerRole.FilePreview,
    private val budget: RaftMediaPlayerBudget = RaftProcessMediaPlayerBudget,
    private val playerFactory: (Context, RaftAndroidVideoBufferPolicy) -> ExoPlayer = { ctx, policy ->
        ExoPlayer.Builder(ctx)
            .setLoadControl(buildRaftAndroidLoadControl(policy))
            .setSeekBackIncrementMs(RaftAndroidVideoSeekJumpMs)
            .setSeekForwardIncrementMs(RaftAndroidVideoSeekJumpMs)
            .build()
    },
) : FrameLayout(context), RaftAndroidVideoActions {
    interface Callbacks {
        fun onPlayState(state: PlayState)

        fun onPlayTime(currentMs: Long, totalMs: Long)

        fun onFirstFrame()
    }

    val uiState = RaftAndroidVideoUiState()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var stopped = false
    private var lastReportedState: PlayState? = null
    private var firstFrameReported = false
    private var appliedPlayControl: VideoPlayControl? = null
    private var appliedSeekRequestId: Int? = null
    private var appliedHostMuted: Boolean? = null
    private var appliedHostRate: Float? = null
    private val progressContract = RaftVideoProgressContract()
    private val terminalGate = RaftVideoPlaybackAttemptGate(progressContract.currentGeneration)
    private var awaitingAttemptStart = false
    private var scheduledTickerGeneration: Long? = null
    private var progressTickerRunnable: Runnable? = null
    private var videoWidth = 0
    private var videoHeight = 0
    private var videoPixelWidthHeightRatio = 1f
    private var savedPositionMs = 0L

    private val hostActivity: ComponentActivity? = context.unwrapRaftComponentActivity()
    private var fullscreenContainer: FrameLayout? = null
    private var fullscreenComposeView: ComposeView? = null
    private var savedOrientation: Int? = null
    private var fullscreenOrientationApplied = false
    private var fullscreenBackCallback: OnBackPressedCallback? = null
    private var releaseDeferred = false
    private val fullscreenLifecycleObserver =
        LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> pauseForHostBackground()
                Lifecycle.Event.ON_DESTROY -> exitFullscreen()
                else -> Unit
            }
        }

    private var player: ExoPlayer? = null
    private var budgetToken: Long? = null

    internal val hasLivePlayerForTest: Boolean
        get() = player != null

    private val playerView: PlayerView =
        (LayoutInflater.from(context).inflate(R.layout.raft_video_player_view, this, false) as PlayerView).also { view ->
            view.useController = false
            view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            view.player = null
            addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }

    init {
        setBackgroundColor(FULLSCREEN_BACKGROUND)
    }

    private fun ensurePlayer(
        prepareNow: Boolean,
        playingHint: Boolean = false,
    ): ExoPlayer? {
        if (stopped) return null
        player?.let { existing ->
            budgetToken?.let { token ->
                budget.markActivity(
                    id = token,
                    playing = if (playingHint) true else null,
                    fullscreen = fullscreenContainer != null,
                    touch = true,
                )
            }
            return existing
        }
        // Acquire a concurrency slot BEFORE constructing ExoPlayer so any evicted LRU victim
        // releases its MediaCodec and buffer pool before the incoming player allocates its own.
        val token =
            budget.register(
                role = role,
                playing = playingHint,
                fullscreen = fullscreenContainer != null,
                onEvict = ::scheduleEvictToDormant,
            )
        val policy = raftAndroidVideoBufferPolicy(role = role, audioOnly = audioOnly)
        val created =
            try {
                playerFactory(context, policy)
            } catch (throwable: Throwable) {
                budget.unregister(token)
                throw throwable
            }
        player = created
        budgetToken = token
        uiState.isDormant = false
        created.setMediaItem(MediaItem.fromUri(src))
        created.playWhenReady = false
        created.volume = if (uiState.muted) 0f else 1f
        if (created.playbackParameters.speed != uiState.rate) {
            created.playbackParameters = PlaybackParameters(uiState.rate)
        }
        created.addListener(createPlayerListener(created))
        playerView.player = created
        if (savedPositionMs > 0L && !uiState.isEnded) {
            created.seekTo(savedPositionMs)
        }
        if (prepareNow) {
            created.prepare()
        }
        return created
    }

    private fun createPlayerListener(ownerPlayer: ExoPlayer): Player.Listener =
        object : Player.Listener {
            override fun onRenderedFirstFrame() {
                if (stopped || player !== ownerPlayer) return
                uiState.hasFirstFrame = true
                reportFirstFrameOnce()
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (stopped || player !== ownerPlayer) return
                if (videoSize.width > 0 && videoSize.height > 0) {
                    videoWidth = videoSize.width
                    videoHeight = videoSize.height
                    videoPixelWidthHeightRatio = videoSize.pixelWidthHeightRatio
                    if (fullscreenContainer != null && !fullscreenOrientationApplied) {
                        applyFullscreenOrientationIfKnown()
                    }
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (stopped || player !== ownerPlayer) return
                syncDurationAndBuffered(ownerPlayer)
                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        uiState.isBuffering = true
                        uiState.hasError = false
                        reportState(PlayState.BUFFERING)
                        ensureProgressTicker()
                    }
                    Player.STATE_READY -> {
                        uiState.isBuffering = false
                        uiState.hasError = false
                        uiState.isEnded = false
                        uiState.playWhenReady = ownerPlayer.playWhenReady
                        val nextState = if (ownerPlayer.playWhenReady) PlayState.PLAYING else PlayState.PAUSED
                        reportState(nextState)
                        pollProgressOnce(progressContract.currentGeneration)
                        if (shouldKeepProgressTickerRunning()) ensureProgressTicker()
                    }
                    // Terminal state is derived through the progress contract and attempt gate
                    // so a stale ended event never terminates a new replay/seek attempt.
                    Player.STATE_ENDED -> {
                        uiState.isBuffering = false
                        uiState.playWhenReady = ownerPlayer.playWhenReady
                        budgetToken?.let { budget.markActivity(it, playing = false, touch = false) }
                        pollProgressOnce(progressContract.currentGeneration, nativeEnded = true)
                    }
                    Player.STATE_IDLE -> {
                        uiState.isBuffering = false
                    }
                }
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (stopped || player !== ownerPlayer) return
                uiState.playWhenReady = playWhenReady
                budgetToken?.let { budget.markActivity(it, playing = playWhenReady, touch = playWhenReady) }
                if (ownerPlayer.playbackState == Player.STATE_READY) {
                    reportState(if (playWhenReady) PlayState.PLAYING else PlayState.PAUSED)
                }
                if (shouldKeepProgressTickerRunning()) ensureProgressTicker()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (stopped || player !== ownerPlayer) return
                val activePlayback =
                    isPlaying || (ownerPlayer.playWhenReady && ownerPlayer.playbackState == Player.STATE_BUFFERING)
                budgetToken?.let { budget.markActivity(it, playing = activePlayback, touch = isPlaying) }
                if (isPlaying) {
                    uiState.isBuffering = false
                    uiState.isEnded = false
                    uiState.hasError = false
                    if (awaitingAttemptStart) {
                        val duration = ownerPlayer.duration.takeIf { it > 0L } ?: 0L
                        if (duration <= 0L || ownerPlayer.currentPosition < duration) {
                            terminalGate.markStarted(progressContract.currentGeneration)
                            awaitingAttemptStart = false
                        }
                    }
                    ensureProgressTicker()
                } else {
                    pollProgressOnce(progressContract.currentGeneration)
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                if (stopped || player !== ownerPlayer) return
                pollProgressOnce(progressContract.currentGeneration)
            }

            override fun onPlayerError(error: PlaybackException) {
                if (stopped || player !== ownerPlayer) return
                uiState.isBuffering = false
                uiState.hasError = true
                budgetToken?.let { budget.markActivity(it, playing = false, touch = false) }
                reportState(PlayState.ERROR)
            }
        }

    private fun scheduleEvictToDormant() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            releasePlayerToDormant(unregisterFromBudget = false)
        } else {
            mainHandler.post { releasePlayerToDormant(unregisterFromBudget = false) }
        }
    }

    private fun releasePlayerToDormant(unregisterFromBudget: Boolean) {
        if (stopped || fullscreenContainer != null) return
        val current = player
        val token = budgetToken
        budgetToken = null
        if (unregisterFromBudget && token != null) {
            budget.unregister(token)
        }
        if (current != null) {
            if (uiState.isEnded) {
                savedPositionMs = 0L
            } else if (current.playbackState == Player.STATE_READY || current.currentPosition > 0L) {
                savedPositionMs = current.currentPosition.coerceAtLeast(0L)
            }
            val duration = current.duration.takeIf { it > 0L }
            if (duration != null) uiState.durationMs = duration
            player = null
            cancelProgressTicker()
            playerView.player = null
            current.release()
        }
        val preserveTerminalState =
            uiState.hasError ||
                uiState.isEnded ||
                lastReportedState == PlayState.ERROR ||
                lastReportedState == PlayState.PLAY_END
        uiState.isDormant = true
        uiState.isBuffering = false
        uiState.playWhenReady = false
        if (!audioOnly && !uiState.hasError) {
            reportFirstFrameOnce()
        }
        if (!preserveTerminalState) {
            reportState(PlayState.PAUSED)
        }
    }

    fun setAudioOnly(value: Boolean) {
        audioOnly = value
    }

    fun applyPlayControl(control: VideoPlayControl) {
        if (control == appliedPlayControl) return
        appliedPlayControl = control
        when (control) {
            // Prepare video cards within the concurrency budget; keep idle inline audio dormant
            // until the user taps Play or scrubs.
            VideoPlayControl.PREPLAY -> {
                if (raftMediaPlayerShouldPrepareOnAttach(role, audioOnly)) {
                    ensurePlayer(prepareNow = true)
                } else {
                    uiState.isDormant = true
                }
            }
            VideoPlayControl.PLAY -> play()
            VideoPlayControl.PAUSE -> pause()
            VideoPlayControl.STOP -> stop()
        }
    }

    fun applySeekRequest(id: Int, timeMs: Long) {
        if (id == appliedSeekRequestId) return
        appliedSeekRequestId = id
        seekToTime(timeMs)
    }

    /** Applies host `muted` changes without clobbering user toggles on unrelated recompositions. */
    fun applyHostMuted(muted: Boolean) {
        if (stopped || muted == appliedHostMuted) return
        appliedHostMuted = muted
        setEffectiveMuted(muted)
    }

    /** Applies host `rate` changes without clobbering user speed picks on unrelated recompositions. */
    fun applyHostRate(rate: Float) {
        if (stopped) return
        val normalized = if (rate.isFinite()) rate.coerceAtLeast(0.25f) else 1f
        if (normalized == appliedHostRate) return
        appliedHostRate = normalized
        setEffectiveRate(normalized)
    }

    override fun togglePlayOrReplay() {
        if (stopped) return
        if (uiState.hasError) {
            retryAfterError()
            return
        }
        val activePlayer = player
        if (uiState.isEnded || activePlayer?.playbackState == Player.STATE_ENDED) {
            play()
        } else if (uiState.isPlaying) {
            pause()
        } else {
            play()
        }
    }

    override fun seekToUserTime(targetMs: Long) {
        seekToTime(targetMs)
    }

    override fun seekRelative(deltaMs: Long) {
        if (stopped) return
        val duration = (player?.duration?.takeIf { it > 0L } ?: uiState.durationMs).coerceAtLeast(0L)
        if (duration <= 0L) return
        val target = raftAndroidVideoResolveRelativeSeekTarget(uiState.positionMs, deltaMs, duration)
        seekToTime(target)
    }

    override fun toggleMuted() {
        if (stopped) return
        setEffectiveMuted(!uiState.muted)
    }

    override fun cycleRate() {
        if (stopped) return
        setEffectiveRate(raftAndroidVideoNextSpeed(uiState.rate))
    }

    override fun retryAfterError() {
        if (stopped) return
        uiState.hasError = false
        uiState.isEnded = false
        uiState.isBuffering = true
        lastReportedState = null
        val activePlayer = ensurePlayer(prepareNow = false, playingHint = true) ?: return
        budgetToken?.let { budget.markActivity(it, playing = true) }
        activePlayer.prepare()
        activePlayer.play()
        ensureProgressTicker()
    }

    private fun setEffectiveMuted(muted: Boolean) {
        uiState.muted = muted
        player?.volume = if (muted) 0f else 1f
    }

    private fun setEffectiveRate(rate: Float) {
        val target = if (rate.isFinite()) rate.coerceAtLeast(0.25f) else 1f
        uiState.rate = target
        player?.let { activePlayer ->
            if (activePlayer.playbackParameters.speed != target) {
                activePlayer.playbackParameters = PlaybackParameters(target)
            }
        }
    }

    private fun play() {
        if (stopped) return
        if (uiState.hasError) {
            retryAfterError()
            return
        }
        val wasEnded = uiState.isEnded || player?.playbackState == Player.STATE_ENDED
        if (wasEnded) {
            savedPositionMs = 0L
        }
        val activePlayer = ensurePlayer(prepareNow = true, playingHint = true) ?: return
        if (wasEnded || activePlayer.playbackState == Player.STATE_ENDED) {
            val progress = progressContract.replay()
            terminalGate.begin(progress.generation)
            awaitingAttemptStart = true
            uiState.isEnded = false
            uiState.positionMs = progress.currentMs
            if (progress.durationMs > 0L) uiState.durationMs = progress.durationMs
            if (!releaseDeferred) callbacks.onPlayTime(progress.currentMs, progress.durationMs)
            activePlayer.seekTo(0L)
            ensureProgressTicker(progress.generation)
        }
        budgetToken?.let { budget.markActivity(it, playing = true) }
        activePlayer.play()
        ensureProgressTicker()
    }

    private fun pause() {
        if (stopped) return
        player?.pause()
    }

    /**
     * The host Activity was stopped (app in background): pause without changing the applied
     * play control, so returning to the app does not auto-resume; inline cards also release
     * their ExoPlayer into dormant state so backgrounded apps hold zero inline decoders.
     */
    fun pauseForHostBackground() {
        if (stopped) return
        val activePlayer = player
        if (activePlayer != null && activePlayer.playWhenReady) {
            activePlayer.pause()
        }
        if (role != RaftMediaPlayerRole.FilePreview && fullscreenContainer == null && player != null) {
            releasePlayerToDormant(unregisterFromBudget = true)
        }
    }

    /**
     * Compose released the inline slot. In fullscreen that happens on its own: the rotation to
     * landscape re-lays the message list out and can drop this row, which used to end fullscreen
     * and rotate straight back. The fullscreen player lives on the Activity, so keep it until the
     * person leaves fullscreen, and release then.
     */
    fun stop() {
        if (stopped) return
        if (fullscreenContainer != null) {
            releaseDeferred = true
            return
        }
        release()
    }

    private fun release() {
        if (stopped) return
        stopped = true
        val token = budgetToken
        budgetToken = null
        if (token != null) budget.unregister(token)
        cancelProgressTicker()
        mainHandler.removeCallbacksAndMessages(null)
        val current = player
        player = null
        playerView.player = null
        current?.release()
    }

    private fun seekToTime(seekToTimeMs: Long) {
        if (stopped) return
        val activePlayer = ensurePlayer(prepareNow = true) ?: return
        syncDurationAndBuffered(activePlayer)
        val duration = (activePlayer.duration.takeIf { it > 0L } ?: uiState.durationMs).coerceAtLeast(0L)
        val clampedTarget = if (duration > 0L) seekToTimeMs.coerceIn(0L, duration) else seekToTimeMs.coerceAtLeast(0L)
        val seekingToEnd = duration > 0L && clampedTarget >= duration
        val progress = progressContract.seek(clampedTarget)
        savedPositionMs = progress.currentMs
        terminalGate.begin(progress.generation)
        if (seekingToEnd) {
            // Seeking directly to duration transitions straight to STATE_ENDED without passing
            // through an in-progress playing frame; keep the terminal gate open so STATE_ENDED
            // cleanly settles into PLAY_END (showing Replay) instead of sticking in PLAYING.
            terminalGate.markStarted(progress.generation)
            awaitingAttemptStart = false
        } else {
            awaitingAttemptStart = true
        }
        uiState.isEnded = false
        uiState.positionMs = progress.currentMs
        val effectiveDuration = if (progress.durationMs > 0L) progress.durationMs else duration
        if (effectiveDuration > 0L) uiState.durationMs = effectiveDuration
        if (!releaseDeferred) callbacks.onPlayTime(progress.currentMs, effectiveDuration)
        activePlayer.seekTo(progress.currentMs)
        if (!seekingToEnd && (uiState.playState == PlayState.PLAY_END || lastReportedState == PlayState.PLAY_END)) {
            reportState(if (activePlayer.playWhenReady) PlayState.PLAYING else PlayState.PAUSED)
        }
        if (seekingToEnd && activePlayer.playbackState == Player.STATE_ENDED) {
            pollProgressOnce(progress.generation, nativeEnded = true)
            return
        }
        ensureProgressTicker(progress.generation)
    }

    private fun syncDurationAndBuffered(activePlayer: ExoPlayer = player ?: return) {
        val duration = activePlayer.duration.takeIf { it > 0L } ?: 0L
        if (duration > 0L) uiState.durationMs = duration
        val buffered = activePlayer.bufferedPosition.takeIf { it >= 0L } ?: 0L
        uiState.bufferedPositionMs = if (duration > 0L) buffered.coerceIn(0L, duration) else buffered
    }

    private fun shouldKeepProgressTickerRunning(): Boolean {
        val activePlayer = player ?: return false
        return !stopped && (
            activePlayer.isPlaying ||
                activePlayer.playbackState == Player.STATE_BUFFERING ||
                (activePlayer.playWhenReady && activePlayer.playbackState != Player.STATE_ENDED)
            )
    }

    private fun cancelProgressTicker() {
        progressTickerRunnable?.let(mainHandler::removeCallbacks)
        progressTickerRunnable = null
        scheduledTickerGeneration = null
    }

    private fun ensureProgressTicker(generation: Long = progressContract.currentGeneration) {
        if (stopped || player == null) return
        if (scheduledTickerGeneration == generation) return
        cancelProgressTicker()
        scheduledTickerGeneration = generation
        scheduleProgressTicker(generation)
    }

    private fun scheduleProgressTicker(generation: Long = progressContract.currentGeneration) {
        val runnable =
            Runnable {
                if (scheduledTickerGeneration == generation) {
                    scheduledTickerGeneration = null
                    progressTickerRunnable = null
                }
                if (stopped || player == null || !terminalGate.acceptsProgress(generation)) return@Runnable
                pollProgressOnce(generation)
                if (shouldKeepProgressTickerRunning() && terminalGate.acceptsProgress(generation)) {
                    scheduledTickerGeneration = generation
                    scheduleProgressTicker(generation)
                }
            }
        progressTickerRunnable = runnable
        mainHandler.postDelayed(runnable, PROGRESS_TICK_MS)
    }

    private fun pollProgressOnce(
        generation: Long,
        nativeEnded: Boolean = false,
    ) {
        val activePlayer = player ?: return
        if (stopped || !terminalGate.acceptsProgress(generation)) return
        syncDurationAndBuffered(activePlayer)
        val knownDuration = (activePlayer.duration.takeIf { it > 0L } ?: uiState.durationMs).coerceAtLeast(0L)
        val duration = if (knownDuration > 0L) knownDuration else if (nativeEnded) activePlayer.currentPosition.coerceAtLeast(0L) else 0L
        val rawPosition = if (nativeEnded && duration > 0L) duration else activePlayer.currentPosition
        val progress = progressContract.progress(rawPosition, duration, generation)
        if (awaitingAttemptStart && activePlayer.isPlaying && !progress.terminal) {
            terminalGate.markStarted(progress.generation)
            awaitingAttemptStart = false
        }
        val reachedEnd = !progress.terminal && ((nativeEnded && duration >= 0L) || (duration > 0L && progress.currentMs >= duration))
        if (reachedEnd) {
            terminalGate.markTerminalEligible(progress.generation)
            if (terminalGate.acceptsTerminal(progress.generation)) {
                val terminal = progressContract.ended(duration, progress.generation)
                savedPositionMs = 0L
                uiState.isEnded = true
                uiState.positionMs = terminal.currentMs
                uiState.durationMs = terminal.durationMs
                if (!releaseDeferred) callbacks.onPlayTime(terminal.currentMs, terminal.durationMs)
                reportState(PlayState.PLAY_END)
                return
            }
        }
        savedPositionMs = progress.currentMs
        uiState.positionMs = progress.currentMs
        if (progress.durationMs > 0L) uiState.durationMs = progress.durationMs
        if (!releaseDeferred) callbacks.onPlayTime(progress.currentMs, progress.durationMs)
    }

    private fun applyFullscreenOrientationIfKnown() {
        val activity = hostActivity ?: return
        val target = raftAndroidVideoFullscreenOrientation(videoWidth, videoHeight, videoPixelWidthHeightRatio)
        if (videoWidth > 0 && videoHeight > 0) {
            fullscreenOrientationApplied = true
            if (target != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
                activity.requestedOrientation = target
            }
        }
    }

    override fun enterFullscreen() {
        if (stopped || audioOnly || fullscreenContainer != null) return
        val activity = hostActivity ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val activePlayer = ensurePlayer(prepareNow = true) ?: return
        if (videoWidth <= 0 || videoHeight <= 0) {
            val currentSize = activePlayer.videoSize
            if (currentSize.width > 0 && currentSize.height > 0) {
                videoWidth = currentSize.width
                videoHeight = currentSize.height
                videoPixelWidthHeightRatio = currentSize.pixelWidthHeightRatio
            }
        }
        removeView(playerView)
        val overlayView =
            ComposeView(activity).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setContent {
                    RaftAndroidVideoControlsOverlay(
                        state = uiState,
                        actions = this@RaftAndroidVideoView,
                        fullscreen = true,
                        audioOnly = false,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        val container =
            FrameLayout(activity).apply {
                setBackgroundColor(FULLSCREEN_BACKGROUND)
                isClickable = true
                isFocusable = true
                addView(playerView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
                addView(overlayView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            }
        content.addView(
            container,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        fullscreenContainer = container
        fullscreenComposeView = overlayView
        uiState.isFullscreen = true
        budgetToken?.let { budget.markActivity(it, fullscreen = true) }
        savedOrientation = activity.requestedOrientation
        fullscreenOrientationApplied = false
        applyFullscreenOrientationIfKnown()
        WindowInsetsControllerCompat(activity.window, activity.window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        fullscreenBackCallback =
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    exitFullscreen()
                }
            }.also { activity.onBackPressedDispatcher.addCallback(it) }
        // Observe ON_STOP (background pause even when inline row was recycled) and ON_DESTROY.
        activity.lifecycle.addObserver(fullscreenLifecycleObserver)
    }

    override fun exitFullscreen() {
        val container = fullscreenContainer ?: return
        fullscreenContainer = null
        val overlayView = fullscreenComposeView
        fullscreenComposeView = null
        fullscreenOrientationApplied = false
        fullscreenBackCallback?.remove()
        fullscreenBackCallback = null
        hostActivity?.lifecycle?.removeObserver(fullscreenLifecycleObserver)
        overlayView?.disposeComposition()
        container.removeAllViews()
        (container.parent as? ViewGroup)?.removeView(container)
        // The inline slot is gone (stop() deferred): nothing to return to.
        if (!releaseDeferred) addView(playerView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        hostActivity?.let { activity ->
            activity.requestedOrientation = savedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            WindowInsetsControllerCompat(activity.window, activity.window.decorView)
                .show(WindowInsetsCompat.Type.systemBars())
        }
        savedOrientation = null
        uiState.isFullscreen = false
        budgetToken?.let { budget.markActivity(it, fullscreen = false) }
        if (releaseDeferred) release()
    }

    private fun reportFirstFrameOnce() {
        if (firstFrameReported) return
        firstFrameReported = true
        if (!releaseDeferred) callbacks.onFirstFrame()
    }

    private fun reportState(state: PlayState) {
        if (stopped) return
        uiState.playState = state
        if (releaseDeferred) return
        if (state == lastReportedState) return
        lastReportedState = state
        callbacks.onPlayState(state)
    }

    private companion object {
        const val PROGRESS_TICK_MS = 250L
        const val FULLSCREEN_BACKGROUND = 0xFF000000.toInt()
    }
}

private fun Context.unwrapRaftComponentActivity(): ComponentActivity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is ComponentActivity) return current
        current = current.baseContext
    }
    return null
}
