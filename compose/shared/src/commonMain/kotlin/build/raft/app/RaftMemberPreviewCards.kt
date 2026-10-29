package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLayoutResult
import build.raft.app.data.SharedServerMembersStore
import build.raft.app.markdown.LocalRaftMarkdownMentionReadStates
import build.raft.app.model.MemberKind
import build.raft.designsystem.components.RaftPreviewCardPopup
import build.raft.designsystem.components.RaftPreviewCardState
import build.raft.designsystem.components.rememberRaftPreviewCardState

/**
 * Where the profile preview card (web `MentionLink` / avatar hover card) finds the member an
 * @mention or a message author is: the Thread route provides it to its message list, so mentions
 * in message text and authors' avatars open the card under a mouse or trackpad pointer. Absent
 * (null) everywhere else: no card.
 *
 * [resolveMention] maps a mention's words (`@ada`, as written) to the member, as the mention's own
 * click does; null shows no card. [model] builds the card's content (the members directory by
 * default; a fixture in tests).
 */
@Stable
internal class RaftMemberPreviewCardSource(
    val serverMembersStore: SharedServerMembersStore?,
    val serverId: String?,
    val agentStatusResolver: RaftAgentStatusResolver,
    val memberAvatarResolver: RaftMemberAvatarResolver,
    val resolveMention: (label: String) -> MemberDetailIdentity?,
    val model: @Composable (
        identity: MemberDetailIdentity,
        fallbackHandle: String?,
        readReceipt: RaftProfilePreviewReadReceipt?,
    ) -> RaftProfilePreviewCardModel = { identity, fallbackHandle, readReceipt ->
        rememberRaftProfilePreviewCardModel(
            memberId = identity.memberId.value,
            kind = if (identity.kind == MemberKind.Agent) MemberKind.Agent else MemberKind.Human,
            serverMembersStore = serverMembersStore,
            serverId = identity.sourceServerId?.value ?: serverId,
            fallbackHandle = fallbackHandle,
            readReceipt = readReceipt,
            agentStatusResolver = agentStatusResolver,
            memberAvatarResolver = memberAvatarResolver,
        )
    },
)

internal val LocalRaftMemberPreviewCardSource = compositionLocalOf<RaftMemberPreviewCardSource?> { null }

/**
 * The profile card of [identity] for a [RaftPreviewCard][build.raft.designsystem.components.RaftPreviewCard]
 * or [RaftPreviewCardPopup]: [RaftProfilePreviewCardContent] with its View Profile button, which
 * closes the card and runs [onViewProfile] (the trigger's own click: open the profile).
 */
@Composable
internal fun RaftMemberPreviewCardContent(
    source: RaftMemberPreviewCardSource,
    identity: MemberDetailIdentity,
    state: RaftPreviewCardState,
    fallbackHandle: String?,
    readReceipt: RaftProfilePreviewReadReceipt? = null,
    onViewProfile: (() -> Unit)?,
) {
    val model = source.model(identity, fallbackHandle, readReceipt)
    RaftProfilePreviewCardContent(
        model = model,
        onViewProfile =
            onViewProfile?.let { open ->
                {
                    state.dismiss()
                    open()
                }
            },
    )
}

/** String annotation over an @mention's words in rendered message text; the item is the mention's label as written. */
internal const val RaftMarkdownMentionAnnotationTag = "raft.build.markdown.mention"

/** One @mention in rendered text: its characters [start] until [end] and its label as written (`@ada`). */
internal data class RaftMarkdownMentionSpan(val start: Int, val end: Int, val label: String)

internal fun AnnotatedString.raftMarkdownMentionSpans(): List<RaftMarkdownMentionSpan> =
    getStringAnnotations(RaftMarkdownMentionAnnotationTag, 0, length)
        .filter { it.end > it.start }
        .map { RaftMarkdownMentionSpan(it.start, it.end, it.item) }
        .distinct()
        .sortedBy { it.start }

/**
 * The index in [spans] of the mention under the pointer: [caret] is the text offset nearest the
 * pointer (a gap), so the character under it is the one after or before it, and it counts only
 * when the pointer is inside that character's box ([inBox]). Null over plain text or past a
 * line's end.
 */
internal fun raftMarkdownMentionIndexAt(
    spans: List<RaftMarkdownMentionSpan>,
    textLength: Int,
    caret: Int,
    inBox: (offset: Int) -> Boolean,
): Int? {
    if (textLength == 0 || spans.isEmpty()) return null
    for (offset in intArrayOf(caret, caret - 1)) {
        if (offset !in 0 until textLength) continue
        val index = spans.indexOfFirst { offset >= it.start && offset < it.end }
        if (index >= 0 && inBox(offset)) return index
    }
    return null
}

/**
 * The @mentions of one rendered text and a [RaftPreviewCardState] each: [hover] reports where a
 * mouse or trackpad pointer is (text px), [press] a click or scroll, [Popups] places each open
 * card under its mention's words on the line the pointer entered it.
 */
@Stable
internal class RaftMarkdownMentionPreviewCards(
    val spans: List<RaftMarkdownMentionSpan>,
    val states: List<RaftPreviewCardState>,
    private val resolveMention: (label: String) -> MemberDetailIdentity?,
) {
    /** The text's last layout; set from its onTextLayout. */
    var layout: TextLayoutResult? = null

    private val anchors = arrayOfNulls<Rect>(spans.size)

    // Who each mention is, looked up the first time the pointer rests on it (most never are).
    private val identities = arrayOfNulls<MemberDetailIdentity>(spans.size)
    private val resolved = BooleanArray(spans.size)

    private fun identity(index: Int): MemberDetailIdentity? {
        if (!resolved[index]) {
            identities[index] = resolveMention(spans[index].label)
            resolved[index] = true
        }
        return identities[index]
    }

    fun hover(position: Offset?) {
        val layout = layout
        val hit =
            if (position == null || layout == null) {
                null
            } else {
                val caret = layout.getOffsetForPosition(position)
                raftMarkdownMentionIndexAt(spans, layout.layoutInput.text.length, caret) { layout.getBoundingBox(it).contains(position) }
                    ?.takeIf { identity(it) != null }
            }
        states.forEachIndexed { index, state ->
            val over = index == hit
            if (over && !state.isTriggerHovered && layout != null && position != null) {
                anchors[index] = layout.mentionAnchor(spans[index], layout.getOffsetForPosition(position))
            }
            if (state.isTriggerHovered != over) state.setTriggerHovered(over)
        }
    }

    /** A click or a wheel scroll on the text: the card of the mention under the pointer closes (the click still runs). */
    fun press() {
        states.forEach { if (it.isTriggerHovered) it.dismiss() }
    }

    @Composable
    fun Popups(source: RaftMemberPreviewCardSource, text: AnnotatedString) {
        val readStates = LocalRaftMarkdownMentionReadStates.current
        spans.forEachIndexed { index, span ->
            val state = states[index]
            // Only a mention the pointer is on, or whose card is open, composes a popup (which runs
            // the open and close delays).
            if (!state.isTriggerHovered && !state.isOpen) return@forEachIndexed
            val identity = identity(index) ?: return@forEachIndexed
            key(span) {
                RaftPreviewCardPopup(state = state, anchor = anchors[index]) {
                    RaftMemberPreviewCardContent(
                        source = source,
                        identity = identity,
                        state = state,
                        fallbackHandle = span.label,
                        readReceipt =
                            if (identity.kind == MemberKind.Agent) {
                                readStates?.readStateFor(span.label)?.let { read ->
                                    if (read) RaftProfilePreviewReadReceipt.Read else RaftProfilePreviewReadReceipt.Unread
                                }
                            } else {
                                null
                            },
                        // View Profile: the mention's own click.
                        onViewProfile = text.mentionClick(span),
                    )
                }
            }
        }
    }
}

/** The mention's words on the line of [offset] (a mention can wrap), in text px. */
private fun TextLayoutResult.mentionAnchor(span: RaftMarkdownMentionSpan, offset: Int): Rect {
    val line = getLineForOffset(offset.coerceIn(span.start, span.end - 1))
    val from = maxOf(span.start, getLineStart(line))
    val to = minOf(span.end, getLineEnd(line, visibleEnd = true)).coerceAtLeast(from + 1)
    return getPathForRange(from, to).getBounds()
}

private fun AnnotatedString.mentionClick(span: RaftMarkdownMentionSpan): (() -> Unit)? {
    val link =
        getLinkAnnotations(span.start, span.end)
            .firstOrNull { it.start <= span.start && span.end <= it.end && it.item is LinkAnnotation.Clickable }
            ?.item as? LinkAnnotation.Clickable
            ?: return null
    val listener = link.linkInteractionListener ?: return null
    return { listener.onClick(link) }
}

/**
 * The preview cards of the @mentions in [text] when a [RaftMemberPreviewCardSource] is provided,
 * one [rememberRaftPreviewCardState] per mention; null without a source or a mention.
 */
@Composable
internal fun rememberRaftMarkdownMentionPreviewCards(text: AnnotatedString): RaftMarkdownMentionPreviewCards? {
    val source = LocalRaftMemberPreviewCardSource.current ?: return null
    val spans = remember(text) { text.raftMarkdownMentionSpans() }
    if (spans.isEmpty()) return null
    val states = spans.map { span -> key(span) { rememberRaftPreviewCardState() } }
    return remember(spans, states, source) { RaftMarkdownMentionPreviewCards(spans, states, source.resolveMention) }
}

/**
 * Feeds [cards] from a mouse or trackpad pointer over the text (never touch or a pen): where it
 * hovers, and its clicks and wheel scrolls. Consumes nothing, so the text's links and the row's
 * gestures run as before.
 */
internal fun Modifier.raftMarkdownMentionHover(cards: RaftMarkdownMentionPreviewCards): Modifier =
    pointerInput(cards) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull() ?: continue
                if (change.type != PointerType.Mouse) continue
                when (event.type) {
                    PointerEventType.Enter, PointerEventType.Move -> cards.hover(change.position)
                    PointerEventType.Exit -> cards.hover(null)
                    PointerEventType.Press, PointerEventType.Scroll -> cards.press()
                }
            }
        }
    }

/**
 * [text] with its @mentions' preview cards: the text in a box that holds the cards' popups, so a
 * card's anchor (the mention's words, from the text's layout) is in the popup's own coordinates.
 * [modifier] goes on the box; [textContent] draws the text with the modifier it is given.
 */
@Composable
internal fun RaftMarkdownMentionPreviewCardHost(
    cards: RaftMarkdownMentionPreviewCards,
    text: AnnotatedString,
    modifier: Modifier,
    textContent: @Composable (Modifier) -> Unit,
) {
    val source = LocalRaftMemberPreviewCardSource.current
    Box(modifier, propagateMinConstraints = true) {
        textContent(Modifier.raftMarkdownMentionHover(cards))
        if (source != null) cards.Popups(source, text)
    }
}
