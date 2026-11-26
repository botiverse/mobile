@file:OptIn(RaftCoreInternalApi::class)

package build.raft.app

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import build.raft.app.core.data.diagnostics.RaftAppLog
import build.raft.app.data.RaftVoiceDictationAudioRecorder
import build.raft.app.data.RaftVoiceDictationAudioRecorderRegistry
import build.raft.app.data.VOICE_DICTATION_DEFAULT_SAMPLE_RATE_HZ
import build.raft.app.data.VOICE_DICTATION_PCM16_BYTES_PER_SAMPLE
import build.raft.app.data.VoiceDictationAudioStartResult
import build.raft.app.data.computeVoiceDictationPcm16LeLevel
import build.raft.app.data.smoothVoiceDictationAudioLevel
import build.raft.app.data.voiceDictationChunkByteSize
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun interface AndroidVoiceDictationPermissionRequester {
    suspend fun requestRecordAudioPermission(): AndroidRecordAudioPermissionOutcome
}

internal interface AndroidVoiceDictationPcmSource {
    fun startRecording(): Boolean
    fun read(buffer: ByteArray, offset: Int, size: Int): Int
    fun stopAndRelease()
}

internal fun interface AndroidVoiceDictationPcmSourceFactory {
    fun create(sampleRateHz: Int, chunkBytes: Int): AndroidVoiceDictationPcmSource?
}

private class AudioRecordPcmSource(
    private val audioRecord: AudioRecord,
) : AndroidVoiceDictationPcmSource {
    private val released = AtomicBoolean(false)

    override fun startRecording(): Boolean =
        runCatching {
            audioRecord.startRecording()
            audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING
        }.getOrElse { false }

    override fun read(buffer: ByteArray, offset: Int, size: Int): Int =
        audioRecord.read(buffer, offset, size)

    override fun stopAndRelease() {
        if (!released.compareAndSet(false, true)) return
        runCatching {
            if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord.stop()
            }
        }
        runCatching { audioRecord.release() }
    }
}

private object DefaultAndroidVoiceDictationPcmSourceFactory : AndroidVoiceDictationPcmSourceFactory {
    @SuppressLint("MissingPermission")
    override fun create(sampleRateHz: Int, chunkBytes: Int): AndroidVoiceDictationPcmSource? {
        val minBufferBytes =
            AudioRecord.getMinBufferSize(
                sampleRateHz,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
        if (minBufferBytes <= 0) return null
        val bufferSize = maxOf(minBufferBytes * 2, chunkBytes * 4)
        val sources =
            intArrayOf(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC,
            )
        for (audioSource in sources) {
            val candidate =
                try {
                    AudioRecord(
                        audioSource,
                        sampleRateHz,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferSize,
                    )
                } catch (security: SecurityException) {
                    throw security
                } catch (_: Throwable) {
                    null
                }
            if (candidate != null && candidate.state == AudioRecord.STATE_INITIALIZED) {
                return AudioRecordPcmSource(candidate)
            }
            runCatching { candidate?.release() }
        }
        return null
    }
}

/**
 * Native Android PCM16 16 kHz mono microphone recorder for thin-client v2 composer voice dictation
 * (`#wg-mobile-sync:#334`), registered into [RaftVoiceDictationAudioRecorderRegistry] at app launch.
 */
class AndroidVoiceDictationAudioRecorder internal constructor(
    private val permissionRequester: AndroidVoiceDictationPermissionRequester,
    private val sourceFactory: AndroidVoiceDictationPcmSourceFactory = DefaultAndroidVoiceDictationPcmSourceFactory,
    private val captureDispatcher: CoroutineDispatcher = Dispatchers.IO,
    override val sampleRateHz: Int = VOICE_DICTATION_DEFAULT_SAMPLE_RATE_HZ,
    private val logWarning: (String) -> Unit = { message -> RaftAppLog.w(LOG_TAG, message) },
) : RaftVoiceDictationAudioRecorder {

    constructor(appContext: Context) : this(
        permissionRequester = AndroidVoiceDictationPermissionRequester {
            AndroidRecordAudioPermissionBridge.requestRecordAudioPermissionOutcome(appContext.applicationContext)
        },
        sourceFactory = DefaultAndroidVoiceDictationPcmSourceFactory,
        captureDispatcher = Dispatchers.IO,
    )

    private class ActiveCapture(
        val source: AndroidVoiceDictationPcmSource,
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

        val permissionOutcome =
            try {
                permissionRequester.requestRecordAudioPermission()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                logWarning("dictation_permission_error error=${error.javaClass.simpleName}")
                return VoiceDictationAudioStartResult.Failed(error.message)
            }

        when (permissionOutcome) {
            AndroidRecordAudioPermissionOutcome.Granted -> Unit
            AndroidRecordAudioPermissionOutcome.Denied -> return VoiceDictationAudioStartResult.PermissionDenied
            AndroidRecordAudioPermissionOutcome.NoForegroundActivity -> return VoiceDictationAudioStartResult.DeviceUnavailable
        }

        val chunkBytes = voiceDictationChunkByteSize(sampleRateHz = sampleRateHz)
        val source =
            try {
                withContext(captureDispatcher) {
                    val created = sourceFactory.create(sampleRateHz, chunkBytes) ?: return@withContext null
                    val started =
                        try {
                            created.startRecording()
                        } catch (error: Throwable) {
                            runCatching { created.stopAndRelease() }
                            throw error
                        }
                    if (!started) {
                        runCatching { created.stopAndRelease() }
                        return@withContext null
                    }
                    created
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SecurityException) {
                return VoiceDictationAudioStartResult.PermissionDenied
            } catch (error: Throwable) {
                logWarning("dictation_audio_start_failed error=${error.javaClass.simpleName}")
                return VoiceDictationAudioStartResult.Failed(error.message)
            }

        if (source == null) {
            return VoiceDictationAudioStartResult.DeviceUnavailable
        }

        val capture = ActiveCapture(source = source)
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
                                source.read(chunkBuffer, filled, chunkBytes - filled)
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
                    source.stopAndRelease()
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
                source.stopAndRelease()
            }
        }

        return VoiceDictationAudioStartResult.Started
    }

    override suspend fun stop() {
        val previous =
            synchronized(lock) {
                activeCapture.also { activeCapture = null }
            } ?: return
        previous.source.stopAndRelease()
        previous.readJob?.cancel()
        runCatching { previous.readJob?.join() }
    }

    private fun isCurrentCapture(capture: ActiveCapture): Boolean =
        synchronized(lock) { activeCapture === capture }

    companion object {
        private const val LOG_TAG = "RaftVoiceDictation"

        @Volatile
        private var installed = false

        fun install(application: Application) {
            synchronized(this) {
                if (installed) return
                installed = true
            }
            AndroidRecordAudioPermissionBridge.install(application)
            RaftVoiceDictationAudioRecorderRegistry.register(
                AndroidVoiceDictationAudioRecorder(application.applicationContext),
            )
        }
    }
}
