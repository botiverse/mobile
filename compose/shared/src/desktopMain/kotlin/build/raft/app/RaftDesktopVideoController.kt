package build.raft.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import build.raft.app.compat.ui.PlayState
import build.raft.app.compat.ui.VideoPlayControl
import build.raft.app.thread.key
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
    /** A picture of the video is on screen: the pages drop their spinner. */
    val firstFrame: Boolean,
)

/**
 * The desktop video host's logic, kept apart from the native player and from Compose UI so it can
 * be tested headless. Mirrors the iOS controller (RaftVideoPlayer.ios.kt):
 * - the shared [VideoPlayControl] is applied only when it changes; `PREPLAY` opens the media paused
 *   and puts its first frame on screen (no playback, no sound), `STOP` stops and drops the media;
 * - a seek request is applied once per distinct id;
 * - the on-video controls ([togglePlay], [seekToFraction], [toggleMuted], [changeVolume]) act on the
 *   player directly, like Media3's / AVKit's own controllers on mobile.
 *
 * The first frame of a `PREPLAY` open takes a warm-up. ComposeMediaPlayer's macOS backend (0.11.4)
 * moves frames to the screen only while it plays: for a media opened paused, its native first-frame
 * capture runs before AVFoundation has decoded anything, so the video stayed a black box until it
 * was played. Once the media has loaded, [poll] silences the player, plays it until its clock has
 * moved [RAFT_DESKTOP_VIDEO_WARM_UP_SECONDS] (frames are on screen by then), pauses it, takes it
 * back to the start and gives the volume back. The pages never see it: the state stays paused at
 * 0:00, and [RaftDesktopVideoSnapshot.firstFrame] turns true once it is done. Play during the
 * warm-up plays from the start with sound; a pause leaves it to end paused; a seek lands when it
 * ends. Re-check when bumping the library: once a paused open draws its first frame, drop it.
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

    /** Where the first-frame warm-up stands (state: the on-video controls read it through [isPlaying]). */
    private var warmUp by mutableStateOf(RaftDesktopVideoWarmUp.Off)

    /**
     * Where the video is shown to be (0..1 of the duration) during the warm-up and, after it, until
     * the next play or seek: the start, or a seek asked for meanwhile. The player's own clock moved
     * during the warm-up, and after its paused rewind it reads the old time for a while.
     */
    private var pinnedFraction by mutableStateOf<Float?>(null)

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

    /** Playing as the user sees it: the warm-up's silent playback does not count. */
    val isPlaying: Boolean get() = !released && warmUp == RaftDesktopVideoWarmUp.Off && engine.isPlaying

    /** A `PREPLAY` open is still putting its first frame on screen: the host polls faster meanwhile. */
    val preparingFirstFrame: Boolean get() = !released && warmUp != RaftDesktopVideoWarmUp.Off

    val failed: Boolean get() = !released && engine.failed

    /** The source turned out to have no video track: the host shows its "not available" notice. */
    val noVideoTrack: Boolean get() = !released && engine.failed && engine.failedWithoutVideo

    val positionMs: Int
        get() {
            val pinned = pinnedFraction
            return raftDesktopVideoMillis(
                when {
                    pinned != null -> pinned * engine.durationSeconds
                    ended -> engine.durationSeconds
                    else -> engine.positionSeconds
                }
            )
        }

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
            isPlaying -> pause()
            else -> play()
        }
    }

    fun seekToFraction(fraction: Float) {
        if (released || engine.durationSeconds <= 0.0) return
        val clamped = fraction.coerceIn(0f, 1f)
        if (warmUp != RaftDesktopVideoWarmUp.Off) {
            // The warm-up ends there instead of at the start.
            pinnedFraction = clamped
            // While it plays silently, the player gets there when it ends.
            if (warmUp == RaftDesktopVideoWarmUp.Playing || warmUp == RaftDesktopVideoWarmUp.Pausing) return
        } else {
            pinnedFraction = null
        }
        markEnded(false)
        engine.seekToFraction(clamped)
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

    /** One tick of the host: moves the first-frame warm-up along, then reads the player. */
    fun poll(): RaftDesktopVideoSnapshot {
        advanceWarmUp()
        return snapshot()
    }

    fun snapshot(): RaftDesktopVideoSnapshot {
        val warmingUp = warmUp != RaftDesktopVideoWarmUp.Off
        val state =
            when {
                released -> PlayState.NONE
                engine.failed -> PlayState.ERROR
                // The warm-up is not playback: the pages see the media paused at its start.
                warmingUp -> if (engine.hasMedia) PlayState.PAUSED else PlayState.NONE
                endedFlag -> PlayState.PLAY_END
                engine.isPlaying && engine.isLoading -> PlayState.BUFFERING
                engine.isPlaying -> PlayState.PLAYING
                engine.hasMedia -> PlayState.PAUSED
                playRequested && opened -> PlayState.BUFFERING
                else -> PlayState.NONE
            }
        if (!warmingUp && ended != endedFlag) ended = endedFlag
        // Frames reach the screen while the player plays, and the last one stays when it stops at
        // the end. A media opened paused gets its frame from the warm-up (finishWarmUp).
        if (!released && !warmingUp && !engine.failed && engine.hasMedia &&
            (endedFlag || (engine.isPlaying && engine.positionSeconds > 0.0))
        ) {
            firstFrameReported = true
        }
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
        // Opened paused (PREPLAY): the warm-up puts the first frame up once the media has loaded.
        warmUp = if (autoplay) RaftDesktopVideoWarmUp.Off else RaftDesktopVideoWarmUp.Opening
        pinnedFraction = if (autoplay) null else 0f
        engine.open(source, autoplay)
        applyVolume()
    }

    /** One step of the warm-up per poll, so each step has reached the player before the next. */
    private fun advanceWarmUp() {
        if (released || engine.failed) return
        when (warmUp) {
            RaftDesktopVideoWarmUp.Off -> Unit
            RaftDesktopVideoWarmUp.Opening ->
                if (engine.hasMedia) {
                    warmUp = RaftDesktopVideoWarmUp.Silencing
                    applyVolume()
                }
            RaftDesktopVideoWarmUp.Silencing -> {
                warmUp = RaftDesktopVideoWarmUp.Playing
                engine.play()
            }
            RaftDesktopVideoWarmUp.Playing ->
                when {
                    // Shorter than the warm-up: it stopped at the end, its last frame on screen.
                    endedFlag && !engine.isPlaying -> finishWarmUp()
                    engine.isPlaying && engine.positionSeconds >= RAFT_DESKTOP_VIDEO_WARM_UP_SECONDS -> {
                        warmUp = RaftDesktopVideoWarmUp.Pausing
                        engine.pause()
                    }
                }
            // The volume comes back only once the player has stopped.
            RaftDesktopVideoWarmUp.Pausing -> if (!engine.isPlaying) finishWarmUp()
        }
    }

    private fun finishWarmUp() {
        warmUp = RaftDesktopVideoWarmUp.Off
        markEnded(false)
        engine.seekToFraction(pinnedFraction ?: 0f)
        applyVolume()
        firstFrameReported = true
    }

    private fun play() {
        if (released) return
        playRequested = true
        if (!opened) {
            openIfNeeded(autoplay = true)
            return
        }
        if (warmUp != RaftDesktopVideoWarmUp.Off) {
            playFromWarmUp()
            return
        }
        pinnedFraction = null
        if (endedFlag) {
            markEnded(false)
            engine.seekToFraction(0f)
        }
        engine.play()
    }

    /** Play during the warm-up: from where the video is shown to be (the start, or a seek), with sound. */
    private fun playFromWarmUp() {
        val moved = warmUp == RaftDesktopVideoWarmUp.Playing || warmUp == RaftDesktopVideoWarmUp.Pausing
        val target = pinnedFraction ?: 0f
        warmUp = RaftDesktopVideoWarmUp.Off
        pinnedFraction = null
        markEnded(false)
        // Back to that point before the sound comes back.
        if (moved) engine.seekToFraction(target)
        applyVolume()
        engine.play()
    }

    private fun pause() {
        if (released) return
        playRequested = false
        // The warm-up is silent and ends paused by itself.
        if (warmUp != RaftDesktopVideoWarmUp.Off) return
        if (opened) engine.pause()
    }

    private fun stop() {
        playRequested = false
        markEnded(false)
        // A stop during the warm-up leaves the player silenced; the next open sets the volume again.
        warmUp = RaftDesktopVideoWarmUp.Off
        pinnedFraction = null
        if (!opened) return
        opened = false
        engine.stop()
    }

    private fun retry() {
        engine.clearError()
        markEnded(false)
        warmUp = RaftDesktopVideoWarmUp.Off
        pinnedFraction = null
        opened = false
        engine.stop()
        playRequested = true
        openIfNeeded(autoplay = true)
    }

    private fun applyVolume() {
        if (released) return
        engine.volume = if (userMuted || hostMuted || warmUpSilenced) 0f else volume
    }

    /** From just before the warm-up plays until the player has stopped again. */
    private val warmUpSilenced: Boolean
        get() =
            warmUp == RaftDesktopVideoWarmUp.Silencing ||
                warmUp == RaftDesktopVideoWarmUp.Playing ||
                warmUp == RaftDesktopVideoWarmUp.Pausing

    private fun markEnded(value: Boolean) {
        endedFlag = value
    }
}

/**
 * How far the warm-up plays (seconds on the player's clock) before it pauses. The player copies a
 * frame for the screen every 1/30 s or so while it plays, so several are up by then.
 */
private const val RAFT_DESKTOP_VIDEO_WARM_UP_SECONDS = 0.2

/** The steps of [RaftDesktopVideoController]'s first-frame warm-up, at most one per poll. */
private enum class RaftDesktopVideoWarmUp {
    /** None asked for, or done. */
    Off,

    /** Opened paused (`PREPLAY`); waiting for the media to load. */
    Opening,

    /** Loaded; the volume goes to 0 now, playback starts on the next poll. */
    Silencing,

    /** Playing silently until the clock has moved far enough for frames to be on screen. */
    Playing,

    /** Pause asked; once the player has stopped: back to the start, volume back, frame reported. */
    Pausing,
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
