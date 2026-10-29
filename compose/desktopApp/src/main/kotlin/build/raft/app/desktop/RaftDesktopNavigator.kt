package build.raft.app.desktop

import androidx.compose.runtime.mutableStateListOf
import build.raft.app.cmp.host.RaftCmpNavigator
import build.raft.app.cmp.host.RaftCmpPageArgs
import build.raft.app.cmp.host.RaftHostEvents
import build.raft.app.data.RaftDesktopLogFile
import build.raft.app.toRaftJsonString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.util.TimeZone

/**
 * One open page: the Android Activity / iOS view controller counterpart, and the Navigation 3 key
 * of its back stack entry. Identity is the instance (two opens of the same page and data are two
 * entries); [contentKey] is the entry's content key: page name plus a per-window serial.
 */
class RaftDesktopPage internal constructor(
    val id: Long,
    val args: RaftCmpPageArgs,
) {
    val events = RaftHostEvents()

    val contentKey: String
        get() = "${args.pageName}#$id"

    override fun toString(): String = "RaftDesktopPage($contentKey)"
}

/**
 * The window's Navigation 3 back stack ([pages], drawn by [RaftDesktopHost]'s `NavDisplay`). Same
 * routing contract as the Android host (RaftCmpActivity): `slockReplaceCurrentPage` replaces the
 * top page and the app shell starts a fresh stack. Unlike Android, the root page never closes: Back
 * on it (Esc, Cmd+[, the shell's root back handler calling closePage) does nothing, closePage from
 * the root page is ignored even while pages are open over it, and only closing
 * the window quits (Tenny; artin's Esc in a chat quit the app). Shared pages reach
 * it through [navigatorFor] (the unchanged [RaftCmpNavigator] `openPage` / `closePage`), which push
 * and pop entries here.
 */
class RaftDesktopNavigator {
    private var nextId = 0L

    /** The back stack, bottom first; the last entry is the page on screen. */
    val pages = mutableStateListOf(page(RaftDesktopPages.APP_SHELL, JsonObject(emptyMap())))

    fun navigatorFor(page: RaftDesktopPage): RaftCmpNavigator =
        object : RaftCmpNavigator {
            override fun openPage(pageName: String, pageData: JsonObject) = open(pageName, pageData, from = page)

            override fun closePage() = close(page)
        }

    fun open(pageName: String, pageData: JsonObject, from: RaftDesktopPage?) {
        log("open page=$pageName from=${from?.args?.pageName} data=${pageData.toRaftJsonString().take(200)}")
        val replace = (pageData[REPLACE_CURRENT_PAGE] as? JsonPrimitive)?.booleanOrNull == true
        val next = page(pageName, pageData)
        when {
            pageName == RaftDesktopPages.APP_SHELL -> {
                pages.clear()
                pages.add(next)
            }
            replace && from != null && pages.contains(from) -> pages[pages.indexOf(from)] = next
            else -> pages.add(next)
        }
    }

    fun close(page: RaftDesktopPage) {
        // The bottom entry stays, covered or not: the shell's root back handler calls closePage
        // when it has nothing left to pop (Android finishes the Activity there).
        if (page === pages.firstOrNull()) {
            log("close page=${page.args.pageName} ignored: root page")
            return
        }
        log("close page=${page.args.pageName}")
        pages.remove(page)
    }

    fun back(): Boolean {
        if (pages.size <= 1) return false
        close(pages.last())
        return true
    }

    /**
     * A history step from the keyboard or mouse ([RaftDesktopHistoryInput]): the app shell, alone on
     * the stack, gets it (it owns the wide layout's history); a page pushed over the shell takes
     * Back as closing itself and ignores Forward. True when something took it.
     */
    internal fun history(direction: RaftDesktopHistoryDirection): Boolean {
        val top = pages.lastOrNull() ?: return false
        return when (raftDesktopHistoryRoute(pages.size, top.args.pageName == RaftDesktopPages.APP_SHELL, direction)) {
            RaftDesktopHistoryRoute.Shell -> {
                log("history ${direction.wireName}")
                top.events.send(RaftDesktopHistoryInput.EVENT_NAME, RaftDesktopHistoryInput.eventData(direction))
                true
            }
            RaftDesktopHistoryRoute.ClosePage -> back()
            RaftDesktopHistoryRoute.None -> false
        }
    }

    private fun page(pageName: String, pageData: JsonObject) =
        RaftDesktopPage(nextId++, RaftCmpPageArgs(pageName, pageData.withRaftDesktopHostTimeContext()))

    private companion object {
        const val REPLACE_CURRENT_PAGE = "slockReplaceCurrentPage"
    }
}

/**
 * The host's local time facts in every page's data, as the Android (RaftCmpActivity) and iOS
 * (RaftPageViewController) hosts add them: without `localOffsetMinutes` the shared pages have no
 * time capabilities, no live time context, and every relative timestamp reads "unknown time".
 * Values the opener already set (a forwarded thread bundle) win.
 */
internal fun JsonObject.withRaftDesktopHostTimeContext(
    nowMillis: Long = System.currentTimeMillis(),
    timeZone: TimeZone = TimeZone.getDefault(),
): JsonObject {
    val offsetMinutes = timeZone.getOffset(nowMillis) / 60_000
    val defaults =
        mapOf(
            "localOffsetMinutes" to JsonPrimitive(offsetMinutes),
            "todayEpochDay" to JsonPrimitive(Math.floorDiv(nowMillis + offsetMinutes * 60_000L, 86_400_000L)),
            "nowEpochMillis" to JsonPrimitive(nowMillis),
        )
    if (defaults.keys.all { it in this }) return this
    return JsonObject(defaults + this)
}

/** Page names of the shared page registry the host opens itself. */
object RaftDesktopPages {
    const val APP_SHELL = "compose_slock_app_shell"
}

internal fun log(message: String) {
    println("RaftDesktop: $message")
    RaftDesktopLogFile.append("I", "RaftDesktop", message)
}
