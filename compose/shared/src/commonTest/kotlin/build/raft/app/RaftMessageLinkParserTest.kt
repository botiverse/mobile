package build.raft.app

import build.raft.app.model.ConversationKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Route format of #proj-mobile #24 (Jasper 10-24): the external message link parser. */
class RaftMessageLinkParserTest {
    private val slug = "lagoon-push-test"
    private val cid = "6f1c2b9e-1d2a-4c7b-9a51-0d3e8f7a6b21"
    private val pid = "a3b4c5d6-e7f8-4a1b-8c2d-3e4f5a6b7c8d"
    private val mid = "0b1c2d3e-4f50-4617-8293-a4b5c6d7e8f9"

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
                "/s/$slug/dm/$cid" to RaftMessageLink(slug, ConversationKind.DirectMessage, cid),
                "/s/$slug/dm/$cid?msg=$mid" to RaftMessageLink(slug, ConversationKind.DirectMessage, cid, messageId = mid),
                "/s/$slug/dm/$cid?thread=$cid:$pid&msg=$mid" to
                    RaftMessageLink(slug, ConversationKind.DirectMessage, cid, messageId = mid, threadParentMessageId = pid),
            )
        for (host in listOf("app.raft.build", "open.raft.build", "APP.RAFT.BUILD")) {
            for ((pathAndQuery, expected) in cases) {
                assertEquals(expected, parseRaftMessageLink("https://$host$pathAndQuery"), "$host$pathAndQuery")
            }
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
    fun appMissingAndUnknownQueryKeysAreIgnored() {
        val expected = RaftMessageLink(slug, ConversationKind.Channel, cid, messageId = mid, threadParentMessageId = pid)
        assertEquals(expected, parseRaftMessageLink("https://app.raft.build/s/$slug/channel/$cid?msg=$mid&thread=$cid:$pid&app=missing"))
        assertEquals(expected, parseRaftMessageLink("https://app.raft.build/s/$slug/channel/$cid?app=missing&msg=$mid&thread=$cid:$pid&utm_source=wechat#x"))
        assertEquals(
            RaftMessageLink(slug, ConversationKind.Channel, cid),
            parseRaftMessageLink("https://app.raft.build/s/$slug/channel/$cid?app=missing")
        )
    }

    @Test
    fun percentEncodedThreadValueIsDecoded() {
        val expected = RaftMessageLink(slug, ConversationKind.Channel, cid, messageId = mid, threadParentMessageId = pid)
        assertEquals(expected, parseRaftMessageLink("https://app.raft.build/s/$slug/channel/$cid?msg=$mid&thread=$cid%3A$pid"))
        assertEquals(expected, parseRaftMessageLink("https://open.raft.build/s/$slug/channel/$cid?msg=$mid&thread=$cid%3a$pid"))
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
                "https://app.raft.build/s/$slug/channel/$cid?thread=$cid:$pid:$mid",
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

    @Test
    fun developerEnvironmentWebHostIsAcceptedOnlyWhenConfigured() {
        val staging = "https://raft-app-staging.botiverse.dev/s/$slug/channel/$cid?msg=$mid"
        assertNull(parseRaftMessageLink(staging, configuredWebUrl = "https://app.raft.build"))
        assertNull(parseRaftMessageLink(staging, configuredWebUrl = null))
        assertEquals(
            RaftMessageLink(slug, ConversationKind.Channel, cid, messageId = mid),
            parseRaftMessageLink(staging, configuredWebUrl = "https://raft-app-staging.botiverse.dev")
        )
        // Exact origin: scheme and port must match the configured web URL.
        assertNull(parseRaftMessageLink("http://raft-app-staging.botiverse.dev/s/$slug/channel/$cid", "https://raft-app-staging.botiverse.dev"))
        assertEquals(
            RaftMessageLink(slug, ConversationKind.DirectMessage, cid),
            parseRaftMessageLink("http://localhost:5173/s/$slug/dm/$cid", configuredWebUrl = "http://localhost:5173/")
        )
        assertNull(parseRaftMessageLink("http://localhost:3000/s/$slug/dm/$cid", configuredWebUrl = "http://localhost:5173/"))
        // Production hosts stay accepted under any configured environment.
        assertEquals(
            RaftMessageLink(slug, ConversationKind.Channel, cid),
            parseRaftMessageLink("https://app.raft.build/s/$slug/channel/$cid", configuredWebUrl = "https://raft-app-staging.botiverse.dev")
        )
    }
}
