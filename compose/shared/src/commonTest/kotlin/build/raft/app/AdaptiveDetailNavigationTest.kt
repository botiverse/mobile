package build.raft.app

import build.raft.app.data.*
import kotlin.test.*

class AdaptiveDetailNavigationTest {
    private val channel = WireThreadLaunchPayload(threadId = "channel", sourceThreadId = "channel")
    private val reply = WireThreadLaunchPayload(
        surfaceKind = RaftThreadSurfaceKind.ReplyThread,
        sourceThreadId = "reply", streamChannelId = "reply", parentChannelId = "channel",
        parentMessageId = "message", parentSurfaceKind = RaftThreadSurfaceKind.Channel,
    )

    private fun session(): SharedAdaptiveWorkspaceSession {
        val state = RaftAdaptiveWorkspaceState(
            shellInstanceId = "shell", serverId = "server",
            pane = RaftAdaptivePaneState(widthPx = 2560, heightPx = 1600, density = 2f,
                posture = RaftAdaptivePanePosture.Flat, layout = RaftAdaptivePaneLayout.DualPane),
        )
        return SharedAdaptiveWorkspaceSession(
            workspace = reduceRaftAdaptiveWorkspace(state, RaftAdaptiveWorkspaceIntent.OpenDetail(channel.toRaftAdaptiveDetailIdentity("server")!!)),
            launchPayload = channel,
        )
    }

    private fun SharedAdaptiveWorkspaceSession.source(): RaftAdaptiveDetailCloseRequest =
        RaftAdaptiveDetailCloseRequest("shell", workspace!!.selection!!.identity.key, workspace.selection!!.generation)

    @Test fun returningToTheShellOnAPhoneClearsTheViewedConversation() {
        // A pushed conversation page closed: nothing in the shell is being viewed, so Home must count
        // unread for the channel that was open (it otherwise waits for the next server summary).
        assertNull(SharedAdaptiveWorkspaceSession().selectedConversationWhenShellReturns(desktopModeOnLargeScreen = false))
        assertNull(SharedAdaptiveWorkspaceSession().selectedConversationWhenShellReturns(desktopModeOnLargeScreen = true))
        // Desktop mode off on a large screen: a parked selection is not on screen either.
        assertNull(session().selectedConversationWhenShellReturns(desktopModeOnLargeScreen = false))
    }

    @Test fun returningToTheShellAfterATransientWideLandingShowsTheHomeList() {
        // Hands d5ec0533: landing installed while wide, window back to compact, user pops to Home.
        val wide = RaftAdaptiveWorkspaceState(
            shellInstanceId = "shell", serverId = "server",
            pane = RaftAdaptivePaneState(widthPx = 2848, heightPx = 1320, density = 3.375f,
                posture = RaftAdaptivePanePosture.Flat, layout = RaftAdaptivePaneLayout.DualPane),
        )
        val landed = SharedAdaptiveWorkspaceSession(workspace = wide)
            .withOpenedDetail(channel.toRaftAdaptiveDetailIdentity("server")!!, channel, defaultLanding = true)
        assertEquals("channel", landed.selectedConversationWhenShellReturns(desktopModeOnLargeScreen = true))
        val compact = landed.copy(workspace = reduceRaftAdaptiveWorkspace(landed.workspace!!,
            RaftAdaptiveWorkspaceIntent.WindowChanged(wide.pane.copy(widthPx = 1320, heightPx = 2848,
                layout = RaftAdaptivePaneLayout.Compact))))
        assertEquals(RaftAdaptiveWorkspacePresentation.ListOnly, compact.workspace!!.presentation)
        assertNull(compact.selectedConversationWhenShellReturns(desktopModeOnLargeScreen = true))

        // Covered shell: the landing is refused outright and the session is untouched.
        val covered = SharedAdaptiveWorkspaceSession(workspace = wide.copy(parentVisible = false))
        assertSame(covered, covered.withOpenedDetail(channel.toRaftAdaptiveDetailIdentity("server")!!, channel, defaultLanding = true))
    }

    @Test fun returningToTheShellKeepsADetailStillShownBesideTheList() {
        val split = session()
        assertEquals(RaftAdaptiveWorkspacePresentation.Split, split.workspace!!.presentation)
        assertEquals("channel", split.selectedConversationWhenShellReturns(desktopModeOnLargeScreen = true))

        val closed = split.backFromDetail(split.source())
        assertNull(closed.workspace!!.selection)
        assertNull(closed.selectedConversationWhenShellReturns(desktopModeOnLargeScreen = true))
    }

    @Test fun replyReplacesOnlyDetailAndBackRestoresParentWithFreshGeneration() {
        val root = session()
        val opened = root.navigateDetail(root.source(), reply, "server")
        assertFalse(root.hasVisibleDetailBackHistory())
        assertTrue(opened.hasVisibleDetailBackHistory())
        assertFalse(opened.copy(workspace = opened.workspace!!.copy(parentVisible = false)).hasVisibleDetailBackHistory())
        assertEquals(reply, opened.launchPayload)
        assertEquals("channel", opened.detailListSelectionId())
        assertEquals(RaftAdaptiveWorkspacePresentation.Split, opened.workspace!!.presentation)
        val restored = opened.backFromDetail(opened.source())
        assertEquals(channel, restored.launchPayload)
        assertFalse(restored.hasVisibleDetailBackHistory())
        assertEquals("channel", restored.detailListSelectionId())
        assertTrue(restored.workspace!!.selection!!.generation > opened.workspace.selection!!.generation)
        assertEquals(restored, restored.backFromDetail(opened.source()))
        assertNull(restored.backFromDetail(restored.source()).workspace!!.selection)
    }

    @Test fun wideOpenKeepsAVisibilityChangeThatLandedFirst() {
        // task #33: the DMs tab became the visible host (parentVisible=true in the store) just
        // before the first tap; the open must keep that instead of reverting to a stale snapshot.
        val stale = session().let { it.copy(workspace = it.workspace!!.copy(parentVisible = false)) }
        val live = stale.copy(workspace = stale.workspace!!.copy(parentVisible = true))
        val replyIdentity = reply.toRaftAdaptiveDetailIdentity("server")!!
        val opened = live.withOpenedDetail(replyIdentity, reply)
        assertTrue(opened.workspace!!.parentVisible)
        assertEquals(replyIdentity, opened.workspace.selection!!.identity)
        assertEquals(reply, opened.launchPayload)
        assertEquals(RaftAdaptiveWorkspacePresentation.Split, opened.workspace.presentation)
        // A compact pane cannot host the detail: the session is left untouched.
        val compact = live.copy(
            workspace = reduceRaftAdaptiveWorkspace(
                live.workspace!!,
                RaftAdaptiveWorkspaceIntent.WindowChanged(
                    RaftAdaptivePaneState(widthPx = 800, heightPx = 1600, density = 2f,
                        posture = RaftAdaptivePanePosture.Flat, layout = RaftAdaptivePaneLayout.Compact)
                )
            )
        )
        assertSame(compact, compact.withOpenedDetail(replyIdentity, reply))
        // Compact pane that already retains the SAME identity (e.g. parked after narrowing):
        // still not an in-pane open, so the caller falls back to the full-page route.
        val channelIdentity = channel.toRaftAdaptiveDetailIdentity("server")!!
        assertEquals(channelIdentity, compact.workspace!!.selection!!.identity)
        assertSame(compact, compact.withOpenedDetail(channelIdentity, channel))
        // Another server's identity is never opened into this workspace.
        val foreign = channel.toRaftAdaptiveDetailIdentity("other-server")!!
        assertSame(live, live.withOpenedDetail(foreign, channel))
    }

    @Test fun taskOverlayCloseAfterInDetailNavigationClosesTheWholeDetail() {
        // task #34: task -> in-detail navigation (reply -> parent) -> close button/scrim must
        // return to the board, not just step back one level like Back does.
        val root = session()
        val opened = root.navigateDetail(root.source(), reply, "server")
        assertTrue(opened.hasVisibleDetailBackHistory())
        assertNotNull(opened.backFromDetail(opened.source()).workspace!!.selection)
        val closed = opened.closeDetailCompletely()
        assertNull(closed.workspace!!.selection)
        assertNull(closed.launchPayload)
        assertNull(closed.detailHistory)
        assertFalse(closed.hasVisibleDetailBackHistory())
        assertTrue(closed.workspace.generationCursor > opened.workspace!!.generationCursor)
        assertEquals(closed, closed.closeDetailCompletely())
    }

    @Test fun onlyAnEmbeddedWideDesktopPageInterceptsNativeNavigation() {
        val root = session()
        assertTrue(shouldOpenWithinAdaptiveDetail(root.source(), root.workspace, true, "server"))
        assertFalse(shouldOpenWithinAdaptiveDetail(root.source(), root.workspace, false, "server"))
        assertFalse(shouldOpenWithinAdaptiveDetail(null, root.workspace, true, "server"))
        assertFalse(shouldOpenWithinAdaptiveDetail(root.source(), root.workspace, true, "other"))
        val compact = root.workspace!!.copy(pane = root.workspace.pane.copy(layout = RaftAdaptivePaneLayout.Compact))
        assertFalse(shouldOpenWithinAdaptiveDetail(root.source(), compact, true, "server"))
    }

    @Test fun staleForeignAndCompactOpensCannotReplacePane() {
        val root = session()
        for (source in listOf(root.source().copy(shellInstanceId = "other"), root.source().copy(generation = 99))) {
            assertEquals(root, root.navigateDetail(source, reply, "server"))
        }
        assertEquals(root, root.navigateDetail(root.source(), reply, "other-server"))
        val compact = root.copy(workspace = root.workspace!!.copy(pane = root.workspace.pane.copy(layout = RaftAdaptivePaneLayout.Compact)))
        assertEquals(compact, compact.navigateDetail(compact.source(), reply, "server"))
    }

    @Test fun resizeKeepsBackHistoryButSwitchingConversationRetiresIt() {
        val store = SharedAdaptiveWorkspaceStore()
        store.update { session() }
        val opened = store.update { it.navigateDetail(it.source(), reply, "server") }
        val compact = store.update { it.copy(workspace = it.workspace!!.copy(pane = it.workspace.pane.copy(layout = RaftAdaptivePaneLayout.Compact))) }
        assertEquals(channel, compact.backFromDetail(compact.source()).launchPayload)
        store.update { opened }
        val switched = store.update { it.copy(workspace = reduceRaftAdaptiveWorkspace(it.workspace!!,
            RaftAdaptiveWorkspaceIntent.OpenDetail(channel.copy(threadId = "other", sourceThreadId = "other").toRaftAdaptiveDetailIdentity("server")!!))) }
        assertNull(switched.detailHistory)
        assertEquals(switched, switched.backFromDetail(opened.source()))
    }

    @Test fun openingParentDoesNotCreateAReplyParentLoop() {
        val root = session()
        val opened = root.navigateDetail(root.source(), reply, "server")
        val parent = opened.navigateDetail(opened.source(), channel, "server")
        assertEquals(channel, parent.launchPayload)
        assertTrue(parent.detailHistory!!.previous.isEmpty())
        assertNull(parent.backFromDetail(parent.source()).workspace!!.selection)
    }
}
