package build.raft.app

import build.raft.app.resources.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.ActionCardFormIssue
import build.raft.app.data.ActionCardFormValue
import build.raft.app.data.ActionCardSpecCard
import build.raft.app.data.ActionCardSpecChoiceField
import build.raft.app.data.ActionCardSpecDisplayElement
import build.raft.app.data.ActionCardSpecFieldValue
import build.raft.app.data.ActionCardSpecMsg
import build.raft.app.data.ActionCardSpecMsgParam
import build.raft.app.data.ActionCardSpecTree
import build.raft.app.data.QuestionAskActionCardType
import build.raft.app.data.ThreadActionCard
import build.raft.app.data.checkActionCardFormValues
import build.raft.app.data.renderActionCardMsgText
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerSize
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftMessageActionCardDefaults
import build.raft.designsystem.components.RaftMessageActionCardHeaderDescription
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme

// Question cards, redesigned (design lead's choice, 2026-11; the web does the same). Two looks:
// C, a row of buttons under the message (no card), for one short single choice the server placed
// on the card; B, a slim card, for every other question card. The rules are Jasper's.

/** How a `question:ask` card is drawn. */
internal enum class QuestionCardLayout {
    /** C: no frame; the question in semibold, a wrap row of outlined option buttons and Other, a hint line. */
    ButtonRow,

    /** B: a card without icon or "Answer question(s)" title; a single question is the title. */
    SlimCard,
}

/** At most this many options (Other not counted) are drawn as C's button row. */
internal const val QUESTION_BUTTON_ROW_MAX_OPTIONS: Int = 4

/**
 * C's options are at most this many columns wide each (Other not counted; a CJK or fullwidth
 * character counts 2): the server's `questionAskPlacement` limit, checked here only where the form
 * that carried `placement` is gone.
 */
internal const val QUESTION_BUTTON_ROW_MAX_OPTION_COLUMNS: Int = 32

/** The server's Msg key of a Choice's Other label; drawn with the app's "Other" (never "Other…"). */
internal const val QUESTION_OTHER_MSG_KEY: String = "actionCard.question.other"

/** The server's Msg key of a question card's "For" row, which B shows as its description instead. */
internal const val QUESTION_FOR_MSG_KEY: String = "actionCard.question.for"

/**
 * The layout of [this] question card ([QuestionCardLayout]), or null for any other card.
 *
 * C needs exactly one question, single choice, at most [QUESTION_BUTTON_ROW_MAX_OPTIONS] options,
 * and what the server's `questionAskPlacement` calls inline: no pages, every option label at most
 * [QUESTION_BUTTON_ROW_MAX_OPTION_COLUMNS] columns. An open card carries that as `placement:
 * "inline"`, which is trusted, never re-measured. An answered card's spec keeps no form (the server
 * drops it with the answers), and an ended (expired, withdrawn) card keeps the shape it had when
 * asked (Jasper, raft-design #triage:#261), so for both the stored action decides with the same
 * rule ([ThreadActionCard.questions], [ThreadActionCard.questionHasPages]). Every other case, and a
 * card waiting on Confirm again, is B.
 */
internal fun ThreadActionCard.questionCardLayout(spec: ActionCardSpecCard): QuestionCardLayout? {
    if (!type.equals(QuestionAskActionCardType, ignoreCase = true)) return null
    if (needsReconfirmation) return QuestionCardLayout.SlimCard
    if (isExecuted || hasEnded) {
        val only = questions.singleOrNull()
        return if (
            !questionHasPages && only != null && only.kind == "single" && only.options in 1..QUESTION_BUTTON_ROW_MAX_OPTIONS &&
            only.widestOptionColumns <= QUESTION_BUTTON_ROW_MAX_OPTION_COLUMNS
        ) {
            QuestionCardLayout.ButtonRow
        } else {
            QuestionCardLayout.SlimCard
        }
    }
    val inline = spec.inlineQuestionForm() ?: return QuestionCardLayout.SlimCard
    val only = inline.fields.singleOrNull() ?: return QuestionCardLayout.SlimCard
    // `placement: "inline"` already says no pages and every label within 32 columns.
    return if (!only.multiple && only.options.size <= QUESTION_BUTTON_ROW_MAX_OPTIONS) QuestionCardLayout.ButtonRow else QuestionCardLayout.SlimCard
}

/**
 * Whether [viewerUserId] is the person [this] question card asks: its addressee, or anyone when
 * it asks anyone in the channel. A card without an addressee (an older server) asks the viewer
 * as far as the App knows; the server's 403 `NOT_ADDRESSEE` stays final.
 */
internal fun ThreadActionCard.questionAsksViewer(viewerUserId: String?): Boolean {
    if (questionAsksAnyone) return true
    val addressee = questionAddresseeUserId ?: return true
    return viewerUserId != null && addressee.trim().equals(viewerUserId.trim(), ignoreCase = true)
}

/** How long a question card still waits ([questionTimeLeft]). */
internal sealed interface QuestionTimeLeft {
    /** More than a day: "N days left" (to the nearest day). */
    data class Days(val days: Int) : QuestionTimeLeft

    /** Under a day: "N hours left" (whole hours). */
    data class Hours(val hours: Int) : QuestionTimeLeft

    /** Under an hour: "Less than an hour left". */
    data object UnderAnHour : QuestionTimeLeft
}

private const val HOUR_MILLIS = 3_600_000L
private const val DAY_MILLIS = 24 * HOUR_MILLIS

/**
 * The time a question card has left at [nowMillis] before [expiresAtMillis] (its `expiresAt`), or
 * null: no deadline, or already past it (the card is drawn expired then). More than a day counts
 * days to the nearest one (a 7-day card just asked says 7, not 6), never below 1; under a day whole
 * hours; under an hour no number.
 */
internal fun questionTimeLeft(expiresAtMillis: Long?, nowMillis: Long): QuestionTimeLeft? {
    val left = (expiresAtMillis ?: return null) - nowMillis
    return when {
        left <= 0 -> null
        left > DAY_MILLIS -> QuestionTimeLeft.Days(((left + DAY_MILLIS / 2) / DAY_MILLIS).toInt().coerceAtLeast(1))
        left >= HOUR_MILLIS -> QuestionTimeLeft.Hours((left / HOUR_MILLIS).toInt())
        else -> QuestionTimeLeft.UnderAnHour
    }
}

@Composable
internal fun questionTimeLeftText(left: QuestionTimeLeft): String =
    when (left) {
        is QuestionTimeLeft.Days ->
            if (left.days == 1) stringResource(Res.string.action_card_question_days_left_one, left.days) else stringResource(Res.string.action_card_question_days_left_other, left.days)
        is QuestionTimeLeft.Hours ->
            if (left.hours == 1) stringResource(Res.string.action_card_question_hours_left_one, left.hours) else stringResource(Res.string.action_card_question_hours_left_other, left.hours)
        QuestionTimeLeft.UnderAnHour -> stringResource(Res.string.action_card_question_under_an_hour)
    }

/** "[line] · [timeLeft]", or [line] alone without a deadline. */
internal fun questionLineWithTimeLeft(line: String, timeLeft: String?): String = if (timeLeft.isNullOrBlank()) line else "$line · $timeLeft"

/** A Choice's Other label as drawn: the app's "Other" for the server's known key, else the server's words. */
@Composable
internal fun questionOtherLabel(label: ActionCardSpecMsg, locale: String): String =
    if (label.key == QUESTION_OTHER_MSG_KEY) stringResource(Res.string.action_card_question_other) else renderActionCardMsgText(label, locale)

/** The question rows (`q_<id>` Fields) of [tree], in element order. */
private fun ActionCardSpecTree.questionRows(): List<ActionCardSpecDisplayElement.Field> =
    elements.filter { (id, node) -> id.startsWith("q_") && node is ActionCardSpecDisplayElement.Field }.values.filterIsInstance<ActionCardSpecDisplayElement.Field>()

/** The answer rows (`a_<id>` Fields) of [tree], in element order. */
private fun ActionCardSpecTree.answerRows(): List<ActionCardSpecDisplayElement.Field> =
    elements.filter { (id, node) -> id.startsWith("a_") && node is ActionCardSpecDisplayElement.Field }.values.filterIsInstance<ActionCardSpecDisplayElement.Field>()

/** A Field's value as one line of plain text (entities by [name], else their fallback). */
internal fun ActionCardSpecFieldValue.plainText(locale: String, name: (build.raft.app.data.ActionCardSpecEntityRef) -> String? = { null }): String =
    when (this) {
        is ActionCardSpecFieldValue.Plain -> text
        is ActionCardSpecFieldValue.Message -> renderActionCardMsgText(msg, locale, name)
        is ActionCardSpecFieldValue.Entities -> refs.joinToString(", ") { name(it) ?: it.fallback }
    }

/** How many questions [this] card asks: the stored action's, else its question rows (body, then record). */
internal fun ThreadActionCard.questionCount(spec: ActionCardSpecCard): Int =
    questions.size.takeIf { it > 0 }
        ?: spec.forms?.values?.firstOrNull()?.fields?.size?.takeIf { it > 0 }
        ?: spec.spec.questionRows().size.takeIf { it > 0 }
        ?: spec.result?.questionRows()?.size
        ?: 0

/**
 * A single question's text: the form's field label while it waits, its row in the body, or its
 * row in the answered record. Null when the card asks several or none can be read.
 */
internal fun ActionCardSpecCard.singleQuestionText(locale: String): String? {
    (forms?.values?.firstOrNull()?.fields?.singleOrNull() as? build.raft.app.data.ActionCardSpecKnownField)?.let { return renderActionCardMsgText(it.label, locale) }
    spec.questionRows().singleOrNull()?.let { return it.value.plainText(locale) }
    return result?.questionRows()?.singleOrNull()?.value?.plainText(locale)
}

/** The answers in an answered card's record (`a_<id>` rows), as plain text, joined with " · ". Null without a record. */
internal fun ActionCardSpecCard.questionAnswerText(locale: String): String? =
    result?.answerRows()?.map { it.value.plainText(locale) }?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }?.joinToString(" · ")

/** The agent a question card's form names (`{agent}` in its visibility line), as a fallback asker. */
internal fun ActionCardSpecCard.questionAskerRef(): build.raft.app.data.ActionCardSpecEntityRef? =
    forms?.values?.firstNotNullOfOrNull { form -> (form.description?.params?.get("agent") as? ActionCardSpecMsgParam.Entity)?.ref }

/**
 * The body of a slim card (B): without the "For" row (now the description) and, when the card
 * asks one question, without that question's row (now the title). A Stack left empty goes too.
 */
internal fun ActionCardSpecTree.withoutSlimQuestionRows(singleQuestion: Boolean): ActionCardSpecTree =
    withoutElements { id, node ->
        node is ActionCardSpecDisplayElement.Field && (node.label.key == QUESTION_FOR_MSG_KEY || (singleQuestion && id.startsWith("q_")))
    }

/**
 * C (question-card redesign): no card frame. The question in semibold as the message's last line,
 * a wrap row of outlined buttons (each option, then "Other"), and a small hint line under them.
 * Tapping an option submits it at once (the 1.5 s "Submitted · Undo" of [QuestionUndoableSubmit]);
 * "Other" opens the one-line input for the person's own answer, sent with its own button. A viewer
 * who isn't asked sees the buttons disabled ([canSubmit] false) and "Waiting for Maya to answer".
 * Answered, the buttons give way to one line, "✓ Maya answered Postgres" ([answeredLine]).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ActionCardQuestionButtonRow(
    question: String,
    inline: ActionCardInlineQuestionForm?,
    values: Map<String, ActionCardFormValue>,
    onValuesChange: (Map<String, ActionCardFormValue>) -> Unit,
    serverIssues: List<ActionCardFormIssue>,
    canSubmit: Boolean,
    submitting: Boolean,
    hint: String?,
    answeredLine: String?,
    error: String?,
    displayLocale: String,
    onSend: (Map<String, ActionCardFormValue>) -> Unit,
    modifier: Modifier = Modifier,
) {
    RaftDesignSystemTheme {
        Column(
            modifier.fillMaxWidth().widthIn(max = RaftMessageActionCardDefaults.MaxWidth),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RaftText(question, style = RaftTheme.typography.body.copy(fontWeight = FontWeight.SemiBold), color = RaftTheme.colors.foreground)
            val field = inline?.fields?.singleOrNull()
            when {
                answeredLine != null -> RaftMessageActionCardHeaderDescription(answeredLine, check = true)
                inline != null && field != null ->
                    QuestionButtons(
                        inline = inline,
                        field = field,
                        values = values,
                        onValuesChange = onValuesChange,
                        serverIssues = serverIssues,
                        canSubmit = canSubmit,
                        submitting = submitting,
                        hint = hint,
                        displayLocale = displayLocale,
                        onSend = onSend,
                    )
                hint != null -> QuestionHint(hint)
            }
            error?.takeIf { it.isNotBlank() }?.let { RaftBanner(description = it, status = RaftBannerStatus.Warning, size = RaftBannerSize.Sm) }
        }
    }
}

@Composable
private fun QuestionHint(text: String) {
    RaftText(text, style = RaftTheme.typography.footnote, color = RaftTheme.colors.foregroundMuted)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuestionButtons(
    inline: ActionCardInlineQuestionForm,
    field: ActionCardSpecChoiceField,
    values: Map<String, ActionCardFormValue>,
    onValuesChange: (Map<String, ActionCardFormValue>) -> Unit,
    serverIssues: List<ActionCardFormIssue>,
    canSubmit: Boolean,
    submitting: Boolean,
    hint: String?,
    displayLocale: String,
    onSend: (Map<String, ActionCardFormValue>) -> Unit,
) {
    val undoable = rememberQuestionUndoableSubmit(inline.formId, onSend)
    val scope = rememberCoroutineScope()
    var pickJob by remember(inline.formId) { mutableStateOf<Job?>(null) }
    val latestValues by rememberUpdatedState(values)
    var clientIssues by remember(inline.formId) { mutableStateOf(emptyList<ActionCardFormIssue>()) }
    var pendingShown by remember(inline.formId) { mutableStateOf(false) }
    var focusOther by remember(inline.formId) { mutableStateOf(false) }
    val waiting = undoable.waiting
    val busy = submitting || waiting
    val value = (values[field.name] as? ActionCardFormValue.Choice) ?: ActionCardFormValue.Choice(emptyList())
    val other = field.other
    val otherChosen = other != null && value.other != null

    val submit = { now: Map<String, ActionCardFormValue> ->
        if (!submitting && !undoable.waiting && canSubmit) {
            if (actionCardFormHasPendingChoice(inline.fields, now)) {
                pendingShown = true
            } else {
                val issues = checkActionCardFormValues(inline.form, now)
                clientIssues = issues
                if (issues.isEmpty()) undoable.start(now)
            }
        }
    }

    serverIssues.filter { it.field == null || it.field != field.name }.map { renderActionCardMsgText(it.message, displayLocale) }.distinct().forEach {
        RaftBanner(description = it, status = RaftBannerStatus.Warning, size = RaftBannerSize.Sm)
    }
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        field.options.forEach { option ->
            val chosen = !otherChosen && option.value in value.selected
            RaftButton(
                text = renderActionCardMsgText(option.label, displayLocale),
                onClick = {
                    if (canSubmit && !busy) {
                        val next = latestValues + (field.name to ActionCardFormValue.Choice(listOf(option.value)))
                        clientIssues = emptyList()
                        onValuesChange(next)
                        // Shown chosen for a moment (web CHOICE_PICK_HIGHLIGHT_MS), then the undoable submit.
                        pickJob?.cancel()
                        pickJob =
                            scope.launch {
                                delay(QUESTION_CHOICE_PICK_HIGHLIGHT_MS)
                                submit(next)
                            }
                    }
                },
                variant = RaftButtonVariant.Default,
                // The chosen answer stays readable while it waits for Undo or is sent; the rest wait.
                enabled = canSubmit && (!busy || chosen),
                loading = submitting && chosen,
            )
        }
        if (other != null) {
            RaftButton(
                text = questionOtherLabel(other.label, displayLocale),
                onClick = {
                    if (canSubmit && !busy && !otherChosen) {
                        pickJob?.cancel()
                        focusOther = true
                        onValuesChange(latestValues + (field.name to ActionCardFormValue.Choice(emptyList(), value.other.orEmpty())))
                    }
                },
                variant = RaftButtonVariant.Default,
                enabled = canSubmit && (!busy || otherChosen),
            )
        }
    }
    if (otherChosen && other != null && canSubmit) {
        ActionCardChoiceOtherInput(
            text = value.other.orEmpty(),
            onTextChange = {
                clientIssues = emptyList()
                onValuesChange(latestValues + (field.name to ActionCardFormValue.Choice(emptyList(), it)))
            },
            maxLength = other.maxLength,
            placeholder = other.placeholder?.let { renderActionCardMsgText(it, displayLocale) } ?: stringResource(Res.string.action_card_question_other_placeholder),
            focus = focusOther,
            onFocusHandled = { focusOther = false },
            onEnter = { submit(latestValues) },
        )
        if (!waiting) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                RaftButton(
                    text = renderActionCardMsgText(inline.form.submitLabel, displayLocale),
                    onClick = { submit(latestValues) },
                    variant = RaftButtonVariant.Default,
                    enabled = !busy,
                    loading = submitting,
                )
            }
        }
    }
    val issue = clientIssues.firstOrNull { it.field == field.name } ?: serverIssues.firstOrNull { it.field == field.name }
    val pending = if (pendingShown && issue == null && canSubmit) actionCardChoicePending(value) else null
    when {
        issue != null ->
            RaftText(renderActionCardMsgText(issue.message, displayLocale), style = RaftTheme.typography.caption.copy(fontWeight = FontWeight.SemiBold), color = RaftTheme.colors.dangerStrong)
        pending != null ->
            RaftText(
                stringResource(if (pending == ActionCardChoicePending.Write) Res.string.action_card_question_write_first else Res.string.action_card_question_choose_first),
                style = RaftTheme.typography.caption,
                color = RaftTheme.colors.foregroundHint,
            )
    }
    if (waiting && canSubmit) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            RaftText(stringResource(Res.string.action_card_question_submitted) + " ·", style = RaftTheme.typography.callout, color = RaftTheme.colors.foregroundMuted)
            RaftButton(text = stringResource(Res.string.action_card_question_undo), onClick = { undoable.undo() }, variant = RaftButtonVariant.Ghost)
        }
    } else if (hint != null) {
        QuestionHint(hint)
    }
}

/** The default wait of a question card (shared `QUESTION_ASK_LIMITS.expiresInMinutes.default`, 7 days). */
internal const val QUESTION_DEFAULT_EXPIRES_IN_MINUTES: Int = 10_080

/** How long a question card waited, in the largest whole unit (web `formatQuestionWait`): 3 days, 12 hours, 90 minutes. */
internal sealed interface QuestionWait {
    data class Days(val days: Int) : QuestionWait

    data class Hours(val hours: Int) : QuestionWait

    data class Minutes(val minutes: Int) : QuestionWait
}

/** [minutes] (`expiresInMinutes`; null or not positive: the default 7 days) in its largest whole unit. */
internal fun questionWait(minutes: Int?): QuestionWait {
    val wait = minutes?.takeIf { it > 0 } ?: QUESTION_DEFAULT_EXPIRES_IN_MINUTES
    return when {
        wait % 1_440 == 0 -> QuestionWait.Days(wait / 1_440)
        wait % 60 == 0 -> QuestionWait.Hours(wait / 60)
        else -> QuestionWait.Minutes(wait)
    }
}

@Composable
internal fun questionWaitText(wait: QuestionWait): String =
    when (wait) {
        is QuestionWait.Days ->
            if (wait.days == 1) stringResource(Res.string.action_card_question_wait_days_one, wait.days) else stringResource(Res.string.action_card_question_wait_days_other, wait.days)
        is QuestionWait.Hours ->
            if (wait.hours == 1) stringResource(Res.string.action_card_question_wait_hours_one, wait.hours) else stringResource(Res.string.action_card_question_wait_hours_other, wait.hours)
        is QuestionWait.Minutes ->
            if (wait.minutes == 1) stringResource(Res.string.action_card_question_wait_minutes_one, wait.minutes) else stringResource(Res.string.action_card_question_wait_minutes_other, wait.minutes)
    }

/**
 * The one line an ended question card says where its question or options were (web
 * `QuestionCardEndedLine`, raft-design "When it ends unanswered"): withdrawn, "Scout withdrew these
 * questions."; expired, "Mei didn't answer within 3 days. Scout was told." ([addressee] null: asked
 * of anyone, "Nobody answered within …"). Null for a card that hasn't ended.
 */
@Composable
internal fun ThreadActionCard.questionEndedLine(asker: String, addressee: String?, questionCount: Int): String? =
    when {
        isWithdrawn ->
            if (questionCount == 1) stringResource(Res.string.action_card_question_withdrawn_line_one, asker) else stringResource(Res.string.action_card_question_withdrawn_line_other, asker)
        isExpired -> {
            val duration = questionWaitText(questionWait(questionExpiresInMinutes))
            if (addressee != null) {
                stringResource(Res.string.action_card_question_expired_line, addressee, duration, asker)
            } else {
                stringResource(Res.string.action_card_question_expired_line_anyone, duration, asker)
            }
        }
        else -> null
    }
