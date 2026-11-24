package build.raft.app.markdown

import build.raft.app.RaftCoreInternalApi

import build.raft.app.raftLogWarn
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownParsingException
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.CompositeASTNode
import org.intellij.markdown.ast.LeafASTNode
import org.intellij.markdown.parser.MarkdownParser

/**
 * [MarkdownParser.buildMarkdownTreeFromString] that never throws the parser's own consistency
 * check. The vendored JetBrains parser asserts its tree is well nested and throws
 * [MarkdownParsingException] when it is not; some message text trips it. A known case: a link
 * definition label whose destination sits on the next line and starts with a nested quote
 * (`[x]:` then `>> y`); the parser keeps the definition's ranges after rejecting it, and the
 * quote then crosses them ("Intersecting parsed nodes detected ... BLOCK_QUOTE vs
 * LINK_DESTINATION"). Uncaught, that took the whole app down while a conversation rendered.
 *
 * Such text is shown as written: one plain-text paragraph over the whole source (the parser's
 * own non-asserting fallback shape). [source] names the caller in the one diagnostic line, which
 * carries only the text length and the failure kind, never message content.
 */
@RaftCoreInternalApi fun MarkdownParser.buildMarkdownTreeOrPlainText(
    text: String,
    source: String,
    diagnostics: (String) -> Unit = raftMarkdownParseDiagnosticsLogger,
): ASTNode =
    try {
        buildMarkdownTreeFromString(text)
    } catch (error: MarkdownParsingException) {
        diagnostics(
            "markdown_parse_fallback source=$source length=${text.length} " +
                "reason=${error.raftMarkdownParseFailureKind()} fallback=plain_text",
        )
        raftPlainTextMarkdownTree(text)
    }

/** The tree [MarkdownParser] itself builds with assertions off: the whole text as one plain paragraph. */
@RaftCoreInternalApi fun raftPlainTextMarkdownTree(text: String): ASTNode =
    CompositeASTNode(
        MarkdownElementTypes.MARKDOWN_FILE,
        if (text.isEmpty()) {
            emptyList()
        } else {
            listOf(
                CompositeASTNode(
                    MarkdownElementTypes.PARAGRAPH,
                    listOf(LeafASTNode(MarkdownTokenTypes.TEXT, 0, text.length)),
                ),
            )
        },
    )

@RaftCoreInternalApi val raftMarkdownParseDiagnosticsLogger: (String) -> Unit = { message ->
    raftLogWarn("RaftMarkdown", message)
}

/**
 * The failure kind only: the parser's message up to its first `:` (e.g. "Intersecting parsed
 * nodes detected"), as a token. What follows the colon is node types and offsets; dropped anyway.
 */
private fun MarkdownParsingException.raftMarkdownParseFailureKind(): String =
    (message ?: "")
        .substringBefore(':')
        .trim()
        .lowercase()
        .map { if (it in 'a'..'z' || it in '0'..'9') it else '_' }
        .joinToString("")
        .trim('_')
        .take(48)
        .ifEmpty { "unknown" }
