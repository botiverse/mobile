package build.raft.app.data

internal fun HomeSnapshot.preserveStableRowsFrom(previous: HomeSnapshot?): HomeSnapshot {
    previous ?: return this
    val stableChannels = channels.preserveStableItemsFrom(previous.channels) { conversation -> conversation.homeRowKey() }
    val stableDirectMessages =
        directMessages.preserveStableItemsFrom(previous.directMessages) { conversation -> conversation.homeRowKey() }
    if (stableChannels === channels && stableDirectMessages === directMessages) {
        return this
    }
    return copy(channels = stableChannels, directMessages = stableDirectMessages)
}

private inline fun <T, K> List<T>.preserveStableItemsFrom(
    previous: List<T>,
    keySelector: (T) -> K
): List<T> {
    if (isEmpty() || previous.isEmpty()) return this
    val previousByKey = previous.associateBy(keySelector)
    var reusedAnyReference = false
    val stableItems =
        map { incoming ->
            val previousItem = previousByKey[keySelector(incoming)]
            if (previousItem != null && previousItem == incoming) {
                reusedAnyReference = reusedAnyReference || previousItem !== incoming
                previousItem
            } else {
                incoming
            }
        }
    val fullyMatchesPrevious =
        stableItems.size == previous.size &&
            stableItems.indices.all { index -> stableItems[index] === previous[index] }
    return when {
        fullyMatchesPrevious -> previous
        reusedAnyReference -> stableItems
        else -> this
    }
}

private fun HomeConversationRow.homeRowKey(): String =
    channelId?.value ?: threadId.value
