package build.raft.app.markdown

import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import build.raft.app.toRaftMessagePermalinkTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** web PermalinkReferenceChip: a permalink whose quoted message cannot be read is a plain "Unavailable linked message" span. */
class RaftUnavailablePermalinkChipTest {
    private val url =
        "https://app.raft.build/s/mobile/channel/c0c0c0c0-0000-4000-8000-000000000001?msg=a0a0a0a0-0000-4000-8000-000000000001"
    private val config = RaftMarkdownRenderConfig(renderMessagePermalinksAsTag = true)
    private val text = buildAnnotatedString { withLink(LinkAnnotation.Url(url)) { append(url) } }

    @Test
    fun anUnreadableQuoteIsALabelledChipThatOpensNothing() {
        val target = assertNotNull(url.toRaftMessagePermalinkTarget())
        val chip =
            text.withRaftMessagePermalinkRenderConfig(
                config = config,
                unavailable = target,
                unavailableLabel = "Unavailable linked message",
            )
        assertTrue("Unavailable linked message" in chip.text.text, chip.text.text)
        assertTrue(chip.text.getLinkAnnotations(0, chip.text.length).isEmpty(), "no link to open")
        assertTrue("msg" !in chip.text.text.removePrefix("Unavailable linked message"), "no kind note: ${chip.text.text}")
    }

    @Test
    fun aReadableQuoteKeepsItsLink() {
        val chip = text.withRaftMessagePermalinkRenderConfig(config = config)
        assertEquals(1, chip.text.getLinkAnnotations(0, chip.text.length).size)
        assertTrue("linked message" in chip.text.text, chip.text.text)
    }
}
