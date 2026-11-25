package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import build.raft.app.data.realtime.RaftRealtimeSessionHealth
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
        RaftAppSettingsSnapshotReport.onAppForeground()
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

    /** [userId]: the signed-in account, which the install-source report is tied to (null: not known). */
    fun setSignedIn(serverId: String, userId: String? = null) {
        val normalized = serverId.trim().takeIf { it.isNotEmpty() } ?: return
        recorder.client.setSignedIn(ServerId(normalized))
        // Before the trace account moves: the last realtime health window is queued under its own account.
        RaftRealtimeSessionHealth.onSignedIn(normalized, userId)
        RaftClientTraceEvents.onSignedIn(userId)
        // After the trace account: a web step-up an earlier launch was killed in is queued under it.
        RaftAppWebStepUp.onSignedIn()
        RaftInstallSourceReport.onSignedIn(userId)
        RaftAppSettingsSnapshotReport.onSignedIn()
    }

    fun setSignedOut() {
        RaftInstallSourceReport.onSignedOut()
        RaftAppSettingsSnapshotReport.onSignedOut()
        recorder.client.setSignedOut()
        RaftRealtimeSessionHealth.onSignedOut()
        RaftClientTraceEvents.onSignedOut()
    }

    /** The user changed "Share usage data": ask `/product-events/config` again before the next batch. */
    fun invalidateConsent() {
        recorder.client.invalidateConsent()
    }

    @RaftCoreInternalApi var testEntryEventObserver: ((RaftProductEvent<*>) -> Unit)? = null

    fun channelTabSelected(tab: RaftChannelTab, wasActive: Boolean) {
        recorder.channelTabSelected(tab, wasActive)
    }

    fun channelTabSwiped(tab: RaftChannelTab) {
        recorder.channelTabSwiped(tab)
    }

    fun conversationHeaderActionSelected(
        action: RaftConversationHeaderAction,
        surface: RaftConversationHeaderSurface,
        conversation: RaftConversationHeaderKind,
        compact: Boolean,
    ) {
        testEntryEventObserver?.invoke(
            RaftProductEvent.ConversationHeaderActionSelected(
                RaftConversationHeaderActionSelectedProperties(action, surface, conversation, compact)
            )
        )
        recorder.conversationHeaderActionSelected(action, surface, conversation, compact)
    }

    fun sidebarActionSelected(
        action: RaftSidebarAction,
        surface: RaftSidebarActionSurface,
        row: RaftSidebarRowKind? = null,
    ) {
        val effectiveRow = row.takeIf { surface == RaftSidebarActionSurface.RowMenu }
        testEntryEventObserver?.invoke(
            RaftProductEvent.SidebarActionSelected(
                RaftSidebarActionSelectedProperties(action, surface, effectiveRow)
            )
        )
        recorder.sidebarActionSelected(action, surface, effectiveRow)
    }

    fun activityActionSelected(
        action: RaftActivityAction,
        surface: RaftActivityActionSurface,
    ) {
        testEntryEventObserver?.invoke(
            RaftProductEvent.ActivityActionSelected(
                RaftActivityActionSelectedProperties(action, surface)
            )
        )
        recorder.activityActionSelected(action, surface)
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

    fun draftsPageViewed(count: Int) {
        recorder.draftsPageViewed(count)
    }

    fun draftsItemOpened(place: RaftDraftPlaceKind) {
        recorder.draftsItemOpened(place)
    }

    /** Artifact viewer temporary "Open in browser" button shown or clicked (#proj-mobile:#364). */
    fun artifactOpenInBrowser(
        action: RaftArtifactOpenInBrowserAction,
        surface: RaftArtifactOpenInBrowserSurface,
    ) {
        testEntryEventObserver?.invoke(
            RaftProductEvent.ArtifactOpenInBrowser(
                RaftArtifactOpenInBrowserProperties(action = action, surface = surface)
            )
        )
        recorder.artifactOpenInBrowser(action, surface)
    }

    /** See [RaftProductEventsRecorder.appRatingPromptRequested]. */
    fun appRatingPromptRequested(trigger: RaftAppRatingPromptTrigger) {
        recorder.appRatingPromptRequested(trigger)
    }

    /** See [RaftProductEventsRecorder.appSettingChanged]. */
    fun appSettingChanged(
        setting: RaftAppSettingKey,
        previous: RaftAppSettingValue,
        value: RaftAppSettingValue,
        surface: RaftAppSettingSurface,
    ) {
        recorder.appSettingChanged(setting, previous, value, surface)
    }

    /** See [RaftAppSettingsSnapshotReport]: sent alone, now, and reports whether the server took it. */
    suspend fun sendAppSettingsSnapshot(
        properties: RaftAppSettingsSnapshotProperties,
        stillAllowed: () -> Boolean,
    ): RaftProductEventOnceResult =
        recorder.client.sendOnce(RaftProductEvent.AppSettingsSnapshot(properties), stillAllowed)

    /** See [RaftInstallSourceReport]: sent alone, now, and reports whether the server took it. */
    suspend fun sendAppInstallSourceRead(
        properties: RaftAppInstallSourceReadProperties,
        stillAllowed: () -> Boolean,
    ): RaftProductEventOnceResult =
        recorder.client.sendOnce(RaftProductEvent.AppInstallSourceRead(properties), stillAllowed)

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
@RaftCoreInternalApi fun currentRaftProductEventsClientInfo(): RaftProductEventsClientInfo? {
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
