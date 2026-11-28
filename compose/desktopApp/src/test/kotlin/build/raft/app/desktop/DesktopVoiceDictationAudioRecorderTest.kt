@file:OptIn(RaftCoreInternalApi::class)

package build.raft.app.desktop

import build.raft.app.RaftCoreInternalApi
import build.raft.app.data.DesktopVoiceDictationAudioRecorder
import build.raft.app.data.DesktopVoiceDictationLineFactory
import build.raft.app.data.DesktopVoiceDictationPcmLine
import build.raft.app.data.RaftVoiceDictationAudioRecorderRegistry
import build.raft.app.data.VoiceDictationAudioStartResult
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.sound.sampled.LineUnavailableException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
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
        assertContentEquals(payload, chunks[0])
        assertEquals(1, levels.size)
        assertTrue(levels[0] > 0.2f && levels[0] <= 1f)

        recorder.stop()
        recorder.stop()
        assertTrue(fakeLine.closeCount.get() >= 1)
    }

    @Test
    fun emitsUntouchedRawPcmBytesWithCalibratedUnAgcLevels() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val roomNoise = pcm16LeChunk(sample = 100, byteSize = 3_200)
        val handheldSpeech = pcm16LeChunk(sample = 700, byteSize = 3_200)
        val fakeLine = FakeDesktopPcmLine(reads = listOf(roomNoise, handheldSpeech))
        val recorder =
            DesktopVoiceDictationAudioRecorder(
                lineFactory = DesktopVoiceDictationLineFactory { fakeLine },
                captureDispatcher = dispatcher,
            )

        val chunks = mutableListOf<ByteArray>()
        val levels = mutableListOf<Float>()
        assertEquals<VoiceDictationAudioStartResult>(
            VoiceDictationAudioStartResult.Started,
            recorder.start(onPcmChunk = { chunks += it }, onAudioLevel = { levels += it }),
        )
        advanceUntilIdle()

        assertEquals(2, chunks.size)
        assertContentEquals(roomNoise, chunks[0])
        assertContentEquals(handheldSpeech, chunks[1])
        assertEquals(0f, levels[0])
        assertTrue(levels[1] > 0.35f && levels[1] < 0.70f, "expected calibrated un-AGC level, got ${levels[1]}")
        recorder.stop()
    }

    @Test
    fun stopFlushesPartialSub100msTailChunkViaStopRecordingBeforeClose() = runBlocking<Unit> {
        val fullChunk = pcm16LeChunk(sample = 3_600, byteSize = 3_200)
        val tailBytes = pcm16LeChunk(sample = 4_100, byteSize = 1_001)
        val events = CopyOnWriteArrayList<String>()
        val blockingReadEntered = CountDownLatch(1)
        val unblockRead = CountDownLatch(1)

        val blockingLine =
            object : DesktopVoiceDictationPcmLine {
                private var step = 0

                override fun openAndStart(bufferBytes: Int) = Unit

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                    when (step++) {
                        0 -> {
                            fullChunk.copyInto(buffer, destinationOffset = offset)
                            fullChunk.size
                        }
                        1 -> {
                            val count = minOf(length, tailBytes.size)
                            tailBytes.copyInto(buffer, destinationOffset = offset, startIndex = 0, endIndex = count)
                            count
                        }
                        else -> {
                            blockingReadEntered.countDown()
                            unblockRead.await(2, TimeUnit.SECONDS)
                            0
                        }
                    }

                override fun stopRecording() {
                    events += "stopRecording"
                    unblockRead.countDown()
                }

                override fun stopAndClose() {
                    events += "stopAndClose"
                    unblockRead.countDown()
                }
            }

        val recorder =
            DesktopVoiceDictationAudioRecorder(
                lineFactory = DesktopVoiceDictationLineFactory { blockingLine },
                captureDispatcher = Dispatchers.IO,
            )

        val chunks = CopyOnWriteArrayList<ByteArray>()
        val levels = CopyOnWriteArrayList<Float>()
        assertEquals<VoiceDictationAudioStartResult>(
            VoiceDictationAudioStartResult.Started,
            recorder.start(onPcmChunk = { chunks += it }, onAudioLevel = { levels += it }),
        )
        assertTrue(blockingReadEntered.await(2, TimeUnit.SECONDS))
        assertEquals(1, chunks.size)

        recorder.stop()

        assertEquals(2, chunks.size)
        assertContentEquals(fullChunk, chunks[0])
        assertEquals(1_000, chunks[1].size)
        assertContentEquals(tailBytes.copyOf(1_000), chunks[1])
        assertEquals(2, levels.size)
        assertTrue(levels[1] > 0.2f && levels[1] <= 1f)
        assertEquals(listOf("stopRecording", "stopAndClose"), events.toList())
    }

    @Test
    fun stopTimesOutAndForcesCloseWithoutLeakingTailWhenReadIgnoresStopRecording() = runBlocking<Unit> {
        val partialTail = pcm16LeChunk(sample = 3_900, byteSize = 640)
        val blockingReadEntered = CountDownLatch(1)
        val unblockOnClose = CountDownLatch(1)
        val closeCalls = AtomicInteger(0)

        val stubbornLine =
            object : DesktopVoiceDictationPcmLine {
                private var step = 0

                override fun openAndStart(bufferBytes: Int) = Unit

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                    if (step++ == 0) {
                        partialTail.copyInto(buffer, destinationOffset = offset)
                        partialTail.size
                    } else {
                        blockingReadEntered.countDown()
                        unblockOnClose.await(2, TimeUnit.SECONDS)
                        0
                    }

                override fun stopRecording() = Unit

                override fun stopAndClose() {
                    closeCalls.incrementAndGet()
                    unblockOnClose.countDown()
                }
            }

        val recorder =
            DesktopVoiceDictationAudioRecorder(
                lineFactory = DesktopVoiceDictationLineFactory { stubbornLine },
                captureDispatcher = Dispatchers.IO,
                stopJoinTimeoutMs = 50L,
            )

        val chunks = CopyOnWriteArrayList<ByteArray>()
        assertEquals<VoiceDictationAudioStartResult>(
            VoiceDictationAudioStartResult.Started,
            recorder.start(onPcmChunk = { chunks += it }, onAudioLevel = {}),
        )
        assertTrue(blockingReadEntered.await(2, TimeUnit.SECONDS))

        recorder.stop()

        assertTrue(closeCalls.get() >= 1)
        assertTrue(chunks.isEmpty(), "timed-out stop fallback must discard stale tail")
    }

    @Test
    fun startSupersedingActiveCaptureDiscardsOrphanedTailFromPreviousSession() = runBlocking<Unit> {
        val partialFirst = pcm16LeChunk(sample = 3_700, byteSize = 700)
        val fullSecond = pcm16LeChunk(sample = 3_800, byteSize = 3_200)
        val firstReadBlocked = CountDownLatch(1)
        val firstClosed = CountDownLatch(1)
        val secondReadDone = CountDownLatch(1)
        val lineCount = AtomicInteger(0)

        val recorder =
            DesktopVoiceDictationAudioRecorder(
                lineFactory = DesktopVoiceDictationLineFactory {
                    if (lineCount.getAndIncrement() == 0) {
                        object : DesktopVoiceDictationPcmLine {
                            private var step = 0

                            override fun openAndStart(bufferBytes: Int) = Unit

                            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                                if (step++ == 0) {
                                    partialFirst.copyInto(buffer, destinationOffset = offset)
                                    partialFirst.size
                                } else {
                                    firstReadBlocked.countDown()
                                    firstClosed.await(2, TimeUnit.SECONDS)
                                    0
                                }

                            override fun stopAndClose() {
                                firstClosed.countDown()
                            }
                        }
                    } else {
                        object : DesktopVoiceDictationPcmLine {
                            private var step = 0

                            override fun openAndStart(bufferBytes: Int) = Unit

                            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                                if (step++ == 0) {
                                    fullSecond.copyInto(buffer, destinationOffset = offset)
                                    fullSecond.size
                                } else {
                                    secondReadDone.countDown()
                                    0
                                }

                            override fun stopAndClose() = Unit
                        }
                    }
                },
                captureDispatcher = Dispatchers.IO,
            )

        val firstChunks = CopyOnWriteArrayList<ByteArray>()
        val secondChunks = CopyOnWriteArrayList<ByteArray>()
        assertEquals<VoiceDictationAudioStartResult>(
            VoiceDictationAudioStartResult.Started,
            recorder.start(onPcmChunk = { firstChunks += it }, onAudioLevel = {}),
        )
        assertTrue(firstReadBlocked.await(2, TimeUnit.SECONDS))

        assertEquals<VoiceDictationAudioStartResult>(
            VoiceDictationAudioStartResult.Started,
            recorder.start(onPcmChunk = { secondChunks += it }, onAudioLevel = {}),
        )
        assertTrue(secondReadDone.await(2, TimeUnit.SECONDS))
        recorder.stop()

        assertTrue(firstChunks.isEmpty(), "superseded capture must discard orphaned tail")
        assertEquals(1, secondChunks.size)
        assertContentEquals(fullSecond, secondChunks[0])
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
