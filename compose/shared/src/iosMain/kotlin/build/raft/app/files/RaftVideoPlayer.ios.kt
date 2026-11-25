package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitView
import androidx.compose.ui.viewinterop.UIKitViewController
import build.raft.app.compat.ui.PlayState
import build.raft.app.compat.ui.VideoPlayControl
import build.raft.app.components.RaftSlotIcon
import build.raft.app.resources.*
import build.raft.designsystem.tokens.RaftUiPalette
import kotlin.math.roundToLong
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionModeDefault
import platform.AVFoundation.*
import platform.AVKit.AVPlayerViewController
import platform.AVKit.AVPlayerViewControllerDelegateProtocol
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMake
import platform.Foundation.NSLog
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSThread
import platform.Foundation.NSURL
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIColor
import platform.UIKit.UIView
import platform.UIKit.UIViewControllerTransitionCoordinatorProtocol
import platform.darwin.NSObject
import platform.darwin.NSObjectProtocol

internal const val RAFT_IOS_INLINE_FORWARD_BUFFER_SECONDS = 5.0
internal const val RAFT_IOS_PREVIEW_FORWARD_BUFFER_SECONDS = 15.0

internal fun raftIosVideoPreferredForwardBufferSeconds(
    role: RaftMediaPlayerRole,
    audioOnly: Boolean,
): Double =
    if (role == RaftMediaPlayerRole.FilePreview) {
        RAFT_IOS_PREVIEW_FORWARD_BUFFER_SECONDS
    } else {
        RAFT_IOS_INLINE_FORWARD_BUFFER_SECONDS
    }

private class RaftIosCompositionActiveFlag(var active: Boolean = true)

@OptIn(ExperimentalForeignApi::class)
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
    val vlcFactory = RaftIosVlcVideoHost.factory
    if (LocalRaftVideoPlaybackEngine.current == RaftVideoPlaybackEngine.Vlc && vlcFactory != null) {
        RaftIosVlcVideoSurface(
            factory = vlcFactory,
            source = source,
            role = role,
            audioOnly = audioOnly,
            playControl = playControl,
            seekRequestId = seekRequestId,
            seekTimeMs = seekTimeMs,
            muted = muted,
            rate = rate,
            onPlayStateChanged = { stateCallback(it) },
            onPlayTimeChanged = { current, total -> timeCallback(current, total) },
            onFirstFrame = { firstFrameCallback() },
            modifier = modifier,
        )
        return
    }
    key(source) {
        val shouldPrepareOnAttach = raftMediaPlayerShouldPrepareOnAttach(role, audioOnly)
        val compositionActive = remember { RaftIosCompositionActiveFlag(true) }
        DisposableEffect(compositionActive) { onDispose { compositionActive.active = false } }

        var lastConsumedSeekRequestId by remember { mutableStateOf<Int?>(null) }
        val hasUnconsumedSeek = seekRequestId != null && seekRequestId != lastConsumedSeekRequestId
        var dormant by remember {
            mutableStateOf(!shouldPrepareOnAttach && playControl != VideoPlayControl.PLAY && !hasUnconsumedSeek)
        }
        var sessionNonce by remember { mutableIntStateOf(0) }
        var savedPositionMs by remember { mutableLongStateOf(0L) }
        var wakeIntoPlay by remember { mutableStateOf(false) }

        LaunchedEffect(playControl, seekRequestId) {
            if (playControl != VideoPlayControl.PREPLAY) {
                wakeIntoPlay = false
            }
            val pendingSeek = seekRequestId != null && seekRequestId != lastConsumedSeekRequestId
            if (dormant && (playControl == VideoPlayControl.PLAY || pendingSeek)) {
                sessionNonce += 1
                dormant = false
            }
        }

        if (dormant) {
            if (audioOnly) {
                Box(modifier = modifier)
            } else {
                RaftIosDormantVideoPlaceholder(
                    onPlay = {
                        wakeIntoPlay = true
                        sessionNonce += 1
                        dormant = false
                    },
                    modifier = modifier,
                )
            }
            return@key
        }

        val effectivePlayControl = if (wakeIntoPlay) VideoPlayControl.PLAY else playControl
        val initialPlaying = effectivePlayControl == VideoPlayControl.PLAY
        key(sessionNonce) {
            val controller =
                remember {
                    RaftIosVideoController(
                        src = source,
                        role = role,
                        audioOnly = audioOnly,
                        initialPlaying = initialPlaying,
                        initialSeekMs = savedPositionMs,
                        onState = { stateCallback(it) },
                        onTime = { current, total -> timeCallback(current, total) },
                        onFirstFrame = { firstFrameCallback() },
                        onDormantEvicted = { positionMs, preserveTerminalState, isError ->
                            if (!compositionActive.active) return@RaftIosVideoController
                            savedPositionMs = positionMs
                            wakeIntoPlay = false
                            dormant = true
                            if (!audioOnly && !isError) firstFrameCallback()
                            if (!preserveTerminalState) stateCallback(PlayState.PAUSED)
                        },
                    )
                }
            LaunchedEffect(controller) {
                // Mirrors the 0.5s AVPlayer periodic time observer of the Kuikly handler; stops
                // immediately once released, evicted to dormant, or unmounted off-page/off-screen.
                while (isActive && !controller.isReleased) {
                    controller.tick()
                    delay(RAFT_IOS_VIDEO_TICK_MS)
                }
            }
            UIKitViewController(
                factory = { controller.viewController },
                modifier = modifier,
                update = {
                    controller.setMuted(muted)
                    controller.setRate(rate)
                    controller.applyPlayControl(effectivePlayControl)
                    if (seekRequestId != null && seekRequestId != lastConsumedSeekRequestId) {
                        if (controller.applySeekRequest(seekRequestId, seekTimeMs.toLong())) {
                            lastConsumedSeekRequestId = seekRequestId
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun RaftIosDormantVideoPlaceholder(
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val playLabel = stringResource(Res.string.common_play)
    Box(
        modifier =
            modifier
                .background(RaftUiPalette.black)
                .clickable(role = Role.Button, onClick = onPlay)
                .semantics { contentDescription = playLabel },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(RaftUiPalette.white.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            RaftSlotIcon(
                name = RaftLucideIconName.Play,
                size = 22.dp,
                color = RaftUiPalette.white,
            )
        }
    }
}

private const val RAFT_IOS_VIDEO_TICK_MS = 500L

@OptIn(ExperimentalForeignApi::class)
private class RaftIosAvPlayerFullscreenDelegate(
    private val onFullscreenChanged: (Boolean) -> Unit,
) : NSObject(), AVPlayerViewControllerDelegateProtocol {
    @ObjCSignatureOverride
    override fun playerViewController(
        playerViewController: AVPlayerViewController,
        willBeginFullScreenPresentationWithAnimationCoordinator: UIViewControllerTransitionCoordinatorProtocol,
    ) {
        onFullscreenChanged(true)
        willBeginFullScreenPresentationWithAnimationCoordinator.animateAlongsideTransition(
            animation = null,
            completion = { context ->
                if (context?.isCancelled() == true) {
                    onFullscreenChanged(false)
                }
            },
        )
    }

    @ObjCSignatureOverride
    override fun playerViewController(
        playerViewController: AVPlayerViewController,
        willEndFullScreenPresentationWithAnimationCoordinator: UIViewControllerTransitionCoordinatorProtocol,
    ) {
        willEndFullScreenPresentationWithAnimationCoordinator.animateAlongsideTransition(
            animation = null,
            completion = { context ->
                if (context?.isCancelled() != true) {
                    onFullscreenChanged(false)
                }
            },
        )
    }
}

/**
 * AVPlayer in an embedded AVPlayerViewController (system controller: play/pause, scrubbing, +-15s,
 * fullscreen), ported from the Kuikly-era `SlockKRVideoViewHandler.m` (task #11). Bounded by
 * [RaftProcessMediaPlayerBudget] with capped [AVPlayerItem.preferredForwardBufferDuration],
 * fullscreen deferral across landscape rotation, `RememberObserver` abandoned-composition cleanup,
 * and background release for inline cards so video-heavy threads never exhaust hardware pipelines.
 */
@OptIn(ExperimentalForeignApi::class)
private class RaftIosVideoController(
    src: String,
    private val role: RaftMediaPlayerRole,
    private val audioOnly: Boolean,
    initialPlaying: Boolean,
    initialSeekMs: Long,
    private val onState: (PlayState) -> Unit,
    private val onTime: (Int, Int) -> Unit,
    private val onFirstFrame: () -> Unit,
    private val onDormantEvicted: (positionMs: Long, preserveTerminalState: Boolean, isError: Boolean) -> Unit,
    private val budget: RaftMediaPlayerBudget = RaftProcessMediaPlayerBudget,
) : RememberObserver {
    private val budgetToken: Long
    private val item: AVPlayerItem?
    private val player: AVPlayer
    private val fullscreenDelegate: RaftIosAvPlayerFullscreenDelegate
    val viewController: AVPlayerViewController
    private val observers = mutableListOf<NSObjectProtocol>()
    private var released = false
    private var fullscreen = false
    private var releaseDeferred = false
    private var firstFrameReported = false
    private var readyReported = false
    private var ended = false
    private var lastReportedState: PlayState? = null
    private var appliedPlayControl: VideoPlayControl? = null
    private var appliedSeekRequestId: Int? = null
    private var preferredRate: Float = 1f
    private var lastKnownPositionMs: Long = initialSeekMs.coerceAtLeast(0L)

    val isReleased: Boolean
        get() = released

    init {
        // Acquire a concurrency slot BEFORE allocating AVPlayerItem/AVPlayer so any LRU victim
        // releases its decode pipeline first.
        budgetToken =
            budget.register(
                role = role,
                playing = initialPlaying,
                fullscreen = false,
                onEvict = { scheduleEvictToDormant(unregisterFromBudget = false) },
            )
        val builtDelegate = RaftIosAvPlayerFullscreenDelegate(::onFullscreenChanged)
        val builtItem: AVPlayerItem?
        val builtPlayer: AVPlayer
        val builtViewController: AVPlayerViewController
        try {
            builtItem =
                NSURL.URLWithString(src)?.let { url ->
                    AVPlayerItem(uRL = url).also { playerItem ->
                        playerItem.preferredForwardBufferDuration =
                            raftIosVideoPreferredForwardBufferSeconds(role = role, audioOnly = audioOnly)
                        playerItem.canUseNetworkResourcesForLiveStreamingWhilePaused = false
                    }
                }
            builtPlayer = AVPlayer(playerItem = builtItem)
            builtViewController =
                AVPlayerViewController().also { controller ->
                    controller.player = builtPlayer
                    controller.delegate = builtDelegate
                    controller.showsPlaybackControls = true
                    controller.videoGravity = AVLayerVideoGravityResizeAspect
                    controller.view.backgroundColor = UIColor.blackColor
                }
        } catch (throwable: Throwable) {
            budget.unregister(budgetToken)
            throw throwable
        }
        fullscreenDelegate = builtDelegate
        item = builtItem
        player = builtPlayer
        viewController = builtViewController

        raftIosConfigureMediaPlaybackAudioSession()
        // Playback must not continue in the background (the Playback category would allow it):
        // pause when the app enters the background, and release non-fullscreen inline pipelines to
        // dormant so backgrounded sessions do not retain decoders or 500ms tick loops.
        observers +=
            NSNotificationCenter.defaultCenter.addObserverForName(
                UIApplicationDidEnterBackgroundNotification,
                null,
                NSOperationQueue.mainQueue,
            ) { _ ->
                if (!released) {
                    if (player.rate > 0f) pause()
                    if (role != RaftMediaPlayerRole.FilePreview && !fullscreen) {
                        evictToDormantOnMain(unregisterFromBudget = true)
                    }
                }
            }
        if (item != null) {
            val center = NSNotificationCenter.defaultCenter
            observers +=
                center.addObserverForName(AVPlayerItemDidPlayToEndTimeNotification, item, NSOperationQueue.mainQueue) { _ ->
                    ended = true
                    lastKnownPositionMs = 0L
                    budget.markActivity(budgetToken, playing = false, touch = false)
                    reportState(PlayState.PLAY_END)
                }
            observers +=
                center.addObserverForName(AVPlayerItemFailedToPlayToEndTimeNotification, item, NSOperationQueue.mainQueue) { _ ->
                    budget.markActivity(budgetToken, playing = false, touch = false)
                    reportState(PlayState.ERROR)
                }
            observers +=
                center.addObserverForName(AVPlayerItemPlaybackStalledNotification, item, NSOperationQueue.mainQueue) { _ ->
                    reportState(PlayState.BUFFERING)
                }
        }
        if (initialSeekMs > 0L) {
            seekTo(initialSeekMs)
        }
    }

    override fun onRemembered() = Unit

    override fun onForgotten() {
        release()
    }

    override fun onAbandoned() {
        releaseInternal(unregisterFromBudget = true)
    }

    private fun onFullscreenChanged(active: Boolean) {
        if (released) return
        fullscreen = active
        budget.markActivity(budgetToken, fullscreen = active, touch = active)
        if (!active && releaseDeferred) {
            releaseInternal(unregisterFromBudget = true)
        }
    }

    fun tick() {
        if (released) return
        val currentItem = player.currentItem ?: return
        when (currentItem.status) {
            AVPlayerItemStatusReadyToPlay -> {
                if (!readyReported) {
                    readyReported = true
                    reportFirstFrameIfNeeded()
                }
                if (!ended) {
                    when (player.timeControlStatus) {
                        AVPlayerTimeControlStatusPlaying -> {
                            budget.markActivity(budgetToken, playing = true, touch = true)
                            reportState(PlayState.PLAYING)
                        }
                        AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate -> reportState(PlayState.BUFFERING)
                        AVPlayerTimeControlStatusPaused -> {
                            budget.markActivity(budgetToken, playing = false, touch = false)
                            reportState(PlayState.PAUSED)
                        }
                        else -> Unit
                    }
                }
                val current = millis(CMTimeGetSeconds(player.currentTime()))
                val total = millis(CMTimeGetSeconds(currentItem.duration))
                if (!ended && current > 0) lastKnownPositionMs = current.toLong()
                if (!releaseDeferred) onTime(current, total)
            }
            AVPlayerItemStatusFailed -> {
                budget.markActivity(budgetToken, playing = false, touch = false)
                reportState(PlayState.ERROR)
            }
            else -> Unit
        }
    }

    fun applyPlayControl(control: VideoPlayControl) {
        if (released || control == appliedPlayControl) return
        appliedPlayControl = control
        when (control) {
            VideoPlayControl.PREPLAY -> if (lastKnownPositionMs == 0L) seekTo(40L)
            VideoPlayControl.PLAY -> play()
            VideoPlayControl.PAUSE -> pause()
            VideoPlayControl.STOP -> release()
        }
    }

    fun applySeekRequest(id: Int, timeMs: Long): Boolean {
        if (released || id == appliedSeekRequestId) return false
        appliedSeekRequestId = id
        seekTo(timeMs)
        return true
    }

    fun setMuted(muted: Boolean) {
        if (released) return
        if (player.muted != muted) player.muted = muted
    }

    fun setRate(rate: Float) {
        preferredRate = rate.coerceAtLeast(0.25f)
        if (!released && player.rate > 0f && player.rate != preferredRate) {
            player.rate = preferredRate
        }
    }

    private fun play() {
        if (released) return
        if (ended) {
            ended = false
            lastKnownPositionMs = 0L
            seekTo(0L)
        }
        budget.markActivity(budgetToken, playing = true, touch = true)
        if (preferredRate > 0f) player.playImmediatelyAtRate(preferredRate) else player.play()
        reportState(PlayState.PLAYING)
    }

    private fun pause() {
        if (released) return
        player.pause()
        budget.markActivity(budgetToken, playing = false, touch = false)
        reportState(PlayState.PAUSED)
    }

    private fun seekTo(timeMs: Long) {
        if (released) return
        ended = false
        val clamped = timeMs.coerceAtLeast(0L)
        if (clamped > 40L) lastKnownPositionMs = clamped
        val zero = CMTimeMake(value = 0, timescale = 1)
        player.seekToTime(
            CMTimeMake(value = clamped, timescale = 1000),
            toleranceBefore = zero,
            toleranceAfter = zero,
        )
    }

    private fun scheduleEvictToDormant(unregisterFromBudget: Boolean) {
        if (NSThread.isMainThread) {
            evictToDormantOnMain(unregisterFromBudget)
        } else {
            NSOperationQueue.mainQueue.addOperationWithBlock {
                evictToDormantOnMain(unregisterFromBudget)
            }
        }
    }

    private fun evictToDormantOnMain(unregisterFromBudget: Boolean) {
        if (released || fullscreen) return
        val current = millis(CMTimeGetSeconds(player.currentTime())).toLong()
        val positionToRestore = if (ended) 0L else if (current > 0L) current else lastKnownPositionMs
        val isError = lastReportedState == PlayState.ERROR
        val preserveTerminalState = ended || isError
        // Release the native AVPlayer synchronously so hardware pipelines free up immediately, then
        // post Compose state updates to the next main-queue turn so we never mutate MutableState
        // inside another composable's remember{} snapshot.
        releaseInternal(unregisterFromBudget = unregisterFromBudget)
        NSOperationQueue.mainQueue.addOperationWithBlock {
            onDormantEvicted(positionToRestore, preserveTerminalState, isError)
        }
    }

    fun release() {
        if (released) return
        if (fullscreen) {
            releaseDeferred = true
            return
        }
        releaseInternal(unregisterFromBudget = true)
    }

    private fun releaseInternal(unregisterFromBudget: Boolean) {
        if (released) return
        released = true
        if (unregisterFromBudget) budget.unregister(budgetToken)
        val center = NSNotificationCenter.defaultCenter
        observers.forEach { center.removeObserver(it) }
        observers.clear()
        viewController.delegate = null
        player.pause()
        player.replaceCurrentItemWithPlayerItem(null)
        viewController.player = null
    }

    private fun reportFirstFrameIfNeeded() {
        if (firstFrameReported) return
        firstFrameReported = true
        if (!releaseDeferred) onFirstFrame()
    }

    private fun reportState(state: PlayState) {
        if (released || state == lastReportedState) return
        lastReportedState = state
        if (!releaseDeferred) onState(state)
    }

    private fun millis(seconds: Double): Int =
        if (seconds.isNaN() || seconds.isInfinite() || seconds <= 0.0) 0 else (seconds * 1000.0).roundToLong().toInt()
}

/**
 * Attachment audio is intentional media playback, including audio-only files: the default
 * SoloAmbient category silences it in Silent mode. AVPlayer and VLC activate the session only when
 * playback starts, so opening a preview does not interrupt other apps' audio.
 */
@OptIn(ExperimentalForeignApi::class)
private fun raftIosConfigureMediaPlaybackAudioSession() {
    val configured =
        AVAudioSession.sharedInstance().setCategory(
            AVAudioSessionCategoryPlayback,
            mode = AVAudioSessionModeDefault,
            options = 0u,
            error = null,
        )
    if (!configured) NSLog("[RaftMedia] audio session configuration failed")
}

/**
 * The VLC engine's surface: the app's MobileVLCKit player ([RaftIosVlcVideoPlayer]) hosted
 * through UIKitView. Like AVPlayerViewController it owns its controls (play/pause, seek, full
 * screen), shares [RaftProcessMediaPlayerBudget], preserves fullscreen across landscape rotation,
 * and enters dormant tap-to-play state when evicted or backgrounded on inline cards.
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
private fun RaftIosVlcVideoSurface(
    factory: RaftIosVlcVideoPlayerFactory,
    source: String,
    role: RaftMediaPlayerRole,
    audioOnly: Boolean,
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
    val labels =
        RaftIosVlcVideoLabels(
            playLabel = stringResource(Res.string.common_play),
            pauseLabel = stringResource(Res.string.common_pause),
            fullScreenLabel = stringResource(Res.string.video_player_fullscreen),
            exitFullScreenLabel = stringResource(Res.string.video_player_exit_fullscreen),
            playbackFailedLabel = stringResource(Res.string.file_preview_media_playback_failed),
        )
    key(source) {
        val shouldPrepareOnAttach = raftMediaPlayerShouldPrepareOnAttach(role, audioOnly)
        val compositionActive = remember { RaftIosCompositionActiveFlag(true) }
        DisposableEffect(compositionActive) { onDispose { compositionActive.active = false } }

        var lastConsumedSeekRequestId by remember { mutableStateOf<Int?>(null) }
        val hasUnconsumedSeek = seekRequestId != null && seekRequestId != lastConsumedSeekRequestId
        var dormant by remember {
            mutableStateOf(!shouldPrepareOnAttach && playControl != VideoPlayControl.PLAY && !hasUnconsumedSeek)
        }
        var sessionNonce by remember { mutableIntStateOf(0) }
        var savedPositionMs by remember { mutableLongStateOf(0L) }
        var wakeIntoPlay by remember { mutableStateOf(false) }

        LaunchedEffect(playControl, seekRequestId) {
            if (playControl != VideoPlayControl.PREPLAY) {
                wakeIntoPlay = false
            }
            val pendingSeek = seekRequestId != null && seekRequestId != lastConsumedSeekRequestId
            if (dormant && (playControl == VideoPlayControl.PLAY || pendingSeek)) {
                sessionNonce += 1
                dormant = false
            }
        }

        if (dormant) {
            if (audioOnly) {
                Box(modifier = modifier)
            } else {
                RaftIosDormantVideoPlaceholder(
                    onPlay = {
                        wakeIntoPlay = true
                        sessionNonce += 1
                        dormant = false
                    },
                    modifier = modifier,
                )
            }
            return@key
        }

        val effectivePlayControl = if (wakeIntoPlay) VideoPlayControl.PLAY else playControl
        val initialPlaying = effectivePlayControl == VideoPlayControl.PLAY
        key(sessionNonce) {
            val controller =
                remember {
                    RaftIosVlcVideoController(
                        factory = factory,
                        source = source,
                        labels = labels,
                        role = role,
                        initialPlaying = initialPlaying,
                        initialSeekMs = savedPositionMs,
                        stateSink = onPlayStateChanged,
                        timeSink = onPlayTimeChanged,
                        firstFrameSink = onFirstFrame,
                        onDormantEvicted = { positionMs, preserveTerminalState, isError ->
                            if (!compositionActive.active) return@RaftIosVlcVideoController
                            savedPositionMs = positionMs
                            wakeIntoPlay = false
                            dormant = true
                            if (!audioOnly && !isError) onFirstFrame()
                            if (!preserveTerminalState) onPlayStateChanged(PlayState.PAUSED)
                        },
                    )
                }
            UIKitView(
                factory = { controller.view },
                modifier = modifier,
                update = {
                    controller.setMuted(muted)
                    controller.setRate(rate)
                    controller.applyPlayControl(effectivePlayControl)
                    if (seekRequestId != null && seekRequestId != lastConsumedSeekRequestId) {
                        if (controller.applySeekRequest(seekRequestId, seekTimeMs.toLong())) {
                            lastConsumedSeekRequestId = seekRequestId
                        }
                    }
                },
            )
        }
    }
}

/**
 * Drives one [RaftIosVlcVideoPlayer] with the AVPlayer controller's contract: the play control is
 * applied when it changes, a seek once per request id, and the player's reports become
 * [PlayState]s. `onReady` is the first frame; `onFailed` before anything played is VLC failing to
 * open the file, which the file page turns into the download card ([raftVideoFallbackEngineOpenFailed]).
 */
@OptIn(ExperimentalForeignApi::class)
private class RaftIosVlcVideoController(
    factory: RaftIosVlcVideoPlayerFactory,
    source: String,
    labels: RaftIosVlcVideoLabels,
    private val role: RaftMediaPlayerRole,
    initialPlaying: Boolean,
    initialSeekMs: Long,
    private val stateSink: (PlayState) -> Unit,
    private val timeSink: (Int, Int) -> Unit,
    private val firstFrameSink: () -> Unit,
    private val onDormantEvicted: (positionMs: Long, preserveTerminalState: Boolean, isError: Boolean) -> Unit,
    private val budget: RaftMediaPlayerBudget = RaftProcessMediaPlayerBudget,
) : RaftIosVlcVideoEvents, RememberObserver {
    private val budgetToken: Long
    private val player: RaftIosVlcVideoPlayer
    val view: UIView
    private var released = false
    private var fullscreen = false
    private var releaseDeferred = false
    private var playing = false
    private var ended = false
    private var firstFrameReported = false
    private var lastReportedState: PlayState? = null
    private var appliedPlayControl: VideoPlayControl? = null
    private var appliedSeekRequestId: Int? = null
    private var appliedMuted: Boolean? = null
    private var appliedRate: Float? = null
    private var lastKnownPositionMs: Long = initialSeekMs.coerceAtLeast(0L)
    private val backgroundObserver: NSObjectProtocol

    init {
        budgetToken =
            budget.register(
                role = role,
                playing = initialPlaying,
                fullscreen = false,
                onEvict = { scheduleEvictToDormant(unregisterFromBudget = false) },
            )
        player =
            try {
                factory.createPlayer(source, labels)
            } catch (throwable: Throwable) {
                budget.unregister(budgetToken)
                throw throwable
            }
        view = player.playerView()
        raftIosConfigureMediaPlaybackAudioSession()
        player.listen(this)
        // No playback in the background, as on the AVPlayer path: pause, and release non-fullscreen
        // inline VLC decoders into dormant state so backgrounded apps hold zero libVLC pipelines.
        backgroundObserver =
            NSNotificationCenter.defaultCenter.addObserverForName(
                UIApplicationDidEnterBackgroundNotification,
                null,
                NSOperationQueue.mainQueue,
            ) { _ ->
                if (!released) {
                    if (playing) player.pause()
                    if (role != RaftMediaPlayerRole.FilePreview && !fullscreen) {
                        evictToDormantOnMain(unregisterFromBudget = true)
                    }
                }
            }
        if (initialSeekMs > 0L) {
            player.seekTo(initialSeekMs)
        }
    }

    override fun onRemembered() = Unit

    override fun onForgotten() {
        release()
    }

    override fun onAbandoned() {
        releaseInternal(unregisterFromBudget = true)
    }

    fun applyPlayControl(control: VideoPlayControl) {
        if (released || control == appliedPlayControl) return
        appliedPlayControl = control
        when (control) {
            VideoPlayControl.PREPLAY -> player.prepare()
            VideoPlayControl.PLAY -> {
                if (ended) {
                    ended = false
                    lastKnownPositionMs = 0L
                    player.seekTo(0L)
                }
                budget.markActivity(budgetToken, playing = true, touch = true)
                player.play()
            }
            VideoPlayControl.PAUSE -> {
                budget.markActivity(budgetToken, playing = false, touch = false)
                player.pause()
            }
            VideoPlayControl.STOP -> release()
        }
    }

    fun applySeekRequest(id: Int, timeMs: Long): Boolean {
        if (released || id == appliedSeekRequestId) return false
        appliedSeekRequestId = id
        ended = false
        val clamped = timeMs.coerceAtLeast(0L)
        lastKnownPositionMs = clamped
        player.seekTo(clamped)
        return true
    }

    fun setMuted(muted: Boolean) {
        if (released || muted == appliedMuted) return
        appliedMuted = muted
        player.applyMuted(muted)
    }

    fun setRate(rate: Float) {
        val next = rate.coerceAtLeast(0.25f)
        if (released || next == appliedRate) return
        appliedRate = next
        player.applyRate(next)
    }

    private fun scheduleEvictToDormant(unregisterFromBudget: Boolean) {
        if (NSThread.isMainThread) {
            evictToDormantOnMain(unregisterFromBudget)
        } else {
            NSOperationQueue.mainQueue.addOperationWithBlock {
                evictToDormantOnMain(unregisterFromBudget)
            }
        }
    }

    private fun evictToDormantOnMain(unregisterFromBudget: Boolean) {
        if (released || fullscreen) return
        val positionToRestore = if (ended) 0L else lastKnownPositionMs
        val isError = lastReportedState == PlayState.ERROR
        val preserveTerminalState = ended || isError
        releaseInternal(unregisterFromBudget = unregisterFromBudget)
        NSOperationQueue.mainQueue.addOperationWithBlock {
            onDormantEvicted(positionToRestore, preserveTerminalState, isError)
        }
    }

    fun release() {
        if (released) return
        if (fullscreen) {
            releaseDeferred = true
            return
        }
        releaseInternal(unregisterFromBudget = true)
    }

    private fun releaseInternal(unregisterFromBudget: Boolean) {
        if (released) return
        released = true
        if (unregisterFromBudget) budget.unregister(budgetToken)
        NSNotificationCenter.defaultCenter.removeObserver(backgroundObserver)
        player.listen(null)
        player.dispose()
    }

    override fun onReady(durationMs: Long) {
        if (released) return
        if (!releaseDeferred) timeSink(0, durationMs.toPlayerMillis())
        reportFirstFrameIfNeeded()
    }

    override fun onPlaying() {
        playing = true
        ended = false
        budget.markActivity(budgetToken, playing = true, touch = true)
        reportFirstFrameIfNeeded()
        reportState(PlayState.PLAYING)
    }

    override fun onPaused() {
        playing = false
        budget.markActivity(budgetToken, playing = false, touch = false)
        reportState(PlayState.PAUSED)
    }

    override fun onBuffering() {
        reportState(PlayState.BUFFERING)
    }

    override fun onEnded() {
        playing = false
        ended = true
        lastKnownPositionMs = 0L
        budget.markActivity(budgetToken, playing = false, touch = false)
        reportState(PlayState.PLAY_END)
    }

    override fun onFailed() {
        playing = false
        budget.markActivity(budgetToken, playing = false, touch = false)
        NSLog("[RaftMedia] vlc playback failed")
        reportState(PlayState.ERROR)
    }

    override fun onTime(currentMs: Long, durationMs: Long) {
        if (released) return
        if (!ended && currentMs > 0L) lastKnownPositionMs = currentMs
        if (!releaseDeferred) timeSink(currentMs.toPlayerMillis(), durationMs.toPlayerMillis())
    }

    override fun onFullscreenChanged(fullscreen: Boolean) {
        if (released) return
        this.fullscreen = fullscreen
        budget.markActivity(budgetToken, fullscreen = fullscreen, touch = fullscreen)
        if (!fullscreen && releaseDeferred) {
            releaseInternal(unregisterFromBudget = true)
        }
    }

    private fun reportFirstFrameIfNeeded() {
        if (released || firstFrameReported) return
        firstFrameReported = true
        if (!releaseDeferred) firstFrameSink()
    }

    private fun reportState(state: PlayState) {
        if (released || state == lastReportedState) return
        lastReportedState = state
        if (!releaseDeferred) stateSink(state)
    }

    private fun Long.toPlayerMillis(): Int = coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
}
