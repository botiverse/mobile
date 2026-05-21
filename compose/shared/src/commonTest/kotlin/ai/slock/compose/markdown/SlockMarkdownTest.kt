package ai.slock.compose.markdown

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SlockMarkdownTest {
    @Test
    fun escapedLineBreaksBecomeMarkdownBlocks() {
        assertEquals("hello\n- #thread", "hello\\n- #thread".normalizeThreadMarkdownBody())
    }

    @Test
    fun htmlTagsAreEscapedOutsideCodeAndAutolinks() {
        val body =
            """
            <https://app.slock.ai>
            `<div>`
            ```html
            <section>code</section>
            ```
            <b>text</b>
            """.trimIndent()

        assertEquals(
            """
            <https://app.slock.ai>
            `<div>`
            ```html
            <section>code</section>
            ```
            \<b\>text\</b\>
            """.trimIndent(),
            body.escapeThreadMarkdownHtmlTagsForThread()
        )
    }

    @Test
    fun longInlineCodeUsesWrappingMarkdownSpanInsteadOfInlineChip() {
        val shortCode = "inline-code"
        val longCode = "very-long-inline-code-value-that-should-wrap-normally"

        assertTrue(shortCode.shouldRenderThreadInlineCodeAsChip())
        assertFalse(longCode.shouldRenderThreadInlineCodeAsChip())

        val inlineContent = slockMarkdownAnnotator("short `$shortCode` long `$longCode`").inlineContent?.invoke().orEmpty()

        assertTrue(inlineContent.containsKey("slock-code:6"))
        assertFalse(inlineContent.containsKey("slock-code:25"))
    }

    @Test
    fun userMentionsRenderAsTextStyleInsteadOfInlineChip() {
        val inlineContent = slockMarkdownAnnotator("Ping @Android-App-Integrator in #markdown专修 for #15").inlineContent?.invoke().orEmpty()

        assertFalse(inlineContent.containsKey("slock:5"))
        assertTrue(inlineContent.containsKey("slock:32"))
        assertTrue(inlineContent.containsKey("slock:48"))
    }

    @Test
    fun channelThreadReferencesRenderAsSingleInlineChip() {
        val matches = "See #markdown专修:7933cf78 and #general:abc123 in chat".threadInlineTagMatches()

        assertEquals(2, matches.size)
        assertEquals(ThreadMarkdownTagKind.ChannelThread, matches[0].tag.kind)
        assertEquals("#markdown专修:7933cf78", matches[0].tag.label)
        assertEquals("markdown专修", matches[0].tag.channelName)
        assertEquals("7933cf78", matches[0].tag.threadShortId)

        val inlineContent = slockMarkdownAnnotator("See #markdown专修:7933cf78 and #general:abc123 in chat").inlineContent?.invoke().orEmpty()
        assertTrue(inlineContent.containsKey("slock:4"))
        assertTrue(inlineContent.containsKey("slock:29"))
    }

    @Test
    fun channelThreadReferencesAreIgnoredInsideInlineCode() {
        val inlineContent = slockMarkdownAnnotator("Use `#markdown专修:7933cf78` outside #markdown专修:7933cf78").inlineContent?.invoke().orEmpty()

        assertFalse(inlineContent.containsKey("slock:5"))
        assertTrue(inlineContent.containsKey("slock:35"))
    }
}
