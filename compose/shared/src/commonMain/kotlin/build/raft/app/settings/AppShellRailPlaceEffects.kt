package build.raft.app.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import build.raft.app.RaftAdaptiveDetailSelection
import build.raft.app.RaftAdaptiveWorkspaceState
import build.raft.app.shell.RaftRailChatTab
import build.raft.app.shell.RaftRailLanding
import build.raft.app.shell.RaftRailPlaces
import build.raft.app.shell.RaftWideNavEntry
import build.raft.app.ServerLastConversationRepository
import build.raft.app.base.BridgeModule
import build.raft.app.shell.chatConversation
import build.raft.app.data.SharedAdaptiveWorkspaceStore
import build.raft.app.data.SharedHomeShellIntent
import build.raft.app.data.SharedHomeShellStore
import build.raft.app.thread.dispatch
import build.raft.app.shell.withDefaultLandingRearmed

/*
 * The shell's rail places (RaftRailPlaces.kt), wired here and not in SettingsHomePage's RaftAppShellReadyContent,
 * whose method stays under the JVM method-size budget (scripts/ci/check_jvm_method_size.py): what the wide layout
 * shows becomes its tab's place, a rail click lands on the place of the tab it selects, and only Chat's conversation
 * becomes its server's last conversation.
 */

/**
 * The shell's rail places: [onShown] records what the wide layout shows, [land] lands a rail click.
 * [clearSelectedConversation] clears Home's selected row (the shell's viewed conversation), [log] is the shell's log.
 */
internal class AppShellRailPlaces(
    private val adaptiveWorkspaceStore: SharedAdaptiveWorkspaceStore,
    private val clearSelectedConversation: () -> Unit,
    private val log: (String) -> Unit,
) {
    /** Each rail tab's place per server, in memory for this shell. */
    var places: RaftRailPlaces = RaftRailPlaces()
        private set

    /**
     * The wide layout shows [shown]; [defaultLandingServerId] is the server where Chat's default landing has settled,
     * [landedByDefault] whether the conversation shown is that landing's pick.
     */
    fun onShown(shown: RaftWideNavEntry, defaultLandingServerId: String?, landedByDefault: Boolean) {
        places =
            places.remember(
                shown,
                landingSettled = defaultLandingServerId == shown.serverId,
                landedByDefault = landedByDefault,
            )
    }

    /**
     * Lands a click on rail [tab] ([RaftRailPlaces.landing]) once the shell handled it: [shown] is what the wide layout
     * showed before the click, [selectedTab] the shell's tab now, [openedOn] the surface that opened the shared
     * detail's conversation ([rememberRaftSelectionOpenedOn]). [restore] is the history's restore path
     * (SettingsHomePage `restoreWideNavEntry`): it reopens a place's conversation, or closes what another tab left
     * open beside a list.
     */
    fun land(
        shown: RaftWideNavEntry?,
        tab: String,
        selectedTab: String,
        openedOn: String?,
        restore: (RaftWideNavEntry) -> Unit,
    ) {
        val open = adaptiveWorkspaceStore.snapshot().workspace?.selection?.identity
        val landing = places.landing(shown, tab, selectedTab, open, openedOn)
        if (landing == RaftRailLanding.Keep) return
        log("app_shell_rail_place_land tab=$tab from=${shown?.surface} landing=$landing")
        when (landing) {
            RaftRailLanding.Keep -> Unit
            is RaftRailLanding.Restore -> restore(landing.place)
            RaftRailLanding.DefaultLanding -> {
                val serverId = shown?.serverId ?: return
                restore(RaftWideNavEntry(serverId, RaftRailChatTab))
                adaptiveWorkspaceStore.update { it.withDefaultLandingRearmed(serverId) }
                // The landing picks the selected Home row first: not the one another tab opened.
                clearSelectedConversation()
            }
        }
    }
}

/**
 * The shell's [AppShellRailPlaces], in memory per shell ([shellInstanceId]) and account
 * ([serverLastConversationOwnerKey]), fed with what the wide layout shows ([wideNavShowing], null off it). The
 * conversation Chat shows becomes its server's last conversation ([AppShellAdaptiveSelectionEffect]);
 * [adaptiveWorkspaceState] and [navRailActive] serve that effect off the wide layout.
 */
@Composable
internal fun rememberAppShellRailPlaces(
    shellInstanceId: String,
    adaptiveWorkspaceStore: SharedAdaptiveWorkspaceStore,
    homeShellStore: SharedHomeShellStore,
    bridge: BridgeModule,
    wideNavShowing: RaftWideNavEntry?,
    defaultLandingServerId: String?,
    adaptiveWorkspaceState: RaftAdaptiveWorkspaceState?,
    navRailActive: Boolean,
    serverLastConversationRepository: ServerLastConversationRepository,
    serverLastConversationOwnerKey: String?,
): AppShellRailPlaces {
    val latestHomeShellStore by rememberUpdatedState(homeShellStore)
    val latestBridge by rememberUpdatedState(bridge)
    val railPlaces =
        remember(shellInstanceId, adaptiveWorkspaceStore, serverLastConversationOwnerKey) {
            AppShellRailPlaces(
                adaptiveWorkspaceStore = adaptiveWorkspaceStore,
                clearSelectedConversation = {
                    latestHomeShellStore.dispatch(SharedHomeShellIntent.SetSelectedConversation(null))
                },
                log = { latestBridge.log(it) },
            )
        }
    val landedByDefault = adaptiveWorkspaceState?.selectionIsDefaultLanding == true
    LaunchedEffect(railPlaces, wideNavShowing, defaultLandingServerId, landedByDefault) {
        val shown = wideNavShowing ?: return@LaunchedEffect
        railPlaces.onShown(shown, defaultLandingServerId, landedByDefault)
    }
    AppShellAdaptiveSelectionEffect(
        adaptiveWorkspaceState = adaptiveWorkspaceState,
        chatConversation = wideNavShowing?.chatConversation(),
        navRailActive = navRailActive,
        serverLastConversationRepository = serverLastConversationRepository,
        serverLastConversationOwnerKey = serverLastConversationOwnerKey,
    )
    return railPlaces
}

/**
 * The surface ([RaftWideNavEntry.surface]) the wide layout showed when the shared detail's [selection] came: the
 * surface that opened it (a row picked there, a link, a history step), whichever surface shows it later. Activity's
 * reopen on arrival counts only its own open as something open (ActivityArrivalReopen.kt): Chat's conversation left
 * in the slot is not. Kept in composition, so a handler that switches the surface and opens there at once (a rail
 * landing, a history restore) gives the open to the surface it lands on.
 */
@Composable
internal fun rememberRaftSelectionOpenedOn(selection: RaftAdaptiveDetailSelection?, surface: String?): String? =
    remember(selection) { surface.takeIf { selection != null } }
