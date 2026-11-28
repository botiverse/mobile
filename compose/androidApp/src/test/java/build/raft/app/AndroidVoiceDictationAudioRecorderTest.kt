@file:OptIn(RaftCoreInternalApi::class)

package build.raft.app

import build.raft.app.data.RaftVoiceDictationAudioRecorderRegistry
import build.raft.app.data.VoiceDictationAudioStartResult
import build.raft.app.data.computeVoiceDictationPcm16LeLevel
import build.raft.app.data.voiceDictationChunkByteSize
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
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
        assertArrayEquals(speechSample.copyOfRange(0, 3_200), chunks[0])
        assertArrayEquals(speechSample.copyOfRange(3_200, 3_800), chunks[1])
        assertEquals(2, levels.size)
        assertTrue(levels[0] > 0.2f && levels[0] <= 1f)
        assertTrue(levels[1] > 0.2f && levels[1] <= 1f)

        // Idempotent stop never throws and releases underlying AudioRecord cleanly.
        recorder.stop()
        recorder.stop()
        assertTrue(fakeSource.releaseCount.get() >= 1)
    }

    @Test
    fun emitsUntouchedRawPcmBytesWhileCalibratingHandheldAndLoudSpeechVisualLevels() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val roomNoise = pcm16LeChunk(sample = 100, byteSize = 3_200)
        val handheldSpeech = pcm16LeChunk(sample = 700, byteSize = 3_200)
        val loudCloseSpeech = pcm16LeChunk(sample = 6_000, byteSize = 3_200)
        val moderateCloseSyllable = pcm16LeChunk(sample = 2_600, byteSize = 3_200)
        val inputChunks =
            listOf(
                roomNoise,
                handheldSpeech,
                loudCloseSpeech,
                loudCloseSpeech,
                loudCloseSpeech,
                loudCloseSpeech,
                loudCloseSpeech,
                loudCloseSpeech,
                moderateCloseSyllable,
            )
        val recorder =
            AndroidVoiceDictationAudioRecorder(
                permissionRequester = { AndroidRecordAudioPermissionOutcome.Granted },
                sourceFactory = { _, _ -> FakePcmSource(reads = inputChunks) },
                captureDispatcher = dispatcher,
                logWarning = {},
            )

        val emittedChunks = mutableListOf<ByteArray>()
        val emittedLevels = mutableListOf<Float>()
        val result =
            recorder.start(
                onPcmChunk = { emittedChunks += it },
                onAudioLevel = { emittedLevels += it },
            )
        assertEquals(VoiceDictationAudioStartResult.Started, result)
        advanceUntilIdle()

        assertEquals(inputChunks.size, emittedChunks.size)
        for (i in inputChunks.indices) {
            assertArrayEquals("chunk $i raw PCM bytes must never be altered", inputChunks[i], emittedChunks[i])
        }

        // Room noise stays at 0f; handheld un-AGC'd speech (~0.021 RMS) registers strongly;
        // loud close-up speech adapts ceiling upward so the quieter 2600-sample syllable drops visibly.
        assertEquals(0f, emittedLevels[0], 0.0001f)
        assertTrue("expected strong handheld speech level, got ${emittedLevels[1]}", emittedLevels[1] > 0.35f && emittedLevels[1] < 0.70f)
        assertTrue("expected loud close-up peak > 0.95, got ${emittedLevels[7]}", emittedLevels[7] > 0.95f)
        assertTrue(
            "expected adapted ceiling to preserve syllable modulation (${emittedLevels[7]} -> ${emittedLevels[8]})",
            emittedLevels[8] < emittedLevels[7] - 0.05f,
        )
        recorder.stop()
    }

    @Test
    fun stopFlushesPartialSub100msTailChunkViaStopRecordingBeforeRelease() = runBlocking<Unit> {
        val fullChunk = pcm16LeChunk(sample = 3_500, byteSize = 3_200)
        // Odd byte count (1,201) verifies PCM16 2-byte alignment -> flushes 1,200 bytes.
        val tailBytes = pcm16LeChunk(sample = 4_200, byteSize = 1_201)
        val events = CopyOnWriteArrayList<String>()
        val blockingReadEntered = CountDownLatch(1)
        val unblockRead = CountDownLatch(1)

        val blockingSource =
            object : AndroidVoiceDictationPcmSource {
                private var step = 0

                override fun startRecording(): Boolean = true

                override fun read(buffer: ByteArray, offset: Int, size: Int): Int {
                    return when (step++) {
                        0 -> {
                            fullChunk.copyInto(buffer, destinationOffset = offset, startIndex = 0, endIndex = fullChunk.size)
                            fullChunk.size
                        }
                        1 -> {
                            val count = minOf(size, tailBytes.size)
                            tailBytes.copyInto(buffer, destinationOffset = offset, startIndex = 0, endIndex = count)
                            count
                        }
                        else -> {
                            blockingReadEntered.countDown()
                            unblockRead.await(2, TimeUnit.SECONDS)
                            0
                        }
                    }
                }

                override fun stopRecording() {
                    events += "stopRecording"
                    unblockRead.countDown()
                }

                override fun stopAndRelease() {
                    events += "stopAndRelease"
                    unblockRead.countDown()
                }
            }

        val recorder =
            AndroidVoiceDictationAudioRecorder(
                permissionRequester = { AndroidRecordAudioPermissionOutcome.Granted },
                sourceFactory = { _, _ -> blockingSource },
                captureDispatcher = Dispatchers.IO,
                logWarning = {},
            )

        val chunks = CopyOnWriteArrayList<ByteArray>()
        val levels = CopyOnWriteArrayList<Float>()
        val result =
            recorder.start(
                onPcmChunk = { chunks += it },
                onAudioLevel = { levels += it },
            )
        assertEquals(VoiceDictationAudioStartResult.Started, result)
        assertTrue(blockingReadEntered.await(2, TimeUnit.SECONDS))
        assertEquals(1, chunks.size)

        recorder.stop()

        assertEquals(2, chunks.size)
        assertArrayEquals(fullChunk, chunks[0])
        assertEquals(1_200, chunks[1].size)
        assertArrayEquals(tailBytes.copyOf(1_200), chunks[1])
        assertEquals(2, levels.size)
        assertTrue(levels[1] > 0.2f && levels[1] <= 1f)
        assertEquals(listOf("stopRecording", "stopAndRelease"), events.toList())
    }

    @Test
    fun stopTimesOutAndForcesReleaseWithoutLeakingTailWhenReadIgnoresStopRecording() = runBlocking<Unit> {
        val partialTail = pcm16LeChunk(sample = 4_000, byteSize = 800)
        val blockingReadEntered = CountDownLatch(1)
        val unblockOnRelease = CountDownLatch(1)
        val releaseCalls = AtomicInteger(0)

        val stubbornSource =
            object : AndroidVoiceDictationPcmSource {
                private var step = 0

                override fun startRecording(): Boolean = true

                override fun read(buffer: ByteArray, offset: Int, size: Int): Int {
                    return if (step++ == 0) {
                        partialTail.copyInto(buffer, destinationOffset = offset, startIndex = 0, endIndex = partialTail.size)
                        partialTail.size
                    } else {
                        blockingReadEntered.countDown()
                        unblockOnRelease.await(2, TimeUnit.SECONDS)
                        0
                    }
                }

                override fun stopRecording() {
                    // Simulate a buggy HAL whose read() ignores stop() until release().
                }

                override fun stopAndRelease() {
                    releaseCalls.incrementAndGet()
                    unblockOnRelease.countDown()
                }
            }

        val recorder =
            AndroidVoiceDictationAudioRecorder(
                permissionRequester = { AndroidRecordAudioPermissionOutcome.Granted },
                sourceFactory = { _, _ -> stubbornSource },
                captureDispatcher = Dispatchers.IO,
                logWarning = {},
                stopJoinTimeoutMs = 50L,
            )

        val chunks = CopyOnWriteArrayList<ByteArray>()
        assertEquals(
            VoiceDictationAudioStartResult.Started,
            recorder.start(onPcmChunk = { chunks += it }, onAudioLevel = {}),
        )
        assertTrue(blockingReadEntered.await(2, TimeUnit.SECONDS))

        recorder.stop()

        assertTrue(releaseCalls.get() >= 1)
        assertTrue("timed-out stop fallback must discard stale tail", chunks.isEmpty())
    }

    @Test
    fun startSupersedingActiveCaptureDiscardsOrphanedTailFromPreviousSession() = runBlocking<Unit> {
        val partialFirst = pcm16LeChunk(sample = 3_800, byteSize = 600)
        val fullSecond = pcm16LeChunk(sample = 3_900, byteSize = 3_200)
        val firstReadBlocked = CountDownLatch(1)
        val firstReleased = CountDownLatch(1)
        val secondReadDone = CountDownLatch(1)
        val sourceCount = AtomicInteger(0)

        val recorder =
            AndroidVoiceDictationAudioRecorder(
                permissionRequester = { AndroidRecordAudioPermissionOutcome.Granted },
                sourceFactory = { _, _ ->
                    if (sourceCount.getAndIncrement() == 0) {
                        object : AndroidVoiceDictationPcmSource {
                            private var step = 0

                            override fun startRecording(): Boolean = true

                            override fun read(buffer: ByteArray, offset: Int, size: Int): Int =
                                if (step++ == 0) {
                                    partialFirst.copyInto(buffer, destinationOffset = offset)
                                    partialFirst.size
                                } else {
                                    firstReadBlocked.countDown()
                                    firstReleased.await(2, TimeUnit.SECONDS)
                                    0
                                }

                            override fun stopAndRelease() {
                                firstReleased.countDown()
                            }
                        }
                    } else {
                        object : AndroidVoiceDictationPcmSource {
                            private var step = 0

                            override fun startRecording(): Boolean = true

                            override fun read(buffer: ByteArray, offset: Int, size: Int): Int =
                                if (step++ == 0) {
                                    fullSecond.copyInto(buffer, destinationOffset = offset)
                                    fullSecond.size
                                } else {
                                    secondReadDone.countDown()
                                    0
                                }

                            override fun stopAndRelease() = Unit
                        }
                    }
                },
                captureDispatcher = Dispatchers.IO,
                logWarning = {},
            )

        val firstChunks = CopyOnWriteArrayList<ByteArray>()
        val secondChunks = CopyOnWriteArrayList<ByteArray>()
        assertEquals(
            VoiceDictationAudioStartResult.Started,
            recorder.start(onPcmChunk = { firstChunks += it }, onAudioLevel = {}),
        )
        assertTrue(firstReadBlocked.await(2, TimeUnit.SECONDS))

        assertEquals(
            VoiceDictationAudioStartResult.Started,
            recorder.start(onPcmChunk = { secondChunks += it }, onAudioLevel = {}),
        )
        assertTrue(secondReadDone.await(2, TimeUnit.SECONDS))
        recorder.stop()

        assertTrue("superseded capture must discard orphaned tail", firstChunks.isEmpty())
        assertEquals(1, secondChunks.size)
        assertArrayEquals(fullSecond, secondChunks[0])
    }

    @Test
    fun pcmLevelMatchesZeroForSilenceAndRoomNoiseAndNonZeroForHandheldSpeech() {
        assertEquals(0f, computeVoiceDictationPcm16LeLevel(ByteArray(3_200)), 0.0001f)
        assertEquals(0f, computeVoiceDictationPcm16LeLevel(pcm16LeChunk(sample = 100, byteSize = 3_200)), 0.0001f)
        val speech = pcm16LeChunk(sample = 700, byteSize = 3_200)
        val level = computeVoiceDictationPcm16LeLevel(speech)
        assertTrue("expected mid-range handheld speech level, got $level", level > 0.35f && level < 1f)
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
