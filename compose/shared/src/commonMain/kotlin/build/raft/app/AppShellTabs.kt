package build.raft.app


/**
 * Per-key bottom-tab icon for both legacy and Slack-aligned tab sets. Slack-aligned icons
 * mirror the #94 preview fixture: Home=house, DMs=message circle, Activity=bell, More=ellipsis.
 */
internal fun appShellTabIcon(key: String): SlockLucideIconName =
    when (key) {
        "home" -> SlockLucideIconName.House
        "dms" -> SlockLucideIconName.MessageCircle
        "activity" -> SlockLucideIconName.Bell
        "more" -> SlockLucideIconName.Ellipsis
        "tasks" -> SlockLucideIconName.SquareCheckBig
        "members" -> SlockLucideIconName.Users
        "settings" -> SlockLucideIconName.Settings
        else -> SlockLucideIconName.Circle
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
