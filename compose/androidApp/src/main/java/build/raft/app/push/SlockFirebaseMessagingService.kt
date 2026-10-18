package build.raft.app.push

import build.raft.app.core.data.diagnostics.SlockAppLog
import build.raft.app.slockSystemNotificationTargetsVisibleConversation
import build.raft.app.shared.AndroidUnreadNotificationPoster
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import org.json.JSONObject

class SlockFirebaseMessagingService : FirebaseMessagingService() {
    override fun onRegistered(installationId: String) {
        super.onRegistered(installationId)
        SlockAppLog.i(TAG, "fcm_registered installationIdPresent=${installationId.isNotBlank()}")
        if (installationId.isNotBlank()) {
            AndroidPushRegistration.onFirebaseInstallationId(applicationContext, installationId)
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val data = message.data
        if (data.isEmpty()) {
            SlockAppLog.i(TAG, "fcm_message_received type=empty_data")
            return
        }

        if (
            slockSystemNotificationTargetsVisibleConversation(
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
            SlockAppLog.i(TAG, "fcm_message_suppressed reason=visible_conversation")
            return
        }
        val request = data.toUnreadNotificationRequest()
        val result = AndroidUnreadNotificationPoster.postUnreadNotification(applicationContext, request)
        SlockAppLog.i(
            TAG,
            "fcm_message_handled dataKeys=${data.keys.sorted().joinToString(",")} status=${result["status"]} code=${result["code"]}"
        )
    }

    private fun Map<String, String>.toUnreadNotificationRequest(): JSONObject =
        JSONObject().apply {
            put("serverId", this@toUnreadNotificationRequest["serverId"].orEmpty())
            put("messageId", this@toUnreadNotificationRequest["messageId"].orEmpty())
            put("threadId", this@toUnreadNotificationRequest["threadId"].orEmpty())
            put("channelId", this@toUnreadNotificationRequest["channelId"].orEmpty())
            put("parentChannelId", this@toUnreadNotificationRequest["parentChannelId"].orEmpty())
            put("parentMessageId", this@toUnreadNotificationRequest["parentMessageId"].orEmpty())
            put("category", this@toUnreadNotificationRequest["category"].orEmpty())
            put("title", this@toUnreadNotificationRequest["title"].orEmpty())
            put("body", this@toUnreadNotificationRequest["body"].orEmpty())
            put("notificationKey", this@toUnreadNotificationRequest["notificationKey"].orEmpty())
            put("routeUrl", this@toUnreadNotificationRequest["routeUrl"].orEmpty())
            put("serverSeq", this@toUnreadNotificationRequest["serverSeq"].orEmpty())
        }

    private companion object {
        const val TAG = "SlockFCM"
    }
}
