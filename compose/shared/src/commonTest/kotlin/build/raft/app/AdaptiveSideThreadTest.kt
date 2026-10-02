package build.raft.app

import build.raft.app.data.*
import kotlin.test.*

/** task #36: channel body and reply thread side by side when the detail pane has room. */
class AdaptiveSideThreadTest {
    private val channel = WireThreadLaunchPayload(threadId = "channel", sourceThreadId = "channel")
    private val reply = WireThreadLaunchPayload(
        surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
        sourceThreadId = "reply", streamChannelId = "reply", parentChannelId = "channel",
        parentMessageId = "message", parentSurfaceKind = KuiklyThreadSurfaceKind.Channel,
    )
    private val otherReply = reply.copy(sourceThreadId = "reply-2", streamChannelId = "reply-2", parentMessageId = "message-2")
    private val foreignReply = reply.copy(sourceThreadId = "reply-x", streamChannelId = "reply-x", parentChannelId = "other-channel")

    private fun pane(widthDp: Int) = SlockAdaptivePaneState(
        widthPx = widthDp * 2, heightPx = 1600, density = 2f,
        posture = SlockAdaptivePanePosture.Flat, layout = SlockAdaptivePaneLayout.DualPane,
    )

    private fun session(widthDp: Int = 1440): SharedAdaptiveWorkspaceSession {
        val state = SlockAdaptiveWorkspaceState(shellInstanceId = "shell", serverId = "server", pane = pane(widthDp))
        return SharedAdaptiveWorkspaceSession(
            workspace = reduceSlockAdaptiveWorkspace(state, SlockAdaptiveWorkspaceIntent.OpenDetail(channel.toSlockAdaptiveDetailIdentity("server")!!)),
            launchPayload = channel,
        )
    }

    private fun SharedAdaptiveWorkspaceSession.mainSource(): SlockAdaptiveDetailCloseRequest =
        SlockAdaptiveDetailCloseRequest("shell", workspace!!.selection!!.identity.key, workspace.selection!!.generation)

    @Test fun columnNeedsTheBreakpointAndTheMeasuredDetailWidth() {
        val needed = SLOCK_SIDE_THREAD_MAIN_MIN_DP + SLOCK_SIDE_THREAD_DIVIDER_DP + SLOCK_SIDE_THREAD_COLUMN_DP
        assertTrue(slockSideThreadColumnAvailable(true, pane(1440), needed))
        // 1199 vs 1200dp window.
        assertFalse(slockSideThreadColumnAvailable(true, pane(1199), 900f))
        assertTrue(slockSideThreadColumnAvailable(true, pane(1200), needed))
        // Window at the breakpoint but rail + list + insets leave too little for the body.
        assertFalse(slockSideThreadColumnAvailable(true, pane(1200), needed - 1f))
        // Not measured yet, desktop mode off, or not dual pane.
        assertFalse(slockSideThreadColumnAvailable(true, pane(1440), null))
        assertFalse(slockSideThreadColumnAvailable(false, pane(1440), needed))
        assertFalse(slockSideThreadColumnAvailable(true, pane(1440).copy(layout = SlockAdaptivePaneLayout.Compact), needed))
    }

    @Test fun replyUnderTheShownChannelOpensBesideItWithoutChangingTheMainDetail() {
        val root = session()
        val opened = assertNotNull(root.openSideThread(root.mainSource(), reply, "server", columnAvailable = true))
        assertEquals(root.workspace, opened.workspace)
        assertEquals(channel, opened.launchPayload)
        assertEquals(reply, opened.sideThread!!.payload)
        assertEquals(root.workspace!!.selection, opened.sideThread!!.owner)
        // Same thread again: no new generation.
        assertSame(opened, opened.openSideThread(opened.mainSource(), reply, "server", columnAvailable = true))
    }

    @Test fun anotherReplyFromChannelOrSideColumnReplacesTheColumn() {
        val root = session()
        val first = root.openSideThread(root.mainSource(), reply, "server", true)!!
        val fromSide = first.openSideThread(first.sideThread!!.closeRequest("shell"), otherReply, "server", true)!!
        assertEquals(otherReply, fromSide.sideThread!!.payload)
        assertTrue(fromSide.sideThread!!.generation > first.sideThread!!.generation)
        assertEquals(channel, fromSide.launchPayload)
    }

    @Test fun fallsBackToInPaneReplaceWhenTheColumnDoesNotApply() {
        val root = session()
        assertNull(root.openSideThread(root.mainSource(), reply, "server", columnAvailable = false))
        // Not a reply thread under the shown channel.
        assertNull(root.openSideThread(root.mainSource(), foreignReply, "server", true))
        assertNull(root.openSideThread(root.mainSource(), channel, "server", true))
        // Stale source (an older generation) or another server.
        val stale = SlockAdaptiveDetailCloseRequest("shell", root.workspace!!.selection!!.identity.key, 999L)
        assertNull(root.openSideThread(stale, reply, "server", true))
        assertNull(root.openSideThread(root.mainSource(), reply, "other-server", true))
    }

    @Test fun sideColumnCloseOnlyClosesTheColumnAndIgnoresStaleRequests() {
        val root = session()
        val first = root.openSideThread(root.mainSource(), reply, "server", true)!!
        val oldRequest = first.sideThread!!.closeRequest("shell")
        val second = first.openSideThread(oldRequest, otherReply, "server", true)!!
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
        store.update { it.openSideThread(it.mainSource(), reply, "server", true)!! }
        assertNotNull(store.snapshot().sideThread)
        val dm = WireThreadLaunchPayload(threadId = "dm", sourceThreadId = "dm", surfaceKind = KuiklyThreadSurfaceKind.DirectMessage)
        store.update { it.withOpenedDetail(dm.toSlockAdaptiveDetailIdentity("server")!!, dm) }
        assertNull(store.snapshot().sideThread)
        // Authority (server/account) change also retires it.
        store.update { session() }
        store.update { it.openSideThread(it.mainSource(), reply, "server", true)!! }
        store.update { it.copy(workspace = reduceSlockAdaptiveWorkspace(it.workspace!!, SlockAdaptiveWorkspaceIntent.AuthorityChanged("other"))) }
        assertNull(store.snapshot().sideThread)
    }

    @Test fun narrowingFoldsTheColumnIntoInPaneHistoryOnce() {
        val root = session()
        val opened = root.openSideThread(root.mainSource(), reply, "server", true)!!
        val folded = opened.foldSideThreadIntoDetail()
        assertNull(folded.sideThread)
        assertEquals(reply, folded.launchPayload)
        assertEquals(reply.toSlockAdaptiveDetailIdentity("server"), folded.workspace!!.selection!!.identity)
        assertEquals(listOf(channel), folded.detailHistory!!.previous)
        // Back returns exactly one level, to the channel.
        val back = folded.backFromDetail(folded.mainSource())
        assertEquals(channel, back.launchPayload)
        assertFalse(back.hasVisibleDetailBackHistory())
        // Folding again (re-crossing the threshold) adds nothing.
        assertSame(folded, folded.foldSideThreadIntoDetail())
    }
}
