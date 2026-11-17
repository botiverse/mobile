package build.raft.app

/** How many earlier tabs Back can walk through. */
internal const val AppShellTabHistoryCap = 8

/**
 * The tab roots Back can return to: the phone bar's tabs (the server tab opens a panel and its
 * pages, Members and Settings, carry their own Back to the page they were opened from), or the
 * wide rail's tab entries (Search there is the rail's inline cover, not a tab).
 */
internal fun appShellTabHistoryRoots(navRailActive: Boolean): Set<String> =
    if (navRailActive) {
        (RaftNavPresentation.Rail.destinations + RaftNavPresentation.Rail.footer)
            .map { it.id }
            .filter { retainedAppShellSelectedTab(it) == it }
            .toSet()
    } else {
        AppShellPhoneBottomTabKeys.filter { it !in AppShellMoreChildTabKeys }.toSet()
    }

/**
 * Why the shell switched tab: a [User] pick records the tab it left; a [Back] switch records
 * nothing (and drops the tab it lands on); a [Programmatic] switch (deep link, notification,
 * server switch, sign-out) starts a fresh history.
 */
internal enum class AppShellTabSwitchSource { User, Back, Programmatic }

/**
 * The tabs the person picked before the current one, oldest first (artin 2026-11-02: Activity ->
 * Search -> Back is Activity, not Home). Back on a tab root returns to the newest earlier tab; with
 * none left it goes to Home, and on Home it leaves the app as before.
 */
internal data class AppShellTabHistory(val tabs: List<String> = emptyList()) {
    // Tab keys are the shell's retained keys (retainedAppShellSelectedTab), as selectedTab holds.

    /** The person picked [to] while [from] showed: [from] moves to the top, [to] leaves the list. */
    fun selected(
        from: String,
        to: String,
        roots: Set<String>,
        cap: Int = AppShellTabHistoryCap,
    ): AppShellTabHistory {
        val kept = tabs.filter { it != from && it != to }
        val pushed = if (from != to && from in roots) kept + from else kept
        return AppShellTabHistory(pushed.takeLast(cap))
    }

    /** Back (or the server page's Back) landed on [tab]: it is current now, not an earlier tab. */
    fun arrivedByBack(tab: String): AppShellTabHistory =
        AppShellTabHistory(tabs.filter { it != tab })

    /** Drops tabs that are no longer available. */
    fun sanitized(allowed: Collection<String>): AppShellTabHistory =
        AppShellTabHistory(tabs.filter { it in allowed })

    /**
     * Where Back on the [current] tab root goes: the newest earlier tab still among [roots], else
     * Home when [current] is another tab, else null (Home with nothing earlier: leave the app).
     */
    fun backTarget(current: String, roots: Set<String>): String? {
        return tabs.lastOrNull { it != current && it in roots } ?: "home".takeIf { current != "home" }
    }
}
