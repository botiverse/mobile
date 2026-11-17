package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import androidx.compose.ui.viewinterop.UIKitViewController
import build.raft.app.compat.ui.PlayState
import build.raft.app.resources.*
import build.raft.app.compat.ui.VideoPlayControl
import kotlin.math.roundToLong
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionModeDefault
import platform.AVFoundation.*
import platform.AVKit.AVPlayerViewController
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMake
import platform.Foundation.NSLog
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIColor
import platform.darwin.NSObjectProtocol

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
    val vlcFactory = RaftIosVlcVideoHost.factory
    if (LocalRaftVideoPlaybackEngine.current == RaftVideoPlaybackEngine.Vlc && vlcFactory != null) {
        RaftIosVlcVideoSurface(
            factory = vlcFactory,
            source = source,
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
        val controller =
            remember {
                RaftIosVideoController(
                    src = source,
                    onState = { stateCallback(it) },
                    onTime = { current, total -> timeCallback(current, total) },
                    onFirstFrame = { firstFrameCallback() },
                )
            }
        DisposableEffect(controller) { onDispose { controller.release() } }
        LaunchedEffect(controller) {
            // Mirrors the 0.5s AVPlayer periodic time observer of the Kuikly handler; also polls
            // item status / timeControlStatus (KVO needs an NSObject subclass).
            while (isActive) {
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
                controller.applyPlayControl(playControl)
                if (seekRequestId != null) controller.applySeekRequest(seekRequestId, seekTimeMs.toLong())
            },
        )
    }
}

private const val RAFT_IOS_VIDEO_TICK_MS = 500L

/**
 * AVPlayer in an embedded AVPlayerViewController (system controller: play/pause, scrubbing, +-15s,
 * fullscreen), ported from the Kuikly-era `SlockKRVideoViewHandler.m` (task #11). The shared
 * minimal controls hide themselves via raftPlatformUsesNativeVideoController().
 */
@OptIn(ExperimentalForeignApi::class)
private class RaftIosVideoController(
    src: String,
    private val onState: (PlayState) -> Unit,
    private val onTime: (Int, Int) -> Unit,
    private val onFirstFrame: () -> Unit,
) {
    private val item: AVPlayerItem? = NSURL.URLWithString(src)?.let { AVPlayerItem(uRL = it) }
    private val player: AVPlayer = AVPlayer(playerItem = item)
    val viewController: AVPlayerViewController =
        AVPlayerViewController().also { controller ->
            controller.player = player
            controller.showsPlaybackControls = true
            controller.videoGravity = AVLayerVideoGravityResizeAspect
            controller.view.backgroundColor = UIColor.blackColor
        }
    private val observers = mutableListOf<NSObjectProtocol>()
    private var released = false
    private var firstFrameReported = false
    private var readyReported = false
    private var ended = false
    private var lastReportedState: PlayState? = null
    private var appliedPlayControl: VideoPlayControl? = null
    private var appliedSeekRequestId: Int? = null
    private var preferredRate: Float = 1f

    init {
        raftIosConfigureMediaPlaybackAudioSession()
        // Playback must not continue in the background (the Playback category would allow it):
        // pause when the app enters the background, like Android does on ON_STOP. The applied
        // play control is unchanged, so returning to the app does not auto-resume.
        observers +=
            NSNotificationCenter.defaultCenter.addObserverForName(
                UIApplicationDidEnterBackgroundNotification,
                null,
                NSOperationQueue.mainQueue,
            ) { _ ->
                if (!released && player.rate > 0f) pause()
            }
        if (item != null) {
            val center = NSNotificationCenter.defaultCenter
            observers +=
                center.addObserverForName(AVPlayerItemDidPlayToEndTimeNotification, item, NSOperationQueue.mainQueue) { _ ->
                    ended = true
                    reportState(PlayState.PLAY_END)
                }
            observers +=
                center.addObserverForName(AVPlayerItemFailedToPlayToEndTimeNotification, item, NSOperationQueue.mainQueue) { _ ->
                    reportState(PlayState.ERROR)
                }
            observers +=
                center.addObserverForName(AVPlayerItemPlaybackStalledNotification, item, NSOperationQueue.mainQueue) { _ ->
                    reportState(PlayState.BUFFERING)
                }
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
                        AVPlayerTimeControlStatusPlaying -> reportState(PlayState.PLAYING)
                        AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate -> reportState(PlayState.BUFFERING)
                        AVPlayerTimeControlStatusPaused -> reportState(PlayState.PAUSED)
                        else -> Unit
                    }
                }
            }
            AVPlayerItemStatusFailed -> reportState(PlayState.ERROR)
            else -> Unit
        }
        val current = millis(CMTimeGetSeconds(player.currentTime()))
        val total = millis(CMTimeGetSeconds(currentItem.duration))
        onTime(current, total)
    }

    fun applyPlayControl(control: VideoPlayControl) {
        if (control == appliedPlayControl) return
        appliedPlayControl = control
        when (control) {
            VideoPlayControl.PREPLAY -> seekTo(40L)
            VideoPlayControl.PLAY -> play()
            VideoPlayControl.PAUSE -> pause()
            VideoPlayControl.STOP -> release()
        }
    }

    fun applySeekRequest(id: Int, timeMs: Long) {
        if (id == appliedSeekRequestId) return
        appliedSeekRequestId = id
        seekTo(timeMs)
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
            seekTo(0L)
        }
        if (preferredRate > 0f) player.playImmediatelyAtRate(preferredRate) else player.play()
        reportState(PlayState.PLAYING)
    }

    private fun pause() {
        if (released) return
        player.pause()
        reportState(PlayState.PAUSED)
    }

    private fun seekTo(timeMs: Long) {
        if (released) return
        ended = false
        val zero = CMTimeMake(value = 0, timescale = 1)
        player.seekToTime(
            CMTimeMake(value = timeMs.coerceAtLeast(0L), timescale = 1000),
            toleranceBefore = zero,
            toleranceAfter = zero,
        )
    }

    fun release() {
        if (released) return
        released = true
        val center = NSNotificationCenter.defaultCenter
        observers.forEach { center.removeObserver(it) }
        observers.clear()
        player.pause()
        player.replaceCurrentItemWithPlayerItem(null)
        viewController.player = null
    }

    private fun reportFirstFrameIfNeeded() {
        if (firstFrameReported) return
        firstFrameReported = true
        onFirstFrame()
    }

    private fun reportState(state: PlayState) {
        if (released || state == lastReportedState) return
        lastReportedState = state
        onState(state)
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
 * screen), so the shared controls stay hidden ([raftPlatformUsesNativeVideoController]).
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
private fun RaftIosVlcVideoSurface(
    factory: RaftIosVlcVideoPlayerFactory,
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
    val labels =
        RaftIosVlcVideoLabels(
            playLabel = stringResource(Res.string.common_play),
            pauseLabel = stringResource(Res.string.common_pause),
            fullScreenLabel = stringResource(Res.string.video_player_fullscreen),
            exitFullScreenLabel = stringResource(Res.string.video_player_exit_fullscreen),
            playbackFailedLabel = stringResource(Res.string.file_preview_media_playback_failed),
        )
    key(source) {
        val controller =
            remember {
                RaftIosVlcVideoController(
                    player = factory.createPlayer(source, labels),
                    stateSink = onPlayStateChanged,
                    timeSink = onPlayTimeChanged,
                    firstFrameSink = onFirstFrame,
                )
            }
        DisposableEffect(controller) { onDispose { controller.release() } }
        UIKitView(
            factory = { controller.view },
            modifier = modifier,
            update = {
                controller.setMuted(muted)
                controller.setRate(rate)
                controller.applyPlayControl(playControl)
                if (seekRequestId != null) controller.applySeekRequest(seekRequestId, seekTimeMs.toLong())
            },
        )
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
    private val player: RaftIosVlcVideoPlayer,
    private val stateSink: (PlayState) -> Unit,
    private val timeSink: (Int, Int) -> Unit,
    private val firstFrameSink: () -> Unit,
) : RaftIosVlcVideoEvents {
    val view = player.playerView()
    private var released = false
    private var playing = false
    private var ended = false
    private var firstFrameReported = false
    private var lastReportedState: PlayState? = null
    private var appliedPlayControl: VideoPlayControl? = null
    private var appliedSeekRequestId: Int? = null
    private var appliedMuted: Boolean? = null
    private var appliedRate: Float? = null
    private val backgroundObserver: NSObjectProtocol

    init {
        raftIosConfigureMediaPlaybackAudioSession()
        player.listen(this)
        // No playback in the background, as on the AVPlayer path: pause, and do not resume on return.
        backgroundObserver =
            NSNotificationCenter.defaultCenter.addObserverForName(
                UIApplicationDidEnterBackgroundNotification,
                null,
                NSOperationQueue.mainQueue,
            ) { _ ->
                if (!released && playing) player.pause()
            }
    }

    fun applyPlayControl(control: VideoPlayControl) {
        if (released || control == appliedPlayControl) return
        appliedPlayControl = control
        when (control) {
            VideoPlayControl.PREPLAY -> player.prepare()
            VideoPlayControl.PLAY -> {
                if (ended) {
                    ended = false
                    player.seekTo(0L)
                }
                player.play()
            }
            VideoPlayControl.PAUSE -> player.pause()
            VideoPlayControl.STOP -> release()
        }
    }

    fun applySeekRequest(id: Int, timeMs: Long) {
        if (released || id == appliedSeekRequestId) return
        appliedSeekRequestId = id
        ended = false
        player.seekTo(timeMs.coerceAtLeast(0L))
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

    fun release() {
        if (released) return
        released = true
        NSNotificationCenter.defaultCenter.removeObserver(backgroundObserver)
        player.listen(null)
        player.dispose()
    }

    override fun onReady(durationMs: Long) {
        if (released) return
        timeSink(0, durationMs.toPlayerMillis())
        reportFirstFrameIfNeeded()
    }

    override fun onPlaying() {
        playing = true
        ended = false
        reportFirstFrameIfNeeded()
        reportState(PlayState.PLAYING)
    }

    override fun onPaused() {
        playing = false
        reportState(PlayState.PAUSED)
    }

    override fun onBuffering() {
        reportState(PlayState.BUFFERING)
    }

    override fun onEnded() {
        playing = false
        ended = true
        reportState(PlayState.PLAY_END)
    }

    override fun onFailed() {
        playing = false
        NSLog("[RaftMedia] vlc playback failed")
        reportState(PlayState.ERROR)
    }

    override fun onTime(currentMs: Long, durationMs: Long) {
        if (released) return
        timeSink(currentMs.toPlayerMillis(), durationMs.toPlayerMillis())
    }

    private fun reportFirstFrameIfNeeded() {
        if (released || firstFrameReported) return
        firstFrameReported = true
        firstFrameSink()
    }

    private fun reportState(state: PlayState) {
        if (released || state == lastReportedState) return
        lastReportedState = state
        stateSink(state)
    }

    private fun Long.toPlayerMillis(): Int = coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
}
