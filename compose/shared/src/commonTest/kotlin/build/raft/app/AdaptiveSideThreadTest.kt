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

    @Test fun wideDetailRoutingUsesSessionWidthAndKeepsStaleSourcesInert() {
        val needed = SLOCK_SIDE_THREAD_MAIN_MIN_DP + SLOCK_SIDE_THREAD_DIVIDER_DP + SLOCK_SIDE_THREAD_COLUMN_DP
        val wide = session().copy(detailPaneWidthDp = needed)
        // Room for the column: opens beside the channel.
        val side = wide.openWithinWideDetail(wide.mainSource(), reply, "server", desktopModeOnLargeScreen = true)
        assertEquals(reply, side.visibleSideThread()!!.payload)
        assertEquals(channel, side.launchPayload)
        // No room (unmeasured): the valid source falls back to the in-pane replace.
        val narrow = session().copy(detailPaneWidthDp = null)
        val replaced = narrow.openWithinWideDetail(narrow.mainSource(), reply, "server", true)
        assertNull(replaced.sideThread)
        assertEquals(reply, replaced.launchPayload)
        // Desktop mode off behaves like no room.
        assertEquals(reply, wide.openWithinWideDetail(wide.mainSource(), reply, "server", false).launchPayload)
        // Stale source: nothing changes.
        val stale = SlockAdaptiveDetailCloseRequest("shell", wide.workspace!!.selection!!.identity.key, 999L)
        assertSame(side, side.openWithinWideDetail(stale, otherReply, "server", true))
    }

    @Test fun sideColumnSourceThatCannotUseTheColumnReplacesOnBehalfOfItsOwner() {
        val needed = SLOCK_SIDE_THREAD_MAIN_MIN_DP + SLOCK_SIDE_THREAD_DIVIDER_DP + SLOCK_SIDE_THREAD_COLUMN_DP
        val wide = session().copy(detailPaneWidthDp = needed)
        val side = wide.openWithinWideDetail(wide.mainSource(), reply, "server", true)
        val sideSource = side.visibleSideThread()!!.closeRequest("shell")
        // From the side column, the parent channel's sibling thread elsewhere: not under the shown
        // channel, so the main detail is replaced (with Back history) and the column goes away.
        val result = side.openWithinWideDetail(sideSource, foreignReply, "server", true)
        assertEquals(foreignReply, result.launchPayload)
        assertNull(result.visibleSideThread())
        assertEquals(listOf(channel), result.detailHistory!!.previous)
    }

    @Test fun backClosesAVisibleSideColumnFirst() {
        val needed = SLOCK_SIDE_THREAD_MAIN_MIN_DP + SLOCK_SIDE_THREAD_DIVIDER_DP + SLOCK_SIDE_THREAD_COLUMN_DP
        val wide = session().let { it.copy(detailPaneWidthDp = needed, workspace = it.workspace!!.copy(parentVisible = true)) }
        assertFalse(wide.backClosesSideThreadFirst(true))
        val side = wide.openWithinWideDetail(wide.mainSource(), reply, "server", true)
        assertTrue(side.backClosesSideThreadFirst(true))
        assertFalse(side.backClosesSideThreadFirst(false))
        assertFalse(side.copy(workspace = side.workspace!!.copy(parentVisible = false)).backClosesSideThreadFirst(true))
    }

    @Test fun aFoldedSideThreadNeverSharesAGenerationWithAnOldSideRequest() {
        // LiBai #2809: main gen 1, side S then T, then fold T into the main detail. The old side
        // T close/open request must not be accepted as a main-detail request afterwards.
        val root = session()
        val s1 = root.openSideThread(root.mainSource(), reply, "server", true).opened()
        val s2 = s1.openSideThread(s1.mainSource(), otherReply, "server", true).opened()
        val oldSideT = s2.sideThread!!.closeRequest("shell")
        val folded = s2.foldSideThreadIntoDetail()
        val mainNow = folded.workspace!!.selection!!
        assertEquals(otherReply.toSlockAdaptiveDetailIdentity("server"), mainNow.identity)
        assertNotEquals(oldSideT.generation, mainNow.generation)
        assertFalse(folded.workspace!!.acceptsDetailCallback(oldSideT.identityKey, oldSideT.generation))
        // Through the shell's close dispatcher the retired side close is not a main Back, and a
        // retired side open changes nothing: main, side and history all stay as they are.
        assertEquals(folded, folded.closeFromDetailPage(oldSideT))
        assertSame(folded, folded.openWithinWideDetail(oldSideT, reply, "server", true))
        assertEquals(AdaptiveThreadOpenRoute.Ignored, folded.routeThreadOpen(oldSideT, true, "server"))
        // Every side generation is unique against every main generation.
        assertTrue(s1.sideThread!!.generation > root.workspace!!.selection!!.generation)
        assertTrue(s2.sideThread!!.generation > s1.sideThread!!.generation)
    }

    @Test fun aRetiredAdaptivePageOpensNothingWhateverTheModePaneOrServer() {
        // LiBai #2809: the thread page validates its adaptive source before any route, so a
        // retired page never reaches the standalone fallback.
        val root = session()
        val s1 = root.openSideThread(root.mainSource(), reply, "server", true).opened()
        val liveSide = s1.sideThread!!.closeRequest("shell")
        val folded = s1.foldSideThreadIntoDetail()
        val compact = folded.copy(workspace = folded.workspace!!.copy(pane = pane(1440).copy(layout = SlockAdaptivePaneLayout.Compact)))
        for (session in listOf(folded, compact)) {
            for (desktop in listOf(true, false)) {
                for (server in listOf("server", "other-server")) {
                    assertEquals(AdaptiveThreadOpenRoute.Ignored, session.routeThreadOpen(liveSide, desktop, server))
                    assertEquals(AdaptiveThreadOpenRoute.Ignored, session.routeThreadOpen(root.mainSource(), desktop, server))
                }
            }
        }
        // Another shell's page is not this session's source either.
        assertEquals(AdaptiveThreadOpenRoute.Ignored, folded.routeThreadOpen(folded.mainSource().copy(shellInstanceId = "other"), true, "server"))
        // No workspace at all: an adaptive page is retired.
        assertEquals(AdaptiveThreadOpenRoute.Ignored, SharedAdaptiveWorkspaceSession().routeThreadOpen(liveSide, false, "server"))
    }

    @Test fun aLivePageKeepsItsRoutesAndNonAdaptivePagesStayStandalone() {
        val root = session()
        val s1 = root.openSideThread(root.mainSource(), reply, "server", true).opened()
        val side = s1.sideThread!!.closeRequest("shell")
        for (source in listOf(s1.mainSource(), side)) {
            assertEquals(AdaptiveThreadOpenRoute.WithinDetail, s1.routeThreadOpen(source, true, "server"))
            // A live page may still use the standalone fallback the wide detail cannot take.
            assertEquals(AdaptiveThreadOpenRoute.Standalone, s1.routeThreadOpen(source, false, "server"))
            assertEquals(AdaptiveThreadOpenRoute.Standalone, s1.routeThreadOpen(source, true, "other-server"))
        }
        val compact = s1.copy(workspace = s1.workspace!!.copy(pane = pane(1440).copy(layout = SlockAdaptivePaneLayout.Compact)))
        assertEquals(AdaptiveThreadOpenRoute.Standalone, compact.routeThreadOpen(s1.mainSource(), true, "server"))
        // Pages outside the adaptive detail keep opening standalone pages.
        assertEquals(AdaptiveThreadOpenRoute.Standalone, s1.routeThreadOpen(null, true, "server"))
        assertEquals(AdaptiveThreadOpenRoute.Standalone, SharedAdaptiveWorkspaceSession().routeThreadOpen(null, false, null))
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
        // The main page's own parent action is not a side action (existing navigation applies).
        assertNull(opened.sideThreadParentFocus(opened.mainSource()))
    }

    @Test fun retiredSideThreadParentActionRequestsNothing() {
        val root = session()
        val s1 = root.openSideThread(root.mainSource(), reply, "server", true).opened()
        val oldSide = s1.sideThread!!.closeRequest("shell")
        val s2 = s1.openSideThread(s1.mainSource(), otherReply, "server", true).opened()
        assertNull(s2.sideThreadParentFocus(oldSide))
        assertNull(s2.closeSideThread(s2.sideThread!!.closeRequest("shell")).sideThreadParentFocus(s2.sideThread!!.closeRequest("shell")))
        val folded = s2.foldSideThreadIntoDetail()
        assertNull(folded.sideThreadParentFocus(s2.sideThread!!.closeRequest("shell")))
        // A focus addressed to the pre-fold main detail is not applied by the folded main.
        val stale = assertNotNull(s2.sideThreadParentFocus(s2.sideThread!!.closeRequest("shell")))
        assertFalse(folded.acceptsParentFocus(folded.mainSource(), stale))
        assertFalse(folded.acceptsParentFocus(stale.target, stale))
    }
}
