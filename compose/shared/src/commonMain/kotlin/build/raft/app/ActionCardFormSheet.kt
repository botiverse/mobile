package build.raft.app

import build.raft.app.resources.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.components.RaftSlotIcon
import build.raft.app.data.ActionCardFormIssue
import build.raft.app.data.ActionCardFormValue
import build.raft.app.data.ActionCardSpecAgentPickerField
import build.raft.app.data.ActionCardSpecChannelPickerField
import build.raft.app.data.ActionCardSpecChannelScope
import build.raft.app.data.ActionCardSpecForm
import build.raft.app.data.ActionCardSpecKnownField
import build.raft.app.data.ActionCardSpecMemberKind
import build.raft.app.data.ActionCardSpecMemberPickerField
import build.raft.app.data.ActionCardSpecMemberScope
import build.raft.app.data.ActionCardSpecMsg
import build.raft.app.data.ActionCardSpecPickerField
import build.raft.app.data.ActionCardSpecSelectField
import build.raft.app.data.ActionCardSpecTextAreaField
import build.raft.app.data.ActionCardSpecTextInputField
import build.raft.app.data.ActionCardSpecToggleField
import build.raft.app.data.AppLocale
import build.raft.app.data.actionCardDisplayLocale
import build.raft.app.data.checkActionCardFormValues
import build.raft.app.data.renderActionCardMsgText
import build.raft.app.data.currentSystemLanguageTag
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftChoicePicker
import build.raft.designsystem.components.RaftComboboxOption
import build.raft.designsystem.components.RaftDialogSheet
import build.raft.designsystem.components.RaftDialogSheetSurface
import build.raft.designsystem.components.RaftField
import build.raft.designsystem.components.RaftFieldError
import build.raft.designsystem.components.RaftFieldItem
import build.raft.designsystem.components.RaftListRowGroup
import build.raft.designsystem.components.RaftSwitch
import build.raft.designsystem.components.RaftTextField
import build.raft.designsystem.components.RaftTextarea
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme

/**
 * What the pickers of an action card form choose from: the client's own store, no extra
 * request (spec §3.4). [joinedChannels] / [visibleChannels] carry the channel id as payload
 * and stable key.
 */
internal data class ActionCardFormCandidates(
    /** Everyone on the card's server (`scope: "server"`). */
    val serverMembers: List<MemberProfile> = emptyList(),
    /** The members of the card's channel (`scope: "channel"`). */
    val channelMembers: List<MemberProfile> = emptyList(),
    val joinedChannels: List<RaftForwardTargetPickerItem<String>> = emptyList(),
    val visibleChannels: List<RaftForwardTargetPickerItem<String>> = emptyList(),
    val loading: Boolean = false,
)

/**
 * The generic action card form sheet (`form.v1`, spec §3.4): what an `open_form` button opens.
 * A [RaftDialogSheet] (raft-ui Dialog "On a phone": several fields) titled by the form, with its
 * submit label as the commit; the form's description, then its fields in order:
 *
 * - `TextInput` a [RaftTextField], `TextArea` a [RaftTextarea] with its counter, `Select` a
 *   [RaftChoicePicker] (radios up to 5 options, a sheet list up to 10, a searched sheet past
 *   that), `Toggle` a [RaftSwitch] row (never required);
 * - `MemberPicker` / `AgentPicker` the shared [RaftMemberPicker], `ChannelPicker` a list of
 *   [RaftForwardPickerTargetRow]s (the forward picker's rows). A picker's value is always a list
 *   of ids; one that is not `multiple` keeps at most one.
 *
 * The caller owns [values] (start from [initialActionCardFormValues]) and submitting. The sheet
 * checks only `required` and length on submit ([checkActionCardFormValues]); when they pass it
 * calls [onSubmit] with the values. [fieldErrors] are the server's issues (a 422 `FORM_INVALID`):
 * each shows under its field until that field is edited, and one without a field (or naming no
 * field of the form) shows in a banner at the top with [formError] (any other failure). While
 * [submitting] the inputs are disabled, the commit is busy, × is disabled (web `closeDisabled`)
 * and the sheet does not close.
 */
@Composable
internal fun RaftActionCardFormSheet(
    form: ActionCardSpecForm,
    values: Map<String, ActionCardFormValue>,
    onValuesChange: (Map<String, ActionCardFormValue>) -> Unit,
    fieldErrors: List<ActionCardFormIssue>,
    submitting: Boolean,
    onSubmit: (Map<String, ActionCardFormValue>) -> Unit,
    onDismiss: () -> Unit,
    candidates: ActionCardFormCandidates = ActionCardFormCandidates(),
    formError: String? = null,
    agentStatusResolver: RaftAgentStatusResolver = EmptyRaftAgentStatusResolver,
) {
    RaftDesignSystemTheme {
        val locale = rememberActionCardFormDisplayLocale()
        val state = rememberActionCardFormSheetState(form, fieldErrors)
        RaftDialogSheet(
            onDismissRequest = { if (!submitting) onDismiss() },
            title = form.title.render(locale),
            commitLabel = form.submitLabel.render(locale),
            onCommit = { state.submit(values, submitting, onSubmit) },
            commitLoading = submitting,
            closeLabel = stringResource(Res.string.common_close),
            cancelLabel = stringResource(Res.string.common_cancel),
            closeEnabled = !submitting,
        ) {
            ActionCardFormBody(form, values, onValuesChange, state, submitting, candidates, formError, locale, agentStatusResolver)
        }
    }
}

/** The phone sheet's surface without its window, for screenshot fixtures. */
@Composable
internal fun RaftActionCardFormSheetSurfaceForFixture(
    form: ActionCardSpecForm,
    values: Map<String, ActionCardFormValue>,
    fieldErrors: List<ActionCardFormIssue>,
    submitting: Boolean,
    candidates: ActionCardFormCandidates,
    modifier: Modifier = Modifier,
    formError: String? = null,
    displayLocale: String = ActionCardFormDefaultLocale,
) {
    val state = rememberActionCardFormSheetState(form, fieldErrors)
    RaftDialogSheetSurface(
        title = form.title.render(displayLocale),
        commitLabel = form.submitLabel.render(displayLocale),
        onCommit = {},
        onClose = {},
        modifier = modifier,
        commitLoading = submitting,
        closeLabel = stringResource(Res.string.common_close),
        closeEnabled = !submitting,
    ) {
        ActionCardFormBody(form, values, {}, state, submitting, candidates, formError, displayLocale, EmptyRaftAgentStatusResolver)
    }
}

/** The sheet's own state: the client check's issues and which fields were edited since the server's. */
private class ActionCardFormSheetState(
    private val form: ActionCardSpecForm,
    private val serverIssues: List<ActionCardFormIssue>,
    clientIssuesState: MutableState<List<ActionCardFormIssue>>,
) {
    private var clientIssues by clientIssuesState
    private var editedSinceServer by mutableStateOf(emptySet<String>())

    fun edited(name: String) {
        if (name !in editedSinceServer) editedSinceServer = editedSinceServer + name
        if (clientIssues.any { it.field == name }) clientIssues = clientIssues.filter { it.field != name }
    }

    fun submit(values: Map<String, ActionCardFormValue>, submitting: Boolean, onSubmit: (Map<String, ActionCardFormValue>) -> Unit) {
        if (submitting) return
        val issues = checkActionCardFormValues(form, values)
        clientIssues = issues
        if (issues.isEmpty()) onSubmit(values)
    }

    /** The issue shown under [name]: the client's, else the server's until the field is edited. */
    fun issueFor(name: String): ActionCardFormIssue? =
        clientIssues.firstOrNull { it.field == name }
            ?: serverIssues.firstOrNull { it.field == name }?.takeIf { name !in editedSinceServer }

    /** Server issues for no field, or for a name the form does not have: shown at the top. */
    fun formIssues(): List<ActionCardFormIssue> {
        val names = form.fields.mapTo(HashSet()) { it.name }
        return serverIssues.filter { it.field == null || it.field !in names }
    }
}

/** New server issues (a new submit's answer) show again under every field; the client's issues outlive them. */
@Composable
private fun rememberActionCardFormSheetState(form: ActionCardSpecForm, fieldErrors: List<ActionCardFormIssue>): ActionCardFormSheetState {
    val clientIssues = remember(form) { mutableStateOf(emptyList<ActionCardFormIssue>()) }
    return remember(form, fieldErrors, clientIssues) { ActionCardFormSheetState(form, fieldErrors, clientIssues) }
}

@Composable
private fun ColumnScope.ActionCardFormBody(
    form: ActionCardSpecForm,
    values: Map<String, ActionCardFormValue>,
    onValuesChange: (Map<String, ActionCardFormValue>) -> Unit,
    state: ActionCardFormSheetState,
    submitting: Boolean,
    candidates: ActionCardFormCandidates,
    formError: String?,
    locale: String,
    agentStatusResolver: RaftAgentStatusResolver,
) {
    val enabled = !submitting
    val update = { name: String, value: ActionCardFormValue ->
        state.edited(name)
        onValuesChange(values + (name to value))
    }
    Column(
        Modifier
            .fillMaxWidth()
            .weight(1f, fill = false)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val banners = listOfNotNull(formError?.takeIf { it.isNotBlank() }) + state.formIssues().map { it.message.render(locale) }
        banners.distinct().forEach { RaftBanner(description = it, status = RaftBannerStatus.Warning) }
        form.description?.let {
            RaftText(it.render(locale), style = RaftTheme.typography.callout, color = RaftTheme.colors.foregroundMuted)
        }
        // A form with an unsupported field never opens (its button reads "Open in web").
        form.fields.filterIsInstance<ActionCardSpecKnownField>().forEach { field ->
            val error = state.issueFor(field.name)?.message?.render(locale)
            ActionCardFormField(field, values[field.name], { update(field.name, it) }, error, enabled, candidates, locale, agentStatusResolver)
        }
    }
}

@Composable
private fun ActionCardFormField(
    field: ActionCardSpecKnownField,
    value: ActionCardFormValue?,
    onValueChange: (ActionCardFormValue) -> Unit,
    error: String?,
    enabled: Boolean,
    candidates: ActionCardFormCandidates,
    locale: String,
    agentStatusResolver: RaftAgentStatusResolver,
) {
    val label = field.label.render(locale)
    val description = field.description?.render(locale)
    val placeholder = field.placeholder?.render(locale)
    when (field) {
        is ActionCardSpecTextInputField ->
            RaftField(label = label, description = description, error = error, enabled = enabled, required = field.required == true) {
                RaftTextField(
                    value = (value as? ActionCardFormValue.Text)?.value.orEmpty(),
                    onValueChange = { onValueChange(ActionCardFormValue.Text(it)) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = placeholder,
                )
            }
        is ActionCardSpecTextAreaField ->
            RaftField(label = label, description = description, error = error, enabled = enabled, required = field.required == true) {
                val rows = (field.rows ?: ActionCardFormTextareaDefaultRows).coerceIn(2, 10)
                RaftTextarea(
                    value = (value as? ActionCardFormValue.Text)?.value.orEmpty(),
                    onValueChange = { onValueChange(ActionCardFormValue.Text(it)) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = placeholder,
                    limit = field.maxLength,
                    minHeight = actionCardFormTextareaHeight(rows),
                    maxHeight = actionCardFormTextareaHeight(10),
                )
            }
        is ActionCardSpecSelectField ->
            RaftField(label = label, description = description, error = error, enabled = enabled, required = field.required == true) {
                val options = field.options.map { RaftComboboxOption(it.value, it.label.render(locale)) }
                // The empty line is built outside composition: fill the query into the resolved string.
                val noMatches = stringResource(Res.string.action_card_form_options_no_matches, "\u0001")
                RaftChoicePicker(
                    value = (value as? ActionCardFormValue.Text)?.value?.takeIf { it.isNotEmpty() },
                    options = options,
                    onValueChange = { onValueChange(ActionCardFormValue.Text(it)) },
                    title = label,
                    emptyText = { query -> noMatches.replace("\u0001", query.trim()) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = placeholder.orEmpty(),
                    searchPlaceholder = stringResource(Res.string.action_card_form_options_search),
                )
            }
        is ActionCardSpecToggleField ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                RaftFieldItem(label = label, description = description, enabled = enabled) {
                    RaftSwitch(
                        checked = (value as? ActionCardFormValue.Toggle)?.value ?: field.default ?: false,
                        onCheckedChange = { onValueChange(ActionCardFormValue.Toggle(it)) },
                        enabled = enabled,
                        label = label,
                    )
                }
                if (error != null) RaftFieldError(error, enabled = enabled)
            }
        is ActionCardSpecPickerField ->
            RaftField(label = label, description = description, error = error, enabled = enabled, required = field.required == true) {
                val ids = (value as? ActionCardFormValue.Ids)?.ids.orEmpty()
                val pick = { id: String -> onValueChange(ActionCardFormValue.Ids(actionCardFormPickerToggle(ids, id, field.multiple))) }
                val emptyText = field.emptyText?.render(locale)
                when (field) {
                    is ActionCardSpecChannelPickerField ->
                        ActionCardFormChannelPicker(
                            channels = if (field.scope == ActionCardSpecChannelScope.Joined) candidates.joinedChannels else candidates.visibleChannels,
                            selectedIds = ids,
                            multiple = field.multiple,
                            onPick = pick,
                            enabled = enabled,
                            loading = candidates.loading,
                            placeholder = placeholder,
                            emptyText = emptyText,
                        )
                    is ActionCardSpecMemberPickerField, is ActionCardSpecAgentPickerField ->
                        ActionCardFormMemberPicker(
                            members = actionCardFormMemberCandidates(field, candidates),
                            selectedIds = ids,
                            multiple = field.multiple,
                            onPick = pick,
                            enabled = enabled,
                            loading = candidates.loading,
                            emptyText = emptyText,
                            agentStatusResolver = agentStatusResolver,
                        )
                }
            }
    }
}

@Composable
private fun ActionCardFormMemberPicker(
    members: List<MemberProfile>,
    selectedIds: List<String>,
    multiple: Boolean,
    onPick: (String) -> Unit,
    enabled: Boolean,
    loading: Boolean,
    emptyText: String?,
    agentStatusResolver: RaftAgentStatusResolver,
) {
    if (members.isEmpty() && !loading && emptyText != null) {
        RaftText(emptyText, style = RaftTheme.typography.callout, color = RaftTheme.colors.foregroundHint)
        return
    }
    var query by remember { mutableStateOf(TextFieldValue()) }
    val selectedKeys =
        remember(members, selectedIds) {
            val picked = selectedIds.toSet()
            members.filter { it.id.value in picked }.mapTo(LinkedHashSet()) { RaftMemberPickerKey(it.kind, it.id.value) }
        }
    RaftMemberPicker(
        members = members,
        query = query,
        onQueryChange = { query = it },
        selectedKeys = selectedKeys,
        onToggle = { key -> onPick(key.id) },
        modifier = Modifier.fillMaxWidth(),
        mode = if (multiple) RaftMemberPickerMode.Multiple else RaftMemberPickerMode.Single,
        loading = loading,
        enabled = enabled,
        agentStatusResolver = agentStatusResolver,
        listMaxHeight = ActionCardFormPickerListMaxHeight,
    )
}

/**
 * The channel picker: a search field over a card list of the forward picker's rows (web
 * `ChannelPicker`, extracted from `ForwardComposerTargetList`), check boxes when [multiple],
 * the picked row bold otherwise.
 */
@Composable
private fun ActionCardFormChannelPicker(
    channels: List<RaftForwardTargetPickerItem<String>>,
    selectedIds: List<String>,
    multiple: Boolean,
    onPick: (String) -> Unit,
    enabled: Boolean,
    loading: Boolean,
    placeholder: String?,
    emptyText: String?,
) {
    val hint = RaftTheme.colors.foregroundHint
    if (channels.isEmpty()) {
        RaftText(
            if (loading) stringResource(Res.string.settings_members_loading) else emptyText ?: stringResource(Res.string.action_card_form_channels_empty),
            style = RaftTheme.typography.callout,
            color = hint,
        )
        return
    }
    var query by remember { mutableStateOf("") }
    val shown = remember(channels, query) { actionCardFormFilterChannels(channels, query) }
    val picked = selectedIds.toSet()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RaftTextField(
            value = query,
            onValueChange = { if (enabled) query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = placeholder ?: stringResource(Res.string.action_card_form_channels_search),
            enabled = enabled,
            leading = { RaftSlotIcon(RaftLucideIconName.Search) },
        )
        RaftListRowGroup(Modifier.fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = ActionCardFormPickerListMaxHeight).padding(vertical = 4.dp)) {
                if (shown.isEmpty()) {
                    item(key = "no-matches") {
                        RaftText(
                            stringResource(Res.string.action_card_form_channels_no_matches, query.trim()),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            style = RaftTheme.typography.callout,
                            color = hint,
                        )
                    }
                }
                items(shown, key = { it.rowKey }) { channel ->
                    RaftForwardPickerTargetRow(
                        target = channel,
                        selected = channel.payload in picked,
                        showCheckbox = multiple,
                        interactive = enabled,
                        onClick = { if (enabled) onPick(channel.payload) },
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Logic (pure, so commonTest covers it). The contract parts (Msg rendering, the client checks)
// are A1's, in build.raft.app.data.

/** The display locale a Msg falls back to, and the one fixtures use. */
internal const val ActionCardFormDefaultLocale = "en"

private const val ActionCardFormTextareaDefaultRows = 3

/** A picker's list scrolls inside its card past this (web `max-h-48`, as [RaftMemberPicker]). */
private val ActionCardFormPickerListMaxHeight: Dp = 192.dp

/** A textarea [rows] lines of `--field-*` (24dp) tall, plus its 12dp padding above and below. */
private fun actionCardFormTextareaHeight(rows: Int): Dp = (rows * 24 + 24).dp

/**
 * The Msg locale (contract `DISPLAY_LOCALES` key) for the language the App UI is shown in
 * (A1 [actionCardDisplayLocale]), so the server copy matches the sheet's own strings.
 */
@Composable
private fun rememberActionCardFormDisplayLocale(): String {
    val appLocale by KmmResourceLocaleRuntime.locale.collectAsState()
    return remember(appLocale) {
        (appLocale ?: AppLocale.System).actionCardDisplayLocale(runCatching { currentSystemLanguageTag() }.getOrNull())
    }
}

/** A Msg as plain text in [displayLocale] (else English); entities by their fallback name. */
private fun ActionCardSpecMsg.render(displayLocale: String): String = renderActionCardMsgText(this, displayLocale)

/** The values a form opens with: each field's default (pickers: the default refs' ids). */
internal fun initialActionCardFormValues(form: ActionCardSpecForm): Map<String, ActionCardFormValue> =
    form.fields.mapNotNull { field ->
        val value =
            when (field) {
                is ActionCardSpecTextInputField -> ActionCardFormValue.Text(field.default.orEmpty())
                is ActionCardSpecTextAreaField -> ActionCardFormValue.Text(field.default.orEmpty())
                is ActionCardSpecSelectField -> ActionCardFormValue.Text(field.default.orEmpty())
                is ActionCardSpecToggleField -> ActionCardFormValue.Toggle(field.default ?: false)
                is ActionCardSpecPickerField -> ActionCardFormValue.Ids(field.default.orEmpty().map { it.id })
                else -> null
            }
        value?.let { field.name to it }
    }.toMap()

/** The ids after tapping [id]: a multiple picker toggles it, a single one makes it the only pick. */
internal fun actionCardFormPickerToggle(ids: List<String>, id: String, multiple: Boolean): List<String> =
    when {
        !multiple -> listOf(id)
        id in ids -> ids - id
        else -> ids + id
    }

/** The member candidates of a member or agent picker, by its scope and kinds. */
internal fun actionCardFormMemberCandidates(field: ActionCardSpecPickerField, candidates: ActionCardFormCandidates): List<MemberProfile> =
    when (field) {
        is ActionCardSpecAgentPickerField -> candidates.serverMembers.filter { it.kind == MemberKind.Agent }
        is ActionCardSpecMemberPickerField -> {
            val pool = if (field.scope == ActionCardSpecMemberScope.Channel) candidates.channelMembers else candidates.serverMembers
            pool.filter { member ->
                when (member.kind) {
                    MemberKind.Agent -> ActionCardSpecMemberKind.Agent in field.kinds
                    MemberKind.Human -> ActionCardSpecMemberKind.Human in field.kinds
                    else -> false
                }
            }
        }
        is ActionCardSpecChannelPickerField -> emptyList()
    }

/** Channels whose name holds every word of [query], ignoring case; all of them for a blank query. */
internal fun actionCardFormFilterChannels(
    channels: List<RaftForwardTargetPickerItem<String>>,
    query: String,
): List<RaftForwardTargetPickerItem<String>> {
    val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return channels
    return channels.filter { channel ->
        val name = channel.title.lowercase()
        words.all { it in name }
    }
}
