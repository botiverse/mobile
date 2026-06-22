package ai.slock.compose.data

import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.InboxItemKind
import ai.slock.compose.model.MemberKind
import ai.slock.compose.model.MemberProfile
import ai.slock.compose.model.MessageId
import ai.slock.compose.model.ServerId
import ai.slock.compose.model.ServerSummary
import ai.slock.compose.model.avatarWireValueForAvatarSlot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val SharedActivityPageSize = 30

class SharedActivityStore(
    private val authStore: SlockAuthSessionStore,
    private val inboxRepository: InboxRepository,
    private val readReceiptRepository: ReadReceiptRepository,
    private val conversationRepository: ConversationRepository,
    private val memberRepository: MemberRepository? = null,
    private val scope: CoroutineScope
) {
    private val _state = MutableStateFlow(SharedActivityState())
    val state: StateFlow<SharedActivityState> = _state.asStateFlow()

    private var loadJob: Job? = null

    fun dispatch(intent: SharedActivityIntent) {
        when (intent) {
            SharedActivityIntent.LoadInitial -> load(refresh = true)
            SharedActivityIntent.Refresh -> load(refresh = true)
            SharedActivityIntent.LoadMore -> load(refresh = false)
            SharedActivityIntent.MarkAllRead -> markAllRead()
            is SharedActivityIntent.SetFilter -> setFilter(intent.filter)
            is SharedActivityIntent.MarkDone -> markDone(intent.item)
            is SharedActivityIntent.MarkRead -> markRead(intent.item)
            is SharedActivityIntent.MarkUnread -> markUnread(intent.item)
        }
    }

    fun clear() {
        loadJob?.cancel()
        loadJob = null
    }

    private fun setFilter(filter: InboxFilter) {
        if (_state.value.selectedFilter == filter) return
        _state.value =
            SharedActivityState(
                selectedFilter = filter,
                loading = true
            )
        load(refresh = true)
    }

    private fun load(refresh: Boolean) {
        val current = _state.value
        if (!refresh && (current.loading || current.refreshing || current.loadingMore || !current.hasMore)) return
        loadJob?.cancel()
        loadJob =
            scope.launch {
                val previous = _state.value
                val filter = previous.selectedFilter
                _state.value =
                    previous.copy(
                        loading = refresh && previous.snapshot == null,
                        refreshing = refresh && previous.snapshot != null,
                        loadingMore = !refresh,
                        errorCode = null,
                        loadMoreErrorCode = null
                    )
                try {
                    val session = authStore.readSession() ?: return@launch setLoadError("unauthenticated", append = !refresh)
                    val serverId = session.currentServerId?.let(::ServerId) ?: return@launch setLoadError("server_missing", append = !refresh)
                    val server = session.servers.firstOrNull { it.id == serverId.value }?.toServerSummary()
                        ?: return@launch setLoadError("server_missing", append = !refresh)
                    val result =
                        if (refresh) {
                            inboxRepository.loadInitial(serverId, filter, SharedActivityPageSize)
                        } else {
                            inboxRepository.loadMore(
                                serverId,
                                filter,
                                previous.nextOffset ?: previous.items.size,
                                SharedActivityPageSize
                            )
                        }
                    val senderProfiles = loadSenderAvatarProfiles(serverId)
                    applyLoadResult(
                        result = result,
                        server = server,
                        append = !refresh,
                        senderProfiles = senderProfiles
                    )
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    setLoadError("activity_network", append = !refresh)
                }
            }
    }

    private fun applyLoadResult(
        result: DataResult<InboxSnapshot>,
        server: ServerSummary,
        append: Boolean,
        senderProfiles: Map<String, InboxSenderAvatarProfile>
    ) {
        val current = _state.value
        _state.value =
            when (result) {
                is DataResult.Content -> {
                    val pageItems = result.data.items.withSenderAvatarProfiles(senderProfiles)
                    val items =
                        if (append) {
                            (current.items + pageItems).distinctBy { it.key }
                        } else {
                            pageItems
                        }
                    current.copy(
                        snapshot =
                            result.data.copy(
                                items = items,
                                totalCount = result.data.totalCount,
                                totalUnreadCount = result.data.totalUnreadCount,
                                hasMore = result.data.hasMore,
                                nextOffset = result.data.nextOffset
                            ),
                        loaded = true,
                        loading = false,
                        refreshing = false,
                        loadingMore = false,
                        errorCode = null,
                        loadMoreErrorCode = null
                    )
                }
                is DataResult.Empty -> {
                    val snapshot =
                        if (append) {
                            current.snapshot?.copy(hasMore = false, nextOffset = null)
                                ?: InboxSnapshot(server = server, items = emptyList())
                        } else {
                            InboxSnapshot(server = server, items = emptyList(), totalCount = 0, totalUnreadCount = 0)
                        }
                    current.copy(
                        snapshot = snapshot,
                        loaded = true,
                        loading = false,
                        refreshing = false,
                        loadingMore = false,
                        errorCode = null,
                        loadMoreErrorCode = null
                    )
                }
                is DataResult.Error ->
                    current.copy(
                        loading = false,
                        refreshing = false,
                        loadingMore = false,
                        errorCode = if (append) null else result.error.code,
                        loadMoreErrorCode = if (append) result.error.code else null
                    )
                is DataResult.NoPermission ->
                    current.copy(
                        loading = false,
                        refreshing = false,
                        loadingMore = false,
                        errorCode = if (append) null else result.blocker.code,
                        loadMoreErrorCode = if (append) result.blocker.code else null
                    )
            }
    }

    private fun markAllRead() {
        val previous = _state.value
        val snapshot = previous.snapshot ?: return
        if (snapshot.totalUnreadCount <= 0) return
        val updatedSnapshot =
            snapshot.copy(
                items =
                    if (previous.selectedFilter == InboxFilter.Unread) {
                        emptyList()
                    } else {
                        snapshot.items.map { it.copy(unreadCount = 0, firstUnreadMessageId = null, hasMention = false) }
                    },
                totalCount = if (previous.selectedFilter == InboxFilter.Unread) 0 else snapshot.totalCount,
                totalUnreadCount = 0
            )
        _state.value = previous.copy(snapshot = updatedSnapshot, errorCode = null)
        scope.launch {
            val serverId = currentServerId()
            val result = serverId?.let { readReceiptRepository.markInboxReadAll(it) }
                ?: DataResult.NoPermission(PermissionBlocker(code = "server_missing"))
            if (result is DataResult.Content || result is DataResult.Empty) {
                load(refresh = true)
            } else {
                refreshAfterFailure(previous, "activity_mark_all_read_failed")
            }
        }
    }

    private fun markDone(item: InboxItem) {
        val previous = _state.value
        if (previous.snapshot == null) return
        val targetChannelId = item.activityActionChannelId()
        if (targetChannelId == null) {
            refreshAfterFailure(previous, "activity_thread_channel_missing")
            return
        }
        removeItemOptimistically(item)
        scope.launch {
            val result =
                if (item.kind == InboxItemKind.Thread) {
                    conversationRepository.markThreadDone(targetChannelId)
                } else {
                    inboxRepository.markDone(targetChannelId)
                }
            if (result !is DataResult.Content && result !is DataResult.Empty) {
                refreshAfterFailure(previous, "activity_mark_done_failed")
            }
        }
    }

    private fun markRead(item: InboxItem) {
        if (item.unreadCount <= 0) return
        val previous = _state.value
        val snapshot = previous.snapshot ?: return
        val targetChannelId = item.activityActionChannelId()
        if (targetChannelId == null) {
            refreshAfterFailure(previous, "activity_thread_channel_missing")
            return
        }
        val key = item.key
        val updatedItems =
            if (previous.selectedFilter == InboxFilter.Unread) {
                snapshot.items.filterNot { it.key == key }
            } else {
                snapshot.items.map { existing ->
                    if (existing.key == key) {
                        existing.copy(unreadCount = 0, firstUnreadMessageId = null, hasMention = false)
                    } else {
                        existing
                    }
                }
            }
        _state.value =
            previous.copy(
                snapshot =
                    snapshot.copy(
                        items = updatedItems,
                        totalCount = if (previous.selectedFilter == InboxFilter.Unread) maxOf(0, snapshot.totalCount - 1) else snapshot.totalCount,
                        totalUnreadCount = maxOf(0, snapshot.totalUnreadCount - item.unreadCount)
                    ),
                errorCode = null
            )
        scope.launch {
            val result = readReceiptRepository.markChannelReadAll(targetChannelId)
            if (result !is DataResult.Content && result !is DataResult.Empty) {
                refreshAfterFailure(previous, "activity_mark_read_failed")
            }
        }
    }

    private fun markUnread(item: InboxItem) {
        val previous = _state.value
        val snapshot = previous.snapshot ?: return
        val targetChannelId = item.activityActionChannelId()
        if (targetChannelId == null) {
            refreshAfterFailure(previous, "activity_thread_channel_missing")
            return
        }
        val wasUnread = item.unreadCount > 0
        val optimisticUnreadCount = item.unreadCount.takeIf { it > 0 } ?: 1
        val key = item.key
        val updatedItems =
            snapshot.items.map { existing ->
                if (existing.key == key) {
                    existing.copy(
                        unreadCount = optimisticUnreadCount,
                        firstUnreadMessageId = existing.firstUnreadMessageId ?: existing.activityUnreadAnchor()
                    )
                } else {
                    existing
                }
            }
        _state.value =
            previous.copy(
                snapshot =
                    snapshot.copy(
                        items = updatedItems,
                        totalUnreadCount =
                            if (wasUnread) {
                                snapshot.totalUnreadCount
                            } else {
                                snapshot.totalUnreadCount + optimisticUnreadCount
                            }
                    ),
                errorCode = null
            )
        scope.launch {
            val result = readReceiptRepository.markChannelUnread(targetChannelId)
            if (result is DataResult.Content || result is DataResult.Empty) {
                load(refresh = true)
            } else {
                refreshAfterFailure(previous, "activity_mark_unread_failed")
            }
        }
    }

    private fun removeItemOptimistically(item: InboxItem) {
        val current = _state.value
        val snapshot = current.snapshot ?: return
        _state.value =
            current.copy(
                snapshot =
                    snapshot.copy(
                        items = snapshot.items.filterNot { it.key == item.key },
                        totalCount = maxOf(0, snapshot.totalCount - 1),
                        totalUnreadCount = maxOf(0, snapshot.totalUnreadCount - item.unreadCount)
                    ),
                errorCode = null
            )
    }

    private fun refreshAfterFailure(
        previous: SharedActivityState,
        code: String
    ) {
        _state.value = previous.copy(loading = false, refreshing = false, loadingMore = false, errorCode = code)
        load(refresh = true)
    }

    private fun setLoadError(code: String, append: Boolean = false) {
        _state.value =
            _state.value.copy(
                loading = false,
                refreshing = false,
                loadingMore = false,
                errorCode = if (append) null else code,
                loadMoreErrorCode = if (append) code else null
            )
    }

    private suspend fun currentServerId(): ServerId? =
        authStore.readSession()?.currentServerId?.takeIf { it.isNotBlank() }?.let(::ServerId)

    private suspend fun loadSenderAvatarProfiles(serverId: ServerId): Map<String, InboxSenderAvatarProfile> =
        try {
            when (val result = memberRepository?.getMembers(DataRequestPriority.Visible, serverId)) {
                is DataResult.Content ->
                    result.data.members.associateBy(
                        keySelector = { it.id.value },
                        valueTransform = {
                            InboxSenderAvatarProfile(
                                kind = it.kind,
                                avatarWireValue = it.avatarWireValueForActivity()
                            )
                        }
                    )
                else -> emptyMap()
            }
        } catch (_: Throwable) {
            emptyMap()
        }
}

data class SharedActivityState(
    val selectedFilter: InboxFilter = InboxFilter.All,
    val snapshot: InboxSnapshot? = null,
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val errorCode: String? = null,
    val loadMoreErrorCode: String? = null
) {
    val items: List<InboxItem>
        get() = snapshot?.items.orEmpty()
    val totalCount: Int
        get() = snapshot?.totalCount ?: 0
    val totalUnreadCount: Int
        get() = snapshot?.totalUnreadCount ?: 0
    val hasMore: Boolean
        get() = snapshot?.hasMore == true
    val nextOffset: Int?
        get() = snapshot?.nextOffset
}

sealed interface SharedActivityIntent {
    data object LoadInitial : SharedActivityIntent
    data object Refresh : SharedActivityIntent
    data object LoadMore : SharedActivityIntent
    data object MarkAllRead : SharedActivityIntent
    data class SetFilter(val filter: InboxFilter) : SharedActivityIntent
    data class MarkDone(val item: InboxItem) : SharedActivityIntent
    data class MarkRead(val item: InboxItem) : SharedActivityIntent
    data class MarkUnread(val item: InboxItem) : SharedActivityIntent
}

private data class InboxSenderAvatarProfile(
    val kind: MemberKind,
    val avatarWireValue: String?
)

private fun MemberProfile.avatarWireValueForActivity(): String? =
    avatarWireValueForAvatarSlot(
        kind = kind,
        id = id.value,
        avatarWireValue = avatar?.wireValue,
        avatarUrl = avatarUrl,
        gravatarHash = gravatarHash
    )

private fun List<InboxItem>.withSenderAvatarProfiles(
    senderProfiles: Map<String, InboxSenderAvatarProfile>
): List<InboxItem> {
    if (senderProfiles.isEmpty()) return this
    return map { item ->
        val lastSender = item.lastMessageSenderId?.let(senderProfiles::get)
        val latestSender = item.latestActivitySenderId?.let(senderProfiles::get)
        item.copy(
            lastMessageSenderKind = lastSender?.kind ?: item.lastMessageSenderKind,
            lastMessageSenderAvatarWireValue = lastSender?.avatarWireValue ?: item.lastMessageSenderAvatarWireValue,
            latestActivitySenderKind = latestSender?.kind ?: item.latestActivitySenderKind,
            latestActivitySenderAvatarWireValue = latestSender?.avatarWireValue ?: item.latestActivitySenderAvatarWireValue
        )
    }
}

private fun InboxItem.activityActionChannelId(): ChannelId? =
    if (kind == InboxItemKind.Thread) threadChannelId else channelId

private fun InboxItem.activityUnreadAnchor(): MessageId? =
    firstUnreadMessageId ?: latestActivityMessageId ?: lastMessageId ?: parentMessageId
