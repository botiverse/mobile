package ai.slock.android.shared

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.VideoView
import com.tencent.kuikly.core.render.android.adapter.IKRVideoView
import com.tencent.kuikly.core.render.android.adapter.IKRVideoViewAdapter
import com.tencent.kuikly.core.render.android.adapter.IKRVideoViewListener
import com.tencent.kuikly.core.render.android.expand.component.KRVideoPlayState
import com.tencent.kuikly.core.render.android.expand.component.KRVideoViewContentMode

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
    private val videoView =
        VideoView(context).also { view ->
            view.layoutParams =
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER)
            addView(view)
        }
    private var mediaPlayer: MediaPlayer? = null
    private var prepared = false
    private var shouldPlayWhenPrepared = false
    private var muted = false
    private var rate = 1f
    private var stopped = false
    private val timeTicker =
        object : Runnable {
            override fun run() {
                if (stopped) return
                if (prepared) {
                    listener.playTimeDidChangedWithCurrentTime(
                        videoView.currentPosition.toLong(),
                        videoView.duration.takeIf { it > 0 }?.toLong() ?: 0L
                    )
                }
                mainHandler.postDelayed(this, 500L)
            }
        }

    init {
        videoView.setOnPreparedListener { player ->
            mediaPlayer = player
            prepared = true
            applyMute(player)
            applyRate(player)
            listener.videoFirstFrameDidDisplay()
            listener.videoPlayStateDidChangedWithState(KRVideoPlayState.KRVideoPlayStatePaused, emptyMap())
            if (shouldPlayWhenPrepared) {
                play()
            }
        }
        videoView.setOnCompletionListener {
            listener.videoPlayStateDidChangedWithState(KRVideoPlayState.KRVideoPlayStatePlayEnd, emptyMap())
        }
        videoView.setOnErrorListener { _, what, extra ->
            listener.videoPlayStateDidChangedWithState(
                KRVideoPlayState.KRVideoPlayStateFaild,
                mapOf("what" to what.toString(), "extra" to extra.toString())
            )
            true
        }
        videoView.setOnInfoListener { _, what, extra ->
            when (what) {
                MediaPlayer.MEDIA_INFO_BUFFERING_START ->
                    listener.videoPlayStateDidChangedWithState(KRVideoPlayState.KRVideoPlayStateCaching, emptyMap())
                MediaPlayer.MEDIA_INFO_BUFFERING_END ->
                    if (videoView.isPlaying) {
                        listener.videoPlayStateDidChangedWithState(KRVideoPlayState.KRVideoPlayStatePlaying, emptyMap())
                    }
            }
            false
        }
        videoView.setVideoURI(Uri.parse(src))
        mainHandler.post(timeTicker)
    }

    override fun preplay() {
        if (prepared) {
            videoView.seekTo(1)
        }
    }

    override fun play() {
        shouldPlayWhenPrepared = true
        if (!prepared || stopped) return
        videoView.start()
        listener.videoPlayStateDidChangedWithState(KRVideoPlayState.KRVideoPlayStatePlaying, emptyMap())
    }

    override fun pause() {
        shouldPlayWhenPrepared = false
        if (!prepared || stopped) return
        videoView.pause()
        listener.videoPlayStateDidChangedWithState(KRVideoPlayState.KRVideoPlayStatePaused, emptyMap())
    }

    override fun stop() {
        stopped = true
        shouldPlayWhenPrepared = false
        mainHandler.removeCallbacks(timeTicker)
        runCatching { videoView.stopPlayback() }
        prepared = false
        mediaPlayer = null
    }

    override fun setVideoContentMode(videoViewContentMode: KRVideoViewContentMode) {
        // Android platform VideoView does not expose a stable crop/contain API.
        // The shared UI frames the view in a black 16:9 surface, so default fit is acceptable for phase 1.
    }

    override fun setMuted(muted: Boolean) {
        this.muted = muted
        mediaPlayer?.let(::applyMute)
    }

    override fun setRate(rate: Float) {
        this.rate = rate.coerceAtLeast(0.25f)
        mediaPlayer?.let(::applyRate)
    }

    override fun seekToTime(seekToTimeMs: Long) {
        if (!prepared || stopped) return
        videoView.seekTo(seekToTimeMs.coerceAtLeast(0L).toInt())
    }

    override fun setProp(propKey: String, propValue: Any): Boolean = false

    override fun call(method: String, params: String?) = Unit

    private fun applyMute(player: MediaPlayer) {
        val volume = if (muted) 0f else 1f
        player.setVolume(volume, volume)
    }

    private fun applyRate(player: MediaPlayer) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        runCatching {
            player.playbackParams = player.playbackParams.setSpeed(rate)
        }
    }
}
