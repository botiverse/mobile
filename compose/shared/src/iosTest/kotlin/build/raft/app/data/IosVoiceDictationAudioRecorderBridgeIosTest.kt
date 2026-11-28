@file:OptIn(
    build.raft.app.RaftCoreInternalApi::class,
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlinx.cinterop.BetaInteropApi::class,
)

package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSData
import platform.Foundation.create

class IosVoiceDictationAudioRecorderBridgeIosTest {
    @Test
    fun stopDeliversSynchronousTailChunkBeforeInvalidatingGenerationAndDropsPostStopCallbacks() = runTest {
        val previous = RaftVoiceDictationAudioRecorderRegistry.currentOrNull()
        val liveBytes = byteArrayOf(1, 2, 3, 4)
        val tailBytes = byteArrayOf(5, 6, 7, 8)
        val lateBytes = byteArrayOf(9, 10)

        var activeListener: RaftIosVoiceDictationRecorderListener? = null
        val fakeNative =
            object : RaftIosVoiceDictationNativeRecorder {
                override val sampleRateHz: Int = VOICE_DICTATION_DEFAULT_SAMPLE_RATE_HZ

                override fun startCapture(listener: RaftIosVoiceDictationRecorderListener) {
                    activeListener = listener
                    listener.onStarted(RaftIosVoiceDictationStartStatus.STARTED, null)
                }

                override fun stopCapture() {
                    activeListener?.onPcmChunk(tailBytes.toNsData(), 0.45f)
                }
            }

        try {
            RaftIosVoiceDictationRecorderHost.install(fakeNative)
            val recorder = assertNotNull(RaftVoiceDictationAudioRecorderRegistry.currentOrNull())

            val receivedChunks = mutableListOf<ByteArray>()
            val receivedLevels = mutableListOf<Float>()
            val startResult =
                recorder.start(
                    onPcmChunk = { receivedChunks += it },
                    onAudioLevel = { receivedLevels += it },
                )
            assertEquals<VoiceDictationAudioStartResult>(VoiceDictationAudioStartResult.Started, startResult)

            activeListener?.onPcmChunk(liveBytes.toNsData(), 0.6f)
            assertEquals(1, receivedChunks.size)
            assertContentEquals(liveBytes, receivedChunks[0])

            recorder.stop()
            assertEquals(2, receivedChunks.size)
            assertContentEquals(tailBytes, receivedChunks[1])
            assertEquals(listOf(0.6f, 0.45f), receivedLevels)

            // Any stray callback arriving after stop() returns must be dropped by the bumped generation.
            activeListener?.onPcmChunk(lateBytes.toNsData(), 0.8f)
            assertEquals(2, receivedChunks.size)
        } finally {
            if (previous != null) {
                RaftVoiceDictationAudioRecorderRegistry.register(previous)
            } else {
                RaftVoiceDictationAudioRecorderRegistry.unregister()
            }
        }
    }

    private fun ByteArray.toNsData(): NSData =
        usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
        }
}
