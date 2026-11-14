package build.raft.app.assistant

import androidx.compose.foundation.shape.CircleShape
import build.raft.designsystem.components.RaftHoverTier
import build.raft.designsystem.components.raftClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import build.raft.app.data.SharedHomeContentState
import build.raft.app.data.SharedHomeState
import build.raft.app.markdown.formatRaftDirectMessagePeerRef
import build.raft.app.model.ConversationKind
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftConversationKindGlyph
import build.raft.designsystem.components.RaftConversationKindIcon
import build.raft.designsystem.components.RaftGlyph
import build.raft.designsystem.components.RaftGlyphIcon
import build.raft.designsystem.components.RaftTooltip
import build.raft.designsystem.components.raftPointerHover
import build.raft.designsystem.components.raftTouchTarget
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius

// The conversation the personal assistant was opened from (web assistant/AssistantContextChip.tsx,
// slock #12335; #proj-assistant:102aff91): opened from a composer's otter, the assistant's DM
// carries that conversation as a chip above its input. The chip is never sent alone: the next
// message with text the owner sends gets the conversation's ordinary reference token appended on
// a line of its own (`#general`, `#general:1a2b3c4d`, `dm:@alice`, `dm:@alice~agent:1a2b3c4d`),
// which every message renders as a reference chip, and then the chip clears. × removes it first.
// Each open of the panel attaches it afresh.
//
// The assistant reads what its owner can see, DMs only while the owner's switch lets it (RFC 077).
// So a DM, or a thread in one, with that switch off says the assistant can't see it; the token
// still goes, since the owner may want to point at it all the same.

/** The thread line's excerpt of its parent message: its first line, cut to this many characters (web `EXCERPT_MAX`). */
internal const val AssistantContextExcerptMax = 48

internal enum class AssistantContextKind { Channel, DirectMessage }

/** Web `AssistantContextRef`: what the chip says and the token the next message carries. */
internal data class AssistantContextRef(
    val kind: AssistantContextKind,
    /** "#general" or "@Alice": the conversation as its own header names it. */
    val conversation: String,
    /** In a thread: the first line of its parent message ("" when it has no text). Null: not a thread. */
    val threadExcerpt: String?,
    /** The reference token the message carries. */
    val token: String,
    /** False: a DM (or a thread in one) while the owner keeps DMs from the assistant. */
    val readable: Boolean,
)

/** The conversation the assistant was opened from, as the client holds it. */
internal sealed interface AssistantContextConversation {
    /** A channel (public, private or joint) by its name, without `#`. */
    data class Channel(val name: String) : AssistantContextConversation

    /**
     * A DM: [peerName] is the peer's handle (the reference grammar's name), [peerKind] pins it when
     * a person and an agent share that name (`dm:@name~agent`), [displayName] is what the chip says.
     */
    data class DirectMessage(
        val peerName: String,
        val peerKind: MemberKind?,
        val displayName: String,
    ) : AssistantContextConversation
}

/** The thread the assistant was opened in: its parent message's id, and that message's text while the page has it. */
internal data class AssistantContextThread(val parentMessageId: String, val parentText: String?)

/** Web `firstLineExcerpt`: the first non-blank line, its runs of whitespace as one space, cut to [AssistantContextExcerptMax] with "…". */
internal fun assistantContextExcerpt(text: String?): String {
    val line = text.orEmpty().split("\n").map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
    val flat = line.replace(AssistantContextWhitespace, " ")
    return if (flat.length > AssistantContextExcerptMax) "${flat.take(AssistantContextExcerptMax).trimEnd()}…" else flat
}

private val AssistantContextWhitespace = Regex("\\s+")

/**
 * Web `assistantContextRef`: what the chip says and the token it appends, for [conversation]
 * (and [thread] in it). Null where there is nothing a message could reference: a name or message
 * id the reference grammar (web raftRefs) can't carry, which would not render as a chip.
 */
internal fun assistantContextRef(
    conversation: AssistantContextConversation,
    thread: AssistantContextThread?,
    readsDms: Boolean,
): AssistantContextRef? {
    val shortId = thread?.parentMessageId?.take(8)?.lowercase()
    if (shortId != null && !shortId.isRaftRefThreadShortId()) return null
    val target: String
    val label: String
    val kind: AssistantContextKind
    when (conversation) {
        is AssistantContextConversation.Channel -> {
            val name = conversation.name.removePrefix("#")
            if (!name.isRaftRefChannelName()) return null
            target = "#$name"
            label = "#$name"
            kind = AssistantContextKind.Channel
        }
        is AssistantContextConversation.DirectMessage -> {
            if (!conversation.peerName.isRaftRefDmPeerName()) return null
            target = "dm:@${formatRaftDirectMessagePeerRef(conversation.peerName, conversation.peerKind)}"
            label = "@${conversation.displayName.ifBlank { conversation.peerName }}"
            kind = AssistantContextKind.DirectMessage
        }
    }
    return AssistantContextRef(
        kind = kind,
        conversation = label,
        threadExcerpt = thread?.let { assistantContextExcerpt(it.parentText) },
        token = if (shortId != null) "$target:$shortId" else target,
        readable = if (kind == AssistantContextKind.DirectMessage) readsDms else true,
    )
}

/**
 * Web `RAFT_REF_CHANNEL_NAME_PATTERN` `[\p{L}\p{N}_-]+`: every letter category and every number
 * category (Nd, Nl, No: ², Ⅷ, ①), not only [Char.isLetterOrDigit]'s decimal digits. A character
 * outside the Basic Multilingual Plane is a surrogate pair here and is not accepted.
 */
private fun String.isRaftRefChannelName(): Boolean =
    isNotEmpty() && all { it == '_' || it == '-' || it.category in RaftRefChannelNameCategories }

private val RaftRefChannelNameCategories =
    setOf(
        CharCategory.UPPERCASE_LETTER,
        CharCategory.LOWERCASE_LETTER,
        CharCategory.TITLECASE_LETTER,
        CharCategory.MODIFIER_LETTER,
        CharCategory.OTHER_LETTER,
        CharCategory.DECIMAL_DIGIT_NUMBER,
        CharCategory.LETTER_NUMBER,
        CharCategory.OTHER_NUMBER,
    )

/** Web `RAFT_REF_DM_PEER_PATTERN`'s name: `[\w.-]+` (ASCII word characters, `.`, `-`). */
private fun String.isRaftRefDmPeerName(): Boolean =
    isNotEmpty() && all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '.' || it == '-' }

/** Web `RAFT_REF_THREAD_SHORT_ID_PATTERN` `[\da-f]{6,8}`. */
private fun String.isRaftRefThreadShortId(): Boolean =
    length in 6..8 && all { it in '0'..'9' || it in 'a'..'f' }

/**
 * The conversation [channelId] as Home holds it for [serverId] (web `findStoredChannel`): a
 * channel row (public, private or joint; not a Lounge, a task's conversation or a thread row), or
 * a DM row whose peer [member] names. Null for anything else, or while Home holds another server.
 */
internal fun SharedHomeState.assistantContextConversation(
    serverId: String,
    channelId: String,
    member: (String) -> MemberProfile?,
): AssistantContextConversation? {
    val snapshot = (content as? SharedHomeContentState.Content)?.snapshot ?: return null
    if (snapshot.server.id.value != serverId) return null
    snapshot.channels.firstOrNull { it.channelId.value == channelId }?.let { row ->
        val channelKind = row.kind == ConversationKind.Channel || row.kind == ConversationKind.Joint
        if (!channelKind || row.thread != null || !row.loungeAgentId.isNullOrBlank()) return null
        return AssistantContextConversation.Channel(row.name)
    }
    val dm = snapshot.directMessages.firstOrNull { it.channelId.value == channelId } ?: return null
    val peer = dm.presenceMemberId?.value?.let(member) ?: return null
    val peerName = peer.handle.takeIf(String::isNotBlank) ?: return null
    val peerKind =
        when {
            peer.kind == MemberKind.Agent || peer.kind == MemberKind.Human -> peer.kind
            dm.directMessagePeerType.equals("agent", ignoreCase = true) -> MemberKind.Agent
            dm.directMessagePeerType.equals("user", ignoreCase = true) ||
                dm.directMessagePeerType.equals("human", ignoreCase = true) -> MemberKind.Human
            else -> null
        }
    return AssistantContextConversation.DirectMessage(
        peerName = peerName,
        peerKind = peerKind,
        displayName = dm.name.ifBlank { peer.displayName },
    )
}

/**
 * Web MessageInput `withComposerReference`: the text a message carries with a reference, its own
 * text (trailing whitespace dropped), then [token] on a line of its own. Without one, [body] as is.
 */
internal fun withAssistantContextReference(body: String, token: String?): String =
    if (token == null) body else "${body.trimEnd()}\n\n$token"

/**
 * Web `sentReference = hasContent ? composerReference : null`: only a message with text carries
 * the reference; an attachment-only message goes without it and the chip stays.
 */
internal fun assistantContextTokenForSend(draftText: String, context: AssistantContextRef?): String? =
    context?.token?.takeIf { draftText.isNotBlank() }

/**
 * The chip's state for one assistant DM (web AssistantOtterPanel `attached` over
 * `useAssistantContextRef`): [origin] is the conversation the otter lies on, as its page knows it
 * now; [attach] on each open of the panel, [detach] on × or once a message took it.
 */
@Stable
internal class ThreadAssistantContextAttachment {
    var origin: AssistantContextRef? by mutableStateOf(null)
    private var attached by mutableStateOf(false)

    /** The chip on the composer now, or null. */
    val context: AssistantContextRef?
        get() = origin.takeIf { attached }

    fun attach() {
        attached = true
    }

    fun detach() {
        attached = false
    }
}

/** Web AssistantContextChip's label: the conversation, or "Thread · #general · excerpt". */
@Composable
internal fun assistantContextChipLabel(context: AssistantContextRef): String {
    val excerpt = context.threadExcerpt ?: return context.conversation
    return if (excerpt.isNotEmpty()) {
        stringResource(Res.string.assistant_context_thread, context.conversation, excerpt)
    } else {
        stringResource(Res.string.assistant_context_thread_no_excerpt, context.conversation)
    }
}

/**
 * The chip above the assistant DM's input (web AssistantContextChip, a `ReferenceChip`): the
 * conversation's icon (a thread's, a DM's, or `#`) and its label in a reference chip (a channel
 * or DM in accent, a thread in the link chip's cyan, as the sent token renders), with × to remove
 * it; when the assistant can't read it, a muted line under it saying so.
 */
@Composable
internal fun ThreadAssistantContextChip(
    context: AssistantContextRef,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RaftTheme.colors
    val label = assistantContextChipLabel(context)
    val remove = stringResource(Res.string.assistant_context_remove, label)
    val attached = stringResource(Res.string.assistant_context_attached, label)
    val shape = RoundedCornerShape(RaftRadius.Chip)
    Column(
        modifier.testTag(ThreadAssistantContextChipTestTag),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Row(
            Modifier
                .height(22.dp)
                .background(if (context.threadExcerpt != null) colors.infoSoft else colors.attentionSoft, shape)
                .padding(horizontal = 6.dp)
                // Web `role="group"` named "Attached: …"; the × stays its own button.
                .semantics(mergeDescendants = true) { contentDescription = attached },
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                context.threadExcerpt != null ->
                    RaftConversationKindIcon(RaftConversationKindGlyph.Thread, size = 12.dp, color = colors.foreground)
                context.kind == AssistantContextKind.DirectMessage ->
                    RaftConversationKindIcon(RaftConversationKindGlyph.DirectMessage, size = 12.dp, color = colors.foreground)
                else -> RaftGlyphIcon(RaftGlyph.Hash, size = 12.dp, color = colors.foreground)
            }
            RaftText(
                label,
                Modifier.weight(1f, fill = false).clearAndSetSemantics {},
                style = RaftTheme.typography.caption.copy(fontWeight = FontWeight.SemiBold),
                color = colors.foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            RaftTooltip(text = remove) {
                val source = remember { MutableInteractionSource() }
                Box(
                    Modifier
                        .size(12.dp)
                        .semantics { contentDescription = remove }
                        .raftTouchTarget(enabled = true, interactionSource = source, onClick = onRemove)
                        .raftPointerHover(source)
                        .raftClickable(onClick = onRemove, shape = CircleShape, tier = RaftHoverTier.Subtle, interactionSource = source)
                        .testTag(ThreadAssistantContextChipRemoveTestTag),
                    contentAlignment = Alignment.Center,
                ) {
                    RaftGlyphIcon(RaftGlyph.X, size = 12.dp, color = colors.foregroundMuted)
                }
            }
        }
        if (!context.readable) {
            RaftText(
                stringResource(Res.string.assistant_context_unreadable),
                Modifier.testTag(ThreadAssistantContextUnreadableTestTag),
                style = RaftTheme.typography.footnote,
                color = colors.foregroundMuted,
            )
        }
    }
}

/** Web `data-testid`s: `assistant-context-chip`, its remove button, the unreadable line. */
internal const val ThreadAssistantContextChipTestTag = "assistant-context-chip"
internal const val ThreadAssistantContextChipRemoveTestTag = "assistant-context-chip-remove"
internal const val ThreadAssistantContextUnreadableTestTag = "assistant-context-unreadable"
