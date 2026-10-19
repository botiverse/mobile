package build.raft.app.data.realtime

import build.raft.app.SlockCoreInternalApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

@SlockCoreInternalApi
object SharedSocketIoProtocol {
    private const val SocketIoPath = "/socket.io/"
    private const val EngineIoOpen = "0"
    private const val EngineIoClose = "1"
    private const val EngineIoPing = "2"
    private const val EngineIoPong = "3"
    private const val SocketIoConnect = "40"
    private const val SocketIoDisconnect = "41"
    private const val SocketIoEvent = "42"
    private const val SocketIoConnectError = "44"

    val json: Json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = false
        }

    fun websocketUrl(socketUrl: String): String {
        val withoutTrailingSlash = socketUrl.removeSuffix("/")
        val wsBase =
            when {
                withoutTrailingSlash.startsWith("https://") -> "wss://" + withoutTrailingSlash.removePrefix("https://")
                withoutTrailingSlash.startsWith("http://") -> "ws://" + withoutTrailingSlash.removePrefix("http://")
                else -> withoutTrailingSlash
            }
        return "$wsBase$SocketIoPath?EIO=4&transport=websocket"
    }

    fun connectFrame(auth: Map<String, String>): String =
        SocketIoConnect + json.encodeToString(auth.toJsonObject())

    fun eventFrame(eventName: String, payload: Map<String, Any?>): String =
        SocketIoEvent + json.encodeToString(JsonArray(listOf(JsonPrimitive(eventName), socketIoPayload(eventName, payload))))

    fun pongFrame(): String = EngineIoPong

    fun decodeFrame(frame: String): SharedSocketIoDecodedFrame {
        if (frame.isEmpty()) return SharedSocketIoDecodedFrame.Empty
        if (frame.startsWith(EngineIoOpen)) {
            val payload = frame.substring(EngineIoOpen.length)
            val values = payload.decodeJsonObjectOrNull()
            return SharedSocketIoDecodedFrame.EngineOpen(
                pingInterval = values?.longValue("pingInterval"),
                pingTimeout = values?.longValue("pingTimeout")
            )
        }
        if (frame.startsWith(EngineIoClose)) return SharedSocketIoDecodedFrame.EngineClose
        if (frame == EngineIoPing) return SharedSocketIoDecodedFrame.EnginePing
        if (frame.startsWith(SocketIoConnect)) return SharedSocketIoDecodedFrame.SocketConnect
        if (frame.startsWith(SocketIoDisconnect)) return SharedSocketIoDecodedFrame.SocketDisconnect
        if (frame.startsWith(SocketIoConnectError)) {
            val message = frame.substring(SocketIoConnectError.length)
            return SharedSocketIoDecodedFrame.SocketConnectError(message)
        }
        if (frame.startsWith(SocketIoEvent)) {
            val values =
                runCatching {
                    json.parseToJsonElement(frame.substring(SocketIoEvent.length)).jsonArray
                }.getOrNull()
                    ?: return SharedSocketIoDecodedFrame.InvalidEvent
            val eventName = values.firstOrNull()?.jsonPrimitiveContentOrNull()?.takeIf { it.isNotBlank() }
                ?: return SharedSocketIoDecodedFrame.InvalidEvent
            return SharedSocketIoDecodedFrame.SocketEvent(
                eventName = eventName,
                payload = values.getOrNull(1) ?: JsonObject(emptyMap())
            )
        }
        return SharedSocketIoDecodedFrame.Unknown
    }

    private fun socketIoPayload(eventName: String, payload: Map<String, Any?>): JsonElement =
        if (eventName == RealtimeSocketEvents.JoinChannel || eventName == RealtimeSocketEvents.LeaveChannel) {
            (payload["channelId"] as? String)?.let(::JsonPrimitive) ?: payload.toJsonObject()
        } else {
            payload.toJsonObject()
        }

    private fun Map<String, Any?>.toJsonObject(): JsonObject =
        JsonObject(entries.associate { (key, value) -> key to value.toJsonElement() })

    private fun Any?.toJsonElement(): JsonElement =
        when (this) {
            null -> JsonNull
            is JsonElement -> this
            is Map<*, *> ->
                JsonObject(
                    entries.associate { (key, value) ->
                        key.toString() to value.toJsonElement()
                    }
                )
            is Iterable<*> -> JsonArray(map { it.toJsonElement() })
            is Array<*> -> JsonArray(map { it.toJsonElement() })
            is String -> JsonPrimitive(this)
            is Number -> JsonPrimitive(this)
            is Boolean -> JsonPrimitive(this)
            else -> JsonPrimitive(toString())
        }

    private fun String.decodeJsonObjectOrNull(): JsonObject? =
        runCatching { json.parseToJsonElement(this).jsonObject }.getOrNull()

    private fun JsonObject.longValue(key: String): Long? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

    private fun JsonElement.jsonPrimitiveContentOrNull(): String? =
        (this as? JsonPrimitive)?.contentOrNull
}

@SlockCoreInternalApi
sealed class SharedSocketIoDecodedFrame {
    data object Empty : SharedSocketIoDecodedFrame()
    data class EngineOpen(val pingInterval: Long?, val pingTimeout: Long?) : SharedSocketIoDecodedFrame()
    data object EngineClose : SharedSocketIoDecodedFrame()
    data object EnginePing : SharedSocketIoDecodedFrame()
    data object SocketConnect : SharedSocketIoDecodedFrame()
    data object SocketDisconnect : SharedSocketIoDecodedFrame()
    data class SocketConnectError(val message: String) : SharedSocketIoDecodedFrame()
    data class SocketEvent(val eventName: String, val payload: JsonElement) : SharedSocketIoDecodedFrame()
    data object InvalidEvent : SharedSocketIoDecodedFrame()
    data object Unknown : SharedSocketIoDecodedFrame()
}
