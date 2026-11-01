package build.raft.app

import build.raft.app.data.RaftNavDestination
import build.raft.app.resources.*
import androidx.compose.runtime.Composable
import build.raft.designsystem.components.RaftConversationKindGlyph
import org.jetbrains.compose.resources.StringResource

// One navigation model for every platform (artin, #proj-mobile 10-30): the app's top-level
// destinations live in [RaftAppDestination], and every navigation surface — the phone bottom tab
// bar, the tablet rail, the Compose 版's sidebar (the same rail in a desktop window) — reads them
// through a [RaftNavPresentation] that picks which entries it shows for the window width. Labels,
// icons, attention dots and what a tap does come from the destination, never from the surface.
// A future Navigation 3 back stack keys on [RaftAppDestination.id] (desktop Nav3 is #3955).

/** How a destination draws its icon. */
internal sealed interface RaftNavIcon {
    /** The Lucide name; a glyph icon's stand-in where only a Lucide name fits (the iOS system bar). */
    val lucide: RaftLucideIconName

    data class Lucide(override val lucide: RaftLucideIconName) : RaftNavIcon

    /** A raft-ui glyph (web `ChatIcon`), drawn by [RaftConversationKindIcon][build.raft.designsystem.components.RaftConversationKindIcon]. */
    data class Glyph(val glyph: RaftConversationKindGlyph, override val lucide: RaftLucideIconName) : RaftNavIcon
}

/** The one source of a destination's attention dot (raft-design Badge: surfaces show a dot, never a count). */
internal enum class RaftNavBadgeSource {
    None,

    /** Activity unread ([appShellActivityTabShowsDot]). */
    ActivityUnread,

    /** Anything new on the server's pages ([RaftMoreAttention]): the phone's server tab, before its own rule. */
    ServerPages,

    /** An unseen offline/outdated computer ([RaftMoreAttention.Computers]). */
    ComputersAttention,

    /** An app update, read in Settings > About ([RaftMoreAttention.AppUpdate]). */
    AppUpdate,

    /**
     * An App waiting for an owner/admin review (web `useAppsPageAttention`: `needsAdminReview` of
     * `GET /integrations/permission-updates`). The app does not load it yet, so it never lights.
     */
    AppsReview,
}

/** What choosing a destination does. */
internal enum class RaftNavRoute {
    /** Selects the shell tab [RaftAppDestination.id]. */
    ShellTab,

    /** Opens a page over the shell (the page its Settings row opens); never selected. */
    Page,

    /** Opens a menu beside the trigger (Help); selected while it is open. */
    Menu,

    /** Opens the server panel (the phone's server tab); never selected. */
    ServerPanel,
}

/**
 * The app's top-level destinations. [id] is stable (it is the persisted shell tab key and the
 * future Nav3 back-stack key): never rename one. [label] and [icon] are the web LeftRail's
 * (`layout.leftRail.*`, lucide-react / raft-ui icons); [compactLabel] / [compactIcon] are what
 * the phone bar has always drawn where it differs (Home's house, Tasks' square-check), kept so the
 * phone bar does not change.
 */
internal enum class RaftAppDestination(
    val id: String,
    val label: StringResource,
    val icon: RaftNavIcon,
    val badge: RaftNavBadgeSource,
    val route: RaftNavRoute,
    /** The product-event destination (`NAV_DESTINATIONS`); null sends no nav event. */
    val navEvent: RaftNavDestination?,
    val compactLabel: StringResource = label,
    val compactIcon: RaftNavIcon = icon,
) {
    Chat(
        id = "home",
        label = Res.string.tab_chat,
        icon = RaftNavIcon.Glyph(RaftConversationKindGlyph.Chat, RaftLucideIconName.MessageSquare),
        badge = RaftNavBadgeSource.None,
        route = RaftNavRoute.ShellTab,
        navEvent = RaftNavDestination.Home,
        compactLabel = Res.string.tab_home,
        compactIcon = RaftNavIcon.Lucide(RaftLucideIconName.House),
    ),
    Activity(
        id = "activity",
        label = Res.string.tab_activity,
        icon = RaftNavIcon.Lucide(RaftLucideIconName.Activity),
        badge = RaftNavBadgeSource.ActivityUnread,
        route = RaftNavRoute.ShellTab,
        navEvent = RaftNavDestination.Activity,
    ),
    Tasks(
        id = "tasks",
        label = Res.string.tab_tasks,
        icon = RaftNavIcon.Lucide(RaftLucideIconName.ListTodo),
        badge = RaftNavBadgeSource.None,
        route = RaftNavRoute.ShellTab,
        navEvent = RaftNavDestination.Tasks,
        compactIcon = RaftNavIcon.Lucide(RaftLucideIconName.SquareCheckBig),
    ),
    Search(
        id = "search",
        label = Res.string.home_nav_search,
        icon = RaftNavIcon.Lucide(RaftLucideIconName.Search),
        badge = RaftNavBadgeSource.None,
        route = RaftNavRoute.ShellTab,
        navEvent = RaftNavDestination.Search,
    ),
    Members(
        id = "members",
        label = Res.string.tab_members,
        icon = RaftNavIcon.Lucide(RaftLucideIconName.Users),
        badge = RaftNavBadgeSource.None,
        route = RaftNavRoute.ShellTab,
        navEvent = RaftNavDestination.Members,
    ),
    Computers(
        id = "computers",
        label = Res.string.tab_computers,
        icon = RaftNavIcon.Lucide(RaftLucideIconName.Monitor),
        badge = RaftNavBadgeSource.ComputersAttention,
        route = RaftNavRoute.Page,
        navEvent = RaftNavDestination.Computers,
    ),
    Apps(
        id = "apps",
        label = Res.string.tab_apps,
        icon = RaftNavIcon.Lucide(RaftLucideIconName.Blocks),
        badge = RaftNavBadgeSource.AppsReview,
        route = RaftNavRoute.Page,
        // NAV_DESTINATIONS has `apps`; the app's mirror (RaftNavDestination) not yet.
        navEvent = null,
    ),
    /** The phone bar's last tab: the current server, opening the server panel (raft-design #712, D1a). */
    Server(
        id = "more",
        label = Res.string.tab_more,
        icon = RaftNavIcon.Lucide(RaftLucideIconName.Ellipsis),
        badge = RaftNavBadgeSource.ServerPages,
        route = RaftNavRoute.ServerPanel,
        navEvent = RaftNavDestination.More,
    ),
    Help(
        id = "help",
        label = Res.string.tab_help,
        icon = RaftNavIcon.Lucide(RaftLucideIconName.CircleQuestionMark),
        badge = RaftNavBadgeSource.None,
        route = RaftNavRoute.Menu,
        navEvent = null,
    ),
    Settings(
        id = "settings",
        label = Res.string.tab_settings,
        icon = RaftNavIcon.Lucide(RaftLucideIconName.Settings),
        badge = RaftNavBadgeSource.AppUpdate,
        route = RaftNavRoute.ShellTab,
        navEvent = RaftNavDestination.Settings,
    ),
    ;

    companion object {
        fun fromId(id: String): RaftAppDestination? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Which destinations a navigation surface shows, by window width ([raftNavPresentation]): below
 * the wide breakpoint ([DualPaneMinimumWidthDp]) the phone bar, unchanged (stdrc 2026-10-21);
 * from it the rail with the web LeftRail's set (Direction A, #proj-mobile 10-30), [footer] pinned
 * to its bottom.
 */
internal enum class RaftNavPresentation(
    val destinations: List<RaftAppDestination>,
    val footer: List<RaftAppDestination> = emptyList(),
) {
    PhoneBar(
        listOf(
            RaftAppDestination.Chat,
            RaftAppDestination.Activity,
            RaftAppDestination.Tasks,
            RaftAppDestination.Search,
            RaftAppDestination.Server,
        ),
    ),
    Rail(
        listOf(
            RaftAppDestination.Chat,
            RaftAppDestination.Activity,
            RaftAppDestination.Tasks,
            RaftAppDestination.Members,
            RaftAppDestination.Computers,
            RaftAppDestination.Apps,
        ),
        // The web heads this footer with its "collapse sidebar" toggle (SidebarToggleRailItem).
        // It is an action on the shell, not a destination, so it is not listed here: the rail
        // adds it (RaftRailSidebarToggle) where a list column sits beside the main area
        // (appShellCollapsibleColumn).
        footer = listOf(RaftAppDestination.Help, RaftAppDestination.Settings),
    ),
    ;

    /** This surface's label for [destination]. */
    fun labelOf(destination: RaftAppDestination): StringResource =
        if (this == PhoneBar) destination.compactLabel else destination.label

    /** This surface's icon for [destination]. */
    fun iconOf(destination: RaftAppDestination): RaftNavIcon =
        if (this == PhoneBar) destination.compactIcon else destination.icon
}

/** The surface for the window: the rail whenever the wide layout is on ([raftAdaptiveNavRailActive]). */
internal fun raftNavPresentation(navRailActive: Boolean): RaftNavPresentation =
    if (navRailActive) RaftNavPresentation.Rail else RaftNavPresentation.PhoneBar

/** What the badge sources read: the shell's Activity unread count and server-page signals. */
internal data class RaftNavBadgeInputs(
    val activityUnreadCount: Int = 0,
    val moreAttention: Set<RaftMoreAttention> = emptySet(),
)

/** Whether [destination] wears its attention dot; a selected destination never does. */
internal fun raftNavShowsBadge(destination: RaftAppDestination, inputs: RaftNavBadgeInputs, selected: Boolean): Boolean =
    when (destination.badge) {
        RaftNavBadgeSource.None, RaftNavBadgeSource.AppsReview -> false
        RaftNavBadgeSource.ActivityUnread -> appShellActivityTabShowsDot(inputs.activityUnreadCount, selected)
        RaftNavBadgeSource.ServerPages -> appShellMoreTabShowsDot(inputs.moreAttention, selected)
        RaftNavBadgeSource.ComputersAttention -> !selected && RaftMoreAttention.Computers in inputs.moreAttention
        RaftNavBadgeSource.AppUpdate -> !selected && RaftMoreAttention.AppUpdate in inputs.moreAttention
    }

/** The count behind the dot for the iOS system bar (it has no small dot): Activity's unread, else 0. */
internal fun raftNavNativeBadgeCount(destination: RaftAppDestination, inputs: RaftNavBadgeInputs, selected: Boolean): Int =
    if (destination.badge == RaftNavBadgeSource.ActivityUnread && raftNavShowsBadge(destination, inputs, selected)) {
        inputs.activityUnreadCount
    } else {
        0
    }

/**
 * [presentation]'s items for [ids] (its destinations, or [RaftNavPresentation.footer]): label and
 * icon from the destination as that surface draws it, selected by [selectedKey] for a shell tab
 * (a page never; a menu while [menuOpen]), the dot from the destination's one badge source. An
 * id that is no destination (the legacy `dms`) keeps the old fallback icon and no dot.
 */
internal fun raftNavItems(
    presentation: RaftNavPresentation,
    ids: List<String>,
    selectedKey: String,
    labelFor: (String) -> String,
    badges: RaftNavBadgeInputs = RaftNavBadgeInputs(),
    menuOpen: Boolean = false,
): List<RaftBottomTabItemPayload> =
    ids.map { id ->
        val destination = RaftAppDestination.fromId(id)
        val selected =
            when (destination?.route) {
                RaftNavRoute.Page -> false
                RaftNavRoute.Menu -> menuOpen
                else -> selectedKey == id
            }
        RaftBottomTabItemPayload(
            key = id,
            label = labelFor(id),
            iconName = destination?.let { presentation.iconOf(it).lucide } ?: appShellLegacyTabIcon(id),
            selected = selected,
            attentionDot = destination != null && raftNavShowsBadge(destination, badges, selected),
            nativeBadgeCount = destination?.let { raftNavNativeBadgeCount(it, badges, selected) } ?: 0,
        )
    }

/** The raft-ui glyph [key] draws on [presentation] instead of its Lucide icon (the rail's Chat). */
internal fun raftNavGlyph(presentation: RaftNavPresentation, key: String): RaftConversationKindGlyph? =
    RaftAppDestination.fromId(key)?.let { (presentation.iconOf(it) as? RaftNavIcon.Glyph)?.glyph }

/** [presentation]'s labels by destination id, in the app language. */
@Composable
internal fun raftNavLabels(presentation: RaftNavPresentation): Map<String, String> =
    (presentation.destinations + presentation.footer).associate { it.id to stringResource(presentation.labelOf(it)) }
