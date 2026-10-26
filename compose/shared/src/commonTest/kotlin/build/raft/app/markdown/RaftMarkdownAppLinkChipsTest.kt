package build.raft.app.markdown

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import build.raft.app.RaftColor
import build.raft.app.data.AppLinkApp
import build.raft.app.data.AppLinkPromptApp
import build.raft.app.data.AppLinkResult
import build.raft.app.data.RaftAppLinkCard
import build.raft.app.data.RaftAppLinkCardElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** artin #20 lane L: bare App links become chips in the message's text (web `AppLinkChip`). */
class RaftMarkdownAppLinkChipsTest {
    private val pr = "https://stamp.build/botiverse/slock/pull/10449"

    private fun source(linkText: String = pr): AnnotatedString =
        buildAnnotatedString {
            append("See ")
            withLink(LinkAnnotation.Url(pr)) { append(linkText) }
            append(" now")
        }

    private fun AnnotatedString.visibleText(): String = text.replace("\uFFFC", "").replace("\u2060", "").replace('\u00A0', ' ')

    private fun host(result: AppLinkResult, onAsk: (AppLinkResult.Consent) -> Unit = {}) =
        RaftAppLinkChipHost(mapOf(result.url to result), onAsk)

    @Test
    fun aPreviewIsACyanChipWithTheTitleAndTheDomain() {
        val opened = mutableListOf<String>()
        val result = AppLinkResult.Card(pr, AppLinkApp("Stamp", null), "Fix the composer tray")
        val chip = source().withRaftAppLinkChipContent(host(result)) { opened += it }

        assertEquals("See Fix the composer tray stamp.build now", chip.text.visibleText())
        val link = chip.text.getLinkAnnotations(0, chip.text.length).single()
        assertEquals(pr, (link.item as LinkAnnotation.Url).url)
        (link.item as LinkAnnotation.Url).linkInteractionListener!!.onClick(link.item)
        assertEquals(listOf(pr), opened)
        val box = chip.text.raftInlineBoxRanges().single().item
        assertEquals(RaftColor.InfoSoft, box.backgroundColor)
        assertEquals(4.5.dp, box.paddingStart)
        assertEquals(4.dp, box.cornerRadius)
        // icon, icon gap, suffix gap.
        assertEquals(3, chip.inlineContent.size)
        // A chip is not a web link: no underline.
        assertTrue(chip.text.withRaftMarkdownLinkUnderlines().raftInlineUnderlineRanges().isEmpty())
    }

    @Test
    fun aPreviewWithACardBodyOpensItsCardInsteadOfTheLink() {
        val body = RaftAppLinkCard(root = "state", elements = mapOf("state" to RaftAppLinkCardElement.Badge("Merged")))
        val withBody = AppLinkResult.Card(pr, AppLinkApp("Stamp", null), "Fix the composer tray", card = body)
        val titleOnly = withBody.copy(card = null)
        val cards = mutableListOf<AppLinkResult.Card>()
        val opened = mutableListOf<String>()
        fun chipLink(result: AppLinkResult.Card, opensCards: Boolean): LinkAnnotation {
            val host = RaftAppLinkChipHost(mapOf(pr to result), onAsk = {}, onOpenCard = if (opensCards) ({ cards += it }) else null)
            assertEquals(opensCards && result.card != null, host.opensCard(result))
            val chip = source().withRaftAppLinkChipContent(host) { opened += it }
            // The chip itself is the same cyan chip either way.
            assertEquals("See Fix the composer tray stamp.build now", chip.text.visibleText())
            assertEquals(RaftColor.InfoSoft, chip.text.raftInlineBoxRanges().single().item.backgroundColor)
            return chip.text.getLinkAnnotations(0, chip.text.length).single().item
        }

        val card = chipLink(withBody, opensCards = true) as LinkAnnotation.Clickable
        assertEquals(RaftAppLinkCardTag, card.tag)
        card.linkInteractionListener!!.onClick(card)
        assertEquals(listOf(withBody), cards)
        assertTrue(opened.isEmpty())
        // No card body, or a renderer that shows no cards: the chip opens the link.
        listOf(titleOnly to true, withBody to false).forEach { (result, opensCards) ->
            val link = chipLink(result, opensCards) as LinkAnnotation.Url
            link.linkInteractionListener!!.onClick(link)
        }
        assertEquals(listOf(pr, pr), opened)
        assertEquals(listOf(withBody), cards)
    }

    @Test
    fun anUnansweredAppIsAGreyChipThatAsksInsteadOfOpening() {
        val asked = mutableListOf<AppLinkResult.Consent>()
        val result = AppLinkResult.Consent(pr, AppLinkPromptApp("client-stamp", "Stamp", null))
        val chip = source().withRaftAppLinkChipContent(host(result) { asked += it }) { error("must not open") }

        assertEquals("See stamp.build/botiverse/slock/pull/10449 now", chip.text.visibleText())
        val link = chip.text.getLinkAnnotations(0, chip.text.length).single().item as LinkAnnotation.Clickable
        link.linkInteractionListener!!.onClick(link)
        assertEquals(listOf(result), asked)
        assertEquals(RaftColor.InactiveSoft, chip.text.raftInlineBoxRanges().single().item.backgroundColor)
    }

    @Test
    fun aPendingChipIsNotPressable() {
        val chip = source().withRaftAppLinkChipContent(host(AppLinkResult.Pending(pr))) {}
        assertTrue(chip.text.getLinkAnnotations(0, chip.text.length).isEmpty())
        assertEquals(RaftColor.InactiveSoft, chip.text.raftInlineBoxRanges().single().item.backgroundColor)
    }

    @Test
    fun aLinkTheAuthorGaveWordsToStaysAsWritten() {
        val source = source(linkText = "the PR")
        val result = AppLinkResult.Card(pr, AppLinkApp("Stamp", null), "Fix")
        assertSame(source, source.withRaftAppLinkChipContent(host(result)) {}.text)
        assertSame(source, source.withRaftAppLinkChipContent(null) {}.text)
    }

    @Test
    fun longWordsAreCappedAndTheDomainKeepsItsEnd() {
        assertEquals("abc", raftAppLinkCap("abc", 5))
        assertEquals("abcd…", raftAppLinkCap("abcdefgh", 5))
        assertEquals("…ttacker.example", raftAppLinkKeepEnd("review.attacker.example", 16))
        val title = "A very long pull request title that goes on and on past the cap"
        val chip =
            source().withRaftAppLinkChipContent(host(AppLinkResult.Card(pr, AppLinkApp("Stamp", null), title))) {}
        val label = chip.text.visibleText().removePrefix("See ").removeSuffix(" stamp.build now")
        assertEquals(RaftAppLinkChipMaxChars - "stamp.build".length, label.length)
        assertTrue(label.endsWith("…"))
    }
}
