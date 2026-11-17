package build.raft.app

import build.raft.app.data.ConversationArtifact
import build.raft.app.data.DataResult
import build.raft.app.data.RaftConversationArtifactsApi
import build.raft.app.data.loadConversationArtifacts
import build.raft.app.data.ScreenError
import build.raft.app.data.runCatchingPreservingCancellation
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class SharedThreadFilesState(
    val basePayload: RaftThreadScreenPayload = RaftThreadScreenPayload(),
    val payload: RaftThreadFilesPayload = RaftThreadFilesPayload(),
    val requestInFlight: Boolean = false,
    /** A pull-to-refresh is reloading the first page while the loaded list stays on screen. */
    val refreshing: Boolean = false,
    /** The conversation's Artifacts, at the head of the list (workspace_artifacts_v0; web #11580). */
    val artifacts: ThreadFilesArtifactsState = ThreadFilesArtifactsState()
)

/**
 * The Artifacts whose home is this conversation (`GET /api/artifacts/conversations/:id`): the first
 * page, then one more a tap on Show More ([nextCursor] non-null while there are more, as the web).
 * Not read (flag off, no server): none. A failed first page shows none and the files as ever; a
 * failed reload keeps the rows shown; a failed next page keeps its cursor to try again.
 */
internal data class ThreadFilesArtifactsState(
    val items: List<ConversationArtifact> = emptyList(),
    val nextCursor: String? = null,
    /** The first page is on its way and nothing is shown yet. */
    val loading: Boolean = false,
    val loadingMore: Boolean = false
)

/** Where the Files tab reads Artifacts: the reader's server, with the flag on. */
internal data class ThreadFilesArtifactsScope(val serverId: String)

internal sealed interface SharedThreadFilesIntent {
    /** [artifacts]: read the conversation's Artifacts there; null (the flag off) shows none. */
    data class Start(
        val basePayload: RaftThreadScreenPayload,
        val artifacts: ThreadFilesArtifactsScope? = null
    ) : SharedThreadFilesIntent

    data class Refresh(val reason: String = "refresh") : SharedThreadFilesIntent

    /**
     * Pull-to-refresh: reload the first page and keep the loaded list on screen (a failure keeps
     * it too, with the error on the load-more row). Single-flight: ignored while a request runs.
     */
    data object PullRefresh : SharedThreadFilesIntent
    data class LoadMore(val reason: String = "load_more") : SharedThreadFilesIntent

    /** Show More under the Artifacts: their next page. */
    data object LoadMoreArtifacts : SharedThreadFilesIntent
}

internal class SharedThreadFilesStore(
    private val dataSource: SharedThreadDataSource,
    private val scope: CoroutineScope,
    private val diagnosticsLogger: ((String) -> Unit)? = null,
    /** Null: this tab never lists Artifacts. */
    private val artifactsApi: RaftConversationArtifactsApi? = null
) {
    private val _state = MutableStateFlow(SharedThreadFilesState())
    val state: StateFlow<SharedThreadFilesState> = _state.asStateFlow()
    private var requestJob: Job? = null
    private var artifactsJob: Job? = null
    private var artifactsScope: ThreadFilesArtifactsScope? = null

    /** Bumped whenever the Artifacts shown are replaced or dropped: an older answer is ignored. */
    private var artifactsGeneration = 0

    fun dispatch(intent: SharedThreadFilesIntent) {
        when (intent) {
            is SharedThreadFilesIntent.Start -> start(intent.basePayload, intent.artifacts)
            is SharedThreadFilesIntent.Refresh -> {
                requestJob?.cancel()
                requestJob = null
                resetInFlightFlag()
                requestJob = scope.launch { fetch(reason = intent.reason, cursor = null) }
                loadArtifactsFirstPage()
            }
            SharedThreadFilesIntent.PullRefresh -> {
                val current = _state.value
                if (current.requestInFlight || current.payload.loadingMore) {
                    diagnosticsLogger?.invoke("thread_files_pull_refresh_skip reason=in_flight")
                    return
                }
                requestJob = scope.launch { fetch(reason = "pull_refresh", cursor = null, keepContent = true) }
                loadArtifactsFirstPage()
            }
            is SharedThreadFilesIntent.LoadMore -> {
                requestJob = scope.launch {
                    val cursor = _state.value.payload.nextCursor?.takeIf { it.isNotBlank() } ?: return@launch
                    if (_state.value.payload.hasMore) {
                        fetch(reason = intent.reason, cursor = cursor)
                    }
                }
            }
            SharedThreadFilesIntent.LoadMoreArtifacts -> loadMoreArtifacts()
        }
    }

    fun clear() {
        requestJob?.cancel()
        requestJob = null
        resetInFlightFlag()
        dropArtifactsRequest()
        if (_state.value.artifacts.loading || _state.value.artifacts.loadingMore) {
            reduce(_state.value.copy(artifacts = _state.value.artifacts.copy(loading = false, loadingMore = false)))
        }
    }

    private fun start(basePayload: RaftThreadScreenPayload, artifacts: ThreadFilesArtifactsScope?) {
        val current = _state.value
        val nextRouteKey = basePayload.threadFilesRouteKey()
        val currentRouteKey = current.basePayload.threadFilesRouteKey()
        val routeChanged = nextRouteKey != currentRouteKey
        if (routeChanged) {
            requestJob?.cancel()
            requestJob = null
        }
        val nextPayload =
            when {
                routeChanged -> basePayload.initialThreadFilesRoutePayload()
                else -> current.payload.copy(instanceId = current.payload.instanceId.ifBlank { basePayload.instanceId })
            }
        val artifactsChanged = routeChanged || artifacts != artifactsScope
        artifactsScope = artifacts.takeIf { artifactsApi != null }
        if (artifactsChanged) dropArtifactsRequest()
        reduce(
            SharedThreadFilesState(
                basePayload = basePayload,
                payload = nextPayload,
                requestInFlight = false,
                artifacts = if (artifactsChanged) ThreadFilesArtifactsState() else current.artifacts
            )
        )
        // The flag turned on (or another server) for a list already read: its Artifacts now.
        if (artifactsChanged && !routeChanged && nextPayload.status != "loading") loadArtifactsFirstPage()
        diagnosticsLogger?.invoke(
            "thread_files_start threadId=${basePayload.sourceThreadId.orEmpty()} " +
                "routeChanged=$routeChanged status=${nextPayload.status} items=${nextPayload.items.size}"
        )
    }

    private suspend fun fetch(reason: String, cursor: String?, keepContent: Boolean = false) {
        val current = _state.value
        if (current.requestInFlight || (cursor != null && current.payload.loadingMore)) return
        val basePayload = current.basePayload
        val routeKey = basePayload.threadFilesRouteKey()
        val refreshInPlace = keepContent && cursor == null && current.payload.status == "content" && current.payload.items.isNotEmpty()
        val loadingPayload =
            when {
                cursor != null -> current.payload.copy(loadingMore = true, errorCode = null)
                refreshInPlace -> current.payload.copy(errorCode = null)
                else -> current.payload.copy(status = "loading", loadingMore = false, errorCode = null)
            }
        reduce(current.copy(payload = loadingPayload, requestInFlight = true, refreshing = refreshInPlace))
        val result =
            runCatchingPreservingCancellation {
                dataSource.fetchFilesPayload(
                    basePayload = basePayload,
                    cursor = cursor,
                    currentPayload = loadingPayload
                )
            }.getOrElse {
                DataResult.Error(ScreenError(code = "thread_files_network", retryable = true))
            }
        val latest = _state.value
        if (latest.basePayload.threadFilesRouteKey() != routeKey) {
            diagnosticsLogger?.invoke("thread_files_result reason=$reason ignored=true routeChanged=true")
            return
        }
        val nextPayload =
            when (result) {
                is DataResult.Content -> result.data
                is DataResult.Empty ->
                    latest.payload.copy(
                        status = if (latest.payload.items.isEmpty()) "empty" else "content",
                        hasMore = false,
                        loadingMore = false,
                        nextCursor = null,
                        errorCode = null,
                        errorServerMessage = null
                    )
                is DataResult.Error ->
                    latest.payload.copy(
                        status = if (latest.payload.items.isEmpty()) "error" else "content",
                        loadingMore = false,
                        errorCode = result.error.code,
                        errorServerMessage = null
                    )
                is DataResult.NoPermission ->
                    latest.payload.copy(
                        status = "no_permission",
                        loadingMore = false,
                        errorCode = result.blocker.requiredCapability ?: result.blocker.code,
                        errorServerMessage = result.blocker.serverMessage
                    )
            }
        reduce(latest.copy(payload = nextPayload, requestInFlight = false, refreshing = false))
        diagnosticsLogger?.invoke(
            "thread_files_result reason=$reason threadId=${basePayload.sourceThreadId.orEmpty()} " +
                "cursor=${cursor.orEmpty()} status=${nextPayload.status} items=${nextPayload.items.size} " +
                "hasMore=${nextPayload.hasMore} error=${nextPayload.errorCode.orEmpty()}"
        )
    }

    private fun dropArtifactsRequest() {
        artifactsGeneration += 1
        artifactsJob?.cancel()
        artifactsJob = null
    }

    /** The first page again (the tab opened, Retry, a pull): replaces the rows when it lands. */
    private fun loadArtifactsFirstPage() {
        val api = artifactsApi ?: return
        val artifactsScope = artifactsScope ?: return
        val conversationId = _state.value.basePayload.threadFilesConversationId() ?: return
        dropArtifactsRequest()
        val generation = artifactsGeneration
        val shown = _state.value.artifacts
        reduce(_state.value.copy(artifacts = shown.copy(loading = shown.items.isEmpty(), loadingMore = false)))
        artifactsJob =
            scope.launch {
                val page = api.loadConversationArtifacts(artifactsScope.serverId, conversationId, cursor = null)
                if (generation != artifactsGeneration) return@launch
                val latest = _state.value.artifacts
                val next =
                    when {
                        page != null -> ThreadFilesArtifactsState(items = page.artifacts, nextCursor = page.nextCursor)
                        // A failed reload keeps what is shown; a failed first read shows none.
                        else -> latest.copy(loading = false, loadingMore = false)
                    }
                reduce(_state.value.copy(artifacts = next))
                diagnosticsLogger?.invoke(
                    "thread_files_artifacts_result ok=${page != null} items=${next.items.size} more=${next.nextCursor != null}"
                )
            }
    }

    private fun loadMoreArtifacts() {
        val api = artifactsApi ?: return
        val artifactsScope = artifactsScope ?: return
        val current = _state.value.artifacts
        val cursor = current.nextCursor ?: return
        if (current.loading || current.loadingMore) return
        val conversationId = _state.value.basePayload.threadFilesConversationId() ?: return
        val generation = artifactsGeneration
        reduce(_state.value.copy(artifacts = current.copy(loadingMore = true)))
        artifactsJob =
            scope.launch {
                val page = api.loadConversationArtifacts(artifactsScope.serverId, conversationId, cursor = cursor)
                if (generation != artifactsGeneration) return@launch
                val latest = _state.value.artifacts
                val next =
                    if (page == null) {
                        latest.copy(loadingMore = false)
                    } else {
                        // An Artifact updated while paging moves to the top: never listed twice.
                        val seen = latest.items.mapTo(HashSet()) { it.summary.id }
                        latest.copy(
                            items = latest.items + page.artifacts.filter { it.summary.id !in seen },
                            nextCursor = page.nextCursor,
                            loadingMore = false
                        )
                    }
                reduce(_state.value.copy(artifacts = next))
            }
    }

    private fun reduce(nextState: SharedThreadFilesState) {
        _state.value = nextState
    }

    private fun resetInFlightFlag() {
        val current = _state.value
        if (!current.requestInFlight && !current.payload.loadingMore) return
        reduce(
            current.copy(
                payload = current.payload.copy(loadingMore = false),
                requestInFlight = false,
                refreshing = false
            )
        )
    }
}

internal class SharedThreadFilesViewModel(
    dataSource: SharedThreadDataSource,
    diagnosticsLogger: ((String) -> Unit)? = null,
    artifactsApi: RaftConversationArtifactsApi? = null
) : ViewModel() {
    private val storeDelegate =
        lazy {
            SharedThreadFilesStore(
                dataSource = dataSource,
                scope = viewModelScope,
                diagnosticsLogger = diagnosticsLogger,
                artifactsApi = artifactsApi
            )
        }
    private val store: SharedThreadFilesStore by storeDelegate
    val state: StateFlow<SharedThreadFilesState>
        get() = store.state

    fun dispatch(intent: SharedThreadFilesIntent) {
        store.dispatch(intent)
    }

    override fun onCleared() {
        if (storeDelegate.isInitialized()) {
            store.clear()
        }
        super.onCleared()
    }
}

private fun RaftThreadScreenPayload.threadFilesRouteKey(): String =
    sourceThreadId?.takeIf { it.isNotBlank() }
        ?: streamChannelId?.takeIf { it.isNotBlank() }
        ?: instanceId

/** The conversation whose files the tab lists (`GET /api/channels/:id/files`): its Artifacts too. */
private fun RaftThreadScreenPayload.threadFilesConversationId(): String? = sourceThreadId?.takeIf { it.isNotBlank() }

internal fun RaftThreadScreenPayload.initialThreadFilesRoutePayload(): RaftThreadFilesPayload =
    RaftThreadFilesPayload(instanceId = instanceId)
