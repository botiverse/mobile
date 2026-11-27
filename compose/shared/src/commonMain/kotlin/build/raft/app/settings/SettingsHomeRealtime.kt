package build.raft.app.settings

import build.raft.app.data.realtime.RealtimeCursorPolicy
import build.raft.app.data.RealtimeConnectionWitness
import build.raft.app.data.RealtimeGrantRecord
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.realtime.RealtimeEventMapper
import build.raft.app.model.DirectMessageCreated
import build.raft.app.model.MemberPresenceUpdated
import build.raft.app.model.AgentActivitySubscribed
import build.raft.app.model.MessageNew
import build.raft.app.model.NotificationPushReceived
import build.raft.app.model.MessageUpdated
import build.raft.app.model.PerReceiverNotificationPrefsChanged
import build.raft.app.model.ServerNotificationPreferencesChanged
import build.raft.app.model.PerReceiverPinnedChanged
import build.raft.app.model.PerReceiverReadStateChanged
import build.raft.app.model.RealtimeEvent
import build.raft.app.model.AgentCreated
import build.raft.app.model.Heartbeat
import build.raft.app.model.RealtimeRefreshRequired
import build.raft.app.model.ServerDeleted
import build.raft.app.model.ActivityChanged
import build.raft.app.model.ActivityDoneUpdated
import build.raft.app.model.ServerAppearanceUpdated
import build.raft.app.model.ServerFeaturesChanged
import build.raft.app.model.ServerPlanUpdated
import build.raft.app.model.ComputerRestartDone
import build.raft.app.model.ServerId
import build.raft.app.model.ServerMembershipRemoved
import build.raft.app.model.SyncResumeResponse
import build.raft.app.model.TaskCreated
import build.raft.app.data.realtime.RaftRealtimeNotifyContract
import build.raft.app.model.TaskDeleted
import build.raft.app.model.TaskUpdated
import build.raft.app.model.ThreadFollowersUpdated
import build.raft.app.model.AgentTypingUpdated
import build.raft.app.model.ConversationWorkerStateUpdated
import build.raft.app.model.ScopeReadUpdated
import build.raft.app.model.ThreadUpdated
import build.raft.app.optLong
import build.raft.app.optString
import kotlinx.coroutines.flow.map
import build.raft.app.data.map
import kotlinx.serialization.json.JsonObject

internal fun RealtimeEvent.performanceMarkerName(): String =
    when (this) {
        is MessageNew -> "message_new"
        is MessageUpdated -> "message_updated"
        is DirectMessageCreated -> "dm_new"
        is ThreadFollowersUpdated -> "thread_followers_updated"
        is build.raft.app.model.ThreadReplyUpdated -> "thread_reply_updated"
        is ScopeReadUpdated -> "scope_read_updated"
        is AgentTypingUpdated -> "agent_typing"
        is ConversationWorkerStateUpdated -> "conversation_worker_state"
        is ThreadUpdated -> "thread_updated"
        is TaskCreated -> "task_created"
        is TaskUpdated -> "task_updated"
        is TaskDeleted -> "task_deleted"
        is MemberPresenceUpdated -> "presence_updated"
        is AgentActivitySubscribed -> "agent_activity_subscribed"
        is RealtimeRefreshRequired -> "refresh_${scope.name}"
        is build.raft.app.model.ChannelAuthorityUpdated -> "channel_authority_updated"
        is ServerMembershipRemoved -> "server_membership_removed"
        is ServerDeleted -> "server_deleted"
        is ServerPlanUpdated -> "server_plan_updated"
        is ServerFeaturesChanged -> "server_features_changed"
        is ActivityChanged -> "activity_changed"
        is ActivityDoneUpdated -> "activity_done_updated"
        is build.raft.app.model.JointInvitesChanged -> "joint_invites_changed"
        is build.raft.app.model.PermissionUpdatesChanged -> "permission_updates_changed"
        is build.raft.app.model.SeatJoinBlocksChanged -> "seat_join_blocks_changed"
        is build.raft.app.model.RailPlacesUpdated -> "rail_places_updated"
        is build.raft.app.model.MobilePlacesUpdated -> "mobile_places_updated"
        is ServerAppearanceUpdated -> "appearance_updated"
        is build.raft.app.model.UserProfileUpdated -> "user_profile_updated"
        is build.raft.app.model.ProductFeedbackUnreadUpdated -> "product_feedback_unread"
        is build.raft.app.model.LinkCardOperationChanged -> "link_card_operation_changed"
        is build.raft.app.model.LinkCardChanged -> "link_card_changed"
        is build.raft.app.model.ActorUpdated -> "actor_updated"
        is build.raft.app.model.AgentWorkersChanged -> "agent_workers_changed"
        is build.raft.app.model.MessageDisplayPrefsChanged -> "message_display_prefs_updated"
        is ComputerRestartDone -> "computer_restart_done"
        is build.raft.app.model.RuntimeAuthUpdated -> "runtime_auth_updated"
        is build.raft.app.model.RuntimeAuthLoginEventPushed -> "runtime_auth_login_event"
        is build.raft.app.model.RuntimeAuthLoginResultPushed -> "runtime_auth_login_result"
        is AgentCreated -> "agent_created"
        is SyncResumeResponse -> "sync_resume"
        is Heartbeat -> "heartbeat"
        is PerReceiverReadStateChanged -> "read_state_updated"
        is PerReceiverNotificationPrefsChanged -> "notification_prefs_updated"
        is ServerNotificationPreferencesChanged -> "server_notification_prefs_updated"
        is PerReceiverPinnedChanged -> "pinned_updated"
        is NotificationPushReceived -> "notification_push"
        is build.raft.app.model.NotificationLevelChanged -> "notification_level_changed"
        is build.raft.app.model.NotificationAccountSettingsChanged -> "notification_account_settings_changed"
        is build.raft.app.model.NotificationRetracted -> "notification_retract"
        is build.raft.app.model.NotificationNoticeReceived -> "notification_notice"
    }

internal fun RealtimeEvent.performanceMarkerPhase(): String =
    when (this) {
        is MemberPresenceUpdated,
        is AgentActivitySubscribed -> "realtime_presence_apply"
        is MessageNew,
        is MessageUpdated,
        is ThreadFollowersUpdated,
        is build.raft.app.model.ThreadReplyUpdated,
        is ScopeReadUpdated,
        is AgentTypingUpdated,
        is ConversationWorkerStateUpdated,
        is ThreadUpdated,
        is TaskCreated,
        is TaskUpdated,
        is TaskDeleted,
        is DirectMessageCreated,
        is SyncResumeResponse -> "realtime_content_apply"
        is RealtimeRefreshRequired,
        is build.raft.app.model.ChannelAuthorityUpdated,
        is ServerFeaturesChanged,
        is ActivityChanged,
        is build.raft.app.model.JointInvitesChanged,
        is build.raft.app.model.PermissionUpdatesChanged,
        is build.raft.app.model.SeatJoinBlocksChanged,
        is build.raft.app.model.AgentWorkersChanged,
        is ComputerRestartDone -> "realtime_refresh_apply"
        is build.raft.app.model.RuntimeAuthUpdated,
        is build.raft.app.model.RuntimeAuthLoginEventPushed,
        is build.raft.app.model.RuntimeAuthLoginResultPushed -> "realtime_refresh_apply"
        is ServerMembershipRemoved,
        is ServerDeleted,
        is ServerPlanUpdated,
        is ServerAppearanceUpdated,
        is build.raft.app.model.UserProfileUpdated,
        is build.raft.app.model.ActorUpdated,
        is AgentCreated -> "realtime_membership_apply"
        is Heartbeat -> "realtime_heartbeat"
        is PerReceiverReadStateChanged,
        is ActivityDoneUpdated,
        is build.raft.app.model.RailPlacesUpdated,
        is build.raft.app.model.MobilePlacesUpdated,
        is PerReceiverNotificationPrefsChanged,
        is ServerNotificationPreferencesChanged,
        is PerReceiverPinnedChanged,
        is build.raft.app.model.MessageDisplayPrefsChanged,
        is build.raft.app.model.NotificationLevelChanged,
        is build.raft.app.model.NotificationAccountSettingsChanged,
        is build.raft.app.model.ProductFeedbackUnreadUpdated,
        is build.raft.app.model.LinkCardOperationChanged -> "realtime_content_apply"
        is build.raft.app.model.LinkCardChanged -> "realtime_content_apply"
        is NotificationPushReceived,
        is build.raft.app.model.NotificationNoticeReceived,
        is build.raft.app.model.NotificationRetracted -> "realtime_notification_apply"
    }

internal fun RealtimeEvent.shouldEmitAppShellRouteMarker(): Boolean =
    this !is Heartbeat && this !is MemberPresenceUpdated && this !is AgentActivitySubscribed

internal fun decodeSharedShellRealtimeEvent(
    eventData: JsonObject,
    mapper: RealtimeEventMapper,
    log: (String) -> Unit
): RealtimeEvent? {
    // Raw path: eventName + rawPayload -> common mapper (canonical for Android)
    val eventName = eventData.optString("eventName").takeIf { it.isNotBlank() }
    val rawPayload = eventData.optString("rawPayload").takeIf { it.isNotBlank() }
    if (eventName != null && rawPayload != null) {
        val event = mapper.map(eventName, rawPayload)
        if (event == null) {
            log("shared_realtime_raw_decode_failed eventName=$eventName")
        }
        return event
    }
    // Legacy payload path: deprecated, Android no longer sends this format
    val typedPayload = eventData.optString("payload").takeIf { it.isNotBlank() }
    if (typedPayload != null) {
        log("shared_realtime_legacy_payload_deprecated")
        return null
    }
    return null
}

internal fun resolveSharedShellRealtimeServerId(
    eventData: JsonObject,
    event: RealtimeEvent,
    @Suppress("UNUSED_PARAMETER") session: StoredAuthSession?,
): String? =
    eventData.optString("serverId").takeIf { it.isNotBlank() }
        ?: event.serverIdHint()?.value

internal suspend fun applySelectedServerTerminalRealtimeTransition(
    grant: RealtimeGrantRecord,
    eventServerId: String,
    isSelected: (String) -> Boolean,
    runIfCurrentGrant: suspend (RealtimeGrantRecord, suspend () -> Unit) -> Boolean,
    disconnectAndReset: suspend () -> Unit,
    clearSelectedServer: suspend () -> Unit,
    restoreAuthGate: suspend () -> Unit,
): Boolean {
    var applied = false
    val accepted =
        runIfCurrentGrant(grant) {
            if (isSelected(eventServerId)) {
                disconnectAndReset()
                clearSelectedServer()
                restoreAuthGate()
                applied = true
            }
        }
    return accepted && applied
}

internal fun JsonObject.realtimeConnectionWitnessOrNull(): RealtimeConnectionWitness? {
    val scopeLeaseId = optString(RaftRealtimeNotifyContract.KeyAuthorityScopeLeaseId).trim()
    val principalId = optString(RaftRealtimeNotifyContract.KeyAuthorityPrincipalId).trim()
    val authGeneration = optLong(RaftRealtimeNotifyContract.KeyAuthorityAuthGeneration, 0L)
    val connectionFamilyId = optString(RaftRealtimeNotifyContract.KeyAuthorityConnectionFamilyId).trim()
    if (
        scopeLeaseId.isBlank() ||
        principalId.isBlank() ||
        authGeneration <= 0L ||
        connectionFamilyId.isBlank()
    ) {
        return null
    }
    return RealtimeConnectionWitness(
        scopeLeaseId = scopeLeaseId,
        principalId = principalId,
        authGeneration = authGeneration,
        connectionFamilyId = connectionFamilyId,
    )
}

internal fun shouldRouteRealtimeEventToShell(shellServerId: String?, eventServerId: String?): Boolean {
    val shellServer = shellServerId?.trim().takeUnless { it.isNullOrBlank() } ?: return false
    val eventServer = eventServerId?.trim().takeUnless { it.isNullOrBlank() } ?: return false
    return shellServer == eventServer
}

internal data class AppShellPresenceTransportCursor(
    val connectionFamilyId: String,
    val transportEpoch: Long
)

internal fun nextAppShellPresenceTransportCursor(
    previous: AppShellPresenceTransportCursor?,
    connectionFamilyId: String?,
    transportEpoch: Long
): AppShellPresenceTransportCursor? {
    val family = connectionFamilyId?.trim()?.takeIf { it.isNotBlank() } ?: return null
    if (transportEpoch <= 0L) return null
    if (
        previous != null &&
        previous.connectionFamilyId == family &&
        transportEpoch <= previous.transportEpoch
    ) {
        return null
    }
    return AppShellPresenceTransportCursor(
        connectionFamilyId = family,
        transportEpoch = transportEpoch
    )
}

internal fun shouldProjectBackgroundServerUnread(
    session: StoredAuthSession?,
    shellServerId: String?,
    eventServerId: String?
): Boolean {
    val shellServer = shellServerId?.trim().takeUnless { it.isNullOrBlank() } ?: return false
    val eventServer = eventServerId?.trim().takeUnless { it.isNullOrBlank() } ?: return false
    if (shellServer == eventServer) return false
    return session?.servers?.any { it.id == eventServer } == true
}

internal fun canRefreshRealtimeServer(
    session: StoredAuthSession?,
    requestedServerId: String?,
): Boolean {
    val target = requestedServerId?.trim().takeIf { !it.isNullOrBlank() } ?: return false
    return session?.servers?.any { server -> server.id.trim() == target } == true
}

private fun RealtimeEvent.serverIdHint(): ServerId? =
    when (this) {
        is RealtimeRefreshRequired -> serverId
        is ServerMembershipRemoved -> serverId
        is ServerDeleted -> serverId
        is ServerPlanUpdated -> serverId
        is ServerFeaturesChanged -> serverId
        is ActivityChanged -> serverId
        is ActivityDoneUpdated -> serverId
        is build.raft.app.model.JointInvitesChanged -> serverId
        is build.raft.app.model.PermissionUpdatesChanged -> serverId
        is build.raft.app.model.SeatJoinBlocksChanged -> serverId
        is build.raft.app.model.RailPlacesUpdated -> serverId
        is build.raft.app.model.MobilePlacesUpdated -> serverId
        is ServerAppearanceUpdated -> serverId
        is build.raft.app.model.UserProfileUpdated -> serverId
        else -> null
    }

private fun Long.updateFrom(event: RealtimeEvent): Long =
    RealtimeCursorPolicy.updateAppliedSeq(this, event)
