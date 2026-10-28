package build.raft.app

import build.raft.app.resources.*
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftButtonSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import build.raft.app.data.DataResult
import build.raft.app.components.RaftBrutalActionButton
import build.raft.app.components.RaftUiBanner
import build.raft.app.components.RaftUiBannerSize
import build.raft.app.components.RaftUiBannerStatus
import build.raft.app.data.runtimeform.v2.RUNTIME_FORM_V2_FORM_REVISION_STALE
import build.raft.app.data.runtimeform.v2.RuntimeFormV2Access
import build.raft.app.data.runtimeform.v2.RuntimeFormV2Loaded
import build.raft.app.data.runtimeform.v2.RuntimeFormV2SubmitBody
import build.raft.app.data.runtimeform.v2.applyRuntimeFormV2Change
import build.raft.app.data.runtimeform.v2.initialRuntimeFormV2Values
import build.raft.app.data.runtimeform.v2.runtimeFormV2Submission
import build.raft.app.data.runtimeform.v2.runtimeFormV2ValuesAfterSourceReload
import build.raft.app.data.runtimeform.v2.validateRuntimeFormV2
import build.raft.app.model.MemberId
import build.raft.app.model.getRuntimeDisplayName
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Edit an agent's runtime settings through runtime form v2: the same form create
 * uses, prefilled by the server (`GET /agents/:id/runtime-form`). writeOnly fields
 * start blank, and blank means "keep the stored value". As on web, a Runtime
 * select sits at the top (options from this agent's computer, [machineRuntimeIds]).
 * Picking another runtime loads that runtime's create form
 * (`…/machines/:machineId/runtime-forms/v2/:runtimeId`, nothing prefilled, no secret to
 * keep); the switch counts as a change by itself and is confirmed before [onSave]
 * (which then starts a new session). A load failure shows a warning with Retry and
 * keeps Save disabled; there is no v1 fallback (task #8, retire runtime form v1).
 */
@Composable
internal fun MembersAgentRuntimeFormV2Dialog(
    access: RuntimeFormV2Access,
    agentId: MemberId,
    machineId: String,
    runtimeId: String,
    machineRuntimeIds: List<String>,
    activeAgent: Boolean,
    saving: Boolean,
    error: String?,
    errorFieldKeys: Set<String>,
    onDismiss: () -> Unit,
    onRuntimeSelected: () -> Unit,
    onSave: (submit: RuntimeFormV2SubmitBody, runtimeSwitched: Boolean) -> Unit
) {
    var selectedRuntimeId by remember(access, agentId, machineId, runtimeId) { mutableStateOf(runtimeId) }
    val runtimeSwitched = membersRuntimeFormV2EditRuntimeSwitched(runtimeId, selectedRuntimeId)
    var loadAttempt by remember(access, agentId, machineId, selectedRuntimeId) { mutableStateOf(0) }
    // A form that went stale mid-session (`form_revision_stale`) is reloaded whole, once; then it fails.
    var staleReloads by remember(access, agentId, machineId, selectedRuntimeId) { mutableStateOf(0) }
    var load by remember(access, agentId, machineId, selectedRuntimeId, loadAttempt, staleReloads) {
        mutableStateOf<MembersRuntimeFormV2EditLoad>(MembersRuntimeFormV2EditLoad.Loading)
    }
    LaunchedEffect(access, agentId, machineId, selectedRuntimeId, loadAttempt, staleReloads) {
        load = loadMembersRuntimeFormV2Edit(access, machineId, agentId, runtimeId, selectedRuntimeId)
    }
    val loaded = (load as? MembersRuntimeFormV2EditLoad.Ready)?.loaded
    var pendingSwitchSubmit by remember(access, agentId, machineId) { mutableStateOf<RuntimeFormV2SubmitBody?>(null) }
    val form = loaded
    val initialValues = remember(form) { form?.let { initialRuntimeFormV2Values(it.form, it.sources) }.orEmpty() }
    var values by remember(form) { mutableStateOf(initialValues) }
    var touched by remember(form) { mutableStateOf(emptySet<String>()) }
    var advancedOpen by remember(form) { mutableStateOf(false) }
    // Re-probed option sources sit on top of the loaded form; render, validate and submit against them.
    val (current, sourceRefresh) =
        rememberRuntimeFormV2SourceRefresh(
            access,
            machineId,
            form,
            onFormStale = {
                if (staleReloads < 1) {
                    staleReloads += 1
                } else {
                    load = MembersRuntimeFormV2EditLoad.Failed(RUNTIME_FORM_V2_FORM_REVISION_STALE)
                }
            }
        ) { updated, sourceId ->
            values = runtimeFormV2ValuesAfterSourceReload(updated.form, updated.sources, values, sourceId)
        }
    // A switched runtime's create form has no stored secret to keep: validate it as create does.
    val errors = current?.let { validateRuntimeFormV2(it.form, it.sources, values, editing = !runtimeSwitched) }.orEmpty()
    val saveEnabled =
        membersRuntimeFormV2EditSaveEnabled(
            saving = saving,
            formReady = form != null,
            runtimeSwitched = runtimeSwitched,
            valuesChanged = values != initialValues,
            hasErrors = errors.isNotEmpty()
        )
    val failureCode = (load as? MembersRuntimeFormV2EditLoad.Failed)?.code

    // Read here: dialog content is hosted at the page root, outside the route's usage access.
    val quotaMachine = RuntimeQuotaMachine(machineId, access = LocalMembersRuntimeAccountUsageAccess.current)
    RaftUnifiedDialog(
        title = stringResource(Res.string.member_dialog_edit_runtime_config),
        dismissEnabled = !saving,
        keyboardAvoidanceEnabled = true,
        onDismiss = onDismiss
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
                if (failureCode != null) {
                    item(key = "runtime-form-v2-failure") {
                        RaftUiBanner(
                            status = RaftUiBannerStatus.Warning,
                            description = Res.string.runtime_form_v2_load_failed.format(listOf(failureCode)).localized(),
                            size = RaftUiBannerSize.Medium,
                            action = {
                                RaftBrutalActionButton(
                                    label = stringResource(Res.string.common_retry),
                                    onClick = { loadAttempt += 1 },
                                    enabled = !saving,
                                    leadingIcon = RaftLucideIconName.RefreshCw,
                                    minWidth = 72.dp,
                                    minHeight = 32.dp
                                )
                            }
                        )
                    }
                }
                item(key = "runtime-form-v2") {
                    RuntimeFormV2RuntimeSection(
                        runtime = selectedRuntimeId,
                        onRuntimeChange = { picked ->
                            membersRuntimeFormV2EditRuntimeSelection(selectedRuntimeId, picked)?.let { next ->
                                // The draft and any server errors belong to the form they were made in.
                                selectedRuntimeId = next
                                onRuntimeSelected()
                            }
                        },
                        machineRuntimeIds = machineRuntimeIds,
                        runtimeAvailabilityError = null,
                        enabled = !saving,
                        quotaMachine = quotaMachine
                    ) {
                        if (form == null && failureCode == null) {
                            Text(
                                text = stringResource(Res.string.agent_create_state_computers_loading),
                                style = RaftType.Caption,
                                color = RaftColor.TextMutedGray
                            )
                        } else if (form != null) {
                            val shown = current ?: form
                            RuntimeFormV2Fields(
                                loaded = shown,
                                values = values,
                                onChange = { key, value ->
                                    values = applyRuntimeFormV2Change(shown.form, shown.sources, values, key, value)
                                    touched = touched + key
                                },
                                errors = runtimeFormV2ShownErrors(errors, touched + errorFieldKeys),
                                serverErrorKeys = errorFieldKeys,
                                serverMessage = null,
                                editing = !runtimeSwitched,
                                advancedOpen = advancedOpen,
                                onAdvancedOpenChange = { advancedOpen = it },
                                enabled = !saving,
                                sourceRefresh = sourceRefresh
                            )
                        }
                    }
                }
                item(key = "restart-hint") {
                    Text(
                        text = stringResource(Res.string.member_hint_runtime_restart),
                        style = RaftType.Caption.copy(fontSize = 11.sp, lineHeight = 16.sp),
                        color = RaftColor.TextMutedGray
                    )
                }
                error?.takeIf { it.isNotBlank() }?.let { message ->
                    item(key = "save-error") {
                        Text(
                            text = message,
                            style = RaftType.Caption.withRaftFontWeight(FontWeight.Bold).copy(color = RaftColor.AccentRed),
                            color = RaftColor.AccentRed
                        )
                    }
                }
            }
            RaftDialogFooter {
                RaftButton(
                    text = stringResource(Res.string.common_cancel),
                    enabled = !saving,
                    onClick = onDismiss,
                    variant = RaftButtonVariant.Ghost,
                    size = RaftButtonSize.Lg
                )
                // Web AgentDetailPanel runtime editor: accent (primary) lg commit.
                RaftButton(
                    text =
                        if (saving) stringResource(Res.string.member_action_saving)
                        else stringResource(Res.string.member_action_save_runtime_config),
                    enabled = saveEnabled,
                    variant = RaftButtonVariant.Primary,
                    size = RaftButtonSize.Lg,
                    onClick = {
                        val submitting = current ?: form ?: return@RaftButton
                        val submit =
                            RuntimeFormV2SubmitBody(
                                runtimeId = submitting.form.runtimeId,
                                formValues = runtimeFormV2Submission(submitting.form, values, submitting.sources)
                            )
                        // A runtime switch resets the session: confirm it first, as web and the v1 editor do.
                        if (runtimeSwitched) pendingSwitchSubmit = submit else onSave(submit, false)
                    }
                )
            }
        }

    pendingSwitchSubmit?.let { submit ->
        val confirmation = membersRuntimeFormV2SwitchConfirmation(runtimeId, submit.runtimeId, activeAgent)
        MembersRuntimeSwitchConfirmationDialog(
            title = confirmation.title.localized(),
            message = confirmation.message.localized(),
            confirmLabel = confirmation.confirmLabel.localized(),
            onConfirm = {
                pendingSwitchSubmit = null
                onSave(submit, true)
            },
            onCancel = { if (!saving) pendingSwitchSubmit = null }
        )
    }
}

/** The editor's form: [Loading] until the first answer, then the form or why it failed. */
internal sealed interface MembersRuntimeFormV2EditLoad {
    data object Loading : MembersRuntimeFormV2EditLoad
    data class Ready(val loaded: RuntimeFormV2Loaded) : MembersRuntimeFormV2EditLoad
    data class Failed(val code: String) : MembersRuntimeFormV2EditLoad
}

/** True when the Runtime select holds a runtime other than the agent's saved [agentRuntimeId]. */
internal fun membersRuntimeFormV2EditRuntimeSwitched(agentRuntimeId: String, selectedRuntimeId: String): Boolean =
    selectedRuntimeId.trim().let { it.isNotBlank() && it != agentRuntimeId.trim() }

/**
 * The runtime the Runtime select moves to when the user picks [picked], or null when
 * [picked] is blank or already selected ([currentRuntimeId]).
 */
internal fun membersRuntimeFormV2EditRuntimeSelection(currentRuntimeId: String, picked: String): String? =
    picked.trim().takeIf { it.isNotBlank() && it != currentRuntimeId.trim() }

/**
 * Loads the editor's form: the agent's own edit form while [selectedRuntimeId] is its saved
 * runtime, otherwise [selectedRuntimeId]'s create form (no agent id, as web after task #8).
 * Every failure is [MembersRuntimeFormV2EditLoad.Failed]; nothing falls back to v1.
 */
internal suspend fun loadMembersRuntimeFormV2Edit(
    access: RuntimeFormV2Access,
    machineId: String,
    agentId: MemberId,
    agentRuntimeId: String,
    selectedRuntimeId: String
): MembersRuntimeFormV2EditLoad {
    val result =
        if (membersRuntimeFormV2EditRuntimeSwitched(agentRuntimeId, selectedRuntimeId)) {
            access.loader.loadCreate(access.serverId, machineId, selectedRuntimeId.trim())
        } else {
            access.loader.loadEdit(access.serverId, machineId, agentId)
        }
    return when (result) {
        is DataResult.Content -> MembersRuntimeFormV2EditLoad.Ready(result.data)
        is DataResult.Error -> MembersRuntimeFormV2EditLoad.Failed(result.error.code)
        is DataResult.NoPermission -> MembersRuntimeFormV2EditLoad.Failed(result.blocker.code)
        is DataResult.Empty -> MembersRuntimeFormV2EditLoad.Failed(result.reason.name)
    }
}

/**
 * Save needs a loaded form without errors, and a change: edited values, or a runtime switch,
 * which is a change by itself even with the new form's defaults untouched.
 */
internal fun membersRuntimeFormV2EditSaveEnabled(
    saving: Boolean,
    formReady: Boolean,
    runtimeSwitched: Boolean,
    valuesChanged: Boolean,
    hasErrors: Boolean
): Boolean = !saving && formReady && (runtimeSwitched || valuesChanged) && !hasErrors

internal data class MembersRuntimeFormV2SwitchConfirmation(
    val title: StringDesc,
    val message: StringDesc,
    val confirmLabel: StringDesc
)

/** Web `switchRuntime{Active,Inactive}Message`: only the from/to runtimes, no model copy. */
internal fun membersRuntimeFormV2SwitchConfirmation(
    fromRuntimeId: String,
    toRuntimeId: String,
    activeAgent: Boolean
): MembersRuntimeFormV2SwitchConfirmation {
    val from = getRuntimeDisplayName(fromRuntimeId)
    val to = getRuntimeDisplayName(toRuntimeId)
    return MembersRuntimeFormV2SwitchConfirmation(
        title = Res.string.member_action_switch_runtime_config.desc(),
        message =
            if (activeAgent) {
                Res.string.member_runtime_switch_active_message.format(listOf(from, to))
            } else {
                Res.string.member_runtime_switch_inactive_message.format(listOf(from, to))
            },
        confirmLabel =
            if (activeAgent) {
                Res.string.member_action_reset_runtime_session.desc()
            } else {
                Res.string.member_action_save_runtime_change.desc()
            }
    )
}
