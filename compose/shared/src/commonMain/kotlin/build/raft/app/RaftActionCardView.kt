package build.raft.app

import build.raft.app.resources.*
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.ActionCardSpecAction
import build.raft.app.data.ActionCardSpecButton
import build.raft.app.data.ActionCardSpecCard
import build.raft.app.data.ActionCardSpecEntityRef
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
internal enum class RaftActionCardStatus { Prepared, Executed, Frozen, ReconfirmRequired }

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
): RaftCardButtonState {
    val link =
        button.action is ActionCardSpecAction.Navigate ||
            button.action is ActionCardSpecAction.NavigateUrl ||
            button.action == ActionCardSpecAction.OpenInWeb
    return when {
        inSpecOfExecutedCard -> RaftCardButtonState.Hidden
        link -> RaftCardButtonState.Enabled
        state.readOnly || state.status == RaftActionCardStatus.Executed -> RaftCardButtonState.Hidden
        elementId == state.busyElementKey -> RaftCardButtonState.Busy
        state.busyElementKey != null || state.status == RaftActionCardStatus.Frozen || state.blockedReason != null -> RaftCardButtonState.Disabled
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
 *   title (with a check), folded; Frozen with every action button disabled; Confirm again
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
) {
    val executed = state.status == RaftActionCardStatus.Executed
    val viewOnly = !executed && (state.readOnly || state.blockedReason != null)
    val foldsByDefault = executed || (state.blockedReason != null && state.status == RaftActionCardStatus.Prepared)
    var expanded by remember(foldsByDefault, initiallyExpanded) { mutableStateOf(initiallyExpanded ?: !foldsByDefault) }
    fun context(inSpecOfExecutedCard: Boolean) =
        RaftCardTreeContext(
            displayLocale = displayLocale,
            entityName = { ref -> entityName(ref, ref.serverIdOr(sourceServerId)) },
            onAction = onAction,
            columnGap = 8.dp,
            buttonState = { id, button -> actionCardFrameButtonState(state, id, button, inSpecOfExecutedCard) },
        )
    val context = context(inSpecOfExecutedCard = executed)
    val title = actionCardMsgPlainMentions(card.title, context)
    RaftDesignSystemTheme {
        val iconColor = raftArgbHex(RaftTheme.colors.foregroundMuted)
        RaftMessageActionCard(
            title = title,
            modifier = modifier,
            icon =
                actionCardIconName(card.icon)?.let { name ->
                    { RaftLucideIcon(name = name, color = iconColor, modifier = Modifier.size(RaftMessageActionCardDefaults.HeaderGlyphSize)) }
                },
            description =
                when {
                    executed -> state.record?.takeIf { it.isNotBlank() }?.let { line -> { RaftMessageActionCardHeaderDescription(line, check = true) } }
                    else -> state.blockedReason?.takeIf { foldsByDefault }?.let { reason -> { RaftMessageActionCardHeaderDescription(reason) } }
                },
            status =
                when {
                    executed -> stringResource(Res.string.common_done)
                    state.status == RaftActionCardStatus.Frozen -> stringResource(Res.string.thread_action_card_frozen)
                    state.status == RaftActionCardStatus.ReconfirmRequired -> stringResource(Res.string.thread_action_card_status_reconfirm)
                    viewOnly -> stringResource(Res.string.thread_action_card_view_only)
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
                if (executed || viewOnly || state.status != RaftActionCardStatus.Prepared) ({ expanded = it }) else null,
            toggleLabel = stringResource(Res.string.thread_action_card_toggle_details),
        ) {
            state.runsOnServerName?.takeIf { it.isNotBlank() }?.let { server ->
                RaftMessageActionCardNotice(stringResource(Res.string.thread_action_card_runs_on_server, server))
            }
            RaftCardTree(card.spec.root, card.spec.elements, context)
            if (executed) card.result?.let { result -> RaftCardTree(result.root, result.elements, context(inSpecOfExecutedCard = false)) }
            state.error?.takeIf { it.isNotBlank() }?.let { error ->
                RaftBanner(description = error, status = RaftBannerStatus.Warning, size = RaftBannerSize.Sm)
            }
        }
    }
}

/** The title is one plain line of the header: entities as mentions, without their styling. */
private fun actionCardMsgPlainMentions(msg: build.raft.app.data.ActionCardSpecMsg, context: RaftCardTreeContext): String =
    actionCardMsgText(msg, context).text
