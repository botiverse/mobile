package build.raft.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
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
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import build.raft.app.compat.ui.PlayState
import build.raft.app.compat.ui.VideoPlayControl

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
    val lifecycleOwner = LocalLifecycleOwner.current
    key(source) {
        val viewHolder = remember { RaftAndroidVideoViewHolder() }
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
        AndroidView(
            factory = { context ->
                RaftAndroidVideoView(
                    context = context,
                    src = source,
                    callbacks =
                        object : RaftAndroidVideoView.Callbacks {
                            override fun onPlayState(state: PlayState) = stateCallback(state)

                            override fun onPlayTime(currentMs: Long, totalMs: Long) =
                                timeCallback(currentMs.toInt(), totalMs.toInt())

                            override fun onFirstFrame() = firstFrameCallback()
                        },
                ).also { viewHolder.view = it }
            },
            modifier = modifier,
            update = { view ->
                view.setMuted(muted)
                view.setRate(rate)
                view.applyPlayControl(playControl)
                if (seekRequestId != null) view.applySeekRequest(seekRequestId, seekTimeMs.toLong())
            },
            onRelease = { view ->
                if (viewHolder.view === view) viewHolder.view = null
                view.stop()
            },
        )
    }
}

private class RaftAndroidVideoViewHolder {
    var view: RaftAndroidVideoView? = null
}

/** The host lifecycle events that pause playback: the app went to the background. */
internal fun raftVideoPausesOnLifecycleEvent(event: Lifecycle.Event): Boolean = event == Lifecycle.Event.ON_STOP

/**
 * Media3 (ExoPlayer) backed video view, ported from the Kuikly-era `SlockKRVideoViewAdapter`
 * (task #556): one player covers video and audio-only sources; PlayerView's built-in controller
 * (play/pause, drag-seek, +-10s, fullscreen) is enabled — the shared minimal controls hide
 * themselves via raftPlatformUsesNativeVideoController(). Fullscreen reparents the PlayerView
 * onto the activity content view, rotates to landscape and hides the system bars; system back
 * exits fullscreen first.
 */
@SuppressLint("ViewConstructor")
@androidx.annotation.OptIn(UnstableApi::class)
internal class RaftAndroidVideoView(
    context: Context,
    src: String,
    private val callbacks: Callbacks,
) : FrameLayout(context) {
    interface Callbacks {
        fun onPlayState(state: PlayState)

        fun onPlayTime(currentMs: Long, totalMs: Long)

        fun onFirstFrame()
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var stopped = false
    private var lastReportedState: PlayState? = null
    private var appliedPlayControl: VideoPlayControl? = null
    private var appliedSeekRequestId: Int? = null
    private val progressContract = RaftVideoProgressContract()
    private val terminalGate = RaftVideoPlaybackAttemptGate(progressContract.currentGeneration)
    private var awaitingAttemptStart = false

    private val hostActivity: ComponentActivity? = context.unwrapRaftComponentActivity()
    private var fullscreenContainer: FrameLayout? = null
    private var savedOrientation: Int? = null
    private var fullscreenBackCallback: OnBackPressedCallback? = null
    private var releaseDeferred = false
    private val fullscreenDestroyObserver =
        LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) exitFullscreen()
        }

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

    init {
        setBackgroundColor(FULLSCREEN_BACKGROUND)
        player.addListener(
            object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    if (!stopped && !releaseDeferred) callbacks.onFirstFrame()
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_BUFFERING -> reportState(PlayState.BUFFERING)
                        Player.STATE_READY ->
                            reportState(if (player.playWhenReady) PlayState.PLAYING else PlayState.PAUSED)
                        // Terminal state is derived by the ticker from a duration-qualified active
                        // generation; ignoring native STATE_ENDED keeps an old queued event from
                        // ending a new replay/seek attempt.
                        Player.STATE_ENDED -> Unit
                        Player.STATE_IDLE -> Unit
                    }
                }

                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    if (player.playbackState == Player.STATE_READY) {
                        reportState(if (playWhenReady) PlayState.PLAYING else PlayState.PAUSED)
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    reportState(PlayState.ERROR)
                }
            }
        )
        player.prepare()
        scheduleProgressTicker()
    }

    fun applyPlayControl(control: VideoPlayControl) {
        if (control == appliedPlayControl) return
        appliedPlayControl = control
        when (control) {
            // prepare() already ran with playWhenReady=false: the first frame is decoded and
            // reported without starting playback.
            VideoPlayControl.PREPLAY -> Unit
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

    private fun play() {
        if (stopped) return
        if (player.playbackState == Player.STATE_ENDED) {
            val progress = progressContract.replay()
            terminalGate.begin(progress.generation)
            awaitingAttemptStart = true
            callbacks.onPlayTime(progress.currentMs, progress.durationMs)
            player.seekTo(0L)
            scheduleProgressTicker(progress.generation)
        }
        player.play()
    }

    private fun pause() {
        if (stopped) return
        player.pause()
    }

    /**
     * The host Activity was stopped (app in background): pause without changing the applied
     * play control, so returning to the app does not auto-resume; the PAUSED state is reported
     * through the player listener as usual.
     */
    fun pauseForHostBackground() {
        if (stopped || !player.playWhenReady) return
        player.pause()
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
        mainHandler.removeCallbacksAndMessages(null)
        playerView.player = null
        player.release()
    }

    fun setMuted(muted: Boolean) {
        if (stopped) return
        player.volume = if (muted) 0f else 1f
    }

    fun setRate(rate: Float) {
        if (stopped) return
        val target = rate.coerceAtLeast(0.25f)
        if (player.playbackParameters.speed != target) {
            player.playbackParameters = PlaybackParameters(target)
        }
    }

    private fun seekToTime(seekToTimeMs: Long) {
        if (stopped) return
        val progress = progressContract.seek(seekToTimeMs)
        terminalGate.begin(progress.generation)
        awaitingAttemptStart = true
        callbacks.onPlayTime(progress.currentMs, progress.durationMs)
        player.seekTo(progress.currentMs)
        scheduleProgressTicker(progress.generation)
    }

    private fun scheduleProgressTicker(generation: Long = progressContract.currentGeneration) {
        mainHandler.postDelayed({
            if (stopped || !terminalGate.acceptsProgress(generation)) return@postDelayed
            val duration = player.duration.takeIf { it > 0 } ?: 0L
            val progress = progressContract.progress(player.currentPosition, duration, generation)
            if (awaitingAttemptStart && player.isPlaying && !progress.terminal) {
                terminalGate.markStarted(progress.generation)
                awaitingAttemptStart = false
            }
            if (!progress.terminal && duration > 0L && progress.currentMs >= duration) {
                terminalGate.markTerminalEligible(progress.generation)
                if (terminalGate.acceptsTerminal(progress.generation)) {
                    val terminal = progressContract.ended(duration, progress.generation)
                    callbacks.onPlayTime(terminal.currentMs, terminal.durationMs)
                    reportState(PlayState.PLAY_END)
                    scheduleProgressTicker(generation)
                    return@postDelayed
                }
            }
            callbacks.onPlayTime(progress.currentMs, progress.durationMs)
            scheduleProgressTicker(generation)
        }, PROGRESS_TICK_MS)
    }

    private fun enterFullscreen() {
        if (stopped || fullscreenContainer != null) return
        val activity = hostActivity ?: return
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        removeView(playerView)
        val container =
            FrameLayout(context).apply {
                setBackgroundColor(FULLSCREEN_BACKGROUND)
                addView(playerView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
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
        // A deferred release must not outlive the Activity.
        activity.lifecycle.addObserver(fullscreenDestroyObserver)
        playerView.setFullscreenButtonState(true)
    }

    private fun exitFullscreen() {
        val container = fullscreenContainer ?: return
        fullscreenContainer = null
        fullscreenBackCallback?.remove()
        fullscreenBackCallback = null
        hostActivity?.lifecycle?.removeObserver(fullscreenDestroyObserver)
        container.removeView(playerView)
        (container.parent as? ViewGroup)?.removeView(container)
        // The inline slot is gone (stop() deferred): nothing to return to.
        if (!releaseDeferred) addView(playerView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        hostActivity?.let { activity ->
            activity.requestedOrientation = savedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            WindowInsetsControllerCompat(activity.window, activity.window.decorView)
                .show(WindowInsetsCompat.Type.systemBars())
        }
        savedOrientation = null
        playerView.setFullscreenButtonState(false)
        if (releaseDeferred) release()
    }

    private fun reportState(state: PlayState) {
        if (stopped || releaseDeferred) return
        if (state == lastReportedState) return
        lastReportedState = state
        callbacks.onPlayState(state)
    }

    private companion object {
        const val PROGRESS_TICK_MS = 500L
        const val SEEK_JUMP_MS = 10_000L
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
