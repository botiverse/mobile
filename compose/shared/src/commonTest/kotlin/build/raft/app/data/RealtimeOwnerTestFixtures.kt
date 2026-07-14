package build.raft.app.data

import build.raft.app.data.realtime.RealtimeAppliedSeqCursorStore
import build.raft.app.model.RealtimeEvent
import build.raft.app.network.sync.OwnerCommitGate

internal val DefaultTestRealtimeOwner =
    RealtimeOwnerEpoch(
        serverId = "server-1",
        memberId = "user-1",
        connectionEpoch = 1L,
    )

internal fun ownedRealtimeEvent(
    event: RealtimeEvent,
    owner: RealtimeOwnerEpoch = DefaultTestRealtimeOwner,
    serverId: String = owner.serverId,
): RealtimeOwnerEnvelope<RealtimeEvent> =
    RealtimeOwnerEnvelope(
        owner = owner.copy(serverId = serverId),
        payload = event,
    )

internal fun testRealtimeDomainEventOwner(
    tasksDataStore: SharedTasksDataStore? = null,
    cursorStore: RealtimeAppliedSeqCursorStore? = null,
    beforeOwnerSideEffects: () -> Unit = {},
    ownerCommitGate: OwnerCommitGate = OwnerCommitGate(),
): SharedRealtimeDomainEventOwner =
    SharedRealtimeDomainEventOwner(
        tasksDataStore = tasksDataStore,
        cursorStore = cursorStore,
        beforeOwnerSideEffects = beforeOwnerSideEffects,
        ownerCommitGate = ownerCommitGate,
    )

internal fun activityRealtimeIntent(
    event: RealtimeEvent,
    ownerToken: RealtimeOwnerEpoch = DefaultTestRealtimeOwner,
    serverId: String = ownerToken.serverId,
    allowLocalPatch: Boolean = true,
): SharedActivityIntent.ApplyRealtimeEvent =
    SharedActivityIntent.ApplyRealtimeEvent(
        envelope = ownedRealtimeEvent(event, ownerToken, serverId),
        allowLocalPatch = allowLocalPatch,
    )

internal fun activityRealtimeSummaryIntent(
    event: RealtimeEvent,
    activeConversationId: String?,
    ownerToken: RealtimeOwnerEpoch = DefaultTestRealtimeOwner,
    serverId: String = ownerToken.serverId,
): SharedActivityIntent.ApplyRealtimeSummaryEvent =
    SharedActivityIntent.ApplyRealtimeSummaryEvent(
        envelope = ownedRealtimeEvent(event, ownerToken, serverId),
        activeConversationId = activeConversationId,
    )

internal fun homeRealtimeIntent(
    event: RealtimeEvent,
    ownerToken: RealtimeOwnerEpoch = DefaultTestRealtimeOwner,
    serverId: String = ownerToken.serverId,
): SharedHomeIntent.ApplyRealtimeEvent =
    SharedHomeIntent.ApplyRealtimeEvent(
        envelope = ownedRealtimeEvent(event, ownerToken, serverId),
    )

internal fun homeRealtimeIntentForServer(
    serverId: String,
    event: RealtimeEvent,
    ownerToken: RealtimeOwnerEpoch = DefaultTestRealtimeOwner,
): SharedHomeIntent.ApplyRealtimeEvent =
    homeRealtimeIntent(event = event, ownerToken = ownerToken, serverId = serverId)

internal fun backgroundHomeRealtimeIntent(
    event: RealtimeEvent,
    ownerToken: RealtimeOwnerEpoch = DefaultTestRealtimeOwner,
    serverId: String = ownerToken.serverId,
): SharedHomeIntent.ApplyBackgroundServerUnreadEvent =
    SharedHomeIntent.ApplyBackgroundServerUnreadEvent(
        envelope = ownedRealtimeEvent(event, ownerToken, serverId),
    )

internal fun settingsRealtimeIntent(
    event: RealtimeEvent,
    ownerToken: RealtimeOwnerEpoch = DefaultTestRealtimeOwner,
    serverId: String = ownerToken.serverId,
): SharedSettingsIntent.ApplyRealtimeEvent =
    SharedSettingsIntent.ApplyRealtimeEvent(
        envelope = ownedRealtimeEvent(event, ownerToken, serverId),
    )

internal fun homeShellRealtimeIntent(
    serverId: String,
    event: RealtimeEvent,
    ownerToken: RealtimeOwnerEpoch = DefaultTestRealtimeOwner,
): SharedHomeShellIntent.ApplyRealtimeEvent =
    SharedHomeShellIntent.ApplyRealtimeEvent(
        envelope = ownedRealtimeEvent(event, ownerToken, serverId),
    )
