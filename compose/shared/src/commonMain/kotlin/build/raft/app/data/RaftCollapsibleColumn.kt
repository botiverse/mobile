package build.raft.app.data

/**
 * The list column beside the main area that the wide rail's Collapse / Expand Sidebar toggle hides
 * (web sidebarToggle.ts `SidebarSlotColumn`). Each remembers its own state, so hiding one never
 * hides the other: the navigation sidebar (Home's channels, the Settings list) and the Activity
 * list beside its opened conversation.
 */
enum class RaftCollapsibleColumn(
    /** Preference key of this column's hidden state (web `slock:sidebarHidden` / `slock:activityListHidden`). */
    val storageKey: String,
) {
    Sidebar("mobile:sidebar_hidden"),
    ActivityList("mobile:activity_list_hidden"),
}
