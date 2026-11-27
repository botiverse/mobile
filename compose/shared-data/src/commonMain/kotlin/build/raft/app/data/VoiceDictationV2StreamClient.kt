package build.raft.app.data

import build.raft.app.RaftCoreInternalApi
import build.raft.app.network.newRaftTraceIds
import build.raft.app.network.raftValidTraceIdOrNull
import build.raft.realtime.socket.RaftWebSocketConnector
import build.raft.realtime.socket.RaftWebSocketFrame
import build.raft.realtime.socket.RaftWebSocketSession
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

// Composer streaming voice dictation — thin-client protocol v2 WebSocket stream client (`#wg-mobile-sync:#334`).
// Authoritative contract: `botiverse/slock/docs/contracts/voice-dictation-v2.md` §Client ↔ holt-live WebSocket.
//
// Strictly v2 only:
// - Native auth uses `Authorization: Bearer <permit>` header (NEVER `?permit=` in query string).
// - Closed-set client frames: `start`, raw PCM16 LE mono binary frames (strictly between `start` and `stop`), `stop`.
// - Closed-set server frames: `ready`, `text`, `done`, `error`.

/** Default PCM16 mono sample rate requested from mobile microphones. */
@RaftCoreInternalApi
const val VOICE_DICTATION_DEFAULT_SAMPLE_RATE_HZ: Int = 16_000

/** Minimum PCM16 sample rate accepted by `holt-live` `start.sample_rate`. */
@RaftCoreInternalApi
const val VOICE_DICTATION_MIN_SAMPLE_RATE_HZ: Int = 8_000

/** Maximum PCM16 sample rate accepted by `holt-live` `start.sample_rate`. */
@RaftCoreInternalApi
const val VOICE_DICTATION_MAX_SAMPLE_RATE_HZ: Int = 48_000

private val voiceDictationStreamJson = Json { ignoreUnknownKeys = true }

/**
 * Typed error code carried by a v2 `{ "deck": "error", "code": ... }` frame.
 * Clients must always branch on [VoiceDictationV2ErrorCode], never diagnostic English `message`.
 */
@RaftCoreInternalApi
sealed interface VoiceDictationV2ErrorCode {
    val wireCode: String

    data object InvalidPermit : VoiceDictationV2ErrorCode {
        override val wireCode: String = "invalid_permit"
    }

    data object SessionLimitReached : VoiceDictationV2ErrorCode {
        override val wireCode: String = "session_limit_reached"
    }

    data object QuotaExceeded : VoiceDictationV2ErrorCode {
        override val wireCode: String = "quota_exceeded"
    }

    data object Busy : VoiceDictationV2ErrorCode {
        override val wireCode: String = "busy"
    }

    data object Unavailable : VoiceDictationV2ErrorCode {
        override val wireCode: String = "unavailable"
    }

    data object ProtocolError : VoiceDictationV2ErrorCode {
        override val wireCode: String = "protocol_error"
    }

    data class Unknown(val raw: String) : VoiceDictationV2ErrorCode {
        override val wireCode: String get() = raw
    }

    companion object {
        fun fromWire(raw: String?): VoiceDictationV2ErrorCode =
            when (raw?.trim()) {
                InvalidPermit.wireCode -> InvalidPermit
                SessionLimitReached.wireCode -> SessionLimitReached
                QuotaExceeded.wireCode -> QuotaExceeded
                Busy.wireCode -> Busy
                Unavailable.wireCode -> Unavailable
                ProtocolError.wireCode -> ProtocolError
                else -> Unknown(raw.orEmpty())
            }
    }
}

/** Closed set of 4 server → client frames on a v2 dictation WebSocket. */
@RaftCoreInternalApi
sealed interface VoiceDictationV2ServerFrame {
    /** `{ "deck": "ready" }` — stream accepted `start`; audio may flow. */
    data object Ready : VoiceDictationV2ServerFrame

    /**
     * `{ "deck": "text", "text": string, "stable_len"?: int, "refined"?: true }` — complete
     * session transcript snapshot with [stableLen] clamped into `0..text.length`.
     */
    data class Text(
        val text: String,
        val stableLen: Int,
        val refined: Boolean,
    ) : VoiceDictationV2ServerFrame

    /** `{ "deck": "done" }` — emitted once after `stop` flush + refine finishes cleanly. */
    data object Done : VoiceDictationV2ServerFrame

    /** `{ "deck": "error", "code": string, "message"?: string }` — terminal server error. */
    data class Error(
        val code: VoiceDictationV2ErrorCode,
        val message: String? = null,
    ) : VoiceDictationV2ServerFrame
}

/** Stream events emitted by [VoiceDictationV2StreamConnection.events]. */
@RaftCoreInternalApi
sealed interface VoiceDictationV2StreamEvent {
    data class Frame(val frame: VoiceDictationV2ServerFrame) : VoiceDictationV2StreamEvent

    /**
     * Emitted once when the underlying WebSocket ends. [unexpected] is `true` when the socket
     * dropped before `done`, before a server `error` frame, and without a local [VoiceDictationV2StreamConnection.close].
     */
    data class Closed(
        val unexpected: Boolean,
        val causeMessage: String? = null,
    ) : VoiceDictationV2StreamEvent
}

/**
 * Encodes the strict v2 client `start` frame:
 * `{ "deck": "start", "v": 2, "sample_rate": <sampleRateHz> }`.
 * Carries zero forbidden fields (`permit`, `context`, `hotwords`, `language`, `refine`).
 */
@RaftCoreInternalApi
fun buildVoiceDictationV2StartFrame(sampleRateHz: Int): String {
    require(sampleRateHz in VOICE_DICTATION_MIN_SAMPLE_RATE_HZ..VOICE_DICTATION_MAX_SAMPLE_RATE_HZ) {
        "Voice dictation sampleRateHz must be in $VOICE_DICTATION_MIN_SAMPLE_RATE_HZ..$VOICE_DICTATION_MAX_SAMPLE_RATE_HZ, got $sampleRateHz"
    }
    return buildJsonObject {
        put("deck", "start")
        put("v", VOICE_DICTATION_PROTOCOL_VERSION)
        put("sample_rate", sampleRateHz)
    }.toString()
}

/** Encodes the strict v2 client `stop` frame: `{ "deck": "stop" }`. */
@RaftCoreInternalApi
fun buildVoiceDictationV2StopFrame(): String =
    buildJsonObject {
        put("deck", "stop")
    }.toString()

/**
 * Decodes one of the 4 closed-set v2 server frames (`ready`, `text`, `done`, `error`), returning
 * `null` (without throwing) for unknown, v1-only, or malformed frames.
 */
@RaftCoreInternalApi
fun parseVoiceDictationV2ServerFrame(rawJson: String): VoiceDictationV2ServerFrame? {
    val root = runCatching { voiceDictationStreamJson.parseToJsonElement(rawJson) }.getOrNull() as? JsonObject ?: return null
    val deck = (root["deck"] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull ?: return null
    return when (deck) {
        "ready" -> VoiceDictationV2ServerFrame.Ready
        "text" -> {
            val textPrimitive = root["text"] as? JsonPrimitive ?: return null
            if (!textPrimitive.isString) return null
            val text = textPrimitive.content
            val rawStableLen =
                (root["stable_len"] as? JsonPrimitive)
                    ?.takeUnless(JsonPrimitive::isString)
                    ?.intOrNull
            val stableLen = (rawStableLen ?: text.length).coerceIn(0, text.length)
            val refined =
                (root["refined"] as? JsonPrimitive)
                    ?.takeUnless(JsonPrimitive::isString)
                    ?.booleanOrNull == true
            VoiceDictationV2ServerFrame.Text(
                text = text,
                stableLen = stableLen,
                refined = refined,
            )
        }
        "done" -> VoiceDictationV2ServerFrame.Done
        "error" -> {
            val codePrimitive = root["code"] as? JsonPrimitive ?: return null
            if (!codePrimitive.isString) return null
            val message = (root["message"] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull
            VoiceDictationV2ServerFrame.Error(
                code = VoiceDictationV2ErrorCode.fromWire(codePrimitive.content),
                message = message,
            )
        }
        else -> null
    }
}

/** Active v2 dictation WebSocket stream enforcing closed-set v2 framing and send ordering. */
@RaftCoreInternalApi
interface VoiceDictationV2StreamConnection {
    /** Cold flow reading decoded v2 server frames until closure. */
    val events: Flow<VoiceDictationV2StreamEvent>

    /** Sends `{ "deck": "start", "v": 2, "sample_rate": sampleRateHz }` once. */
    suspend fun sendStart(sampleRateHz: Int): Boolean

    /** Sends raw PCM16 LE mono audio strictly after [sendStart] and before [sendStop]. */
    suspend fun sendAudio(pcm16LeMonoBytes: ByteArray): Boolean

    /** Sends `{ "deck": "stop" }` once (only if [sendStart] already succeeded). */
    suspend fun sendStop(): Boolean

    /** Closes the WebSocket (idempotent). */
    suspend fun close(
        code: Int = RaftWebSocketSession.NORMAL_CLOSURE,
        reason: String = "",
    )
}

private val voiceDictationSpanIdPattern = Regex("^[0-9a-f]{16}$")
private val voiceDictationAllZeroSpanPattern = Regex("^0+$")

/**
 * Builds the native `/v1/stream` WebSocket URL, optionally attaching `?tp=00-<traceId>-<spanId>-01`
 * for W3C trace stitching (`docs/contracts/voice-dictation-v2.md`, `slock#17246`). Never appends
 * `?permit=` (native auth always goes via `Authorization: Bearer <permit>` header).
 */
@RaftCoreInternalApi
fun buildVoiceDictationV2StreamUrl(
    baseUrl: String,
    traceId: String? = null,
    spanId: String? = null,
): String {
    require(isValidVoiceDictationStreamUrl(baseUrl)) { "Invalid voice dictation stream URL" }
    val validTraceId = raftValidTraceIdOrNull(traceId) ?: return baseUrl
    val normalizedSpanId =
        spanId
            ?.trim()
            ?.lowercase()
            ?.takeIf { voiceDictationSpanIdPattern.matches(it) && !voiceDictationAllZeroSpanPattern.matches(it) }
            ?: newRaftTraceIds().spanId
    val traceparent = "00-$validTraceId-$normalizedSpanId-01"
    val hashIndex = baseUrl.indexOf('#')
    val beforeHash = if (hashIndex >= 0) baseUrl.substring(0, hashIndex) else baseUrl
    val fragment = if (hashIndex >= 0) baseUrl.substring(hashIndex) else ""
    val separator = if ('?' in beforeHash) '&' else '?'
    return "$beforeHash${separator}tp=$traceparent$fragment"
}

/** Connector seam for opening a v2 dictation stream. */
@RaftCoreInternalApi
interface VoiceDictationV2StreamConnector {
    suspend fun connect(url: String, permit: String): VoiceDictationV2StreamConnection

    suspend fun connect(
        url: String,
        permit: String,
        traceId: String?,
    ): VoiceDictationV2StreamConnection = connect(url = url, permit = permit)
}

/**
 * Thin WebSocket client over [RaftWebSocketConnector].
 * Authenticates via `Authorization: Bearer <permit>` header and NEVER places permits in the URL.
 */
@RaftCoreInternalApi
class VoiceDictationV2StreamClient(
    private val webSocketConnector: RaftWebSocketConnector,
) : VoiceDictationV2StreamConnector {
    override suspend fun connect(url: String, permit: String): VoiceDictationV2StreamConnection =
        connect(url = url, permit = permit, traceId = null)

    override suspend fun connect(
        url: String,
        permit: String,
        traceId: String?,
    ): VoiceDictationV2StreamConnection {
        require(isValidVoiceDictationStreamUrl(url)) { "Invalid voice dictation stream URL" }
        require(permit.isNotBlank() && permit.none(Char::isWhitespace)) { "Invalid voice dictation permit" }
        val resolvedUrl = buildVoiceDictationV2StreamUrl(baseUrl = url, traceId = traceId)
        val session =
            webSocketConnector.connect(
                url = resolvedUrl,
                headers = mapOf("Authorization" to "Bearer $permit"),
            )
        return RaftVoiceDictationV2StreamConnection(session)
    }
}

private class RaftVoiceDictationV2StreamConnection(
    private val session: RaftWebSocketSession,
) : VoiceDictationV2StreamConnection {
    private val sendMutex = Mutex()
    private val flagLock = SynchronizedObject()

    // Protected by [sendMutex].
    private var started: Boolean = false
    private var stopped: Boolean = false

    // Protected by [flagLock] so [close] can flip immediately even if a send is blocked.
    private var localClosed: Boolean = false
    private var sawTerminalFrame: Boolean = false

    override val events: Flow<VoiceDictationV2StreamEvent> =
        flow {
            var closeCause: Throwable? = null
            try {
                for (rawFrame in session.incoming) {
                    when (rawFrame) {
                        is RaftWebSocketFrame.Text -> {
                            val parsed = parseVoiceDictationV2ServerFrame(rawFrame.text) ?: continue
                            if (parsed is VoiceDictationV2ServerFrame.Done || parsed is VoiceDictationV2ServerFrame.Error) {
                                synchronized(flagLock) { sawTerminalFrame = true }
                            }
                            emit(VoiceDictationV2StreamEvent.Frame(parsed))
                        }
                        is RaftWebSocketFrame.Binary -> Unit
                    }
                }
            } catch (cancellation: CancellationException) {
                currentCoroutineContext().ensureActive()
                closeCause = cancellation
            } catch (error: Throwable) {
                closeCause = error
            }
            val expected = synchronized(flagLock) { localClosed || sawTerminalFrame }
            emit(
                VoiceDictationV2StreamEvent.Closed(
                    unexpected = !expected,
                    causeMessage = closeCause?.message,
                ),
            )
        }

    override suspend fun sendStart(sampleRateHz: Int): Boolean {
        val frame = buildVoiceDictationV2StartFrame(sampleRateHz)
        return sendMutex.withLock {
            if (started || stopped || isClosed()) return@withLock false
            try {
                session.send(frame)
                started = true
                true
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                false
            }
        }
    }

    override suspend fun sendAudio(pcm16LeMonoBytes: ByteArray): Boolean {
        if (pcm16LeMonoBytes.isEmpty()) return false
        return sendMutex.withLock {
            if (!started || stopped || isClosed()) return@withLock false
            try {
                session.sendBinary(pcm16LeMonoBytes)
                true
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                false
            }
        }
    }

    override suspend fun sendStop(): Boolean =
        sendMutex.withLock {
            if (stopped || isClosed()) return@withLock false
            stopped = true
            if (!started) return@withLock false
            try {
                session.send(buildVoiceDictationV2StopFrame())
                true
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                false
            }
        }

    override suspend fun close(code: Int, reason: String) {
        val shouldClose =
            synchronized(flagLock) {
                if (localClosed) {
                    false
                } else {
                    localClosed = true
                    true
                }
            }
        if (shouldClose) {
            withContext(NonCancellable) {
                runCatching { session.close(code = code, reason = reason) }
            }
        }
    }

    private fun isClosed(): Boolean = synchronized(flagLock) { localClosed }
}
