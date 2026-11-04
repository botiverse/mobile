package build.raft.app

/** Who the reaction tooltip names, and how many more reacted ("+3 more"). */
internal data class ThreadReactionTooltipNames(
    val visibleNames: List<String>,
    val hiddenCount: Int
)

/** web MessageItem `reactionReactorNames`: `reactorNames.slice(0, 5)`. */
internal const val ThreadReactionTooltipMaxNames = 5

/**
 * web MessageItem `reactionReactorNames`: when you reacted, [youLabel] ("You") first and the
 * others after it; at most [maxNames] names, the rest counted from [RaftThreadReactionItem.count].
 *
 * Your own entry is found by [viewerUserId] in the id-aligned reactor lists. When the names are
 * not aligned with the ids (some reactor could not be named) and you are among the ids, your name
 * can't be told apart, so the names show as they are, without "You".
 */
internal fun threadReactionTooltipNames(
    reaction: RaftThreadReactionItem,
    viewerUserId: String?,
    youLabel: String,
    maxNames: Int = ThreadReactionTooltipMaxNames
): ThreadReactionTooltipNames {
    val viewer = viewerUserId?.trim()?.takeIf { it.isNotEmpty() }
    val ids = reaction.reactorIds.map { it.trim() }
    val names = reaction.reactorNames
    val ordered =
        when {
            !reaction.selected || viewer == null -> names
            ids.size == names.size -> listOf(youLabel) + names.filterIndexed { index, _ -> ids[index] != viewer }
            viewer !in ids -> listOf(youLabel) + names
            else -> names
        }
    val visible = ordered.map { it.trim() }.filter { it.isNotEmpty() }.take(maxNames)
    return ThreadReactionTooltipNames(visible, maxOf(0, reaction.count - visible.size))
}
