package ai.slock.compose

import ai.slock.compose.data.DataResult
import ai.slock.compose.data.HomeNetworkResponse
import ai.slock.compose.data.ScreenError
import ai.slock.compose.data.SharedThreadNetworkApi
import ai.slock.compose.data.SlockAuthSessionStore
import ai.slock.compose.data.StoredAuthSession
import com.tencent.kuikly.lifecycle.ViewModel
import com.tencent.kuikly.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal class SharedThreadDataSource(
    private val api: SharedThreadNetworkApi,
    private val sessionStore: SlockAuthSessionStore
) {
    private val payloadCache = mutableMapOf<String, KuiklyThreadScreenPayload>()

    suspend fun readSession(): StoredAuthSession? = sessionStore.readSession()

    fun cachePayload(payload: KuiklyThreadScreenPayload) {
        val threadId = payload.sourceThreadId?.takeIf { it.isNotBlank() } ?: return
        if (payload.hasRenderableThreadContent()) {
            payloadCache[threadId] = payload.copy(status = "content", errorCode = null)
        }
    }

    fun cachedPayload(threadId: String): KuiklyThreadScreenPayload? = payloadCache[threadId]

    suspend fun fetchThreadPayload(basePayload: KuiklyThreadScreenPayload): DataResult<KuiklyThreadScreenPayload> {
        val threadId =
            basePayload.sourceThreadId?.takeIf { it.isNotBlank() }
                ?: return DataResult.Error(ScreenError(code = "thread_id_missing", retryable = false))
        val session =
            readSession()
                ?: return DataResult.Error(ScreenError(code = "unauthenticated", retryable = false))
        val response =
            try {
                api.channelMessages(threadId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                return DataResult.Error(ScreenError(code = "thread_network", retryable = true))
            }
        if (!response.isSuccessful || response.bodyText.isNullOrBlank()) {
            return DataResult.Error(
                ScreenError(
                    code = response.errorBodyText?.takeIf { it.isNotBlank() } ?: "thread_network",
                    retryable = true
                )
            )
        }
        val payload =
            buildSharedThreadPayloadFromMessagesResponse(
                basePayload = basePayload,
                bodyText = response.bodyText,
                session = session
            )
        cachePayload(payload)
        return DataResult.Content(payload)
    }

    suspend fun sendMessage(channelId: String, body: String, asTask: Boolean): HomeNetworkResponse =
        api.sendMessage(channelId = channelId, body = body, asTask = asTask)

    suspend fun attachmentUrl(attachmentId: String): HomeNetworkResponse =
        api.attachmentUrl(attachmentId)
}

internal data class SharedThreadState(
    val payload: KuiklyThreadScreenPayload = KuiklyThreadScreenPayload(),
    val highlightedMentions: Set<String> = emptySet()
)

internal sealed interface SharedThreadIntent {
    data class Start(val payload: KuiklyThreadScreenPayload) : SharedThreadIntent
    data object Refresh : SharedThreadIntent
    data class SetPayload(val payload: KuiklyThreadScreenPayload) : SharedThreadIntent
}

internal class SharedThreadStore(
    private val dataSource: SharedThreadDataSource,
    private val scope: CoroutineScope
) {
    private val _state = MutableStateFlow(SharedThreadState())
    val state: StateFlow<SharedThreadState> = _state.asStateFlow()
    private var loadJob: Job? = null

    fun dispatch(intent: SharedThreadIntent) {
        when (intent) {
            is SharedThreadIntent.Start,
            SharedThreadIntent.Refresh -> {
                loadJob?.cancel()
                loadJob =
                    scope.launch {
                        accept(intent)
                    }
            }
            is SharedThreadIntent.SetPayload -> {
                _state.value = _state.value.copy(payload = intent.payload)
                dataSource.cachePayload(intent.payload)
            }
        }
    }

    suspend fun accept(intent: SharedThreadIntent) {
        when (intent) {
            is SharedThreadIntent.Start -> start(intent.payload)
            SharedThreadIntent.Refresh -> refresh(_state.value.payload)
            is SharedThreadIntent.SetPayload -> {
                _state.value = _state.value.copy(payload = intent.payload)
                dataSource.cachePayload(intent.payload)
            }
        }
    }

    fun clear() {
        loadJob?.cancel()
        loadJob = null
    }

    private suspend fun start(initialPayload: KuiklyThreadScreenPayload) {
        dataSource.cachePayload(initialPayload)
        val session = dataSource.readSession()
        val threadId = initialPayload.sourceThreadId?.takeIf { it.isNotBlank() }
        val cached = threadId?.let(dataSource::cachedPayload)?.withRouteContext(initialPayload)
        val current = _state.value.payload.takeIf { it.sourceThreadId == threadId && it.hasRenderableThreadContent() }
        val visiblePayload =
            cached
                ?: current
                ?: if (threadId == null) {
                    initialPayload
                } else {
                    initialPayload.copy(status = "loading", errorCode = null)
                }
        _state.value =
            SharedThreadState(
                payload = visiblePayload,
                highlightedMentions = session.threadSelfMentionNames()
            )
        if (threadId != null) {
            refresh(initialPayload)
        }
    }

    private suspend fun refresh(basePayload: KuiklyThreadScreenPayload) {
        val existing = _state.value.payload
        val loadingBase =
            if (existing.hasRenderableThreadContent()) {
                existing.withRouteContext(basePayload)
            } else {
                basePayload.copy(status = "loading", errorCode = null)
            }
        _state.value = _state.value.copy(payload = loadingBase)
        val result = dataSource.fetchThreadPayload(basePayload)
        _state.value =
            when (result) {
                is DataResult.Content ->
                    _state.value.copy(payload = result.data.withRouteContext(basePayload))
                is DataResult.Empty ->
                    _state.value.copy(payload = _state.value.payload.asRefreshFailed("empty_thread"))
                is DataResult.Error ->
                    _state.value.copy(payload = _state.value.payload.asRefreshFailed(result.error.code))
                is DataResult.NoPermission ->
                    _state.value.copy(payload = _state.value.payload.asRefreshFailed(result.blocker.code))
            }
    }
}

internal class SharedThreadViewModel(
    private val dataSource: SharedThreadDataSource
) : ViewModel() {
    private val storeDelegate =
        lazy {
            SharedThreadStore(dataSource = dataSource, scope = viewModelScope)
        }
    private val store: SharedThreadStore by storeDelegate
    val state: StateFlow<SharedThreadState>
        get() = store.state

    fun dispatch(intent: SharedThreadIntent) {
        store.dispatch(intent)
    }

    override fun onCleared() {
        if (storeDelegate.isInitialized()) {
            store.clear()
        }
        super.onCleared()
    }
}

private fun KuiklyThreadScreenPayload.asRefreshFailed(code: String): KuiklyThreadScreenPayload =
    if (hasRenderableThreadContent()) {
        copy(status = "content", errorCode = code)
    } else {
        copy(status = "error", errorCode = code)
    }

private fun KuiklyThreadScreenPayload.withRouteContext(base: KuiklyThreadScreenPayload): KuiklyThreadScreenPayload =
    copy(
        instanceId = base.instanceId.ifBlank { instanceId },
        title = base.title.ifBlank { title },
        subtitle = base.subtitle ?: subtitle,
        sourceThreadId = base.sourceThreadId ?: sourceThreadId,
        navigationLabel = base.navigationLabel,
        selectedTab = base.selectedTab,
        showModeTabs = base.showModeTabs,
        alignOwnMessagesRight = base.alignOwnMessagesRight,
        localOffsetMinutes = base.localOffsetMinutes,
        todayEpochDay = base.todayEpochDay ?: todayEpochDay,
        canSend = base.canSend,
        joined = base.joined,
        membersCount = if (base.membersCount > 0) base.membersCount else membersCount,
        composerPayload = base.composerPayload.ifBlank { composerPayload },
        emptyTitle = base.emptyTitle,
        emptyDetail = base.emptyDetail
    )

private fun KuiklyThreadScreenPayload.hasRenderableThreadContent(): Boolean =
    messages.isNotEmpty() || originMessage != null || tasks.isNotEmpty()
