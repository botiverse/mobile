@file:OptIn(RaftCoreInternalApi::class)

package build.raft.app.desktop

import build.raft.app.RaftCoreInternalApi
import build.raft.app.data.DesktopVoiceDictationAudioRecorder
import build.raft.app.data.DesktopVoiceDictationLineFactory
import build.raft.app.data.DesktopVoiceDictationPcmLine
import build.raft.app.data.RaftVoiceDictationAudioRecorderRegistry
import build.raft.app.data.VoiceDictationAudioStartResult
import java.util.concurrent.atomic.AtomicInteger
import javax.sound.sampled.LineUnavailableException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopVoiceDictationAudioRecorderTest {

    @Test
    fun lineUnavailableAndIllegalArgumentMapToDeviceUnavailable() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)

        val unavailableRecorder =
            DesktopVoiceDictationAudioRecorder(
                lineFactory = DesktopVoiceDictationLineFactory { throw LineUnavailableException("no mic") },
                captureDispatcher = dispatcher,
            )
        assertEquals<VoiceDictationAudioStartResult>(
            VoiceDictationAudioStartResult.DeviceUnavailable,
            unavailableRecorder.start(onPcmChunk = {}, onAudioLevel = {}),
        )

        val unsupportedFormatRecorder =
            DesktopVoiceDictationAudioRecorder(
                lineFactory = DesktopVoiceDictationLineFactory { throw IllegalArgumentException("unsupported format") },
                captureDispatcher = dispatcher,
            )
        assertEquals<VoiceDictationAudioStartResult>(
            VoiceDictationAudioStartResult.DeviceUnavailable,
            unsupportedFormatRecorder.start(onPcmChunk = {}, onAudioLevel = {}),
        )
    }

    @Test
    fun securityExceptionMapsToPermissionDenied() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val recorder =
            DesktopVoiceDictationAudioRecorder(
                lineFactory = DesktopVoiceDictationLineFactory { throw SecurityException("mic denied") },
                captureDispatcher = dispatcher,
            )
        assertEquals<VoiceDictationAudioStartResult>(
            VoiceDictationAudioStartResult.PermissionDenied,
            recorder.start(onPcmChunk = {}, onAudioLevel = {}),
        )
    }

    @Test
    fun streams16kHzMonoPcm16ChunksWithRmsLevelsAndIdempotentStop() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val payload = pcm16LeChunk(sample = 3_600, byteSize = 3_200)
        val fakeLine =
            FakeDesktopPcmLine(
                reads =
                    listOf(
                        payload.copyOfRange(0, 1_600),
                        payload.copyOfRange(1_600, 3_200),
                    ),
            )
        var capturedRate = 0f
        var capturedChannels = 0
        var capturedSampleBits = 0
        var capturedBigEndian = true
        val recorder =
            DesktopVoiceDictationAudioRecorder(
                lineFactory = DesktopVoiceDictationLineFactory { format ->
                    capturedRate = format.sampleRate
                    capturedChannels = format.channels
                    capturedSampleBits = format.sampleSizeInBits
                    capturedBigEndian = format.isBigEndian
                    fakeLine
                },
                captureDispatcher = dispatcher,
            )

        val chunks = mutableListOf<ByteArray>()
        val levels = mutableListOf<Float>()
        val result =
            recorder.start(
                onPcmChunk = { chunks += it },
                onAudioLevel = { levels += it },
            )
        assertEquals<VoiceDictationAudioStartResult>(VoiceDictationAudioStartResult.Started, result)
        assertEquals(16_000f, capturedRate)
        assertEquals(1, capturedChannels)
        assertEquals(16, capturedSampleBits)
        assertEquals(false, capturedBigEndian)

        advanceUntilIdle()

        assertEquals(1, chunks.size)
        assertEquals(3_200, chunks[0].size)
        assertEquals(1, levels.size)
        assertTrue(levels[0] > 0.2f && levels[0] <= 1f)

        recorder.stop()
        recorder.stop()
        assertTrue(fakeLine.closeCount.get() >= 1)
    }

    @Test
    fun registryReportsSupportedAfterRegister() {
        val previous = RaftVoiceDictationAudioRecorderRegistry.currentOrNull()
        try {
            val recorder = DesktopVoiceDictationAudioRecorder()
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

    private class FakeDesktopPcmLine(
        private val reads: List<ByteArray>,
    ) : DesktopVoiceDictationPcmLine {
        private var index = 0
        val closeCount = AtomicInteger(0)

        override fun openAndStart(bufferBytes: Int) = Unit

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (index >= reads.size) return 0
            val next = reads[index++]
            val count = minOf(length, next.size)
            next.copyInto(buffer, destinationOffset = offset, startIndex = 0, endIndex = count)
            return count
        }

        override fun stopAndClose() {
            closeCount.incrementAndGet()
        }
    }
}
