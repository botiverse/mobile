package ai.slock.compose.markdown

import ai.slock.compose.SlockColor
import ai.slock.compose.SlockType
import com.tencent.kuikly.compose.ui.text.AnnotatedString
import com.tencent.kuikly.compose.ui.text.SpanStyle
import com.tencent.kuikly.compose.ui.text.TextLinkStyles
import com.tencent.kuikly.compose.ui.text.buildAnnotatedString
import com.tencent.kuikly.compose.ui.text.font.FontFamily
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuiklybase.markdown.annotator.DefaultAnnotatorSettings
import com.tencent.kuiklybase.markdown.annotator.buildMarkdownAnnotatedString
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SlockMarkdownTest {
    @Test
    fun singleMarkdownLineBreakRendersAsVisibleNewline() {
        val rendered = renderSlockMarkdownParagraphText("first line\nsecond line")

        assertEquals("first line\nsecond line", rendered)
    }

    @Test
    fun escapedSingleLineBreakRendersAsVisibleNewlineWhenDecoded() {
        val content = "first line\\nsecond line".normalizeThreadMarkdownBody()

        assertEquals("first line\nsecond line", renderSlockMarkdownParagraphText(content))
    }

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
    fun userMentionsRenderAsClickableInlineContent() {
        val inlineContent = slockMarkdownAnnotator("Ping @Android-App-Integrator in #markdown专修 for #15").inlineContent?.invoke().orEmpty()

        assertTrue(inlineContent.containsKey("slock:5"))
        assertTrue(inlineContent.containsKey("slock:32"))
        assertTrue(inlineContent.containsKey("slock:48"))
    }

    @Test
    fun highlightedUserMentionsStillRenderAsInlineContent() {
        val inlineContent =
            slockMarkdownAnnotator(
                "Ping @artin and @Android-App-Integrator in #markdown专修",
                highlightedMentions = setOf("artin")
            ).inlineContent?.invoke().orEmpty()

        assertTrue(inlineContent.containsKey("slock:5"))
        assertTrue(inlineContent.containsKey("slock:16"))
        assertTrue(inlineContent.containsKey("slock:43"))
    }

    @Test
    fun highlightedUserMentionUsesReactYellowChipColor() {
        assertEquals(
            SlockColor.BrandYellow,
            slockMarkdownTagColor(ThreadMarkdownTagKind.UserMention, highlighted = true)
        )
        assertEquals(
            SlockColor.AccentLavender,
            slockMarkdownTagColor(ThreadMarkdownTagKind.UserMention)
        )
    }

    @Test
    fun inlineCodeKeepsSlockFontInsteadOfMono() {
        val inlineCodeStyle = slockMarkdownInlineCodeTextStyle(SlockType.Body)

        assertEquals(SlockType.Body.fontFamily, inlineCodeStyle.fontFamily)
        assertFalse(inlineCodeStyle.fontFamily == FontFamily.Monospace)
    }

    @Test
    fun markdownTypographyKeepsSlockFontsForOhosRichText() {
        val typography = slockMarkdownTypography(SlockType.Body)

        assertEquals(SlockType.Body.fontFamily, typography.text.fontFamily)
        assertEquals(SlockType.Body.fontFamily, typography.paragraph.fontFamily)
        assertEquals(SlockType.Body.fontFamily, typography.textLink.style?.fontFamily)
        assertEquals(SlockType.Caption.fontFamily, typography.code.fontFamily)
        assertFalse(typography.code.fontFamily == FontFamily.Monospace)
    }

    @Test
    fun markdownStrongSpansReceiveWeightSpecificSlockFont() {
        val annotated =
            AnnotatedString(
                "normal bold",
                spanStyles =
                    listOf(
                        AnnotatedString.Range(
                            SpanStyle(fontWeight = FontWeight.Bold),
                            start = 7,
                            end = 11
                        )
                    )
            ).withSlockMarkdownSpanFonts(SlockType.Body)

        val strongFontSpan = annotated.spanStyles.last { it.start == 7 && it.end == 11 }

        assertEquals(SlockType.ButtonBold.fontFamily, strongFontSpan.item.fontFamily)
    }

    @Test
    fun markdownCodeSpansDoNotKeepExplicitMonospaceFont() {
        val annotated =
            AnnotatedString(
                "normal code",
                spanStyles =
                    listOf(
                        AnnotatedString.Range(
                            SpanStyle(fontFamily = FontFamily.Monospace),
                            start = 7,
                            end = 11
                        )
                    )
            ).withSlockMarkdownSpanFonts(SlockType.Body)

        val codeFontSpan = annotated.spanStyles.last { it.start == 7 && it.end == 11 }

        assertEquals(SlockType.Body.fontFamily, codeFontSpan.item.fontFamily)
        assertFalse(codeFontSpan.item.fontFamily == FontFamily.Monospace)
    }

    @Test
    fun inlineTagWidthsDoNotReserveLargeTrailingPadding() {
        val mentionWidth =
            slockMarkdownInlineTagWidthSp(
                "@Android-Developer-2",
                ThreadMarkdownTagKind.UserMention
            ).value
        val selfMentionWidth =
            slockMarkdownInlineTagWidthSp(
                "@artin",
                ThreadMarkdownTagKind.UserMention,
                highlighted = true
            ).value
        val taskWidth = slockMarkdownInlineChipWidthSp("#405").value

        assertTrue(mentionWidth < 170f)
        assertTrue(selfMentionWidth < 70f)
        assertTrue(taskWidth < 50f)
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
    fun inlineReferencesParseWithoutPlatformRegexClasses() {
        val body =
            "Ping @KMP-专家 in #markdown专修:d93db904 from #general:abc123 and task #16; " +
                "ignore `#markdown专修:d93db904` plus <https://slock.ai/#general:abc123>."

        val matches = body.threadInlineTagMatches()

        assertEquals(
            listOf("@KMP-专家", "#markdown专修:d93db904", "#general:abc123", "#16"),
            matches.map { it.tag.label }
        )
        assertEquals(ThreadMarkdownTagKind.UserMention, matches[0].tag.kind)
        assertEquals(ThreadMarkdownTagKind.ChannelThread, matches[1].tag.kind)
        assertEquals("markdown专修", matches[1].tag.channelName)
        assertEquals("d93db904", matches[1].tag.threadShortId)
        assertEquals(ThreadMarkdownTagKind.ChannelThread, matches[2].tag.kind)
        assertEquals(ThreadMarkdownTagKind.Task, matches[3].tag.kind)
    }

    @Test
    fun channelThreadReferencesAreIgnoredInsideInlineCode() {
        val inlineContent = slockMarkdownAnnotator("Use `#markdown专修:7933cf78` outside #markdown专修:7933cf78").inlineContent?.invoke().orEmpty()

        assertFalse(inlineContent.containsKey("slock:5"))
        assertTrue(inlineContent.containsKey("slock:35"))
    }

    private fun renderSlockMarkdownParagraphText(content: String): String {
        val parsedTree = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(content)
        val paragraph = parsedTree.children.first { it.type == MarkdownElementTypes.PARAGRAPH }
        val annotator = slockMarkdownAnnotator(content)
        val settings =
            DefaultAnnotatorSettings(
                linkTextSpanStyle = TextLinkStyles(style = SpanStyle()),
                codeSpanStyle = SpanStyle(),
                annotator = annotator
            )
        return buildAnnotatedString {
            buildMarkdownAnnotatedString(content, paragraph, settings)
        }.text
    }
}
