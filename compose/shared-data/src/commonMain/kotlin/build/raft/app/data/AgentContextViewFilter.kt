package build.raft.app.data

/**
 * Pure filtering, tool-call/result pairing, and search helpers for Agent View Context messages
 * (mirroring web `agentContextViewMessageFilter.ts`).
 */

data class AgentContextViewRowMember(
    val messageIndex: Int,
    val partIndices: List<Int>,
)

data class AgentContextViewDisplayRow(
    val messageIndex: Int,
    val partIndices: List<Int>,
    val members: List<AgentContextViewRowMember>,
    val lastMessageIndex: Int,
)

data class AgentContextViewPartPlace(
    val messageIndex: Int,
    val partIndex: Int,
)

data class AgentContextViewToolPairs(
    val absorbedInto: Map<Int, Int>,
    val resultOf: Map<String, AgentContextViewPartPlace>,
    val callOf: Map<String, String>,
)

fun agentContextViewPartKey(messageIndex: Int, partIndex: Int): String = "$messageIndex:$partIndex"

/**
 * Detects whether a user text part carries Raft-delivered notices (`[Raft ...]` or `[target=... msg=...]`).
 */
fun isAgentContextViewRaftNoticePart(
    message: AgentContextViewMessage,
    part: AgentContextViewPart,
): Boolean {
    if (message.role != AgentContextViewMessageRole.User) return false
    val text = (part as? AgentContextViewPart.Text)?.text ?: return false
    return RAFT_DELIVERY_NOTICE_REGEX.containsMatchIn(text)
}

private val RAFT_DELIVERY_NOTICE_REGEX =
    Regex("""\[Raft\s+[^\]]+\]|\[target=[^\s\]]+\s+msg=[^\s\]]+[^\]]*\]""")

/**
 * Pairs tool results in a non-assistant message with their preceding assistant tool calls,
 * excluding the trigger (`raft message send`) call.
 */
fun buildAgentContextViewToolPairs(context: AgentContextView): AgentContextViewToolPairs {
    val absorbedInto = linkedMapOf<Int, Int>()
    val resultOf = linkedMapOf<String, AgentContextViewPartPlace>()
    val callOf = linkedMapOf<String, String>()
    val trigger = context.trigger
    var head: Int? = null
    var open = linkedMapOf<String, Int>()

    context.messages.forEachIndexed { messageIndex, message ->
        val allMatchOpen =
            message.role != AgentContextViewMessageRole.Assistant &&
                message.parts.isNotEmpty() &&
                message.parts.all { part ->
                    part is AgentContextViewPart.ToolResult &&
                        part.toolUseId != null &&
                        part.toolUseId in open
                }
        if (allMatchOpen && head != null) {
            val callMessage = head!!
            absorbedInto[messageIndex] = callMessage
            message.parts.forEachIndexed { partIndex, part ->
                val result = part as AgentContextViewPart.ToolResult
                val toolUseId = result.toolUseId!!
                val callPartIndex = open.remove(toolUseId)!!
                val callKey = agentContextViewPartKey(callMessage, callPartIndex)
                resultOf[callKey] = AgentContextViewPartPlace(messageIndex, partIndex)
                callOf[agentContextViewPartKey(messageIndex, partIndex)] = callKey
            }
            return@forEachIndexed
        }
        head = null
        open = linkedMapOf()
        if (message.role != AgentContextViewMessageRole.Assistant) return@forEachIndexed
        message.parts.forEachIndexed { partIndex, part ->
            val isTrigger = trigger != null && trigger.messageIndex == messageIndex && trigger.partIndex == partIndex
            if (part is AgentContextViewPart.ToolUse && !part.toolUseId.isNullOrEmpty() && !isTrigger) {
                open[part.toolUseId] = partIndex
            }
        }
        if (open.isNotEmpty()) {
            head = messageIndex
        }
    }
    return AgentContextViewToolPairs(
        absorbedInto = absorbedInto,
        resultOf = resultOf,
        callOf = callOf,
    )
}

private fun isPartShownForFilter(
    filter: AgentContextViewMessageFilter,
    message: AgentContextViewMessage,
    part: AgentContextViewPart,
): Boolean =
    when (filter) {
        AgentContextViewMessageFilter.All -> true
        AgentContextViewMessageFilter.Conversation ->
            part is AgentContextViewPart.Text &&
                (message.role == AgentContextViewMessageRole.User || message.role == AgentContextViewMessageRole.Assistant)
        AgentContextViewMessageFilter.Notices -> isAgentContextViewRaftNoticePart(message, part)
        AgentContextViewMessageFilter.Tools ->
            part is AgentContextViewPart.ToolUse || part is AgentContextViewPart.ToolResult
    }

fun buildAgentContextViewRowsByFilter(
    context: AgentContextView,
    pairs: AgentContextViewToolPairs = buildAgentContextViewToolPairs(context),
): Map<AgentContextViewMessageFilter, List<AgentContextViewDisplayRow>> {
    val buckets =
        AgentContextViewMessageFilter.entries.associateWith { mutableListOf<AgentContextViewDisplayRow>() }
    context.messages.forEachIndexed { messageIndex, message ->
        val head = pairs.absorbedInto[messageIndex]
        for (filter in AgentContextViewMessageFilter.entries) {
            val shownParts =
                message.parts.indices.filter { partIndex ->
                    isPartShownForFilter(filter, message, message.parts[partIndex])
                }
            val list = buckets.getValue(filter)
            val previous = list.lastOrNull()
            if (head != null && shownParts.isNotEmpty() && previous?.messageIndex == head) {
                list[list.lastIndex] =
                    previous.copy(
                        members = previous.members + AgentContextViewRowMember(messageIndex, shownParts),
                        lastMessageIndex = messageIndex,
                    )
                continue
            }
            if (shownParts.isNotEmpty() || filter == AgentContextViewMessageFilter.All) {
                list +=
                    AgentContextViewDisplayRow(
                        messageIndex = messageIndex,
                        partIndices = shownParts,
                        members = emptyList(),
                        lastMessageIndex = messageIndex,
                    )
            }
        }
    }
    return buckets
}

fun agentContextViewFilterMessageCount(rows: List<AgentContextViewDisplayRow>): Int =
    rows.sumOf { 1 + it.members.size }

fun agentContextViewRowIndexForMessage(
    rows: List<AgentContextViewDisplayRow>,
    messageIndex: Int,
): Int {
    if (rows.isEmpty()) return -1
    val found = rows.indexOfFirst { it.lastMessageIndex >= messageIndex }
    return if (found >= 0) found else rows.lastIndex
}

fun agentContextViewPartSearchableText(part: AgentContextViewPart): String? =
    when (part) {
        is AgentContextViewPart.Text -> part.text
        is AgentContextViewPart.Thinking -> part.text
        is AgentContextViewPart.ToolUse -> "${part.toolName}\n${part.inputJson}"
        is AgentContextViewPart.ToolResult -> part.output
        is AgentContextViewPart.Compaction -> part.text
        is AgentContextViewPart.Image, is AgentContextViewPart.Unknown -> null
    }

/** Returns indices into [rows] that match [query] (case-insensitive substring). */
fun findAgentContextViewMatchingRows(
    context: AgentContextView,
    rows: List<AgentContextViewDisplayRow>,
    query: String,
): List<Int> {
    val needle = query.trim()
    if (needle.isEmpty()) return emptyList()
    val matches = mutableListOf<Int>()
    rows.forEachIndexed { rowIndex, row ->
        val mainMsg = context.messages[row.messageIndex]
        val mainHit =
            row.partIndices.any { idx ->
                agentContextViewPartSearchableText(mainMsg.parts[idx])?.contains(needle, ignoreCase = true) == true
            }
        val memberHit =
            !mainHit &&
                row.members.any { member ->
                    val memberMsg = context.messages[member.messageIndex]
                    member.partIndices.any { idx ->
                        agentContextViewPartSearchableText(memberMsg.parts[idx])?.contains(needle, ignoreCase = true) == true
                    }
                }
        if (mainHit || memberHit) {
            matches += rowIndex
        }
    }
    return matches
}
