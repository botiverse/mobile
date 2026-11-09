package build.raft.app.links

import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MessageId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Ported from slock `packages/shared/src/appLinkPreview.test.ts` (bare links),
 * `packages/shared/src/raftPermalinks.test.ts` and the quoted-permalink extractor's rules
 * (`packages/web/src/components/message/quotedMessagePermalink.ts`).
 */
class RaftMessageLinksTest {
    // --- bare links -----------------------------------------------------------------------------

    @Test
    fun bareLinksSkipCodeImagesUnusedDefinitionsAndNamedLinks() {
        val text =
            "`https://code.example/x` ![img](https://img.example/x) [named](https://app.example/pr/1) " +
                "https://app.example/pr/1 http://localhost:5173/artifacts/pt_dev " +
                "https://raft.example.com:8443/artifacts/pt_x https://app.example:443/pr/1 mailto:x@example.com"
        assertEquals(
            listOf(
                "https://app.example/pr/1",
                "http://localhost:5173/artifacts/pt_dev",
                "https://raft.example.com:8443/artifacts/pt_x",
            ),
            raftMessageBareLinks(text),
        )
    }

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

    @Test
    fun bareLinksResolveReferenceLinksWhoseTextIsTheAddress() {
        assertEquals(
            listOf("https://app.example/pr/2"),
            raftMessageBareLinks("[https://app.example/pr/2][id]\n\n[id]: https://app.example/pr/2"),
        )
    }

    @Test
    fun bareLinksAreDeduplicatedInMessageOrderAndNotLimited() {
        val twenty = (0 until 20).map { "https://app.example/pr/$it" }
        assertEquals(20, raftMessageBareLinks(twenty.joinToString(" ")).size)
        assertEquals(
            listOf(twenty[1], twenty[0]) + twenty.drop(2),
            raftMessageBareLinks((listOf(twenty[1], twenty[0], twenty[1], "https://app.example:443/pr/0") + twenty.drop(2)).joinToString(" ")),
            "an explicit :443 is the same link",
        )
    }

    @Test
    fun messageLinkKeyIsTheWhatwgHref() {
        assertEquals("https://app.example/", raftMessageLinkKey("HTTPS://App.Example"))
        assertEquals("https://app.example/a?b=1", raftMessageLinkKey("https://app.example:443/a?b=1"))
        assertNull(raftMessageLinkKey("mailto:x@example.com"))
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

    @Test
    fun planCountsOneReferenceOnceAndCardsTheFirstLink() {
        val hosts = listOf("app.raft.build")
        val a = "https://app.raft.build/artifacts/pt_a"
        val aAgain = "https://app.raft.build/s/raft/artifacts/pt_a"
        val plan = planRaftMessageLinks("$a $aAgain", hosts)
        assertEquals(listOf(a), plan.objectCards)
        assertEquals(setOf(a, aAgain), plan.objects.keys)
    }

    @Test
    fun planWithArtifactsOffLeavesEveryPublicLinkToTheApps() {
        val link = "https://app.raft.build/artifacts/pt_a"
        val plan = planRaftMessageLinks(link, listOf("app.raft.build"), artifactsEnabled = false)
        assertEquals(emptyList(), plan.objectCards)
        assertEquals(listOf(link), plan.appLinks)
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
    fun parsesThreadPermalinkWithPercentEncodedColon() {
        val url = "https://app.slock.ai/s/botiverse/channel/$channel?thread=$channel%3A$parent&msg=$message"
        assertEquals(
            ParsedRaftPermalink(url, "botiverse", ConversationKind.Channel, ChannelId(channel), MessageId(message), MessageId(parent)),
            parseRaftPermalink(url, hosts),
        )
    }

    @Test
    fun threadOnlyUrlTargetsTheThreadParent() {
        val parsed = parseRaftPermalink("https://app.slock.ai/s/botiverse/channel/$channel?thread=$channel%3A$parent", hosts)
        assertEquals(MessageId(parent), parsed?.messageId)
        assertEquals(MessageId(parent), parsed?.threadParentMessageId)
    }

    @Test
    fun acceptsTheCurrentAndConfiguredHosts() {
        val url = "https://chat.example.com/s/botiverse/channel/$channel?msg=$message"
        assertNull(parseRaftPermalink(url, hosts))
        assertEquals("botiverse", parseRaftPermalink(url, raftPermalinkHostnames(emptyList(), "https://chat.example.com/"))?.serverSlug)
        assertEquals("botiverse", parseRaftPermalink(url, raftPermalinkHostnames(listOf("old.example.com", "chat.example.com")))?.serverSlug)
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

    @Test
    fun rejectsMessageIdsThatAreNotAUuidOrShortId() {
        val base = "https://app.slock.ai/s/botiverse/channel/$channel"
        assertNull(parseRaftPermalink("$base?msg=not-a-message", hosts))
        assertNull(parseRaftPermalink("$base?msg=这条", hosts))
        // An id that runs on into more id characters is not silently truncated.
        assertNull(parseRaftPermalink("$base?msg=${message}abc", hosts))
        assertNull(parseRaftPermalink("$base?msg=abc123456789", hosts))
    }

    // --- the quoted permalink -------------------------------------------------------------------

    private val raftUrl =
        "https://app.raft.build/s/raft/channel/1417c3d4-a7c2-47ca-b247-7e84bc5842be" +
            "?msg=e1cf9d25-db9a-4626-9406-be8b3aa8672b" +
            "&thread=1417c3d4-a7c2-47ca-b247-7e84bc5842be%3Aa6e03470-d465-4e0a-801d-945efa952684"

    @Test
    fun extractsTheThreadReplyPermalinkOfMessageE6eee1b9() {
        val extracted = extractFirstQuotedRaftPermalink("…visual aid @Oscar 来补充\n$raftUrl", hosts, "raft")
        assertNotNull(extracted)
        assertEquals(raftUrl, extracted.rawUrl)
        assertEquals(MessageId("e1cf9d25-db9a-4626-9406-be8b3aa8672b"), extracted.parsed.messageId)
        assertEquals(MessageId("a6e03470-d465-4e0a-801d-945efa952684"), extracted.parsed.threadParentMessageId)
    }

    @Test
    fun quotedPermalinkIsTheFirstOfTheCurrentServerOnly() {
        val other = "https://app.raft.build/s/other/channel/$channel?msg=$message"
        val mine = "https://app.raft.build/s/mine/channel/$channel?msg=$message"
        val second = "https://app.raft.build/s/mine/channel/$channel?msg=$parent"
        assertEquals(mine, extractFirstQuotedRaftPermalink("$other $mine $second", hosts, "mine")?.rawUrl)
        assertNull(extractFirstQuotedRaftPermalink(other, hosts, "mine"))
        assertNull(extractFirstQuotedRaftPermalink(mine, hosts, null))
    }

    @Test
    fun quotedPermalinkSkipsCodeMarkdownLinkTargetsAndKeepsTrailingPunctuationOut() {
        val url = "https://app.raft.build/s/mine/channel/$channel?msg=$message"
        assertNull(extractFirstQuotedRaftPermalink("`$url`", hosts, "mine"))
        assertNull(extractFirstQuotedRaftPermalink("```\n$url\n```", hosts, "mine"))
        assertNull(extractFirstQuotedRaftPermalink("~~~\n$url\n~~~", hosts, "mine"))
        assertNull(extractFirstQuotedRaftPermalink("[words]($url)", hosts, "mine"))
        assertEquals(url, extractFirstQuotedRaftPermalink("see $url.", hosts, "mine")?.rawUrl)
        assertEquals(url, extractFirstQuotedRaftPermalink("看 $url）", hosts, "mine")?.rawUrl)
    }

    @Test
    fun quotedPermalinkReadsRaftDeepLinksAsTheirWebLink() {
        val deep = "raft:///s/mine/channel/$channel?msg=$message"
        val extracted = extractFirstQuotedRaftPermalink("open $deep", hosts, "mine", webBaseUrl = "https://app.raft.build")
        assertEquals("https://app.raft.build/s/mine/channel/$channel?msg=$message", extracted?.rawUrl)
        assertNull(extractFirstQuotedRaftPermalink("craft://s/mine/channel/$channel?msg=$message", hosts, "mine"))
    }
}
