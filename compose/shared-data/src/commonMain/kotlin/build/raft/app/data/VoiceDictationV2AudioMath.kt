package build.raft.app.data

import build.raft.app.RaftCoreInternalApi
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

// PCM16 mono chunking and perceptual RMS audio-level helpers shared by Android, iOS, and Desktop
// voice dictation recorders (`#wg-mobile-sync:#334`, `dm:@artin:#177`).
//
// Unlike web `getUserMedia({ autoGainControl: true })` (where speech arrives at RMS 0.02–0.16),
// native mobile capture intentionally disables AGC (`VOICE_RECOGNITION` on Android,
// `.record` + `.measurement` on iOS) so raw PCM sent to `holt-live` stays clean for ASR.
// Handheld un-AGC'd speech typically lands around RMS 0.006–0.035 (~10–18 dB quieter than Web
// AGC), so the visual meter uses an un-AGC'd noise floor (`0.0045`, ~ -47 dBFS) plus a stateful
// adaptive ceiling (`0.045..0.25`, starting at `0.055`) to keep normal speech lively while
// preventing close-up loud speech from pinning flat at `1f`. Raw PCM bytes are NEVER altered.

/** Target capture chunk duration in milliseconds (~100 ms frames). */
@RaftCoreInternalApi
const val VOICE_DICTATION_CHUNK_DURATION_MS: Int = 100

/** Bytes per signed 16-bit mono PCM sample. */
@RaftCoreInternalApi
const val VOICE_DICTATION_PCM16_BYTES_PER_SAMPLE: Int = 2

/** Normalized PCM RMS noise floor (~ -47 dBFS) below which visual audio level stays at 0. */
@RaftCoreInternalApi
const val VOICE_DICTATION_RMS_NOISE_FLOOR: Float = 0.0045f

/** Minimum adaptive RMS ceiling for quiet un-AGC'd handheld speech. */
@RaftCoreInternalApi
const val VOICE_DICTATION_RMS_CEILING_MIN: Float = 0.045f

/** Initial adaptive RMS ceiling (and default stateless ceiling) for un-AGC'd handheld speech. */
@RaftCoreInternalApi
const val VOICE_DICTATION_RMS_CEILING_INITIAL: Float = 0.055f

/** Maximum adaptive RMS ceiling for loud / close-mic speech. */
@RaftCoreInternalApi
const val VOICE_DICTATION_RMS_CEILING_MAX: Float = 0.25f

/** Default normalized PCM RMS ceiling used by stateless [normalizeVoiceDictationRms] callers. */
@RaftCoreInternalApi
const val VOICE_DICTATION_RMS_CEILING: Float = VOICE_DICTATION_RMS_CEILING_INITIAL

private const val VOICE_DICTATION_RMS_EXPONENT: Double = 0.60
private const val VOICE_DICTATION_ATTACK_MS: Double = 50.0
private const val VOICE_DICTATION_RELEASE_MS: Double = 220.0
private const val VOICE_DICTATION_PEAK_ATTACK_MS: Double = 150.0
private const val VOICE_DICTATION_PEAK_DECAY_MS: Double = 3_000.0
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
 * Maps raw PCM RMS (`0f..1f`) into perceptual visual audio level (`0f..1f`) against
 * `[VOICE_DICTATION_RMS_NOISE_FLOOR, ceiling]`, suppressing quiet room noise at or below
 * [VOICE_DICTATION_RMS_NOISE_FLOOR] to `0f`.
 */
@RaftCoreInternalApi
fun normalizeVoiceDictationRms(
    rawRms: Float,
    ceiling: Float = VOICE_DICTATION_RMS_CEILING,
): Float {
    if (!rawRms.isFinite() || rawRms <= VOICE_DICTATION_RMS_NOISE_FLOOR) return 0f
    val safeCeiling =
        if (ceiling.isFinite()) {
            ceiling.coerceAtLeast(VOICE_DICTATION_RMS_CEILING_MIN)
        } else {
            VOICE_DICTATION_RMS_CEILING
        }
    val span = safeCeiling - VOICE_DICTATION_RMS_NOISE_FLOOR
    if (span <= 0f) return 0f
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
 * rises fast (~50 ms) on speech onset and decays smoothly (~220 ms) between syllables.
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

/**
 * Pure, deterministic stateful audio-level meter for un-AGC'd microphone streams (`dm:@artin:#177`).
 *
 * Tracks a clamped adaptive ceiling (`peakRms` in `[0.045f, 0.25f]`, starting at `0.055f`) that
 * adapts quickly upward (`tau = 150 ms`) on loud close-up speech so syllables keep dynamic
 * breathing instead of pegging at `1f`, and decays slowly (`tau = 3000 ms`) back toward
 * [VOICE_DICTATION_RMS_CEILING_INITIAL] during quieter speech or pauses. Used strictly for the
 * visual mic halo; never modifies raw PCM bytes sent to ASR.
 */
@RaftCoreInternalApi
class VoiceDictationAudioLevelMeter(
    initialPeakRms: Float = VOICE_DICTATION_RMS_CEILING_INITIAL,
) {
    private val defaultPeakRms: Float =
        if (initialPeakRms.isFinite()) {
            initialPeakRms.coerceIn(VOICE_DICTATION_RMS_CEILING_MIN, VOICE_DICTATION_RMS_CEILING_MAX)
        } else {
            VOICE_DICTATION_RMS_CEILING_INITIAL
        }

    var peakRms: Float = defaultPeakRms
        private set

    var smoothedLevel: Float = 0f
        private set

    fun reset() {
        peakRms = defaultPeakRms
        smoothedLevel = 0f
    }

    fun processPcm16Le(
        pcmBytes: ByteArray,
        byteCount: Int = pcmBytes.size,
        dtMs: Int = VOICE_DICTATION_CHUNK_DURATION_MS,
    ): Float = processRawRms(computeVoiceDictationPcm16LeRawRms(pcmBytes, byteCount), dtMs = dtMs)

    fun processRawRms(
        rawRms: Float,
        dtMs: Int = VOICE_DICTATION_CHUNK_DURATION_MS,
    ): Float {
        val safeRms = if (rawRms.isFinite() && rawRms > 0f) rawRms.coerceAtMost(1f) else 0f
        val dt = if (dtMs > 0) dtMs.toDouble() else VOICE_DICTATION_CHUNK_DURATION_MS.toDouble()
        val rawTarget =
            if (safeRms > VOICE_DICTATION_RMS_NOISE_FLOOR) {
                if (safeRms > peakRms) {
                    val targetPeak = safeRms.coerceAtMost(VOICE_DICTATION_RMS_CEILING_MAX)
                    val alpha = 1.0 - exp(-dt / VOICE_DICTATION_PEAK_ATTACK_MS)
                    peakRms = (peakRms + (targetPeak - peakRms) * alpha).toFloat()
                } else {
                    val alpha = 1.0 - exp(-dt / VOICE_DICTATION_PEAK_DECAY_MS)
                    peakRms = (peakRms + (VOICE_DICTATION_RMS_CEILING_INITIAL - peakRms) * alpha).toFloat()
                }
                peakRms = peakRms.coerceIn(VOICE_DICTATION_RMS_CEILING_MIN, VOICE_DICTATION_RMS_CEILING_MAX)
                normalizeVoiceDictationRms(safeRms, ceiling = peakRms)
            } else {
                val alpha = 1.0 - exp(-dt / VOICE_DICTATION_PEAK_DECAY_MS)
                peakRms =
                    (peakRms + (VOICE_DICTATION_RMS_CEILING_INITIAL - peakRms) * alpha)
                        .toFloat()
                        .coerceIn(VOICE_DICTATION_RMS_CEILING_MIN, VOICE_DICTATION_RMS_CEILING_MAX)
                0f
            }
        smoothedLevel = smoothVoiceDictationAudioLevel(previous = smoothedLevel, target = rawTarget, dtMs = dtMs)
        return smoothedLevel
    }
}
