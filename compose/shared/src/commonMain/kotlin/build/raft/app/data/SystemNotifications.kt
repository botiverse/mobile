package build.raft.app.data

import build.raft.app.shownAuthorName
import build.raft.app.markdown.markdownToPreviewText
import build.raft.app.markdown.RaftMarkdownPreviewNotificationLength
import build.raft.app.model.ConversationKind
import build.raft.app.model.NotificationPushReceived
import build.raft.app.model.Message
import build.raft.app.toRaftSurfaceKind
import kotlin.concurrent.Volatile

data class NotificationPreferenceSnapshot(
    val enabled: Boolean,
    val foregroundKeepAliveEnabled: Boolean = false,
    val offlineComputerAlertsEnabled: Boolean = false,
)

const val NOTIFICATION_ENABLED_PREFERENCE_KEY = "notifications:enabled"
const val NOTIFICATION_FOREGROUND_KEEP_ALIVE_ENABLED_PREFERENCE_KEY = "notifications:foreground_keep_alive_enabled"
const val NOTIFICATION_OFFLINE_COMPUTER_ALERTS_ENABLED_PREFERENCE_KEY = "notifications:offline_computer_alerts_enabled"

interface NotificationPreferenceStore {
    fun readNotificationEnabledSync(): Boolean
    fun readForegroundKeepAliveEnabledSync(): Boolean
    fun readOfflineComputerAlertsEnabledSync(): Boolean
    suspend fun readNotificationEnabled(): Boolean
    suspend fun readForegroundKeepAliveEnabled(): Boolean
    suspend fun readOfflineComputerAlertsEnabled(): Boolean
    suspend fun saveNotificationEnabled(enabled: Boolean)
    suspend fun saveForegroundKeepAliveEnabled(enabled: Boolean)
    suspend fun saveOfflineComputerAlertsEnabled(enabled: Boolean)
}

class KeyValueNotificationPreferenceStore(
    private val store: SharedPreferenceStore
) : NotificationPreferenceStore {
    override fun readNotificationEnabledSync(): Boolean =
        store.getBoolean(KEY_NOTIFICATION_ENABLED, defaultValue = true)

    override fun readForegroundKeepAliveEnabledSync(): Boolean =
        store.getBoolean(KEY_FOREGROUND_KEEP_ALIVE_ENABLED, defaultValue = false)

    override fun readOfflineComputerAlertsEnabledSync(): Boolean =
        store.getBoolean(KEY_OFFLINE_COMPUTER_ALERTS_ENABLED, defaultValue = false)

    override suspend fun readNotificationEnabled(): Boolean =
        readNotificationEnabledSync()

    override suspend fun readForegroundKeepAliveEnabled(): Boolean =
        readForegroundKeepAliveEnabledSync()

    override suspend fun readOfflineComputerAlertsEnabled(): Boolean =
        readOfflineComputerAlertsEnabledSync()

    override suspend fun saveNotificationEnabled(enabled: Boolean) {
        store.putBoolean(KEY_NOTIFICATION_ENABLED, enabled)
    }

    override suspend fun saveForegroundKeepAliveEnabled(enabled: Boolean) {
        store.putBoolean(KEY_FOREGROUND_KEEP_ALIVE_ENABLED, enabled)
    }

    override suspend fun saveOfflineComputerAlertsEnabled(enabled: Boolean) {
        store.putBoolean(KEY_OFFLINE_COMPUTER_ALERTS_ENABLED, enabled)
    }

    private companion object {
        const val KEY_NOTIFICATION_ENABLED = NOTIFICATION_ENABLED_PREFERENCE_KEY
        const val KEY_FOREGROUND_KEEP_ALIVE_ENABLED = NOTIFICATION_FOREGROUND_KEEP_ALIVE_ENABLED_PREFERENCE_KEY
        const val KEY_OFFLINE_COMPUTER_ALERTS_ENABLED =
            NOTIFICATION_OFFLINE_COMPUTER_ALERTS_ENABLED_PREFERENCE_KEY
    }
}

interface NotificationPreferenceRepository {
    suspend fun getNotificationPreference(): NotificationPreferenceSnapshot
    suspend fun setNotificationEnabled(enabled: Boolean): NotificationPreferenceSnapshot
    suspend fun setForegroundKeepAliveEnabled(enabled: Boolean): NotificationPreferenceSnapshot
    suspend fun setOfflineComputerAlertsEnabled(enabled: Boolean): NotificationPreferenceSnapshot
}

class PersistentNotificationPreferenceRepository(
    private val store: NotificationPreferenceStore
) : NotificationPreferenceRepository {
    override suspend fun getNotificationPreference(): NotificationPreferenceSnapshot =
        NotificationPreferenceSnapshot(
            enabled = store.readNotificationEnabled(),
            foregroundKeepAliveEnabled = store.readForegroundKeepAliveEnabled(),
            offlineComputerAlertsEnabled = store.readOfflineComputerAlertsEnabled(),
        )

    override suspend fun setNotificationEnabled(enabled: Boolean): NotificationPreferenceSnapshot {
        store.saveNotificationEnabled(enabled)
        return getNotificationPreference()
    }

    override suspend fun setForegroundKeepAliveEnabled(enabled: Boolean): NotificationPreferenceSnapshot {
        store.saveForegroundKeepAliveEnabled(enabled)
        return getNotificationPreference()
    }

    override suspend fun setOfflineComputerAlertsEnabled(enabled: Boolean): NotificationPreferenceSnapshot {
        store.saveOfflineComputerAlertsEnabled(enabled)
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
    val conversationKind: ConversationKind?,
    val category: SystemUnreadNotificationCategory,
    val title: String,
    val body: String,
    val notificationKey: String,
    /**
     * Who sent the message, without any message text. The Android poster uses it to redact a locally
     * built request for this device's push preview choice (sender_only shows only this name).
     */
    val senderName: String? = null,
    /** The message mentions the current user; picks the "mentioned you" wording when redacted. */
    val mentioned: Boolean = false
)

data class SystemUnreadNotificationServerGateResult(
    val shouldPost: Boolean,
    val code: String
)

fun systemUnreadNotificationServerGate(
    notificationServerId: String?,
    currentServerId: String?
): SystemUnreadNotificationServerGateResult {
    val notificationServer = notificationServerId?.trim().takeIf { !it.isNullOrBlank() }
    val currentServer = currentServerId?.trim().takeIf { !it.isNullOrBlank() }
    return when {
        notificationServer == null ->
            SystemUnreadNotificationServerGateResult(shouldPost = false, code = "server_missing")
        currentServer == null ->
            SystemUnreadNotificationServerGateResult(shouldPost = false, code = "current_server_missing")
        notificationServer != currentServer ->
            SystemUnreadNotificationServerGateResult(shouldPost = false, code = "server_mismatch")
        else ->
            SystemUnreadNotificationServerGateResult(shouldPost = true, code = "allowed")
    }
}

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
    val sender = message.shownAuthorName().ifBlank { message.author.handle.ifBlank { "Someone" } }
    val preview = markdownToPreviewText(message.body, RaftMarkdownPreviewNotificationLength).ifBlank { "Sent a new message." }
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
        conversationKind = conversationKind,
        category = category,
        title = title,
        body = body,
        senderName = sender,
        mentioned = mentioned,
        // Message ids are canonical across host/peer joint projections. The
        // local route stays in the request, while the tray identity deliberately
        // does not include Server/channel so two projected deliveries replace
        // one fact instead of producing duplicate system notifications.
        notificationKey = "unread:message:$messageId"
    )
}

/**
 * task #459: tray request from the server-qualified notification stream
 * (`notification:push`, server PR #4233). Sender exclusion, mention/scope
 * qualification and mute are already decided server-side; the local policy
 * left is foreground/visible-thread suppression (the caller's decision) and
 * the current-server gate inside the poster (#435, unchanged).
 */
fun buildSystemUnreadNotificationRequestFromPush(
    push: NotificationPushReceived,
    isVisibleThread: Boolean,
    isAppInForeground: Boolean
): SystemUnreadNotificationRequest? {
    if (isAppInForeground || isVisibleThread) return null
    val category =
        when (push.kind) {
            ConversationKind.DirectMessage -> SystemUnreadNotificationCategory.DirectMessage
            ConversationKind.Channel,
            ConversationKind.Joint,
            ConversationKind.Thread -> SystemUnreadNotificationCategory.ChannelUnread
            else -> SystemUnreadNotificationCategory.GeneralUnread
        }
    val streamChannelId = (push.threadId ?: push.channelId)?.value
    return SystemUnreadNotificationRequest(
        serverId = push.serverId.value,
        messageId = push.messageId.value,
        threadId = streamChannelId ?: push.messageId.value,
        channelId = streamChannelId,
        parentChannelId = push.parentChannelId?.value,
        parentMessageId = push.parentMessageId?.value,
        conversationKind = push.kind,
        category = category,
        title = push.title.ifBlank { "Raft" },
        body = push.body.ifBlank { "Sent a new message." },
        notificationKey = "unread:message:${push.messageId.value}",
        senderName = push.senderName?.trim()?.takeIf { it.isNotEmpty() },
        mentioned = push.mentioned == true
    )
}

fun SystemUnreadNotificationRequest.conversationKindWireValue(): String? =
    conversationKind?.toRaftSurfaceKind()

/**
 * The conversation [channelId] of [serverId] was read in the app (opened, scrolled to its end or
 * marked read: every path goes through the process read receipt coordinator), so the platform can
 * drop the system notification it posted for that conversation's unread messages.
 */
fun interface RaftConversationReadListener {
    fun onConversationRead(serverId: String, channelId: String)
}

/** Installed by the platform host (androidApp); while null, nothing is told about reads. */
object RaftConversationReadHost {
    @Volatile
    var listener: RaftConversationReadListener? = null

    internal fun conversationRead(serverId: String, channelId: String) {
        val listener = listener ?: return
        runCatching { listener.onConversationRead(serverId, channelId) }
    }
}
