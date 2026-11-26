package build.raft.app

import build.raft.app.resources.*
import org.jetbrains.compose.resources.StringResource

/**
 * The expanded composer's formatting tools (botiverse/slock #16767 / #triage:#629,
 * `packages/web/src/features/composer/composerMarkdown.ts` & `ComposerFormatBar.tsx`):
 * each tool writes plain Markdown into the draft, the same text a person could type, so the
 * message is sent and rendered identically across web and mobile.
 *
 * - [Wrap]: around the selection (`**bold**`); with nothing selected the caret lands between the
 *   two marks. With [Wrap.ownLines] true (a code fence), a line break goes before or after where
 *   the text does not already break.
 * - [Line]: at the start of each line the selection touches (`- `, `> `, `1. `); a selection that
 *   ends immediately after a line break leaves the following line alone.
 */
internal sealed interface ThreadComposerMarkdownTool {
    data class Wrap(
        val before: String,
        val after: String,
        val ownLines: Boolean = false,
    ) : ThreadComposerMarkdownTool

    data class Line(
        val prefix: String,
    ) : ThreadComposerMarkdownTool
}

internal data class ThreadComposerMarkdownEdit(
    /** The range in the original value that [text] replaces. */
    val start: Int,
    val end: Int,
    val text: String,
    /** The resulting full text after applying the edit. */
    val nextValue: String,
    /** The selection after the insertion. */
    val selectionStart: Int,
    val selectionEnd: Int,
)

/** What [tool] inserts into [value] with the selection [start]..[end] (exact port of web `composerMarkdownEdit`). */
internal fun threadComposerMarkdownEdit(
    value: String,
    start: Int,
    end: Int,
    tool: ThreadComposerMarkdownTool,
): ThreadComposerMarkdownEdit {
    val selStart = minOf(start, end).coerceIn(0, value.length)
    val selEnd = maxOf(start, end).coerceIn(selStart, value.length)
    return when (tool) {
        is ThreadComposerMarkdownTool.Wrap -> {
            val selected = value.substring(selStart, selEnd)
            val before =
                if (tool.ownLines && selStart > 0 && value[selStart - 1] != '\n') {
                    "\n${tool.before}"
                } else {
                    tool.before
                }
            val after =
                if (tool.ownLines && selEnd < value.length && value[selEnd] != '\n') {
                    "${tool.after}\n"
                } else {
                    tool.after
                }
            val text = "$before$selected$after"
            val innerStart = selStart + before.length
            ThreadComposerMarkdownEdit(
                start = selStart,
                end = selEnd,
                text = text,
                nextValue = value.substring(0, selStart) + text + value.substring(selEnd),
                selectionStart = innerStart,
                selectionEnd = innerStart + selected.length,
            )
        }
        is ThreadComposerMarkdownTool.Line -> {
            val stop = if (selEnd > selStart && value[selEnd - 1] == '\n') selEnd - 1 else selEnd
            val lineStart = if (selStart == 0) 0 else value.lastIndexOf('\n', selStart - 1) + 1
            val lines = value.substring(lineStart, stop).split('\n')
            val text = lines.joinToString("\n") { line -> "${tool.prefix}$line" }
            val caret = lineStart + text.length
            ThreadComposerMarkdownEdit(
                start = lineStart,
                end = stop,
                text = text,
                nextValue = value.substring(0, lineStart) + text + value.substring(stop),
                selectionStart = if (selStart == selEnd) caret else lineStart,
                selectionEnd = caret,
            )
        }
    }
}

internal data class ThreadComposerFormatAction(
    val id: String,
    val labelRes: StringResource,
    val icon: RaftLucideIconName,
    val tool: ThreadComposerMarkdownTool,
)

/**
 * Three groups matching web `ComposerFormatBar.tsx`:
 * - text inline (bold, italic, strikethrough)
 * - code / link inline (code, link)
 * - block tools (numbered list, bulleted list, quote, code block — fold into overflow menu on narrow columns)
 */
internal val ThreadComposerInlineFormatGroups: List<List<ThreadComposerFormatAction>> =
    listOf(
        listOf(
            ThreadComposerFormatAction(
                id = "bold",
                labelRes = Res.string.composer_format_bold,
                icon = RaftLucideIconName.Bold,
                tool = ThreadComposerMarkdownTool.Wrap(before = "**", after = "**"),
            ),
            ThreadComposerFormatAction(
                id = "italic",
                labelRes = Res.string.composer_format_italic,
                icon = RaftLucideIconName.Italic,
                tool = ThreadComposerMarkdownTool.Wrap(before = "_", after = "_"),
            ),
            ThreadComposerFormatAction(
                id = "strikethrough",
                labelRes = Res.string.composer_format_strikethrough,
                icon = RaftLucideIconName.Strikethrough,
                tool = ThreadComposerMarkdownTool.Wrap(before = "~~", after = "~~"),
            ),
        ),
        listOf(
            ThreadComposerFormatAction(
                id = "code",
                labelRes = Res.string.composer_format_code,
                icon = RaftLucideIconName.Code,
                tool = ThreadComposerMarkdownTool.Wrap(before = "`", after = "`"),
            ),
            ThreadComposerFormatAction(
                id = "link",
                labelRes = Res.string.composer_format_link,
                icon = RaftLucideIconName.Link,
                tool = ThreadComposerMarkdownTool.Wrap(before = "[", after = "](https://)"),
            ),
        ),
    )

internal val ThreadComposerBlockFormatActions: List<ThreadComposerFormatAction> =
    listOf(
        ThreadComposerFormatAction(
            id = "numbered-list",
            labelRes = Res.string.composer_format_numbered_list,
            icon = RaftLucideIconName.ListOrdered,
            tool = ThreadComposerMarkdownTool.Line(prefix = "1. "),
        ),
        ThreadComposerFormatAction(
            id = "bulleted-list",
            labelRes = Res.string.composer_format_bulleted_list,
            icon = RaftLucideIconName.List,
            tool = ThreadComposerMarkdownTool.Line(prefix = "- "),
        ),
        ThreadComposerFormatAction(
            id = "quote",
            labelRes = Res.string.composer_format_quote,
            icon = RaftLucideIconName.Quote,
            tool = ThreadComposerMarkdownTool.Line(prefix = "> "),
        ),
        ThreadComposerFormatAction(
            id = "code-block",
            labelRes = Res.string.composer_format_code_block,
            icon = RaftLucideIconName.SquareCode,
            tool = ThreadComposerMarkdownTool.Wrap(before = "```\n", after = "\n```", ownLines = true),
        ),
    )
