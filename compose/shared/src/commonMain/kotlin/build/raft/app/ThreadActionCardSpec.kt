package build.raft.app

import build.raft.app.data.ActionCardExecuteOutcome
import build.raft.app.data.ScreenError
import build.raft.app.data.ActionCardFormIssue
import build.raft.app.data.ActionCardFormValue
import build.raft.app.data.ActionCardSpecAction
import build.raft.app.data.ActionCardSpecButton
import build.raft.app.data.ActionCardSpecCard
import build.raft.app.data.ActionCardSpecConfirm
import build.raft.app.data.ActionCardSpecEmphasis
import build.raft.app.data.ActionCardSpecForm
import build.raft.app.data.ActionCardSpecIntent
import build.raft.app.data.ActionCardSpecNavigateTarget
import build.raft.app.data.ActionCardSpecTree
import build.raft.app.data.CardStack
import build.raft.app.data.DataResult
import build.raft.app.data.ExecuteActionFormBody
import build.raft.app.data.FeedbackSubmitActionCardType
import build.raft.app.data.ThreadActionCard
import build.raft.app.data.isFeedbackSubmitAction
import build.raft.app.data.stripActionCardSpec
import build.raft.app.model.ConversationKind
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.ServerId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// Which card renders (task #39, A4).

/**
 * Types that keep their typed card with the flag on (web `TYPED_CARD_ONLY_ACTION_TYPES`): the MCP
 * connect picker, and the install confirmation that shows the App's prompt text and turns its 409
 * refusals into copy (ThreadInstallAppActionDialog); the spec has neither yet.
 */
internal val ThreadActionCardTypedOnlyTypes: Set<String> = setOf("integration:connect_mcp", "integration:install_marketplace_app")

/** The server whose `action_card_spec` flag decides for [this] card: its own, else the viewer's (web `ActionCard`). */
internal fun ThreadActionCard.actionCardSpecFlagServerId(viewerServerId: String?): String? =
    sourceServerId?.takeIf { it.isNotBlank() } ?: viewerServerId?.takeIf { it.isNotBlank() }

/**
 * The spec card to draw for [this] card (web `ActionCard` + `readActionCardSpec`), or null for the
 * typed / legacy card exactly as before: the flag is off for the card's server ([specEnabled]),
 * the card has no `presentation.card` this App can read (A1's read parse; an unknown catalog or
 * a card that fails it is already null), or its type keeps its typed card
 * ([ThreadActionCardTypedOnlyTypes]). Otherwise the card stripped to [capabilities]: every
 * button this App cannot run is "Open in web". A result's "View in Settings"
 * (`open feedback_ticket`) stays only when [feedbackTicketVisible] ([showsFeedbackTicket]);
 * otherwise it is removed, and the viewer sees the summary alone (S0.3: hidden, not "Open in web").
 */
internal fun ThreadActionCard.actionCardSpecToRender(
    specEnabled: Boolean,
    capabilities: Collection<String>,
    feedbackTicketVisible: Boolean = false,
): ActionCardSpecCard? {
    if (!specEnabled) return null
    if (type in ThreadActionCardTypedOnlyTypes) return null
    val read = presentationCard ?: return null
    val stripped = stripActionCardSpec(read, actionCardSpecCapabilitiesFor(capabilities)) ?: return null
    return if (feedbackTicketVisible) stripped else stripped.withoutResultIntentButtons()
}

/**
 * Whether the viewer may follow a done `feedback:submit` card to its ticket (S0.3, slock #11381
 * `docs/development/action-card-forms.md`): the card is done with a `product-feedback` result,
 * the viewer ([viewerUserId]) is the one who sent it (`executedByUserId`), and the ticket was
 * filed into this App's own Hands app (`result.source` == [clientOs], `android` / `ios`:
 * `featureFlagClientOs`). A ticket is read only through its own app. [feedbackPageAvailable]: the
 * App's Settings › Feedback is the page that lists this App's tickets (`product_feedback_multi_app`
 * on for the viewing server); without it the page could not show the ticket.
 */
internal fun ThreadActionCard.showsFeedbackTicket(viewerUserId: String?, clientOs: String, feedbackPageAvailable: Boolean): Boolean {
    if (!feedbackPageAvailable || !isExecuted || !isFeedbackSubmitAction()) return false
    if (resultKind != "product-feedback") return false
    val viewer = viewerUserId?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    val source = resultSource ?: return false
    return executedByUserId == viewer && clientOs.isNotBlank() && source == clientOs
}

/**
 * [this] without the result buttons that `open` a result intent (`feedback_ticket`): removed from
 * the elements and from every Stack's children. A result whose root was such a button is dropped.
 */
internal fun ActionCardSpecCard.withoutResultIntentButtons(): ActionCardSpecCard {
    val tree = result ?: return this
    val removed =
        tree.elements.filterValues { node ->
            node is ActionCardSpecButton && (node.action as? ActionCardSpecAction.Open)?.intent?.resultOnly == true
        }.keys
    if (removed.isEmpty()) return this
    if (tree.root in removed) return copy(result = null)
    val elements =
        tree.elements.filterKeys { it !in removed }.mapValues { (_, node) ->
            if (node is CardStack && node.children.orEmpty().any { it in removed }) node.copy(children = node.children.orEmpty().filterNot { it in removed }) else node
        }
    return copy(result = ActionCardSpecTree(tree.root, elements))
}

/**
 * [capabilities] for this card: an `intent.*` only for the intent whose native path is this card's
 * type ([typedActionType]). The App's native paths are per type (the typed card's button), so an
 * `open` for another type's dialog strips to "Open in web" with that label, not a button that
 * keeps its own label and quietly opens the browser.
 */
internal fun ThreadActionCard.actionCardSpecCapabilitiesFor(capabilities: Collection<String>): List<String> =
    capabilities.filter { capability ->
        val intent = ActionCardSpecIntent.entries.firstOrNull { it.capability == capability } ?: return@filter true
        intent.typedActionType()?.equals(type, ignoreCase = true) == true
    }

/** The card type whose existing native path an `open` intent runs; null for one the App has none for. */
internal fun ActionCardSpecIntent.typedActionType(): String? =
    when (this) {
        ActionCardSpecIntent.CreateChannel -> "channel:create"
        ActionCardSpecIntent.AddMembers -> "channel:add_member"
        ActionCardSpecIntent.CreateAgent -> "agent:create"
        ActionCardSpecIntent.FeedbackForm -> FeedbackSubmitActionCardType
        // A done feedback card's "View in Settings" (S0.3): Settings › Feedback on the ticket.
        ActionCardSpecIntent.FeedbackTicket -> FeedbackSubmitActionCardType
        ActionCardSpecIntent.UpgradeDialog -> null
    }

/** A Button that changes the card (as opposed to a link). A result intent only views what the result names. */
internal val ActionCardSpecAction.changesCard: Boolean
    get() = this is ActionCardSpecAction.Execute || this is ActionCardSpecAction.OpenForm ||
        (this is ActionCardSpecAction.Open && !intent.resultOnly)

/** The Button [elementId] of the rendered card: in its body, else in its result. */
internal fun ActionCardSpecCard.button(elementId: String): ActionCardSpecButton? =
    spec.element(elementId) as? ActionCardSpecButton ?: result?.element(elementId) as? ActionCardSpecButton

/** What a tap on a spec card Button does ([threadActionCardSpecPress]). */
internal sealed interface ThreadActionCardSpecPress {
    /** Nothing: no such button, it is disabled, or the card can't change now. */
    data object Ignore : ThreadActionCardSpecPress

    /**
     * The typed card's path (ThreadPage `commitActionCard`): an `open` intent's native dialog or
     * run, as the typed card's button does today; and any button that changes a card waiting on
     * Confirm again, which reconfirms it (web: an execute on such a card is Confirm again).
     */
    data object Typed : ThreadActionCardSpecPress

    /** The store runs it: an execute, an execute behind a confirm, a form. */
    data object Store : ThreadActionCardSpecPress

    data class Navigate(val target: ActionCardSpecNavigateTarget) : ThreadActionCardSpecPress

    /** A public HTTPS URL outside Raft, opened as every other link. */
    data class OpenUrl(val url: String) : ThreadActionCardSpecPress

    /** This message's web link, in the browser. */
    data object OpenInWeb : ThreadActionCardSpecPress

    /**
     * `open feedback_ticket` (S0.3): Settings › Feedback on [ticketId], the done card's
     * `result.ticketId`; null (none, or not a UUID) opens the feedback list.
     */
    data class OpenFeedbackTicket(val ticketId: String?) : ThreadActionCardSpecPress
}

private val FEEDBACK_TICKET_UUID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

/** A ticket id Settings › Feedback can open (a UUID, per S0.3), else null: the list. */
internal fun feedbackTicketIdOrNull(raw: String?): String? = raw?.trim()?.takeIf { FEEDBACK_TICKET_UUID.matches(it) }

/**
 * Routes a tap on Button [elementId] of the rendered [spec] of [card] (spec §3.3, web
 * `ActionCardSpecButton`). Links always work; a Button that changes the card does nothing on a
 * done or frozen card, and an `open` intent whose native path is for another type (or none) opens
 * in web, as strip would have.
 */
internal fun threadActionCardSpecPress(
    card: ThreadActionCard,
    spec: ActionCardSpecCard,
    elementId: String,
    viewerServerId: String? = null,
): ThreadActionCardSpecPress {
    val button = spec.button(elementId) ?: return ThreadActionCardSpecPress.Ignore
    if (button.disabledReason != null) return ThreadActionCardSpecPress.Ignore
    val buttonAction = button.action
    // A result intent views the done card's result: it works on a done card, where every button
    // that changes the card is gone. Only on the card type it belongs to.
    if (buttonAction is ActionCardSpecAction.Open && buttonAction.intent.resultOnly) {
        return if (card.isExecuted && buttonAction.intent.typedActionType()?.equals(card.type, ignoreCase = true) == true) {
            ThreadActionCardSpecPress.OpenFeedbackTicket(feedbackTicketIdOrNull(card.resultTicketId))
        } else {
            ThreadActionCardSpecPress.OpenInWeb
        }
    }
    return when (val action = buttonAction) {
        is ActionCardSpecAction.Navigate ->
            if (actionCardTargetOnViewerServer(action.target, card.sourceServerId, viewerServerId)) {
                ThreadActionCardSpecPress.Navigate(action.target)
            } else {
                ThreadActionCardSpecPress.Ignore
            }
        is ActionCardSpecAction.NavigateUrl -> ThreadActionCardSpecPress.OpenUrl(action.url)
        ActionCardSpecAction.OpenInWeb, is ActionCardSpecAction.Unsupported -> ThreadActionCardSpecPress.OpenInWeb
        is ActionCardSpecAction.Execute, is ActionCardSpecAction.OpenForm, is ActionCardSpecAction.Open ->
            when {
                card.isExecuted || card.isFrozen -> ThreadActionCardSpecPress.Ignore
                card.needsReconfirmation -> ThreadActionCardSpecPress.Typed
                action is ActionCardSpecAction.Open ->
                    if (action.intent.typedActionType()?.equals(card.type, ignoreCase = true) == true) {
                        ThreadActionCardSpecPress.Typed
                    } else {
                        ThreadActionCardSpecPress.OpenInWeb
                    }
                else -> ThreadActionCardSpecPress.Store
            }
    }
}

/**
 * The [RaftMessageLink] of [message] on this page, by the rules of [threadMessagePermalink]: a reply
 * in a thread links to its parent's channel with `thread=`; a DM is `/dm/`. Null without a slug
 * or a channel.
 */
internal fun RaftThreadScreenPayload.threadMessageLink(messageId: String, serverSlug: String?): RaftMessageLink? {
    val slug = serverSlug?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val id = messageId.trim().takeIf { it.isNotBlank() } ?: return null
    val replyParentMessageId =
        if (surfaceKind == RaftThreadSurfaceKind.ReplyThread) {
            originParentMessageId?.takeIf { it.isNotBlank() } ?: parentMessageId?.takeIf { it.isNotBlank() }
        } else {
            null
        }
    val channel =
        if (replyParentMessageId != null) {
            parentChannelId?.takeIf { it.isNotBlank() }
        } else {
            sourceThreadId?.takeIf { it.isNotBlank() }
                ?: streamChannelId?.takeIf { it.isNotBlank() }
                ?: parentChannelId?.takeIf { it.isNotBlank() }
        } ?: return null
    val dm =
        if (replyParentMessageId != null) {
            parseConversationKindOrNull(parentChannelKind) == ConversationKind.DirectMessage
        } else {
            surfaceKind == RaftThreadSurfaceKind.DirectMessage || parseConversationKindOrNull(channelKind) == ConversationKind.DirectMessage
        }
    return RaftMessageLink(
        serverSlug = slug,
        routeKind = if (dm) ConversationKind.DirectMessage else ConversationKind.Channel,
        channelId = channel,
        messageId = id,
        threadParentMessageId = replyParentMessageId,
    )
}

/**
 * Whether a navigate target is on the viewer's server, so the App can open it (web
 * `isOnViewerServer`): its own server, else the card's, else the viewer's; never without a viewer
 * server. A target elsewhere is a disabled Button, as on the web.
 */
internal fun actionCardTargetOnViewerServer(target: ActionCardSpecNavigateTarget, cardSourceServerId: String?, viewerServerId: String?): Boolean {
    if (viewerServerId.isNullOrBlank()) return false
    return (target.serverId ?: cardSourceServerId ?: viewerServerId) == viewerServerId
}

/** A Button the frame shows disabled for this viewer: a navigate to another server's entity. */
internal fun ActionCardSpecButton.actionCardSpecLinkNavigable(cardSourceServerId: String?, viewerServerId: String?): Boolean =
    (action as? ActionCardSpecAction.Navigate)?.let { actionCardTargetOnViewerServer(it.target, cardSourceServerId, viewerServerId) } ?: true

/**
 * A `navigate` Button's target, opened natively (web `useActionCardNavigate().toTarget`): a channel
 * by id, an agent's or a person's profile, a message through the in-app permalink route, a task as
 * the task list opens it. Only a target on the viewer's server opens (web
 * `useIsNavigableTarget`); the App has no route into another server's entity.
 */
internal fun openActionCardSpecTarget(
    target: ActionCardSpecNavigateTarget,
    cardSourceServerId: String?,
    viewerServerId: String?,
    serverSlug: String?,
    isDirectMessageChannel: (channelId: String) -> Boolean,
    onOpenUrl: (String) -> Unit,
    onOpenChannel: (String) -> Unit,
    onOpenMember: (MemberDetailIdentity) -> Unit,
    onOpenTask: (String) -> Unit,
) {
    if (!actionCardTargetOnViewerServer(target, cardSourceServerId, viewerServerId)) return
    val server = viewerServerId
    when (target.kind) {
        ActionCardSpecNavigateTarget.Kind.Channel -> onOpenChannel(target.id)
        ActionCardSpecNavigateTarget.Kind.Agent ->
            onOpenMember(MemberDetailIdentity(MemberId(target.id), MemberKind.Agent, server?.let(::ServerId)))
        ActionCardSpecNavigateTarget.Kind.User ->
            onOpenMember(MemberDetailIdentity(MemberId(target.id), MemberKind.Human, server?.let(::ServerId)))
        ActionCardSpecNavigateTarget.Kind.Message -> {
            val channelId = target.channelId ?: return
            val slug = serverSlug?.trim()?.takeIf { it.isNotBlank() } ?: return
            // The production origin is one the in-app permalink route always takes.
            val routeKind = if (isDirectMessageChannel(channelId)) ConversationKind.DirectMessage else ConversationKind.Channel
            buildRaftMessageLink(RaftMessageLink(slug, routeKind, channelId, messageId = target.id), webUrl = null)
                ?.let(onOpenUrl)
        }
        ActionCardSpecNavigateTarget.Kind.Task -> onOpenTask(target.id)
    }
}

// ---------------------------------------------------------------------------
// Store.

/** Why the last action of a card failed, as the frame or a sheet says it. */
internal sealed interface ThreadActionCardSpecError {
    /** The server's words when it gave any, else the generic "Failed to execute action". */
    data class Failed(val serverMessage: String? = null) : ThreadActionCardSpecError

    /** 422 `FORM_REQUIRED`: the card changed under this view; refresh to get its form. */
    data object FormRequired : ThreadActionCardSpecError

    /**
     * 429 `feedback_rate_limited`: a feedback card's form over the per-person feedback limit it
     * shares with the feedback dialog. The card stays prepared; [retryAfterSeconds] is the
     * `Retry-After` wait when the answer named one.
     */
    data class FeedbackRateLimited(val retryAfterSeconds: Long? = null) : ThreadActionCardSpecError
}

internal const val ACTION_CARD_FEEDBACK_RATE_LIMITED_CODE = "feedback_rate_limited"

/** A non-form execute refusal as the frame or sheet says it: the localized words for known codes, else the server's. */
internal fun ScreenError.toThreadActionCardSpecError(): ThreadActionCardSpecError =
    if (serverCode == ACTION_CARD_FEEDBACK_RATE_LIMITED_CODE) {
        ThreadActionCardSpecError.FeedbackRateLimited(retryAfterSeconds)
    } else {
        ThreadActionCardSpecError.Failed(serverMessage?.takeIf { it.isNotBlank() })
    }

/** The generic confirm before an execute (`confirm.v1`), over the card it confirms. */
internal data class ThreadActionCardSpecConfirmState(
    val messageId: String,
    val elementId: String,
    val card: ThreadActionCard,
    val confirm: ActionCardSpecConfirm,
    /** web: red only for a destructive execute. */
    val destructive: Boolean,
    val submitting: Boolean = false,
    val error: ThreadActionCardSpecError? = null,
)

/** The generic form sheet an `open_form` button opened (`form.v1`, A3's [RaftActionCardFormSheet]). */
internal data class ThreadActionCardSpecFormState(
    val messageId: String,
    val elementId: String,
    val card: ThreadActionCard,
    val formId: String,
    val form: ActionCardSpecForm,
    val values: Map<String, ActionCardFormValue>,
    /** The server's 422 `FORM_INVALID` issues of the last submit. */
    val issues: List<ActionCardFormIssue> = emptyList(),
    val submitting: Boolean = false,
    /** Any other refusal, as the sheet's banner. */
    val error: ThreadActionCardSpecError? = null,
)

/**
 * Every spec card of a thread page: which Button runs ([busy], by message), the last failure
 * under each card ([errors]), the open confirm or form, and which Button handed its card to the
 * typed path ([typedPress]) so the frame can show that one busy while it runs.
 */
internal data class ThreadActionCardSpecState(
    val busy: Map<String, String> = emptyMap(),
    val errors: Map<String, ThreadActionCardSpecError> = emptyMap(),
    val confirm: ThreadActionCardSpecConfirmState? = null,
    val form: ThreadActionCardSpecFormState? = null,
    val typedPress: Map<String, String> = emptyMap(),
) {
    /** The Button the frame shows busy: the store's run, else the typed path's while it is pending. */
    fun busyElement(messageId: String, typedPending: Boolean): String? =
        busy[messageId] ?: typedPress[messageId]?.takeIf { typedPending }

    /** What one card's frame reads: a card selects only this, so another card's run does not recompose it. */
    fun slice(messageId: String): ThreadActionCardSpecCardSlice =
        ThreadActionCardSpecCardSlice(busy = busy[messageId], typedPress = typedPress[messageId], error = errors[messageId])
}

/** The sheets [ThreadActionCardSpecState] has open: what the page draws over the thread. */
internal data class ThreadActionCardSpecDialogs(
    val confirm: ThreadActionCardSpecConfirmState? = null,
    val form: ThreadActionCardSpecFormState? = null,
)

/** One card's part of [ThreadActionCardSpecState]. */
internal data class ThreadActionCardSpecCardSlice(
    val busy: String? = null,
    val typedPress: String? = null,
    val error: ThreadActionCardSpecError? = null,
) {
    fun busyElement(typedPending: Boolean): String? = busy ?: typedPress?.takeIf { typedPending }
}

internal sealed interface ThreadActionCardSpecIntent {
    /** A Button [threadActionCardSpecPress] routed to the store, on the card as the thread holds it now. */
    data class Press(val messageId: String, val elementId: String, val card: ThreadActionCard, val spec: ActionCardSpecCard) :
        ThreadActionCardSpecIntent

    /** A Button handed to the typed path: remembered only for the busy spinner. */
    data class TypedPress(val messageId: String, val elementId: String) : ThreadActionCardSpecIntent

    /** The card as the thread now holds it (realtime, an answer): an open confirm or form follows it. */
    data class CardUpdated(val messageId: String, val card: ThreadActionCard) : ThreadActionCardSpecIntent

    data object ConfirmExecute : ThreadActionCardSpecIntent

    data object DismissConfirm : ThreadActionCardSpecIntent

    data class FormValuesChanged(val values: Map<String, ActionCardFormValue>) : ThreadActionCardSpecIntent

    /** The sheet's submit, after its own required / length checks passed. */
    data class SubmitForm(val values: Map<String, ActionCardFormValue>) : ThreadActionCardSpecIntent

    data object DismissForm : ThreadActionCardSpecIntent

    /** "Open in web": [link] (this message on this page) on the Developer environment's web URL, in the browser. */
    data class OpenInWeb(val messageId: String, val link: RaftMessageLink?) : ThreadActionCardSpecIntent
}

/** One-shot results for the page. */
internal sealed interface ThreadActionCardSpecEffect {
    /**
     * The card was handled elsewhere while its sheet was open: a 409 `ALREADY_EXECUTED`, or the
     * card turning done or frozen under an open confirm or form (realtime). The sheet closed.
     */
    data class AlreadyExecuted(val executedByUserName: String?, val frozen: Boolean = false) : ThreadActionCardSpecEffect

    /** "Open in web" had no link to open (no server slug, or an id a link can't carry). */
    data object OpenInWebFailed : ThreadActionCardSpecEffect
}

/**
 * MVI owner of spec card Buttons on a thread page (spec §3.3-§3.4, web `ActionCardSpecCard`):
 * UI → [dispatch] → [execute] (the repository: `SharedThreadDataSource.executeActionCardSpec`).
 *
 * - execute: runs the card; one action per card at a time; the answer's card is applied
 *   ([applyCard]); a failure stays under the card until its next action.
 * - execute + confirm: opens [ThreadActionCardSpecState.confirm]; only its confirm executes; a
 *   failure stays in the sheet for another try.
 * - open_form: opens [ThreadActionCardSpecState.form] from the form's defaults; submit executes
 *   with `{ form: { id, values } }`. 422 `FORM_INVALID` puts the issues under their fields (or
 *   the banner), 422 `FORM_REQUIRED` says to refresh, 409 `ALREADY_EXECUTED` applies the card it
 *   carries and closes the sheet ([ThreadActionCardSpecEffect.AlreadyExecuted]).
 * Links and the typed path never come here ([threadActionCardSpecPress]).
 */
internal class ThreadActionCardSpecStore(
    private val scope: CoroutineScope,
    private val execute: suspend (messageId: String, card: ThreadActionCard, form: ExecuteActionFormBody?) -> DataResult<ActionCardExecuteOutcome>,
    private val applyCard: (messageId: String, card: ThreadActionCard) -> Unit,
    /** A NoPermission the route handles (session expired): true when it did. */
    private val onNoPermission: (DataResult.NoPermission) -> Boolean = { false },
    /** The Developer environment's web URL (AGENTS.md "Environment Web Link URL Rule"); null is production. */
    private val loadWebUrl: suspend () -> String? = { null },
    /** HostLinkCapability.openUrlInBrowser: the browser, never Raft through App Links. */
    private val openInBrowser: (url: String) -> Unit = {},
) {
    private val _state = MutableStateFlow(ThreadActionCardSpecState())
    val state: StateFlow<ThreadActionCardSpecState> = _state.asStateFlow()
    private val effectChannel = Channel<ThreadActionCardSpecEffect>(Channel.BUFFERED)
    val effects: Flow<ThreadActionCardSpecEffect> = effectChannel.receiveAsFlow()
    private var dialogToken = 0L

    fun dispatch(intent: ThreadActionCardSpecIntent) {
        when (intent) {
            is ThreadActionCardSpecIntent.Press -> press(intent)
            is ThreadActionCardSpecIntent.TypedPress ->
                update { it.copy(typedPress = it.typedPress + (intent.messageId to intent.elementId), errors = it.errors - intent.messageId) }
            is ThreadActionCardSpecIntent.CardUpdated -> cardUpdated(intent.messageId, intent.card)
            ThreadActionCardSpecIntent.ConfirmExecute -> confirmExecute()
            ThreadActionCardSpecIntent.DismissConfirm -> update { if (it.confirm?.submitting == true) it else it.copy(confirm = null) }
            is ThreadActionCardSpecIntent.FormValuesChanged ->
                update { current -> current.form?.takeIf { !it.submitting }?.let { current.copy(form = it.copy(values = intent.values)) } ?: current }
            is ThreadActionCardSpecIntent.SubmitForm -> submitForm(intent.values)
            ThreadActionCardSpecIntent.DismissForm -> update { if (it.form?.submitting == true) it else it.copy(form = null) }
            is ThreadActionCardSpecIntent.OpenInWeb -> openInWeb(intent.link)
        }
    }

    private fun openInWeb(link: RaftMessageLink?) {
        if (link == null) {
            effectChannel.trySend(ThreadActionCardSpecEffect.OpenInWebFailed)
            return
        }
        scope.launch {
            val url = buildRaftMessageLink(link, loadWebUrl())
            if (url == null) effectChannel.send(ThreadActionCardSpecEffect.OpenInWebFailed) else openInBrowser(url)
        }
    }

    private inline fun update(transform: (ThreadActionCardSpecState) -> ThreadActionCardSpecState) {
        val next = transform(_state.value)
        if (next != _state.value) _state.value = next
    }

    private fun press(intent: ThreadActionCardSpecIntent.Press) {
        val current = _state.value
        if (threadActionCardSpecPress(intent.card, intent.spec, intent.elementId) != ThreadActionCardSpecPress.Store) return
        if (intent.messageId in current.busy || current.confirm != null || current.form != null) return
        val button = intent.spec.button(intent.elementId) ?: return
        when (val action = button.action) {
            is ActionCardSpecAction.Execute -> {
                val confirmId = action.confirm
                if (confirmId == null) {
                    runExecute(intent.messageId, intent.elementId, intent.card)
                } else {
                    // Strip keeps an execute behind a confirm only with confirm.v1 and a confirm the card has.
                    val confirm = intent.spec.confirms?.get(confirmId) ?: return
                    dialogToken++
                    _state.value =
                        current.copy(
                            errors = current.errors - intent.messageId,
                            confirm =
                                ThreadActionCardSpecConfirmState(
                                    messageId = intent.messageId,
                                    elementId = intent.elementId,
                                    card = intent.card,
                                    confirm = confirm,
                                    destructive = button.emphasis == ActionCardSpecEmphasis.Destructive,
                                ),
                        )
                }
            }
            is ActionCardSpecAction.OpenForm -> {
                val form = intent.spec.forms?.get(action.form) ?: return
                dialogToken++
                _state.value =
                    current.copy(
                        errors = current.errors - intent.messageId,
                        form =
                            ThreadActionCardSpecFormState(
                                messageId = intent.messageId,
                                elementId = intent.elementId,
                                card = intent.card,
                                formId = action.form,
                                form = form,
                                values = initialActionCardFormValues(form),
                            ),
                    )
            }
            else -> Unit
        }
    }

    private fun runExecute(messageId: String, elementId: String, card: ThreadActionCard) {
        update { it.copy(busy = it.busy + (messageId to elementId), errors = it.errors - messageId) }
        scope.launch {
            val result = execute(messageId, card, null)
            update { it.copy(busy = it.busy - messageId) }
            val error: ThreadActionCardSpecError? =
                when (result) {
                    is DataResult.Content ->
                        when (val outcome = result.data) {
                            is ActionCardExecuteOutcome.Executed -> {
                                outcome.card?.let { applyCard(messageId, it) }
                                null
                            }
                            is ActionCardExecuteOutcome.AlreadyExecuted -> {
                                outcome.card?.let { applyCard(messageId, it) }
                                null
                            }
                            is ActionCardExecuteOutcome.FormRequired -> ThreadActionCardSpecError.FormRequired
                            is ActionCardExecuteOutcome.FormInvalid -> ThreadActionCardSpecError.Failed(outcome.serverMessage)
                        }
                    is DataResult.Empty -> ThreadActionCardSpecError.Failed()
                    is DataResult.Error -> result.error.toThreadActionCardSpecError()
                    is DataResult.NoPermission ->
                        if (onNoPermission(result)) null else ThreadActionCardSpecError.Failed(result.blocker.serverMessage?.takeIf { it.isNotBlank() })
                }
            if (error != null) update { it.copy(errors = it.errors + (messageId to error)) }
        }
    }

    private fun confirmExecute() {
        val confirm = _state.value.confirm ?: return
        if (confirm.submitting || confirm.card.isExecuted || confirm.card.isFrozen) return
        val token = ++dialogToken
        update { it.copy(confirm = confirm.copy(submitting = true, error = null)) }
        scope.launch {
            val result = execute(confirm.messageId, confirm.card, null)
            // Closed meanwhile, or another dialog opened: the card still takes the answer.
            val open = _state.value.confirm?.takeIf { token == dialogToken && it.messageId == confirm.messageId }
            when (val settled = settle(confirm.messageId, result)) {
                null -> if (open != null) update { it.copy(confirm = null) }
                else -> if (open != null) update { it.copy(confirm = open.copy(submitting = false, error = settled)) }
            }
        }
    }

    private fun submitForm(values: Map<String, ActionCardFormValue>) {
        val form = _state.value.form ?: return
        if (form.submitting || form.card.isExecuted || form.card.isFrozen) return
        val token = ++dialogToken
        update { it.copy(form = form.copy(values = values, submitting = true, error = null)) }
        scope.launch {
            val result = execute(form.messageId, form.card, ExecuteActionFormBody(id = form.formId, values = values))
            val open = _state.value.form?.takeIf { token == dialogToken && it.messageId == form.messageId }
            if (result is DataResult.Content && result.data is ActionCardExecuteOutcome.FormInvalid) {
                val invalid = result.data as ActionCardExecuteOutcome.FormInvalid
                if (open != null) update { it.copy(form = open.copy(submitting = false, issues = invalid.issues, error = null)) }
                return@launch
            }
            when (val settled = settle(form.messageId, result)) {
                null -> if (open != null) update { it.copy(form = null) }
                else -> if (open != null) update { it.copy(form = open.copy(submitting = false, issues = emptyList(), error = settled)) }
            }
        }
    }

    /** A dialog's answer: null when it closes (done, or the route took the NoPermission), else what it says. */
    private suspend fun settle(messageId: String, result: DataResult<ActionCardExecuteOutcome>): ThreadActionCardSpecError? =
        when (result) {
            is DataResult.Content ->
                when (val outcome = result.data) {
                    is ActionCardExecuteOutcome.Executed -> {
                        outcome.card?.let { applyCard(messageId, it) }
                        null
                    }
                    is ActionCardExecuteOutcome.AlreadyExecuted -> {
                        outcome.card?.let { applyCard(messageId, it) }
                        effectChannel.send(ThreadActionCardSpecEffect.AlreadyExecuted(outcome.card?.executedByUserName?.takeIf { it.isNotBlank() }))
                        null
                    }
                    is ActionCardExecuteOutcome.FormRequired -> ThreadActionCardSpecError.FormRequired
                    is ActionCardExecuteOutcome.FormInvalid -> ThreadActionCardSpecError.Failed(outcome.serverMessage)
                }
            is DataResult.Empty -> ThreadActionCardSpecError.Failed()
            is DataResult.Error -> result.error.toThreadActionCardSpecError()
            is DataResult.NoPermission ->
                if (onNoPermission(result)) null else ThreadActionCardSpecError.Failed(result.blocker.serverMessage?.takeIf { it.isNotBlank() })
        }

    /**
     * The live card under an open confirm or form. Done or frozen elsewhere while nothing is in
     * flight here: the sheet closes and says so, as a 409 would (no Submit that can only fail). While
     * its own submit runs, the answer decides.
     */
    private fun cardUpdated(messageId: String, card: ThreadActionCard) {
        val current = _state.value
        val handledElsewhere = card.isExecuted || card.isFrozen
        val confirmOpen = current.confirm?.takeIf { it.messageId == messageId && it.card != card }
        val formOpen = current.form?.takeIf { it.messageId == messageId && it.card != card }
        var closed = false
        val next =
            current.copy(
                confirm =
                    when {
                        confirmOpen == null -> current.confirm
                        handledElsewhere && !confirmOpen.submitting -> null.also { closed = true }
                        else -> confirmOpen.copy(card = card)
                    },
                form =
                    when {
                        formOpen == null -> current.form
                        handledElsewhere && !formOpen.submitting -> null.also { closed = true }
                        else -> formOpen.copy(card = card)
                    },
            )
        if (next != current) _state.value = next
        if (closed) {
            dialogToken++
            effectChannel.trySend(
                ThreadActionCardSpecEffect.AlreadyExecuted(
                    executedByUserName = card.executedByUserName?.takeIf { it.isNotBlank() && card.isExecuted },
                    frozen = !card.isExecuted,
                ),
            )
        }
    }
}
