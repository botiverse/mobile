package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import build.raft.app.components.SlockBrutalActionButton
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
import build.raft.app.resources.MR
import com.tencent.kuikly.compose.foundation.clickable
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.Spacer
import com.tencent.kuikly.compose.foundation.layout.fillMaxHeight
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.lazy.LazyColumn
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.tmm.kmmresource.compose.stringResource

/**
 * Edit an agent's runtime settings through runtime form v2: the same form create
 * uses, prefilled by the server (`GET /agents/:id/runtime-form`). writeOnly fields
 * start blank, and blank means "keep the stored value". Changing the runtime
 * itself stays in the v1 editor ([onChangeRuntime]); any load failure hands back
 * to v1 through [onLoadFailed].
 */
@Composable
internal fun MembersAgentRuntimeFormV2Dialog(
    access: RuntimeFormV2Access,
    agentId: MemberId,
    machineId: String,
    saving: Boolean,
    error: String?,
    errorFieldKeys: Set<String>,
    onDismiss: () -> Unit,
    onLoadFailed: () -> Unit,
    onChangeRuntime: () -> Unit,
    onSave: (RuntimeFormV2SubmitBody) -> Unit
) {
    var loaded by remember(access, agentId, machineId) { mutableStateOf<RuntimeFormV2Loaded?>(null) }
    LaunchedEffect(access, agentId, machineId) {
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
        rememberRuntimeFormV2SourceRefresh(access, machineId, form) { updated, sourceId ->
            values = runtimeFormV2ValuesAfterSourceReload(updated.form, updated.sources, values, sourceId)
        }
    val errors = current?.let { validateRuntimeFormV2(it.form, it.sources, values, editing = true) }.orEmpty()
    val changed = form != null && values != initialValues

    SlockUnifiedDialog(
        title = stringResource(MR.strings.member_dialog_edit_runtime_config).uppercase(),
        dismissEnabled = !saving,
        keyboardAvoidanceEnabled = true,
        onDismiss = onDismiss
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
                item(key = "runtime-form-v2") {
                    if (form == null) {
                        Text(
                            text = stringResource(MR.strings.agent_create_state_computers_loading),
                            style = SlockType.Caption,
                            color = SlockColor.TextMutedGray
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
                            errors = errors.filterKeys { it in touched || it in errorFieldKeys },
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
                item(key = "change-runtime") {
                    Text(
                        text = stringResource(MR.strings.runtime_form_v2_change_runtime),
                        style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold),
                        color = if (saving) SlockColor.TextMutedGray else SlockColor.TextBlack,
                        modifier = Modifier.clickable(enabled = !saving) { onChangeRuntime() }.padding(vertical = 4.dp)
                    )
                }
                item(key = "restart-hint") {
                    Text(
                        text = stringResource(MR.strings.member_hint_runtime_restart),
                        style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 16.sp),
                        color = SlockColor.TextMutedGray
                    )
                }
                error?.takeIf { it.isNotBlank() }?.let { message ->
                    item(key = "save-error") {
                        Text(
                            text = message,
                            style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(color = SlockColor.AccentRed),
                            color = SlockColor.AccentRed
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(Modifier.weight(1f))
                SlockBrutalActionButton(
                    label = stringResource(MR.strings.common_cancel),
                    enabled = !saving,
                    backgroundColor = SlockColor.SurfaceWhite,
                    onClick = onDismiss
                )
                SlockBrutalActionButton(
                    label =
                        if (saving) stringResource(MR.strings.member_action_saving)
                        else stringResource(MR.strings.member_action_save_runtime_config),
                    enabled = !saving && form != null && changed && errors.isEmpty(),
                    backgroundColor = SlockColor.AccentPink,
                    minWidth = 166.dp,
                    onClick = {
                        val submitting = current ?: form ?: return@SlockBrutalActionButton
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
