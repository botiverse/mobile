@file:OptIn(RaftCoreInternalApi::class)

package build.raft.app.data

import build.raft.app.RaftCoreInternalApi
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.LineUnavailableException
import javax.sound.sampled.TargetDataLine
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Testable PCM16 mono audio line seam for Desktop JVM capture. */
@RaftCoreInternalApi
interface DesktopVoiceDictationPcmLine {
    fun openAndStart(bufferBytes: Int)
    fun read(buffer: ByteArray, offset: Int, length: Int): Int
    fun stopAndClose()
}

@RaftCoreInternalApi
fun interface DesktopVoiceDictationLineFactory {
    fun create(format: AudioFormat): DesktopVoiceDictationPcmLine
}

private class TargetDataLineAdapter(
    private val line: TargetDataLine,
    private val format: AudioFormat,
) : DesktopVoiceDictationPcmLine {
    private val closed = AtomicBoolean(false)

    override fun openAndStart(bufferBytes: Int) {
        line.open(format, bufferBytes)
        line.start()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        line.read(buffer, offset, length)

    override fun stopAndClose() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { line.stop() }
        runCatching { line.flush() }
        runCatching { line.close() }
    }
}

/**
 * Desktop JVM PCM16 16 kHz signed little-endian mono audio recorder backed by
 * `javax.sound.sampled.AudioSystem.getTargetDataLine`.
 */
@RaftCoreInternalApi
class DesktopVoiceDictationAudioRecorder(
    private val lineFactory: DesktopVoiceDictationLineFactory =
        DesktopVoiceDictationLineFactory { format ->
            val line = AudioSystem.getTargetDataLine(format)
            TargetDataLineAdapter(line, format)
        },
    private val captureDispatcher: CoroutineDispatcher = Dispatchers.IO,
    override val sampleRateHz: Int = VOICE_DICTATION_DEFAULT_SAMPLE_RATE_HZ,
) : RaftVoiceDictationAudioRecorder {
    private class ActiveCapture(
        val line: DesktopVoiceDictationPcmLine,
        var readJob: Job? = null,
    )

    private val lock = Any()
    private val captureScope = CoroutineScope(SupervisorJob() + captureDispatcher)
    private var activeCapture: ActiveCapture? = null

    override suspend fun start(
        onPcmChunk: (ByteArray) -> Unit,
        onAudioLevel: (Float) -> Unit,
    ): VoiceDictationAudioStartResult {
        stop()

        val format =
            AudioFormat(
                sampleRateHz.toFloat(),
                16,
                1,
                true,
                false,
            )
        val chunkBytes = voiceDictationChunkByteSize(sampleRateHz = sampleRateHz)
        val bufferBytes = chunkBytes * 4

        val line =
            try {
                withContext(captureDispatcher) {
                    val created = lineFactory.create(format)
                    try {
                        created.openAndStart(bufferBytes)
                        created
                    } catch (error: Throwable) {
                        runCatching { created.stopAndClose() }
                        throw error
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SecurityException) {
                return VoiceDictationAudioStartResult.PermissionDenied
            } catch (_: LineUnavailableException) {
                return VoiceDictationAudioStartResult.DeviceUnavailable
            } catch (_: IllegalArgumentException) {
                return VoiceDictationAudioStartResult.DeviceUnavailable
            } catch (error: Throwable) {
                return VoiceDictationAudioStartResult.Failed(error.message)
            }

        val capture = ActiveCapture(line = line)
        synchronized(lock) {
            activeCapture = capture
        }

        val job =
            captureScope.launch {
                val chunkBuffer = ByteArray(chunkBytes)
                var filled = 0
                var smoothedLevel = 0f
                try {
                    while (isActive && isCurrentCapture(capture)) {
                        val read =
                            try {
                                line.read(chunkBuffer, filled, chunkBytes - filled)
                            } catch (_: Throwable) {
                                -1
                            }
                        if (read <= 0) break
                        filled += read
                        if (filled >= chunkBytes) {
                            val emit = chunkBuffer.copyOf(chunkBytes)
                            filled = 0
                            if (!isCurrentCapture(capture)) break
                            val rawLevel = computeVoiceDictationPcm16LeLevel(emit)
                            smoothedLevel = smoothVoiceDictationAudioLevel(smoothedLevel, rawLevel)
                            onAudioLevel(smoothedLevel)
                            onPcmChunk(emit)
                        }
                    }
                    val remainingEven = filled - (filled % VOICE_DICTATION_PCM16_BYTES_PER_SAMPLE)
                    if (remainingEven > 0 && isCurrentCapture(capture)) {
                        val tail = chunkBuffer.copyOf(remainingEven)
                        val rawLevel = computeVoiceDictationPcm16LeLevel(tail)
                        smoothedLevel = smoothVoiceDictationAudioLevel(smoothedLevel, rawLevel)
                        onAudioLevel(smoothedLevel)
                        onPcmChunk(tail)
                    }
                } finally {
                    line.stopAndClose()
                    synchronized(lock) {
                        if (activeCapture === capture) {
                            activeCapture = null
                        }
                    }
                }
            }

        synchronized(lock) {
            if (activeCapture === capture) {
                capture.readJob = job
            } else {
                job.cancel()
                line.stopAndClose()
            }
        }

        return VoiceDictationAudioStartResult.Started
    }

    override suspend fun stop() {
        val previous =
            synchronized(lock) {
                activeCapture.also { activeCapture = null }
            } ?: return
        previous.line.stopAndClose()
        previous.readJob?.cancel()
        runCatching { previous.readJob?.join() }
    }

    private fun isCurrentCapture(capture: ActiveCapture): Boolean =
        synchronized(lock) { activeCapture === capture }

    companion object {
        @Volatile
        private var installed = false

        fun install() {
            synchronized(this) {
                if (installed) return
                installed = true
            }
            RaftVoiceDictationAudioRecorderRegistry.register(DesktopVoiceDictationAudioRecorder())
        }
    }
}

/** Public startup hook for `:desktopApp` (avoids requiring `@RaftCoreInternalApi` in desktop `Main`). */
object RaftDesktopVoiceDictationRecorderHost {
    fun install() {
        DesktopVoiceDictationAudioRecorder.install()
    }
}
