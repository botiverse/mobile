package build.raft.app

internal enum class AppShellBottomTabTapAction {
    /** Another tab: select it. */
    Select,
    /** The selected tab again, and it has no re-tap action. */
    Ignore,
    /** The selected tab again: scroll its list to the top. */
    ScrollCurrentToTop,
    /** The selected Search tab again: focus its field (nav spec: no autofocus on the switch). */
    FocusSearchField,
}

/**
 * The bottom-bar tap at the app-shell interaction boundary (native and shared bars emit the same
 * click callback). Re-tapping the selected tab scrolls its list to the top (Home, Tasks, More and
 * the rail's Members); Activity walks its unread rows instead (ActivityUnreadJump.kt, falling back
 * to the top), and a double re-tap there goes back to the top (ActivityRetapGesture, timed in the
 * Activity panel for both the Compose bar/rail and the iOS UITabBar); re-tapping the selected
 * Search tab focuses its field.
 */
internal fun appShellBottomTabTapAction(selectedTab: String, tappedTab: String): AppShellBottomTabTapAction {
    val normalizedSelected = retainedAppShellSelectedTab(selectedTab)
    val normalizedTapped = retainedAppShellSelectedTab(tappedTab)
    return when {
        normalizedSelected != normalizedTapped -> AppShellBottomTabTapAction.Select
        normalizedTapped == AppShellSearchTabKey -> AppShellBottomTabTapAction.FocusSearchField
        normalizedTapped in AppShellScrollToTopTabs -> AppShellBottomTabTapAction.ScrollCurrentToTop
        else -> AppShellBottomTabTapAction.Ignore
    }
}

internal val AppShellScrollToTopTabs: Set<String> = setOf("home", "activity", "dms", "tasks", "members", "more", "settings")

internal data class AppShellTabScrollToTopRequests(
    val home: Int = 0,
    val activity: Int = 0,
    val dms: Int = 0,
    val tasks: Int = 0,
    val members: Int = 0,
    // The More root (the Settings page; "settings" on the wide rail).
    val more: Int = 0,
) {
    fun request(tab: String): AppShellTabScrollToTopRequests =
        when (retainedAppShellSelectedTab(tab)) {
            "home" -> copy(home = home + 1)
            "activity" -> copy(activity = activity + 1)
            "dms" -> copy(dms = dms + 1)
            "tasks" -> copy(tasks = tasks + 1)
            "members" -> copy(members = members + 1)
            "more", "settings" -> copy(more = more + 1)
            else -> this
        }

    fun forTab(tab: String): Int =
        when (retainedAppShellSelectedTab(tab)) {
            "home" -> home
            "activity" -> activity
            "dms" -> dms
            "tasks" -> tasks
            "members" -> members
            "more", "settings" -> more
            else -> 0
        }
}

/**
 * A list owner consumes only request generations observed after it mounted. This prevents a
 * loading/content branch change or retained-view remount from replaying an already-completed
 * viewport replacement intent.
 */
internal class AppShellScrollToTopRequestFence(initialRequest: Int) {
    private var consumedRequest: Int = initialRequest

    fun consume(request: Int): Boolean {
        if (request <= consumedRequest) return false
        consumedRequest = request
        return true
    }
}
