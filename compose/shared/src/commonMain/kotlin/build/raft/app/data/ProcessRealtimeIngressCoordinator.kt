package build.raft.app.data

import build.raft.app.data.realtime.GeneratedRealtimeContract
import build.raft.app.data.realtime.RealtimeCursorPolicy
import build.raft.app.data.realtime.RealtimeEventMapper
import build.raft.app.data.realtime.SlockRealtimeNotifyContract
import build.raft.app.data.realtime.realtimeProcessDesiredSession
import build.raft.app.host.HostRealtimeCapability
import build.raft.app.model.RealtimeEvent
import build.raft.app.model.RealtimeRefreshRequired
import build.raft.app.model.ServerDeleted
import build.raft.app.model.ServerId
import build.raft.app.model.ServerMembershipRemoved
import build.raft.app.model.ServerPlanUpdated
import build.raft.app.optLong
import build.raft.app.optString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * Process-lifetime adapter for the normal native realtime topic. It owns no authority, queue, dedup, or
 * repository state: every frame carries the native connection witness and is resolved through the existing
 * authority provider before entering the existing process domain owner. Android raw ingest intentionally
 * remains on the AppShell-only task #354 boundary until its separate migration contract is approved.
 */
internal class ProcessRealtimeIngressCoordinator(
    private val events: KuiklyRealtimeEvents,
    private val authorityProvider: RealtimeAuthorityProvider,
    private val authorityFlow: kotlinx.coroutines.flow.StateFlow<RealtimeScopeAuthority?>,
    private val domainOwner: SharedRealtimeDomainEventOwner,
    private val authStore: SlockAuthSessionStore,
    private val environmentStore: SharedApiEnvironmentStore,
    private val mapper: RealtimeEventMapper,
    private val nativeHost: ProcessRealtimeNativeHost,
    private val scope: CoroutineScope,
    private val onPresenceTransportConnected: suspend (RealtimeGrantRecord, Long) -> Unit = { _, _ -> },
    private val onRoomsJoined: suspend (RealtimeGrantRecord, Long) -> Unit = { _, _ -> },
) {
    fun start() {
        // kotlinx JsonObject is immutable: hand the event to the coroutine without a defensive copy.
        events.addListener { event -> scope.launch { accept(event) } }
        scope.launch {
            authorityFlow.collect { authority -> reconcile(authority) }
        }
    }

    private suspend fun reconcile(authority: RealtimeScopeAuthority?) {
        val environment = environmentStore.current()
        val selectedServerId = authStore.readSession()?.selectedServerId
        val desired =
            realtimeProcessDesiredSession(
                authority = authority,
                environmentId = environment.apiBaseUrl,
                socketUrl = environment.socketBaseUrl,
                currentServerId = selectedServerId,
            )
        nativeHost.reconcile(authority, desired, environment.socketBaseUrl)
    }

    private suspend fun accept(eventData: JsonObject) {
        val transportSignal =
            when (eventData.optString(SlockRealtimeNotifyContract.KeyAction)) {
                SlockRealtimeNotifyContract.ActionPresenceTransportConnected -> onPresenceTransportConnected
                SlockRealtimeNotifyContract.ActionRealtimeRoomsJoined -> onRoomsJoined
                else -> null
            }
        if (transportSignal != null) {
            val serverId =
                eventData
                    .optString(SlockRealtimeNotifyContract.KeyServerId)
                    .trim()
                    .takeIf(String::isNotEmpty)
                    ?: return
            val transportEpoch =
                eventData
                    .optLong(SlockRealtimeNotifyContract.KeyPresenceTransportEpoch, 0L)
                    .takeIf { it > 0L }
                    ?: return
            val witness = eventData.processRealtimeWitnessOrNull() ?: return
            val grant = authorityProvider.resolve(witness, serverId) ?: return
            transportSignal(grant, transportEpoch)
            return
        }
        val event = decode(eventData) ?: return
        val envelopeServerId =
            eventData.optString(SlockRealtimeNotifyContract.KeyServerId).trim().takeIf(String::isNotEmpty)
        val payloadServerId = event.serverIdHint()?.value
        if (envelopeServerId != null && payloadServerId != null && envelopeServerId != payloadServerId) return
        val serverId = envelopeServerId ?: payloadServerId ?: return
        val witness = eventData.processRealtimeWitnessOrNull() ?: return
        val grant = authorityProvider.resolve(witness, serverId) ?: return
        val accepted =
            if (event is ServerMembershipRemoved || event is ServerDeleted) {
                domainOwner.acceptTerminalProcessMutation(
                    grant = grant,
                    event = event,
                    source = "process_normal_realtime",
                    runTerminalIfCurrentGrant = authorityProvider::runTerminalIfCurrentGrant,
                    terminalMutation = {
                        val session = authStore.readSession()
                        if (session?.selectedServerId == serverId) {
                            authStore.updateSelectedServer(serverId = null, serverSlug = null)
                        }
                    },
                )
            } else {
                domainOwner.acceptProcessMutation(
                    grant,
                    event,
                    source = "process_normal_realtime",
                    presenceTransportEpoch =
                        eventData
                            .optLong(SlockRealtimeNotifyContract.KeyPresenceTransportEpoch, 0L)
                            .takeIf { it > 0L },
                )
            }
        if (!accepted) return
        RealtimeCursorPolicy.appliedSeqForEvent(event)
            .takeIf { it > 0L }
            ?.let { appliedSeq ->
                nativeHost.advanceCursor(serverId, appliedSeq, grant)
            }
        if (event is ServerMembershipRemoved || event is ServerDeleted) {
            nativeHost.disconnect(
                serverId,
                if (event is ServerDeleted) {
                    GeneratedRealtimeContract.DisconnectReason.ServerDeleted
                } else {
                    GeneratedRealtimeContract.DisconnectReason.ServerMembershipRemoved
                },
            )
        }
    }

    private fun decode(eventData: JsonObject): RealtimeEvent? {
        val eventName = eventData.optString(SlockRealtimeNotifyContract.KeyEventName).trim()
        val rawPayload = eventData.optString(SlockRealtimeNotifyContract.KeyRawPayload).trim()
        if (eventName.isEmpty() || rawPayload.isEmpty()) return null
        return mapper.map(eventName, rawPayload)
    }
}

internal interface ProcessRealtimeNativeHost {
    fun reconcile(
        authority: RealtimeScopeAuthority?,
        desired: build.raft.app.data.realtime.RealtimeProcessDesiredSession?,
        fallbackSocketUrl: String,
    )

    fun advanceCursor(serverId: String, appliedSeq: Long, grant: RealtimeGrantRecord)

    fun disconnect(serverId: String, reason: String)
}

internal class BridgeProcessRealtimeNativeHost(
    private val bridge: HostRealtimeCapability,
) : ProcessRealtimeNativeHost {
    override fun reconcile(
        authority: RealtimeScopeAuthority?,
        desired: build.raft.app.data.realtime.RealtimeProcessDesiredSession?,
        fallbackSocketUrl: String,
    ) {
        bridge.realtimeReconcile(
            serverIds = desired?.serverIds.orEmpty(),
            accessToken = desired?.accessToken.orEmpty(),
            environmentId = desired?.environmentId.orEmpty(),
            socketUrl = desired?.socketUrl ?: fallbackSocketUrl,
            forceReconnect = false,
            disconnectReason = GeneratedRealtimeContract.DisconnectReason.AuthInvalid,
            authorityScopeLeaseId = authority?.witness?.scopeLeaseId.orEmpty(),
            authorityPrincipalId = authority?.witness?.principalId.orEmpty(),
            authorityAuthGeneration = authority?.witness?.authGeneration ?: 0L,
            authorityConnectionFamilyId = authority?.witness?.connectionFamilyId.orEmpty(),
        )
    }

    override fun advanceCursor(serverId: String, appliedSeq: Long, grant: RealtimeGrantRecord) {
        bridge.realtimeCursorAdvance(
            serverId = serverId,
            appliedSeq = appliedSeq,
            authorityScopeLeaseId = grant.scopeLeaseId,
            authorityPrincipalId = grant.principalId,
            authorityAuthGeneration = grant.authGeneration,
            authorityConnectionFamilyId = grant.connectionFamilyId,
        )
    }

    override fun disconnect(serverId: String, reason: String) {
        bridge.realtimeDisconnect(
            reason = reason,
            serverId = serverId,
            shellInstanceId = "process-realtime-host-owner",
            explicitClose = true,
        )
    }
}

private fun JsonObject.processRealtimeWitnessOrNull(): RealtimeConnectionWitness? {
    val scopeLeaseId = optString(SlockRealtimeNotifyContract.KeyAuthorityScopeLeaseId).trim()
    val principalId = optString(SlockRealtimeNotifyContract.KeyAuthorityPrincipalId).trim()
    val authGeneration = optLong(SlockRealtimeNotifyContract.KeyAuthorityAuthGeneration, 0L)
    val connectionFamilyId = optString(SlockRealtimeNotifyContract.KeyAuthorityConnectionFamilyId).trim()
    if (scopeLeaseId.isEmpty() || principalId.isEmpty() || authGeneration <= 0L || connectionFamilyId.isEmpty()) {
        return null
    }
    return RealtimeConnectionWitness(
        scopeLeaseId = scopeLeaseId,
        principalId = principalId,
        authGeneration = authGeneration,
        connectionFamilyId = connectionFamilyId,
    )
}

private fun RealtimeEvent.serverIdHint(): ServerId? =
    when (this) {
        is RealtimeRefreshRequired -> serverId
        is ServerMembershipRemoved -> serverId
        is ServerDeleted -> serverId
        is ServerPlanUpdated -> serverId
        else -> null
    }
