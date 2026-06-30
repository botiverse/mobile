package build.raft.app.data.realtime

import com.tencent.tmm.knoi.annotation.KNExport

private val slockOhosRealtimeKnoiEngine = SlockRealtimeAppScopeEngine()

@KNExport
fun slockRealtimeKnoiConnectDecision(
    serverId: String,
    accessToken: String,
    requestedLastSeq: Long,
    forceReconnect: Boolean,
    connectionStateName: String
): String {
    val decision =
        slockOhosRealtimeKnoiEngine.connectDecisionForHost(
            serverIdValue = serverId,
            accessToken = accessToken,
            requestedLastSeq = requestedLastSeq,
            forceReconnect = forceReconnect,
            connectionStateName = connectionStateName
        )
    return buildJsonObject {
        bool("shouldConnect", decision.shouldConnect)
        number("resumeSeq", decision.resumeSeq)
        bool("sessionChanged", decision.sessionChanged)
        nullableString("logMessage", decision.logMessage)
    }
}

@KNExport
fun slockRealtimeKnoiDisconnectForSessionEnd(reason: String): Boolean =
    slockOhosRealtimeKnoiEngine.disconnectForSessionEnd(reason)

@KNExport
fun slockRealtimeKnoiRoomCommandDecision(action: String, channelId: String, reason: String): String {
    val decision =
        slockOhosRealtimeKnoiEngine.roomCommandDecisionForHost(
            action = action,
            channelIdValue = channelId,
            reason = reason
        )
    return decision.toJson()
}

@KNExport
fun slockRealtimeKnoiJoinChannelDecision(channelId: String, reason: String): String {
    val decision =
        slockOhosRealtimeKnoiEngine.joinChannelDecisionForHost(
            channelIdValue = channelId,
            reason = reason
        )
    return decision.toJson()
}

@KNExport
fun slockRealtimeKnoiRawDecision(eventName: String, rawPayload: String): String {
    val decision = slockOhosRealtimeKnoiEngine.onRawEvent(eventName, rawPayload)
    return buildJsonObject {
        bool("shouldFanOut", decision.shouldFanOut)
        nullableString("serverId", decision.serverId?.value)
        string("eventName", decision.eventName)
        string("rawPayload", decision.rawPayload)
        string("logMessage", decision.logMessage)
    }
}

@KNExport
fun slockRealtimeKnoiOnAppShown() {
    slockOhosRealtimeKnoiEngine.onAppShown()
}

@KNExport
fun slockRealtimeKnoiOnAppHidden() {
    slockOhosRealtimeKnoiEngine.onAppHidden()
}

private fun SlockRealtimeAppScopeRoomCommandDecision.toJson(): String =
    buildJsonObject {
        bool("shouldEmit", shouldEmit)
        nullableString("eventName", eventName)
        nullableString("payload", payload)
        nullableString("channelId", channelId?.value)
        string("joinedChannelAction", joinedChannelAction)
        string("logMessage", logMessage)
    }

private inline fun buildJsonObject(body: JsonObjectBuilder.() -> Unit): String =
    JsonObjectBuilder().apply(body).build()

private class JsonObjectBuilder {
    private val fields = mutableListOf<String>()

    fun bool(name: String, value: Boolean) {
        fields += "\"${name.escapeJson()}\":$value"
    }

    fun number(name: String, value: Long) {
        fields += "\"${name.escapeJson()}\":$value"
    }

    fun string(name: String, value: String) {
        fields += "\"${name.escapeJson()}\":\"${value.escapeJson()}\""
    }

    fun nullableString(name: String, value: String?) {
        fields += if (value == null) {
            "\"${name.escapeJson()}\":null"
        } else {
            "\"${name.escapeJson()}\":\"${value.escapeJson()}\""
        }
    }

    fun build(): String = fields.joinToString(prefix = "{", postfix = "}")
}

private fun String.escapeJson(): String =
    buildString(length) {
        for (char in this@escapeJson) {
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (char.code < 0x20) {
                        append("\\u")
                        append(char.code.toString(16).padStart(4, '0'))
                    } else {
                        append(char)
                    }
                }
            }
        }
    }
