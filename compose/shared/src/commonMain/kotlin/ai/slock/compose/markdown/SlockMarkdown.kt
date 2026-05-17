package ai.slock.compose.markdown

import ai.slock.compose.SlockColor
import ai.slock.compose.SlockSpacing
import ai.slock.compose.SlockStroke
import ai.slock.compose.SlockType
import androidx.compose.runtime.Composable
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.text.InlineTextContent
import com.tencent.kuikly.compose.foundation.text.appendInlineContent
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.text.Placeholder
import com.tencent.kuikly.compose.ui.text.font.FontFamily
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.kuiklybase.markdown.model.MarkdownAnnotator
import com.tencent.kuiklybase.markdown.model.markdownAnnotator
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.getTextInNode

data class ThreadMarkdownTag(val label: String, val kind: ThreadMarkdownTagKind)

enum class ThreadMarkdownTagKind {
    Channel,
    Task,
    UserMention
}

data class ThreadInlineTagMatch(
    val id: String,
    val tag: ThreadMarkdownTag,
    val range: IntRange
)

data class ThreadInlineCodeMatch(
    val id: String,
    val code: String,
    val range: IntRange
)

data class SlockMarkdownPreviewSample(
    val title: String,
    val body: String,
    val description: String
)

object SlockMarkdownPreviewSamples {
    val inlineTags =
        SlockMarkdownPreviewSample(
            title = "Inline tags",
            description = "Channels, task references, and user mentions should render as inline chips.",
            body = "Please review #22 with @alice in #android-dev, then compare #382-#383."
        )

    val markdownBlocks =
        SlockMarkdownPreviewSample(
            title = "Markdown blocks",
            description = "Lists, quotes, paragraphs, and inline code should keep normal markdown behavior.",
            body =
                """
                **Markdown** body under the sender:

                - lists stay left aligned
                - `inline code #999` stays code, not a task chip
                - tags like #283 and #android-dev become clickable chips

                > quoted text remains inside the message column
                """.trimIndent()
        )

    val codeFence =
        SlockMarkdownPreviewSample(
            title = "Code fence",
            description = "Code fences should keep syntax highlighting and avoid inline tag replacement.",
            body =
                """
                ```kotlin
                val task = "#123 should stay code"
                println(task)
                ```
                """.trimIndent()
        )

    val image =
        SlockMarkdownPreviewSample(
            title = "Image",
            description = "Remote markdown images should render through the platform image pipeline.",
            body = "![Slock preview](https://picsum.photos/seed/slock-markdown/480/240)"
        )

    val all = listOf(inlineTags, markdownBlocks, codeFence, image)
}

fun String.normalizeThreadMarkdownBody(): String {
    if (!contains("\\n") && !contains("\\r")) return this

    val shouldDecodeEscapedLineBreaks =
        threadEscapedMarkdownLineBreakRegex().containsMatchIn(this) ||
            (!contains('\n') && !contains('\r') && contains("\\n"))
    if (!shouldDecodeEscapedLineBreaks) return this

    return replace("\\r\\n", "\n").replace("\\n", "\n").replace("\\r", "\r")
}

private fun threadEscapedMarkdownLineBreakRegex(): Regex =
    Regex("""\\r?\\n(?:\\r?\\n|[-*+] |\d+\. |> |#|```|~~~)""")

fun threadMarkdownTaskReferenceKey(label: String): String =
    label.removePrefix("#").trim().lowercase()

fun threadMarkdownTagDisplayLabel(label: String): String {
    val trimmed = label.trim()
    if (trimmed.isEmpty()) return trimmed
    val prefix = if (trimmed.startsWith("@")) "@" else if (trimmed.startsWith("#")) "#" else ""
    if (prefix.isEmpty()) return trimmed
    return prefix + trimmed.dropWhile { it == prefix.first() }
}

private fun threadInlineTagRegex(): Regex =
    Regex(
        """(?<![\p{L}\p{N}_])@[\p{L}\p{N}][\p{L}\p{N}_.\-]{0,63}|(?<![\p{L}\p{N}_])#\d+|(?<![\p{L}\p{N}_])#[\p{L}][\p{L}\p{N}_-]{0,63}"""
    )

private fun String.toThreadMarkdownTagOrNull(): ThreadMarkdownTag? =
    when {
        startsWith('@') -> ThreadMarkdownTag(this, ThreadMarkdownTagKind.UserMention)
        drop(1).all { it.isDigit() } -> ThreadMarkdownTag(this, ThreadMarkdownTagKind.Task)
        startsWith('#') -> ThreadMarkdownTag(this, ThreadMarkdownTagKind.Channel)
        else -> null
    }

fun String.threadInlineTagMatches(baseOffset: Int = 0): List<ThreadInlineTagMatch> =
    threadInlineTagRegex().findAll(this).mapNotNull { match ->
        match.value.toThreadMarkdownTagOrNull()?.let { tag ->
            ThreadInlineTagMatch(
                id = "slock:${baseOffset + match.range.first}",
                tag = tag,
                range = match.range
            )
        }
    }.toList()

fun String.threadInlineCodeMatches(baseOffset: Int = 0): List<ThreadInlineCodeMatch> {
    val matches = mutableListOf<ThreadInlineCodeMatch>()
    var cursor = 0
    while (cursor < length) {
        val start = indexOf('`', startIndex = cursor)
        if (start < 0) break
        val tickCount = countBackticks(start)
        val fence = "`".repeat(tickCount)
        val end = indexOf(fence, startIndex = start + tickCount)
        if (end < 0) break
        val rawCode = substring(start + tickCount, end)
        matches +=
            ThreadInlineCodeMatch(
                id = "slock-code:${baseOffset + start}",
                code = rawCode.normalizeMarkdownInlineCodeText(),
                range = start until end + tickCount
            )
        cursor = end + tickCount
    }
    return matches
}

private fun String.countBackticks(start: Int): Int {
    var count = 0
    while (start + count < length && this[start + count] == '`') count++
    return count
}

private fun String.normalizeMarkdownInlineCodeText(): String =
    if (length >= 2 && first() == ' ' && last() == ' ' && any { it != ' ' }) {
        substring(1, lastIndex)
    } else {
        this
    }

fun slockMarkdownAnnotator(content: String): MarkdownAnnotator {
    val inlineTagMatches = content.threadInlineTagMatches()
    val inlineTagById = inlineTagMatches.associateBy { it.id }
    val inlineCodeMatches = content.threadInlineCodeMatches()
    val inlineCodeById = inlineCodeMatches.associateBy { it.id }
    return markdownAnnotator(
        annotate = { markdownContent, child, _ ->
            if (child.type == MarkdownElementTypes.CODE_SPAN) {
                val codeMatch =
                    inlineCodeById["slock-code:${child.startOffset}"]
                        ?: inlineCodeMatches.firstOrNull { child.startOffset in it.range }
                if (codeMatch != null) {
                    appendInlineContent(codeMatch.id, codeMatch.code)
                    return@markdownAnnotator true
                }
            }
            if (child.type != MarkdownTokenTypes.TEXT ||
                child.parent?.type == MarkdownElementTypes.CODE_SPAN
            ) {
                return@markdownAnnotator false
            }

            val nodeText = child.getTextInNode(markdownContent).toString()
            val nodeMatches =
                nodeText.threadInlineTagMatches(baseOffset = child.startOffset)
                    .filter { inlineTagById.containsKey(it.id) }
            if (nodeMatches.isEmpty()) return@markdownAnnotator false

            var cursor = 0
            nodeMatches.forEach { match ->
                if (match.range.first > cursor) {
                    append(nodeText.substring(cursor, match.range.first))
                }
                appendInlineContent(match.id, match.tag.label)
                cursor = match.range.last + 1
            }
            if (cursor < nodeText.length) append(nodeText.substring(cursor))
            true
        },
        inlineContent = {
            inlineTagById.mapValues { (_, match) ->
                val label = threadMarkdownTagDisplayLabel(match.tag.label)
                InlineTextContent(
                    Placeholder(
                        width = slockMarkdownInlineChipWidthSp(label),
                        height = SlockMarkdownInlineChipHeight
                    )
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        SlockMarkdownInlineChip(label, slockMarkdownTagColor(match.tag.kind))
                    }
                }
            } + inlineCodeById.mapValues { (_, match) ->
                InlineTextContent(
                    Placeholder(
                        width = slockMarkdownInlineCodeWidthSp(match.code),
                        height = SlockMarkdownInlineChipHeight
                    )
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        SlockMarkdownInlineCodeChip(match.code)
                    }
                }
            }
        }
    )
}

private val SlockMarkdownInlineChipHeight = 20.sp

private fun slockMarkdownInlineChipWidthSp(label: String) =
    (label.slockInlineTextWidthUnits() * 8 + 32).coerceAtLeast(28).sp

private fun slockMarkdownInlineCodeWidthSp(code: String) =
    (code.slockInlineTextWidthUnits() * 8 + 32).coerceAtLeast(28).sp

private fun String.slockInlineTextWidthUnits(): Int =
    fold(0) { total, char ->
        total + if (char.code > 0x7F) 2 else 1
    }

private fun slockMarkdownTagColor(kind: ThreadMarkdownTagKind): Color =
    when (kind) {
        ThreadMarkdownTagKind.Channel -> SlockColor.AccentCyan
        ThreadMarkdownTagKind.Task -> SlockColor.AccentOrange
        ThreadMarkdownTagKind.UserMention -> SlockColor.AccentLavender
    }

@Composable
private fun SlockMarkdownInlineChip(label: String, color: Color) {
    Box(
        modifier =
            Modifier
                .background(color)
                .border(SlockStroke.Thin, SlockColor.BorderBlack)
                .padding(start = 8.dp, end = 8.dp, top = 1.dp, bottom = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style = SlockType.Caption.copy(fontSize = 14.sp, lineHeight = 16.sp),
            color = SlockColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Visible
        )
    }
}

@Composable
private fun SlockMarkdownInlineCodeChip(code: String) {
    Box(
        modifier =
            Modifier
                .background(SlockColor.BrandYellow.copy(alpha = 0.4f))
                .border(SlockStroke.Thin, SlockColor.BorderBlack)
                .padding(start = 10.dp, end = 10.dp, top = 1.dp, bottom = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            code,
            style =
                SlockType.Caption.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    lineHeight = 16.sp
                ),
            color = SlockColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Visible
        )
    }
}
