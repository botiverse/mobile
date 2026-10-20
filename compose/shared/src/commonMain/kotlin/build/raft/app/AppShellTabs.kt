package build.raft.app


/**
 * Per-key bottom-tab icon. Activity uses the Lucide `activity` glyph, the same icon as the web
 * Activity nav item (artin 2026-10-20, Activity as a bottom tab); DMs/More keep the #94 icons.
 */
internal fun appShellTabIcon(key: String): RaftLucideIconName =
    when (key) {
        "home" -> RaftLucideIconName.House
        "dms" -> RaftLucideIconName.MessageCircle
        "activity" -> RaftLucideIconName.Activity
        "more" -> RaftLucideIconName.Ellipsis
        "tasks" -> RaftLucideIconName.SquareCheckBig
        "members" -> RaftLucideIconName.Users
        "settings" -> RaftLucideIconName.Settings
        else -> RaftLucideIconName.Circle
    }

/**
 * task #49: the wide-screen rail lists its entries in the web LeftRail order — Search, Chat
 * (Home, with DMs beside it), Activity, Tasks, Members. Entries the web rail does not have keep
 * their relative order after these. Settings stays pinned to the rail bottom separately.
 */
internal val AppShellRailWebOrder: List<String> =
    listOf(
        AppShellRailSearchKey,
        "home",
        "dms",
        "activity",
        "tasks",
        "members",
    )

internal fun <T> List<T>.orderedLikeWebRail(key: (T) -> String): List<T> =
    sortedBy { item ->
        AppShellRailWebOrder.indexOf(key(item)).let { if (it < 0) AppShellRailWebOrder.size else it }
    }
