package build.raft.app.thread.experiment

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal data class VirtualTimelineSearchState(
    val open: Boolean = false,
    val query: String = "",
    val matches: List<String> = emptyList(),
    val matchingMessageIds: Set<String> = emptySet(),
    val activeIndex: Int = 0,
    val pendingContextLoadMessageId: String? = null,
) {
    val activeMatchMessageId: String?
        get() = if (open) matches.getOrNull(activeIndex) else null

    val countLabel: String
        get() =
            when {
                query.trim().isEmpty() -> ""
                matches.isEmpty() -> "0/0"
                else -> "${activeIndex + 1}/${matches.size}"
            }
}

internal enum class VirtualSearchJumpOutcome {
    NoActiveMatch,
    JumpedInWindow,
    RequestedContextLoad,
}

/**
 * Externalized in-thread search coordinator decoupled from mounted `LazyLayout` items.
 *
 * Mirrors web `useThreadSearch` + mobile `SharedThreadFindStore`:
 * - Matches against the full hydrated message corpus even when 95%+ of rows are unmounted.
 * - Highlight state (`activeMatchMessageId`, `matchingMessageIds`) lives outside item composition.
 * - Jumping to a faraway hit either resolves its virtual row key directly via
 *   [VirtualTimelineProjection] (auto-expanding a collapsed system run if requested) or triggers
 *   context-window loading while arming a key-based pending jump with pagination suppression.
 */
@Stable
internal class VirtualTimelineSearchCoordinator {
    var state: VirtualTimelineSearchState by mutableStateOf(VirtualTimelineSearchState())
        private set

    private var corpus: List<VirtualTimelineMessage> = emptyList()

    fun updateCorpus(messages: List<VirtualTimelineMessage>) {
        corpus = messages
        recomputeMatches(resetActiveIndex = false)
    }

    fun open() {
        if (!state.open) {
            state = state.copy(open = true)
        }
    }

    fun close() {
        if (state.open || state.pendingContextLoadMessageId != null) {
            state = state.copy(open = false, pendingContextLoadMessageId = null)
        }
    }

    fun setQuery(query: String) {
        state = state.copy(open = true, query = query, activeIndex = 0, pendingContextLoadMessageId = null)
        recomputeMatches(resetActiveIndex = true)
    }

    fun nextMatch(): String? = step(direction = 1)

    fun previousMatch(): String? = step(direction = -1)

    /**
     * Jumps [timelineState] to the currently active search match with `Center` alignment.
     *
     * When the match is not present in the loaded [projection], arms a pending jump on
     * [timelineState] (suppressing edge auto-load) and invokes [onLoadContextWindow] so the
     * caller can swap/hydrate the context window around the match.
     */
    fun jumpToActiveMatch(
        timelineState: VirtualMessageTimelineState,
        projection: VirtualTimelineProjection,
        expandFoldedSystemGroup: Boolean = true,
        onExpandSystemGroup: ((String) -> Unit)? = null,
        onLoadContextWindow: ((String) -> Unit)? = null,
    ): VirtualSearchJumpOutcome {
        val targetMessageId = state.activeMatchMessageId ?: return VirtualSearchJumpOutcome.NoActiveMatch
        val foldedGroupId = projection.groupIdForFoldedMessage(targetMessageId)
        if (foldedGroupId != null && expandFoldedSystemGroup && onExpandSystemGroup != null) {
            onExpandSystemGroup(foldedGroupId)
            val expandedKey = VirtualTimelineRow.messageRowKey(targetMessageId)
            timelineState.jumpToKey(expandedKey, align = VirtualTimelineAlign.Center, suppressAutoLoad = true)
            state = state.copy(pendingContextLoadMessageId = null)
            return VirtualSearchJumpOutcome.JumpedInWindow
        }

        val rowKey = projection.resolveRowKeyForMessageId(targetMessageId)
        if (rowKey != null) {
            timelineState.jumpToKey(rowKey, align = VirtualTimelineAlign.Center, suppressAutoLoad = true)
            state = state.copy(pendingContextLoadMessageId = null)
            return VirtualSearchJumpOutcome.JumpedInWindow
        }

        // Hit exists in the full search corpus but lies outside the currently loaded window.
        val expectedKey = VirtualTimelineRow.messageRowKey(targetMessageId)
        timelineState.jumpToKey(expectedKey, align = VirtualTimelineAlign.Center, suppressAutoLoad = true)
        state = state.copy(pendingContextLoadMessageId = targetMessageId)
        onLoadContextWindow?.invoke(targetMessageId)
        return VirtualSearchJumpOutcome.RequestedContextLoad
    }

    /**
     * Called when a new context window lands into [projection]; resolves a pending search jump
     * (including folded system rows) without extra jitter.
     */
    fun onContextWindowLoaded(
        timelineState: VirtualMessageTimelineState,
        projection: VirtualTimelineProjection,
        expandFoldedSystemGroup: Boolean = true,
        onExpandSystemGroup: ((String) -> Unit)? = null,
    ): Boolean {
        val pendingId = state.pendingContextLoadMessageId ?: return false
        val foldedGroupId = projection.groupIdForFoldedMessage(pendingId)
        if (foldedGroupId != null && expandFoldedSystemGroup && onExpandSystemGroup != null) {
            onExpandSystemGroup(foldedGroupId)
            timelineState.jumpToKey(
                key = VirtualTimelineRow.messageRowKey(pendingId),
                align = VirtualTimelineAlign.Center,
                suppressAutoLoad = true,
            )
            state = state.copy(pendingContextLoadMessageId = null)
            return true
        }
        val rowKey = projection.resolveRowKeyForMessageId(pendingId) ?: return false
        timelineState.jumpToKey(rowKey, align = VirtualTimelineAlign.Center, suppressAutoLoad = true)
        state = state.copy(pendingContextLoadMessageId = null)
        return true
    }

    private fun step(direction: Int): String? {
        val count = state.matches.size
        if (count == 0) return null
        val current = state.activeIndex.coerceIn(0, count - 1)
        val nextIndex = (current + direction + count) % count
        state = state.copy(open = true, activeIndex = nextIndex, pendingContextLoadMessageId = null)
        return state.matches[nextIndex]
    }

    private fun recomputeMatches(resetActiveIndex: Boolean) {
        val normalized = state.query.trim().lowercase()
        if (normalized.isEmpty()) {
            state =
                state.copy(
                    matches = emptyList(),
                    matchingMessageIds = emptySet(),
                    activeIndex = 0,
                )
            return
        }
        val hits = ArrayList<String>()
        val hitSet = HashSet<String>()
        for (message in corpus) {
            val authorMatches = message.author.lowercase().contains(normalized)
            val bodyMatches = message.body.lowercase().contains(normalized)
            if (authorMatches || bodyMatches) {
                hits.add(message.id)
                hitSet.add(message.id)
            }
        }
        val nextActive =
            when {
                hits.isEmpty() -> 0
                resetActiveIndex -> 0
                else -> state.activeIndex.coerceIn(0, hits.lastIndex)
            }
        state =
            state.copy(
                matches = hits,
                matchingMessageIds = hitSet,
                activeIndex = nextActive,
            )
    }
}
