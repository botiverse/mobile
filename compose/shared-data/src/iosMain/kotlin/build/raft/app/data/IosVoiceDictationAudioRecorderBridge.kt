@file:OptIn(RaftCoreInternalApi::class)

package build.raft.app.data

import build.raft.app.RaftCoreInternalApi
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import platform.Foundation.NSData
import platform.posix.memcpy

/** Status tokens reported by the Swift iOS voice dictation audio recorder. */
object RaftIosVoiceDictationStartStatus {
    const val STARTED: String = "started"
    const val PERMISSION_DENIED: String = "permission_denied"
    const val DEVICE_UNAVAILABLE: String = "device_unavailable"
    const val FAILED: String = "failed"
}

/** Callback listener passed from Kotlin to the Swift `AVAudioEngine` dictation recorder. */
interface RaftIosVoiceDictationRecorderListener {
    fun onStarted(status: String, detail: String?)
    fun onPcmChunk(data: NSData, audioLevel: Float)
}

/**
 * Non-suspend ObjC-friendly native recorder seam implemented in Swift
 * (`RaftIosVoiceDictationAudioRecorder`) using `AVAudioSession` + `AVAudioEngine` + `AVAudioConverter`.
 */
interface RaftIosVoiceDictationNativeRecorder {
    val sampleRateHz: Int
    fun startCapture(listener: RaftIosVoiceDictationRecorderListener)
    fun stopCapture()
}

/**
 * Installs the Swift iOS voice dictation recorder into [RaftVoiceDictationAudioRecorderRegistry]
 * during app launch (`iOSApp.init`).
 */
object RaftIosVoiceDictationRecorderHost {
    fun install(recorder: RaftIosVoiceDictationNativeRecorder) {
        RaftVoiceDictationAudioRecorderRegistry.register(IosVoiceDictationAudioRecorderAdapter(recorder))
    }

    fun isInstalled(): Boolean = RaftVoiceDictationAudioRecorderRegistry.isSupported()

    fun currentSampleRateHz(): Int = RaftVoiceDictationAudioRecorderRegistry.currentOrNull()?.sampleRateHz ?: 0

    fun unregister() {
        RaftVoiceDictationAudioRecorderRegistry.unregister()
    }
}

private class IosVoiceDictationAudioRecorderAdapter(
    private val nativeRecorder: RaftIosVoiceDictationNativeRecorder,
) : RaftVoiceDictationAudioRecorder {
    private val lock = SynchronizedObject()
    private var generation: Long = 0L

    override val sampleRateHz: Int
        get() = nativeRecorder.sampleRateHz

    override suspend fun start(
        onPcmChunk: (ByteArray) -> Unit,
        onAudioLevel: (Float) -> Unit,
    ): VoiceDictationAudioStartResult {
        val currentGeneration =
            synchronized(lock) {
                generation += 1L
                generation
            }
        val startedDeferred = CompletableDeferred<VoiceDictationAudioStartResult>()
        val listener =
            object : RaftIosVoiceDictationRecorderListener {
                override fun onStarted(status: String, detail: String?) {
                    val result =
                        when (status) {
                            RaftIosVoiceDictationStartStatus.STARTED -> VoiceDictationAudioStartResult.Started
                            RaftIosVoiceDictationStartStatus.PERMISSION_DENIED -> VoiceDictationAudioStartResult.PermissionDenied
                            RaftIosVoiceDictationStartStatus.DEVICE_UNAVAILABLE -> VoiceDictationAudioStartResult.DeviceUnavailable
                            else -> VoiceDictationAudioStartResult.Failed(detail)
                        }
                    startedDeferred.complete(result)
                }

                override fun onPcmChunk(data: NSData, audioLevel: Float) {
                    val active = synchronized(lock) { generation == currentGeneration }
                    if (!active) return
                    val bytes = data.toVoiceDictationByteArray()
                    if (bytes.isEmpty()) return
                    onAudioLevel(if (audioLevel.isFinite()) audioLevel.coerceIn(0f, 1f) else 0f)
                    onPcmChunk(bytes)
                }
            }

        try {
            nativeRecorder.startCapture(listener)
        } catch (error: Throwable) {
            return VoiceDictationAudioStartResult.Failed(error.message)
        }

        return try {
            startedDeferred.await()
        } catch (cancelled: CancellationException) {
            val stillCurrent =
                synchronized(lock) {
                    if (generation == currentGeneration) {
                        generation += 1L
                        true
                    } else {
                        false
                    }
                }
            if (stillCurrent) {
                runCatching { nativeRecorder.stopCapture() }
            }
            throw cancelled
        }
    }

    override suspend fun stop() {
        val stopGeneration = synchronized(lock) { generation }
        try {
            runCatching { nativeRecorder.stopCapture() }
        } finally {
            synchronized(lock) {
                if (generation == stopGeneration) {
                    generation += 1L
                }
            }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toVoiceDictationByteArray(): ByteArray {
    val size = length.toInt()
    if (size <= 0) return ByteArray(0)
    val source = bytes ?: return ByteArray(0)
    val out = ByteArray(size)
    out.usePinned { pinned ->
        memcpy(pinned.addressOf(0), source, length)
    }
    return out
}
