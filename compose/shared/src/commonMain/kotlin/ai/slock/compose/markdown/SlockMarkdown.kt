package ai.slock.compose.markdown

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

private val ThreadEscapedMarkdownLineBreakRegex =
    Regex("""\\r?\\n(?:\\r?\\n|[-*+] |\d+\. |> |#|```|~~~)""")

fun String.normalizeThreadMarkdownBody(): String {
    if (!contains("\\n") && !contains("\\r")) return this

    val shouldDecodeEscapedLineBreaks =
        ThreadEscapedMarkdownLineBreakRegex.containsMatchIn(this) ||
            (!contains('\n') && !contains('\r') && contains("\\n"))
    if (!shouldDecodeEscapedLineBreaks) return this

    return replace("\\r\\n", "\n").replace("\\n", "\n").replace("\\r", "\r")
}

fun threadMarkdownTaskReferenceKey(label: String): String =
    label.removePrefix("#").trim().lowercase()

fun threadMarkdownTagDisplayLabel(label: String): String {
    val trimmed = label.trim()
    if (trimmed.isEmpty()) return trimmed
    val prefix = if (trimmed.startsWith("@")) "@" else if (trimmed.startsWith("#")) "#" else ""
    if (prefix.isEmpty()) return trimmed
    return prefix + trimmed.dropWhile { it == prefix.first() }
}

private val ThreadInlineTagRegex =
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
    ThreadInlineTagRegex.findAll(this).mapNotNull { match ->
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
