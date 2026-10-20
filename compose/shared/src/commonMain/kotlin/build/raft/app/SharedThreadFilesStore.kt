package build.raft.app

import build.raft.app.data.DataResult
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
    val requestInFlight: Boolean = false
)

internal sealed interface SharedThreadFilesIntent {
    data class Start(val basePayload: RaftThreadScreenPayload) : SharedThreadFilesIntent

    data class Refresh(val reason: String = "refresh") : SharedThreadFilesIntent
    data class LoadMore(val reason: String = "load_more") : SharedThreadFilesIntent
}

internal class SharedThreadFilesStore(
    private val dataSource: SharedThreadDataSource,
    private val scope: CoroutineScope,
    private val diagnosticsLogger: ((String) -> Unit)? = null
) {
    private val _state = MutableStateFlow(SharedThreadFilesState())
    val state: StateFlow<SharedThreadFilesState> = _state.asStateFlow()
    private var requestJob: Job? = null

    fun dispatch(intent: SharedThreadFilesIntent) {
        when (intent) {
            is SharedThreadFilesIntent.Start -> start(intent.basePayload)
            is SharedThreadFilesIntent.Refresh -> {
                requestJob?.cancel()
                requestJob = null
                resetInFlightFlag()
                requestJob = scope.launch { fetch(reason = intent.reason, cursor = null) }
            }
            is SharedThreadFilesIntent.LoadMore -> {
                requestJob = scope.launch {
                    val cursor = _state.value.payload.nextCursor?.takeIf { it.isNotBlank() } ?: return@launch
                    if (_state.value.payload.hasMore) {
                        fetch(reason = intent.reason, cursor = cursor)
                    }
                }
            }
        }
    }

    fun clear() {
        requestJob?.cancel()
        requestJob = null
        resetInFlightFlag()
    }

    private fun start(basePayload: RaftThreadScreenPayload) {
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
        reduce(
            SharedThreadFilesState(
                basePayload = basePayload,
                payload = nextPayload,
                requestInFlight = false
            )
        )
        diagnosticsLogger?.invoke(
            "thread_files_start threadId=${basePayload.sourceThreadId.orEmpty()} " +
                "routeChanged=$routeChanged status=${nextPayload.status} items=${nextPayload.items.size}"
        )
    }

    private suspend fun fetch(reason: String, cursor: String?) {
        val current = _state.value
        if (current.requestInFlight || (cursor != null && current.payload.loadingMore)) return
        val basePayload = current.basePayload
        val routeKey = basePayload.threadFilesRouteKey()
        val loadingPayload =
            if (cursor != null) {
                current.payload.copy(loadingMore = true, errorCode = null)
            } else {
                current.payload.copy(status = "loading", loadingMore = false, errorCode = null)
            }
        reduce(current.copy(payload = loadingPayload, requestInFlight = true))
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
        reduce(latest.copy(payload = nextPayload, requestInFlight = false))
        diagnosticsLogger?.invoke(
            "thread_files_result reason=$reason threadId=${basePayload.sourceThreadId.orEmpty()} " +
                "cursor=${cursor.orEmpty()} status=${nextPayload.status} items=${nextPayload.items.size} " +
                "hasMore=${nextPayload.hasMore} error=${nextPayload.errorCode.orEmpty()}"
        )
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
                requestInFlight = false
            )
        )
    }
}

internal class SharedThreadFilesViewModel(
    dataSource: SharedThreadDataSource,
    diagnosticsLogger: ((String) -> Unit)? = null
) : ViewModel() {
    private val storeDelegate =
        lazy {
            SharedThreadFilesStore(
                dataSource = dataSource,
                scope = viewModelScope,
                diagnosticsLogger = diagnosticsLogger
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

internal fun RaftThreadScreenPayload.initialThreadFilesRoutePayload(): RaftThreadFilesPayload =
    RaftThreadFilesPayload(instanceId = instanceId)
