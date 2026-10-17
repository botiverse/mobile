package build.raft.app.shared

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.tencent.kuikly.core.render.android.adapter.IKRVideoView
import com.tencent.kuikly.core.render.android.adapter.IKRVideoViewAdapter
import com.tencent.kuikly.core.render.android.adapter.IKRVideoViewListener
import com.tencent.kuikly.core.render.android.expand.component.KRVideoPlayState
import com.tencent.kuikly.core.render.android.expand.component.KRVideoViewContentMode
import build.raft.app.SlockVideoProgressContract
import build.raft.app.SlockVideoPlaybackAttemptGate

/**
 * Media3 (ExoPlayer) backed Kuikly video view (task #556).
 *
 * Phase 1 replaced the legacy android.widget.VideoView/MediaPlayer host: one
 * player covers video (mp4/HLS/…) and audio-only sources (mp3/m4a/…) — an
 * audio source simply renders no frames while playback state/progress/seek
 * behave identically.
 *
 * Phase 2 enables PlayerView's built-in controller (tap-to-show play/pause,
 * drag-seek bar, ±10s jumps, fullscreen); the shared preview hides its
 * minimal controls on Android via slockPlatformUsesNativeVideoController().
 * Fullscreen reparents the PlayerView onto the activity's content view,
 * rotates to landscape, and hides the system bars; the system back gesture
 * exits fullscreen before it can leave the preview page.
 */
internal object SlockKRVideoViewAdapter : IKRVideoViewAdapter {
    override fun createVideoView(context: Context, src: String, listener: IKRVideoViewListener): IKRVideoView =
        SlockKRVideoView(context, src, listener)
}

private class SlockKRVideoView(
    context: Context,
    src: String,
    private val listener: IKRVideoViewListener
) : FrameLayout(context), IKRVideoView {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var stopped = false
    private var lastReportedState: KRVideoPlayState? = null
    private val progressContract = SlockVideoProgressContract()
    private val terminalGate = SlockVideoPlaybackAttemptGate(progressContract.currentGeneration)
    private var awaitingAttemptStart = false
    // Media3 does not attach an attempt id to callbacks. Keep the generation
    // observed by the current READY attempt so a late callback from the prior
    // replay/seek attempt is rejected by the shared contract.
    private var callbackGeneration = progressContract.currentGeneration

    private val hostActivity: ComponentActivity? = context.unwrapComponentActivity()
    private var fullscreenContainer: FrameLayout? = null
    private var savedOrientation: Int? = null
    private var fullscreenBackCallback: OnBackPressedCallback? = null

    private val player: ExoPlayer =
        ExoPlayer.Builder(context)
            .setSeekBackIncrementMs(SEEK_JUMP_MS)
            .setSeekForwardIncrementMs(SEEK_JUMP_MS)
            .build()
            .also { player ->
                player.setMediaItem(MediaItem.fromUri(src))
                player.playWhenReady = false
            }

    private val playerView: PlayerView =
        PlayerView(context).also { view ->
            view.useController = true
            view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            // Single media item: prev/next would only ever be disabled chrome.
            view.setShowNextButton(false)
            view.setShowPreviousButton(false)
            view.setFullscreenButtonClickListener { isFullScreen ->
                if (isFullScreen) enterFullscreen() else exitFullscreen()
            }
            view.player = player
            addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }

    private val timeTicker =
        object : Runnable {
            override fun run() {
                if (stopped) return
                val duration = player.duration.takeIf { it > 0 } ?: 0L
                val progress =
                    progressContract.progress(player.currentPosition, duration, callbackGeneration)
                if (awaitingAttemptStart && player.isPlaying &&
                    progress.generation == progressContract.currentGeneration && !progress.terminal
                ) {
                    terminalGate.markStarted(progress.generation)
                    awaitingAttemptStart = false
                }
                if (!progress.terminal && duration > 0L && progress.currentMs >= duration) {
                    terminalGate.markTerminalEligible(progress.generation)
                    if (terminalGate.acceptsTerminal(progress.generation)) {
                        val terminal = progressContract.ended(duration, progress.generation)
                        listener.playTimeDidChangedWithCurrentTime(terminal.currentMs, terminal.durationMs)
                        reportState(KRVideoPlayState.KRVideoPlayStatePlayEnd)
                        mainHandler.postDelayed(this, PROGRESS_TICK_MS)
                        return
                    }
                }
                listener.playTimeDidChangedWithCurrentTime(progress.currentMs, progress.durationMs)
                mainHandler.postDelayed(this, PROGRESS_TICK_MS)
            }
        }

    init {
        player.addListener(
            object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    listener.videoFirstFrameDidDisplay()
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_BUFFERING ->
                            reportState(KRVideoPlayState.KRVideoPlayStateCaching)
                        Player.STATE_READY -> {
                            reportState(
                                if (player.playWhenReady) {
                                    KRVideoPlayState.KRVideoPlayStatePlaying
                                } else {
                                    KRVideoPlayState.KRVideoPlayStatePaused
                                }
                            )
                        }
                        Player.STATE_ENDED -> {
                            // Terminal state is derived by the ticker from a
                            // duration-qualified active generation. Ignoring
                            // native STATE_ENDED prevents an old queued event
                            // from ending the new replay/seek attempt.
                        }
                        Player.STATE_IDLE -> Unit
                    }
                }


                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    if (player.playbackState == Player.STATE_READY) {
                        reportState(
                            if (playWhenReady) {
                                KRVideoPlayState.KRVideoPlayStatePlaying
                            } else {
                                KRVideoPlayState.KRVideoPlayStatePaused
                            }
                        )
                    }
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    reportState(
                        KRVideoPlayState.KRVideoPlayStateFaild,
                        mapOf(
                            "errorCode" to error.errorCode.toString(),
                            "errorCodeName" to error.errorCodeName
                        )
                    )
                }
            }
        )
        player.prepare()
        mainHandler.post(timeTicker)
    }

    override fun preplay() {
        // prepare() already ran with playWhenReady=false: the first frame is
        // decoded and reported without starting playback — the Media3-native
        // equivalent of the old seekTo(1) warm-up hack.
    }

    override fun play() {
        if (stopped) return
        if (player.playbackState == Player.STATE_ENDED) {
            val progress = progressContract.replay()
            callbackGeneration = progress.generation
            terminalGate.begin(progress.generation)
            awaitingAttemptStart = true
            listener.playTimeDidChangedWithCurrentTime(progress.currentMs, progress.durationMs)
            player.seekTo(0L)
        }
        player.play()
    }

    override fun pause() {
        if (stopped) return
        player.pause()
    }

    override fun stop() {
        if (stopped) return
        exitFullscreen()
        stopped = true
        mainHandler.removeCallbacks(timeTicker)
        playerView.player = null
        player.release()
    }

    override fun setVideoContentMode(videoViewContentMode: KRVideoViewContentMode) {
        playerView.resizeMode =
            when (videoViewContentMode) {
                KRVideoViewContentMode.KRVideoViewContentModeScaleAspectFit ->
                    AspectRatioFrameLayout.RESIZE_MODE_FIT
                KRVideoViewContentMode.KRVideoViewContentModeScaleAspectFill ->
                    AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                KRVideoViewContentMode.KRVideoViewContentModeScaleToFill ->
                    AspectRatioFrameLayout.RESIZE_MODE_FILL
            }
    }

    override fun setMuted(muted: Boolean) {
        player.volume = if (muted) 0f else 1f
    }

    override fun setRate(rate: Float) {
        player.playbackParameters = PlaybackParameters(rate.coerceAtLeast(0.25f))
    }

    override fun seekToTime(seekToTimeMs: Long) {
        if (stopped) return
        val progress = progressContract.seek(seekToTimeMs)
        callbackGeneration = progress.generation
        terminalGate.begin(progress.generation)
        awaitingAttemptStart = true
        listener.playTimeDidChangedWithCurrentTime(progress.currentMs, progress.durationMs)
        player.seekTo(progress.currentMs)
    }

    override fun setProp(propKey: String, propValue: Any): Boolean =
        when (propKey) {
            SLOCK_VIDEO_SEEK_TO_TIME_MS_PROP -> {
                propValue.toSlockSeekTimeMsOrNull()?.let(::seekToTime)
                true
            }
            else -> false
        }

    override fun call(method: String, params: String?) = Unit

    private fun enterFullscreen() {
        if (stopped || fullscreenContainer != null) return
        val activity = hostActivity ?: return
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        removeView(playerView)
        val container =
            FrameLayout(context).apply {
                setBackgroundColor(FULLSCREEN_BACKGROUND)
                addView(
                    playerView,
                    LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
                )
            }
        content.addView(
            container,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        fullscreenContainer = container
        savedOrientation = activity.requestedOrientation
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
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
        playerView.setFullscreenButtonState(true)
    }

    private fun exitFullscreen() {
        val container = fullscreenContainer ?: return
        fullscreenContainer = null
        fullscreenBackCallback?.remove()
        fullscreenBackCallback = null
        container.removeView(playerView)
        (container.parent as? ViewGroup)?.removeView(container)
        addView(playerView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        hostActivity?.let { activity ->
            activity.requestedOrientation =
                savedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            WindowInsetsControllerCompat(activity.window, activity.window.decorView)
                .show(WindowInsetsCompat.Type.systemBars())
        }
        savedOrientation = null
        playerView.setFullscreenButtonState(false)
    }

    private fun reportState(state: KRVideoPlayState, extInfo: Map<String, String> = emptyMap()) {
        if (stopped) return
        if (state == lastReportedState && extInfo.isEmpty()) return
        lastReportedState = state
        listener.videoPlayStateDidChangedWithState(state, extInfo)
    }

    companion object {
        private const val PROGRESS_TICK_MS = 500L
        private const val SEEK_JUMP_MS = 10_000L
        private const val FULLSCREEN_BACKGROUND = 0xFF000000.toInt()
    }
}

private const val SLOCK_VIDEO_SEEK_TO_TIME_MS_PROP = "seekToTimeMs"

private fun Any.toSlockSeekTimeMsOrNull(): Long? =
    when (this) {
        is Number -> toLong()
        is String -> substringAfterLast(':').toLongOrNull()
        else -> null
    }

private fun Context.unwrapComponentActivity(): ComponentActivity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is ComponentActivity) return current
        current = current.baseContext
    }
    return null
}
