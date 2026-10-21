package build.raft.app.markdown

import build.raft.app.RaftColor
import build.raft.app.RaftType
import build.raft.app.raftFontFamilyForWeight
import build.raft.app.raftInlineCodeCharWrapBridgeThreshold
import build.raft.app.raftInlineCodeUsesEdgePaddingBridge
import build.raft.app.raftMonoFamilyForWeight
import build.raft.app.withRaftFontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsModifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.geometry.Size
import androidx.compose.runtime.snapshots.Snapshot
import com.tencent.kuiklybase.markdown.annotator.DefaultAnnotatorSettings
import com.tencent.kuiklybase.markdown.annotator.appendRaftInlineCodeWithEdgeBoxes
import com.tencent.kuiklybase.markdown.annotator.buildMarkdownAnnotatedString
import com.tencent.kuiklybase.markdown.compose.elements.updateMarkdownTableColumnWidth
import com.tencent.kuiklybase.markdown.model.MarkdownAnnotator
import com.tencent.kuiklybase.markdown.model.MarkdownImageStateImpl
import com.tencent.kuiklybase.markdown.model.MarkdownParserContent
import com.tencent.kuiklybase.markdown.model.RaftGFMFlavourDescriptor
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.parser.MarkdownParser
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RaftMarkdownTest {
    // web `text-foreground-link` (light theme in unit tests).
    private val expectedLinkBlue = build.raft.app.RaftAppThemeState.colors.foregroundLink
    private val raftInlineCodeAnnotationTag = RAFT_RICHTEXT_INLINE_CODE_ANNOTATION_TAG
    private val raftInlineCodeTrailingMarginAnnotationTag = RAFT_RICHTEXT_INLINE_CODE_TRAILING_MARGIN_ANNOTATION_TAG

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

        val resolved = source.withRaftMarkdownBodyClick { bodyClicks++ }
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

        val resolved = source.withRaftMessagePermalinkRenderConfig()

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
            source.withRaftMessagePermalinkRenderConfig(
                config = RaftMarkdownRenderConfig(renderMessagePermalinksAsTag = true),
                resolveLabel = { "proj" },
            )

        assertEquals("#proj msg", resolved.text.text.replace("\uFFFC", "").replace("\u2060", ""))
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
            source.withRaftMessagePermalinkChips { target ->
                "proj-server-management".takeIf { target.channelId.value == "channel-1" }
            }
        val link = resolved.getLinkAnnotations(0, resolved.length).single().item as LinkAnnotation.Url

        assertEquals(
            "See #proj-server-management msg now",
            resolved.text.replace("\uFFFC", "").replace("\u2060", ""),
        )
        // icon, icon gap, suffix gap + the chip's reserved outer gap (raft-inline-gap).
        assertEquals(4, resolved.raftMarkdownInlineContentIds().size)
        assertEquals(url, link.url)
        // CMP switch: the chip box travels as a RaftInlineBoxStyle annotation over the link text.
        val linkRange = resolved.getLinkAnnotations(0, resolved.length).single()
        val chipBox = resolved.raftInlineBoxWithin(linkRange.start, linkRange.end)
        assertEquals(raftMessagePermalinkChipBoxStyle(), chipBox)
        assertEquals(Color(0xFFFCEFBD), chipBox?.backgroundColor)
        assertEquals(RaftColor.BorderBlack, chipBox?.borderColor)
        assertEquals(1.dp, chipBox?.borderWidth)
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

        assertSame(source, source.withRaftMessagePermalinkChips())
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
            source.withRaftMessagePermalinkChips().text.replace("\uFFFC", "").replace("\u2060", ""),
        )
    }

    @Test
    fun permalinkChipUsesNativeTextRunsInsteadOfEstimatedWholeChipWidth() {
        val url = "https://app.raft.build/s/mobile/channel/channel-1?msg=message-1"
        val source = buildAnnotatedString {
            withLink(LinkAnnotation.Url(url)) { append(url) }
        }

        val resolved = source.withRaftMessagePermalinkChips { "proj-server-management" }
        val labelStyle =
            resolved.spanStyles.first {
                it.item.fontSize == 14.sp && it.item.fontFamily == raftFontFamilyForWeight(FontWeight.Bold)
            }
        val suffixStyle = resolved.spanStyles.first { it.item.fontSize == 10.sp }

        assertEquals("#proj-server-management", resolved.text.substring(labelStyle.start, labelStyle.end))
        assertEquals("msg", resolved.text.substring(suffixStyle.start, suffixStyle.end))
        // icon, icon gap, suffix gap + the chip's reserved outer gap (raft-inline-gap).
        assertEquals(4, resolved.raftMarkdownInlineContentIds().size)
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

        val resolved = source.withRaftMessagePermalinkChips { "proj-server-management" }

        assertEquals("#proj-server-management thread", resolved.text.replace("\uFFFC", "").replace("\u2060", ""))
    }

    @Test
    fun markdownAutolinkPreservesEncodedThreadPermalinkTarget() {
        val url =
            "https://app.raft.build/s/mobile/channel/channel-1" +
                "?thread=channel-1%3Aparent-1&msg=message-1"
        val source = buildRaftMarkdownParagraphAnnotatedString("Thread reply: $url")

        val resolved = source.withRaftMessagePermalinkChips { "proj-server-management" }

        assertEquals(
            "Thread reply: #proj-server-management thread",
            resolved.text.replace("\uFFFC", "").replace("\u2060", ""),
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
                    tag = RAFT_RICHTEXT_INLINE_CODE_ANNOTATION_TAG,
                    annotation = "true",
                    start = start,
                    end = length
                )
                append(" ")
                withLink(LinkAnnotation.Url(externalUrl)) { append("external") }
            }

        val resolved = source.withRaftMessagePermalinkChips()

        assertEquals("$url external", resolved.text.replace("\uFFFC", "").replace("\u2060", ""))
        val remainingLink =
            resolved.getLinkAnnotations(0, resolved.length).single().item as LinkAnnotation.Url
        assertEquals(externalUrl, remainingLink.url)
        assertTrue(
            resolved.getStringAnnotations(
                RAFT_RICHTEXT_INLINE_CODE_ANNOTATION_TAG,
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
            "Plain text only".preparedRaftMarkdownFullRendererContentOrNull()?.sourceContent
        )
        assertEquals(
            "Ping @artin in #markdown专修",
            "Ping @artin in #markdown专修".preparedRaftMarkdownFullRendererContentOrNull()?.sourceContent
        )
        assertEquals(
            fullRendererBody,
            fullRendererBody.preparedRaftMarkdownFullRendererContentOrNull()?.sourceContent
        )

        val first =
            prepareRaftMarkdownFullRendererBodies(
                listOf("Plain text only", "Ping @artin", fullRendererBody, fullRendererBody)
            )
        assertEquals(3, first.requestedCount)
        // The inline-token planner and full renderer intentionally share the
        // canonical parse cache. Test order may therefore make one of these a
        // parser-cache hit; every requested body must still be accounted for.
        assertEquals(3, first.parsedCount + first.cacheHitCount)
        assertEquals(3, first.scheduledCount)

        val second = prepareRaftMarkdownFullRendererBodies(listOf(fullRendererBody))
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
                async { prepareRaftMarkdownFullRendererBodies(listOf(body)) },
                async { prepareRaftMarkdownFullRendererBodies(listOf(body)) }
            ).awaitAll()

        assertEquals(2, results.sumOf { it.requestedCount })
        assertEquals(1, results.sumOf { it.scheduledCount })
        assertEquals(1, results.sumOf { it.cacheHitCount })
    }

    @Test
    fun markdownImageStateIgnoresSameSizeUpdates() {
        // CMP switch: upstream Size is a value class, so reference identity (assertSame) says
        // nothing; count snapshot state writes instead — a no-op update must not write state.
        val state = MarkdownImageStateImpl()
        val initialContainer = state.containerSize
        val initialIntrinsic = state.intrinsicImageSize

        assertEquals(
            0,
            countSnapshotStateWrites {
                state.updateContainerSize(Size(Float.NaN, Float.NaN))
                state.updateImageSize(Size(Float.NaN, Float.NaN))
            },
        )
        assertEquals(initialContainer, state.containerSize)
        assertEquals(initialIntrinsic, state.intrinsicImageSize)

        state.updateContainerSize(Size(320f, 48f))
        state.updateImageSize(Size(120f, 80f))
        assertEquals(Size(320f, 48f), state.containerSize)
        assertEquals(Size(120f, 80f), state.intrinsicImageSize)

        assertEquals(
            0,
            countSnapshotStateWrites {
                state.updateContainerSize(Size(320f, 48f))
                state.updateImageSize(Size(120f, 80f))
            },
        )
        assertEquals(
            2,
            countSnapshotStateWrites {
                state.updateContainerSize(Size(321f, 48f))
                state.updateImageSize(Size(120f, 81f))
            },
        )
    }

    private fun countSnapshotStateWrites(block: () -> Unit): Int {
        var writes = 0
        val snapshot = Snapshot.takeMutableSnapshot(writeObserver = { writes++ })
        try {
            snapshot.enter(block)
            snapshot.apply().check()
        } finally {
            snapshot.dispose()
        }
        return writes
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
        assertEquals(TextOverflow.Clip, raftMarkdownContentOverflow(TextOverflow.Ellipsis))
        assertEquals(TextOverflow.Clip, raftMarkdownContentOverflow(TextOverflow.Clip))
        assertEquals(TextOverflow.Visible, raftMarkdownContentOverflow(TextOverflow.Visible))
    }

    @Test
    fun singleMarkdownLineBreakRendersAsVisibleNewline() {
        val rendered = renderRaftMarkdownParagraphText("first line\nsecond line")

        assertEquals("first line\nsecond line", rendered)
    }

    @Test
    fun escapedSingleLineBreakRendersAsVisibleNewlineWhenDecoded() {
        val content = "first line\\nsecond line".normalizeThreadMarkdownBody()

        assertEquals("first line\nsecond line", renderRaftMarkdownParagraphText(content))
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

        val rendered = renderRaftMarkdownListItemTexts(body)

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
            renderRaftMarkdownListItemAnnotatedStrings(
                content = body,
                annotator =
                    raftMarkdownAnnotator(
                        content = body,
                        isTagValid = isTagValid
                    )
            )

        assertEquals(2, rendered.size)
        assertEquals("请看 #markdown专修", rendered[0].raftTextWithoutInlineGaps())
        assertEquals(
            setOf("slock:5"),
            rendered[0].raftMarkdownClickableTags()
        )
        assertEquals("继续看 #markdown专修:7933cf78 和 #50", rendered[1].raftTextWithoutInlineGaps())
        assertEquals(
            setOf("slock:23", "slock:46"),
            rendered[1].raftMarkdownClickableTags()
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
                .raftMarkdownTaskCheckboxSemantics(
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
        assertEquals(14.dp, RaftMarkdownTaskCheckboxSize)
        // Centred on the text-body 24sp line box: (24 - 14) / 2.
        assertEquals(5.dp, RaftMarkdownTaskCheckboxFirstLineOffset)
    }

    @Test
    fun bareCheckboxTextDoesNotBecomeTaskListState() {
        val body = "[x] bare text\n\n- [X] completed"
        val parserContent = MarkdownParserContent.from(body).parserContent
        val parsedTree = MarkdownParser(RaftGFMFlavourDescriptor()).buildMarkdownTreeFromString(parserContent)
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
            renderRaftMarkdownParagraphText("<!-- Describe what happened here. --> activity status")
        )
    }

    @Test
    fun documentPreviewSoftLineBreaksCollapseToSpacesForViewportWrapping() {
        val body = "Treat the first source line\nas one ordinary paragraph."

        val annotated =
            buildRaftMarkdownParagraphAnnotatedString(
                content = body,
                annotator =
                    raftMarkdownAnnotator(
                        content = body,
                        softLineBreaksAsNewLines = false
                    )
            )

        assertEquals("Treat the first source line as one ordinary paragraph.", annotated.text)
    }

    @Test
    fun threadMarkdownKeepsLegacySoftLineBreakRenderingByDefault() {
        val body = "Keep the first message line\non its authored line."

        val annotated = buildRaftMarkdownParagraphAnnotatedString(content = body)

        assertEquals("Keep the first message line\non its authored line.", annotated.text)
    }

    @Test
    fun documentPreviewKeepsExplicitMarkdownHardLineBreaks() {
        val body = "Keep this explicit break.  \nStart the next line."

        val annotated =
            buildRaftMarkdownParagraphAnnotatedString(
                content = body,
                annotator =
                    raftMarkdownAnnotator(
                        content = body,
                        softLineBreaksAsNewLines = false
                    )
            )

        assertEquals("Keep this explicit break.\nStart the next line.", annotated.text)
    }

    @Test
    fun inlineCodeStaysInMarkdownRendererInsteadOfRaftPlaceholders() {
        val shortCode = "inline-code"
        val longCode = "very-long-inline-code-value-that-should-wrap-normally"
        val body = "short `$shortCode` long `$longCode`"
        val plan = RaftMarkdownInlineTokenRegistry.plan(body)
        val annotator = raftMarkdownAnnotator(body)

        assertEquals(2, plan.inlineCodeMatchCount())
        assertTrue(annotator.inlineContent?.invoke().orEmpty().isEmpty())

        val annotated =
            buildRaftMarkdownParagraphAnnotatedString(
                content = body,
                annotator = annotator
            )

        assertTrue(annotated.text.contains(shortCode))
        assertTrue(annotated.text.contains(longCode))
        assertTrue(annotated.raftMarkdownInlineContentIds().isEmpty())
        assertEquals(
            listOf(
                inlineCodeSegment(shortCode),
                inlineCodeSegment(longCode)
            ),
            annotated.raftInlineCodeMarkerTextSegments()
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
            buildRaftMarkdownParagraphAnnotatedString(
                content = body,
                annotator = raftMarkdownAnnotator(body)
            )

        assertEquals("Run ${inlineCodeSegment(command)} before review", annotated.text)
        assertEquals(
            listOf(inlineCodeSegment(command)),
            annotated.raftInlineCodeMarkerTextSegments()
        )
        assertEquals(
            listOf(" "),
            annotated.raftInlineCodeTrailingMarginSegments()
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

    // task #104 (gray background): a platform whose composed-character
    // segmenter is unavailable (raftFirstComposedCharacterEnd == null) must STILL attach the inline-code box chrome as a
    // single-child group. Dropping the group is the defect: mono typography
    // survives via the outer code-span style while the gray chip silently
    // disappears, so no shared test goes red without this tooth.
    @Test
    fun inlineCodeEdgeBoxesSurviveMissingComposedCharacterSegmenter() {
        val code = "raft build"

        // Null segmenter and a working segmenter must agree on
        // the box invariant; only the child split may differ.
        val withoutSegmenter = buildAnnotatedString {
            appendRaftInlineCodeWithEdgeBoxes(code, firstComposedCharacterEnd = null)
        }
        val withSegmenter = buildAnnotatedString {
            appendRaftInlineCodeWithEdgeBoxes(code, firstComposedCharacterEnd = 1)
        }
        // Degenerate one-grapheme answer (== length) keeps the single-child shape.
        val wholeTextCluster = buildAnnotatedString {
            appendRaftInlineCodeWithEdgeBoxes(code, firstComposedCharacterEnd = code.length)
        }

        for (annotated in listOf(withoutSegmenter, withSegmenter, wholeTextCluster)) {
            assertEquals(code, annotated.text)
            val boxRanges = annotated.raftInlineBoxRanges()
            assertEquals(1, boxRanges.size)
            val box = boxRanges.single()
            assertEquals(0, box.start)
            assertEquals(code.length, box.end)
            assertEquals(
                RaftColor.TextBlack.copy(alpha = 0.05f),
                box.item.backgroundColor,
            )
            assertTrue(box.item.paddingStart > 0.dp)
            assertTrue(box.item.paddingEnd > 0.dp)
        }

        // The split child exists only when the segmenter answered mid-text.
        // CMP switch: the box is an annotation, so the split child is a plain span-style range.
        assertTrue(
            withSegmenter.spanStyles.any { range -> range.start == 0 && range.end == 1 }
        )
        assertFalse(
            withoutSegmenter.spanStyles.any { range -> range.start == 0 && range.end < code.length }
        )
    }

    @Test
    fun userMentionsRenderAsClickableTags() {
        val body = "Ping @Android-App-Integrator in #markdown专修 for #15"
        val annotated =
            buildRaftMarkdownParagraphAnnotatedString(
                content = body,
                annotator =
                    raftMarkdownAnnotator(
                        body,
                        isTagValid =
                            validThreadMarkdownTags(
                                knownChannels = setOf("markdown专修"),
                                knownMentions = setOf("Android-App-Integrator")
                            )
                    )
            )

        assertEquals(setOf("slock:5", "slock:32", "slock:48"), annotated.raftMarkdownClickableTags())
    }

    @Test
    fun unknownUserMentionsAndChannelsDoNotRenderAsClickableTags() {
        val body = "Ping @not-a-member in #not-a-channel for #15"
        val annotated = buildRaftMarkdownParagraphAnnotatedString(body)

        assertEquals("Ping @not-a-member in #not-a-channel for #15", annotated.raftTextWithoutInlineGaps())
        assertEquals(setOf("slock:41"), annotated.raftMarkdownClickableTags())
    }

    @Test
    fun highlightedUserMentionsStillRenderAsClickableTags() {
        val body = "Ping @artin and @Android-App-Integrator in #markdown专修"
        val annotator =
            raftMarkdownAnnotator(
                body,
                highlightedMentions = setOf("artin"),
                isTagValid =
                    validThreadMarkdownTags(
                        knownChannels = setOf("markdown专修"),
                        knownMentions = setOf("artin", "Android-App-Integrator")
                    )
            )
        val annotated = buildRaftMarkdownParagraphAnnotatedString(body, annotator)

        assertEquals(setOf("slock:5", "slock:16", "slock:43"), annotated.raftMarkdownClickableTags())
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
            RaftMarkdownInlineTokenRegistry.plan(
                body,
                highlightedMentions = setOf("artin"),
                isTagValid = validator
            )
        val annotator =
            raftMarkdownAnnotator(
                body,
                highlightedMentions = setOf("artin"),
                isTagValid = validator,
                tokenPlan = plan
            )
        val annotated = buildRaftMarkdownParagraphAnnotatedString(body, annotator)

        assertEquals(
            listOf("@artin", "#markdown专修", "#markdown专修:692d9e88", "#42", "#43"),
            plan.tagTokens().map { it.label }
        )
        assertEquals(listOf(true, false, false, false, false), plan.tagTokens().map { it.highlighted })
        val expectedTokenIds = plan.tagTokens().mapTo(mutableSetOf()) { it.id }
        assertEquals(expectedTokenIds, annotated.raftMarkdownClickableTags())
        assertEquals(
            listOf(
                RaftColor.BrandYellow,
                RaftColor.AccentPink.copy(alpha = 0.3f),
                RaftColor.AccentCyan.copy(alpha = 0.3f),
                RaftColor.BrandYellow.copy(alpha = 0.4f),
                RaftColor.BrandYellow.copy(alpha = 0.4f)
            ),
            plan.tagTokens().map { token ->
                annotated.raftMarkdownClickableTagInlineBoxBackground(token.id)
            }
        )
        assertTrue(annotated.raftMarkdownInlineContentIds().all { raftInlineGapWidth(it) != null })
    }

    @Test
    fun markdownReferenceTagsUseReactChipColors() {
        assertEquals(RaftColor.AccentPink.copy(alpha = 0.3f), raftMarkdownTagColor(ThreadMarkdownTagKind.Channel))
        assertEquals(RaftColor.AccentCyan.copy(alpha = 0.3f), raftMarkdownTagColor(ThreadMarkdownTagKind.ChannelThread))
        assertEquals(RaftColor.BrandYellow.copy(alpha = 0.4f), raftMarkdownTagColor(ThreadMarkdownTagKind.Task))
        assertEquals(RaftColor.BrandYellow, raftMarkdownTagColor(ThreadMarkdownTagKind.UserMention, highlighted = true))
        assertEquals("channel", RaftRichTextChromeKind.Channel.styleToken.id)
        assertEquals(RaftRichTextColorToken.AccentPink30, RaftRichTextChromeKind.Channel.styleToken.chipFill)
        assertEquals(RaftRichTextColorToken.AccentCyan30, RaftRichTextChromeKind.Thread.styleToken.chipFill)
        assertEquals(RaftRichTextColorToken.BrandYellow40, RaftRichTextChromeKind.Task.styleToken.chipFill)
        assertEquals(RAFT_RICHTEXT_INLINE_CHIP_HORIZONTAL_CHROME_SP, RaftRichTextChromeKind.Channel.styleToken.horizontalChromeSp)

        assertFalse(raftMarkdownTagColor(ThreadMarkdownTagKind.Channel) == RaftColor.BrandYellow)
        assertFalse(raftMarkdownTagColor(ThreadMarkdownTagKind.Channel) == RaftColor.AccentCyan)
        assertFalse(raftMarkdownTagColor(ThreadMarkdownTagKind.ChannelThread) == RaftColor.AccentPink)
        assertFalse(raftMarkdownTagColor(ThreadMarkdownTagKind.Task) == RaftColor.AccentLavender)
    }

    @Test
    fun referenceTagsUseUnderlineAndSelfMentionUsesSoftSignalChip() {
        // React (MentionLink.tsx): task refs, #channels and non-self @mentions all render
        // as the bold-black underline; only the self-mention gets the soft-signal chip.
        val taskStyle = raftMarkdownTagSpanStyle(ThreadMarkdownTagKind.Task)
        val channelStyle = raftMarkdownTagSpanStyle(ThreadMarkdownTagKind.Channel)
        val threadStyle = raftMarkdownTagSpanStyle(ThreadMarkdownTagKind.ChannelThread)
        val selfMentionStyle = raftMarkdownTagSpanStyle(ThreadMarkdownTagKind.UserMention, highlighted = true)
        val ordinaryMentionStyle = raftMarkdownTagSpanStyle(ThreadMarkdownTagKind.UserMention, highlighted = false)

        // CMP switch: upstream SpanStyle has no inlineBoxStyle / textDecorationColor/Thickness/Offset.
        // The tag text style carries colour/font only; the reference underline and the self-mention
        // chip travel as RaftInlineUnderlineStyle / RaftInlineBoxStyle annotations (painted by
        // raftInlineDecorations). Non-self tags lower through the OrdinaryMention chrome token.
        val referenceUnderline =
            raftMarkdownRichTextTagInlineUnderlineStyle(RaftRichTextChromeKind.OrdinaryMention)
        val selfMentionBox = raftMarkdownRichTextTagInlineBoxStyle(RaftRichTextChromeKind.SelfMention)

        assertEquals(14.sp, RaftMarkdownTagFontSize)
        assertEquals(Color.Unspecified, taskStyle.background)
        assertEquals(null, taskStyle.textDecoration)
        assertEquals(RaftColor.TextBlack, taskStyle.color)
        assertEquals(RaftMarkdownTagFontSize, taskStyle.fontSize)

        assertEquals(Color.Unspecified, channelStyle.background)
        assertEquals(null, channelStyle.textDecoration)
        assertEquals(RaftMarkdownTagFontSize, channelStyle.fontSize)

        assertEquals(Color.Unspecified, threadStyle.background)
        assertEquals(null, threadStyle.textDecoration)

        assertEquals(RaftColor.BorderBlack.copy(alpha = 0.3f), referenceUnderline?.color)
        assertEquals(2.sp, referenceUnderline?.thickness)
        assertEquals(2.sp, referenceUnderline?.offset)
        assertEquals(null, raftMarkdownRichTextTagInlineBoxStyle(RaftRichTextChromeKind.OrdinaryMention))

        assertEquals(RaftColor.SoftSignal, RaftColor.BrandYellow)
        // The box paints the chip fill; a span background would cover its border.
        assertEquals(Color.Unspecified, selfMentionStyle.background)
        assertEquals(RaftColor.BrandYellow, selfMentionBox?.backgroundColor)
        assertEquals(RaftColor.BorderBlack, selfMentionBox?.borderColor)
        assertEquals(1.dp, selfMentionBox?.borderWidth)
        assertEquals(4.dp, selfMentionBox?.paddingStart)
        assertEquals(4.dp, selfMentionBox?.paddingEnd)
        assertEquals(2.dp, selfMentionBox?.paddingTop)
        assertEquals(2.dp, selfMentionBox?.paddingBottom)
        assertEquals(2.dp, selfMentionBox?.marginStart)
        assertEquals(2.dp, selfMentionBox?.marginEnd)
        assertEquals(null, raftMarkdownRichTextTagInlineUnderlineStyle(RaftRichTextChromeKind.SelfMention))
        assertEquals(null, selfMentionStyle.textDecoration)
        assertEquals(RaftMarkdownTagFontSize, selfMentionStyle.fontSize)
        assertEquals(null, ordinaryMentionStyle.textDecoration)
        assertEquals(Color.Unspecified, ordinaryMentionStyle.background)
        assertEquals(RaftMarkdownTagFontSize, ordinaryMentionStyle.fontSize)

        val activeMentionStyle = raftMarkdownTagSpanStyle(ThreadMarkdownTagKind.UserMention, active = true)
        assertEquals(null, activeMentionStyle.textDecoration)
        // Active tags reuse the self-mention chip: fill comes from the box, not a span background.
        assertEquals(selfMentionStyle, activeMentionStyle)
        assertEquals(RaftRichTextTextDecorationToken.Underline, RaftRichTextChromeKind.OrdinaryMention.styleToken.textDecoration)
        assertEquals(2f, RaftRichTextChromeKind.OrdinaryMention.styleToken.textDecorationThicknessSp)
        assertEquals(2f, RaftRichTextChromeKind.OrdinaryMention.styleToken.textDecorationOffsetSp)
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
            RaftMarkdownInlineTokenRegistry.plan(
                body,
                highlightedMentions = setOf("artin"),
                isTagValid = validator
            )
        val channelToken = plan.tagTokens().first { it.tag?.kind == ThreadMarkdownTagKind.Channel }
        val taskToken = plan.tagTokens().first { it.tag?.kind == ThreadMarkdownTagKind.Task }
        val annotated =
            buildRaftMarkdownParagraphAnnotatedString(
                content = body,
                annotator =
                    raftMarkdownAnnotator(
                        body,
                        highlightedMentions = setOf("artin"),
                        isTagValid = validator,
                        activeTagKey = threadMarkdownTagActivationKey(channelToken.tag!!),
                        tokenPlan = plan
                    )
            )

        // CMP switch: the active chip fill is painted by the RaftInlineBoxStyle annotation; the span
        // background stays unspecified so it cannot cover the box border.
        assertEquals(Color.Unspecified, annotated.raftMarkdownClickableTagBackground(channelToken.id))
        assertEquals(RaftColor.BrandYellow, annotated.raftMarkdownClickableTagInlineBoxBackground(channelToken.id))
        // The generic inline box owns the task chip fill; LinkAnnotation.background stays unspecified.
        assertEquals(
            Color.Unspecified,
            annotated.raftMarkdownClickableTagBackground(taskToken.id)
        )
        assertEquals(
            RaftColor.BrandYellow.copy(alpha = 0.4f),
            annotated.raftMarkdownClickableTagInlineBoxBackground(taskToken.id)
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
            buildRaftMarkdownParagraphAnnotatedString(
                content = body,
                annotator =
                    raftMarkdownAnnotator(
                        body,
                        highlightedMentions = setOf("artin"),
                        isTagValid = validator
                    )
            )

        assertEquals(
            setOf("slock:5", "slock:16", "slock:43", "slock:55", "slock:80"),
            annotated.raftMarkdownClickableTags()
        )
        assertEquals(
            listOf(
                RaftColor.BrandYellow,
                null,
                RaftColor.AccentPink.copy(alpha = 0.3f),
                RaftColor.AccentCyan.copy(alpha = 0.3f),
                RaftColor.BrandYellow.copy(alpha = 0.4f)
            ),
            listOf("slock:5", "slock:16", "slock:43", "slock:55", "slock:80").map { tag ->
                annotated.raftMarkdownClickableTagInlineBoxBackground(tag)
            }
        )
        assertTrue(annotated.raftMarkdownInlineContentIds().all { raftInlineGapWidth(it) != null })
    }

    @Test
    fun mixedMarkdownKeepsReferenceInlineBoxesOnTheirOwnTextRanges() {
        val body =
            "另外帮一个澄清避免混淆：#31（read-state→Activity，我的）和 @Android-Developer-4 的 " +
                "**task #521（`message:new`→Activity 即时新增，artin 原话\"新消息来了 activity 没刷新\"）" +
                "是两个不同 gap，不是重复——Codex 早前建议的\"#521 判重关闭\"是把两者混了，AD4 已更正。" +
                "#31 复用 versioned read-state fact，#521 是 SharedActivityStore 缺 message:new realtime，两条链路各修各的。"
        val annotated =
            buildRaftMarkdownParagraphAnnotatedString(
                content = body,
                annotator =
                    raftMarkdownAnnotator(
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
                        ?.let { annotated.raftInlineBoxWithin(range.start, range.end) } != null
                }
                .map { range -> annotated.text.substring(range.start, range.end) }

        assertEquals(
            listOf("#31", "@Android-Developer-4", "#521", "#521", "#31", "#521"),
            boxedLinkTexts
        )
        assertFalse(boxedLinkTexts.contains("另外帮一个澄清避免混淆："))
        assertEquals(listOf(inlineCodeSegment("message:new")), annotated.raftInlineCodeMarkerTextSegments())
    }

    @Test
    fun richTextDocumentDrivesRendererChromeAndActions() {
        val body = "Ping @artin in #visual-testing:0211621f for #438 and `#not-ref`"
        val document =
            buildRaftRichTextDocument(
                body,
                RaftRichTextContext(
                    knownChannels = setOf("visual-testing"),
                    knownMentions = setOf("artin"),
                    highlightedMentions = setOf("artin")
                )
            )
        var openedAction: RaftRichTextAction? = null
        val annotated =
            buildRaftMarkdownParagraphAnnotatedString(
                content = document.content,
                annotator =
                    raftMarkdownAnnotator(
                        document = document,
                        onOpenAction = { action -> openedAction = action }
                    )
            )
        val refTokens = document.inlineTokens.filter { it.kind == RaftRichTextInlineKind.RaftRef }
        val inlineCode = document.inlineTokens.single { it.kind == RaftRichTextInlineKind.InlineCode }
        val expectedActionIds = refTokens.mapNotNullTo(mutableSetOf()) { it.actionId }

        assertEquals(setOf("slock:5", "slock:15", "slock:44"), expectedActionIds)
        assertEquals(expectedActionIds, annotated.raftMarkdownClickableTags())
        assertEquals(expectedActionIds, annotated.raftMarkdownClickableLinkTags())
        assertEquals(
            listOf(
                RaftColor.BrandYellow,
                RaftColor.AccentCyan.copy(alpha = 0.3f),
                RaftColor.BrandYellow.copy(alpha = 0.4f)
            ),
            refTokens.map { token ->
                annotated.raftMarkdownClickableTagInlineBoxBackground(token.actionId!!)
            }
        )
        assertEquals("#not-ref", inlineCode.text)
        assertFalse(annotated.raftMarkdownClickableTags().contains(inlineCode.id))

        val taskAction = document.actions.single { it.refKind == RaftRichTextRefKind.Task }
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
            buildRaftMarkdownParagraphAnnotatedString(
                content = body,
                annotator =
                    raftMarkdownAnnotator(
                        body,
                        isTagValid = validator
                    )
            )

        assertEquals(body, annotated.text)
        assertEquals(listOf("@KMP-专家", "#520"), body.threadInlineTagMatches().map { it.tag.label })
        assertEquals(emptySet(), annotated.raftMarkdownClickableTags())
        assertTrue(annotated.raftMarkdownInlineContentIds().isEmpty())
    }

    @Test
    fun channelBeforeProseColonKeepsKnownChannelToken() {
        val body = "Rich fixture for #design: @artin"
        val matches = body.threadInlineTagMatches()
        val channel = matches.single { it.tag.kind == ThreadMarkdownTagKind.Channel }

        assertEquals("#design", channel.tag.label)
        assertEquals("#design", body.substring(channel.range))

        val annotated =
            buildRaftMarkdownParagraphAnnotatedString(
                content = body,
                annotator =
                    raftMarkdownAnnotator(
                        body,
                        highlightedMentions = setOf("artin"),
                        isTagValid =
                            validThreadMarkdownTags(
                                knownChannels = setOf("design"),
                                knownMentions = setOf("artin")
                            )
                    )
            )

        assertEquals(body, annotated.raftTextWithoutInlineGaps())
        assertEquals(setOf("slock:17", "slock:26"), annotated.raftMarkdownClickableTags())
        assertEquals(
            listOf(
                RaftColor.AccentPink.copy(alpha = 0.3f),
                RaftColor.BrandYellow
            ),
            listOf("slock:17", "slock:26").map { tag ->
                annotated.raftMarkdownClickableTagInlineBoxBackground(tag)
            }
        )
    }

    @Test
    fun task25ReferenceScenarioKeepsReactTokenColorsAndBoundaries() {
        val body =
            "Ping @artin in #raft-mobile and #raft-mobile:4752266f for #25; " +
                "ignore #a67dddad #3adea0d5 #34xxx and #missing-channel:4752266f."

        val plan =
            RaftMarkdownInlineTokenRegistry.plan(
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
                RaftColor.BrandYellow,
                RaftColor.AccentPink.copy(alpha = 0.3f),
                RaftColor.AccentCyan.copy(alpha = 0.3f),
                RaftColor.BrandYellow.copy(alpha = 0.4f)
            ),
            tokens.map { token -> raftMarkdownTagColor(token.tag!!.kind, token.highlighted) }
        )
    }

    @Test
    fun referenceChipsUseReactBoldFontFamily() {
        val channelStyle = raftMarkdownTagSpanStyle(ThreadMarkdownTagKind.Channel)
        val richChannelStyle =
            requireNotNull(raftMarkdownRichTextTagLinkStyles(RaftRichTextChromeKind.Channel).style)

        assertEquals(RaftType.ButtonBold.fontFamily, channelStyle.fontFamily)
        assertEquals(RaftType.ButtonBold.fontWeight, channelStyle.fontWeight)
        assertEquals(RaftType.ButtonBold.fontFamily, richChannelStyle.fontFamily)
        assertEquals(RaftType.ButtonBold.fontWeight, richChannelStyle.fontWeight)
        assertEquals(null, richChannelStyle.textDecoration)
        assertEquals(RaftRichTextFontWeightToken.Bold, RaftRichTextChromeKind.Channel.styleToken.fontWeight)

        // CMP switch: the ordinary-mention underline is a RaftInlineUnderlineStyle annotation.
        val ordinaryMention = raftMarkdownTagSpanStyle(ThreadMarkdownTagKind.UserMention)
        assertEquals(null, ordinaryMention.textDecoration)
        assertEquals(
            2.sp,
            raftMarkdownRichTextTagInlineUnderlineStyle(RaftRichTextChromeKind.OrdinaryMention)?.thickness,
        )
    }

    // Product ruling (task #816): message prose keeps the shared product font;
    // only inline-code glyphs use Web's Space Mono typography. Chrome remains
    // a single painter and carries Web's taller inline box.
    @Test
    fun inlineCodeUsesMonoWithoutLeakingIntoBodyAndSingleNeutralChrome() {
        val inlineCodeStyle = raftMarkdownInlineCodeTextStyle(RaftType.Body)
        val inlineCodeStyleFromBoldText =
            raftMarkdownInlineCodeTextStyle(RaftType.Body.withRaftFontWeight(FontWeight.Bold))
        val inlineBox = raftMarkdownInlineBoxStyle(RaftRichTextChromeKind.InlineCode)
        val leadingEdge = raftMarkdownInlineCodeEdgeStyle(leading = true)
        val trailingEdge = raftMarkdownInlineCodeEdgeStyle(leading = false)

        assertEquals(raftMonoFamilyForWeight(FontWeight.Normal), inlineCodeStyle.fontFamily)
        assertEquals(FontWeight.Normal, inlineCodeStyle.fontWeight)
        assertEquals(RaftType.Body.fontSize, inlineCodeStyle.fontSize)
        assertEquals(RaftType.Body.lineHeight, inlineCodeStyle.lineHeight)
        assertEquals(RaftType.Body.letterSpacing, inlineCodeStyle.letterSpacing)
        assertFalse(inlineCodeStyle.fontFamily == raftFontFamilyForWeight(FontWeight.Normal))
        assertEquals(raftMonoFamilyForWeight(FontWeight.Normal), inlineCodeStyleFromBoldText.fontFamily)
        assertEquals(FontWeight.Normal, inlineCodeStyleFromBoldText.fontWeight)
        assertEquals("inlineCode", RaftRichTextChromeKind.InlineCode.styleToken.id)
        assertEquals(RaftRichTextFontFamilyToken.RaftMono, RaftRichTextChromeKind.InlineCode.styleToken.fontFamily)
        assertEquals(RaftRichTextFontWeightToken.Normal, RaftRichTextChromeKind.InlineCode.styleToken.fontWeight)
        assertEquals(null, RaftRichTextChromeKind.InlineCode.styleToken.background)
        assertEquals(RaftRichTextColorToken.Black5, RaftRichTextChromeKind.InlineCode.styleToken.chipFill)
        assertEquals(RaftColor.TextBlack.copy(alpha = 0.05f), inlineBox.backgroundColor)
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
        assertEquals(RaftColor.TextBlack.copy(alpha = 0.05f), leadingEdge.backgroundColor)
        assertEquals(RaftColor.TextBlack.copy(alpha = 0.05f), trailingEdge.backgroundColor)

        val body = "before `effective-family` after"
        val rendered =
            buildRaftMarkdownParagraphAnnotatedString(
                content = body,
                annotator = raftMarkdownAnnotator(body),
                codeSpanStyle = inlineCodeStyle.toSpanStyle(),
            ).withRaftMarkdownSpanFonts(RaftType.Body)
        val codeStart = rendered.text.indexOf("effective-family")
        val codeEnd = codeStart + "effective-family".length
        fun effectiveSpanStyleAt(index: Int): SpanStyle =
            rendered.spanStyles
                .filter { range -> range.start <= index && range.end > index }
                .fold(SpanStyle()) { style, range -> style.merge(range.item) }
        val effectiveAtCode = effectiveSpanStyleAt(codeStart)
        assertEquals(raftMonoFamilyForWeight(FontWeight.Normal), effectiveAtCode.fontFamily)
        assertEquals(FontWeight.Normal, effectiveAtCode.fontWeight)
        assertEquals(RaftType.Body.fontSize * 0.875f, effectiveAtCode.fontSize)
        assertEquals(RaftType.Body.letterSpacing, effectiveAtCode.letterSpacing)
        assertTrue(
            rendered.spanStyles.none { range ->
                range.item.fontFamily == raftMonoFamilyForWeight(FontWeight.Normal) &&
                    (range.start < codeStart || range.end > codeEnd)
            }
        )
        assertFalse(effectiveSpanStyleAt(codeStart - 1).fontFamily == raftMonoFamilyForWeight(FontWeight.Normal))
        assertFalse(effectiveSpanStyleAt(codeEnd).fontFamily == raftMonoFamilyForWeight(FontWeight.Normal))
    }

    @Test
    fun releaseNotesSectionGapClearsInlineCodeChromePaintOverflow() {
        // Web's 18px visible box paints 1dp beyond each Mobile text edge.
        assertFalse(raftMarkdownSectionHeadingGapClearsInlineCodeChrome(0.dp))
        assertTrue(raftMarkdownSectionHeadingGapClearsInlineCodeChrome(8.dp))
    }

    @Test
    fun markdownTextStyleRaisesLineHeightForInlineChips() {
        val style = RaftType.Body.copy(fontSize = 14.sp, lineHeight = 20.sp)

        val resolvedStyle = raftMarkdownTextStyleWithInlineLineHeight(style)
        val typography = raftMarkdownTypography(style)

        assertEquals(22.sp, resolvedStyle.lineHeight)
        assertEquals(22.sp, typography.text.lineHeight)
        assertEquals(22.sp, typography.paragraph.lineHeight)
        assertEquals(22.sp, typography.inlineCode.lineHeight)
        assertEquals(14.sp, typography.inlineCode.fontSize)
        assertEquals(0.sp, typography.inlineCode.letterSpacing)
        assertEquals(RaftType.Body.fontFamily, resolvedStyle.fontFamily)
    }

    @Test
    fun markdownTextStyleKeepsRoomierLineHeight() {
        val style = RaftType.Body.copy(fontSize = 14.sp, lineHeight = 28.sp)

        val resolvedStyle = raftMarkdownTextStyleWithInlineLineHeight(style)
        val typography = raftMarkdownTypography(style)

        assertEquals(28.sp, resolvedStyle.lineHeight)
        assertEquals(28.sp, typography.text.lineHeight)
        assertEquals(28.sp, typography.inlineCode.lineHeight)
    }

    @Test
    fun markdownPerformanceLogsOnlySlowSamplesByDefault() {
        assertFalse(shouldLogRaftMarkdownPerformance(0))
        assertFalse(shouldLogRaftMarkdownPerformance(7))
        assertTrue(shouldLogRaftMarkdownPerformance(8))
    }

    @Test
    fun taggedTextUsesFullRendererPreparse() {
        assertEquals(
            "Ping @artin in #KMP",
            "Ping @artin in #KMP".preparedRaftMarkdownFullRendererContentOrNull()?.sourceContent
        )
        assertEquals(
            "#KMP is a channel reference, not a heading",
            "#KMP is a channel reference, not a heading".preparedRaftMarkdownFullRendererContentOrNull()?.sourceContent
        )
        assertEquals(
            "Plain text only",
            "Plain text only".preparedRaftMarkdownFullRendererContentOrNull()?.sourceContent
        )
    }

    @Test
    fun markdownTypographyKeepsRaftFontsForOhosRichText() {
        val typography = raftMarkdownTypography(RaftType.Body)

        assertEquals(RaftType.Body.fontFamily, typography.text.fontFamily)
        assertEquals(RaftType.Body.fontFamily, typography.paragraph.fontFamily)
        assertEquals(RaftType.Body.fontFamily, typography.textLink.style?.fontFamily)
        assertEquals(expectedLinkBlue, typography.textLink.style?.color)
        assertEquals(TextDecoration.Underline, typography.textLink.style?.textDecoration)
        assertEquals(RaftType.Body.fontWeight, typography.textLink.style?.fontWeight)
        assertEquals(expectedLinkBlue, typography.link.color)
        assertEquals(TextDecoration.Underline, typography.link.textDecoration)
        assertEquals(RaftType.Body.fontWeight, typography.link.fontWeight)
        // web pre `font-mono [font-size:inherit]`: bundled Space Mono at the body size.
        assertEquals(raftMonoFamilyForWeight(FontWeight.Normal), typography.code.fontFamily)
        assertEquals(RaftType.Body.fontSize, typography.code.fontSize)
        assertFalse(typography.code.fontFamily == FontFamily.Monospace)
    }

    @Test
    fun markdownLinksRenderBlueUnderline() {
        val body = "Open [Raft](https://raft.build) now"
        val annotated =
            buildRaftMarkdownParagraphAnnotatedString(
                content = body,
                annotator = raftMarkdownAnnotator(body)
            )
        val link = annotated.getLinkAnnotations(0, annotated.length).single().item as LinkAnnotation.Url
        val linkStyle = link.styles?.style

        assertEquals("Open Raft now", annotated.text)
        assertEquals("https://raft.build", link.url)
        assertEquals(expectedLinkBlue, linkStyle?.color)
        assertEquals(TextDecoration.Underline, linkStyle?.textDecoration)
        assertEquals(RaftType.Body.fontWeight, linkStyle?.fontWeight)
    }

    @Ignore // TODO(cmp-switch): markdown links lost the 2sp underline thickness/offset (upstream
    // SpanStyle has none; see raftMarkdownLinkSpanStyle). Re-enable once links carry a
    // RaftInlineUnderlineStyle annotation like reference tags do.
    @Test
    fun markdownLinksUseTwoSpUnderlineThicknessAndOffset() {
        val body = "Open [Raft](https://raft.build) now"
        val annotated =
            buildRaftMarkdownParagraphAnnotatedString(
                content = body,
                annotator = raftMarkdownAnnotator(body)
            )
        val link = annotated.getLinkAnnotations(0, annotated.length).single()
        val underline =
            annotated.raftInlineUnderlineRanges()
                .firstOrNull { it.start >= link.start && it.end <= link.end }
                ?.item

        assertEquals(2.sp, underline?.thickness)
        assertEquals(2.sp, underline?.offset)
    }

    @Test
    fun markdownStrongSpansUseWeightSpecificRaftFontWithoutSyntheticWeight() {
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
            ).withRaftMarkdownSpanFonts(RaftType.Body)

        val strongFontSpan = annotated.spanStyles.last { it.start == 7 && it.end == 11 }

        assertEquals(RaftType.ButtonBold.fontFamily, strongFontSpan.item.fontFamily)
        assertEquals(RaftType.ButtonBold.fontWeight, strongFontSpan.item.fontWeight)
    }

    @Test
    fun fullRendererKeepsCodeBoldAndClickableTagsTogether() {
        val body = "Ping @artin with `mimo` and **bold** in #markdown专修"
        val tokenPlan =
            RaftMarkdownInlineTokenRegistry.plan(
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
            raftMarkdownAnnotator(
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
        val annotated = buildRaftMarkdownParagraphAnnotatedString(body, annotator)
            .withRaftMarkdownSpanFonts(RaftType.Body)
        val expectedTagIds = tokenPlan.tagTokens().mapTo(mutableSetOf()) { it.id }

        assertEquals("Ping @artin with ${inlineCodeSegment("mimo")} and bold in #markdown专修", annotated.raftTextWithoutInlineGaps())
        assertEquals(
            setOf("slock-message-mixed:5", "slock-message-mixed:40"),
            annotated.raftMarkdownClickableTags()
        )
        assertTrue(annotated.raftMarkdownInlineContentIds().all { raftInlineGapWidth(it) != null })
        assertTrue(annotator.inlineContent?.invoke().orEmpty().isEmpty())
        val boldStart = annotated.text.indexOf("bold")
        val boldSpan = annotated.spanStyles.last { it.start == boldStart && it.end == boldStart + "bold".length }
        assertEquals(RaftType.ButtonBold.fontFamily, boldSpan.item.fontFamily)
        assertEquals(RaftType.ButtonBold.fontWeight, boldSpan.item.fontWeight)
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
            ).withRaftMarkdownSpanFonts(RaftType.Body)

        val codeFontSpan = annotated.spanStyles.last { it.start == 7 && it.end == 11 }

        assertEquals(raftMonoFamilyForWeight(FontWeight.Normal), codeFontSpan.item.fontFamily)
        assertFalse(codeFontSpan.item.fontFamily == FontFamily.Monospace)
    }

    @Test
    fun inlineTagWidthsDoNotReserveLargeTrailingPadding() {
        val shortMentionWidth =
            raftMarkdownInlineTagWidthSp(
                "@mention",
                ThreadMarkdownTagKind.UserMention
            ).value
        val mentionWidth =
            raftMarkdownInlineTagWidthSp(
                "@Android-Developer-2",
                ThreadMarkdownTagKind.UserMention
            ).value
        val longAgentMentionWidth =
            raftMarkdownInlineTagWidthSp(
                "@Codex-KMP-Developer",
                ThreadMarkdownTagKind.UserMention
            ).value
        val qaMentionWidth =
            raftMarkdownInlineTagWidthSp(
                "@Pi-QA-Smoke",
                ThreadMarkdownTagKind.UserMention
            ).value
        val selfMentionWidth =
            raftMarkdownInlineTagWidthSp(
                "@artin",
                ThreadMarkdownTagKind.UserMention,
                highlighted = true
            ).value
        val channelWidth =
            raftMarkdownInlineTagWidthSp(
                "#markdown专修",
                ThreadMarkdownTagKind.Channel
            ).value
        val threadRefWidth =
            raftMarkdownInlineTagWidthSp(
                "#markdown专修:692d9e88",
                ThreadMarkdownTagKind.ChannelThread
            ).value
        val taskWidth = raftMarkdownInlineChipWidthSp("#405").value

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
            raftMarkdownAnnotator(
                content = body,
                highlightedMentions = setOf("artin"),
                isTagValid = validThreadMarkdownTags(knownMentions = setOf("artin"))
            )

        assertTrue(annotator.inlineContent?.invoke().orEmpty().isEmpty())

        val annotated = buildRaftMarkdownParagraphAnnotatedString(body, annotator)
        assertEquals("Ping @artin for #405 and ${inlineCodeSegment("mimo")}", annotated.raftTextWithoutInlineGaps())
        assertEquals(setOf("slock:5", "slock:16"), annotated.raftMarkdownClickableTags())
        assertTrue(annotated.raftMarkdownInlineContentIds().all { raftInlineGapWidth(it) != null })
        assertEquals(listOf(inlineCodeSegment("mimo")), annotated.raftInlineCodeMarkerTextSegments())
    }

    @Test
    fun richMarkdownFullRendererKeepsTagsWhileCodeUsesMarkdownCodeSpan() {
        val body = "**Bold** text for @artin in #markdown专修 with `code`"
        val annotator =
            raftMarkdownAnnotator(
                content = body,
                isTagValid =
                    validThreadMarkdownTags(
                        knownChannels = setOf("markdown专修"),
                        knownMentions = setOf("artin")
                    )
            )

        assertTrue(annotator.inlineContent?.invoke().orEmpty().isEmpty())

        val annotated = buildRaftMarkdownParagraphAnnotatedString(body, annotator)

        assertEquals("Bold text for @artin in #markdown专修 with ${inlineCodeSegment("code")}", annotated.raftTextWithoutInlineGaps())
        assertEquals(setOf("slock:18", "slock:28"), annotated.raftMarkdownClickableTags())
        assertTrue(annotated.raftMarkdownInlineContentIds().all { raftInlineGapWidth(it) != null })
        assertEquals(listOf(inlineCodeSegment("code")), annotated.raftInlineCodeMarkerTextSegments())
    }

    @Test
    fun inlineTagPlanFindsTokensForTextNodeRange() {
        val body = "Ping @artin for #405 and #markdown专修:7933cf78"
        val plan =
            RaftMarkdownInlineTokenRegistry.plan(
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
    fun inlineCodeTokensAreNotRegisteredAsRaftInlineContent() {
        val body = "`mimo` `very-long-inline-code-value-that-should-wrap-normally`"
        val plan = RaftMarkdownInlineTokenRegistry.plan(body)
        val inlineContent =
            raftMarkdownAnnotator(
                content = body
            ).inlineContent?.invoke().orEmpty()

        assertEquals(2, plan.inlineCodeMatchCount())
        assertTrue(inlineContent.isEmpty())
    }

    @Test
    fun channelThreadReferencesRenderAsSingleInlineChip() {
        val matches = "See #markdown专修:7933cf78 and #general:abc123 in chat".threadInlineTagMatches()
        val annotated =
            buildRaftMarkdownParagraphAnnotatedString(
                content = "See #markdown专修:7933cf78 and #general:abc123 in chat",
                annotator =
                    raftMarkdownAnnotator(
                        content = "See #markdown专修:7933cf78 and #general:abc123 in chat",
                        isTagValid = validThreadMarkdownTags(knownChannels = setOf("markdown专修", "general"))
                    )
            )

        assertEquals(2, matches.size)
        assertEquals(ThreadMarkdownTagKind.ChannelThread, matches[0].tag.kind)
        assertEquals("#markdown专修:7933cf78", matches[0].tag.label)
        assertEquals("markdown专修", matches[0].tag.channelName)
        assertEquals("7933cf78", matches[0].tag.threadShortId)
        assertEquals(setOf("slock:4", "slock:29"), annotated.raftMarkdownClickableTags())
        assertEquals(setOf("slock:4", "slock:29"), annotated.raftMarkdownClickableLinkTags())
    }

    @Test
    fun fullMarkdownRendererKeepsChannelThreadClickableTags() {
        val body = "Final #markdown专修:9579b48a @artin #markdown专修 #37."
        val isTagValid =
            validThreadMarkdownTags(
                knownChannels = setOf("markdown专修"),
                knownMentions = setOf("artin")
            )
        val plan = RaftMarkdownInlineTokenRegistry.plan(body, isTagValid = isTagValid)
        val annotated =
            buildRaftMarkdownParagraphAnnotatedString(
                content = body,
                annotator =
                    raftMarkdownAnnotator(
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
            annotated.raftMarkdownClickableTags()
        )
        assertEquals(
            setOf("slock:6", "slock:27", "slock:34", "slock:46"),
            annotated.raftMarkdownClickableLinkTags()
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
            RaftMarkdownInlineTokenRegistry.plan(
                body,
                isTagValid = isTagValid,
                inlineContentScope = "message-a"
            )
        val secondPlan =
            RaftMarkdownInlineTokenRegistry.plan(
                body,
                isTagValid = isTagValid,
                inlineContentScope = "message-b"
            )
        val firstAnnotated =
            buildRaftMarkdownParagraphAnnotatedString(
                content = body,
                annotator =
                    raftMarkdownAnnotator(
                        content = body,
                        isTagValid = isTagValid,
                        inlineContentScope = "message-a",
                        tokenPlan = firstPlan
                    )
            )
        val secondAnnotated =
            buildRaftMarkdownParagraphAnnotatedString(
                content = body,
                annotator =
                    raftMarkdownAnnotator(
                        content = body,
                        isTagValid = isTagValid,
                        inlineContentScope = "message-b",
                        tokenPlan = secondPlan
                    )
            )

        assertEquals(
            setOf("slock-message-a:5", "slock-message-a:15"),
            firstAnnotated.raftMarkdownClickableTags()
        )
        assertEquals(
            setOf("slock-message-b:5", "slock-message-b:15"),
            secondAnnotated.raftMarkdownClickableTags()
        )
        assertFalse(firstPlan.signature == secondPlan.signature)
    }

    @Test
    fun fiftyLongRowsOnlyRebuildPlansWhoseOwnMentionValidityChanges() {
        val planner = RaftMarkdownInlineTokenPlanner(maxEntries = 256)
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
        val planner = RaftMarkdownInlineTokenPlanner(maxEntries = 256)
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
            buildRaftMarkdownParagraphAnnotatedString(
                content = bodies[0],
                annotator =
                    raftMarkdownAnnotator(
                        content = bodies[0],
                        isTagValid = validator,
                        resolveTagLabel = initialResolver,
                        tokenPlan = initialPlans[0]
                    )
            )
        var openedBy = ""
        var currentHandler: (ThreadMarkdownTag) -> Unit = { openedBy = "old:${it.label}" }
        val hydratedAnnotated =
            buildRaftMarkdownParagraphAnnotatedString(
                content = bodies[0],
                annotator =
                    raftMarkdownAnnotator(
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
            RaftMarkdownInlineTokenRegistry.plan(
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
            RaftMarkdownInlineTokenRegistry.plan(
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
            RaftMarkdownInlineTokenRegistry.plan(
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
            RaftMarkdownInlineTokenRegistry.plan(
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
        val annotated = buildRaftMarkdownParagraphAnnotatedString(body)
        val links = annotated.getLinkAnnotations(0, annotated.length)
        // The task chip starts after its reserved leading gap (placeholder + word joiner).
        assertEquals(
            listOf("Url:0..32", "Clickable:45..50"),
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

        assertEquals("#1487 feat(search): pin entities then task #1487", annotated.raftTextWithoutInlineGaps())
        assertEquals("#1487 feat(search): pin entities", annotated.text.substring(external.start, external.end))
        assertEquals("#1487", annotated.text.substring(raft.start, raft.end))
        assertTrue(raft.start >= external.end)
        assertEquals(setOf("slock:${body.lastIndexOf("#1487")}"), annotated.raftMarkdownClickableTags())
    }

    @Test
    fun mentionInsideLinkStaysOwnedWhileStandaloneSiblingRemainsRaftAction() {
        val body = "[ping @artin](https://example.test) and outside @artin."
        val validator = validThreadMarkdownTags(knownMentions = setOf("artin"))
        val matches = body.threadInlineTagMatches()
        val annotated =
            buildRaftMarkdownParagraphAnnotatedString(
                content = body,
                annotator = raftMarkdownAnnotator(content = body, isTagValid = validator),
            )
        val links = annotated.getLinkAnnotations(0, annotated.length)
        val external = links.single { it.item is LinkAnnotation.Url }
        val standalone = links.single { it.item is LinkAnnotation.Clickable }

        assertEquals(listOf(body.lastIndexOf("@artin")), matches.map { it.range.first })
        assertEquals("ping @artin", annotated.text.substring(external.start, external.end))
        assertEquals("@artin", annotated.text.substring(standalone.start, standalone.end))
        assertTrue(standalone.start >= external.end)
        assertEquals(setOf("slock:${body.lastIndexOf("@artin")}"), annotated.raftMarkdownClickableTags())
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
            RaftMarkdownInlineTokenRegistry.plan(
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
            buildRaftMarkdownParagraphAnnotatedString(
                content = "Use `#markdown专修:7933cf78` outside #markdown专修:7933cf78",
                annotator =
                    raftMarkdownAnnotator(
                        content = "Use `#markdown专修:7933cf78` outside #markdown专修:7933cf78",
                        isTagValid = validThreadMarkdownTags(knownChannels = setOf("markdown专修"))
                    )
            )
        val clickableTags = annotated.raftMarkdownClickableTags()

        assertFalse(clickableTags.contains("slock:5"))
        assertTrue(clickableTags.contains("slock:35"))
    }

    @Test
    fun rawMarkdownUrlsKeepClickListener() {
        var openedUrl: String? = null
        val annotated =
            buildRaftMarkdownParagraphAnnotatedString(
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
            buildRaftMarkdownParagraphAnnotatedString(
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

    private fun renderRaftMarkdownParagraphText(content: String): String {
        return buildRaftMarkdownParagraphAnnotatedString(content).text
    }

    private fun renderRaftMarkdownListItemTexts(content: String): List<String> {
        return renderRaftMarkdownListItemAnnotatedStrings(
            content = content,
            annotator = raftMarkdownAnnotator(content)
        ).map { it.text }
    }

    private fun renderRaftMarkdownListItemAnnotatedStrings(
        content: String,
        annotator: MarkdownAnnotator
    ): List<AnnotatedString> {
        val parserContent = MarkdownParserContent.from(content).parserContent
        val parsedTree = MarkdownParser(RaftGFMFlavourDescriptor()).buildMarkdownTreeFromString(parserContent)
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

    private fun AnnotatedString.raftMarkdownClickableTags(): Set<String> =
        getStringAnnotations(RaftMarkdownTagAnnotationTag, 0, length)
            .map { it.item }
            .toSet()

    private fun AnnotatedString.raftMarkdownClickableLinkTags(): Set<String> =
        getLinkAnnotations(0, length)
            .mapNotNull { (it.item as? LinkAnnotation.Clickable)?.tag }
            .toSet()

    private fun AnnotatedString.raftMarkdownClickableTagBackground(tag: String): Color? =
        getLinkAnnotations(0, length)
            .firstNotNullOfOrNull { range ->
                (range.item as? LinkAnnotation.Clickable)
                    ?.takeIf { it.tag == tag }
                    ?.styles
                    ?.style
                    ?.background
            }

    private fun AnnotatedString.raftMarkdownClickableTagInlineBoxBackground(tag: String): Color? =
        getLinkAnnotations(0, length)
            .firstNotNullOfOrNull { range ->
                (range.item as? LinkAnnotation.Clickable)
                    ?.takeIf { it.tag == tag }
                    ?.let { raftInlineBoxWithin(range.start, range.end) }
                    ?.backgroundColor
            }

    // CMP switch: Kuikly carried the chip box on SpanStyle.inlineBoxStyle; upstream Compose carries
    // it as a RaftInlineBoxStyle annotation nested inside the link range.
    private fun AnnotatedString.raftInlineBoxWithin(start: Int, end: Int): RaftInlineBoxStyle? =
        raftInlineBoxRanges().firstOrNull { it.start >= start && it.end <= end && it.start < it.end }?.item

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
                ThreadMarkdownTagKind.DirectMessage ->
                    tag.directMessagePeerName.orEmpty().trim().lowercase() in directMessagePeerKeys
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

    private fun buildRaftMarkdownParagraphAnnotatedString(
        content: String,
        linkInteractionListener: LinkInteractionListener? = null
    ): AnnotatedString {
        return buildRaftMarkdownParagraphAnnotatedString(
            content = content,
            annotator = raftMarkdownAnnotator(content),
            linkInteractionListener = linkInteractionListener
        )
    }

    private fun buildRaftMarkdownParagraphAnnotatedString(
        content: String,
        annotator: MarkdownAnnotator,
        linkInteractionListener: LinkInteractionListener? = null,
        codeSpanStyle: SpanStyle = SpanStyle(),
    ): AnnotatedString {
        val parserContent = MarkdownParserContent.from(content).parserContent
        val parsedTree = MarkdownParser(RaftGFMFlavourDescriptor()).buildMarkdownTreeFromString(parserContent)
        val paragraph = parsedTree.children.first { it.type == MarkdownElementTypes.PARAGRAPH }
        val settings =
            DefaultAnnotatorSettings(
                linkTextSpanStyle = TextLinkStyles(style = raftMarkdownLinkSpanStyle(RaftType.Body)),
                codeSpanStyle = codeSpanStyle,
                annotator = annotator,
                linkInteractionListener = linkInteractionListener
        )
        return buildAnnotatedString {
            buildMarkdownAnnotatedString(parserContent, paragraph, settings)
        }
    }

    private fun AnnotatedString.raftInlineCodeMarkerTextSegments(): List<String> =
        getStringAnnotations(raftInlineCodeAnnotationTag, start = 0, end = length).map { range ->
            text.substring(range.start, range.end)
        }

    private fun AnnotatedString.raftInlineCodeTrailingMarginSegments(): List<String> =
        getStringAnnotations(raftInlineCodeTrailingMarginAnnotationTag, start = 0, end = length).map { range ->
            text.substring(range.start, range.end)
        }

    private fun assertInlineCodeEdgeGraphemes(
        code: String,
        expectedLeading: String,
        expectedTrailing: String,
    ) {
        assertEquals(expectedLeading.length, build.raft.app.raftFirstComposedCharacterEnd(code))
        val annotated = buildRaftMarkdownParagraphAnnotatedString("`$code`")
        val boxRanges =
            annotated.raftInlineBoxRanges().filter { range ->
                range.item.paddingStart > 0.dp || range.item.paddingEnd > 0.dp
            }
        val glyphBackgroundRanges =
            annotated.spanStyles.filter { range ->
                range.item.background != Color.Unspecified &&
                    range.start < annotated.length && range.end > 0
            }
        val firstClusterBoundary = code.indexOf(expectedLeading) + expectedLeading.length
        val splitRange =
            annotated.spanStyles.first { range ->
                range.start == 0 && range.end == firstClusterBoundary
            }

        assertEquals(code, annotated.text)
        assertEquals(1, boxRanges.size)
        assertTrue(glyphBackgroundRanges.isEmpty())
        assertEquals(code, annotated.text.substring(boxRanges.single().start, boxRanges.single().end))
        assertEquals(expectedLeading, annotated.text.substring(splitRange.start, splitRange.end))
        assertTrue(annotated.text.endsWith(expectedTrailing))
        assertEquals(
            RaftColor.TextBlack.copy(alpha = 0.05f),
            boxRanges.single().item.backgroundColor,
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
