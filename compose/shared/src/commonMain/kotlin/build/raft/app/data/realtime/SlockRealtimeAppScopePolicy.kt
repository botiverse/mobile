package build.raft.app.data.realtime

import build.raft.app.data.SlockPushDeliveryStatus
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.DirectMessageCreated
import build.raft.app.model.Message
import build.raft.app.model.MessageNew
import build.raft.app.model.RealtimeEvent
import build.raft.app.model.ServerId

object SlockRealtimeNotifyContract {
    const val EventName = GeneratedRealtimeContract.Notify.EventName

    /**
     * task #354: raw server realtime events (the potentially multi-MiB
     * fanout) travel on this dedicated topic to the single canonical
     * AppShell/store ingest listener only. Page surfaces keep observing
     * [EventName] for the small lifecycle/action signals, so a large event
     * is decoded exactly once instead of once per open page (the Kuikly
     * bridge parses per listener). Only the Android host publishes here;
     * iOS/OHOS ingress keeps posting raw events on [EventName].
     */
    const val RawIngestEventName = GeneratedRealtimeContract.Notify.EventName + ".rawIngest"
    const val RoomsEventName = GeneratedRealtimeContract.Notify.RoomsEventName
    const val ActionJoinChannel = GeneratedRealtimeContract.RoomAction.JoinChannel
    const val ActionLeaveChannel = GeneratedRealtimeContract.RoomAction.LeaveChannel
    const val ActionRejoinChannel = GeneratedRealtimeContract.RoomAction.RejoinChannel
    const val KeyAction = GeneratedRealtimeContract.PayloadKey.Action
    const val KeyChannelId = GeneratedRealtimeContract.PayloadKey.ChannelId
    const val KeyServerId = GeneratedRealtimeContract.PayloadKey.ServerId
    const val KeyEventName = GeneratedRealtimeContract.PayloadKey.EventName
    const val KeyRawPayload = GeneratedRealtimeContract.PayloadKey.RawPayload
    const val KeyAuthorityScopeLeaseId = "authorityScopeLeaseId"
    const val KeyAuthorityPrincipalId = "authorityPrincipalId"
    const val KeyAuthorityAuthGeneration = "authorityAuthGeneration"
    const val KeyAuthorityConnectionFamilyId = "authorityConnectionFamilyId"
    const val ActionPresenceTransportConnected = "presenceTransportConnected"
    /**
     * The server sent `rooms:joined` on the socket of [KeyPresenceTransportEpoch]: it has (re)subscribed that
     * connection to all of its rooms, so frames of that epoch from here on cover every joined room.
     */
    const val ActionRealtimeRoomsJoined = "realtimeRoomsJoined"
    const val KeyPresenceTransportEpoch = "presenceTransportEpoch"
    const val ActionAppLifecycle = "appLifecycle"
    const val LifecycleVisibleResume = "resume"
    const val LifecycleVisibleShow = "show"
    const val LifecycleHiddenPause = "pause"
    const val LifecycleHidden = "hide"
    const val LifecycleHiddenKeepAlive = "hide_keep_alive"
    const val KeyLifecycleEvent = "event"
    const val KeyForegroundEpoch = "foregroundEpoch"
    const val KeyPageName = "pageName"
    const val KeyShellInstanceId = "shellInstanceId"
}

object SlockRealtimeRoomJoinedChannelAction {
    const val None = "none"
    const val Join = "join"
    const val Rejoin = "rejoin"
    const val Leave = "leave"
}

data class SlockRealtimeOwnedSession(
    val serverId: ServerId,
    val accessToken: String,
    val lastSeq: Long,
    val epoch: String = DefaultMessageSeqEpoch
)

data class SlockRealtimeConnectPlan(
    val sessionChanged: Boolean,
    val resumeSeq: Long,
    val epoch: String,
    val rebaseline: Boolean = false
)

class SlockRealtimeAppScopeState {
    private val joinedChannels = linkedSetOf<ChannelId>()

    var serverId: ServerId? = null
        private set
    var accessToken: String? = null
        private set
    var lastSeq: Long = 0L
        private set
    var epoch: String = DefaultMessageSeqEpoch
        private set

    private var sessionKey: String? = null
    private var epochGeneration: Long = 0L

    val joinedChannelCount: Int
        get() = joinedChannels.size

    fun ownedSession(): SlockRealtimeOwnedSession? {
        val ownedServerId = serverId ?: return null
        val ownedAccessToken = accessToken?.takeIf { it.isNotBlank() } ?: return null
        return SlockRealtimeOwnedSession(
            serverId = ownedServerId,
            accessToken = ownedAccessToken,
            lastSeq = lastSeq,
            epoch = epoch
        )
    }

    fun isSameSession(serverId: ServerId, accessToken: String): Boolean =
        this.serverId == serverId && sessionKey == sessionKey(accessToken, serverId)

    fun prepareConnect(
        serverId: ServerId,
        accessToken: String,
        requestedLastSeq: Long,
        rebaseline: Boolean = false
    ): SlockRealtimeConnectPlan {
        val nextSessionKey = sessionKey(accessToken, serverId)
        val sessionChanged = sessionKey != null && sessionKey != nextSessionKey
        if (sessionChanged) {
            lastSeq = 0L
            joinedChannels.clear()
        }
        if (sessionChanged || rebaseline) {
            epoch = nextEpoch()
            lastSeq = 0L
        }
        val resumeSeq =
            if (sessionChanged || rebaseline) {
                0L
            } else {
                maxOf(requestedLastSeq.coerceAtLeast(0L), lastSeq)
            }
        sessionKey = nextSessionKey
        this.serverId = serverId
        this.accessToken = accessToken
        if (resumeSeq == 0L) {
            lastSeq = 0L
        }
        return SlockRealtimeConnectPlan(
            sessionChanged = sessionChanged,
            resumeSeq = resumeSeq,
            epoch = epoch,
            rebaseline = rebaseline || sessionChanged
        )
    }

    fun updateFromEvent(event: RealtimeEvent) {
        lastSeq = RealtimeCursorPolicy.updateAppliedSeq(lastSeq, event)
    }

    fun advanceAppliedSeq(seq: Long) {
        val nextSeq = seq.takeIf { it > 0L } ?: return
        lastSeq = maxOf(lastSeq, nextSeq)
    }

    fun markChannelJoined(channelId: ChannelId): Boolean {
        if (channelId.value.isBlank()) return false
        return joinedChannels.add(channelId)
    }

    fun markChannelLeft(channelId: ChannelId): Boolean {
        if (channelId.value.isBlank()) return false
        return joinedChannels.remove(channelId)
    }

    fun resetForSessionEnd() {
        serverId = null
        accessToken = null
        sessionKey = null
        lastSeq = 0L
        epoch = nextEpoch()
        joinedChannels.clear()
    }

    private fun sessionKey(accessToken: String, serverId: ServerId): String =
        "$accessToken|${serverId.value}"

    private fun nextEpoch(): String {
        epochGeneration += 1L
        // Session epochs are app-scope in-memory guards. They are deliberately
        // distinct from persisted cursor epochs and must not be compared across domains.
        return "session-generation-$epochGeneration"
    }
}

object SlockRealtimeAppScopePolicy {
    const val SkipReasonRemotePushActive = "remote_push_active"

    fun isSessionEndDisconnectReason(reason: String): Boolean =
        reason in GeneratedRealtimeContract.DisconnectReason.SessionEnd

    /**
     * Server push already delivers [serverId]'s tray notifications to this device, so the background
     * realtime path must not post a second copy. True only when the stored delivery-status answer belongs
     * to the current environment, signed-in user and installation, lists [serverId] (the event's server,
     * not the selected one), and says `fcmActive`. No answer, a missing server or any mismatch reads
     * false (post locally): a possible duplicate beats a missed notification.
     */
    fun isRemotePushActive(
        serverId: ServerId,
        environmentId: String?,
        currentUserId: String?,
        installationId: String?,
        deliveryStatus: SlockPushDeliveryStatus?
    ): Boolean {
        val status = deliveryStatus ?: return false
        val server = serverId.value.trim().takeIf { it.isNotEmpty() } ?: return false
        val environment = environmentId?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return false
        val userId = currentUserId?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val installation = installationId?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        if (status.environmentId.trim().trimEnd('/') != environment) return false
        if (status.userId.trim() != userId || status.installationId.trim() != installation) return false
        return status.fcmActiveByServer[server] == true
    }

    fun shouldPostBackgroundUnreadNotification(
        hasAttachedConsumers: Boolean,
        preferenceEnabled: Boolean,
        isAppInForeground: Boolean,
        remotePushActive: Boolean = false
    ): Boolean =
        !hasAttachedConsumers && preferenceEnabled && !isAppInForeground && !remotePushActive

    fun backgroundUnreadNotificationSkipReason(
        hasAttachedConsumers: Boolean,
        preferenceEnabled: Boolean,
        isAppInForeground: Boolean,
        remotePushActive: Boolean = false
    ): String =
        when {
            hasAttachedConsumers -> "foreground_consumer"
            isAppInForeground -> "app_foreground"
            !preferenceEnabled -> "preference_disabled"
            remotePushActive -> SkipReasonRemotePushActive
            else -> "unknown"
        }

    fun backgroundUnreadNotificationConversationKind(message: Message): ConversationKind? =
        when {
            message.parentMessageId != null || message.threadChannelId != null -> ConversationKind.Thread
            message.channelId != null -> ConversationKind.Channel
            else -> null
        }

    fun nativeRealtimeEventName(event: RealtimeEvent): String =
        when (event) {
            is DirectMessageCreated -> "direct_message_created"
            is MessageNew -> "message_new"
            else -> event::class.simpleName ?: "unknown"
        }
}
