package ai.slock.compose.markdown

import ai.slock.compose.SlockColor
import ai.slock.compose.SlockType
import ai.slock.compose.slockFontFamilyForWeight
import com.tencent.kuikly.compose.ui.text.AnnotatedString
import com.tencent.kuikly.compose.ui.text.LinkAnnotation
import com.tencent.kuikly.compose.ui.text.LinkInteractionListener
import com.tencent.kuikly.compose.ui.text.SpanStyle
import com.tencent.kuikly.compose.ui.text.TextLinkStyles
import com.tencent.kuikly.compose.ui.text.buildAnnotatedString
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.text.font.FontFamily
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.style.TextDecoration
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.kuikly.core.base.Size
import com.tencent.kuiklybase.markdown.annotator.DefaultAnnotatorSettings
import com.tencent.kuiklybase.markdown.annotator.buildMarkdownAnnotatedString
import com.tencent.kuiklybase.markdown.compose.elements.updateMarkdownTableColumnWidth
import com.tencent.kuiklybase.markdown.model.MarkdownAnnotator
import com.tencent.kuiklybase.markdown.model.MarkdownImageStateImpl
import com.tencent.kuiklybase.markdown.model.markdownParserLiteralHtmlContent
import kotlinx.coroutines.runBlocking
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SlockMarkdownTest {
    private val expectedLinkBlue = Color(0xFF1D4ED8)

    @Test
    fun markdownPreparseOnlyWarmsFullRendererBodies() = runBlocking {
        val fullRendererBody = "Open https://raft.build/task36-preparse"

        assertEquals(null, "Plain text only".preparedSlockMarkdownFullRendererContentOrNull())
        assertEquals(
            "Ping @artin in #markdown专修",
            "Ping @artin in #markdown专修".preparedSlockMarkdownFullRendererContentOrNull()
        )
        assertEquals(
            fullRendererBody,
            fullRendererBody.preparedSlockMarkdownFullRendererContentOrNull()
        )

        val first =
            prepareSlockMarkdownFullRendererBodies(
                listOf("Plain text only", "Ping @artin", fullRendererBody, fullRendererBody)
            )
        assertEquals(2, first.requestedCount)
        assertEquals(2, first.parsedCount)
        assertEquals(0, first.cacheHitCount)

        val second = prepareSlockMarkdownFullRendererBodies(listOf(fullRendererBody))
        assertEquals(1, second.requestedCount)
        assertEquals(0, second.parsedCount)
        assertEquals(1, second.cacheHitCount)
    }

    @Test
    fun markdownImageStateIgnoresSameSizeUpdates() {
        val state = MarkdownImageStateImpl()
        val initialContainer = state.containerSize
        val initialIntrinsic = state.intrinsicImageSize

        state.updateContainerSize(Size(Float.NaN, Float.NaN))
        state.updateImageSize(Size(Float.NaN, Float.NaN))

        assertSame(initialContainer, state.containerSize)
        assertSame(initialIntrinsic, state.intrinsicImageSize)

        state.updateContainerSize(Size(320f, 48f))
        val container = state.containerSize
        state.updateContainerSize(Size(320f, 48f))

        state.updateImageSize(Size(120f, 80f))
        val intrinsic = state.intrinsicImageSize
        state.updateImageSize(Size(120f, 80f))

        assertSame(container, state.containerSize)
        assertSame(intrinsic, state.intrinsicImageSize)
    }

    @Test
    fun markdownTableColumnWidthIgnoresNoOpUpdates() {
        val widths = mutableMapOf<Int, Dp>()

        assertTrue(widths.updateMarkdownTableColumnWidth(index = 0, measuredWidth = 140.dp, maxWidth = 120.dp))
        assertEquals(120.dp, widths[0])

        assertFalse(widths.updateMarkdownTableColumnWidth(index = 0, measuredWidth = 140.dp, maxWidth = 120.dp))
        assertEquals(120.dp, widths[0])

        assertFalse(widths.updateMarkdownTableColumnWidth(index = 0, measuredWidth = 80.dp, maxWidth = 120.dp))
        assertEquals(120.dp, widths[0])
    }

    @Test
    fun markdownTableColumnWidthCanGrowWhenUnbounded() {
        val widths = mutableMapOf<Int, Dp>()

        assertTrue(widths.updateMarkdownTableColumnWidth(index = 1, measuredWidth = 80.dp, maxWidth = Dp.Unspecified))
        assertEquals(80.dp, widths[1])

        assertTrue(widths.updateMarkdownTableColumnWidth(index = 1, measuredWidth = 96.dp, maxWidth = Dp.Unspecified))
        assertEquals(96.dp, widths[1])

        assertFalse(widths.updateMarkdownTableColumnWidth(index = 1, measuredWidth = 90.dp, maxWidth = Dp.Unspecified))
        assertEquals(96.dp, widths[1])
    }

    @Test
    fun markdownContentNeverUsesEllipsisOverflow() {
        assertEquals(TextOverflow.Clip, slockMarkdownContentOverflow(TextOverflow.Ellipsis))
        assertEquals(TextOverflow.Clip, slockMarkdownContentOverflow(TextOverflow.Clip))
        assertEquals(TextOverflow.Visible, slockMarkdownContentOverflow(TextOverflow.Visible))
    }

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
    fun parserEscapesHtmlTagsOutsideCodeAndAutolinks() {
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
            body.markdownParserLiteralHtmlContent()
        )
    }

    @Test
    fun parserEscapesHtmlCommentsWithoutDroppingFollowingText() {
        val body =
            """
            <!-- Describe what happened here. --> activity status
            before <!-- hidden --> after
            `<!-- code -->`
            ```html
            <!-- code -->
            ```
            """.trimIndent()

        val prepared = body.markdownParserLiteralHtmlContent()

        assertEquals(
            """
            \<!-- Describe what happened here. --\> activity status
            before \<!-- hidden --\> after
            `<!-- code -->`
            ```html
            <!-- code -->
            ```
            """.trimIndent(),
            prepared
        )
        assertEquals(
            "<!-- Describe what happened here. --> activity status",
            renderSlockMarkdownParagraphText("<!-- Describe what happened here. --> activity status")
        )
    }

    @Test
    fun inlineCodeUsesTextMarkerWithoutPlaceholderChips() {
        val shortCode = "inline-code"
        val longCode = "very-long-inline-code-value-that-should-wrap-normally"
        val body = "short `$shortCode` long `$longCode`"
        val annotator = slockMarkdownAnnotator(body)

        assertTrue(shortCode.shouldRenderThreadInlineCodeAsChip())
        assertFalse(longCode.shouldRenderThreadInlineCodeAsChip())

        val inlineContent = annotator.inlineContent?.invoke().orEmpty()
        assertFalse(inlineContent.keys.any { it.startsWith("slock-code:") })

        val annotated = buildSlockMarkdownInlineTextAnnotatedString(body, SlockType.Body, annotator)
        val inlineCodeAnnotations =
            annotated.getStringAnnotations(SlockMarkdownInlineCodeAnnotationTag, 0, annotated.length)
        val inlineCodeText = inlineCodeAnnotations.map { annotated.text.substring(it.start, it.end) }

        assertEquals("short inline-code long very-long-inline-code-value-that-should-wrap-normally", annotated.text)
        assertEquals(listOf(shortCode, longCode), inlineCodeText)
        assertEquals("short ".length, inlineCodeAnnotations.first().start)
        assertEquals("short inline-code long ".length, inlineCodeAnnotations.last().start)
    }

    @Test
    fun userMentionsRenderAsClickableTags() {
        val annotated =
            buildSlockMarkdownParagraphAnnotatedString(
                "Ping @Android-App-Integrator in #markdown专修 for #15"
            )

        assertEquals(setOf("slock:5", "slock:32", "slock:48"), annotated.slockMarkdownClickableTags())
    }

    @Test
    fun highlightedUserMentionsStillRenderAsClickableTags() {
        val body = "Ping @artin and @Android-App-Integrator in #markdown专修"
        val annotator =
            slockMarkdownAnnotator(
                body,
                highlightedMentions = setOf("artin")
            )
        val annotated = buildSlockMarkdownParagraphAnnotatedString(body, annotator)

        assertEquals(setOf("slock:5", "slock:16", "slock:43"), annotated.slockMarkdownClickableTags())
    }

    @Test
    fun markdownReferenceTagsUseReactChipColors() {
        assertEquals(SlockColor.AccentPink.copy(alpha = 0.3f), slockMarkdownTagColor(ThreadMarkdownTagKind.Channel))
        assertEquals(SlockColor.AccentCyan.copy(alpha = 0.3f), slockMarkdownTagColor(ThreadMarkdownTagKind.ChannelThread))
        assertEquals(SlockColor.BrandYellow.copy(alpha = 0.4f), slockMarkdownTagColor(ThreadMarkdownTagKind.Task))
        assertEquals(SlockColor.BrandYellow, slockMarkdownTagColor(ThreadMarkdownTagKind.UserMention, highlighted = true))

        assertFalse(slockMarkdownTagColor(ThreadMarkdownTagKind.Channel) == SlockColor.BrandYellow)
        assertFalse(slockMarkdownTagColor(ThreadMarkdownTagKind.Channel) == SlockColor.AccentCyan)
        assertFalse(slockMarkdownTagColor(ThreadMarkdownTagKind.ChannelThread) == SlockColor.AccentPink)
        assertFalse(slockMarkdownTagColor(ThreadMarkdownTagKind.Task) == SlockColor.AccentLavender)
    }

    @Test
    fun task25ReferenceScenarioKeepsReactTokenColorsAndBoundaries() {
        val body =
            "Ping @artin in #raft-mobile and #raft-mobile:4752266f for #25; " +
                "ignore #a67dddad #3adea0d5 #34xxx and #missing-channel:4752266f."

        val plan =
            SlockMarkdownInlineTokenRegistry.plan(
                body,
                highlightedMentions = setOf("artin"),
                knownChannelNames = setOf("raft-mobile")
            )
        val tokens = plan.tagTokens()

        assertEquals(listOf("@artin", "#raft-mobile", "#raft-mobile:4752266f", "#25"), tokens.map { it.label })
        assertEquals(
            listOf(
                MarkdownInlineTokenKind.UserMention,
                MarkdownInlineTokenKind.Channel,
                MarkdownInlineTokenKind.ChannelThread,
                MarkdownInlineTokenKind.Task
            ),
            tokens.map { it.kind }
        )
        assertEquals(listOf(true, false, false, false), tokens.map { it.highlighted })
        assertEquals(
            listOf(
                SlockColor.BrandYellow,
                SlockColor.AccentPink.copy(alpha = 0.3f),
                SlockColor.AccentCyan.copy(alpha = 0.3f),
                SlockColor.BrandYellow.copy(alpha = 0.4f)
            ),
            tokens.map { token -> slockMarkdownTagColor(token.tag!!.kind, token.highlighted) }
        )
    }

    @Test
    fun inlineCodeKeepsSlockFontInsteadOfMono() {
        val inlineCodeStyle = slockMarkdownInlineCodeTextStyle(SlockType.Body)

        assertEquals(slockFontFamilyForWeight(FontWeight.SemiBold), inlineCodeStyle.fontFamily)
        assertEquals(FontWeight.Normal, inlineCodeStyle.fontWeight)
        assertEquals(SlockType.Body.fontSize, inlineCodeStyle.fontSize)
        assertEquals(SlockType.Body.lineHeight, inlineCodeStyle.lineHeight)
        assertFalse(inlineCodeStyle.fontFamily == FontFamily.Monospace)
    }

    @Test
    fun markdownTextStyleRaisesLineHeightForInlineChips() {
        val style = SlockType.Body.copy(fontSize = 14.sp, lineHeight = 20.sp)

        val resolvedStyle = slockMarkdownTextStyleWithInlineLineHeight(style)
        val typography = slockMarkdownTypography(style)

        assertEquals(22.sp, resolvedStyle.lineHeight)
        assertEquals(22.sp, typography.text.lineHeight)
        assertEquals(22.sp, typography.paragraph.lineHeight)
        assertEquals(22.sp, typography.inlineCode.lineHeight)
        assertEquals(SlockType.Body.fontFamily, resolvedStyle.fontFamily)
    }

    @Test
    fun markdownTextStyleKeepsRoomierLineHeight() {
        val style = SlockType.Body.copy(fontSize = 14.sp, lineHeight = 28.sp)

        val resolvedStyle = slockMarkdownTextStyleWithInlineLineHeight(style)
        val typography = slockMarkdownTypography(style)

        assertEquals(28.sp, resolvedStyle.lineHeight)
        assertEquals(28.sp, typography.text.lineHeight)
        assertEquals(28.sp, typography.inlineCode.lineHeight)
    }

    @Test
    fun plainTextFastPathOnlyAcceptsBodiesWithoutMarkdownWork() {
        assertTrue("Short plain reply.".shouldUseSlockMarkdownPlainTextFastPath())
        assertTrue("中文普通消息 123".shouldUseSlockMarkdownPlainTextFastPath())
        assertTrue("<b>literal</b>".shouldUseSlockMarkdownPlainTextFastPath())
        assertTrue("<!-- hidden --> literal".shouldUseSlockMarkdownPlainTextFastPath())

        assertFalse("Ping @artin".shouldUseSlockMarkdownPlainTextFastPath())
        assertFalse("See #KMP".shouldUseSlockMarkdownPlainTextFastPath())
        assertFalse("Use `code`".shouldUseSlockMarkdownPlainTextFastPath())
        assertFalse("Open https://raft.build".shouldUseSlockMarkdownPlainTextFastPath())
        assertFalse("first line\nsecond line".shouldUseSlockMarkdownPlainTextFastPath())
        assertFalse("**bold**".shouldUseSlockMarkdownPlainTextFastPath())
    }

    @Test
    fun markdownPerformanceLogsOnlySlowSamplesByDefault() {
        assertFalse(shouldLogSlockMarkdownPerformance(0))
        assertFalse(shouldLogSlockMarkdownPerformance(7))
        assertTrue(shouldLogSlockMarkdownPerformance(8))
    }

    @Test
    fun taggedTextUsesFullRendererPreparse() {
        assertEquals(
            "Ping @artin in #KMP",
            "Ping @artin in #KMP".preparedSlockMarkdownFullRendererContentOrNull()
        )
        assertEquals(
            "#KMP is a channel reference, not a heading",
            "#KMP is a channel reference, not a heading".preparedSlockMarkdownFullRendererContentOrNull()
        )
        assertEquals(null, "Plain text only".preparedSlockMarkdownFullRendererContentOrNull())
    }

    @Test
    fun markdownTypographyKeepsSlockFontsForOhosRichText() {
        val typography = slockMarkdownTypography(SlockType.Body)

        assertEquals(SlockType.Body.fontFamily, typography.text.fontFamily)
        assertEquals(SlockType.Body.fontFamily, typography.paragraph.fontFamily)
        assertEquals(SlockType.Body.fontFamily, typography.textLink.style?.fontFamily)
        assertEquals(expectedLinkBlue, typography.textLink.style?.color)
        assertEquals(TextDecoration.Underline, typography.textLink.style?.textDecoration)
        assertEquals(expectedLinkBlue, typography.link.color)
        assertEquals(TextDecoration.Underline, typography.link.textDecoration)
        assertEquals(SlockType.Caption.fontFamily, typography.code.fontFamily)
        assertFalse(typography.code.fontFamily == FontFamily.Monospace)
    }

    @Test
    fun markdownLinksRenderBlueUnderline() {
        val body = "Open [Raft](https://raft.build) now"
        val annotated =
            buildSlockMarkdownInlineTextAnnotatedString(
                body,
                SlockType.Body,
                slockMarkdownAnnotator(body)
            )
        val link = annotated.getLinkAnnotations(0, annotated.length).single().item as LinkAnnotation.Url
        val linkStyle = link.styles?.style

        assertEquals("Open Raft now", annotated.text)
        assertEquals("https://raft.build", link.url)
        assertEquals(expectedLinkBlue, linkStyle?.color)
        assertEquals(TextDecoration.Underline, linkStyle?.textDecoration)
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
        assertEquals(FontWeight.Normal, strongFontSpan.item.fontWeight)
    }

    @Test
    fun fullRendererKeepsCodeBoldAndClickableTagsTogether() {
        val body = "Ping @artin with `mimo` and **bold** in #markdown专修"
        var openedTag: ThreadMarkdownTag? = null
        val tokenPlan =
            SlockMarkdownInlineTokenRegistry.plan(
                body,
                highlightedMentions = setOf("artin"),
                knownChannelNames = setOf("markdown专修"),
                inlineContentScope = "message-mixed"
            )
        val annotator =
            slockMarkdownAnnotator(
                body,
                highlightedMentions = setOf("artin"),
                knownChannelNames = setOf("markdown专修"),
                inlineContentScope = "message-mixed",
                onOpenTag = { openedTag = it },
                tokenPlan = tokenPlan
            )
        val annotated = buildSlockMarkdownParagraphAnnotatedString(body, annotator)
            .withSlockMarkdownSpanFonts(SlockType.Body)

        assertEquals("Ping @artin with mimo and bold in #markdown专修", annotated.text)
        assertEquals(
            setOf("slock-message-mixed:5", "slock-message-mixed:40"),
            annotated.slockMarkdownClickableTags()
        )
        assertEquals(
            listOf("mimo"),
            annotated
                .getStringAnnotations(SlockMarkdownInlineCodeAnnotationTag, 0, annotated.length)
                .map { annotated.text.substring(it.start, it.end) }
        )
        val boldStart = annotated.text.indexOf("bold")
        val boldSpan = annotated.spanStyles.last { it.start == boldStart && it.end == boldStart + "bold".length }
        assertEquals(SlockType.ButtonBold.fontFamily, boldSpan.item.fontFamily)
        assertEquals(FontWeight.Normal, boldSpan.item.fontWeight)
        val mention = annotated.getLinkAnnotations(0, annotated.length).first {
            (it.item as? LinkAnnotation.Clickable)?.tag == "slock-message-mixed:5"
        }.item as LinkAnnotation.Clickable
        mention.linkInteractionListener?.onClick(mention)
        assertEquals("@artin", openedTag?.label)
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
        val shortMentionWidth =
            slockMarkdownInlineTagWidthSp(
                "@mention",
                ThreadMarkdownTagKind.UserMention
            ).value
        val mentionWidth =
            slockMarkdownInlineTagWidthSp(
                "@Android-Developer-2",
                ThreadMarkdownTagKind.UserMention
            ).value
        val longAgentMentionWidth =
            slockMarkdownInlineTagWidthSp(
                "@Codex-KMP-Developer",
                ThreadMarkdownTagKind.UserMention
            ).value
        val qaMentionWidth =
            slockMarkdownInlineTagWidthSp(
                "@Pi-QA-Smoke",
                ThreadMarkdownTagKind.UserMention
            ).value
        val selfMentionWidth =
            slockMarkdownInlineTagWidthSp(
                "@artin",
                ThreadMarkdownTagKind.UserMention,
                highlighted = true
            ).value
        val taskWidth = slockMarkdownInlineChipWidthSp("#405").value

        assertTrue(shortMentionWidth in 70f..80f)
        assertTrue(mentionWidth in 160f..170f)
        assertTrue(longAgentMentionWidth in 172f..182f)
        assertTrue(qaMentionWidth in 110f..120f)
        assertTrue(selfMentionWidth < 70f)
        assertTrue(taskWidth < 54f)
    }

    @Test
    fun inlineCodeWidthsStayCompactAroundShortTokens() {
        val shortTokenWidth = slockMarkdownInlineCodeWidthSp("mimo").value
        val envVarWidth = slockMarkdownInlineCodeWidthSp("MIMO_API_KEY").value
        val pathWidth = slockMarkdownInlineCodeWidthSp("~/.pi/agent/auth.json").value

        assertTrue(shortTokenWidth in 44f..48f)
        assertTrue(envVarWidth in 102f..110f)
        assertTrue(pathWidth < 145f)
    }

    @Test
    fun fullRendererTagLinksRemainButCodeUsesTextMarker() {
        val body = "Ping @artin for #405 and `mimo`"
        val annotator =
            slockMarkdownAnnotator(
                content = body,
                highlightedMentions = setOf("artin"),
                inlineCodeTextStyle = SlockType.Body.copy(fontSize = 15.sp, lineHeight = 20.sp)
            )

        assertTrue(annotator.inlineContent?.invoke().orEmpty().isEmpty())

        val annotated = buildSlockMarkdownParagraphAnnotatedString(body, annotator)
        assertEquals(setOf("slock:5", "slock:16"), annotated.slockMarkdownClickableTags())
        val inlineCodeText =
            annotated.getStringAnnotations(SlockMarkdownInlineCodeAnnotationTag, 0, annotated.length)
                .map { annotated.text.substring(it.start, it.end) }
        assertEquals(listOf("mimo"), inlineCodeText)
    }

    @Test
    fun richMarkdownFullRendererKeepsTagsWithoutInlineContentPlaceholders() {
        val body = "**Bold** text for @artin in #markdown专修 with `code`"
        val annotator =
            slockMarkdownAnnotator(
                content = body,
                inlineCodeTextStyle = SlockType.Body.copy(fontSize = 15.sp, lineHeight = 20.sp),
                knownChannelNames = setOf("markdown专修")
            )

        assertTrue(annotator.inlineContent?.invoke().orEmpty().isEmpty())

        val annotated = buildSlockMarkdownParagraphAnnotatedString(body, annotator)

        assertEquals("Bold text for @artin in #markdown专修 with code", annotated.text)
        assertEquals(setOf("slock:18", "slock:28"), annotated.slockMarkdownClickableTags())
        assertEquals(
            listOf("code"),
            annotated.getStringAnnotations(SlockMarkdownInlineCodeAnnotationTag, 0, annotated.length)
                .map { annotated.text.substring(it.start, it.end) }
        )
    }

    @Test
    fun inlineTagPlanFindsTokensForTextNodeRange() {
        val body = "Ping @artin for #405 and #markdown专修:7933cf78"
        val plan = SlockMarkdownInlineTokenRegistry.plan(body)

        assertEquals(listOf("@artin"), plan.tagTokensInRange(0, 12).map { it.label })
        assertEquals(listOf("#405"), plan.tagTokensInRange(12, 22).map { it.label })
        assertEquals(listOf("#markdown专修:7933cf78"), plan.tagTokensInRange(22, body.length).map { it.label })
        assertTrue(plan.tagTokensInRange(5, 8).isEmpty())
    }

    @Test
    fun inlineCodeWidthHelpersStillMatchOldChromeConstants() {
        val fallbackWidth = slockMarkdownInlineCodeWidthSp("mimo")
        val inlineContent =
            slockMarkdownAnnotator(
                content = "Use `mimo`",
                inlineCodeWidths = mapOf("slock-code:4" to 72.sp)
            ).inlineContent?.invoke().orEmpty()

        assertTrue(fallbackWidth.value in 42f..46f)
        assertFalse(inlineContent.containsKey("slock-code:4"))
    }

    @Test
    fun inlineCodeTokensAreNotRegisteredAsInlineContent() {
        val tokens =
            listOf(
                "mimo",
                "MIMO_API_KEY",
                "~/.pi/agent/auth.json",
                "SlockColor.BorderBlack",
                "blockNextChannels",
                "very-long-inline-code-value-that-should-wrap-normally"
            )
        val body = tokens.joinToString(separator = " ") { "`$it`" }
        val plan = SlockMarkdownInlineTokenRegistry.plan(body)
        val inlineContent =
            slockMarkdownAnnotator(
                content = body,
                inlineCodeTextStyle = SlockType.Body
            ).inlineContent?.invoke().orEmpty()

        assertTrue(plan.inlineCodeTokens().isEmpty())
        assertFalse(inlineContent.keys.any { it.startsWith("slock-code:") })
    }

    @Test
    fun channelThreadReferencesRenderAsSingleInlineChip() {
        val matches = "See #markdown专修:7933cf78 and #general:abc123 in chat".threadInlineTagMatches()
        val annotated =
            buildSlockMarkdownParagraphAnnotatedString(
                "See #markdown专修:7933cf78 and #general:abc123 in chat"
            )

        assertEquals(2, matches.size)
        assertEquals(ThreadMarkdownTagKind.ChannelThread, matches[0].tag.kind)
        assertEquals("#markdown专修:7933cf78", matches[0].tag.label)
        assertEquals("markdown专修", matches[0].tag.channelName)
        assertEquals("7933cf78", matches[0].tag.threadShortId)
        assertEquals(setOf("slock:4", "slock:29"), annotated.slockMarkdownClickableTags())
    }

    @Test
    fun fullMarkdownRendererKeepsChannelThreadClickableTags() {
        val body = "Final #markdown专修:9579b48a @artin #markdown专修 #37."
        val plan = SlockMarkdownInlineTokenRegistry.plan(body, knownChannelNames = setOf("markdown专修"))
        val annotated =
            buildSlockMarkdownParagraphAnnotatedString(
                content = body,
                annotator =
                    slockMarkdownAnnotator(
                        content = body,
                        knownChannelNames = setOf("markdown专修"),
                        tokenPlan = plan
                    )
            )

        assertEquals(
            listOf("#markdown专修:9579b48a", "@artin", "#markdown专修", "#37"),
            plan.tagTokens().map { it.label }
        )
        assertEquals(
            setOf("slock:6", "slock:27", "slock:34", "slock:46"),
            annotated.slockMarkdownClickableTags()
        )
    }

    @Test
    fun fullRendererClickableTagsCanBeScopedPerMessageRow() {
        val body = "Ping @artin in #markdown专修"
        val firstPlan =
            SlockMarkdownInlineTokenRegistry.plan(
                body,
                knownChannelNames = setOf("markdown专修"),
                inlineContentScope = "message-a"
            )
        val secondPlan =
            SlockMarkdownInlineTokenRegistry.plan(
                body,
                knownChannelNames = setOf("markdown专修"),
                inlineContentScope = "message-b"
            )
        val firstAnnotated =
            buildSlockMarkdownParagraphAnnotatedString(
                content = body,
                annotator =
                    slockMarkdownAnnotator(
                        content = body,
                        knownChannelNames = setOf("markdown专修"),
                        inlineContentScope = "message-a",
                        tokenPlan = firstPlan
                    )
            )
        val secondAnnotated =
            buildSlockMarkdownParagraphAnnotatedString(
                content = body,
                annotator =
                    slockMarkdownAnnotator(
                        content = body,
                        knownChannelNames = setOf("markdown专修"),
                        inlineContentScope = "message-b",
                        tokenPlan = secondPlan
                    )
            )

        assertEquals(
            setOf("slock-message-a:5", "slock-message-a:15"),
            firstAnnotated.slockMarkdownClickableTags()
        )
        assertEquals(
            setOf("slock-message-b:5", "slock-message-b:15"),
            secondAnnotated.slockMarkdownClickableTags()
        )
        assertFalse(firstPlan.signature == secondPlan.signature)
    }

    @Test
    fun inlineReferencesMatchTask25Boundaries() {
        val body =
            "我已补发日报到 #raft-mobile，消息是 #raft-mobile:4752266f。也已确认主 reminder " +
                "#a67dddad 存在并确实触发过；backup reminder #3adea0d5 和 #34xxx 都不是引用。"

        val matches = body.threadInlineTagMatches()
        val plan = SlockMarkdownInlineTokenRegistry.plan(body, knownChannelNames = setOf("raft-mobile"))

        assertEquals(listOf("#raft-mobile", "#raft-mobile:4752266f"), matches.map { it.tag.label })
        assertEquals(ThreadMarkdownTagKind.Channel, matches[0].tag.kind)
        assertEquals(ThreadMarkdownTagKind.ChannelThread, matches[1].tag.kind)
        assertEquals("raft-mobile", matches[1].tag.channelName)
        assertEquals("4752266f", matches[1].tag.threadShortId)
        assertEquals(listOf("#raft-mobile", "#raft-mobile:4752266f"), plan.tagTokens().map { it.label })
    }

    @Test
    fun inlineChannelReferencesRequireKnownChannelWhenProvided() {
        val body = "Known #raft-mobile thread #raft-mobile:4752266f unknown #not-channel and #not-channel:4752266f"

        val plan = SlockMarkdownInlineTokenRegistry.plan(body, knownChannelNames = setOf("raft-mobile"))

        assertEquals(listOf("#raft-mobile", "#raft-mobile:4752266f"), plan.tagTokens().map { it.label })
        assertEquals(
            listOf(MarkdownInlineTokenKind.Channel, MarkdownInlineTokenKind.ChannelThread),
            plan.tagTokens().map { it.kind }
        )
    }

    @Test
    fun emptyKnownChannelSetSuppressesAllChannelReferences() {
        val body = "Ping @artin in #raft-mobile and #raft-mobile:4752266f for #25"

        val plan = SlockMarkdownInlineTokenRegistry.plan(body, knownChannelNames = emptySet())

        assertEquals(listOf("@artin", "#25"), plan.tagTokens().map { it.label })
        assertEquals(
            listOf(MarkdownInlineTokenKind.UserMention, MarkdownInlineTokenKind.Task),
            plan.tagTokens().map { it.kind }
        )
    }

    @Test
    fun numericTaskReferencesDoNotMatchInsideShortIds() {
        assertEquals(listOf("#3", "#382", "#383"), "Tasks #3 and #382-#383".threadInlineTagMatches().map { it.tag.label })
        assertTrue("#3adea0d5".threadInlineTagMatches().isEmpty())
        assertTrue("#34xxx".threadInlineTagMatches().isEmpty())
        assertTrue("#a67dddad".threadInlineTagMatches().isEmpty())
    }

    @Test
    fun inlineTokenRegistryKeepsColonThreadReferenceAsSingleToken() {
        val plan =
            SlockMarkdownInlineTokenRegistry.plan(
                "Ping @KMP-专家 in #KMP and #KMP:a6967088"
            )

        assertEquals(
            listOf(
                MarkdownInlineTokenKind.UserMention,
                MarkdownInlineTokenKind.Channel,
                MarkdownInlineTokenKind.ChannelThread
            ),
            plan.tagTokens().map { it.kind }
        )
        assertEquals("#KMP:a6967088", plan.tagTokens()[2].label)
        assertEquals("KMP", plan.tagTokens()[2].tag?.channelName)
        assertEquals("a6967088", plan.tagTokens()[2].tag?.threadShortId)
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
        val annotated =
            buildSlockMarkdownParagraphAnnotatedString(
                "Use `#markdown专修:7933cf78` outside #markdown专修:7933cf78"
            )
        val clickableTags = annotated.slockMarkdownClickableTags()

        assertFalse(clickableTags.contains("slock:5"))
        assertTrue(clickableTags.contains("slock:35"))
    }

    @Test
    fun rawMarkdownUrlsKeepClickListener() {
        var openedUrl: String? = null
        val annotated =
            buildSlockMarkdownParagraphAnnotatedString(
                "Open https://raft.build now",
                LinkInteractionListener { link ->
                    openedUrl = (link as? LinkAnnotation.Url)?.url
                }
            )
        val link = annotated.getLinkAnnotations(0, annotated.length).single().item as LinkAnnotation.Url

        assertEquals("https://raft.build", link.url)
        link.linkInteractionListener?.onClick(link)
        assertEquals("https://raft.build", openedUrl)
    }

    @Test
    fun inlineMarkdownUrlsKeepClickListener() {
        var openedUrl: String? = null
        val annotated =
            buildSlockMarkdownParagraphAnnotatedString(
                "Open [Raft](https://raft.build) now",
                LinkInteractionListener { link ->
                    openedUrl = (link as? LinkAnnotation.Url)?.url
                }
            )
        val link = annotated.getLinkAnnotations(0, annotated.length).single().item as LinkAnnotation.Url

        assertEquals("https://raft.build", link.url)
        link.linkInteractionListener?.onClick(link)
        assertEquals("https://raft.build", openedUrl)
    }

    private fun renderSlockMarkdownParagraphText(content: String): String {
        return buildSlockMarkdownParagraphAnnotatedString(content).text
    }

    private fun AnnotatedString.slockMarkdownClickableTags(): Set<String> =
        getLinkAnnotations(0, length)
            .mapNotNull { (it.item as? LinkAnnotation.Clickable)?.tag }
            .toSet()

    private fun buildSlockMarkdownParagraphAnnotatedString(
        content: String,
        linkInteractionListener: LinkInteractionListener? = null
    ): AnnotatedString {
        return buildSlockMarkdownParagraphAnnotatedString(
            content = content,
            annotator = slockMarkdownAnnotator(content),
            linkInteractionListener = linkInteractionListener
        )
    }

    private fun buildSlockMarkdownParagraphAnnotatedString(
        content: String,
        annotator: MarkdownAnnotator,
        linkInteractionListener: LinkInteractionListener? = null
    ): AnnotatedString {
        val parserContent = content.markdownParserLiteralHtmlContent()
        val parsedTree = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(parserContent)
        val paragraph = parsedTree.children.first { it.type == MarkdownElementTypes.PARAGRAPH }
        val settings =
            DefaultAnnotatorSettings(
                linkTextSpanStyle = TextLinkStyles(style = SpanStyle()),
                codeSpanStyle = SpanStyle(),
                annotator = annotator,
                linkInteractionListener = linkInteractionListener
        )
        return buildAnnotatedString {
            buildMarkdownAnnotatedString(parserContent, paragraph, settings)
        }
    }
}
