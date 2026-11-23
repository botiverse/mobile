package build.raft.app.markdown

import build.raft.app.raftMarkdownMentionSpans
import build.raft.app.RaftColor
import build.raft.app.RaftType
import build.raft.app.raftInlineCodeCharWrapBridgeThreshold
import build.raft.app.raftInlineCodeUsesEdgePaddingBridge
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tencent.kuiklybase.markdown.annotator.DefaultAnnotatorSettings
import com.tencent.kuiklybase.markdown.annotator.buildMarkdownAnnotatedString
import com.tencent.kuiklybase.markdown.model.MarkdownAnnotator
import com.tencent.kuiklybase.markdown.model.MarkdownParserContent
import com.tencent.kuiklybase.markdown.model.RaftGFMFlavourDescriptor
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.parser.MarkdownParser
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RaftMarkdownTest {
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
    fun raftMessagePermalinkUsesGenericInlineBoxChip() {
        val url = "https://app.raft.build/s/mobile/channel/6cb4981e-00c7-4076-81b0-0d46c208c132?msg=3d6b824f-d8c1-420e-8a04-7d21fee6fb1f"
        val source =
            buildAnnotatedString {
                append("See ")
                withLink(LinkAnnotation.Url(url)) { append(url) }
                append(" now")
            }

        val resolved =
            source.withRaftMessagePermalinkChips { target ->
                "proj-server-management".takeIf { target.channelId.value == "6cb4981e-00c7-4076-81b0-0d46c208c132" }
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
        // web PermalinkReferenceChip (variant="link"): `bg-info-soft`, no edge, sticker radius.
        assertEquals(RaftColor.InfoSoft, chipBox?.backgroundColor)
        assertEquals(0.dp, chipBox?.borderWidth)
        assertEquals(4.dp, chipBox?.cornerRadius)
        assertEquals(4.5.dp, chipBox?.paddingStart)
        // A chip link is never underlined: an explicit None in every state (artin 10-29).
        assertEquals(TextDecoration.None, link.styles?.style?.textDecoration)
        // A chip is not a web link: no link underline over it.
        assertTrue(resolved.withRaftMarkdownLinkUnderlines().raftInlineUnderlineRanges().isEmpty())
    }

    @Test
    fun externalAndContentlessRaftLinksRemainOrdinaryLinks() {
        val external = "https://example.com/page"
        val contentless = "https://app.raft.build/s/mobile/channel/6cb4981e-00c7-4076-81b0-0d46c208c132"
        val source =
            buildAnnotatedString {
                withLink(LinkAnnotation.Url(external)) { append("external") }
                append(" ")
                withLink(LinkAnnotation.Url(contentless)) { append("channel") }
            }

        assertSame(source, source.withRaftMessagePermalinkChips())
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
    fun inlineCodePreservesExactCommandTextWithEdgeChrome() {
        val command = "go test ./..."
        val body = "Run `$command` before review"
        val annotated =
            buildRaftMarkdownParagraphAnnotatedString(
                content = body,
                annotator = raftMarkdownAnnotator(body)
            )

        assertEquals("Run ${inlineCodeSegment(command)} before review", annotated.raftTextWithoutInlineGaps())
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
        val annotated =
            buildRaftMarkdownParagraphAnnotatedString(
                content = body,
                annotator = raftMarkdownAnnotator(content = body, isTagValid = validThreadMarkdownTags(knownTaskNumbers = setOf(15)))
            )

        assertEquals("Ping @not-a-member in #not-a-channel for #15", annotated.raftTextWithoutInlineGaps())
        assertEquals(setOf("slock:41"), annotated.raftMarkdownClickableTags())
        // No tag context knows no task either: the bare `#15` is plain too (Web MessageItem).
        assertTrue(buildRaftMarkdownParagraphAnnotatedString(body).raftMarkdownClickableTags().isEmpty())
    }

    @Test
    fun channelRefGluedToCjkTextIsAChipOnlyForAKnownChannel() {
        // artin's message: no space between `去` and `#`. Web renders the chip.
        val body = "这个组件样式很怪啊，你可以写完之后去#proj-raft-ui 写一下，或者a#nope"
        val annotated =
            buildRaftMarkdownParagraphAnnotatedString(
                content = body,
                annotator =
                    raftMarkdownAnnotator(
                        content = body,
                        isTagValid = validThreadMarkdownTags(knownChannels = setOf("proj-raft-ui"))
                    )
            )
        val matches = body.threadInlineTagMatches()

        assertEquals(listOf("#proj-raft-ui", "#nope"), matches.map { it.tag.label })
        assertEquals(ThreadMarkdownTagKind.Channel, matches[0].tag.kind)
        assertEquals("proj-raft-ui", matches[0].tag.channelName)
        assertEquals(setOf("slock:${body.indexOf('#')}"), annotated.raftMarkdownClickableTags())
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
    fun markdownLinksOwnReferenceSemanticsInsideTheirLabelsAndDestinations() {
        val body =
            "[#1487 feat(search): pin entities](https://github.com/botiverse/mobile/pull/1487#discussion) " +
                "then task #1487"

        val matches = body.threadInlineTagMatches()

        assertEquals(listOf("#1487"), matches.map { it.tag.label })
        assertEquals(body.lastIndexOf("#1487"), matches.single().range.first)
    }

    @Test
    fun selfMentionChipPaddingTapOpensTheMentionNotTheBody() {
        // task #wg-mobile:#9: the sticker's painted padding is reserved by gap placeholders outside
        // the mention's link; a tap there used to fall through to the body and open the thread.
        val body = "Captured the Android visual artifact for\n@artin and queued React parity."
        var mentionOpens = 0
        var bodyClicks = 0
        val annotated =
            buildRaftMarkdownParagraphAnnotatedString(
                content = body,
                annotator =
                    raftMarkdownAnnotator(
                        content = body,
                        highlightedMentions = setOf("artin"),
                        isTagValid = validThreadMarkdownTags(knownMentions = setOf("artin")),
                        onOpenTag = { mentionOpens++ },
                    ),
            )
        val mention = annotated.getLinkAnnotations(0, annotated.length).single { it.item is LinkAnnotation.Clickable }
        assertEquals("@artin", annotated.text.substring(mention.start, mention.end))
        val chipGaps =
            annotated.getStringAnnotations("androidx.compose.foundation.text.inlineContent", 0, annotated.length)
                .filter { it.item.startsWith(RaftInlineGapInlineIdPrefix) }
                .filter { gap -> gap.end == mention.start - 1 || gap.start == mention.end + 1 }
        assertEquals(2, chipGaps.size, "the sticker reserves its padding on both sides")

        val resolved = annotated.withRaftInlineChipGapLinks().withRaftMarkdownBodyClick { bodyClicks++ }

        val resolvedLinks = resolved.getLinkAnnotations(0, resolved.length)
        // The chip's own link (range and styles) is untouched.
        assertTrue(resolvedLinks.any { it.start == mention.start && it.end == mention.end && it.item == mention.item })
        chipGaps.forEach { gap ->
            val covering = resolvedLinks.filter { it.start <= gap.start && gap.end <= it.end }
            val link = covering.single().item
            link.linkInteractionListener?.onClick(link)
        }
        assertEquals(2, mentionOpens, "each painted padding side opens the mention")
        assertEquals(0, bodyClicks, "a tap on the chip must not reach the body (thread open)")
        // Text around the chip still opens the body.
        val plain = resolvedLinks.single { it.start == 0 }.item
        plain.linkInteractionListener?.onClick(plain)
        assertEquals(1, bodyClicks)
        assertEquals(2, mentionOpens)
    }

    @Test
    fun markdownLinkProtectionHandlesEscapesAndNestedParentheses() {
        val body =
            "[PR #42 with \\] label](https://example.test/path_(preview)#fragment) outside #43"

        assertEquals(listOf("#43"), body.threadInlineTagMatches().map { it.tag.label })
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
    fun mentionsCarryTheirLabelForTheProfilePreviewCard() {
        // Only the @mention's words carry the label the preview card resolves; a channel ref doesn't.
        val content = "Ask @ada in #general"
        val annotated = buildRaftMarkdownParagraphAnnotatedString(content, raftMarkdownAnnotator(content, isTagValid = { true }))
        val spans = annotated.raftMarkdownMentionSpans()

        assertEquals(listOf("@ada"), spans.map { it.label })
        val span = spans.single()
        assertEquals("@ada", annotated.text.substring(span.start, span.end))
        // The mention's own click (it opens the profile; the card has no button) covers the same words.
        assertTrue(
            annotated.getLinkAnnotations(span.start, span.end).any { link ->
                link.item is LinkAnnotation.Clickable && link.start <= span.start && span.end <= link.end
            }
        )
    }

    private fun renderRaftMarkdownParagraphText(content: String): String {
        return buildRaftMarkdownParagraphAnnotatedString(content).text
    }

    private fun renderRaftMarkdownListItemTexts(content: String): List<String> {
        return renderRaftMarkdownListItemAnnotatedStrings(
            content = content,
            annotator = raftMarkdownAnnotator(content)
        ).map { it.raftTextWithoutInlineGaps() }
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
                ThreadMarkdownTagKind.Message ->
                    tag.directMessagePeerName?.let { it.trim().lowercase() in directMessagePeerKeys }
                        ?: (tag.channelName.orEmpty().trim().lowercase() in channelKeys)
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
            // The code as written: without its reserved `px-1` gaps and wrap marks.
            subSequence(range.start, range.end).raftTextWithoutInlineGaps()
        }

    private fun AnnotatedString.raftInlineCodeTrailingMarginSegments(): List<String> =
        getStringAnnotations(raftInlineCodeTrailingMarginAnnotationTag, start = 0, end = length).map { range ->
            text.substring(range.start, range.end)
        }

    // Mirrors the CODE_SPAN emission (AnnotatedStringKtx): edge-only boxes
    // provide horizontal chrome without making the code run atomic.
    private fun inlineCodeSegment(code: String): String =
        code
}
