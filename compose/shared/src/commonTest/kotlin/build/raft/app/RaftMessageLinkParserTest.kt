package build.raft.app

import build.raft.app.model.ConversationKind
import build.raft.app.thread.RaftMessageLink
import build.raft.app.thread.buildRaftMessageLink
import build.raft.app.thread.parseRaftMessageLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Route format of #proj-mobile #24 (Jasper 10-24): the external message link parser. */
class RaftMessageLinkParserTest {
    private val slug = "lagoon-push-test"
    private val cid = "6f1c2b9e-1d2a-4c7b-9a51-0d3e8f7a6b21"
    private val pid = "a3b4c5d6-e7f8-4a1b-8c2d-3e4f5a6b7c8d"
    private val mid = "0b1c2d3e-4f50-4617-8293-a4b5c6d7e8f9"
    private val tid = "b0b0b0b0-1111-4222-8333-444455556666"

    @Test
    fun acceptsEveryRouteFormOnBothProductionHosts() {
        val cases =
            listOf(
                "/s/$slug/channel/$cid" to RaftMessageLink(slug, ConversationKind.Channel, cid),
                "/s/$slug/channel/$cid?msg=$mid" to RaftMessageLink(slug, ConversationKind.Channel, cid, messageId = mid),
                "/s/$slug/channel/$cid?msg=$mid&thread=$cid:$pid" to
                    RaftMessageLink(slug, ConversationKind.Channel, cid, messageId = mid, threadParentMessageId = pid),
                "/s/$slug/channel/$cid?thread=$cid:$pid" to
                    RaftMessageLink(slug, ConversationKind.Channel, cid, threadParentMessageId = pid),
                "/s/$slug/channel/$cid?task=$cid%3Atask%3A$tid" to
                    RaftMessageLink(slug, ConversationKind.Channel, cid, taskId = tid),
                "/s/$slug/channel/$cid?msg=$mid&task=$cid%3Atask%3A$tid" to
                    RaftMessageLink(slug, ConversationKind.Channel, cid, messageId = mid, taskId = tid),
                "/s/$slug/dm/$cid" to RaftMessageLink(slug, ConversationKind.DirectMessage, cid),
                "/s/$slug/dm/$cid?msg=$mid" to RaftMessageLink(slug, ConversationKind.DirectMessage, cid, messageId = mid),
                "/s/$slug/dm/$cid?thread=$cid:$pid&msg=$mid" to
                    RaftMessageLink(slug, ConversationKind.DirectMessage, cid, messageId = mid, threadParentMessageId = pid),
                "/s/$slug/dm/$cid?task=$cid:task:$tid" to
                    RaftMessageLink(slug, ConversationKind.DirectMessage, cid, taskId = tid),
                "/s/$slug/dm/$cid?msg=$mid&task=$cid%3Atask%3A$tid" to
                    RaftMessageLink(slug, ConversationKind.DirectMessage, cid, messageId = mid, taskId = tid),
            )
        for (host in listOf("app.raft.build", "open.raft.build", "APP.RAFT.BUILD")) {
            for ((pathAndQuery, expected) in cases) {
                assertEquals(expected, parseRaftMessageLink("https://$host$pathAndQuery"), "$host$pathAndQuery")
            }
        }
    }

    @Test
    fun taskConversationLinkRoundTripsAndRejectsNonTaskConversationParams() {
        val channelTask = RaftMessageLink(slug, ConversationKind.Channel, cid, messageId = mid, taskId = tid)
        val built = buildRaftMessageLink(channelTask, webUrl = null)
        assertEquals("https://app.raft.build/s/$slug/channel/$cid?msg=$mid&task=$cid%3Atask%3A$tid", built)
        assertEquals(channelTask, parseRaftMessageLink(built!!))

        val dmTaskNoMsg = RaftMessageLink(slug, ConversationKind.DirectMessage, cid, taskId = tid)
        val builtDm = buildRaftMessageLink(dmTaskNoMsg, webUrl = null)
        assertEquals("https://app.raft.build/s/$slug/dm/$cid?task=$cid%3Atask%3A$tid", builtDm)
        assertEquals(dmTaskNoMsg, parseRaftMessageLink(builtDm!!))

        val other = "11111111-2222-4333-8444-555555555555"
        for (rejected in listOf(
            "https://app.raft.build/s/$slug/channel/$cid?task=1",
            "https://app.raft.build/s/$slug/channel/$cid?task=1&thread=$cid:$pid",
            "https://app.raft.build/s/$slug/channel/$cid?task=$cid:$mid",
            "https://app.raft.build/s/$slug/channel/$cid?task=$other:task:$tid",
            "https://app.raft.build/s/$slug/channel/$cid?task=$cid:task:",
            "https://app.raft.build/s/$slug/channel/$cid?task=$cid:task:a:b",
            "https://app.raft.build/s/$slug/channel/$cid?thread=$cid:$pid&task=$cid:task:$tid",
        )) {
            assertNull(parseRaftMessageLink(rejected), rejected)
        }
    }

    @Test
    fun focusIsTheReplyThenTheThreadParent() {
        assertEquals(mid, parseRaftMessageLink("https://app.raft.build/s/$slug/channel/$cid?msg=$mid&thread=$cid:$pid")?.focusMessageId)
        assertEquals(pid, parseRaftMessageLink("https://app.raft.build/s/$slug/channel/$cid?thread=$cid:$pid")?.focusMessageId)
        assertNull(parseRaftMessageLink("https://app.raft.build/s/$slug/channel/$cid")?.focusMessageId)
    }

    @Test
    fun threadNamingAnotherChannelRejectsTheWholeLink() {
        val other = "11111111-2222-4333-8444-555555555555"
        assertNull(parseRaftMessageLink("https://app.raft.build/s/$slug/channel/$cid?msg=$mid&thread=$other:$pid"))
        assertNull(parseRaftMessageLink("https://open.raft.build/s/$slug/dm/$cid?thread=$other:$pid"))
    }

    @Test
    fun aLinkGluedToTextKeepsOnlyTheLeadingId() {
        // The web's grammar (raft-shared parseRaftPermalink): a UUID may be followed by prose.
        val expected = RaftMessageLink(slug, ConversationKind.Channel, cid, messageId = mid, threadParentMessageId = pid)
        assertEquals(expected, parseRaftMessageLink("https://app.raft.build/s/$slug/channel/$cid?msg=${mid}这条&thread=$cid:${pid}:$mid"))
        // Ids that are not hex are not a message.
        assertNull(parseRaftMessageLink("https://app.raft.build/s/$slug/channel/channel-1"))
        assertNull(parseRaftMessageLink("https://app.raft.build/s/$slug/channel/$cid?msg=message-1"))
    }

    @Test
    fun otherPathsHostsAndMalformedValuesAreNotClaimed() {
        val rejected =
            listOf(
                "https://app.raft.build/",
                "https://app.raft.build/download",
                "https://app.raft.build/s/$slug",
                "https://app.raft.build/s/$slug/channel",
                "https://app.raft.build/s/$slug/channel/",
                "https://app.raft.build/s/$slug/channel/$cid/",
                "https://app.raft.build/s/$slug/channel/$cid/extra",
                "https://app.raft.build/s/$slug/tasks/$cid",
                "https://app.raft.build/s/$slug/x/channel/$cid",
                "https://app.raft.build/s//channel/$cid",
                "https://app.raft.build/x/$slug/channel/$cid",
                "https://app.raft.build/s/$slug/channel/$cid?msg=",
                "https://app.raft.build/s/$slug/channel/$cid?msg=a%20b",
                "https://app.raft.build/s/$slug/channel/$cid?msg=%zz",
                "https://app.raft.build/s/$slug/channel/$cid?thread=$cid",
                "https://app.raft.build/s/$slug/channel/$cid?thread=$cid:",
                "http://app.raft.build/s/$slug/channel/$cid",
                "https://app.raft.build:8443/s/$slug/channel/$cid",
                "https://user@app.raft.build/s/$slug/channel/$cid",
                "https://app.slock.ai/s/$slug/channel/$cid",
                "https://evil.example/s/$slug/channel/$cid",
                "https://app.raft.build.evil.example/s/$slug/channel/$cid",
                "raft://s/$slug/channel/$cid",
                "not a url",
            )
        for (url in rejected) {
            assertNull(parseRaftMessageLink(url), url)
        }
    }
}
