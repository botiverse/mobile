package build.raft.app

import androidx.compose.ui.input.key.Key

/**
 * The wide layout's search panel (web `SearchOverlay`, `useSearchKeyboardSelection` in
 * `activateResultsInChat` mode): which rows the arrow keys move through, where the cursor is,
 * and what Return opens.
 *
 * The rows run in the order they are drawn: recent conversations (nothing typed), then, once
 * something is typed, an exact destination, the "Search for" row, the shown destinations, the
 * shown messages. "Show all" is a button, not a row.
 */
internal enum class HomeSearchOverlaySection { Recent, Exact, SearchFor, Entities, Messages }

/** One row the cursor can rest on: its [key] (stable across result waves) and [section]. */
internal data class HomeSearchOverlayRow(val key: String, val section: HomeSearchOverlaySection)

/** The "Search for" row's key (web `OVERLAY_ALL_RESULTS_KEY`). */
internal const val HomeSearchOverlaySearchForKey = "action:view-all-results"

/** The panel shows at most this many destinations before "Show all" (web `ENTITY_PREVIEW_COUNT`). */
internal const val HomeSearchOverlayEntityPreviewCount = 5

/** The panel previews at most this many messages (web `OVERLAY_MESSAGE_PREVIEW_LIMIT`). */
internal const val HomeSearchOverlayMessagePreviewCount = 5

/** Recent conversations with nothing typed (web `RECENT_CONVERSATION_ROW_LIMIT`). */
internal const val HomeSearchOverlayRecentLimit = 10

/** The panel's rows, in drawing order. [searchFor] is false with nothing typed. */
internal fun homeSearchOverlayRows(
    recentKeys: List<String> = emptyList(),
    exactKey: String? = null,
    searchFor: Boolean = false,
    entityKeys: List<String> = emptyList(),
    messageKeys: List<String> = emptyList(),
): List<HomeSearchOverlayRow> =
    buildList {
        recentKeys.forEach { add(HomeSearchOverlayRow(it, HomeSearchOverlaySection.Recent)) }
        exactKey?.let { add(HomeSearchOverlayRow(it, HomeSearchOverlaySection.Exact)) }
        if (searchFor) add(HomeSearchOverlayRow(HomeSearchOverlaySearchForKey, HomeSearchOverlaySection.SearchFor))
        entityKeys.forEach { add(HomeSearchOverlayRow(it, HomeSearchOverlaySection.Entities)) }
        messageKeys.forEach { add(HomeSearchOverlayRow(it, HomeSearchOverlaySection.Messages)) }
    }

/**
 * The cursor of the panel: the key of the row it is on, or null for "the first row" (a palette's
 * cursor starts at the top and returns there whenever the query changes, so Return always means
 * the first row for what was just typed).
 */
internal data class HomeSearchOverlayCursor(val selectedKey: String? = null) {
    /** The row index the cursor is on in [rows]: its key's row, else the first; -1 when there are none. */
    fun index(rows: List<HomeSearchOverlayRow>): Int {
        if (rows.isEmpty()) return -1
        val found = selectedKey?.let { key -> rows.indexOfFirst { it.key == key } } ?: -1
        return if (found >= 0) found else 0
    }

    /** The row the cursor is on, or null when there are no rows. */
    fun selected(rows: List<HomeSearchOverlayRow>): HomeSearchOverlayRow? = rows.getOrNull(index(rows))

    /**
     * One row down ([delta] 1) or up (-1). It stops at the first and the last row, as the web's
     * does (no wrap: holding ↓ never jumps back to the top).
     */
    fun move(rows: List<HomeSearchOverlayRow>, delta: Int): HomeSearchOverlayCursor {
        if (rows.isEmpty()) return this
        val next = (index(rows) + delta).coerceIn(0, rows.lastIndex)
        return HomeSearchOverlayCursor(rows[next].key)
    }

    /** The query changed: back to the top. */
    fun reset(): HomeSearchOverlayCursor = HomeSearchOverlayCursor()
}

/** What a key does in the panel. */
internal sealed interface HomeSearchOverlayKeyAction {
    data class Move(val delta: Int) : HomeSearchOverlayKeyAction

    /** Return: open the selected row. */
    data object Open : HomeSearchOverlayKeyAction
}

/**
 * ↑ / ↓ move, Return (not Shift+Return) opens; any other key, a key up, or a key while an input
 * method is composing ([composing]) is the field's. Escape is the panel's window's.
 */
internal fun homeSearchOverlayKeyAction(
    key: Key,
    keyDown: Boolean,
    shift: Boolean,
    composing: Boolean = false,
): HomeSearchOverlayKeyAction? {
    if (!keyDown || composing) return null
    return when (key) {
        Key.DirectionDown -> HomeSearchOverlayKeyAction.Move(1)
        Key.DirectionUp -> HomeSearchOverlayKeyAction.Move(-1)
        Key.Enter, Key.NumPadEnter -> if (shift) null else HomeSearchOverlayKeyAction.Open
        else -> null
    }
}

/**
 * The destination the query names exactly (web `findExactDestination`, simplified): a result
 * whose title is the query, ignoring case and a leading `#` or `@`. It ranks above "Search for",
 * so Return goes straight there.
 */
internal fun homeSearchOverlayExactKey(query: String, results: List<SearchEntityResult>): String? {
    val wanted = query.trim().trimStart('#', '@').trim().lowercase()
    if (wanted.isEmpty()) return null
    return results.firstOrNull { it.title.trim().trimStart('#', '@').lowercase() == wanted }?.key
}

/**
 * Recent conversations for the panel with nothing typed (web `selectRecentConversationEntities`):
 * the conversations visited most recently ([visitedThreadIds], newest first, from the wide
 * layout's history), then, while the list is short, the frequent destinations ([fill]); never
 * the conversation behind the panel ([excludeThreadId]), each once, at most [limit].
 */
internal fun homeSearchOverlayRecentConversations(
    visitedThreadIds: List<String>,
    candidates: List<SearchEntityResult>,
    fill: List<SearchEntityResult>,
    excludeThreadId: String?,
    limit: Int = HomeSearchOverlayRecentLimit,
): List<SearchEntityResult> {
    val byThread =
        candidates
            .filter { !it.archived && it.threadId != null && it.threadId != excludeThreadId }
            .filter { it.type == SearchEntityType.Channel || it.type == SearchEntityType.DirectMessage }
            .associateBy { it.threadId!! }
    val picked = LinkedHashMap<String, SearchEntityResult>()
    visitedThreadIds.forEach { threadId ->
        if (picked.size >= limit) return picked.values.toList()
        byThread[threadId]?.let { picked.putIfAbsent(it.key, it) }
    }
    fill.forEach { result ->
        if (picked.size >= limit) return picked.values.toList()
        if (result.archived || (result.threadId != null && result.threadId == excludeThreadId)) return@forEach
        picked.putIfAbsent(result.key, result)
    }
    return picked.values.toList()
}

/** The host window's ⌘K / Ctrl+K for the app shell (the desktop app: a key no field took). */
internal const val RAFT_SEARCH_SHORTCUT_EVENT = "slockSearchShortcut"

/**
 * ⌘K on Apple platforms, Ctrl+K elsewhere (web `isGlobalSearchShortcut`), with no Shift or Alt.
 * Key repeat is the caller's to ignore.
 */
internal fun raftSearchShortcut(
    keyDown: Boolean,
    key: Key,
    meta: Boolean,
    ctrl: Boolean,
    alt: Boolean,
    shift: Boolean,
    apple: Boolean,
): Boolean {
    if (!keyDown || key != Key.K || alt || shift) return false
    return if (apple) meta && !ctrl else ctrl && !meta
}

/**
 * The conversations the wide layout showed, newest first, each once ([RaftWideNavHistory]'s
 * entries with a conversation in the detail pane): the panel's Recent conversations.
 */
internal fun RaftWideNavHistory.visitedThreadIds(): List<String> =
    entries
        .asReversed()
        .mapNotNull { (it.detail as? RaftWideNavDetail.Conversation)?.identity?.threadId }
        .distinct()
