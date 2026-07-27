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
                reportProgress(player.currentPosition, player.duration)
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
                        Player.STATE_BUFFERING -> {
                            val progress = slockVideoProgressSnapshot(player.currentPosition, player.duration)
                            if (!shouldSuppressTerminalClampState(lastReportedState, player.playWhenReady, progress)) {
                                reportState(KRVideoPlayState.KRVideoPlayStateCaching)
                            }
                        }
                        Player.STATE_READY -> {
                            val terminal = slockVideoProgressSnapshot(player.currentPosition, player.duration)
                            val terminalClampSettled =
                                shouldSuppressTerminalClampState(
                                    lastReportedState = lastReportedState,
                                    playWhenReady = player.playWhenReady,
                                    progress = terminal
                                )
                            if (!terminalClampSettled) {
                                reportState(
                                    if (player.playWhenReady) {
                                        KRVideoPlayState.KRVideoPlayStatePlaying
                                    } else {
                                        KRVideoPlayState.KRVideoPlayStatePaused
                                    }
                                )
                            }
                        }
                        Player.STATE_ENDED -> {
                            val terminal = slockVideoProgressSnapshot(player.currentPosition, player.duration)
                            reportProgress(terminal.positionMs, terminal.durationMs)
                            reportState(KRVideoPlayState.KRVideoPlayStatePlayEnd)
                            if (terminal.durationMs > 0L && player.currentPosition != terminal.durationMs) {
                                player.seekTo(terminal.durationMs)
                            }
                        }
                        Player.STATE_IDLE -> Unit
                    }
                }

                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    if (player.playbackState == Player.STATE_READY) {
                        val progress = slockVideoProgressSnapshot(player.currentPosition, player.duration)
                        if (!shouldSuppressTerminalClampState(lastReportedState, playWhenReady, progress)) {
                            reportState(
                                if (playWhenReady) {
                                    KRVideoPlayState.KRVideoPlayStatePlaying
                                } else {
                                    KRVideoPlayState.KRVideoPlayStatePaused
                                }
                            )
                        }
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
            player.seekTo(0L)
            reportProgress(0L, player.duration)
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
        val target = slockVideoProgressSnapshot(seekToTimeMs, player.duration)
        player.seekTo(target.positionMs)
        reportProgress(target.positionMs, target.durationMs)
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

    private fun reportProgress(positionMs: Long, durationMs: Long) {
        val snapshot = slockVideoProgressSnapshot(positionMs, durationMs)
        listener.playTimeDidChangedWithCurrentTime(snapshot.positionMs, snapshot.durationMs)
    }

    companion object {
        private const val PROGRESS_TICK_MS = 500L
        private const val SEEK_JUMP_MS = 10_000L
        private const val FULLSCREEN_BACKGROUND = 0xFF000000.toInt()
    }
}

internal data class SlockVideoProgressSnapshot(
    val positionMs: Long,
    val durationMs: Long
)

internal fun slockVideoProgressSnapshot(positionMs: Long, durationMs: Long): SlockVideoProgressSnapshot {
    val normalizedDuration = durationMs.coerceAtLeast(0L)
    val normalizedPosition =
        if (normalizedDuration > 0L) {
            positionMs.coerceIn(0L, normalizedDuration)
        } else {
            positionMs.coerceAtLeast(0L)
        }
    return SlockVideoProgressSnapshot(
        positionMs = normalizedPosition,
        durationMs = normalizedDuration
    )
}

internal fun shouldSuppressTerminalClampState(
    lastReportedState: KRVideoPlayState?,
    playWhenReady: Boolean,
    progress: SlockVideoProgressSnapshot
): Boolean =
    lastReportedState == KRVideoPlayState.KRVideoPlayStatePlayEnd &&
        !playWhenReady &&
        progress.durationMs > 0L &&
        progress.positionMs == progress.durationMs

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
