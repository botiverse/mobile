package build.raft.app

import build.raft.app.data.ConversationRepository
import build.raft.app.data.AuthenticatedRouteAuthorityGateway
import build.raft.app.data.AuthenticatedRouteOperationRunner
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.DataResult
import build.raft.app.data.EmptyReason
import build.raft.app.data.ScreenError
import build.raft.app.data.ThreadSnapshot
import build.raft.app.data.RealConversationRepository
import build.raft.app.data.SlockAuthSessionStore
import build.raft.app.data.candidateCacheScopeId
import build.raft.app.model.ChannelId
import build.raft.app.model.ForwardMessageBundle
import build.raft.app.model.ForwardMessageItem
import build.raft.app.model.Message
import build.raft.app.model.MessageId
import build.raft.app.model.ServerId
import build.raft.app.compat.json.JSONObject
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal const val ThreadForwardBundlePreviewLineLimit = 8
internal const val ThreadForwardBundlePreviewCharLimit = 420

internal data class ThreadForwardBundleRoute(
    val serverId: ServerId,
    val channelId: ChannelId,
    val messageId: MessageId
)

internal fun JSONObject.toThreadForwardBundleRouteOrNull(): ThreadForwardBundleRoute? {
    val serverId = optString("serverId").trim().takeIf { it.isNotBlank() }?.let(::ServerId) ?: return null
    val channelId = optString("channelId").trim().takeIf { it.isNotBlank() }?.let(::ChannelId) ?: return null
    val messageId = optString("messageId").trim().takeIf { it.isNotBlank() }?.let(::MessageId) ?: return null
    return ThreadForwardBundleRoute(serverId = serverId, channelId = channelId, messageId = messageId)
}

internal fun ForwardMessageBundle.inlinePreviewMessages(): List<ForwardMessageItemPreview> =
    messages.take(ThreadForwardBundleInlinePreviewLimit).map { item ->
        val plan =
            threadLongMessageRenderPlan(
                body = item.body,
                lineLimit = ThreadForwardBundlePreviewLineLimit,
                charLimit = ThreadForwardBundlePreviewCharLimit
            )
        ForwardMessageItemPreview(
            item = item,
            body = if (plan.collapsible) plan.collapsedDisplayText else item.body,
            truncated = plan.collapsible
        )
    }

internal data class ForwardMessageItemPreview(
    val item: ForwardMessageItem,
    val body: String,
    val truncated: Boolean
)

internal enum class ThreadForwardBundleDetailStatus {
    Loading,
    Content,
    Empty,
    Error
}

internal data class ThreadForwardBundleDetailState(
    val status: ThreadForwardBundleDetailStatus = ThreadForwardBundleDetailStatus.Loading,
    val bundle: ForwardMessageBundle? = null,
    val errorCode: String? = null
)

internal sealed interface ThreadForwardBundleDetailIntent {
    data object Load : ThreadForwardBundleDetailIntent
    data object Retry : ThreadForwardBundleDetailIntent
}

internal interface ThreadForwardBundleDetailRepository {
    suspend fun load(route: ThreadForwardBundleRoute): DataResult<ForwardMessageBundle>
}

internal class RealThreadForwardBundleDetailRepository(
    private val messageStore: SharedThreadMessageStore,
    private val conversationRepository: ConversationRepository
) : ThreadForwardBundleDetailRepository {
    override suspend fun load(route: ThreadForwardBundleRoute): DataResult<ForwardMessageBundle> {
        val resolution =
            messageStore.resolve(route.serverId, route.channelId, route.messageId) {
                when (
                    val result =
                        conversationRepository.getMessageContext(
                            messageId = route.messageId,
                            channelId = route.channelId,
                            priority = DataRequestPriority.Foreground
                        )
                ) {
                    is DataResult.Content -> result.data.toForwardBundleMessageResolution(route)
                    is DataResult.Empty -> SharedThreadMessageResolution.Missing
                    is DataResult.Error -> SharedThreadMessageResolution.Error(result.error.code)
                    is DataResult.NoPermission -> SharedThreadMessageResolution.Error(result.blocker.code)
                }
            }
        return when (resolution) {
            is SharedThreadMessageResolution.Loaded ->
                resolution.message.forwardBundle
                    ?.let { bundle -> DataResult.Content(bundle) }
                    ?: DataResult.Empty(EmptyReason.NoMessages)
            SharedThreadMessageResolution.Missing -> DataResult.Empty(EmptyReason.NoMessages)
            is SharedThreadMessageResolution.Error ->
                DataResult.Error(ScreenError(code = resolution.code, retryable = true))
        }
    }
}

/** Production repository: authority is admitted before even the message cache key is read. */
internal class AuthenticatedThreadForwardBundleDetailRepository(
    private val messageStore: SharedThreadMessageStore,
    private val authStore: SlockAuthSessionStore,
    owner: build.raft.app.data.AuthenticatedRouteOwner,
    gateway: AuthenticatedRouteAuthorityGateway,
    private val logger: (String) -> Unit = {},
) : ThreadForwardBundleDetailRepository {
    private val runner = AuthenticatedRouteOperationRunner(owner, gateway)

    override suspend fun load(route: ThreadForwardBundleRoute): DataResult<ForwardMessageBundle> =
        runner.run { receipt, networkClient ->
            val conversationRepository =
                RealConversationRepository(
                    sessionStore = authStore,
                    networkClient = networkClient,
                    routeServerIdProvider = { route.serverId.value },
                    warningLogger = logger,
                )
            val resolution =
                messageStore.resolveScoped(
                    scopeId = receipt.candidateCacheScopeId(),
                    channelId = route.channelId,
                    messageId = route.messageId,
                ) {
                    when (
                        val result =
                            conversationRepository.getMessageContext(
                                messageId = route.messageId,
                                channelId = route.channelId,
                                priority = DataRequestPriority.Foreground,
                            )
                    ) {
                        is DataResult.Content -> result.data.toForwardBundleMessageResolution(route)
                        is DataResult.Empty -> SharedThreadMessageResolution.Missing
                        is DataResult.Error -> SharedThreadMessageResolution.Error(result.error.code)
                        is DataResult.NoPermission -> SharedThreadMessageResolution.Error(result.blocker.code)
                    }
                }
            when (resolution) {
                is SharedThreadMessageResolution.Loaded ->
                    resolution.message.forwardBundle
                        ?.let { bundle -> DataResult.Content(bundle) }
                        ?: DataResult.Empty(EmptyReason.NoMessages)
                SharedThreadMessageResolution.Missing -> DataResult.Empty(EmptyReason.NoMessages)
                is SharedThreadMessageResolution.Error ->
                    DataResult.Error(ScreenError(code = resolution.code, retryable = true))
            }
        }
}

private fun ThreadSnapshot.toForwardBundleMessageResolution(
    route: ThreadForwardBundleRoute
): SharedThreadMessageResolution {
    val message =
        sequenceOf(parentMessage)
            .plus(messages.asSequence())
            .filterNotNull()
            .firstOrNull { candidate -> candidate.matchesForwardBundleRoute(route) }
            ?: return SharedThreadMessageResolution.Missing
    return SharedThreadMessageResolution.Loaded(message)
}

private fun Message.matchesForwardBundleRoute(route: ThreadForwardBundleRoute): Boolean =
    id == route.messageId &&
        (channelId == route.channelId || threadId.value == route.channelId.value)

internal class ThreadForwardBundleDetailStore(
    private val route: ThreadForwardBundleRoute,
    private val repository: ThreadForwardBundleDetailRepository,
    private val scope: CoroutineScope
) {
    private val mutableState = MutableStateFlow(ThreadForwardBundleDetailState())
    val state: StateFlow<ThreadForwardBundleDetailState> = mutableState.asStateFlow()

    private var loadJob: Job? = null
    private var generation = 0L

    fun dispatch(intent: ThreadForwardBundleDetailIntent) {
        when (intent) {
            ThreadForwardBundleDetailIntent.Load -> load(force = false)
            ThreadForwardBundleDetailIntent.Retry -> load(force = true)
        }
    }

    private fun load(force: Boolean) {
        if (!force && (loadJob?.isActive == true || mutableState.value.status == ThreadForwardBundleDetailStatus.Content)) {
            return
        }
        loadJob?.cancel()
        generation += 1
        val requestGeneration = generation
        mutableState.value = ThreadForwardBundleDetailState(status = ThreadForwardBundleDetailStatus.Loading)
        loadJob =
            scope.launch {
                val result =
                    try {
                        repository.load(route)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        DataResult.Error(ScreenError(code = "thread_forward_bundle_network", retryable = true))
                    }
                if (requestGeneration != generation) return@launch
                mutableState.value =
                    when (result) {
                        is DataResult.Content ->
                            ThreadForwardBundleDetailState(
                                status = ThreadForwardBundleDetailStatus.Content,
                                bundle = result.data
                            )
                        is DataResult.Empty ->
                            ThreadForwardBundleDetailState(status = ThreadForwardBundleDetailStatus.Empty)
                        is DataResult.Error ->
                            ThreadForwardBundleDetailState(
                                status = ThreadForwardBundleDetailStatus.Error,
                                errorCode = result.error.code
                            )
                        is DataResult.NoPermission ->
                            ThreadForwardBundleDetailState(
                                status = ThreadForwardBundleDetailStatus.Error,
                                errorCode = result.blocker.code
                            )
                    }
            }
    }

    fun clear() {
        loadJob?.cancel()
        loadJob = null
    }
}

internal class ThreadForwardBundleDetailViewModel(
    route: ThreadForwardBundleRoute,
    repository: ThreadForwardBundleDetailRepository
) : ViewModel() {
    private val storeDelegate = lazy {
        ThreadForwardBundleDetailStore(route = route, repository = repository, scope = viewModelScope)
    }
    private val store: ThreadForwardBundleDetailStore by storeDelegate

    val state: StateFlow<ThreadForwardBundleDetailState>
        get() = store.state

    fun dispatch(intent: ThreadForwardBundleDetailIntent) {
        store.dispatch(intent)
    }

    override fun onCleared() {
        if (storeDelegate.isInitialized()) store.clear()
        super.onCleared()
    }
}
