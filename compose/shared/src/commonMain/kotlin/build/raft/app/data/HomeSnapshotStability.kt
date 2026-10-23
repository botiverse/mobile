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

/**
 * Home is served from a replica that lags the primary database (median ~2.6 s, p99 > 20 s), so a
 * re-fetch — notably the catch-up after foreground or socket reconnect — can answer with rows older
 * than the realtime messages already folded into [local]. Per row, by `lastMessageSeq` (the only
 * per-row content version Home carries): when the local row's latest message is newer than the
 * fetched one, the fetched row keeps the local message fields, and keeps the local unread fields
 * too unless the fetched read state already reaches that newer message (read on another device).
 * Every other field (name, membership, permissions, ...) takes the fetched value. A fetched row
 * without a seq has no usable version and wins as before. Read cursors themselves are guarded
 * separately by `readStateVersion` (read-state v1 canary).
 *
 * Only rows in [guardedRowKeys] are protected: rows a realtime message advanced recently (the
 * store bounds the window). Home has no delete push, so an unbounded guard would pin a deleted
 * latest message over every later fetch.
 */
internal fun HomeSnapshot.keepingNewerLocalMessagesFrom(
    local: HomeSnapshot?,
    guardedRowKeys: Set<String>,
): HomeSnapshot {
    local ?: return this
    if (guardedRowKeys.isEmpty()) return this
    if (local.server.id != server.id || local.currentMember.id != currentMember.id) return this
    val localChannels = local.channels.associateBy { it.homeRowKey() }
    val localDirectMessages = local.directMessages.associateBy { it.homeRowKey() }
    var changed = false
    val mergedChannels =
        channels.map { fetched ->
            val key = fetched.homeRowKey()
            val kept = if (key in guardedRowKeys) fetched.keepingNewerLocalMessage(localChannels[key]) else fetched
            if (kept !== fetched) changed = true
            kept
        }
    val mergedDirectMessages =
        directMessages.map { fetched ->
            val key = fetched.homeRowKey()
            val kept = if (key in guardedRowKeys) fetched.keepingNewerLocalMessage(localDirectMessages[key]) else fetched
            if (kept !== fetched) changed = true
            kept
        }
    return if (changed) copy(channels = mergedChannels, directMessages = mergedDirectMessages) else this
}

/** The local row's latest message is newer than the fetched row's (both carry a seq). */
private fun HomeConversationRow.localMessageIsNewer(local: HomeConversationRow?): Boolean {
    val localSeq = local?.lastMessageSeq ?: return false
    val fetchedSeq = lastMessageSeq ?: return false
    return localSeq > fetchedSeq
}

/** The fetched read state already covers the local row's newer message: its unread answer is current. */
private fun HomeConversationRow.fetchedReadCovers(local: HomeConversationRow): Boolean {
    val readSeq = readStateMaxReadSeq ?: return false
    val localSeq = local.lastMessageSeq ?: return false
    return readSeq >= localSeq
}

private fun HomeChannelRow.keepingNewerLocalMessage(local: HomeChannelRow?): HomeChannelRow {
    if (local == null || !localMessageIsNewer(local)) return this
    val withMessage =
        copy(
            lastMessageId = local.lastMessageId,
            lastMessageSeq = local.lastMessageSeq,
            lastMessagePreview = local.lastMessagePreview,
            lastMessageAt = local.lastMessageAt,
            lastMessageSenderName = local.lastMessageSenderName,
        )
    if (fetchedReadCovers(local)) return withMessage
    return withMessage.copy(
        unreadCount = local.unreadCount,
        unreadCountKnown = local.unreadCountKnown,
        mentionCount = local.mentionCount,
        mentionCountKnown = local.mentionCountKnown,
        firstUnreadMessageId = local.firstUnreadMessageId,
    )
}

private fun HomeDirectMessageRow.keepingNewerLocalMessage(local: HomeDirectMessageRow?): HomeDirectMessageRow {
    if (local == null || !localMessageIsNewer(local)) return this
    val withMessage =
        copy(
            lastMessageId = local.lastMessageId,
            lastMessageSeq = local.lastMessageSeq,
            lastMessagePreview = local.lastMessagePreview,
            lastMessageAt = local.lastMessageAt,
            lastMessageSenderName = local.lastMessageSenderName,
        )
    if (fetchedReadCovers(local)) return withMessage
    return withMessage.copy(
        unreadCount = local.unreadCount,
        unreadCountKnown = local.unreadCountKnown,
        mentionCount = local.mentionCount,
        mentionCountKnown = local.mentionCountKnown,
        firstUnreadMessageId = local.firstUnreadMessageId,
    )
}

/** Row key -> latest message seq of every row whose latest message is newer here than in [previous]. */
internal fun HomeSnapshot.rowsWithNewerMessageThan(previous: HomeSnapshot): Map<String, Long> {
    if (previous.server.id != server.id) return emptyMap()
    val previousSeqs =
        (previous.channels + previous.directMessages).associate { it.homeRowKey() to it.lastMessageSeq }
    return buildMap {
        (channels + directMessages).forEach { row ->
            val seq = row.lastMessageSeq ?: return@forEach
            val key = row.homeRowKey()
            val before = previousSeqs[key]
            if (before == null || seq > before) put(key, seq)
        }
    }
}

/** Row key -> latest message seq of every row of this snapshot that carries one. */
internal fun HomeSnapshot.rowMessageSeqs(): Map<String, Long> =
    buildMap {
        (channels + directMessages).forEach { row -> row.lastMessageSeq?.let { put(row.homeRowKey(), it) } }
    }
