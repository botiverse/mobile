package ai.slock.android.feature.inbox

import ai.slock.compose.data.DataResult
import ai.slock.compose.data.InboxFilter as RepositoryInboxFilter
import ai.slock.compose.data.InboxSnapshot
import ai.slock.compose.data.SavedSnapshot
import ai.slock.compose.data.SearchSnapshot
import ai.slock.compose.data.ThreadListSnapshot
import ai.slock.android.core.model.ChannelId
import ai.slock.android.core.model.ConversationKind
import ai.slock.android.core.model.ConversationSummary
import ai.slock.android.core.model.InboxItem
import ai.slock.android.core.model.InboxItemKind
import ai.slock.android.core.model.Message
import ai.slock.android.core.model.MessageType
import ai.slock.android.core.model.RealtimeEvent
import ai.slock.android.core.model.MessageNew
import ai.slock.android.core.model.MessageUpdated
import ai.slock.android.core.model.DirectMessageCreated
import ai.slock.android.core.model.ThreadUpdated
import ai.slock.android.core.model.TaskCreated
import ai.slock.android.core.model.TaskUpdated
import ai.slock.android.core.model.TaskDeleted
import ai.slock.android.core.model.MemberPresenceUpdated
import ai.slock.android.core.model.SyncResumeResponse
import ai.slock.android.core.model.Heartbeat
import ai.slock.android.core.model.ThreadId
import ai.slock.android.core.model.ThreadConversation
import ai.slock.compose.model.parentChannelIdOrNull
import ai.slock.compose.model.threadIdentity
import ai.slock.android.core.state.EmptyReason
import ai.slock.android.core.state.ScreenLoadState
import ai.slock.compose.model.topLevelThreadId

fun InboxUiState.visibleContent(): InboxContent? =
        (loadState as? ScreenLoadState.Content)?.data ?: previousContent

fun InboxUiState.visibleUnreadCount(): Int? = visibleContent()?.totalUnreadCount

fun InboxUiState.visibleTotalCount(): Int? = visibleContent()?.totalCount

fun InboxUiState.contentOrNull(): InboxContent? =
        (loadState as? ScreenLoadState.Content)?.data

fun InboxContent.toInboxUiState(selectedFilter: InboxFilter = InboxFilter.All): InboxUiState =
        InboxUiState(
                loadState = ScreenLoadState.Content(this),
                selectedFilter = selectedFilter
        )

fun InboxUiState.clearAllUnread(): InboxUiState {
    fun InboxContent.clearUnreadContent(): InboxContent {
        val updatedItems =
                if (selectedFilter == InboxFilter.Unread || selectedFilter == InboxFilter.Mentions) {
                    emptyList()
                } else {
                    items.map { item -> item.copy(unreadCount = 0, firstUnreadMessageId = null) }
                }
        return copy(
            items = updatedItems,
            totalCount = if (selectedFilter == InboxFilter.Unread || selectedFilter == InboxFilter.Mentions) 0 else totalCount,
            totalUnreadCount = 0
        )
    }
    return copy(
            loadState =
                    when (val state = loadState) {
                        is ScreenLoadState.Content ->
                                ScreenLoadState.Content(state.data.clearUnreadContent())
                        else -> loadState
                    },
            previousContent = previousContent?.clearUnreadContent()
    )
}

fun DataResult<InboxSnapshot>.toInboxUiState(
        selectedFilter: InboxFilter
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
                nextOffset = (this as? DataResult.Content)?.data?.nextOffset
        )

fun DataResult<ThreadListSnapshot>.toFollowedThreadsUiState(
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
                nextOffset = (this as? DataResult.Content)?.data?.nextOffset
        )

fun DataResult<SavedSnapshot>.toSavedInboxUiState(
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
                nextOffset = (this as? DataResult.Content)?.data?.nextOffset
        )

fun DataResult<SearchSnapshot>.toSearchInboxUiState(
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
                nextOffset = (this as? DataResult.Content)?.data?.nextOffset
        )

fun InboxUiState.applyRealtimeEvent(
        event: RealtimeEvent,
        query: String? = null
): InboxUiState =
        when (event) {
            is MessageNew -> applyRealtimeMessages(listOf(event.message), query)
            is MessageUpdated -> applyRealtimeMessages(listOf(event.message), query)
            is SyncResumeResponse -> applyRealtimeMessages(event.messages, query)
            is ThreadUpdated -> applyThreadUpdated(event)
            else -> this
        }

fun InboxUiState.applySavedRealtimeEvent(event: RealtimeEvent): InboxUiState =
        when (event) {
            is MessageUpdated -> updateExistingMessage(event.message)
            is SyncResumeResponse ->
                    event.messages.fold(this) { state, message ->
                        state.updateExistingMessage(message)
                    }
            else -> this
        }

private fun InboxUiState.applyThreadUpdated(event: ThreadUpdated): InboxUiState {
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
                        else if (parentMessageId != null || threadId.parentChannelIdOrNull() != null)
                                InboxItemKind.Thread
                        else InboxItemKind.Channel,
                channelId = channelId ?: threadId.parentChannelIdOrNull() ?: ChannelId(threadId.value),
                channelName = channelId?.value ?: threadId.parentChannelIdOrNull()?.value ?: threadId.value,
                unreadCount = 1,
                firstUnreadMessageId = id,
                lastMessageId = id,
                lastMessagePreview = body,
                lastMessageAt = timestampLabel,
                lastMessageSenderName = author.displayName,
                threadChannelId = threadChannelId ?: channelId,
                latestActivityMessageId = id,
                latestActivityPreview = body,
                latestActivityAt = timestampLabel,
                latestActivitySenderName = author.displayName,
                replyCount = replyCount
        )

fun InboxItem.toConversationSummary(): ConversationSummary {
    val itemParentChannelId = parentChannelId
    val itemParentMessageId = parentMessageId
    val threadId =
            if (kind == InboxItemKind.Thread && itemParentChannelId != null && itemParentMessageId != null) {
                threadIdentity(itemParentChannelId, itemParentMessageId)
            } else {
                topLevelThreadId(threadChannelId ?: channelId)
            }
    val title =
            when (kind) {
                InboxItemKind.Thread -> parentChannelName ?: channelName
                InboxItemKind.DirectMessage, InboxItemKind.Channel, InboxItemKind.System -> channelName
            }
    return ConversationSummary(
            threadId = threadId,
            channelId = itemParentChannelId ?: channelId,
            thread =
                    if (kind == InboxItemKind.Thread && itemParentChannelId != null && itemParentMessageId != null) {
                        ThreadConversation(
                                parentChannelId = itemParentChannelId,
                                parentMessageId = itemParentMessageId,
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
            InboxFilter.Mentions -> RepositoryInboxFilter.Mentions
        }
