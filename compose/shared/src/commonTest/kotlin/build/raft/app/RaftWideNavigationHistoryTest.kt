package build.raft.app

import build.raft.app.model.MemberKind
import build.raft.app.shell.RaftWideNavDetail
import build.raft.app.shell.RaftWideNavDirection
import build.raft.app.shell.RaftWideNavEntry
import build.raft.app.shell.RaftWideNavHistories
import build.raft.app.shell.RaftWideNavHistory
import build.raft.app.shell.toRaftWideNavRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** artin 2026-10-27: the wide layout's Back / Forward history ([RaftWideNavHistory]). */
class RaftWideNavigationHistoryTest {
    private fun route(id: String, kind: String = RaftThreadSurfaceKind.Channel) =
        WireThreadLaunchPayload(surfaceKind = kind, threadId = id, sourceThreadId = id)

    private fun conversation(id: String, server: String = "s1", kind: String = RaftThreadSurfaceKind.Channel) =
        route(id, kind).let { RaftWideNavDetail.Conversation(it.toRaftAdaptiveDetailIdentity(server)!!, it) }

    private fun home(id: String, server: String = "s1") = RaftWideNavEntry(server, "home", conversation(id, server))

    private fun RaftWideNavHistory.recordAll(vararg entries: RaftWideNavEntry) = entries.fold(this) { history, entry -> history.record(entry) }

    /** Steps and then shows what the step reopens, as the shell does. */
    private fun RaftWideNavHistory.step(direction: RaftWideNavDirection): RaftWideNavHistory = go(direction).let { it.record(it.current!!) }

    @Test fun `selecting destinations pushes them and Back and Forward walk them without pushing`() {
        val history = RaftWideNavHistory().recordAll(home("a"), home("b"), home("c"))
        assertEquals(listOf(home("a"), home("b"), home("c")), history.entries)
        assertTrue(history.canGoBack)
        assertFalse(history.canGoForward)

        val back = history.step(RaftWideNavDirection.Back)
        assertEquals(home("b"), back.current)
        assertEquals(3, back.entries.size)
        assertNull(back.pendingRestore)
        assertTrue(back.canGoBack)
        assertTrue(back.canGoForward)

        val first = back.step(RaftWideNavDirection.Back)
        assertEquals(home("a"), first.current)
        assertFalse(first.canGoBack)
        assertEquals(first, first.go(RaftWideNavDirection.Back))

        val forward = first.step(RaftWideNavDirection.Forward).step(RaftWideNavDirection.Forward)
        assertEquals(home("c"), forward.current)
        assertFalse(forward.canGoForward)
        assertEquals(forward, forward.go(RaftWideNavDirection.Forward))
    }

    @Test fun `a new selection after going back drops the forward entries`() {
        val history =
            RaftWideNavHistory()
                .recordAll(home("a"), home("b"), home("c"))
                .step(RaftWideNavDirection.Back)
                .step(RaftWideNavDirection.Back)
                .record(home("d"))
        assertEquals(listOf(home("a"), home("d")), history.entries)
        assertEquals(home("d"), history.current)
        assertFalse(history.canGoForward)
    }

    @Test fun `each server keeps its own history`() {
        val histories =
            RaftWideNavHistories()
                .record(home("a", "s1"))
                .record(home("b", "s1"))
                .record(home("x", "s2"))
        assertEquals(listOf(home("a", "s1"), home("b", "s1")), histories.of("s1").entries)
        assertEquals(listOf(home("x", "s2")), histories.of("s2").entries)
        assertFalse(histories.of("s2").canGoBack)
        assertFalse(histories.of("s3").canGoBack)
        assertFalse(histories.of(null).canGoBack)

        val back = histories.go("s1", RaftWideNavDirection.Back)
        assertEquals(home("a", "s1"), back.of("s1").current)
        assertEquals(histories.of("s2"), back.of("s2"))
        // Another server's state never settles this server's restore onto it.
        assertEquals(back.of("s1").copy(pendingRestore = null), back.settle("s1", home("x", "s2")).of("s1"))
    }

    @Test fun `an entry keeps the identity of its route and none of its content`() {
        val opened =
            route("c1").copy(
                focusedMessageId = "msg",
                originFallbackPreview = "hello",
                originFallbackSenderId = "u1",
                originFallbackSenderKind = MemberKind.Human,
                composerSeedText = "draft",
                composerSyncNonce = 3,
                composerFocusRequestNonce = 4,
                focusedMessageRequestNonce = 5,
            )
        val kept = opened.toRaftWideNavRoute()
        assertEquals(opened.toRaftAdaptiveDetailIdentity("s1"), kept.toRaftAdaptiveDetailIdentity("s1"))
        assertEquals("msg", kept.focusedMessageId)
        assertNull(kept.originFallbackPreview)
        assertNull(kept.originFallbackSenderId)
        assertNull(kept.originFallbackSenderKind)
        assertEquals("", kept.composerSeedText)
        assertEquals(0, kept.composerSyncNonce)
        assertEquals(0, kept.composerFocusRequestNonce)
        assertEquals(0, kept.focusedMessageRequestNonce)
    }
}
