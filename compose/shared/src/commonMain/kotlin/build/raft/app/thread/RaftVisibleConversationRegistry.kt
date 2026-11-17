package build.raft.app

import build.raft.app.data.SharedConversationDirectoryState
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MessageId
import build.raft.app.model.NotificationPushReceived
import build.raft.app.model.ServerId
import build.raft.app.network.raftProcessIsForeground
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

internal data class RaftVisibleConversationSnapshot(
    val currentServerId: String?,
    val visibleConversationKey: String?,
    val visibleRouteIdentity: ThreadRouteIdentity?,
    val conversationDirectory: SharedConversationDirectoryState
)

/**
 * Process-wide pointer to the conversation the active AppShell has on screen. The in-app banner
 * already skips that conversation; platform hosts read this so system notifications do too.
 */
internal object RaftVisibleConversationRegistry {
    private val lock = SynchronizedObject()
    private var ownerId: String? = null
    private var provider: (() -> RaftVisibleConversationSnapshot?)? = null

    fun register(ownerId: String, provider: () -> RaftVisibleConversationSnapshot?) {
        synchronized(lock) {
            this.ownerId = ownerId
            this.provider = provider
        }
    }

    fun unregister(ownerId: String) {
        synchronized(lock) {
            if (this.ownerId != ownerId) return
            this.ownerId = null
            provider = null
        }
    }

    fun snapshot(): RaftVisibleConversationSnapshot? {
        val current = synchronized(lock) { provider } ?: return null
        return runCatching { current() }.getOrNull()
    }
}

/**
 * Platform hosts call this before presenting a system notification. True means the push belongs to
 * the conversation on screen (or one of its threads), so it should stay silent — like the in-app
 * banner. When the process is in the background nothing is on screen, so this is always false.
 */
fun raftSystemNotificationTargetsVisibleConversation(
    serverId: String?,
    kind: String?,
    channelId: String?,
    threadId: String?,
    parentChannelId: String?,
    parentMessageId: String?,
    messageId: String?,
    category: String?
): Boolean {
    if (!raftProcessIsForeground()) return false
    val event =
        raftSystemNotificationPushEventOrNull(
            serverId = serverId,
            kind = kind,
            channelId = channelId,
            threadId = threadId,
            parentChannelId = parentChannelId,
            parentMessageId = parentMessageId,
            messageId = messageId,
            category = category
        ) ?: return false
    val visible = RaftVisibleConversationRegistry.snapshot() ?: return false
    return event.targetsForegroundVisibleConversation(visible)
}

internal fun NotificationPushReceived.targetsForegroundVisibleConversation(
    visible: RaftVisibleConversationSnapshot
): Boolean {
    var suppressedReason: String? = null
    raftNotificationPushBannerOrNull(
        event = this,
        currentServerId = visible.currentServerId,
        visibleConversationKey = visible.visibleConversationKey,
        visibleRouteIdentity = visible.visibleRouteIdentity,
        conversationDirectory = visible.conversationDirectory,
        onSuppressed = { reason -> suppressedReason = reason }
    )
    return suppressedReason == "visible_conversation"
}

/** Push payloads carry ids as plain strings; APNs names the kind, FCM may not. */
internal fun raftSystemNotificationPushEventOrNull(
    serverId: String?,
    kind: String?,
    channelId: String?,
    threadId: String?,
    parentChannelId: String?,
    parentMessageId: String?,
    messageId: String?,
    category: String?
): NotificationPushReceived? {
    fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
    val server = serverId.clean() ?: return null
    val message = messageId.clean() ?: return null
    val channel = channelId.clean()
    val parentChannel = parentChannelId.clean()
    val parentMessage = parentMessageId.clean()
    val thread = threadId.clean()
    val conversationKind =
        when (kind.clean()?.lowercase()) {
            "thread" -> ConversationKind.Thread
            "dm", "direct_message" -> ConversationKind.DirectMessage
            "channel" -> ConversationKind.Channel
            "joint" -> ConversationKind.Joint
            null ->
                when {
                    parentMessage != null || (thread != null && channel != null && thread != channel) ->
                        ConversationKind.Thread
                    // `dm` is the unified contract's category; `direct_message` the older local one.
                    category.clean() == "direct_message" || category.clean() == "dm" -> ConversationKind.DirectMessage
                    else -> ConversationKind.Channel
                }
            else -> return null
        }
    val threadChannel = if (conversationKind == ConversationKind.Thread) thread ?: channel else null
    if (channel == null && threadChannel == null) return null
    return NotificationPushReceived(
        serverId = ServerId(server),
        kind = conversationKind,
        channelId = channel?.let(::ChannelId),
        threadId = threadChannel?.let(::ChannelId),
        parentChannelId = parentChannel?.let(::ChannelId),
        parentMessageId = parentMessage?.let(::MessageId),
        messageId = MessageId(message),
        title = "",
        body = "",
        tag = ""
    )
}
