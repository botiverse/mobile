package build.raft.app

import build.raft.app.data.*
import build.raft.app.thread.key
import kotlin.test.*

/** task #36: channel body and reply thread side by side when the detail pane has room. */
class AdaptiveSideThreadTest {
    private val channel = WireThreadLaunchPayload(threadId = "channel", sourceThreadId = "channel")
    private val reply = WireThreadLaunchPayload(
        surfaceKind = RaftThreadSurfaceKind.ReplyThread,
        sourceThreadId = "reply", streamChannelId = "reply", parentChannelId = "channel",
        parentMessageId = "message", parentSurfaceKind = RaftThreadSurfaceKind.Channel,
    )
    private val otherReply = reply.copy(sourceThreadId = "reply-2", streamChannelId = "reply-2", parentMessageId = "message-2")
    private val foreignReply = reply.copy(sourceThreadId = "reply-x", streamChannelId = "reply-x", parentChannelId = "other-channel")

    private fun pane(widthDp: Int) = RaftAdaptivePaneState(
        widthPx = widthDp * 2, heightPx = 1600, density = 2f,
        posture = RaftAdaptivePanePosture.Flat, layout = RaftAdaptivePaneLayout.DualPane,
    )

    private fun session(widthDp: Int = 1440): SharedAdaptiveWorkspaceSession {
        val state = RaftAdaptiveWorkspaceState(shellInstanceId = "shell", serverId = "server", pane = pane(widthDp))
        return SharedAdaptiveWorkspaceSession(
            workspace = reduceRaftAdaptiveWorkspace(state, RaftAdaptiveWorkspaceIntent.OpenDetail(channel.toRaftAdaptiveDetailIdentity("server")!!)),
            launchPayload = channel,
        )
    }

    private fun AdaptiveSideThreadOpen.opened(): SharedAdaptiveWorkspaceSession =
        assertIs<AdaptiveSideThreadOpen.Opened>(this).session

    private fun SharedAdaptiveWorkspaceSession.mainSource(): RaftAdaptiveDetailCloseRequest =
        workspace!!.selection!!.let { RaftAdaptiveDetailCloseRequest("shell", it.identity.key, it.generation) }

    @Test fun columnFollowsTheRowPlansFoldNotAFixedWindowWidth() {
        // #189 rule 3 (web decideFold): alone with the conversation, the thread stands beside while the two keep 360
        // each, from 720 of room, whatever the window (before #189: a 1200 window and an 841 detail).
        assertTrue(raftSideThreadColumnAvailable(true, pane(1440), 720f))
        assertFalse(raftSideThreadColumnAvailable(true, pane(1440), 719f))
        assertTrue(raftSideThreadColumnAvailable(true, pane(1100), 720f))
        // The plan's answer for the row the detail stands in (Tasks / Files beside it, kept widths) decides when known.
        assertFalse(raftSideThreadColumnAvailable(true, pane(1440), 999f, threadFolds = true))
        assertTrue(raftSideThreadColumnAvailable(true, pane(1440), 1000f, threadFolds = false))
        // Not measured yet, desktop mode off, or not dual pane.
        assertFalse(raftSideThreadColumnAvailable(true, pane(1440), null))
        assertFalse(raftSideThreadColumnAvailable(true, pane(1440), null, threadFolds = false))
        assertFalse(raftSideThreadColumnAvailable(false, pane(1440), 900f))
        assertFalse(raftSideThreadColumnAvailable(true, pane(1440).copy(layout = RaftAdaptivePaneLayout.Compact), 900f))
    }

    @Test fun replyUnderTheShownChannelOpensBesideItWithoutChangingTheMainDetail() {
        val root = session()
        val opened = root.openSideThread(root.mainSource(), reply, "server", columnAvailable = true).opened()
        assertEquals(root.workspace!!.selection, opened.workspace!!.selection)
        assertEquals(channel, opened.launchPayload)
        assertEquals(reply, opened.sideThread!!.payload)
        assertEquals(root.workspace!!.selection, opened.sideThread!!.owner)
        // Same thread again: no new generation.
        assertSame(opened, opened.openSideThread(opened.mainSource(), reply, "server", columnAvailable = true).opened())
    }

    @Test fun anotherReplyFromChannelOrSideColumnReplacesTheColumn() {
        val root = session()
        val first = root.openSideThread(root.mainSource(), reply, "server", true).opened()
        val fromSide = first.openSideThread(first.sideThread!!.closeRequest("shell"), otherReply, "server", true).opened()
        assertEquals(otherReply, fromSide.sideThread!!.payload)
        assertTrue(fromSide.sideThread!!.generation > first.sideThread!!.generation)
        assertEquals(channel, fromSide.launchPayload)
    }

    @Test fun validSourceFallsBackToInPaneReplaceWhenTheColumnDoesNotApply() {
        val root = session()
        val fallback = AdaptiveSideThreadOpen.FallbackToReplace
        assertEquals(fallback, root.openSideThread(root.mainSource(), reply, "server", columnAvailable = false))
        // Not a reply thread under the shown channel.
        assertEquals(fallback, root.openSideThread(root.mainSource(), foreignReply, "server", true))
        assertEquals(fallback, root.openSideThread(root.mainSource(), channel, "server", true))
    }

    @Test fun staleOrForeignSourceIsIgnoredAndChangesNothing() {
        val root = session()
        val withSide = root.openSideThread(root.mainSource(), reply, "server", true).opened()
        val ignored = AdaptiveSideThreadOpen.Ignored
        // An old generation of the main detail, another shell, another server.
        val staleMain = RaftAdaptiveDetailCloseRequest("shell", root.workspace!!.selection!!.identity.key, 999L)
        assertEquals(ignored, withSide.openSideThread(staleMain, otherReply, "server", true))
        val otherShell = withSide.mainSource().copy(shellInstanceId = "other-shell")
        assertEquals(ignored, withSide.openSideThread(otherShell, otherReply, "server", true))
        assertEquals(ignored, withSide.openSideThread(withSide.mainSource(), otherReply, "other-server", true))
        // A close request from a side thread that has since been replaced.
        val replaced = withSide.openSideThread(withSide.mainSource(), otherReply, "server", true).opened()
        assertEquals(ignored, replaced.openSideThread(withSide.sideThread!!.closeRequest("shell"), reply, "server", true))
        // Ignored means the caller leaves main detail, side column and history untouched.
        assertEquals(withSide.launchPayload, channel)
        assertEquals(replaced.sideThread!!.payload, otherReply)
        assertNull(replaced.detailHistory)
        // The in-pane replace would also refuse the stale main source.
        assertEquals(withSide, withSide.navigateDetail(staleMain, otherReply, "server"))
    }

    @Test fun sideColumnCloseOnlyClosesTheColumnAndIgnoresStaleRequests() {
        val root = session()
        val first = root.openSideThread(root.mainSource(), reply, "server", true).opened()
        val oldRequest = first.sideThread!!.closeRequest("shell")
        val second = first.openSideThread(oldRequest, otherReply, "server", true).opened()
        // A close from the replaced thread arriving late must not close the new one.
        assertSame(second, second.closeSideThread(oldRequest))
        val closed = second.closeSideThread(second.sideThread!!.closeRequest("shell"))
        assertNull(closed.sideThread)
        assertEquals(second.workspace, closed.workspace)
        assertEquals(channel, closed.launchPayload)
        // A main-detail close request never touches the column.
        assertSame(second, second.closeSideThread(second.mainSource()))
    }

    @Test fun switchingConversationDropsTheColumnInTheStore() {
        val store = SharedAdaptiveWorkspaceStore()
        store.update { session() }
        store.update { it.openSideThread(it.mainSource(), reply, "server", true).opened() }
        assertNotNull(store.snapshot().sideThread)
        val dm = WireThreadLaunchPayload(threadId = "dm", sourceThreadId = "dm", surfaceKind = RaftThreadSurfaceKind.DirectMessage)
        store.update { it.withOpenedDetail(dm.toRaftAdaptiveDetailIdentity("server")!!, dm) }
        assertNull(store.snapshot().sideThread)
        // Authority (server/account) change also retires it.
        store.update { session() }
        store.update { it.openSideThread(it.mainSource(), reply, "server", true).opened() }
        store.update { it.copy(workspace = reduceRaftAdaptiveWorkspace(it.workspace!!, RaftAdaptiveWorkspaceIntent.AuthorityChanged("other"))) }
        assertNull(store.snapshot().sideThread)
    }

    @Test fun narrowingFoldsTheColumnIntoInPaneHistoryOnce() {
        val root = session()
        val opened = root.openSideThread(root.mainSource(), reply, "server", true).opened()
        val folded = opened.foldSideThreadIntoDetail()
        assertNull(folded.sideThread)
        assertEquals(reply, folded.launchPayload)
        assertEquals(reply.toRaftAdaptiveDetailIdentity("server"), folded.workspace!!.selection!!.identity)
        assertEquals(listOf(channel), folded.detailHistory!!.previous)
        // Back returns exactly one level, to the channel.
        val back = folded.backFromDetail(folded.mainSource())
        assertEquals(channel, back.launchPayload)
        assertFalse(back.hasVisibleDetailBackHistory())
        // Folding again (re-crossing the threshold) adds nothing.
        assertSame(folded, folded.foldSideThreadIntoDetail())
    }

    @Test fun backClosesAVisibleSideColumnFirst() {
        val needed = 720f
        val wide = session().let { it.copy(detailPaneWidthDp = needed, workspace = it.workspace!!.copy(parentVisible = true)) }
        assertFalse(wide.backClosesSideThreadFirst(true))
        val side = wide.openWithinWideDetail(wide.mainSource(), reply, "server", true)
        assertTrue(side.backClosesSideThreadFirst(true))
        assertFalse(side.backClosesSideThreadFirst(false))
        assertFalse(side.copy(workspace = side.workspace!!.copy(parentVisible = false)).backClosesSideThreadFirst(true))
    }

    @Test fun sideParentActionFocusesTheParentMessageInTheMainDetailAndKeepsTheColumn() {
        val root = session()
        val opened = root.openSideThread(root.mainSource(), reply, "server", true).opened()
        val side = opened.sideThread!!.closeRequest("shell")
        val focus = assertNotNull(opened.sideThreadParentFocus(side))
        assertEquals(opened.mainSource(), focus.target)
        assertEquals("message", focus.messageId)
        // The main page accepts it; the side page, a retired main and another shell do not.
        assertTrue(opened.acceptsParentFocus(opened.mainSource(), focus))
        assertFalse(opened.acceptsParentFocus(side, focus))
        assertFalse(opened.acceptsParentFocus(opened.mainSource().copy(shellInstanceId = "other"), focus))
        // Wire round trip.
        assertEquals(focus, focus.toJson().toAdaptiveParentFocusRequestOrNull())
        // An original behind the history window: the history start, round-tripped too.
        val historyStart = focus.copy(historyStart = true)
        assertEquals(historyStart, historyStart.toJson().toAdaptiveParentFocusRequestOrNull())
        assertFalse(focus.toJson().containsKey(RAFT_ADAPTIVE_DETAIL_FOCUS_HISTORY_START))
        // The main page's own parent action is not a side action (existing navigation applies).
        assertNull(opened.sideThreadParentFocus(opened.mainSource()))
    }
}
