package build.raft.app

import build.raft.app.data.ActionCardExecuteOutcome
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
import build.raft.app.data.DataResult
import build.raft.app.data.ExecuteActionFormBody
import build.raft.app.data.ThreadActionCard
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
 * button this App cannot run is "Open in web".
 */
internal fun ThreadActionCard.actionCardSpecToRender(specEnabled: Boolean, capabilities: Collection<String>): ActionCardSpecCard? {
    if (!specEnabled) return null
    if (type in ThreadActionCardTypedOnlyTypes) return null
    val read = presentationCard ?: return null
    return stripActionCardSpec(read, capabilities)
}

/** The card type whose existing native path an `open` intent runs; null for one the App has none for. */
internal fun ActionCardSpecIntent.typedActionType(): String? =
    when (this) {
        ActionCardSpecIntent.CreateChannel -> "channel:create"
        ActionCardSpecIntent.AddMembers -> "channel:add_member"
        ActionCardSpecIntent.CreateAgent -> "agent:create"
        ActionCardSpecIntent.FeedbackForm -> "feedback:submit"
        ActionCardSpecIntent.UpgradeDialog -> null
    }

/** A Button that changes the card (as opposed to a link). */
internal val ActionCardSpecAction.changesCard: Boolean
    get() = this is ActionCardSpecAction.Execute || this is ActionCardSpecAction.OpenForm || this is ActionCardSpecAction.Open

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
}

/**
 * Routes a tap on Button [elementId] of the rendered [spec] of [card] (spec §3.3, web
 * `ActionCardSpecButton`). Links always work; a Button that changes the card does nothing on a
 * done or frozen card, and an `open` intent whose native path is for another type (or none) opens
 * in web, as strip would have.
 */
internal fun threadActionCardSpecPress(card: ThreadActionCard, spec: ActionCardSpecCard, elementId: String): ThreadActionCardSpecPress {
    val button = spec.button(elementId) ?: return ThreadActionCardSpecPress.Ignore
    if (button.disabledReason != null) return ThreadActionCardSpecPress.Ignore
    return when (val action = button.action) {
        is ActionCardSpecAction.Navigate -> ThreadActionCardSpecPress.Navigate(action.target)
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
    onOpenUrl: (String) -> Unit,
    onOpenChannel: (String) -> Unit,
    onOpenMember: (MemberDetailIdentity) -> Unit,
    onOpenTask: (String) -> Unit,
) {
    val server = target.serverIdOr(cardSourceServerId)
    if (server != null && viewerServerId != null && server != viewerServerId) return
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
            buildRaftMessageLink(RaftMessageLink(slug, ConversationKind.Channel, channelId, messageId = target.id), webUrl = null)
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
}

/** One-shot results for the page. */
internal sealed interface ThreadActionCardSpecEffect {
    /** 409 `ALREADY_EXECUTED`: someone ran the card first; its sheet closed. */
    data class AlreadyExecuted(val executedByUserName: String?) : ThreadActionCardSpecEffect
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
                    is DataResult.Error -> ThreadActionCardSpecError.Failed(result.error.serverMessage?.takeIf { it.isNotBlank() })
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
            is DataResult.Error -> ThreadActionCardSpecError.Failed(result.error.serverMessage?.takeIf { it.isNotBlank() })
            is DataResult.NoPermission ->
                if (onNoPermission(result)) null else ThreadActionCardSpecError.Failed(result.blocker.serverMessage?.takeIf { it.isNotBlank() })
        }

    private fun cardUpdated(messageId: String, card: ThreadActionCard) {
        update { current ->
            val confirm = current.confirm?.takeIf { it.messageId == messageId && it.card != card }?.copy(card = card)
            val form = current.form?.takeIf { it.messageId == messageId && it.card != card }?.copy(card = card)
            current.copy(confirm = confirm ?: current.confirm, form = form ?: current.form)
        }
    }
}
