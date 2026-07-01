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
