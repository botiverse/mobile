package build.raft.app

import android.content.pm.ActivityInfo
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

internal const val RaftAndroidVideoSeekJumpMs = 10_000L
internal const val RaftAndroidVideoHorizontalDragSpanCapMs = 90_000L
internal const val RaftAndroidVideoControlsAutoHideMs = 3_000L
internal const val RaftAndroidVideoSeekBadgeDurationMs = 750L
internal const val RaftAndroidVideoMinOverlaySizeDp = 48f

internal val RaftAndroidVideoPlaybackSpeeds = listOf(1.0f, 1.25f, 1.5f, 2.0f, 0.5f)

internal enum class RaftAndroidVideoTapZone {
    Rewind,
    Center,
    Forward,
}

/**
 * Picks fullscreen orientation from the video's display aspect ratio:
 * - Portrait video (height > width, e.g. 9:16 phone recording) stays portrait so it fills the
 *   screen instead of being rotated into a narrow strip.
 * - Landscape video (width > height, e.g. 16:9) rotates to sensor landscape.
 * - Square or not-yet-known dimensions keep the current orientation.
 */
internal fun raftAndroidVideoFullscreenOrientation(
    width: Int,
    height: Int,
    pixelWidthHeightRatio: Float = 1f,
): Int {
    if (width <= 0 || height <= 0) return ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    val safeRatio = if (pixelWidthHeightRatio.isFinite() && pixelWidthHeightRatio > 0f) pixelWidthHeightRatio else 1f
    val displayWidth = width.toFloat() * safeRatio
    val displayHeight = height.toFloat()
    val diff = displayWidth - displayHeight
    return when {
        abs(diff) <= 0.01f -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        displayHeight > displayWidth -> ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
        else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }
}

/** Cycles through [RaftAndroidVideoPlaybackSpeeds] starting from `1x -> 1.25x -> 1.5x -> 2x -> 0.5x -> 1x`. */
internal fun raftAndroidVideoNextSpeed(current: Float): Float {
    val index = RaftAndroidVideoPlaybackSpeeds.indexOfFirst { abs(it - current) < 0.01f }
    return if (index < 0) RaftAndroidVideoPlaybackSpeeds.first()
    else RaftAndroidVideoPlaybackSpeeds[(index + 1) % RaftAndroidVideoPlaybackSpeeds.size]
}

/** Formats a playback speed like `1x`, `1.25x`, `1.5x`, `2x`, `0.5x`. */
internal fun raftAndroidVideoSpeedLabel(rate: Float): String {
    val safe = if (rate.isFinite()) rate.coerceAtLeast(0.25f) else 1f
    val hundredths = (safe * 100f).roundToInt()
    val whole = hundredths / 100
    val frac = hundredths % 100
    return when {
        frac == 0 -> "${whole}x"
        frac % 10 == 0 -> "${whole}.${frac / 10}x"
        else -> "${whole}.${frac.toString().padStart(2, '0')}x"
    }
}

/** Maps a pointer x coordinate inside a scrub bar of width [widthPx] (with horizontal [insetPx]) to `0f..1f`. */
internal fun raftAndroidVideoScrubFraction(
    xPx: Float,
    widthPx: Float,
    insetPx: Float = 0f,
): Float {
    if (!xPx.isFinite() || !widthPx.isFinite() || !insetPx.isFinite()) return 0f
    val safeInset = insetPx.coerceAtLeast(0f)
    val trackWidth = widthPx - safeInset * 2f
    if (trackWidth <= 0f) return 0f
    return ((xPx - safeInset) / trackWidth).coerceIn(0f, 1f)
}

/** Converts a normalized `0..1` scrub [fraction] to clamped milliseconds in `0..durationMs`. */
internal fun raftAndroidVideoScrubTargetMs(
    fraction: Float,
    durationMs: Long,
): Long {
    val safeDuration = durationMs.coerceAtLeast(0L)
    if (safeDuration <= 0L || !fraction.isFinite()) return 0L
    return (fraction.coerceIn(0f, 1f).toDouble() * safeDuration.toDouble())
        .roundToLong()
        .coerceIn(0L, safeDuration)
}

/**
 * Computes the target seek position for a horizontal drag across the video surface.
 * Full-width sweep covers the whole video for clips under 90s, and caps at 90s for long videos so
 * scrubbing stays controllable on both short clips and long recordings.
 */
internal fun raftAndroidVideoHorizontalDragTargetMs(
    startPositionMs: Long,
    dragDeltaPx: Float,
    surfaceWidthPx: Float,
    durationMs: Long,
): Long {
    val safeDuration = durationMs.coerceAtLeast(0L)
    if (safeDuration <= 0L) return 0L
    val clampedStart = startPositionMs.coerceIn(0L, safeDuration)
    if (!surfaceWidthPx.isFinite() || surfaceWidthPx <= 0f || !dragDeltaPx.isFinite()) return clampedStart
    val sweepSpanMs = safeDuration.coerceAtMost(RaftAndroidVideoHorizontalDragSpanCapMs)
    val deltaMs = ((dragDeltaPx.toDouble() / surfaceWidthPx.toDouble()) * sweepSpanMs.toDouble()).roundToLong()
    return (clampedStart + deltaMs).coerceIn(0L, safeDuration)
}

/** Splits the video width into thirds for double-tap: left rewinds, center toggles play, right forwards. */
internal fun raftAndroidVideoDoubleTapZone(
    xPx: Float,
    widthPx: Float,
): RaftAndroidVideoTapZone {
    if (widthPx <= 0f) return RaftAndroidVideoTapZone.Center
    val third = widthPx / 3f
    return when {
        xPx < third -> RaftAndroidVideoTapZone.Rewind
        xPx > widthPx - third -> RaftAndroidVideoTapZone.Forward
        else -> RaftAndroidVideoTapZone.Center
    }
}

/**
 * Horizontal surface drag arbitration: claim only when horizontal travel clearly exceeds touch slop
 * and beats vertical travel (1.5:1), leaving vertical drags to the message list's `LazyColumn`.
 */
internal fun raftAndroidVideoHorizontalDragShouldClaim(
    dx: Float,
    dy: Float,
    touchSlopPx: Float,
): Boolean {
    val absDx = abs(dx)
    val absDy = abs(dy)
    return absDx > touchSlopPx && absDx > absDy * 1.5f
}

/** Release surface drag arbitration as soon as vertical movement beats horizontal past touch slop. */
internal fun raftAndroidVideoHorizontalDragShouldRelease(
    dx: Float,
    dy: Float,
    touchSlopPx: Float,
): Boolean {
    val absDx = abs(dx)
    val absDy = abs(dy)
    return absDy > touchSlopPx && absDy >= absDx
}

/** Accumulates rapid consecutive double-tap seeks in the same direction (`+10s -> +20s`), resetting on reversal. */
internal fun raftAndroidVideoAccumulateDoubleTapDelta(
    previousDeltaMs: Long,
    stepMs: Long,
): Long =
    if ((previousDeltaMs > 0L && stepMs > 0L) || (previousDeltaMs < 0L && stepMs < 0L)) {
        previousDeltaMs + stepMs
    } else {
        stepMs
    }

/** Clamps a relative seek (e.g. `-10s` / `+10s`) into `0..durationMs`. */
internal fun raftAndroidVideoResolveRelativeSeekTarget(
    basePositionMs: Long,
    deltaMs: Long,
    durationMs: Long,
): Long {
    val safeDuration = durationMs.coerceAtLeast(0L)
    if (safeDuration <= 0L) return 0L
    return (basePositionMs.coerceIn(0L, safeDuration) + deltaMs).coerceIn(0L, safeDuration)
}

/** Formats playback timestamps as `m:ss` (or `h:mm:ss` when [totalMs] is at least 1 hour). */
internal fun raftAndroidVideoFormatTime(
    ms: Long,
    totalMs: Long = ms,
): String {
    val totalSeconds = (ms.coerceAtLeast(0L) / 1000L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    val showHours = totalMs >= 3_600_000L || hours > 0L
    return if (showHours) {
        "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        "$minutes:${seconds.toString().padStart(2, '0')}"
    }
}

/** Formats a signed seek delta for HUD feedback (e.g. `+10s`, `-10s`, `+1:05`, `-1:05`, `0s`). */
internal fun raftAndroidVideoFormatSeekDelta(deltaMs: Long): String {
    val roundedSeconds = (deltaMs.toDouble() / 1000.0).roundToLong()
    if (roundedSeconds == 0L) return "0s"
    val sign = if (roundedSeconds > 0L) "+" else "-"
    val absSeconds = abs(roundedSeconds)
    return if (absSeconds < 60L) {
        "$sign${absSeconds}s"
    } else {
        val minutes = absSeconds / 60L
        val seconds = absSeconds % 60L
        "$sign$minutes:${seconds.toString().padStart(2, '0')}"
    }
}

/** Controls auto-hide only during active, non-scrubbing video playback with no error. */
internal fun raftAndroidVideoShouldAutoHideControls(
    isPlaying: Boolean,
    isScrubbing: Boolean,
    isAudioOnly: Boolean,
    hasError: Boolean,
): Boolean = isPlaying && !isScrubbing && !isAudioOnly && !hasError

/** Skips composing the visual controller over hidden 1dp audio engines (e.g. `ThreadInlineAudioPreview`). */
internal fun raftAndroidVideoShouldShowInlineControls(
    widthDp: Float,
    heightDp: Float,
): Boolean = widthDp >= RaftAndroidVideoMinOverlaySizeDp && heightDp >= RaftAndroidVideoMinOverlaySizeDp

/**
 * Tight Media3 [androidx.media3.exoplayer.DefaultLoadControl] thresholds per playback role.
 * Media3's stock defaults buffer up to 50s / 125 MB per video instance; capping inline cards to
 * 5s / 2 MB (and audio to 512 KB) keeps peak heap tiny even when multiple cards are visible.
 */
internal data class RaftAndroidVideoBufferPolicy(
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val bufferForPlaybackMs: Int,
    val bufferForPlaybackAfterRebufferMs: Int,
    val targetBufferBytes: Int,
    val backBufferDurationMs: Int = 0,
    val retainBackBufferFromKeyframe: Boolean = false,
)

internal fun raftAndroidVideoBufferPolicy(
    role: RaftMediaPlayerRole,
    audioOnly: Boolean,
): RaftAndroidVideoBufferPolicy =
    when {
        role == RaftMediaPlayerRole.InlineAudio || (role != RaftMediaPlayerRole.FilePreview && audioOnly) ->
            RaftAndroidVideoBufferPolicy(
                minBufferMs = 2_000,
                maxBufferMs = 5_000,
                bufferForPlaybackMs = 500,
                bufferForPlaybackAfterRebufferMs = 1_000,
                targetBufferBytes = 512 * 1024,
            )
        role == RaftMediaPlayerRole.InlineVideo ->
            RaftAndroidVideoBufferPolicy(
                minBufferMs = 2_000,
                maxBufferMs = 5_000,
                bufferForPlaybackMs = 500,
                bufferForPlaybackAfterRebufferMs = 1_000,
                targetBufferBytes = 2 * 1024 * 1024,
            )
        else ->
            RaftAndroidVideoBufferPolicy(
                minBufferMs = 5_000,
                maxBufferMs = 15_000,
                bufferForPlaybackMs = 1_000,
                bufferForPlaybackAfterRebufferMs = 2_000,
                targetBufferBytes = 8 * 1024 * 1024,
            )
    }
