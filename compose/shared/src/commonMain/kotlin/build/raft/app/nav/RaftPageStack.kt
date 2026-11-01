package build.raft.app.nav

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import build.raft.app.RAFT_APP_SHELL_PAGE
import build.raft.app.cmp.host.RaftCmpNavigator
import build.raft.app.cmp.host.RaftCmpPageArgs
import build.raft.app.cmp.host.RaftHostEvents
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * One open page: the Android Activity / iOS view controller counterpart, and the Navigation 3 key
 * of its back stack entry. Identity is the instance; [key] is what a host saves, [args] what the
 * page renders with (the key's pageData plus the host's facts).
 */
class RaftStackPage internal constructor(
    val key: RaftPageKey,
    val args: RaftCmpPageArgs,
) {
    val events = RaftHostEvents()

    /** The entry's Navigation 3 content key ([RaftPageKey.contentKey]). */
    val contentKey: String
        get() = key.contentKey

    override fun toString(): String = "RaftStackPage($contentKey)"
}

/**
 * A window's page back stack ([pages], drawn by a `NavDisplay` with [RaftStackSceneStrategy]). Same
 * routing contract as the Android host (RaftCmpActivity): `slockReplaceCurrentPage` replaces the
 * opener and opening the app shell starts a fresh stack. Unlike Android, the root page never
 * closes: Back on it does nothing, closePage from the root page is ignored even while pages are
 * open over it (Tenny; artin's Esc in a chat quit the desktop app). Shared pages reach it through
 * [navigatorFor] (the unchanged [RaftCmpNavigator] `openPage` / `closePage`).
 *
 * [hostPageData] adds the host's facts (time context, insets, ...) to each page's args (page name,
 * key pageData); [log] gets one line per open / close.
 *
 * Saving: a host keeps [savedKeys] (identity only, the pages over the root) and hands them back to
 * [restore] in a new stack; the id serial restarts above the highest restored id, so a page opened
 * after a restore never reuses a restored page's content key (its saved UI state).
 */
class RaftPageStack(
    private val hostPageData: (pageName: String, pageData: JsonObject) -> JsonObject = { _, data -> data },
    private val log: (String) -> Unit = {},
) {
    private var nextId = 0L

    /** The back stack, bottom first; the last entry is the page on screen. */
    val pages: SnapshotStateList<RaftStackPage> = mutableStateListOf(page(RAFT_APP_SHELL_PAGE, JsonObject(emptyMap())))

    fun navigatorFor(page: RaftStackPage): RaftCmpNavigator =
        object : RaftCmpNavigator {
            override fun openPage(pageName: String, pageData: JsonObject) = open(pageName, pageData, from = page)

            override fun closePage() = close(page)
        }

    fun open(pageName: String, pageData: JsonObject, from: RaftStackPage?) {
        log("open page=$pageName from=${from?.args?.pageName} data=${pageData.toString().take(200)}")
        val replace = (pageData[RAFT_REPLACE_CURRENT_PAGE] as? JsonPrimitive)?.booleanOrNull == true
        val next = page(pageName, pageData)
        when {
            pageName == RAFT_APP_SHELL_PAGE -> {
                pages.clear()
                pages.add(next)
            }
            replace && from != null && pages.contains(from) -> pages[pages.indexOf(from)] = next
            else -> pages.add(next)
        }
    }

    fun close(page: RaftStackPage) {
        // The bottom entry stays, covered or not: the shell's root back handler calls closePage
        // when it has nothing left to pop (Android finishes the Activity there).
        if (page === pages.firstOrNull()) {
            log("close page=${page.args.pageName} ignored: root page")
            return
        }
        log("close page=${page.args.pageName}")
        pages.remove(page)
    }

    /** Closes the top page; false (nothing done) when only the root page is left. */
    fun back(): Boolean {
        if (pages.size <= 1) return false
        close(pages.last())
        return true
    }

    /** The keys of the pages over the root page, bottom first: what a host saves. */
    fun savedKeys(): List<RaftPageKey> = pages.drop(1).map { it.key }

    /**
     * Puts [keys] (a [savedKeys] list) back over the root page. Only a stack with nothing open over
     * its root accepts them; keys that repeat a content key, or reuse the root's, are refused whole
     * (false, nothing changed). The id serial continues above the highest restored id.
     */
    fun restore(keys: List<RaftPageKey>): Boolean {
        if (keys.isEmpty()) return true
        val root = pages.firstOrNull() ?: return false
        val contentKeys = keys.map { it.contentKey }
        if (pages.size != 1 || contentKeys.toSet().size != keys.size || root.contentKey in contentKeys) {
            log("restore refused pages=${pages.size} keys=$contentKeys")
            return false
        }
        pages.addAll(keys.map { key -> RaftStackPage(key, RaftCmpPageArgs(key.pageName, hostPageData(key.pageName, key.pageData))) })
        nextId = maxOf(nextId, keys.maxOf { it.id } + 1)
        log("restore pages=$contentKeys nextId=$nextId")
        return true
    }

    private fun page(pageName: String, pageData: JsonObject): RaftStackPage {
        val key =
            RaftPageKey.create(pageName, pageData, nextId++) { field ->
                log("open page=$pageName dropped non-identity pageData key=$field")
            }
        return RaftStackPage(key, RaftCmpPageArgs(pageName, hostPageData(pageName, key.pageData)))
    }
}
