package build.raft.app.markdown

import androidx.compose.runtime.Immutable
import build.raft.app.RaftAppThemeState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.tencent.kuiklybase.markdown.model.RaftGFMFlavourDescriptor
import com.tencent.kuiklybase.markdown.utils.EntityConverter
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.MarkdownParser

/**
 * A message body's Markdown read as one line of text, for every surface that shows a message in a
 * line or two (Activity rows, Saved, search hits, banners, local notifications, the widget, quotes).
 *
 * Parity contract: botiverse/slock `packages/shared/src/notificationDisplay.ts`
 * (`notificationPlainText` + `boundNotificationPreview`), which reads Markdown with the parser the
 * web renders messages with. The rules, node for node:
 * - text and inline code keep their text; a code block keeps its code;
 * - emphasis, strong, strikethrough and link syntax drop their markers (a link reads as its text, an
 *   autolink as its URL); raw HTML, link definitions, rules, list/quote/heading markers and task-list
 *   checkboxes are dropped;
 * - an image reads as its alt text, or "Image";
 * - a table reads "Header: value" for each body cell;
 * - mentions, channel and task references, `$math$` and `:shortcodes:` stay as written (the web
 *   preview has no plugins for them);
 * - entities and backslash escapes are decoded;
 * - every run of whitespace, line breaks and block boundaries included, becomes one space.
 *
 * It walks the app's own Markdown tree ([RaftGFMFlavourDescriptor], the one messages render with),
 * so `**` inside code or `_` inside a URL are never mistaken for markup, and an unterminated marker
 * stays a single delimiter token the walker can recognise. Truncation ([maxLength]) happens after
 * flattening, so a cut never lands inside syntax.
 *
 * Styled ranges ([RaftMarkdownPreview.spans]) let a row draw bold, italic, struck, code and link runs
 * inline, as the web `InlineMarkdownPreview` does in the Activity list.
 */
@Immutable
data class RaftMarkdownPreview(
    val text: String,
    val spans: List<RaftMarkdownPreviewSpan> = emptyList(),
) {
    fun toAnnotatedString(linkColor: Color = RaftAppThemeState.colors.foregroundLink): AnnotatedString {
        if (spans.isEmpty()) return AnnotatedString(text)
        val spanStyles =
            spans.map { span -> AnnotatedString.Range(span.style.toSpanStyle(linkColor), span.start, span.end) }
        return AnnotatedString(text, spanStyles = spanStyles)
    }

    companion object {
        val Empty = RaftMarkdownPreview("")
    }
}

@Immutable
data class RaftMarkdownPreviewSpan(val start: Int, val end: Int, val style: RaftMarkdownPreviewStyle)

enum class RaftMarkdownPreviewStyle { Strong, Emphasis, Strikethrough, Code, Link }

private fun RaftMarkdownPreviewStyle.toSpanStyle(linkColor: Color): SpanStyle =
    when (this) {
        RaftMarkdownPreviewStyle.Strong -> SpanStyle(fontWeight = FontWeight.Bold)
        RaftMarkdownPreviewStyle.Emphasis -> SpanStyle(fontStyle = FontStyle.Italic)
        RaftMarkdownPreviewStyle.Strikethrough -> SpanStyle(textDecoration = TextDecoration.LineThrough)
        // web InlineMarkdownPreview: InlineCode with no chrome, so only the mono family remains.
        RaftMarkdownPreviewStyle.Code -> SpanStyle(fontFamily = raftMarkdownInlineCodeTextStyle(TextStyle.Default).fontFamily)
        // web: preview links are spans in the link colour, without the underline of a clickable link.
        RaftMarkdownPreviewStyle.Link -> SpanStyle(color = linkColor)
    }

/** web `MAX_NOTIFICATION_BODY_LENGTH`: the push and banner preview length. */
const val RaftMarkdownPreviewNotificationLength = 140

/** web `MAX_NOTIFICATION_MARKDOWN_SOURCE_LENGTH`: no preview shows more, so parse no more. */
internal const val RaftMarkdownPreviewMaxSourceLength = 2_048

/** The one-line plain text of [markdown]; cut to [maxLength] (ending in "…") when given. */
fun markdownToPreviewText(markdown: String, maxLength: Int? = null): String =
    markdownToPreview(markdown, maxLength).text

/** [markdownToPreviewText] with its styled runs. Cached per body, so list rows can call it per frame. */
fun markdownToPreview(markdown: String, maxLength: Int? = null): RaftMarkdownPreview {
    if (markdown.isBlank()) return RaftMarkdownPreview.Empty
    val full = RaftMarkdownPreviewCache.get(markdown)
    return if (maxLength == null) full else full.truncated(maxLength)
}

internal fun RaftMarkdownPreview.truncated(maxLength: Int): RaftMarkdownPreview {
    if (text.length <= maxLength) return this
    val keep = (maxLength - 1).coerceAtLeast(0)
    return RaftMarkdownPreview(
        text = text.take(keep) + "…",
        spans = spans.mapNotNull { span ->
            if (span.start >= keep) null else span.copy(end = minOf(span.end, keep))
        },
    )
}

private object RaftMarkdownPreviewCache {
    private const val MaxEntries = 512
    private val lock = SynchronizedObject()
    private val entries = LinkedHashMap<String, RaftMarkdownPreview>()

    fun get(markdown: String): RaftMarkdownPreview {
        synchronized(lock) {
            entries.remove(markdown)?.let { cached ->
                entries[markdown] = cached
                return cached
            }
        }
        val computed = flattenMarkdownPreview(markdown)
        synchronized(lock) {
            entries[markdown] = computed
            while (entries.size > MaxEntries) entries.keys.firstOrNull()?.let(entries::remove) ?: break
        }
        return computed
    }
}

internal fun flattenMarkdownPreview(markdown: String): RaftMarkdownPreview {
    val source = markdown.take(RaftMarkdownPreviewMaxSourceLength)
    val root =
        try {
            MarkdownParser(RaftGFMFlavourDescriptor()).buildMarkdownTreeFromString(source)
        } catch (_: Throwable) {
            // web: a source the parser rejects is shown as written.
            return RaftMarkdownPreview(PreviewWriter(source).apply { appendLiteral(source) }.finish().text)
        }
    return PreviewWriter(source).apply { block(root) }.finish()
}

/**
 * Walks the tree and writes collapsed text. Plain text is buffered so entities and escapes split
 * across tokens (`&#35;` lexes as `&`, `#35`, `;`) decode as one run.
 */
private class PreviewWriter(private val source: String) {
    private val out = StringBuilder()
    private val pending = StringBuilder()
    private var pendingSpace = false
    private val spans = mutableListOf<RaftMarkdownPreviewSpan>()

    fun finish(): RaftMarkdownPreview {
        flush()
        return RaftMarkdownPreview(out.toString(), spans.filter { it.end > it.start })
    }

    private fun ASTNode.text(): String = source.substring(startOffset, endOffset)

    fun block(node: ASTNode) {
        when (node.type) {
            MarkdownElementTypes.CODE_FENCE -> {
                node.children.forEach { child ->
                    when (child.type) {
                        MarkdownTokenTypes.CODE_FENCE_CONTENT -> appendLiteral(child.text())
                        MarkdownTokenTypes.EOL -> separate()
                    }
                }
                separate()
            }
            MarkdownElementTypes.CODE_BLOCK -> {
                node.children.forEach { child ->
                    if (child.type == MarkdownTokenTypes.CODE_LINE) appendLiteral(child.text()) else separate()
                }
                separate()
            }
            GFMElementTypes.TABLE -> table(node)
            GFMElementTypes.BLOCK_MATH -> {
                appendLiteral(node.text())
                separate()
            }
            MarkdownElementTypes.HTML_BLOCK,
            MarkdownElementTypes.LINK_DEFINITION,
            MarkdownTokenTypes.HORIZONTAL_RULE,
            MarkdownTokenTypes.EOL -> separate()
            MarkdownElementTypes.PARAGRAPH,
            MarkdownTokenTypes.ATX_CONTENT,
            MarkdownTokenTypes.SETEXT_CONTENT -> {
                inline(node.children)
                separate()
            }
            // Markers of the container blocks: `#`, `===`, `- `, `1. `, `[x] `, `> `.
            MarkdownTokenTypes.ATX_HEADER,
            MarkdownTokenTypes.SETEXT_1,
            MarkdownTokenTypes.SETEXT_2,
            MarkdownTokenTypes.LIST_BULLET,
            MarkdownTokenTypes.LIST_NUMBER,
            GFMTokenTypes.CHECK_BOX,
            MarkdownTokenTypes.BLOCK_QUOTE,
            MarkdownTokenTypes.WHITE_SPACE -> separate()
            else ->
                if (node.children.isEmpty()) {
                    // A loose token at block level (rare): keep its text.
                    appendText(node.text())
                } else {
                    node.children.forEach(::block)
                    separate()
                }
        }
    }

    private fun table(node: ASTNode) {
        val header = node.children.firstOrNull { it.type == GFMElementTypes.HEADER }
        val headings = header?.cells()?.map { cell -> cellText(cell) }.orEmpty()
        node.children.filter { it.type == GFMElementTypes.ROW }.forEach { row ->
            row.cells().forEachIndexed { index, cell ->
                appendText(headings.getOrNull(index)?.text.orEmpty())
                appendText(": ")
                inline(cell.children)
                separate()
            }
        }
    }

    private fun ASTNode.cells(): List<ASTNode> = children.filter { it.type == GFMTokenTypes.CELL }

    private fun cellText(cell: ASTNode): RaftMarkdownPreview =
        PreviewWriter(source).apply { inline(cell.children) }.finish()

    fun inline(nodes: List<ASTNode>) {
        nodes.forEachIndexed { index, node -> inlineNode(node, nodes, index) }
    }

    private fun inlineNode(node: ASTNode, siblings: List<ASTNode>, index: Int) {
        when (node.type) {
            MarkdownElementTypes.STRONG -> styled(RaftMarkdownPreviewStyle.Strong) { inline(node.withoutDelimiters()) }
            MarkdownElementTypes.EMPH -> styled(RaftMarkdownPreviewStyle.Emphasis) { inline(node.withoutDelimiters()) }
            GFMElementTypes.STRIKETHROUGH ->
                styled(RaftMarkdownPreviewStyle.Strikethrough) { inline(node.withoutDelimiters()) }
            MarkdownElementTypes.CODE_SPAN -> styled(RaftMarkdownPreviewStyle.Code) { appendLiteral(node.codeSpanText()) }
            MarkdownElementTypes.INLINE_LINK,
            MarkdownElementTypes.FULL_REFERENCE_LINK,
            MarkdownElementTypes.SHORT_REFERENCE_LINK -> {
                val label =
                    node.children.firstOrNull { it.type == MarkdownElementTypes.LINK_TEXT }
                        ?: node.children.firstOrNull { it.type == MarkdownElementTypes.LINK_LABEL }
                styled(RaftMarkdownPreviewStyle.Link) { label?.let { inline(it.withoutBrackets()) } }
            }
            MarkdownElementTypes.AUTOLINK ->
                styled(RaftMarkdownPreviewStyle.Link) {
                    node.children.filter { it.type == MarkdownTokenTypes.AUTOLINK }.forEach { appendLiteral(it.text()) }
                }
            GFMTokenTypes.GFM_AUTOLINK -> styled(RaftMarkdownPreviewStyle.Link) { appendLiteral(node.text()) }
            MarkdownElementTypes.IMAGE -> {
                val link = node.children.firstOrNull { it.type != MarkdownTokenTypes.EXCLAMATION_MARK }
                val alt = link?.children?.firstOrNull { it.type == MarkdownElementTypes.LINK_TEXT }
                val altText = alt?.let { PreviewWriter(source).apply { inline(it.withoutBrackets()) }.finish().text }
                appendText(altText?.takeIf { it.isNotBlank() } ?: "Image")
                separate()
            }
            MarkdownTokenTypes.HTML_TAG -> Unit
            MarkdownTokenTypes.EOL,
            MarkdownTokenTypes.HARD_LINE_BREAK -> separate()
            // Quote markers of a paragraph's continuation lines.
            MarkdownTokenTypes.BLOCK_QUOTE -> separate()
            // A delimiter no span claimed. A dangling opener (`**foo` from a cut preview) is syntax,
            // not prose; a lone `*` between spaces (`2 * 3`) or inside a word stays.
            MarkdownTokenTypes.EMPH,
            GFMTokenTypes.TILDE,
            MarkdownTokenTypes.BACKTICK ->
                if (!isDanglingOpener(siblings, index)) appendText(node.text())
            else ->
                if (node.children.isEmpty()) appendText(node.text()) else inline(node.children)
        }
    }

    /**
     * True for a run of unclaimed delimiters at the start of a word (start of text or after
     * whitespace, followed by text): an opener whose closer is missing.
     */
    private fun isDanglingOpener(siblings: List<ASTNode>, index: Int): Boolean {
        val type = siblings[index].type
        var first = index
        while (first > 0 && siblings[first - 1].type == type) first--
        var last = index
        while (last < siblings.lastIndex && siblings[last + 1].type == type) last++
        val before = siblings[first].startOffset.let { if (it == 0) null else source[it - 1] }
        val after = siblings[last].endOffset.let { if (it >= source.length) null else source[it] }
        return (before == null || before.isWhitespace()) && after != null && !after.isWhitespace()
    }

    private fun ASTNode.withoutDelimiters(): List<ASTNode> {
        val delimiter: (IElementType) -> Boolean = { it == MarkdownTokenTypes.EMPH || it == GFMTokenTypes.TILDE }
        var from = 0
        var to = children.size
        while (from < to && delimiter(children[from].type) && children[from].children.isEmpty()) from++
        while (to > from && delimiter(children[to - 1].type) && children[to - 1].children.isEmpty()) to--
        return children.subList(from, to)
    }

    private fun ASTNode.withoutBrackets(): List<ASTNode> =
        children.filterNot { it.type == MarkdownTokenTypes.LBRACKET || it.type == MarkdownTokenTypes.RBRACKET }

    /** CommonMark code span content: between the backtick runs, one framing space stripped. */
    private fun ASTNode.codeSpanText(): String {
        val inner = children.dropWhile { it.type == MarkdownTokenTypes.BACKTICK }.dropLastWhile { it.type == MarkdownTokenTypes.BACKTICK }
        if (inner.isEmpty()) return ""
        val text = source.substring(inner.first().startOffset, inner.last().endOffset)
        return if (text.length >= 2 && text.first() == ' ' && text.last() == ' ' && text.isNotBlank()) {
            text.substring(1, text.length - 1)
        } else {
            text
        }
    }

    private inline fun styled(style: RaftMarkdownPreviewStyle, content: () -> Unit) {
        flush()
        val start = out.length + if (pendingSpace && out.isNotEmpty()) 1 else 0
        content()
        flush()
        spans += RaftMarkdownPreviewSpan(start, out.length, style)
    }

    /** Prose: entities and escapes decode when the run flushes. */
    fun appendText(text: String) {
        pending.append(text)
    }

    /** Code and URLs: written as is. */
    fun appendLiteral(text: String) {
        flush()
        write(text)
    }

    private fun separate() {
        flush()
        pendingSpace = true
    }

    private fun flush() {
        if (pending.isEmpty()) return
        val decoded = EntityConverter.replaceEntities(pending, processEntities = true, processEscapes = true)
        pending.clear()
        write(decoded)
    }

    private fun write(text: String) {
        for (char in text) {
            if (char.isWhitespace()) {
                pendingSpace = true
            } else {
                if (pendingSpace && out.isNotEmpty()) out.append(' ')
                pendingSpace = false
                out.append(char)
            }
        }
    }
}
