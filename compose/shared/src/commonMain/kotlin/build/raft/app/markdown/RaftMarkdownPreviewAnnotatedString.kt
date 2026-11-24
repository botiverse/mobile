package build.raft.app.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import build.raft.app.RaftAppThemeState

/**
 * Compose UI projection of [RaftMarkdownPreview] into an [AnnotatedString]. Kept separate from
 * [RaftMarkdownPreviewText] so `:shared-data` can call [markdownToPreviewText] without pulling in
 * [RaftAppThemeState] or Compose text styling.
 */
fun RaftMarkdownPreview.toAnnotatedString(
    linkColor: Color = RaftAppThemeState.colors.foregroundLink,
): AnnotatedString {
    if (spans.isEmpty()) return AnnotatedString(text)
    val spanStyles =
        spans.map { span -> AnnotatedString.Range(span.style.toSpanStyle(linkColor), span.start, span.end) }
    return AnnotatedString(text, spanStyles = spanStyles)
}

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
