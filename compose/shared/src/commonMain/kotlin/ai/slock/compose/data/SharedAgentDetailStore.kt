package ai.slock.compose.data

import ai.slock.compose.MembersAgentDetailTab
import ai.slock.compose.MembersAgentLifecycleAction
import ai.slock.compose.model.MemberId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Screen-relative anchor of a 3-dot button, captured at click time and used to
 * position the dropdown overlay. Stored in a data class to keep this file
 * free of `androidx.compose.ui.geometry.Offset` (the data package must not
 * depend on Compose runtime types — see `SharedThreadStore`).
 */
internal data class SharedAgentDetailMenuAnchor(
    val x: Float,
    val y: Float
)

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
 * selected tab, management menu open/anchor, lifecycle action in-flight,
 * diagnostic action in-flight, pending confirmation, last action result,
 * profile edit (displayName + role draft/saving/error). Tab-specific state
 * (workspace tree / file / diagnostics tabs / activity / reminders / apps /
 * permissions) deliberately left local for Phase 2 / follow-up.
 *
 * The store is state-only (no API orchestration); the composable still
 * invokes the host callback (e.g. `onAgentLifecycleAction`) and dispatches
 * the result via `SharedAgentDetailIntent` so the store is the single
 * source of truth for "what's pending right now".
 */
internal data class SharedAgentDetailState(
    val agentId: MemberId? = null,
    val detail: AgentDetailSnapshot? = null,
    val detailLoadStatus: SharedAgentDetailLoadStatus = SharedAgentDetailLoadStatus.Idle,
    val detailErrorCode: String? = null,
    val selectedTab: MembersAgentDetailTab = MembersAgentDetailTab.Profile,
    val managementMenuOpen: Boolean = false,
    val managementMenuAnchor: SharedAgentDetailMenuAnchor? = null,
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

internal sealed interface SharedAgentDetailIntent {
    data class SelectMember(val memberId: MemberId) : SharedAgentDetailIntent
    data object DeselectMember : SharedAgentDetailIntent

    data class SetDetailLoading(val agentId: MemberId) : SharedAgentDetailIntent
    data class SetDetail(val agentId: MemberId, val detail: AgentDetailSnapshot) : SharedAgentDetailIntent
    data class SetDetailNoPermission(val agentId: MemberId, val code: String) : SharedAgentDetailIntent
    data class SetDetailError(val agentId: MemberId, val code: String) : SharedAgentDetailIntent
    data class SetDetailEmpty(val agentId: MemberId) : SharedAgentDetailIntent

    data class SelectTab(val tab: MembersAgentDetailTab) : SharedAgentDetailIntent

    data class OpenManagementMenu(val anchor: SharedAgentDetailMenuAnchor) : SharedAgentDetailIntent
    data object CloseManagementMenu : SharedAgentDetailIntent

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
            is SharedAgentDetailIntent.SelectMember -> current.copy(agentId = intent.memberId)
            SharedAgentDetailIntent.DeselectMember -> SharedAgentDetailState()

            is SharedAgentDetailIntent.SetDetailLoading -> current.copy(
                agentId = intent.agentId,
                detailLoadStatus = SharedAgentDetailLoadStatus.Loading,
                detailErrorCode = null
            )
            is SharedAgentDetailIntent.SetDetail -> current.copy(
                agentId = intent.agentId,
                detail = intent.detail,
                detailLoadStatus = SharedAgentDetailLoadStatus.Content,
                detailErrorCode = null
            )
            is SharedAgentDetailIntent.SetDetailNoPermission -> current.copy(
                agentId = intent.agentId,
                detailLoadStatus = SharedAgentDetailLoadStatus.NoPermission,
                detailErrorCode = intent.code
            )
            is SharedAgentDetailIntent.SetDetailError -> current.copy(
                agentId = intent.agentId,
                detailLoadStatus = SharedAgentDetailLoadStatus.Error,
                detailErrorCode = intent.code
            )
            is SharedAgentDetailIntent.SetDetailEmpty -> current.copy(
                agentId = intent.agentId,
                detailLoadStatus = SharedAgentDetailLoadStatus.Empty,
                detailErrorCode = null
            )

            is SharedAgentDetailIntent.SelectTab -> current.copy(selectedTab = intent.tab)

            is SharedAgentDetailIntent.OpenManagementMenu -> current.copy(
                managementMenuOpen = true,
                managementMenuAnchor = intent.anchor
            )
            SharedAgentDetailIntent.CloseManagementMenu -> current.copy(
                managementMenuOpen = false,
                managementMenuAnchor = null
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
                displayNameDraft = intent.draft
            )
            is SharedAgentDetailIntent.StartSaveDisplayName -> current.copy(
                displayNameSaving = true,
                displayNameError = null
            )
            is SharedAgentDetailIntent.SetDisplayNameError -> current.copy(
                displayNameError = intent.code
            )
            SharedAgentDetailIntent.FinishSaveDisplayName -> current.copy(
                displayNameSaving = false
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
                roleDraft = intent.draft
            )
            is SharedAgentDetailIntent.StartSaveRole -> current.copy(
                roleSaving = true,
                roleError = null
            )
            is SharedAgentDetailIntent.SetRoleError -> current.copy(
                roleError = intent.code
            )
            SharedAgentDetailIntent.FinishSaveRole -> current.copy(
                roleSaving = false
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
}
