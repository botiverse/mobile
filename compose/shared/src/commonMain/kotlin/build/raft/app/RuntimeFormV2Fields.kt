package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import build.raft.app.components.SlockBrutalSectionLabel
import build.raft.app.components.SlockBrutalSelectField
import build.raft.app.components.SlockBrutalSelectOption
import build.raft.app.components.SlockBrutalTextField
import build.raft.app.components.SlockCheckbox
import build.raft.app.data.runtimeform.v2.RuntimeFormV2Choices
import build.raft.app.data.runtimeform.v2.RuntimeFormV2ErrorCode
import build.raft.app.data.runtimeform.v2.RuntimeFormV2Field
import build.raft.app.data.runtimeform.v2.RuntimeFormV2FieldKind
import build.raft.app.data.runtimeform.v2.RuntimeFormV2Loaded
import build.raft.app.data.runtimeform.v2.RuntimeFormV2Values
import build.raft.app.data.runtimeform.v2.booleanValue
import build.raft.app.data.runtimeform.v2.isRuntimeFormV2FieldVisible
import build.raft.app.data.runtimeform.v2.runtimeFormV2Choices
import build.raft.app.data.runtimeform.v2.stringMapValue
import build.raft.app.data.runtimeform.v2.text
import build.raft.app.resources.MR
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.platform.LocalFocusManager
import com.tencent.kuikly.compose.ui.text.input.TextFieldValue
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.tmm.kmmresource.compose.stringResource
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Sentinel select id for "no value", which a derived select offers as the runtime default. */
private const val RUNTIME_FORM_V2_DEFAULT_OPTION_ID = "__runtime_form_v2_default__"

/**
 * Runtime form v2 renderer shared by create and edit. Every field is drawn by
 * its kind (see [RuntimeFormV2FieldKind]); nothing here knows a field or runtime
 * by name. Order, the Advanced group, visibility and copy come from the form.
 *
 * [errors] carries local validation codes; [serverErrorKeys] marks fields a
 * server response pointed at (`/formValues/<field>`), shown with [serverMessage].
 */
@Composable
internal fun RuntimeFormV2Fields(
    loaded: RuntimeFormV2Loaded,
    values: RuntimeFormV2Values,
    onChange: (key: String, value: JsonElement) -> Unit,
    errors: Map<String, RuntimeFormV2ErrorCode>,
    serverErrorKeys: Set<String>,
    serverMessage: String?,
    editing: Boolean,
    advancedOpen: Boolean,
    onAdvancedOpenChange: (Boolean) -> Unit,
    enabled: Boolean
) {
    val focusManager = LocalFocusManager.current
    val visible = loaded.form.fields.filter {
        it.kind != RuntimeFormV2FieldKind.Unsupported || it.required
    }.filter { isRuntimeFormV2FieldVisible(it, values) }
    // Hidden choices (a derived select with nothing to pick) are not drawn.
    val drawn = visible.filterNot { field ->
        field.kind == RuntimeFormV2FieldKind.DerivedSelect &&
            (runtimeFormV2Choices(field, loaded.sources, values) as? RuntimeFormV2Choices.Select)?.options.isNullOrEmpty()
    }
    val primary = drawn.filterNot { it.advanced }
    val advanced = drawn.filter { it.advanced }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (loaded.form.blockingFieldKeys.isNotEmpty()) {
            Text(
                text = stringResource(MR.strings.runtime_form_v2_update_required_blocking),
                style = SlockType.Caption,
                color = SlockColor.AccentRed
            )
        }
        primary.forEach { field ->
            key(field.key) {
                RuntimeFormV2FieldRow(field, loaded, values, onChange, runtimeFormV2ErrorText(field, errors, serverErrorKeys, serverMessage), editing, enabled) {
                    focusManager.clearFocus(force = true)
                }
            }
        }
        if (advanced.isNotEmpty()) {
            AdvancedToggleButton(advancedOpen) {
                focusManager.clearFocus(force = true)
                onAdvancedOpenChange(!advancedOpen)
            }
            // A field with an error opens the group so the error is never hidden.
            val forcedOpen = advanced.any { it.key in errors || it.key in serverErrorKeys }
            if (advancedOpen || forcedOpen) {
                advanced.forEach { field ->
                    key(field.key) {
                        RuntimeFormV2FieldRow(field, loaded, values, onChange, runtimeFormV2ErrorText(field, errors, serverErrorKeys, serverMessage), editing, enabled) {
                            focusManager.clearFocus(force = true)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun runtimeFormV2ErrorText(
    field: RuntimeFormV2Field,
    errors: Map<String, RuntimeFormV2ErrorCode>,
    serverErrorKeys: Set<String>,
    serverMessage: String?
): String? =
    if (field.key in serverErrorKeys) {
        serverMessage?.takeIf { it.isNotBlank() } ?: stringResource(MR.strings.runtime_form_v2_error_rejected)
    } else {
        when (errors[field.key]) {
            RuntimeFormV2ErrorCode.Required -> stringResource(MR.strings.runtime_form_v2_error_required)
            RuntimeFormV2ErrorCode.InvalidUrl -> stringResource(MR.strings.runtime_form_v2_error_invalid_url)
            RuntimeFormV2ErrorCode.NotAChoice -> stringResource(MR.strings.runtime_form_v2_error_not_a_choice)
            RuntimeFormV2ErrorCode.UnsupportedRequired -> stringResource(MR.strings.runtime_form_v2_update_required)
            null -> null
        }
    }

@Composable
private fun RuntimeFormV2FieldRow(
    field: RuntimeFormV2Field,
    loaded: RuntimeFormV2Loaded,
    values: RuntimeFormV2Values,
    onChange: (key: String, value: JsonElement) -> Unit,
    error: String?,
    editing: Boolean,
    enabled: Boolean,
    clearFocus: () -> Unit
) {
    val current = values.text(field.key)
    when (field.kind) {
        RuntimeFormV2FieldKind.Text, RuntimeFormV2FieldKind.Url ->
            SlockBrutalTextField(
                label = field.label,
                value = current,
                onValueChange = { onChange(field.key, JsonPrimitive(it)) },
                placeholder = field.placeholder.orEmpty(),
                required = field.required,
                hint = field.hint,
                error = error,
                enabled = enabled
            )
        RuntimeFormV2FieldKind.Secret -> {
            // Masked input keeps its own TextFieldValue so the editing token survives recomposition.
            var secret by remember(field.key) { mutableStateOf(TextFieldValue(current)) }
            if (secret.text != current) secret = TextFieldValue(current)
            SlockBrutalTextField(
                label = field.label,
                value = secret,
                onValueChange = {
                    secret = it
                    onChange(field.key, JsonPrimitive(it.text))
                },
                placeholder = field.placeholder.orEmpty(),
                required = field.required && !editing,
                hint = if (editing) stringResource(MR.strings.runtime_form_v2_secret_keep_hint) else field.hint,
                error = error,
                enabled = enabled,
                password = true
            )
        }
        RuntimeFormV2FieldKind.Boolean ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SlockCheckbox(
                    checked = values[field.key].booleanValue(),
                    onCheckedChange = { onChange(field.key, JsonPrimitive(it)) },
                    enabled = enabled
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(field.label, style = SlockType.Caption, color = SlockColor.TextBlack)
                    field.hint?.let { Text(it, style = SlockType.Caption, color = SlockColor.TextMutedGray) }
                    error?.let { Text(it, style = SlockType.Caption, color = SlockColor.AccentRed) }
                }
            }
        RuntimeFormV2FieldKind.StringMap -> {
            // Half-typed rows (blank key) stay on screen but never reach the value.
            val external = values[field.key].stringMapValue()
            var entries by remember(field.key) { mutableStateOf(external.toList()) }
            // Follow a value set from outside (reset, prefill) unless it is just our own half-typed rows.
            val ownValue = entries.mapNotNull { (key, value) -> key.trim().takeIf { it.isNotEmpty() }?.let { it to value } }.toMap()
            if (ownValue != external) entries = external.toList()
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                EnvVarsEditor(
                    entries = entries,
                    onChange = { next ->
                        entries = next
                        val map = linkedMapOf<String, JsonElement>()
                        next.forEach { (key, value) -> key.trim().takeIf { it.isNotEmpty() }?.let { map[it] = JsonPrimitive(value) } }
                        onChange(field.key, JsonObject(map))
                    },
                    enabled = enabled,
                    label = field.label,
                    required = field.required
                )
                field.hint?.let { Text(it, style = SlockType.Caption, color = SlockColor.TextMutedGray) }
                error?.let { Text(it, style = SlockType.Caption, color = SlockColor.AccentRed) }
            }
        }
        RuntimeFormV2FieldKind.Select,
        RuntimeFormV2FieldKind.DependentSelect,
        RuntimeFormV2FieldKind.DerivedSelect ->
            when (val choices = runtimeFormV2Choices(field, loaded.sources, values)) {
                RuntimeFormV2Choices.FreeText ->
                    SlockBrutalTextField(
                        label = field.label,
                        value = current,
                        onValueChange = { onChange(field.key, JsonPrimitive(it)) },
                        placeholder = field.placeholder.orEmpty(),
                        required = field.required,
                        hint = field.hint,
                        error = error,
                        enabled = enabled
                    )
                is RuntimeFormV2Choices.Select -> {
                    val defaultLabel = stringResource(MR.strings.runtime_form_v2_option_default)
                    val options = buildList {
                        if (choices.allowEmpty) add(SlockBrutalSelectOption(RUNTIME_FORM_V2_DEFAULT_OPTION_ID, defaultLabel))
                        choices.options.forEach { add(SlockBrutalSelectOption(it.value, it.label)) }
                        // A stored value the server no longer offers stays visible, but cannot be re-picked.
                        if (current.isNotEmpty() && choices.options.none { it.value == current }) {
                            add(SlockBrutalSelectOption(current, current, enabled = false))
                        }
                    }
                    val selectedId = if (current.isEmpty() && choices.allowEmpty) RUNTIME_FORM_V2_DEFAULT_OPTION_ID else current
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        SlockBrutalSectionLabel(label = field.label, required = field.required)
                        SlockBrutalSelectField(
                            options = options,
                            selectedId = selectedId,
                            selectedLabel = options.firstOrNull { it.id == selectedId }?.label.orEmpty(),
                            placeholder = field.placeholder ?: field.label,
                            enabled = enabled,
                            rowHeight = 36.dp,
                            onSelect = { option ->
                                clearFocus()
                                val next = if (option.id == RUNTIME_FORM_V2_DEFAULT_OPTION_ID) "" else option.id
                                onChange(field.key, JsonPrimitive(next))
                            }
                        )
                        field.hint?.let { Text(it, style = SlockType.Caption, color = SlockColor.TextMutedGray) }
                        error?.let { Text(it, style = SlockType.Caption, color = SlockColor.AccentRed) }
                    }
                }
                null -> Unit
            }
        RuntimeFormV2FieldKind.Unsupported ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SlockBrutalSectionLabel(label = field.label, required = field.required)
                Text(
                    text = stringResource(MR.strings.runtime_form_v2_update_required),
                    style = SlockType.Caption,
                    color = SlockColor.AccentRed
                )
            }
    }
}

/** Runtime picker plus the v2 form body, in the create dialog's runtime slot. */
@Composable
internal fun RuntimeFormV2RuntimeSection(
    runtime: String,
    onRuntimeChange: (String) -> Unit,
    machineRuntimeIds: List<String>,
    runtimeAvailabilityError: String?,
    enabled: Boolean,
    content: @Composable () -> Unit
) {
    val focusManager = LocalFocusManager.current
    val runtimeOptions = createAgentRuntimeDropdownItems(
        machineRuntimeIds = machineRuntimeIds,
        allowStaticFallback = false,
        currentRuntimeId = runtime,
        comingSoonSuffix = stringResource(MR.strings.agent_create_suffix_coming_soon),
        notInstalledSuffix = stringResource(MR.strings.agent_create_suffix_not_installed)
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SlockBrutalSectionLabel(label = stringResource(MR.strings.agent_create_label_runtime))
        SlockBrutalSelectField(
            options = runtimeOptions.toBrutalSelectOptions(),
            selectedId = runtime,
            selectedLabel = runtimeOptions.firstOrNull { it.id == runtime }?.label.orEmpty(),
            placeholder = stringResource(MR.strings.agent_create_placeholder_select_runtime),
            enabled = enabled && runtimeAvailabilityError == null,
            rowHeight = 36.dp,
            onSelect = { option ->
                focusManager.clearFocus(force = true)
                onRuntimeChange(option.id)
            }
        )
        content()
    }
}
