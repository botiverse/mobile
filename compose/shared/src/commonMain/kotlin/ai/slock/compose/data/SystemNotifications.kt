package ai.slock.compose.data

import ai.slock.compose.model.ConversationKind
import ai.slock.compose.model.Message

data class NotificationPreferenceSnapshot(
    val enabled: Boolean
)

const val NOTIFICATION_ENABLED_PREFERENCE_KEY = "notifications:enabled"

interface NotificationPreferenceStore {
    fun readNotificationEnabledSync(): Boolean
    suspend fun readNotificationEnabled(): Boolean
    suspend fun saveNotificationEnabled(enabled: Boolean)
}

class KeyValueNotificationPreferenceStore(
    private val store: SharedPreferenceStore
) : NotificationPreferenceStore {
    override fun readNotificationEnabledSync(): Boolean =
        store.getBoolean(KEY_NOTIFICATION_ENABLED, defaultValue = true)

    override suspend fun readNotificationEnabled(): Boolean =
        readNotificationEnabledSync()

    override suspend fun saveNotificationEnabled(enabled: Boolean) {
        store.putBoolean(KEY_NOTIFICATION_ENABLED, enabled)
    }

    private companion object {
        const val KEY_NOTIFICATION_ENABLED = NOTIFICATION_ENABLED_PREFERENCE_KEY
    }
}

interface NotificationPreferenceRepository {
    suspend fun getNotificationPreference(): NotificationPreferenceSnapshot
    suspend fun setNotificationEnabled(enabled: Boolean): NotificationPreferenceSnapshot
}

class PersistentNotificationPreferenceRepository(
    private val store: NotificationPreferenceStore
) : NotificationPreferenceRepository {
    override suspend fun getNotificationPreference(): NotificationPreferenceSnapshot =
        NotificationPreferenceSnapshot(enabled = store.readNotificationEnabled())

    override suspend fun setNotificationEnabled(enabled: Boolean): NotificationPreferenceSnapshot {
        store.saveNotificationEnabled(enabled)
        return getNotificationPreference()
    }
}

data class SystemNotificationActionResult(
    val status: SystemNotificationActionStatus,
    val code: String = status.name.lowercase(),
    val message: String = ""
) {
    val ok: Boolean
        get() = status == SystemNotificationActionStatus.Ok || status == SystemNotificationActionStatus.Requested
}

enum class SystemNotificationActionStatus {
    Ok,
    Requested,
    PermissionDenied,
    NotificationsDisabled,
    ChannelBlocked,
    Unsupported,
    Error
}

enum class SystemUnreadNotificationCategory(val wireValue: String) {
    GeneralUnread("general_unread"),
    ChannelUnread("channel_unread"),
    DirectMessage("direct_message"),
    Mention("mention")
}

data class SystemUnreadNotificationRequest(
    val serverId: String,
    val messageId: String,
    val threadId: String,
    val channelId: String?,
    val parentChannelId: String?,
    val parentMessageId: String?,
    val category: SystemUnreadNotificationCategory,
    val title: String,
    val body: String,
    val notificationKey: String
)

fun shouldShowSystemInboxNotification(
    messageAuthorId: String?,
    currentMemberId: String?,
    isVisibleThread: Boolean,
    isAppInForeground: Boolean = false
): Boolean {
    if (isAppInForeground) return false
    if (!messageAuthorId.isNullOrBlank() && messageAuthorId == currentMemberId) return false
    if (isVisibleThread) return false
    return true
}

fun buildSystemUnreadNotificationRequest(
    serverId: String,
    message: Message,
    currentMemberId: String?,
    isVisibleThread: Boolean,
    conversationName: String?,
    conversationKind: ConversationKind?,
    currentMemberHandle: String? = null,
    isAppInForeground: Boolean = false
): SystemUnreadNotificationRequest? {
    if (
        !shouldShowSystemInboxNotification(
            messageAuthorId = message.author.id.value,
            currentMemberId = currentMemberId,
            isVisibleThread = isVisibleThread,
            isAppInForeground = isAppInForeground
        )
    ) {
        return null
    }
    val sender = message.author.displayName.ifBlank { message.author.handle.ifBlank { "Someone" } }
    val preview = message.body.toNotificationPreview().ifBlank { "Sent a new message." }
    val mentioned =
        currentMemberHandle
            ?.trim()
            ?.removePrefix("@")
            ?.takeIf { it.isNotBlank() }
            ?.let { handle ->
                message.body.contains("@$handle", ignoreCase = true) ||
                    message.body.contains(handle, ignoreCase = true)
            } == true
    val category =
        when {
            mentioned -> SystemUnreadNotificationCategory.Mention
            conversationKind == ConversationKind.DirectMessage -> SystemUnreadNotificationCategory.DirectMessage
            conversationKind == ConversationKind.Channel || conversationKind == ConversationKind.Thread ->
                SystemUnreadNotificationCategory.ChannelUnread
            else -> SystemUnreadNotificationCategory.GeneralUnread
        }
    val title =
        when (category) {
            SystemUnreadNotificationCategory.DirectMessage -> sender
            SystemUnreadNotificationCategory.ChannelUnread,
            SystemUnreadNotificationCategory.Mention ->
                conversationName
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { name -> if (name.startsWith("#")) name else "#$name" }
                    ?: "Raft"
            SystemUnreadNotificationCategory.GeneralUnread -> "Raft"
        }
    val body =
        when (category) {
            SystemUnreadNotificationCategory.DirectMessage -> preview
            else -> "$sender: $preview"
        }
    val channelId = message.threadChannelId?.value ?: message.channelId?.value
    val threadId = channelId ?: message.threadId.value
    val messageId = message.id.value
    val parentMessageId = message.parentMessageId?.value
    val parentChannelId =
        if (parentMessageId.isNullOrBlank()) {
            null
        } else {
            message.channelId?.value ?: channelId
        }
    return SystemUnreadNotificationRequest(
        serverId = serverId,
        messageId = messageId,
        threadId = threadId,
        channelId = channelId,
        parentChannelId = parentChannelId,
        parentMessageId = parentMessageId,
        category = category,
        title = title,
        body = body,
        notificationKey = "unread:$serverId:${channelId ?: threadId}:$messageId"
    )
}

private fun String.toNotificationPreview(maxLength: Int = 140): String =
    trim()
        .replace(Regex("\\s+"), " ")
        .let { value ->
            if (value.length <= maxLength) {
                value
            } else {
                value.take(maxLength - 1).trimEnd() + "…"
            }
        }
