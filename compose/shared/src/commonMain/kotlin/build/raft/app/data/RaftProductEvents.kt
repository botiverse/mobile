package build.raft.app.data

import build.raft.app.model.ServerId
import build.raft.app.network.platformRaftUserAgentDevice
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Process-wide product analytics entry point (RFC-067; registry pinned in
 * [RAFT_PRODUCT_EVENTS_SLOCK_SHA]). Call sites report what happened; [RaftProductEventsRecorder]
 * decides what becomes an event and [RaftProductEventsClient] decides whether and when it is sent
 * (only to a signed-in server whose `/product-events/config` says `clientEventsAllowed`).
 *
 * Hosts wire:
 *  - lifecycle: [onAppForeground] / [onAppBackground] (Android process visibility, iOS
 *    UIApplication notifications), and [onAppOpenSignal] for what opened the app;
 *  - sign-in: [setSignedIn] / [setSignedOut] (app shell auth gate, auth-session clears);
 *  - transport: [bindTransport] once the shared network client exists.
 */
object RaftProductEvents {
    val recorder: RaftProductEventsRecorder by lazy {
        val nowEpochMillis: () -> Long = { ProcessRaftDatetimeFoundationOwner.nowEpochMillis() }
        RaftProductEventsRecorder(
            client =
                RaftProductEventsClient(
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                    nowEpochMillis = nowEpochMillis,
                    newUuid = ::newRaftProductEventUuid,
                    clientInfo = ::currentRaftProductEventsClientInfo,
                    log = { message -> raftLogInfoProductEvents(message) },
                ),
            nowEpochMillis = nowEpochMillis,
        )
    }

    val pageViews: RaftPageViewTracker
        get() = recorder.pageViews

    fun bindTransport(networkClient: RaftNetworkClient) {
        recorder.client.bindTransport(RaftNetworkProductEventsTransport(networkClient))
    }

    fun onAppForeground(via: RaftAppOpenVia? = null) {
        recorder.onAppForeground(via)
    }

    /** How the app is being opened (launcher icon, notification tap): `app_opened.via`. */
    fun onAppOpenSignal(signal: RaftAppOpenSignal) {
        recorder.onAppOpenSignal(signal)
    }

    fun onDefaultAppOpenSignal(signal: RaftAppOpenSignal) {
        recorder.onDefaultAppOpenSignal(signal)
    }

    fun onAppBackground() {
        recorder.onAppBackground()
    }

    fun setSignedIn(serverId: String) {
        val normalized = serverId.trim().takeIf { it.isNotEmpty() } ?: return
        recorder.client.setSignedIn(ServerId(normalized))
    }

    fun setSignedOut() {
        recorder.client.setSignedOut()
    }

    /** The user changed "Share usage data": ask `/product-events/config` again before the next batch. */
    fun invalidateConsent() {
        recorder.client.invalidateConsent()
    }

    fun channelTabSelected(tab: RaftChannelTab, wasActive: Boolean) {
        recorder.channelTabSelected(tab, wasActive)
    }

    fun messageActionSelected(action: RaftMessageAction, inThread: Boolean) {
        recorder.messageActionSelected(action, inThread)
    }

    fun activityItemOpen(itemKind: RaftActivityItemKind?) {
        if (itemKind == null) return
        recorder.activityItemOpen(itemKind)
    }

    fun activityMark(action: RaftActivityMarkAction) {
        recorder.activityMark(action)
    }

    fun agentCreateOpened(entry: RaftAgentCreateEntry) {
        recorder.agentCreateOpened(entry)
    }

    fun navEntrySelected(destination: RaftNavDestination?, surface: RaftNavSurface, wasActive: Boolean) {
        if (destination == null) return
        recorder.navEntrySelected(destination, surface, wasActive)
    }
}

@OptIn(ExperimentalUuidApi::class)
private fun newRaftProductEventUuid(): String = Uuid.random().toString()

/**
 * `source` / `app_version` from the same app identity the User-Agent and feature flags carry, and
 * `device_class` from the same platform phone/tablet decision the User-Agent token uses.
 */
internal fun currentRaftProductEventsClientInfo(): RaftProductEventsClientInfo? {
    val context = currentFeatureFlagClientContext()
    val source = raftProductEventSourceForOs(context.os) ?: return null
    return RaftProductEventsClientInfo(
        source = source,
        appVersion = context.appVersion,
        deviceClass = raftProductEventDeviceClass(platformRaftUserAgentDevice()),
    )
}

private fun raftLogInfoProductEvents(message: String) {
    build.raft.app.raftLogInfo("RaftProductEvents", message)
}
