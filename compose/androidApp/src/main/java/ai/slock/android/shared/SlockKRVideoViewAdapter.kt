package ai.slock.android.shared

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View.MeasureSpec
import android.widget.FrameLayout
import android.widget.VideoView
import com.tencent.kuikly.core.render.android.adapter.IKRVideoView
import com.tencent.kuikly.core.render.android.adapter.IKRVideoViewAdapter
import com.tencent.kuikly.core.render.android.adapter.IKRVideoViewListener
import com.tencent.kuikly.core.render.android.expand.component.KRVideoPlayState
import com.tencent.kuikly.core.render.android.expand.component.KRVideoViewContentMode
import org.json.JSONObject
import kotlin.math.roundToInt

internal object SlockKRVideoViewAdapter : IKRVideoViewAdapter {
    override fun createVideoView(
        context: Context,
        src: String,
        listener: IKRVideoViewListener
    ): IKRVideoView =
        SlockKRVideoView(context = context, source = src, listener = listener)
}

private class SlockKRVideoView(
    context: Context,
    source: String,
    private val listener: IKRVideoViewListener
) : FrameLayout(context), IKRVideoView {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val videoView = ResizablePlatformVideoView(context)
    private var source: String = source
    private var loadedSource: String? = null
    private var mediaPlayer: MediaPlayer? = null
    private var prepared = false
    private var pendingPlay = false
    private var pendingPreplay = false
    private var muted = false
    private var playbackRate = 1f
    private var firstFrameSent = false
    private var timeUpdatesRunning = false
    private val timeUpdateRunnable =
        object : Runnable {
            override fun run() {
                if (!timeUpdatesRunning) return
                listener.playTimeDidChangedWithCurrentTime(
                    videoView.currentPosition.coerceAtLeast(0).toLong(),
                    videoView.duration.coerceAtLeast(0).toLong()
                )
                mainHandler.postDelayed(this, PLAY_TIME_UPDATE_INTERVAL_MS)
            }
        }

    init {
        clipChildren = true
        addView(
            videoView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER)
        )
        installVideoCallbacks()
        loadSourceIfNeeded()
    }

    override fun preplay() {
        pendingPreplay = true
        pendingPlay = false
        loadSourceIfNeeded()
        if (prepared) {
            videoView.seekTo(FIRST_FRAME_POSITION_MS)
        }
    }

    override fun play() {
        pendingPreplay = false
        pendingPlay = true
        loadSourceIfNeeded()
        runCatching {
            videoView.start()
            if (prepared) {
                notifyState(KRVideoPlayState.KRVideoPlayStatePlaying)
                startTimeUpdates()
            } else {
                notifyState(KRVideoPlayState.KRVideoPlayStateCaching)
            }
        }.onFailure { throwable ->
            notifyPlaybackError("play_failed", throwable)
        }
    }

    override fun pause() {
        pendingPlay = false
        if (videoView.isPlaying) {
            videoView.pause()
        }
        stopTimeUpdates()
        notifyState(KRVideoPlayState.KRVideoPlayStatePaused)
    }

    override fun stop() {
        pendingPlay = false
        pendingPreplay = false
        stopTimeUpdates()
        runCatching { videoView.stopPlayback() }
        prepared = false
        loadedSource = null
        mediaPlayer = null
        firstFrameSent = false
        notifyState(KRVideoPlayState.KRVideoPlayStatePaused)
    }

    override fun setVideoContentMode(videoViewContentMode: KRVideoViewContentMode) {
        videoView.contentMode = videoViewContentMode
    }

    override fun setMuted(muted: Boolean) {
        this.muted = muted
        applyMuted()
    }

    override fun setRate(rate: Float) {
        playbackRate = rate.takeIf { it > 0f } ?: 1f
        applyPlaybackRate()
    }

    override fun seekToTime(seekToTimeMs: Long) {
        val target = seekToTimeMs.coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        runCatching { videoView.seekTo(target) }
    }

    override fun setProp(propKey: String, propValue: Any): Boolean =
        when (propKey) {
            PROP_SRC -> {
                setSource(propValue.toString())
                true
            }
            else -> false
        }

    override fun call(method: String, params: String?) {
        when (method) {
            METHOD_PLAY -> play()
            METHOD_PAUSE -> pause()
            METHOD_STOP -> stop()
            METHOD_PREPLAY -> preplay()
            METHOD_SEEK_TO_TIME, METHOD_SEEK_TO ->
                seekToTime(params.toSeekMillis())
        }
    }

    override fun onDetachedFromWindow() {
        stopTimeUpdates()
        runCatching { videoView.stopPlayback() }
        mediaPlayer = null
        super.onDetachedFromWindow()
    }

    private fun installVideoCallbacks() {
        videoView.setOnPreparedListener { player ->
            mediaPlayer = player
            prepared = true
            videoView.videoWidth = player.videoWidth
            videoView.videoHeight = player.videoHeight
            applyMuted()
            applyPlaybackRate()
            if (pendingPreplay) {
                videoView.seekTo(FIRST_FRAME_POSITION_MS)
                notifyState(KRVideoPlayState.KRVideoPlayStatePaused)
            }
            if (pendingPlay) {
                videoView.start()
                notifyState(KRVideoPlayState.KRVideoPlayStatePlaying)
                startTimeUpdates()
            }
        }
        videoView.setOnCompletionListener {
            pendingPlay = false
            stopTimeUpdates()
            notifyState(KRVideoPlayState.KRVideoPlayStatePlayEnd)
        }
        videoView.setOnErrorListener { _, what, extra ->
            pendingPlay = false
            stopTimeUpdates()
            prepared = false
            loadedSource = null
            notifyState(
                KRVideoPlayState.KRVideoPlayStateFaild,
                mapOf("what" to what.toString(), "extra" to extra.toString())
            )
            true
        }
        videoView.setOnInfoListener { _, what, extra ->
            when (what) {
                MediaPlayer.MEDIA_INFO_BUFFERING_START ->
                    notifyState(KRVideoPlayState.KRVideoPlayStateCaching)
                MediaPlayer.MEDIA_INFO_BUFFERING_END ->
                    if (videoView.isPlaying) notifyState(KRVideoPlayState.KRVideoPlayStatePlaying)
                MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START ->
                    notifyFirstFrame()
            }
            listener.customEventWithInfo(mapOf("what" to what.toString(), "extra" to extra.toString()))
            false
        }
    }

    private fun setSource(next: String) {
        if (source == next) return
        source = next
        prepared = false
        mediaPlayer = null
        firstFrameSent = false
        loadedSource = null
        stopTimeUpdates()
        loadSourceIfNeeded()
    }

    private fun loadSourceIfNeeded() {
        if (source.isBlank()) return
        if (loadedSource == source) return
        runCatching {
            notifyState(KRVideoPlayState.KRVideoPlayStateCaching)
            videoView.setVideoURI(Uri.parse(source))
            loadedSource = source
        }.onFailure { throwable ->
            loadedSource = null
            notifyPlaybackError("source_failed", throwable)
        }
    }

    private fun applyMuted() {
        val volume = if (muted) 0f else 1f
        runCatching { mediaPlayer?.setVolume(volume, volume) }
    }

    private fun applyPlaybackRate() {
        val player = mediaPlayer ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        runCatching {
            player.playbackParams = player.playbackParams.setSpeed(playbackRate)
        }.onFailure { throwable ->
            Log.w(LOG_TAG, "Unable to apply playback rate=$playbackRate", throwable)
        }
    }

    private fun startTimeUpdates() {
        if (timeUpdatesRunning) return
        timeUpdatesRunning = true
        mainHandler.post(timeUpdateRunnable)
    }

    private fun stopTimeUpdates() {
        timeUpdatesRunning = false
        mainHandler.removeCallbacks(timeUpdateRunnable)
    }

    private fun notifyFirstFrame() {
        if (firstFrameSent) return
        firstFrameSent = true
        listener.videoFirstFrameDidDisplay()
    }

    private fun notifyState(
        state: KRVideoPlayState,
        extInfo: Map<String, String> = emptyMap()
    ) {
        listener.videoPlayStateDidChangedWithState(state, extInfo)
    }

    private fun notifyPlaybackError(code: String, throwable: Throwable) {
        Log.w(LOG_TAG, "Video playback error code=$code source=$source", throwable)
        notifyState(
            KRVideoPlayState.KRVideoPlayStateFaild,
            mapOf("code" to code, "message" to throwable.message.orEmpty())
        )
    }

    private fun String?.toSeekMillis(): Long {
        val raw = this?.trim().orEmpty()
        if (raw.isBlank()) return 0L
        raw.toLongOrNull()?.let { return it }
        return runCatching {
            val json = JSONObject(raw)
            json.optLong("time", json.optLong("timeMillis", 0L))
        }.getOrDefault(0L)
    }

    companion object {
        private const val LOG_TAG = "SlockKRVideoView"
        private const val PROP_SRC = "src"
        private const val METHOD_PLAY = "play"
        private const val METHOD_PAUSE = "pause"
        private const val METHOD_STOP = "stop"
        private const val METHOD_PREPLAY = "preplay"
        private const val METHOD_SEEK_TO = "seekTo"
        private const val METHOD_SEEK_TO_TIME = "seekToTime"
        private const val FIRST_FRAME_POSITION_MS = 1
        private const val PLAY_TIME_UPDATE_INTERVAL_MS = 500L
    }
}

private class ResizablePlatformVideoView(context: Context) : VideoView(context) {
    var contentMode: KRVideoViewContentMode = KRVideoViewContentMode.KRVideoViewContentModeScaleAspectFit
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }
    var videoWidth: Int = 0
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }
    var videoHeight: Int = 0
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = MeasureSpec.getSize(widthMeasureSpec)
        val availableHeight = MeasureSpec.getSize(heightMeasureSpec)
        if (availableWidth <= 0 || availableHeight <= 0 || videoWidth <= 0 || videoHeight <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        if (contentMode == KRVideoViewContentMode.KRVideoViewContentModeScaleToFill) {
            setMeasuredDimension(availableWidth, availableHeight)
            return
        }
        val widthScale = availableWidth.toFloat() / videoWidth.toFloat()
        val heightScale = availableHeight.toFloat() / videoHeight.toFloat()
        val scale =
            if (contentMode == KRVideoViewContentMode.KRVideoViewContentModeScaleAspectFill) {
                maxOf(widthScale, heightScale)
            } else {
                minOf(widthScale, heightScale)
            }
        setMeasuredDimension(
            (videoWidth * scale).roundToInt().coerceAtLeast(1),
            (videoHeight * scale).roundToInt().coerceAtLeast(1)
        )
    }
}
