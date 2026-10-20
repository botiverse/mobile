package build.raft.app.markdown

import com.tencent.kuiklybase.markdown.model.MarkdownParserContent

data class RaftRichTextDocument(
    val source: String,
    val content: String,
    val blocks: List<RaftRichTextBlock>,
    val inlineTokens: List<RaftRichTextInlineToken>,
    val actions: List<RaftRichTextAction>
)

data class RaftRichTextBlock(
    val id: String,
    val kind: RaftRichTextBlockKind,
    val range: RaftRichTextRange,
    val inlineTokenIds: List<String>
)

enum class RaftRichTextBlockKind(val wireValue: String) {
    Paragraph("paragraph")
}

data class RaftRichTextInlineToken(
    val id: String,
    val kind: RaftRichTextInlineKind,
    val range: RaftRichTextRange,
    val text: String,
    val sourceText: String,
    val chrome: RaftRichTextChromeKind? = null,
    val styleTokenId: String? = chrome?.styleToken?.id,
    val actionId: String? = null,
    val refKind: RaftRichTextRefKind? = null,
    val trailingIndicator: String = "",
    val active: Boolean = false
)

enum class RaftRichTextInlineKind(val wireValue: String) {
    Text("text"),
    InlineCode("inlineCode"),
    RaftRef("slockRef")
}

enum class RaftRichTextChromeKind(val wireValue: String) {
    InlineCode("inlineCode"),
    Channel("channel"),
    Thread("thread"),
    Task("task"),
    SelfMention("selfMention"),
    OrdinaryMention("ordinaryMention"),
    Active("active")
}

val RaftRichTextChromeKind.styleToken: RaftRichTextChromeStyleToken
    get() =
        when (this) {
            RaftRichTextChromeKind.InlineCode -> RaftRichTextChromeStyleTokens.InlineCode
            RaftRichTextChromeKind.Channel -> RaftRichTextChromeStyleTokens.Channel
            RaftRichTextChromeKind.Thread -> RaftRichTextChromeStyleTokens.Thread
            RaftRichTextChromeKind.Task -> RaftRichTextChromeStyleTokens.Task
            RaftRichTextChromeKind.SelfMention -> RaftRichTextChromeStyleTokens.SelfMention
            RaftRichTextChromeKind.OrdinaryMention -> RaftRichTextChromeStyleTokens.OrdinaryMention
            RaftRichTextChromeKind.Active -> RaftRichTextChromeStyleTokens.Active
        }

data class RaftRichTextChromeStyleToken(
    val id: String,
    val foreground: RaftRichTextColorToken = RaftRichTextColorToken.TextBlack,
    val background: RaftRichTextColorToken? = null,
    val chipFill: RaftRichTextColorToken? = background,
    val fontFamily: RaftRichTextFontFamilyToken = RaftRichTextFontFamilyToken.RaftUi,
    val fontWeight: RaftRichTextFontWeightToken = RaftRichTextFontWeightToken.Bold,
    val fontSizeSp: Float? = 14f,
    val textDecoration: RaftRichTextTextDecorationToken? = null,
    val textDecorationColor: RaftRichTextColorToken? = null,
    val textDecorationThicknessSp: Float? = null,
    val textDecorationOffsetSp: Float? = null,
    val horizontalChromeSp: Float? = null
)

enum class RaftRichTextColorToken(val wireValue: String) {
    TextBlack("textBlack"),
    Black5("black5"),
    BrandYellow("brandYellow"),
    BrandYellow40("brandYellow40"),
    AccentPink30("accentPink30"),
    AccentCyan30("accentCyan30"),
    SoftSignal("softSignal"),
    BorderBlack30("borderBlack30")
}

enum class RaftRichTextFontFamilyToken(val wireValue: String) {
    RaftUi("slockUi"),
    RaftMono("slockMono")
}

enum class RaftRichTextFontWeightToken(val wireValue: String) {
    Normal("normal"),
    SemiBold("semiBold"),
    Bold("bold")
}

enum class RaftRichTextTextDecorationToken(val wireValue: String) {
    Underline("underline")
}

object RaftRichTextChromeStyleTokens {
    val InlineCode =
        RaftRichTextChromeStyleToken(
            id = "inlineCode",
            foreground = RaftRichTextColorToken.TextBlack,
            background = null,
            chipFill = RaftRichTextColorToken.Black5,
            fontFamily = RaftRichTextFontFamilyToken.RaftMono,
            fontWeight = RaftRichTextFontWeightToken.Normal
        )
    val Channel =
        referenceUnderline(
            id = "channel",
            chipFill = RaftRichTextColorToken.AccentPink30
        )
    val Thread =
        referenceUnderline(
            id = "thread",
            chipFill = RaftRichTextColorToken.AccentCyan30
        )
    val Task =
        referenceUnderline(
            id = "task",
            chipFill = RaftRichTextColorToken.BrandYellow40
        )
    val OrdinaryMention =
        referenceUnderline(
            id = "ordinaryMention",
            chipFill = RaftRichTextColorToken.BrandYellow40
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
        chipFill: RaftRichTextColorToken
    ): RaftRichTextChromeStyleToken =
        RaftRichTextChromeStyleToken(
            id = id,
            chipFill = chipFill,
            textDecoration = RaftRichTextTextDecorationToken.Underline,
            textDecorationColor = RaftRichTextColorToken.BorderBlack30,
            textDecorationThicknessSp = RAFT_RICHTEXT_REFERENCE_UNDERLINE_THICKNESS_SP,
            textDecorationOffsetSp = RAFT_RICHTEXT_REFERENCE_UNDERLINE_OFFSET_SP,
            horizontalChromeSp = RAFT_RICHTEXT_INLINE_CHIP_HORIZONTAL_CHROME_SP
        )

    private fun referenceChip(id: String): RaftRichTextChromeStyleToken =
        RaftRichTextChromeStyleToken(
            id = id,
            background = RaftRichTextColorToken.SoftSignal,
            chipFill = RaftRichTextColorToken.SoftSignal,
            horizontalChromeSp = RAFT_RICHTEXT_INLINE_CHIP_HORIZONTAL_CHROME_SP
        )
}

// Keep semantic metadata distinct from Kuikly's legacy
// `raft.build.markdown.inlineCode` renderer marker. The legacy marker injects
// yellow framed chrome and fixed margins; current renderers consume the
// ordinary wrapping SpanStyle background instead.
const val RAFT_RICHTEXT_INLINE_CODE_ANNOTATION_TAG = "raft.build.markdown.inlineCodeMetadata"
const val RAFT_RICHTEXT_INLINE_CODE_TRAILING_MARGIN_ANNOTATION_TAG =
    "raft.build.markdown.inlineCodeTrailingMarginMetadata"
const val RAFT_RICHTEXT_INLINE_CODE_CHAR_WRAP_BREAK = '\u200B'
const val RAFT_RICHTEXT_INLINE_CHIP_HORIZONTAL_CHROME_SP = 12f
const val RAFT_RICHTEXT_REFERENCE_UNDERLINE_THICKNESS_SP = 2f
const val RAFT_RICHTEXT_REFERENCE_UNDERLINE_OFFSET_SP = 2f

enum class RaftRichTextRefKind(val wireValue: String) {
    Channel("channel"),
    Thread("thread"),
    DirectMessageThread("directMessageThread"),
    DirectMessage("directMessage"),
    Task("task"),
    UserMention("userMention")
}

data class RaftRichTextAction(
    val id: String,
    val kind: RaftRichTextActionKind,
    val refKind: RaftRichTextRefKind,
    val label: String,
    val channelName: String? = null,
    val threadShortId: String? = null,
    val directMessagePeerName: String? = null
)

enum class RaftRichTextActionKind(val wireValue: String) {
    OpenRaftRef("openSlockRef")
}

data class RaftRichTextRange(
    val start: Int,
    val endExclusive: Int
)

data class RaftRichTextContext(
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
const val RAFT_RICHTEXT_CAPS = "SLOCK_RICHTEXT_CAPS"

data class RaftRichTextCapabilities(
    val consumesDocument: Boolean? = null,
    val consumesChromeTokens: Boolean? = null,
    val consumesActions: Boolean? = null,
    val consumesStyleTokens: Boolean? = null,
    val hasMeasurementAdapter: Boolean? = null,
    val hasDrawAdapter: Boolean? = null,
    val hasHitTestAdapter: Boolean? = null,
    val declaredDivergences: List<RaftRichTextDeclaredDivergence> = emptyList()
)

data class RaftRichTextDeclaredDivergence(
    val id: String,
    val description: String
)

val richTextCapabilities: RaftRichTextCapabilities = RaftRichTextCapabilities()

fun buildRaftRichTextDocument(
    body: String,
    context: RaftRichTextContext = RaftRichTextContext()
): RaftRichTextDocument {
    val content = body.normalizeThreadMarkdownBody()
    val parserContent = MarkdownParserContent.from(content).parserContent
    val validator = context.toMarkdownTagValidator()
    val plan =
        RaftMarkdownInlineTokenRegistry.plan(
            parserContent,
            highlightedMentions = context.viewerScopedHighlightedMentions(),
            isTagValid = validator,
            inlineContentScope = context.inlineContentScope
        )
    return buildRaftRichTextDocumentFromMarkdownPlan(
        source = body,
        content = parserContent,
        tokenPlan = plan,
        activeTagKey = context.activeTagKey
    )
}

internal fun buildRaftRichTextDocumentFromMarkdownPlan(
    source: String,
    content: String,
    tokenPlan: MarkdownInlineTokenPlan,
    activeTagKey: String? = null,
    activeActionId: String? = null,
    activeActionIndicator: String = ""
): RaftRichTextDocument {
    val richTokenSources =
        buildList {
            addAll(tokenPlan.tagTokens().map { RaftRichTextTokenSource.Ref(it) })
            addAll(tokenPlan.inlineCodeMatches().map { RaftRichTextTokenSource.InlineCode(it) })
        }.sortedWith(compareBy<RaftRichTextTokenSource> { it.start }.thenBy { it.endExclusive })

    val tokens = mutableListOf<RaftRichTextInlineToken>()
    val actions = mutableListOf<RaftRichTextAction>()
    var cursor = 0
    richTokenSources.forEach { tokenSource ->
        if (tokenSource.start < cursor) return@forEach
        if (tokenSource.start > cursor) {
            tokens += content.textToken(cursor, tokenSource.start)
        }
        when (tokenSource) {
            is RaftRichTextTokenSource.InlineCode -> {
                val match = tokenSource.match
                tokens +=
                    RaftRichTextInlineToken(
                        id = match.id,
                        kind = RaftRichTextInlineKind.InlineCode,
                        range = match.range.toRichTextRange(),
                        text = match.code,
                        sourceText = content.substring(match.range),
                        chrome = RaftRichTextChromeKind.InlineCode
                    )
            }
            is RaftRichTextTokenSource.Ref -> {
                val token = tokenSource.token
                val tag = token.tag ?: return@forEach
                val action = tag.toRichTextAction(token.id)
                val actionPending = activeActionId == action.id
                actions += action
                tokens +=
                    RaftRichTextInlineToken(
                        id = token.id,
                        kind = RaftRichTextInlineKind.RaftRef,
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
                        trailingIndicator = activeActionIndicator.takeIf { actionPending }.orEmpty(),
                        active = actionPending
                    )
            }
        }
        cursor = tokenSource.endExclusive
    }
    if (cursor < content.length) {
        tokens += content.textToken(cursor, content.length)
    }

    val block =
        RaftRichTextBlock(
            id = "block:0",
            kind = RaftRichTextBlockKind.Paragraph,
            range = RaftRichTextRange(0, content.length),
            inlineTokenIds = tokens.map { it.id }
        )
    return RaftRichTextDocument(
        source = source,
        content = content,
        blocks = listOf(block),
        inlineTokens = tokens,
        actions = actions
    )
}

fun RaftRichTextDocument.toGoldenJson(): String =
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

private sealed class RaftRichTextTokenSource {
    abstract val start: Int
    abstract val endExclusive: Int

    data class Ref(val token: MarkdownInlineToken) : RaftRichTextTokenSource() {
        override val start: Int = token.range.first
        override val endExclusive: Int = token.range.last + 1
    }

    data class InlineCode(val match: ThreadInlineCodeMatch) : RaftRichTextTokenSource() {
        override val start: Int = match.range.first
        override val endExclusive: Int = match.range.last + 1
    }
}

private fun RaftRichTextContext.toMarkdownTagValidator(): (ThreadMarkdownTag) -> Boolean {
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
            ThreadMarkdownTagKind.DirectMessage ->
                directMessagePeerKeys.contains(tag.directMessagePeerName.orEmpty().trim().lowercase())
            ThreadMarkdownTagKind.Channel ->
                channelKeys.contains(tag.channelName.orEmpty().trim().lowercase())
            ThreadMarkdownTagKind.ChannelThread ->
                channelKeys.contains(tag.channelName.orEmpty().trim().lowercase()) &&
                    !tag.threadShortId.isNullOrBlank()
        }
    }
}

private fun RaftRichTextContext.viewerScopedHighlightedMentions(): Set<String> =
    buildSet {
        addAll(highlightedMentions)
        viewerUserId?.takeIf { it.isNotBlank() }?.let(::add)
    }

private fun String.textToken(start: Int, endExclusive: Int): RaftRichTextInlineToken =
    RaftRichTextInlineToken(
        id = "text:$start-$endExclusive",
        kind = RaftRichTextInlineKind.Text,
        range = RaftRichTextRange(start, endExclusive),
        text = substring(start, endExclusive),
        sourceText = substring(start, endExclusive)
    )

private fun ThreadMarkdownTag.toRichTextAction(id: String): RaftRichTextAction =
    RaftRichTextAction(
        id = id,
        kind = RaftRichTextActionKind.OpenRaftRef,
        refKind = kind.toRichTextRefKind(),
        label = label,
        channelName = channelName,
        threadShortId = threadShortId,
        directMessagePeerName = directMessagePeerName
    )

private fun ThreadMarkdownTag.toRichTextChromeKind(
    highlighted: Boolean,
    active: Boolean
): RaftRichTextChromeKind =
    when {
        active && (kind == ThreadMarkdownTagKind.ChannelThread || kind == ThreadMarkdownTagKind.DirectMessageThread) ->
            RaftRichTextChromeKind.Thread
        active -> RaftRichTextChromeKind.Active
        kind == ThreadMarkdownTagKind.UserMention && highlighted -> RaftRichTextChromeKind.SelfMention
        kind == ThreadMarkdownTagKind.UserMention -> RaftRichTextChromeKind.OrdinaryMention
        kind == ThreadMarkdownTagKind.Channel -> RaftRichTextChromeKind.Channel
        kind == ThreadMarkdownTagKind.ChannelThread -> RaftRichTextChromeKind.Thread
        kind == ThreadMarkdownTagKind.DirectMessageThread -> RaftRichTextChromeKind.Thread
        kind == ThreadMarkdownTagKind.DirectMessage -> RaftRichTextChromeKind.Channel
        kind == ThreadMarkdownTagKind.Task -> RaftRichTextChromeKind.Task
        else -> RaftRichTextChromeKind.OrdinaryMention
    }

private fun ThreadMarkdownTagKind.toRichTextRefKind(): RaftRichTextRefKind =
    when (this) {
        ThreadMarkdownTagKind.Channel -> RaftRichTextRefKind.Channel
        ThreadMarkdownTagKind.ChannelThread -> RaftRichTextRefKind.Thread
        ThreadMarkdownTagKind.DirectMessageThread -> RaftRichTextRefKind.DirectMessageThread
        ThreadMarkdownTagKind.DirectMessage -> RaftRichTextRefKind.DirectMessage
        ThreadMarkdownTagKind.Task -> RaftRichTextRefKind.Task
        ThreadMarkdownTagKind.UserMention -> RaftRichTextRefKind.UserMention
    }

private fun IntRange.toRichTextRange(): RaftRichTextRange =
    RaftRichTextRange(first, last + 1)

private fun StringBuilder.appendJsonField(name: String, value: String) {
    append("\"")
    append(name)
    append("\":")
    appendJsonString(value)
}

private fun RaftRichTextBlock.toGoldenJson(): String =
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

private fun RaftRichTextInlineToken.toGoldenJson(): String =
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

private fun RaftRichTextAction.toGoldenJson(): String =
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

private fun RaftRichTextRange.toGoldenJson(): String =
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
