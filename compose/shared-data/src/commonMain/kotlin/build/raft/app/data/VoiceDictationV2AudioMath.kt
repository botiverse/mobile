package build.raft.app.data

import build.raft.app.RaftCoreInternalApi
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

// PCM16 mono chunking and perceptual RMS audio-level helpers shared by Android, iOS, and Desktop
// voice dictation recorders (`#wg-mobile-sync:#334`).
// Calibration mirrors web `packages/web/src/features/composer/voiceDictationPresentation.ts`
// (Kirby spec: noise floor 0.015, ceiling 0.16, power curve 0.65, 60 ms attack / 250 ms release).

/** Target capture chunk duration in milliseconds (~100 ms frames). */
@RaftCoreInternalApi
const val VOICE_DICTATION_CHUNK_DURATION_MS: Int = 100

/** Bytes per signed 16-bit mono PCM sample. */
@RaftCoreInternalApi
const val VOICE_DICTATION_PCM16_BYTES_PER_SAMPLE: Int = 2

/** Normalized PCM RMS noise floor below which visual audio level stays at 0. */
@RaftCoreInternalApi
const val VOICE_DICTATION_RMS_NOISE_FLOOR: Float = 0.015f

/** Normalized PCM RMS ceiling where conversational speech hits full visual level (1f). */
@RaftCoreInternalApi
const val VOICE_DICTATION_RMS_CEILING: Float = 0.16f

private const val VOICE_DICTATION_RMS_EXPONENT: Double = 0.65
private const val VOICE_DICTATION_ATTACK_MS: Double = 60.0
private const val VOICE_DICTATION_RELEASE_MS: Double = 250.0
private const val VOICE_DICTATION_SILENCE_SNAP_EPSILON: Float = 0.005f

/** Returns the PCM16 mono byte size for [durationMs] at [sampleRateHz] (3,200 bytes at 16 kHz / 100 ms). */
@RaftCoreInternalApi
fun voiceDictationChunkByteSize(
    sampleRateHz: Int = VOICE_DICTATION_DEFAULT_SAMPLE_RATE_HZ,
    durationMs: Int = VOICE_DICTATION_CHUNK_DURATION_MS,
): Int {
    val safeRate = sampleRateHz.coerceIn(VOICE_DICTATION_MIN_SAMPLE_RATE_HZ, VOICE_DICTATION_MAX_SAMPLE_RATE_HZ)
    val safeDuration = durationMs.coerceAtLeast(10)
    val samples = (safeRate.toLong() * safeDuration.toLong() / 1_000L).toInt().coerceAtLeast(1)
    return samples * VOICE_DICTATION_PCM16_BYTES_PER_SAMPLE
}

/**
 * Computes raw normalized RMS in `0f..1f` for little-endian signed 16-bit mono PCM samples in
 * `pcmBytes[0 until byteCount]`. Any trailing odd byte is ignored.
 */
@RaftCoreInternalApi
fun computeVoiceDictationPcm16LeRawRms(
    pcmBytes: ByteArray,
    byteCount: Int = pcmBytes.size,
): Float {
    val usableBytes = byteCount.coerceIn(0, pcmBytes.size)
    val sampleCount = usableBytes / VOICE_DICTATION_PCM16_BYTES_PER_SAMPLE
    if (sampleCount <= 0) return 0f
    var sumSquares = 0.0
    var index = 0
    val limit = sampleCount * VOICE_DICTATION_PCM16_BYTES_PER_SAMPLE
    while (index < limit) {
        val lo = pcmBytes[index].toInt() and 0xFF
        val hi = pcmBytes[index + 1].toInt()
        val sample = (hi shl 8) or lo
        val normalized = sample / 32768.0
        sumSquares += normalized * normalized
        index += 2
    }
    return sqrt(sumSquares / sampleCount.toDouble()).toFloat().coerceIn(0f, 1f)
}

/**
 * Maps raw PCM RMS (`0f..1f`) into perceptual visual audio level (`0f..1f`), suppressing room
 * noise at or below [VOICE_DICTATION_RMS_NOISE_FLOOR] to `0f`.
 */
@RaftCoreInternalApi
fun normalizeVoiceDictationRms(rawRms: Float): Float {
    if (!rawRms.isFinite() || rawRms <= VOICE_DICTATION_RMS_NOISE_FLOOR) return 0f
    val span = VOICE_DICTATION_RMS_CEILING - VOICE_DICTATION_RMS_NOISE_FLOOR
    val linear = ((rawRms - VOICE_DICTATION_RMS_NOISE_FLOOR) / span).coerceIn(0f, 1f)
    return linear.toDouble().pow(VOICE_DICTATION_RMS_EXPONENT).toFloat().coerceIn(0f, 1f)
}

/** Computes calibrated visual audio level (`0f..1f`) directly from a PCM16 LE mono chunk. */
@RaftCoreInternalApi
fun computeVoiceDictationPcm16LeLevel(
    pcmBytes: ByteArray,
    byteCount: Int = pcmBytes.size,
): Float = normalizeVoiceDictationRms(computeVoiceDictationPcm16LeRawRms(pcmBytes, byteCount))

/**
 * Asymmetric attack/release envelope smoother for ~100 ms audio level ticks:
 * rises fast (~60 ms) on speech onset and decays smoothly (~250 ms) between syllables.
 */
@RaftCoreInternalApi
fun smoothVoiceDictationAudioLevel(
    previous: Float,
    target: Float,
    dtMs: Int = VOICE_DICTATION_CHUNK_DURATION_MS,
): Float {
    val prev = if (previous.isFinite()) previous.coerceIn(0f, 1f) else 0f
    val nextTarget = if (target.isFinite()) target.coerceIn(0f, 1f) else 0f
    val dt = if (dtMs > 0) dtMs.toDouble() else VOICE_DICTATION_CHUNK_DURATION_MS.toDouble()
    val tau = if (nextTarget >= prev) VOICE_DICTATION_ATTACK_MS else VOICE_DICTATION_RELEASE_MS
    val alpha = 1.0 - exp(-dt / tau)
    val smoothed = (prev + (nextTarget - prev) * alpha).toFloat()
    if (nextTarget == 0f && smoothed < VOICE_DICTATION_SILENCE_SNAP_EPSILON) return 0f
    return smoothed.coerceIn(0f, 1f)
}
