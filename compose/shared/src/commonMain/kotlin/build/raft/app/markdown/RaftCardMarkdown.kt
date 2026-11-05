package build.raft.app.markdown

import com.tencent.kuiklybase.markdown.model.RaftGFMFlavourDescriptor
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.MarkdownParser

/*
 * A card message's `Markdown` element (RFC 082 v2.0 §4.8): the card Markdown subset, checked on
 * this App's own parse (the message renderer's parser, [RaftGFMFlavourDescriptor]).
 *
 * Contract: botiverse/slock #13224, staging @ 48cf48c24288f88f44c39c1d3e3e8497cbfed4a9
 * `packages/shared/src/messageCardMarkdown.ts` (`MESSAGE_CARD_MARKDOWN_NODE_TYPES`,
 * `messageCardMarkdownViolations`) and the shared contract vectors (`messageCard.v1.vector.json`
 * `markdown[]`); the web's drawing is #13225 `remarkMessageCardMarkdown.ts`.
 *
 * The allowlist, as mdast node types: paragraph, text, break, strong, emphasis, delete,
 * inlineCode, code (fenced), list, listItem (not a task list item), blockquote, link (http:,
 * https: or mailto:, as a message's own links; a card Button stays https: only). Lists and quotes together nest at most 3 levels. The Server refuses a card with
 * anything else; a client still checks on its own parse and draws each node outside the subset
 * as its source text, never rendered (Tenny, #13213), so a stored card never depends on the
 * Server having been right. `@name` and `#channel` in card Markdown are text: no chips.
 *
 * This parser is not the Server's (micromark), so the walk maps its tree onto mdast's and covers
 * where they differ: GFM e-mail literals (the Server's are `mailto:` links; this lexer has none,
 * so they are drawn as `<address>` autolinks), footnotes (this parser reads `[^1]` as a reference link), references
 * without a definition (text to the Server), indented code (off on the Server: text), and the
 * Raft-only `raft://` literal links (text to the Server).
 */

/** One node outside the card Markdown subset: its mdast [node] type and where its source is in the content. */
internal data class RaftCardMarkdownViolation(val node: String, val start: Int, val end: Int)

/** The subset check of one Markdown element's content. */
internal class RaftCardMarkdownCheck(
    val content: String,
    /** Every node outside the subset, outermost first, in document order (nothing inside one is looked at). */
    val violations: List<RaftCardMarkdownViolation>,
    /** The mdast node types of the tree (root aside) when it is all in the subset, as the Server reads it. */
    val nodeTypes: Set<String>,
    internal val edits: List<RaftCardMarkdownEdit>,
)

/** A stretch of the content drawn otherwise: as plain text, with its URL scheme lower-cased ([lowercase]), or as an `<…>` autolink ([autolink]). */
internal data class RaftCardMarkdownEdit(val start: Int, val end: Int, val lowercase: Boolean = false, val autolink: Boolean = false)

/** Lists and quotes, together, nest at most this many levels (`MESSAGE_CARD_MARKDOWN_MAX_NESTING`). */
internal const val RAFT_CARD_MARKDOWN_MAX_NESTING = 3

/** 1-based line and column (UTF-16 units, as the vectors' positions) of [offset] in [content]. */
internal fun raftCardMarkdownLineColumn(content: String, offset: Int): Pair<Int, Int> {
    var line = 1
    var lineStart = 0
    for (index in 0 until offset) {
        if (content[index] == '\n') {
            line++
            lineStart = index + 1
        }
    }
    return line to offset - lineStart + 1
}

/** The nodes of [content] outside the card Markdown subset (see [raftCardMarkdownCheck]). */
internal fun raftCardMarkdownViolations(content: String): List<RaftCardMarkdownViolation> = raftCardMarkdownCheck(content).violations

/** Parses [content] with the message renderer's parser and checks it against the card Markdown subset. */
internal fun raftCardMarkdownCheck(content: String): RaftCardMarkdownCheck {
    val tree = MarkdownParser(RaftGFMFlavourDescriptor()).buildMarkdownTreeFromString(content)
    val walker = CardMarkdownWalker(content, definedLabels(content, tree))
    walker.visit(tree, nesting = 0, report = true)
    return RaftCardMarkdownCheck(
        content = content,
        violations = walker.violations.sortedBy { it.start },
        nodeTypes = walker.types,
        edits = walker.edits.sortedBy { it.start },
    )
}

/**
 * [content] as the message renderer should draw it for a card (web `remarkMessageCardMarkdownSubset`):
 * each node outside the subset as its source text (its markup escaped, so this parser reads it as
 * text), a task item's box written out, the Server's text that this parser would read otherwise
 * (indented lines, an undefined reference, a `raft://` literal) as text too, and a link's scheme
 * in lower case. Everything else is left as written.
 */
internal fun raftCardMarkdownRenderSource(content: String): String = raftCardMarkdownCheck(content).renderSource()

internal fun RaftCardMarkdownCheck.renderSource(): String {
    if (edits.isEmpty()) return content
    val out = StringBuilder(content.length + 16)
    var cursor = 0
    for (edit in edits) {
        if (edit.start < cursor) continue
        out.append(content, cursor, edit.start)
        when {
            edit.lowercase -> out.append(content.substring(edit.start, edit.end).lowercase())
            edit.autolink -> out.append('<').append(content, edit.start, edit.end).append('>')
            else -> out.append(sourceAsText(content, edit.start, edit.end))
        }
        cursor = edit.end
    }
    out.append(content, cursor, content.length)
    return out.toString()
}

/** Characters this lexer reads a backslash escape of as the character itself (gfm.flex `Escaping`), less `@` and `#` (refs). */
private const val CardMarkdownEscapable = "\\\"'`*_{}[]()+.,!:$%&~<>/-"

/** Starts each drawn-as-source line, so nothing at a line's start reads as block markup (a heading, a rule, `===`). */
private const val CardMarkdownWordJoiner = '⁠'

/**
 * The source between [start] and [end] as text: each line's markup escaped and its leading
 * blanks dropped (so it reads as no block), a container's own prefix (`> `, a list's indent) kept
 * on the lines after the first.
 */
private fun sourceAsText(content: String, start: Int, end: Int): String {
    val lineStart = content.lastIndexOf('\n', start - 1) + 1
    val column = start - lineStart
    val lines = content.substring(start, end).split('\n')
    return lines.mapIndexed { index, line ->
        val prefix = if (index == 0) "" else line.take(column).takeIf { it.all { char -> char == ' ' || char == '\t' || char == '>' } } ?: ""
        val rest = line.substring(prefix.length).trimStart(' ', '\t')
        if (rest.isEmpty()) {
            prefix.trimEnd()
        } else {
            buildString {
                append(prefix)
                append(CardMarkdownWordJoiner)
                rest.forEach { char ->
                    if (char in CardMarkdownEscapable) append('\\')
                    append(char)
                }
            }
        }
    }.joinToString("\n")
}

private val HeadingTypes: Set<IElementType> =
    setOf(
        MarkdownElementTypes.ATX_1, MarkdownElementTypes.ATX_2, MarkdownElementTypes.ATX_3,
        MarkdownElementTypes.ATX_4, MarkdownElementTypes.ATX_5, MarkdownElementTypes.ATX_6,
        MarkdownElementTypes.SETEXT_1, MarkdownElementTypes.SETEXT_2,
    )

private val CardLinkScheme = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")

/** A link the subset allows: an `http:`, `https:` or `mailto:` URL (scheme case-insensitive), as a message's links. */
private fun isCardMarkdownLinkUrl(url: String): Boolean {
    val scheme = CardLinkScheme.find(url)?.groupValues?.get(1)?.lowercase() ?: return false
    if (scheme != "http" && scheme != "https" && scheme != "mailto") return false
    val rest = url.substring(scheme.length + 1)
    return rest.isNotEmpty() && rest.none { it.isWhitespace() }
}

// GFM e-mail literals (micromark-extension-gfm-autolink-literal): `a.b+c@example.com`.
private val CardEmailLiteral = Regex("[A-Za-z0-9.+_-]+@[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)+")
private const val CardEmailLocalChars = ".+_-"

/** A link reference label as a definition is matched: brackets off, blanks collapsed, case folded. */
private fun normalizedLabel(raw: String): String =
    raw.removePrefix("[").removeSuffix("]").trim().split(Regex("\\s+")).joinToString(" ").lowercase()

private fun ASTNode.text(content: String): String = content.substring(startOffset, endOffset)

private fun definedLabels(content: String, tree: ASTNode): Set<String> {
    val labels = mutableSetOf<String>()
    fun visit(node: ASTNode) {
        if (node.type == MarkdownElementTypes.LINK_DEFINITION) {
            node.children.firstOrNull { it.type == MarkdownElementTypes.LINK_LABEL }?.let { labels += normalizedLabel(it.text(content)) }
            return
        }
        node.children.forEach(::visit)
    }
    visit(tree)
    return labels
}

private class CardMarkdownWalker(private val content: String, private val defined: Set<String>) {
    val violations = mutableListOf<RaftCardMarkdownViolation>()
    val types = mutableSetOf<String>()
    val edits = mutableListOf<RaftCardMarkdownEdit>()

    /** The end of [node]'s source without trailing blanks and line breaks (mdast positions end at the last character). */
    private fun endOf(node: ASTNode): Int = trimmedEnd(node.startOffset, node.endOffset)

    private fun trimmedEnd(start: Int, end: Int): Int {
        var at = end
        while (at > start && content[at - 1].let { it == ' ' || it == '\t' || it == '\n' || it == '\r' }) at--
        return at
    }

    /** Outside the subset: drawn as source; named in [violations] when [report] (not inside another one). */
    private fun outside(node: String, start: Int, end: Int, report: Boolean) {
        if (report) violations += RaftCardMarkdownViolation(node, start, end)
        edits += RaftCardMarkdownEdit(start, end)
    }

    private fun plain(start: Int, end: Int, report: Boolean) {
        if (report) types += "text"
        edits += RaftCardMarkdownEdit(start, end)
    }

    fun visit(node: ASTNode, nesting: Int, report: Boolean) {
        when (val type = node.type) {
            MarkdownElementTypes.MARKDOWN_FILE -> visitChildren(node, nesting, report)
            MarkdownElementTypes.PARAGRAPH -> {
                if (report) {
                    types += "paragraph"
                    if (hasText(node)) types += "text"
                }
                scanEmails(node, report)
                visitChildren(node, nesting, report)
            }
            // Indented code is off on the Server (as in chat on the web): those lines are a paragraph's text.
            MarkdownElementTypes.CODE_BLOCK -> {
                if (report) types += "paragraph"
                plain(node.startOffset, endOf(node), report)
            }
            MarkdownElementTypes.CODE_FENCE -> if (report) types += "code"
            MarkdownElementTypes.CODE_SPAN -> if (report) types += "inlineCode"
            MarkdownElementTypes.EMPH -> mark("emphasis", node, nesting, report)
            MarkdownElementTypes.STRONG -> mark("strong", node, nesting, report)
            GFMElementTypes.STRIKETHROUGH -> mark("delete", node, nesting, report)
            MarkdownElementTypes.UNORDERED_LIST, MarkdownElementTypes.ORDERED_LIST -> container("list", node, nesting, report)
            MarkdownElementTypes.BLOCK_QUOTE -> container("blockquote", node, nesting, report)
            // A GitHub alert is a block quote to the Server; its `[!NOTE]` line is text.
            GFMElementTypes.ALERT -> {
                container("blockquote", node, nesting, report)
                if (nesting + 1 <= RAFT_CARD_MARKDOWN_MAX_NESTING) {
                    if (report) types += "paragraph"
                    alertTitles(node).forEach { plain(it.startOffset, it.endOffset, report) }
                }
            }
            MarkdownElementTypes.LIST_ITEM -> {
                val box = node.children.firstOrNull { it.type == GFMTokenTypes.CHECK_BOX }
                if (box != null) {
                    // The item stays an item (a list holds only those), its box written out; its content is drawn as usual.
                    if (report) violations += RaftCardMarkdownViolation("taskListItem", node.startOffset, endOf(node))
                    edits += RaftCardMarkdownEdit(box.startOffset, trimmedEnd(box.startOffset, box.endOffset))
                    visitChildren(node, nesting, report = false)
                } else {
                    if (report) types += "listItem"
                    visitChildren(node, nesting, report)
                }
            }
            MarkdownElementTypes.INLINE_LINK -> {
                val destination = node.children.firstOrNull { it.type == MarkdownElementTypes.LINK_DESTINATION }
                val url = destination?.text(content)?.removeSurrounding("<", ">").orEmpty()
                if (!isCardMarkdownLinkUrl(url)) {
                    outside("link", node.startOffset, endOf(node), report)
                } else {
                    if (report) types += "link"
                    val schemeStart = destination!!.startOffset + if (content[destination.startOffset] == '<') 1 else 0
                    edits += RaftCardMarkdownEdit(schemeStart, schemeStart + url.indexOf(':'), lowercase = true)
                    node.children.filter { it.type == MarkdownElementTypes.LINK_TEXT }.forEach { visitChildren(it, nesting, report) }
                }
            }
            MarkdownElementTypes.AUTOLINK -> {
                val inner = node.text(content).removePrefix("<").removeSuffix(">")
                if (inner.startsWith("www.", ignoreCase = true) || isCardMarkdownLinkUrl(inner)) {
                    if (report) types += setOf("link", "text")
                } else {
                    outside("link", node.startOffset, endOf(node), report)
                }
            }
            MarkdownElementTypes.FULL_REFERENCE_LINK, MarkdownElementTypes.SHORT_REFERENCE_LINK -> {
                val label = node.children.lastOrNull { it.type == MarkdownElementTypes.LINK_LABEL }?.text(content)
                val key = label?.let(::normalizedLabel)
                when {
                    key == null || key !in defined -> plain(node.startOffset, endOf(node), report)
                    key.startsWith("^") -> outside("footnoteReference", node.startOffset, endOf(node), report)
                    else -> outside("linkReference", node.startOffset, endOf(node), report)
                }
            }
            MarkdownElementTypes.LINK_DEFINITION -> {
                val label = node.children.firstOrNull { it.type == MarkdownElementTypes.LINK_LABEL }?.text(content)
                val footnote = label?.let(::normalizedLabel)?.startsWith("^") == true
                outside(if (footnote) "footnoteDefinition" else "definition", node.startOffset, endOf(node), report)
            }
            MarkdownElementTypes.IMAGE -> outside("image", node.startOffset, endOf(node), report)
            in HeadingTypes -> outside("heading", node.startOffset, endOf(node), report)
            GFMElementTypes.TABLE -> outside("table", node.startOffset, endOf(node), report)
            MarkdownElementTypes.HTML_BLOCK -> outside("html", node.startOffset, endOf(node), report)
            GFMElementTypes.INLINE_MATH -> outside("inlineMath", node.startOffset, endOf(node), report)
            GFMElementTypes.BLOCK_MATH -> outside("math", node.startOffset, endOf(node), report)
            MarkdownTokenTypes.HORIZONTAL_RULE -> outside("thematicBreak", node.startOffset, endOf(node), report)
            MarkdownTokenTypes.HTML_TAG -> outside("html", node.startOffset, endOf(node), report)
            MarkdownTokenTypes.EMAIL_AUTOLINK, MarkdownTokenTypes.AUTOLINK -> {
                // Outside an AUTOLINK element (`<…>` it didn't pair): the brackets beside it are part of it.
                val siblings = node.parent?.children.orEmpty()
                val index = siblings.indexOf(node)
                val before = siblings.getOrNull(index - 1)?.takeIf { it.type == MarkdownTokenTypes.LT && it.endOffset == node.startOffset }
                val after = siblings.getOrNull(index + 1)?.takeIf { it.type == MarkdownTokenTypes.GT && it.startOffset == node.endOffset }
                val inner = node.text(content)
                // `<a@example.com>` is a `mailto:` link, in the subset.
                if (type == MarkdownTokenTypes.EMAIL_AUTOLINK || isCardMarkdownLinkUrl(inner)) {
                    if (report) types += setOf("link", "text")
                } else {
                    outside("link", before?.startOffset ?: node.startOffset, after?.endOffset ?: node.endOffset, report)
                }
            }
            GFMTokenTypes.GFM_AUTOLINK -> {
                val text = node.text(content)
                val lower = text.lowercase()
                when {
                    lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("www.") ->
                        if (report) types += setOf("link", "text")
                    // `ftp://`, `file://` and Raft's own `raft://` literals are text to the Server.
                    else -> plain(node.startOffset, node.endOffset, report)
                }
            }
            MarkdownTokenTypes.HARD_LINE_BREAK -> if (report) types += "break"
            else -> visitChildren(node, nesting, report)
        }
    }

    private fun visitChildren(node: ASTNode, nesting: Int, report: Boolean) {
        node.children.forEach { visit(it, nesting, report) }
    }

    private fun mark(name: String, node: ASTNode, nesting: Int, report: Boolean) {
        if (report) types += name
        visitChildren(node, nesting, report)
    }

    /** A list or a quote: one more level, refused as a whole past [RAFT_CARD_MARKDOWN_MAX_NESTING]. */
    private fun container(name: String, node: ASTNode, nesting: Int, report: Boolean) {
        val depth = nesting + 1
        if (depth > RAFT_CARD_MARKDOWN_MAX_NESTING) {
            outside(name, node.startOffset, endOf(node), report)
            return
        }
        if (report) types += name
        visitChildren(node, depth, report)
    }

    private fun alertTitles(node: ASTNode): List<ASTNode> =
        node.children.flatMap { child -> if (child.type == GFMTokenTypes.ALERT_TITLE) listOf(child) else if (child.type == GFMElementTypes.ALERT) emptyList() else alertTitles(child) }

    /** Whether a paragraph has text of its own (an mdast `text` node): not only code, markup or blanks. */
    private fun hasText(node: ASTNode): Boolean {
        when (node.type) {
            MarkdownElementTypes.CODE_SPAN, MarkdownElementTypes.LINK_DESTINATION, MarkdownElementTypes.LINK_TITLE,
            MarkdownElementTypes.IMAGE, GFMElementTypes.INLINE_MATH, MarkdownTokenTypes.HTML_TAG, MarkdownTokenTypes.EMAIL_AUTOLINK,
            MarkdownTokenTypes.EOL, MarkdownTokenTypes.WHITE_SPACE, MarkdownTokenTypes.HARD_LINE_BREAK, MarkdownTokenTypes.BACKTICK,
            -> return false
        }
        if (node.children.isNotEmpty()) return node.children.any(::hasText)
        val parent = node.parent?.type
        if (node.type == MarkdownTokenTypes.EMPH && (parent == MarkdownElementTypes.EMPH || parent == MarkdownElementTypes.STRONG)) return false
        if (node.type == GFMTokenTypes.TILDE && parent == GFMElementTypes.STRIKETHROUGH) return false
        if (node.type in LinkPunctuation && parent in LinkParents) return false
        return node.text(content).isNotBlank()
    }

    /**
     * GFM e-mail literals in a paragraph's own text (outside code, links, HTML and math): the
     * Server's parser reads each as a `mailto:` link, in the subset; this lexer has none, so each
     * is drawn as an `<address>` autolink (a mailto link here too).
     */
    private fun scanEmails(paragraph: ASTNode, report: Boolean) {
        val runs = mutableListOf<IntArray>()
        fun collect(node: ASTNode) {
            when (node.type) {
                MarkdownElementTypes.CODE_SPAN, MarkdownElementTypes.INLINE_LINK, MarkdownElementTypes.AUTOLINK, MarkdownElementTypes.IMAGE,
                MarkdownElementTypes.FULL_REFERENCE_LINK, MarkdownElementTypes.SHORT_REFERENCE_LINK, GFMElementTypes.INLINE_MATH,
                MarkdownTokenTypes.HTML_TAG, GFMTokenTypes.GFM_AUTOLINK, MarkdownTokenTypes.EMAIL_AUTOLINK, MarkdownTokenTypes.AUTOLINK,
                -> return
            }
            if (node.children.isNotEmpty()) {
                node.children.forEach(::collect)
                return
            }
            val last = runs.lastOrNull()
            if (last != null && last[1] == node.startOffset) last[1] = node.endOffset else runs += intArrayOf(node.startOffset, node.endOffset)
        }
        paragraph.children.forEach(::collect)
        for ((start, end) in runs.map { it[0] to it[1] }) {
            val run = content.substring(start, end)
            for (match in CardEmailLiteral.findAll(run)) {
                val before = run.getOrNull(match.range.first - 1)
                if (before != null && (before.isLetterOrDigit() || before in CardEmailLocalChars || before == '@' || before == '/')) continue
                val last = match.value.last()
                if (last == '-' || last == '_') continue
                if (report) types += setOf("link", "text")
                edits += RaftCardMarkdownEdit(start + match.range.first, start + match.range.last + 1, autolink = true)
            }
        }
    }

    private companion object {
        val LinkPunctuation: Set<IElementType> =
            setOf(MarkdownTokenTypes.LBRACKET, MarkdownTokenTypes.RBRACKET, MarkdownTokenTypes.LPAREN, MarkdownTokenTypes.RPAREN, MarkdownTokenTypes.LT, MarkdownTokenTypes.GT)
        val LinkParents: Set<IElementType?> =
            setOf(
                MarkdownElementTypes.INLINE_LINK, MarkdownElementTypes.LINK_TEXT, MarkdownElementTypes.AUTOLINK,
                MarkdownElementTypes.FULL_REFERENCE_LINK, MarkdownElementTypes.SHORT_REFERENCE_LINK, MarkdownElementTypes.LINK_LABEL,
            )
    }
}
