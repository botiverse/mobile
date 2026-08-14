package build.raft.app

import build.raft.app.data.AUTHENTICATED_ROUTE_AUTHORITY_CHANGED
import build.raft.app.data.AddChannelMemberRequest
import build.raft.app.data.AuthenticatedRouteAuthorityGateway
import build.raft.app.data.AuthenticatedRouteOperationRunner
import build.raft.app.data.ChannelMemberActionResult
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.DataResult
import build.raft.app.data.RealMemberRepository
import build.raft.app.data.RealConversationRepository
import build.raft.app.data.RemoveChannelMemberRequest
import build.raft.app.data.ScreenError
import build.raft.app.data.SlockAuthSessionStore
import build.raft.app.model.MemberProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal enum class ThreadMembersRouteStatus {
    Loading,
    Content,
    Error,
    AuthorityRejected,
}

internal enum class ThreadMembersRouteAction {
    Add,
    Remove,
}

internal sealed interface ThreadMembersRouteEffect {
    data class Added(val member: MemberProfile) : ThreadMembersRouteEffect
    data class Removed(val memberId: String) : ThreadMembersRouteEffect
    data class Failure(
        val action: ThreadMembersRouteAction,
        val code: String,
        val noPermission: Boolean,
    ) : ThreadMembersRouteEffect
}

internal data class ThreadMembersRouteSnapshot(
    val channelName: String,
    val channelMembers: List<MemberProfile>,
    val serverMembers: List<MemberProfile>,
    val canAddMembers: Boolean,
    val serverMembersErrorCode: String? = null,
)

internal data class ThreadMembersRouteState(
    val status: ThreadMembersRouteStatus = ThreadMembersRouteStatus.Loading,
    val channelName: String = "",
    val channelMembers: List<MemberProfile> = emptyList(),
    val serverMembers: List<MemberProfile> = emptyList(),
    val canAddMembers: Boolean = false,
    val refreshing: Boolean = false,
    val errorCode: String? = null,
    val serverMembersErrorCode: String? = null,
    val actionInFlight: ThreadMembersRouteAction? = null,
    val effect: ThreadMembersRouteEffect? = null,
)

internal sealed interface ThreadMembersRouteIntent {
    data object Load : ThreadMembersRouteIntent
    data object Refresh : ThreadMembersRouteIntent
    data class Add(val member: MemberProfile) : ThreadMembersRouteIntent
    data class Remove(val request: RemoveChannelMemberRequest) : ThreadMembersRouteIntent
    data object ConsumeEffect : ThreadMembersRouteIntent
}

internal interface ThreadMembersRouteDataSource {
    suspend fun load(): DataResult<ThreadMembersRouteSnapshot>
    suspend fun add(request: AddChannelMemberRequest): DataResult<ChannelMemberActionResult>
    suspend fun remove(request: RemoveChannelMemberRequest): DataResult<ChannelMemberActionResult>
}

internal class RealThreadMembersRouteDataSource(
    private val route: ThreadMembersPageRoute,
    private val authStore: SlockAuthSessionStore,
    owner: build.raft.app.data.AuthenticatedRouteOwner,
    gateway: AuthenticatedRouteAuthorityGateway,
    private val logger: (String) -> Unit = {},
) : ThreadMembersRouteDataSource {
    private val runner = AuthenticatedRouteOperationRunner(owner, gateway)

    override suspend fun load(): DataResult<ThreadMembersRouteSnapshot> =
        runner.run { _, networkClient ->
            val detailResult =
                conversationRepository(networkClient).getConversationDetail(
                    channelId = route.channelId,
                    priority = DataRequestPriority.Visible,
                )
            val channelName =
                when (detailResult) {
                    is DataResult.Content -> {
                        if (detailResult.data.channelId != route.channelId) {
                            return@run DataResult.Error(
                                ScreenError(code = "thread_members_channel_identity_mismatch", retryable = false)
                            )
                        }
                        detailResult.data.name
                            .trim()
                            .removePrefix("#")
                            .ifBlank { route.channelId.value }
                    }
                    is DataResult.Empty,
                    is DataResult.Error,
                    is DataResult.NoPermission -> route.channelId.value
                }
            val repository = repository(networkClient)
            val channelResult =
                repository.getChannelMembers(
                    priority = DataRequestPriority.Visible,
                    channelId = route.channelId,
                )
            val channelMembers: List<MemberProfile>
            val canAddMembers: Boolean
            when (channelResult) {
                is DataResult.Content -> {
                    channelMembers = channelResult.data.members
                    canAddMembers = channelResult.data.canAddMembers
                }
                is DataResult.Empty -> {
                    // RealMemberRepository deliberately represents an empty
                    // channel roster as Empty(NoMembers). It is still a valid
                    // destination state and must not suppress server candidates.
                    channelMembers = emptyList()
                    canAddMembers = true
                }
                is DataResult.Error -> return@run channelResult
                is DataResult.NoPermission -> return@run channelResult
            }
            val serverResult =
                repository.getMembers(
                    priority = DataRequestPriority.Background,
                    serverId = route.serverId,
                )
            val serverMembers = (serverResult as? DataResult.Content)?.data?.members.orEmpty()
            val serverError =
                when (serverResult) {
                    is DataResult.Error -> serverResult.error.code
                    is DataResult.NoPermission -> serverResult.blocker.code
                    is DataResult.Empty -> serverResult.reason.name
                    is DataResult.Content -> null
                }
            DataResult.Content(
                ThreadMembersRouteSnapshot(
                    channelName = channelName,
                    channelMembers = channelMembers,
                    serverMembers = serverMembers,
                    canAddMembers = canAddMembers,
                    serverMembersErrorCode = serverError,
                )
            )
        }

    private fun conversationRepository(
        networkClient: build.raft.app.data.SlockNetworkClient,
    ): RealConversationRepository =
        RealConversationRepository(
            sessionStore = authStore,
            networkClient = networkClient,
            routeServerIdProvider = { route.serverId.value },
            warningLogger = { logger("conversation $it") },
        )

    override suspend fun add(
        request: AddChannelMemberRequest,
    ): DataResult<ChannelMemberActionResult> =
        runner.run { _, networkClient ->
            repository(networkClient).addChannelMember(DataRequestPriority.Foreground, request)
        }

    override suspend fun remove(
        request: RemoveChannelMemberRequest,
    ): DataResult<ChannelMemberActionResult> =
        runner.run { _, networkClient ->
            repository(networkClient).removeChannelMember(DataRequestPriority.Foreground, request)
        }

    private fun repository(networkClient: build.raft.app.data.SlockNetworkClient): RealMemberRepository =
        RealMemberRepository(
            sessionStore = authStore,
            networkClient = networkClient,
            warningLogger = { logger("warning $it") },
            logger = logger,
        )
}

internal class ThreadMembersRouteStore(
    private val route: ThreadMembersPageRoute,
    private val dataSource: ThreadMembersRouteDataSource,
    private val scope: CoroutineScope,
) {
    private val mutableState =
        MutableStateFlow(
            ThreadMembersRouteState(
                channelName = route.channelId.value.trim().removePrefix("#"),
            )
        )
    val state: StateFlow<ThreadMembersRouteState> = mutableState.asStateFlow()

    private var loadGeneration = 0L
    private var actionGeneration = 0L
    private var loadJob: Job? = null
    private var actionJob: Job? = null

    fun dispatch(intent: ThreadMembersRouteIntent) {
        when (intent) {
            ThreadMembersRouteIntent.Load -> load(force = false)
            ThreadMembersRouteIntent.Refresh -> load(force = true)
            is ThreadMembersRouteIntent.Add -> add(intent.member)
            is ThreadMembersRouteIntent.Remove -> remove(intent.request)
            ThreadMembersRouteIntent.ConsumeEffect ->
                mutableState.value = mutableState.value.copy(effect = null)
        }
    }

    fun invalidateAuthority() {
        loadGeneration += 1L
        actionGeneration += 1L
        loadJob?.cancel()
        actionJob?.cancel()
        loadJob = null
        actionJob = null
        mutableState.value = authorityRejectedState()
    }

    private fun load(force: Boolean) {
        if (mutableState.value.actionInFlight != null) return
        if (!force && (loadJob?.isActive == true || mutableState.value.status == ThreadMembersRouteStatus.Content)) {
            return
        }
        loadJob?.cancel()
        loadGeneration += 1L
        val requestGeneration = loadGeneration
        val previous = mutableState.value
        mutableState.value =
            if (previous.status == ThreadMembersRouteStatus.Content) {
                previous.copy(refreshing = true, errorCode = null)
            } else {
                previous.copy(status = ThreadMembersRouteStatus.Loading, refreshing = false, errorCode = null)
            }
        loadJob =
            scope.launch {
                val result = runThreadMembersCall(dataSource::load)
                if (requestGeneration != loadGeneration) return@launch
                mutableState.value =
                    when (result) {
                        is DataResult.Content ->
                            ThreadMembersRouteState(
                                status = ThreadMembersRouteStatus.Content,
                                channelName = result.data.channelName,
                                channelMembers = result.data.channelMembers,
                                serverMembers = result.data.serverMembers,
                                canAddMembers = result.data.canAddMembers,
                                serverMembersErrorCode = result.data.serverMembersErrorCode,
                            )
                        is DataResult.Error ->
                            if (result.error.code == AUTHENTICATED_ROUTE_AUTHORITY_CHANGED) {
                                authorityRejectedState()
                            } else if (previous.status == ThreadMembersRouteStatus.Content) {
                                previous.copy(refreshing = false, errorCode = result.error.code)
                            } else {
                                previous.copy(
                                    status = ThreadMembersRouteStatus.Error,
                                    refreshing = false,
                                    errorCode = result.error.code,
                                )
                            }
                        is DataResult.NoPermission ->
                            if (previous.status == ThreadMembersRouteStatus.Content) {
                                previous.copy(refreshing = false, errorCode = result.blocker.code)
                            } else {
                                previous.copy(
                                    status = ThreadMembersRouteStatus.Error,
                                    refreshing = false,
                                    errorCode = result.blocker.code,
                                )
                            }
                        is DataResult.Empty ->
                            ThreadMembersRouteState(
                                status = ThreadMembersRouteStatus.Content,
                                channelName = route.channelId.value.trim().removePrefix("#"),
                            )
                    }
            }
    }

    private fun add(member: MemberProfile) {
        if (
            mutableState.value.status != ThreadMembersRouteStatus.Content ||
            mutableState.value.actionInFlight != null ||
            !mutableState.value.canAddMembers
        ) {
            return
        }
        val request = AddChannelMemberRequest(route.channelId, member.id, member.kind)
        launchAction(ThreadMembersRouteAction.Add, member = member) { dataSource.add(request) }
    }

    private fun remove(request: RemoveChannelMemberRequest) {
        if (
            mutableState.value.status != ThreadMembersRouteStatus.Content ||
            mutableState.value.actionInFlight != null ||
            request.channelId != route.channelId
        ) {
            return
        }
        launchAction(ThreadMembersRouteAction.Remove, removedMemberId = request.memberId.value) {
            dataSource.remove(request)
        }
    }

    private fun launchAction(
        action: ThreadMembersRouteAction,
        member: MemberProfile? = null,
        removedMemberId: String? = null,
        call: suspend () -> DataResult<ChannelMemberActionResult>,
    ) {
        mutableState.value = mutableState.value.copy(actionInFlight = action, effect = null)
        loadGeneration += 1L
        loadJob?.cancel()
        loadJob = null
        actionJob?.cancel()
        actionGeneration += 1L
        val requestGeneration = actionGeneration
        actionJob =
            scope.launch {
                val result = runThreadMembersCall(call)
                if (requestGeneration != actionGeneration) return@launch
                mutableState.value =
                    when (result) {
                        is DataResult.Content -> {
                            val current = mutableState.value
                            when (action) {
                                ThreadMembersRouteAction.Add -> {
                                    val added = member ?: return@launch
                                    current.copy(
                                        channelMembers =
                                            (current.channelMembers + added)
                                                .distinctBy { "${it.kind.name}:${it.id.value}" },
                                        actionInFlight = null,
                                        effect = ThreadMembersRouteEffect.Added(added),
                                    )
                                }
                                ThreadMembersRouteAction.Remove -> {
                                    val removed = removedMemberId.orEmpty()
                                    current.copy(
                                        channelMembers = current.channelMembers.filterNot { it.id.value == removed },
                                        actionInFlight = null,
                                        effect = ThreadMembersRouteEffect.Removed(removed),
                                    )
                                }
                            }
                        }
                        is DataResult.Error ->
                            if (result.error.code == AUTHENTICATED_ROUTE_AUTHORITY_CHANGED) {
                                authorityRejectedState()
                            } else {
                                actionFailure(action, result.error.code, noPermission = false)
                            }
                        is DataResult.NoPermission ->
                            actionFailure(action, result.blocker.code, noPermission = true)
                        is DataResult.Empty ->
                            actionFailure(action, result.reason.name, noPermission = false)
                    }
            }
    }

    private fun actionFailure(
        action: ThreadMembersRouteAction,
        code: String,
        noPermission: Boolean,
    ): ThreadMembersRouteState =
        mutableState.value.copy(
            actionInFlight = null,
            effect = ThreadMembersRouteEffect.Failure(action, code, noPermission),
        )

    private fun authorityRejectedState(): ThreadMembersRouteState =
        ThreadMembersRouteState(
            status = ThreadMembersRouteStatus.AuthorityRejected,
            channelName = route.channelId.value.trim().removePrefix("#"),
            errorCode = AUTHENTICATED_ROUTE_AUTHORITY_CHANGED,
        )

    fun clear() {
        loadGeneration += 1L
        actionGeneration += 1L
        loadJob?.cancel()
        actionJob?.cancel()
        loadJob = null
        actionJob = null
    }
}

private suspend fun <T> runThreadMembersCall(call: suspend () -> DataResult<T>): DataResult<T> =
    try {
        call()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        DataResult.Error(ScreenError(code = "thread_members_network", retryable = true))
    }
