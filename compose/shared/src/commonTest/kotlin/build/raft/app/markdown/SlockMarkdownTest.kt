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
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.semantics.Role
import com.tencent.kuikly.compose.ui.semantics.SemanticsActions
import com.tencent.kuikly.compose.ui.semantics.SemanticsConfiguration
import com.tencent.kuikly.compose.ui.semantics.SemanticsModifier
import com.tencent.kuikly.compose.ui.semantics.SemanticsProperties
import com.tencent.kuikly.compose.ui.semantics.getOrNull
import com.tencent.kuikly.core.base.Size
import com.tencent.kuiklybase.markdown.annotator.DefaultAnnotatorSettings
import com.tencent.kuiklybase.markdown.annotator.appendSlockInlineCodeWithEdgeBoxes
import com.tencent.kuiklybase.markdown.annotator.buildMarkdownAnnotatedString
import com.tencent.kuiklybase.markdown.compose.elements.updateMarkdownTableColumnWidth
import com.tencent.kuiklybase.markdown.model.MarkdownAnnotator
import com.tencent.kuiklybase.markdown.model.MarkdownImageStateImpl
import com.tencent.kuiklybase.markdown.model.MarkdownParserContent
import com.tencent.kuiklybase.markdown.model.SlockGFMFlavourDescriptor
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.parser.MarkdownParser
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SlockMarkdownTest {
    private val expectedLinkBlue = Color(0xFF1D4ED8)
    private val slockInlineCodeAnnotationTag = SLOCK_RICHTEXT_INLINE_CODE_ANNOTATION_TAG
    private val slockInlineCodeTrailingMarginAnnotationTag = SLOCK_RICHTEXT_INLINE_CODE_TRAILING_MARGIN_ANNOTATION_TAG

    @Test
    fun bodyClickAnnotatesOnlyTextOutsideExistingLinks() {
        var bodyClicks = 0
        var tagClicks = 0
        val source =
            buildAnnotatedString {
                append("before ")
                withLink(
                    LinkAnnotation.Clickable(
                        tag = "existing-tag",
                        linkInteractionListener = LinkInteractionListener { tagClicks++ }
                    )
                ) {
                    append("#product")
                }
                append(" after")
            }

        val resolved = source.withSlockMarkdownBodyClick { bodyClicks++ }
        val links = resolved.getLinkAnnotations(0, resolved.length).sortedBy { it.start }

        assertEquals(listOf(0 until 7, 7 until 15, 15 until 21), links.map { it.start until it.end })
        links[0].item.linkInteractionListener?.onClick(links[0].item)
        links[1].item.linkInteractionListener?.onClick(links[1].item)
        links[2].item.linkInteractionListener?.onClick(links[2].item)
        assertEquals(2, bodyClicks)
        assertEquals(1, tagClicks)
    }

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
        // The inline-token planner and full renderer intentionally share the
        // canonical parse cache. Test order may therefore make one of these a
        // parser-cache hit; every requested body must still be accounted for.
        assertEquals(3, first.parsedCount + first.cacheHitCount)
        assertEquals(3, first.scheduledCount)

        val second = prepareSlockMarkdownFullRendererBodies(listOf(fullRendererBody))
        assertEquals(1, second.requestedCount)
        assertEquals(0, second.parsedCount)
        assertEquals(1, second.cacheHitCount)
        assertEquals(0, second.scheduledCount)
    }

    @Test
    fun concurrentMarkdownPreparseSchedulesEachBodyOnlyOnce() = runBlocking {
        val body = "Open https://raft.build/task264-concurrent-preparse"

        val results =
            listOf(
                async { prepareSlockMarkdownFullRendererBodies(listOf(body)) },
                async { prepareSlockMarkdownFullRendererBodies(listOf(body)) }
            ).awaitAll()

        assertEquals(2, results.sumOf { it.requestedCount })
        assertEquals(1, results.sumOf { it.scheduledCount })
        assertEquals(1, results.sumOf { it.cacheHitCount })
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
    fun markdownTaskListCheckboxAcceptsCanonicalCaseVariantsOnly() {
        assertTrue(markdownTaskListCheckboxChecked("[x]"))
        assertTrue(markdownTaskListCheckboxChecked("[X]"))
        assertFalse(markdownTaskListCheckboxChecked("[ ]"))
        assertFalse(markdownTaskListCheckboxChecked("[xx]"))
        assertFalse(markdownTaskListCheckboxChecked("[x] trailing"))
    }

    @Test
    fun markdownTaskListCheckboxPublishesReadOnlyCheckboxRole() {
        val config =
            Modifier
                .slockMarkdownTaskCheckboxSemantics(
                    checkboxLabel = "Task list checkbox",
                    stateLabel = "Completed"
                )
                .semanticsConfiguration()

        assertEquals(Role.Checkbox, config.getOrNull(SemanticsProperties.Role))
        assertEquals("Task list checkbox", config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull())
        assertEquals("Completed", config.getOrNull(SemanticsProperties.StateDescription))
        assertFalse(config.getOrNull(SemanticsActions.OnClick) != null)
    }

    @Test
    fun markdownTaskListCheckboxPinsToFirstBodyLine() {
        assertEquals(14.dp, SlockMarkdownTaskCheckboxSize)
        assertEquals(4.dp, SlockMarkdownTaskCheckboxFirstLineOffset)
    }

    @Test
    fun bareCheckboxTextDoesNotBecomeTaskListState() {
        val body = "[x] bare text\n\n- [X] completed"
        val parserContent = MarkdownParserContent.from(body).parserContent
        val parsedTree = MarkdownParser(SlockGFMFlavourDescriptor()).buildMarkdownTreeFromString(parserContent)
        val checkboxNodes = mutableListOf<ASTNode>()

        fun collect(node: ASTNode) {
            if (node.type.name == "CHECK_BOX") checkboxNodes += node
            node.children.forEach(::collect)
        }
        collect(parsedTree)

        assertEquals(1, checkboxNodes.size)
        assertTrue(markdownTaskListCheckboxChecked(checkboxNodes.single().getTextInNode(parserContent).toString()))
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
    fun documentPreviewSoftLineBreaksCollapseToSpacesForViewportWrapping() {
        val body = "Treat the first source line\nas one ordinary paragraph."

        val annotated =
            buildSlockMarkdownParagraphAnnotatedString(
                content = body,
                annotator =
                    slockMarkdownAnnotator(
                        content = body,
                        softLineBreaksAsNewLines = false
                    )
            )

        assertEquals("Treat the first source line as one ordinary paragraph.", annotated.text)
    }

    @Test
    fun threadMarkdownKeepsLegacySoftLineBreakRenderingByDefault() {
        val body = "Keep the first message line\non its authored line."

        val annotated = buildSlockMarkdownParagraphAnnotatedString(content = body)

        assertEquals("Keep the first message line\non its authored line.", annotated.text)
    }

    @Test
    fun documentPreviewKeepsExplicitMarkdownHardLineBreaks() {
        val body = "Keep this explicit break.  \nStart the next line."

        val annotated =
            buildSlockMarkdownParagraphAnnotatedString(
                content = body,
                annotator =
                    slockMarkdownAnnotator(
                        content = body,
                        softLineBreaksAsNewLines = false
                    )
            )

        assertEquals("Keep this explicit break.\nStart the next line.", annotated.text)
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
        assertTrue(
            annotated.getStringAnnotations(
                "raft.build.markdown.inlineCode",
                start = 0,
                end = annotated.length
            ).isEmpty()
        )
    }

    @Test
    fun inlineCodePreservesExactCommandTextWithEdgeChrome() {
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
    fun inlineCodeEdgeBoxesPreserveExtendedGraphemeClusters() {
        assertInlineCodeEdgeGraphemes(
            code = "👩‍💻tool e\u0301",
            expectedLeading = "👩‍💻",
            expectedTrailing = "e\u0301",
        )
        assertInlineCodeEdgeGraphemes(
            code = "e\u0301tool 👩‍💻",
            expectedLeading = "e\u0301",
            expectedTrailing = "👩‍💻",
        )
    }

    // task #104 (OHOS gray background): a platform whose composed-character
    // segmenter is unavailable (slockFirstComposedCharacterEnd == null, the
    // OHOS actual) must STILL attach the inline-code box chrome as a
    // single-child group. Dropping the group is the defect: mono typography
    // survives via the outer code-span style while the gray chip silently
    // disappears, so no shared test goes red without this tooth.
    @Test
    fun inlineCodeEdgeBoxesSurviveMissingComposedCharacterSegmenter() {
        val code = "raft build"

        // Null segmenter (OHOS shape) and a working segmenter must agree on
        // the box invariant; only the child split may differ.
        val withoutSegmenter = buildAnnotatedString {
            appendSlockInlineCodeWithEdgeBoxes(code, firstComposedCharacterEnd = null)
        }
        val withSegmenter = buildAnnotatedString {
            appendSlockInlineCodeWithEdgeBoxes(code, firstComposedCharacterEnd = 1)
        }
        // Degenerate one-grapheme answer (== length) keeps the single-child shape.
        val wholeTextCluster = buildAnnotatedString {
            appendSlockInlineCodeWithEdgeBoxes(code, firstComposedCharacterEnd = code.length)
        }

        for (annotated in listOf(withoutSegmenter, withSegmenter, wholeTextCluster)) {
            assertEquals(code, annotated.text)
            val boxRanges = annotated.spanStyles.filter { it.item.inlineBoxStyle != null }
            assertEquals(1, boxRanges.size)
            val box = boxRanges.single()
            assertEquals(0, box.start)
            assertEquals(code.length, box.end)
            assertEquals(
                SlockColor.TextBlack.copy(alpha = 0.05f),
                box.item.inlineBoxStyle?.backgroundColor,
            )
            assertTrue(box.item.inlineBoxStyle!!.paddingStart > 0.dp)
            assertTrue(box.item.inlineBoxStyle!!.paddingEnd > 0.dp)
        }

        // The split child exists only when the segmenter answered mid-text.
        assertTrue(
            withSegmenter.spanStyles.any { range ->
                range.start == 0 && range.end == 1 && range.item.inlineBoxStyle == null
            }
        )
        assertFalse(
            withoutSegmenter.spanStyles.any { range -> range.item.inlineBoxStyle == null }
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
    fun mixedMarkdownKeepsReferenceInlineBoxesOnTheirOwnTextRanges() {
        val body =
            "另外帮一个澄清避免混淆：#31（read-state→Activity，我的）和 @Android-Developer-4 的 " +
                "**task #521（`message:new`→Activity 即时新增，artin 原话\"新消息来了 activity 没刷新\"）" +
                "是两个不同 gap，不是重复——Codex 早前建议的\"#521 判重关闭\"是把两者混了，AD4 已更正。" +
                "#31 复用 versioned read-state fact，#521 是 SharedActivityStore 缺 message:new realtime，两条链路各修各的。"
        val annotated =
            buildSlockMarkdownParagraphAnnotatedString(
                content = body,
                annotator =
                    slockMarkdownAnnotator(
                        body,
                        highlightedMentions = setOf("Android-Developer-4"),
                        isTagValid =
                            validThreadMarkdownTags(
                                knownMentions = setOf("Android-Developer-4"),
                                knownTaskNumbers = setOf(31, 521)
                            )
                    )
            )

        val boxedLinkTexts =
            annotated.getLinkAnnotations(0, annotated.length)
                .filter { range ->
                    (range.item as? LinkAnnotation.Clickable)
                        ?.styles
                        ?.style
                        ?.inlineBoxStyle != null
                }
                .map { range -> annotated.text.substring(range.start, range.end) }

        assertEquals(
            listOf("#31", "@Android-Developer-4", "#521", "#521", "#31", "#521"),
            boxedLinkTexts
        )
        assertFalse(boxedLinkTexts.contains("另外帮一个澄清避免混淆："))
        assertEquals(listOf(inlineCodeSegment("message:new")), annotated.slockInlineCodeMarkerTextSegments())
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
        assertEquals(SlockType.ButtonBold.fontWeight, channelStyle.fontWeight)
        assertEquals(SlockType.ButtonBold.fontFamily, richChannelStyle.fontFamily)
        assertEquals(SlockType.ButtonBold.fontWeight, richChannelStyle.fontWeight)
        assertEquals(null, richChannelStyle.textDecoration)
        assertEquals(SlockRichTextFontWeightToken.Bold, SlockRichTextChromeKind.Channel.styleToken.fontWeight)

        val ordinaryMention = slockMarkdownTagSpanStyle(ThreadMarkdownTagKind.UserMention)
        assertEquals(TextDecoration.Underline, ordinaryMention.textDecoration)
        assertEquals(2.sp, ordinaryMention.textDecorationThickness)
    }

    // Product ruling (task #816): message prose keeps the shared product font;
    // only inline-code glyphs use Web's Space Mono typography. Chrome remains
    // a single painter and carries Web's taller inline box.
    @Test
    fun inlineCodeUsesMonoWithoutLeakingIntoBodyAndSingleNeutralChrome() {
        val inlineCodeStyle = slockMarkdownInlineCodeTextStyle(SlockType.Body)
        val inlineCodeStyleFromBoldText =
            slockMarkdownInlineCodeTextStyle(SlockType.Body.withSlockFontWeight(FontWeight.Bold))
        val inlineBox = slockMarkdownInlineBoxStyle(SlockRichTextChromeKind.InlineCode)
        val leadingEdge = slockMarkdownInlineCodeEdgeStyle(leading = true)
        val trailingEdge = slockMarkdownInlineCodeEdgeStyle(leading = false)

        assertEquals(slockMonoFamilyForWeight(FontWeight.Normal), inlineCodeStyle.fontFamily)
        assertEquals(FontWeight.Normal, inlineCodeStyle.fontWeight)
        assertEquals(SlockType.Body.fontSize, inlineCodeStyle.fontSize)
        assertEquals(SlockType.Body.lineHeight, inlineCodeStyle.lineHeight)
        assertEquals(SlockType.Body.letterSpacing, inlineCodeStyle.letterSpacing)
        assertFalse(inlineCodeStyle.fontFamily == slockFontFamilyForWeight(FontWeight.Normal))
        assertEquals(slockMonoFamilyForWeight(FontWeight.Normal), inlineCodeStyleFromBoldText.fontFamily)
        assertEquals(FontWeight.Normal, inlineCodeStyleFromBoldText.fontWeight)
        assertEquals("inlineCode", SlockRichTextChromeKind.InlineCode.styleToken.id)
        assertEquals(SlockRichTextFontFamilyToken.SlockMono, SlockRichTextChromeKind.InlineCode.styleToken.fontFamily)
        assertEquals(SlockRichTextFontWeightToken.Normal, SlockRichTextChromeKind.InlineCode.styleToken.fontWeight)
        assertEquals(null, SlockRichTextChromeKind.InlineCode.styleToken.background)
        assertEquals(SlockRichTextColorToken.Black5, SlockRichTextChromeKind.InlineCode.styleToken.chipFill)
        assertEquals(SlockColor.TextBlack.copy(alpha = 0.05f), inlineBox.backgroundColor)
        assertEquals(0.dp, inlineBox.borderWidth)
        assertEquals(4.dp, inlineBox.paddingStart)
        assertEquals(4.dp, inlineBox.paddingEnd)
        assertEquals(1.dp, inlineBox.paddingTop)
        assertEquals(1.dp, inlineBox.paddingBottom)
        assertEquals(0.dp, inlineBox.marginStart)
        assertEquals(0.dp, inlineBox.marginEnd)
        assertEquals(0.dp, inlineBox.cornerRadius)
        assertEquals(4.dp, leadingEdge.paddingStart)
        assertEquals(0.dp, leadingEdge.paddingEnd)
        assertEquals(0.dp, trailingEdge.paddingStart)
        assertEquals(4.dp, trailingEdge.paddingEnd)
        assertEquals(1.dp, leadingEdge.paddingTop)
        assertEquals(1.dp, leadingEdge.paddingBottom)
        assertEquals(1.dp, trailingEdge.paddingTop)
        assertEquals(1.dp, trailingEdge.paddingBottom)
        assertEquals(SlockColor.TextBlack.copy(alpha = 0.05f), leadingEdge.backgroundColor)
        assertEquals(SlockColor.TextBlack.copy(alpha = 0.05f), trailingEdge.backgroundColor)

        val body = "before `effective-family` after"
        val rendered =
            buildSlockMarkdownParagraphAnnotatedString(
                content = body,
                annotator = slockMarkdownAnnotator(body),
                codeSpanStyle = inlineCodeStyle.toSpanStyle(),
            ).withSlockMarkdownSpanFonts(SlockType.Body)
        val codeStart = rendered.text.indexOf("effective-family")
        val codeEnd = codeStart + "effective-family".length
        fun effectiveSpanStyleAt(index: Int): SpanStyle =
            rendered.spanStyles
                .filter { range -> range.start <= index && range.end > index }
                .fold(SpanStyle()) { style, range -> style.merge(range.item) }
        val effectiveAtCode = effectiveSpanStyleAt(codeStart)
        assertEquals(slockMonoFamilyForWeight(FontWeight.Normal), effectiveAtCode.fontFamily)
        assertEquals(FontWeight.Normal, effectiveAtCode.fontWeight)
        assertEquals(SlockType.Body.fontSize * 0.875f, effectiveAtCode.fontSize)
        assertEquals(SlockType.Body.letterSpacing, effectiveAtCode.letterSpacing)
        assertTrue(
            rendered.spanStyles.none { range ->
                range.item.fontFamily == slockMonoFamilyForWeight(FontWeight.Normal) &&
                    (range.start < codeStart || range.end > codeEnd)
            }
        )
        assertFalse(effectiveSpanStyleAt(codeStart - 1).fontFamily == slockMonoFamilyForWeight(FontWeight.Normal))
        assertFalse(effectiveSpanStyleAt(codeEnd).fontFamily == slockMonoFamilyForWeight(FontWeight.Normal))
    }

    @Test
    fun releaseNotesSectionGapClearsInlineCodeChromePaintOverflow() {
        // Web's 18px visible box paints 1dp beyond each Mobile text edge.
        assertFalse(slockMarkdownSectionHeadingGapClearsInlineCodeChrome(0.dp))
        assertTrue(slockMarkdownSectionHeadingGapClearsInlineCodeChrome(8.dp))
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
        assertEquals(14.sp, typography.inlineCode.fontSize)
        assertEquals(0.sp, typography.inlineCode.letterSpacing)
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
        assertEquals(2.sp, typography.textLink.style?.textDecorationThickness)
        assertEquals(2.sp, typography.textLink.style?.textDecorationOffset)
        assertEquals(SlockType.Body.fontWeight, typography.textLink.style?.fontWeight)
        assertEquals(expectedLinkBlue, typography.link.color)
        assertEquals(TextDecoration.Underline, typography.link.textDecoration)
        assertEquals(SlockType.Body.fontWeight, typography.link.fontWeight)
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
        assertEquals(2.sp, linkStyle?.textDecorationThickness)
        assertEquals(2.sp, linkStyle?.textDecorationOffset)
        assertEquals(SlockType.Body.fontWeight, linkStyle?.fontWeight)
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
        assertEquals(SlockType.ButtonBold.fontWeight, strongFontSpan.item.fontWeight)
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
        assertEquals(SlockType.ButtonBold.fontWeight, boldSpan.item.fontWeight)
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
    fun fiftyLongRowsOnlyRebuildPlansWhoseOwnMentionValidityChanges() {
        val planner = SlockMarkdownInlineTokenPlanner(maxEntries = 256)
        val bodies =
            List(50) { index ->
                buildString {
                    append("Long joint-channel message $index: ")
                    append("stable markdown content ".repeat(120))
                    if (index < 9) append(" @member$index")
                }
            }
        val noDirectory = validThreadMarkdownTags()
        val membersDirectory =
            validThreadMarkdownTags(
                knownMentions = (0 until 9).mapTo(mutableSetOf()) { index -> "member$index" }
            )
        val candidatesDirectory =
            validThreadMarkdownTags(
                knownMentions =
                    buildSet {
                        (0 until 9).forEach { index -> add("member$index") }
                        (0 until 80).forEach { index -> add("candidate$index") }
                    }
            )

        val emptyPlans =
            bodies.mapIndexed { index, body ->
                planner.plan(body, isTagValid = noDirectory, inlineContentScope = "message-$index")
            }
        assertEquals(50, planner.planBuildCount)

        val memberPlans =
            bodies.mapIndexed { index, body ->
                planner.plan(body, isTagValid = membersDirectory, inlineContentScope = "message-$index")
            }
        assertEquals(
            59,
            planner.planBuildCount,
            "only the nine visible/overscan rows whose own mentions became valid may rebuild"
        )
        (0 until 9).forEach { index -> assertNotSame(emptyPlans[index], memberPlans[index]) }
        (9 until 50).forEach { index -> assertSame(emptyPlans[index], memberPlans[index]) }

        val candidatePlans =
            bodies.mapIndexed { index, body ->
                planner.plan(body, isTagValid = candidatesDirectory, inlineContentScope = "message-$index")
            }
        assertEquals(
            59,
            planner.planBuildCount,
            "irrelevant candidate hydration must not create a third plan for any row"
        )
        memberPlans.indices.forEach { index -> assertSame(memberPlans[index], candidatePlans[index]) }
    }

    @Test
    fun resolvedMentionLabelChangeRebuildsOnlyOwningRowAndUsesLatestHandler() {
        val planner = SlockMarkdownInlineTokenPlanner(maxEntries = 256)
        val bodies =
            List(50) { index ->
                buildString {
                    append("Long joint-channel message $index: ")
                    append("stable markdown content ".repeat(120))
                    if (index == 0) append(" @member0")
                }
            }
        val validator = validThreadMarkdownTags(knownMentions = setOf("member0"))
        val initialResolver: (ThreadMarkdownTag) -> String = { tag ->
            threadMarkdownTagDisplayLabel(tag.label)
        }
        val hydratedResolver: (ThreadMarkdownTag) -> String = { tag ->
            if (tag.kind == ThreadMarkdownTagKind.UserMention && tag.label == "@member0") {
                "@Display Name"
            } else {
                threadMarkdownTagDisplayLabel(tag.label)
            }
        }

        val initialPlans =
            bodies.mapIndexed { index, body ->
                planner.plan(
                    body,
                    isTagValid = validator,
                    resolveTagLabel = initialResolver,
                    inlineContentScope = "message-$index"
                )
            }
        assertEquals(50, planner.planBuildCount)

        val hydratedPlans =
            bodies.mapIndexed { index, body ->
                planner.plan(
                    body,
                    isTagValid = validator,
                    resolveTagLabel = hydratedResolver,
                    inlineContentScope = "message-$index"
                )
            }
        assertEquals(
            51,
            planner.planBuildCount,
            "a valid mention whose resolved label changes must rebuild only its owning row"
        )
        assertNotSame(initialPlans[0], hydratedPlans[0])
        (1 until 50).forEach { index -> assertSame(initialPlans[index], hydratedPlans[index]) }
        assertEquals("@member0", initialPlans[0].tagTokens().single().resolvedLabel)
        assertEquals("@Display Name", hydratedPlans[0].tagTokens().single().resolvedLabel)

        val initialAnnotated =
            buildSlockMarkdownParagraphAnnotatedString(
                content = bodies[0],
                annotator =
                    slockMarkdownAnnotator(
                        content = bodies[0],
                        isTagValid = validator,
                        resolveTagLabel = initialResolver,
                        tokenPlan = initialPlans[0]
                    )
            )
        var openedBy = ""
        var currentHandler: (ThreadMarkdownTag) -> Unit = { openedBy = "old:${it.label}" }
        val hydratedAnnotated =
            buildSlockMarkdownParagraphAnnotatedString(
                content = bodies[0],
                annotator =
                    slockMarkdownAnnotator(
                        content = bodies[0],
                        isTagValid = validator,
                        resolveTagLabel = hydratedResolver,
                        onOpenTag = { tag -> currentHandler(tag) },
                        tokenPlan = hydratedPlans[0]
                    )
            )

        assertTrue(initialAnnotated.text.endsWith(" @member0"))
        assertTrue(hydratedAnnotated.text.endsWith(" @Display Name"))
        currentHandler = { openedBy = "new:${it.label}" }
        val mentionLink =
            hydratedAnnotated.getLinkAnnotations(0, hydratedAnnotated.length)
                .map { it.item }
                .filterIsInstance<LinkAnnotation.Clickable>()
                .single()
        mentionLink.linkInteractionListener?.onClick(mentionLink)
        assertEquals("new:@member0", openedBy)

        val candidatePlans =
            bodies.mapIndexed { index, body ->
                planner.plan(
                    body,
                    isTagValid = validator,
                    resolveTagLabel = hydratedResolver,
                    inlineContentScope = "message-$index"
                )
            }
        assertEquals(51, planner.planBuildCount)
        hydratedPlans.indices.forEach { index -> assertSame(hydratedPlans[index], candidatePlans[index]) }
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
    fun directMessageThreadReferencePreservesTypedPeerAndShortId() {
        val body = "Open dm:@artin:1c50c30a, not @artin:1c50c30a."
        val plan =
            SlockMarkdownInlineTokenRegistry.plan(
                body,
                isTagValid =
                    validThreadMarkdownTags(
                        knownMentions = setOf("artin"),
                        knownDirectMessagePeers = setOf("artin")
                    )
            )

        assertEquals(listOf("dm:@artin:1c50c30a", "@artin"), plan.tagTokens().map { it.label })
        assertEquals(
            listOf(MarkdownInlineTokenKind.DirectMessageThread, MarkdownInlineTokenKind.UserMention),
            plan.tagTokens().map { it.kind }
        )
        val dmThread = plan.tagTokens().first().tag
        assertEquals(ThreadMarkdownTagKind.DirectMessageThread, dmThread?.kind)
        assertEquals("artin", dmThread?.directMessagePeerName)
        assertEquals("1c50c30a", dmThread?.threadShortId)
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
    fun markdownLinksOwnReferenceSemanticsInsideTheirLabelsAndDestinations() {
        val body =
            "[#1487 feat(search): pin entities](https://github.com/botiverse/mobile/pull/1487#discussion) " +
                "then task #1487"

        val matches = body.threadInlineTagMatches()

        assertEquals(listOf("#1487"), matches.map { it.tag.label })
        assertEquals(body.lastIndexOf("#1487"), matches.single().range.first)
    }

    @Test
    fun productionLinkRendersAsOneExternalActionWhileStandaloneTaskStaysRaftAction() {
        val body =
            "[#1487 feat(search): pin entities](https://github.com/botiverse/mobile/pull/1487) " +
                "then task #1487"
        val annotated = buildSlockMarkdownParagraphAnnotatedString(body)
        val links = annotated.getLinkAnnotations(0, annotated.length)
        assertEquals(
            listOf("Url:0..32", "Clickable:43..48"),
            links.map { range ->
                val kind =
                    when (range.item) {
                        is LinkAnnotation.Url -> "Url"
                        is LinkAnnotation.Clickable -> "Clickable"
                        else -> "Other"
                    }
                "$kind:${range.start}..${range.end}"
            },
        )
        val external = links.single { it.item is LinkAnnotation.Url }
        val raft = links.single { it.item is LinkAnnotation.Clickable }

        assertEquals("#1487 feat(search): pin entities then task #1487", annotated.text)
        assertEquals("#1487 feat(search): pin entities", annotated.text.substring(external.start, external.end))
        assertEquals("#1487", annotated.text.substring(raft.start, raft.end))
        assertTrue(raft.start >= external.end)
        assertEquals(setOf("slock:${body.lastIndexOf("#1487")}"), annotated.slockMarkdownClickableTags())
    }

    @Test
    fun mentionInsideLinkStaysOwnedWhileStandaloneSiblingRemainsRaftAction() {
        val body = "[ping @artin](https://example.test) and outside @artin."
        val validator = validThreadMarkdownTags(knownMentions = setOf("artin"))
        val matches = body.threadInlineTagMatches()
        val annotated =
            buildSlockMarkdownParagraphAnnotatedString(
                content = body,
                annotator = slockMarkdownAnnotator(content = body, isTagValid = validator),
            )
        val links = annotated.getLinkAnnotations(0, annotated.length)
        val external = links.single { it.item is LinkAnnotation.Url }
        val standalone = links.single { it.item is LinkAnnotation.Clickable }

        assertEquals(listOf(body.lastIndexOf("@artin")), matches.map { it.range.first })
        assertEquals("ping @artin", annotated.text.substring(external.start, external.end))
        assertEquals("@artin", annotated.text.substring(standalone.start, standalone.end))
        assertTrue(standalone.start >= external.end)
        assertEquals(setOf("slock:${body.lastIndexOf("@artin")}"), annotated.slockMarkdownClickableTags())
    }

    @Test
    fun markdownLinkProtectionHandlesEscapesAndNestedParentheses() {
        val body =
            "[PR #42 with \\] label](https://example.test/path_(preview)#fragment) outside #43"

        assertEquals(listOf("#43"), body.threadInlineTagMatches().map { it.tag.label })
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
        val parsedTree = MarkdownParser(SlockGFMFlavourDescriptor()).buildMarkdownTreeFromString(parserContent)
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
        knownDirectMessagePeers: Set<String> = emptySet(),
        knownTaskNumbers: Set<Int>? = null
    ): (ThreadMarkdownTag) -> Boolean {
        val channelKeys = knownChannels.mapTo(mutableSetOf()) { it.trim().lowercase() }
        val mentionKeys = knownMentions.mapTo(mutableSetOf()) { it.trim().removePrefix("@").lowercase() }
        val directMessagePeerKeys =
            knownDirectMessagePeers.mapTo(mutableSetOf()) { it.trim().removePrefix("@").lowercase() }
        return { tag ->
            when (tag.kind) {
                ThreadMarkdownTagKind.Task ->
                    knownTaskNumbers?.contains(tag.label.removePrefix("#").toIntOrNull()) ?: true
                ThreadMarkdownTagKind.UserMention ->
                    tag.label.removePrefix("@").trim().lowercase() in mentionKeys
                ThreadMarkdownTagKind.DirectMessageThread ->
                    tag.directMessagePeerName.orEmpty().trim().lowercase() in directMessagePeerKeys &&
                        !tag.threadShortId.isNullOrBlank()
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
        linkInteractionListener: LinkInteractionListener? = null,
        codeSpanStyle: SpanStyle = SpanStyle(),
    ): AnnotatedString {
        val parserContent = MarkdownParserContent.from(content).parserContent
        val parsedTree = MarkdownParser(SlockGFMFlavourDescriptor()).buildMarkdownTreeFromString(parserContent)
        val paragraph = parsedTree.children.first { it.type == MarkdownElementTypes.PARAGRAPH }
        val settings =
            DefaultAnnotatorSettings(
                linkTextSpanStyle = TextLinkStyles(style = slockMarkdownLinkSpanStyle(SlockType.Body)),
                codeSpanStyle = codeSpanStyle,
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

    private fun assertInlineCodeEdgeGraphemes(
        code: String,
        expectedLeading: String,
        expectedTrailing: String,
    ) {
        assertEquals(expectedLeading.length, build.raft.app.slockFirstComposedCharacterEnd(code))
        val annotated = buildSlockMarkdownParagraphAnnotatedString("`$code`")
        val boxRanges =
            annotated.spanStyles.filter { range ->
                range.item.inlineBoxStyle?.let { box ->
                    box.paddingStart > 0.dp || box.paddingEnd > 0.dp
                } == true
            }
        val glyphBackgroundRanges =
            annotated.spanStyles.filter { range ->
                range.item.background != Color.Unspecified &&
                    range.start < annotated.length && range.end > 0
            }
        val firstClusterBoundary = code.indexOf(expectedLeading) + expectedLeading.length
        val splitRange =
            annotated.spanStyles.first { range ->
                range.start == 0 && range.end == firstClusterBoundary && range.item.inlineBoxStyle == null
            }

        assertEquals(code, annotated.text)
        assertEquals(1, boxRanges.size)
        assertTrue(glyphBackgroundRanges.isEmpty())
        assertEquals(code, annotated.text.substring(boxRanges.single().start, boxRanges.single().end))
        assertEquals(expectedLeading, annotated.text.substring(splitRange.start, splitRange.end))
        assertTrue(annotated.text.endsWith(expectedTrailing))
        assertEquals(
            SlockColor.TextBlack.copy(alpha = 0.05f),
            boxRanges.single().item.inlineBoxStyle?.backgroundColor,
        )
    }

    // Mirrors the CODE_SPAN emission (AnnotatedStringKtx): edge-only boxes
    // provide horizontal chrome without making the code run atomic.
    private fun inlineCodeSegment(code: String): String =
        code
}

private fun Modifier.semanticsConfiguration(): SemanticsConfiguration =
    foldIn(emptyList<SemanticsConfiguration>()) { current, element ->
        val semantics = (element as? SemanticsModifier)?.semanticsConfiguration
        if (semantics == null) current else current + listOf(semantics)
    }.lastOrNull() ?: error("Expected a semantics modifier")
