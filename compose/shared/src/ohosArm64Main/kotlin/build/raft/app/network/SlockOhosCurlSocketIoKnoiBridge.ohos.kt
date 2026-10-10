package build.raft.app.network

import com.tencent.tmm.knoi.annotation.KNExport
import com.tencent.tmm.knoi.annotation.ServiceConsumer
import com.tencent.tmm.knoi.service.KNOI
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Task #154: NetworkKMM's curl Socket.IO client for the OHOS realtime host (ArkTS
 * `OhosCurlSocketIoClient`). ArkTS opens a session by handle; the session's callbacks come back on the
 * native connection thread through the `slockOhosCurlSocketIoObserver` KNOI service, tagged with the
 * handle. OHOS curl uses the platform trust store and a direct connection like the OHOS curl HTTP
 * path; DoH fallback providers on VBTransportCurl are tried only after a system-resolver failure.
 */
private const val SlockOhosCurlSocketIoObserverService = "slockOhosCurlSocketIoObserver"

@ServiceConsumer(name = SlockOhosCurlSocketIoObserverService)
interface SlockOhosCurlSocketIoObserverSink {
    /** `{handle, state, code, detail}`; state is a [SlockCurlSocketIoState] name. */
    fun onState(payload: Map<String, Any?>)

    /** `{handle, eventName, payloadJson}`; payloadJson is "null" for an event without arguments. */
    fun onEvent(payload: Map<String, Any?>)
}

private object OhosCurlSocketIoSessions {
    val lock = SynchronizedObject()
    val sessions = mutableMapOf<String, SlockCurlSocketIoSession>()
    var nextId = 0L

    // close() joins the native connection thread, which may be waiting to deliver a callback to the
    // ArkTS thread that called us, so it never runs on the caller's thread.
    val closeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

private val headersJson = Json { ignoreUnknownKeys = true }

/** Called once at startup: shows the Settings switch when this build has the curl Socket.IO client. */
@KNExport
fun slockOhosCurlSocketIoInstall(): Boolean {
    if (slockCurlSocketIoSupported) SlockCurlSocketIoPolicy.markPlatformSupported()
    return slockCurlSocketIoSupported
}

/** The per-connect decision (Settings switch, else the mobile_curl_socketio_v0 flag) and availability. */
@KNExport
fun slockOhosCurlSocketIoEnabledFor(serverId: String): Boolean =
    slockCurlSocketIoSupported && SlockCurlSocketIoPolicy.isEnabledFor(serverId)

/** Opens and starts a session; returns its handle, or "" when it could not be created or started. */
@KNExport
fun slockOhosCurlSocketIoOpen(serverUrl: String, authJson: String, headersJsonText: String): String {
    val handle = synchronized(OhosCurlSocketIoSessions.lock) {
        OhosCurlSocketIoSessions.nextId += 1
        "curl-sio-${OhosCurlSocketIoSessions.nextId}"
    }
    val session =
        createSlockCurlSocketIoSession(
            serverUrl = serverUrl,
            authJson = authJson,
            headers = parseHeaders(headersJsonText),
            caInfoPath = null,
            proxyUrl = "",
            listener = KnoiListener(handle),
        ) ?: return ""
    synchronized(OhosCurlSocketIoSessions.lock) { OhosCurlSocketIoSessions.sessions[handle] = session }
    if (!session.start()) {
        slockOhosCurlSocketIoClose(handle)
        return ""
    }
    return handle
}

@KNExport
fun slockOhosCurlSocketIoEmit(handle: String, eventName: String, payloadJson: String): Boolean {
    val session = synchronized(OhosCurlSocketIoSessions.lock) { OhosCurlSocketIoSessions.sessions[handle] }
    return session?.emit(eventName, payloadJson) ?: false
}

@KNExport
fun slockOhosCurlSocketIoClose(handle: String) {
    val session =
        synchronized(OhosCurlSocketIoSessions.lock) { OhosCurlSocketIoSessions.sessions.remove(handle) } ?: return
    OhosCurlSocketIoSessions.closeScope.launch { runCatching { session.close() } }
}

private fun parseHeaders(text: String): Map<String, String> =
    runCatching {
        (headersJson.parseToJsonElement(text) as? JsonObject)
            ?.mapNotNull { (key, value) -> (value as? JsonPrimitive)?.contentOrNull?.let { key to it } }
            ?.toMap()
    }.getOrNull().orEmpty()

private class KnoiListener(private val handle: String) : SlockCurlSocketIoListener {
    private val sink: SlockOhosCurlSocketIoObserverSink
        get() = KNOI.get(SlockOhosCurlSocketIoObserverService)

    override fun onState(state: SlockCurlSocketIoState, code: Int, detail: String) {
        runCatching {
            sink.onState(mapOf("handle" to handle, "state" to state.name, "code" to code, "detail" to detail))
        }
    }

    override fun onEvent(eventName: String, payloadJson: String) {
        runCatching {
            sink.onEvent(mapOf("handle" to handle, "eventName" to eventName, "payloadJson" to payloadJson))
        }
    }
}
