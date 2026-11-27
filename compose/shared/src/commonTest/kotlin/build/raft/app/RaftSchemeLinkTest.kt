package build.raft.app

import build.raft.app.model.ConversationKind
import build.raft.app.thread.RaftMessageLink
import build.raft.app.thread.RaftMessageLinkInbox
import build.raft.app.thread.RaftMessageLinkIngress
import build.raft.app.thread.RaftThreadWebLinkTarget
import build.raft.app.thread.raftMarkdownUrlOpen
import build.raft.app.thread.toRaftThreadWebLinkTarget
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Hands 25012a26: Raft Desktop links (`raft:///s/{slug}/channel/{cid}?msg={mid}`, slock
 * `desktopAppLink.ts`) opened the app but stayed on the current page.
 */
class RaftSchemeLinkTest {
    private val slug = "lagoon-push-test"
    private val cid = "6f1c2b9e-1d2a-4c7b-9a51-0d3e8f7a6b21"
    private val pid = "a3b4c5d6-e7f8-4a1b-8c2d-3e4f5a6b7c8d"
    private val mid = "0b1c2d3e-4f50-4617-8293-a4b5c6d7e8f9"
    private val staging = "https://raft-app-staging.botiverse.dev"

    @AfterTest
    fun clearInbox() = RaftMessageLinkInbox.Process.clear()

    @Test
    fun convertsBothHostFormsOntoTheEnvironmentWebUrlKeepingTheQuery() {
        for (prefix in listOf("raft:///", "raft://", "RAFT:///", "raft-debug:///")) {
            assertEquals(
                "$staging/s/$slug/channel/$cid?msg=$mid&thread=$cid:$pid&x=1",
                raftSchemeLinkToWebLink("${prefix}s/$slug/channel/$cid?msg=$mid&thread=$cid:$pid&x=1", "$staging/"),
                prefix,
            )
        }
        assertEquals(
            "https://app.raft.build/s/$slug/dm/$cid",
            raftSchemeLinkToWebLink("raft:///s/$slug/dm/$cid#frag", webUrl = null),
        )
        assertEquals(
            "https://app.raft.build/s/$slug/tasks",
            raftSchemeLinkToWebLink("raft:///s/$slug/tasks", webUrl = " "),
        )
    }

    @Test
    fun rejectsAnythingNotUnderAServerSlug() {
        val rejected =
            listOf(
                "raft:///",
                "raft://",
                "raft:///servers",
                "raft:///s/",
                "raft:///s//channel/$cid",
                "raft:////s/$slug/channel/$cid",
                "raft://s.evil.com/s/$slug/channel/$cid",
                "raft://evil.com/s/$slug/channel/$cid",
                "raft:///artifacts/x",
                "raft://login/callback?code=x",
                "raft://oauth/callback?code=x",
                "raft:///s/$slug\\..\\x",
                "raft:///s/a@evil.com/channel/$cid",
                "https://app.raft.build/s/$slug/channel/$cid",
                "rafty:///s/$slug/channel/$cid",
                "javascript:///s/$slug",
                "raft:/s/$slug/channel/$cid",
            )
        for (url in rejected) assertNull(raftSchemeLinkToWebLink(url, staging), url)
    }

    @Test
    fun ingressQueuesChannelDmMessageAndThreadLinks() {
        val cases =
            listOf(
                "raft:///s/$slug/channel/$cid?msg=$mid" to
                    RaftMessageLink(slug, ConversationKind.Channel, cid, messageId = mid),
                "raft://s/$slug/channel/$cid" to RaftMessageLink(slug, ConversationKind.Channel, cid),
                "raft:///s/$slug/dm/$cid?msg=$mid" to
                    RaftMessageLink(slug, ConversationKind.DirectMessage, cid, messageId = mid),
                "raft:///s/$slug/channel/$cid?msg=$mid&thread=$cid%3A$pid" to
                    RaftMessageLink(slug, ConversationKind.Channel, cid, messageId = mid, threadParentMessageId = pid),
            )
        for (webUrl in listOf(staging, null)) {
            for ((url, expected) in cases) {
                RaftMessageLinkInbox.Process.clear()
                assertTrue(RaftMessageLinkIngress.accept(url, source = "test", configuredWebUrl = webUrl ?: ""), url)
                assertEquals(expected, RaftMessageLinkInbox.Process.pending.value?.link, url)
            }
        }
    }

    @Test
    fun tappedChatRaftLinkOpensAsItsEnvironmentWebLink() {
        val thread = raftMarkdownUrlOpen("raft:///s/$slug/dm/$cid?msg=$mid&thread=$cid:$pid", staging)
        assertEquals("$staging/s/$slug/dm/$cid?msg=$mid&thread=$cid:$pid", thread.url)
        assertEquals(
            RaftThreadWebLinkTarget(
                channelId = cid,
                messageId = mid,
                routeKind = RaftThreadSurfaceKind.DirectMessage,
                threadParentMessageId = pid,
            ),
            thread.target,
        )

        val channel = raftMarkdownUrlOpen("raft://s/$slug/channel/$cid?msg=$mid", webUrl = null)
        assertEquals("https://app.raft.build/s/$slug/channel/$cid?msg=$mid", channel.url)
        assertEquals(RaftThreadWebLinkTarget(channelId = cid, messageId = mid), channel.target)
        // The in-app target is the one the same https link opens.
        assertEquals("https://app.raft.build/s/$slug/channel/$cid?msg=$mid".toRaftThreadWebLinkTarget(), channel.target)
    }
}
