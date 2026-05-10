package ai.slock.android.feature.inbox

import ai.slock.android.core.data.repository.DataResult
import ai.slock.android.core.data.repository.InboxFilter as RepositoryInboxFilter
import ai.slock.android.core.data.repository.InboxSnapshot
import ai.slock.android.core.data.repository.SavedSnapshot
import ai.slock.android.core.data.repository.SearchSnapshot
import ai.slock.android.core.data.repository.ThreadListSnapshot
import ai.slock.android.core.model.ChannelId
import ai.slock.android.core.model.ConversationKind
import ai.slock.android.core.model.ConversationSummary
import ai.slock.android.core.model.InboxItem
import ai.slock.android.core.model.InboxItemKind
import ai.slock.android.core.model.Message
import ai.slock.android.core.model.MessageType
import ai.slock.android.core.model.RealtimeEvent
import ai.slock.android.core.model.ThreadId
import ai.slock.android.core.model.ThreadConversation
import ai.slock.android.core.state.EmptyReason
import ai.slock.android.core.state.ScreenLoadState

fun InboxUiState.visibleContent(): InboxContent? =
        (loadState as? ScreenLoadState.Content)?.data ?: previousContent

fun InboxUiState.visibleUnreadCount(): Int? = visibleContent()?.totalUnreadCount

fun InboxUiState.visibleTotalCount(): Int? = visibleContent()?.totalCount

fun DataResult<InboxSnapshot>.toInboxUiState(
        selectedFilter: InboxFilter,
        previousContent: InboxContent? = null
): InboxUiState =
        InboxUiState(
                loadState =
                        when (this) {
                            is DataResult.Content ->
                                    ScreenLoadState.Content(
                                            InboxContent(
                                                    server = data.server,
                                                    items = data.items,
                                                    totalCount = data.totalCount,
                                                    totalUnreadCount = data.totalUnreadCount,
                                                    hasMore = data.hasMore
                                            )
                                    )
                            is DataResult.Empty -> ScreenLoadState.Empty(reason)
                            is DataResult.Error -> ScreenLoadState.Error(error)
                            is DataResult.NoPermission -> ScreenLoadState.NoPermission(blocker)
                        },
                selectedFilter = selectedFilter,
                nextOffset = (this as? DataResult.Content)?.data?.nextOffset,
                previousContent = (this as? DataResult.Content)?.let { null } ?: previousContent
        )

fun DataResult<ThreadListSnapshot>.toFollowedThreadsUiState(
        previousContent: InboxContent? = null
): InboxUiState =
        InboxUiState(
                loadState =
                        when (this) {
                            is DataResult.Content ->
                                    ScreenLoadState.Content(
                                            InboxContent(
                                                    server = data.server,
                                                    items = data.threads,
                                                    totalCount = data.totalCount,
                                                    totalUnreadCount =
                                                            data.threads.sumOf { it.unreadCount },
                                                    hasMore = data.hasMore
                                            )
                                    )
                            is DataResult.Empty -> ScreenLoadState.Empty(reason)
                            is DataResult.Error -> ScreenLoadState.Error(error)
                            is DataResult.NoPermission -> ScreenLoadState.NoPermission(blocker)
                        },
                selectedFilter = InboxFilter.All,
                nextOffset = (this as? DataResult.Content)?.data?.nextOffset,
                previousContent = (this as? DataResult.Content)?.let { null } ?: previousContent
        )

fun DataResult<SavedSnapshot>.toSavedInboxUiState(
        previousContent: InboxContent? = null
): InboxUiState =
        InboxUiState(
                loadState =
                        when (this) {
                            is DataResult.Content ->
                                    ScreenLoadState.Content(
                                            InboxContent(
                                                    server = data.server,
                                                    items = data.items,
                                                    totalCount = data.totalCount,
                                                    hasMore = data.hasMore
                                            )
                                    )
                            is DataResult.Empty -> ScreenLoadState.Empty(EmptyReason.NoInboxItems)
                            is DataResult.Error -> ScreenLoadState.Error(error)
                            is DataResult.NoPermission -> ScreenLoadState.NoPermission(blocker)
                        },
                selectedFilter = InboxFilter.All,
                nextOffset = (this as? DataResult.Content)?.data?.nextOffset,
                previousContent = (this as? DataResult.Content)?.let { null } ?: previousContent
        )

fun DataResult<SearchSnapshot>.toSearchInboxUiState(
        previousContent: InboxContent? = null
): InboxUiState =
        InboxUiState(
                loadState =
                        when (this) {
                            is DataResult.Content ->
                                    ScreenLoadState.Content(
                                            InboxContent(
                                                    server = data.server,
                                                    items = data.items,
                                                    totalCount = data.totalCount,
                                                    hasMore = data.hasMore
                                            )
                                    )
                            is DataResult.Empty -> ScreenLoadState.Empty(reason)
                            is DataResult.Error -> ScreenLoadState.Error(error)
                            is DataResult.NoPermission -> ScreenLoadState.NoPermission(blocker)
                        },
                selectedFilter = InboxFilter.All,
                nextOffset = (this as? DataResult.Content)?.data?.nextOffset,
                previousContent = (this as? DataResult.Content)?.let { null } ?: previousContent
        )

fun InboxUiState.applyRealtimeEvent(
        event: RealtimeEvent,
        query: String? = null
): InboxUiState =
        when (event) {
            is RealtimeEvent.MessageNew -> applyRealtimeMessages(listOf(event.message), query)
            is RealtimeEvent.MessageUpdated -> applyRealtimeMessages(listOf(event.message), query)
            is RealtimeEvent.SyncResumeResponse -> applyRealtimeMessages(event.messages, query)
            is RealtimeEvent.ThreadUpdated -> applyThreadUpdated(event)
            else -> this
        }

fun InboxUiState.applySavedRealtimeEvent(event: RealtimeEvent): InboxUiState =
        when (event) {
            is RealtimeEvent.MessageUpdated -> updateExistingMessage(event.message)
            is RealtimeEvent.SyncResumeResponse ->
                    event.messages.fold(this) { state, message ->
                        state.updateExistingMessage(message)
                    }
            else -> this
        }

private fun InboxUiState.applyThreadUpdated(event: RealtimeEvent.ThreadUpdated): InboxUiState {
    val contentState = loadState as? ScreenLoadState.Content ?: return this
    val content = contentState.data
    val updatedItems =
            content.items.map { item ->
                if (item.parentMessageId == event.parentMessageId ||
                                item.threadChannelId == event.threadChannelId
                ) {
                    item.copy(
                            replyCount = event.replyCount,
                            latestActivityAt = event.lastReplyAt ?: item.latestActivityAt,
                            participantIds = event.participantIds
                    )
                } else {
                    item
                }
            }
    return copy(loadState = ScreenLoadState.Content(content.copy(items = updatedItems)))
}

private fun InboxUiState.applyRealtimeMessages(
        messages: List<Message>,
        query: String?
): InboxUiState {
    val contentState = loadState as? ScreenLoadState.Content ?: return this
    val filteredMessages =
            if (query.isNullOrBlank()) {
                messages
            } else {
                messages.filter { it.body.contains(query, ignoreCase = true) }
            }
    if (filteredMessages.isEmpty()) return this
    val content = contentState.data
    val items = (filteredMessages.map { it.toInboxItem() } + content.items).distinctBy { it.key }
    return copy(
            loadState =
                    ScreenLoadState.Content(
                            content.copy(
                                    items = items,
                                    totalCount = content.totalCount.coerceAtLeast(items.size),
                                    totalUnreadCount = items.sumOf { it.unreadCount }
                            )
                    )
    )
}

private fun InboxUiState.updateExistingMessage(message: Message): InboxUiState {
    val contentState = loadState as? ScreenLoadState.Content ?: return this
    val content = contentState.data
    val updatedItems =
            content.items.map { item ->
                if (item.lastMessageId == message.id || item.latestActivityMessageId == message.id
                ) {
                    item.copy(
                            lastMessagePreview = message.body,
                            latestActivityPreview = message.body,
                            latestActivityAt = message.timestampLabel,
                            latestActivitySenderName = message.author.displayName
                    )
                } else {
                    item
                }
            }
    return copy(loadState = ScreenLoadState.Content(content.copy(items = updatedItems)))
}

private fun Message.toInboxItem(): InboxItem =
        InboxItem(
                key = id.value,
                kind =
                        if (messageType == MessageType.System) InboxItemKind.System
                        else if (channelId == null) InboxItemKind.Thread else InboxItemKind.Channel,
                channelId = channelId ?: ChannelId(threadId.value),
                channelName = channelId?.value ?: threadId.value,
                unreadCount = 1,
                firstUnreadMessageId = id,
                lastMessageId = id,
                lastMessagePreview = body,
                lastMessageAt = timestampLabel,
                lastMessageSenderName = author.displayName,
                threadChannelId = ChannelId(threadId.value),
                latestActivityMessageId = id,
                latestActivityPreview = body,
                latestActivityAt = timestampLabel,
                latestActivitySenderName = author.displayName,
                replyCount = replyCount
        )

fun InboxItem.toConversationSummary(): ConversationSummary {
    val threadId =
            if (kind == InboxItemKind.Thread && parentChannelId != null && parentMessageId != null) {
                ai.slock.android.core.model.threadIdentity(parentChannelId, parentMessageId)
            } else {
                ThreadId((threadChannelId ?: channelId).value)
            }
    val title =
            when (kind) {
                InboxItemKind.Thread -> parentChannelName ?: channelName
                InboxItemKind.DirectMessage, InboxItemKind.Channel, InboxItemKind.System -> channelName
            }
    return ConversationSummary(
            threadId = threadId,
            channelId = parentChannelId ?: channelId,
            thread =
                    if (kind == InboxItemKind.Thread && parentChannelId != null && parentMessageId != null) {
                        ThreadConversation(
                                parentChannelId = parentChannelId,
                                parentMessageId = parentMessageId,
                                streamChannelId = threadChannelId
                        )
                    } else {
                        null
                    },
            kind = parentChannelKind
                            ?: when (kind) {
                                InboxItemKind.DirectMessage -> ConversationKind.DirectMessage
                                InboxItemKind.Thread -> ConversationKind.Thread
                                else -> ConversationKind.Channel
                            },
            name = title,
            subtitle = parentMessagePreview ?: lastMessagePreview ?: latestActivityPreview,
            unreadCount = unreadCount,
            lastMessageId = latestActivityMessageId ?: lastMessageId,
            firstUnreadMessageId = firstUnreadMessageId,
            lastMessagePreview = latestActivityPreview ?: lastMessagePreview,
            lastMessageAt = latestActivityAt ?: lastMessageAt,
            lastMessageSenderName = latestActivitySenderName ?: lastMessageSenderName
    )
}

fun mergeInboxPage(current: InboxUiState, next: InboxUiState): InboxUiState {
    val currentContent = current.loadState as? ScreenLoadState.Content
    val nextContent = next.loadState as? ScreenLoadState.Content
    if (currentContent == null || nextContent == null) {
        return next.copy(loadingMore = false)
    }

    val mergedItems = (currentContent.data.items + nextContent.data.items).distinctBy { it.key }

    return next.copy(
            loadState =
                    ScreenLoadState.Content(
                            nextContent.data.copy(
                                    items = mergedItems,
                                    totalCount =
                                            nextContent.data.totalCount.coerceAtLeast(
                                                    mergedItems.size
                                            )
                            )
                    ),
            loadingMore = false
    )
}

fun InboxFilter.toRepositoryFilter(): RepositoryInboxFilter =
        when (this) {
            InboxFilter.All -> RepositoryInboxFilter.All
            InboxFilter.Unread -> RepositoryInboxFilter.Unread
        }
