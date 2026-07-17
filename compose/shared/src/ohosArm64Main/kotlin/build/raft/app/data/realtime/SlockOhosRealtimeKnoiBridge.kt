package build.raft.app.data.realtime

import build.raft.app.data.SlockShellStoreScope
import build.raft.app.data.updateSlockHostVisibility
import com.tencent.tmm.knoi.annotation.KNExport

private val slockOhosRealtimeKnoiEngines = mutableMapOf<String, SlockRealtimeAppScopeEngine>()

private fun slockOhosRealtimeKnoiEngine(runtimeId: String): SlockRealtimeAppScopeEngine =
    slockOhosRealtimeKnoiEngines.getOrPut(runtimeId.trim()) { SlockRealtimeAppScopeEngine() }

@KNExport
fun slockRealtimeKnoiConnectDecision(
    runtimeId: String,
    serverId: String,
    accessToken: String,
    requestedLastSeq: Long,
    forceReconnect: Boolean,
    connectionStateName: String
): Map<String, Any?> {
    val decision =
        slockOhosRealtimeKnoiEngine(runtimeId).connectDecisionForHost(
            serverIdValue = serverId,
            accessToken = accessToken,
            requestedLastSeq = requestedLastSeq,
            forceReconnect = forceReconnect,
            connectionStateName = connectionStateName
        )
    return decision.toKnoiMap()
}

@KNExport
fun slockRealtimeKnoiDisconnectForSessionEnd(runtimeId: String, reason: String): Boolean =
    slockOhosRealtimeKnoiEngine(runtimeId).disconnectForSessionEnd(reason)

@KNExport
fun slockRealtimeKnoiRoomCommandDecision(
    runtimeId: String,
    action: String,
    channelId: String,
    reason: String,
): Map<String, Any?> {
    val decision =
        slockOhosRealtimeKnoiEngine(runtimeId).roomCommandDecisionForHost(
            action = action,
            channelIdValue = channelId,
            reason = reason
        )
    return decision.toKnoiMap()
}

@KNExport
fun slockRealtimeKnoiJoinChannelDecision(runtimeId: String, channelId: String, reason: String): Map<String, Any?> {
    val decision =
        slockOhosRealtimeKnoiEngine(runtimeId).joinChannelDecisionForHost(
            channelIdValue = channelId,
            reason = reason
        )
    return decision.toKnoiMap()
}

@KNExport
fun slockRealtimeKnoiRawDecision(
    runtimeId: String,
    connectionServerId: String,
    eventName: String,
    rawPayload: String,
): Map<String, Any?> {
    val decision =
        slockOhosRealtimeKnoiEngine(runtimeId)
            .onRawEventForConnection(connectionServerId, eventName, rawPayload)
    return decision.toKnoiMap()
}

@KNExport
fun slockRealtimeKnoiOnAppShown(runtimeId: String) {
    slockOhosRealtimeKnoiEngine(runtimeId).onAppShown()
    updateSlockHostVisibility(
        shellInstanceId = SlockShellStoreScope.Legacy.shellInstanceId,
        visible = true
    )
}

@KNExport
fun slockRealtimeKnoiOnAppHidden(runtimeId: String) {
    updateSlockHostVisibility(
        shellInstanceId = SlockShellStoreScope.Legacy.shellInstanceId,
        visible = false
    )
    slockOhosRealtimeKnoiEngine(runtimeId).onAppHidden()
}

@KNExport
fun slockRealtimeKnoiReleaseRuntime(runtimeId: String) {
    slockOhosRealtimeKnoiEngines.remove(runtimeId.trim())
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
