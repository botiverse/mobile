package build.raft.app.markdown

import build.raft.app.data.RaftClientConfigAppHosts
import build.raft.app.links.ParsedRaftPermalink
import build.raft.app.thread.raftMarkdownUrlOpen
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The link status: what a hovered link says, how a long one is cut, and who may clear it. */
class RaftLinkHoverStatusTest {
    private val terms = RaftLinkHoverTerms(message = "message", thread = "thread")
    private val staging = "https://staging.raft.build"
    private val names: (ParsedRaftPermalink) -> String? = { target ->
        when (target.channelId.value) {
            "c1" -> "proj-mobile"
            "d1" -> "artin"
            else -> null
        }
    }

    @AfterTest
    fun resetAppHosts() {
        RaftClientConfigAppHosts.publish(emptyList())
    }

    private fun label(
        url: String,
        webUrl: String? = null,
        appHosts: Set<String> = emptySet(),
        forceInApp: Boolean = false,
    ) = raftLinkHoverLabel(url, webUrl, names, terms, appHosts, forceInApp)

    @Test
    fun externalLinkShowsItsFullUrlAsExternal() {
        val url = "https://example.com/docs/a?b=1#c"
        assertEquals(RaftLinkHoverLabel(url, external = true), label(url))
        assertEquals(RaftLinkHoverLabel("mailto:a@b.c", external = true), label(" mailto:a@b.c "))
        assertNull(label("  "))
    }

    @Test
    fun raftMessageLinksReadAsTheirConversation() {
        val msgId = "ae7be26c-daa7-42ca-8480-68d5ac90eaca"
        val parentId = "ced6bfe1-f650-46b3-8859-4d37eb7850d2"
        val cases =
            listOf(
                "https://app.raft.build/s/acme/channel/c1?msg=$msgId" to "#proj-mobile · message",
                "https://app.raft.build/s/acme/channel/c1?msg=$msgId&thread=c1%3A$parentId" to "#proj-mobile · thread",
                "https://app.raft.build/s/acme/dm/d1?msg=$msgId" to "@artin · message",
                "https://open.raft.build/s/acme/channel/c1?msg=$msgId" to "#proj-mobile · message",
                // A Raft Desktop link reads as the web link it stands for.
                "raft:///s/acme/channel/c1?msg=$msgId" to "#proj-mobile · message",
            )
        cases.forEach { (url, expected) -> assertEquals(RaftLinkHoverLabel(expected, external = false), label(url), url) }

        // Staging webUrl and custom appHosts message permalinks resolve both open.target and hover label.
        val stagingMessage = "$staging/s/acme/channel/c1?msg=$msgId"
        val stagingThread = "$staging/s/acme/channel/c1?msg=$msgId&thread=c1%3A$parentId"
        val stagingDm = "$staging/s/acme/dm/d1?msg=$msgId"
        assertNotNull(raftMarkdownUrlOpen(stagingMessage, webUrl = staging, appHosts = emptySet()).target)
        assertEquals(RaftLinkHoverLabel("#proj-mobile · message", external = false), label(stagingMessage, webUrl = staging))
        assertEquals(RaftLinkHoverLabel("#proj-mobile · thread", external = false), label(stagingThread, webUrl = staging))
        assertEquals(RaftLinkHoverLabel("@artin · message", external = false), label(stagingDm, webUrl = staging))
        // Outside its configured staging environment, a staging permalink leaves the app.
        assertEquals(RaftLinkHoverLabel(stagingMessage, external = true), label(stagingMessage, webUrl = null))

        val customHostMessage = "https://raft.example/s/acme/channel/c1?msg=$msgId"
        assertNotNull(raftMarkdownUrlOpen(customHostMessage, webUrl = null, appHosts = setOf("raft.example")).target)
        assertEquals(
            RaftLinkHoverLabel("#proj-mobile · message", external = false),
            label(customHostMessage, appHosts = setOf("raft.example")),
        )
    }

    @Test
    fun middleEllipsisKeepsBothEnds() {
        assertEquals("short", raftMiddleEllipsis("short", 10))
        assertEquals("abcd…xyz", raftMiddleEllipsis("abcdefghijklmnopqrstuvwxyz", 8))
        assertEquals("a…z", raftMiddleEllipsis("abcdefghijklmnopqrstuvwxyz", 1))
    }

    @Test
    fun onlyTheTextShowingTheStatusClearsIt() {
        val state = RaftLinkHoverStatusState()
        val first = Any()
        val second = Any()
        val a = RaftLinkHoverLabel("https://a.example", external = true)
        val b = RaftLinkHoverLabel("#proj-mobile · message", external = false)
        state.show(first, a)
        state.show(second, b)
        // The first text's late exit leaves the second text's status up.
        state.hide(first)
        assertEquals(b, state.label)
        state.hide(second)
        assertNull(state.label)
    }
}
