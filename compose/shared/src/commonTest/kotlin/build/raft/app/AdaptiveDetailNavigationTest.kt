package build.raft.app

import build.raft.app.data.*
import kotlin.test.*

class AdaptiveDetailNavigationTest {
    private val channel = WireThreadLaunchPayload(threadId = "channel", sourceThreadId = "channel")
    private val reply = WireThreadLaunchPayload(
        surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
        sourceThreadId = "reply", streamChannelId = "reply", parentChannelId = "channel",
        parentMessageId = "message", parentSurfaceKind = KuiklyThreadSurfaceKind.Channel,
    )

    private fun session(): SharedAdaptiveWorkspaceSession {
        val state = SlockAdaptiveWorkspaceState(
            shellInstanceId = "shell", serverId = "server",
            pane = SlockAdaptivePaneState(widthPx = 2560, heightPx = 1600, density = 2f,
                posture = SlockAdaptivePanePosture.Flat, layout = SlockAdaptivePaneLayout.DualPane),
        )
        return SharedAdaptiveWorkspaceSession(
            workspace = reduceSlockAdaptiveWorkspace(state, SlockAdaptiveWorkspaceIntent.OpenDetail(channel.toSlockAdaptiveDetailIdentity("server")!!)),
            launchPayload = channel,
        )
    }

    private fun SharedAdaptiveWorkspaceSession.source(): SlockAdaptiveDetailCloseRequest =
        SlockAdaptiveDetailCloseRequest("shell", workspace!!.selection!!.identity.key, workspace.selection!!.generation)

    @Test fun replyReplacesOnlyDetailAndBackRestoresParentWithFreshGeneration() {
        val root = session()
        val opened = root.navigateDetail(root.source(), reply, "server")
        assertFalse(root.hasVisibleDetailBackHistory())
        assertTrue(opened.hasVisibleDetailBackHistory())
        assertFalse(opened.copy(workspace = opened.workspace!!.copy(parentVisible = false)).hasVisibleDetailBackHistory())
        assertEquals(reply, opened.launchPayload)
        assertEquals("channel", opened.detailListSelectionId())
        assertEquals(SlockAdaptiveWorkspacePresentation.Split, opened.workspace!!.presentation)
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
        val replyIdentity = reply.toSlockAdaptiveDetailIdentity("server")!!
        val opened = live.withOpenedDetail(replyIdentity, reply)
        assertTrue(opened.workspace!!.parentVisible)
        assertEquals(replyIdentity, opened.workspace.selection!!.identity)
        assertEquals(reply, opened.launchPayload)
        assertEquals(SlockAdaptiveWorkspacePresentation.Split, opened.workspace.presentation)
        // A compact pane cannot host the detail: the session is left untouched.
        val compact = live.copy(
            workspace = reduceSlockAdaptiveWorkspace(
                live.workspace!!,
                SlockAdaptiveWorkspaceIntent.WindowChanged(
                    SlockAdaptivePaneState(widthPx = 800, heightPx = 1600, density = 2f,
                        posture = SlockAdaptivePanePosture.Flat, layout = SlockAdaptivePaneLayout.Compact)
                )
            )
        )
        assertSame(compact, compact.withOpenedDetail(replyIdentity, reply))
    }

    @Test fun onlyAnEmbeddedWideDesktopPageInterceptsNativeNavigation() {
        val root = session()
        assertTrue(shouldOpenWithinAdaptiveDetail(root.source(), root.workspace, true, "server"))
        assertFalse(shouldOpenWithinAdaptiveDetail(root.source(), root.workspace, false, "server"))
        assertFalse(shouldOpenWithinAdaptiveDetail(null, root.workspace, true, "server"))
        assertFalse(shouldOpenWithinAdaptiveDetail(root.source(), root.workspace, true, "other"))
        val compact = root.workspace!!.copy(pane = root.workspace.pane.copy(layout = SlockAdaptivePaneLayout.Compact))
        assertFalse(shouldOpenWithinAdaptiveDetail(root.source(), compact, true, "server"))
    }

    @Test fun staleForeignAndCompactOpensCannotReplacePane() {
        val root = session()
        for (source in listOf(root.source().copy(shellInstanceId = "other"), root.source().copy(generation = 99))) {
            assertEquals(root, root.navigateDetail(source, reply, "server"))
        }
        assertEquals(root, root.navigateDetail(root.source(), reply, "other-server"))
        val compact = root.copy(workspace = root.workspace!!.copy(pane = root.workspace.pane.copy(layout = SlockAdaptivePaneLayout.Compact)))
        assertEquals(compact, compact.navigateDetail(compact.source(), reply, "server"))
    }

    @Test fun resizeKeepsBackHistoryButSwitchingConversationRetiresIt() {
        val store = SharedAdaptiveWorkspaceStore()
        store.update { session() }
        val opened = store.update { it.navigateDetail(it.source(), reply, "server") }
        val compact = store.update { it.copy(workspace = it.workspace!!.copy(pane = it.workspace.pane.copy(layout = SlockAdaptivePaneLayout.Compact))) }
        assertEquals(channel, compact.backFromDetail(compact.source()).launchPayload)
        store.update { opened }
        val switched = store.update { it.copy(workspace = reduceSlockAdaptiveWorkspace(it.workspace!!,
            SlockAdaptiveWorkspaceIntent.OpenDetail(channel.copy(threadId = "other", sourceThreadId = "other").toSlockAdaptiveDetailIdentity("server")!!))) }
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
