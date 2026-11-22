package build.raft.app.links

import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MessageId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Ported from slock `packages/shared/src/appLinkPreview.test.ts` (bare links),
 * `packages/shared/src/raftPermalinks.test.ts` and the quoted-permalink extractor's rules
 * (`packages/web/src/components/message/quotedMessagePermalink.ts`).
 */
class RaftMessageLinksTest {
    // --- bare links -----------------------------------------------------------------------------

    @Test
    fun bareLinksUseOnlyLinksWhoseTextIsTheirOwnAddress() {
        assertEquals(
            emptyList(),
            raftMessageBareLinks(
                "[my fix](https://app.example/pr/1) and [PR][id] and [**PR**](https://app.example/pr/3)\n\n" +
                    "[id]: https://app.example/pr/2",
            ),
        )
        assertEquals(
            listOf("https://app.example/pr/4", "https://app.example/pr/5", "https://app.example/pr/6"),
            raftMessageBareLinks(
                "<https://app.example/pr/4> [https://app.example/pr/5](https://app.example/pr/5) https://app.example/pr/6",
            ),
        )
        // Text that is another address is not bare: it would show one link and open another.
        assertEquals(emptyList(), raftMessageBareLinks("[https://app.example/pr/7](https://app.example/pr/8)"))
        // Markup in the text: the author's link, not a chip.
        assertEquals(emptyList(), raftMessageBareLinks("[<b>https://app.example/pr/9</b>](https://app.example/pr/9)"))
    }

    // --- classification -------------------------------------------------------------------------

    @Test
    fun planSplitsObjectsFromAppLinksAndCapsObjectsAtTen() {
        val hosts = listOf("app.raft.build")
        val artifacts = (0 until 12).map { "https://app.raft.build/artifacts/pt_a$it" }
        val app = "https://linear.app/team/issue/ABC-1"
        val plan = planRaftMessageLinks((artifacts + app).joinToString(" "), hosts)
        assertEquals(10, plan.objectCards.size)
        assertEquals(artifacts.take(10), plan.objectCards)
        assertEquals(10, plan.objects.size)
        assertEquals(listOf(app), plan.appLinks, "links past the limit stay the author's: not an App's either")
    }

    // --- permalinks -----------------------------------------------------------------------------

    private val hosts = raftPermalinkHostnames(emptyList())
    private val channel = "89dce17f-1cd3-4db8-b08f-7b4141004b5a"
    private val message = "7556f881-a5f8-4b0d-88dc-8b5af28e8f05"
    private val parent = "11111111-2222-3333-4444-555555555555"

    @Test
    fun parsesPermalinksOnTheDefaultHosts() {
        for (host in listOf("app.slock.ai", "app.raft.build")) {
            assertEquals(
                ParsedRaftPermalink(
                    "https://$host/s/botiverse/channel/$channel?msg=$message",
                    "botiverse",
                    ConversationKind.Channel,
                    ChannelId(channel),
                    MessageId(message),
                    null,
                ),
                parseRaftPermalink("https://$host/s/botiverse/channel/$channel?msg=$message", hosts),
            )
        }
        assertNull(parseRaftPermalink("https://api.raft.build/s/botiverse/channel/$channel?msg=$message", hosts))
        assertEquals(
            ConversationKind.DirectMessage,
            parseRaftPermalink("https://app.slock.ai/s/botiverse/dm/$channel?msg=$message", hosts)?.routeKind,
        )
    }

    @Test
    fun rejectsUrlsWithoutAMessageIdAndThreadsOfAnotherChannel() {
        assertNull(parseRaftPermalink("https://app.slock.ai/s/botiverse/channel/$channel", hosts))
        assertNull(parseRaftPermalink("https://app.slock.ai/s/botiverse/channel/$channel?thread=other%3A$parent&msg=$message", hosts))
        assertNull(parseRaftPermalink("https://app.slock.ai/s/botiverse/joint/$channel?msg=$message", hosts))
    }

    @Test
    fun keepsOnlyTheLeadingMessageIdWhenCjkTextFollows() {
        val base = "https://app.slock.ai/s/botiverse/channel/$channel"
        assertEquals(MessageId(message), parseRaftPermalink("$base?msg=${message}这条你是不是没收到", hosts)?.messageId)
        assertEquals(MessageId(message), parseRaftPermalink("$base?msg=$message，看一下", hosts)?.messageId)
        assertEquals(MessageId("7556f881"), parseRaftPermalink("$base?msg=7556f881这条", hosts)?.messageId)
        val thread = parseRaftPermalink("$base?thread=$channel%3A${parent}这个", hosts)
        assertEquals(MessageId(parent), thread?.messageId)
        assertEquals(MessageId(parent), thread?.threadParentMessageId)
    }

    // --- the quoted permalink -------------------------------------------------------------------

    @Test
    fun quotedPermalinkIsTheFirstOfTheCurrentServerOnly() {
        val other = "https://app.raft.build/s/other/channel/$channel?msg=$message"
        val mine = "https://app.raft.build/s/mine/channel/$channel?msg=$message"
        val second = "https://app.raft.build/s/mine/channel/$channel?msg=$parent"
        assertEquals(mine, extractFirstQuotedRaftPermalink("$other $mine $second", hosts, "mine")?.rawUrl)
        assertNull(extractFirstQuotedRaftPermalink(other, hosts, "mine"))
        assertNull(extractFirstQuotedRaftPermalink(mine, hosts, null))
    }
}
