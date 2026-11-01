package build.raft.app.desktop

import androidx.compose.runtime.snapshots.SnapshotStateList
import build.raft.app.RAFT_APP_SHELL_PAGE
import build.raft.app.cmp.host.RaftCmpNavigator
import build.raft.app.nav.RaftPageStack
import build.raft.app.nav.RaftStackPage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.TimeZone

/** One open page of the window's stack ([RaftStackPage], shared with the other hosts). */
typealias RaftDesktopPage = RaftStackPage

/**
 * The window's page back stack: :shared's [RaftPageStack] (open / replace / close, the root page
 * never closes) drawn by [RaftDesktopHost]'s `NavDisplay`, plus the desktop's history input. Pages
 * carry the host time context ([withRaftDesktopHostTimeContext]).
 */
class RaftDesktopNavigator {
    val stack = RaftPageStack(hostPageData = { it.withRaftDesktopHostTimeContext() }, log = ::log)

    /** The back stack, bottom first; the last entry is the page on screen. */
    val pages: SnapshotStateList<RaftDesktopPage> get() = stack.pages

    fun navigatorFor(page: RaftDesktopPage): RaftCmpNavigator = stack.navigatorFor(page)

    fun open(pageName: String, pageData: JsonObject, from: RaftDesktopPage?) = stack.open(pageName, pageData, from)

    fun close(page: RaftDesktopPage) = stack.close(page)

    fun back(): Boolean = stack.back()

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
    const val APP_SHELL = RAFT_APP_SHELL_PAGE
}

internal fun log(message: String) = println("RaftDesktop: $message")
