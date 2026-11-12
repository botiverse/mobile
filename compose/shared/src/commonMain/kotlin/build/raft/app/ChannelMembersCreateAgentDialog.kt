package build.raft.app

import build.raft.app.resources.*
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftButtonSize
import androidx.compose.runtime.*
import build.raft.app.data.*
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.launch

/** Reuses the canonical create form and retains a created agent when joining fails. */
@Composable
internal fun ChannelMembersCreateAgentDialog(
    owner: AuthenticatedRouteOwner,
    channelId: ChannelId,
    initialName: String,
    mode: MembersCreateAgentMode = MembersCreateAgentMode.Managed,
    /** The channel the new agent joins, said in the dialog (web `autoJoinChannelName`). */
    channelName: String? = null,
    authority: AuthenticatedRouteAuthorityGateway,
    computersStore: SharedComputersStore,
    formStore: SharedCreateAgentFormStore,
    membersStore: SharedServerMembersStore,
    onDismiss: () -> Unit,
    onJoined: () -> Unit,
    /**
     * Reports the created agent so the caller can open its profile. External agents are
     * created to be configured straight away (task #4), so the caller needs the id; managed
     * agents keep the previous behaviour of just closing and refreshing.
     */
    onCreated: (MemberId) -> Unit = {},
    /** The no-computer dialog's "Add Computer"; null hides it. */
    onAddComputer: (() -> Unit)? = null,
    /** The server's plan from the session, for the agent limit when billing can't be read. */
    serverPlan: String? = null,
    /** "Upgrade for more" in the agent-limit banner; null leaves it as text. */
    onUpgrade: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val picker by remember(computersStore, owner) {
        computersStore.computerPickerSliceFlow(owner.serverId)
    }.collectAsState(SharedComputersPickerSlice())
    // Owner-scoped server directory, so a suggested name (blank query) avoids agent
    // @names already used on this server; the server's uniqueness check still decides.
    val serverMembers by remember(membersStore, owner) {
        membersStore.serverMembersEntryFlow(owner)
    }.collectAsState(remember(membersStore, owner) { membersStore.currentServerMembersEntry(owner) })
    // The plan's agent limit (web CreateAgentDialog `loadBilling`): at the limit the dialog says so
    // and Create stays disabled. Unread billing (a 403, offline) falls back to the plan's limits.
    var billing by remember(owner) { mutableStateOf<CreateAgentBilling?>(null) }
    LaunchedEffect(owner) {
        val receipt = authority.capture(owner) ?: return@LaunchedEffect
        val loaded = loadCreateAgentBilling(authority.networkClient(receipt))
        if (authority.isCurrent(receipt)) billing = loaded
    }
    val agentLimit =
        createAgentLimit(
            billing = billing,
            serverPlan = serverPlan,
            serverAgentCount = serverMembers.members.count { it.kind == build.raft.app.model.MemberKind.Agent },
        )
    var submitting by remember(owner) { mutableStateOf(false) }
    var error by remember(owner) { mutableStateOf<String?>(null) }
    var joinState by remember(owner) { mutableStateOf(CreatedAgentJoinState()) }
    val failedLabel = stringResource(Res.string.common_error)
    val joinFailedLabel = stringResource(Res.string.thread_members_created_join_failed)
    LaunchedEffect(owner, mode) {
        if (mode.requiresComputerSelection()) {
            computersStore.dispatch(SharedComputersIntent.EnsureComputerPicker(owner.serverId, DataRequestPriority.Visible))
        }
    }
    fun submit(request: SharedCreateAgentDialogSubmit?) {
        if (submitting) return
        submitting = true
        error = null
        scope.launch {
            try {
                val receipt = authority.capture(owner)
                if (receipt == null) { error = failedLabel; return@launch }
                suspend fun createFrom(submitted: SharedCreateAgentDialogSubmit): DataResult<String> {
                    val client = authority.networkClient(receipt)
                    val coordinator = SharedCreateAgentCoordinator(
                        RaftAgentsApiClient(RaftAgentsNetworkApi(client)),
                        RaftComputersApiClient(RaftComputersNetworkApi(client)),
                    )
                    return when (val result = coordinator.createAgent(submitted.toCreateAgentRequest())) {
                        is DataResult.Content -> {
                            formStore.rememberCreatedAgent(owner.serverId, submitted)
                            DataResult.Content(result.data.id)
                        }
                        is DataResult.Empty -> result
                        is DataResult.Error -> result
                        is DataResult.NoPermission -> result
                    }
                }
                val create: (suspend () -> DataResult<String>)? = request?.let { submitted ->
                    val block: suspend () -> DataResult<String> = { createFrom(submitted) }
                    block
                }
                val outcome = createThenJoinAgent(
                    state = joinState,
                    create = create,
                    join = { agentId -> membersStore.addChannelMembersBatch(owner, createdAgentJoinRequest(channelId, agentId)) },
                    isCurrent = { authority.isCurrent(receipt) },
                    publish = { joinState = it },
                )
                when (outcome) {
                    CreateThenJoinOutcome.NothingToCreate -> error = failedLabel
                    CreateThenJoinOutcome.Stale -> Unit
                    // The server's words when it gave a reason (a model no longer offered, one set by an admin).
                    is CreateThenJoinOutcome.CreateFailed ->
                        error = (outcome.result as? DataResult.Error)?.error?.let { createAgentFallbackErrorText(it, failedLabel) } ?: failedLabel
                    CreateThenJoinOutcome.JoinFailed -> error = joinFailedLabel
                    is CreateThenJoinOutcome.Joined ->
                        // task #5 (Hands 906abab9): ordering is the contract — see
                        // createdAgentAfterJoinSteps. Opening the profile after the dismissal never
                        // ran because the dismissal cancels this composable's scope.
                        createdAgentAfterJoinSteps(mode, isExternalAgent = true).forEach { step ->
                            when (step) {
                                CreatedAgentAfterJoinStep.OpenProfile -> onCreated(MemberId(outcome.agentId))
                                CreatedAgentAfterJoinStep.DismissDialog -> onJoined()
                            }
                        }
                }
            } finally {
                submitting = false
            }
        }
    }
    CreatedAgentJoinFaces(
        joinState = joinState,
        submitting = submitting,
        message = error ?: joinFailedLabel,
        onRetry = { submit(null) },
        onDismiss = onDismiss,
    ) {
        SharedCreateAgentDialog(
            external = mode == MembersCreateAgentMode.External,
            offersHosting = true,
            autoJoinChannelName = channelName,
            submitting = submitting, errorMessage = error, initialName = initialName,
            machines = if (mode.requiresComputerSelection()) picker.machines else emptyList(),
            machinesLoading = mode.requiresComputerSelection() && picker.initialLoadInFlight,
            machinesErrorMessage = if (mode.requiresComputerSelection() && picker.initialLoadError != null) failedLabel else null,
            onMachinesRetry = { computersStore.dispatch(SharedComputersIntent.RefreshComputerPicker(owner.serverId, DataRequestPriority.Visible)) },
            createAgentFormStore = formStore, createAgentServerId = owner.serverId,
            takenAgentNames = serverMembers.members.takenAgentNames(),
            currentAgentCount = agentLimit.usage,
            maxAgents = agentLimit.limit,
            limitPlanLabel = agentLimit.planLabel,
            onUpgrade = onUpgrade,
            onAddComputer = onAddComputer,
            onDismiss = { if (!submitting) onDismiss() }, onSubmit = { submit(it) },
        )
    }
}

internal const val CreatedAgentJoinRetryMessageTestTag = "created-agent-join-retry-message"

/**
 * The dialog's two faces: the create form, or — only once a join has really failed — the
 * "created, but joining failed" message with its trace id and Retry (#proj-mobile:#130).
 */
@Composable
internal fun CreatedAgentJoinFaces(
    joinState: CreatedAgentJoinState,
    submitting: Boolean,
    message: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    createForm: @Composable () -> Unit,
) {
    if (joinState.showsJoinRetry) {
        RaftUnifiedDialog(title = stringResource(Res.string.thread_action_add_member),
            onDismiss = onDismiss, dismissEnabled = !submitting) {
            Text(message, style = RaftType.Body, modifier = Modifier.testTag(CreatedAgentJoinRetryMessageTestTag))
            RaftTraceIdNote(joinState.joinFailureTraceId)
            RaftDialogFooter {
                RaftButton(
                    text = stringResource(Res.string.common_retry),
                    enabled = !submitting,
                    onClick = onRetry,
                    variant = RaftButtonVariant.Ghost,
                    size = RaftButtonSize.Lg
                )
            }
        }
    } else {
        createForm()
    }
}

/** How [createThenJoinAgent] ended. */
internal sealed interface CreateThenJoinOutcome {
    /** A retry with no created agent and no form to create one from. */
    data object NothingToCreate : CreateThenJoinOutcome
    /** The route's authority changed before any agent existed: nothing to keep. */
    data object Stale : CreateThenJoinOutcome
    data class CreateFailed(val result: DataResult<*>) : CreateThenJoinOutcome
    /** The agent exists but is not known to be in the channel: the retry face shows. */
    data object JoinFailed : CreateThenJoinOutcome
    data class Joined(val agentId: String) : CreateThenJoinOutcome
}

/**
 * The create → join sequence of [ChannelMembersCreateAgentDialog], without Compose so the dialog
 * and its UI test run the same steps. [create] is null on a retry (the agent already exists in
 * [state]). Every state change goes through [publish].
 *
 * Once an agent exists it is never forgotten: if the route's authority changed while creating or
 * joining ([isCurrent] false), the join counts as failed, so the retry face shows and Retry joins
 * that agent instead of the form creating a second one.
 */
internal suspend fun createThenJoinAgent(
    state: CreatedAgentJoinState,
    create: (suspend () -> DataResult<String>)?,
    join: suspend (agentId: String) -> DataResult<*>,
    isCurrent: suspend () -> Boolean,
    publish: (CreatedAgentJoinState) -> Unit,
): CreateThenJoinOutcome {
    var current = state
    val agentId = current.createdAgentId ?: run {
        val createAgent = create ?: return CreateThenJoinOutcome.NothingToCreate
        val created = createAgent()
        if (created is DataResult.Content) {
            current = current.created(created.data)
            publish(current)
        }
        if (!isCurrent()) {
            if (current.createdAgentId == null) return CreateThenJoinOutcome.Stale
            current = current.joinFinished(authenticatedRouteAuthorityChanged<Unit>())
            publish(current)
            return CreateThenJoinOutcome.JoinFailed
        }
        if (created !is DataResult.Content) return CreateThenJoinOutcome.CreateFailed(created)
        created.data
    }
    val joined = join(agentId)
    current = current.joinFinished(if (isCurrent()) joined else authenticatedRouteAuthorityChanged<Unit>())
    publish(current)
    return if (current.joinFailed) CreateThenJoinOutcome.JoinFailed else CreateThenJoinOutcome.Joined(agentId)
}

/**
 * Where the create → join sequence of [ChannelMembersCreateAgentDialog] is.
 *
 * #proj-mobile:#130: the dialog used to switch to the "created, but couldn't join" face as soon as
 * the agent existed, i.e. while the join request was still in flight, so every create from a
 * channel flashed the failure before the join succeeded and the dialog closed. The failure face
 * now shows only after a join request has really failed ([joinFailed]); a retry keeps it until
 * that retry succeeds. [createdAgentId] still keeps a retry from creating a second agent.
 */
internal data class CreatedAgentJoinState(
    val createdAgentId: String? = null,
    val joinFailed: Boolean = false,
    /** The failed join's `X-Trace-Id` (AGENTS.md "Errors carry the trace ID"). */
    val joinFailureTraceId: String? = null,
) {
    val showsJoinRetry: Boolean get() = createdAgentId != null && joinFailed

    fun created(agentId: String): CreatedAgentJoinState =
        CreatedAgentJoinState(createdAgentId = agentId)

    fun joinFinished(result: DataResult<*>): CreatedAgentJoinState =
        when (result) {
            is DataResult.Content -> copy(joinFailed = false, joinFailureTraceId = null)
            // A route-authority change is local (no request to trace), so it shows no trace id.
            is DataResult.Error -> copy(
                joinFailed = true,
                joinFailureTraceId = result.error.traceId.takeUnless { result.error.code == AUTHENTICATED_ROUTE_AUTHORITY_CHANGED },
            )
            is DataResult.NoPermission -> copy(joinFailed = true, joinFailureTraceId = result.blocker.traceId)
            is DataResult.Empty -> copy(joinFailed = true, joinFailureTraceId = null)
        }
}

/**
 * The created agent's join (web `joinCreatedAgent`): the same one-call batch as "Add Selected",
 * `{ userIds: [], agentIds: [id] }`, so a failed join is retried the same way.
 */
internal fun createdAgentJoinRequest(channelId: ChannelId, agentId: String): AddChannelMembersBatchRequest =
    AddChannelMembersBatchRequest(channelId, emptyList(), listOf(MemberId(agentId)))

/**
 * Ordered steps to perform once the created agent has been joined into the channel.
 *
 * task #5 (Hands 906abab9): #3132 called `onJoined` before `onCreated`. `onJoined` clears
 * `createAgentName`, which removes the dialog from composition and cancels the dialog's own
 * coroutine scope, so the follow-on navigation never ran. On device change #3132 looked like a
 * no-op while its unit tests stayed green, because nothing pinned this ordering.
 *
 * The sequence is a contract, so it is modelled here where a test can assert it rather than
 * being implied by statement order inside a composable body.
 */
internal enum class CreatedAgentAfterJoinStep { OpenProfile, DismissDialog }

internal fun createdAgentAfterJoinSteps(
    mode: MembersCreateAgentMode,
    isExternalAgent: Boolean
): List<CreatedAgentAfterJoinStep> {
    val openProfile =
        mode == MembersCreateAgentMode.External && isExternalAgent
    // Navigation must happen while the dialog's coroutine scope is still alive, i.e. before the
    // dismissal that tears the dialog down.
    return if (openProfile) {
        listOf(CreatedAgentAfterJoinStep.OpenProfile, CreatedAgentAfterJoinStep.DismissDialog)
    } else {
        listOf(CreatedAgentAfterJoinStep.DismissDialog)
    }
}
