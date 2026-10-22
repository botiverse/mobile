package build.raft.app.data

import build.raft.app.MembersAgentLifecycleAction
import build.raft.app.model.AgentStatus
import build.raft.app.model.MemberId
import build.raft.app.model.MemberProfile
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberPresenceUpdated
import build.raft.app.model.RealtimeAgentActivityEntry
import build.raft.app.model.RuntimeConfig
import build.raft.app.model.RuntimeModeConfig
import build.raft.app.model.RuntimeModelConfig
import build.raft.app.model.ServerId
import build.raft.app.model.preferredAvatarWireValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shared store for Members agent detail page transient state.
 *
 * Per task #56 (see #members专修:b1db664b), the detail page used to hold
 * 11+ local `var by remember` slots in `MembersDetailContent` (action in-flight,
 * dropdown menu, pending confirmation, selected tab, profile edit draft/saving/
 * error, etc.). Hoisting these to a shared store makes the detail page
 * single-source-of-truth and lets the dropdown row / confirm button read
 * "is this action in flight?" from one place instead of from
 * `MembersDetailContent` local state.
 *
 * Phase 1 (this commit) covers: agent detail snapshot + load status,
 * lifecycle action in-flight, diagnostic action in-flight, pending confirmation,
 * last action result, profile edit (displayName + role draft/saving/error).
 * UI-only state (selected tab, dropdown open/anchor, avatar picker) and
 * tab-specific state (workspace tree / file / diagnostics tabs / activity /
 * reminders / apps / permissions) deliberately stay local.
 *
 * The store is state-only (no API orchestration); the composable still
 * invokes the host callback (e.g. `onAgentLifecycleAction`) and dispatches
 * the result via `SharedAgentDetailIntent` so the store is the single
 * source of truth for "what's pending right now".
 */
internal data class SharedAgentDetailState(
    val agentId: MemberId? = null,
    val detailOwner: SharedAgentDetailOwner? = null,
    val detailProfileGeneration: Long = 0L,
    val detailServerId: ServerId? = null,
    val detail: AgentDetailSnapshot? = null,
    val runtimeConfigProjection: SharedAgentRuntimeConfigProjection? = null,
    val agentActivityLogs: Map<MemberId, List<AgentActivityEntry>> = emptyMap(),
    val agentRealtimeActivityLogs: Map<MemberId, List<AgentActivityEntry>> = emptyMap(),
    val agentActivityObservedAt: Map<MemberId, Long> = emptyMap(),
    val agentActivityVersions: Map<MemberId, Long> = emptyMap(),
    val agentActivitySeq: Map<MemberId, Long> = emptyMap(),
    /**
     * Bumped by every [SharedAgentDetailIntent.ResetAgentActivitySeq] (a realtime reconnect /
     * reconcile rebaseline). Lets an observer tell that a rebaseline happened even when
     * [agentActivitySeq] was already empty.
     */
    val agentActivitySeqEpoch: Long = 0L,
    val detailLoadStatus: SharedAgentDetailLoadStatus = SharedAgentDetailLoadStatus.Idle,
    /**
     * [detail] is the agent's last canonical response replayed from the process cache (data-layer
     * plan step 5) and the background refresh has not answered yet: paint it, but grant no profile
     * edit authority until a fresh response replaces it.
     */
    val detailFromCache: Boolean = false,
    val detailError: ScreenError? = null,
    val detailPermissionBlocker: PermissionBlocker? = null,
    val lifecycleActionInFlight: String? = null,
    val diagnosticActionInFlight: String? = null,
    val pendingConfirmation: MembersAgentLifecycleAction? = null,
    val lastActionResult: SharedAgentDetailActionResult? = null,
    val displayNameEditing: Boolean = false,
    val displayNameDraft: String? = null,
    val displayNameSaving: Boolean = false,
    val displayNameError: String? = null,
    val roleEditing: Boolean = false,
    val roleDraft: String? = null,
    val roleSaving: Boolean = false,
    val roleError: String? = null
)

internal data class SharedAgentDetailOwner(
    val environmentId: String,
    val accountId: String,
    val serverId: ServerId
)

internal fun sharedAgentDetailOwner(
    environmentId: String?,
    accountId: String?,
    serverId: ServerId?
): SharedAgentDetailOwner? {
    val normalizedEnvironment = environmentId?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: return null
    val normalizedAccount = accountId?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val normalizedServer = serverId?.value?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return SharedAgentDetailOwner(
        environmentId = normalizedEnvironment,
        accountId = normalizedAccount,
        serverId = ServerId(normalizedServer)
    )
}

internal data class SharedAgentRuntimeConfigProjection(
    val owner: SharedAgentDetailOwner,
    val agentId: MemberId,
    val config: RuntimeConfig,
    val profileGeneration: Long
)

internal enum class SharedAgentDetailLoadStatus {
    Idle,
    Loading,
    Content,
    NoPermission,
    Error,
    Empty
}

internal data class SharedAgentDetailActionResult(
    val actionKey: String,
    val successLabel: String? = null,
    val error: ScreenError? = null
)

internal data class SharedAgentIssueReportRequest(
    val description: String,
    val diagnostics: String,
    val includeRecentMessages: Boolean = true,
    val includeLiveActivity: Boolean = true,
    val includeActivityHistory: Boolean = true,
    val includeRuntimeTranscript: Boolean = false,
    val liveActivity: List<AgentActivityEntry> = emptyList()
)

internal interface SharedAgentDetailActions {
    /**
     * `workspaceChoice` only applies to `MembersAgentLifecycleAction.Delete` —
     * the archive workspace disposition (`keep`/`delete`). Callers omitting it
     * archive with the workspace kept, which is also the server default.
     */
    suspend fun runLifecycleAction(
        agentId: MemberId,
        action: MembersAgentLifecycleAction,
        workspaceChoice: AgentArchiveWorkspaceChoice = AgentArchiveWorkspaceChoice.Keep
    ): DataResult<AgentLifecycleActionResult>

    fun copyDiagnostics(diagnostics: String)

    suspend fun reportIssue(request: SharedAgentIssueReportRequest): DataResult<AgentIssueReportResult>

    suspend fun updateAgentProfile(request: AgentProfileUpdateRequest): DataResult<MemberProfile>
}

internal sealed interface SharedAgentDetailIntent {
    data class SelectMember(
        val memberId: MemberId,
        val owner: SharedAgentDetailOwner? = null,
        val profileGeneration: Long = 0L
    ) : SharedAgentDetailIntent
    data class SeedDetailFromMember(val member: MemberProfile) : SharedAgentDetailIntent
    data object DeselectMember : SharedAgentDetailIntent

    data class SetDetailLoading(val agentId: MemberId) : SharedAgentDetailIntent
    data class SetDetail(
        val agentId: MemberId,
        val detail: AgentDetailSnapshot,
        val serverId: ServerId? = null,
        val preserveSequencedActivity: Boolean = true,
        val owner: SharedAgentDetailOwner? = null,
        val profileGeneration: Long = 0L,
        /** Replayed from the process cache, not a fresh response (see [SharedAgentDetailState.detailFromCache]). */
        val fromCache: Boolean = false
    ) : SharedAgentDetailIntent
    data class ApplyRuntimeConfigProjection(
        val owner: SharedAgentDetailOwner,
        val agentId: MemberId,
        val config: RuntimeConfig,
        val profileGeneration: Long
    ) : SharedAgentDetailIntent
    data class SetAgentActivity(
        val agentId: MemberId,
        val activity: List<AgentActivityEntry>,
        val serverId: ServerId? = null
    ) : SharedAgentDetailIntent
    data class SetDetailNoPermission(
        val agentId: MemberId,
        val blocker: PermissionBlocker
    ) : SharedAgentDetailIntent {
        constructor(agentId: MemberId, code: String) : this(agentId, PermissionBlocker(code))
    }
    data class SetDetailError(
        val agentId: MemberId,
        val error: ScreenError
    ) : SharedAgentDetailIntent {
        constructor(agentId: MemberId, code: String) : this(agentId, ScreenError(code))
    }
    data class SetDetailEmpty(val agentId: MemberId) : SharedAgentDetailIntent
    data class ApplyAgentActivityUpdate(
        val event: MemberPresenceUpdated,
        val observedAtMillis: Long = defaultNowMillis()
    ) : SharedAgentDetailIntent
    data object ResetAgentActivitySeq : SharedAgentDetailIntent

    data class StartLifecycleAction(val actionKey: String) : SharedAgentDetailIntent
    data class FinishLifecycleAction(
        val actionKey: String,
        val successLabel: String? = null,
        val error: ScreenError? = null
    ) : SharedAgentDetailIntent
    data class StartDiagnosticAction(val actionKey: String) : SharedAgentDetailIntent
    data class FinishDiagnosticAction(
        val actionKey: String,
        val successLabel: String? = null,
        val error: ScreenError? = null
    ) : SharedAgentDetailIntent

    data class ShowConfirmation(val action: MembersAgentLifecycleAction) : SharedAgentDetailIntent
    data object DismissConfirmation : SharedAgentDetailIntent

    data class BeginEditDisplayName(val draft: String) : SharedAgentDetailIntent
    data class UpdateDisplayNameDraft(val draft: String) : SharedAgentDetailIntent
    data class StartSaveDisplayName(val draft: String) : SharedAgentDetailIntent
    data class SetDisplayNameError(val code: String) : SharedAgentDetailIntent
    data object FinishSaveDisplayName : SharedAgentDetailIntent
    data object CancelEditDisplayName : SharedAgentDetailIntent

    data class BeginEditRole(val draft: String) : SharedAgentDetailIntent
    data class UpdateRoleDraft(val draft: String) : SharedAgentDetailIntent
    data class StartSaveRole(val draft: String) : SharedAgentDetailIntent
    data class SetRoleError(val code: String) : SharedAgentDetailIntent
    data object FinishSaveRole : SharedAgentDetailIntent
    data object CancelEditRole : SharedAgentDetailIntent

    data class ReportActionResult(val result: SharedAgentDetailActionResult) : SharedAgentDetailIntent
    data object ClearActionResult : SharedAgentDetailIntent
}

internal class SharedAgentDetailStore(
    private val logger: (String) -> Unit = {}
) {
    private val _state = MutableStateFlow(SharedAgentDetailState())
    val state: StateFlow<SharedAgentDetailState> = _state.asStateFlow()

    fun dispatch(intent: SharedAgentDetailIntent) {
        val current = _state.value
        val next = when (intent) {
            is SharedAgentDetailIntent.SelectMember ->
                if (
                    current.agentId == intent.memberId &&
                    current.detailOwner == intent.owner &&
                    current.detailProfileGeneration == intent.profileGeneration
                ) {
                    current
                } else {
                    SharedAgentDetailState(
                        agentId = intent.memberId,
                        detailOwner = intent.owner,
                        detailProfileGeneration = intent.profileGeneration,
                        agentActivityLogs = current.agentActivityLogs,
                        agentRealtimeActivityLogs = current.agentRealtimeActivityLogs,
                        agentActivityObservedAt = current.agentActivityObservedAt,
                        agentActivityVersions = current.agentActivityVersions,
                        agentActivitySeq = current.agentActivitySeq
                    )
                }
            is SharedAgentDetailIntent.SeedDetailFromMember -> current.seedDetailFromMember(intent.member)
            SharedAgentDetailIntent.DeselectMember -> SharedAgentDetailState(
                agentActivityLogs = current.agentActivityLogs,
                agentRealtimeActivityLogs = current.agentRealtimeActivityLogs,
                agentActivityObservedAt = current.agentActivityObservedAt,
                agentActivityVersions = current.agentActivityVersions,
                agentActivitySeq = current.agentActivitySeq
            )

            is SharedAgentDetailIntent.SetDetailLoading ->
                if (current.agentId == intent.agentId) {
                    current.copy(
                        detailLoadStatus = SharedAgentDetailLoadStatus.Loading,
                        detailError = null,
                        detailPermissionBlocker = null
                    )
                } else {
                    current
                }
            is SharedAgentDetailIntent.SetDetail ->
                if (
                    current.agentId == intent.agentId &&
                    current.acceptsDetailResult(intent.owner, intent.profileGeneration)
                ) {
                    val base =
                        if (intent.serverId != null && current.detailServerId != intent.serverId) {
                            current.withoutCachedAgentActivity(intent.agentId)
                        } else {
                            current
                        }
                    val incoming =
                        base.detail
                            ?.takeIf {
                                it.agentId == intent.agentId &&
                                    (intent.serverId == null || base.detailServerId == intent.serverId)
                            }
                            ?.let { intent.detail.withExistingAgentDetailSections(it) }
                            ?: intent.detail
                    val mergedDetail = base.mergeSequencedActivityIntoDetail(
                        agentId = intent.agentId,
                        incoming = incoming,
                        preserveSequencedActivity = intent.preserveSequencedActivity
                    ).withCachedAgentActivity(base.agentActivityLogs[intent.agentId])
                    val projection =
                        base.runtimeConfigProjection?.takeIf {
                            it.owner == intent.owner &&
                                it.agentId == intent.agentId &&
                                it.profileGeneration >= intent.profileGeneration
                        }
                    val detail = projection?.let { mergedDetail.withRuntimeConfigProjection(it.config) } ?: mergedDetail
                    val nextActivityLogs = base.agentActivityLogs.withAgentActivityLog(intent.agentId, detail.activity)
                    base.copy(
                        detail = detail,
                        detailOwner = intent.owner,
                        detailProfileGeneration = intent.profileGeneration,
                        detailServerId = intent.serverId ?: base.detailServerId,
                        runtimeConfigProjection = projection,
                        agentActivityLogs = nextActivityLogs,
                        detailLoadStatus = SharedAgentDetailLoadStatus.Content,
                        detailFromCache = intent.fromCache,
                        detailError = null,
                        detailPermissionBlocker = null
                    )
                } else {
                    current
                }
            is SharedAgentDetailIntent.ApplyRuntimeConfigProjection ->
                if (
                    current.agentId == intent.agentId &&
                    current.detail?.agentId == intent.agentId &&
                    current.detailOwner == intent.owner &&
                    current.detailServerId == intent.owner.serverId &&
                    current.detailProfileGeneration == intent.profileGeneration
                ) {
                    val projection =
                        SharedAgentRuntimeConfigProjection(
                            owner = intent.owner,
                            agentId = intent.agentId,
                            config = intent.config,
                            profileGeneration = intent.profileGeneration
                        )
                    current.copy(
                        detail = current.detail.withRuntimeConfigProjection(intent.config),
                        runtimeConfigProjection = projection
                    )
                } else {
                    current
                }
            is SharedAgentDetailIntent.SetAgentActivity ->
                if (intent.serverId != null && current.detailServerId != intent.serverId) {
                    current
                } else {
                    current.applyAgentActivitySnapshot(intent.agentId, intent.activity)
                }
            is SharedAgentDetailIntent.SetDetailNoPermission ->
                if (current.agentId == intent.agentId) {
                    current.copy(
                        detailLoadStatus = SharedAgentDetailLoadStatus.NoPermission,
                        detailError = null,
                        detailPermissionBlocker = intent.blocker
                    )
                } else {
                    current
                }
            is SharedAgentDetailIntent.SetDetailError ->
                if (current.agentId == intent.agentId) {
                    current.copy(
                        detailLoadStatus = SharedAgentDetailLoadStatus.Error,
                        detailError = intent.error,
                        detailPermissionBlocker = null
                    )
                } else {
                    current
                }
            is SharedAgentDetailIntent.SetDetailEmpty ->
                if (current.agentId == intent.agentId) {
                    current.copy(
                        detailLoadStatus = SharedAgentDetailLoadStatus.Empty,
                        detailError = null,
                        detailPermissionBlocker = null
                    )
                } else {
                    current
                }
            is SharedAgentDetailIntent.ApplyAgentActivityUpdate ->
                current.applyAgentActivityUpdate(intent.event, intent.observedAtMillis)
            SharedAgentDetailIntent.ResetAgentActivitySeq -> current.copy(
                agentActivitySeq = emptyMap(),
                agentActivitySeqEpoch = current.agentActivitySeqEpoch + 1
            )

            is SharedAgentDetailIntent.StartLifecycleAction -> current.copy(
                lifecycleActionInFlight = intent.actionKey,
                pendingConfirmation = null,
                lastActionResult = null
            )
            is SharedAgentDetailIntent.FinishLifecycleAction -> current.copy(
                lifecycleActionInFlight = null,
                lastActionResult = SharedAgentDetailActionResult(
                    actionKey = intent.actionKey,
                    successLabel = intent.successLabel,
                    error = intent.error
                )
            )
            is SharedAgentDetailIntent.StartDiagnosticAction -> current.copy(
                diagnosticActionInFlight = intent.actionKey,
                lastActionResult = null
            )
            is SharedAgentDetailIntent.FinishDiagnosticAction -> current.copy(
                diagnosticActionInFlight = null,
                lastActionResult = SharedAgentDetailActionResult(
                    actionKey = intent.actionKey,
                    successLabel = intent.successLabel,
                    error = intent.error
                )
            )

            is SharedAgentDetailIntent.ShowConfirmation -> current.copy(
                pendingConfirmation = intent.action
            )
            SharedAgentDetailIntent.DismissConfirmation -> current.copy(
                pendingConfirmation = null
            )

            is SharedAgentDetailIntent.BeginEditDisplayName -> current.copy(
                displayNameEditing = true,
                displayNameDraft = intent.draft,
                displayNameError = null
            )
            is SharedAgentDetailIntent.UpdateDisplayNameDraft -> current.copy(
                displayNameDraft = intent.draft,
                displayNameError = null
            )
            is SharedAgentDetailIntent.StartSaveDisplayName -> current.copy(
                displayNameSaving = true,
                displayNameError = null
            )
            is SharedAgentDetailIntent.SetDisplayNameError -> current.copy(
                displayNameSaving = false,
                displayNameError = intent.code
            )
            SharedAgentDetailIntent.FinishSaveDisplayName -> current.copy(
                displayNameEditing = false,
                displayNameDraft = null,
                displayNameSaving = false,
                displayNameError = null
            )
            SharedAgentDetailIntent.CancelEditDisplayName -> current.copy(
                displayNameEditing = false,
                displayNameDraft = null,
                displayNameError = null
            )

            is SharedAgentDetailIntent.BeginEditRole -> current.copy(
                roleEditing = true,
                roleDraft = intent.draft,
                roleError = null
            )
            is SharedAgentDetailIntent.UpdateRoleDraft -> current.copy(
                roleDraft = intent.draft,
                roleError = null
            )
            is SharedAgentDetailIntent.StartSaveRole -> current.copy(
                roleSaving = true,
                roleError = null
            )
            is SharedAgentDetailIntent.SetRoleError -> current.copy(
                roleSaving = false,
                roleError = intent.code
            )
            SharedAgentDetailIntent.FinishSaveRole -> current.copy(
                roleEditing = false,
                roleDraft = null,
                roleSaving = false,
                roleError = null
            )
            SharedAgentDetailIntent.CancelEditRole -> current.copy(
                roleEditing = false,
                roleDraft = null,
                roleError = null
            )

            is SharedAgentDetailIntent.ReportActionResult -> current.copy(
                lastActionResult = intent.result
            )
            SharedAgentDetailIntent.ClearActionResult -> current.copy(
                lastActionResult = null
            )
        }
        if (next != current) {
            _state.value = next
            logger("shared_agent_detail_store ${intent::class.simpleName} -> state updated")
        }
    }

    suspend fun runLifecycleAction(
        agentId: MemberId,
        action: MembersAgentLifecycleAction,
        actions: SharedAgentDetailActions,
        workspaceChoice: AgentArchiveWorkspaceChoice = AgentArchiveWorkspaceChoice.Keep
    ): DataResult<AgentLifecycleActionResult> {
        dispatch(SharedAgentDetailIntent.StartLifecycleAction(action.actionKey))
        val result =
            try {
                actions.runLifecycleAction(agentId, action, workspaceChoice)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                DataResult.Error(ScreenError("agent_${action.actionKey}_failed"))
            }
        val error = result.lifecycleError(action)
        dispatch(
            SharedAgentDetailIntent.FinishLifecycleAction(
                actionKey = action.actionKey,
                successLabel = action.successLabel.takeIf { error == null },
                error = error
            )
        )
        return result
    }

    fun copyDiagnostics(diagnostics: String, actions: SharedAgentDetailActions) {
        dispatch(SharedAgentDetailIntent.StartDiagnosticAction("copy"))
        actions.copyDiagnostics(diagnostics)
        dispatch(
            SharedAgentDetailIntent.FinishDiagnosticAction(
                actionKey = "copy",
                successLabel = "Diagnostics copied"
            )
        )
    }

    suspend fun reportIssue(
        request: SharedAgentIssueReportRequest,
        actions: SharedAgentDetailActions
    ): DataResult<AgentIssueReportResult> {
        dispatch(SharedAgentDetailIntent.StartDiagnosticAction("report"))
        val result =
            try {
                actions.reportIssue(request)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                DataResult.Error(ScreenError("agent_issue_report_failed"))
            }
        val error =
            when (result) {
                is DataResult.Content -> null
                is DataResult.Empty -> ScreenError(result.reason.name, retryable = false)
                is DataResult.Error -> result.error
                is DataResult.NoPermission -> ScreenError(result.blocker.code, retryable = false)
            }
        dispatch(
            SharedAgentDetailIntent.FinishDiagnosticAction(
                actionKey = "report",
                successLabel = "Issue reported".takeIf { error == null },
                error = error
            )
        )
        return result
    }

    private fun DataResult<AgentLifecycleActionResult>.lifecycleError(
        action: MembersAgentLifecycleAction
    ): ScreenError? =
        when (this) {
            is DataResult.Content ->
                if (data.success) null else ScreenError("agent_${action.actionKey}_failed")
            is DataResult.Empty -> ScreenError(reason.name, retryable = false)
            is DataResult.Error -> error
            is DataResult.NoPermission -> ScreenError(blocker.code, retryable = false)
        }
}

private fun SharedAgentDetailState.applyAgentActivityUpdate(
    event: MemberPresenceUpdated,
    observedAtMillis: Long
): SharedAgentDetailState {
    if (event.kind != MemberKind.Agent) return this
    val agentId = event.memberId
    if (this.agentId != agentId) return this
    // agent:seen is a seq-less presence fact: it updates only lastSeenAt on
    // the detail summary and must never enter the activity logs or advance
    // the managed-activity sequence.
    val isSeenEvent = event.serverSeq == null && event.lastSeenAt != null
    if (!isSeenEvent && !event.shouldApplyAfter(agentActivitySeq[agentId])) return this
    val nextActivityLog =
        if (isSeenEvent) agentActivityLogs[agentId].orEmpty()
        else agentActivityLogs[agentId].orEmpty().withRealtimeAgentActivity(event, observedAtMillis)
    val nextRealtimeActivityLog =
        if (isSeenEvent) agentRealtimeActivityLogs[agentId].orEmpty()
        else agentRealtimeActivityLogs[agentId].orEmpty().withRealtimeAgentActivity(event, observedAtMillis)
    val nextDetail =
        if (this.agentId == agentId) {
            detail?.withAgentActivity(event, observedAtMillis)?.let { d ->
                if (isSeenEvent) d else d.copy(activity = nextActivityLog)
            }
        } else {
            detail
        }
    return copy(
        detail = nextDetail,
        agentActivityLogs = agentActivityLogs.withAgentActivityLog(agentId, nextActivityLog),
        agentRealtimeActivityLogs = agentRealtimeActivityLogs.withAgentActivityLog(agentId, nextRealtimeActivityLog),
        agentActivityObservedAt = agentActivityObservedAt + (agentId to observedAtMillis),
        agentActivityVersions = agentActivityVersions + (agentId to ((agentActivityVersions[agentId] ?: 0L) + 1L)),
        agentActivitySeq = event.serverSeq?.let { agentActivitySeq + (agentId to it) } ?: agentActivitySeq
    )
}

private fun SharedAgentDetailState.acceptsDetailResult(
    owner: SharedAgentDetailOwner?,
    profileGeneration: Long
): Boolean =
    detailOwner == owner && detailProfileGeneration == profileGeneration

private fun SharedAgentDetailState.applyAgentActivitySnapshot(
    agentId: MemberId,
    activity: List<AgentActivityEntry>
): SharedAgentDetailState {
    if (activity.isEmpty()) return this
    val nextActivityLog = mergeAgentActivityLogs(agentActivityLogs[agentId].orEmpty() + activity)
    val nextDetail =
        if (this.agentId == agentId && detail?.agentId == agentId) {
            detail.copy(activity = nextActivityLog)
        } else {
            detail
        }
    return copy(
        detail = nextDetail,
        agentActivityLogs = agentActivityLogs.withAgentActivityLog(agentId, nextActivityLog)
    )
}

private fun SharedAgentDetailState.seedDetailFromMember(member: MemberProfile): SharedAgentDetailState {
    if (member.kind != MemberKind.Agent) return this
    if (
        agentId == member.id &&
        detail?.agentId == member.id &&
        detailServerId?.value?.trim() == member.serverId?.value?.trim()
    ) {
        return this
    }
    val canReuseCachedActivity =
        (agentId == member.id && detailServerId == member.serverId) ||
            (member.serverId == null && detailServerId == null)
    val base = if (canReuseCachedActivity) this else withoutCachedAgentActivity(member.id)
    return base.copy(
        agentId = member.id,
        detailServerId = member.serverId,
        detail = member.toAgentDetailSeed(base.agentActivityLogs[member.id].orEmpty()),
        detailLoadStatus = SharedAgentDetailLoadStatus.Content,
        detailError = null,
        detailPermissionBlocker = null,
        lifecycleActionInFlight = null,
        diagnosticActionInFlight = null,
        pendingConfirmation = null,
        lastActionResult = null,
        displayNameEditing = false,
        displayNameDraft = null,
        displayNameSaving = false,
        displayNameError = null,
        roleEditing = false,
        roleDraft = null,
        roleSaving = false,
        roleError = null
    )
}

private fun SharedAgentDetailState.withoutCachedAgentActivity(agentId: MemberId): SharedAgentDetailState =
    copy(
        agentActivityLogs = agentActivityLogs - agentId,
        agentRealtimeActivityLogs = agentRealtimeActivityLogs - agentId,
        agentActivityObservedAt = agentActivityObservedAt - agentId,
        agentActivityVersions = agentActivityVersions - agentId,
        agentActivitySeq = agentActivitySeq - agentId
    )

private fun MemberProfile.toAgentDetailSeed(activity: List<AgentActivityEntry>): AgentDetailSnapshot {
    val fallbackName = handle.ifBlank { displayName.ifBlank { id.value } }
    val display = displayName.ifBlank { fallbackName }
    return AgentDetailSnapshot(
        agentId = id,
        summary = AgentDetailSummary(
            id = id.value,
            name = fallbackName,
            displayName = display,
            runtime = roleDescription,
            avatarUrl = preferredAvatarWireValue(avatar, avatarUrl),
            activityDetail = description,
            machineName = permissionBadges.firstNotNullOfOrNull { it.memberComputerLabelOrNull() },
            machineId = permissionBadges.firstNotNullOfOrNull { it.memberComputerIdOrNull() }
        ),
        directMessages = emptyList(),
        reminders = emptyList(),
        activity = activity
    )
}

private fun MemberPresenceUpdated.shouldApplyAfter(previousSeq: Long?): Boolean {
    val nextSeq = serverSeq
    if (nextSeq != null && previousSeq != null && nextSeq <= previousSeq) return false
    return true
}

private fun SharedAgentDetailState.mergeSequencedActivityIntoDetail(
    agentId: MemberId,
    incoming: AgentDetailSnapshot,
    preserveSequencedActivity: Boolean
): AgentDetailSnapshot {
    if (!preserveSequencedActivity || agentActivitySeq[agentId] == null) return incoming
    val currentDetail = detail
    if (currentDetail?.agentId != agentId) return incoming
    val currentSummary = currentDetail.summary
    val incomingSummary = incoming.summary
    val currentLiveActivityIds = currentDetail.activity
        .filter { it.id.startsWith(RealtimeAgentActivityIdPrefix) }
        .map { it.id }
        .toSet()
    val nextActivity =
        if (currentLiveActivityIds.isEmpty()) {
            incoming.activity
        } else {
            (incoming.activity + currentDetail.activity.filter { it.id in currentLiveActivityIds })
                .dedupeAgentActivityEntries()
                .takeLast(MaxAgentActivityEntries)
        }
    val nextSummary =
        if (currentSummary != null && incomingSummary != null) {
            incomingSummary.copy(
                status = currentSummary.status ?: incomingSummary.status,
                activity = currentSummary.activity ?: incomingSummary.activity,
                activityDetail = currentSummary.activityDetail ?: incomingSummary.activityDetail
            )
        } else {
            incomingSummary
        }
    return incoming.copy(
        summary = nextSummary,
        activity = nextActivity
    )
}

private fun AgentDetailSnapshot.withCachedAgentActivity(
    cachedActivity: List<AgentActivityEntry>?
): AgentDetailSnapshot {
    val mergedActivity = mergeAgentActivityLogs(cachedActivity.orEmpty() + activity)
    return if (mergedActivity.isEmpty()) this else copy(activity = mergedActivity)
}

private fun AgentDetailSnapshot.withExistingAgentDetailSections(
    existing: AgentDetailSnapshot
): AgentDetailSnapshot {
    if (existing.agentId != agentId) return this
    return copy(
        directMessages = directMessages.ifEmpty { existing.directMessages },
        channels = channels.ifEmpty { existing.channels },
        reminders = reminders.ifEmpty { existing.reminders },
        activity = activity.ifEmpty { existing.activity },
        skills = skills.takeUnless { it.groups.isEmpty() } ?: existing.skills,
        appLogins =
            appLogins
                .takeUnless {
                    it.items.isEmpty() &&
                        it.loadState == AgentAppLoginLoadState.Content &&
                        it.errorCode == null
                }
                ?: existing.appLogins
    )
}

internal fun AgentDetailSnapshot.withRuntimeConfigProjection(config: RuntimeConfig): AgentDetailSnapshot {
    val currentSummary = summary ?: AgentDetailSummary(id = agentId.value, name = agentId.value)
    return copy(
        summary = currentSummary.copy(
            runtime = config.runtime,
            model =
                when (val modelConfig = config.model) {
                    is RuntimeModelConfig.Preset -> modelConfig.id
                    is RuntimeModelConfig.Custom -> modelConfig.name
                },
            runtimeProvider = config.provider,
            runtimeModelKind =
                when (config.model) {
                    is RuntimeModelConfig.Preset -> "preset"
                    is RuntimeModelConfig.Custom -> "custom"
                },
            reasoning = config.reasoningEffort,
            executionMode = if (config.mode is RuntimeModeConfig.Fast) "fast" else "default",
            envVars = config.envVars.orEmpty(),
            envVarNames = config.envVars.orEmpty().keys.sorted()
        )
    )
}

private fun AgentDetailSnapshot.withAgentActivity(
    event: MemberPresenceUpdated,
    observedAtMillis: Long
): AgentDetailSnapshot {
    val currentSummary = summary
    val activityLabel = event.agentStatus?.label
    // agent:seen is a seq-less presence fact: it carries only presence +
    // lastSeenAt and must not overwrite the managed activity columns.
    val isSeenEvent = event.serverSeq == null && event.lastSeenAt != null
    return copy(
        summary = currentSummary?.copy(
            status = if (isSeenEvent) currentSummary.status else (activityLabel ?: currentSummary.status),
            activity = if (isSeenEvent) currentSummary.activity else (activityLabel ?: currentSummary.activity),
            activityDetail =
                if (isSeenEvent) currentSummary.activityDetail
                else event.statusLabel.nonBlankOrNull() ?: currentSummary.activityDetail,
            lastSeenAt = event.lastSeenAt ?: currentSummary.lastSeenAt
        ),
        activity = if (isSeenEvent) activity else activity.withRealtimeAgentActivity(event, observedAtMillis)
    )
}

private fun List<AgentActivityEntry>.withRealtimeAgentActivity(
    event: MemberPresenceUpdated,
    observedAtMillis: Long
): List<AgentActivityEntry> {
    val entries = event.activityEntries
        .map { it.toAgentActivityEntry(observedAtMillis) }
    if (entries.isNotEmpty()) {
        return (this + entries)
            .dedupeAgentActivityEntries()
            .takeLast(MaxAgentActivityEntries)
    }
    val entry = event.toRealtimeAgentActivityEntry(observedAtMillis) ?: return this
    val last = lastOrNull()
    if (last != null && last.isDuplicateAgentStatusEntry(entry, observedAtMillis)) return this
    return (this + entry)
        .dedupeAgentActivityEntries()
        .takeLast(MaxAgentActivityEntries)
}

private fun MemberPresenceUpdated.toRealtimeAgentActivityEntry(
    observedAtMillis: Long
): AgentActivityEntry? {
    val activity = agentStatus ?: return null
    val activityKey = activity.name.lowercase()
    val detail = statusLabel.nonBlankOrNull()
    val idSuffix = serverSeq?.let { "seq:$it" }
        ?: "${observedAtMillis}:${activityKey}:${detail.orEmpty()}"
    return AgentActivityEntry(
        id = "$RealtimeAgentActivityIdPrefix${memberId.value}:$idSuffix",
        type = "status",
        title = activity.toAgentActivityTitle(detail),
        body = detail,
        timestamp = observedAtMillis.toString(),
        statusActivity = activityKey,
        statusDetail = detail,
        refTextEnabled = true
    )
}

private fun RealtimeAgentActivityEntry.toAgentActivityEntry(
    observedAtMillis: Long
): AgentActivityEntry {
    val activityId =
        if (id.startsWith(RealtimeAgentActivityIdPrefix)) {
            id
        } else {
            "$RealtimeAgentActivityIdPrefix$id"
        }
    return AgentActivityEntry(
        id = activityId,
        type = type,
        title = title,
        body = body,
        timestamp = timestamp ?: observedAtMillis.toString(),
        details = details.map { it.label to it.value },
        toolName = toolName,
        toolInput = toolInput,
        statusActivity = statusActivity,
        statusDetail = statusDetail,
        isSubagent = isSubagent,
        subagentType = subagentType,
        expandable = expandable,
        refTextEnabled = refTextEnabled
    )
}

private fun AgentStatus.toAgentActivityTitle(detail: String?): String =
    when {
        this == AgentStatus.Online -> "Idle"
        this == AgentStatus.Offline && detail == "Stopped" -> "Stopped"
        this == AgentStatus.Disconnected -> "Disconnected"
        this == AgentStatus.Error -> "Error"
        else -> label
    }

private fun AgentActivityEntry.isDuplicateAgentStatusEntry(
    next: AgentActivityEntry,
    nextObservedAtMillis: Long
): Boolean {
    if (type != "status" || next.type != "status") return false
    if (statusActivity != next.statusActivity || statusDetail != next.statusDetail) return false
    val previousTimestamp = timestamp?.toLongOrNull() ?: return false
    return nextObservedAtMillis - previousTimestamp in 0 until 1000
}

private fun List<AgentActivityEntry>.dedupeAgentActivityEntries(): List<AgentActivityEntry> =
    distinctBy { it.id.ifBlank { "${it.type}:${it.timestamp.orEmpty()}:${it.body.orEmpty()}:${it.statusActivity.orEmpty()}" } }

private fun Map<MemberId, List<AgentActivityEntry>>.withAgentActivityLog(
    agentId: MemberId,
    activity: List<AgentActivityEntry>
): Map<MemberId, List<AgentActivityEntry>> {
    if (activity.isEmpty()) return this
    val mergedActivity = mergeAgentActivityLogs(activity)
    return if (this[agentId] == mergedActivity) this else this + (agentId to mergedActivity)
}

private fun mergeAgentActivityLogs(activity: List<AgentActivityEntry>): List<AgentActivityEntry> =
    activity
        .dedupeAgentActivityEntries()
        .sortedWith(compareBy<AgentActivityEntry> { it.timestamp.orEmpty() }.thenBy { it.id })
        .takeLast(MaxAgentActivityEntries)

private fun String?.nonBlankOrNull(): String? = this?.takeIf { it.isNotBlank() }

private const val RealtimeAgentActivityIdPrefix = "realtime:"
private const val MaxAgentActivityEntries = 50

private fun String.memberComputerLabelOrNull(): String? =
    removePrefix(MemberComputerBadgePrefix).takeIf { startsWith(MemberComputerBadgePrefix) && it.isNotBlank() }

private fun String.memberComputerIdOrNull(): String? =
    removePrefix(MemberComputerIdBadgePrefix).takeIf { startsWith(MemberComputerIdBadgePrefix) && it.isNotBlank() }

private const val MemberComputerBadgePrefix = "computer:"
private const val MemberComputerIdBadgePrefix = "computer-id:"

/**
 * One agent's last canonical detail response (data-layer plan step 5), with the route facts the
 * page needs to paint it: the server it was loaded on and the viewer.
 */
internal data class RaftCachedAgentDetail(
    val server: build.raft.app.model.ServerSummary,
    val currentUserId: String?,
    val detail: AgentDetailSnapshot
)

/**
 * Process agent-detail cache: memory-only (the detail graph carries runtime config and activity
 * that are revalidated on every open anyway), keyed environment + account + server + agent,
 * capped per server.
 */
internal typealias RaftAgentDetailCache = RaftKeyedSnapshotCache<RaftCachedAgentDetail, Unit>

internal fun SharedAgentDetailOwner.agentDetailCacheKey(agentId: MemberId): RaftDurableSnapshotKey =
    RaftDurableSnapshotKey(
        environmentId = environmentId,
        accountId = accountId,
        serverId = serverId.value,
        entityId = agentId.value
    )
