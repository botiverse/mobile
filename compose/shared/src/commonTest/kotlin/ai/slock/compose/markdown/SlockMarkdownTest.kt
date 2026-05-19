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
}
