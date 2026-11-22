package build.raft.app.thread.experiment

/**
 * Lightweight experimental message model used to benchmark and compare virtual timeline
 * projections independent of full production `RaftThreadMessageItem` wiring.
 */
internal data class VirtualTimelineMessage(
    val id: String,
    val author: String,
    val body: String,
    val isSystemFoldable: Boolean = false,
    val syntheticHeightDp: Int = 72,
)

/**
 * A single virtual row emitted into [VirtualMessageTimeline].
 *
 * Unlike `ThreadMessagesList.kt` (which emits a zero-height `Box` item in `LazyColumn` for every
 * folded system message), a collapsed run of consecutive system messages coalesces into a single
 * [CollapsedSystemGroupRow] virtual row — matching web `buildMessageTimelineRows` in
 * `packages/web/src/components/message/messageTimelineShared.ts`.
 */
internal sealed interface VirtualTimelineRow {
    val key: String

    data class MessageRow(
        val message: VirtualTimelineMessage,
        val sourceIndex: Int,
        override val key: String = messageRowKey(message.id),
    ) : VirtualTimelineRow

    data class CollapsedSystemGroupRow(
        val groupId: String,
        val memberIds: List<String>,
        val summaryCount: Int,
        val firstSourceIndex: Int,
        override val key: String = systemGroupRowKey(groupId),
    ) : VirtualTimelineRow

    companion object {
        fun messageRowKey(messageId: String): String = "msg:$messageId"

        fun systemGroupRowKey(groupId: String): String = "sysgroup:$groupId"
    }
}

internal data class VirtualTimelineProjection(
    val rows: List<VirtualTimelineRow>,
    val rowKeys: List<String>,
    /** Maps any folded member message ID to the [CollapsedSystemGroupRow.key] displaying it. */
    val foldedMemberToRowKey: Map<String, String>,
    /** Maps any folded member message ID to its collapsible `groupId`. */
    val foldedMemberToGroupId: Map<String, String>,
    val sourceMessageCount: Int,
    /** Exact number of zero-height placeholder rows eliminated by coalescing collapsed runs. */
    val collapsedZeroHeightRowsEliminated: Int,
) {
    private val directMessageKeys: Set<String> =
        buildSet(rows.size) {
            for (row in rows) {
                if (row is VirtualTimelineRow.MessageRow) {
                    add(row.message.id)
                }
            }
        }

    /**
     * Resolves the virtual row key that renders [messageId]: either its own [VirtualTimelineRow.MessageRow]
     * key or the [VirtualTimelineRow.CollapsedSystemGroupRow] key it is folded into, or `null` if
     * [messageId] is not in the currently loaded window.
     */
    fun resolveRowKeyForMessageId(messageId: String): String? {
        foldedMemberToRowKey[messageId]?.let { return it }
        return if (messageId in directMessageKeys) VirtualTimelineRow.messageRowKey(messageId) else null
    }

    fun groupIdForFoldedMessage(messageId: String): String? = foldedMemberToGroupId[messageId]
}

/**
 * Coalesces runs of 2 or more consecutive foldable system messages into one virtual row per
 * collapsed run (unless its `groupId` is in [expandedGroupIds]).
 *
 * Eliminates the zero-height placeholder `Box` rows that cause `LazyColumn` to compose and lay out
 * every hidden member inside the viewport at once.
 */
internal fun coalesceVirtualTimelineRows(
    messages: List<VirtualTimelineMessage>,
    expandedGroupIds: Set<String> = emptySet(),
): VirtualTimelineProjection {
    if (messages.isEmpty()) {
        return VirtualTimelineProjection(
            rows = emptyList(),
            rowKeys = emptyList(),
            foldedMemberToRowKey = emptyMap(),
            foldedMemberToGroupId = emptyMap(),
            sourceMessageCount = 0,
            collapsedZeroHeightRowsEliminated = 0,
        )
    }

    val rows = ArrayList<VirtualTimelineRow>(messages.size)
    val rowKeys = ArrayList<String>(messages.size)
    val foldedMemberToRowKey = HashMap<String, String>()
    val foldedMemberToGroupId = HashMap<String, String>()
    var eliminated = 0

    var i = 0
    while (i < messages.size) {
        val current = messages[i]
        if (!current.isSystemFoldable) {
            val row = VirtualTimelineRow.MessageRow(message = current, sourceIndex = i)
            rows.add(row)
            rowKeys.add(row.key)
            i += 1
            continue
        }

        var end = i + 1
        while (end < messages.size && messages[end].isSystemFoldable) {
            end += 1
        }
        val runLength = end - i
        if (runLength >= 2) {
            val groupId = "system-group:${messages[i].id}:${messages[end - 1].id}"
            if (groupId !in expandedGroupIds) {
                val memberIds = ArrayList<String>(runLength)
                val rowKey = VirtualTimelineRow.systemGroupRowKey(groupId)
                for (j in i until end) {
                    val memberId = messages[j].id
                    memberIds.add(memberId)
                    foldedMemberToRowKey[memberId] = rowKey
                    foldedMemberToGroupId[memberId] = groupId
                }
                val groupRow =
                    VirtualTimelineRow.CollapsedSystemGroupRow(
                        groupId = groupId,
                        memberIds = memberIds,
                        summaryCount = runLength,
                        firstSourceIndex = i,
                        key = rowKey,
                    )
                rows.add(groupRow)
                rowKeys.add(groupRow.key)
                eliminated += (runLength - 1)
                i = end
                continue
            }
        }

        for (j in i until end) {
            val row = VirtualTimelineRow.MessageRow(message = messages[j], sourceIndex = j)
            rows.add(row)
            rowKeys.add(row.key)
        }
        i = end
    }

    return VirtualTimelineProjection(
        rows = rows,
        rowKeys = rowKeys,
        foldedMemberToRowKey = foldedMemberToRowKey,
        foldedMemberToGroupId = foldedMemberToGroupId,
        sourceMessageCount = messages.size,
        collapsedZeroHeightRowsEliminated = eliminated,
    )
}
