package build.raft.app

import build.raft.app.resources.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import build.raft.app.compat.ui.NestedScrollMode
import build.raft.app.compat.ui.nestedScroll
import build.raft.designsystem.components.RaftMessageListJumpControls
import build.raft.designsystem.components.RaftMessageListJumpPill
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Where the agent Activity log stands against its newest entry (raft-design "Conversation ›
 * Following the newest"): [following] while the reader is parked at the bottom, so a new entry
 * keeps the newest in view; once they leave the bottom it is false, the content they read stays
 * still and [newBelow] counts the entries that arrived under them since [seenLastKey], the newest
 * entry they had at the bottom.
 */
internal data class MembersAgentActivityTail(
    val following: Boolean = true,
    val seenLastKey: String? = null,
    val newBelow: Int = 0,
)

/** What a new newest entry does: follow it (scroll to it), or only count it under the reader. */
internal data class MembersAgentActivityTailUpdate(
    val tail: MembersAgentActivityTail,
    val scrollToLatest: Boolean,
)

/**
 * The log's newest entry changed ([rowKeys] in display order, oldest first). A parked reader who is
 * not scrolling follows it; anyone else keeps their place and the count of new entries below grows.
 * The count is how many rows now sit after [MembersAgentActivityTail.seenLastKey]; if that row is
 * gone (trimmed out of the capped log), it grows by one, never past the rows shown.
 */
internal fun membersAgentActivityTailOnNewLatest(
    tail: MembersAgentActivityTail,
    rowKeys: List<String>,
    scrolling: Boolean,
): MembersAgentActivityTailUpdate {
    val latest = rowKeys.lastOrNull() ?: return MembersAgentActivityTailUpdate(tail, scrollToLatest = false)
    if (tail.seenLastKey == null || (tail.following && !scrolling)) {
        return MembersAgentActivityTailUpdate(
            MembersAgentActivityTail(following = true, seenLastKey = latest, newBelow = 0),
            scrollToLatest = true,
        )
    }
    val seenIndex = rowKeys.lastIndexOf(tail.seenLastKey)
    val newBelow =
        if (seenIndex >= 0) rowKeys.size - 1 - seenIndex
        else (tail.newBelow + 1).coerceAtMost(rowKeys.size)
    return MembersAgentActivityTailUpdate(tail.copy(following = false, newBelow = newBelow), scrollToLatest = false)
}

/**
 * The viewport moved. Reaching the bottom parks the reader (following, nothing new below, the
 * newest entry seen); a drag away from it stops following; anything else keeps the tail as it is.
 */
internal fun membersAgentActivityTailOnViewport(
    tail: MembersAgentActivityTail,
    latestKey: String?,
    scrolling: Boolean,
    atBottom: Boolean,
): MembersAgentActivityTail =
    when {
        atBottom -> MembersAgentActivityTail(following = true, seenLastKey = latestKey ?: tail.seenLastKey, newBelow = 0)
        scrolling -> tail.copy(following = false)
        else -> tail
    }

internal const val MembersAgentActivityLogListTag = "members_agent_activity_log"
internal const val MembersAgentActivityLogJumpTag = "members_agent_activity_log_jump"

/**
 * The raw Activity log: entries oldest first, opening at the newest. Rows are keyed by
 * [rowKey] (the entry's own id), so a new entry or a trimmed old one never renames the rows on
 * screen. With [followLatest] the list follows new entries while the reader is at the bottom; a
 * reader who scrolled up keeps their place and gets the thread's jump pill ("Back to Bottom", or
 * "N new steps" once entries arrive below), which returns to the newest.
 */
@Composable
internal fun <T> MembersAgentActivityLogList(
    rows: List<T>,
    rowKey: (T) -> String,
    resetKey: Any?,
    followLatest: Boolean,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(initialFirstVisibleItemIndex = membersAgentActivityInitialFirstVisibleIndex(rows.size)),
    row: @Composable (index: Int, item: T) -> Unit,
) {
    val rowKeys = remember(rows) { rows.map(rowKey) }
    val latestKey = rowKeys.lastOrNull()
    val currentRowKeys by rememberUpdatedState(rowKeys)
    var tail by remember(resetKey) { mutableStateOf(MembersAgentActivityTail()) }
    if (followLatest) {
        LaunchedEffect(resetKey, listState) {
            snapshotFlow { listState.isScrollInProgress to listState.isMembersActivityLogAtBottom() }
                .distinctUntilChanged()
                .collect { (scrolling, atBottom) ->
                    tail = membersAgentActivityTailOnViewport(tail, currentRowKeys.lastOrNull(), scrolling, atBottom)
                }
        }
        LaunchedEffect(resetKey, latestKey) {
            if (latestKey == null) return@LaunchedEffect
            val update = membersAgentActivityTailOnNewLatest(tail, currentRowKeys, listState.isScrollInProgress)
            tail = update.tail
            if (update.scrollToLatest) listState.scrollToItem(currentRowKeys.lastIndex)
        }
    }
    Box(modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .testTag(MembersAgentActivityLogListTag)
                .nestedScroll(scrollUp = NestedScrollMode.SELF_FIRST, scrollDown = NestedScrollMode.SELF_FIRST),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            items(rows.size, key = { rowKeys[it] }) { index -> row(index, rows[index]) }
        }
        if (followLatest && !tail.following && rows.isNotEmpty()) {
            val scope = rememberCoroutineScope()
            RaftMessageListJumpControls(
                modifier = Modifier.align(Alignment.BottomCenter).raftNativeDispatchCapture(),
            ) {
                RaftMessageListJumpPill(
                    text = when {
                        tail.newBelow <= 0 -> stringResource(Res.string.thread_action_back_to_bottom)
                        tail.newBelow == 1 -> stringResource(Res.string.member_activity_log_new_steps_one)
                        else -> stringResource(Res.string.member_activity_log_new_steps_many, tail.newBelow)
                    },
                    onClick = {
                        // Direct user action: an explicit viewport replacement.
                        tail = MembersAgentActivityTail(following = true, seenLastKey = currentRowKeys.lastOrNull(), newBelow = 0)
                        scope.launch { listState.scrollToItem(currentRowKeys.lastIndex.coerceAtLeast(0)) }
                    },
                    modifier = Modifier.testTag(MembersAgentActivityLogJumpTag),
                )
            }
        }
    }
}

private fun LazyListState.isMembersActivityLogAtBottom(): Boolean {
    val lastIndex = layoutInfo.totalItemsCount - 1
    if (lastIndex <= 0 || !canScrollForward) return true
    return layoutInfo.visibleItemsInfo.any { item ->
        item.index == lastIndex && item.offset + item.size <= layoutInfo.viewportEndOffset + 4
    }
}
