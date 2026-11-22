package build.raft.app

import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Where a rail click lands on the wide layout (RaftRailPlaces.kt; web layout-memory.md, slock #15815). Each case is
 * what the rail tabs showed (their places), what showed when the rail was clicked and what the shared detail held
 * (and which surface opened it), then where the click lands.
 */
class RaftRailPlacesTest {
    private fun channel(id: String, focusedMessageId: String? = null) =
        WireThreadLaunchPayload(
            surfaceKind = RaftThreadSurfaceKind.Channel,
            sourceThreadId = id,
            streamChannelId = id,
            focusedMessageId = focusedMessageId,
        )

    private fun dm(id: String) =
        WireThreadLaunchPayload(
            surfaceKind = RaftThreadSurfaceKind.DirectMessage,
            threadId = id,
            sourceThreadId = id,
            streamChannelId = id,
        )

    private fun reply(parentChannelId: String, parentMessageId: String) =
        WireThreadLaunchPayload(
            surfaceKind = RaftThreadSurfaceKind.ReplyThread,
            threadId = "stream-$parentMessageId",
            sourceThreadId = "stream-$parentMessageId",
            streamChannelId = "stream-$parentMessageId",
            parentChannelId = parentChannelId,
            parentMessageId = parentMessageId,
            parentSurfaceKind = RaftThreadSurfaceKind.Channel,
        )

    /** [surface] showing [route] in the shared detail (none when null). */
    private fun on(surface: String, route: WireThreadLaunchPayload? = null, server: String = "s1") =
        RaftWideNavEntry(
            server,
            surface,
            route?.let { RaftWideNavDetail.Conversation(it.toRaftAdaptiveDetailIdentity(server)!!, it) },
        )

    /** The places after the wide layout showed [entries] in turn, Chat's default landing settled. */
    private fun seen(vararg entries: RaftWideNavEntry) =
        entries.fold(RaftRailPlaces()) { places, entry -> places.remember(entry, landingSettled = true) }

    private val proj = channel("proj-workspace")
    private val projThread = reply(parentChannelId = "proj-workspace", parentMessageId = "m1")
    private val ana = dm("dm-ana")
    private val member =
        RaftWideNavEntry("s1", "members", RaftWideNavDetail.Member(MemberDetailIdentity(MemberId("u-ana"), MemberKind.Human)))

    private class Case(
        val name: String,
        val places: RaftRailPlaces,
        /** What the wide layout showed when the rail was clicked; null off the wide layout. */
        val before: RaftWideNavEntry?,
        val tab: String,
        /** The conversation the shared detail held at the click (on server s1), and the surface that opened it. */
        val open: WireThreadLaunchPayload? = null,
        val openedOn: String? = null,
        val expected: RaftRailLanding,
        /** The shell's tab after the click; the clicked one unless the click selected nothing. */
        val selectedTab: String = tab,
    )

    private val cases =
        listOf(
            Case(
                "Chat comes back to its channel after a visit to Activity",
                places = seen(on("home", proj), on("activity")),
                before = on("activity"),
                tab = "home",
                expected = RaftRailLanding.Restore(on("home", proj)),
            ),
            Case(
                "a conversation opened from Activity does not become Chat's",
                places = seen(on("home", proj), on("activity"), on("activity", ana)),
                before = on("activity", ana),
                tab = "home",
                open = ana,
                openedOn = "activity",
                expected = RaftRailLanding.Restore(on("home", proj)),
            ),
            Case(
                "closing a reply thread in Activity does not clear Chat's channel",
                places = seen(on("home", proj), on("activity", projThread), on("activity")),
                before = on("activity"),
                tab = "home",
                expected = RaftRailLanding.Restore(on("home", proj)),
            ),
            Case(
                "a reply thread of Chat's channel that Activity opened is Activity's: Chat reopens its channel",
                places = seen(on("home", proj), on("activity", projThread)),
                before = on("activity", projThread),
                tab = "home",
                open = projThread,
                openedOn = "activity",
                expected = RaftRailLanding.Restore(on("home", proj)),
            ),
            Case(
                "Chat's channel still in the detail stays as it is (Members leaves it there)",
                places = seen(on("home", proj), member),
                before = member,
                tab = "home",
                open = proj,
                openedOn = "home",
                expected = RaftRailLanding.Keep,
            ),
            Case(
                "a reply thread Chat opened over its channel is a pane of it: the channel stays the place",
                places = seen(on("home", proj), on("home", projThread), on("activity", ana)),
                before = on("activity", ana),
                tab = "home",
                open = ana,
                openedOn = "activity",
                expected = RaftRailLanding.Restore(on("home", proj)),
            ),
            Case(
                "the reply thread Chat opened over its channel stays open while it is still there",
                places = seen(on("home", proj), on("home", projThread), member),
                before = member,
                tab = "home",
                open = projThread,
                openedOn = "home",
                expected = RaftRailLanding.Keep,
            ),
            Case(
                "a link opened in Chat wins: it is Chat's place, its focused message a one-shot open",
                places = seen(on("home", proj), on("home", channel("ops", focusedMessageId = "m42")), on("activity")),
                before = on("activity"),
                tab = "home",
                expected = RaftRailLanding.Restore(on("home", channel("ops"))),
            ),
            Case(
                "leaving Search for Chat lands on Chat's channel, not the result open beside Search",
                places = seen(on("home", proj), on(AppShellRailSearchKey, ana)),
                before = on(AppShellRailSearchKey, ana),
                tab = "home",
                open = ana,
                openedOn = AppShellRailSearchKey,
                expected = RaftRailLanding.Restore(on("home", proj)),
            ),
            Case(
                "a close in Chat is its place: nothing open",
                places = seen(on("home", proj), on("home"), on("activity", ana)),
                before = on("activity", ana),
                tab = "home",
                open = ana,
                openedOn = "activity",
                expected = RaftRailLanding.Restore(on("home")),
            ),
            Case(
                "the channel Chat's default landing picked is picked again, so it stays automatic",
                places = RaftRailPlaces().remember(on("home", channel("all")), landingSettled = true, landedByDefault = true),
                before = on("activity", ana),
                tab = "home",
                open = ana,
                openedOn = "activity",
                expected = RaftRailLanding.DefaultLanding,
            ),
            Case(
                "the channel Chat's default landing picked stays as it is while still open",
                places = RaftRailPlaces().remember(on("home", channel("all")), landingSettled = true, landedByDefault = true),
                before = member,
                tab = "home",
                open = channel("all"),
                openedOn = "home",
                expected = RaftRailLanding.Keep,
            ),
            Case(
                "once the user opens a conversation in Chat it is restored as theirs",
                places =
                    RaftRailPlaces()
                        .remember(on("home", channel("all")), landingSettled = true, landedByDefault = true)
                        .remember(on("home", proj), landingSettled = true),
                before = on("activity", ana),
                tab = "home",
                open = ana,
                openedOn = "activity",
                expected = RaftRailLanding.Restore(on("home", proj)),
            ),
            Case(
                "Chat showing nothing before its default landing settled is no place: the default landing picks",
                places = RaftRailPlaces().remember(on("home"), landingSettled = false),
                before = on("activity", ana),
                tab = "home",
                open = ana,
                openedOn = "activity",
                expected = RaftRailLanding.DefaultLanding,
            ),
            Case(
                "Activity lands on its list with nothing open beside it",
                places = seen(on("home", proj)),
                before = on("home", proj),
                tab = "activity",
                open = proj,
                openedOn = "home",
                expected = RaftRailLanding.Restore(on("activity")),
            ),
            Case(
                "Members lands on its list with no member open",
                places = seen(member, on("home", proj)),
                before = on("home", proj),
                tab = "members",
                open = proj,
                openedOn = "home",
                expected = RaftRailLanding.Restore(on("members")),
            ),
            Case(
                "Tasks comes back on its list as it is (its task column closed when Tasks was left)",
                places = seen(on("home", proj)),
                before = on("home", proj),
                tab = "tasks",
                open = proj,
                openedOn = "home",
                expected = RaftRailLanding.Keep,
            ),
            Case(
                "re-selecting Chat keeps the conversation it shows",
                places = seen(on("home", proj)),
                before = on("home", proj),
                tab = "home",
                open = proj,
                openedOn = "home",
                expected = RaftRailLanding.Keep,
            ),
            Case(
                "each server keeps its own Chat place",
                places = seen(on("home", proj, server = "s1"), on("home", channel("ops"), server = "s2")),
                before = on("activity", server = "s1"),
                tab = "home",
                expected = RaftRailLanding.Restore(on("home", proj, server = "s1")),
            ),
            Case(
                "Chat never shown on this server lands by default, not on another server's place",
                places = seen(on("home", proj, server = "s1")),
                before = on("activity", server = "s2"),
                tab = "home",
                expected = RaftRailLanding.DefaultLanding,
            ),
            Case(
                "off the wide layout (a phone) nothing lands",
                places = seen(on("home", proj)),
                before = null,
                tab = "home",
                expected = RaftRailLanding.Keep,
            ),
            Case(
                "a click the shell did not take lands nothing",
                places = seen(on("home", proj)),
                before = on("activity", ana),
                tab = "home",
                open = ana,
                openedOn = "activity",
                expected = RaftRailLanding.Keep,
                selectedTab = "activity",
            ),
        )

    @Test
    fun aRailClickLandsOnTheTabsOwnPlace() {
        cases.forEach { case ->
            val open = case.open?.toRaftAdaptiveDetailIdentity("s1")
            assertEquals(
                case.expected,
                case.places.landing(case.before, case.tab, case.selectedTab, open, case.openedOn),
                case.name,
            )
        }
    }

    /**
     * A place reopens through the history's restore path, which refuses an open that is not the place's conversation:
     * its route must name exactly that conversation, without the focused message of the link that opened it.
     */
    @Test
    fun aChatPlaceReopensAsTheConversationItNames() {
        val place = seen(on("home", channel("ops", focusedMessageId = "m42"))).placeFor("s1", "home")
        val detail = assertIs<RaftWideNavDetail.Conversation>(place?.detail)
        assertEquals(detail.identity, detail.route.toRaftAdaptiveDetailIdentity("s1"))
        assertNull(detail.route.focusedMessageId)
    }
}
