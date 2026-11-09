package build.raft.app.nav

import androidx.compose.runtime.staticCompositionLocalOf
import build.raft.app.AppShellInitialTabKey
import build.raft.app.raftAppShellLaunchTab
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The app shell tab under a stack's pages (the stack's root page is the shell). The phone Settings
 * is a shell tab, not a page of its own, so a stack restored after process death needs it too:
 * Settings > About restored over Home would make Back skip Settings. The shell writes its selected
 * tab here while a host provides [LocalRaftStackRootTab]; the host saves it beside the page keys and
 * gives it back to the shell on restore ([raftAppShellPageDataWithTab]). Identity only (a tab key).
 */
class RaftStackRootTab {
    var tab: String? = null
}

/** Provided by a host that draws the shell as a stack's root page; null elsewhere (no-op). */
val LocalRaftStackRootTab = staticCompositionLocalOf<RaftStackRootTab?> { null }

/**
 * The shell's pageData opening on [tab] (the shell's own launch tab key): unchanged for a missing,
 * unknown or Home tab.
 */
fun raftAppShellPageDataWithTab(pageData: JsonObject, tab: String?): JsonObject {
    val launchTab = tab?.let(::raftAppShellLaunchTab)
    if (launchTab == null || launchTab == "home") return pageData
    return JsonObject(pageData + (AppShellInitialTabKey to JsonPrimitive(launchTab)))
}
