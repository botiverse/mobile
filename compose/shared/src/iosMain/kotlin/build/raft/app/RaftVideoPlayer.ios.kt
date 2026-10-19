package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitViewController
import build.raft.app.compat.ui.PlayState
import build.raft.app.compat.ui.VideoPlayControl
import kotlin.math.roundToLong
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import platform.AVFoundation.*
import platform.AVKit.AVPlayerViewController
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMake
import platform.Foundation.NSLog
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
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
 * minimal controls hide themselves via slockPlatformUsesNativeVideoController().
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
        // Attachment audio is intentional media playback, including audio-only files: the
        // default SoloAmbient category silences it in Silent mode. AVPlayer activates the session
        // only when playback starts, so opening a preview does not interrupt other apps' audio.
        val configured =
            AVAudioSession.sharedInstance().setCategory(
                AVAudioSessionCategoryPlayback,
                mode = AVAudioSessionModeDefault,
                options = 0u,
                error = null,
            )
        if (!configured) NSLog("[RaftMedia] audio session configuration failed")
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
