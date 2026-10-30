package build.raft.app.push

import build.raft.app.core.data.diagnostics.RaftAppLog
import build.raft.app.data.RaftPushMessageType
import build.raft.app.data.parseRaftFcmNotificationRetract
import build.raft.app.raftSystemNotificationTargetsVisibleConversation
import build.raft.app.shared.AndroidUnreadNotificationPoster
import build.raft.app.shared.RaftNotificationAlertsPreference
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import org.json.JSONObject

/**
 * FCM data messages of the unified notification contract (slock docs/contracts/notifications.md
 * §4.3), split by `type`:
 *
 * - `alert` (or no type: an older server): posted to the tray unless this device's notifications are
 *   switched off or the user is looking at that conversation. Every workspace's alert is posted, not
 *   only the selected one's; its tap switches workspace first.
 * - `retract`: the conversation was read on some device; its notifications up to `throughSeq` close.
 * - `sync`: only the badge changed; Android has no badge count of its own beyond the tray.
 * - anything else: dropped, so a future type never posts a blank alert.
 */
class RaftFirebaseMessagingService : FirebaseMessagingService() {
    override fun onRegistered(installationId: String) {
        super.onRegistered(installationId)
        RaftAppLog.i(TAG, "fcm_registered installationIdPresent=${installationId.isNotBlank()}")
        if (installationId.isNotBlank()) {
            AndroidPushRegistration.onFirebaseInstallationId(applicationContext, installationId)
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val data = message.data
        if (data.isEmpty()) {
            RaftAppLog.i(TAG, "fcm_message_received type=empty_data")
            return
        }
        when (raftFcmMessageDecision(data, alertsEnabled = { RaftNotificationAlertsPreference.isEnabled(applicationContext) })) {
            RaftFcmMessageDecision.Retract -> {
                val retract = parseRaftFcmNotificationRetract(data)
                val closed = retract?.let { AndroidUnreadNotificationPoster.retract(applicationContext, it) } ?: 0
                RaftAppLog.i(TAG, "fcm_message_handled type=retract parsed=${retract != null} closed=$closed")
            }
            RaftFcmMessageDecision.Sync ->
                // The launcher badge follows the tray's notification counts (setNumber); nothing else to update.
                RaftAppLog.i(TAG, "fcm_message_handled type=sync")
            RaftFcmMessageDecision.DropUnknownType ->
                RaftAppLog.i(TAG, "fcm_message_dropped reason=unknown_type")
            RaftFcmMessageDecision.DropAlertsDisabled ->
                // The second guard: the server already stops alerting a registration with alertsEnabled=false.
                RaftAppLog.i(TAG, "fcm_message_suppressed reason=alerts_disabled")
            RaftFcmMessageDecision.Alert -> postAlert(data)
        }
    }

    private fun postAlert(data: Map<String, String>) {
        if (
            raftSystemNotificationTargetsVisibleConversation(
                serverId = data["serverId"],
                kind = data["kind"],
                channelId = data["channelId"],
                threadId = data["threadId"],
                parentChannelId = data["parentChannelId"],
                parentMessageId = data["parentMessageId"],
                messageId = data["messageId"],
                category = data["category"]
            )
        ) {
            // The user is already looking at this conversation: stay silent, same as the in-app banner.
            RaftAppLog.i(TAG, "fcm_message_suppressed reason=visible_conversation")
            return
        }
        val request = raftFcmUnreadNotificationRequest(data)
        val result = AndroidUnreadNotificationPoster.postUnreadNotification(applicationContext, request)
        RaftAppLog.i(
            TAG,
            "fcm_message_handled type=alert dataKeys=${data.keys.sorted().joinToString(",")} status=${result["status"]} code=${result["code"]}"
        )
    }

    private companion object {
        const val TAG = "SlockFCM"
    }
}

internal enum class RaftFcmMessageDecision {
    Alert,
    Retract,
    Sync,
    DropUnknownType,
    DropAlertsDisabled
}

/**
 * What one FCM data message does. [alertsEnabled] (the device's notifications switch) is read only
 * for an alert: a retraction or sync still applies when alerts are off, so a read conversation does
 * not linger and the badge stays right.
 */
internal fun raftFcmMessageDecision(data: Map<String, String>, alertsEnabled: () -> Boolean): RaftFcmMessageDecision =
    when (RaftPushMessageType.fromWire(data["type"])) {
        null -> RaftFcmMessageDecision.DropUnknownType
        RaftPushMessageType.Retract -> RaftFcmMessageDecision.Retract
        RaftPushMessageType.Sync -> RaftFcmMessageDecision.Sync
        RaftPushMessageType.Alert ->
            if (alertsEnabled()) RaftFcmMessageDecision.Alert else RaftFcmMessageDecision.DropAlertsDisabled
    }

/**
 * The poster request for one FCM data message. `previewMode` / `titleKey` / `bodyKey` / `bodyArgs`
 * are present when the server redacted the push for the account's preview choice.
 */
internal fun raftFcmUnreadNotificationRequest(data: Map<String, String>): JSONObject =
    JSONObject().apply {
        for (key in FCM_UNREAD_NOTIFICATION_KEYS) {
            put(key, data[key].orEmpty())
        }
    }

private val FCM_UNREAD_NOTIFICATION_KEYS =
    listOf(
        "serverId",
        "messageId",
        "threadId",
        "channelId",
        "parentChannelId",
        "parentMessageId",
        "kind",
        "category",
        "title",
        "body",
        "notificationKey",
        "routeUrl",
        "serverSeq",
        "previewMode",
        "titleKey",
        "bodyKey",
        "bodyArgs",
        // Task v2 task-reply pushes (slock #11445): open the task conversation on tap.
        "taskId",
        "taskConversationChannelId",
        "taskMessageId",
        // Unified notification contract (§4.3): identity for retraction, the click target, the
        // account sound setting and the personal / normal priority.
        "type",
        "id",
        "groupKey",
        "conversationId",
        "seq",
        "route",
        "priority",
        "silent",
        "badge",
        "testId"
    )
