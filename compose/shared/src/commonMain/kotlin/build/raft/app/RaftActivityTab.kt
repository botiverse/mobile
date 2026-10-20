package build.raft.app

/**
 * Activity as an app-shell bottom tab (artin 2026-10-20): 首页 · 动态 · 任务 · 成员 · 设置.
 *
 * The tab hosts the existing Activity page content ([SharedHomeActivityScreen]) under a main-tab
 * header (icon sticker + title + stats line, no back arrow), with the three Activity segments
 * 全部 · 未读 · 提及. The notification center stays the Home head's Bell popover (task #18) — it
 * is not part of Activity.
 */

/** `slockInitialTab` values that name the old Home 动态 row / Activity page. */
private val RaftActivityTabAliases = setOf("activity", "inbox", "home_activity", HOME_ACTIVITY_PAGE)

/**
 * Resolves the launch tab from `slockInitialTab`. Activity aliases land on the Activity tab;
 * everything else keeps the retained-tab clamp ([retainedAppShellSelectedTab]).
 */
internal fun raftAppShellLaunchTab(rawTab: String?): String {
    val tab = rawTab?.trim().orEmpty()
    return if (tab in RaftActivityTabAliases) "activity" else retainedAppShellSelectedTab(tab)
}
