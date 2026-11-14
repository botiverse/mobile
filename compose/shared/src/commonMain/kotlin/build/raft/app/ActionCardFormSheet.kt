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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
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
import build.raft.app.data.ActionCardSpecChoiceField
import build.raft.app.data.ActionCardSpecForm
import build.raft.app.data.cardCodePoints
import build.raft.app.data.isActionCardBlank
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
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftGlyph
import build.raft.designsystem.components.RaftGlyphIcon
import build.raft.designsystem.components.RaftMessageActionCardActions
import build.raft.designsystem.components.RaftCheckboxRow
import build.raft.designsystem.components.RaftRadioGroup
import build.raft.designsystem.components.RaftRadioRow
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftCombobox
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
 * submit label as the commit (with `submitLabels`, the label of the value chosen in its Select:
 * [ActionCardSpecForm.submitLabelFor]); the form's description, then its fields in order:
 *
 * - `TextInput` a [RaftTextField], `TextArea` a [RaftTextarea] with its counter, `Select` a
 *   [RaftCombobox] (the web's Select: a list up to 10 options, a searched sheet past that),
 *   `Toggle` a [RaftSwitch] row (never required);
 * - `MemberPicker` / `AgentPicker` the shared [RaftMemberPicker], `ChannelPicker` a list of
 *   [RaftForwardPickerTargetRow]s (the forward picker's rows). A picker's value is always a list
 *   of ids; one that is not `multiple` keeps at most one;
 * - `Choice` (a question card's, schemaVersion 8) every option at once as [RaftRadioRow]s, or
 *   [RaftCheckboxRow]s when `multiple`, each with its description; "Other" is the last row and,
 *   chosen, opens a one-line [RaftTextField] under the list for the person's own answer (at most
 *   its maxLength in code points). Nothing is chosen at first, so a required Choice not yet answered
 *   disables the commit with a line under it saying what is missing ("Choose an option first." /
 *   "Write your answer first."; web question-card spec §5.3, A′).
 *
 * A form with `steps` pages: a step a page under "Question n of total", Next (the commit) checking
 * only that page, Back (the head's arrow, or Back in the centred dialog's footer) keeping every
 * value, one submit on the last page; a server problem on another page turns to it. A question
 * card's form with `placement: "inline"` is answered on the card instead
 * ([ActionCardQuestionInlineForm]); any other `inline` form still opens here.
 *
 * Enter in "Other" does what the commit does (Next, or the submit on the last page), but not the
 * Enter that confirms an IME composition (web ActionCardChoiceField: Safari sends it with keyCode
 * 229): see [actionCardOtherEnterConfirms].
 *
 * The caller owns [values] (start from [initialActionCardFormValues]) and submitting. The sheet
 * checks only `required` and length on submit ([checkActionCardFormValues]); when they pass it
 * calls [onSubmit] with the values. [fieldErrors] are the server's issues (a 422 `FORM_INVALID`):
 * each shows under its field until that field is edited, and one without a field (or naming no
 * field of the form) shows in a banner at the top with [formError] (any other failure). That 422's
 * [fieldErrorsTraceId] shows once ([RaftTraceIdNote]): under the banners, else under the first
 * field whose server issue still shows ([actionCardTraceIdSpot]). While
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
    fieldErrorsTraceId: String? = null,
) {
    RaftDesignSystemTheme {
        val locale = rememberActionCardFormDisplayLocale()
        val state = rememberActionCardFormSheetState(form, fieldErrors)
        // A server problem under a field of another page turns to that page.
        LaunchedEffect(fieldErrors) { state.showServerIssuePage() }
        val nextLabel = stringResource(Res.string.action_card_question_next)
        RaftDialogSheet(
            onDismissRequest = { if (!submitting) onDismiss() },
            title = form.title.render(locale),
            commitLabel = state.commitLabel(values, locale, nextLabel),
            onCommit = { state.commit(values, submitting, onSubmit) },
            commitLoading = submitting,
            closeLabel = stringResource(Res.string.common_close),
            cancelLabel = stringResource(Res.string.common_cancel),
            closeEnabled = !submitting,
            onBack = if (state.page > 0) ({ state.back() }) else null,
            backLabel = stringResource(Res.string.action_card_question_back),
        ) {
            ActionCardFormBody(form, values, onValuesChange, state, submitting, candidates, formError, locale, agentStatusResolver, fieldErrorsTraceId) {
                state.commit(values, submitting, onSubmit)
            }
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
    /** The page of a form with steps the fixture shows (0-based). */
    page: Int = 0,
    /** Next or the submit already tried on that page: a required Choice unanswered says what it lacks. */
    tried: Boolean = false,
    fieldErrorsTraceId: String? = null,
) {
    val state = rememberActionCardFormSheetState(form, fieldErrors, initialPage = page, initialTried = tried)
    val nextLabel = stringResource(Res.string.action_card_question_next)
    RaftDialogSheetSurface(
        title = form.title.render(displayLocale),
        commitLabel = state.commitLabel(values, displayLocale, nextLabel),
        onCommit = {},
        onClose = {},
        modifier = modifier,
        commitLoading = submitting,
        closeLabel = stringResource(Res.string.common_close),
        closeEnabled = !submitting,
        onBack = if (state.page > 0) ({}) else null,
        backLabel = stringResource(Res.string.action_card_question_back),
    ) {
        ActionCardFormBody(form, values, {}, state, submitting, candidates, formError, displayLocale, EmptyRaftAgentStatusResolver, fieldErrorsTraceId) {}
    }
}

/** The sheet's own state: the client check's issues and which fields were edited since the server's. */
private class ActionCardFormSheetState(
    private val form: ActionCardSpecForm,
    private val serverIssues: List<ActionCardFormIssue>,
    clientIssuesState: MutableState<List<ActionCardFormIssue>>,
    private val pageState: MutableState<Int>,
    triedPagesState: MutableState<Set<Int>>,
) {
    private var clientIssues by clientIssuesState
    private var editedSinceServer by mutableStateOf(emptySet<String>())

    /** The sheet's pages: each step's fields in its order, else every field on one page. */
    val pages: List<ActionCardFormPage> = actionCardFormPages(form)
    val paged: Boolean get() = pages.size > 1

    /** The current page (0-based), kept in range. */
    var page: Int
        get() = pageState.value.coerceIn(0, pages.lastIndex)
        private set(value) {
            pageState.value = value.coerceIn(0, pages.lastIndex)
        }

    val lastPage: Boolean get() = page >= pages.lastIndex
    val pageFields: List<ActionCardSpecKnownField> get() = pages[page].fields
    val pageTitle: ActionCardSpecMsg? get() = if (paged) pages[page].title else null

    /** A required Choice of this page still unanswered holds Next or the submit back (A′). */
    fun pending(values: Map<String, ActionCardFormValue>): Boolean = actionCardFormHasPendingChoice(pageFields, values)

    /** The pages whose Next or submit was tried with a required Choice unanswered: only they say what it lacks. */
    private var triedPages by triedPagesState
    val pendingShown: Boolean get() = page in triedPages

    /** The commit's label: Next before the last page, else the form's (with `submitLabels`, the chosen one's). */
    fun commitLabel(values: Map<String, ActionCardFormValue>, locale: String, nextLabel: String): String =
        if (lastPage) form.submitLabelFor(values).render(locale) else nextLabel

    fun back() {
        if (page > 0) page -= 1
    }

    /** The first page with a server problem under one of its fields (none: the page stays). */
    fun showServerIssuePage() = showPageOfIssue(serverIssues)

    private fun showPageOfIssue(issues: List<ActionCardFormIssue>) {
        val first = pages.indexOfFirst { candidate -> candidate.fields.any { field -> issues.any { it.field == field.name } } }
        if (first >= 0 && first != page) page = first
    }

    fun edited(name: String) {
        if (name !in editedSinceServer) editedSinceServer = editedSinceServer + name
        if (clientIssues.any { it.field == name }) clientIssues = clientIssues.filter { it.field != name }
    }

    /**
     * The commit, and Enter in a text field: Next checks only this page's fields (the problems of the
     * other pages stay as they were) and turns the page; on the last page everything is checked and
     * submitted, a problem on another page turning to it. Nothing while submitting; while a required
     * Choice of this page is unanswered, only what it lacks shows under it.
     */
    fun commit(values: Map<String, ActionCardFormValue>, submitting: Boolean, onSubmit: (Map<String, ActionCardFormValue>) -> Unit) {
        if (submitting) return
        if (pending(values)) {
            triedPages = triedPages + page
            return
        }
        if (!lastPage) {
            val names = pageFields.mapTo(HashSet()) { it.name }
            val pageIssues = checkActionCardFormValues(form.copy(fields = pageFields), values)
            clientIssues = clientIssues.filter { it.field == null || it.field !in names } + pageIssues
            if (pageIssues.isEmpty()) page += 1
            return
        }
        val issues = checkActionCardFormValues(form, values)
        clientIssues = issues
        if (issues.isEmpty()) onSubmit(values) else showPageOfIssue(issues)
    }

    /** The issue shown under [name]: the client's, else the server's until the field is edited. */
    fun issueFor(name: String): ActionCardFormIssue? =
        clientIssues.firstOrNull { it.field == name }
            ?: serverIssues.firstOrNull { it.field == name }?.takeIf { name !in editedSinceServer }

    /** Whether [name]'s server issue shows under it: not edited since, and not covered by the client's own. */
    fun serverIssueShown(name: String): Boolean =
        clientIssues.none { it.field == name } && name !in editedSinceServer && serverIssues.any { it.field == name }

    /** Server issues for no field, or for a name the form does not have: shown at the top. */
    fun formIssues(): List<ActionCardFormIssue> {
        val names = form.fields.mapTo(HashSet()) { it.name }
        return serverIssues.filter { it.field == null || it.field !in names }
    }
}

/** New server issues (a new submit's answer) show again under every field; the client's issues outlive them. */
@Composable
private fun rememberActionCardFormSheetState(
    form: ActionCardSpecForm,
    fieldErrors: List<ActionCardFormIssue>,
    initialPage: Int = 0,
    initialTried: Boolean = false,
): ActionCardFormSheetState {
    val clientIssues = remember(form) { mutableStateOf(emptyList<ActionCardFormIssue>()) }
    val page = remember(form) { mutableStateOf(initialPage) }
    val triedPages = remember(form) { mutableStateOf(if (initialTried) setOf(initialPage) else emptySet()) }
    return remember(form, fieldErrors, clientIssues, page, triedPages) { ActionCardFormSheetState(form, fieldErrors, clientIssues, page, triedPages) }
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
    issuesTraceId: String?,
    onEnter: () -> Unit,
) {
    val enabled = !submitting
    val update = { name: String, value: ActionCardFormValue ->
        state.edited(name)
        onValuesChange(values + (name to value))
    }
    val scroll = rememberScrollState()
    // A page turn starts at the top of the new page.
    LaunchedEffect(state.page) { scroll.scrollTo(0) }
    Column(
        Modifier
            .fillMaxWidth()
            .weight(1f, fill = false)
            .imePadding()
            .verticalScroll(scroll)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val issueBanners = state.formIssues().map { it.message.render(locale) }
        val banners = listOfNotNull(formError?.takeIf { it.isNotBlank() }) + issueBanners
        banners.distinct().forEach { RaftBanner(description = it, status = RaftBannerStatus.Warning) }
        // The 422's trace id, once: under its banners, else under the first field it put an issue under.
        val traceSpot = actionCardTraceIdSpot(issuesTraceId, issueBanners.isNotEmpty(), state.pageFields.map { it.name }, state::serverIssueShown)
        if (traceSpot == ActionCardTraceIdSpot.UnderBanners) RaftTraceIdNote(issuesTraceId)
        if (state.paged) {
            RaftText(
                stringResource(Res.string.action_card_question_page, state.page + 1, state.pages.size),
                style = RaftTheme.typography.footnote,
                color = RaftTheme.colors.foregroundHint,
            )
        }
        form.description?.let {
            RaftText(it.render(locale), style = RaftTheme.typography.callout, color = RaftTheme.colors.foregroundMuted)
        }
        state.pageTitle?.let {
            RaftText(it.render(locale), style = RaftTheme.typography.callout.copy(fontWeight = FontWeight.SemiBold), color = RaftTheme.colors.foreground)
        }
        // A form with an unsupported field never opens (its button reads "Open in web").
        state.pageFields.forEach { field ->
            val error = state.issueFor(field.name)?.message?.render(locale)
            ActionCardFormField(field, values[field.name], { update(field.name, it) }, error, enabled, candidates, locale, agentStatusResolver, onEnter, state.pendingShown)
            if (traceSpot == ActionCardTraceIdSpot.UnderField(field.name)) RaftTraceIdNote(issuesTraceId)
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
    onEnter: () -> Unit,
    showPending: Boolean,
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
                // Web ActionCardFormSheet: a Select at any count (ComboboxSelect past ten), never
                // radios (artin 2026-11-02).
                RaftCombobox(
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
        is ActionCardSpecChoiceField ->
            ActionCardChoiceField(
                field = field,
                value = (value as? ActionCardFormValue.Choice) ?: ActionCardFormValue.Choice(emptyList()),
                onValueChange = onValueChange,
                error = error,
                enabled = enabled,
                locale = locale,
                onEnter = onEnter,
                showPending = showPending,
            )
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

/**
 * A Choice (web `ChoiceFieldControl`): the label (with "(optional)" when optional), the question's
 * description, every option as a radio row (single) or checkbox row (multiple) with its description,
 * "Other" last, the one-line input under the list while Other is chosen, then the issue (by [RaftField])
 * or, for a required Choice, what it still lacks. The value is [ActionCardFormValue.Choice]: `other` is
 * non-null while Other is chosen, even empty. Choosing a single option drops Other; unticking Other
 * keeps what was typed for the next time it is chosen.
 *
 * On a question card answered inline ([ActionCardQuestionInlineForm]): [number] goes before the
 * question's text when the card asks several ("2. …", web `ChoiceFieldControl` `number`), and
 * [onPicked] hears a single option tapped (not Other…), which moves the form on (spec §5.10).
 * [showLabel] false draws no label: a slim card whose title is its one question.
 * Other reads the app's "Other" when the server sent its known key ([questionOtherLabel]).
 *
 * A confirm ([isConfirm]: one option, no Other; slock #14497) is not a list of one row but one
 * bordered button with the option's label ([ActionCardConfirmButton]), checked once chosen.
 * Pressing it chooses the option and moves on ([onPicked]); pressed again, an optional one is
 * taken back ([confirmPressed]). [confirmInActions]: it stands in the card's actions row (the
 * card's only question), and [confirmLoading] shows the send it started.
 */
@Composable
internal fun ActionCardChoiceField(
    field: ActionCardSpecChoiceField,
    value: ActionCardFormValue.Choice,
    onValueChange: (ActionCardFormValue) -> Unit,
    error: String?,
    enabled: Boolean,
    locale: String,
    onEnter: () -> Unit,
    number: Int? = null,
    onPicked: (() -> Unit)? = null,
    showPending: Boolean = false,
    showLabel: Boolean = true,
    confirmInActions: Boolean = false,
    confirmLoading: Boolean = false,
) {
    val other = field.other
    val otherLabel = other?.let { questionOtherLabel(it.label, locale) }
    val otherChosen = other != null && value.other != null
    // What a deselected Other had: choosing it again brings the text back.
    var otherDraft by remember { mutableStateOf(value.other.orEmpty()) }
    // Other was just chosen by a tap: its input takes focus once drawn.
    var focusOther by remember { mutableStateOf(false) }
    // A required Choice speaks only to someone who can answer it, once they tried to go on, and not over an issue.
    val pending = if (field.required == true && enabled && showPending && error == null) actionCardChoicePending(value) else null
    val label = field.label.render(locale)
    RaftField(
        label = if (!showLabel) null else if (number != null) "$number. $label" else label,
        description = field.description?.render(locale),
        error = error,
        enabled = enabled,
        required = showLabel && field.required == true,
        optional = showLabel && field.required != true,
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (field.isConfirm) {
                val option = field.options.single()
                val press = {
                    val next = field.confirmPressed(value)
                    if (next != null) onValueChange(next)
                    // Taken back: nothing to move on to.
                    if (next == null || next.selected.isNotEmpty()) onPicked?.invoke()
                }
                val button = @Composable {
                    ActionCardConfirmButton(
                        label = option.label.render(locale),
                        chosen = option.value in value.selected,
                        onClick = { if (enabled && !confirmLoading) press() },
                        enabled = enabled || confirmLoading,
                        loading = confirmLoading,
                    )
                }
                if (confirmInActions) RaftMessageActionCardActions { button() } else button()
            } else if (field.multiple) {
                field.options.forEach { option ->
                    RaftCheckboxRow(
                        label = option.label.render(locale),
                        checked = option.value in value.selected,
                        onCheckedChange = { checked ->
                            // In the options' order, whatever order they were ticked in.
                            val next = field.options.map { it.value }.filter { if (it == option.value) checked else it in value.selected }
                            onValueChange(ActionCardFormValue.Choice(next, value.other))
                        },
                        description = option.description?.render(locale),
                        enabled = enabled,
                    )
                }
                if (other != null) {
                    RaftCheckboxRow(
                        label = otherLabel.orEmpty(),
                        checked = otherChosen,
                        onCheckedChange = { checked ->
                            if (checked) focusOther = true
                            onValueChange(ActionCardFormValue.Choice(value.selected, if (checked) value.other ?: otherDraft else null))
                        },
                        description = other.description?.render(locale),
                        enabled = enabled,
                    )
                }
            } else {
                RaftRadioGroup {
                    field.options.forEach { option ->
                        RaftRadioRow(
                            label = option.label.render(locale),
                            selected = !otherChosen && option.value in value.selected,
                            onClick = {
                                onValueChange(ActionCardFormValue.Choice(listOf(option.value)))
                                onPicked?.invoke()
                            },
                            description = option.description?.render(locale),
                            enabled = enabled,
                        )
                    }
                    if (other != null) {
                        RaftRadioRow(
                            label = otherLabel.orEmpty(),
                            selected = otherChosen,
                            onClick = {
                                if (!otherChosen) {
                                    focusOther = true
                                    onValueChange(ActionCardFormValue.Choice(emptyList(), value.other ?: otherDraft))
                                }
                            },
                            description = other.description?.render(locale),
                            enabled = enabled,
                        )
                    }
                }
            }
            if (otherChosen && other != null) {
                ActionCardChoiceOtherInput(
                    text = value.other.orEmpty(),
                    onTextChange = {
                        otherDraft = it
                        onValueChange(ActionCardFormValue.Choice(value.selected, it))
                    },
                    maxLength = other.maxLength,
                    placeholder = other.placeholder?.render(locale) ?: stringResource(Res.string.action_card_question_other_placeholder),
                    focus = focusOther,
                    onFocusHandled = { focusOther = false },
                    onEnter = onEnter,
                )
            }
            if (pending != null) {
                RaftText(
                    stringResource(
                        if (pending == ActionCardChoicePending.Write) Res.string.action_card_question_write_first else Res.string.action_card_question_choose_first,
                    ),
                    style = RaftTheme.typography.caption,
                    color = RaftTheme.colors.foregroundHint,
                )
            }
        }
    }
}

/**
 * A confirm's one button (web `ChoiceFieldControl` confirm, slock #14497): the card's bordered
 * button ([RaftButtonVariant.Default], as its own submit; never the pink one on a card) with the
 * option's label, a check before the label once [chosen] (pressed). The same on a question card's
 * button row (C), a slim card (B) and the sheet.
 */
@Composable
internal fun ActionCardConfirmButton(
    label: String,
    chosen: Boolean,
    onClick: () -> Unit,
    enabled: Boolean,
    loading: Boolean = false,
) {
    RaftButton(
        text = label,
        onClick = onClick,
        modifier = Modifier.semantics { selected = chosen },
        variant = RaftButtonVariant.Default,
        enabled = enabled,
        loading = loading,
        leadingIcon = if (chosen) ({ RaftGlyphIcon(RaftGlyph.Check, size = 16.dp, color = RaftTheme.colors.foreground) }) else null,
    )
}

/**
 * The one-line input under a Choice's list for the person's own answer: at most [maxLength] code
 * points, Done = the commit ([onEnter]) unless an IME composition is open ([actionCardOtherEnterConfirms]).
 */
@Composable
internal fun ActionCardChoiceOtherInput(
    text: String,
    onTextChange: (String) -> Unit,
    maxLength: Int,
    placeholder: String,
    focus: Boolean,
    onFocusHandled: () -> Unit,
    onEnter: () -> Unit,
) {
    var field by remember { mutableStateOf(TextFieldValue(text)) }
    LaunchedEffect(text) { if (field.text != text) field = TextFieldValue(text, TextRange(text.length)) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (focus) {
            runCatching { focusRequester.requestFocus() }
            onFocusHandled()
        }
    }
    RaftTextField(
        value = field,
        onValueChange = { next ->
            val clipped = actionCardClipCodePoints(next.text, maxLength)
            field = if (clipped == next.text) next else TextFieldValue(clipped, TextRange(clipped.length))
            if (field.text != text) onTextChange(field.text)
        },
        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
        placeholder = placeholder,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (actionCardOtherEnterConfirms(field.composition)) onEnter() }),
    )
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
                is ActionCardSpecChoiceField -> field.default ?: ActionCardFormValue.Choice(emptyList())
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

// ---------------------------------------------------------------------------
// Question card logic (pure).

/** One page of the sheet: a step's [title] and its [fields] (the whole form when it has no steps). */
internal data class ActionCardFormPage(val title: ActionCardSpecMsg?, val fields: List<ActionCardSpecKnownField>)

/**
 * The sheet's pages (web `formPages`): each step's fields in its order, a step naming none this
 * build draws skipped; fewer than two pages is one page with every field, as a form without steps.
 * A form with an unsupported field never opens, so only known fields are drawn.
 */
internal fun actionCardFormPages(form: ActionCardSpecForm): List<ActionCardFormPage> {
    val known = form.fields.filterIsInstance<ActionCardSpecKnownField>()
    val byName = known.associateBy { it.name }
    val pages =
        form.steps.orEmpty()
            .map { step -> ActionCardFormPage(step.title, step.fields.mapNotNull { byName[it] }) }
            .filter { it.fields.isNotEmpty() }
    return if (pages.size > 1) pages else listOf(ActionCardFormPage(null, known))
}

/** What a required Choice still lacks before it can be answered (web `choicePending`). */
internal enum class ActionCardChoicePending {
    /** Nothing is chosen. */
    Choose,

    /** Other is chosen and empty. */
    Write,
}

internal fun actionCardChoicePending(value: ActionCardFormValue.Choice): ActionCardChoicePending? {
    val other = value.other
    if (other != null && isActionCardBlank(other)) return ActionCardChoicePending.Write
    return if (value.selected.isEmpty() && other == null) ActionCardChoicePending.Choose else null
}

/** Whether a required Choice among [fields] is still unanswered (web `hasPendingChoice`): it holds Next or the submit back. */
internal fun actionCardFormHasPendingChoice(fields: List<ActionCardSpecKnownField>, values: Map<String, ActionCardFormValue>): Boolean =
    fields.any { field ->
        field is ActionCardSpecChoiceField && field.required == true &&
            actionCardChoicePending((values[field.name] as? ActionCardFormValue.Choice) ?: ActionCardFormValue.Choice(emptyList())) != null
    }

/**
 * Whether the Enter/Done of "Other" counts as a press of the commit: not while an IME composition
 * is open. The Enter that confirms CJK input is the IME's (web: `isComposing`, and keyCode 229 on
 * Safari), so it must not move the form on.
 */
internal fun actionCardOtherEnterConfirms(composition: TextRange?): Boolean = composition == null

/** [text] cut to at most [max] Unicode code points (an "Other" answer's `maxLength` counts code points, never splitting a pair). */
internal fun actionCardClipCodePoints(text: String, max: Int): String {
    if (cardCodePoints(text) <= max) return text
    var index = 0
    var count = 0
    while (index < text.length && count < max) {
        index += if (text[index].isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate()) 2 else 1
        count++
    }
    return text.substring(0, index)
}
