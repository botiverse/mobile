package build.raft.app

import build.raft.app.resources.*
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.ActionCardSpecAction
import build.raft.app.data.ActionCardSpecButton
import build.raft.app.data.ActionCardSpecCard
import build.raft.app.data.ActionCardSpecEntityRef
import build.raft.app.data.ActionCardSpecContent
import build.raft.app.data.ActionCardSpecDisplayElement
import build.raft.app.data.ActionCardSpecTree
import build.raft.app.data.CardStack
import build.raft.app.data.CardStackRole
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerSize
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftMessageActionCard
import build.raft.designsystem.components.RaftMessageActionCardDefaults
import build.raft.designsystem.components.RaftMessageActionCardHeaderDescription
import build.raft.designsystem.components.RaftMessageActionCardNotice
import build.raft.designsystem.components.RaftMessageActionCardStatusTone
import build.raft.designsystem.theme.RaftTheme

/** `actionMetadata.state` as the frame shows it. */
internal enum class RaftActionCardStatus {
    Prepared,
    Executed,
    Frozen,
    ReconfirmRequired,

    /** Past its deadline without running (wire `expired`, or `expiresAt` behind the client clock). */
    Expired,

    /** A question card its agent took back (wire `withdrawn`). */
    Withdrawn;

    /** Ended without running: folded, no Button that changes it. */
    val ended: Boolean
        get() = this == Expired || this == Withdrawn
}

/**
 * What the frame reads from `actionMetadata` and the viewer, never from the spec (S0: the
 * frame is the client's). The caller (A4) fills it in.
 *
 * - [status]: the card's state.
 * - [record]: an executed card's line under the title ("Created by Ada · 10:51").
 * - [blockedReason]: why this viewer can't act (a guest, another server's card); the card is
 *   view only and says why.
 * - [readOnly]: this viewer can't act and there is nothing to say (a read-only thread): view
 *   only, no buttons but navigation.
 * - [busyElementKey]: the Button whose action is running; the others wait.
 * - [error]: the last action's failure, under the body.
 * - [endedLine]: an [RaftActionCardStatus.ended] card's line under the title: when it expired and
 *   to ask for a new one, or a seat request's "no owner answered".
 * - [runsOnServerName]: `actionMetadata.targetServerName` when the card's message is in a joint
 *   channel (web `useActionCardAccess.runsOnTargetServer`): the action always runs on that
 *   server, never the viewer's, and the body opens with "Runs on {server}". Null otherwise.
 */
internal data class RaftActionCardFrameState(
    val status: RaftActionCardStatus = RaftActionCardStatus.Prepared,
    val record: String? = null,
    val blockedReason: String? = null,
    val readOnly: Boolean = false,
    val busyElementKey: String? = null,
    val error: String? = null,
    val runsOnServerName: String? = null,
    val endedLine: String? = null,
    /**
     * The viewer may not act here, but the card keeps the shape everyone sees (a question card for
     * someone else): every Button that changes the card is disabled, nothing folds or says "View only".
     */
    val controlsDisabled: Boolean = false,
)

/**
 * A question card's slim frame (B, question-card redesign 2026-11, raft-design questions.md): no
 * icon tile and no "Answer question(s)" title. [title] replaces the card's (a single question's
 * text, or "Fixer has 3 questions"), [description] is the line under it while the card waits
 * ("Fixer is asking you · 7 days left", "Waiting for Maya · 7 days left"). It never folds and wears
 * no status tag: once answered or ended its controls give way to one [statusLine] where they were
 * ("✓ Maya answered **Postgres**" with [statusCheck], "Maya didn't answer within 7 days. Fixer
 * was told.", "Fixer withdrew these questions.").
 */
internal data class RaftActionCardSlim(
    val title: String?,
    val description: String?,
    val statusLine: AnnotatedString? = null,
    val statusCheck: Boolean = false,
)

/**
 * Whether the frame shows, enables or busies a card Button (web `ActionCardSpecButton`). Links
 * (navigate, a URL, "Open in web", which strip makes of any button this App can't run) never
 * change the card, so they stay usable on a frozen, view-only or busy card and run nothing: a tap
 * only reports the element id. Buttons that change the card (execute, a form, a native dialog)
 * are gone on a read-only card, wait while another action runs, and are disabled on a frozen
 * card or for a viewer who can't act. [inSpecOfExecutedCard]: a done card keeps its body as the
 * record without any button (its result tree has its own).
 */
internal fun actionCardFrameButtonState(
    state: RaftActionCardFrameState,
    elementId: String,
    button: ActionCardSpecButton,
    inSpecOfExecutedCard: Boolean = false,
    /** False for a link this viewer can't follow (a navigate to another server's entity): disabled, as on the web. */
    linkNavigable: Boolean = true,
): RaftCardButtonState {
    // A result intent (`open feedback_ticket`, S0.3) only views what the done card's result names:
    // a link too, shown on the done card.
    val link =
        button.action is ActionCardSpecAction.Navigate ||
            button.action is ActionCardSpecAction.NavigateUrl ||
            button.action == ActionCardSpecAction.OpenInWeb ||
            (button.action as? ActionCardSpecAction.Open)?.intent?.resultOnly == true
    return when {
        inSpecOfExecutedCard -> RaftCardButtonState.Hidden
        link -> if (linkNavigable) RaftCardButtonState.Enabled else RaftCardButtonState.Disabled
        state.readOnly || state.status == RaftActionCardStatus.Executed || state.status.ended -> RaftCardButtonState.Hidden
        elementId == state.busyElementKey -> RaftCardButtonState.Busy
        state.busyElementKey != null || state.status == RaftActionCardStatus.Frozen || state.blockedReason != null || state.controlsDisabled -> RaftCardButtonState.Disabled
        else -> RaftCardButtonState.Enabled
    }
}

/**
 * A first-party action card drawn from its `presentation.card` (`action-card.v1`, task #36 A2):
 * the generic frame round the spec, the same for every action type (web `ActionCard`, DS
 * [RaftMessageActionCard]).
 *
 * - Header: the card's [ActionCardSpecCard.icon] (the default zap for one this build has
 *   no glyph for) and its title (a Msg in the viewer's language, entities by name).
 * - Body: "Runs on {server}" first for a joint channel's card
 *   ([RaftActionCardFrameState.runsOnServerName]), then the spec ([RaftCardTree]); once
 *   executed, the spec stays as the record without its buttons, then the `result` tree (web
 *   `ActionCardSpecCard`).
 * - Status, as [ThreadActionCardView]: Done with the success pixel and the [record] under the
 *   title (with a check), folded; Expired / Withdrawn folded with the [endedLine] under the title
 *   and no action button; Frozen with every action button disabled; Confirm again
 *   with the pink pixel; View only for [RaftActionCardFrameState.blockedReason] (folded, the
 *   reason under the title, buttons disabled) or [RaftActionCardFrameState.readOnly] (no action
 *   buttons). A done, frozen, to-reconfirm or view-only card folds by the reader's hand.
 * - Buttons: [actionCardFrameButtonState]. Busy: the running Button shows the spinner, the other
 *   actions wait. An error is a small warning banner under the body.
 *
 * [entityName] resolves an entity on a server (its own `serverId`, else [sourceServerId]) to the
 * name in the app's store (members, agents, channels, computers) or null, which shows the card's fallback; a card never shows a UUID.
 * [onAction] gets the tapped Button's element id; what it does (execute, confirm, form, open,
 * navigate, open in web) is the caller's (A4).
 */
@Composable
internal fun RaftActionCardView(
    card: ActionCardSpecCard,
    state: RaftActionCardFrameState,
    entityName: (ref: ActionCardSpecEntityRef, serverId: String?) -> String?,
    onAction: (elementId: String) -> Unit,
    modifier: Modifier = Modifier,
    /** `actionMetadata.sourceServerId`: where an entity without its own `serverId` lives. */
    sourceServerId: String? = null,
    displayLocale: String = rememberActionCardDisplayLocale(),
    /** Overrides whether the card starts open (a done or view-only card starts folded). */
    initiallyExpanded: Boolean? = null,
    /** Whether a link Button leads somewhere this viewer can open ([actionCardFrameButtonState] `linkNavigable`). */
    linkNavigable: (ActionCardSpecButton) -> Boolean = { true },
    /** Property labels take only their width ([RaftCardTreeContext.fitLabels]). */
    fitLabels: Boolean = false,
    /** Drawn under the body, before the error: a question card's inline answer ([ActionCardQuestionInlineForm]). */
    footer: (@Composable () -> Unit)? = null,
    /** A question card's slim frame ([RaftActionCardSlim]); null: the generic frame. */
    slim: RaftActionCardSlim? = null,
) {
    val executed = state.status == RaftActionCardStatus.Executed
    val ended = state.status.ended
    val viewOnly = !executed && !ended && (state.readOnly || state.blockedReason != null)
    // A slim question card never folds (raft-design questions.md).
    val foldsByDefault = slim == null && (executed || ended || (state.blockedReason != null && state.status == RaftActionCardStatus.Prepared))
    var expanded by remember(foldsByDefault, initiallyExpanded) { mutableStateOf(initiallyExpanded ?: !foldsByDefault) }
    fun context(inSpecOfExecutedCard: Boolean) =
        RaftCardTreeContext(
            displayLocale = displayLocale,
            entityName = { ref -> entityName(ref, ref.serverIdOr(sourceServerId)) },
            onAction = onAction,
            columnGap = 8.dp,
            buttonState = { id, button -> actionCardFrameButtonState(state, id, button, inSpecOfExecutedCard, linkNavigable(button)) },
            fitLabels = fitLabels,
        )
    val context = context(inSpecOfExecutedCard = executed)
    val cardTitle = actionCardMsgPlainMentions(card.title, context)
    val slimDescription = slim?.description?.takeIf { !executed && !ended && it.isNotBlank() }
    val title = slim?.title?.takeIf { it.isNotBlank() } ?: cardTitle
    val foldedResult =
        if (executed && slim == null) card.result?.let { result -> actionCardFoldedResult(result, cardTitle, context) } else null
    RaftDesignSystemTheme {
        val iconColor = raftArgbHex(RaftTheme.colors.foregroundMuted)
        RaftMessageActionCard(
            title = title,
            modifier = modifier,
            showIcon = slim == null,
            icon =
                actionCardIconName(card.icon)?.let { name ->
                    { RaftLucideIcon(name = name, color = iconColor, modifier = Modifier.size(RaftMessageActionCardDefaults.HeaderGlyphSize)) }
                },
            description =
                when {
                    slim != null ->
                        (state.blockedReason ?: slimDescription)?.takeIf { it.isNotBlank() }?.let { line -> { RaftMessageActionCardHeaderDescription(line) } }
                    executed ->
                        state.record?.takeIf { it.isNotBlank() }?.let { line ->
                            // Folded, what came of it after an arrow (web ActionCardFoldedResult): "✓ By Ada · 20:28 → Sent to Raft".
                            val shown = foldedResult?.takeIf { !expanded }?.let { "$line $ACTION_CARD_RESULT_ARROW$it" } ?: line
                            { RaftMessageActionCardHeaderDescription(shown, check = true) }
                        }
                    ended -> state.endedLine?.takeIf { it.isNotBlank() }?.let { line -> { RaftMessageActionCardHeaderDescription(line) } }
                    else -> state.blockedReason?.takeIf { foldsByDefault }?.let { reason -> { RaftMessageActionCardHeaderDescription(reason) } }
                },
            status =
                when {
                    // A slim question card says answered / expired / withdrawn in its status line, not a tag.
                    slim != null && (executed || ended) -> null
                    executed -> stringResource(Res.string.common_done)
                    state.status == RaftActionCardStatus.Expired -> stringResource(Res.string.thread_action_card_status_expired)
                    state.status == RaftActionCardStatus.Withdrawn -> stringResource(Res.string.thread_action_card_status_withdrawn)
                    state.status == RaftActionCardStatus.Frozen -> stringResource(Res.string.thread_action_card_frozen)
                    state.status == RaftActionCardStatus.ReconfirmRequired -> stringResource(Res.string.thread_action_card_status_reconfirm)
                    viewOnly && (slim == null || state.blockedReason != null) -> stringResource(Res.string.thread_action_card_view_only)
                    else -> null
                },
            statusTone =
                when {
                    executed -> RaftMessageActionCardStatusTone.Success
                    state.status == RaftActionCardStatus.ReconfirmRequired -> RaftMessageActionCardStatusTone.Accent
                    else -> RaftMessageActionCardStatusTone.Neutral
                },
            expanded = expanded,
            onExpandedChange =
                if (slim == null && (executed || viewOnly || state.status != RaftActionCardStatus.Prepared)) ({ expanded = it }) else null,
            toggleLabel = stringResource(Res.string.thread_action_card_toggle_details),
        ) {
            state.runsOnServerName?.takeIf { it.isNotBlank() }?.let { server ->
                RaftMessageActionCardNotice(stringResource(Res.string.thread_action_card_runs_on_server, server))
            }
            RaftCardTree(card.spec.root, card.spec.elements, context)
            if (executed && slim == null) card.result?.let { result -> RaftCardTree(result.root, result.elements, context(inSpecOfExecutedCard = false)) }
            slim?.statusLine?.takeIf { (executed || ended) && it.isNotBlank() }?.let { line -> QuestionStatusLine(line, check = slim.statusCheck) }
            footer?.invoke()
            state.error?.takeIf { it.isNotBlank() }?.let { error ->
                RaftBanner(description = error, status = RaftBannerStatus.Warning, size = RaftBannerSize.Sm)
            }
        }
    }
}

/** The title is one plain line of the header: entities as mentions, without their styling. */
private fun actionCardMsgPlainMentions(msg: build.raft.app.data.ActionCardSpecMsg, context: RaftCardTreeContext): String =
    actionCardMsgText(msg, context).text

/** Before a folded done card's result: punctuation, not copy; the no-break space keeps the arrow on its first word's line. */
private const val ACTION_CARD_RESULT_ARROW = "\u2192\u00a0"

/**
 * A done card's result section's first item as its folded header names it (web `resultHeadline` +
 * `ActionCardFoldedResult`): the first Text or EntityRef in the tree's order, through its Stacks,
 * past its buttons and its actions row; null when the section starts with anything else (a Field,
 * a badge, a notice), or when the card's [title] already says it (it would only repeat a name).
 */
internal fun actionCardFoldedResult(result: ActionCardSpecTree, title: String, context: RaftCardTreeContext): String? {
    // Some(text) found, Some(null) a first item it cannot name, null nothing here (keep looking).
    fun visit(id: String, depth: Int): Pair<String?, Boolean>? {
        val node = result.elements[id] ?: return null
        if (depth > 8) return null
        return when (node) {
            is ActionCardSpecDisplayElement.Text ->
                when (val text = node.text) {
                    is ActionCardSpecContent.Plain -> text.text
                    is ActionCardSpecContent.Message -> actionCardMsgPlain(text.msg, context)
                } to true
            is ActionCardSpecDisplayElement.EntityRef -> actionCardEntityName(node.ref, context) to true
            is ActionCardSpecButton -> null
            is CardStack ->
                if (node.role == CardStackRole.Actions) null else node.children.orEmpty().firstNotNullOfOrNull { child -> visit(child, depth + 1) }
            else -> null to true
        }
    }
    val text = visit(result.root, 0)?.first?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    fun word(value: String) = value.trim().lowercase().removePrefix("#").removePrefix("@")
    return text.takeIf { word(it) !in word(title) }
}
