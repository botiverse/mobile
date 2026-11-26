@file:OptIn(RaftCoreInternalApi::class)

package build.raft.app

import build.raft.app.data.RaftVoiceDictationAudioRecorderRegistry
import build.raft.app.data.VoiceDictationAudioStartResult
import build.raft.app.data.computeVoiceDictationPcm16LeLevel
import build.raft.app.data.voiceDictationChunkByteSize
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidVoiceDictationAudioRecorderTest {

    @Test
    fun permissionDeniedReturnsPermissionDeniedWithoutOpeningAudioSource() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var sourceCreated = false
        val recorder =
            AndroidVoiceDictationAudioRecorder(
                permissionRequester = { AndroidRecordAudioPermissionOutcome.Denied },
                sourceFactory = { _, _ ->
                    sourceCreated = true
                    FakePcmSource(emptyList())
                },
                captureDispatcher = dispatcher,
                logWarning = {},
            )

        val result = recorder.start(onPcmChunk = {}, onAudioLevel = {})
        assertEquals(VoiceDictationAudioStartResult.PermissionDenied, result)
        assertFalse(sourceCreated)
    }

    @Test
    fun missingForegroundActivityReturnsDeviceUnavailable() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var sourceCreated = false
        val recorder =
            AndroidVoiceDictationAudioRecorder(
                permissionRequester = { AndroidRecordAudioPermissionOutcome.NoForegroundActivity },
                sourceFactory = { _, _ ->
                    sourceCreated = true
                    FakePcmSource(emptyList())
                },
                captureDispatcher = dispatcher,
                logWarning = {},
            )

        val result = recorder.start(onPcmChunk = {}, onAudioLevel = {})
        assertEquals(VoiceDictationAudioStartResult.DeviceUnavailable, result)
        assertFalse(sourceCreated)
    }

    @Test
    fun unavailableAudioSourceReturnsDeviceUnavailable() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val recorder =
            AndroidVoiceDictationAudioRecorder(
                permissionRequester = { AndroidRecordAudioPermissionOutcome.Granted },
                sourceFactory = { _, _ -> null },
                captureDispatcher = dispatcher,
                logWarning = {},
            )

        val result = recorder.start(onPcmChunk = {}, onAudioLevel = {})
        assertEquals(VoiceDictationAudioStartResult.DeviceUnavailable, result)
    }

    @Test
    fun accumulatesPartialReadsInto100msChunksComputesRmsLevelsAndReleasesOnceOnIdempotentStop() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val chunkBytes = voiceDictationChunkByteSize(sampleRateHz = 16_000, durationMs = 100)
        assertEquals(3_200, chunkBytes)

        // Feed partial reads (1,000 + 1,000 + 1,200 = 3,200 bytes for chunk 1, plus 600-byte tail).
        val speechSample = pcm16LeChunk(sample = 4_000, byteSize = 3_800)
        val fakeSource =
            FakePcmSource(
                reads =
                    listOf(
                        speechSample.copyOfRange(0, 1_000),
                        speechSample.copyOfRange(1_000, 2_000),
                        speechSample.copyOfRange(2_000, 3_200),
                        speechSample.copyOfRange(3_200, 3_800),
                    ),
            )
        val recorder =
            AndroidVoiceDictationAudioRecorder(
                permissionRequester = { AndroidRecordAudioPermissionOutcome.Granted },
                sourceFactory = { rate, bytes ->
                    assertEquals(16_000, rate)
                    assertEquals(3_200, bytes)
                    fakeSource
                },
                captureDispatcher = dispatcher,
                logWarning = {},
            )

        val chunks = mutableListOf<ByteArray>()
        val levels = mutableListOf<Float>()

        val result =
            recorder.start(
                onPcmChunk = { chunks += it },
                onAudioLevel = { levels += it },
            )
        assertEquals(VoiceDictationAudioStartResult.Started, result)

        advanceUntilIdle()

        assertEquals(2, chunks.size)
        assertEquals(3_200, chunks[0].size)
        assertEquals(600, chunks[1].size)
        assertEquals(2, levels.size)
        assertTrue(levels[0] > 0.2f && levels[0] <= 1f)
        assertTrue(levels[1] > 0.2f && levels[1] <= 1f)

        // Idempotent stop never throws and releases underlying AudioRecord cleanly.
        recorder.stop()
        recorder.stop()
        assertTrue(fakeSource.releaseCount.get() >= 1)
    }

    @Test
    fun pcmLevelMatchesZeroForSilenceAndNonZeroForSpeech() {
        assertEquals(0f, computeVoiceDictationPcm16LeLevel(ByteArray(3_200)), 0.0001f)
        val speech = pcm16LeChunk(sample = 3_500, byteSize = 3_200)
        val level = computeVoiceDictationPcm16LeLevel(speech)
        assertTrue(level > 0.3f && level < 1f)
    }

    @Test
    fun registryReportsSupportedWhenAndroidRecorderRegistered() {
        val previous = RaftVoiceDictationAudioRecorderRegistry.currentOrNull()
        try {
            val recorder =
                AndroidVoiceDictationAudioRecorder(
                    permissionRequester = { AndroidRecordAudioPermissionOutcome.Granted },
                    sourceFactory = { _, _ -> null },
                    logWarning = {},
                )
            RaftVoiceDictationAudioRecorderRegistry.register(recorder)
            assertTrue(RaftVoiceDictationAudioRecorderRegistry.isSupported())
            assertEquals(recorder, RaftVoiceDictationAudioRecorderRegistry.currentOrNull())
        } finally {
            if (previous != null) {
                RaftVoiceDictationAudioRecorderRegistry.register(previous)
            } else {
                RaftVoiceDictationAudioRecorderRegistry.unregister()
            }
        }
    }

    private fun pcm16LeChunk(sample: Short, byteSize: Int): ByteArray {
        val out = ByteArray(byteSize)
        val lo = (sample.toInt() and 0xFF).toByte()
        val hi = ((sample.toInt() shr 8) and 0xFF).toByte()
        var i = 0
        while (i + 1 < out.size) {
            out[i] = lo
            out[i + 1] = hi
            i += 2
        }
        return out
    }

    private class FakePcmSource(
        private val reads: List<ByteArray>,
    ) : AndroidVoiceDictationPcmSource {
        private var index = 0
        val releaseCount = AtomicInteger(0)

        override fun startRecording(): Boolean = true

        override fun read(buffer: ByteArray, offset: Int, size: Int): Int {
            if (index >= reads.size) return 0
            val next = reads[index++]
            val count = minOf(size, next.size)
            next.copyInto(buffer, destinationOffset = offset, startIndex = 0, endIndex = count)
            return count
        }

        override fun stopAndRelease() {
            releaseCount.incrementAndGet()
        }
    }
}
