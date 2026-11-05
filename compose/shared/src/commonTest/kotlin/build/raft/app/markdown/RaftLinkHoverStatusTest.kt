package build.raft.app.markdown

import build.raft.app.links.ParsedRaftPermalink
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    private fun label(url: String, webUrl: String? = null) = raftLinkHoverLabel(url, webUrl, names, terms)

    @Test
    fun externalLinkShowsItsFullUrlAsExternal() {
        val url = "https://example.com/docs/a?b=1#c"
        assertEquals(RaftLinkHoverLabel(url, external = true), label(url))
        assertEquals(RaftLinkHoverLabel("mailto:a@b.c", external = true), label(" mailto:a@b.c "))
        assertNull(label("  "))
    }

    @Test
    fun raftMessageLinksReadAsTheirConversation() {
        val cases =
            listOf(
                "https://app.raft.build/s/acme/channel/c1?msg=ae7be26c-daa7-42ca-8480-68d5ac90eaca" to "#proj-mobile · message",
                "https://app.raft.build/s/acme/channel/c1?msg=ae7be26c-daa7-42ca-8480-68d5ac90eaca&thread=c1%3Aced6bfe1-f650-46b3-8859-4d37eb7850d2" to "#proj-mobile · thread",
                "https://app.raft.build/s/acme/dm/d1?msg=ae7be26c-daa7-42ca-8480-68d5ac90eaca" to "@artin · message",
                // A Raft Desktop link reads as the web link it stands for.
                "raft:///s/acme/channel/c1?msg=ae7be26c-daa7-42ca-8480-68d5ac90eaca" to "#proj-mobile · message",
            )
        cases.forEach { (url, expected) -> assertEquals(RaftLinkHoverLabel(expected, external = false), label(url), url) }
    }

    @Test
    fun raftLinksWithoutAKnownNameShowTheirWebLinkInApp() {
        // Message link to a conversation the page does not know: its web link, still in-app.
        val unknown = "https://app.raft.build/s/acme/channel/c9?msg=ae7be26c-daa7-42ca-8480-68d5ac90eaca"
        assertEquals(RaftLinkHoverLabel(unknown, external = false), label(unknown))
        // A Raft Desktop link shows its environment's web link and stays with Raft.
        assertEquals(RaftLinkHoverLabel("$staging/s/acme/tasks", external = false), label("raft:///s/acme/tasks", staging))
        assertEquals(RaftLinkHoverLabel("$staging/s/acme/channel/c9?msg=ae7be26c-daa7-42ca-8480-68d5ac90eaca", external = false), label("raft:///s/acme/channel/c9?msg=ae7be26c-daa7-42ca-8480-68d5ac90eaca", staging))
    }

    @Test
    fun middleEllipsisKeepsBothEnds() {
        assertEquals("short", raftMiddleEllipsis("short", 10))
        assertEquals("abcd…xyz", raftMiddleEllipsis("abcdefghijklmnopqrstuvwxyz", 8))
        assertEquals("a…z", raftMiddleEllipsis("abcdefghijklmnopqrstuvwxyz", 1))
    }

    @Test
    fun fitMiddleEllipsisTakesTheLongestCutThatFits() {
        val url = "https://example.com/a/very/long/path/to/some/document.pdf"
        assertEquals(url, raftFitMiddleEllipsis(url) { true })
        val fitted = raftFitMiddleEllipsis(url) { it.length <= 30 }
        assertEquals(30, fitted.length)
        assertTrue(fitted.startsWith("https://example") && fitted.endsWith("document.pdf"), fitted)
        // Nothing fits: the shortest cut, never empty.
        assertEquals(12, raftFitMiddleEllipsis(url) { false }.length)
    }

    @Test
    fun hoverableLinksAreUrlLinksAndAppLinkCards() {
        val text =
            buildAnnotatedString {
                append("see ")
                withLink(LinkAnnotation.Url("https://example.com")) { append("example") }
                append(" and ")
                pushStringAnnotation(RaftAppLinkHoverTag, "https://app.example/item/1")
                withLink(LinkAnnotation.Clickable(RaftAppLinkCardTag, linkInteractionListener = null)) { append("card") }
                pop()
                append(" ")
                withLink(LinkAnnotation.Clickable("mention-1", linkInteractionListener = null)) { append("@artin") }
            }
        assertEquals(
            listOf(
                RaftHoverableLink(4, 11, "https://example.com"),
                RaftHoverableLink(16, 20, "https://app.example/item/1"),
            ),
            text.raftHoverableLinks(),
        )
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
