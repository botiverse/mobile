package build.raft.app

import build.raft.app.data.DataRequestPriority
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.RaftNetworkClient
import build.raft.app.model.ConversationKind
import build.raft.app.model.ServerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * A link into a public server the account has not joined (slock #11126 rule 2). Wire shapes from
 * botiverse/slock@175cf23e9 packages/server/src/routes/publicServer.ts (`/api/public/servers/…`).
 */
class RaftMessageLinkPublicPreviewTest {
    private val slug = "open-source-club"
    private val cid = "6f1c2b9e-1d2a-4c7b-9a51-0d3e8f7a6b21"
    private val pid = "a3b4c5d6-e7f8-4a1b-8c2d-3e4f5a6b7c8d"
    private val mid = "0b1c2d3e-4f50-4617-8293-a4b5c6d7e8f9"
    private val threadId = "7c7c7c7c-1111-4222-8333-444455556666"

    private fun message(id: String, content: String, threadId: String? = null, replyCount: Int = 0) =
        """{"id":"$id","senderType":"user","sender":{"displayName":"Theo","handle":"theo","avatarUrl":null,"description":null},""" +
            """"messageType":"message","content":"$content","createdAt":"2026-10-24T02:14:00.000Z","attachments":[],"mentions":[],""" +
            """"reactions":[],"threadId":${threadId?.let { "\"$it\"" } ?: "null"},"replyCount":$replyCount}"""

    private val card =
        """{"server":{"id":"s1","name":"Open Source Club","slug":"$slug"},"channels":[{"id":"$cid","name":"release-notes","description":null}],""" +
            """"canJoinAsGuest":false,"translationAvailable":false}"""

    private fun store(network: RaftNetworkClient, link: RaftMessageLink, scope: TestScope) =
        RaftPublicConversationPreviewStore(network, link, scope)

    @Test
    fun readableChannelShowsItsPreviewWithTheMessageHighlighted() = runTest {
        val network =
            FakePublicNetwork(
                channelPage = HomeNetworkResponse(200, """{"messages":[${message(pid, "first")},${message(mid, "second")}]}"""),
                card = HomeNetworkResponse(200, card),
            )
        val state = store(network, RaftMessageLink(slug, ConversationKind.Channel, cid, messageId = mid), this).loadOnce()
        val content = assertIs<RaftPublicPreviewState.Content>(state)
        assertEquals("release-notes", content.channelName)
        assertEquals("Open Source Club", content.serverName)
        assertEquals(listOf(pid, mid), content.messages.map { it.id })
        assertEquals(mid, content.highlightMessageId)
        // Anonymous public routes only; never a member route or another server's header.
        assertEquals(
            setOf("public/servers/$slug", "public/servers/$slug/channels/$cid/messages"),
            network.paths.toSet()
        )
    }

    @Test
    fun threadLinkShowsTheParentAndRepliesWithTheReplyHighlighted() = runTest {
        val network =
            FakePublicNetwork(
                channelPage = HomeNetworkResponse(200, """{"messages":[${message(pid, "parent", threadId, 1)}]}"""),
                card = HomeNetworkResponse(200, card),
                threadPage = HomeNetworkResponse(200, """{"messages":[${message(mid, "reply")}]}"""),
            )
        val link = RaftMessageLink(slug, ConversationKind.Channel, cid, messageId = mid, threadParentMessageId = pid)
        val content = assertIs<RaftPublicPreviewState.Content>(store(network, link, this).loadOnce())
        assertEquals(true, content.thread)
        assertEquals(listOf(pid, mid), content.messages.map { it.id })
        assertEquals(mid, content.highlightMessageId)
    }

    @Test
    fun notReadableByAStrangerIsTheSameNotFoundState() = runTest {
        for (code in listOf(400, 403, 404)) {
            val network = FakePublicNetwork(channelPage = HomeNetworkResponse(code, """{"error":"Not found"}"""))
            val state = store(network, RaftMessageLink(slug, ConversationKind.Channel, cid, messageId = mid), this).loadOnce()
            assertEquals(RaftPublicPreviewState.NotFound, state, "code=$code")
        }
    }

    @Test
    fun serverErrorTimeoutOrOfflineKeepsTheRetryStateNotTheEmptyState() = runTest {
        for (code in listOf(500, 503, 408, 429, 0)) {
            val network = FakePublicNetwork(channelPage = HomeNetworkResponse(code, null))
            val state = store(network, RaftMessageLink(slug, ConversationKind.Channel, cid), this).loadOnce()
            assertEquals(RaftPublicPreviewState.Unavailable, state, "code=$code")
        }
        val offline = FakePublicNetwork(throwOnChannel = true)
        assertEquals(
            RaftPublicPreviewState.Unavailable,
            store(offline, RaftMessageLink(slug, ConversationKind.Channel, cid), this).loadOnce()
        )
    }

    @Test
    fun messageNotOnTheReadablePageStillShowsTheConversation() = runTest {
        val network = FakePublicNetwork(channelPage = HomeNetworkResponse(200, """{"messages":[${message(pid, "only")}]}"""))
        val content =
            assertIs<RaftPublicPreviewState.Content>(
                store(network, RaftMessageLink(slug, ConversationKind.Channel, cid, messageId = mid), this).loadOnce()
            )
        assertNull(content.highlightMessageId)
        assertNull(content.channelName, "the card failed: no name, still the preview")
    }

    @Test
    fun pageDataCarriesOnlyLinkIdentityAndRoundTrips() {
        val link = RaftMessageLink(slug, ConversationKind.DirectMessage, cid, messageId = mid, threadParentMessageId = pid)
        val pageData = link.toPublicPreviewPageData()
        assertEquals(
            setOf("pageName", "serverSlug", "routeKind", "channelId", "messageId", "threadParentMessageId"),
            pageData.keys().asSequence().toSet()
        )
        assertEquals(link, pageData.publicPreviewMessageLinkOrNull())
    }
}

private class FakePublicNetwork(
    private val channelPage: HomeNetworkResponse? = null,
    private val card: HomeNetworkResponse? = null,
    private val threadPage: HomeNetworkResponse? = null,
    private val throwOnChannel: Boolean = false,
) : RaftNetworkClient {
    val paths = mutableListOf<String>()

    override suspend fun request(
        priority: DataRequestPriority,
        path: String,
        method: String,
        query: List<Pair<String, String>>,
        serverIdOverride: ServerId?,
        bodyText: String?,
    ): HomeNetworkResponse {
        paths += path
        return when {
            path.contains("/threads/") -> threadPage ?: HomeNetworkResponse(404, null)
            path.endsWith("/messages") -> if (throwOnChannel) error("dns") else channelPage ?: HomeNetworkResponse(404, null)
            path.startsWith("public/servers/") -> card ?: HomeNetworkResponse(500, null)
            else -> error("unexpected request $path")
        }
    }
}
