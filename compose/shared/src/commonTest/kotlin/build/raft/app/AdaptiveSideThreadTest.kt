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

    private fun AdaptiveSideThreadOpen.opened(): SharedAdaptiveWorkspaceSession =
        assertIs<AdaptiveSideThreadOpen.Opened>(this).session

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
        val opened = root.openSideThread(root.mainSource(), reply, "server", columnAvailable = true).opened()
        assertEquals(root.workspace, opened.workspace)
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
        val staleMain = SlockAdaptiveDetailCloseRequest("shell", root.workspace!!.selection!!.identity.key, 999L)
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
        val dm = WireThreadLaunchPayload(threadId = "dm", sourceThreadId = "dm", surfaceKind = KuiklyThreadSurfaceKind.DirectMessage)
        store.update { it.withOpenedDetail(dm.toSlockAdaptiveDetailIdentity("server")!!, dm) }
        assertNull(store.snapshot().sideThread)
        // Authority (server/account) change also retires it.
        store.update { session() }
        store.update { it.openSideThread(it.mainSource(), reply, "server", true).opened() }
        store.update { it.copy(workspace = reduceSlockAdaptiveWorkspace(it.workspace!!, SlockAdaptiveWorkspaceIntent.AuthorityChanged("other"))) }
        assertNull(store.snapshot().sideThread)
    }

    @Test fun narrowingFoldsTheColumnIntoInPaneHistoryOnce() {
        val root = session()
        val opened = root.openSideThread(root.mainSource(), reply, "server", true).opened()
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
