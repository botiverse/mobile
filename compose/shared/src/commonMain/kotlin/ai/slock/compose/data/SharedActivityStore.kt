package ai.slock.compose.data

import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.InboxItemKind
import ai.slock.compose.model.MemberProfile
import ai.slock.compose.model.MessageId
import ai.slock.compose.model.DirectMessageCreated
import ai.slock.compose.model.MessageNew
import ai.slock.compose.model.MessageUpdated
import ai.slock.compose.model.RealtimeEvent
import ai.slock.compose.model.RealtimeRefreshRequired
import ai.slock.compose.model.RealtimeRefreshScope
import ai.slock.compose.model.ServerId
import ai.slock.compose.model.ServerSummary
import ai.slock.compose.model.SyncResumeResponse
import ai.slock.compose.model.TaskCreated
import ai.slock.compose.model.TaskDeleted
import ai.slock.compose.model.TaskUpdated
import ai.slock.compose.model.ThreadUpdated
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
    private val serverMembersStore: SharedServerMembersStore? = null,
    localWriteThrough: ComposeLocalWriteThrough? = null,
    private val activityStateCache: SharedActivityStateCache? = null,
    onUnreadCountsChanged: () -> Unit = {},
    scope: CoroutineScope
) {
    private val _state = MutableStateFlow(SharedActivityState())
    val state: StateFlow<SharedActivityState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var localWriteThrough: ComposeLocalWriteThrough? = localWriteThrough
    private var onUnreadCountsChanged: () -> Unit = onUnreadCountsChanged
    private var scope: CoroutineScope = scope
    private var unreadClearOverrides: Map<String, ActivityUnreadClearOverride> = emptyMap()

    fun updateRuntimeIntegrations(
        localWriteThrough: ComposeLocalWriteThrough? = null,
        onUnreadCountsChanged: (() -> Unit)? = null,
        scope: CoroutineScope? = null
    ) {
        if (localWriteThrough != null) {
            this.localWriteThrough = localWriteThrough
        }
        if (onUnreadCountsChanged != null) {
            this.onUnreadCountsChanged = onUnreadCountsChanged
        }
        if (scope != null) {
            this.scope = scope
        }
    }

    fun dispatch(intent: SharedActivityIntent) {
        when (intent) {
            SharedActivityIntent.ClearSummary -> clearSummary()
            SharedActivityIntent.LoadInitial -> load(refresh = true)
            SharedActivityIntent.Refresh -> load(refresh = true, pullRefresh = false)
            SharedActivityIntent.PullRefresh -> load(refresh = true, pullRefresh = true)
            SharedActivityIntent.LoadMore -> load(refresh = false)
            SharedActivityIntent.MarkAllRead -> markAllRead()
            is SharedActivityIntent.SetFilter -> setFilter(intent.filter)
            is SharedActivityIntent.MarkDone -> markDone(intent.item)
            is SharedActivityIntent.MarkRead -> markRead(intent.item)
            is SharedActivityIntent.MarkUnread -> markUnread(intent.item)
            is SharedActivityIntent.ApplyRealtimeEvent -> applyRealtimeEvent(intent.event)
        }
    }

    fun clear() {
        loadJob?.cancel()
        loadJob = null
    }

    fun reset() {
        clear()
        unreadClearOverrides = emptyMap()
        _state.value = SharedActivityState()
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

    private fun clearSummary() {
        _state.value =
            SharedActivityState(
                selectedFilter = _state.value.selectedFilter
            )
    }

    private fun applyRealtimeEvent(event: RealtimeEvent) {
        if (!event.refreshesActivity()) return
        val current = _state.value
        if (current.loading || current.refreshing) return
        if (!current.loaded || current.snapshot != null) {
            load(refresh = true, pullRefresh = false)
        }
    }

    private fun load(
        refresh: Boolean,
        pullRefresh: Boolean = false
    ) {
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
                        pullRefreshing = refresh && pullRefresh && previous.snapshot != null,
                        loadingMore = !refresh,
                        errorCode = null,
                        loadMoreErrorCode = null
                    )
                try {
                    val session = authStore.readSession() ?: return@launch setLoadError("unauthenticated", append = !refresh)
                    val serverId = session.currentServerId?.let(::ServerId) ?: return@launch setLoadError("server_missing", append = !refresh)
                    val userId = session.user.id
                    val server = session.servers.firstOrNull { it.id == serverId.value }?.toServerSummary()
                        ?: return@launch setLoadError("server_missing", append = !refresh)
                    if (refresh && previous.snapshot == null) {
                        activityStateCache?.read(serverId.value, userId, filter)?.let { cached ->
                            _state.value =
                                cached.copy(
                                    refreshing = true,
                                    pullRefreshing = false,
                                    loading = false,
                                    loadingMore = false,
                                    errorCode = null,
                                    loadMoreErrorCode = null
                                )
                        }
                    }
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
                    val senderProfiles = loadSenderProfiles(serverId)
                    applyLoadResult(
                        result = result,
                        server = server,
                        append = !refresh,
                        senderProfiles = senderProfiles,
                        userId = userId
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
        senderProfiles: Map<String, SharedActivitySenderProfile>,
        userId: String
    ) {
        val current = _state.value
        _state.value =
            when (result) {
                is DataResult.Content -> {
                    val pageItems =
                        result.data.items
                            .reconcileLocallyClearedUnreadItems(current.selectedFilter)
                    val items =
                        if (append) {
                            (current.items + pageItems.items).distinctBy { it.key }
                        } else {
                            pageItems.items
                        }
                    val nextState =
                        current.copy(
                            snapshot =
                                result.data.copy(
                                    items = items,
                                    totalCount =
                                        if (current.selectedFilter == InboxFilter.Unread) {
                                            maxOf(0, result.data.totalCount - pageItems.removedUnreadRows)
                                        } else {
                                            result.data.totalCount
                                        },
                                    totalUnreadCount = maxOf(0, result.data.totalUnreadCount - pageItems.suppressedUnreadCount),
                                    hasMore = result.data.hasMore,
                                    nextOffset = result.data.nextOffset
                                ),
                            senderProfiles = if (append) current.senderProfiles + senderProfiles else senderProfiles,
                            loaded = true,
                            loading = false,
                            refreshing = false,
                            pullRefreshing = false,
                            loadingMore = false,
                            errorCode = null,
                            loadMoreErrorCode = null
                        )
                    activityStateCache?.write(result.data.server.id.value, userId, nextState)
                    nextState
                }
                is DataResult.Empty -> {
                    val snapshot =
                        if (append) {
                            current.snapshot?.copy(hasMore = false, nextOffset = null)
                                ?: InboxSnapshot(server = server, items = emptyList())
                        } else {
                            InboxSnapshot(server = server, items = emptyList(), totalCount = 0, totalUnreadCount = 0)
                        }
                    val nextState =
                        current.copy(
                            snapshot = snapshot,
                            senderProfiles = if (append) current.senderProfiles else senderProfiles,
                            loaded = true,
                            loading = false,
                            refreshing = false,
                            pullRefreshing = false,
                            loadingMore = false,
                            errorCode = null,
                            loadMoreErrorCode = null
                        )
                    activityStateCache?.write(server.id.value, userId, nextState)
                    nextState
                }
                is DataResult.Error ->
                    current.copy(
                        loading = false,
                        refreshing = false,
                        pullRefreshing = false,
                        loadingMore = false,
                        errorCode = if (append) null else result.error.code,
                        loadMoreErrorCode = if (append) result.error.code else null
                    )
                is DataResult.NoPermission ->
                    current.copy(
                        loading = false,
                        refreshing = false,
                        pullRefreshing = false,
                        loadingMore = false,
                        errorCode = if (append) null else result.blocker.code,
                        loadMoreErrorCode = if (append) result.blocker.code else null
                    ).also {
                        activityStateCache?.clear(server.id.value, userId, current.selectedFilter)
                    }
            }
    }

    private fun markAllRead() {
        val previous = _state.value
        val snapshot = previous.snapshot ?: return
        if (snapshot.totalUnreadCount <= 0) return
        val updatedSnapshot =
            snapshot.copy(
                items =
                    if (previous.selectedFilter.keepsReadActivityRows()) {
                        snapshot.items.map { it.copy(unreadCount = 0, firstUnreadMessageId = null, hasMention = false) }
                    } else {
                        emptyList()
                    },
                totalCount = if (previous.selectedFilter.keepsReadActivityRows()) snapshot.totalCount else 0,
                totalUnreadCount = 0
            )
        rememberUnreadCleared(snapshot.items)
        _state.value = previous.copy(snapshot = updatedSnapshot, errorCode = null)
        scope.launch {
            val serverId = currentServerId()
            serverId?.let { localWriteThrough?.clearServerUnread(it, "shared_activity_mark_all_read") }
            val result = serverId?.let { readReceiptRepository.markInboxReadAll(it) }
                ?: DataResult.NoPermission(PermissionBlocker(code = "server_missing"))
            if (result is DataResult.Content || result is DataResult.Empty) {
                onUnreadCountsChanged()
                load(refresh = true, pullRefresh = false)
            } else {
                forgetUnreadCleared(snapshot.items)
                onUnreadCountsChanged()
                refreshAfterFailure(previous, "activity_mark_all_read_failed")
            }
        }
    }

    private fun markDone(item: InboxItem) {
        val previous = _state.value
        if (previous.snapshot == null) return
        if (item.kind == InboxItemKind.MentionAction) {
            removeItemOptimistically(item)
            return
        }
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
        rememberUnreadCleared(item)
        val updatedItems =
            if (previous.selectedFilter.keepsReadActivityRows()) {
                snapshot.items.map { existing ->
                    if (existing.key == key) {
                        existing.copy(unreadCount = 0, firstUnreadMessageId = null, hasMention = false)
                    } else {
                        existing
                    }
                }
            } else {
                snapshot.items.filterNot { it.key == key }
            }
        _state.value =
            previous.copy(
                snapshot =
                    snapshot.copy(
                        items = updatedItems,
                        totalCount = if (previous.selectedFilter.keepsReadActivityRows()) snapshot.totalCount else maxOf(0, snapshot.totalCount - 1),
                        totalUnreadCount = maxOf(0, snapshot.totalUnreadCount - item.unreadCount)
                    ),
                errorCode = null
            )
        scope.launch {
            val serverId = currentServerId()
            localWriteThrough?.clearConversationUnread(targetChannelId, "shared_activity_mark_read", serverId)
            val result = readReceiptRepository.markChannelReadAll(targetChannelId)
            if (result !is DataResult.Content && result !is DataResult.Empty) {
                forgetUnreadCleared(key)
                onUnreadCountsChanged()
                refreshAfterFailure(previous, "activity_mark_read_failed")
            } else {
                onUnreadCountsChanged()
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
        forgetUnreadCleared(key)
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
                onUnreadCountsChanged()
                load(refresh = true, pullRefresh = false)
            } else {
                onUnreadCountsChanged()
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
        _state.value = previous.copy(loading = false, refreshing = false, pullRefreshing = false, loadingMore = false, errorCode = code)
        load(refresh = true, pullRefresh = false)
    }

    private fun setLoadError(code: String, append: Boolean = false) {
        _state.value =
            _state.value.copy(
                loading = false,
                refreshing = false,
                pullRefreshing = false,
                loadingMore = false,
                errorCode = if (append) null else code,
                loadMoreErrorCode = if (append) code else null
            )
    }

    private suspend fun currentServerId(): ServerId? =
        authStore.readSession()?.currentServerId?.takeIf { it.isNotBlank() }?.let(::ServerId)

    private suspend fun loadSenderProfiles(serverId: ServerId): Map<String, SharedActivitySenderProfile> =
        try {
            when (
                val result =
                    serverMembersStore?.loadMembers(serverId, DataRequestPriority.Visible)
                        ?: memberRepository?.getMembers(DataRequestPriority.Visible, serverId)
            ) {
                is DataResult.Content ->
                    result.data.members.associateBy(
                        keySelector = { it.id.value },
                        valueTransform = {
                            SharedActivitySenderProfile(
                                displayName = it.displayName.ifBlank { it.handle.ifBlank { it.id.value } }
                            )
                        }
                    )
                else -> emptyMap()
            }
        } catch (_: Throwable) {
            emptyMap()
        }

    private fun rememberUnreadCleared(items: List<InboxItem>) {
        val unreadItems = items.filter { it.unreadCount > 0 }
        if (unreadItems.isEmpty()) return
        unreadClearOverrides =
            unreadClearOverrides +
                unreadItems.associate { item ->
                    item.key to ActivityUnreadClearOverride(item.activityUnreadAnchor())
                }
    }

    private fun rememberUnreadCleared(item: InboxItem) {
        if (item.unreadCount <= 0) return
        unreadClearOverrides =
            unreadClearOverrides +
                (item.key to ActivityUnreadClearOverride(item.activityUnreadAnchor()))
    }

    private fun forgetUnreadCleared(items: List<InboxItem>) {
        if (unreadClearOverrides.isEmpty()) return
        val keys = items.mapTo(mutableSetOf()) { it.key }
        unreadClearOverrides = unreadClearOverrides.filterKeys { it !in keys }
    }

    private fun forgetUnreadCleared(key: String) {
        if (key in unreadClearOverrides) {
            unreadClearOverrides = unreadClearOverrides - key
        }
    }

    private fun List<InboxItem>.reconcileLocallyClearedUnreadItems(filter: InboxFilter): ActivityUnreadReconcileResult {
        if (unreadClearOverrides.isEmpty()) {
            return ActivityUnreadReconcileResult(items = this)
        }
        val nextOverrides = unreadClearOverrides.toMutableMap()
        val reconciled = mutableListOf<InboxItem>()
        var suppressedUnreadCount = 0
        var removedUnreadRows = 0
        for (item in this) {
            val override = nextOverrides[item.key]
            if (override == null) {
                reconciled += item
                continue
            }
            if (item.unreadCount <= 0) {
                nextOverrides.remove(item.key)
                reconciled += item
                continue
            }
            val incomingAnchor = item.activityUnreadAnchor()
            val staleUnread =
                override.unreadAnchor == null ||
                    incomingAnchor == override.unreadAnchor
            if (staleUnread) {
                suppressedUnreadCount += item.unreadCount
                val clearedItem = item.copy(unreadCount = 0, firstUnreadMessageId = null, hasMention = false)
                if (filter.keepsReadActivityRows()) {
                    reconciled += clearedItem
                } else {
                    removedUnreadRows += 1
                }
            } else {
                nextOverrides.remove(item.key)
                reconciled += item
            }
        }
        unreadClearOverrides = nextOverrides
        return ActivityUnreadReconcileResult(
            items = reconciled,
            suppressedUnreadCount = suppressedUnreadCount,
            removedUnreadRows = removedUnreadRows
        )
    }
}

private data class ActivityUnreadClearOverride(
    val unreadAnchor: MessageId?
)

private data class ActivityUnreadReconcileResult(
    val items: List<InboxItem>,
    val suppressedUnreadCount: Int = 0,
    val removedUnreadRows: Int = 0
)

data class SharedActivityState(
    val selectedFilter: InboxFilter = InboxFilter.All,
    val snapshot: InboxSnapshot? = null,
    val senderProfiles: Map<String, SharedActivitySenderProfile> = emptyMap(),
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val pullRefreshing: Boolean = false,
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

class SharedActivityStateCache {
    private var ownerKey: String? = null
    private var state: SharedActivityState? = null

    fun read(serverId: String, userId: String, filter: InboxFilter): SharedActivityState? =
        state.takeIf { ownerKey == activityStateCacheKey(serverId, userId, filter) }

    fun write(serverId: String, userId: String?, state: SharedActivityState) {
        if (!state.loaded || state.snapshot == null) return
        val resolvedUserId = userId?.takeIf { it.isNotBlank() } ?: return
        ownerKey = activityStateCacheKey(serverId, resolvedUserId, state.selectedFilter)
        this.state =
            state.copy(
                loading = false,
                refreshing = false,
                pullRefreshing = false,
                loadingMore = false,
                errorCode = null,
                loadMoreErrorCode = null
            )
    }

    fun clear() {
        ownerKey = null
        state = null
    }

    fun clear(serverId: String, userId: String, filter: InboxFilter) {
        if (ownerKey == activityStateCacheKey(serverId, userId, filter)) {
            clear()
        }
    }
}

private fun activityStateCacheKey(serverId: String, userId: String, filter: InboxFilter): String =
    "${serverId.trim()}:${userId.trim()}:${filter.name}"

private fun InboxFilter.keepsReadActivityRows(): Boolean =
    this == InboxFilter.All

sealed interface SharedActivityIntent {
    data object ClearSummary : SharedActivityIntent
    data object LoadInitial : SharedActivityIntent
    data object Refresh : SharedActivityIntent
    data object PullRefresh : SharedActivityIntent
    data object LoadMore : SharedActivityIntent
    data object MarkAllRead : SharedActivityIntent
    data class SetFilter(val filter: InboxFilter) : SharedActivityIntent
    data class MarkDone(val item: InboxItem) : SharedActivityIntent
    data class MarkRead(val item: InboxItem) : SharedActivityIntent
    data class MarkUnread(val item: InboxItem) : SharedActivityIntent
    data class ApplyRealtimeEvent(val event: RealtimeEvent) : SharedActivityIntent
}

private fun RealtimeEvent.refreshesActivity(): Boolean =
    when (this) {
        is MessageNew,
        is MessageUpdated,
        is DirectMessageCreated,
        is ThreadUpdated,
        is TaskCreated,
        is TaskUpdated,
        is TaskDeleted,
        is SyncResumeResponse -> true
        is RealtimeRefreshRequired -> scope == RealtimeRefreshScope.Home
        else -> false
    }

data class SharedActivitySenderProfile(
    val displayName: String
)

private fun InboxItem.activityActionChannelId(): ChannelId? =
    if (kind == InboxItemKind.Thread) threadChannelId else channelId

private fun InboxItem.activityUnreadAnchor(): MessageId? =
    firstUnreadMessageId ?: latestActivityMessageId ?: lastMessageId ?: parentMessageId
