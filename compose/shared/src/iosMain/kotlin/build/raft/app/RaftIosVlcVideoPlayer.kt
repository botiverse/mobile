package build.raft.app

import platform.UIKit.UIView

/**
 * One MobileVLCKit player, implemented by the iOS app (Swift, `RaftIosVlcVideoPlayer.swift`, on
 * top of `RaftIosVlcEngine.m`). It plays the video containers AVPlayer cannot demux (WebM, MKV,
 * MPEG-TS/PS, FLV; [RaftVideoPlaybackEngine.Vlc]) and draws its own controls: play/pause, a seek
 * bar with the time, and full screen. [RaftVideoPlayer] hosts [playerView] and drives it like the
 * AVPlayer controller. Main thread only.
 */
interface RaftIosVlcVideoPlayer {
    /** The video and its controls; [RaftVideoPlayer] hosts it through UIKitView. */
    fun playerView(): UIView

    /** Where the player reports; null stops the reports. */
    fun listen(events: RaftIosVlcVideoEvents?)

    /**
     * Opens the source and probes its container without playing (the AVPlayer path's PREPLAY):
     * [RaftIosVlcVideoEvents.onReady] when VLC can open it, [RaftIosVlcVideoEvents.onFailed] when it
     * cannot.
     */
    fun prepare()

    fun play()

    fun pause()

    fun seekTo(timeMs: Long)

    fun applyMuted(muted: Boolean)

    fun applyRate(rate: Float)

    /** Stops playback and leaves full screen; the player is not used again. */
    fun dispose()
}

/** What a [RaftIosVlcVideoPlayer] reports, on the main thread. */
interface RaftIosVlcVideoEvents {
    /** VLC opened the source: the player can play (the AVPlayer path's first frame). */
    fun onReady(durationMs: Long)

    fun onPlaying()

    fun onPaused()

    fun onBuffering()

    fun onEnded()

    /** VLC could not open or play the source. */
    fun onFailed()

    fun onTime(currentMs: Long, durationMs: Long)

    /** Full-screen presentation entered or exited so the inline host can defer disposal across rotation. */
    fun onFullscreenChanged(fullscreen: Boolean)
}

/** Implemented by the iOS app: makes a [RaftIosVlcVideoPlayer] for a signed attachment link. */
interface RaftIosVlcVideoPlayerFactory {
    fun createPlayer(url: String, labels: RaftIosVlcVideoLabels): RaftIosVlcVideoPlayer
}

/** The player controls' accessibility labels, in the app language. */
class RaftIosVlcVideoLabels(
    val playLabel: String,
    val pauseLabel: String,
    val fullScreenLabel: String,
    val exitFullScreenLabel: String,
    val playbackFailedLabel: String,
)

/**
 * The app's VLC player, registered at launch (`RaftIosVlcVideo.install()` in `iOSApp.swift`)
 * before the first page. Null (tests, or a host without MobileVLCKit): no VLC, so WebM / MKV / ...
 * keep the file page's download card ([raftPlatformVlcVideoEngineAvailable]).
 */
object RaftIosVlcVideoHost {
    var factory: RaftIosVlcVideoPlayerFactory? = null
}
