package build.raft.app.data

import build.raft.app.model.ServerId
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Last-known per-row unread count and last-message preview, shown on cold start while Home is
 * still the durable identity cache (task #166).
 *
 * The identity slice deliberately persists rows with `unreadCount = 0` / `unreadCountKnown =
 * false` / no preview: those values gate the app badge sum, realtime increments and sidebar
 * mark-read. Hints therefore never enter the rows. They live beside them in
 * [SharedHomeContentState.Content.restoredRowHints], are read only by the row projection while
 * [SharedHomeContentState.Content.isDurableIdentityCache] is true and the row's own count is
 * still unknown, and disappear when the first network Home replaces the content.
 *
 * Previews are message content, so this lives under its own key family and is wiped by
 * [HomeRowHintPersistStorage.clearAll] when the auth session ends.
 */
@Serializable
data class HomeRowRestoredHint(
    val unreadCount: Int = 0,
    val lastMessagePreview: String? = null,
    val lastMessageAt: String? = null,
    val lastMessageSenderName: String? = null
)

@Serializable
internal data class HomeRowHintsSnapshot(
    val serverId: String,
    val memberId: String,
    val hints: Map<String, HomeRowRestoredHint> = emptyMap()
)

/** Bounds what a single row can add to the durable preference store. */
internal const val HomeRowHintPreviewMaxChars = 280

internal fun createSharedHomeRowHintPersistSlice(
    storage: HomeRowHintPersistStorage,
    diagnosticsLogger: (String) -> Unit = {},
    failureLogger: (String) -> Unit = diagnosticsLogger
): MviPersistSlice<SharedHomeState, SharedHomeIntent> =
    MviPersistMiddleware(
        storage = storage,
        spec = SharedHomeRowHintPersistSpec,
        diagnosticsLogger = diagnosticsLogger,
        failureLogger = failureLogger
    )

/**
 * Tracks every key written through it so the whole family can be removed without key
 * enumeration (MMKV access here is get/put only).
 */
internal class HomeRowHintPersistStorage(
    private val store: SharedPreferenceStore
) : MviPersistStorage {
    override fun read(key: String): String? = store.getString(key)

    override fun write(key: String, value: String) {
        store.putString(key, value)
        val keys = indexedKeys()
        if (key !in keys) writeIndex(keys + key)
    }

    override fun remove(key: String) {
        store.putString(key, null)
        val keys = indexedKeys()
        if (key in keys) writeIndex(keys - key)
    }

    /** Removes every hint written on this device (only one account is signed in at a time). */
    fun clearAll() {
        indexedKeys().forEach { store.putString(it, null) }
        store.putString(IndexKey, null)
    }

    private fun indexedKeys(): List<String> =
        store.getString(IndexKey)
            ?.let { encoded -> runCatching { IndexJson.decodeFromString(IndexSerializer, encoded) }.getOrNull() }
            .orEmpty()

    private fun writeIndex(keys: List<String>) {
        if (keys.isEmpty()) {
            store.putString(IndexKey, null)
        } else {
            store.putString(IndexKey, IndexJson.encodeToString(IndexSerializer, keys.distinct()))
        }
    }

    private companion object {
        const val IndexKey = "mvi:persist:home:hints:index"
        val IndexSerializer = ListSerializer(String.serializer())
        val IndexJson = Json { ignoreUnknownKeys = true }
    }
}

private object SharedHomeRowHintPersistSpec :
    MviPersistSpec<SharedHomeState, SharedHomeIntent, HomeRowHintsSnapshot> {
    override val name: String = "shared_home_row_hints"
    override val version: Int = 1
    override val snapshotSerializer = HomeRowHintsSnapshot.serializer()

    override fun key(previous: SharedHomeState, intent: SharedHomeIntent, reduced: SharedHomeState): String? {
        val (userId, serverId) = homePersistScope(previous, reduced) ?: return null
        return "mvi:persist:home:hints:v$version:user:$userId:server:${serverId.value}"
    }

    override fun shouldHydrate(
        previous: SharedHomeState,
        intent: SharedHomeIntent,
        reduced: SharedHomeState
    ): Boolean =
        // Runs after the identity slice in the same reduction, so a restored identity cache is
        // already in [reduced] when the hints are attached.
        (reduced.content as? SharedHomeContentState.Content)?.isDurableIdentityCache == true &&
            (intent is SharedHomeIntent.Start || intent is SharedHomeIntent.SelectServer)

    override fun mergeHydrated(
        previous: SharedHomeState,
        intent: SharedHomeIntent,
        reduced: SharedHomeState,
        snapshot: HomeRowHintsSnapshot,
        metadata: MviPersistMetadata
    ): SharedHomeState {
        val content = reduced.content as? SharedHomeContentState.Content ?: return reduced
        if (!content.isDurableIdentityCache) return reduced
        val base = content.baseSnapshot
        if (snapshot.serverId != base.server.id.value) return reduced
        if (snapshot.memberId != content.session.user.id || snapshot.memberId != base.currentMember.id.value) {
            return reduced
        }
        val rowIds = base.homeRowIds()
        val hints = snapshot.hints.filterKeys { it in rowIds }
        if (hints.isEmpty()) return reduced
        return reduced.copy(content = content.copy(restoredRowHints = hints))
    }

    override fun shouldPersist(previous: SharedHomeState, intent: SharedHomeIntent, next: SharedHomeState): Boolean =
        next.content is SharedHomeContentState.Content &&
            (
                intent is SharedHomeIntent.Start ||
                    intent is SharedHomeIntent.Refresh ||
                    intent is SharedHomeIntent.ForegroundRefresh ||
                    intent is SharedHomeIntent.SelectServer ||
                    intent is SharedHomeIntent.CloseDirectMessage ||
                    intent is SharedHomeIntent.ArchiveChannel ||
                    // Reading a conversation must not resurrect its old count on the next cold start.
                    intent is SharedHomeIntent.ApplyReadReceiptPrediction
                )

    override fun shouldRemove(previous: SharedHomeState, intent: SharedHomeIntent, next: SharedHomeState): Boolean =
        next.content is SharedHomeContentState.Error &&
            next.content.code == "unauthenticated"

    override fun partialize(state: SharedHomeState): HomeRowHintsSnapshot? {
        val content = state.content as? SharedHomeContentState.Content ?: return null
        return content.toRowHintsSnapshot()
    }
}

internal fun SharedHomeContentState.Content.toRowHintsSnapshot(): HomeRowHintsSnapshot {
    val base = baseSnapshot
    val hints =
        (base.channels + base.directMessages).mapNotNull { row ->
            val id = row.threadId.value
            val prior = restoredRowHints[id]
            val unread = if (row.unreadCountKnown) row.unreadCount.coerceAtLeast(0) else prior?.unreadCount ?: 0
            val rowHasPreview = !row.lastMessagePreview.isNullOrBlank()
            val preview = (if (rowHasPreview) row.lastMessagePreview else prior?.lastMessagePreview)
                ?.take(HomeRowHintPreviewMaxChars)
            if (unread <= 0 && preview == null) return@mapNotNull null
            id to
                HomeRowRestoredHint(
                    unreadCount = unread,
                    lastMessagePreview = preview,
                    lastMessageAt = if (rowHasPreview) row.lastMessageAt else prior?.lastMessageAt ?: row.lastMessageAt,
                    lastMessageSenderName =
                        if (rowHasPreview) row.lastMessageSenderName else prior?.lastMessageSenderName
                )
        }.toMap()
    return HomeRowHintsSnapshot(
        serverId = base.server.id.value,
        memberId = base.currentMember.id.value,
        hints = hints
    )
}

/**
 * Hints the sidebar may show: only while this content is still the durable cache. The row
 * projection additionally ignores a hint once the row's own count is known (a read prediction
 * or realtime event), because that is newer than anything restored.
 */
internal fun SharedHomeContentState.Content.displayRowHints(): Map<String, HomeRowRestoredHint> =
    if (isDurableIdentityCache || rowHintsRetained) restoredRowHints else emptyMap()

/**
 * Task #164: true when this content's row counts came from a network Home (live), false while it
 * shows only the durable cache's last-known hints (cold start, server switch, sign-in).
 */
internal fun SharedHomeContentState.Content.showsLiveUnread(): Boolean =
    !isDurableIdentityCache && !rowHintsRetained

/**
 * Task #164: a network Home whose unread was frozen (stale or unavailable) over content that still
 * showed the restored last-known counts keeps showing them, instead of rows with no count, until
 * a load with fresh unread replaces the content.
 */
internal fun SharedHomeContentState.Content.keepingLastKnownRowHintsFrom(
    previous: SharedHomeContentState.Content?,
    unreadFrozen: Boolean
): SharedHomeContentState.Content {
    if (!unreadFrozen || previous == null) return this
    if (previous.baseSnapshot.server.id != baseSnapshot.server.id || previous.session.user.id != session.user.id) return this
    val hints = previous.displayRowHints()
    if (hints.isEmpty()) return this
    return copy(restoredRowHints = hints, rowHintsRetained = true)
}

private fun HomeSnapshot.homeRowIds(): Set<String> =
    (channels.map { it.threadId.value } + directMessages.map { it.threadId.value }).toSet()

internal fun homePersistScope(previous: SharedHomeState, reduced: SharedHomeState): Pair<String, ServerId>? {
    val session = reduced.content.homeSessionOrNull() ?: previous.content.homeSessionOrNull() ?: return null
    val serverId =
        reduced.content.homeServerIdOrNull()
            ?: previous.content.homeServerIdOrNull()
            ?: session.currentServerId?.let(::ServerId)
            ?: return null
    val userId = session.user.id.takeIf { it.isNotBlank() } ?: return null
    return userId to serverId
}
