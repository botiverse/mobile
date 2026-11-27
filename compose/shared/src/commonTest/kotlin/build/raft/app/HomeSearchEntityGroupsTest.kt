package build.raft.app

import build.raft.app.data.ScreenError
import build.raft.app.data.SearchChannelHit
import build.raft.app.data.SearchCoreGroupState
import build.raft.app.data.SearchCoreGroupStatus
import build.raft.app.data.SearchCoreGroupsState
import build.raft.app.data.SearchPersonHit
import build.raft.app.data.SearchRequiredAction
import build.raft.app.memberDetailIdentity
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MemberKind
import build.raft.app.model.ThreadId
import build.raft.app.settings.HomeSearchEntityDestination
import build.raft.app.settings.SearchEntityResult
import build.raft.app.settings.SearchEntityType
import build.raft.app.settings.destinationOrNull
import build.raft.app.settings.homeSearchInlineConversationOrNull
import build.raft.app.settings.toHomeSearchEntityResult
import build.raft.app.thread.key
import build.raft.app.thread.memberDetailIdentity
import build.raft.app.toHomeSearchEntityResult
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
