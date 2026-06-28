package ai.slock.compose.data

import ai.slock.compose.MembersAgentLifecycleAction
import ai.slock.compose.model.AgentStatus
import ai.slock.compose.model.MemberId
import ai.slock.compose.model.MemberProfile
import ai.slock.compose.model.MemberKind
import ai.slock.compose.model.MemberPresenceUpdated
import ai.slock.compose.model.RealtimeAgentActivityEntry
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
    val detail: AgentDetailSnapshot? = null,
    val agentActivityObservedAt: Map<MemberId, Long> = emptyMap(),
    val agentActivityVersions: Map<MemberId, Long> = emptyMap(),
    val agentActivitySeq: Map<MemberId, Long> = emptyMap(),
    val detailLoadStatus: SharedAgentDetailLoadStatus = SharedAgentDetailLoadStatus.Idle,
    val detailErrorCode: String? = null,
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
    val errorCode: String? = null
)

internal interface SharedAgentDetailActions {
    suspend fun runLifecycleAction(
        agentId: MemberId,
        action: MembersAgentLifecycleAction
    ): DataResult<AgentLifecycleActionResult>

    fun copyDiagnostics(diagnostics: String)

    suspend fun reportIssue(diagnostics: String): Boolean

    suspend fun updateAgentProfile(request: AgentProfileUpdateRequest): DataResult<MemberProfile>
}

internal sealed interface SharedAgentDetailIntent {
    data class SelectMember(val memberId: MemberId) : SharedAgentDetailIntent
    data class SeedDetailFromMember(val member: MemberProfile) : SharedAgentDetailIntent
    data object DeselectMember : SharedAgentDetailIntent

    data class SetDetailLoading(val agentId: MemberId) : SharedAgentDetailIntent
    data class SetDetail(
        val agentId: MemberId,
        val detail: AgentDetailSnapshot,
        val preserveSequencedActivity: Boolean = true
    ) : SharedAgentDetailIntent
    data class SetDetailNoPermission(val agentId: MemberId, val code: String) : SharedAgentDetailIntent
    data class SetDetailError(val agentId: MemberId, val code: String) : SharedAgentDetailIntent
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
        val errorCode: String? = null
    ) : SharedAgentDetailIntent
    data class StartDiagnosticAction(val actionKey: String) : SharedAgentDetailIntent
    data class FinishDiagnosticAction(
        val actionKey: String,
        val successLabel: String? = null,
        val errorCode: String? = null
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
                if (current.agentId == intent.memberId) {
                    current
                } else {
                    SharedAgentDetailState(
                        agentId = intent.memberId,
                        agentActivityObservedAt = current.agentActivityObservedAt,
                        agentActivityVersions = current.agentActivityVersions,
                        agentActivitySeq = current.agentActivitySeq
                    )
                }
            is SharedAgentDetailIntent.SeedDetailFromMember -> current.seedDetailFromMember(intent.member)
            SharedAgentDetailIntent.DeselectMember -> SharedAgentDetailState(
                agentActivityObservedAt = current.agentActivityObservedAt,
                agentActivityVersions = current.agentActivityVersions,
                agentActivitySeq = current.agentActivitySeq
            )

            is SharedAgentDetailIntent.SetDetailLoading ->
                if (current.agentId == intent.agentId) {
                    current.copy(
                        detailLoadStatus = SharedAgentDetailLoadStatus.Loading,
                        detailErrorCode = null
                    )
                } else {
                    current
                }
            is SharedAgentDetailIntent.SetDetail ->
                if (current.agentId == intent.agentId) {
                    val detail = current.mergeSequencedActivityIntoDetail(
                        agentId = intent.agentId,
                        incoming = intent.detail,
                        preserveSequencedActivity = intent.preserveSequencedActivity
                    )
                    current.copy(
                        detail = detail,
                        detailLoadStatus = SharedAgentDetailLoadStatus.Content,
                        detailErrorCode = null
                    )
                } else {
                    current
                }
            is SharedAgentDetailIntent.SetDetailNoPermission ->
                if (current.agentId == intent.agentId) {
                    current.copy(
                        detailLoadStatus = SharedAgentDetailLoadStatus.NoPermission,
                        detailErrorCode = intent.code
                    )
                } else {
                    current
                }
            is SharedAgentDetailIntent.SetDetailError ->
                if (current.agentId == intent.agentId) {
                    current.copy(
                        detailLoadStatus = SharedAgentDetailLoadStatus.Error,
                        detailErrorCode = intent.code
                    )
                } else {
                    current
                }
            is SharedAgentDetailIntent.SetDetailEmpty ->
                if (current.agentId == intent.agentId) {
                    current.copy(
                        detailLoadStatus = SharedAgentDetailLoadStatus.Empty,
                        detailErrorCode = null
                    )
                } else {
                    current
                }
            is SharedAgentDetailIntent.ApplyAgentActivityUpdate ->
                current.applyAgentActivityUpdate(intent.event, intent.observedAtMillis)
            SharedAgentDetailIntent.ResetAgentActivitySeq -> current.copy(
                agentActivitySeq = emptyMap()
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
                    errorCode = intent.errorCode
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
                    errorCode = intent.errorCode
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
        actions: SharedAgentDetailActions
    ): DataResult<AgentLifecycleActionResult> {
        dispatch(SharedAgentDetailIntent.StartLifecycleAction(action.actionKey))
        val result =
            try {
                actions.runLifecycleAction(agentId, action)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                DataResult.Error(ScreenError("agent_${action.actionKey}_failed"))
            }
        val errorCode = result.lifecycleErrorCode(action)
        dispatch(
            SharedAgentDetailIntent.FinishLifecycleAction(
                actionKey = action.actionKey,
                successLabel = action.successLabel.takeIf { errorCode == null },
                errorCode = errorCode
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

    suspend fun reportIssue(diagnostics: String, actions: SharedAgentDetailActions): Boolean {
        dispatch(SharedAgentDetailIntent.StartDiagnosticAction("report"))
        val reported =
            try {
                actions.reportIssue(diagnostics)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                false
            }
        dispatch(
            SharedAgentDetailIntent.FinishDiagnosticAction(
                actionKey = "report",
                successLabel = "Issue reported".takeIf { reported },
                errorCode = if (reported) null else "agent_issue_report_failed"
            )
        )
        return reported
    }

    private fun DataResult<AgentLifecycleActionResult>.lifecycleErrorCode(
        action: MembersAgentLifecycleAction
    ): String? =
        when (this) {
            is DataResult.Content ->
                if (data.success) null else "agent_${action.actionKey}_failed"
            is DataResult.Empty -> reason.name
            is DataResult.Error -> error.code
            is DataResult.NoPermission -> blocker.code
        }
}

private fun SharedAgentDetailState.applyAgentActivityUpdate(
    event: MemberPresenceUpdated,
    observedAtMillis: Long
): SharedAgentDetailState {
    if (event.kind != MemberKind.Agent) return this
    val agentId = event.memberId
    if (!event.shouldApplyAfter(agentActivitySeq[agentId])) return this
    val nextDetail =
        if (this.agentId == agentId) {
            detail?.withAgentActivity(event, observedAtMillis)
        } else {
            detail
        }
    return copy(
        detail = nextDetail,
        agentActivityObservedAt = agentActivityObservedAt + (agentId to observedAtMillis),
        agentActivityVersions = agentActivityVersions + (agentId to ((agentActivityVersions[agentId] ?: 0L) + 1L)),
        agentActivitySeq = event.serverSeq?.let { agentActivitySeq + (agentId to it) } ?: agentActivitySeq
    )
}

private fun SharedAgentDetailState.seedDetailFromMember(member: MemberProfile): SharedAgentDetailState {
    if (member.kind != MemberKind.Agent) return this
    if (agentId == member.id && detail?.agentId == member.id) return this
    return copy(
        agentId = member.id,
        detail = member.toAgentDetailSeed(),
        detailLoadStatus = SharedAgentDetailLoadStatus.Content,
        detailErrorCode = null,
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

private fun MemberProfile.toAgentDetailSeed(): AgentDetailSnapshot {
    val fallbackName = handle.ifBlank { displayName.ifBlank { id.value } }
    val display = displayName.ifBlank { fallbackName }
    val status = agentStatus?.label
    return AgentDetailSnapshot(
        agentId = id,
        summary = AgentDetailSummary(
            id = id.value,
            name = fallbackName,
            displayName = display,
            status = status,
            runtime = roleDescription,
            avatarUrl = avatar?.wireValue ?: avatarUrl,
            activity = status,
            activityDetail = description,
            machineName = permissionBadges.firstNotNullOfOrNull { it.memberComputerLabelOrNull() },
            machineId = permissionBadges.firstNotNullOfOrNull { it.memberComputerIdOrNull() }
        ),
        directMessages = emptyList(),
        reminders = emptyList(),
        activity = emptyList()
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

private fun AgentDetailSnapshot.withAgentActivity(
    event: MemberPresenceUpdated,
    observedAtMillis: Long
): AgentDetailSnapshot {
    val currentSummary = summary
    val activityLabel = event.agentStatus?.label
    return copy(
        summary = currentSummary?.copy(
            status = activityLabel ?: currentSummary.status,
            activity = activityLabel ?: currentSummary.activity,
            activityDetail = event.statusLabel.nonBlankOrNull() ?: currentSummary.activityDetail
        ),
        activity = activity.withRealtimeAgentActivity(event, observedAtMillis)
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

private fun String?.nonBlankOrNull(): String? = this?.takeIf { it.isNotBlank() }

private const val RealtimeAgentActivityIdPrefix = "realtime:"
private const val MaxAgentActivityEntries = 50

private fun String.memberComputerLabelOrNull(): String? =
    removePrefix(MemberComputerBadgePrefix).takeIf { startsWith(MemberComputerBadgePrefix) && it.isNotBlank() }

private fun String.memberComputerIdOrNull(): String? =
    removePrefix(MemberComputerIdBadgePrefix).takeIf { startsWith(MemberComputerIdBadgePrefix) && it.isNotBlank() }

private const val MemberComputerBadgePrefix = "computer:"
private const val MemberComputerIdBadgePrefix = "computer-id:"
