package build.raft.app

import build.raft.app.resources.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import build.raft.app.data.ActionCardFormIssue
import build.raft.app.data.ActionCardFormValue
import build.raft.app.data.ActionCardSpecAction
import build.raft.app.data.ActionCardSpecButton
import build.raft.app.data.ActionCardSpecCard
import build.raft.app.data.ActionCardSpecChoiceField
import build.raft.app.data.ActionCardSpecDisplayElement
import build.raft.app.data.ActionCardSpecForm
import build.raft.app.data.ActionCardSpecFormPlacement
import build.raft.app.data.ActionCardSpecMsg
import build.raft.app.data.ActionCardSpecTree
import build.raft.app.data.ActionCardSpecElement
import build.raft.app.data.CardStack
import build.raft.app.data.QuestionAskActionCardType
import build.raft.app.data.ThreadActionCard
import build.raft.app.data.checkActionCardFormValues
import build.raft.app.data.renderActionCardMsgText
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerSize
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftMessageActionCardActions
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// A question card answered on the card itself (web QuestionInlineForm.tsx, question-card spec
// §5.9, §5.10; botiverse/slock staging `docs/development/action-card-forms.md`). The server
// decides: only a form with `placement: "inline"` (questionAskPlacement: 1-3 choice questions,
// short options, no pages) is answered here; every other card keeps its button and the sheet.

/** How long a question card's answer waits for an Undo before it is sent (web `QUESTION_SUBMIT_UNDO_MS`, slock #13455). */
internal const val QUESTION_SUBMIT_UNDO_MS: Long = 1_500L

/** How long a tapped option shows chosen before the form moves on (web `CHOICE_PICK_HIGHLIGHT_MS`). */
internal const val QUESTION_CHOICE_PICK_HIGHLIGHT_MS: Long = 150L

/** A question card's form answered on the card, and the button that would open it in the sheet (web `InlineQuestionForm`). */
internal data class ActionCardInlineQuestionForm(
    val formId: String,
    val form: ActionCardSpecForm,
    val fields: List<ActionCardSpecChoiceField>,
    val buttonId: String,
    /** The button's label ("Answer Question(s)"): the card's one submit says the same. */
    val buttonLabel: ActionCardSpecMsg,
)

/**
 * The form a card's body asks inline (web `inlineQuestionForm`): the first `open_form` button's
 * form when it is `placement: "inline"` and every field a Choice; else null, and the button opens
 * the sheet as before. A form whose Choice this App could not read never gets here: strip made its
 * button "Open in web".
 */
internal fun ActionCardSpecCard.inlineQuestionForm(): ActionCardInlineQuestionForm? {
    for ((buttonId, node) in spec.elements) {
        val button = node as? ActionCardSpecButton ?: continue
        val action = button.action as? ActionCardSpecAction.OpenForm ?: continue
        val form = forms?.get(action.form)
        if (form == null || form.placement != ActionCardSpecFormPlacement.Inline) return null
        val fields = form.fields.filterIsInstance<ActionCardSpecChoiceField>()
        return if (fields.isNotEmpty() && fields.size == form.fields.size) {
            ActionCardInlineQuestionForm(action.form, form, fields, buttonId, button.label)
        } else {
            null
        }
    }
    return null
}

/**
 * The inline form of [this] question card as drawn now (web `ActionCardSpecCard`): only a
 * `question:ask` card still waiting for its answer. Frozen, it is drawn with its options disabled;
 * answered, expired or withdrawn, or waiting on Confirm again, there is none (the card shows its
 * record, its ended line, or its button).
 */
internal fun ThreadActionCard.inlineQuestionForm(spec: ActionCardSpecCard): ActionCardInlineQuestionForm? {
    if (!type.equals(QuestionAskActionCardType, ignoreCase = true)) return null
    if (isExecuted || hasEnded || needsReconfirmation) return null
    return spec.inlineQuestionForm()
}

/**
 * The body without what the inline form draws itself (web `withoutInlineQuestions`): its button,
 * and each question's row (the server's Field `q_<id>`), which the form shows numbered over its
 * options. A Stack left with nothing in it goes too.
 */
internal fun ActionCardSpecTree.withoutInlineQuestions(inline: ActionCardInlineQuestionForm): ActionCardSpecTree {
    val rows = inline.fields.mapTo(HashSet()) { "q_${it.name}" }
    return withoutElements { id, node ->
        (node is ActionCardSpecButton && id == inline.buttonId) || (node is ActionCardSpecDisplayElement.Field && id in rows)
    }
}

/** The tree without the elements [drop] picks (never the root), and without the Stacks that leaves empty. */
internal fun ActionCardSpecTree.withoutElements(drop: (id: String, node: ActionCardSpecElement) -> Boolean): ActionCardSpecTree {
    val removed = elements.filter { (id, node) -> id != root && drop(id, node) }.keys.toMutableSet()
    if (removed.isEmpty()) return this
    // Stacks emptied by the removal, innermost first.
    var changed = true
    while (changed) {
        changed = false
        elements.forEach { (id, node) ->
            if (id != root && id !in removed && node is CardStack && node.children.orEmpty().isNotEmpty() && node.children.orEmpty().all { it in removed }) {
                removed += id
                changed = true
            }
        }
    }
    val kept =
        elements.filterKeys { it !in removed }.mapValues { (_, node) ->
            if (node is CardStack && node.children.orEmpty().any { it in removed }) node.copy(children = node.children.orEmpty().filterNot { it in removed }) else node
        }
    return ActionCardSpecTree(root, kept)
}

/**
 * Whether [this] Choice is a confirm (web `isConfirmChoice`, slock #14497): a single one of exactly
 * one option and no Other. A question card's one-option question comes as this, single or multiple
 * alike (shared `isQuestionAskConfirm` / `questionField`). There is nothing to choose between, so it
 * is drawn as one bordered button with the option's label ([ActionCardConfirmButton]), not a list of
 * one row; its answer is that option, as any choice's.
 */
internal val ActionCardSpecChoiceField.isConfirm: Boolean
    get() = !multiple && options.size == 1 && other == null

/** Whether [fields] is a lone confirm: the card's one question, whose button stands where the card's own submit would. */
internal fun actionCardLoneConfirm(fields: List<ActionCardSpecChoiceField>): Boolean = fields.singleOrNull()?.isConfirm == true

/**
 * What pressing a confirm does to its [value] (web `ChoiceFieldControl` `pressConfirm`): not chosen,
 * it chooses the option; chosen and optional, it takes it back (nothing else on the form could
 * unchoose it); chosen and required, it stays chosen. Null: the value stays as it is.
 */
internal fun ActionCardSpecChoiceField.confirmPressed(value: ActionCardFormValue.Choice): ActionCardFormValue.Choice? {
    val option = options.singleOrNull()?.value ?: return null
    val chosen = option in value.selected
    return when {
        !chosen -> ActionCardFormValue.Choice(listOf(option))
        required != true -> ActionCardFormValue.Choice(emptyList())
        else -> null
    }
}

/** Where a card's or sheet's one trace id note goes ([actionCardTraceIdSpot]). */
internal sealed interface ActionCardTraceIdSpot {
    /** Under the server's banners (an issue for no field shows). */
    data object UnderBanners : ActionCardTraceIdSpot

    /** Under the first field whose server issue shows. */
    data class UnderField(val name: String) : ActionCardTraceIdSpot
}

/**
 * Where a refused form's trace id goes (AGENTS.md "Errors carry the trace ID"): once per card or
 * sheet, under the server's banners when [bannerShown], else under the first of [fieldNames] whose
 * server issue still shows ([serverIssueShown]: not edited away, not covered by the client's own).
 * Null without a valid [traceId] or once no server issue shows.
 */
internal fun actionCardTraceIdSpot(
    traceId: String?,
    bannerShown: Boolean,
    fieldNames: List<String>,
    serverIssueShown: (String) -> Boolean,
): ActionCardTraceIdSpot? {
    if (build.raft.app.network.raftValidTraceIdOrNull(traceId) == null) return null
    if (bannerShown) return ActionCardTraceIdSpot.UnderBanners
    return fieldNames.firstOrNull(serverIssueShown)?.let { ActionCardTraceIdSpot.UnderField(it) }
}

/**
 * Whether tapping an option answers the whole form (web `tapSubmitsForm`, spec §5.10): its one
 * question is a single Choice and Other… isn't chosen. Such a form shows no submit button.
 */
internal fun actionCardTapSubmitsForm(fields: List<ActionCardSpecChoiceField>, values: Map<String, ActionCardFormValue>): Boolean {
    val only = fields.singleOrNull() ?: return false
    if (only.multiple) return false
    return (values[only.name] as? ActionCardFormValue.Choice)?.other == null
}

/**
 * A question card's answer held back for a moment (web `useUndoableSubmit`, spec §5.10): [start]
 * waits [waitMillis] while the card says "Submitted · Undo", then [send]s. [undo] within it takes
 * the answer back (nothing was sent: neither the server nor the agent sees a corrected answer);
 * [flush] sends it at once (the card leaving the screen, the app going to the background). The
 * answer is taken before it is sent, so it goes once whichever comes first; a second [start]
 * while one waits does nothing.
 */
internal class QuestionUndoableSubmit<T : Any>(
    private val scope: CoroutineScope,
    private val waitMillis: Long = QUESTION_SUBMIT_UNDO_MS,
    private val send: (T) -> Unit,
) {
    private var pending: Pair<T, Job>? = null

    /** Whether an answer waits (the card shows "Submitted · Undo"). */
    var waiting: Boolean by mutableStateOf(false)
        private set

    fun start(payload: T) {
        if (pending != null) return
        val timer = scope.launch {
            delay(waitMillis)
            take(cancelTimer = false)?.let(send)
        }
        // A scope already cancelled ran nothing: send now rather than lose the answer.
        if (timer.isCancelled) {
            send(payload)
            return
        }
        pending = payload to timer
        waiting = true
    }

    fun undo() {
        take(cancelTimer = true)
    }

    fun flush() {
        take(cancelTimer = true)?.let(send)
    }

    private fun take(cancelTimer: Boolean): T? {
        val current = pending ?: return null
        pending = null
        waiting = false
        if (cancelTimer) current.second.cancel()
        return current.first
    }
}

/**
 * [QuestionUndoableSubmit] for one form, sent at once when the card leaves (another page, scrolled
 * away) or the app goes to the background.
 */
@Composable
internal fun rememberQuestionUndoableSubmit(
    formId: String,
    onSend: (Map<String, ActionCardFormValue>) -> Unit,
): QuestionUndoableSubmit<Map<String, ActionCardFormValue>> {
    val scope = rememberCoroutineScope()
    val currentSend by rememberUpdatedState(onSend)
    val undoable = remember(formId) { QuestionUndoableSubmit<Map<String, ActionCardFormValue>>(scope) { currentSend(it) } }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(undoable, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) undoable.flush() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            undoable.flush()
        }
    }
    return undoable
}

/**
 * A question card answered in its body (web `QuestionInlineForm`): each question numbered when
 * there are several, over its options ([ActionCardChoiceField]: radios or checkboxes, Other… last
 * with its one-line input), then the card's one button labelled as the button that would open the
 * sheet. A single choice tapped moves on after [QUESTION_CHOICE_PICK_HIGHLIGHT_MS]: after the last
 * question it submits; a lone single choice has no button until Other… is chosen. A multiple choice
 * or an Other… answer submits with the button. A submit runs the client checks (required, lengths),
 * then waits [QUESTION_SUBMIT_UNDO_MS] with "Submitted · Undo" before [onSend].
 *
 * A question of one option is a confirm ([isConfirm], slock #14497): one bordered button with the
 * option's label, in the card's actions row when it is the card's only question (where the submit
 * would be; pressing it answers), else under its question (pressing it moves on as a tapped radio
 * does, the card's submit sends the form).
 *
 * [values]: the choices so far (the store's, so they outlive a scroll away); [serverIssues]: the
 * last 422's, and [serverIssuesTraceId] its trace id, shown once under them. [canSubmit]: this
 * viewer may answer now (else the options are disabled, and while an answer waits no Undo shows); [showSubmit]: whether the button is drawn at all; [submitting]:
 * the answer is in flight.
 */
@Composable
internal fun ActionCardQuestionInlineForm(
    inline: ActionCardInlineQuestionForm,
    values: Map<String, ActionCardFormValue>,
    onValuesChange: (Map<String, ActionCardFormValue>) -> Unit,
    serverIssues: List<ActionCardFormIssue>,
    canSubmit: Boolean,
    showSubmit: Boolean,
    submitting: Boolean,
    displayLocale: String,
    onSend: (Map<String, ActionCardFormValue>) -> Unit,
    serverIssuesTraceId: String? = null,
) {
    val scope = rememberCoroutineScope()
    // Leaving (the card scrolled away, another page) or the app going to the background sends at once.
    val undoable = rememberQuestionUndoableSubmit(inline.formId, onSend)
    var clientIssues by remember(inline.formId) { mutableStateOf(emptyList<ActionCardFormIssue>()) }
    val latestValues by rememberUpdatedState(values)
    val waiting = undoable.waiting
    val busy = submitting || waiting
    // The submit tried with a required question unanswered: only then does each say what it lacks.
    var pendingShown by remember(inline.formId) { mutableStateOf(false) }
    val tapSubmits = actionCardTapSubmitsForm(inline.fields, values)
    // The card's one question is a confirm: its button is the card's button, and shows the send's wait itself.
    val loneConfirm = actionCardLoneConfirm(inline.fields)
    val fieldNames = inline.fields.map { it.name }

    val submit = submit@{
        val now = latestValues
        if (submitting || undoable.waiting || !canSubmit) return@submit
        if (actionCardFormHasPendingChoice(inline.fields, now)) {
            pendingShown = true
            return@submit
        }
        val issues = checkActionCardFormValues(inline.form, now)
        clientIssues = issues
        if (issues.isEmpty()) undoable.start(now)
    }
    var advanceJob by remember { mutableStateOf<Job?>(null) }
    // A single option tapped: shown chosen for a moment, then, after the last question, the submit.
    val picked = { name: String ->
        advanceJob?.cancel()
        advanceJob =
            scope.launch {
                delay(QUESTION_CHOICE_PICK_HIGHLIGHT_MS)
                // Other… chosen meanwhile: the person is typing their own answer.
                if ((latestValues[name] as? ActionCardFormValue.Choice)?.other == null && fieldNames.lastOrNull() == name) submit()
            }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        val names = fieldNames.toSet()
        val banners = serverIssues.filter { it.field == null || it.field !in names }.map { renderActionCardMsgText(it.message, displayLocale) }.distinct()
        banners.forEach {
            RaftBanner(description = it, status = RaftBannerStatus.Warning, size = RaftBannerSize.Sm)
        }
        val traceSpot =
            actionCardTraceIdSpot(serverIssuesTraceId, banners.isNotEmpty(), fieldNames) { name ->
                clientIssues.none { it.field == name } && serverIssues.any { it.field == name }
            }
        if (traceSpot == ActionCardTraceIdSpot.UnderBanners) RaftTraceIdNote(serverIssuesTraceId)
        inline.fields.forEachIndexed { index, field ->
            val issue = clientIssues.firstOrNull { it.field == field.name } ?: serverIssues.firstOrNull { it.field == field.name }
            ActionCardChoiceField(
                field = field,
                value = (values[field.name] as? ActionCardFormValue.Choice) ?: ActionCardFormValue.Choice(emptyList()),
                onValueChange = { value ->
                    if (!busy && canSubmit) {
                        clientIssues = clientIssues.filter { it.field != field.name }
                        onValuesChange(values + (field.name to value))
                    }
                },
                error = issue?.let { renderActionCardMsgText(it.message, displayLocale) },
                enabled = !busy && canSubmit,
                locale = displayLocale,
                onEnter = { submit() },
                number = if (inline.fields.size > 1) index + 1 else null,
                onPicked = if (canSubmit) ({ picked(field.name) }) else null,
                showPending = pendingShown,
                // One question: the slim card's title is the question, so no label repeats it.
                showLabel = inline.fields.size > 1,
                confirmInActions = loneConfirm,
                confirmLoading = loneConfirm && submitting,
            )
            if (traceSpot == ActionCardTraceIdSpot.UnderField(field.name)) RaftTraceIdNote(serverIssuesTraceId)
        }
        when {
            showSubmit && waiting && canSubmit ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    RaftText(
                        stringResource(Res.string.action_card_question_submitted) + " ·",
                        style = RaftTheme.typography.callout,
                        color = RaftTheme.colors.foregroundMuted,
                    )
                    RaftButton(
                        text = stringResource(Res.string.action_card_question_undo),
                        onClick = { undoable.undo() },
                        variant = RaftButtonVariant.Ghost,
                    )
                }
            showSubmit && !waiting && !loneConfirm && (!tapSubmits || submitting) ->
                RaftMessageActionCardActions {
                    RaftButton(
                        text = renderActionCardMsgText(inline.buttonLabel, displayLocale),
                        onClick = { submit() },
                        variant = RaftButtonVariant.Default,
                        enabled = canSubmit && !busy,
                        loading = submitting,
                    )
                }
        }
    }
}
