package build.raft.app.thread

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import build.raft.app.RaftLiveRootAnchorRef
import build.raft.app.data.RaftDefaultFavoriteReaction
import build.raft.app.data.RaftReactionFavoriteStore
import build.raft.app.data.RaftReactionFavorites
import build.raft.app.raftCaptureLiveRootAnchor
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftMessageItemToolbarButton
import build.raft.designsystem.components.RaftMessageItemToolbarDefaults
import build.raft.designsystem.components.RaftTooltip
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import kotlinx.coroutines.delay

/** How long a pointer rests on the hover toolbar's reaction button before the reaction bar opens (slock #13955). */
internal const val ThreadHoverToolbarReactionHoverOpenDelayMs: Long = 500L

internal const val THREAD_HOVER_TOOLBAR_QUICK_REACTION_TAG: String = "thread-hover-toolbar-quick-reaction"

/** The [RaftReactionFavoriteStore] used by hover toolbars (overridable in UI tests). */
internal val LocalRaftReactionFavoriteStore =
    compositionLocalOf<RaftReactionFavoriteStore> { RaftReactionFavorites.store }

internal enum class ThreadHoverToolbarQuickReactionLabelKind {
    Like,
    Unlike,
    React,
    Remove,
}

internal fun threadHoverToolbarQuickReactionLabelKind(
    quickReaction: String,
    reacted: Boolean,
): ThreadHoverToolbarQuickReactionLabelKind {
    val isDefaultLike = quickReaction == RaftDefaultFavoriteReaction
    return when {
        isDefaultLike && reacted -> ThreadHoverToolbarQuickReactionLabelKind.Unlike
        isDefaultLike -> ThreadHoverToolbarQuickReactionLabelKind.Like
        reacted -> ThreadHoverToolbarQuickReactionLabelKind.Remove
        else -> ThreadHoverToolbarQuickReactionLabelKind.React
    }
}

internal enum class ThreadHoverToolbarReactionActivation {
    QuickReact,
    OpenReactionBar,
}

/** A touch tap opens the reaction bar; a mouse/stylus click or keyboard activation toggles the quick reaction. */
internal fun threadHoverToolbarReactionActivation(
    pressPointerType: PointerType?,
): ThreadHoverToolbarReactionActivation =
    if (pressPointerType == PointerType.Touch) {
        ThreadHoverToolbarReactionActivation.OpenReactionBar
    } else {
        ThreadHoverToolbarReactionActivation.QuickReact
    }

internal fun shouldScheduleHoverToolbarReactionOpen(
    hovered: Boolean,
    suppressedByPress: Boolean,
    reactionPopupOpen: Boolean,
): Boolean = hovered && !suppressedByPress && !reactionPopupOpen

/**
 * Whether the viewer currently has [emoji] selected on [messageId], accounting for optimistic
 * in-flight toggles in [pendingVisuals] as well as [reactions].
 */
internal fun threadMessageHasViewerReaction(
    messageId: String,
    reactions: List<RaftThreadReactionItem>,
    pendingVisuals: Map<ThreadReactionPendingKey, ThreadReactionPendingVisual>,
    emoji: String,
): Boolean {
    val cleanEmoji = emoji.trim()
    if (cleanEmoji.isEmpty()) return false
    val pending = pendingVisuals[threadReactionPendingKey(messageId, cleanEmoji)]
    if (pending != null) {
        return pending.reaction.selected && pending.reaction.count > 0
    }
    return reactions.any { reaction ->
        reaction.emoji == cleanEmoji && reaction.selected && reaction.count > 0
    }
}

/**
 * The hover toolbar's quick-reaction button (botiverse/slock #13955): displays the viewer's
 * snapshotted favorite reaction (`👍` default), toggles it on click / Enter / Space (highlighting
 * with `attention-soft` while reacted), and opens the reaction bar after resting a non-touch
 * pointer on the button for [ThreadHoverToolbarReactionHoverOpenDelayMs] (500ms).
 */
@Composable
internal fun ThreadMessageHoverToolbarReactionButton(
    messageId: String,
    quickReaction: String,
    quickReacted: Boolean,
    reactionPopupOpen: Boolean,
    reactionAnchor: RaftLiveRootAnchorRef,
    onQuickReact: (String) -> Unit,
    onOpenReactionBar: () -> Unit,
) {
    val quickReactionLabel =
        when (threadHoverToolbarQuickReactionLabelKind(quickReaction, quickReacted)) {
            ThreadHoverToolbarQuickReactionLabelKind.Like ->
                stringResource(Res.string.thread_quick_reaction_like)
            ThreadHoverToolbarQuickReactionLabelKind.Unlike ->
                stringResource(Res.string.thread_quick_reaction_unlike)
            ThreadHoverToolbarQuickReactionLabelKind.React ->
                stringResource(Res.string.thread_quick_reaction_react, quickReaction)
            ThreadHoverToolbarQuickReactionLabelKind.Remove ->
                stringResource(Res.string.thread_quick_reaction_remove, quickReaction)
        }
    val quickReactionTooltip = stringResource(Res.string.thread_quick_reaction_tooltip, quickReactionLabel)
    var reactionButtonHovered by remember { mutableStateOf(false) }
    var hoverOpenConsumedUntilExit by remember { mutableStateOf(false) }
    val lastPressPointerType = remember { arrayOfNulls<PointerType>(1) }
    val reactionInteractionSource = remember { MutableInteractionSource() }
    val currentOnQuickReact by rememberUpdatedState(onQuickReact)
    val currentOnOpenReactionBar by rememberUpdatedState(onOpenReactionBar)
    val shouldScheduleHoverOpen =
        shouldScheduleHoverToolbarReactionOpen(
            hovered = reactionButtonHovered,
            suppressedByPress = hoverOpenConsumedUntilExit,
            reactionPopupOpen = reactionPopupOpen,
        )
    LaunchedEffect(shouldScheduleHoverOpen, messageId) {
        if (!shouldScheduleHoverOpen) return@LaunchedEffect
        delay(ThreadHoverToolbarReactionHoverOpenDelayMs)
        hoverOpenConsumedUntilExit = true
        currentOnOpenReactionBar()
    }
    val quickReactionButton: @Composable () -> Unit = {
        RaftMessageItemToolbarButton(
            onClick = {
                hoverOpenConsumedUntilExit = true
                val activation = threadHoverToolbarReactionActivation(lastPressPointerType[0])
                lastPressPointerType[0] = null
                when (activation) {
                    ThreadHoverToolbarReactionActivation.OpenReactionBar -> currentOnOpenReactionBar()
                    ThreadHoverToolbarReactionActivation.QuickReact -> currentOnQuickReact(quickReaction)
                }
            },
            label = quickReactionLabel,
            modifier =
                Modifier
                    .testTag(THREAD_HOVER_TOOLBAR_QUICK_REACTION_TAG)
                    .raftCaptureLiveRootAnchor(reactionAnchor)
                    .then(
                        if (quickReacted) {
                            Modifier.background(
                                RaftTheme.colors.attentionSoft,
                                RoundedCornerShape(RaftRadius.Chip),
                            )
                        } else {
                            Modifier
                        }
                    )
                    .semantics { toggleableState = ToggleableState(quickReacted) }
                    .onPreviewKeyEvent {
                        lastPressPointerType[0] = null
                        false
                    }
                    .pointerInput(messageId) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val pointerType = event.changes.firstOrNull()?.type
                                when (event.type) {
                                    PointerEventType.Enter -> {
                                        if (pointerType != PointerType.Touch) {
                                            reactionButtonHovered = true
                                        }
                                    }
                                    PointerEventType.Move -> {
                                        if (pointerType != PointerType.Touch) {
                                            val pos = event.changes.first().position
                                            val inside =
                                                pos.x >= 0f && pos.y >= 0f &&
                                                    pos.x < size.width && pos.y < size.height
                                            if (!inside) {
                                                reactionButtonHovered = false
                                                hoverOpenConsumedUntilExit = false
                                            } else if (!reactionButtonHovered) {
                                                reactionButtonHovered = true
                                            }
                                        }
                                    }
                                    PointerEventType.Exit -> {
                                        reactionButtonHovered = false
                                        hoverOpenConsumedUntilExit = false
                                    }
                                    PointerEventType.Press -> {
                                        hoverOpenConsumedUntilExit = true
                                        lastPressPointerType[0] = pointerType
                                    }
                                }
                            }
                        }
                    },
            active = quickReacted,
            open = reactionPopupOpen,
            interactionSource = reactionInteractionSource,
            showTooltip = false,
        ) {
            ThreadQuickReactionGlyph(
                emoji = quickReaction,
                modifier = Modifier.size(RaftMessageItemToolbarDefaults.IconSize),
            )
        }
    }
    if (!reactionPopupOpen) {
        RaftTooltip(
            text = quickReactionTooltip,
            openOnLongPress = false,
        ) {
            quickReactionButton()
        }
    } else {
        quickReactionButton()
    }
}
