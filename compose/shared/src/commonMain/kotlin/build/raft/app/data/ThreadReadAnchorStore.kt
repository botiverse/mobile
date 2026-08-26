package build.raft.app.data

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

internal data class ThreadReadAnchorOwner(
    val environmentId: String,
    val accountId: String,
    val serverId: String,
    val conversationId: String,
) {
    fun normalizedOrNull(): ThreadReadAnchorOwner? {
        val normalized =
            copy(
                environmentId = environmentId.trim().trimEnd('/'),
                accountId = accountId.trim(),
                serverId = serverId.trim(),
                conversationId = conversationId.trim(),
            )
        return normalized.takeIf {
            it.environmentId.isNotBlank() &&
                it.accountId.isNotBlank() &&
                it.serverId.isNotBlank() &&
                it.conversationId.isNotBlank()
        }
    }
}

internal data class ThreadReadAnchor(
    val seq: Long,
    val messageId: String?,
) {
    fun normalizedOrNull(): ThreadReadAnchor? =
        takeIf { seq > 0L }
            ?.copy(messageId = messageId?.trim()?.takeIf(String::isNotBlank))
}

internal data class ThreadUnreadAnchorMessage(
    val id: String,
    val seq: Long?,
)

internal data class ThreadUnreadJumpProjection(
    val unreadCount: Int,
    val targetMessageId: String?,
)

/**
 * Advances the route-local read frontier without allowing a viewport moving toward older rows to
 * make already-seen messages unread again. Persistence remains owned by [ThreadReadAnchorStore].
 */
internal fun advanceThreadReadAnchorFrontier(
    current: ThreadReadAnchor?,
    visible: ThreadReadAnchor?,
): ThreadReadAnchor? {
    val currentAnchor = current?.normalizedOrNull()
    val visibleAnchor = visible?.normalizedOrNull()
    return when {
        currentAnchor == null -> visibleAnchor
        visibleAnchor == null -> currentAnchor
        visibleAnchor.seq > currentAnchor.seq -> visibleAnchor
        visibleAnchor.seq < currentAnchor.seq -> currentAnchor
        currentAnchor.messageId == null && visibleAnchor.messageId != null -> visibleAnchor
        else -> currentAnchor
    }
}

internal fun projectThreadUnreadJump(
    anchor: ThreadReadAnchor?,
    messages: List<ThreadUnreadAnchorMessage>,
    excludedMessageIds: Set<String> = emptySet(),
): ThreadUnreadJumpProjection {
    val validAnchor = anchor?.normalizedOrNull()
        ?: return ThreadUnreadJumpProjection(unreadCount = 0, targetMessageId = null)
    val canonical = messages.filter { message -> message.id.isNotBlank() && (message.seq ?: 0L) > 0L }
    val unread =
        canonical.filter { message ->
            message.seq!! > validAnchor.seq && message.id !in excludedMessageIds
        }
    if (unread.isEmpty()) {
        return ThreadUnreadJumpProjection(unreadCount = 0, targetMessageId = null)
    }
    val firstUnread = unread.minByOrNull { message -> message.seq!! }?.id
    return ThreadUnreadJumpProjection(
        unreadCount = unread.distinctBy { message -> message.id }.size,
        targetMessageId = firstUnread,
    )
}

internal interface ThreadReadAnchorStore {
    fun read(owner: ThreadReadAnchorOwner): ThreadReadAnchor?

    /** Persists only a forward reading frontier. Returns the retained record. */
    fun advance(owner: ThreadReadAnchorOwner, anchor: ThreadReadAnchor): ThreadReadAnchor?
}

internal class KeyValueThreadReadAnchorStore(
    private val store: SharedPreferenceStore,
) : ThreadReadAnchorStore {
    private val lock = SynchronizedObject()

    override fun read(owner: ThreadReadAnchorOwner): ThreadReadAnchor? {
        val normalizedOwner = owner.normalizedOrNull() ?: return null
        return synchronized(lock) { readLocked(normalizedOwner) }
    }

    override fun advance(owner: ThreadReadAnchorOwner, anchor: ThreadReadAnchor): ThreadReadAnchor? {
        val normalizedOwner = owner.normalizedOrNull() ?: return null
        val normalizedAnchor = anchor.normalizedOrNull() ?: return read(normalizedOwner)
        return synchronized(lock) {
            val current = readLocked(normalizedOwner)
            if (current != null && current.seq >= normalizedAnchor.seq) {
                current
            } else {
                store.putString(normalizedOwner.storageKey(), normalizedAnchor.encode())
                normalizedAnchor
            }
        }
    }

    private fun readLocked(owner: ThreadReadAnchorOwner): ThreadReadAnchor? =
        store.getString(owner.storageKey())?.decodeThreadReadAnchor()
}

private fun ThreadReadAnchorOwner.storageKey(): String =
    buildString {
        append(ThreadReadAnchorKeyPrefix)
        append(
            listOf(environmentId, accountId, serverId, conversationId)
                .joinToString(separator = "\u001f")
                .encodeToByteArray()
                .joinToString(separator = "") { byte ->
                    byte.toUByte().toString(radix = 16).padStart(2, '0')
                }
        )
    }

private fun ThreadReadAnchor.encode(): String =
    buildString {
        append(ThreadReadAnchorSchemaVersion)
        append('\n')
        append(seq)
        append('\n')
        append(messageId.orEmpty())
    }

private fun String.decodeThreadReadAnchor(): ThreadReadAnchor? {
    val parts = split('\n', limit = 3)
    if (parts.size != 3 || parts[0] != ThreadReadAnchorSchemaVersion) return null
    return ThreadReadAnchor(
        seq = parts[1].toLongOrNull() ?: return null,
        messageId = parts[2],
    ).normalizedOrNull()
}

private const val ThreadReadAnchorKeyPrefix = "thread.read.anchor.v1."
private const val ThreadReadAnchorSchemaVersion = "1"
