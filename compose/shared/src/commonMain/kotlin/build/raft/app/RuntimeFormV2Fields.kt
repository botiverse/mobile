package build.raft.app

import build.raft.app.resources.*
import build.raft.designsystem.components.RaftCombobox
import build.raft.designsystem.components.RaftComboboxOption
import build.raft.designsystem.components.RaftFieldLabel
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import build.raft.app.components.RaftBrutalSelectField
import build.raft.app.components.RaftBrutalSelectOption
import build.raft.app.components.RaftBrutalTextField
import build.raft.app.components.RaftAppCheckbox
import build.raft.app.components.RaftLinkButton
import build.raft.app.data.DataResult
import build.raft.app.data.runtimeform.v2.RuntimeFormV2Access
import build.raft.app.data.runtimeform.v2.RuntimeFormV2Choices
import build.raft.app.data.runtimeform.v2.RuntimeFormV2ErrorCode
import build.raft.app.data.runtimeform.v2.RuntimeFormV2Field
import build.raft.app.data.runtimeform.v2.RuntimeFormV2FieldKind
import build.raft.app.data.runtimeform.v2.RuntimeFormV2Loaded
import build.raft.app.data.runtimeform.v2.RuntimeFormV2Source
import build.raft.app.data.runtimeform.v2.RuntimeFormV2SourceStatus
import build.raft.app.data.runtimeform.v2.RuntimeFormV2Values
import build.raft.app.data.runtimeform.v2.booleanValue
import build.raft.app.data.runtimeform.v2.isFormRevisionStale
import build.raft.app.data.runtimeform.v2.isRuntimeFormV2FieldVisible
import build.raft.app.data.runtimeform.v2.runtimeFormV2Choices
import build.raft.app.data.runtimeform.v2.runtimeFormV2SelectedConnection
import build.raft.app.data.runtimeform.v2.runtimeFormV2SourceStatus
import build.raft.app.data.runtimeform.v2.stringMapValue
import build.raft.app.data.runtimeform.v2.text
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Sentinel select id for "no value", which a derived select offers as the runtime default. */
private const val RUNTIME_FORM_V2_DEFAULT_OPTION_ID = "__runtime_form_v2_default__"

/** Select id prefix for a group heading (`provider.saved_connection`: saved / direct). */
private const val RUNTIME_FORM_V2_GROUP_OPTION_PREFIX = "__runtime_form_v2_group__"

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
    enabled: Boolean,
    sourceRefresh: RuntimeFormV2SourceRefresh? = null
) {
    val focusManager = LocalFocusManager.current
    val visible = loaded.form.fields.filter {
        it.kind != RuntimeFormV2FieldKind.Unsupported || it.required
    }.filter { isRuntimeFormV2FieldVisible(it, values, loaded.sources) }
    // Hidden choices (a derived select with nothing to pick) are not drawn.
    val drawn = visible.filterNot { field ->
        field.kind == RuntimeFormV2FieldKind.DerivedSelect &&
            (runtimeFormV2Choices(field, loaded.sources, values) as? RuntimeFormV2Choices.Select)?.options.isNullOrEmpty()
    }
    val primary = drawn.filterNot { it.advanced }
    val advanced = drawn.filter { it.advanced }
    // `provider.saved_connection`: while a field holds a saved connection, the fields it owns are the
    // connection's: disabled, not required, and (by runtimeFormV2Submission) never submitted.
    val connection = loaded.form.fields.firstNotNullOfOrNull { runtimeFormV2SelectedConnection(it, loaded.sources, values) }
    val connectionOwnedHint = stringResource(Res.string.runtime_form_v2_connection_owned, connection?.label.orEmpty())
    val managedHint = connectionOwnedHint.takeIf { connection != null }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (loaded.form.blockingFieldKeys.isNotEmpty()) {
            Text(
                text = stringResource(Res.string.runtime_form_v2_update_required_blocking),
                style = RaftType.Caption,
                color = RaftColor.AccentRed
            )
        }
        primary.forEach { field ->
            key(field.key) {
                RuntimeFormV2FieldRow(field, loaded, values, onChange, runtimeFormV2ErrorText(field, errors, serverErrorKeys, serverMessage), runtimeFormV2FieldEditing(field, errors, editing), enabled, sourceRefresh, managedHint) {
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
                        RuntimeFormV2FieldRow(field, loaded, values, onChange, runtimeFormV2ErrorText(field, errors, serverErrorKeys, serverMessage), runtimeFormV2FieldEditing(field, errors, editing), enabled, sourceRefresh, managedHint) {
                            focusManager.clearFocus(force = true)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Errors to show: those of fields the user touched or the server pointed at, plus the ones that block
 * the save before the field itself is touched: an unavailable saved connection (`provider.saved_connection`)
 * and a blank secret whose bound fields changed (`writeOnlyKeepRequiresUnchanged`; slock web shows it
 * without a submit attempt).
 */
internal fun runtimeFormV2ShownErrors(
    errors: Map<String, RuntimeFormV2ErrorCode>,
    shownKeys: Set<String>
): Map<String, RuntimeFormV2ErrorCode> =
    errors.filter { (key, code) ->
        key in shownKeys ||
            code == RuntimeFormV2ErrorCode.ConnectionUnavailable ||
            code == RuntimeFormV2ErrorCode.SecretReentryRequired
    }

/**
 * A secret that cannot keep the stored value is edited like on create: required, without the
 * "leave blank to keep" hint (slock web RuntimeConfigFields `editing && errors[key] !== "secret_reentry_required"`).
 */
private fun runtimeFormV2FieldEditing(
    field: RuntimeFormV2Field,
    errors: Map<String, RuntimeFormV2ErrorCode>,
    editing: Boolean
): Boolean = editing && errors[field.key] != RuntimeFormV2ErrorCode.SecretReentryRequired

@Composable
private fun runtimeFormV2ErrorText(
    field: RuntimeFormV2Field,
    errors: Map<String, RuntimeFormV2ErrorCode>,
    serverErrorKeys: Set<String>,
    serverMessage: String?
): String? =
    if (field.key in serverErrorKeys) {
        serverMessage?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.runtime_form_v2_error_rejected)
    } else {
        when (errors[field.key]) {
            RuntimeFormV2ErrorCode.Required -> stringResource(Res.string.runtime_form_v2_error_required)
            RuntimeFormV2ErrorCode.InvalidUrl -> stringResource(Res.string.runtime_form_v2_error_invalid_url)
            RuntimeFormV2ErrorCode.NotAChoice -> stringResource(Res.string.runtime_form_v2_error_not_a_choice)
            RuntimeFormV2ErrorCode.UnsupportedRequired -> stringResource(Res.string.runtime_form_v2_update_required)
            // The field's source status line already says why, with its retry.
            RuntimeFormV2ErrorCode.SourceUnavailable -> null
            RuntimeFormV2ErrorCode.ConnectionUnavailable -> stringResource(Res.string.runtime_form_v2_saved_provider_unavailable)
            RuntimeFormV2ErrorCode.SecretReentryRequired -> stringResource(Res.string.runtime_form_v2_error_secret_reentry_required)
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
    sourceRefresh: RuntimeFormV2SourceRefresh?,
    managedHint: String?,
    clearFocus: () -> Unit
) {
    val current = values.text(field.key)
    // Owned by the selected saved connection: shown disabled with "Provided by …" instead of its own hint.
    val connectionOwned = managedHint != null && field.connectionOwned
    val fieldEnabled = enabled && !connectionOwned
    val required = field.required && !connectionOwned
    val hint = if (connectionOwned) managedHint else field.hint
    when (field.kind) {
        RuntimeFormV2FieldKind.Text, RuntimeFormV2FieldKind.Url ->
            RaftBrutalTextField(
                label = field.label,
                value = current,
                onValueChange = { onChange(field.key, JsonPrimitive(it)) },
                placeholder = field.placeholder.orEmpty(),
                required = required,
                hint = hint,
                error = error,
                enabled = fieldEnabled
            )
        RuntimeFormV2FieldKind.Secret -> {
            // Masked input keeps its own TextFieldValue so the editing token survives recomposition.
            var secret by remember(field.key) { mutableStateOf(TextFieldValue(current)) }
            if (secret.text != current) secret = TextFieldValue(current)
            RaftBrutalTextField(
                label = field.label,
                value = secret,
                onValueChange = {
                    secret = it
                    onChange(field.key, JsonPrimitive(it.text))
                },
                placeholder = field.placeholder.orEmpty(),
                required = required && !editing,
                hint = if (editing && !connectionOwned) stringResource(Res.string.runtime_form_v2_secret_keep_hint) else hint,
                error = error,
                enabled = fieldEnabled,
                password = true
            )
        }
        RuntimeFormV2FieldKind.Boolean ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RaftAppCheckbox(
                    checked = values[field.key].booleanValue(),
                    onCheckedChange = { onChange(field.key, JsonPrimitive(it)) },
                    enabled = fieldEnabled
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(field.label, style = RaftType.Caption, color = RaftColor.TextBlack)
                    hint?.let { Text(it, style = RaftType.Caption, color = RaftColor.TextMutedGray) }
                    error?.let { Text(it, style = RaftType.Caption, color = RaftColor.AccentRed) }
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
                    enabled = fieldEnabled,
                    label = field.label,
                    required = required
                )
                hint?.let { Text(it, style = RaftType.Caption, color = RaftColor.TextMutedGray) }
                error?.let { Text(it, style = RaftType.Caption, color = RaftColor.AccentRed) }
            }
        }
        RuntimeFormV2FieldKind.Select,
        RuntimeFormV2FieldKind.DependentSelect,
        RuntimeFormV2FieldKind.DerivedSelect ->
            when (val choices = runtimeFormV2Choices(field, loaded.sources, values)) {
                RuntimeFormV2Choices.FreeText ->
                    RaftBrutalTextField(
                        label = field.label,
                        value = current,
                        onValueChange = { onChange(field.key, JsonPrimitive(it)) },
                        placeholder = field.placeholder.orEmpty(),
                        required = required,
                        hint = hint,
                        error = error,
                        enabled = fieldEnabled
                    )
                is RuntimeFormV2Choices.Select -> {
                    val defaultLabel = stringResource(Res.string.runtime_form_v2_option_default)
                    val savedGroupLabel = stringResource(Res.string.runtime_form_v2_saved_providers)
                    val directGroupLabel = stringResource(Res.string.runtime_form_v2_direct_providers)
                    val noMatches = stringResource(Res.string.common_no_matches)
                    val searchLabel = stringResource(Res.string.search_filter_search)
                    val useCustomLabel = stringResource(Res.string.runtime_form_v2_use_custom, "%1\$s")
                    // `provider.saved_connection`: headings only when the list mixes saved and direct choices.
                    val grouped = choices.options.mapNotNull { it.group }.distinct().size > 1
                    val unlisted = current.isNotEmpty() && choices.options.none { it.value == current }
                    val options = buildList {
                        if (choices.allowEmpty) add(RaftBrutalSelectOption(RUNTIME_FORM_V2_DEFAULT_OPTION_ID, defaultLabel))
                        var lastGroup: String? = null
                        choices.options.forEach { option ->
                            val group = option.group
                            if (grouped && group != null && group != lastGroup) {
                                val heading = if (group == "saved") savedGroupLabel else directGroupLabel
                                add(RaftBrutalSelectOption("$RUNTIME_FORM_V2_GROUP_OPTION_PREFIX$group", heading, header = true))
                            }
                            lastGroup = group
                            // A bound connection that is unavailable stays visible but cannot be picked again.
                            add(RaftBrutalSelectOption(option.value, option.label, enabled = !option.unavailable))
                        }
                        if (unlisted) {
                            // A stored value the server no longer offers stays visible, but cannot be re-picked.
                            add(RaftBrutalSelectOption(current, current, enabled = false))
                        }
                    }
                    val selectedId = when {
                        current.isEmpty() && choices.allowEmpty -> RUNTIME_FORM_V2_DEFAULT_OPTION_ID
                        else -> current
                    }
                    // `option_source.status`: a source that is not live says why.
                    val sourceStatus = runtimeFormV2SourceStatus(field, loaded.sources)
                    // Re-probe: selects that read a machine option source can ask for it again, like v1's
                    // refresh next to the model picker. Derived selects follow their parent's source. A source
                    // whose status says retrying cannot help (missing_config, unsupported) offers no re-probe.
                    val refreshableSource = field.optionSourceId
                        ?.takeIf { field.kind != RuntimeFormV2FieldKind.DerivedSelect && sourceRefresh != null }
                        ?.takeIf { sourceStatus == null || sourceStatus.retryable }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (refreshableSource != null && sourceRefresh != null) {
                            CreateAgentSectionLabelWithAction(
                                label = field.label,
                                required = required,
                                actionIcon = RaftLucideIconName.RefreshCw,
                                actionEnabled = fieldEnabled && refreshableSource !in sourceRefresh.refreshing,
                                loading = refreshableSource in sourceRefresh.refreshing,
                                onAction = {
                                    clearFocus()
                                    sourceRefresh.onRefresh(refreshableSource)
                                }
                            )
                        } else {
                            RaftFieldLabel(text = field.label, required = required)
                        }
                        if (choices.allowCustom) {
                            // `select.custom_value` is raft-ui's ComboboxSelect with `create`: the list
                            // with search, and typed text that is not a choice offered as "Use “…”".
                            // A stored value the list does not offer shows as itself.
                            RaftCombobox(
                                value = current.takeIf { it.isNotEmpty() || choices.allowEmpty },
                                options = buildList {
                                    if (choices.allowEmpty) add(RaftComboboxOption("", defaultLabel))
                                    choices.options.forEach { option ->
                                        add(
                                            RaftComboboxOption(
                                                value = option.value,
                                                label = option.label,
                                                group = option.group?.takeIf { grouped }?.let { if (it == "saved") savedGroupLabel else directGroupLabel },
                                                enabled = !option.unavailable
                                            )
                                        )
                                    }
                                },
                                onValueChange = { next ->
                                    clearFocus()
                                    onChange(field.key, JsonPrimitive(next))
                                },
                                emptyText = { noMatches },
                                placeholder = field.placeholder ?: field.label,
                                title = field.label,
                                searchPlaceholder = searchLabel,
                                create = { typed ->
                                    clearFocus()
                                    onChange(field.key, JsonPrimitive(typed))
                                },
                                createLabel = { typed -> useCustomLabel.replace("%1\$s", typed) },
                                enabled = fieldEnabled,
                                label = field.label
                            )
                        } else {
                            RaftBrutalSelectField(
                                options = options,
                                selectedId = selectedId,
                                selectedLabel = options.firstOrNull { it.id == selectedId }?.label.orEmpty(),
                                placeholder = field.placeholder ?: field.label,
                                enabled = fieldEnabled,
                                rowHeight = 36.dp,
                                onSelect = { option ->
                                    clearFocus()
                                    val next = if (option.id == RUNTIME_FORM_V2_DEFAULT_OPTION_ID) "" else option.id
                                    onChange(field.key, JsonPrimitive(next))
                                }
                            )
                        }
                        // `choice.labels`: the selected choice's description (e.g. what a reasoning level does).
                        choices.options.firstOrNull { it.value == current }?.description?.let {
                            Text(it, style = RaftType.Caption, color = RaftColor.TextMutedGray)
                        }
                        hint?.let { Text(it, style = RaftType.Caption, color = RaftColor.TextMutedGray) }
                        sourceStatus?.let { status ->
                            RuntimeFormV2SourceStatusLine(
                                status = status,
                                retrying = refreshableSource != null && sourceRefresh != null && refreshableSource in sourceRefresh.refreshing,
                                onRetry = if (refreshableSource != null && sourceRefresh != null && enabled) {
                                    {
                                        clearFocus()
                                        sourceRefresh.onRefresh(refreshableSource)
                                    }
                                } else {
                                    null
                                }
                            )
                        }
                        if (refreshableSource != null && sourceRefresh != null && refreshableSource in sourceRefresh.failed) {
                            Text(
                                stringResource(Res.string.runtime_form_v2_source_refresh_failed),
                                style = RaftType.Caption,
                                color = RaftColor.AccentRed
                            )
                        }
                        error?.let { Text(it, style = RaftType.Caption, color = RaftColor.AccentRed) }
                    }
                }
                null -> Unit
            }
        RuntimeFormV2FieldKind.Unsupported ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                RaftFieldLabel(text = field.label, required = field.required)
                Text(
                    text = stringResource(Res.string.runtime_form_v2_update_required),
                    style = RaftType.Caption,
                    color = RaftColor.AccentRed
                )
            }
    }
}

/** Why a source is not live (`option_source.status`); an unknown reason gets the generic line. */
@Composable
private fun RuntimeFormV2SourceStatusLine(status: RuntimeFormV2SourceStatus, retrying: Boolean, onRetry: (() -> Unit)?) {
    val reason = when (status.reason) {
        "probe_timeout" -> stringResource(Res.string.runtime_form_v2_source_status_probe_timeout)
        "probe_failed" -> stringResource(Res.string.runtime_form_v2_source_status_probe_failed)
        "missing_config" -> stringResource(Res.string.runtime_form_v2_source_status_missing_config)
        "no_models" -> stringResource(Res.string.runtime_form_v2_source_status_no_models)
        "unsupported" -> stringResource(Res.string.runtime_form_v2_source_status_unsupported)
        "machine_offline" -> stringResource(Res.string.runtime_form_v2_source_status_machine_offline)
        else -> stringResource(Res.string.runtime_form_v2_source_status_unknown)
    }
    val text = if (status.unavailable) reason else "$reason ${stringResource(Res.string.runtime_form_v2_source_status_fallback)}"
    Text(text, style = RaftType.Caption, color = if (status.unavailable) RaftColor.AccentRed else RaftColor.TextMutedGray)
    // Retry only when the server says it may help.
    if (status.retryable && onRetry != null) {
        RaftLinkButton(
            text = stringResource(Res.string.common_retry),
            onClick = onRetry,
            enabled = !retrying,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            color = RaftColor.TextBlack
        )
    }
}

/**
 * Re-probe wiring for option sources: which sources are being re-requested, which last
 * attempt failed (the previous options stay on screen), and how to ask again.
 */
internal data class RuntimeFormV2SourceRefresh(
    val refreshing: Set<String>,
    val failed: Set<String>,
    val onRefresh: (sourceId: String) -> Unit
)

/**
 * Holds re-probed sources as overrides on top of [loaded], so a refresh never replaces the
 * loaded form (dialogs key their values on it). Returns the form to render/validate/submit with
 * and the refresh wiring; the wiring is null when there is nothing to refresh against.
 * [onFormStale]: the server answered `form_revision_stale` (`provider.saved_connection`); the
 * caller reloads the whole form, as options are never merged into a stale one.
 */
@Composable
internal fun rememberRuntimeFormV2SourceRefresh(
    access: RuntimeFormV2Access?,
    machineId: String?,
    loaded: RuntimeFormV2Loaded?,
    onFormStale: () -> Unit,
    onSourceReloaded: (updated: RuntimeFormV2Loaded, sourceId: String) -> Unit
): Pair<RuntimeFormV2Loaded?, RuntimeFormV2SourceRefresh?> {
    var overrides by remember(loaded) { mutableStateOf(emptyMap<String, RuntimeFormV2Source>()) }
    var refreshing by remember(loaded) { mutableStateOf(emptySet<String>()) }
    var failed by remember(loaded) { mutableStateOf(emptySet<String>()) }
    val scope = rememberCoroutineScope()
    if (loaded == null || access == null || machineId.isNullOrBlank()) return loaded to null
    val effective = if (overrides.isEmpty()) loaded else loaded.copy(sources = loaded.sources + overrides)
    val refresh =
        RuntimeFormV2SourceRefresh(refreshing = refreshing, failed = failed) { sourceId ->
            if (sourceId !in refreshing) {
                refreshing = refreshing + sourceId
                failed = failed - sourceId
                scope.launch {
                    val result = access.loader.reloadSource(
                        access.serverId,
                        machineId,
                        loaded.form.runtimeId,
                        sourceId,
                        loaded.form.revision
                    )
                    when {
                        result is DataResult.Content -> {
                            overrides = overrides + (sourceId to result.data)
                            onSourceReloaded(loaded.copy(sources = loaded.sources + overrides), sourceId)
                        }
                        result.isFormRevisionStale() -> onFormStale()
                        else -> failed = failed + sourceId
                    }
                    refreshing = refreshing - sourceId
                }
            }
        }
    return effective to refresh
}

/** Runtime picker plus the v2 form body, in the create dialog's runtime slot. */
@Composable
internal fun RuntimeFormV2RuntimeSection(
    runtime: String,
    onRuntimeChange: (String) -> Unit,
    machineRuntimeIds: List<String>,
    runtimeAvailabilityError: String?,
    enabled: Boolean,
    /** The computer whose usage gives each runtime its quota line; null: no lines. */
    quotaMachine: RuntimeQuotaMachine? = null,
    content: @Composable () -> Unit
) {
    val focusManager = LocalFocusManager.current
    val runtimeOptions = createAgentRuntimeDropdownItems(
        machineRuntimeIds = machineRuntimeIds,
        allowStaticFallback = false,
        currentRuntimeId = runtime,
        comingSoonSuffix = stringResource(Res.string.agent_create_suffix_coming_soon),
        notInstalledSuffix = stringResource(Res.string.agent_create_suffix_not_installed)
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        RaftFieldLabel(text = stringResource(Res.string.agent_create_label_runtime))
        RuntimeSelectChoice(
            runtime = runtime,
            runtimeOptions = runtimeOptions,
            quotaMachine = quotaMachine,
            enabled = enabled && runtimeAvailabilityError == null,
            emptyLabel = null,
            onSelect = { id ->
                focusManager.clearFocus(force = true)
                onRuntimeChange(id)
            }
        )
        content()
    }
}
