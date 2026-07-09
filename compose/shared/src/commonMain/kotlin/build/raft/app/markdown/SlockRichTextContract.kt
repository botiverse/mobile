package build.raft.app.markdown

import com.tencent.kuiklybase.markdown.model.MarkdownParserContent

data class SlockRichTextDocument(
    val source: String,
    val content: String,
    val blocks: List<SlockRichTextBlock>,
    val inlineTokens: List<SlockRichTextInlineToken>,
    val actions: List<SlockRichTextAction>
)

data class SlockRichTextBlock(
    val id: String,
    val kind: SlockRichTextBlockKind,
    val range: SlockRichTextRange,
    val inlineTokenIds: List<String>
)

enum class SlockRichTextBlockKind(val wireValue: String) {
    Paragraph("paragraph")
}

data class SlockRichTextInlineToken(
    val id: String,
    val kind: SlockRichTextInlineKind,
    val range: SlockRichTextRange,
    val text: String,
    val sourceText: String,
    val chrome: SlockRichTextChromeKind? = null,
    val actionId: String? = null,
    val refKind: SlockRichTextRefKind? = null
)

enum class SlockRichTextInlineKind(val wireValue: String) {
    Text("text"),
    InlineCode("inlineCode"),
    SlockRef("slockRef")
}

enum class SlockRichTextChromeKind(val wireValue: String) {
    InlineCode("inlineCode"),
    Channel("channel"),
    Thread("thread"),
    Task("task"),
    SelfMention("selfMention"),
    OrdinaryMention("ordinaryMention"),
    Active("active")
}

enum class SlockRichTextRefKind(val wireValue: String) {
    Channel("channel"),
    Thread("thread"),
    Task("task"),
    UserMention("userMention")
}

data class SlockRichTextAction(
    val id: String,
    val kind: SlockRichTextActionKind,
    val refKind: SlockRichTextRefKind,
    val label: String,
    val channelName: String? = null,
    val threadShortId: String? = null
)

enum class SlockRichTextActionKind(val wireValue: String) {
    OpenSlockRef("openSlockRef")
}

data class SlockRichTextRange(
    val start: Int,
    val endExclusive: Int
)

data class SlockRichTextContext(
    val knownChannels: Set<String> = emptySet(),
    val knownMentions: Set<String> = emptySet(),
    val highlightedMentions: Set<String> = emptySet(),
    val knownTaskNumbers: Set<Int>? = null,
    val activeTagKey: String? = null,
    val inlineContentScope: String? = null
)

/**
 * Visual-test metadata key for renderer capability declarations.
 *
 * Capability booleans are nullable on purpose: a missing value means unknown,
 * not unsupported. Declared divergences are documentation for review and visual
 * reports; they do not auto-accept or suppress visual diffs.
 */
const val SLOCK_RICHTEXT_CAPS = "SLOCK_RICHTEXT_CAPS"

data class SlockRichTextCapabilities(
    val consumesDocument: Boolean? = null,
    val consumesChromeTokens: Boolean? = null,
    val consumesActions: Boolean? = null,
    val consumesStyleTokens: Boolean? = null,
    val hasMeasurementAdapter: Boolean? = null,
    val hasDrawAdapter: Boolean? = null,
    val hasHitTestAdapter: Boolean? = null,
    val declaredDivergences: List<SlockRichTextDeclaredDivergence> = emptyList()
)

data class SlockRichTextDeclaredDivergence(
    val id: String,
    val description: String
)

val richTextCapabilities: SlockRichTextCapabilities = SlockRichTextCapabilities()

fun buildSlockRichTextDocument(
    body: String,
    context: SlockRichTextContext = SlockRichTextContext()
): SlockRichTextDocument {
    val content = body.normalizeThreadMarkdownBody()
    val parserContent = MarkdownParserContent.from(content).parserContent
    val validator = context.toMarkdownTagValidator()
    val plan =
        SlockMarkdownInlineTokenRegistry.plan(
            parserContent,
            highlightedMentions = context.highlightedMentions,
            isTagValid = validator,
            inlineContentScope = context.inlineContentScope
        )
    return buildSlockRichTextDocumentFromMarkdownPlan(
        source = body,
        content = parserContent,
        tokenPlan = plan,
        activeTagKey = context.activeTagKey
    )
}

internal fun buildSlockRichTextDocumentFromMarkdownPlan(
    source: String,
    content: String,
    tokenPlan: MarkdownInlineTokenPlan,
    activeTagKey: String? = null
): SlockRichTextDocument {
    val richTokenSources =
        buildList {
            addAll(tokenPlan.tagTokens().map { SlockRichTextTokenSource.Ref(it) })
            addAll(tokenPlan.inlineCodeMatches().map { SlockRichTextTokenSource.InlineCode(it) })
        }.sortedWith(compareBy<SlockRichTextTokenSource> { it.start }.thenBy { it.endExclusive })

    val tokens = mutableListOf<SlockRichTextInlineToken>()
    val actions = mutableListOf<SlockRichTextAction>()
    var cursor = 0
    richTokenSources.forEach { tokenSource ->
        if (tokenSource.start < cursor) return@forEach
        if (tokenSource.start > cursor) {
            tokens += content.textToken(cursor, tokenSource.start)
        }
        when (tokenSource) {
            is SlockRichTextTokenSource.InlineCode -> {
                val match = tokenSource.match
                tokens +=
                    SlockRichTextInlineToken(
                        id = match.id,
                        kind = SlockRichTextInlineKind.InlineCode,
                        range = match.range.toRichTextRange(),
                        text = match.code,
                        sourceText = content.substring(match.range),
                        chrome = SlockRichTextChromeKind.InlineCode
                    )
            }
            is SlockRichTextTokenSource.Ref -> {
                val token = tokenSource.token
                val tag = token.tag ?: return@forEach
                val action = tag.toRichTextAction(token.id)
                actions += action
                tokens +=
                    SlockRichTextInlineToken(
                        id = token.id,
                        kind = SlockRichTextInlineKind.SlockRef,
                        range = token.range.toRichTextRange(),
                        text = threadMarkdownTagDisplayLabel(tag.label),
                        sourceText = content.substring(token.range),
                        chrome =
                            tag.toRichTextChromeKind(
                                highlighted = token.highlighted,
                                active = activeTagKey == threadMarkdownTagActivationKey(tag)
                            ),
                        actionId = action.id,
                        refKind = tag.kind.toRichTextRefKind()
                    )
            }
        }
        cursor = tokenSource.endExclusive
    }
    if (cursor < content.length) {
        tokens += content.textToken(cursor, content.length)
    }

    val block =
        SlockRichTextBlock(
            id = "block:0",
            kind = SlockRichTextBlockKind.Paragraph,
            range = SlockRichTextRange(0, content.length),
            inlineTokenIds = tokens.map { it.id }
        )
    return SlockRichTextDocument(
        source = source,
        content = content,
        blocks = listOf(block),
        inlineTokens = tokens,
        actions = actions
    )
}

fun SlockRichTextDocument.toGoldenJson(): String =
    buildString {
        append("{")
        appendJsonField("source", source)
        append(",")
        appendJsonField("content", content)
        append(",\"blocks\":[")
        blocks.forEachIndexed { index, block ->
            if (index > 0) append(",")
            append(block.toGoldenJson())
        }
        append("],\"inlineTokens\":[")
        inlineTokens.forEachIndexed { index, token ->
            if (index > 0) append(",")
            append(token.toGoldenJson())
        }
        append("],\"actions\":[")
        actions.forEachIndexed { index, action ->
            if (index > 0) append(",")
            append(action.toGoldenJson())
        }
        append("]}")
    }

internal fun MarkdownInlineTokenPlan.inlineCodeMatches(): List<ThreadInlineCodeMatch> =
    inlineCodeMatchesForRichText()

private sealed class SlockRichTextTokenSource {
    abstract val start: Int
    abstract val endExclusive: Int

    data class Ref(val token: MarkdownInlineToken) : SlockRichTextTokenSource() {
        override val start: Int = token.range.first
        override val endExclusive: Int = token.range.last + 1
    }

    data class InlineCode(val match: ThreadInlineCodeMatch) : SlockRichTextTokenSource() {
        override val start: Int = match.range.first
        override val endExclusive: Int = match.range.last + 1
    }
}

private fun SlockRichTextContext.toMarkdownTagValidator(): (ThreadMarkdownTag) -> Boolean {
    val channelKeys = knownChannels.mapTo(mutableSetOf()) { it.trim().lowercase() }
    val mentionKeys = knownMentions.mapTo(mutableSetOf()) { it.removePrefix("@").trim().lowercase() }
    return { tag ->
        when (tag.kind) {
            ThreadMarkdownTagKind.Task ->
                knownTaskNumbers?.contains(tag.label.removePrefix("#").toIntOrNull()) ?: true
            ThreadMarkdownTagKind.UserMention ->
                mentionKeys.contains(tag.label.removePrefix("@").trim().lowercase())
            ThreadMarkdownTagKind.Channel ->
                channelKeys.contains(tag.channelName.orEmpty().trim().lowercase())
            ThreadMarkdownTagKind.ChannelThread ->
                channelKeys.contains(tag.channelName.orEmpty().trim().lowercase()) &&
                    !tag.threadShortId.isNullOrBlank()
        }
    }
}

private fun String.textToken(start: Int, endExclusive: Int): SlockRichTextInlineToken =
    SlockRichTextInlineToken(
        id = "text:$start-$endExclusive",
        kind = SlockRichTextInlineKind.Text,
        range = SlockRichTextRange(start, endExclusive),
        text = substring(start, endExclusive),
        sourceText = substring(start, endExclusive)
    )

private fun ThreadMarkdownTag.toRichTextAction(id: String): SlockRichTextAction =
    SlockRichTextAction(
        id = id,
        kind = SlockRichTextActionKind.OpenSlockRef,
        refKind = kind.toRichTextRefKind(),
        label = label,
        channelName = channelName,
        threadShortId = threadShortId
    )

private fun ThreadMarkdownTag.toRichTextChromeKind(
    highlighted: Boolean,
    active: Boolean
): SlockRichTextChromeKind =
    when {
        active -> SlockRichTextChromeKind.Active
        kind == ThreadMarkdownTagKind.UserMention && highlighted -> SlockRichTextChromeKind.SelfMention
        kind == ThreadMarkdownTagKind.UserMention -> SlockRichTextChromeKind.OrdinaryMention
        kind == ThreadMarkdownTagKind.Channel -> SlockRichTextChromeKind.Channel
        kind == ThreadMarkdownTagKind.ChannelThread -> SlockRichTextChromeKind.Thread
        kind == ThreadMarkdownTagKind.Task -> SlockRichTextChromeKind.Task
        else -> SlockRichTextChromeKind.OrdinaryMention
    }

private fun ThreadMarkdownTagKind.toRichTextRefKind(): SlockRichTextRefKind =
    when (this) {
        ThreadMarkdownTagKind.Channel -> SlockRichTextRefKind.Channel
        ThreadMarkdownTagKind.ChannelThread -> SlockRichTextRefKind.Thread
        ThreadMarkdownTagKind.Task -> SlockRichTextRefKind.Task
        ThreadMarkdownTagKind.UserMention -> SlockRichTextRefKind.UserMention
    }

private fun IntRange.toRichTextRange(): SlockRichTextRange =
    SlockRichTextRange(first, last + 1)

private fun StringBuilder.appendJsonField(name: String, value: String) {
    append("\"")
    append(name)
    append("\":")
    appendJsonString(value)
}

private fun SlockRichTextBlock.toGoldenJson(): String =
    buildString {
        append("{")
        appendJsonField("id", id)
        append(",")
        appendJsonField("kind", kind.wireValue)
        append(",\"range\":")
        append(range.toGoldenJson())
        append(",\"inlineTokenIds\":")
        appendStringArray(inlineTokenIds)
        append("}")
    }

private fun SlockRichTextInlineToken.toGoldenJson(): String =
    buildString {
        append("{")
        appendJsonField("id", id)
        append(",")
        appendJsonField("kind", kind.wireValue)
        append(",\"range\":")
        append(range.toGoldenJson())
        append(",")
        appendJsonField("text", text)
        append(",")
        appendJsonField("sourceText", sourceText)
        chrome?.let {
            append(",")
            appendJsonField("chrome", it.wireValue)
        }
        actionId?.let {
            append(",")
            appendJsonField("actionId", it)
        }
        refKind?.let {
            append(",")
            appendJsonField("refKind", it.wireValue)
        }
        append("}")
    }

private fun SlockRichTextAction.toGoldenJson(): String =
    buildString {
        append("{")
        appendJsonField("id", id)
        append(",")
        appendJsonField("kind", kind.wireValue)
        append(",")
        appendJsonField("refKind", refKind.wireValue)
        append(",")
        appendJsonField("label", label)
        channelName?.let {
            append(",")
            appendJsonField("channelName", it)
        }
        threadShortId?.let {
            append(",")
            appendJsonField("threadShortId", it)
        }
        append("}")
    }

private fun SlockRichTextRange.toGoldenJson(): String =
    "[$start,$endExclusive]"

private fun StringBuilder.appendStringArray(values: List<String>) {
    append("[")
    values.forEachIndexed { index, value ->
        if (index > 0) append(",")
        appendJsonString(value)
    }
    append("]")
}

private fun StringBuilder.appendJsonString(value: String) {
    append("\"")
    value.forEach { char ->
        when (char) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(char)
        }
    }
    append("\"")
}
