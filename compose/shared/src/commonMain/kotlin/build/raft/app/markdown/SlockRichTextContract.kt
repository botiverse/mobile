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
    val styleTokenId: String? = chrome?.styleToken?.id,
    val actionId: String? = null,
    val refKind: SlockRichTextRefKind? = null,
    val trailingIndicator: String = ""
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

val SlockRichTextChromeKind.styleToken: SlockRichTextChromeStyleToken
    get() =
        when (this) {
            SlockRichTextChromeKind.InlineCode -> SlockRichTextChromeStyleTokens.InlineCode
            SlockRichTextChromeKind.Channel -> SlockRichTextChromeStyleTokens.Channel
            SlockRichTextChromeKind.Thread -> SlockRichTextChromeStyleTokens.Thread
            SlockRichTextChromeKind.Task -> SlockRichTextChromeStyleTokens.Task
            SlockRichTextChromeKind.SelfMention -> SlockRichTextChromeStyleTokens.SelfMention
            SlockRichTextChromeKind.OrdinaryMention -> SlockRichTextChromeStyleTokens.OrdinaryMention
            SlockRichTextChromeKind.Active -> SlockRichTextChromeStyleTokens.Active
        }

data class SlockRichTextChromeStyleToken(
    val id: String,
    val foreground: SlockRichTextColorToken = SlockRichTextColorToken.TextBlack,
    val background: SlockRichTextColorToken? = null,
    val chipFill: SlockRichTextColorToken? = background,
    val fontFamily: SlockRichTextFontFamilyToken = SlockRichTextFontFamilyToken.SlockUi,
    val fontWeight: SlockRichTextFontWeightToken = SlockRichTextFontWeightToken.Bold,
    val fontSizeSp: Float? = 14f,
    val textDecoration: SlockRichTextTextDecorationToken? = null,
    val textDecorationColor: SlockRichTextColorToken? = null,
    val textDecorationThicknessSp: Float? = null,
    val textDecorationOffsetSp: Float? = null,
    val horizontalChromeSp: Float? = null
)

enum class SlockRichTextColorToken(val wireValue: String) {
    TextBlack("textBlack"),
    Black5("black5"),
    BrandYellow("brandYellow"),
    BrandYellow40("brandYellow40"),
    AccentPink30("accentPink30"),
    AccentCyan30("accentCyan30"),
    SoftSignal("softSignal"),
    BorderBlack30("borderBlack30")
}

enum class SlockRichTextFontFamilyToken(val wireValue: String) {
    SlockUi("slockUi"),
    SlockMono("slockMono")
}

enum class SlockRichTextFontWeightToken(val wireValue: String) {
    Normal("normal"),
    SemiBold("semiBold"),
    Bold("bold")
}

enum class SlockRichTextTextDecorationToken(val wireValue: String) {
    Underline("underline")
}

object SlockRichTextChromeStyleTokens {
    val InlineCode =
        SlockRichTextChromeStyleToken(
            id = "inlineCode",
            foreground = SlockRichTextColorToken.TextBlack,
            background = null,
            chipFill = SlockRichTextColorToken.Black5,
            fontFamily = SlockRichTextFontFamilyToken.SlockMono,
            fontWeight = SlockRichTextFontWeightToken.Normal
        )
    val Channel =
        referenceUnderline(
            id = "channel",
            chipFill = SlockRichTextColorToken.AccentPink30
        )
    val Thread =
        referenceUnderline(
            id = "thread",
            chipFill = SlockRichTextColorToken.AccentCyan30
        )
    val Task =
        referenceUnderline(
            id = "task",
            chipFill = SlockRichTextColorToken.BrandYellow40
        )
    val OrdinaryMention =
        referenceUnderline(
            id = "ordinaryMention",
            chipFill = SlockRichTextColorToken.BrandYellow40
        )
    val SelfMention =
        referenceChip(
            id = "selfMention"
        )
    val Active =
        referenceChip(
            id = "active"
        )

    private fun referenceUnderline(
        id: String,
        chipFill: SlockRichTextColorToken
    ): SlockRichTextChromeStyleToken =
        SlockRichTextChromeStyleToken(
            id = id,
            chipFill = chipFill,
            textDecoration = SlockRichTextTextDecorationToken.Underline,
            textDecorationColor = SlockRichTextColorToken.BorderBlack30,
            textDecorationThicknessSp = SLOCK_RICHTEXT_REFERENCE_UNDERLINE_THICKNESS_SP,
            textDecorationOffsetSp = SLOCK_RICHTEXT_REFERENCE_UNDERLINE_OFFSET_SP,
            horizontalChromeSp = SLOCK_RICHTEXT_INLINE_CHIP_HORIZONTAL_CHROME_SP
        )

    private fun referenceChip(id: String): SlockRichTextChromeStyleToken =
        SlockRichTextChromeStyleToken(
            id = id,
            background = SlockRichTextColorToken.SoftSignal,
            chipFill = SlockRichTextColorToken.SoftSignal,
            horizontalChromeSp = SLOCK_RICHTEXT_INLINE_CHIP_HORIZONTAL_CHROME_SP
        )
}

// Keep semantic metadata distinct from Kuikly's legacy
// `raft.build.markdown.inlineCode` renderer marker. The legacy marker injects
// yellow framed chrome and fixed margins; current renderers consume the
// ordinary wrapping SpanStyle background instead.
const val SLOCK_RICHTEXT_INLINE_CODE_ANNOTATION_TAG = "raft.build.markdown.inlineCodeMetadata"
const val SLOCK_RICHTEXT_INLINE_CODE_TRAILING_MARGIN_ANNOTATION_TAG =
    "raft.build.markdown.inlineCodeTrailingMarginMetadata"
const val SLOCK_RICHTEXT_INLINE_CODE_CHAR_WRAP_BREAK = '\u200B'
const val SLOCK_RICHTEXT_INLINE_CHIP_HORIZONTAL_CHROME_SP = 12f
const val SLOCK_RICHTEXT_REFERENCE_UNDERLINE_THICKNESS_SP = 2f
const val SLOCK_RICHTEXT_REFERENCE_UNDERLINE_OFFSET_SP = 2f

enum class SlockRichTextRefKind(val wireValue: String) {
    Channel("channel"),
    Thread("thread"),
    DirectMessageThread("directMessageThread"),
    Task("task"),
    UserMention("userMention")
}

data class SlockRichTextAction(
    val id: String,
    val kind: SlockRichTextActionKind,
    val refKind: SlockRichTextRefKind,
    val label: String,
    val channelName: String? = null,
    val threadShortId: String? = null,
    val directMessagePeerName: String? = null
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
    // Viewer identity is render-context data, not a stable message fact.
    // The same @mention can be self chrome for one viewer and ordinary
    // mention chrome for another.
    val viewerUserId: String? = null,
    val highlightedMentions: Set<String> = emptySet(),
    val knownTaskNumbers: Set<Int>? = null,
    val activeTagKey: String? = null,
    val inlineContentScope: String? = null,
    val knownDirectMessagePeers: Set<String> = emptySet()
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
            highlightedMentions = context.viewerScopedHighlightedMentions(),
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
    activeTagKey: String? = null,
    activeActionId: String? = null,
    activeActionIndicator: String = ""
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
                val actionPending = activeActionId == action.id
                actions += action
                tokens +=
                    SlockRichTextInlineToken(
                        id = token.id,
                        kind = SlockRichTextInlineKind.SlockRef,
                        range = token.range.toRichTextRange(),
                        text =
                            (token.resolvedLabel ?: threadMarkdownTagDisplayLabel(tag.label)) +
                                activeActionIndicator.takeIf { actionPending }.orEmpty(),
                        sourceText = content.substring(token.range),
                        chrome =
                            tag.toRichTextChromeKind(
                                highlighted = token.highlighted,
                                active = actionPending || activeTagKey == threadMarkdownTagActivationKey(tag)
                            ),
                        actionId = action.id,
                        refKind = tag.kind.toRichTextRefKind(),
                        trailingIndicator = activeActionIndicator.takeIf { actionPending }.orEmpty()
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
    val directMessagePeerKeys =
        knownDirectMessagePeers.mapTo(mutableSetOf()) { it.removePrefix("@").trim().lowercase() }
    return { tag ->
        when (tag.kind) {
            ThreadMarkdownTagKind.Task ->
                knownTaskNumbers?.contains(tag.label.removePrefix("#").toIntOrNull()) ?: true
            ThreadMarkdownTagKind.UserMention ->
                mentionKeys.contains(tag.label.removePrefix("@").trim().lowercase())
            ThreadMarkdownTagKind.DirectMessageThread ->
                directMessagePeerKeys.contains(tag.directMessagePeerName.orEmpty().trim().lowercase()) &&
                    !tag.threadShortId.isNullOrBlank()
            ThreadMarkdownTagKind.Channel ->
                channelKeys.contains(tag.channelName.orEmpty().trim().lowercase())
            ThreadMarkdownTagKind.ChannelThread ->
                channelKeys.contains(tag.channelName.orEmpty().trim().lowercase()) &&
                    !tag.threadShortId.isNullOrBlank()
        }
    }
}

private fun SlockRichTextContext.viewerScopedHighlightedMentions(): Set<String> =
    buildSet {
        addAll(highlightedMentions)
        viewerUserId?.takeIf { it.isNotBlank() }?.let(::add)
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
        threadShortId = threadShortId,
        directMessagePeerName = directMessagePeerName
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
        kind == ThreadMarkdownTagKind.DirectMessageThread -> SlockRichTextChromeKind.Thread
        kind == ThreadMarkdownTagKind.Task -> SlockRichTextChromeKind.Task
        else -> SlockRichTextChromeKind.OrdinaryMention
    }

private fun ThreadMarkdownTagKind.toRichTextRefKind(): SlockRichTextRefKind =
    when (this) {
        ThreadMarkdownTagKind.Channel -> SlockRichTextRefKind.Channel
        ThreadMarkdownTagKind.ChannelThread -> SlockRichTextRefKind.Thread
        ThreadMarkdownTagKind.DirectMessageThread -> SlockRichTextRefKind.DirectMessageThread
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
        styleTokenId?.let {
            append(",")
            appendJsonField("styleTokenId", it)
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
        directMessagePeerName?.let {
            append(",")
            appendJsonField("directMessagePeerName", it)
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
