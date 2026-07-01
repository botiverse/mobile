package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Message
import build.raft.app.model.MessageId
import build.raft.app.model.DirectMessageCreated
import build.raft.app.model.MessageNew
import build.raft.app.model.MessageUpdated
import build.raft.app.model.RealtimeEvent
import build.raft.app.model.RealtimeRefreshRequired
import build.raft.app.model.RealtimeRefreshScope
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.SyncResumeResponse
import build.raft.app.model.TaskCreated
import build.raft.app.model.TaskDeleted
import build.raft.app.model.TaskSummary
import build.raft.app.model.TaskUpdated
import build.raft.app.model.ThreadUpdated
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.TimeSource

private const val SharedActivityPageSize = 30

class SharedActivityStore internal constructor(
    private val authStore: SlockAuthSessionStore,
    private val inboxRepository: InboxRepository,
    private val readReceiptRepository: ReadReceiptRepository,
    private val conversationRepository: ConversationRepository,
    private val memberRepository: MemberRepository? = null,
    private val serverMembersStore: SharedServerMembersStore? = null,
    private val tasksDataStore: SharedTasksDataStore? = null,
    localWriteThrough: ComposeLocalWriteThrough? = null,
    private val activityStateCache: SharedActivityStateCache? = null,
    onUnreadCountsChanged: () -> Unit = {},
    scope: CoroutineScope,
    private var diagnosticsLogger: (String) -> Unit = {}
) {
    private val _state = MutableStateFlow(SharedActivityState())
    val state: StateFlow<SharedActivityState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var localWriteThrough: ComposeLocalWriteThrough? = localWriteThrough
    private var onUnreadCountsChanged: () -> Unit = onUnreadCountsChanged
    private var scope: CoroutineScope = scope
    private var unreadClearOverrides: Map<String, ActivityUnreadClearOverride> = emptyMap()
    private var doneOverrides: Map<String, ActivityDoneOverride> = emptyMap()

    fun updateRuntimeIntegrations(
        localWriteThrough: ComposeLocalWriteThrough? = null,
        onUnreadCountsChanged: (() -> Unit)? = null,
        scope: CoroutineScope? = null,
        diagnosticsLogger: ((String) -> Unit)? = null
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
        if (diagnosticsLogger != null) {
            this.diagnosticsLogger = diagnosticsLogger
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
            is SharedActivityIntent.ApplyRealtimeSummaryEvent ->
                applyRealtimeSummaryEvent(intent.event, intent.activeConversationId)
        }
    }

    fun clear() {
        loadJob?.cancel()
        loadJob = null
    }

    fun reset() {
        clear()
        unreadClearOverrides = emptyMap()
        doneOverrides = emptyMap()
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

    private fun applyRealtimeSummaryEvent(
        event: RealtimeEvent,
        activeConversationId: String?
    ) {
        scope.launch {
            val current = _state.value
            val snapshot = current.snapshot ?: return@launch
            val currentUserId = currentUserId() ?: return@launch
            val increment = event.activitySummaryUnreadIncrement(snapshot, activeConversationId, currentUserId)
            if (increment <= 0) return@launch
            _state.value =
                current.copy(
                    snapshot =
                        snapshot.copy(
                            totalUnreadCount = snapshot.totalUnreadCount + increment
                        ),
                    errorCode = null
                )
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
                val startedAt = TimeSource.Monotonic.markNow()
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
                    val session = authStore.readSession()
                        ?: return@launch setLoadError("unauthenticated", append = !refresh).also {
                            logActivityLoad(
                                result = "error",
                                code = "unauthenticated",
                                refresh = refresh,
                                append = !refresh,
                                filter = filter,
                                durationMs = startedAt.elapsedNow().inWholeMilliseconds
                            )
                        }
                    val serverId = session.currentServerId?.let(::ServerId)
                        ?: return@launch setLoadError("server_missing", append = !refresh).also {
                            logActivityLoad(
                                result = "error",
                                code = "server_missing",
                                refresh = refresh,
                                append = !refresh,
                                filter = filter,
                                durationMs = startedAt.elapsedNow().inWholeMilliseconds
                            )
                        }
                    val userId = session.user.id
                    val server = session.servers.firstOrNull { it.id == serverId.value }?.toServerSummary()
                        ?: return@launch setLoadError("server_missing", append = !refresh)
                    if (refresh && previous.snapshot == null) {
                        activityStateCache?.read(serverId.value, userId, filter)?.let { cached ->
                            _state.value =
                                cached.copy(
                                    senderProfiles =
                                        cached.senderProfiles + currentSenderProfiles(serverId),
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
                    logActivityLoad(
                        result = result.activityDiagnosticsResult(),
                        code = null,
                        refresh = refresh,
                        append = !refresh,
                        filter = filter,
                        durationMs = startedAt.elapsedNow().inWholeMilliseconds
                    )
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    setLoadError("activity_network", append = !refresh)
                    logActivityLoad(
                        result = "exception",
                        code = "activity_network",
                        refresh = refresh,
                        append = !refresh,
                        filter = filter,
                        durationMs = startedAt.elapsedNow().inWholeMilliseconds
                    )
                }
            }
    }

    private fun logActivityLoad(
        result: String,
        code: String?,
        refresh: Boolean,
        append: Boolean,
        filter: InboxFilter,
        durationMs: Long
    ) {
        diagnosticsLogger(
            "store_load domain=activity refresh=$refresh append=$append filter=${filter.name} " +
                "result=$result code=${code.orEmpty()} durationMs=$durationMs"
        )
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
                            .withActivityThreadTaskProjection(result.data.server.id.value, tasksDataStore)
                            .reconcileLocallyClearedUnreadItems(current.selectedFilter)
                            .reconcileLocallyDoneItems()
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
                                            maxOf(0, result.data.totalCount - pageItems.removedUnreadRows - pageItems.removedDoneRows)
                                        } else {
                                            maxOf(0, result.data.totalCount - pageItems.removedDoneRows)
                                        },
                                    totalUnreadCount =
                                        maxOf(
                                            0,
                                            result.data.totalUnreadCount -
                                                pageItems.suppressedUnreadCount -
                                                pageItems.suppressedDoneUnreadCount
                                        ),
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
        rememberDone(item)
        if (item.kind == InboxItemKind.MentionAction) {
            removeItemOptimistically(item)
            return
        }
        val targetChannelId = item.activityActionChannelId()
        if (targetChannelId == null) {
            forgetDone(item.key)
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
                forgetDone(item.key)
                refreshAfterFailure(previous, "activity_mark_done_failed")
            } else {
                onUnreadCountsChanged()
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

    private suspend fun currentUserId(): String? =
        authStore.readSession()
            ?.user
            ?.id
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    private suspend fun loadSenderProfiles(serverId: ServerId): Map<String, SharedActivitySenderProfile> =
        try {
            val cachedProfiles = currentSenderProfiles(serverId)
            val profilesById =
                serverMembersStore?.let { store ->
                    when (store.refreshMembers(serverId, DataRequestPriority.Visible)) {
                        is DataResult.Content -> store.currentServerMemberProfilesById(serverId)
                        else -> emptyMap()
                    }
                } ?: when (val result = memberRepository?.getMembers(DataRequestPriority.Visible, serverId)) {
                    is DataResult.Content ->
                        result.data.members
                            .filter { it.id.value.trim().isNotBlank() }
                            .associateBy { it.id.value.trim() }
                    else -> emptyMap()
                }
            cachedProfiles + profilesById.toActivitySenderProfiles()
        } catch (_: Throwable) {
            currentSenderProfiles(serverId)
        }

    private fun currentSenderProfiles(serverId: ServerId): Map<String, SharedActivitySenderProfile> =
        serverMembersStore
            ?.currentServerMemberProfilesById(serverId)
            ?.toActivitySenderProfiles()
            .orEmpty()

    private fun Map<String, MemberProfile>.toActivitySenderProfiles(): Map<String, SharedActivitySenderProfile> =
        mapValues { (_, profile) ->
            SharedActivitySenderProfile(
                displayName = profile.displayName.ifBlank { profile.handle.ifBlank { profile.id.value } }
            )
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

    private fun rememberDone(item: InboxItem) {
        doneOverrides =
            doneOverrides +
                (item.key to ActivityDoneOverride(item.activityUnreadAnchor()))
    }

    private fun forgetDone(key: String) {
        if (key in doneOverrides) {
            doneOverrides = doneOverrides - key
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

    private fun ActivityUnreadReconcileResult.reconcileLocallyDoneItems(): ActivityReconcileResult {
        if (doneOverrides.isEmpty()) {
            return ActivityReconcileResult(
                items = items,
                suppressedUnreadCount = suppressedUnreadCount,
                removedUnreadRows = removedUnreadRows
            )
        }
        val nextOverrides = doneOverrides.toMutableMap()
        val reconciled = mutableListOf<InboxItem>()
        var suppressedDoneUnreadCount = 0
        var removedDoneRows = 0
        for (item in items) {
            val override = nextOverrides[item.key]
            if (override == null) {
                reconciled += item
                continue
            }
            val incomingAnchor = item.activityUnreadAnchor()
            val staleDone =
                override.activityAnchor == null ||
                    incomingAnchor == override.activityAnchor
            if (staleDone) {
                suppressedDoneUnreadCount += item.unreadCount
                removedDoneRows += 1
            } else {
                nextOverrides.remove(item.key)
                reconciled += item
            }
        }
        doneOverrides = nextOverrides
        return ActivityReconcileResult(
            items = reconciled,
            suppressedUnreadCount = suppressedUnreadCount,
            removedUnreadRows = removedUnreadRows,
            suppressedDoneUnreadCount = suppressedDoneUnreadCount,
            removedDoneRows = removedDoneRows
        )
    }
}

private data class ActivityUnreadClearOverride(
    val unreadAnchor: MessageId?
)

private data class ActivityDoneOverride(
    val activityAnchor: MessageId?
)

private data class ActivityUnreadReconcileResult(
    val items: List<InboxItem>,
    val suppressedUnreadCount: Int = 0,
    val removedUnreadRows: Int = 0
)

private data class ActivityReconcileResult(
    val items: List<InboxItem>,
    val suppressedUnreadCount: Int = 0,
    val removedUnreadRows: Int = 0,
    val suppressedDoneUnreadCount: Int = 0,
    val removedDoneRows: Int = 0
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
    data class ApplyRealtimeSummaryEvent(
        val event: RealtimeEvent,
        val activeConversationId: String?
    ) : SharedActivityIntent
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
        is RealtimeRefreshRequired ->
            scope == RealtimeRefreshScope.Home ||
                scope == RealtimeRefreshScope.ChannelMembers ||
                scope == RealtimeRefreshScope.ServerMembers ||
                scope == RealtimeRefreshScope.Agents ||
                scope == RealtimeRefreshScope.Machines ||
                scope == RealtimeRefreshScope.Settings
        else -> false
    }

private fun List<InboxItem>.withActivityThreadTaskProjection(
    serverId: String,
    tasksDataStore: SharedTasksDataStore?
): List<InboxItem> {
    tasksDataStore ?: return this
    val projection = tasksDataStore.projection(serverId, SharedTasksScope.Server)
    if (projection.status != SharedTasksDataStoreStatus.Loaded || projection.tasks.isEmpty()) return this
    val tasksByMessageId =
        projection.tasks
            .flatMap { task ->
                listOfNotNull(task.messageId, task.parentMessageId)
                    .mapNotNull { messageId -> messageId.value.trim().takeIf { it.isNotBlank() } }
                    .distinct()
                    .map { messageId -> messageId to task }
            }
            .toMap()
    if (tasksByMessageId.isEmpty()) return this
    return map { item -> item.withActivityThreadTaskProjection(tasksByMessageId) }
}

private fun InboxItem.withActivityThreadTaskProjection(tasksByMessageId: Map<String, TaskSummary>): InboxItem {
    if (kind != InboxItemKind.Thread) return this
    if (taskNumber != null && taskStatus != null) return this
    val parentMessageKey = parentMessageId?.value?.trim()?.takeIf { it.isNotBlank() } ?: return this
    val task = tasksByMessageId[parentMessageKey] ?: return this
    return copy(
        taskNumber = taskNumber ?: task.taskNumber,
        taskStatus = taskStatus ?: task.status,
        taskClaimedByName = taskClaimedByName ?: task.assignee?.displayName?.takeIf { it.isNotBlank() }
    )
}

private fun RealtimeEvent.activitySummaryUnreadIncrement(
    snapshot: InboxSnapshot,
    activeConversationId: String?,
    currentUserId: String
): Int =
    when (this) {
        is MessageNew -> snapshot.unreadIncrementForMessage(message, activeConversationId, currentUserId)
        is SyncResumeResponse ->
            messages
                .distinctBy { it.id }
                .sumOf { message -> snapshot.unreadIncrementForMessage(message, activeConversationId, currentUserId) }
        is ThreadUpdated ->
            unreadCount
                ?.takeIf { it > 0 && !isActiveActivityConversation(threadChannelId.value, activeConversationId, snapshot) }
                ?: 0
        else -> 0
    }

private fun InboxSnapshot.unreadIncrementForMessage(
    message: Message,
    activeConversationId: String?,
    currentUserId: String
): Int {
    val channelId = message.channelId ?: message.threadChannelId ?: return 0
    if (message.author.id.value == currentUserId) return 0
    if (isActiveActivityConversation(channelId.value, activeConversationId, this)) return 0
    if (items.any { item -> item.latestActivityMessageId == message.id || item.lastMessageId == message.id }) return 0
    return 1
}

private fun isActiveActivityConversation(
    streamChannelId: String,
    activeConversationId: String?,
    snapshot: InboxSnapshot
): Boolean {
    if (activeConversationId.isNullOrBlank()) return false
    if (streamChannelId == activeConversationId) return true
    return snapshot.items.any { item ->
        item.channelId.value == streamChannelId &&
            (
                item.key == activeConversationId ||
                    item.channelId.value == activeConversationId ||
                    item.threadChannelId?.value == activeConversationId ||
                    item.parentMessageId?.value == activeConversationId
                )
    }
}

private fun DataResult<InboxSnapshot>.activityDiagnosticsResult(): String =
    when (this) {
        is DataResult.Content -> "content items=${data.items.size} unread=${data.totalUnreadCount}"
        is DataResult.Empty -> "empty code=${reason.name}"
        is DataResult.Error -> "error code=${error.code}"
        is DataResult.NoPermission -> "no_permission code=${blocker.code}"
    }

data class SharedActivitySenderProfile(
    val displayName: String
)

private fun InboxItem.activityActionChannelId(): ChannelId? =
    if (kind == InboxItemKind.Thread) threadChannelId else channelId

private fun InboxItem.activityUnreadAnchor(): MessageId? =
    firstUnreadMessageId ?: latestActivityMessageId ?: lastMessageId ?: parentMessageId
