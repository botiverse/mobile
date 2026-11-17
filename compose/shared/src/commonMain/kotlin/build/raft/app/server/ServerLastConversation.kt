package build.raft.app

import build.raft.app.data.SharedPreferenceStore
import build.raft.app.data.raftSharedPreferenceStore
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MessageId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/*
 * Each server remembers the conversation last open there, so switching servers back and forth
 * comes back to it instead of #all.
 *
 * web parity: `useTabRouteMemory` writes the current route of the current server to localStorage
 * `slock:serverSurface:v1:<serverSlug>` on every navigation, and every server switch (workspace
 * strip, server switcher menu, host commands, entering the app) navigates to that remembered
 * route, else `/s/<slug>` (#all). A remembered route that no longer parses is dropped. A channel
 * that has gone is left to the channel page; here the conversation must still be listed (or, for
 * a reply thread, its parent conversation), else #all opens and the stale entry is forgotten.
 *
 * Only the identity is kept (the Route/PageData rule); the opened page loads the content itself.
 */

/** The conversation last open on one server: a channel/DM row, or a reply thread under one. */
internal data class ServerLastConversation(
    val surfaceKind: String,
    /** The Home row id for a channel/DM; unused for a reply thread (see [parentChannelId]). */
    val threadId: String,
    val parentChannelId: String? = null,
    val parentMessageId: String? = null,
    val streamChannelId: String? = null,
) {
    val isReplyThread: Boolean
        get() = surfaceKind == RaftThreadSurfaceKind.ReplyThread
}

/** What the wide detail shows, as the memory keeps it; null for a thread without its parent. */
internal fun RaftAdaptiveDetailIdentity.toServerLastConversation(): ServerLastConversation? =
    if (surfaceKind == RaftThreadSurfaceKind.ReplyThread) {
        val parentChannel = parentChannelId?.takeIf(String::isNotBlank)
        val parentMessage = parentMessageId?.takeIf(String::isNotBlank)
        if (parentChannel == null || parentMessage == null) {
            null
        } else {
            ServerLastConversation(
                surfaceKind = surfaceKind,
                threadId = threadId,
                parentChannelId = parentChannel,
                parentMessageId = parentMessage,
                streamChannelId = streamChannelId?.takeIf(String::isNotBlank),
            )
        }
    } else {
        ServerLastConversation(surfaceKind = surfaceKind, threadId = threadId)
    }

/**
 * Persisted key of one server's last conversation for one person:
 * `serverLastConversation:<environment>|<user>|<serverId>` (the owner part is
 * [build.raft.app.data.messageBodyFontSizeOwnerKey]), so another account or environment on the
 * device never reopens someone else's conversation.
 */
internal const val SERVER_LAST_CONVERSATION_KEY_PREFIX = "serverLastConversation:"

internal fun serverLastConversationKey(ownerKey: String, serverId: String): String =
    "$SERVER_LAST_CONVERSATION_KEY_PREFIX$ownerKey|$serverId"

internal fun ServerLastConversation.encode(): String =
    buildJsonObject {
        put("surfaceKind", surfaceKind)
        put("threadId", threadId)
        parentChannelId?.let { put("parentChannelId", it) }
        parentMessageId?.let { put("parentMessageId", it) }
        streamChannelId?.let { put("streamChannelId", it) }
    }.toString()

internal fun decodeServerLastConversation(raw: String?): ServerLastConversation? {
    val value = raw?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() } ?: return null
    fun text(name: String): String? =
        (value[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()?.takeIf(String::isNotBlank)
    val surfaceKind = text("surfaceKind") ?: return null
    val threadId = text("threadId") ?: return null
    val conversation =
        ServerLastConversation(
            surfaceKind = surfaceKind,
            threadId = threadId,
            parentChannelId = text("parentChannelId"),
            parentMessageId = text("parentMessageId"),
            streamChannelId = text("streamChannelId"),
        )
    if (conversation.isReplyThread && (conversation.parentChannelId == null || conversation.parentMessageId == null)) {
        return null
    }
    return conversation
}

/** The last conversation per environment + user + server, kept across app restarts. */
internal class ServerLastConversationRepository(
    private val preferences: () -> SharedPreferenceStore? = { null },
) {
    private val store: SharedPreferenceStore? by lazy { runCatching(preferences).getOrNull() }

    fun read(ownerKey: String?, serverId: String?): ServerLastConversation? {
        val key = key(ownerKey, serverId) ?: return null
        val raw = runCatching { store?.getString(key) }.getOrNull() ?: return null
        return decodeServerLastConversation(raw).also { decoded ->
            // Self-heal like web: an unreadable entry is dropped instead of read again.
            if (decoded == null) runCatching { store?.putString(key, null) }
        }
    }

    fun write(ownerKey: String?, serverId: String?, conversation: ServerLastConversation) {
        val key = key(ownerKey, serverId) ?: return
        val encoded = conversation.encode()
        runCatching {
            if (store?.getString(key) != encoded) store?.putString(key, encoded)
        }
    }

    fun forget(ownerKey: String?, serverId: String?) {
        val key = key(ownerKey, serverId) ?: return
        runCatching { store?.putString(key, null) }
    }

    private fun key(ownerKey: String?, serverId: String?): String? {
        val owner = ownerKey?.trim()?.takeIf(String::isNotBlank) ?: return null
        val server = serverId?.trim()?.takeIf(String::isNotBlank) ?: return null
        return serverLastConversationKey(owner, server)
    }

    companion object {
        val App: ServerLastConversationRepository =
            ServerLastConversationRepository(preferences = { raftSharedPreferenceStore() })
    }
}

/** Where the wide Home detail lands when it opens empty on a server. */
internal sealed interface RaftAdaptiveHomeLanding {
    /** A listed channel/DM row. */
    data class Row(val threadId: String) : RaftAdaptiveHomeLanding

    /** The remembered reply thread; its parent conversation [parentThreadId] is still listed. */
    data class ReplyThread(
        val conversation: ServerLastConversation,
        val parentThreadId: String,
        val parentKind: ConversationKind?,
    ) : RaftAdaptiveHomeLanding
}

internal data class RaftAdaptiveHomeLandingResolution(
    val landing: RaftAdaptiveHomeLanding?,
    /** The remembered conversation is no longer listed here (left, deleted, no access): forget it. */
    val rememberedUnavailable: Boolean,
)

/**
 * The visible selection when it is still listed, else the server's remembered conversation when it
 * (or a reply thread's parent) is still listed, else #all.
 */
internal fun resolveRaftAdaptiveHomeLanding(
    selectedThreadId: String?,
    remembered: ServerLastConversation?,
    candidates: List<RaftAdaptiveHomeLandingCandidate>,
): RaftAdaptiveHomeLandingResolution {
    val selected =
        selectedThreadId?.trim()?.takeIf(String::isNotBlank)?.let { id -> candidates.firstOrNull { it.threadId == id } }
    if (selected != null) {
        return RaftAdaptiveHomeLandingResolution(RaftAdaptiveHomeLanding.Row(selected.threadId), rememberedUnavailable = false)
    }
    val restored: RaftAdaptiveHomeLanding? =
        remembered?.let { memory ->
            if (memory.isReplyThread) {
                candidates
                    .firstOrNull { it.threadId == memory.parentChannelId }
                    ?.let { parent -> RaftAdaptiveHomeLanding.ReplyThread(memory, parent.threadId, parent.kind) }
            } else {
                candidates.firstOrNull { it.threadId == memory.threadId }?.let { RaftAdaptiveHomeLanding.Row(it.threadId) }
            }
        }
    if (restored != null) return RaftAdaptiveHomeLandingResolution(restored, rememberedUnavailable = false)
    val all = candidates.firstOrNull { it.name == "all" }?.let { RaftAdaptiveHomeLanding.Row(it.threadId) }
    return RaftAdaptiveHomeLandingResolution(all, rememberedUnavailable = remembered != null)
}

/** The launch identity that reopens a remembered reply thread (identity only; the page loads the rest). */
internal fun RaftAdaptiveHomeLanding.ReplyThread.toThreadLaunchConversationIdentity(): ThreadLaunchConversationIdentity {
    val parentChannel = ChannelId(conversation.parentChannelId.orEmpty())
    val streamChannel = conversation.streamChannelId?.let(::ChannelId)
    return buildThreadLaunchConversationIdentity(
        kind = ConversationKind.Thread,
        channelId = streamChannel ?: parentChannel,
        threadChannelId = streamChannel,
        parentChannelId = parentChannel,
        parentMessageId = MessageId(conversation.parentMessageId.orEmpty()),
        parentSurfaceKind = parentKind?.toRaftSurfaceKind(),
        parentChannelKind = parentKind?.toWireChannelKindOrNull(),
    )
}
