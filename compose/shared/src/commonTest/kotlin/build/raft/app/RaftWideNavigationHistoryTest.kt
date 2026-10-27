package build.raft.app

import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.ServerId
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

    private fun member(id: String) =
        RaftWideNavEntry("s1", "members", RaftWideNavDetail.Member(MemberDetailIdentity(MemberId(id), MemberKind.Agent, ServerId("s1"))))

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

    @Test fun `an empty history has neither button enabled`() {
        val empty = RaftWideNavHistory()
        assertFalse(empty.canGoBack)
        assertFalse(empty.canGoForward)
        assertEquals(empty, empty.go(RaftWideNavDirection.Back))
        val one = empty.record(home("a"))
        assertFalse(one.canGoBack)
        assertFalse(one.canGoForward)
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

    @Test fun `the oldest entries drop off past the cap`() {
        val history = (1..60).fold(RaftWideNavHistory()) { acc, n -> acc.record(home("c$n")) }
        assertEquals(RaftWideNavHistoryCap, history.entries.size)
        assertEquals(home("c11"), history.entries.first())
        assertEquals(home("c60"), history.current)
        val small = RaftWideNavHistory(cap = 2).recordAll(home("a"), home("b"), home("c"))
        assertEquals(listOf(home("b"), home("c")), small.entries)
        assertEquals(1, small.index)
    }

    @Test fun `re-selecting what is showing adds no entry`() {
        val history = RaftWideNavHistory().recordAll(home("a"), home("b"), home("b"))
        assertEquals(listOf(home("a"), home("b")), history.entries)
        // The same conversation reopened with another launch route (instance, time context) is the same stop.
        val reopened = RaftWideNavEntry("s1", "home", RaftWideNavDetail.Conversation(conversation("b").identity, route("b").copy(instanceId = "other")))
        assertEquals(history, history.record(reopened))
    }

    @Test fun `a pick on a surface that showed nothing takes the empty entry's place`() {
        val history =
            RaftWideNavHistory().recordAll(
                RaftWideNavEntry("s1", "home"),
                home("all"),
                RaftWideNavEntry("s1", "members"),
                member("m1"),
                member("m2"),
            )
        assertEquals(listOf(home("all"), member("m1"), member("m2")), history.entries)
        // Another surface's empty state is a stop of its own.
        val settings = history.record(RaftWideNavEntry("s1", "settings"))
        assertEquals(RaftWideNavEntry("s1", "settings"), settings.current)
        assertEquals(4, settings.entries.size)
    }

    @Test fun `a restore ignores the states it passes through until its entry shows`() {
        val restoring = RaftWideNavHistory().recordAll(home("a"), member("m1")).go(RaftWideNavDirection.Back)
        assertEquals(home("a"), restoring.pendingRestore)
        assertTrue(restoring.canGoForward)
        // The tab switched first; the conversation is not open yet.
        val midway = restoring.record(RaftWideNavEntry("s1", "home", conversation("old")))
        assertEquals(restoring, midway)
        val arrived = midway.record(home("a"))
        assertNull(arrived.pendingRestore)
        assertEquals(listOf(home("a"), member("m1")), arrived.entries)
        assertEquals(0, arrived.index)
    }

    @Test fun `a restore that never arrives settles on what is showing`() {
        val restoring = RaftWideNavHistory().recordAll(home("a"), home("b"), home("c")).go(RaftWideNavDirection.Back)
        val settled = restoring.settle(home("x"))
        assertNull(settled.pendingRestore)
        assertEquals(listOf(home("a"), home("b"), home("x")), settled.entries)
        assertEquals(restoring.copy(pendingRestore = null), restoring.settle(null))
        val idle = RaftWideNavHistory().record(home("a"))
        assertEquals(idle, idle.settle(home("z")))
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

    @Test fun `the entry shows the detail the surface draws`() {
        val channel = route("c1")
        val dm = route("d1", RaftThreadSurfaceKind.DirectMessage)
        val memberIdentity = MemberDetailIdentity(MemberId("m1"), MemberKind.Human, ServerId("s1"))
        fun entryOf(
            tab: String,
            route: WireThreadLaunchPayload? = channel,
            inline: String? = null,
            homeDestination: String? = null,
            tasksOverlay: Boolean = false,
            server: String? = "s1",
        ) = raftWideNavEntryOf(
            serverId = server,
            selectedTab = tab,
            railInlineSecondaryDestination = inline,
            selectedHomeDestination = homeDestination,
            tasksDetailOverlayOpen = tasksOverlay,
            conversation = route?.toRaftAdaptiveDetailIdentity("s1"),
            conversationRoute = route,
            member = memberIdentity,
        )

        assertEquals(RaftWideNavEntry("s1", "home", conversation("c1")), entryOf("home"))
        assertEquals(RaftWideNavEntry("s1", "activity", conversation("c1")), entryOf("activity"))
        assertEquals(RaftWideNavEntry("s1", AppShellRailSearchKey, conversation("c1")), entryOf("home", inline = AppShellRailSearchKey))
        assertEquals(RaftWideNavEntry("s1", AppShellRailSavedKey, conversation("c1")), entryOf("members", inline = AppShellRailSavedKey))
        assertEquals(RaftWideNavEntry("s1", "members", RaftWideNavDetail.Member(memberIdentity)), entryOf("members"))
        assertEquals(RaftWideNavEntry("s1", "settings"), entryOf("settings"))
        assertEquals(RaftWideNavEntry("s1", "tasks"), entryOf("tasks"))
        assertEquals(RaftWideNavEntry("s1", "tasks", conversation("c1")), entryOf("tasks", tasksOverlay = true))
        // The DMs tab draws a DM only; a channel kept from Home is not its detail.
        assertEquals(RaftWideNavEntry("s1", "dms"), entryOf("dms"))
        assertEquals(RaftWideNavEntry("s1", "dms", conversation("d1", kind = RaftThreadSurfaceKind.DirectMessage)), entryOf("dms", route = dm))
        assertEquals(RaftWideNavEntry("s1", "home"), entryOf("home", route = null))
        assertNull(entryOf("home", homeDestination = "saved"))
        assertNull(entryOf("home", server = null))
        assertNull(entryOf("home", server = " "))
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

    @Test fun `host directions parse from their wire names`() {
        assertEquals(RaftWideNavDirection.Back, RaftWideNavDirection.fromWireName("back"))
        assertEquals(RaftWideNavDirection.Forward, RaftWideNavDirection.fromWireName(" forward "))
        assertNull(RaftWideNavDirection.fromWireName("sideways"))
        assertNull(RaftWideNavDirection.fromWireName(null))
        assertEquals("slockWideHistoryNavigate", RAFT_WIDE_HISTORY_NAVIGATE_EVENT)
        assertEquals("direction", RAFT_WIDE_HISTORY_DIRECTION)
    }
}
