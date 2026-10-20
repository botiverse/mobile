package build.raft.app.push

import build.raft.app.core.data.diagnostics.RaftAppLog
import build.raft.app.raftSystemNotificationTargetsVisibleConversation
import build.raft.app.shared.AndroidUnreadNotificationPoster
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import org.json.JSONObject

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
            "fcm_message_handled dataKeys=${data.keys.sorted().joinToString(",")} status=${result["status"]} code=${result["code"]}"
        )
    }

    private companion object {
        const val TAG = "SlockFCM"
    }
}

/**
 * The poster request for one FCM data message. `previewMode` / `titleKey` / `bodyKey` / `bodyArgs`
 * are present when the server redacted the push for this registration's preview choice.
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
        "category",
        "title",
        "body",
        "notificationKey",
        "routeUrl",
        "serverSeq",
        "previewMode",
        "titleKey",
        "bodyKey",
        "bodyArgs"
    )
