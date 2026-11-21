package build.raft.app

import build.raft.app.data.ScreenError
import build.raft.app.data.SearchChannelHit
import build.raft.app.data.SearchCoreGroupState
import build.raft.app.data.SearchCoreGroupStatus
import build.raft.app.data.SearchCoreGroupsState
import build.raft.app.data.SearchPersonHit
import build.raft.app.data.SearchRequiredAction
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MemberKind
import build.raft.app.model.ThreadId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Global search's destinations in groups from core's search (web #15387 `composeSearchEntityGroups`,
 * `EntityResultRow`, `SearchEntityResultsSection`; tests `searchCoreGroups.test.ts`).
 */
class HomeSearchEntityGroupsTest {
    private val local =
        listOf(
            channelRow("local-1", "alp-local"),
            computerRow("m1"),
            personRow("u-local", "Alp Local"),
        )

    @Test
    fun beforeTheServerAnswersEveryGroupShowsItsLocalRowsWithoutACount() {
        val groups = compose(core(channels = loading(), people = loading()))

        assertEquals(
            listOf(
                Triple(HomeSearchEntityGroupId.Channels, false, listOf("conversation:local-1")),
                Triple(HomeSearchEntityGroupId.People, false, listOf("sender:s-1:Human:u-local")),
                Triple(HomeSearchEntityGroupId.Computers, false, listOf("computer:s-1:m1")),
            ),
            groups.groups.map { Triple(it.id, it.fromServer, it.results.map { row -> row.key }) },
        )
        assertTrue(groups.pending)
        // While loading: no count, no Show all (web: its count is the server's total).
        val channels = groups.group(HomeSearchEntityGroupId.Channels)!!
        assertNull(channels.count)
        assertFalse(channels.showsToggle(expanded = false))
        // Computers have no server group: their count is known at once.
        assertEquals(1, groups.group(HomeSearchEntityGroupId.Computers)!!.count)
        assertFalse(groups.incomplete)
    }

    @Test
    fun theServersAnswerReplacesItsGroupsLocalRowsInItsOrderWithItsStatus() {
        val channels =
            ok(
                listOf(
                    channelHit("c-exact", name = "alp"),
                    channelHit("c-not-joined", joined = false, requiredAction = SearchRequiredAction.JoinChannel),
                    channelHit("c-archived", archived = true),
                    channelHit("c-joint", type = "joint", external = true, guest = true),
                    channelHit("local-1"),
                    channelHit("c-6"),
                ),
                total = 37,
                next = "channels.6.tag",
            )
        val groups = compose(core(channels = channels, people = loading()))

        val section = groups.group(HomeSearchEntityGroupId.Channels)!!
        assertTrue(section.fromServer)
        assertEquals(
            listOf("c-exact", "c-not-joined", "c-archived", "c-joint", "local-1", "c-6").map { "conversation:$it" },
            section.results.map { it.key },
            "the server's rows and order, the local row of the same channel among them once",
        )
        assertEquals(37, section.count)
        assertTrue(section.showsToggle(expanded = false), "Show all with a known count over five")
        assertEquals(5, section.shown(expanded = false).size)
        assertFalse(section.showsMore(expanded = false))
        assertTrue(section.showsMore(expanded = true), "Load more once expanded, while the server has more")
        // The people group still waits: local rows, no count; computers stay local.
        assertFalse(groups.group(HomeSearchEntityGroupId.People)!!.fromServer)
        assertTrue(groups.pending)
        assertFalse(groups.group(HomeSearchEntityGroupId.Computers)!!.fromServer)

        // A server answer with nothing for these words: no section, not the local rows.
        assertNull(compose(core(channels = ok(emptyList()), people = loading())).group(HomeSearchEntityGroupId.Channels))
        // An answer for other words is about to be replaced: the local rows, pending.
        val stale = compose(core(channels = channels.copy(query = "al"), people = loading())).group(HomeSearchEntityGroupId.Channels)!!
        assertFalse(stale.fromServer)
        assertTrue(stale.pending)
    }

    @Test
    fun offlineAndAFailedRequestKeepTheLocalRowsAndSayDifferentThings() {
        val failed = SearchCoreGroupState<SearchChannelHit>(SearchCoreGroupStatus.Failed, query = "alp", error = ScreenError("web_search_http_500", traceId = "t1"))
        val groups = compose(core(channels = failed, people = ok(emptyList())))

        assertEquals(listOf("conversation:local-1"), groups.group(HomeSearchEntityGroupId.Channels)!!.results.map { it.key })
        assertEquals(1, groups.group(HomeSearchEntityGroupId.Channels)!!.count, "a failed group counts its local rows")
        assertTrue(groups.incomplete)
        assertEquals("t1", groups.error?.traceId)
        assertEquals(HomeSearchCoreGroupsNote.Offline, groups.note(offline = true))
        assertEquals(HomeSearchCoreGroupsNote.Failed, groups.note(offline = false))

        val retrying = compose(core(channels = SearchCoreGroupState(SearchCoreGroupStatus.Loading, query = "alp", retrying = true), people = ok(emptyList())))
        assertEquals(HomeSearchCoreGroupsNote.Retrying, retrying.note(offline = false))
        assertNull(compose(core(channels = ok(emptyList()), people = ok(emptyList()))).note(offline = true), "complete: no line, offline or not")
    }

    @Test
    fun aFailedNextPageShowsRetryInLoadMoresPlace() {
        val paged = ok((1..6).map { channelHit("c-$it") }, total = 60, next = "p2").copy(pageFailed = true, error = ScreenError("web_search_network", traceId = "t2"))
        val section = compose(core(channels = paged, people = loading())).group(HomeSearchEntityGroupId.Channels)!!

        assertTrue(section.pageFailed)
        assertEquals("t2", section.pageError?.traceId)
        assertTrue(section.showsMore(expanded = true))
        assertEquals(HomeSearchEntityGroupId.Channels.loadMoreIntent(), build.raft.app.data.SearchCoreGroupsIntent.LoadMore(build.raft.app.data.SearchCoreGroup.Channels))
        assertNull(HomeSearchEntityGroupId.Computers.loadMoreIntent())
    }

    @Test
    fun aRowDrawsOneMarkAtMost() {
        fun channel(archived: Boolean = false, external: Boolean = false, guest: Boolean = false) =
            channelHit("c", archived = archived, external = external, guest = guest).toHomeSearchEntityResult(emptyMap(), "Channel").homeSearchEntityBadge()
        assertEquals(HomeSearchEntityBadge.Archived, channel(archived = true, external = true, guest = true), "Archived wins")
        assertEquals(HomeSearchEntityBadge.External, channel(external = true))
        assertEquals(HomeSearchEntityBadge.External, channel(guest = true), "a channel with Guests is External")
        assertNull(channel())

        fun person(agent: Boolean, guest: Boolean) = personHit("p", agent = agent, guest = guest).toHomeSearchEntityResult(emptyMap(), "s-1", "Agent", "Human")!!.homeSearchEntityBadge()
        assertEquals(HomeSearchEntityBadge.Agent, person(agent = true, guest = true), "Agent wins")
        assertEquals(HomeSearchEntityBadge.Guest, person(agent = false, guest = true))
        assertNull(person(agent = false, guest = false))

        // Local rows have no server status: an archived channel still says so; no kind tags.
        assertEquals(HomeSearchEntityBadge.Archived, channelRow("old", "old", archived = true).homeSearchEntityBadge())
        assertNull(channelRow("x", "x").homeSearchEntityBadge())
        assertNull(computerRow("m").homeSearchEntityBadge())
        assertNull(personRow("u", "U").homeSearchEntityBadge())
        assertEquals(HomeSearchEntityBadge.Agent, personRow("a", "A", agent = true).homeSearchEntityBadge())
    }

    @Test
    fun aNotJoinedChannelSaysSoAndOpensItsPreviewNotAJoin() {
        val row =
            channelHit("c-pub", joined = false, description = "Launch notes", requiredAction = SearchRequiredAction.JoinChannel)
                .toHomeSearchEntityResult(emptyMap(), "Channel")

        assertTrue(row.homeSearchNotJoined)
        assertEquals("Launch notes", row.description)
        // One tappable row: it opens the channel itself (the page shows the preview with Join).
        val destination = assertIs<HomeSearchEntityDestination.Conversation>(row.destinationOrNull())
        assertEquals(ChannelId("c-pub"), destination.identity.channelId)
        assertEquals(ConversationKind.Channel, destination.identity.kind)
        assertEquals("conversation:c-pub", row.key)
        assertEquals(destination.identity, row.homeSearchInlineConversationOrNull(), "inline in the wide shell like any channel")
        // Joined, archived or a joint: no "Not joined".
        assertFalse(channelHit("c").toHomeSearchEntityResult(emptyMap(), "Channel").homeSearchNotJoined)
        val joint = channelHit("j", type = "joint", external = true).toHomeSearchEntityResult(emptyMap(), "Channel")
        assertEquals(ConversationKind.Joint, joint.conversationIdentity?.kind)
        assertTrue(joint.homeSearchExternalChannel)
    }

    @Test
    fun aChannelResultTakesTheGlobeAndSlackFromHomeAndPeopleFromOutsideFromItsStatus() {
        // web EntityResultRow `SearchChannelIcon` (botiverse/slock 6b0965841): people from outside come
        // from core's status (`external || guest`), the public page and Slack from the stored channel;
        // the widest group wins (globe > Slack > two people).
        val kinds =
            RaftChannelKinds(
                glyphsByChannelId =
                    mapOf(
                        "s" to RaftChannelKindGlyph.Slack,
                        "p" to RaftChannelKindGlyph.SlackPrivate,
                        "l" to RaftChannelKindGlyph.PublicPage,
                    ),
            )
        fun glyph(hit: SearchChannelHit) = hit.toHomeSearchEntityResult(emptyMap(), "Channel").homeSearchChannelIconGlyph(kinds)

        assertEquals(RaftChannelKindGlyph.Slack, glyph(channelHit("s")))
        // A Guest in a public Slack channel: the Slack mark, no corner lock (it is not private).
        assertEquals(RaftChannelKindGlyph.Slack, glyph(channelHit("s", guest = true)))
        assertEquals(RaftChannelKindGlyph.SlackPrivate, glyph(channelHit("p", type = "private", external = true)))
        // A Guest in a channel anyone can read on the web: the globe.
        assertEquals(RaftChannelKindGlyph.PublicPage, glyph(channelHit("l", guest = true)))
        // Not a Slack channel, or not loaded: two people from the status alone, with the lock when
        // private; else the Home row's or the type's glyph.
        assertEquals(RaftChannelKindGlyph.External, glyph(channelHit("x", external = true)))
        assertEquals(RaftChannelKindGlyph.ExternalPrivate, glyph(channelHit("x", type = "private", guest = true)))
        assertEquals(RaftChannelKindGlyph.Hash, glyph(channelHit("x")))
        assertEquals(RaftChannelKindGlyph.Slack, channelRow("s", "partners-sync").homeSearchChannelIconGlyph(kinds))
    }

    @Test
    fun aServerRowReusesTheLoadedIdentityAndKey() {
        val loadedDm = ThreadLaunchConversationIdentity(threadId = ThreadId("dm-1"), channelId = ChannelId("dm-1"), kind = ConversationKind.DirectMessage)
        val localPerson = personRow("u-1", "Remy").copy(conversationIdentity = loadedDm, threadId = "dm-1")
        val localChannel = channelRow("c-1", "eng")
        val localByKey = listOf(localPerson, localChannel).associateBy { it.key }

        val person = personHit("u-1", displayName = "Remy").toHomeSearchEntityResult(localByKey, "s-1", "Agent", "Human")!!
        assertEquals(localPerson.key, person.key)
        assertEquals(loadedDm, person.conversationIdentity, "the DM the app holds opens without a request")
        assertEquals("Remy", person.title)
        assertEquals("u-1", person.subtitle)

        val channel = channelHit("c-1").toHomeSearchEntityResult(localByKey, "Channel")
        assertEquals(localChannel.conversationIdentity, channel.conversationIdentity)

        // A person without a loaded DM opens through the DM request (else their profile).
        val stranger = personHit("u-9", agent = true).toHomeSearchEntityResult(localByKey, "s-1", "Agent", "Human")!!
        assertNull(stranger.conversationIdentity)
        assertEquals("@u-9", stranger.subtitle)
        assertEquals(MemberKind.Agent, assertIs<HomeSearchEntityDestination.Member>(stranger.destinationOrNull()).identity.kind)
    }

    private fun compose(core: SearchCoreGroupsState?, query: String = "alp") =
        composeHomeSearchEntityGroups(
            query = query,
            local = local,
            core = core,
            toChannel = { it.toHomeSearchEntityResult(emptyMap(), "Channel") },
            toPerson = { it.toHomeSearchEntityResult(emptyMap(), "s-1", "Agent", "Human") },
        )

    private fun core(
        channels: SearchCoreGroupState<SearchChannelHit>,
        people: SearchCoreGroupState<SearchPersonHit>,
    ) = SearchCoreGroupsState(serverId = "s-1", channels = channels, people = people)

    private fun <T> loading() = SearchCoreGroupState<T>(SearchCoreGroupStatus.Loading, query = "alp")

    private fun <T> ok(items: List<T>, total: Int = items.size, next: String? = null) =
        SearchCoreGroupState(SearchCoreGroupStatus.Ok, query = "alp", items = items, total = total, nextCursor = next)

    private fun channelHit(
        id: String,
        name: String = id,
        type: String = "channel",
        description: String? = null,
        archived: Boolean = false,
        joined: Boolean = true,
        external: Boolean = false,
        guest: Boolean = false,
        requiredAction: SearchRequiredAction? = null,
    ) = SearchChannelHit(id, name, description, type, archived, joined, external, guest, canPost = joined && !archived, requiredAction = requiredAction)

    private fun personHit(id: String, agent: Boolean = false, guest: Boolean = false, displayName: String? = null) =
        SearchPersonHit(agent, id, id, displayName, null, if (guest) "guest" else "member", guest, null, false, SearchRequiredAction.CreateDm)

    private fun channelRow(id: String, name: String, archived: Boolean = false) =
        SearchEntityResult(
            key = "conversation:$id",
            type = SearchEntityType.Channel,
            title = name,
            subtitle = "Channel",
            threadId = id,
            memberIdentity = null,
            conversationIdentity = ThreadLaunchConversationIdentity(threadId = ThreadId(id), channelId = ChannelId(id), channelType = "channel", kind = ConversationKind.Channel),
            archived = archived,
        )

    private fun personRow(id: String, name: String, agent: Boolean = false): SearchEntityResult {
        val kind = if (agent) MemberKind.Agent else MemberKind.Human
        return SearchEntityResult(
            key = "sender:s-1:$kind:$id",
            type = if (agent) SearchEntityType.Agent else SearchEntityType.Human,
            title = name,
            subtitle = id,
            threadId = null,
            memberIdentity = memberDetailIdentity(id, kind, "s-1"),
            conversationIdentity = null,
            showPresence = agent,
        )
    }

    private fun computerRow(id: String) =
        SearchEntityResult(
            key = "computer:s-1:$id",
            type = SearchEntityType.Computer,
            title = id,
            subtitle = "Computer",
            threadId = null,
            memberIdentity = null,
            conversationIdentity = null,
            computerIdentity = settingsComputerRouteIdentity("s-1", id),
        )
}
