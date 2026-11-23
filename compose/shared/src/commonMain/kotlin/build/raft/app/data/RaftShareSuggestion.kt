package build.raft.app.data

import build.raft.app.model.ConversationKind
import build.raft.app.model.messageStreamChannelId
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull
import build.raft.app.toRaftAvatarLabel
import kotlin.concurrent.Volatile
import kotlinx.serialization.Serializable

/**
 * Conversation kind carried by a Direct Share shortcut hint (`raft_share:v1:...`).
 * Matches the live conversation kinds eligible for immediate composer handoff.
 */
@Serializable
enum class RaftShareSuggestionKind(val wireValue: String) {
    Channel("channel"),
    Joint("joint"),
    DirectMessage("dm");

    fun toConversationKind(): ConversationKind =
        when (this) {
            Channel -> ConversationKind.Channel
            Joint -> ConversationKind.Joint
            DirectMessage -> ConversationKind.DirectMessage
        }

    companion object {
        fun fromWireValue(raw: String): RaftShareSuggestionKind? =
            when (raw.trim().lowercase()) {
                Channel.wireValue -> Channel
                Joint.wireValue -> Joint
                DirectMessage.wireValue -> DirectMessage
                else -> null
            }
    }
}

/**
 * Decoded, syntactically validated Direct Share shortcut hint.
 *
 * Security boundary: a hint is NEVER trusted as authorization. [ExternalShareStore] must verify
 * [serverId] against the active session's authorized servers and resolve [channelId] against live
 * eligible targets from [ForwardMessageTargetRepository] before opening the composer.
 */
data class RaftShareSuggestionTargetHint(
    val serverId: String,
    val kind: RaftShareSuggestionKind,
    val channelId: String
)

/**
 * Canonical cross-platform shortcut ID / target hint format for Direct Share suggestions
 * (Android Sharing Shortcuts `EXTRA_SHORTCUT_ID` and iOS `INSendMessageIntent.conversationIdentifier`).
 */
object RaftShareSuggestionContract {
    const val HintPrefix = "raft_share:v1:"
    const val MaxSuggestionCount = 8
    const val MaxHintLength = 256
    const val MaxSegmentLength = 120

    fun encodeTargetHint(
        serverId: String,
        kind: RaftShareSuggestionKind,
        channelId: String
    ): String? {
        val cleanServerId = serverId.trim().takeIf(::isSafeSegment) ?: return null
        val cleanChannelId = channelId.trim().takeIf(::isSafeSegment) ?: return null
        val encoded = "$HintPrefix$cleanServerId:${kind.wireValue}:$cleanChannelId"
        return encoded.takeIf { it.length <= MaxHintLength }
    }

    fun decodeTargetHint(raw: String?): RaftShareSuggestionTargetHint? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed.length > MaxHintLength) return null
        if (!trimmed.startsWith(HintPrefix)) return null
        val remainder = trimmed.removePrefix(HintPrefix)
        val parts = remainder.split(':')
        if (parts.size != 3) return null
        val serverId = parts[0].takeIf(::isSafeSegment) ?: return null
        val kind = RaftShareSuggestionKind.fromWireValue(parts[1]) ?: return null
        val channelId = parts[2].takeIf(::isSafeSegment) ?: return null
        return RaftShareSuggestionTargetHint(
            serverId = serverId,
            kind = kind,
            channelId = channelId
        )
    }

    fun canonicalizeTargetHint(raw: String?): String? =
        decodeTargetHint(raw)?.let { hint ->
            encodeTargetHint(hint.serverId, hint.kind, hint.channelId)
        }

    private fun isSafeSegment(segment: String): Boolean {
        if (segment.isEmpty() || segment.length > MaxSegmentLength) return false
        return segment.all { ch ->
            ch in 'a'..'z' ||
                ch in 'A'..'Z' ||
                ch in '0'..'9' ||
                ch == '-' ||
                ch == '_' ||
                ch == '.' ||
                ch == '~'
        }
    }
}

/**
 * Recent conversation published to the OS Share Sheet Direct Share surface.
 */
@Serializable
data class RaftShareSuggestion(
    val shortcutId: String,
    val serverId: String,
    val serverName: String,
    val channelId: String,
    val kind: RaftShareSuggestionKind,
    val title: String,
    val subtitle: String? = null,
    val peerType: String? = null,
    val avatarWireValue: String? = null,
    val avatarLabel: String = title.toRaftAvatarLabel(),
    val lastActivityEpochMillis: Long = 0L
)

/**
 * Bottom capability installed by Android (Sharing Shortcuts) or iOS (`INSendMessageIntent`)
 * to publish and purge recent Share-to-Raft conversation suggestions.
 */
interface RaftShareSuggestionsHost {
    fun publish(
        accountId: String,
        serverId: String,
        suggestions: List<RaftShareSuggestion>
    )

    fun clear(reason: String)
}

object RaftShareSuggestionsHostProvider {
    @Volatile
    private var appScopeHost: RaftShareSuggestionsHost? = null

    fun installAppScopeHost(host: RaftShareSuggestionsHost) {
        appScopeHost = host
    }

    fun publish(
        accountId: String,
        serverId: String,
        suggestions: List<RaftShareSuggestion>
    ) {
        val cleanAccountId = accountId.trim()
        val cleanServerId = serverId.trim()
        if (cleanAccountId.isEmpty() || cleanServerId.isEmpty()) return
        appScopeHost?.publish(cleanAccountId, cleanServerId, suggestions)
    }

    fun clear(reason: String) {
        appScopeHost?.clear(reason)
    }

    fun clearForTests() {
        appScopeHost = null
    }
}

/**
 * Extracts up to [maxCount] writable, joined, non-archived, non-frozen recent conversations from
 * a live [HomeSnapshot], ordered by latest activity descending. Unnamed DMs are excluded so every
 * published suggestion aligns with [RealForwardMessageTargetRepository.recentTargets].
 */
internal fun extractRecentShareSuggestions(
    snapshot: HomeSnapshot,
    nowEpochMillis: Long = defaultNowMillis(),
    maxCount: Int = RaftShareSuggestionContract.MaxSuggestionCount
): List<RaftShareSuggestion> {
    val serverId = snapshot.server.id.value.trim()
    val serverName = snapshot.server.name.trim()
    if (serverId.isBlank() || maxCount <= 0) return emptyList()

    val candidates = mutableListOf<RaftShareSuggestion>()

    snapshot.directMessages.forEach { dm ->
        if (!dm.isEligibleForShareSuggestion(nowEpochMillis)) return@forEach
        val title = dm.name.trim().takeIf { it.isNotBlank() } ?: return@forEach
        val channelId = dm.toConversationSummary().messageStreamChannelId().value.trim()
        val shortcutId =
            RaftShareSuggestionContract.encodeTargetHint(
                serverId = serverId,
                kind = RaftShareSuggestionKind.DirectMessage,
                channelId = channelId
            ) ?: return@forEach
        candidates +=
            RaftShareSuggestion(
                shortcutId = shortcutId,
                serverId = serverId,
                serverName = serverName,
                channelId = channelId,
                kind = RaftShareSuggestionKind.DirectMessage,
                title = title,
                subtitle = serverName.takeIf { it.isNotBlank() },
                peerType = dm.directMessagePeerType?.trim()?.takeIf { it.isNotBlank() },
                avatarWireValue = dm.directMessagePeerAvatarWireValue?.trim()?.takeIf { it.isNotBlank() },
                avatarLabel = title.toRaftAvatarLabel(),
                lastActivityEpochMillis = dm.lastMessageAt?.let { parseRaftTimestampEpochMillisOrNull(it, 0) } ?: 0L
            )
    }

    snapshot.channels.forEach { channel ->
        if (!channel.isEligibleForShareSuggestion(nowEpochMillis)) return@forEach
        val channelId = channel.toConversationSummary().messageStreamChannelId().value.trim()
        val kind =
            if (channel.kind == ConversationKind.Joint || channel.channelType.equals("joint", ignoreCase = true)) {
                RaftShareSuggestionKind.Joint
            } else {
                RaftShareSuggestionKind.Channel
            }
        val shortcutId =
            RaftShareSuggestionContract.encodeTargetHint(
                serverId = serverId,
                kind = kind,
                channelId = channelId
            ) ?: return@forEach
        val cleanName = channel.name.trim().removePrefix("#").trim()
        if (cleanName.isBlank()) return@forEach
        val title = "#$cleanName"
        candidates +=
            RaftShareSuggestion(
                shortcutId = shortcutId,
                serverId = serverId,
                serverName = serverName,
                channelId = channelId,
                kind = kind,
                title = title,
                subtitle = serverName.takeIf { it.isNotBlank() },
                peerType = null,
                avatarWireValue = null,
                avatarLabel = "#",
                lastActivityEpochMillis = channel.lastMessageAt?.let { parseRaftTimestampEpochMillisOrNull(it, 0) } ?: 0L
            )
    }

    return candidates
        .distinctBy { it.shortcutId }
        .sortedWith(
            compareByDescending<RaftShareSuggestion> { it.lastActivityEpochMillis }
                .thenBy { if (it.kind == RaftShareSuggestionKind.DirectMessage) 0 else 1 }
                .thenBy { it.title.lowercase() }
                .thenBy { it.shortcutId }
        )
        .take(maxCount)
}

private fun HomeConversationRow.isEligibleForShareSuggestion(nowEpochMillis: Long): Boolean {
    if (!joined || archived) return false
    if (!permissions.canRead || !permissions.canSend) return false
    val freezeAt = externalFreezeAt?.takeIf { it.isNotBlank() }
    if (freezeAt != null) {
        val deadlineMillis = parseRaftTimestampEpochMillisOrNull(freezeAt, 0)
        if (deadlineMillis != null && nowEpochMillis >= deadlineMillis) return false
    }
    return true
}
