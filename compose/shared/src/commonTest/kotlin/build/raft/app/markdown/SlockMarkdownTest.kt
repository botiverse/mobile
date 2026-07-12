package build.raft.app.markdown

import build.raft.app.SlockColor
import build.raft.app.SlockType
import build.raft.app.slockFontFamilyForWeight
import build.raft.app.slockInlineCodeCharWrapBridgeThreshold
import build.raft.app.slockInlineCodeUsesEdgePaddingBridge
import build.raft.app.slockMonoFamilyForWeight
import build.raft.app.withSlockFontWeight
import com.tencent.kuikly.compose.ui.text.AnnotatedString
import com.tencent.kuikly.compose.ui.text.LinkAnnotation
import com.tencent.kuikly.compose.ui.text.LinkInteractionListener
import com.tencent.kuikly.compose.ui.text.SpanStyle
import com.tencent.kuikly.compose.ui.text.TextLinkStyles
import com.tencent.kuikly.compose.ui.text.buildAnnotatedString
import com.tencent.kuikly.compose.ui.text.withLink
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
import com.tencent.kuiklybase.markdown.model.MarkdownParserContent
import kotlinx.coroutines.runBlocking
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SlockMarkdownTest {
    private val expectedLinkBlue = Color(0xFF1D4ED8)
    private val slockInlineCodeAnnotationTag = SLOCK_RICHTEXT_INLINE_CODE_ANNOTATION_TAG
    private val slockInlineCodeTrailingMarginAnnotationTag = SLOCK_RICHTEXT_INLINE_CODE_TRAILING_MARGIN_ANNOTATION_TAG
    private val inlineCodePadding = SLOCK_RICHTEXT_INLINE_CODE_EDGE_PADDING.toString()

    @Test
    fun messagePermalinkRenderConfigDefaultsToOrdinaryLink() {
        val url = "https://app.raft.build/s/mobile/channel/channel-1?msg=message-1"
        val source = buildAnnotatedString {
            withLink(LinkAnnotation.Url(url)) { append(url) }
        }

        val resolved = source.withSlockMessagePermalinkRenderConfig()

        assertEquals(url, resolved.text.text)
        assertTrue(resolved.inlineContent.isEmpty())
        assertEquals(
            url,
            (resolved.text.getLinkAnnotations(0, resolved.text.length).single().item as LinkAnnotation.Url).url,
        )
    }

    @Test
    fun messagePermalinkRenderConfigCanEnableTagProjection() {
        val url = "https://app.raft.build/s/mobile/channel/channel-1?msg=message-1"
        val source = buildAnnotatedString {
            withLink(LinkAnnotation.Url(url)) { append(url) }
        }

        val resolved =
            source.withSlockMessagePermalinkRenderConfig(
                config = SlockMarkdownRenderConfig(renderMessagePermalinksAsTag = true),
                resolveLabel = { "proj" },
            )

        assertEquals("#proj msg", resolved.text.text.replace("\uFFFC", ""))
        assertEquals(3, resolved.inlineContent.size)
    }

    @Test
    fun raftMessagePermalinkUsesGenericInlineBoxChip() {
        val url = "https://app.raft.build/s/mobile/channel/channel-1?msg=message-1"
        val source =
            buildAnnotatedString {
                append("See ")
                withLink(LinkAnnotation.Url(url)) { append(url) }
                append(" now")
            }

        val resolved =
            source.withSlockMessagePermalinkChips { target ->
                "proj-server-management".takeIf { target.channelId.value == "channel-1" }
            }
        val link = resolved.getLinkAnnotations(0, resolved.length).single().item as LinkAnnotation.Url

        assertEquals(
            "See #proj-server-management msg now",
            resolved.text.replace("\uFFFC", ""),
        )
        assertEquals(3, resolved.slockMarkdownInlineContentIds().size)
        assertEquals(url, link.url)
        assertEquals(Color(0xFFFCEFBD), link.styles?.style?.inlineBoxStyle?.backgroundColor)
        assertEquals(SlockColor.BorderBlack, link.styles?.style?.inlineBoxStyle?.borderColor)
        assertEquals(1.dp, link.styles?.style?.inlineBoxStyle?.borderWidth)
        assertEquals(null, link.styles?.style?.textDecoration)
    }

    @Test
    fun externalAndContentlessRaftLinksRemainOrdinaryLinks() {
        val external = "https://example.com/page"
        val contentless = "https://app.raft.build/s/mobile/channel/channel-1"
        val source =
            buildAnnotatedString {
                withLink(LinkAnnotation.Url(external)) { append("external") }
                append(" ")
                withLink(LinkAnnotation.Url(contentless)) { append("channel") }
            }

        assertSame(source, source.withSlockMessagePermalinkChips())
    }

    @Test
    fun unresolvedRaftMessagePermalinkUsesNeutralFallback() {
        val url = "https://app.raft.build/s/another/channel/private-id?msg=message-1"
        val source =
            buildAnnotatedString {
                withLink(LinkAnnotation.Url(url)) { append(url) }
            }

        assertEquals(
            "linked message msg",
            source.withSlockMessagePermalinkChips().text.replace("\uFFFC", ""),
        )
    }

    @Test
    fun permalinkChipUsesNativeTextRunsInsteadOfEstimatedWholeChipWidth() {
        val url = "https://app.raft.build/s/mobile/channel/channel-1?msg=message-1"
        val source = buildAnnotatedString {
            withLink(LinkAnnotation.Url(url)) { append(url) }
        }

        val resolved = source.withSlockMessagePermalinkChips { "proj-server-management" }
        val labelStyle =
            resolved.spanStyles.first {
                it.item.fontSize == 14.sp && it.item.fontWeight == FontWeight.Bold
            }
        val suffixStyle = resolved.spanStyles.first { it.item.fontSize == 10.sp }

        assertEquals("#proj-server-management", resolved.text.substring(labelStyle.start, labelStyle.end))
        assertEquals("msg", resolved.text.substring(suffixStyle.start, suffixStyle.end))
        assertEquals(3, resolved.slockMarkdownInlineContentIds().size)
    }

    @Test
    fun threadPermalinkUsesTypedThreadSuffix() {
        val url =
            "https://app.raft.build/s/mobile/channel/channel-1" +
                "?thread=channel-1%3Aparent-1&msg=message-1"
        val source =
            buildAnnotatedString {
                withLink(LinkAnnotation.Url(url)) { append(url) }
            }

        val resolved = source.withSlockMessagePermalinkChips { "proj-server-management" }

        assertEquals("#proj-server-management thread", resolved.text.replace("\uFFFC", ""))
    }

    @Test
    fun markdownAutolinkPreservesEncodedThreadPermalinkTarget() {
        val url =
            "https://app.raft.build/s/mobile/channel/channel-1" +
                "?thread=channel-1%3Aparent-1&msg=message-1"
        val source = buildSlockMarkdownParagraphAnnotatedString("Thread reply: $url")

        val resolved = source.withSlockMessagePermalinkChips { "proj-server-management" }

        assertEquals(
            "Thread reply: #proj-server-management thread",
            resolved.text.replace("\uFFFC", ""),
        )
    }

    @Test
    fun raftPermalinkInsideInlineCodeRemainsLiteral() {
        val url = "https://app.raft.build/s/mobile/channel/channel-1?msg=message-1"
        val externalUrl = "https://example.com/visual"
        val source =
            buildAnnotatedString {
                val start = length
                withLink(LinkAnnotation.Url(url)) { append(url) }
                addStringAnnotation(
                    tag = SLOCK_RICHTEXT_INLINE_CODE_ANNOTATION_TAG,
                    annotation = "true",
                    start = start,
                    end = length
                )
                append(" ")
                withLink(LinkAnnotation.Url(externalUrl)) { append("external") }
            }

        val resolved = source.withSlockMessagePermalinkChips()

        assertEquals("$url external", resolved.text.replace("\uFFFC", ""))
        val remainingLink =
            resolved.getLinkAnnotations(0, resolved.length).single().item as LinkAnnotation.Url
        assertEquals(externalUrl, remainingLink.url)
        assertTrue(
            resolved.getStringAnnotations(
                SLOCK_RICHTEXT_INLINE_CODE_ANNOTATION_TAG,
                0,
                resolved.length
            ).isNotEmpty()
        )
    }

    @Test
    fun markdownPreparseOnlyWarmsFullRendererBodies() = runBlocking {
        val fullRendererBody = "Open https://raft.build/task36-preparse"

        assertEquals(
            "Plain text only",
            "Plain text only".preparedSlockMarkdownFullRendererContentOrNull()?.sourceContent
        )
        assertEquals(
            "Ping @artin in #markdown专修",
            "Ping @artin in #markdown专修".preparedSlockMarkdownFullRendererContentOrNull()?.sourceContent
        )
        assertEquals(
            fullRendererBody,
            fullRendererBody.preparedSlockMarkdownFullRendererContentOrNull()?.sourceContent
        )

        val first =
            prepareSlockMarkdownFullRendererBodies(
                listOf("Plain text only", "Ping @artin", fullRendererBody, fullRendererBody)
            )
        assertEquals(3, first.requestedCount)
        assertEquals(3, first.parsedCount)
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
    fun markdownListItemParagraphsKeepVisibleText() {
        val body =
            """
            1. 方案正确：server 的 `GET /messages/context/:id` 明确支持 `channelId` query，且对 `type === "thread"` 的本地 channel 会走 projection。
            2. KMP 改动干净：`getMessageContext` 加可选 `channelId`，老调用不受影响。
            3. 测试到位：新增的 joint local thread projection 测试断言了 scoped 请求和 reply body 真正加载。
            4. 一个观察（非 blocker）：reply-thread 初始加载现在对所有带 focusedMessageId 的 reply thread 都先走 focused-context。
            5. main 上 app compile 被挡确实是别的 agent 未提交的 androidApp 改动。
            """.trimIndent()

        val rendered = renderSlockMarkdownListItemTexts(body)

        assertEquals(5, rendered.size)
        assertTrue(rendered[0].contains("方案正确"))
        assertTrue(rendered[0].contains("GET /messages/context/:id"))
        assertTrue(rendered[0].contains("channelId"))
        assertTrue(rendered[1].contains("KMP 改动干净"))
        assertTrue(rendered[2].contains("reply body 真正加载"))
        assertTrue(rendered[3].contains("focusedMessageId"))
        assertTrue(rendered[4].contains("androidApp 改动"))
    }

    @Test
    fun markdownListItemParagraphsKeepClickableTags() {
        val body =
            """
            - 请看 #markdown专修
            - 继续看 #markdown专修:7933cf78 和 #50
            """.trimIndent()
        val isTagValid = validThreadMarkdownTags(knownChannels = setOf("markdown专修"))
        val rendered =
            renderSlockMarkdownListItemAnnotatedStrings(
                content = body,
                annotator =
                    slockMarkdownAnnotator(
                        content = body,
                        isTagValid = isTagValid
                    )
            )

        assertEquals(2, rendered.size)
        assertEquals("请看 #markdown专修", rendered[0].text)
        assertEquals(
            setOf("slock:5"),
            rendered[0].slockMarkdownClickableTags()
        )
        assertEquals("继续看 #markdown专修:7933cf78 和 #50", rendered[1].text)
        assertEquals(
            setOf("slock:23", "slock:46"),
            rendered[1].slockMarkdownClickableTags()
        )
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
            MarkdownParserContent.from(body).parserContent
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

        val prepared = MarkdownParserContent.from(body).parserContent

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
    fun inlineCodeStaysInMarkdownRendererInsteadOfSlockPlaceholders() {
        val shortCode = "inline-code"
        val longCode = "very-long-inline-code-value-that-should-wrap-normally"
        val body = "short `$shortCode` long `$longCode`"
        val plan = SlockMarkdownInlineTokenRegistry.plan(body)
        val annotator = slockMarkdownAnnotator(body)

        assertEquals(2, plan.inlineCodeMatchCount())
        assertTrue(annotator.inlineContent?.invoke().orEmpty().isEmpty())

        val annotated =
            buildSlockMarkdownParagraphAnnotatedString(
                content = body,
                annotator = annotator
            )

        assertTrue(annotated.text.contains(shortCode))
        assertTrue(annotated.text.contains(longCode))
        assertTrue(annotated.slockMarkdownInlineContentIds().isEmpty())
        assertEquals(
            listOf(
                inlineCodeSegment(shortCode),
                inlineCodeSegment(longCode)
            ),
            annotated.slockInlineCodeMarkerTextSegments()
        )
    }

    @Test
    fun inlineCodeUsesNonBreakingEdgePaddingForCommandRepro() {
        val command = "go test ./..."
        val body = "Run `$command` before review"
        val annotated =
            buildSlockMarkdownParagraphAnnotatedString(
                content = body,
                annotator = slockMarkdownAnnotator(body)
            )

        assertEquals("Run ${inlineCodeSegment(command)} before review", annotated.text)
        assertEquals(
            listOf(inlineCodeSegment(command)),
            annotated.slockInlineCodeMarkerTextSegments()
        )
        assertEquals(
            listOf(" "),
            annotated.slockInlineCodeTrailingMarginSegments()
        )
    }

    @Test
    fun userMentionsRenderAsClickableTags() {
        val body = "Ping @Android-App-Integrator in #markdown专修 for #15"
        val annotated =
            buildSlockMarkdownParagraphAnnotatedString(
                content = body,
                annotator =
                    slockMarkdownAnnotator(
                        body,
                        isTagValid =
                            validThreadMarkdownTags(
                                knownChannels = setOf("markdown专修"),
                                knownMentions = setOf("Android-App-Integrator")
                            )
                    )
            )

        assertEquals(setOf("slock:5", "slock:32", "slock:48"), annotated.slockMarkdownClickableTags())
    }

    @Test
    fun unknownUserMentionsAndChannelsDoNotRenderAsClickableTags() {
        val body = "Ping @not-a-member in #not-a-channel for #15"
        val annotated = buildSlockMarkdownParagraphAnnotatedString(body)

        assertEquals("Ping @not-a-member in #not-a-channel for #15", annotated.text)
        assertEquals(setOf("slock:41"), annotated.slockMarkdownClickableTags())
    }

    @Test
    fun highlightedUserMentionsStillRenderAsClickableTags() {
        val body = "Ping @artin and @Android-App-Integrator in #markdown专修"
        val annotator =
            slockMarkdownAnnotator(
                body,
                highlightedMentions = setOf("artin"),
                isTagValid =
                    validThreadMarkdownTags(
                        knownChannels = setOf("markdown专修"),
                        knownMentions = setOf("artin", "Android-App-Integrator")
                    )
            )
        val annotated = buildSlockMarkdownParagraphAnnotatedString(body, annotator)

        assertEquals(setOf("slock:5", "slock:16", "slock:43"), annotated.slockMarkdownClickableTags())
    }

    @Test
    fun visualFixtureTagCoverageUsesValidatedTags() {
        val body =
            "Tag chip coverage: mention @artin, channel #markdown专修, " +
                "thread #markdown专修:692d9e88, tasks task #42 and task #43."
        val validator =
            validThreadMarkdownTags(
                knownChannels = setOf("markdown专修"),
                knownMentions = setOf("artin")
            )
        val plan =
            SlockMarkdownInlineTokenRegistry.plan(
                body,
                highlightedMentions = setOf("artin"),
                isTagValid = validator
            )
        val annotator =
            slockMarkdownAnnotator(
                body,
                highlightedMentions = setOf("artin"),
                isTagValid = validator,
                tokenPlan = plan
            )
        val annotated = buildSlockMarkdownParagraphAnnotatedString(body, annotator)

        assertEquals(
            listOf("@artin", "#markdown专修", "#markdown专修:692d9e88", "#42", "#43"),
            plan.tagTokens().map { it.label }
        )
        assertEquals(listOf(true, false, false, false, false), plan.tagTokens().map { it.highlighted })
        val expectedTokenIds = plan.tagTokens().mapTo(mutableSetOf()) { it.id }
        assertEquals(expectedTokenIds, annotated.slockMarkdownClickableTags())
        assertEquals(
            listOf(
                SlockColor.BrandYellow,
                SlockColor.AccentPink.copy(alpha = 0.3f),
                SlockColor.AccentCyan.copy(alpha = 0.3f),
                SlockColor.BrandYellow.copy(alpha = 0.4f),
                SlockColor.BrandYellow.copy(alpha = 0.4f)
            ),
            plan.tagTokens().map { token ->
                annotated.slockMarkdownClickableTagInlineBoxBackground(token.id)
            }
        )
        assertTrue(annotated.slockMarkdownInlineContentIds().isEmpty())
    }

    @Test
    fun markdownReferenceTagsUseReactChipColors() {
        assertEquals(SlockColor.AccentPink.copy(alpha = 0.3f), slockMarkdownTagColor(ThreadMarkdownTagKind.Channel))
        assertEquals(SlockColor.AccentCyan.copy(alpha = 0.3f), slockMarkdownTagColor(ThreadMarkdownTagKind.ChannelThread))
        assertEquals(SlockColor.BrandYellow.copy(alpha = 0.4f), slockMarkdownTagColor(ThreadMarkdownTagKind.Task))
        assertEquals(SlockColor.BrandYellow, slockMarkdownTagColor(ThreadMarkdownTagKind.UserMention, highlighted = true))
        assertEquals("channel", SlockRichTextChromeKind.Channel.styleToken.id)
        assertEquals(SlockRichTextColorToken.AccentPink30, SlockRichTextChromeKind.Channel.styleToken.chipFill)
        assertEquals(SlockRichTextColorToken.AccentCyan30, SlockRichTextChromeKind.Thread.styleToken.chipFill)
        assertEquals(SlockRichTextColorToken.BrandYellow40, SlockRichTextChromeKind.Task.styleToken.chipFill)
        assertEquals(SLOCK_RICHTEXT_INLINE_CHIP_HORIZONTAL_CHROME_SP, SlockRichTextChromeKind.Channel.styleToken.horizontalChromeSp)

        assertFalse(slockMarkdownTagColor(ThreadMarkdownTagKind.Channel) == SlockColor.BrandYellow)
        assertFalse(slockMarkdownTagColor(ThreadMarkdownTagKind.Channel) == SlockColor.AccentCyan)
        assertFalse(slockMarkdownTagColor(ThreadMarkdownTagKind.ChannelThread) == SlockColor.AccentPink)
        assertFalse(slockMarkdownTagColor(ThreadMarkdownTagKind.Task) == SlockColor.AccentLavender)
    }

    @Test
    fun referenceTagsUseUnderlineAndSelfMentionUsesSoftSignalChip() {
        // React (MentionLink.tsx): task refs, #channels and non-self @mentions all render
        // as the bold-black underline; only the self-mention gets the soft-signal chip.
        val taskStyle = slockMarkdownTagSpanStyle(ThreadMarkdownTagKind.Task)
        val channelStyle = slockMarkdownTagSpanStyle(ThreadMarkdownTagKind.Channel)
        val threadStyle = slockMarkdownTagSpanStyle(ThreadMarkdownTagKind.ChannelThread)
        val selfMentionStyle = slockMarkdownTagSpanStyle(ThreadMarkdownTagKind.UserMention, highlighted = true)
        val ordinaryMentionStyle = slockMarkdownTagSpanStyle(ThreadMarkdownTagKind.UserMention, highlighted = false)

        assertEquals(14.sp, SlockMarkdownTagFontSize)
        assertEquals(Color.Unspecified, taskStyle.background)
        assertEquals(TextDecoration.Underline, taskStyle.textDecoration)
        assertEquals(SlockColor.BorderBlack.copy(alpha = 0.3f), taskStyle.textDecorationColor)
        assertEquals(SlockColor.TextBlack, taskStyle.color)
        assertEquals(SlockMarkdownTagFontSize, taskStyle.fontSize)

        assertEquals(Color.Unspecified, channelStyle.background)
        assertEquals(TextDecoration.Underline, channelStyle.textDecoration)
        assertEquals(SlockColor.BorderBlack.copy(alpha = 0.3f), channelStyle.textDecorationColor)
        assertEquals(SlockMarkdownTagFontSize, channelStyle.fontSize)

        assertEquals(Color.Unspecified, threadStyle.background)
        assertEquals(TextDecoration.Underline, threadStyle.textDecoration)

        assertEquals(SlockColor.SoftSignal, selfMentionStyle.background)
        assertEquals(SlockColor.BrandYellow, selfMentionStyle.background)
        assertEquals(SlockColor.BrandYellow, selfMentionStyle.inlineBoxStyle?.backgroundColor)
        assertEquals(SlockColor.BorderBlack, selfMentionStyle.inlineBoxStyle?.borderColor)
        assertEquals(1.dp, selfMentionStyle.inlineBoxStyle?.borderWidth)
        assertEquals(4.dp, selfMentionStyle.inlineBoxStyle?.paddingStart)
        assertEquals(4.dp, selfMentionStyle.inlineBoxStyle?.paddingEnd)
        assertEquals(2.dp, selfMentionStyle.inlineBoxStyle?.paddingTop)
        assertEquals(2.dp, selfMentionStyle.inlineBoxStyle?.paddingBottom)
        assertEquals(2.dp, selfMentionStyle.inlineBoxStyle?.marginStart)
        assertEquals(2.dp, selfMentionStyle.inlineBoxStyle?.marginEnd)
        assertEquals(null, selfMentionStyle.textDecoration)
        assertEquals(SlockMarkdownTagFontSize, selfMentionStyle.fontSize)
        assertEquals(TextDecoration.Underline, ordinaryMentionStyle.textDecoration)
        assertEquals(SlockColor.BorderBlack.copy(alpha = 0.3f), ordinaryMentionStyle.textDecorationColor)
        assertEquals(2.sp, ordinaryMentionStyle.textDecorationThickness)
        assertEquals(2.sp, ordinaryMentionStyle.textDecorationOffset)
        assertEquals(Color.Unspecified, ordinaryMentionStyle.background)
        assertEquals(null, ordinaryMentionStyle.inlineBoxStyle)
        assertEquals(SlockMarkdownTagFontSize, ordinaryMentionStyle.fontSize)

        val activeMentionStyle = slockMarkdownTagSpanStyle(ThreadMarkdownTagKind.UserMention, active = true)
        assertEquals(SlockColor.BrandYellow, activeMentionStyle.background)
        assertEquals(null, activeMentionStyle.textDecoration)
        assertEquals(Color.Unspecified, activeMentionStyle.textDecorationColor)
        assertEquals(SlockRichTextTextDecorationToken.Underline, SlockRichTextChromeKind.OrdinaryMention.styleToken.textDecoration)
        assertEquals(2f, SlockRichTextChromeKind.OrdinaryMention.styleToken.textDecorationThicknessSp)
        assertEquals(2f, SlockRichTextChromeKind.OrdinaryMention.styleToken.textDecorationOffsetSp)
    }

    @Test
    fun activeMarkdownReferenceTagOverridesDefaultChromeImmediately() {
        val body = "Ping @artin in #markdown专修 and task #42"
        val validator =
            validThreadMarkdownTags(
                knownChannels = setOf("markdown专修"),
                knownMentions = setOf("artin")
            )
        val plan =
            SlockMarkdownInlineTokenRegistry.plan(
                body,
                highlightedMentions = setOf("artin"),
                isTagValid = validator
            )
        val channelToken = plan.tagTokens().first { it.tag?.kind == ThreadMarkdownTagKind.Channel }
        val taskToken = plan.tagTokens().first { it.tag?.kind == ThreadMarkdownTagKind.Task }
        val annotated =
            buildSlockMarkdownParagraphAnnotatedString(
                content = body,
                annotator =
                    slockMarkdownAnnotator(
                        body,
                        highlightedMentions = setOf("artin"),
                        isTagValid = validator,
                        activeTagKey = threadMarkdownTagActivationKey(channelToken.tag!!),
                        tokenPlan = plan
                    )
            )

        assertEquals(SlockColor.BrandYellow, annotated.slockMarkdownClickableTagBackground(channelToken.id))
        assertEquals(SlockColor.BrandYellow, annotated.slockMarkdownClickableTagInlineBoxBackground(channelToken.id))
        // The generic inline box owns the task chip fill; LinkAnnotation.background stays unspecified.
        assertEquals(
            Color.Unspecified,
            annotated.slockMarkdownClickableTagBackground(taskToken.id)
        )
        assertEquals(
            SlockColor.BrandYellow.copy(alpha = 0.4f),
            annotated.slockMarkdownClickableTagInlineBoxBackground(taskToken.id)
        )
        assertEquals(
            threadMarkdownTagActivationKey(channelToken.tag!!),
            threadMarkdownTagActivationKey(
                ThreadMarkdownTag(
                    label = " #MARKDOWN专修 ",
                    kind = ThreadMarkdownTagKind.Channel,
                    channelName = " MARKDOWN专修 "
                )
            )
        )
    }

    @Test
    fun markdownReferenceTagsDeclareGenericInlineBoxStylesSeparatelyFromClicks() {
        val body = "Ping @artin and @Android-App-Integrator in #markdown专修 #markdown专修:692d9e88 for #42"
        val validator =
            validThreadMarkdownTags(
                knownChannels = setOf("markdown专修"),
                knownMentions = setOf("artin", "Android-App-Integrator")
            )
        val annotated =
            buildSlockMarkdownParagraphAnnotatedString(
                content = body,
                annotator =
                    slockMarkdownAnnotator(
                        body,
                        highlightedMentions = setOf("artin"),
                        isTagValid = validator
                    )
            )

        assertEquals(
            setOf("slock:5", "slock:16", "slock:43", "slock:55", "slock:80"),
            annotated.slockMarkdownClickableTags()
        )
        assertEquals(
            listOf(
                SlockColor.BrandYellow,
                null,
                SlockColor.AccentPink.copy(alpha = 0.3f),
                SlockColor.AccentCyan.copy(alpha = 0.3f),
                SlockColor.BrandYellow.copy(alpha = 0.4f)
            ),
            listOf("slock:5", "slock:16", "slock:43", "slock:55", "slock:80").map { tag ->
                annotated.slockMarkdownClickableTagInlineBoxBackground(tag)
            }
        )
        assertTrue(annotated.slockMarkdownInlineContentIds().isEmpty())
    }

    @Test
    fun richTextDocumentDrivesRendererChromeAndActions() {
        val body = "Ping @artin in #visual-testing:0211621f for #438 and `#not-ref`"
        val document =
            buildSlockRichTextDocument(
                body,
                SlockRichTextContext(
                    knownChannels = setOf("visual-testing"),
                    knownMentions = setOf("artin"),
                    highlightedMentions = setOf("artin")
                )
            )
        var openedAction: SlockRichTextAction? = null
        val annotated =
            buildSlockMarkdownParagraphAnnotatedString(
                content = document.content,
                annotator =
                    slockMarkdownAnnotator(
                        document = document,
                        onOpenAction = { action -> openedAction = action }
                    )
            )
        val refTokens = document.inlineTokens.filter { it.kind == SlockRichTextInlineKind.SlockRef }
        val inlineCode = document.inlineTokens.single { it.kind == SlockRichTextInlineKind.InlineCode }
        val expectedActionIds = refTokens.mapNotNullTo(mutableSetOf()) { it.actionId }

        assertEquals(setOf("slock:5", "slock:15", "slock:44"), expectedActionIds)
        assertEquals(expectedActionIds, annotated.slockMarkdownClickableTags())
        assertEquals(expectedActionIds, annotated.slockMarkdownClickableLinkTags())
        assertEquals(
            listOf(
                SlockColor.BrandYellow,
                SlockColor.AccentCyan.copy(alpha = 0.3f),
                SlockColor.BrandYellow.copy(alpha = 0.4f)
            ),
            refTokens.map { token ->
                annotated.slockMarkdownClickableTagInlineBoxBackground(token.actionId!!)
            }
        )
        assertEquals("#not-ref", inlineCode.text)
        assertFalse(annotated.slockMarkdownClickableTags().contains(inlineCode.id))

        val taskAction = document.actions.single { it.refKind == SlockRichTextRefKind.Task }
        val taskLink =
            annotated.getLinkAnnotations(0, annotated.length)
                .map { it.item }
                .filterIsInstance<LinkAnnotation.Clickable>()
                .single { it.tag == taskAction.id }
        taskLink.linkInteractionListener?.onClick(taskLink)

        assertEquals(taskAction, openedAction)
    }

    @Test
    fun visualFixtureUnknownMentionAndTaskReferenceStayPlainText() {
        val body = "@KMP-专家 #520 Phase 5: delete remaining ConversationSummary launch compatibility helpers"
        val validator =
            validThreadMarkdownTags(
                knownMentions = setOf("artin"),
                knownTaskNumbers = setOf(42, 43)
            )
        val annotated =
            buildSlockMarkdownParagraphAnnotatedString(
                content = body,
                annotator =
                    slockMarkdownAnnotator(
                        body,
                        isTagValid = validator
                    )
            )

        assertEquals(body, annotated.text)
        assertEquals(listOf("@KMP-专家", "#520"), body.threadInlineTagMatches().map { it.tag.label })
        assertEquals(emptySet(), annotated.slockMarkdownClickableTags())
        assertTrue(annotated.slockMarkdownInlineContentIds().isEmpty())
    }

    @Test
    fun channelBeforeProseColonKeepsKnownChannelToken() {
        val body = "Rich fixture for #design: @artin"
        val matches = body.threadInlineTagMatches()
        val channel = matches.single { it.tag.kind == ThreadMarkdownTagKind.Channel }

        assertEquals("#design", channel.tag.label)
        assertEquals("#design", body.substring(channel.range))

        val annotated =
            buildSlockMarkdownParagraphAnnotatedString(
                content = body,
                annotator =
                    slockMarkdownAnnotator(
                        body,
                        highlightedMentions = setOf("artin"),
                        isTagValid =
                            validThreadMarkdownTags(
                                knownChannels = setOf("design"),
                                knownMentions = setOf("artin")
                            )
                    )
            )

        assertEquals(body, annotated.text)
        assertEquals(setOf("slock:17", "slock:26"), annotated.slockMarkdownClickableTags())
        assertEquals(
            listOf(
                SlockColor.AccentPink.copy(alpha = 0.3f),
                SlockColor.BrandYellow
            ),
            listOf("slock:17", "slock:26").map { tag ->
                annotated.slockMarkdownClickableTagInlineBoxBackground(tag)
            }
        )
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
                isTagValid =
                    validThreadMarkdownTags(
                        knownChannels = setOf("raft-mobile"),
                        knownMentions = setOf("artin")
                    )
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
    fun referenceChipsUseReactBoldFontFamily() {
        val channelStyle = slockMarkdownTagSpanStyle(ThreadMarkdownTagKind.Channel)
        val richChannelStyle =
            requireNotNull(slockMarkdownRichTextTagLinkStyles(SlockRichTextChromeKind.Channel).style)

        assertEquals(SlockType.ButtonBold.fontFamily, channelStyle.fontFamily)
        assertEquals(FontWeight.Normal, channelStyle.fontWeight)
        assertEquals(SlockType.ButtonBold.fontFamily, richChannelStyle.fontFamily)
        assertEquals(FontWeight.Normal, richChannelStyle.fontWeight)
        assertEquals(null, richChannelStyle.textDecoration)
        assertEquals(SlockRichTextFontWeightToken.Bold, SlockRichTextChromeKind.Channel.styleToken.fontWeight)

        val ordinaryMention = slockMarkdownTagSpanStyle(ThreadMarkdownTagKind.UserMention)
        assertEquals(TextDecoration.Underline, ordinaryMention.textDecoration)
        assertEquals(2.sp, ordinaryMention.textDecorationThickness)
    }

    // react parity (task #407): web inline code renders in Space Mono
    // (`--font-mono`) at the inherited body size. The previous pin
    // ("keep the Slock body font") predated the Space Mono wiring (#130), when
    // FontFamily.Monospace still meant the platform Roboto Mono.
    @Test
    fun inlineCodeUsesSpaceMonoAtBodyMetrics() {
        val inlineCodeStyle = slockMarkdownInlineCodeTextStyle(SlockType.Body)
        val inlineCodeStyleFromBoldText =
            slockMarkdownInlineCodeTextStyle(SlockType.Body.withSlockFontWeight(FontWeight.Bold))

        assertEquals(slockMonoFamilyForWeight(FontWeight.Normal), inlineCodeStyle.fontFamily)
        assertEquals(FontWeight.Normal, inlineCodeStyle.fontWeight)
        assertEquals(SlockType.Body.fontSize, inlineCodeStyle.fontSize)
        assertEquals(SlockType.Body.lineHeight, inlineCodeStyle.lineHeight)
        assertFalse(inlineCodeStyle.fontFamily == FontFamily.Monospace)
        assertEquals(slockMonoFamilyForWeight(FontWeight.Normal), inlineCodeStyleFromBoldText.fontFamily)
        assertEquals(FontWeight.Normal, inlineCodeStyleFromBoldText.fontWeight)
        assertEquals("inlineCode", SlockRichTextChromeKind.InlineCode.styleToken.id)
        assertEquals(SlockRichTextFontFamilyToken.SlockMono, SlockRichTextChromeKind.InlineCode.styleToken.fontFamily)
        assertEquals(SlockRichTextFontWeightToken.Normal, SlockRichTextChromeKind.InlineCode.styleToken.fontWeight)
        assertEquals(SlockRichTextColorToken.BrandYellow40, SlockRichTextChromeKind.InlineCode.styleToken.background)
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
    fun markdownPerformanceLogsOnlySlowSamplesByDefault() {
        assertFalse(shouldLogSlockMarkdownPerformance(0))
        assertFalse(shouldLogSlockMarkdownPerformance(7))
        assertTrue(shouldLogSlockMarkdownPerformance(8))
    }

    @Test
    fun taggedTextUsesFullRendererPreparse() {
        assertEquals(
            "Ping @artin in #KMP",
            "Ping @artin in #KMP".preparedSlockMarkdownFullRendererContentOrNull()?.sourceContent
        )
        assertEquals(
            "#KMP is a channel reference, not a heading",
            "#KMP is a channel reference, not a heading".preparedSlockMarkdownFullRendererContentOrNull()?.sourceContent
        )
        assertEquals(
            "Plain text only",
            "Plain text only".preparedSlockMarkdownFullRendererContentOrNull()?.sourceContent
        )
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
            buildSlockMarkdownParagraphAnnotatedString(
                content = body,
                annotator = slockMarkdownAnnotator(body)
            )
        val link = annotated.getLinkAnnotations(0, annotated.length).single().item as LinkAnnotation.Url
        val linkStyle = link.styles?.style

        assertEquals("Open Raft now", annotated.text)
        assertEquals("https://raft.build", link.url)
        assertEquals(expectedLinkBlue, linkStyle?.color)
        assertEquals(TextDecoration.Underline, linkStyle?.textDecoration)
    }

    @Test
    fun markdownStrongSpansUseWeightSpecificSlockFontWithoutSyntheticWeight() {
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
        val tokenPlan =
            SlockMarkdownInlineTokenRegistry.plan(
                body,
                highlightedMentions = setOf("artin"),
                isTagValid =
                    validThreadMarkdownTags(
                        knownChannels = setOf("markdown专修"),
                        knownMentions = setOf("artin")
                    ),
                inlineContentScope = "message-mixed"
            )
        val annotator =
            slockMarkdownAnnotator(
                body,
                highlightedMentions = setOf("artin"),
                isTagValid =
                    validThreadMarkdownTags(
                        knownChannels = setOf("markdown专修"),
                        knownMentions = setOf("artin")
                    ),
                inlineContentScope = "message-mixed",
                tokenPlan = tokenPlan
            )
        val annotated = buildSlockMarkdownParagraphAnnotatedString(body, annotator)
            .withSlockMarkdownSpanFonts(SlockType.Body)
        val expectedTagIds = tokenPlan.tagTokens().mapTo(mutableSetOf()) { it.id }

        assertEquals("Ping @artin with ${inlineCodeSegment("mimo")} and bold in #markdown专修", annotated.text)
        assertEquals(
            setOf("slock-message-mixed:5", "slock-message-mixed:40"),
            annotated.slockMarkdownClickableTags()
        )
        assertTrue(annotated.slockMarkdownInlineContentIds().isEmpty())
        assertTrue(annotator.inlineContent?.invoke().orEmpty().isEmpty())
        val boldStart = annotated.text.indexOf("bold")
        val boldSpan = annotated.spanStyles.last { it.start == boldStart && it.end == boldStart + "bold".length }
        assertEquals(SlockType.ButtonBold.fontFamily, boldSpan.item.fontFamily)
        assertEquals(FontWeight.Normal, boldSpan.item.fontWeight)
    }

    @Test
    fun markdownCodeSpansMapMonospaceToSpaceMono() {
        // react parity (task #407): Monospace-intent spans (code) render in
        // Space Mono, not the generic platform mono and not the body family.
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

        assertEquals(slockMonoFamilyForWeight(FontWeight.Normal), codeFontSpan.item.fontFamily)
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
        val channelWidth =
            slockMarkdownInlineTagWidthSp(
                "#markdown专修",
                ThreadMarkdownTagKind.Channel
            ).value
        val threadRefWidth =
            slockMarkdownInlineTagWidthSp(
                "#markdown专修:692d9e88",
                ThreadMarkdownTagKind.ChannelThread
            ).value
        val taskWidth = slockMarkdownInlineChipWidthSp("#405").value

        assertTrue(shortMentionWidth in 70f..80f, "shortMentionWidth=$shortMentionWidth")
        assertTrue(mentionWidth in 160f..170f, "mentionWidth=$mentionWidth")
        assertTrue(longAgentMentionWidth in 172f..182f, "longAgentMentionWidth=$longAgentMentionWidth")
        assertTrue(qaMentionWidth in 110f..120f, "qaMentionWidth=$qaMentionWidth")
        assertTrue(selfMentionWidth in 52f..62f, "selfMentionWidth=$selfMentionWidth")
        assertTrue(channelWidth in 130f..140f, "channelWidth=$channelWidth")
        assertTrue(threadRefWidth in 192f..204f, "threadRefWidth=$threadRefWidth")
        assertTrue(taskWidth in 40f..48f, "taskWidth=$taskWidth")
    }

    @Test
    fun fullRendererTagLinksRemainAndCompactCodeUsesMarkdownCodeSpan() {
        val body = "Ping @artin for #405 and `mimo`"
        val annotator =
            slockMarkdownAnnotator(
                content = body,
                highlightedMentions = setOf("artin"),
                isTagValid = validThreadMarkdownTags(knownMentions = setOf("artin"))
            )

        assertTrue(annotator.inlineContent?.invoke().orEmpty().isEmpty())

        val annotated = buildSlockMarkdownParagraphAnnotatedString(body, annotator)
        assertEquals("Ping @artin for #405 and ${inlineCodeSegment("mimo")}", annotated.text)
        assertEquals(setOf("slock:5", "slock:16"), annotated.slockMarkdownClickableTags())
        assertTrue(annotated.slockMarkdownInlineContentIds().isEmpty())
        assertEquals(listOf(inlineCodeSegment("mimo")), annotated.slockInlineCodeMarkerTextSegments())
    }

    @Test
    fun richMarkdownFullRendererKeepsTagsWhileCodeUsesMarkdownCodeSpan() {
        val body = "**Bold** text for @artin in #markdown专修 with `code`"
        val annotator =
            slockMarkdownAnnotator(
                content = body,
                isTagValid =
                    validThreadMarkdownTags(
                        knownChannels = setOf("markdown专修"),
                        knownMentions = setOf("artin")
                    )
            )

        assertTrue(annotator.inlineContent?.invoke().orEmpty().isEmpty())

        val annotated = buildSlockMarkdownParagraphAnnotatedString(body, annotator)

        assertEquals("Bold text for @artin in #markdown专修 with ${inlineCodeSegment("code")}", annotated.text)
        assertEquals(setOf("slock:18", "slock:28"), annotated.slockMarkdownClickableTags())
        assertTrue(annotated.slockMarkdownInlineContentIds().isEmpty())
        assertEquals(listOf(inlineCodeSegment("code")), annotated.slockInlineCodeMarkerTextSegments())
    }

    @Test
    fun inlineTagPlanFindsTokensForTextNodeRange() {
        val body = "Ping @artin for #405 and #markdown专修:7933cf78"
        val plan =
            SlockMarkdownInlineTokenRegistry.plan(
                body,
                isTagValid =
                    validThreadMarkdownTags(
                        knownChannels = setOf("markdown专修"),
                        knownMentions = setOf("artin")
                    )
            )

        assertEquals(listOf("@artin"), plan.tagTokensInRange(0, 12).map { it.label })
        assertEquals(listOf("#405"), plan.tagTokensInRange(12, 22).map { it.label })
        assertEquals(listOf("#markdown专修:7933cf78"), plan.tagTokensInRange(22, body.length).map { it.label })
        assertTrue(plan.tagTokensInRange(5, 8).isEmpty())
    }

    @Test
    fun inlineCodeTokensAreNotRegisteredAsSlockInlineContent() {
        val body = "`mimo` `very-long-inline-code-value-that-should-wrap-normally`"
        val plan = SlockMarkdownInlineTokenRegistry.plan(body)
        val inlineContent =
            slockMarkdownAnnotator(
                content = body
            ).inlineContent?.invoke().orEmpty()

        assertEquals(2, plan.inlineCodeMatchCount())
        assertTrue(inlineContent.isEmpty())
    }

    @Test
    fun channelThreadReferencesRenderAsSingleInlineChip() {
        val matches = "See #markdown专修:7933cf78 and #general:abc123 in chat".threadInlineTagMatches()
        val annotated =
            buildSlockMarkdownParagraphAnnotatedString(
                content = "See #markdown专修:7933cf78 and #general:abc123 in chat",
                annotator =
                    slockMarkdownAnnotator(
                        content = "See #markdown专修:7933cf78 and #general:abc123 in chat",
                        isTagValid = validThreadMarkdownTags(knownChannels = setOf("markdown专修", "general"))
                    )
            )

        assertEquals(2, matches.size)
        assertEquals(ThreadMarkdownTagKind.ChannelThread, matches[0].tag.kind)
        assertEquals("#markdown专修:7933cf78", matches[0].tag.label)
        assertEquals("markdown专修", matches[0].tag.channelName)
        assertEquals("7933cf78", matches[0].tag.threadShortId)
        assertEquals(setOf("slock:4", "slock:29"), annotated.slockMarkdownClickableTags())
        assertEquals(setOf("slock:4", "slock:29"), annotated.slockMarkdownClickableLinkTags())
    }

    @Test
    fun fullMarkdownRendererKeepsChannelThreadClickableTags() {
        val body = "Final #markdown专修:9579b48a @artin #markdown专修 #37."
        val isTagValid =
            validThreadMarkdownTags(
                knownChannels = setOf("markdown专修"),
                knownMentions = setOf("artin")
            )
        val plan = SlockMarkdownInlineTokenRegistry.plan(body, isTagValid = isTagValid)
        val annotated =
            buildSlockMarkdownParagraphAnnotatedString(
                content = body,
                annotator =
                    slockMarkdownAnnotator(
                        content = body,
                        isTagValid = isTagValid,
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
        assertEquals(
            setOf("slock:6", "slock:27", "slock:34", "slock:46"),
            annotated.slockMarkdownClickableLinkTags()
        )
    }

    @Test
    fun fullRendererClickableTagsCanBeScopedPerMessageRow() {
        val body = "Ping @artin in #markdown专修"
        val isTagValid =
            validThreadMarkdownTags(
                knownChannels = setOf("markdown专修"),
                knownMentions = setOf("artin")
            )
        val firstPlan =
            SlockMarkdownInlineTokenRegistry.plan(
                body,
                isTagValid = isTagValid,
                inlineContentScope = "message-a"
            )
        val secondPlan =
            SlockMarkdownInlineTokenRegistry.plan(
                body,
                isTagValid = isTagValid,
                inlineContentScope = "message-b"
            )
        val firstAnnotated =
            buildSlockMarkdownParagraphAnnotatedString(
                content = body,
                annotator =
                    slockMarkdownAnnotator(
                        content = body,
                        isTagValid = isTagValid,
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
                        isTagValid = isTagValid,
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
        val plan =
            SlockMarkdownInlineTokenRegistry.plan(
                body,
                isTagValid = validThreadMarkdownTags(knownChannels = setOf("raft-mobile"))
            )

        assertEquals(listOf("#raft-mobile", "#raft-mobile:4752266f"), matches.map { it.tag.label })
        assertEquals(ThreadMarkdownTagKind.Channel, matches[0].tag.kind)
        assertEquals(ThreadMarkdownTagKind.ChannelThread, matches[1].tag.kind)
        assertEquals("raft-mobile", matches[1].tag.channelName)
        assertEquals("4752266f", matches[1].tag.threadShortId)
        assertEquals(listOf("#raft-mobile", "#raft-mobile:4752266f"), plan.tagTokens().map { it.label })
    }

    @Test
    fun inlineChannelReferencesRequireValidChannelCallback() {
        val body = "Known #raft-mobile thread #raft-mobile:4752266f unknown #not-channel and #not-channel:4752266f"

        val plan =
            SlockMarkdownInlineTokenRegistry.plan(
                body,
                isTagValid = validThreadMarkdownTags(knownChannels = setOf("raft-mobile"))
            )

        assertEquals(listOf("#raft-mobile", "#raft-mobile:4752266f"), plan.tagTokens().map { it.label })
        assertEquals(
            listOf(MarkdownInlineTokenKind.Channel, MarkdownInlineTokenKind.ChannelThread),
            plan.tagTokens().map { it.kind }
        )
    }

    @Test
    fun emptyChannelCallbackSuppressesAllChannelReferences() {
        val body = "Ping @artin in #raft-mobile and #raft-mobile:4752266f for #25"

        val plan =
            SlockMarkdownInlineTokenRegistry.plan(
                body,
                isTagValid = validThreadMarkdownTags(knownMentions = setOf("artin"))
            )

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
                "Ping @KMP-专家 in #KMP and #KMP:a6967088",
                isTagValid =
                    validThreadMarkdownTags(
                        knownChannels = setOf("KMP"),
                        knownMentions = setOf("KMP-专家")
                    )
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
                content = "Use `#markdown专修:7933cf78` outside #markdown专修:7933cf78",
                annotator =
                    slockMarkdownAnnotator(
                        content = "Use `#markdown专修:7933cf78` outside #markdown专修:7933cf78",
                        isTagValid = validThreadMarkdownTags(knownChannels = setOf("markdown专修"))
                    )
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

    private fun renderSlockMarkdownListItemTexts(content: String): List<String> {
        return renderSlockMarkdownListItemAnnotatedStrings(
            content = content,
            annotator = slockMarkdownAnnotator(content)
        ).map { it.text }
    }

    private fun renderSlockMarkdownListItemAnnotatedStrings(
        content: String,
        annotator: MarkdownAnnotator
    ): List<AnnotatedString> {
        val parserContent = MarkdownParserContent.from(content).parserContent
        val parsedTree = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(parserContent)
        val paragraphs = mutableListOf<ASTNode>()
        fun collectListParagraphs(node: ASTNode) {
            if (node.type == MarkdownElementTypes.PARAGRAPH &&
                node.parent?.type == MarkdownElementTypes.LIST_ITEM
            ) {
                paragraphs += node
            }
            node.children.forEach(::collectListParagraphs)
        }
        collectListParagraphs(parsedTree)

        val settings =
            DefaultAnnotatorSettings(
                linkTextSpanStyle = TextLinkStyles(style = SpanStyle()),
                codeSpanStyle = SpanStyle(),
                annotator = annotator
            )
        return paragraphs.map { paragraph ->
            buildAnnotatedString {
                buildMarkdownAnnotatedString(parserContent, paragraph, settings)
            }
        }
    }

    private fun AnnotatedString.slockMarkdownClickableTags(): Set<String> =
        getStringAnnotations(SlockMarkdownTagAnnotationTag, 0, length)
            .map { it.item }
            .toSet()

    private fun AnnotatedString.slockMarkdownClickableLinkTags(): Set<String> =
        getLinkAnnotations(0, length)
            .mapNotNull { (it.item as? LinkAnnotation.Clickable)?.tag }
            .toSet()

    private fun AnnotatedString.slockMarkdownClickableTagBackground(tag: String): Color? =
        getLinkAnnotations(0, length)
            .firstNotNullOfOrNull { range ->
                (range.item as? LinkAnnotation.Clickable)
                    ?.takeIf { it.tag == tag }
                    ?.styles
                    ?.style
                    ?.background
            }

    private fun AnnotatedString.slockMarkdownClickableTagInlineBoxBackground(tag: String): Color? =
        getLinkAnnotations(0, length)
            .firstNotNullOfOrNull { range ->
                (range.item as? LinkAnnotation.Clickable)
                    ?.takeIf { it.tag == tag }
                    ?.styles
                    ?.style
                    ?.inlineBoxStyle
                    ?.backgroundColor
            }

    private fun validThreadMarkdownTags(
        knownChannels: Set<String> = emptySet(),
        knownMentions: Set<String> = emptySet(),
        knownTaskNumbers: Set<Int>? = null
    ): (ThreadMarkdownTag) -> Boolean {
        val channelKeys = knownChannels.mapTo(mutableSetOf()) { it.trim().lowercase() }
        val mentionKeys = knownMentions.mapTo(mutableSetOf()) { it.trim().removePrefix("@").lowercase() }
        return { tag ->
            when (tag.kind) {
                ThreadMarkdownTagKind.Task ->
                    knownTaskNumbers?.contains(tag.label.removePrefix("#").toIntOrNull()) ?: true
                ThreadMarkdownTagKind.UserMention ->
                    tag.label.removePrefix("@").trim().lowercase() in mentionKeys
                ThreadMarkdownTagKind.Channel,
                ThreadMarkdownTagKind.ChannelThread -> {
                    val key =
                        (tag.channelName ?: tag.label.removePrefix("#").substringBefore(":"))
                            .trim()
                            .lowercase()
                    key.isNotBlank() && key in channelKeys
                }
            }
        }
    }

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
        val parserContent = MarkdownParserContent.from(content).parserContent
        val parsedTree = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(parserContent)
        val paragraph = parsedTree.children.first { it.type == MarkdownElementTypes.PARAGRAPH }
        val settings =
            DefaultAnnotatorSettings(
                linkTextSpanStyle = TextLinkStyles(style = slockMarkdownLinkSpanStyle(SlockType.Body)),
                codeSpanStyle = SpanStyle(),
                annotator = annotator,
                linkInteractionListener = linkInteractionListener
        )
        return buildAnnotatedString {
            buildMarkdownAnnotatedString(parserContent, paragraph, settings)
        }
    }

    private fun AnnotatedString.slockInlineCodeMarkerTextSegments(): List<String> =
        getStringAnnotations(slockInlineCodeAnnotationTag, start = 0, end = length).map { range ->
            text.substring(range.start, range.end)
        }

    private fun AnnotatedString.slockInlineCodeTrailingMarginSegments(): List<String> =
        getStringAnnotations(slockInlineCodeTrailingMarginAnnotationTag, start = 0, end = length).map { range ->
            text.substring(range.start, range.end)
        }

    // Mirrors the CODE_SPAN emission (AnnotatedStringKtx): the NBSP edge
    // padding only exists on A-bridge platforms (no native chip chrome); the
    // android target reserves real advance padding instead (task #407).
    private fun inlineCodeSegment(code: String): String =
        if (slockInlineCodeUsesEdgePaddingBridge()) {
            "$inlineCodePadding$code$inlineCodePadding"
        } else {
            code
        }
}
