@file:OptIn(RaftCoreInternalApi::class)

package build.raft.app

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import build.raft.app.core.data.diagnostics.RaftAppLog
import build.raft.app.data.RaftAgentVoiceCallEngine
import build.raft.app.data.RaftAgentVoiceCallEngineRegistry
import build.raft.app.data.RaftAgentVoiceDisconnectReason
import build.raft.app.data.RaftAgentVoiceEngineListener
import build.raft.app.data.RaftAgentVoiceEngineState
import build.raft.app.shared.RaftActivityPageOpens
import com.twilio.audioswitch.AudioDevice
import io.livekit.android.AudioOptions
import io.livekit.android.ConnectOptions
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.audio.AudioSwitchHandler
import io.livekit.android.events.DisconnectReason
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.RemoteParticipant
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

internal const val RAFT_AGENT_VOICE_CAPTION_TOPIC = "caption"
internal const val RAFT_AGENT_VOICE_MIC_PERMISSION_DENIED = "permission_denied"
internal const val RAFT_AGENT_VOICE_MIC_UNAVAILABLE = "unavailable"

/**
 * Maps LiveKit Android's [DisconnectReason] to the shared [RaftAgentVoiceDisconnectReason] tokens.
 */
internal fun mapAndroidLiveKitDisconnectReason(reason: DisconnectReason?): String =
    when (reason) {
        DisconnectReason.CLIENT_INITIATED -> RaftAgentVoiceDisconnectReason.CLIENT_INITIATED
        DisconnectReason.ROOM_DELETED,
        DisconnectReason.PARTICIPANT_REMOVED,
        DisconnectReason.ROOM_CLOSED -> RaftAgentVoiceDisconnectReason.SERVER_CLOSED
        else -> RaftAgentVoiceDisconnectReason.FAILED
    }

/**
 * Extracts a UTF-8 JSON caption payload when [topic] is `"caption"` and [data] is non-empty.
 */
internal fun extractAndroidVoiceCaptionJsonOrNull(topic: String?, data: ByteArray): String? {
    if (topic != RAFT_AGENT_VOICE_CAPTION_TOPIC || data.isEmpty()) return null
    val decoded = runCatching { data.decodeToString() }.getOrNull() ?: return null
    return decoded.takeIf { it.isNotEmpty() }
}

/**
 * Returns true when at least one active speaker in the LiveKit room is a remote participant
 * (i.e. the voice agent rather than the local caller).
 */
internal fun hasRemoteActiveSpeaker(speakers: List<Participant>): Boolean =
    hasRemoteActiveSpeakerFlags(speakers.map { it is RemoteParticipant })

internal fun hasRemoteActiveSpeakerFlags(isRemoteFlags: List<Boolean>): Boolean =
    isRemoteFlags.any { it }

/**
 * Device routing priority list for [AudioSwitchHandler] based on whether speakerphone is on.
 * External headsets (Bluetooth / wired) stay ahead of the built-in transducer by default,
 * while speakerphone vs earpiece order follows [speakerOn].
 */
internal fun preferredAudioDeviceListForSpeaker(speakerOn: Boolean): List<Class<out AudioDevice>> =
    if (speakerOn) {
        listOf(
            AudioDevice.BluetoothHeadset::class.java,
            AudioDevice.WiredHeadset::class.java,
            AudioDevice.Speakerphone::class.java,
            AudioDevice.Earpiece::class.java,
        )
    } else {
        listOf(
            AudioDevice.BluetoothHeadset::class.java,
            AudioDevice.WiredHeadset::class.java,
            AudioDevice.Earpiece::class.java,
            AudioDevice.Speakerphone::class.java,
        )
    }

internal enum class AndroidRecordAudioPermissionOutcome {
    Granted,
    Denied,
    NoForegroundActivity,
}

internal fun interface AndroidRecordAudioPermissionRequester {
    suspend fun ensureRecordAudioPermission(): Boolean
}

internal interface AndroidVoiceRoomEventSink {
    fun onConnectionState(state: String)
    fun onAgentSpeaking(speaking: Boolean)
    fun onCaptionData(jsonText: String)
    fun onDisconnected(reason: String, detail: String?)
}

internal interface AndroidVoiceRoomHandle {
    suspend fun connect(
        url: String,
        token: String,
        speakerOn: Boolean,
        eventSink: AndroidVoiceRoomEventSink,
    )

    suspend fun setMicrophoneEnabled(enabled: Boolean): Boolean

    fun setSpeakerOn(speakerOn: Boolean)

    fun disconnectAndRelease()
}

internal fun interface AndroidVoiceRoomFactory {
    fun create(): AndroidVoiceRoomHandle
}

/**
 * Process-scoped bridge that tracks the foreground [Activity] and awaits runtime
 * [Manifest.permission.RECORD_AUDIO] results from [build.raft.app.shared.RaftCmpActivity]
 * and [MainActivity].
 */
internal object AndroidRecordAudioPermissionBridge {
    private const val LOG_TAG = "RaftAgentVoice"
    const val REQUEST_RECORD_AUDIO_CODE = 4208

    private val lock = Any()
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    @Volatile
    private var installed = false

    @Volatile
    private var resumedActivity: WeakReference<Activity>? = null

    private var pendingRequest: CompletableDeferred<Boolean>? = null
    private var pendingActivity: WeakReference<Activity>? = null

    fun install(application: Application) {
        synchronized(this) {
            if (installed) return
            installed = true
        }
        application.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) {
                    resumedActivity = WeakReference(activity)
                }

                override fun onActivityPaused(activity: Activity) {
                    if (resumedActivity?.get() === activity) {
                        resumedActivity = null
                    }
                }

                override fun onActivityDestroyed(activity: Activity) {
                    val toFail =
                        synchronized(lock) {
                            if (pendingActivity?.get() === activity) {
                                pendingActivity = null
                                pendingRequest.also { pendingRequest = null }
                            } else {
                                null
                            }
                        }
                    toFail?.complete(false)
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            }
        )
    }

    /**
     * Delivers an Activity's permission callback to any pending voice-call request.
     * Returns true when [requestCode] was owned by this bridge.
     */
    fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ): Boolean {
        if (requestCode != REQUEST_RECORD_AUDIO_CODE) return false
        val audioIndex = permissions.indexOf(Manifest.permission.RECORD_AUDIO)
        val granted =
            audioIndex >= 0 &&
                audioIndex < grantResults.size &&
                grantResults[audioIndex] == PackageManager.PERMISSION_GRANTED
        val deferred =
            synchronized(lock) {
                pendingActivity = null
                pendingRequest.also { pendingRequest = null }
            }
        deferred?.complete(granted)
        return true
    }

    suspend fun ensureRecordAudioPermission(appContext: Context): Boolean =
        requestRecordAudioPermissionOutcome(appContext) == AndroidRecordAudioPermissionOutcome.Granted

    suspend fun requestRecordAudioPermissionOutcome(appContext: Context): AndroidRecordAudioPermissionOutcome {
        val granted =
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        if (granted) return AndroidRecordAudioPermissionOutcome.Granted

        val activity = currentForegroundActivity()
        if (activity == null) {
            RaftAppLog.w(LOG_TAG, "record_audio_permission_unavailable reason=no_foreground_activity")
            return AndroidRecordAudioPermissionOutcome.NoForegroundActivity
        }

        val deferred = CompletableDeferred<Boolean>()
        val previous =
            synchronized(lock) {
                val prior = pendingRequest
                pendingRequest = deferred
                pendingActivity = WeakReference(activity)
                prior
            }
        previous?.complete(false)

        mainHandler.post {
            if (activity.isFinishing || activity.isDestroyed) {
                val toFail =
                    synchronized(lock) {
                        if (pendingRequest === deferred) {
                            pendingActivity = null
                            pendingRequest = null
                            deferred
                        } else {
                            null
                        }
                    }
                toFail?.complete(false)
            } else {
                ActivityCompat.requestPermissions(
                    activity,
                    arrayOf(Manifest.permission.RECORD_AUDIO),
                    REQUEST_RECORD_AUDIO_CODE,
                )
            }
        }

        val approved =
            try {
                deferred.await()
            } catch (cancelled: CancellationException) {
                synchronized(lock) {
                    if (pendingRequest === deferred) {
                        pendingRequest = null
                        pendingActivity = null
                    }
                }
                throw cancelled
            }
        return if (approved) {
            AndroidRecordAudioPermissionOutcome.Granted
        } else {
            AndroidRecordAudioPermissionOutcome.Denied
        }
    }

    private fun currentForegroundActivity(): Activity? {
        resumedActivity?.get()?.takeUnless { it.isFinishing || it.isDestroyed }?.let { return it }
        val topTaskId = resumedActivity?.get()?.taskId
        if (topTaskId != null) {
            RaftActivityPageOpens.Process.topHost(topTaskId)
                ?.takeUnless { it.isFinishing || it.isDestroyed }
                ?.let { return it }
        }
        return null
    }
}

/**
 * Production [AndroidVoiceRoomHandle] backed by `io.livekit:livekit-android`.
 */
private class LiveKitAndroidVoiceRoomHandle(
    private val appContext: Context,
    private val eventDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : AndroidVoiceRoomHandle {
    private val lock = Any()
    private val released = AtomicBoolean(false)
    private var room: Room? = null
    private var audioHandler: AudioSwitchHandler? = null
    private var eventsJob: Job? = null
    private var eventsScope: CoroutineScope? = null

    override suspend fun connect(
        url: String,
        token: String,
        speakerOn: Boolean,
        eventSink: AndroidVoiceRoomEventSink,
    ) {
        val switchHandler =
            AudioSwitchHandler(appContext).apply {
                loggingEnabled = false
                preferredDeviceList = preferredAudioDeviceListForSpeaker(speakerOn)
            }
        val liveKitRoom =
            LiveKit.create(
                appContext = appContext,
                overrides = LiveKitOverrides(
                    audioOptions = AudioOptions(audioHandler = switchHandler)
                ),
            )
        val scope = CoroutineScope(SupervisorJob() + eventDispatcher)
        val collectorJob =
            scope.launch {
                liveKitRoom.events.collect { event ->
                    when (event) {
                        is RoomEvent.Connected ->
                            eventSink.onConnectionState(RaftAgentVoiceEngineState.CONNECTED)
                        is RoomEvent.Reconnecting ->
                            eventSink.onConnectionState(RaftAgentVoiceEngineState.RECONNECTING)
                        is RoomEvent.Reconnected ->
                            eventSink.onConnectionState(RaftAgentVoiceEngineState.CONNECTED)
                        is RoomEvent.ActiveSpeakersChanged ->
                            eventSink.onAgentSpeaking(hasRemoteActiveSpeaker(event.speakers))
                        is RoomEvent.DataReceived -> {
                            extractAndroidVoiceCaptionJsonOrNull(event.topic, event.data)?.let { json ->
                                eventSink.onCaptionData(json)
                            }
                        }
                        is RoomEvent.Disconnected -> {
                            val mapped = mapAndroidLiveKitDisconnectReason(event.reason)
                            val detail = event.error?.message ?: event.reason.name
                            eventSink.onDisconnected(mapped, detail)
                        }
                        is RoomEvent.FailedToConnect -> {
                            eventSink.onDisconnected(
                                RaftAgentVoiceDisconnectReason.FAILED,
                                event.error.message,
                            )
                        }
                        else -> Unit
                    }
                }
            }

        synchronized(lock) {
            if (released.get()) {
                collectorJob.cancel()
                runCatching { liveKitRoom.release() }
                runCatching { switchHandler.stop() }
                throw CancellationException("Voice room released before connect")
            }
            room = liveKitRoom
            audioHandler = switchHandler
            eventsScope = scope
            eventsJob = collectorJob
        }

        liveKitRoom.connect(
            url = url,
            token = token,
            options = ConnectOptions(
                autoSubscribe = true,
                audio = false,
                video = false,
            ),
        )
        applySpeakerRouting(switchHandler, speakerOn, explicitToggle = false)
    }

    override suspend fun setMicrophoneEnabled(enabled: Boolean): Boolean {
        val currentRoom = synchronized(lock) { room } ?: return false
        return currentRoom.localParticipant.setMicrophoneEnabled(enabled)
    }

    override fun setSpeakerOn(speakerOn: Boolean) {
        val handler = synchronized(lock) { audioHandler } ?: return
        applySpeakerRouting(handler, speakerOn, explicitToggle = true)
    }

    override fun disconnectAndRelease() {
        if (!released.compareAndSet(false, true)) return
        val roomToClose: Room?
        val handlerToStop: AudioSwitchHandler?
        val jobToCancel: Job?
        synchronized(lock) {
            roomToClose = room
            handlerToStop = audioHandler
            jobToCancel = eventsJob
            room = null
            audioHandler = null
            eventsJob = null
            eventsScope = null
        }
        jobToCancel?.cancel()
        runCatching { roomToClose?.disconnect() }
        runCatching { roomToClose?.release() }
        runCatching { handlerToStop?.stop() }
    }

    private fun applySpeakerRouting(
        handler: AudioSwitchHandler,
        speakerOn: Boolean,
        explicitToggle: Boolean,
    ) {
        handler.preferredDeviceList = preferredAudioDeviceListForSpeaker(speakerOn)
        if (!explicitToggle) return
        val available = handler.availableAudioDevices
        if (speakerOn) {
            val speaker = available.firstOrNull { it is AudioDevice.Speakerphone }
            handler.selectDevice(speaker)
        } else {
            val nonSpeaker =
                available.firstOrNull { it is AudioDevice.BluetoothHeadset }
                    ?: available.firstOrNull { it is AudioDevice.WiredHeadset }
                    ?: available.firstOrNull { it is AudioDevice.Earpiece }
            handler.selectDevice(nonSpeaker)
        }
    }
}

/**
 * Native Android LiveKit voice call engine registered into [RaftAgentVoiceCallEngineRegistry].
 */
class AndroidAgentVoiceCallEngine internal constructor(
    private val permissionRequester: AndroidRecordAudioPermissionRequester,
    private val roomFactory: AndroidVoiceRoomFactory,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val logWarning: (String) -> Unit = { message -> RaftAppLog.w(LOG_TAG, message) },
) : RaftAgentVoiceCallEngine {

    constructor(appContext: Context) : this(
        permissionRequester = AndroidRecordAudioPermissionRequester {
            AndroidRecordAudioPermissionBridge.ensureRecordAudioPermission(appContext.applicationContext)
        },
        roomFactory = AndroidVoiceRoomFactory {
            LiveKitAndroidVoiceRoomHandle(appContext.applicationContext)
        },
        dispatcher = Dispatchers.Default,
    )

    private class ActiveSession(
        val generation: Long,
        val callId: String,
        val url: String,
        val listener: RaftAgentVoiceEngineListener,
        @Volatile var latestToken: String,
        @Volatile var muted: Boolean,
        @Volatile var speakerOn: Boolean,
    ) {
        val terminalReported = AtomicBoolean(false)
        val micBlocked = AtomicBoolean(false)
        @Volatile var connected: Boolean = false
        @Volatile var room: AndroidVoiceRoomHandle? = null
        @Volatile var connectJob: Job? = null
        @Volatile var micJob: Job? = null
    }

    private val lock = Any()
    private val generationCounter = AtomicLong(0L)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var activeSession: ActiveSession? = null

    override val isSupported: Boolean = true

    internal val latestTokenForTests: String?
        get() = synchronized(lock) { activeSession?.latestToken }

    internal val isMutedForTests: Boolean
        get() = synchronized(lock) { activeSession?.muted == true }

    internal val isSpeakerOnForTests: Boolean
        get() = synchronized(lock) { activeSession?.speakerOn == true }

    override fun connect(
        callId: String,
        url: String,
        token: String,
        muted: Boolean,
        speakerOn: Boolean,
        listener: RaftAgentVoiceEngineListener,
    ) {
        val generation = generationCounter.incrementAndGet()
        val session =
            ActiveSession(
                generation = generation,
                callId = callId,
                url = url,
                listener = listener,
                latestToken = token,
                muted = muted,
                speakerOn = speakerOn,
            )
        val previous =
            synchronized(lock) {
                activeSession.also { activeSession = session }
            }
        teardownSession(previous)

        listener.onConnectionState(RaftAgentVoiceEngineState.CONNECTING)

        session.connectJob =
            scope.launch {
                val hasPermission =
                    try {
                        permissionRequester.ensureRecordAudioPermission()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        logWarning("record_audio_permission_error error=${error.javaClass.simpleName}")
                        false
                    }
                if (!isCurrentSession(session)) return@launch
                if (!hasPermission) {
                    session.listener.onMicError(RAFT_AGENT_VOICE_MIC_PERMISSION_DENIED)
                    if (isCurrentSession(session)) {
                        reportTerminalDisconnect(
                            session = session,
                            reason = RaftAgentVoiceDisconnectReason.FAILED,
                            detail = RAFT_AGENT_VOICE_MIC_PERMISSION_DENIED,
                        )
                    }
                    return@launch
                }

                val roomHandle = roomFactory.create()
                val attached =
                    synchronized(lock) {
                        if (activeSession === session) {
                            session.room = roomHandle
                            true
                        } else {
                            false
                        }
                    }
                if (!attached) {
                    roomHandle.disconnectAndRelease()
                    return@launch
                }

                val sink =
                    object : AndroidVoiceRoomEventSink {
                        override fun onConnectionState(state: String) {
                            if (!isCurrentSession(session)) return
                            if (state == RaftAgentVoiceEngineState.CONNECTED) {
                                session.connected = true
                            }
                            session.listener.onConnectionState(state)
                        }

                        override fun onAgentSpeaking(speaking: Boolean) {
                            if (!isCurrentSession(session)) return
                            session.listener.onAgentSpeaking(speaking)
                        }

                        override fun onCaptionData(jsonText: String) {
                            if (!isCurrentSession(session)) return
                            session.listener.onCaptionData(jsonText)
                        }

                        override fun onDisconnected(reason: String, detail: String?) {
                            reportTerminalDisconnect(session, reason, detail)
                        }
                    }

                try {
                    roomHandle.connect(
                        url = session.url,
                        token = session.latestToken,
                        speakerOn = session.speakerOn,
                        eventSink = sink,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    logWarning(
                        "voice_room_connect_failed callId=${session.callId} error=${error.javaClass.simpleName}",
                    )
                    reportTerminalDisconnect(
                        session = session,
                        reason = RaftAgentVoiceDisconnectReason.FAILED,
                        detail = error.message ?: error.javaClass.simpleName,
                    )
                    return@launch
                }

                if (!isCurrentSession(session)) {
                    roomHandle.disconnectAndRelease()
                    return@launch
                }

                session.connected = true
                // Apply the latest speaker and mute choices (including any toggled while connecting).
                roomHandle.setSpeakerOn(session.speakerOn)
                applyMicrophoneState(session, roomHandle, enabled = !session.muted)
            }
    }

    override fun setMuted(muted: Boolean) {
        val session = synchronized(lock) { activeSession } ?: return
        session.muted = muted
        val roomHandle = session.room ?: return
        if (!session.connected) return
        session.micJob?.cancel()
        session.micJob =
            scope.launch {
                if (!isCurrentSession(session)) return@launch
                applyMicrophoneState(session, roomHandle, enabled = !muted)
            }
    }

    override fun setSpeakerOn(speakerOn: Boolean) {
        val session = synchronized(lock) { activeSession } ?: return
        session.speakerOn = speakerOn
        session.room?.setSpeakerOn(speakerOn)
    }

    override fun updateToken(token: String) {
        if (token.isBlank()) return
        // LiveKit's SignalClient handles server-pushed REFRESH_TOKEN frames automatically for
        // live sessions; retain the latest caller-supplied token for any pending connect/rejoin.
        synchronized(lock) {
            activeSession?.latestToken = token
        }
    }

    override fun disconnect() {
        val previous =
            synchronized(lock) {
                activeSession.also { activeSession = null }
            }
        teardownSession(previous)
    }

    private suspend fun applyMicrophoneState(
        session: ActiveSession,
        roomHandle: AndroidVoiceRoomHandle,
        enabled: Boolean,
    ) {
        val success =
            try {
                roomHandle.setMicrophoneEnabled(enabled)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                logWarning("voice_mic_toggle_failed enabled=$enabled error=${error.javaClass.simpleName}")
                if (isCurrentSession(session)) {
                    session.micBlocked.set(true)
                    session.listener.onMicError(RAFT_AGENT_VOICE_MIC_UNAVAILABLE)
                }
                return
            }
        if (enabled && !success && isCurrentSession(session)) {
            session.micBlocked.set(true)
            session.listener.onMicError(RAFT_AGENT_VOICE_MIC_UNAVAILABLE)
        } else if (enabled && success && session.micBlocked.compareAndSet(true, false) && isCurrentSession(session)) {
            session.listener.onMicError("")
        }
    }

    private fun reportTerminalDisconnect(
        session: ActiveSession,
        reason: String,
        detail: String?,
    ) {
        if (!session.terminalReported.compareAndSet(false, true)) return
        val wasCurrent =
            synchronized(lock) {
                if (activeSession === session) {
                    activeSession = null
                    true
                } else {
                    false
                }
            }
        if (!wasCurrent) return
        teardownSession(session, markTerminal = false)
        session.listener.onDisconnected(reason, detail)
    }

    private fun teardownSession(session: ActiveSession?, markTerminal: Boolean = true) {
        if (session == null) return
        if (markTerminal) {
            session.terminalReported.set(true)
        }
        session.connected = false
        session.connectJob?.cancel()
        session.micJob?.cancel()
        val roomToClose = session.room
        session.room = null
        roomToClose?.disconnectAndRelease()
    }

    private fun isCurrentSession(session: ActiveSession): Boolean =
        synchronized(lock) { activeSession === session }

    companion object {
        private const val LOG_TAG = "RaftAgentVoice"

        @Volatile
        private var installed = false

        fun install(application: Application) {
            synchronized(this) {
                if (installed) return
                installed = true
            }
            AndroidRecordAudioPermissionBridge.install(application)
            RaftAgentVoiceCallEngineRegistry.register(AndroidAgentVoiceCallEngine(application.applicationContext))
        }
    }
}
