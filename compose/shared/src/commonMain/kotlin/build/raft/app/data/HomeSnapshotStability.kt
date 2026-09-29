package build.raft.app.data

internal fun HomeSnapshot.preserveStableRowsFrom(previous: HomeSnapshot?): HomeSnapshot {
    previous ?: return this
    if (this === previous) return this
    if (previous.server.id != this.server.id || previous.currentMember.id != this.currentMember.id) {
        return this
    }
    val previousChannelsByKey = previous.channels.associateBy { it.homeRowKey() }
    val channelsWithMergedPreview = channels.map { incoming ->
        incoming.preserveStablePreviewFrom(previousChannelsByKey[incoming.homeRowKey()])
    }
    val previousDmsByKey = previous.directMessages.associateBy { it.homeRowKey() }
    val dmsWithMergedPreview = directMessages.map { incoming ->
        incoming.preserveStablePreviewFrom(previousDmsByKey[incoming.homeRowKey()])
    }
    val stableChannels = channelsWithMergedPreview.preserveStableItemsFrom(previous.channels) { it.homeRowKey() }
    val stableDirectMessages = dmsWithMergedPreview.preserveStableItemsFrom(previous.directMessages) { it.homeRowKey() }
    if (stableChannels === channels && stableDirectMessages === directMessages) {
        return this
    }
    return copy(channels = stableChannels, directMessages = stableDirectMessages)
}

private fun HomeDirectMessageRow.preserveStablePreviewFrom(previous: HomeDirectMessageRow?): HomeDirectMessageRow {
    previous ?: return this
    if (!lastMessagePreview.isNullOrBlank()) return this
    val priorPreview = previous.lastMessagePreview?.takeIf { it.isNotBlank() } ?: return this
    return copy(
        lastMessagePreview = priorPreview,
        lastMessageSenderName = lastMessageSenderName ?: previous.lastMessageSenderName,
        lastMessageId = lastMessageId ?: previous.lastMessageId,
        lastMessageAt = lastMessageAt ?: previous.lastMessageAt,
    )
}

private fun HomeChannelRow.preserveStablePreviewFrom(previous: HomeChannelRow?): HomeChannelRow {
    previous ?: return this
    if (!lastMessagePreview.isNullOrBlank()) return this
    val priorPreview = previous.lastMessagePreview?.takeIf { it.isNotBlank() } ?: return this
    return copy(
        lastMessagePreview = priorPreview,
        lastMessageSenderName = lastMessageSenderName ?: previous.lastMessageSenderName,
        lastMessageId = lastMessageId ?: previous.lastMessageId,
        lastMessageAt = lastMessageAt ?: previous.lastMessageAt,
    )
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
