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

        // ~0.009 amplitude is below the 0.015 noise floor -> 0 visual level.
        val quiet = constantPcm16Le(sample = 300, sampleCount = 160)
        assertTrue(computeVoiceDictationPcm16LeRawRms(quiet) < VOICE_DICTATION_RMS_NOISE_FLOOR)
        assertEquals(0f, computeVoiceDictationPcm16LeLevel(quiet))
    }

    @Test
    fun speechAndPeakSamplesMapIntoZeroToOneRange() {
        // ~0.10 normalized amplitude (conversational speech) -> strictly inside (0, 1).
        val speech = constantPcm16Le(sample = 3_277, sampleCount = 160)
        val rawSpeechRms = computeVoiceDictationPcm16LeRawRms(speech)
        assertTrue(abs(rawSpeechRms - 0.1f) < 0.005f, "expected ~0.10 RMS, got $rawSpeechRms")
        val speechLevel = computeVoiceDictationPcm16LeLevel(speech)
        assertTrue(speechLevel > 0.3f && speechLevel < 1f, "expected mid-range level, got $speechLevel")

        // Negative samples produce the same RMS magnitude as positive samples.
        val negativeSpeech = constantPcm16Le(sample = (-3_277).toShort(), sampleCount = 160)
        assertEquals(speechLevel, computeVoiceDictationPcm16LeLevel(negativeSpeech))

        // Peak signal (>= 0.16 RMS ceiling) saturates cleanly at 1.0f.
        val loud = constantPcm16Le(sample = 16_384, sampleCount = 160)
        assertEquals(1f, computeVoiceDictationPcm16LeLevel(loud))
    }

    @Test
    fun smootherRisesFasterThanItReleasesAndSnapsSilenceToZero() {
        val attackStep = smoothVoiceDictationAudioLevel(previous = 0f, target = 1f, dtMs = 100)
        val releaseStep = smoothVoiceDictationAudioLevel(previous = 1f, target = 0f, dtMs = 100)
        // 100ms attack (tau=60ms) moves much further than 100ms release (tau=250ms).
        assertTrue(attackStep > 0.75f, "expected fast attack > 0.75, got $attackStep")
        assertTrue(releaseStep > 0.55f && releaseStep < 0.80f, "expected smooth release, got $releaseStep")

        // Tiny decaying tail snaps cleanly to 0f.
        assertEquals(0f, smoothVoiceDictationAudioLevel(previous = 0.004f, target = 0f, dtMs = 100))
        // Non-finite inputs are handled defensively.
        assertEquals(0f, normalizeVoiceDictationRms(Float.NaN))
        assertEquals(0f, normalizeVoiceDictationRms(Float.NEGATIVE_INFINITY))
        assertEquals(0f, smoothVoiceDictationAudioLevel(Float.NaN, Float.NaN))
    }
}
