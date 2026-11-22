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
import build.raft.app.data.ArtifactHome
import build.raft.app.data.ArtifactKind
import build.raft.app.data.ArtifactSummary
import build.raft.app.data.RaftAppLinkCard
import build.raft.app.data.RaftAppLinkCardElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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
    fun aBlockCardWithoutInlineLeavesTheAuthorsLinkAndWithInlineIsAChipThatOpensTheLink() {
        val block = build.raft.app.data.AppLinkBlockCard("Fix the composer tray", build.raft.app.data.RaftAppLinkCardV2("r", mapOf("r" to build.raft.app.data.CardStack())))
        val only = AppLinkResult.Card(pr, AppLinkApp("Stamp", null), "Fix the composer tray", block = block, chip = false)
        val opened = mutableListOf<String>()
        val plain = source().withRaftAppLinkChipContent(host(only)) { opened += it }
        // Nothing replaces the link: the text is the author's, and no chip is drawn.
        assertTrue(plain.text.raftInlineBoxRanges().isEmpty())
        assertEquals("See $pr now", plain.text.text)
        assertTrue(plain.inlineContent.isEmpty())

        val both = only.copy(inline = build.raft.app.data.AppLinkInline("slock#10449"), chip = true)
        val cards = mutableListOf<AppLinkResult.Card>()
        val chipHost = RaftAppLinkChipHost(mapOf(pr to both), onAsk = {}, onOpenCard = { cards += it })
        // The block card is not the chip's: no hover card, a tap opens the link.
        assertEquals(false, chipHost.opensCard(both))
        val chip = source().withRaftAppLinkChipContent(chipHost) { opened += it }
        assertEquals("See slock#10449 stamp.build now", chip.text.visibleText())
        val link = chip.text.getLinkAnnotations(0, chip.text.length).single().item as LinkAnnotation.Url
        link.linkInteractionListener!!.onClick(link)
        assertEquals(listOf(pr), opened)
        assertTrue(cards.isEmpty())
    }

    @Test
    fun anUnansweredAppIsAGreyChipThatAsksInsteadOfOpening() {
        val asked = mutableListOf<AppLinkResult.Consent>()
        val result = AppLinkResult.Consent(pr, AppLinkPromptApp("client-stamp", "Stamp", null))
        val chip = source().withRaftAppLinkChipContent(host(result) { asked += it }) { error("must not open") }

        assertEquals("See stamp.build/botiverse/slock/pull/10449 now", chip.text.visibleText())
        val range = chip.text.getLinkAnnotations(0, chip.text.length).single()
        val link = range.item as LinkAnnotation.Clickable
        assertEquals(RaftAppLinkConsentTag, link.tag)
        link.linkInteractionListener!!.onClick(link)
        assertEquals(listOf(result), asked)
        assertEquals(RaftColor.InactiveSoft, chip.text.raftInlineBoxRanges().single().item.backgroundColor)
        assertEquals(
            listOf(pr),
            chip.text.getStringAnnotations(RaftMarkdownLinkUrlTag, range.start, range.end).map { it.item },
        )
        assertTrue(chip.text.getStringAnnotations(RaftAppLinkHoverTag, 0, chip.text.length).isEmpty())
    }

    @Test
    fun longPressOnConsentCardAndArtifactChipsResolvesUrlOnBodyAndPaintedFlanks() {
        val artifactId = "pt_0b6e0f7e-1c1a-4c4e-9a65-0f5e2b1d7a11"
        val artifactUrl = "https://artifacts.raft.build/$artifactId"
        val cardUrl = "https://stamp.build/botiverse/slock/pull/10449"
        val consentUrl = "https://github.com/botiverse/mobile/pull/5295"
        val summary =
            ArtifactSummary(
                id = artifactId,
                title = "Q3 report",
                description = null,
                kind = ArtifactKind.Html,
                version = 4,
                pinned = false,
                latestVersion = 4,
                home = ArtifactHome.Conversation("proj-mobile", dm = false),
            )
        val body = RaftAppLinkCard(root = "state", elements = mapOf("state" to RaftAppLinkCardElement.Badge("Merged")))
        val chipHost =
            RaftAppLinkChipHost(
                results =
                    mapOf(
                        cardUrl to AppLinkResult.Card(cardUrl, AppLinkApp("Stamp", null), "Fix hover", card = body),
                        consentUrl to AppLinkResult.Consent(consentUrl, AppLinkPromptApp("client-gh", "GitHub", null)),
                    ),
                onAsk = {},
                onOpenCard = {},
                artifacts = mapOf(artifactId to summary),
                artifactHosts = listOf("app.raft.build"),
                onOpenArtifact = {},
            )
        val rendered =
            buildAnnotatedString {
                append("See ")
                withLink(LinkAnnotation.Url(consentUrl)) { append(consentUrl) }
                append(" ")
                withLink(LinkAnnotation.Url(cardUrl)) { append(cardUrl) }
                append(" ")
                withLink(LinkAnnotation.Url(artifactUrl)) { append(artifactUrl) }
                append(" done")
            }
                .withRaftAppLinkChipContent(chipHost) {}
                .text
                .withRaftInlineChipGapLinks()
                .withRaftMarkdownBodyClick {}

        // Plain body text returns null (body click is never a link).
        assertNull(rendered.raftMarkdownPressedLinkAt(0))
        assertNull(rendered.raftMarkdownPressedLinkAt(rendered.length - 1))

        // Hover card spans must stay one per card chip (never duplicated onto painted flanks).
        assertEquals(listOf(cardUrl), rendered.raftMarkdownAppLinkSpans().map { it.url })

        val extents = rendered.raftInlineChipExtents()
        assertEquals(3, extents.size)
        listOf(consentUrl, cardUrl, artifactUrl).forEachIndexed { index, expectedUrl ->
            val extent = extents[index]
            assertEquals("\uFFFC\u2060", rendered.text.substring(extent.first, extent.first + 2), expectedUrl)
            assertEquals("\u2060\uFFFC", rendered.text.substring(extent.last - 1, extent.last + 1), expectedUrl)
            // Leading painted flank (\uFFFC and \u2060), chip body, and trailing painted flank (\u2060 and \uFFFC).
            for (offset in listOf(extent.first, extent.first + 1, (extent.first + extent.last) / 2, extent.last - 1, extent.last)) {
                assertEquals(
                    RaftMarkdownPressedLink(expectedUrl),
                    rendered.raftMarkdownPressedLinkAt(offset),
                    "url=$expectedUrl offset=$offset",
                )
            }
        }

        // An adjacent non-URL clickable chip starting with '\u2060' (e.g. a Skia-spaced task chip)
        // must never borrow a neighbouring labelled Artifact link's URL.
        val adjacent =
            buildAnnotatedString {
                pushStringAnnotation(RaftMarkdownLinkUrlTag, artifactUrl)
                withLink(LinkAnnotation.Clickable(RaftArtifactLabelledLinkTag, linkInteractionListener = null)) {
                    append("report")
                }
                pop()
                withLink(LinkAnnotation.Clickable("task-243", linkInteractionListener = null)) {
                    append("\u2060#243\u2060")
                }
            }
        assertEquals(RaftMarkdownPressedLink(artifactUrl), adjacent.raftMarkdownPressedLinkAt(0))
        assertEquals(RaftMarkdownPressedLink(url = null), adjacent.raftMarkdownPressedLinkAt(6))
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
