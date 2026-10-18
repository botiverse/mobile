package build.raft.app

import androidx.compose.runtime.*
import build.raft.app.data.*
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.resources.MR
import com.tencent.tmm.kmmresource.compose.stringResource
import build.raft.app.components.SlockBrutalActionButton
import com.tencent.kuikly.compose.material3.Text
import kotlinx.coroutines.launch

/** Reuses the canonical create form and retains a created agent when joining fails. */
@Composable
internal fun ChannelMembersCreateAgentDialog(
    owner: AuthenticatedRouteOwner,
    channelId: ChannelId,
    initialName: String,
    mode: MembersCreateAgentMode = MembersCreateAgentMode.Managed,
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
) {
    val scope = rememberCoroutineScope()
    val picker by remember(computersStore, owner) {
        computersStore.computerPickerSliceFlow(owner.serverId)
    }.collectAsState(SharedComputersPickerSlice())
    // Owner-scoped server directory, so a suggested name (blank query) avoids agent
    // @names already used on this server; the server's uniqueness check still decides.
    val serverMembers by remember(membersStore, owner) {
        membersStore.serverMembersEntryFlow(owner)
    }.collectAsState(SharedServerMembersEntry(serverId = owner.serverId))
    var submitting by remember(owner) { mutableStateOf(false) }
    var error by remember(owner) { mutableStateOf<String?>(null) }
    var createdId by remember(owner) { mutableStateOf<String?>(null) }
    val failedLabel = stringResource(MR.strings.common_error)
    val joinFailedLabel = stringResource(MR.strings.thread_members_created_join_failed)
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
                var agentId = createdId
                if (agentId == null) {
                    if (request == null) { error = failedLabel; return@launch }
                    val client = authority.networkClient(receipt)
                    val coordinator = SharedCreateAgentCoordinator(
                        SlockAgentsApiClient(SlockAgentsNetworkApi(client)),
                        SlockComputersApiClient(SlockComputersNetworkApi(client)),
                    )
                    val result = coordinator.createAgent(request.toCreateAgentRequest())
                        .recoverRejectedCreateAgentFormOrRetry(formStore, owner.serverId, request) { retry ->
                            coordinator.createAgent(retry.toCreateAgentRequest())
                        }
                    if (!authority.isCurrent(receipt)) return@launch
                    if (result !is DataResult.Content) { error = failedLabel; return@launch }
                    agentId = result.data.id
                    createdId = agentId
                }
                val joined = membersStore.addChannelMembersBatch(owner,
                    AddChannelMembersBatchRequest(channelId, emptyList(), listOf(MemberId(agentId))))
                if (!authority.isCurrent(receipt)) return@launch
                if (joined is DataResult.Content) {
                    onJoined()
                    onCreated(MemberId(agentId))
                } else {
                    error = joinFailedLabel
                }
            } finally {
                submitting = false
            }
        }
    }
    if (createdId != null) {
        SlockUnifiedDialog(title = stringResource(MR.strings.thread_action_add_member),
            onDismiss = onDismiss, dismissEnabled = !submitting) {
            Text(error ?: joinFailedLabel, style = SlockType.Body)
            SlockDialogFooter {
                SlockBrutalActionButton(label = stringResource(MR.strings.common_retry),
                    enabled = !submitting, onClick = { submit(null) })
            }
        }
    } else {
        SharedCreateAgentDialog(
            external = mode == MembersCreateAgentMode.External,
            submitting = submitting, errorMessage = error, initialName = initialName,
            machines = if (mode.requiresComputerSelection()) picker.machines else emptyList(),
            machinesLoading = mode.requiresComputerSelection() && picker.initialLoadInFlight,
            machinesErrorMessage = if (mode.requiresComputerSelection() && picker.initialLoadError != null) failedLabel else null,
            onMachinesRetry = { computersStore.dispatch(SharedComputersIntent.RefreshComputerPicker(owner.serverId, DataRequestPriority.Visible)) },
            createAgentFormStore = formStore, createAgentServerId = owner.serverId,
            takenAgentNames = serverMembers.members.takenAgentNames(),
            onDismiss = { if (!submitting) onDismiss() }, onSubmit = { submit(it) },
        )
    }
}
