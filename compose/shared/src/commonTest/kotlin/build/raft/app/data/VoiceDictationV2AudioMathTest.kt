package build.raft.app.data

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VoiceDictationV2AudioMathTest {
    private fun constantPcm16Le(sample: Short, sampleCount: Int): ByteArray {
        val out = ByteArray(sampleCount * 2)
        val lo = (sample.toInt() and 0xFF).toByte()
        val hi = ((sample.toInt() shr 8) and 0xFF).toByte()
        var i = 0
        while (i < out.size) {
            out[i] = lo
            out[i + 1] = hi
            i += 2
        }
        return out
    }

    @Test
    fun chunkByteSizeMatches100msAt16kHzMonoPcm16() {
        assertEquals(3_200, voiceDictationChunkByteSize(sampleRateHz = 16_000, durationMs = 100))
        assertEquals(1_600, voiceDictationChunkByteSize(sampleRateHz = 8_000, durationMs = 100))
        assertEquals(9_600, voiceDictationChunkByteSize(sampleRateHz = 48_000, durationMs = 100))
    }

    @Test
    fun silenceAndNoiseFloorClampToZero() {
        val silence = ByteArray(3_200)
        assertEquals(0f, computeVoiceDictationPcm16LeRawRms(silence))
        assertEquals(0f, computeVoiceDictationPcm16LeLevel(silence))
        assertEquals(0f, computeVoiceDictationPcm16LeRawRms(ByteArray(0)))
        assertEquals(0f, computeVoiceDictationPcm16LeRawRms(byteArrayOf(0x7F)))

        // ~0.0031 amplitude (-50 dBFS quiet room noise) is below the 0.0045 noise floor -> 0 visual level.
        val quietRoomNoise = constantPcm16Le(sample = 100, sampleCount = 160)
        assertTrue(computeVoiceDictationPcm16LeRawRms(quietRoomNoise) < VOICE_DICTATION_RMS_NOISE_FLOOR)
        assertEquals(0f, computeVoiceDictationPcm16LeLevel(quietRoomNoise))
    }

    @Test
    fun handheldUnAgcSpeechAndPeakSamplesMapIntoZeroToOneRange() {
        // Un-AGC'd handheld phone speech (~0.012-0.0305 RMS) registers strongly instead of sitting
        // dead below the old 0.015 web-AGC floor.
        val softSpeech = constantPcm16Le(sample = 400, sampleCount = 160)
        val softLevel = computeVoiceDictationPcm16LeLevel(softSpeech)
        assertTrue(softLevel > 0.25f && softLevel < 0.50f, "expected visible soft handheld level, got $softLevel")

        val speech = constantPcm16Le(sample = 700, sampleCount = 160)
        val rawSpeechRms = computeVoiceDictationPcm16LeRawRms(speech)
        assertTrue(abs(rawSpeechRms - 0.02136f) < 0.001f, "expected ~0.0214 RMS, got $rawSpeechRms")
        val speechLevel = computeVoiceDictationPcm16LeLevel(speech)
        assertTrue(speechLevel > 0.40f && speechLevel < 0.70f, "expected strong mid-range level, got $speechLevel")

        val strongHandheld = constantPcm16Le(sample = 1_000, sampleCount = 160)
        val strongLevel = computeVoiceDictationPcm16LeLevel(strongHandheld)
        assertTrue(strongLevel > 0.55f && strongLevel < 0.85f, "expected strong handheld level, got $strongLevel")

        // Negative samples produce the same RMS magnitude as positive samples.
        val negativeSpeech = constantPcm16Le(sample = (-700).toShort(), sampleCount = 160)
        assertEquals(speechLevel, computeVoiceDictationPcm16LeLevel(negativeSpeech))

        // Peak signal saturates cleanly at 1.0f.
        val loud = constantPcm16Le(sample = 16_384, sampleCount = 160)
        assertEquals(1f, computeVoiceDictationPcm16LeLevel(loud))
    }

    @Test
    fun smootherRisesFasterThanItReleasesAndSnapsSilenceToZero() {
        val attackStep = smoothVoiceDictationAudioLevel(previous = 0f, target = 1f, dtMs = 100)
        val releaseStep = smoothVoiceDictationAudioLevel(previous = 1f, target = 0f, dtMs = 100)
        // 100ms attack (tau=50ms) moves much further than 100ms release (tau=220ms).
        assertTrue(attackStep > 0.80f, "expected fast attack > 0.80, got $attackStep")
        assertTrue(releaseStep > 0.55f && releaseStep < 0.75f, "expected smooth release, got $releaseStep")

        // Tiny decaying tail snaps cleanly to 0f.
        assertEquals(0f, smoothVoiceDictationAudioLevel(previous = 0.004f, target = 0f, dtMs = 100))
        // Non-finite inputs are handled defensively.
        assertEquals(0f, normalizeVoiceDictationRms(Float.NaN))
        assertEquals(0f, normalizeVoiceDictationRms(Float.NEGATIVE_INFINITY))
        assertEquals(0f, smoothVoiceDictationAudioLevel(Float.NaN, Float.NaN))
    }

    @Test
    fun audioLevelMeterSuppressesRoomNoiseBoostsHandheldSpeechAndAdaptsCeilingForLoudSpeakers() {
        val meter = VoiceDictationAudioLevelMeter()
        assertEquals(VOICE_DICTATION_RMS_CEILING_INITIAL, meter.peakRms)
        assertEquals(0f, meter.smoothedLevel)

        // 1. Quiet room noise (~0.0031 RMS <= 0.0045 floor) stays cleanly at 0f.
        val quietRoom = constantPcm16Le(sample = 100, sampleCount = 1_600)
        repeat(5) {
            assertEquals(0f, meter.processPcm16Le(quietRoom))
        }
        assertEquals(VOICE_DICTATION_RMS_CEILING_INITIAL, meter.peakRms, 0.0001f)

        // 2. Normal un-AGC'd handheld phone speech (RMS 0.015–0.0305) produces lively visible halo levels.
        val handheld500 = meter.processPcm16Le(constantPcm16Le(sample = 500, sampleCount = 1_600))
        val handheld700 = meter.processPcm16Le(constantPcm16Le(sample = 700, sampleCount = 1_600))
        val handheld1000 = meter.processPcm16Le(constantPcm16Le(sample = 1_000, sampleCount = 1_600))
        assertTrue(handheld500 > 0.30f && handheld500 < 0.50f, "expected visible 500-sample level, got $handheld500")
        assertTrue(handheld700 > 0.40f && handheld700 < 0.65f, "expected visible 700-sample level, got $handheld700")
        assertTrue(handheld1000 > 0.55f && handheld1000 < 0.85f, "expected visible 1000-sample level, got $handheld1000")

        // 3. Loud close-up speech (RMS ~0.18) adapts peakRms quickly upward so quieter syllables
        // (~0.08) still modulate noticeably below loud syllables instead of pegging flat at 1.0f.
        var loudPeakLevel = 0f
        repeat(6) {
            loudPeakLevel = meter.processRawRms(0.18f, dtMs = 100)
        }
        val adaptedPeak = meter.peakRms
        assertTrue(
            adaptedPeak > 0.15f && adaptedPeak <= VOICE_DICTATION_RMS_CEILING_MAX,
            "expected peakRms to adapt upward near 0.18, got $adaptedPeak",
        )
        assertTrue(loudPeakLevel > 0.95f, "expected loud speech near top of halo, got $loudPeakLevel")

        val quieterSyllable1 = meter.processRawRms(0.08f, dtMs = 100)
        val quieterSyllable2 = meter.processRawRms(0.08f, dtMs = 100)
        val nextLoudSyllable = meter.processRawRms(0.18f, dtMs = 100)
        assertTrue(
            quieterSyllable1 < loudPeakLevel - 0.08f && quieterSyllable2 < quieterSyllable1,
            "expected quieter syllable (0.08 RMS) to breathe downward under adapted peakRms: $loudPeakLevel -> $quieterSyllable1 -> $quieterSyllable2",
        )
        assertTrue(
            nextLoudSyllable > quieterSyllable2 + 0.15f,
            "expected loud syllable (0.18 RMS) to rebound strongly: $quieterSyllable2 -> $nextLoudSyllable",
        )

        // 4. Silence decays smoothedLevel to 0f and gently decays peakRms back toward initial ceiling.
        val peakBeforeSilence = meter.peakRms
        repeat(30) {
            meter.processRawRms(0f, dtMs = 100)
        }
        assertEquals(0f, meter.smoothedLevel)
        assertTrue(
            meter.peakRms < peakBeforeSilence - 0.05f && meter.peakRms >= VOICE_DICTATION_RMS_CEILING_INITIAL,
            "expected peakRms to decay back toward initial ceiling ($VOICE_DICTATION_RMS_CEILING_INITIAL), got ${meter.peakRms}",
        )

        // 5. Defensive non-finite inputs and reset() keep meter state valid and deterministic.
        val nanLevel = meter.processRawRms(Float.NaN, dtMs = 0)
        assertTrue(nanLevel.isFinite() && nanLevel in 0f..1f)
        assertTrue(meter.peakRms in VOICE_DICTATION_RMS_CEILING_MIN..VOICE_DICTATION_RMS_CEILING_MAX)
        meter.reset()
        assertEquals(VOICE_DICTATION_RMS_CEILING_INITIAL, meter.peakRms)
        assertEquals(0f, meter.smoothedLevel)
    }
}
