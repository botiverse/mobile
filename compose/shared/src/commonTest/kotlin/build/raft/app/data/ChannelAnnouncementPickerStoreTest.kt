package build.raft.app.data

import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The Set / Replace picker's list (web `AnnouncementPicker`): the channel's Artifacts a page at a
 * time from `GET artifacts/conversations/:id` (cards as ConversationArtifactsApiTest writes them),
 * the current announcement left out, a failure that offers Try again.
 */
class ChannelAnnouncementPickerStoreTest {
    private fun card(id: String, title: String) =
        """
        {
          "id": "$id", "url": "https://app.raft.build/artifacts/$id", "title": "$title", "description": null,
          "kind": "markdown", "facts": { "sizeBytes": 2048, "fileCount": 1, "pages": 1 },
          "preview": { "kind": "excerpt", "text": "First lines" }, "version": 1, "pinned": false, "latestVersion": 2,
          "author": { "type": "agent", "id": "agent-1", "name": "kirby", "displayName": "Kirby", "avatarUrl": null },
          "createdAt": "2026-10-20T08:00:00.000Z", "updatedAt": "2026-10-28T09:30:00.000Z", "commentCount": 0,
          "home": { "kind": "conversation", "conversation": { "id": "ch-ops", "name": "ops", "type": "channel" } }
        }
        """.trimIndent()

    private class FakeApi(val pages: MutableList<HomeNetworkResponse>) : RaftConversationArtifactsApi {
        val cursors = mutableListOf<String?>()

        override suspend fun conversationArtifacts(serverId: String, conversationId: String, cursor: String?): HomeNetworkResponse {
            cursors += cursor
            return pages.removeAt(0)
        }
    }

    private fun page(vararg cards: String, next: String?) =
        HomeNetworkResponse(200, """{ "rows": [${cards.joinToString { """{ "card": $it }""" }}], "nextCursor": ${next?.let { "\"$it\"" } ?: "null"} }""")

    @Test
    fun listsTheChannelsArtifactsPageByPageWithoutTheCurrentOne() =
        runTest {
            val api = FakeApi(mutableListOf(page(card("pt_a", "Rules"), card("pt_b", "Links"), next = "c1"), page(card("pt_c", "Dates"), next = null)))
            val store = ChannelAnnouncementPickerStore(api, "srv-1", "ch-ops", this)
            store.dispatch(ChannelAnnouncementPickerIntent.Load)
            advanceUntilIdle()
            assertEquals(ChannelAnnouncementPickerState.Status.Ready, store.state.value.status)
            assertEquals(listOf("pt_b"), store.state.value.choicesExcept("pt_a").map { it.summary.id })
            store.dispatch(ChannelAnnouncementPickerIntent.ShowMore)
            advanceUntilIdle()
            assertEquals(listOf(null, "c1"), api.cursors)
            assertEquals(listOf("pt_a", "pt_b", "pt_c"), store.state.value.artifacts.map { it.summary.id })
            assertEquals(null, store.state.value.nextCursor)
            assertFalse(store.state.value.loadingMore)
        }

    @Test
    fun aFailedReadOffersTryAgain() =
        runTest {
            val api = FakeApi(mutableListOf(HomeNetworkResponse(403, null), page(card("pt_a", "Rules"), next = null)))
            val store = ChannelAnnouncementPickerStore(api, "srv-1", "ch-ops", this)
            store.dispatch(ChannelAnnouncementPickerIntent.Load)
            advanceUntilIdle()
            assertEquals(ChannelAnnouncementPickerState.Status.Failed, store.state.value.status)
            store.dispatch(ChannelAnnouncementPickerIntent.Load)
            advanceUntilIdle()
            assertEquals(listOf("pt_a"), store.state.value.artifacts.map { it.summary.id })
        }
}
