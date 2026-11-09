package build.raft.app

import build.raft.app.resources.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.ActionCardSpecButton
import build.raft.app.data.ActionCardSpecCard
import build.raft.app.data.ActionCardSpecEntityKind
import build.raft.app.data.ActionCardSpecEntityRef
import build.raft.app.data.ActionCardSpecMsg
import build.raft.app.data.ActionCardSpecTree
import build.raft.app.data.RAFT_APP_ACTION_CARD_CAPABILITIES
import build.raft.app.data.HomeChannelRow
import build.raft.app.data.jointInviteRateLimitMinutes
import build.raft.app.data.QuestionAskActionCardType
import build.raft.app.data.SeatsAddActionCardType
import build.raft.app.data.ThreadActionCard
import build.raft.app.data.defaultNowMillis
import build.raft.app.data.expiresAtEpochMillis
import build.raft.app.model.ConversationKind
import build.raft.app.model.MemberProfile
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerSize
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftDialogSheet

/**
 * What a thread page gives its spec cards (task #39, A4). The default is the page without spec
 * cards: [specEnabled] is false for every server, so every card is the typed / legacy one.
 *
 * - [specEnabled]: the `action_card_spec` flag for a server (the card's own,
 *   [actionCardSpecFlagServerId]); [ensureFlags] asks for that server's flags once when this
 *   process has none yet (a joint channel's card from another server).
 * - [capabilities]: what strip keeps ([build.raft.app.data.raftAppActionCardCapabilities]), cut
 *   per card ([actionCardSpecCapabilitiesFor]).
 * - [state] / [onIntent]: [ThreadActionCardSpecStore]; each card reads only its own
 *   [ThreadActionCardSpecState.slice], so the host does not change when one card runs.
 * - [openInWeb]: dispatches [ThreadActionCardSpecIntent.OpenInWeb] with this message's link.
 * - [isDirectMessageChannel]: whether a channel id is a DM this device knows (a message target's route).
 * - [viewerUserId] / [clientOs] / [feedbackPageAvailable]: who may follow a done feedback card to
 *   its ticket ([showsFeedbackTicket], S0.3); [openFeedbackTicket] opens Settings › Feedback on
 *   that ticket (null: the list).
 */
internal data class ThreadActionCardSpecHost(
    val specEnabled: (serverId: String?) -> Boolean = { false },
    val ensureFlags: (serverId: String) -> Unit = {},
    val capabilities: List<String> = RAFT_APP_ACTION_CARD_CAPABILITIES,
    val state: StateFlow<ThreadActionCardSpecState> = MutableStateFlow(ThreadActionCardSpecState()),
    val onIntent: (ThreadActionCardSpecIntent) -> Unit = {},
    val openInWeb: (messageId: String) -> Unit = {},
    val isDirectMessageChannel: (channelId: String) -> Boolean = { false },
    val viewerUserId: String? = null,
    val clientOs: String = "",
    val feedbackPageAvailable: Boolean = false,
    val openFeedbackTicket: (ticketId: String?) -> Unit = {},
    /** iOS view-only Billing: "View your plan" opens the view-only Plan & Billing page. */
    val openPlan: () -> Unit = {},
)

internal val LocalThreadActionCardSpecHost = compositionLocalOf { ThreadActionCardSpecHost() }

/**
 * The frame's status for a card's `state` (web `ActionCardSpecCard`). A state this build doesn't
 * know is drawn as Prepared, as before. Draw the card as of the client clock first
 * ([build.raft.app.data.expiredAsOf]) so a prepared card past its `expiresAt` reads Expired.
 */
internal fun ThreadActionCard.actionCardSpecStatus(): RaftActionCardStatus =
    when {
        isExecuted -> RaftActionCardStatus.Executed
        isExpired -> RaftActionCardStatus.Expired
        isWithdrawn -> RaftActionCardStatus.Withdrawn
        isFrozen -> RaftActionCardStatus.Frozen
        needsReconfirmation -> RaftActionCardStatus.ReconfirmRequired
        else -> RaftActionCardStatus.Prepared
    }

/**
 * Every Button that changes the card labelled [label] (web: on a card waiting on Confirm again, the
 * execute is "Confirm again" and reconfirms). Links keep theirs.
 */
internal fun ActionCardSpecCard.withChangingButtonsLabelled(label: ActionCardSpecMsg): ActionCardSpecCard {
    fun relabel(tree: ActionCardSpecTree) =
        tree.copy(
            elements = tree.elements.mapValues { (_, node) ->
                if (node is ActionCardSpecButton && node.action.changesCard) node.copy(label = label) else node
            },
        )
    return copy(spec = relabel(spec))
}

/**
 * Types that keep their own deadline rule (shared `isActionCardExpiryExempt`, slock #13254): a
 * person's request among them keeps its own "No owner answered" line when it expires.
 */
internal val ActionCardExpiryExemptTypes: Set<String> =
    setOf(QuestionAskActionCardType, "agent:approve_call", "assistant:owner_action", SeatsAddActionCardType, "plan:upgrade")

/** Which line an expired card says under its title (web `useExpiredCardLine`, slock #13275). */
internal sealed interface ActionCardExpiredLine {
    /** A person's request with its own deadline (a seat or Pro request): "No owner answered within 7 days". */
    data object PersonRequest : ActionCardExpiredLine

    /** The viewer sent it: "Expired on {date}. Send a new request." */
    data class Own(val date: String) : ActionCardExpiredLine

    /** A person sent it under the 30-day rule and is a member here: "Ask {owner} to send a new request." */
    data class FromPerson(val date: String, val owner: String) : ActionCardExpiredLine

    /** An agent's card, or its sender is no longer known here: "Ask the agent to send a new request." */
    data class FromAgent(val date: String) : ActionCardExpiredLine
}

/**
 * The line for an expired card ([ActionCardExpiredLine]), or null: not expired, a question card
 * (the web has its own ended lines), or no date. [formatDate] writes `expiresAt` as the done line
 * writes its time (null: it can't); [requesterName] is the requester's name from this server's
 * member directory (null when not a member here); [viewerUserId] is the signed-in person.
 */
internal fun ThreadActionCard.actionCardExpiredLine(
    viewerUserId: String?,
    requesterName: String?,
    formatDate: (String) -> String?,
): ActionCardExpiredLine? {
    if (!isExpired || type.equals(QuestionAskActionCardType, ignoreCase = true)) return null
    val requester = requesterUserId
    if (requester != null && ActionCardExpiryExemptTypes.any { it.equals(type, ignoreCase = true) }) return ActionCardExpiredLine.PersonRequest
    val at = expiresAt?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val date = formatDate(at)?.takeIf { it.isNotBlank() } ?: return null
    return when {
        requester != null && requester == viewerUserId?.trim() -> ActionCardExpiredLine.Own(date)
        requester != null && !requesterName.isNullOrBlank() -> ActionCardExpiredLine.FromPerson(date, requesterName)
        else -> ActionCardExpiredLine.FromAgent(date)
    }
}

/**
 * An ended card's line under its title ([actionCardExpiredLine]): the requester by name from the
 * thread's member lookup ([LocalThreadActionCardNames]), the viewer from the spec host, the date
 * in the app's time format (never the raw wire timestamp, nothing without a live time context).
 */
@Composable
internal fun ThreadActionCard.actionCardEndedLine(): String? {
    if (!isExpired) return null
    val viewerUserId = LocalThreadActionCardSpecHost.current.viewerUserId
    val names = LocalThreadActionCardNames.current
    val context = currentRaftTimeContextProvider?.current
    val requesterName = requesterUserId?.let { names.name(ThreadActionCardEntityKind.Human, it) }
    val line =
        actionCardExpiredLine(viewerUserId, requesterName) { at ->
            context?.formatTimestampLabel(at)?.takeIf { it != at }
        } ?: return null
    return when (line) {
        ActionCardExpiredLine.PersonRequest -> stringResource(Res.string.thread_action_card_seats_expired_line)
        is ActionCardExpiredLine.Own -> stringResource(Res.string.thread_action_card_expired_line_own, line.date)
        is ActionCardExpiredLine.FromPerson -> stringResource(Res.string.thread_action_card_expired_line_from_person, line.date, line.owner)
        is ActionCardExpiredLine.FromAgent -> stringResource(Res.string.thread_action_card_expired_line, line.date)
    }
}

/** An app string as a card Msg: the same words in [displayLocale] and as the English fallback. */
internal fun actionCardAppMsg(key: String, text: String, displayLocale: String): ActionCardSpecMsg =
    ActionCardSpecMsg(key = key, text = mapOf("en" to text, displayLocale to text))

@Composable
internal fun threadActionCardSpecErrorText(error: ThreadActionCardSpecError?): String? = threadActionCardSpecErrorDesc(error)?.localized()

/** What the frame or a sheet says for [error]: the server's words for a generic refusal, app copy otherwise. */
internal fun threadActionCardSpecErrorDesc(error: ThreadActionCardSpecError?): StringDesc? =
    when (error) {
        null -> null
        ThreadActionCardSpecError.FormRequired -> Res.string.thread_action_card_form_required.desc()
        ThreadActionCardSpecError.QuestionNotSent -> Res.string.action_card_question_not_sent.desc()
        is ThreadActionCardSpecError.Failed -> error.serverMessage?.desc() ?: Res.string.thread_action_card_execute_failed.desc()
        // Web `settings.about.feedbackError.rateLimited` in substance; the minutes as the joint invite's 429.
        is ThreadActionCardSpecError.FeedbackRateLimited ->
            when (val minutes = jointInviteRateLimitMinutes(error.retryAfterSeconds)) {
                null -> Res.string.thread_action_card_feedback_rate_limited_later.desc()
                1 -> Res.string.thread_action_card_feedback_rate_limited_one.desc()
                else -> Res.string.thread_action_card_feedback_rate_limited_other.format(minutes)
            }
    }

/** An entity's name from the thread's lookup; only for the viewer's own server, else the card's fallback. */
private fun ThreadActionCardNameLookup.actionCardSpecEntityName(ref: ActionCardSpecEntityRef, serverId: String?, viewerServerId: String?): String? {
    if (serverId != null && viewerServerId != null && serverId != viewerServerId) return null
    val kind =
        when (ref.kind) {
            ActionCardSpecEntityKind.User -> ThreadActionCardEntityKind.Human
            ActionCardSpecEntityKind.Agent -> ThreadActionCardEntityKind.Agent
            ActionCardSpecEntityKind.Channel -> ThreadActionCardEntityKind.Channel
            ActionCardSpecEntityKind.Computer -> return null
        }
    return name(kind, ref.id)
}

/**
 * A first-party action card drawn from its spec ([RaftActionCardView]) in a message, with the
 * frame read as the typed card reads it ([ThreadActionCardView]): done with who and when, frozen,
 * Confirm again, view only on another server's card (the same reason), no buttons but links in a
 * read-only thread. [onAction] gets the tapped Button's element id.
 */
@Composable
internal fun ThreadActionCardSpecView(
    messageId: String,
    card: ThreadActionCard,
    spec: ActionCardSpecCard,
    commitEnabled: Boolean,
    typedPending: Boolean,
    executedTimeLabel: String?,
    modifier: Modifier = Modifier,
    /** The message's sender, for a question card: the agent that asks ("Fixer is asking you"). */
    askerName: String? = null,
    onAction: (elementId: String) -> Unit,
) {
    val host = LocalThreadActionCardSpecHost.current
    val sliceFlow = remember(host.state, messageId) { host.state.map { it.slice(messageId) }.distinctUntilChanged() }
    val slice by sliceFlow.collectAsState(remember(host.state, messageId) { host.state.value.slice(messageId) })
    val viewerServerId = LocalThreadViewerServerId.current
    val names = LocalThreadActionCardNames.current
    val displayLocale = rememberActionCardDisplayLocale()
    val blockedReason =
        when {
            card.isExecuted || card.hasEnded -> null
            card.sourceServerId != null && viewerServerId != null && card.sourceServerId != viewerServerId ->
                card.targetServerName?.let { stringResource(Res.string.thread_action_card_blocked_target_server, it) }
                    ?: stringResource(Res.string.thread_action_card_blocked_source_workspace)
            else -> null
        }
    val endedLine = card.actionCardEndedLine()
    val committedBy = card.executedByUserName?.takeIf { it.isNotBlank() }?.let { stringResource(Res.string.thread_action_card_committed_by, it) }
    val record = committedBy?.let { by -> executedTimeLabel?.let { "$by · $it" } ?: by } ?: executedTimeLabel
    val reconfirmLabel = stringResource(Res.string.thread_action_card_reconfirm)
    val viewPlanLabel = stringResource(Res.string.settings_billing_view_your_plan)
    val offersUpgrade = settingsBillingOffersUpgrade()
    val rendered =
        remember(spec, card.needsReconfirmation, reconfirmLabel, viewPlanLabel, offersUpgrade, displayLocale) {
            val labelled =
                if (card.needsReconfirmation) {
                    spec.withChangingButtonsLabelled(actionCardAppMsg("actionCard.reconfirm", reconfirmLabel, displayLocale))
                } else {
                    spec
                }
            // iOS view-only Billing: Add Seats / Upgrade read "View your plan" (threadActionCardSpecPress).
            if (offersUpgrade) labelled else labelled.withUpgradeButtonsLabelled(actionCardAppMsg("planBilling.viewYourPlan", viewPlanLabel, displayLocale))
        }
    // A question card the server placed on the card (`placement: "inline"`, spec §5.9): answered in
    // its body, without its button or its question rows; every other card keeps its sheet.
    val inline = remember(rendered, card) { card.inlineQuestionForm(rendered) }
    // A question card (redesign 2026-11): C, a button row without a card, or B, a slim card.
    val questionLayout = remember(rendered, card) { card.questionCardLayout(rendered) }
    val question = questionLayout?.let { rememberQuestionCardWords(card, rendered, askerName, displayLocale, record) }
    val singleQuestion = question?.single == true
    val shown =
        remember(rendered, inline, questionLayout, singleQuestion) {
            val body = if (inline == null) rendered.spec else rendered.spec.withoutInlineQuestions(inline)
            rendered.copy(spec = if (questionLayout == QuestionCardLayout.SlimCard) body.withoutSlimQuestionRows(singleQuestion) else body)
        }
    // Only the person asked (or anyone, on a card for anyone) may answer: the server refuses the rest (403 NOT_ADDRESSEE).
    val canOperate = commitEnabled && blockedReason == null && (question?.asked ?: true)
    val currentCard by rememberUpdatedState(card)
    val error = threadActionCardSpecErrorText(slice.error)
    val canAnswer = canOperate && card.actionCardSpecStatus() == RaftActionCardStatus.Prepared
    val send = { form: ActionCardInlineQuestionForm ->
        { values: Map<String, build.raft.app.data.ActionCardFormValue> ->
            host.onIntent(ThreadActionCardSpecIntent.SubmitInlineForm(messageId, currentCard, form.formId, form.buttonId, values))
        }
    }
    if (questionLayout == QuestionCardLayout.ButtonRow && question != null && blockedReason == null) {
        val defaults = remember(inline) { inline?.let { initialActionCardFormValues(it.form) }.orEmpty() }
        ActionCardQuestionButtonRow(
            question = question.text ?: actionCardMsgPlain(rendered.title, RaftCardTreeContext(displayLocale = displayLocale)),
            inline = inline,
            values = slice.inlineValues ?: defaults,
            onValuesChange = { host.onIntent(ThreadActionCardSpecIntent.InlineValuesChanged(messageId, it)) },
            serverIssues = slice.inlineIssues,
            canSubmit = canAnswer,
            submitting = inline != null && slice.busy == inline.buttonId,
            hint = question.buttonRowHint,
            answeredLine = if (card.isExecuted) question.answeredLine else null,
            error = error,
            displayLocale = displayLocale,
            onSend = { values -> inline?.let { send(it)(values) } },
            modifier = modifier,
        )
        return
    }
    val frame =
        RaftActionCardFrameState(
            status = card.actionCardSpecStatus(),
            record = record,
            blockedReason = blockedReason,
            readOnly = !commitEnabled,
            // Someone else's question: the same card, its controls disabled (who is asked never changes the shape).
            controlsDisabled = question?.asked == false,
            busyElementKey = slice.busyElement(typedPending),
            error = error,
            endedLine = endedLine,
        )
    RaftActionCardView(
        card = shown,
        state = frame,
        entityName = { ref, serverId -> names.actionCardSpecEntityName(ref, serverId, viewerServerId) },
        onAction = onAction,
        modifier = modifier,
        sourceServerId = card.sourceServerId,
        displayLocale = displayLocale,
        linkNavigable = { button -> button.actionCardSpecLinkNavigable(card.sourceServerId, viewerServerId) },
        fitLabels = inline != null,
        slim =
            question?.let {
                RaftActionCardSlim(
                    title = it.slimTitle,
                    description = it.slimDescription,
                    statusLine = if (card.isExecuted) it.answeredLine else it.endedLine?.let(::AnnotatedString),
                    statusCheck = card.isExecuted,
                )
            },
        footer =
            inline?.let { form ->
                {
                    val defaults = remember(form) { initialActionCardFormValues(form.form) }
                    ActionCardQuestionInlineForm(
                        inline = form,
                        values = slice.inlineValues ?: defaults,
                        onValuesChange = { host.onIntent(ThreadActionCardSpecIntent.InlineValuesChanged(messageId, it)) },
                        serverIssues = slice.inlineIssues,
                        canSubmit = canAnswer,
                        showSubmit = canOperate,
                        submitting = slice.busy == form.buttonId,
                        displayLocale = displayLocale,
                        onSend = send(form),
                    )
                }
            },
    )
}

/**
 * The words a question card's two layouts say, worked out once per card:
 * - [asked]: the viewer is the person asked ([questionAsksViewer]);
 * - [single] / [text]: whether it asks one question, and that question's text;
 * - C: [buttonRowHint] ("Fixer is waiting for your answer · 7 days left", or "Waiting for Maya to
 *   answer");
 * - B: [slimTitle] (the one question, or "Fixer has 3 questions", raft-design #1095) and
 *   [slimDescription] ("Fixer is asking you · 7 days left", or "Waiting for Maya · 7 days left");
 * - both, where the controls were once it is over: [answeredLine] ("✓ Maya answered **Postgres**")
 *   or [endedLine] ("Mei didn't answer within 3 days. Scout was told.", "Scout withdrew these questions.").
 */
internal data class QuestionCardWords(
    val asked: Boolean,
    val single: Boolean,
    val text: String?,
    val buttonRowHint: String?,
    val answeredLine: AnnotatedString?,
    val slimTitle: String?,
    val slimDescription: String?,
    /** Ended without an answer: the one line where the question's options (C) or the card (B, folded) were. */
    val endedLine: String?,
)

/**
 * [QuestionCardWords] for [card]: the person asked by their name in this server's member list (by
 * id), else the handle the server stored; the asker is the message's sender ([askerName]), else
 * the agent the form names; the time left from the card's `expiresAt` on the live clock; who
 * answered from `executedByUserName`, and what from the record's answer rows.
 */
@Composable
private fun rememberQuestionCardWords(
    card: ThreadActionCard,
    spec: ActionCardSpecCard,
    askerName: String?,
    displayLocale: String,
    record: String?,
): QuestionCardWords {
    val host = LocalThreadActionCardSpecHost.current
    val names = LocalThreadActionCardNames.current
    val viewerServerId = LocalThreadViewerServerId.current
    val someone = stringResource(Res.string.action_card_question_someone)
    val asked = card.questionAsksViewer(host.viewerUserId)
    val count = card.questionCount(spec)
    val single = count <= 1
    val text = spec.singleQuestionText(displayLocale)
    val addressee =
        card.questionAddresseeUserId?.let { names.name(ThreadActionCardEntityKind.Human, it) }?.takeIf { it.isNotBlank() }
            ?: card.questionAddresseeHandle?.trim()?.removePrefix("@")?.takeIf { it.isNotBlank() }
            ?: someone
    val asker =
        askerName?.takeIf { it.isNotBlank() }
            ?: spec.questionAskerRef()?.let { ref -> names.actionCardSpecEntityName(ref, ref.serverIdOr(card.sourceServerId), viewerServerId) ?: ref.fallback }
            ?: card.agentName
            ?: someone
    val nowMillis = currentRaftTimeContextProvider?.current?.nowEpochMillis ?: defaultNowMillis()
    val timeLeft = questionTimeLeft(card.expiresAtEpochMillis(), nowMillis)?.let { questionTimeLeftText(it) }
    val waiting = card.actionCardSpecStatus() == RaftActionCardStatus.Prepared || card.isFrozen
    val answerer = card.executedByUserName?.takeIf { it.isNotBlank() } ?: addressee
    val answer = spec.questionAnswerText(displayLocale)
    // Expired: the person asked by name; asked of anyone (or no addressee on the card), "Nobody answered".
    val endedLine = card.questionEndedLine(asker, addressee.takeIf { card.questionAddresseeUserId != null }, count.coerceAtLeast(1))
    return QuestionCardWords(
        asked = asked,
        single = single,
        text = text,
        buttonRowHint =
            when {
                endedLine != null -> endedLine
                !waiting -> null
                asked -> questionLineWithTimeLeft(stringResource(Res.string.action_card_question_waiting_for_your_answer, asker), timeLeft)
                else -> stringResource(Res.string.action_card_question_waiting_for_answer, addressee)
            },
        answeredLine = answer?.let { questionAnsweredText(answerer, it) } ?: record?.let(::AnnotatedString),
        slimTitle =
            when {
                single -> text
                count == 1 -> stringResource(Res.string.action_card_question_asker_has_questions_one, asker, count)
                else -> stringResource(Res.string.action_card_question_asker_has_questions_other, asker, count)
            },
        slimDescription =
            when {
                !waiting -> null
                asked -> questionLineWithTimeLeft(stringResource(Res.string.action_card_question_asking_you, asker), timeLeft)
                else -> questionLineWithTimeLeft(stringResource(Res.string.action_card_question_waiting_for, addressee), timeLeft)
            },
        endedLine = endedLine,
    )
}

/**
 * The generic confirm before an execute (`confirm.v1`, web `SpecConfirmDialog`): the server's
 * title, body (a tree without buttons) and verb, red only for a destructive execute. Only its
 * commit executes; a failure stays in the sheet for another try. Same sheet as the install
 * confirmation ([ThreadInstallAppActionDialog]).
 */
@Composable
internal fun ThreadActionCardSpecConfirmSheet(
    state: ThreadActionCardSpecConfirmState,
    onIntent: (ThreadActionCardSpecIntent) -> Unit,
) {
    val viewerServerId = LocalThreadViewerServerId.current
    val names = LocalThreadActionCardNames.current
    val displayLocale = rememberActionCardDisplayLocale()
    val context =
        RaftCardTreeContext(
            displayLocale = displayLocale,
            entityName = { ref -> names.actionCardSpecEntityName(ref, ref.serverIdOr(state.card.sourceServerId), viewerServerId) },
            columnGap = 8.dp,
        )
    val error = threadActionCardSpecErrorText(state.error)
    RaftDesignSystemTheme {
        RaftDialogSheet(
            onDismissRequest = { onIntent(ThreadActionCardSpecIntent.DismissConfirm) },
            title = actionCardMsgPlain(state.confirm.title, context),
            commitLabel = actionCardMsgPlain(state.confirm.confirmLabel, context),
            onCommit = { onIntent(ThreadActionCardSpecIntent.ConfirmExecute) },
            commitEnabled = !state.card.isExecuted && !state.card.isFrozen && !state.card.hasEnded,
            commitLoading = state.submitting,
            commitVariant = if (state.destructive) RaftButtonVariant.Danger else RaftButtonVariant.Primary,
            closeLabel = stringResource(Res.string.common_close),
            cancelLabel = stringResource(Res.string.common_cancel),
            closeEnabled = !state.submitting,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                RaftCardTree(state.confirm.spec.root, state.confirm.spec.elements, context)
                if (error != null) RaftBanner(description = error, status = RaftBannerStatus.Warning, size = RaftBannerSize.Sm)
            }
        }
    }
}

/**
 * What an action card form's pickers choose from on a thread page (A3's [ActionCardFormCandidates]):
 * the card's server and channel members, and the Home sidebar's channels. `scope: "joined"` is
 * the joined ones; `scope: "visible"` has no wider list on this device, so it is every row Home
 * holds (the server checks visibility on submit, 422 `not_found` / `not_allowed`).
 */
internal fun threadActionCardFormCandidates(
    serverMembers: List<MemberProfile>,
    channelMembers: List<MemberProfile>,
    channels: List<HomeChannelRow>,
): ActionCardFormCandidates {
    val rows =
        channels.filter { it.kind == ConversationKind.Channel || it.kind == ConversationKind.Joint }.map { row ->
            row.joined to
                RaftForwardTargetPickerItem(
                    stableKey = row.channelId.value,
                    title = row.name.removePrefix("#"),
                    subtitle = "",
                    visualKind =
                        when {
                            row.kind == ConversationKind.Joint || row.channelType?.trim()?.lowercase() == "joint" -> RaftForwardTargetVisualKind.JointChannel
                            row.channelType?.trim()?.lowercase() == "private" -> RaftForwardTargetVisualKind.PrivateChannel
                            else -> RaftForwardTargetVisualKind.PublicChannel
                        },
                    payload = row.channelId.value,
                )
        }
    return ActionCardFormCandidates(
        serverMembers = serverMembers,
        channelMembers = channelMembers,
        joinedChannels = rows.filter { it.first }.map { it.second },
        visibleChannels = rows.map { it.second },
    )
}
