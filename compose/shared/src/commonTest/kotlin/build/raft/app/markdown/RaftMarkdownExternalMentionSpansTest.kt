package build.raft.app.markdown

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import build.raft.app.RaftType
import build.raft.app.members.RaftMarkdownExternalMentionAnnotationTag
import build.raft.app.members.RaftMarkdownMentionAnnotationTag
import build.raft.app.members.decodeRaftExternalMentionAnnotation
import build.raft.app.members.raftMarkdownMentionSpans
import build.raft.app.members.toExternalPreviewAuthor
import build.raft.app.model.ChannelExternalMemberProfile
import build.raft.app.model.ExternalMentionSpan
import com.tencent.kuiklybase.markdown.annotator.DefaultAnnotatorSettings
import com.tencent.kuiklybase.markdown.annotator.buildMarkdownAnnotatedString
import com.tencent.kuiklybase.markdown.model.MarkdownParserContent
import com.tencent.kuiklybase.markdown.model.RaftGFMFlavourDescriptor
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.parser.MarkdownParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RaftMarkdownExternalMentionSpansTest {

    private data class RenderedExternalChip(
        val text: String,
        val projectionId: String,
        val actionId: String,
    )

    private fun spanOf(
        content: String,
        label: String,
        id: String,
        nth: Int = 0,
    ): ExternalMentionSpan {
        var start = -1
        for (seen in 0..nth) {
            start = content.indexOf(label, start + 1)
        }
        assertTrue(start >= 0, "Expected $label #$nth in $content")
        return ExternalMentionSpan(id = id, label = label, start = start, end = start + label.length)
    }

    private fun renderBodyAnnotated(
        rawBody: String,
        spans: List<ExternalMentionSpan>,
        isTagValid: (ThreadMarkdownTag) -> Boolean = ::defaultRaftMarkdownTagValidity,
        resolveTagLabel: ((ThreadMarkdownTag) -> String)? = null,
        onOpenTag: (ThreadMarkdownTag) -> Unit = {},
    ): AnnotatedString {
        val normalized = rawBody.normalizeThreadMarkdownBody()
        val parserContent = MarkdownParserContent.from(normalized).parserContent
        val annotator =
            raftMarkdownAnnotator(
                content = parserContent,
                isTagValid = isTagValid,
                resolveTagLabel = resolveTagLabel,
                onOpenTag = onOpenTag,
                rawBody = rawBody,
                normalizedBody = normalized,
                externalMentionSpans = spans,
            )
        val tree = MarkdownParser(RaftGFMFlavourDescriptor()).buildMarkdownTreeFromString(parserContent)
        val settings =
            DefaultAnnotatorSettings(
                linkTextSpanStyle = TextLinkStyles(style = raftMarkdownLinkSpanStyle(RaftType.Body)),
                codeSpanStyle = SpanStyle(),
                annotator = annotator,
                linkInteractionListener = null,
            )
        return buildAnnotatedString {
            fun appendBlock(node: ASTNode) {
                when (node.type) {
                    MarkdownElementTypes.PARAGRAPH,
                    MarkdownTokenTypes.ATX_CONTENT -> buildMarkdownAnnotatedString(parserContent, node, settings)
                    MarkdownElementTypes.CODE_FENCE,
                    MarkdownElementTypes.CODE_BLOCK -> Unit
                    else -> node.children.forEach(::appendBlock)
                }
            }
            tree.children.forEach(::appendBlock)
        }
    }

    private fun AnnotatedString.externalChips(): List<RenderedExternalChip> =
        getStringAnnotations(RaftMarkdownExternalMentionAnnotationTag, 0, length)
            .sortedBy { it.start }
            .map { range ->
                val decoded = assertNotNull(decodeRaftExternalMentionAnnotation(range.item))
                RenderedExternalChip(
                    text = subSequence(range.start, range.end).raftTextWithoutInlineGaps(),
                    projectionId = decoded.projectionId,
                    actionId = decoded.actionId,
                )
            }

    @Test
    fun plainSlackHandleChipsOnlyAtRecordedSpanNotHandTypedCopy() {
        val content = "+slack:tygg typed, +slack:tygg picked"
        val annotated = renderBodyAnnotated(content, listOf(spanOf(content, "+slack:tygg", "proj-tygg", nth = 1)))

        assertEquals(
            listOf(RenderedExternalChip("+slack:tygg", "proj-tygg", annotated.externalChips().single().actionId)),
            annotated.externalChips(),
        )
        assertTrue(annotated.text.startsWith("+slack:tygg typed, "))
        assertTrue(renderBodyAnnotated("hello +slack:tygg and +slack:\"Bob Smith\"", emptyList()).externalChips().isEmpty())
    }

    @Test
    fun quotedDisplayNameAndDuplicateDisplayNamesChipWithDistinctProjectionIds() {
        val content = "+slack:\"Sam Lee\" and +slack:\"Sam Lee\""
        val annotated =
            renderBodyAnnotated(
                content,
                listOf(
                    spanOf(content, "+slack:\"Sam Lee\"", "proj-sam-1", nth = 0),
                    spanOf(content, "+slack:\"Sam Lee\"", "proj-sam-2", nth = 1),
                ),
            )

        assertEquals(
            listOf("+slack:\"Sam Lee\"" to "proj-sam-1", "+slack:\"Sam Lee\"" to "proj-sam-2"),
            annotated.externalChips().map { it.text to it.projectionId },
        )
    }

    @Test
    fun utf16OffsetsWithEmojiAndCjkCharactersBeforeSpan() {
        val content = "\uD83D\uDE00\u4E2D +slack:tygg"
        val span = spanOf(content, "+slack:tygg", "proj-tygg")
        assertEquals(4, span.start)
        assertEquals(15, span.end)

        val annotated = renderBodyAnnotated(content, listOf(span))
        assertEquals(
            listOf("+slack:tygg" to "proj-tygg"),
            annotated.externalChips().map { it.text to it.projectionId },
        )
    }

    @Test
    fun escapedAndCodeSpanLabelsRenderUnescapedWithoutCodeChromeOrLinks() {
        val escaped = "+slack:\"Ann \\*bold\\* \\& \\\$x\\\$\""
        val underscore = "+slack:john\\_doe"
        val www = "`+slack:www.example.com`"
        val lineBreak = "`+slack:\"line\\\\nbreak\"`"
        val doubleTick = "``+slack:\"tick`www.x\"``"
        val content = "$escaped $underscore $www $lineBreak $doubleTick end"
        val spans =
            listOf(
                spanOf(content, escaped, "proj-a"),
                spanOf(content, underscore, "proj-b"),
                spanOf(content, www, "proj-c"),
                spanOf(content, lineBreak, "proj-d"),
                spanOf(content, doubleTick, "proj-e"),
            )

        val annotated = renderBodyAnnotated(content, spans)
        assertEquals(
            listOf(
                "+slack:\"Ann *bold* & \$x\$\"" to "proj-a",
                "+slack:john_doe" to "proj-b",
                "+slack:www.example.com" to "proj-c",
                "+slack:\"line\\\\nbreak\"" to "proj-d",
                "+slack:\"tick`www.x\"" to "proj-e",
            ),
            annotated.externalChips().map { it.text to it.projectionId },
        )
        assertTrue(annotated.getStringAnnotations(RAFT_RICHTEXT_INLINE_CODE_ANNOTATION_TAG, 0, annotated.length).isEmpty())
        assertTrue(annotated.getLinkAnnotations(0, annotated.length).none { it.item is LinkAnnotation.Url })

        val doc = buildRaftRichTextDocument(content, RaftRichTextContext(externalMentionSpans = spans))
        assertTrue(doc.inlineTokens.none { it.kind == RaftRichTextInlineKind.InlineCode })
        assertEquals(5, doc.actions.count { it.refKind == RaftRichTextRefKind.ExternalMention })
    }

    @Test
    fun spanInsideEmphasisBoldStrikethroughAndBeforeHardBreakChips() {
        val content = "*see +slack:tygg* and **+slack:bob** and ~~+slack:sam~~ and +slack:ada  \nnext"
        val spans =
            listOf(
                spanOf(content, "+slack:tygg", "proj-tygg"),
                spanOf(content, "+slack:bob", "proj-bob"),
                spanOf(content, "+slack:sam", "proj-sam"),
                spanOf(content, "+slack:ada", "proj-ada"),
            )

        val annotated = renderBodyAnnotated(content, spans)
        assertEquals(
            listOf(
                "+slack:tygg" to "proj-tygg",
                "+slack:bob" to "proj-bob",
                "+slack:sam" to "proj-sam",
                "+slack:ada" to "proj-ada",
            ),
            annotated.externalChips().map { it.text to it.projectionId },
        )
    }

    @Test
    fun adjacentBackToBackSpansSharingTextNodeBothChip() {
        val content = "+slack:a+slack:b"
        val spans =
            listOf(
                spanOf(content, "+slack:a", "proj-a"),
                spanOf(content, "+slack:b", "proj-b"),
            )

        val annotated = renderBodyAnnotated(content, spans)
        assertEquals(
            listOf("+slack:a" to "proj-a", "+slack:b" to "proj-b"),
            annotated.externalChips().map { it.text to it.projectionId },
        )
    }

    @Test
    fun spansRejectedInQuotesCodeBlocksInlineCodeLinksMismatchOutOfBoundsAndAcrossStructure() {
        val content =
            "> quoted +slack:tygg\n\n" +
                "```\n+slack:tygg\n```\n\n" +
                "    +slack:tygg\n\n" +
                "`foo +slack:tygg bar` and [+slack:tygg](https://example.com) and *em* x"
        val spans =
            listOf(
                spanOf(content, "+slack:tygg", "proj-q", nth = 0),
                spanOf(content, "+slack:tygg", "proj-f", nth = 1),
                spanOf(content, "+slack:tygg", "proj-i", nth = 2),
                spanOf(content, "+slack:tygg", "proj-c", nth = 3),
                spanOf(content, "+slack:tygg", "proj-l", nth = 4),
                spanOf(content, "*em* x", "proj-s"),
                ExternalMentionSpan(id = "proj-stale", label = "+slack:tygg", start = 0, end = 11),
                ExternalMentionSpan(id = "proj-oob", label = "+slack:tygg", start = 0, end = content.length + 5),
                ExternalMentionSpan(id = "proj-neg", label = "+slack:tygg", start = -1, end = 5),
            )

        val annotated = renderBodyAnnotated(content, spans)
        assertTrue(annotated.externalChips().isEmpty())
        assertTrue(annotated.getStringAnnotations(RAFT_RICHTEXT_INLINE_CODE_ANNOTATION_TAG, 0, annotated.length).isNotEmpty())
        assertTrue(annotated.getLinkAnnotations(0, annotated.length).any { it.item is LinkAnnotation.Url })
    }

    @Test
    fun overlappingSpansKeepEarliestAcceptedAndDoNotCorruptText() {
        val content = "hi +slack:alice_bob end"
        val spans =
            listOf(
                ExternalMentionSpan(id = "proj-first", label = "+slack:alice", start = 3, end = 15),
                ExternalMentionSpan(id = "proj-overlap", label = "alice_bob", start = 10, end = 19),
            )

        val annotated = renderBodyAnnotated(content, spans)
        assertEquals(
            listOf("+slack:alice" to "proj-first"),
            annotated.externalChips().map { it.text to it.projectionId },
        )
        assertEquals("hi +slack:alice_bob end", annotated.raftTextWithoutInlineGaps())
    }

    @Test
    fun offsetMappingSurvivesNormalizedLineBreaksAndLiteralHtmlEscaping() {
        val rawBody = "line1\\n\\n<b>hi</b> +slack:tygg and +slack:\"Ann <ops>\""
        val spans =
            listOf(
                spanOf(rawBody, "+slack:tygg", "proj-tygg"),
                spanOf(rawBody, "+slack:\"Ann <ops>\"", "proj-ann"),
            )

        val annotated = renderBodyAnnotated(rawBody, spans)
        assertEquals(
            listOf(
                "+slack:tygg" to "proj-tygg",
                "+slack:\"Ann <ops>\"" to "proj-ann",
            ),
            annotated.externalChips().map { it.text to it.projectionId },
        )
    }

    @Test
    fun externalMentionChipsCoexistWithRegularRaftMentionsAndSuppressEmbeddedRefs() {
        val content = "@alice met +slack:\"ops #general @alice\""
        val externalSpan = spanOf(content, "+slack:\"ops #general @alice\"", "proj-ops")
        val doc =
            buildRaftRichTextDocument(
                body = content,
                context =
                    RaftRichTextContext(
                        knownChannels = setOf("general"),
                        knownMentions = setOf("alice"),
                        externalMentionSpans = listOf(externalSpan),
                    ),
            )

        assertEquals(
            listOf(RaftRichTextRefKind.UserMention to "@alice", RaftRichTextRefKind.ExternalMention to "+slack:\"ops #general @alice\""),
            doc.actions.map { it.refKind to it.label },
        )

        val annotated =
            renderBodyAnnotated(
                rawBody = content,
                spans = listOf(externalSpan),
                isTagValid = { tag ->
                    (tag.kind == ThreadMarkdownTagKind.UserMention && tag.label == "@alice") ||
                        (tag.kind == ThreadMarkdownTagKind.Channel && tag.channelName == "general")
                },
            )
        assertEquals(
            listOf("@alice"),
            annotated.getStringAnnotations(RaftMarkdownMentionAnnotationTag, 0, annotated.length).map { it.item },
        )
        assertEquals(
            listOf("+slack:\"ops #general @alice\"" to "proj-ops"),
            annotated.externalChips().map { it.text to it.projectionId },
        )
    }

    @Test
    fun externalMentionChipClickFiresExternalTagAndNeverOverwritesLabelFromRaftResolver() {
        val content = "+slack:tygg approved"
        val span = spanOf(content, "+slack:tygg", "proj-tygg")
        val clicked = mutableListOf<ThreadMarkdownTag>()

        val annotated =
            renderBodyAnnotated(
                rawBody = content,
                spans = listOf(span),
                resolveTagLabel = { "@Raft Tygg" },
                onOpenTag = { clicked += it },
            )

        assertEquals(listOf("+slack:tygg" to "proj-tygg"), annotated.externalChips().map { it.text to it.projectionId })
        val clickable = annotated.getLinkAnnotations(0, annotated.length).single().item as LinkAnnotation.Clickable
        clickable.linkInteractionListener?.onClick(clickable)

        val opened = clicked.single()
        assertEquals(ThreadMarkdownTagKind.ExternalMention, opened.kind)
        assertEquals("proj-tygg", opened.externalProjectionId)
        assertEquals("+slack:tygg", opened.label)
    }

    @Test
    fun externalMentionPreviewCardSpanDecodesAndFallsBackWhenMemberAbsent() {
        val content = "hi @alice and +slack:tygg"
        val span = spanOf(content, "+slack:tygg", "proj-tygg")
        val annotated =
            renderBodyAnnotated(
                rawBody = content,
                spans = listOf(span),
                isTagValid = { it.kind == ThreadMarkdownTagKind.UserMention },
            )

        val previewSpans = annotated.raftMarkdownMentionSpans()
        assertEquals(2, previewSpans.size)
        assertNull(previewSpans[0].externalProjectionId)
        assertEquals("@alice", previewSpans[0].label)
        assertEquals("proj-tygg", previewSpans[1].externalProjectionId)
        assertEquals("+slack:tygg", previewSpans[1].label)

        val profile =
            ChannelExternalMemberProfile(
                id = "proj-tygg",
                provider = "slack",
                displayName = "Tygg",
                actorKind = "human",
                avatarUrl = "https://slack.example/tygg.png",
                workspaceName = "Acme",
            )
        val resolvedAuthor = profile.toExternalPreviewAuthor(fallbackLabel = "+slack:tygg", projectionId = "proj-tygg")
        assertEquals("Tygg", resolvedAuthor.displayName)
        assertEquals("slack", resolvedAuthor.provider)
        assertEquals("human", resolvedAuthor.actorKind)
        assertEquals("Acme", resolvedAuthor.workspaceName)
        assertEquals("https://slack.example/tygg.png", resolvedAuthor.avatarUrl)

        val fallbackAuthor =
            (null as ChannelExternalMemberProfile?).toExternalPreviewAuthor(
                fallbackLabel = "+slack:tygg",
                projectionId = "proj-tygg",
            )
        assertEquals("+slack:tygg", fallbackAuthor.displayName)
        assertEquals("", fallbackAuthor.provider)
        assertEquals("unknown", fallbackAuthor.actorKind)
        assertNull(fallbackAuthor.workspaceName)
        assertNull(fallbackAuthor.avatarUrl)
    }
}
