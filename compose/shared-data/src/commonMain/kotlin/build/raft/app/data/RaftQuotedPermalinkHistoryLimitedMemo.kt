package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * #proj-mobile:#274, the app side of botiverse/slock #16101 (4cc5684e4, web
 * packages/web/src/components/message/permalinkPreviewMisses.ts `rememberPermalinkPreviewOlder` /
 * `permalinkPreviewOlderAnswer`, read by QuotedMessagePermalinkPreview.tsx). A quoted message link's
 * preview reads `GET /api/messages/context/:messageId?channelId=` every time its row comes on screen.
 * For a message behind the Free plan's history window the server answers 404 `history_limited`
 * every time (one iPhone asked 16 and 23 times in a day for the same message). That answer only
 * changes with the server's plan, so it is kept and the preview is answered from it.
 */

/** Only Free has a history window, so only its `history_limited` answer is kept (web `olderKeyOf`). */
private const val QuotedPermalinkHistoryWindowPlan = "free"

/** At most this many answers are kept, the oldest dropped first (web `MAX_OLDER`). */
@RaftCoreInternalApi const val RaftQuotedPermalinkHistoryLimitedMaxEntries: Int = 500

/** The one JSON document the answers are kept in across restarts, in the shared preference store. */
@RaftCoreInternalApi const val RaftQuotedPermalinkHistoryLimitedStorageKey: String = "messages:quotedPreviewHistoryLimited.v1"

/**
 * Who reads a quoted message link's preview (web `{ userId, serverId, plan }`): the signed-in user,
 * the server the request goes to, and that server's plan as the server list has it.
 */
@RaftCoreInternalApi data class RaftQuotedPermalinkViewer(
    val userId: String?,
    val serverId: String?,
    val plan: String?,
)

/** The session's reader: its user, its current server and that server's plan (web `useServerStore` `current`). */
@RaftCoreInternalApi fun StoredAuthSession.quotedPermalinkViewer(): RaftQuotedPermalinkViewer {
    val serverId = currentServerId
    return RaftQuotedPermalinkViewer(
        userId = user.id,
        serverId = serverId,
        plan = servers.firstOrNull { it.id == serverId }?.plan,
    )
}

/** What a quoted message link's preview read came to. */
@RaftCoreInternalApi sealed interface RaftQuotedPermalinkContextRead {
    /** The `messages/context` page. */
    data class Page(val payload: SharedMessagesPayload) : RaftQuotedPermalinkContextRead

    /**
     * Behind the plan's history window (404 `history_limited`): the message as the server showed
     * it, who sent it and when, from this answer or from one kept under the same plan.
     */
    data class HistoryLimited(val target: MessageContextHiddenTarget) : RaftQuotedPermalinkContextRead

    /** Any other answer: not found, not readable, or a request that failed. */
    data object Unavailable : RaftQuotedPermalinkContextRead
}

/**
 * The `history_limited` answers of quoted message link previews (see the file comment):
 * - kept only for a 404 whose code is `history_limited` with a readable hidden target, and only
 *   while the viewer's server is on Free: under another plan that answer means the server has not
 *   seen an upgrade yet;
 * - keyed by viewer, server, conversation, message and the server's plan, so a plan change, or
 *   another person signed in on this device, asks again;
 * - no time limit (a message never comes back inside the window by itself); at most
 *   [RaftQuotedPermalinkHistoryLimitedMaxEntries], the oldest dropped first;
 * - kept across app restarts in [store], like the other small caches over
 *   [raftSharedPreferenceStore]; a store that cannot be read or written keeps them for this
 *   process only.
 */
@RaftCoreInternalApi class RaftQuotedPermalinkHistoryLimitedMemo(
    private val store: () -> SharedPreferenceStore? = { null },
) {
    private data class Key(
        val userId: String,
        val serverId: String,
        val channelId: String,
        val messageId: String,
        val plan: String,
    )

    private val lock = SynchronizedObject()
    private val preferences: SharedPreferenceStore? by lazy { runCatching(store).getOrNull() }

    /** Oldest first; null until read from [preferences]. */
    private var answers: LinkedHashMap<Key, MessageContextHiddenTarget>? = null

    /** The kept answer for [viewer]'s link to [messageId] in [channelId]; null when none is kept under its plan. */
    fun answer(viewer: RaftQuotedPermalinkViewer?, channelId: String?, messageId: String): MessageContextHiddenTarget? {
        val key = keyOf(viewer, channelId, messageId) ?: return null
        return synchronized(lock) { loadedLocked()[key] }
    }

    /** Keeps [target] for [viewer]'s link; nothing without a user, server or message, or under any plan but Free. */
    fun remember(viewer: RaftQuotedPermalinkViewer?, channelId: String?, messageId: String, target: MessageContextHiddenTarget) {
        val key = keyOf(viewer, channelId, messageId) ?: return
        synchronized(lock) {
            val kept = loadedLocked()
            kept.remove(key)
            kept[key] = target
            while (kept.size > RaftQuotedPermalinkHistoryLimitedMaxEntries) {
                val oldest = kept.keys.firstOrNull() ?: break
                kept.remove(oldest)
            }
            saveLocked(kept)
        }
    }

    /**
     * The preview's read: a kept answer without asking; otherwise [fetch] (the `messages/context`
     * request), keeping its 404 `history_limited` answer under [viewer], whose plan is the one the
     * request was made under (web QuotedMessagePermalinkPreview takes the viewer before the request).
     */
    suspend fun read(
        viewer: RaftQuotedPermalinkViewer?,
        channelId: String?,
        messageId: String,
        fetch: suspend () -> ThreadApiResult<SharedMessagesPayload>,
    ): RaftQuotedPermalinkContextRead {
        answer(viewer, channelId, messageId)?.let { return RaftQuotedPermalinkContextRead.HistoryLimited(it) }
        return when (val result = fetch()) {
            is ThreadApiResult.Success -> RaftQuotedPermalinkContextRead.Page(result.data)
            is ThreadApiResult.Failure -> {
                val target = result.historyLimitedTargetOrNull() ?: return RaftQuotedPermalinkContextRead.Unavailable
                remember(viewer, channelId, messageId, target)
                RaftQuotedPermalinkContextRead.HistoryLimited(target)
            }
        }
    }

    /** Null without a user, server or message, or under any plan but Free (web `olderKeyOf`). */
    private fun keyOf(viewer: RaftQuotedPermalinkViewer?, channelId: String?, messageId: String): Key? {
        val reader = viewer ?: return null
        val userId = reader.userId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val serverId = reader.serverId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val message = messageId.trim().takeIf { it.isNotEmpty() } ?: return null
        val plan = reader.plan?.trim()?.takeIf { it == QuotedPermalinkHistoryWindowPlan } ?: return null
        return Key(userId = userId, serverId = serverId, channelId = channelId?.trim().orEmpty(), messageId = message, plan = plan)
    }

    private fun loadedLocked(): LinkedHashMap<Key, MessageContextHiddenTarget> {
        answers?.let { return it }
        val loaded = LinkedHashMap<Key, MessageContextHiddenTarget>()
        // An unreadable document reads as empty and is replaced by the next answer kept.
        val raw = runCatching { preferences?.getString(RaftQuotedPermalinkHistoryLimitedStorageKey) }.getOrNull()
        val stored =
            raw?.takeIf { it.isNotBlank() }?.let { text ->
                runCatching { quotedPermalinkAnswersJson.decodeFromString(PersistedQuotedPermalinkAnswers.serializer(), text) }
                    .getOrNull()
            }
        stored?.entries?.takeLast(RaftQuotedPermalinkHistoryLimitedMaxEntries)?.forEach { entry ->
            val key =
                Key(
                    userId = entry.userId,
                    serverId = entry.serverId,
                    channelId = entry.channelId,
                    messageId = entry.messageId,
                    plan = entry.plan,
                )
            loaded.remove(key)
            loaded[key] = entry.target
        }
        answers = loaded
        return loaded
    }

    private fun saveLocked(kept: Map<Key, MessageContextHiddenTarget>) {
        val prefs = preferences ?: return
        val document =
            PersistedQuotedPermalinkAnswers(
                entries =
                    kept.map { (key, target) ->
                        PersistedQuotedPermalinkAnswer(
                            userId = key.userId,
                            serverId = key.serverId,
                            channelId = key.channelId,
                            messageId = key.messageId,
                            plan = key.plan,
                            target = target,
                        )
                    }
            )
        runCatching {
            prefs.putString(
                RaftQuotedPermalinkHistoryLimitedStorageKey,
                quotedPermalinkAnswersJson.encodeToString(PersistedQuotedPermalinkAnswers.serializer(), document),
            )
        }
    }

    companion object {
        /** The app's memo, kept in the shared preference store. */
        val App: RaftQuotedPermalinkHistoryLimitedMemo =
            RaftQuotedPermalinkHistoryLimitedMemo(store = { raftSharedPreferenceStore() })
    }
}

/** The hidden target of a 404 `history_limited` refusal; null for any other failure. */
private fun ThreadApiResult.Failure.historyLimitedTargetOrNull(): MessageContextHiddenTarget? =
    if (code == 404) parseMessageContextHiddenTarget(errorBodyText) else null

/** [RaftQuotedPermalinkHistoryLimitedStorageKey]'s document: the kept answers, oldest first. */
@Serializable
private data class PersistedQuotedPermalinkAnswers(
    val entries: List<PersistedQuotedPermalinkAnswer> = emptyList(),
)

@Serializable
private data class PersistedQuotedPermalinkAnswer(
    val userId: String,
    val serverId: String,
    val channelId: String = "",
    val messageId: String,
    val plan: String,
    val target: MessageContextHiddenTarget,
)

private val quotedPermalinkAnswersJson = Json { ignoreUnknownKeys = true }
