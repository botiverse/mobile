package build.raft.app.nav

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The saved form of a [RaftPageStack]: its [RaftPageStack.savedKeys] as JSON, identity only (the
 * keys carry no content, AGENTS.md route rule). Android keeps it in the app shell's
 * `onSaveInstanceState` bundle, so it is size guarded: a stack over [RAFT_PAGE_STACK_SAVED_MAX_PAGES]
 * pages or [RAFT_PAGE_STACK_SAVED_MAX_CHARS] characters is not saved and the window comes back on
 * its root page (the app shell), as after a fresh launch.
 */
object RaftPageStackSavedState {
    private val keysSerializer = ListSerializer(RaftPageKey.serializer())
    private val json = Json { ignoreUnknownKeys = true }

    /** [keys] as saved text, or null when they are over the size guard (nothing is saved). */
    fun encode(keys: List<RaftPageKey>): String? {
        if (keys.size > RAFT_PAGE_STACK_SAVED_MAX_PAGES) return null
        return json.encodeToString(keysSerializer, keys).takeIf { it.length <= RAFT_PAGE_STACK_SAVED_MAX_CHARS }
    }

    /**
     * The keys in [text]; null when there is nothing usable (absent, over the size guard, not a key
     * list, a key carrying content, or a page [isRestorable] refuses): the host then shows only the
     * root page. All or nothing, a partly restored stack would put pages in an order nobody opened.
     */
    fun decode(text: String?, isRestorable: (RaftPageKey) -> Boolean): List<RaftPageKey>? {
        if (text.isNullOrBlank() || text.length > RAFT_PAGE_STACK_SAVED_MAX_CHARS) return null
        val keys = runCatching { json.decodeFromString(keysSerializer, text) }.getOrNull() ?: return null
        if (keys.size > RAFT_PAGE_STACK_SAVED_MAX_PAGES || !keys.all(isRestorable)) return null
        return keys
    }
}

/** Most pages a saved stack keeps (deeper stacks come back on the root page). */
const val RAFT_PAGE_STACK_SAVED_MAX_PAGES: Int = 32

/** Largest saved stack text, well under the ~500 KB Binder budget of a saved-state bundle. */
const val RAFT_PAGE_STACK_SAVED_MAX_CHARS: Int = 32 * 1024
