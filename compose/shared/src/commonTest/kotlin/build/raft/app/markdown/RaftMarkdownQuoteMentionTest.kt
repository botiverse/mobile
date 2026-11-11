package build.raft.app.markdown

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * An `@` inside a block quote is not a mention: botiverse/slock #14349, squash
 * 8b324501027a4f6050b2f8db49c259ae53dd18c9. The vectors are copied from that commit's
 * `packages/shared/src/raftRefs.test.ts` ("extractRaftMentionHandles skips block quotes") and
 * `packages/web/tests/messageQuoteMentions.test.ts` (`PARITY_ROWS`, the expected handles being the
 * Server's `extractRaftMentionHandles` at that SHA). Each asserts the mention chips the mobile
 * renderer plans are the handles the Server notifies.
 */
class RaftMarkdownQuoteMentionTest {
    /** The handles the renderer would draw as mention chips (every mention valid). */
    private fun chips(source: String): List<String> =
        source.threadInlineTagMatches()
            .filter { it.tag.kind == ThreadMarkdownTagKind.UserMention }
            .map { it.tag.label.removePrefix("@") }
            .distinct()
            .sorted()

    @Test
    fun sharedExtractVectors() {
        listOf(
            "> @botiverse 组织的 npm 发布权限\n\n好的" to emptyList<String>(),
            "> @Jasper said so\n\n@Yes agreed" to listOf("Yes"),
            "> outer\n> > @Nested inner\n\n@Yes" to listOf("Yes"),
            "> first line\n@After the quote" to listOf("After"),
            "> first @Quoted\n> second\nthen @After\n> @Lazy" to listOf("After", "Lazy"),
            "> a  \n@After" to listOf("After"),
            "> a\\\n@After" to listOf("After"),
            "> **note\n@After check**" to listOf("After"),
            "> *a\n> b*\n@After" to listOf("After"),
            "> [a\nb](<@Linked>)" to emptyList<String>(),
            "> `x\ny` @After" to listOf("After"),
            "> outer\n> > inner\n@StillQuoted" to emptyList<String>(),
            "> <@Angle> and [x](<@Linked>)\n\n@Yes" to listOf("Yes"),
            "> `@Code` in a quote\n\nthen `@Code` and @Yes" to listOf("Yes"),
            "- > @Listed quote\n- @Yes" to listOf("Yes"),
        ).forEach { (source, expected) ->
            assertEquals(expected.sorted(), chips(source), "source=$source")
        }
    }

    @Test
    fun webParityRows() {
        listOf(
            "> @Jasper said so\n\n@Mona agreed" to listOf("Mona"),
            "> outer\n> > @Jasper inner\n@Mona lazy" to emptyList<String>(),
            "> > b\n@Jasper" to emptyList<String>(),
            "> first @Jasper\n> second\nthen @Mona\n> @Jasper" to listOf("Jasper", "Mona"),
            "> a\nb @Mona\n> @Jasper" to listOf("Jasper", "Mona"),
            "- > @Jasper in a list\n- @Mona" to listOf("Mona"),
            "> `@Jasper` and <@Jasper>\n\n`@Mona` @Mona" to listOf("Mona"),
            "> a  \n@Jasper" to listOf("Jasper"),
            "> a\\\n@Jasper" to listOf("Jasper"),
            "> **note\n@Jasper check**" to listOf("Jasper"),
            "> *a\n> b*\n@Jasper" to listOf("Jasper"),
            "> `x\n> y`\n@Jasper" to listOf("Jasper"),
            "> [a\nb](<@Jasper>)" to emptyList<String>(),
            "> `x\ny` @Mona\n> @Jasper" to listOf("Jasper", "Mona"),
            "> **[a\nb](https://e.com) @Mona**" to listOf("Mona"),
            "> a&#10;@Jasper\n@Mona" to listOf("Mona"),
            "> a\n> b&#10;@Jasper\n@Mona" to listOf("Mona"),
            "> *a&#10;@Jasper\nb* @Mona" to listOf("Mona"),
        ).forEach { (source, expected) ->
            assertEquals(expected.sorted(), chips(source), "source=$source")
        }
    }

    @Test
    fun otherRefsInAQuoteStayChips() {
        val kinds = "> see #general and #12 with @Jasper".threadInlineTagMatches().map { it.tag.kind }
        assertEquals(listOf(ThreadMarkdownTagKind.Channel, ThreadMarkdownTagKind.Task), kinds)
    }

    @Test
    fun quoteRangesEndWhereTheWebDrawsTheQuoteEnd() {
        assertEquals(emptyList<RaftMarkdownQuoteRange>(), raftMarkdownQuoteRanges("no quote @Jasper"))
        // The unquoted line starts the text after the quote.
        assertEquals(listOf(RaftMarkdownQuoteRange(0, 4)), raftMarkdownQuoteRanges("> a\nb"))
        // A link the unquoted line starts inside stays whole in the quote.
        assertEquals(listOf(RaftMarkdownQuoteRange(0, 18)), raftMarkdownQuoteRanges("> [a\nb](https://e) c"))
    }
}
