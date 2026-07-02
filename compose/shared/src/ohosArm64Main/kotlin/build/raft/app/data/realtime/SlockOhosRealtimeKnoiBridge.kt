package build.raft.app.data.realtime

import com.tencent.tmm.knoi.annotation.KNExport
import com.tencent.tmm.knoi.annotation.ServiceConsumer
import com.tencent.tmm.knoi.service.KNOI
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

private val slockOhosRealtimeKnoiEngine = SlockRealtimeAppScopeEngine()
private val slockOhosRealtimeKnoiSharedTransport = SlockOhosRealtimeKnoiSharedTransport()

@ServiceConsumer(name = "slockOhosRealtimeSharedTransportSink")
interface SlockOhosRealtimeSharedTransportSink {
    fun onConnected()
    fun onDisconnected(reason: String)
    fun onConnectError(message: String)
    fun onEvent(eventName: String, rawPayload: String)
}

@KNExport
fun slockRealtimeKnoiConnectDecision(
    serverId: String,
    accessToken: String,
    requestedLastSeq: Long,
    forceReconnect: Boolean,
    connectionStateName: String
): Map<String, Any?> {
    val decision =
        slockOhosRealtimeKnoiEngine.connectDecisionForHost(
            serverIdValue = serverId,
            accessToken = accessToken,
            requestedLastSeq = requestedLastSeq,
            forceReconnect = forceReconnect,
            connectionStateName = connectionStateName
        )
    return decision.toKnoiMap()
}

@KNExport
fun slockRealtimeKnoiDisconnectForSessionEnd(reason: String): Boolean =
    slockOhosRealtimeKnoiEngine.disconnectForSessionEnd(reason)

@KNExport
fun slockRealtimeKnoiRoomCommandDecision(action: String, channelId: String, reason: String): Map<String, Any?> {
    val decision =
        slockOhosRealtimeKnoiEngine.roomCommandDecisionForHost(
            action = action,
            channelIdValue = channelId,
            reason = reason
        )
    return decision.toKnoiMap()
}

@KNExport
fun slockRealtimeKnoiJoinChannelDecision(channelId: String, reason: String): Map<String, Any?> {
    val decision =
        slockOhosRealtimeKnoiEngine.joinChannelDecisionForHost(
            channelIdValue = channelId,
            reason = reason
        )
    return decision.toKnoiMap()
}

@KNExport
fun slockRealtimeKnoiRawDecision(eventName: String, rawPayload: String): Map<String, Any?> {
    val decision = slockOhosRealtimeKnoiEngine.onRawEvent(eventName, rawPayload)
    return decision.toKnoiMap()
}

@KNExport
fun slockRealtimeKnoiOnAppShown() {
    slockOhosRealtimeKnoiEngine.onAppShown()
}

@KNExport
fun slockRealtimeKnoiOnAppHidden() {
    slockOhosRealtimeKnoiEngine.onAppHidden()
}

@KNExport
fun slockRealtimeKnoiSharedTransportConnect(socketUrl: String, serverId: String, accessToken: String) {
    slockOhosRealtimeKnoiSharedTransport.connect(
        socketUrl = socketUrl,
        serverId = serverId,
        accessToken = accessToken
    )
}

@KNExport
fun slockRealtimeKnoiSharedTransportDisconnect() {
    slockOhosRealtimeKnoiSharedTransport.disconnect()
}

@KNExport
fun slockRealtimeKnoiSharedTransportEmit(eventName: String, payload: Map<String, Any>): Boolean =
    slockOhosRealtimeKnoiSharedTransport.emit(eventName, payload)

private fun SlockRealtimeAppScopeConnectDecision.toKnoiMap(): Map<String, Any?> =
    mapOf(
        "shouldConnect" to shouldConnect,
        "resumeSeq" to resumeSeq,
        "sessionChanged" to sessionChanged,
        "logMessage" to logMessage
    )

private fun SlockRealtimeAppScopeRoomCommandDecision.toKnoiMap(): Map<String, Any?> =
    mapOf(
        "shouldEmit" to shouldEmit,
        "eventName" to eventName,
        "payload" to payload,
        "channelId" to channelId?.value,
        "joinedChannelAction" to joinedChannelAction,
        "logMessage" to logMessage
    )

private fun SlockRealtimeAppScopeRawDecision.toKnoiMap(): Map<String, Any?> =
    mapOf(
        "shouldFanOut" to shouldFanOut,
        "serverId" to serverId?.value,
        "eventName" to eventName,
        "rawPayload" to rawPayload,
        "logMessage" to logMessage
    )

private class SlockOhosRealtimeKnoiSharedTransport {
    private var transport: SharedKtorSocketIoTransport? = null
    private val subscriptions = mutableListOf<build.raft.app.data.RealtimeSubscription>()

    fun connect(socketUrl: String, serverId: String, accessToken: String) {
        disconnect()
        val nextTransport =
            createSharedKtorSocketIoTransportForHost(
                socketUrl = socketUrl,
                headersProvider = {
                    mapOf(
                        "Authorization" to "Bearer $accessToken",
                        "X-Server-Id" to serverId
                    )
                },
                logger = { message -> log(message) }
            )
        transport = nextTransport
        installHandlers(nextTransport)
        nextTransport.connect(
            auth =
                mapOf(
                    "token" to accessToken,
                    "serverId" to serverId
                )
        )
    }

    fun disconnect() {
        subscriptions.forEach { it.close() }
        subscriptions.clear()
        transport?.disconnect()
        transport?.close()
        transport = null
    }

    fun emit(eventName: String, payload: Map<String, Any>): Boolean {
        val activeTransport = transport ?: return false
        activeTransport.emit(eventName, payload)
        return true
    }

    private fun installHandlers(activeTransport: SharedKtorSocketIoTransport) {
        subscriptions +=
            activeTransport.on(RealtimeSocketEvents.Connect) {
                sink()?.onConnected()
            }
        subscriptions +=
            activeTransport.on(RealtimeSocketEvents.Disconnect) { payload ->
                sink()?.onDisconnected(payload?.toString().orEmpty())
            }
        subscriptions +=
            activeTransport.on(RealtimeSocketEvents.ConnectError) { payload ->
                sink()?.onConnectError(payload?.toString().orEmpty())
            }
        RealtimeSocketEvents.ServerWireEvents.forEach { eventName ->
            subscriptions +=
                activeTransport.on(eventName) { payload ->
                    sink()?.onEvent(eventName, payload.toJsonPayloadString())
                }
        }
    }

    private fun sink(): SlockOhosRealtimeSharedTransportSink? =
        runCatching { KNOI.get<SlockOhosRealtimeSharedTransportSink>("slockOhosRealtimeSharedTransportSink") }
            .onFailure { error -> log("shared_transport_sink_missing message=${error.message.orEmpty()}") }
            .getOrNull()

    private fun log(message: String) {
        println("[SlockOhosRealtimeKnoi] $message")
    }

    private fun Any?.toJsonPayloadString(): String =
        when (this) {
            null -> "{}"
            is JsonElement -> SharedSocketIoProtocol.json.encodeToString(this)
            is Map<*, *> -> SharedSocketIoProtocol.json.encodeToString(toJsonElement())
            is Iterable<*> -> SharedSocketIoProtocol.json.encodeToString(toJsonElement())
            is Array<*> -> SharedSocketIoProtocol.json.encodeToString(toJsonElement())
            is String -> if (startsWith("{") || startsWith("[")) this else SharedSocketIoProtocol.json.encodeToString(this)
            else -> toString().takeIf { it.startsWith("{") || it.startsWith("[") } ?: "{}"
        }

    private fun Any?.toJsonElement(): JsonElement =
        when (this) {
            null -> kotlinx.serialization.json.JsonNull
            is JsonElement -> this
            is Map<*, *> ->
                JsonObject(
                    entries.associate { (key, value) ->
                        key.toString() to value.toJsonElement()
                    }
                )
            is Iterable<*> -> kotlinx.serialization.json.JsonArray(map { it.toJsonElement() })
            is Array<*> -> kotlinx.serialization.json.JsonArray(map { it.toJsonElement() })
            is String -> kotlinx.serialization.json.JsonPrimitive(this)
            is Number -> kotlinx.serialization.json.JsonPrimitive(this)
            is Boolean -> kotlinx.serialization.json.JsonPrimitive(this)
            else -> kotlinx.serialization.json.JsonPrimitive(toString())
        }
}
