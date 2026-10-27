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
import androidx.compose.ui.Modifier
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
import build.raft.app.data.ThreadActionCard
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
)

internal val LocalThreadActionCardSpecHost = compositionLocalOf { ThreadActionCardSpecHost() }

/** The frame's status for a card's `state`. */
internal fun ThreadActionCard.actionCardSpecStatus(): RaftActionCardStatus =
    when {
        isExecuted -> RaftActionCardStatus.Executed
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
            card.isExecuted -> null
            card.sourceServerId != null && viewerServerId != null && card.sourceServerId != viewerServerId ->
                card.targetServerName?.let { stringResource(Res.string.thread_action_card_blocked_target_server, it) }
                    ?: stringResource(Res.string.thread_action_card_blocked_source_workspace)
            else -> null
        }
    val committedBy = card.executedByUserName?.takeIf { it.isNotBlank() }?.let { stringResource(Res.string.thread_action_card_committed_by, it) }
    val record = committedBy?.let { by -> executedTimeLabel?.let { "$by · $it" } ?: by } ?: executedTimeLabel
    val reconfirmLabel = stringResource(Res.string.thread_action_card_reconfirm)
    val rendered =
        remember(spec, card.needsReconfirmation, reconfirmLabel, displayLocale) {
            if (card.needsReconfirmation) {
                spec.withChangingButtonsLabelled(actionCardAppMsg("actionCard.reconfirm", reconfirmLabel, displayLocale))
            } else {
                spec
            }
        }
    val frame =
        RaftActionCardFrameState(
            status = card.actionCardSpecStatus(),
            record = record,
            blockedReason = blockedReason,
            readOnly = !commitEnabled,
            busyElementKey = slice.busyElement(typedPending),
            error = threadActionCardSpecErrorText(slice.error),
        )
    RaftActionCardView(
        card = rendered,
        state = frame,
        entityName = { ref, serverId -> names.actionCardSpecEntityName(ref, serverId, viewerServerId) },
        onAction = onAction,
        modifier = modifier,
        sourceServerId = card.sourceServerId,
        displayLocale = displayLocale,
        linkNavigable = { button -> button.actionCardSpecLinkNavigable(card.sourceServerId, viewerServerId) },
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
            commitEnabled = !state.card.isExecuted && !state.card.isFrozen,
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
