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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Testable PCM16 mono audio line seam for Desktop JVM capture. */
@RaftCoreInternalApi
interface DesktopVoiceDictationPcmLine {
    fun openAndStart(bufferBytes: Int)
    fun read(buffer: ByteArray, offset: Int, length: Int): Int
    fun stopRecording() = stopAndClose()
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
    private val stateLock = Any()
    private var stopped = false
    private var closed = false

    override fun openAndStart(bufferBytes: Int) {
        line.open(format, bufferBytes)
        line.start()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        line.read(buffer, offset, length)

    override fun stopRecording() {
        synchronized(stateLock) {
            if (closed || stopped) return
            stopped = true
            // Stop capture without flush()/close() so an in-flight read() returns buffered tail bytes.
            runCatching { line.stop() }
        }
    }

    override fun stopAndClose() {
        synchronized(stateLock) {
            if (closed) return
            if (!stopped) {
                stopped = true
                runCatching { line.stop() }
            }
            closed = true
            runCatching { line.flush() }
            runCatching { line.close() }
        }
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
    private val stopJoinTimeoutMs: Long = DEFAULT_STOP_JOIN_TIMEOUT_MS,
) : RaftVoiceDictationAudioRecorder {
    private class ActiveCapture(
        val line: DesktopVoiceDictationPcmLine,
        val stopRequested: AtomicBoolean = AtomicBoolean(false),
        val discardTail: AtomicBoolean = AtomicBoolean(false),
        var readJob: Job? = null,
    )

    private val lock = Any()
    private val captureScope = CoroutineScope(SupervisorJob() + captureDispatcher)
    private var activeCapture: ActiveCapture? = null

    override suspend fun start(
        onPcmChunk: (ByteArray) -> Unit,
        onAudioLevel: (Float) -> Unit,
    ): VoiceDictationAudioStartResult {
        stopCapture(discardTail = true)

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
        val job =
            captureScope.launch(start = CoroutineStart.LAZY) {
                val chunkBuffer = ByteArray(chunkBytes)
                var filled = 0
                val audioLevelMeter = VoiceDictationAudioLevelMeter()
                try {
                    while (isActive && !capture.stopRequested.get()) {
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
                            if (capture.discardTail.get()) break
                            val visualLevel = audioLevelMeter.processPcm16Le(emit)
                            onAudioLevel(visualLevel)
                            onPcmChunk(emit)
                        }
                    }
                    val remainingEven = filled - (filled % VOICE_DICTATION_PCM16_BYTES_PER_SAMPLE)
                    if (remainingEven > 0 && !capture.discardTail.get()) {
                        val tail = chunkBuffer.copyOf(remainingEven)
                        val visualLevel = audioLevelMeter.processPcm16Le(tail)
                        onAudioLevel(visualLevel)
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
            capture.readJob = job
            activeCapture = capture
        }
        job.start()

        return VoiceDictationAudioStartResult.Started
    }

    override suspend fun stop() {
        stopCapture(discardTail = false)
    }

    private suspend fun stopCapture(discardTail: Boolean) {
        val previous =
            synchronized(lock) {
                activeCapture.also { activeCapture = null }
            } ?: return
        if (discardTail) {
            previous.discardTail.set(true)
        }
        previous.stopRequested.set(true)
        val joinTimeout = stopJoinTimeoutMs.coerceAtLeast(1L)
        if (discardTail) {
            previous.line.stopAndClose()
            previous.readJob?.cancel()
            runCatching { withTimeoutOrNull(joinTimeout) { previous.readJob?.join() } }
            return
        }
        previous.line.stopRecording()
        val joined =
            withTimeoutOrNull(joinTimeout) {
                previous.readJob?.join()
                true
            } == true
        if (!joined) {
            previous.discardTail.set(true)
            previous.line.stopAndClose()
            previous.readJob?.cancel()
            runCatching { withTimeoutOrNull(STOP_FALLBACK_JOIN_TIMEOUT_MS) { previous.readJob?.join() } }
        }
    }

    companion object {
        private const val DEFAULT_STOP_JOIN_TIMEOUT_MS = 500L
        private const val STOP_FALLBACK_JOIN_TIMEOUT_MS = 250L

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
