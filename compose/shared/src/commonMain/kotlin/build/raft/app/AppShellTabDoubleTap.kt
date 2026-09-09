package build.raft.app

internal const val AppShellBottomTabDoubleTapWindowMs: Long = 400L

internal enum class AppShellBottomTabTapAction {
    Select,
    ArmCurrent,
    ScrollCurrentToTop,
}

/**
 * Turns the renderer-neutral stream of bottom-tab click callbacks into a deterministic
 * double-tap action. Native and shared tab bars both emit the same single-click callback,
 * so recognition belongs at the app-shell interaction boundary rather than in three
 * platform renderers.
 */
internal class AppShellBottomTabTapTracker(
    private val doubleTapWindowMs: Long = AppShellBottomTabDoubleTapWindowMs,
) {
    private var armedTab: String? = null
    private var armedAtMs: Long = Long.MIN_VALUE

    fun onTap(
        selectedTab: String,
        tappedTab: String,
        nowMs: Long,
    ): AppShellBottomTabTapAction {
        val normalizedSelected = retainedAppShellSelectedTab(selectedTab)
        val normalizedTapped = retainedAppShellSelectedTab(tappedTab)
        val supportsScrollToTop = normalizedTapped in AppShellScrollToTopTabs
        val elapsed = nowMs - armedAtMs
        val completesDoubleTap =
            supportsScrollToTop &&
                armedTab == normalizedTapped &&
                elapsed in 0..doubleTapWindowMs

        if (completesDoubleTap && normalizedSelected == normalizedTapped) {
            clear()
            return AppShellBottomTabTapAction.ScrollCurrentToTop
        }

        if (supportsScrollToTop) {
            armedTab = normalizedTapped
            armedAtMs = nowMs
        } else {
            clear()
        }

        return if (normalizedSelected == normalizedTapped) {
            AppShellBottomTabTapAction.ArmCurrent
        } else {
            AppShellBottomTabTapAction.Select
        }
    }

    private fun clear() {
        armedTab = null
        armedAtMs = Long.MIN_VALUE
    }
}

internal val AppShellScrollToTopTabs: Set<String> = setOf("home", "dms", "tasks", "members")

internal data class AppShellTabScrollToTopRequests(
    val home: Int = 0,
    val dms: Int = 0,
    val tasks: Int = 0,
    val members: Int = 0,
) {
    fun request(tab: String): AppShellTabScrollToTopRequests =
        when (retainedAppShellSelectedTab(tab)) {
            "home" -> copy(home = home + 1)
            "dms" -> copy(dms = dms + 1)
            "tasks" -> copy(tasks = tasks + 1)
            "members" -> copy(members = members + 1)
            else -> this
        }

    fun forTab(tab: String): Int =
        when (retainedAppShellSelectedTab(tab)) {
            "home" -> home
            "dms" -> dms
            "tasks" -> tasks
            "members" -> members
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
