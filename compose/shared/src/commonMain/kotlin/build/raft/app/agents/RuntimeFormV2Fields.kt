package build.raft.app

import build.raft.app.resources.*
import org.jetbrains.compose.resources.StringResource
import build.raft.designsystem.components.RaftCombobox
import build.raft.designsystem.components.RaftComboboxOption
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import build.raft.app.data.BundledRuntimeCatalog
import build.raft.app.data.ServerRuntimeCatalog
import build.raft.app.data.SharedCreateAgentRuntimeOption
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import build.raft.app.components.RaftDesignSystemEditor
import build.raft.app.components.rememberRaftDesignSystemEditor
import build.raft.designsystem.components.RaftField
import build.raft.designsystem.components.RaftFieldState
import build.raft.designsystem.components.LocalRaftFieldState
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftIconButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.app.components.RaftSlotIcon
import androidx.compose.runtime.CompositionLocalProvider
import build.raft.designsystem.components.RaftTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import build.raft.designsystem.components.RaftCheckbox
import build.raft.designsystem.components.RaftCard
import build.raft.designsystem.components.RaftCardVariant
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import build.raft.designsystem.tokens.RaftStroke
import build.raft.app.data.DataResult
import build.raft.app.data.runtimeform.v2.RuntimeFormV2Access
import build.raft.app.data.runtimeform.v2.RuntimeFormV2Choice
import build.raft.app.data.runtimeform.v2.RuntimeFormV2Choices
import build.raft.app.data.runtimeform.v2.RuntimeFormV2ErrorCode
import build.raft.app.data.runtimeform.v2.RuntimeFormV2Field
import build.raft.app.data.runtimeform.v2.RuntimeFormV2FieldKind
import build.raft.app.data.runtimeform.v2.RuntimeFormV2Loaded
import build.raft.app.data.runtimeform.v2.RuntimeFormV2Source
import build.raft.app.data.runtimeform.v2.RuntimeFormV2SourceStatus
import build.raft.app.data.runtimeform.v2.RuntimeFormV2UnavailableChoice
import build.raft.app.data.runtimeform.v2.RuntimeFormV2Values
import build.raft.app.data.runtimeform.v2.booleanValue
import build.raft.app.data.runtimeform.v2.isFormRevisionStale
import build.raft.app.data.runtimeform.v2.isRuntimeFormV2FieldRequired
import build.raft.app.data.runtimeform.v2.isRuntimeFormV2FieldVisible
import build.raft.app.data.runtimeform.v2.runtimeFormV2Choices
import build.raft.app.data.runtimeform.v2.runtimeFormV2AdvancedStartsOpen
import build.raft.app.data.runtimeform.v2.RuntimeFormV2HintPart
import build.raft.app.data.runtimeform.v2.parseRuntimeFormV2Hint
import build.raft.app.data.runtimeform.v2.runtimeFormV2HintHasLinks
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.buildAnnotatedString
import build.raft.designsystem.components.appendRaftTextLink
import build.raft.designsystem.text.RaftText
import build.raft.app.data.runtimeform.v2.runtimeFormV2DependentSourceIds
import build.raft.app.data.runtimeform.v2.RuntimeFormV2EffortUnavailable
import build.raft.app.data.runtimeform.v2.runtimeFormV2EffortUnavailable
import build.raft.app.data.runtimeform.v2.runtimeFormV2SelectedConnection
import build.raft.app.data.runtimeform.v2.runtimeFormV2SourceStatus
import build.raft.app.data.runtimeform.v2.runtimeFormV2UnavailableChoices
import build.raft.app.data.runtimeform.v2.runtimeFormV2UnavailableSelection
import build.raft.app.data.runtimeform.v2.stringMapValue
import build.raft.app.data.runtimeform.v2.text
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.text.style.TextAlign
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.designsystem.components.RaftTextLink
import build.raft.designsystem.components.RaftTextLinkVariant
import build.raft.designsystem.theme.RaftTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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
        it.kind != RuntimeFormV2FieldKind.Unsupported || isRuntimeFormV2FieldRequired(it, values)
    }.filter { isRuntimeFormV2FieldVisible(it, values, loaded.sources) }
    // Hidden choices (a derived select with nothing to pick) are not drawn, unless the Computer says why a
    // model's effort list is empty: then it is drawn, not adjustable, with the reason (slock #15274).
    val drawn = visible.filterNot { field ->
        field.kind == RuntimeFormV2FieldKind.DerivedSelect &&
            (runtimeFormV2Choices(field, loaded.sources, values) as? RuntimeFormV2Choices.Select)?.options.isNullOrEmpty() &&
            runtimeFormV2EffortUnavailable(field, loaded.sources, values) == null
    }
    val primary = drawn.filterNot { it.advanced }
    val advanced = drawn.filter { it.advanced }
    // `provider.saved_connection`: while a field holds a saved connection, the fields it owns are the
    // connection's: disabled, not required, and (by runtimeFormV2Submission) never submitted.
    val connection = loaded.form.fields.firstNotNullOfOrNull { runtimeFormV2SelectedConnection(it, loaded.sources, values) }
    val connectionOwnedHint = stringResource(Res.string.runtime_form_v2_connection_owned, connection?.label.orEmpty())
    val managedHint = connectionOwnedHint.takeIf { connection != null }

    // Web: StableFields stacked `stack-y-1`; each keeps its own message line open, so 4dp apart.
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (loaded.form.blockingFieldKeys.isNotEmpty()) {
            Text(
                text = stringResource(Res.string.runtime_form_v2_update_required_blocking),
                style = RaftType.Caption,
                color = RaftColor.AccentRed
            )
        }
        primary.forEach { field ->
            key(field.key) {
                RuntimeFormV2FieldRow(field, loaded, values, onChange, runtimeFormV2ErrorText(field, loaded, values, errors, serverErrorKeys, serverMessage), runtimeFormV2FieldEditing(field, errors, editing), enabled, sourceRefresh, managedHint) {
                    focusManager.clearFocus(force = true)
                }
            }
        }
        // Web renderer convention: Advanced opens whenever a visible field in it has a value (on edit,
        // or once a value is set, e.g. a picked runtime's default); the user may close it afterwards.
        val advancedHasValue = runtimeFormV2AdvancedStartsOpen(loaded.form, values, loaded.sources)
        val currentOnAdvancedOpenChange by rememberUpdatedState(onAdvancedOpenChange)
        LaunchedEffect(loaded.form.runtimeId, advancedHasValue) {
            if (advancedHasValue) currentOnAdvancedOpenChange(true)
        }
        if (advanced.isNotEmpty()) {
            AdvancedToggleButton(advancedOpen) {
                focusManager.clearFocus(force = true)
                onAdvancedOpenChange(!advancedOpen)
            }
            // A field with an error opens the group so the error is never hidden.
            val forcedOpen = advanced.any { it.key in errors || it.key in serverErrorKeys }
            if (advancedOpen || forcedOpen) {
                // Web: the group's fields sit behind a hairline on their left (`border-l pl-3 stack-y-3`).
                val hairline = RaftTheme.colors.lineHairline
                Column(
                    Modifier
                        .fillMaxWidth()
                        // Web `mt-3`: 12dp under the toggle (8 + the column's 4).
                        .padding(top = 8.dp)
                        .drawBehind {
                            val x = RaftStroke.Hairline.toPx() / 2f
                            drawLine(hairline, Offset(x, 0f), Offset(x, size.height), strokeWidth = RaftStroke.Hairline.toPx())
                        }
                        .padding(start = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                advanced.forEach { field ->
                    key(field.key) {
                        RuntimeFormV2FieldRow(field, loaded, values, onChange, runtimeFormV2ErrorText(field, loaded, values, errors, serverErrorKeys, serverMessage), runtimeFormV2FieldEditing(field, errors, editing), enabled, sourceRefresh, managedHint) {
                            focusManager.clearFocus(force = true)
                        }
                    }
                }
                }
            }
        }
    }
}

/**
 * Web RuntimeChoiceCombobox `renderOption`: the line under a model's name is its stored id (for Pi
 * `provider/modelId`), only when it differs from the name. Only real model / provider ids get it
 * (runtime owner, PR #4682; web slock #14728): the runtime default (""), a placeholder value
 * (`__...`, e.g. a default row) and a saved provider connection's internal `connection:<id>` have
 * none, nor does a value the list does not have (shown as itself).
 */
internal fun runtimeChoiceIdentifier(value: String, label: String): String? =
    value.takeIf {
        it.isNotEmpty() &&
            it != label &&
            !it.startsWith(RUNTIME_CHOICE_PLACEHOLDER_PREFIX) &&
            !it.startsWith(RUNTIME_CHOICE_CONNECTION_PREFIX)
    }

/** A placeholder value (the web's `__runtime_form_v2_default__`), never a real id. */
private const val RUNTIME_CHOICE_PLACEHOLDER_PREFIX = "__"

/** `provider.saved_connection`: a saved connection's option value is `connection:<connectionId>`. */
private const val RUNTIME_CHOICE_CONNECTION_PREFIX = "connection:"

/**
 * One listed choice of a runtime form select. A bound connection that is unavailable stays visible
 * but cannot be picked again. A choice's description (`choice.labels`) is the row's second line, as
 * web draws it in the list (RuntimeChoiceCombobox `renderOption`, RuntimeSelectControl
 * `SelectItemDescription` when it says more than the label); the closed field never repeats it. With
 * [showsIdentifiers], a row without a description has its id under the name. Search matches what web
 * `runtimeChoiceMatches` (RuntimeChoiceCombobox.tsx) matches on every list, searchable or not: the
 * name, the stored value (the id), the group (a model's provider) and the description; the value,
 * the server's group and the description ride as keywords (#wg-runtime-form:#28), so typing an id
 * or a provider finds a choice whether or not its id line is drawn.
 */
internal fun runtimeFormV2ListedOption(
    option: RuntimeFormV2Choice,
    group: String?,
    showsIdentifiers: Boolean
): RaftComboboxOption<String> {
    val description = option.description?.takeIf { it.isNotBlank() && !it.equals(option.label, ignoreCase = true) }
    val described = description != null
    return RaftComboboxOption(
        value = option.value,
        label = option.label,
        description = description,
        group = group,
        enabled = !option.unavailable,
        identifier = if (showsIdentifiers && !described) runtimeChoiceIdentifier(option.value, option.label) else null,
        keywords = runtimeFormV2ChoiceSearchKeywords(option, description)
    )
}

/**
 * Web RuntimeFormV2FieldControl: a select takes the searchable list whose rows carry the id line when
 * it accepts a typed value or lists what an option source reports (models); a derived select (an
 * effort menu) and a fixed list stay a plain select without it.
 */
internal fun runtimeFormV2ShowsChoiceIdentifiers(field: RuntimeFormV2Field, choices: RuntimeFormV2Choices.Select): Boolean =
    choices.allowCustom || (field.optionSourceId != null && field.kind != RuntimeFormV2FieldKind.DerivedSelect)

/**
 * Web RuntimeChoiceCombobox `unlisted && !customValue`: the searchable list's stored value is not one it
 * offers and was not typed on purpose, so it says "The saved value … is not in the current list", also
 * when the value is one of the source's unavailable entries. A saved provider connection (its value is
 * the internal `connection:<id>`) has its own label and reason instead.
 */
internal fun runtimeFormV2ShowsSavedValueNotInList(
    searchable: Boolean,
    allowCustom: Boolean,
    unlisted: Boolean,
    unavailableSelected: RuntimeFormV2UnavailableChoice?
): Boolean = searchable && !allowCustom && unlisted && unavailableSelected?.connectionId == null

/** The option source a field can ask for again: its own (a derived select follows its parent's), when refresh is wired. */
internal fun runtimeFormV2RefreshableSource(field: RuntimeFormV2Field, sourceRefresh: RuntimeFormV2SourceRefresh?): String? =
    field.optionSourceId?.takeIf { field.kind != RuntimeFormV2FieldKind.DerivedSelect && sourceRefresh != null }

/**
 * Web RuntimeFormV2FieldControl `showRefresh`: the label-row refresh shows unless the source's status line
 * already offers Retry (a retryable non-live source), so the two never repeat each other; a source whose
 * retry cannot help (missing_config, unsupported) still has the refresh.
 */
internal fun runtimeFormV2ShowsRefreshAction(refreshableSource: String?, status: RuntimeFormV2SourceStatus?): Boolean =
    refreshableSource != null && status?.retryable != true

private fun runtimeFormV2RefreshAction(
    refreshableSource: String?,
    status: RuntimeFormV2SourceStatus?,
    sourceRefresh: RuntimeFormV2SourceRefresh?,
    enabled: Boolean,
    clearFocus: () -> Unit
): (@Composable () -> Unit)? {
    if (refreshableSource == null || sourceRefresh == null || !runtimeFormV2ShowsRefreshAction(refreshableSource, status)) return null
    return {
        // Web FieldAction `tone="icon"`: raft-ui's ghost 32px icon button with RefreshCw at 14px, in the label
        // row (RaftField draws it 7dp past the label line, as StableField's `-my-[7px]`).
        val refreshing = refreshableSource in sourceRefresh.refreshing
        RaftIconButton(
            onClick = {
                clearFocus()
                sourceRefresh.onRefresh(refreshableSource)
            },
            tooltip = stringResource(Res.string.runtime_form_v2_refresh_source_title),
            contentDescription = stringResource(Res.string.runtime_form_v2_refresh_source_label),
            variant = RaftButtonVariant.Ghost,
            size = RaftIconButtonSize.Md,
            enabled = enabled && !refreshing,
            loading = refreshing
        ) {
            RaftSlotIcon(RaftLucideIconName.RefreshCw, size = RuntimeFormV2RefreshGlyph)
        }
    }
}

/** Web `<RefreshCw size={14} />` in the label-row refresh. */
private val RuntimeFormV2RefreshGlyph = 14.dp

/**
 * Web `runtimeChoiceMatches`: a choice is found by its label, value, group or description. The label
 * is matched by the combobox itself; the rest are keywords (blank and label-equal ones left out).
 */
internal fun runtimeFormV2ChoiceSearchKeywords(option: RuntimeFormV2Choice, description: String?): List<String> =
    listOfNotNull(option.value, option.group, description ?: option.description)
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.equals(option.label, ignoreCase = true) }
        .distinct()

/**
 * The combobox value for a select whose stored value is [current]. "" means "nothing chosen" unless the
 * list offers a "" option (the runtime default, e.g. reasoning "Default"): then that option is the
 * selection, so the trigger shows its label and its row is checked, as v1.19.0 did.
 */
internal fun runtimeFormV2ComboboxValue(current: String, options: List<RaftComboboxOption<String>>): String? =
    current.takeIf { it.isNotEmpty() || options.any { option -> option.value.isEmpty() } }

/**
 * Errors to show: those of fields the user touched or the server pointed at, plus the ones that block
 * the save before the field itself is touched: an unavailable saved connection (`provider.saved_connection`),
 * a current value that is an `unavailableOptions` entry (v3, shown selected-and-unavailable) and a blank
 * secret whose bound fields changed (`writeOnlyKeepRequiresUnchanged`; slock web shows it without a
 * submit attempt).
 */
internal fun runtimeFormV2ShownErrors(
    errors: Map<String, RuntimeFormV2ErrorCode>,
    shownKeys: Set<String>
): Map<String, RuntimeFormV2ErrorCode> =
    errors.filter { (key, code) ->
        key in shownKeys ||
            code == RuntimeFormV2ErrorCode.ConnectionUnavailable ||
            code == RuntimeFormV2ErrorCode.OptionUnavailable ||
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
    loaded: RuntimeFormV2Loaded,
    values: RuntimeFormV2Values,
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
            // The field itself says "Update Raft to edit this setting." and Save stays off: not twice (slock #15397).
            RuntimeFormV2ErrorCode.UnsupportedRequired -> null
            // The field's source status line already says why, with its retry.
            RuntimeFormV2ErrorCode.SourceUnavailable -> null
            RuntimeFormV2ErrorCode.ConnectionUnavailable -> stringResource(Res.string.runtime_form_v2_saved_provider_unavailable)
            RuntimeFormV2ErrorCode.SecretReentryRequired -> stringResource(Res.string.runtime_form_v2_error_secret_reentry_required)
            // v3: the value is an unavailable entry: a saved connection says so, a model asks for another pick
            // (slock web RuntimeConfigFields `option_unavailable`).
            RuntimeFormV2ErrorCode.OptionUnavailable ->
                if (runtimeFormV2UnavailableSelection(field, loaded.sources, values)?.connectionId != null) {
                    stringResource(Res.string.runtime_form_v2_saved_provider_unavailable)
                } else {
                    stringResource(Res.string.runtime_form_v2_error_not_a_choice)
                }
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
    val required = isRuntimeFormV2FieldRequired(field, values) && !connectionOwned
    val hint = if (connectionOwned) managedHint else field.hint
    // A reasoning effort the selected model has no levels for, with the Computer's explanation.
    runtimeFormV2EffortUnavailable(field, loaded.sources, values)?.let { unavailable ->
        RuntimeFormV2EffortUnavailableField(field, unavailable)
        return
    }
    when (field.kind) {
        RuntimeFormV2FieldKind.Text, RuntimeFormV2FieldKind.Url ->
            RuntimeFormV2TextInput(
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
            RuntimeFormV2FieldFrame(
                label = field.label.takeIf { it.isNotBlank() },
                description =
                    (if (editing && !connectionOwned) stringResource(Res.string.runtime_form_v2_secret_keep_hint) else hint)
                        ?.takeIf { it.isNotBlank() },
                error = error?.takeIf { it.isNotBlank() },
                enabled = fieldEnabled,
                required = required && !editing
            ) {
                val editor = rememberRuntimeFormV2Editor(KeyboardOptions.Default.copy(keyboardType = KeyboardType.Password))
                RaftTextField(
                    value = secret,
                    onValueChange = {
                        secret = it
                        onChange(field.key, JsonPrimitive(it.text))
                    },
                    modifier = Modifier.fillMaxWidth().then(editor.modifier),
                    placeholder = field.placeholder.orEmpty().ifEmpty { null },
                    keyboardOptions = editor.keyboardOptions,
                    visualTransformation = PasswordVisualTransformation()
                )
            }
        }
        RuntimeFormV2FieldKind.Boolean ->
            RuntimeFormV2BooleanField(
                label = field.label,
                checked = values[field.key].booleanValue(),
                onCheckedChange = {
                    clearFocus()
                    onChange(field.key, JsonPrimitive(it))
                },
                hint = hint,
                error = error,
                required = required,
                enabled = fieldEnabled
            )
        RuntimeFormV2FieldKind.StringMap -> {
            // Half-typed rows (blank key) stay on screen but never reach the value.
            val external = values[field.key].stringMapValue()
            var entries by remember(field.key) { mutableStateOf(external.toList()) }
            // Follow a value set from outside (reset, prefill) unless it is just our own half-typed rows.
            val ownValue = entries.mapNotNull { (key, value) -> key.trim().takeIf { it.isNotEmpty() }?.let { it to value } }.toMap()
            if (ownValue != external) entries = external.toList()
            // Web RuntimeEnvVarsField: a StableField that adopts no control (`adopt={false}`): the rows keep
            // their own look when the group has an error, which takes the reserved line under them.
            RaftField(
                label = null,
                modifier = Modifier.fillMaxWidth(),
                error = error?.takeIf { it.isNotBlank() },
                enabled = fieldEnabled,
                reserveMessage = true
            ) {
                CompositionLocalProvider(LocalRaftFieldState provides RaftFieldState()) {
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
                    required = required,
                    // Web RuntimeEnvVarsField: the hint under the label, above the rows.
                    hint = hint?.let { { RuntimeFormV2HintText(it) } }
                )
                }
            }
        }
        RuntimeFormV2FieldKind.Select,
        RuntimeFormV2FieldKind.DependentSelect,
        RuntimeFormV2FieldKind.DerivedSelect ->
            when (val choices = runtimeFormV2Choices(field, loaded.sources, values)) {
                RuntimeFormV2Choices.FreeText ->
                    // Web: a source-bound field typed into keeps the label-row refresh, without a status line.
                    RuntimeFormV2TextInput(
                        label = field.label,
                        value = current,
                        onValueChange = { onChange(field.key, JsonPrimitive(it)) },
                        placeholder = field.placeholder.orEmpty(),
                        required = required,
                        hint = hint,
                        error = error,
                        enabled = fieldEnabled,
                        labelTrailing =
                            runtimeFormV2RefreshAction(
                                runtimeFormV2RefreshableSource(field, sourceRefresh),
                                runtimeFormV2SourceStatus(field, loaded.sources),
                                sourceRefresh,
                                fieldEnabled,
                                clearFocus
                            )
                    )
                is RuntimeFormV2Choices.Select -> {
                    val defaultLabel = stringResource(Res.string.runtime_form_v2_option_default)
                    val savedGroupLabel = stringResource(Res.string.runtime_form_v2_saved_providers)
                    val directGroupLabel = stringResource(Res.string.runtime_form_v2_direct_providers)
                    val unavailableGroupLabel = stringResource(Res.string.runtime_form_v2_unavailable_group)
                    val noMatches = stringResource(Res.string.common_no_matches)
                    val searchLabel = stringResource(Res.string.search_filter_search)
                    val useCustomLabel = stringResource(Res.string.runtime_form_v2_use_custom, "%1\$s")
                    val customValueLabel = stringResource(Res.string.runtime_form_v2_custom_value)
                    // `provider.saved_connection`: headings only when the list mixes saved and direct choices.
                    val grouped = choices.options.mapNotNull { it.group }.distinct().size > 1
                    val unlisted = current.isNotEmpty() && choices.options.none { it.value == current }
                    // `OptionSource.unavailableOptions`: drawn disabled after the list, by `group` when the
                    // server sends one, else "Not available on this computer"; the reason is plain text.
                    val unavailableChoices = runtimeFormV2UnavailableChoices(field, loaded.sources)
                    // The current value is one of them (v3: a bound saved connection that is disabled): shown
                    // selected by its own label and reason, never rewritten; validation blocks the save.
                    val unavailableSelected =
                        if (unlisted) unavailableChoices.firstOrNull { it.value == current.trim() } else null
                    // Web draws a searchable list (RuntimeChoiceCombobox) for these, each row with its id under the name.
                    val showsIdentifiers = runtimeFormV2ShowsChoiceIdentifiers(field, choices)
                    // One option list for both pickers; "" is the runtime default.
                    val options = buildList {
                        if (choices.allowEmpty) add(RaftComboboxOption("", defaultLabel))
                        choices.options.forEach { option ->
                            add(
                                runtimeFormV2ListedOption(
                                    option = option,
                                    group = option.group?.takeIf { grouped }?.let { if (it == "saved") savedGroupLabel else directGroupLabel },
                                    showsIdentifiers = showsIdentifiers
                                )
                            )
                        }
                        unavailableChoices
                            .filter { entry -> entry.value != current && choices.options.none { it.value == entry.value } }
                            .forEach { entry ->
                                add(
                                    RaftComboboxOption(
                                        value = entry.value,
                                        label = entry.label,
                                        description = entry.reason,
                                        group = when (entry.group) {
                                            "saved" -> savedGroupLabel
                                            "direct" -> directGroupLabel
                                            else -> unavailableGroupLabel
                                        },
                                        enabled = false,
                                        // Shown only without a reason (the reason takes its line).
                                        identifier = if (showsIdentifiers) runtimeChoiceIdentifier(entry.value, entry.label) else null
                                    )
                                )
                            }
                    }
                    // `option_source.status`: a source that is not live says why.
                    val sourceStatus = runtimeFormV2SourceStatus(field, loaded.sources)
                    // Re-probe: selects that read a machine option source can ask for it again. Derived selects
                    // follow their parent's source. The label-row action and the status line's Retry never both
                    // show (web RuntimeFormV2FieldControl `showRefresh`).
                    val refreshableSource = runtimeFormV2RefreshableSource(field, sourceRefresh)
                    // Web StableField: the label row (with the refresh action), the control, the status line
                    // (`belowControl`), then one message row where an error replaces the hint.
                    RuntimeFormV2FieldFrame(
                        label = field.label,
                        description = hint?.takeIf { it.isNotBlank() },
                        error = error?.takeIf { it.isNotBlank() },
                        enabled = fieldEnabled,
                        required = required,
                        labelTrailing = runtimeFormV2RefreshAction(refreshableSource, sourceStatus, sourceRefresh, fieldEnabled, clearFocus)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            // Required shows no mark: the picker says it to screen readers ("Model, Required"); the
                            // field state also gives the picker the field type (and a plain select its invalid edge).
                            if (choices.allowCustom) {
                                // `select.custom_value` is raft-ui's ComboboxSelect with `create`: the list
                                // with search, and typed text that is not a choice offered as "Use “…”".
                                // A stored value the list does not offer shows as itself.
                                // A value not in the list of a field that takes a typed one is the person's own
                                // value (e.g. a model id): listed first, selected, and marked "Custom" (slock web
                                // RuntimeFormV2FieldControl `customValue` / isTypedCustomValue).
                                val typedCustom = unlisted && unavailableSelected == null
                                RaftCombobox(
                                    value = runtimeFormV2ComboboxValue(current, options),
                                    options =
                                        if (typedCustom) {
                                            val defaults = if (choices.allowEmpty) 1 else 0
                                            options.take(defaults) +
                                                RaftComboboxOption(current, current, description = customValueLabel) +
                                                options.drop(defaults)
                                        } else {
                                            options
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
                                    // A typed-value list is web's RuntimeChoiceCombobox: no red edge on error.
                                    invalid = false,
                                    label = field.label
                                )
                            } else {
                                // raft-ui ComboboxSelect without `create`: the list under the trigger up to
                                // ten options, a searchable sheet above (the picker-count rule). A stored value
                                // the server no longer offers stays visible, but cannot be re-picked.
                                RaftCombobox(
                                    value = runtimeFormV2ComboboxValue(current, options),
                                    options =
                                        if (unavailableSelected != null) {
                                            listOf(
                                                RaftComboboxOption(
                                                    value = current,
                                                    label = unavailableSelected.label,
                                                    description = unavailableSelected.reason,
                                                    enabled = false
                                                )
                                            ) + options
                                        } else if (unlisted) {
                                            options + RaftComboboxOption(current, current, enabled = false)
                                        } else {
                                            options
                                        },
                                    onValueChange = { next ->
                                        clearFocus()
                                        onChange(field.key, JsonPrimitive(next))
                                    },
                                    emptyText = { noMatches },
                                    placeholder = field.placeholder ?: field.label,
                                    title = field.label,
                                    searchPlaceholder = searchLabel,
                                    enabled = fieldEnabled,
                                    // Web RuntimeChoiceCombobox draws its own field edge, which a field error leaves ink (the
                                    // message row says it); a plain select's edge turns red (RuntimeSelectControl).
                                    invalid = !showsIdentifiers && !error.isNullOrBlank(),
                                    label = field.label
                                )
                            }
                            // A stored value the list does not offer (not a typed custom one) says so, also when it
                            // is one of the source's unavailable entries (web RuntimeChoiceCombobox
                            // `savedValueNotInList`: `unlisted && !customValue`). A saved connection's internal
                            // `connection:<id>` is never shown: its own label and reason say it.
                            if (runtimeFormV2ShowsSavedValueNotInList(showsIdentifiers, choices.allowCustom, unlisted, unavailableSelected)) {
                                Text(
                                    stringResource(Res.string.runtime_form_v2_saved_value_not_in_list, current),
                                    style = RaftType.Caption,
                                    color = RaftColor.ForegroundHint
                                )
                            }
                            // `choice.labels` descriptions are the list rows' second line only: web shows none under
                            // the closed field.
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
                        }
                    }
                }
                null -> Unit
            }
        // Web: the field's label over "update to edit" in callout muted ink (not an error until submit).
        RuntimeFormV2FieldKind.Unsupported ->
            RuntimeFormV2FieldFrame(
                label = field.label,
                description = null,
                error = error?.takeIf { it.isNotBlank() },
                enabled = true,
                required = required
            ) {
                RaftText(
                    stringResource(Res.string.runtime_form_v2_update_required),
                    style = RaftTheme.typography.callout,
                    color = RaftTheme.colors.foregroundMuted
                )
            }
    }
}

/**
 * A boolean field as web RuntimeFormV2FieldControl draws it (slock #14981): a raft-ui option card, the
 * checkbox leading, the label as its title and the hint as its description, so the field repeats
 * neither label nor hint. The whole card is the one checkbox target (its checkbox is drawn, not
 * tapped); an error sits under the card in the field's message row. Hint links read as their text:
 * the design-system card description is plain text.
 */
@Composable
internal fun RuntimeFormV2BooleanField(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    hint: String?,
    error: String?,
    required: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    RaftField(
        label = null,
        modifier = modifier.fillMaxWidth(),
        error = error?.takeIf { it.isNotBlank() },
        enabled = enabled,
        required = required,
        // Web StableField under the option card: its message line stays open.
        reserveMessage = true
    ) {
        RaftCard(
            modifier = Modifier.fillMaxWidth(),
            variant = RaftCardVariant.Option,
            title = label,
            description = hint?.takeIf { it.isNotBlank() }?.let(::runtimeFormV2HintPlainText),
            leading = { RaftCheckbox(checked = checked, onCheckedChange = null, enabled = enabled) },
            onClick = { onCheckedChange(!checked) },
            selected = checked,
            role = Role.Checkbox,
            enabled = enabled
        )
    }
}

/** A hint's words without its inline link markup (`[text](https://…)` reads as `text`). */
internal fun runtimeFormV2HintPlainText(hint: String): String =
    if (!runtimeFormV2HintHasLinks(hint)) {
        hint
    } else {
        parseRuntimeFormV2Hint(hint).joinToString("") { it.text }
    }

/**
 * The line for why a source is not live (`option_source.status` reason), as the web's
 * RUNTIME_FORM_V2_SOURCE_REASON_MESSAGE; an unknown reason gets the generic line.
 * not_signed_in / runtime_outdated: botiverse/slock #15105.
 */
internal fun runtimeFormV2SourceReasonText(reason: String?): StringResource =
    when (reason) {
        "probe_timeout" -> Res.string.runtime_form_v2_source_status_probe_timeout
        "probe_failed" -> Res.string.runtime_form_v2_source_status_probe_failed
        "missing_config" -> Res.string.runtime_form_v2_source_status_missing_config
        "no_models" -> Res.string.runtime_form_v2_source_status_no_models
        "unsupported" -> Res.string.runtime_form_v2_source_status_unsupported
        "machine_offline" -> Res.string.runtime_form_v2_source_status_machine_offline
        "not_signed_in" -> Res.string.runtime_form_v2_source_status_not_signed_in
        "runtime_outdated" -> Res.string.runtime_form_v2_source_status_runtime_outdated
        else -> Res.string.runtime_form_v2_source_status_unknown
    }

/**
 * Why a source is not live (`option_source.status`); an unknown reason gets the generic line. Web
 * RuntimeFormV2SourceStatusLine: one caption sentence in muted ink behind a hairline on its left, the
 * Retry link (only when the server says it may help) at the end of the sentence.
 */
@Composable
private fun RuntimeFormV2SourceStatusLine(status: RuntimeFormV2SourceStatus, retrying: Boolean, onRetry: (() -> Unit)?) {
    val reason = stringResource(runtimeFormV2SourceReasonText(status.reason))
    val fallback = stringResource(Res.string.runtime_form_v2_source_status_fallback)
    // A stale list (the Computer's last one: it did not answer in time) says so instead of the reason.
    val text =
        when {
            status.stale -> stringResource(Res.string.runtime_form_v2_source_status_stale)
            status.unavailable -> reason
            else -> "$reason $fallback"
        }
    val retryLabel = stringResource(Res.string.common_retry)
    val retry = onRetry?.takeIf { status.retryable }
    val line =
        buildAnnotatedString {
            append(text)
            if (retry != null) {
                append(" ")
                appendRaftTextLink(retryLabel, onClick = retry, enabled = !retrying)
            }
        }
    val hairline = RaftTheme.colors.lineHairline
    RaftText(
        line,
        modifier =
            Modifier
                .fillMaxWidth()
                .drawBehind {
                    val x = RaftStroke.Hairline.toPx() / 2f
                    drawLine(hairline, Offset(x, 0f), Offset(x, size.height), strokeWidth = RaftStroke.Hairline.toPx())
                }
                .padding(start = 8.dp),
        style = RaftTheme.typography.caption,
        color = RaftTheme.colors.foregroundMuted
    )
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
    // One source answers; then the sources keyed by its field follow (v3: a reasoning effort keyed by
    // model), without `refresh=1`: the parent's fresh answer is cached server-side (slock web
    // useRuntimeFormV2 `dependentsOf`). Returns false when the whole form went stale.
    val form: RuntimeFormV2Loaded = loaded
    val loader: RuntimeFormV2Access = access
    val machine: String = machineId
    suspend fun reload(sourceId: String, refresh: Boolean): Boolean {
        val result = loader.loader.reloadSource(
            loader.serverId,
            machine,
            form.form.runtimeId,
            sourceId,
            form.form.revision,
            refresh = refresh
        )
        when {
            result is DataResult.Content -> {
                overrides = overrides + (sourceId to result.data)
                onSourceReloaded(form.copy(sources = form.sources + overrides), sourceId)
                for (dependent in runtimeFormV2DependentSourceIds(form.form, sourceId)) {
                    if (!reload(dependent, refresh = false)) return false
                }
            }
            result.isFormRevisionStale() -> {
                onFormStale()
                return false
            }
            else -> failed = failed + sourceId
        }
        return true
    }
    val refresh =
        RuntimeFormV2SourceRefresh(refreshing = refreshing, failed = failed) { sourceId ->
            if (sourceId !in refreshing) {
                refreshing = refreshing + sourceId
                failed = failed - sourceId
                scope.launch {
                    reload(sourceId, refresh = true)
                    refreshing = refreshing - sourceId
                }
            }
        }
    return effective to refresh
}

/**
 * Runtime picker plus the v2 form body, in the create and edit dialogs' runtime slot. The
 * runtimes are [catalog]'s (the server catalog, its cache, or the bundled table), grouped by
 * what the selected computer runs ([runtimePickerRows]); [currentRuntimeId] is the agent's own
 * runtime when switching (null when creating), pinned first; [serverSelectable] is the
 * runtime-options row's `canSelectInThisContext` when known. With the server's runtime-options
 * [options] (create: the computer's rows; switch: the agent's rows) those rows are the list and
 * [catalog] only names them.
 */
@Composable
internal fun RuntimeFormV2RuntimeSection(
    runtime: String,
    onRuntimeChange: (String) -> Unit,
    machineRuntimeIds: List<String>,
    runtimeAvailabilityError: String?,
    enabled: Boolean,
    /** The computer whose usage gives each runtime its quota line; null: no lines. */
    quotaMachine: RuntimeQuotaMachine? = null,
    catalog: ServerRuntimeCatalog = BundledRuntimeCatalog,
    currentRuntimeId: String? = null,
    serverSelectable: (String) -> Boolean? = { null },
    options: List<SharedCreateAgentRuntimeOption>? = null,
    content: @Composable () -> Unit
) {
    val focusManager = LocalFocusManager.current
    // Show All Supported belongs to the computer it was asked on: another computer starts collapsed.
    var showAll by remember(quotaMachine?.machineId) { mutableStateOf(false) }
    val rows =
        runtimePickerRows(
            catalog = catalog,
            machineRuntimeIds = machineRuntimeIds,
            selectedRuntimeId = runtime,
            showAll = showAll,
            currentRuntimeId = currentRuntimeId,
            serverSelectable = serverSelectable,
            options = options
        )
    val hint = rows.selectedCapabilityHint(runtime).takeIf { runtimeAvailabilityError == null }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // Web StableField: its message line is always there.
        RaftField(
            label = stringResource(Res.string.agent_create_label_runtime),
            modifier = Modifier.fillMaxWidth(),
            error =
                runtimeAvailabilityError?.let { error ->
                    if (error == CREATE_AGENT_NO_MACHINE_RUNTIMES_ERROR) {
                        stringResource(Res.string.agent_create_error_no_machine_runtimes)
                    } else {
                        error
                    }
                },
            reserveMessage = hint == null
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                RuntimeSelectChoice(
                    runtime = runtime,
                    rows = rows,
                    showAll = showAll,
                    onShowAllChange = { showAll = it },
                    quotaMachine = quotaMachine,
                    enabled = enabled && runtimeAvailabilityError == null,
                    emptyLabel =
                        if (runtimeAvailabilityError != null) {
                            stringResource(Res.string.agent_create_state_no_runtimes)
                        } else {
                            null
                        },
                    onSelect = { id ->
                        focusManager.clearFocus(force = true)
                        onRuntimeChange(id)
                    }
                )
                // Web slock #15551: what the chosen runtime lacks in Raft, under the field (never in the list).
                RuntimeCapabilityHintText(hint, enabled = enabled)
            }
        }
        if (runtimeAvailabilityError == null) content()
    }
}

/** A single-line runtime-form text field: the design-system field with the app editor behaviour. */
@Composable
private fun RuntimeFormV2TextInput(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    required: Boolean,
    hint: String?,
    error: String?,
    enabled: Boolean,
    labelTrailing: (@Composable () -> Unit)? = null
) {
    RuntimeFormV2FieldFrame(
        label = label.takeIf { it.isNotBlank() },
        description = hint?.takeIf { it.isNotBlank() },
        error = error?.takeIf { it.isNotBlank() },
        enabled = enabled,
        required = required,
        labelTrailing = labelTrailing
    ) {
        val editor = rememberRuntimeFormV2Editor(KeyboardOptions.Default)
        RaftTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().then(editor.modifier),
            placeholder = placeholder.ifEmpty { null },
            keyboardOptions = editor.keyboardOptions
        )
    }
}

@Composable
private fun rememberRuntimeFormV2Editor(keyboardOptions: KeyboardOptions): RaftDesignSystemEditor =
    rememberRaftDesignSystemEditor(
        keyboardOptions = keyboardOptions,
        keyboardActions = KeyboardActions.Default,
        keyboardHeightChange = {},
        focusRequester = remember { FocusRequester() },
        autoFocus = false,
        onFocus = {},
        onBlur = {}
    )

/**
 * A reasoning effort field whose model offers no level, as on the web (slock #15274): the label, the value in
 * effect (Default) in a picker that cannot change, and why: the model takes no effort, or its levels could not
 * be read (the runtime's reason, plain text, never links or markup). The stored value is left as it is.
 */
@Composable
private fun RuntimeFormV2EffortUnavailableField(field: RuntimeFormV2Field, unavailable: RuntimeFormV2EffortUnavailable) {
    val defaultLabel = stringResource(Res.string.runtime_form_v2_option_default)
    val noMatches = stringResource(Res.string.common_no_matches)
    val reason = when (unavailable) {
        RuntimeFormV2EffortUnavailable.None -> stringResource(Res.string.runtime_form_v2_effort_not_supported)
        is RuntimeFormV2EffortUnavailable.Unreadable -> stringResource(Res.string.runtime_form_v2_effort_unreadable, unavailable.reason)
    }
    // Web: the reason is the field's hint, in readable ink (the field is not disabled, only its picker).
    RuntimeFormV2FieldFrame(label = field.label, description = reason, error = null, enabled = true, required = false) {
        RaftCombobox(
            value = "",
            options = listOf(RaftComboboxOption("", defaultLabel)),
            onValueChange = {},
            emptyText = { noMatches },
            placeholder = field.placeholder ?: field.label,
            title = field.label,
            enabled = false,
            label = field.label
        )
    }
}

/**
 * A runtime form v2 hint under a custom control: plain text as before, or, when the server copy holds
 * inline `[text](https://…)` links (web RuntimeFormV2HintText), those words as tappable TextLinks that
 * open the link as any other link in the app does.
 */
@Composable
private fun RuntimeFormV2HintText(hint: String) {
    if (!runtimeFormV2HintHasLinks(hint)) {
        Text(hint, style = RaftType.Caption, color = RaftColor.ForegroundHint)
        return
    }
    RaftText(rememberRuntimeFormV2HintText(hint), style = RaftType.Caption, color = RaftColor.ForegroundHint)
}

@Composable
private fun rememberRuntimeFormV2HintText(hint: String): androidx.compose.ui.text.AnnotatedString {
    val uriHandler = LocalUriHandler.current
    return remember(hint, uriHandler) {
        buildAnnotatedString {
            parseRuntimeFormV2Hint(hint).forEach { part ->
                when (part) {
                    is RuntimeFormV2HintPart.Plain -> append(part.text)
                    is RuntimeFormV2HintPart.Link -> appendRaftTextLink(part.text, onClick = { uriHandler.openUri(part.href) }, leavesRaft = true)
                }
            }
        }
    }
}

/**
 * [RaftField] for runtime form v2 controls: a [description] with inline https links is drawn by the form
 * (the design-system description is plain text), in the description's place and look; an error still
 * replaces it.
 */
@Composable
private fun RuntimeFormV2FieldFrame(
    label: String?,
    description: String?,
    error: String?,
    enabled: Boolean,
    required: Boolean,
    labelTrailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit
) {
    if (description == null || !runtimeFormV2HintHasLinks(description)) {
        RaftField(
            label = label,
            modifier = Modifier.fillMaxWidth(),
            description = description,
            error = error,
            enabled = enabled,
            required = required,
            labelTrailing = labelTrailing,
            // Web StableField: the line under the control is always there, so a hint or an error
            // appearing moves nothing below.
            reserveMessage = true,
            content = content
        )
        return
    }
    // A hint with links: its line is always taken (by the hint, or by the error in its place), so
    // nothing below moves either.
    val linked = rememberRuntimeFormV2HintText(description)
    // RaftField's own 6dp gap between the control and its description.
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RaftField(
            label = label,
            modifier = Modifier.fillMaxWidth(),
            error = error,
            enabled = enabled,
            required = required,
            labelTrailing = labelTrailing,
            content = content
        )
        if (error == null) {
            RaftText(
                linked,
                style = RaftTheme.typography.caption,
                color = if (enabled) RaftTheme.colors.foregroundHint else RaftTheme.colors.foregroundDisabled
            )
        }
    }
}
