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
import build.raft.app.data.runtimeform.v2.RuntimeFormV2Access
import build.raft.app.data.runtimeform.v2.RuntimeFormV2Loaded
import build.raft.app.data.runtimeform.v2.RuntimeFormV2SubmitBody
import build.raft.app.data.runtimeform.v2.applyRuntimeFormV2Change
import build.raft.app.data.runtimeform.v2.initialRuntimeFormV2Values
import build.raft.app.data.runtimeform.v2.runtimeFormV2Submission
import build.raft.app.data.runtimeform.v2.runtimeFormV2ValuesAfterSourceReload
import build.raft.app.data.runtimeform.v2.validateRuntimeFormV2
import build.raft.app.model.MemberId
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
 * select sits at the top (options from this agent's computer, [machineRuntimeIds]);
 * picking a different runtime hands that runtime to the v1 editor through
 * [onChangeRuntime], which opens with it already selected. Any load failure hands
 * back to v1 through [onLoadFailed].
 */
@Composable
internal fun MembersAgentRuntimeFormV2Dialog(
    access: RuntimeFormV2Access,
    agentId: MemberId,
    machineId: String,
    runtimeId: String,
    machineRuntimeIds: List<String>,
    saving: Boolean,
    error: String?,
    errorFieldKeys: Set<String>,
    onDismiss: () -> Unit,
    onLoadFailed: () -> Unit,
    onChangeRuntime: (nextRuntime: String) -> Unit,
    onSave: (RuntimeFormV2SubmitBody) -> Unit
) {
    // A form that went stale mid-session (`form_revision_stale`) is reloaded whole, once; then back to v1.
    var staleReloads by remember(access, agentId, machineId) { mutableStateOf(0) }
    var loaded by remember(access, agentId, machineId, staleReloads) { mutableStateOf<RuntimeFormV2Loaded?>(null) }
    LaunchedEffect(access, agentId, machineId, staleReloads) {
        when (val result = access.loader.loadEdit(access.serverId, machineId, agentId)) {
            is DataResult.Content -> loaded = result.data
            else -> onLoadFailed()
        }
    }
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
            onFormStale = { if (staleReloads < 1) staleReloads += 1 else onLoadFailed() }
        ) { updated, sourceId ->
            values = runtimeFormV2ValuesAfterSourceReload(updated.form, updated.sources, values, sourceId)
        }
    val errors = current?.let { validateRuntimeFormV2(it.form, it.sources, values, editing = true) }.orEmpty()
    val changed = form != null && values != initialValues

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
                item(key = "runtime-form-v2") {
                    val shownRuntimeId = (current ?: form)?.form?.runtimeId ?: runtimeId
                    RuntimeFormV2RuntimeSection(
                        runtime = shownRuntimeId,
                        onRuntimeChange = { picked ->
                            membersRuntimeFormV2EditRuntimeSwitch(shownRuntimeId, picked)?.let(onChangeRuntime)
                        },
                        machineRuntimeIds = machineRuntimeIds,
                        runtimeAvailabilityError = null,
                        enabled = !saving,
                        quotaMachine = quotaMachine
                    ) {
                        if (form == null) {
                            Text(
                                text = stringResource(Res.string.agent_create_state_computers_loading),
                                style = RaftType.Caption,
                                color = RaftColor.TextMutedGray
                            )
                        } else {
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
                                editing = true,
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
                    enabled = !saving && form != null && changed && errors.isEmpty(),
                    variant = RaftButtonVariant.Primary,
                    size = RaftButtonSize.Lg,
                    onClick = {
                        val submitting = current ?: form ?: return@RaftButton
                        onSave(
                            RuntimeFormV2SubmitBody(
                                runtimeId = submitting.form.runtimeId,
                                formValues = runtimeFormV2Submission(submitting.form, values, submitting.sources)
                            )
                        )
                    }
                )
            }
        }
    }

/**
 * The runtime the v2 edit dialog hands to the v1 editor when the user picks [picked]
 * in its Runtime select, or null when [picked] is the runtime being edited (stay in v2).
 */
internal fun membersRuntimeFormV2EditRuntimeSwitch(currentRuntimeId: String, picked: String): String? =
    picked.trim().takeIf { it.isNotBlank() && it != currentRuntimeId.trim() }
