package build.raft.app.links

import build.raft.app.data.AppLinkApp
import build.raft.app.data.AppLinkBlockCard
import build.raft.app.data.AppLinkInline
import build.raft.app.data.AppLinkResult
import build.raft.app.data.CardStack
import build.raft.app.data.RaftAppLinkCardV2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Where a message draws its App link block cards (raft-design #1010 "Block cards"). */
class RaftMessageBlockCardsTest {
    private fun url(n: Int) = "https://stamp.example.test/pr/$n"

    private fun block(n: Int, inline: Boolean = false) =
        AppLinkResult.Card(
            url = url(n),
            app = AppLinkApp("Stamp", null),
            title = "PR $n",
            inline = if (inline) AppLinkInline("PR $n") else null,
            block = AppLinkBlockCard("PR $n", RaftAppLinkCardV2("root", mapOf("root" to CardStack()))),
            chip = inline,
        )

    private fun results(vararg n: Int, inline: Boolean = false) = n.associate { url(it) to block(it, inline) as AppLinkResult }

    private fun plan(content: String, results: Map<String, AppLinkResult>): List<Pair<String, List<Int>>> =
        planRaftBlockCards(content, results).map { (start, cards) -> content.substring(start).lineSequence().first() to cards.map { it.url.substringAfterLast('/').toInt() } }

    @Test
    fun theCardFollowsTheParagraphOfItsLinkWhetherTheLinkIsAloneOrInASentence() {
        val content = "See ${url(1)} for the fix.\n\n${url(2)}\n\nNo links here."
        assertEquals(
            listOf("See ${url(1)} for the fix." to listOf(1), url(2) to listOf(2)),
            plan(content, results(1, 2)),
        )
    }

    @Test
    fun severalLinksInOneParagraphFollowItInLinkOrder() {
        val content = "${url(2)} then ${url(1)} and ${url(3)}"
        assertEquals(listOf(content to listOf(2, 1, 3)), plan(content, results(1, 2, 3)))
    }

    @Test
    fun aLinkInAListItemGivesItsCardToTheItemsParagraph() {
        val content = "- first ${url(1)}\n- second\n- third ${url(3)}"
        assertEquals(listOf("first ${url(1)}" to listOf(1), "third ${url(3)}" to listOf(3)), plan(content, results(1, 3)))
    }

    @Test
    fun aTableCellAHeadingAndTheAuthorsQuoteGetNoCard() {
        val content =
            "| a | b |\n| --- | --- |\n| ${url(1)} | x |\n\n# Heading ${url(2)}\n\n> quoted ${url(3)}\n\n> - in a list ${url(4)}\n\nSetext ${url(5)}\n===\n"
        assertTrue(planRaftBlockCards(content, results(1, 2, 3, 4, 5)).isEmpty())
    }

    @Test
    fun aLinkThatIsNotBareGetsNoCard() {
        val content = "[the fix](${url(1)}) and `${url(2)}`"
        assertTrue(planRaftBlockCards(content, results(1, 2)).isEmpty())
    }

    @Test
    fun theSameUrlIsDrawnOnceAtItsFirstOccurrenceAndOnlyThatOneCounts() {
        val content = "${url(1)}\n\n${url(1)}\n\n${url(2)}\n\n${url(3)}\n\n${url(4)}"
        // 1 once, then 2 and 3: the repeated 1 does not count toward the three; 4 is the fourth.
        assertEquals(listOf(1, 2, 3), planRaftBlockCards(content, results(1, 2, 3, 4)).values.flatten().map { it.url.substringAfterLast('/').toInt() })
    }

    @Test
    fun atMostThreeInAMessageInMessageOrder() {
        val content = (1..5).joinToString("\n\n") { url(it) }
        assertEquals(listOf(1, 2, 3), planRaftBlockCards(content, results(1, 2, 3, 4, 5)).values.flatten().map { it.url.substringAfterLast('/').toInt() })
    }

    @Test
    fun aFirstOccurrenceInATableDoesNotUseUpTheUrlOrTheLimit() {
        val content = "| a |\n| --- |\n| ${url(1)} |\n\n${url(1)}"
        assertEquals(1, planRaftBlockCards(content, results(1)).values.flatten().size)
    }

    @Test
    fun onlyAnswersWithABlockCardGetOne() {
        val hover = AppLinkResult.Card(url(2), AppLinkApp("Stamp", null), "PR 2")
        val content = "${url(1)}\n\n${url(2)}\n\n${url(3)}"
        assertEquals(
            listOf(url(1)),
            planRaftBlockCards(content, results(1) + mapOf(url(2) to hover as AppLinkResult)).values.flatten().map { it.url },
        )
        assertTrue(planRaftBlockCards(content, emptyMap()).isEmpty())
    }

    @Test
    fun aConsentOrPendingLinkGetsNoCard() {
        val content = url(1)
        assertTrue(planRaftBlockCards(content, mapOf(url(1) to AppLinkResult.Pending(url(1)))).isEmpty())
    }
}
