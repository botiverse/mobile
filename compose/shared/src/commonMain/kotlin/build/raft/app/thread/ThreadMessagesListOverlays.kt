package build.raft.app

import build.raft.app.data.defaultNowMillis
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftGlyph
import build.raft.designsystem.components.RaftMessageListJumpControls
import build.raft.designsystem.components.RaftMessageListJumpPill
import build.raft.designsystem.components.RaftMessageListJumpSide
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/** Over the messages, under the typing line, the find bar (at the list's top) and the jump pills. */
private const val ThreadMessageListEndFadeZIndex = 1f
/** Over the messages, under the jump pills and the inline search bar's popups. */
private const val ThreadAgentTypingLineZIndex = 1.5f
/** Above the list rows and the inline search bar's neighbours, as the old floating pills. */
private const val ThreadJumpControlsZIndex = 5f

/**
 * What `ThreadMessagesListContent` draws over its list, in this order: the end fade, the thread
 * timeline (outline), the typing line, the bottom jump controls (select to here, back to bottom,
 * next unread thread) and the top one (earliest unread thread).
 *
 * Non-restartable: it runs whenever the list's content runs and is never skipped, so the outline's
 * remembered values, the time context's clock read and every state read here behave as when this
 * was inline. Reads stay lazy where they were: [backToBottomLoadingState] is read inside the
 * controls' content, [outlineOriginJumpRequestState] is only written from the outline's jump.
 */
@Composable
@NonRestartableComposable
internal fun BoxWithConstraintsScope.ThreadMessagesListOverlays(
    shareSelectionActive: Boolean,
    shareSelectionCaptureActive: Boolean,
    renderPayload: ThreadTimelineSlice,
    renderConfig: ThreadTimelineRenderConfig,
    duplicateMessageRandomIds: Set<String>,
    listState: ThreadListViewport,
    reverseLayout: Boolean,
    agentTyping: ThreadAgentTypingUi?,
    agentTypingLineShown: Boolean,
    outlineOriginJumpRequestState: MutableState<Int>,
    selectToHere: ThreadShareSelectToHere?,
    selectToHereOrderedIds: List<String>,
    selectToHereVisibleIds: () -> List<String>,
    selectToHerePillDirection: ThreadSelectToHereDirection?,
    backToBottomMounted: Boolean,
    backToBottomLoadingState: State<Boolean>,
    bottomJumpCount: Int,
    onBackToBottomClick: () -> Unit,
    unreadThreadJump: ThreadUnreadThreadJump?,
    nextUnreadThread: ThreadUnreadThreadJump?,
    unreadThreadPill: @Composable (ThreadUnreadThreadJump) -> Unit,
    onThreadEvent: ThreadEventHandler,
    diagnosticsLogger: (String) -> Unit,
) {
    var outlineOriginJumpRequest by outlineOriginJumpRequestState
    val backToBottomLoading by backToBottomLoadingState
    // The list's end fade (raft-ui MessageListEndFade), always on; while the typing line shows it
    // grows by the line's height, solid under the line (web ChatPanel / ThreadPanel).
    // Except while "Generate image" captures the list: the export reads the window's pixels
    // slice by slice, so an overlay on the list would be baked into every slice that reaches
    // the list's end (artin: a washed-out band mid-image). Same rule as the jump pills above.
    if (!shareSelectionCaptureActive) {
        ThreadMessageListEndFade(
            typingLineShown = agentTypingLineShown,
            modifier = Modifier.align(Alignment.BottomCenter).zIndex(ThreadMessageListEndFadeZIndex),
        )
    }
    if (renderPayload.replyThreadSurface && !shareSelectionActive && !shareSelectionCaptureActive) {
        // The thread timeline (web ConversationOutline): the rail in a wide window, the ⋯ menu's
        // Timeline sheet elsewhere; see ThreadOutline.kt.
        // Built on first read only: the viewport is read while the rail or the sheet shows, so a
        // phone without the sheet open never maps its rows.
        val outlineKeyToMessageId =
            remember(renderPayload.messages, renderPayload.originMessage?.id, duplicateMessageRandomIds) {
                lazy {
                    buildMap {
                        renderPayload.originMessage?.id?.let { put(ThreadOriginListItemKey, it) }
                        renderPayload.messages.forEach { put(it.messageListItemKey(duplicateMessageRandomIds), it.id) }
                    }
                }
            }
        val outlineViewport =
            remember(listState, reverseLayout, outlineKeyToMessageId) {
                {
                    val topFirst = listState.layoutInfo.visibleItemsInfo
                    val keys = outlineKeyToMessageId.value
                    ThreadOutlineViewport(topFirst.mapNotNull { keys[it.key] })
                }
            }
        val outlineOriginId = renderPayload.originMessage?.id
        val outlineTimeContext =
            currentRaftDateTimeContext(renderConfig.timeContext)
                ?.takeIf { it.resolvedTodayEpochDay() != null }
                ?: renderConfig.timeContext.refreshedAt(defaultNowMillis())
        ThreadOutlineHost(
            state = LocalThreadOutlineState.current,
            leadingMessageId = renderPayload.parentMessageId ?: outlineOriginId,
            origin = renderPayload.originMessage,
            messages = renderPayload.messages,
            viewport = outlineViewport,
            jumpTargetId = renderPayload.focusedMessageId,
            jumpNonce = renderPayload.focusedMessageRequestNonce,
            listWidth = maxWidth,
            // The payload's time context has no "today": without the page's live one every row
            // would show the raw UTC wire timestamp. Same day source as the date dividers.
            formatTime = { value -> outlineTimeContext.formatTimestampLabel(value) },
            onJump = { messageId ->
                listState.noteUserNavigation()
                // The thread's first message heads the list and isn't among the replies the
                // page focuses: scroll to its row (web `jumpToParent`); a reply is focused.
                if (messageId == (renderPayload.parentMessageId ?: outlineOriginId)) {
                    outlineOriginJumpRequest += 1
                } else {
                    onThreadEvent(ThreadOutlineJumpAction, messageId, null, null)
                }
            },
            modifier = Modifier.zIndex(ThreadJumpControlsZIndex),
        )
    }
    if (agentTyping != null && agentTypingLineShown) {
        ThreadAgentTypingLine(
            typing = agentTyping,
            modifier = Modifier.align(Alignment.BottomCenter).zIndex(ThreadAgentTypingLineZIndex),
        )
    }
    if (backToBottomMounted || nextUnreadThread != null || selectToHerePillDirection != null) {
        // Kuikly-era rule kept: the native dispatch capture sits on the pills' ancestor path so
        // a tap on a pill never also starts a gesture on the message row underneath.
        RaftMessageListJumpControls(
            // The pills sit above the typing line's slot (web `--message-list-bottom-inset`)
            // where the conversation has one, whether or not the line shows: like the list's
            // end, they do not move when it comes or goes.
            modifier = Modifier.align(Alignment.BottomCenter)
                .padding(bottom = if (renderPayload.agentTypingLineSlot) ThreadAgentTypingLineHeight else 0.dp)
                .zIndex(ThreadJumpControlsZIndex).raftNativeDispatchCapture(),
        ) {
            // Web BottomJumpControls: first on the controls' line (nearest the edge when stacked).
            if (selectToHere != null && selectToHerePillDirection != null) {
                RaftMessageListJumpPill(
                    text = stringResource(Res.string.thread_select_to_here),
                    icon =
                        if (selectToHerePillDirection == ThreadSelectToHereDirection.Up) RaftGlyph.ArrowUp else RaftGlyph.ArrowDown,
                    onClick = {
                        val selectable = selectToHere.selectableIds
                        threadIdsToViewportEdge(
                            orderedIds = selectToHereOrderedIds,
                            anchorId = selectToHere.anchorId,
                            visibleIds = selectToHereVisibleIds(),
                            isSelectable = { it in selectable },
                        )?.let { range ->
                            diagnosticsLogger(
                                "thread_select_to_here direction=${range.direction} count=${range.ids.size}"
                            )
                            selectToHere.onSelectRange(range.ids, range.edgeId)
                        }
                    },
                )
            }
            if (backToBottomMounted) {
                RaftMessageListJumpPill(
                    text =
                        when {
                            backToBottomLoading -> stringResource(Res.string.thread_jump_latest_loading)
                            bottomJumpCount <= 0 -> stringResource(Res.string.thread_action_back_to_bottom)
                            bottomJumpCount == 1 -> stringResource(Res.string.thread_jump_new_messages_one)
                            else -> stringResource(Res.string.thread_jump_new_messages_many, bottomJumpCount)
                        },
                    onClick = onBackToBottomClick,
                    busy = backToBottomLoading,
                )
            }
            if (nextUnreadThread != null) unreadThreadPill(nextUnreadThread)
        }
    }
    unreadThreadJump?.takeIf { it.direction == ThreadUnreadThreadJumpDirection.Earliest }?.let { earliest ->
        RaftMessageListJumpControls(
            modifier = Modifier.align(Alignment.TopCenter).zIndex(ThreadJumpControlsZIndex).raftNativeDispatchCapture(),
            side = RaftMessageListJumpSide.Top,
        ) {
            unreadThreadPill(earliest)
        }
    }
}
