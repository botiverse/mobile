package build.raft.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import build.raft.app.compat.ui.PlayState
import build.raft.app.compat.ui.VideoPlayControl
import kotlin.math.roundToLong

/**
 * What [RaftDesktopVideoController] needs from a media player. The app adapts ComposeMediaPlayer's
 * `VideoPlayerState` to it (RaftVideoPlayer.desktop.kt); tests use a fake, since the native player
 * only runs on a Mac.
 */
interface RaftDesktopVideoEngine {
    val hasMedia: Boolean
    val isPlaying: Boolean
    val isLoading: Boolean
    val failed: Boolean

    /** The media opened but has no video track (audio-only): the player cannot show it. */
    val failedWithoutVideo: Boolean
    val positionSeconds: Double
    val durationSeconds: Double
    var volume: Float
    var speed: Float

    fun open(uri: String, autoplay: Boolean)

    fun play()

    fun pause()

    /** [fraction] of the duration, 0..1. */
    fun seekToFraction(fraction: Float)

    fun stop()

    fun clearError()

    fun release()
}

/** One poll of the player, in the units the shared pages use (RaftVideoPlayer's callbacks). */
data class RaftDesktopVideoSnapshot(
    val state: PlayState,
    val currentMs: Int,
    val totalMs: Int,
    val firstFrame: Boolean,
)

/**
 * The desktop video host's logic, kept apart from the native player and from Compose UI so it can
 * be tested headless. Mirrors the iOS controller (RaftVideoPlayer.ios.kt):
 * - the shared [VideoPlayControl] is applied only when it changes; `PREPLAY` opens the media paused
 *   (first frame, no playback), `STOP` stops and drops the media;
 * - a seek request is applied once per distinct id;
 * - the on-video controls ([togglePlay], [seekToFraction], [toggleMuted], [changeVolume]) act on the
 *   player directly, like Media3's / AVKit's own controllers on mobile.
 */
class RaftDesktopVideoController(
    private val source: String,
    private val engine: RaftDesktopVideoEngine,
) {
    private var opened = false
    private var released = false
    private var appliedPlayControl: VideoPlayControl? = null
    private var appliedSeekRequestId: Int? = null
    private var hostMuted = false
    private var firstFrameReported = false
    private var playRequested = false

    /** Set from the player's end callback (any thread); cleared by play and seek. */
    @Volatile
    private var endedFlag = false

    /** The user's volume (0..1), kept while muted so unmuting restores it. */
    var volume by mutableStateOf(1f)
        private set

    var userMuted by mutableStateOf(false)
        private set

    /** Muted by the user, by the page, or by a zero volume: what the mute button shows. */
    val muted: Boolean get() = userMuted || hostMuted || volume <= 0f

    var ended by mutableStateOf(false)
        private set

    val isPlaying: Boolean get() = !released && engine.isPlaying

    val failed: Boolean get() = !released && engine.failed

    /** The source turned out to have no video track: the host shows its "not available" notice. */
    val noVideoTrack: Boolean get() = !released && engine.failed && engine.failedWithoutVideo

    val positionMs: Int get() = raftDesktopVideoMillis(if (ended) engine.durationSeconds else engine.positionSeconds)

    val durationMs: Int get() = raftDesktopVideoMillis(engine.durationSeconds)

    /** Playback fraction 0..1 for the seek bar; 0 while the duration is unknown. */
    val progress: Float
        get() {
            val total = durationMs
            return if (total <= 0) 0f else (positionMs.toFloat() / total).coerceIn(0f, 1f)
        }

    fun applyPlayControl(control: VideoPlayControl) {
        if (released || control == appliedPlayControl) return
        appliedPlayControl = control
        when (control) {
            VideoPlayControl.PREPLAY -> openIfNeeded(autoplay = false)
            VideoPlayControl.PLAY -> play()
            VideoPlayControl.PAUSE -> pause()
            VideoPlayControl.STOP -> stop()
        }
    }

    fun applySeekRequest(id: Int, timeMs: Int) {
        if (released || id == appliedSeekRequestId) return
        appliedSeekRequestId = id
        seekToMs(timeMs)
    }

    fun setHostMuted(value: Boolean) {
        hostMuted = value
        applyVolume()
    }

    fun setRate(rate: Float) {
        if (released) return
        val clamped = rate.coerceIn(0.25f, 2f)
        if (engine.speed != clamped) engine.speed = clamped
    }

    fun togglePlay() {
        if (released) return
        when {
            engine.failed -> retry()
            engine.isPlaying -> pause()
            else -> play()
        }
    }

    fun seekToFraction(fraction: Float) {
        if (released || engine.durationSeconds <= 0.0) return
        markEnded(false)
        engine.seekToFraction(fraction.coerceIn(0f, 1f))
    }

    fun seekToMs(timeMs: Int) {
        val total = durationMs
        if (total <= 0) return
        seekToFraction(timeMs.toFloat() / total)
    }

    fun toggleMuted() {
        if (released) return
        if (muted) {
            userMuted = false
            if (volume <= 0f) volume = 1f
        } else {
            userMuted = true
        }
        applyVolume()
    }

    fun changeVolume(value: Float) {
        if (released) return
        volume = value.coerceIn(0f, 1f)
        userMuted = false
        applyVolume()
    }

    /** The player reached the end (called from the player's thread). */
    fun onEnded() {
        markEnded(true)
    }

    fun snapshot(): RaftDesktopVideoSnapshot {
        val state =
            when {
                released -> PlayState.NONE
                engine.failed -> PlayState.ERROR
                endedFlag -> PlayState.PLAY_END
                engine.isPlaying && engine.isLoading -> PlayState.BUFFERING
                engine.isPlaying -> PlayState.PLAYING
                engine.hasMedia -> PlayState.PAUSED
                playRequested && opened -> PlayState.BUFFERING
                else -> PlayState.NONE
            }
        if (ended != endedFlag) ended = endedFlag
        val firstFrame = !released && engine.hasMedia && !engine.failed
        if (firstFrame) firstFrameReported = true
        return RaftDesktopVideoSnapshot(
            state = state,
            currentMs = positionMs,
            totalMs = durationMs,
            firstFrame = firstFrameReported,
        )
    }

    fun release() {
        if (released) return
        released = true
        engine.release()
    }

    private fun openIfNeeded(autoplay: Boolean) {
        if (opened || source.isBlank()) return
        opened = true
        engine.open(source, autoplay)
        applyVolume()
    }

    private fun play() {
        if (released) return
        playRequested = true
        if (!opened) {
            openIfNeeded(autoplay = true)
            return
        }
        if (endedFlag) {
            markEnded(false)
            engine.seekToFraction(0f)
        }
        engine.play()
    }

    private fun pause() {
        if (released) return
        playRequested = false
        if (opened) engine.pause()
    }

    private fun stop() {
        playRequested = false
        markEnded(false)
        if (!opened) return
        opened = false
        engine.stop()
    }

    private fun retry() {
        engine.clearError()
        markEnded(false)
        opened = false
        engine.stop()
        playRequested = true
        openIfNeeded(autoplay = true)
    }

    private fun applyVolume() {
        if (released) return
        engine.volume = if (userMuted || hostMuted) 0f else volume
    }

    private fun markEnded(value: Boolean) {
        endedFlag = value
    }
}

/** Seconds from the player to whole milliseconds; NaN, infinite and negative read as 0. */
fun raftDesktopVideoMillis(seconds: Double): Int =
    if (seconds.isNaN() || seconds.isInfinite() || seconds <= 0.0) {
        0
    } else {
        (seconds * 1000.0).roundToLong().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

/** A player clock: `m:ss`, or `h:mm:ss` from an hour on (the same form for the current time and the total). */
fun raftDesktopVideoTimeLabel(millis: Int, totalMillis: Int = millis): String {
    val seconds = millis.coerceAtLeast(0) / 1000
    val withHours = totalMillis.coerceAtLeast(millis) >= 3_600_000
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (withHours) {
        "$h:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
    } else {
        "${seconds / 60}:${s.toString().padStart(2, '0')}"
    }
}

/** A key on a focused video; Escape is left to the page (it closes the preview). */
enum class RaftDesktopVideoKeyAction { TogglePlay }

fun raftDesktopVideoKeyAction(key: Key): RaftDesktopVideoKeyAction? =
    when (key) {
        Key.Spacebar -> RaftDesktopVideoKeyAction.TogglePlay
        else -> null
    }
