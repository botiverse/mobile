package build.raft.app.data.realtime

import build.raft.app.data.SlockShellStoreScope
import build.raft.app.data.updateSlockHostVisibility
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
    updateSlockHostVisibility(
        shellInstanceId = SlockShellStoreScope.Legacy.shellInstanceId,
        visible = true
    )
}

@KNExport
fun slockRealtimeKnoiOnAppHidden() {
    updateSlockHostVisibility(
        shellInstanceId = SlockShellStoreScope.Legacy.shellInstanceId,
        visible = false
    )
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
        // KNOI turns a Kotlin null map value into JS `undefined`, which the ArkTS
        // `=== null`/`!== null` guards can't catch. Never send null across the
        // bridge: model "absent" as "" in-band. Validity of eventName/payload is
        // gated by shouldEmit; channelId is a presence+length gate for join/leave.
        "eventName" to eventName.orEmpty(),
        "payload" to payload.orEmpty(),
        "channelId" to channelId?.value.orEmpty(),
        "joinedChannelAction" to joinedChannelAction,
        "logMessage" to logMessage
    )

private fun SlockRealtimeAppScopeRawDecision.toKnoiMap(): Map<String, Any?> =
    mapOf(
        "shouldFanOut" to shouldFanOut,
        "serverId" to serverId?.value.orEmpty(),
        "eventName" to eventName,
        "rawPayload" to rawPayload,
        "logMessage" to logMessage
    )
