package build.raft.app

import build.raft.app.data.ArtifactHome
import build.raft.app.data.ArtifactKind
import build.raft.app.data.ArtifactSummary
import build.raft.app.data.ArtifactsHomeActor
import build.raft.app.data.ArtifactsHomeNews
import build.raft.app.data.ArtifactsHomeRow
import build.raft.app.data.SearchArtifactHit
import build.raft.app.data.SearchArtifactsState
import build.raft.app.data.SearchArtifactsStatus
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ThreadId
import build.raft.app.thread.key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The wide panel's Artifacts group (web `MessageSearchPage` `appSearch.state.groups` between the
 * entity results and the messages, `appGroupPreviewLimit`, `showNoResults`): where it goes, what
 * the cursor walks, and that "No results" waits for it.
 */
class HomeSearchArtifactsOverlayTest {
    @Test
    fun theGroupSitsBetweenTheDestinationsAndTheMessagesAndTheCursorWalksIt() {
        val content = content(SearchArtifactsState(query = "plan", status = SearchArtifactsStatus.Ok, hits = (1..7).map { hit("pt_$it") }))

        val entries = content.entries()
        val headings = entries.map { it.itemKey }.filter { it.contains("-heading") }
        assertEquals(listOf("entities-heading:channels", "artifacts-heading", "messages-heading"), headings)
        assertEquals(HomeSearchOverlayMessagePreviewCount, entries.count { it is HomeSearchOverlayEntry.Artifact }, "a preview, like its messages")
        assertEquals(
            listOf(HomeSearchOverlaySection.SearchFor, HomeSearchOverlaySection.Entities) +
                List(HomeSearchOverlayMessagePreviewCount) { HomeSearchOverlaySection.Artifacts } +
                HomeSearchOverlaySection.Messages,
            content.rows.map { it.section },
        )
        assertEquals("app:artifacts:pt_1", content.rows.first { it.section == HomeSearchOverlaySection.Artifacts }.key)
    }

    @Test
    fun noResultsWaitsForTheGroupAndAGroupWithNothingHasNoSection() {
        fun entries(artifacts: SearchArtifactsState?) = content(artifacts, entities = false, messages = false).entries()

        assertTrue(entries(null).any { it == HomeSearchOverlayEntry.NoResults })
        assertTrue(entries(SearchArtifactsState(query = "plan", status = SearchArtifactsStatus.Ok)).let { list ->
            list.any { it == HomeSearchOverlayEntry.NoResults } && list.none { it is HomeSearchOverlayEntry.ArtifactsHeading }
        })
        for (status in listOf(SearchArtifactsStatus.Loading, SearchArtifactsStatus.Failed)) {
            assertTrue(entries(SearchArtifactsState(query = "plan", status = status)).none { it == HomeSearchOverlayEntry.NoResults }, "$status")
        }
        val found = entries(SearchArtifactsState(query = "plan", status = SearchArtifactsStatus.Ok, hits = listOf(hit("pt_a"))))
        assertTrue(found.none { it == HomeSearchOverlayEntry.NoResults })
        assertEquals(1, found.count { it is HomeSearchOverlayEntry.Artifact })
    }

    private fun content(artifacts: SearchArtifactsState?, entities: Boolean = true, messages: Boolean = true) =
        HomeSearchOverlayContent(
            typed = true,
            recent = emptyList(),
            exact = null,
            entityGroups =
                if (entities) {
                    listOf(HomeSearchEntityGroupView(HomeSearchEntityGroupId.Channels, listOf(channelEntity("plan-room")), total = 1, fromServer = false))
                } else {
                    emptyList()
                },
            messages =
                if (messages) {
                    listOf(HomeSearchResultGroup.Message(build.raft.app.data.InboxItem(key = "m1", kind = build.raft.app.model.InboxItemKind.Channel, channelId = ChannelId("c"), channelName = "launch")))
                } else {
                    emptyList()
                },
            messageCount = null,
            searched = true,
            loading = false,
            artifacts = artifacts,
        )

    private fun channelEntity(name: String): SearchEntityResult =
        SearchEntityResult(
            key = "conversation:$name",
            type = SearchEntityType.Channel,
            title = name,
            subtitle = "Channel",
            threadId = name,
            memberIdentity = null,
            conversationIdentity =
                ThreadLaunchConversationIdentity(threadId = ThreadId(name), channelId = ChannelId(name), kind = ConversationKind.Channel),
        )

    private fun hit(id: String) =
        SearchArtifactHit(
            ArtifactsHomeRow(
                summary =
                    ArtifactSummary(
                        id = id,
                        title = "Plan $id",
                        description = null,
                        kind = ArtifactKind.Html,
                        version = 1,
                        pinned = false,
                        latestVersion = 1,
                        home = ArtifactHome.Public,
                    ),
                author = ArtifactsHomeActor(type = "user", id = "u-1", name = "kirby", displayName = "Kirby"),
                openedAt = null,
                news = ArtifactsHomeNews(),
            ),
        )
}
