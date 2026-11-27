package build.raft.app

import build.raft.app.data.InboxItem
import build.raft.app.data.SharedAdaptiveWorkspaceStore
import build.raft.app.data.SyncTestPreferences
import build.raft.app.data.closeDetailCompletely
import build.raft.app.data.closeFromDetailPage
import build.raft.app.data.detailListSelectionId
import build.raft.app.data.messageBodyFontSizeOwnerKey
import build.raft.app.data.withOpenedDetail
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.InboxItemKind
import build.raft.app.settings.AppShellRailPlaces
import build.raft.app.thread.key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The tablet bug (switching the rail between Chat and Activity mixed up what each had open), replayed through the
 * shell's own pieces: the shared workspace store and its open and close paths, what the wide layout shows
 * (raftWideNavEntryOf), the rail places (AppShellRailPlaces), the server's last conversation, and Activity's last
 * opened row with its reopen on arrival. [Shell] stands in for the SettingsHomePage glue between them: a rail click,
 * the history's restore path, the default landing and what each composition records.
 */
class RaftRailPlacesReplayTest {
    private val proj = channel("proj-workspace")
    private val projThread =
        WireThreadLaunchPayload(
            surfaceKind = RaftThreadSurfaceKind.ReplyThread,
            threadId = "stream-m1",
            sourceThreadId = "stream-m1",
            streamChannelId = "stream-m1",
            parentChannelId = "proj-workspace",
            parentMessageId = "m1",
            parentSurfaceKind = RaftThreadSurfaceKind.Channel,
        )
    private val rows =
        mapOf(
            "s1" to listOf(row("all", "all"), row("proj-workspace", "proj-workspace"), row("dm-ana", "Ana", direct = true)),
            "s2" to listOf(row("s2-all", "all"), row("dm-bo", "Bo", direct = true)),
        )

    /** Recorded on a tablet: the reply thread Activity opened and closed used to take Chat's channel with it. */
    @Test
    fun chatKeepsItsChannelAndActivityItsOwnConversationAcrossTheRecordedSequence() {
        val shell = Shell(rows)
        shell.openInChat(proj)

        shell.rail("activity")
        assertNull(shell.open, "Activity lands on its list: Chat's channel is not beside it")
        shell.openFromActivity("thread:proj-workspace:m1", projThread)
        shell.closeFromHeader()
        assertNull(shell.open)

        shell.rail("home")
        assertEquals(proj.identity(), shell.open, "#proj-workspace is still open in Chat")

        shell.rail("activity")
        assertEquals(projThread.identity(), shell.open, "Activity reopens the conversation opened from it last time")

        shell.rail("home")
        assertEquals(proj.identity(), shell.open, "the thread Activity reopened stays Activity's")
        assertEquals(listOf("s1/all", "s1/proj-workspace"), shell.lastConversations, "only Chat wrote the last conversation")
    }

    @Test
    fun eachServerLandsChatOnItsOwnConversationWhateverActivityOpened() {
        val shell = Shell(rows)
        shell.openInChat(proj)
        shell.rail("activity")
        shell.openFromActivity("dm:dm-ana", dm("dm-ana"))

        shell.switchServer("s2")
        assertNull(shell.open, "Chat's default landing does not fill Activity's detail on the new server")
        shell.openFromActivity("dm:dm-bo", dm("dm-bo"))
        shell.rail("home")
        assertEquals(channel("s2-all").identity("s2"), shell.open, "Chat, never shown on s2, lands by default, not on Activity's DM")

        shell.switchServer("s1")
        assertEquals(proj.identity("s1"), shell.open, "back on s1 Chat lands on its own last conversation")
        assertEquals(
            listOf("s1/all", "s1/proj-workspace", "s2/s2-all", "s1/proj-workspace"),
            shell.lastConversations,
            "the DMs opened from Activity never became a server's last conversation",
        )
    }

    /**
     * Activity's reopen reads Activity's own state: Chat's channel left in the shared detail by an entry that is not a
     * rail click (a notification's "open Activity") is not something open in Activity. It runs on the wide layout only.
     */
    @Test
    fun activityReopensItsOwnLastConversationBesideChatsChannelOnTheWideLayoutOnly() {
        listOf(true, false).forEach { wide ->
            val shell = Shell(rows, wide)
            shell.rail("activity")
            shell.openFromActivity("thread:proj-workspace:m1", projThread)
            shell.rail("home")
            shell.openInChat(proj)

            shell.enterActivity()
            if (wide) {
                assertEquals(projThread.identity(), shell.open, "Activity reopens its own conversation")
                shell.rail("home")
                assertEquals(proj.identity(), shell.open, "Chat's channel is still its place")
            } else {
                assertNull(shell.open, "a phone pushes conversations as pages: nothing opens beside the list")
            }
        }
    }

    private class Row(val candidate: RaftAdaptiveHomeLandingCandidate, val route: WireThreadLaunchPayload)

    /** A Home row: a channel, or a DM when [direct]. */
    private fun row(id: String, name: String, direct: Boolean = false) =
        Row(
            RaftAdaptiveHomeLandingCandidate(id, name, if (direct) ConversationKind.DirectMessage else ConversationKind.Channel),
            if (direct) dm(id) else channel(id),
        )

    private fun channel(id: String) =
        WireThreadLaunchPayload(surfaceKind = RaftThreadSurfaceKind.Channel, sourceThreadId = id, streamChannelId = id)

    private fun dm(id: String) =
        WireThreadLaunchPayload(
            surfaceKind = RaftThreadSurfaceKind.DirectMessage,
            threadId = id,
            sourceThreadId = id,
            streamChannelId = id,
        )

    private fun WireThreadLaunchPayload.identity(server: String = "s1") = toRaftAdaptiveDetailIdentity(server)!!

    /**
     * The app shell on a tablet (desktop mode, [wide]: the rail beside a dual pane; else a compact window, where
     * conversations are pushed pages), modelled on SettingsHomePage around the real pieces it calls.
     */
    private class Shell(private val rows: Map<String, List<Row>>, private val wide: Boolean = true) {
        private var server = "s1"
        private var tab = "home"
        private val owner = messageBodyFontSizeOwnerKey("https://api.raft.build", "user-1")!!
        private val preferences = SyncTestPreferences()
        private val lastConversation = ServerLastConversationRepository { preferences }
        private val activityLastOpened = ActivityLastOpenedStore(preferences)
        private val activityVisits = ActivityArrivalVisits()
        private val activityRows = mutableMapOf<String, MutableList<Pair<InboxItem, WireThreadLaunchPayload>>>()
        private val store = SharedAdaptiveWorkspaceStore()

        /** Home's selected row, which the default landing tries first. */
        private var selectedRow: String? = null
        private val railPlaces = AppShellRailPlaces(store, clearSelectedConversation = { selectedRow = null }, log = {})
        private var selection: RaftAdaptiveDetailSelection? = null
        private var selectionOpenedOn: String? = null

        /** The server's last conversation after every step, as `server/conversation`, each change once. */
        val lastConversations = mutableListOf<String>()

        val open: RaftAdaptiveDetailIdentity?
            get() = store.snapshot().workspace?.selection?.identity

        init {
            val pane =
                RaftAdaptivePaneState(
                    widthPx = if (wide) 2200 else 700,
                    heightPx = 1200,
                    density = 2f,
                    posture = RaftAdaptivePanePosture.Flat,
                    layout = if (wide) RaftAdaptivePaneLayout.DualPane else RaftAdaptivePaneLayout.Compact,
                )
            val workspace =
                reduceRaftAdaptiveWorkspace(
                    RaftAdaptiveWorkspaceState(shellInstanceId = "shell", serverId = server),
                    RaftAdaptiveWorkspaceIntent.WindowChanged(pane),
                )
            store.update { it.copy(workspace = workspace) }
            compose()
        }

        /** A row picked in Chat's list (openHomeConversation). */
        fun openInChat(route: WireThreadLaunchPayload) {
            check(tab == "home")
            open(route)
        }

        /** A row picked in Activity's list (SharedHomeActivityScreen openActivityItem), listed there from now on. */
        fun openFromActivity(key: String, route: WireThreadLaunchPayload) {
            val listed = activityRows.getOrPut(server) { mutableListOf() }
            if (listed.none { it.first.key == key }) {
                listed += InboxItem(key = key, kind = InboxItemKind.Thread, channelId = ChannelId(key), channelName = key) to route
            }
            activityLastOpened.write(server, key)
            open(route)
        }

        /** The detail header's arrow: the page's close event (closeFromDetailPage). */
        fun closeFromHeader() {
            val current = store.snapshot().workspace?.selection ?: return
            val after =
                store.update {
                    it.closeFromDetailPage(RaftAdaptiveDetailCloseRequest("shell", current.identity.key, current.generation))
                }
            selectedRow = after.detailListSelectionId()
            compose()
        }

        /** A rail click: the shell selects the tab (onAppShellTabSelected), then lands on its place. */
        fun rail(target: String) {
            val before = shown()
            val openedOn = selectionOpenedOn
            tab = target
            railPlaces.land(before, target, tab, openedOn, ::restore)
            compose()
        }

        /** An entry into Activity that is not a rail click (a notification's "open Activity"): the tab only. */
        fun enterActivity() {
            tab = "activity"
            compose()
        }

        /** A server switch from the server menu: Home's selection clears, the workspace changes authority. */
        fun switchServer(to: String) {
            server = to
            selectedRow = null
            store.update { current ->
                current.copy(
                    workspace =
                        current.workspace?.let { reduceRaftAdaptiveWorkspace(it, RaftAdaptiveWorkspaceIntent.AuthorityChanged(to)) },
                    launchPayload = null,
                    defaultLandingServerId = null,
                )
            }
            compose()
        }

        /** The wide open path (openLaunchPayloadInAdaptiveDetail): a compact window pushes a page instead. */
        private fun open(route: WireThreadLaunchPayload) {
            val identity = route.toRaftAdaptiveDetailIdentity(server)!!
            store.update { it.withOpenedDetail(identity, route) }
            selectedRow = identity.threadId
            compose()
        }

        /** SettingsHomePage restoreWideNavEntry for Chat and Activity entries. */
        private fun restore(entry: RaftWideNavEntry) {
            tab = entry.surface
            when (val detail = entry.detail) {
                is RaftWideNavDetail.Conversation -> {
                    assertEquals(detail.identity, detail.route.toRaftAdaptiveDetailIdentity(server), "restores open the place")
                    store.update { it.withOpenedDetail(detail.identity, detail.route) }
                    selectedRow = store.snapshot().detailListSelectionId()
                }
                is RaftWideNavDetail.Member -> error("no member entries here")
                is RaftWideNavDetail.Artifact -> error("no artifact entries here")
                null ->
                    if (store.snapshot().workspace?.selection != null) {
                        store.update { it.closeDetailCompletely() }
                        selectedRow = null
                    }
            }
        }

        /** What the wide layout shows (SettingsHomePage `wideNavShowing`): null off the wide layout. */
        private fun shown(): RaftWideNavEntry? {
            if (!wide) return null
            val session = store.snapshot()
            return raftWideNavEntryOf(
                serverId = server,
                selectedTab = tab,
                railInlineSecondaryDestination = null,
                selectedHomeDestination = null,
                tasksDetailColumnOpen = false,
                conversation = session.workspace?.selection?.identity,
                conversationRoute = session.launchPayload,
                member = null,
            )
        }

        /** A composition of the shell after a step, and the effects it runs. */
        private fun compose() {
            val shown = shown()
            val session = store.snapshot()
            val current = session.workspace?.selection
            // rememberRaftSelectionOpenedOn: the surface showing when the selection came.
            if (current != selection) {
                selection = current
                selectionOpenedOn = shown?.surface.takeIf { current != null }
            }
            // rememberAppShellRailPlaces and its AppShellAdaptiveSelectionEffect.
            shown?.let {
                railPlaces.onShown(it, session.defaultLandingServerId, session.workspace?.selectionIsDefaultLanding == true)
            }
            shown?.chatConversation()?.let { lastConversation.write(owner, it.serverId, it.toServerLastConversation()!!) }
            lastConversation.read(owner, server)?.let { "$server/${it.threadId}" }?.let {
                if (lastConversations.lastOrNull() != it) lastConversations += it
            }
            landByDefault()
            reopenActivityOnArrival()
        }

        /** SettingsHomePage's default landing: Chat's empty detail opens Home's row, the last conversation or #all. */
        private fun landByDefault() {
            val session = store.snapshot()
            val workspace = session.workspace ?: return
            if (
                !appShellChatDetailShown(tab, selectedHomeDestination = null, railInlineSecondaryDestination = null) ||
                !workspace.acceptsAdaptiveDefaultLanding() ||
                session.defaultLandingServerId == server
            ) {
                return
            }
            val serverRows = rows.getValue(server)
            val landing =
                resolveRaftAdaptiveHomeLanding(selectedRow, lastConversation.read(owner, server), serverRows.map { it.candidate })
                    .landing as? RaftAdaptiveHomeLanding.Row ?: return
            val route = serverRows.first { it.candidate.threadId == landing.threadId }.route
            store.update { it.withOpenedDetail(route.toRaftAdaptiveDetailIdentity(server)!!, route, defaultLanding = true) }
            selectedRow = landing.threadId
            compose()
        }

        /** SharedHomeActivityScreen's reopen on arrival (ActivityArrivalReopenEffect): once per visit to Activity. */
        private fun reopenActivityOnArrival() {
            val active = tab == "activity"
            if (!active) activityVisits.leave()
            if (!activityVisits.of(server).claim(active = active, loaded = true)) return
            val opened = store.snapshot().workspace?.selection?.identity?.threadId
            val item =
                activityArrivalReopenTarget(
                    wide = wide,
                    somethingOpen = opened != null && selectionOpenedOn == "activity",
                    items = activityRows[server].orEmpty().map { it.first },
                    lastOpenedKey = activityLastOpened.read(server),
                ) ?: return
            openFromActivity(item.key, activityRows.getValue(server).first { it.first.key == item.key }.second)
        }
    }
}
