package build.raft.app

import build.raft.app.resources.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.StringResource
import build.raft.app.data.APP_LINK_HANDLE_FRESH_MILLIS
import build.raft.app.data.AppLinkActionResult
import build.raft.app.data.AppLinkExecuted
import build.raft.app.data.AppLinkOperationStatus
import build.raft.app.data.RaftAppCardEncryptionKey
import build.raft.app.data.RaftLinkCardOperationHint
import build.raft.app.data.AppLinkPrepared
import build.raft.app.data.AppLinkResult
import build.raft.app.data.RaftLinkCardButtonAction
import build.raft.app.data.RaftLinkCardButtonVariant
import build.raft.app.data.RaftLinkCardSealTo
import build.raft.app.data.RaftLinkCardV2Element
import build.raft.app.data.RaftMessageCardForm
import build.raft.app.data.RaftSealedInputsBinding
import build.raft.app.data.RaftSealedInputsBindingInputs
import build.raft.app.data.appLinkClientRequestId
import build.raft.app.data.sealInputs
import build.raft.app.data.sealedInputsBinding
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerSize
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftDialog
import build.raft.designsystem.components.RaftDialogSize
import build.raft.designsystem.components.RaftPreviewCardButton
import build.raft.designsystem.components.RaftPreviewCardButtonKind
import build.raft.designsystem.components.RaftToastIntent
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * What a block card needs to run its buttons (RFC 076 sections 6.2 and 6.3), made by the message
 * row from the store: prepare (a `confirm: "prepare"` button), execute, reading the card again, and
 * the thread's toast.
 */
internal class ThreadAppLinkCardActionHost(
    val prepare: suspend (handle: String, businessRevision: String) -> AppLinkActionResult<AppLinkPrepared>,
    val execute: suspend (handle: String, clientRequestId: String, prepared: AppLinkPrepared?) -> AppLinkActionResult<AppLinkExecuted>,
    /** False: the read failed or was rate limited, the card stays as it was. */
    val reread: suspend (url: String) -> Boolean,
    val toast: (String) -> Unit,
    /** [toast] with the tone Raft gives an operation's state (web `showToast`); plain where none is wired. */
    val toastWithIntent: (String, RaftToastIntent?) -> Unit = { text, _ -> toast(text) },
    /** Failed action toast carrying this response's `X-Trace-Id` when present; plain where none is wired. */
    val errorToast: (String, String?) -> Unit = { text, _ -> toast(text) },
    /** `POST /link-cards/status` for an operation; null where it is not wired (no following). */
    val status: (suspend (operationId: String) -> AppLinkOperationStatus)? = null,
    /** This server's `link_card.operation_changed` hints (RFC 076 §6.8); null: none followed. */
    val operationHints: Flow<RaftLinkCardOperationHint>? = null,
    /**
     * The card [url] as the store holds it now, read after [reread] for a fresh handle; null where
     * it is not wired (a press then uses the handle it has, as before).
     */
    val held: ((url: String) -> AppLinkResult.Card?)? = null,
    /** The clock a handle's age is measured with ([AppLinkResult.Card.readAtMillis]). */
    val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    /** Where the message's presses are seen from ([ThreadAppLinkCardActivity]); null where nothing follows them. */
    val activity: ThreadAppLinkCardActivity? = null,
    /**
     * Form-aware execute carrying optional `inputs` and `sealed_inputs` (RFC 086 §5.4).
     * Wired by message-card hosts; falls back to [execute] when neither is supplied.
     */
    val executeWithForm: (suspend (
        handle: String,
        clientRequestId: String,
        prepared: AppLinkPrepared?,
        inputs: Map<String, String>?,
        sealedInputs: String?,
    ) -> AppLinkActionResult<AppLinkExecuted>)? = null,
    /** Viewer identity (`<userId>:<serverId>`), required to bind `sub` and `srv` in sealed JWE headers (RFC 086 §5.3). */
    val viewerKey: String? = null,
) {
    suspend fun executeAction(
        handle: String,
        clientRequestId: String,
        prepared: AppLinkPrepared?,
        inputs: Map<String, String>? = null,
        sealedInputs: String? = null,
    ): AppLinkActionResult<AppLinkExecuted> {
        val withForm = executeWithForm
        if (withForm != null) return withForm(handle, clientRequestId, prepared, inputs, sealedInputs)
        check(inputs == null && sealedInputs == null) { "executeWithForm must be wired for form submissions" }
        return execute(handle, clientRequestId, prepared)
    }
}

/**
 * What the cards of one message are doing, for the re-read after a `link_card.changed` nudge (web
 * `linkCardChangedRereads.ts`): it waits while Raft's confirmation is open on one of them
 * ([confirming], web `busy === "confirming"`), and leaves a card whose buttons are at work to that
 * work ([busyUrls], web `linkCardViewBusy`). Snapshot state: read it in a composition or a
 * `snapshotFlow`.
 */
internal class ThreadAppLinkCardActivity {
    private val presses = mutableStateListOf<ThreadAppLinkCardPress>()

    fun add(press: ThreadAppLinkCardPress) {
        if (press !in presses) presses += press
    }

    fun remove(press: ThreadAppLinkCardPress) {
        presses -= press
    }

    val confirming: Boolean get() = presses.any { it.confirmation != null }

    val busyUrls: Set<String> get() = presses.filter { it.busy }.mapTo(LinkedHashSet<String>()) { it.card.url }
}

@Composable
internal fun rememberThreadAppLinkCardPress(
    card: AppLinkResult.Card,
    actions: ThreadAppLinkCardActionHost?,
    /** What the press lives as long as: the link (a link card), or the message (a card message, RFC 086). */
    key: Any? = card.url,
): ThreadAppLinkCardPress {
    val scope = rememberCoroutineScope()
    val failed = stringResource(Res.string.thread_link_preview_action_failed)
    val undo = stringResource(Res.string.common_undo)
    val refreshFailed = stringResource(Res.string.thread_link_preview_refresh_failed)
    val changed = stringResource(Res.string.thread_link_preview_action_changed)
    val sealStale = stringResource(Res.string.thread_link_preview_action_form_seal_stale)
    val appSays = stringResource(Res.string.thread_link_preview_action_app_says)
    val appSaysUnnamed = stringResource(Res.string.thread_link_preview_action_app_says_unnamed)
    val working = stringResource(Res.string.thread_link_preview_action_working)
    val stateSent = stringResource(Res.string.thread_link_preview_action_state_sent)
    val stateAccepted = stringResource(Res.string.thread_link_preview_action_state_accepted)
    val stateDone = stringResource(Res.string.thread_link_preview_action_state_done)
    val stateNotCarriedOut = stringResource(Res.string.thread_link_preview_action_state_not_carried_out)
    val stateUnconfirmed = stringResource(Res.string.thread_link_preview_action_state_unconfirmed)
    val stateResolvedManually = stringResource(Res.string.thread_link_preview_action_state_resolved_manually)
    val press =
        remember(key, actions) {
            ThreadAppLinkCardPress(
                card = card,
                actions = actions,
                scope = scope,
                failedText = failed,
                undoLabel = undo,
                refreshFailedText = refreshFailed,
                changedText = changed,
                workingLabel = working,
                stateHintText = { state ->
                    appLinkStateHintText(
                        state = state,
                        sent = stateSent,
                        accepted = stateAccepted,
                        done = stateDone,
                        notCarriedOut = stateNotCarriedOut,
                        unconfirmed = stateUnconfirmed,
                        resolvedManually = stateResolvedManually,
                    )
                },
                appSaysText = { app, text ->
                    if (app.isBlank()) appSaysUnnamed.replace("%1\$s", text) else appSays.replace("%1\$s", app).replace("%2\$s", text)
                },
                sealStaleText = sealStale,
            )
        }
    // The card is read again after a press: its handles and revision are the latest one's.
    press.card = card
    val activity = actions?.activity
    if (activity != null) {
        DisposableEffect(activity, press) {
            activity.add(press)
            onDispose { activity.remove(press) }
        }
    }
    return press
}

/**
 * The pressing of one block card's `execute` buttons, as the web does it (RFC 076 section 13: they
 * work by touch):
 *  - `confirm: "prepare"`: Raft asks the App to describe the action, shows it in Raft's own
 *    dialog (the App's words in Raft's frame), and runs it on Confirm;
 *  - `undoWindowMs`: the button becomes Undo for the window; if it is not pressed the action runs.
 * An App the read marks `app.reviewed: false` (RFC 076 section 17, `unreviewed_app.v1`: Raft has not
 * reviewed it, it acts on its own Server's confirmation) confirms EVERY execute button first, in
 * Raft's dialog naming it as not reviewed: a `confirm: "prepare"` button keeps the App's prepared
 * words; an undo-window button asks Run "label"? and, once confirmed, starts its Undo window as
 * any App's does (web `pressLinkCardButton`, botiverse/slock #13956).
 * Then the card is read again, again a little later while the App may not have finished. One press
 * at a time per card; a refusal is the server's own words in a toast, and a stale card or
 * confirmation (410) is read again.
 *
 * A `read` button (Refresh) reads the card again (web `pressLinkCardButton`): while it reads it
 * shows its spinner and the card's other buttons wait; a read that fails, or that Raft rate
 * limits, says so in a toast and the card stays as it was.
 */
internal class ThreadAppLinkCardPress(
    var card: AppLinkResult.Card,
    private val actions: ThreadAppLinkCardActionHost?,
    private val scope: CoroutineScope,
    private val failedText: String,
    private val undoLabel: String,
    private val refreshFailedText: String,
    /** The card changed since it was drawn (web `message.appLink.action.changed`): nothing was sent. */
    private val changedText: String = refreshFailedText,
    /**
     * The busy button's spinner name, "Working…" (web `message.appLink.action.working`, the
     * `loadingLabel` of LinkCardActions' and MessageCardActingButtons' buttons): a card's running
     * state never says a task status such as In progress (slock #14958). Null: the theme's Loading.
     */
    private val workingLabel: String? = null,
    /** Localized Raft hint per operation state (web `STATE_HINTS` in `linkCardToasts.ts`); null falls back to server `hint`. */
    private val stateHintText: (state: String) -> String? = { null },
    /** "{App}: {text}" (web `message.appLink.action.appSays`): the App's own words, shown as the App's. */
    private val appSaysText: (app: String, text: String) -> String = { app, text -> if (app.isBlank()) text else "$app: $text" },
    /** Fail-closed sealing refusal text (web `message.appLink.action.form.sealStale`). */
    private val sealStaleText: String = changedText,
    /** Device-side JWE sealer for SecretInput forms (default [sealInputs]; overridable in tests). */
    private val sealer: (RaftAppCardEncryptionKey, RaftSealedInputsBinding, Map<String, String>) -> String = ::sealInputs,
) {
    /** The action whose prepare, execute, or post-execute reread is on its way. */
    private var running by mutableStateOf<String?>(null)

    /** The action whose undo window is counting down. */
    private var undoing by mutableStateOf<String?>(null)
    private var undoJob: Job? = null
    private var confirming by mutableStateOf<PendingConfirmation?>(null)

    /**
     * Raft's confirmation on screen: [prepared] is the App's description of a `confirm: "prepare"`
     * button; null for an undo-window button of an unreviewed App or with a TextInput [form], asked
     * about by its [label].
     */
    internal class PendingConfirmation(
        val actionId: String,
        val handle: String,
        val prepared: AppLinkPrepared?,
        val requestId: String,
        val label: String,
        val unreviewed: Boolean,
        val undoWindowMs: Long? = null,
        val form: RaftMessageCardForm? = null,
        val sealTo: RaftLinkCardSealTo? = null,
        val executing: Boolean = false,
    ) {
        fun withExecuting(value: Boolean): PendingConfirmation =
            PendingConfirmation(
                actionId = actionId,
                handle = handle,
                prepared = prepared,
                requestId = requestId,
                label = label,
                unreviewed = unreviewed,
                undoWindowMs = undoWindowMs,
                form = form,
                sealTo = sealTo,
                executing = value,
            )
    }

    /** The confirmation waiting on the person, or null. */
    internal val confirmation: PendingConfirmation? get() = confirming

    /**
     * The action whose open operation (`sent` / `accepted`) is still being followed until it
     * reaches a terminal receipt and the card is read again (web `state.operation`).
     */
    private var pendingOperationActionId by mutableStateOf<String?>(null)

    private val inFlightActionId: String? get() = running ?: pendingOperationActionId
    private val interactionLocked: Boolean get() = running != null || undoing != null || pendingOperationActionId != null || confirming?.executing == true

    /** A button of this card is at work (sending, counting down its Undo, or following its operation): that work reads the card again. */
    internal val busy: Boolean get() = interactionLocked

    /** Cancel (or dismiss) the confirmation: nothing is sent. Ignored while sealing/executing. */
    internal fun dismissConfirmation() {
        if (confirming?.executing == true) return
        confirming = null
    }

    /**
     * Confirm: a prepared action runs now (sealing SecretInput values after fail-closed re-read when
     * [secrets] is non-null); an undo-window button starts its Undo window with any TextInput [inputs].
     */
    internal fun confirm(
        inputs: Map<String, String>? = null,
        secrets: MutableMap<String, String>? = null,
    ) {
        val pending = confirming ?: return
        if (pending.executing) return
        val host = actions ?: return
        if (pending.prepared == null) {
            if (secrets != null) {
                secrets.clear()
                return
            }
            confirming = null
            val held = host.held
            val readAt = card.readAtMillis
            if (held != null && (readAt == null || host.nowMillis() - readAt > APP_LINK_HANDLE_FRESH_MILLIS)) {
                running = pending.actionId
                scope.launch {
                    val read =
                        try {
                            host.reread(card.url)
                        } finally {
                            running = null
                        }
                    val fresh = if (read) held(card.url) else null
                    val freshHandle = fresh?.handles?.get(pending.actionId)
                    val formChanged = fresh != null && fresh.forms[pending.actionId] != pending.form
                    when {
                        !read || fresh == null -> host.toast(refreshFailedText)
                        freshHandle == null || formChanged -> host.toast(changedText)
                        else -> {
                            card = fresh
                            startUndoWindow(host, pending.actionId, freshHandle, pending.undoWindowMs, pending.requestId, inputs)
                        }
                    }
                }
            } else {
                startUndoWindow(host, pending.actionId, pending.handle, pending.undoWindowMs, pending.requestId, inputs)
            }
            return
        }
        if (secrets == null) {
            confirming = null
            running = pending.actionId
            scope.launch { run(host, pending.actionId, pending.handle, pending.prepared, pending.requestId, inputs, sealedInputs = null) }
            return
        }
        confirming = pending.withExecuting(true)
        scope.launch {
            val sealed = sealLinkCardSecrets(host, pending, secrets)
            if (sealed == null) {
                confirming = null
                return@launch
            }
            confirming = null
            run(host, pending.actionId, pending.handle, pending.prepared, pending.requestId, inputs, sealedInputs = sealed)
        }
    }

    /**
     * Fail-closed re-read + sealing of SecretInput values (`sealLinkCardSecrets` in `linkCardSeal.ts`):
     * refuses if the handle is expiring (< 5s left), re-reads the card, verifies the same App key
     * (`kid`/`x`/`y`), `client_key`, and `card_revision`, seals bound to this prepared execute
     * request, and ALWAYS clears [secrets] in `finally`. Never logs or surfaces secret values.
     */
    private suspend fun sealLinkCardSecrets(
        host: ThreadAppLinkCardActionHost,
        pending: PendingConfirmation,
        secrets: MutableMap<String, String>,
    ): String? {
        val sealTo = pending.sealTo
        val prepared = pending.prepared
        return try {
            if (sealTo == null || prepared == null || sealTo.isExpiring(host.nowMillis())) {
                host.toastWithIntent(sealStaleText, RaftToastIntent.Warning)
                return null
            }
            val read = host.reread(card.url)
            if (!read) {
                host.toastWithIntent(refreshFailedText, RaftToastIntent.Warning)
                return null
            }
            val freshSealTo = host.held?.invoke(card.url)?.sealsTo?.get(pending.actionId)
            if (freshSealTo == null || !freshSealTo.sameTarget(sealTo) || sealTo.isExpiring(host.nowMillis())) {
                host.toastWithIntent(sealStaleText, RaftToastIntent.Warning)
                return null
            }
            val viewerKey = host.viewerKey
            val colon = viewerKey?.indexOf(':') ?: -1
            if (viewerKey == null || colon <= 0 || colon >= viewerKey.length - 1) {
                host.toastWithIntent(failedText, RaftToastIntent.Warning)
                return null
            }
            val binding =
                sealedInputsBinding(
                    RaftSealedInputsBindingInputs(
                        handle = pending.handle,
                        serverId = viewerKey.substring(colon + 1),
                        userId = viewerKey.substring(0, colon),
                        appClientKey = sealTo.clientKey,
                        actionId = pending.actionId,
                        preparedId = prepared.preparedActionId,
                        preparedDigest = prepared.preparedDigest,
                        clientRequestId = pending.requestId,
                        handleExp = sealTo.handleExp,
                    ),
                )
            sealer(sealTo.key, binding, LinkedHashMap(secrets))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            // Do not retain or log error details: they may reflect form inputs.
            host.toastWithIntent(failedText, RaftToastIntent.Warning)
            null
        } finally {
            secrets.clear()
        }
    }

    private fun startUndoWindow(
        host: ThreadAppLinkCardActionHost,
        id: String,
        handle: String,
        windowMs: Long?,
        requestId: String,
        inputs: Map<String, String>? = null,
    ) {
        undoing = id
        undoJob =
            scope.launch {
                delay(windowMs ?: DefaultUndoWindowMs)
                undoing = null
                undoJob = null
                run(host, id, handle, null, requestId, inputs, sealedInputs = null)
            }
    }

    /** The button for [button], or null where it cannot be pressed (no handle, none of Raft's sending: not drawn). */
    fun button(button: RaftLinkCardV2Element.Button, action: RaftLinkCardButtonAction.Execute): RaftPreviewCardButton? {
        if (actions == null) return null
        val handle = card.handles[action.actionId]
        if (handle == null && button.disabledReason == null) return null
        val id = action.actionId
        val counting = undoing == id
        return RaftPreviewCardButton(
            label = if (counting) undoLabel else button.label,
            kind = RaftPreviewCardButtonKind.Execute,
            onClick = {
                if (counting) cancelUndo() else if (handle != null) press(id, button.label, action, handle)
            },
            // Undo is a plain button.
            frameless = counting || button.variant == RaftLinkCardButtonVariant.Ghost,
            disabledReason = button.disabledReason,
            enabled = counting || !interactionLocked,
            loading = inFlightActionId == id,
            loadingLabel = workingLabel,
        )
    }

    /** The `read` button [button] (Refresh), or null where the card can't be read again (not drawn). */
    fun readButton(button: RaftLinkCardV2Element.Button): RaftPreviewCardButton? {
        if (actions == null) return null
        return RaftPreviewCardButton(
            label = button.label,
            kind = RaftPreviewCardButtonKind.Read,
            onClick = { refresh() },
            frameless = button.variant == RaftLinkCardButtonVariant.Ghost,
            disabledReason = button.disabledReason,
            enabled = !interactionLocked,
            loading = running == ReadBusyKey,
            loadingLabel = workingLabel,
        )
    }

    private fun refresh() {
        val host = actions ?: return
        if (interactionLocked || confirming != null) return
        running = ReadBusyKey
        scope.launch {
            val read =
                try {
                    host.reread(card.url)
                } finally {
                    running = null
                }
            if (!read) host.toast(refreshFailedText)
        }
    }

    private fun cancelUndo() {
        undoJob?.cancel()
        undoJob = null
        undoing = null
    }

    private fun press(id: String, label: String, action: RaftLinkCardButtonAction.Execute, handle: String) {
        val host = actions ?: return
        if (interactionLocked || confirming != null) return
        val held = host.held
        val readAt = card.readAtMillis
        if (held == null || (readAt != null && host.nowMillis() - readAt <= APP_LINK_HANDLE_FRESH_MILLIS)) {
            pressWith(host, id, label, action, handle)
            return
        }
        // Web `pressExecute` (addendum 5 §3): a handle signed more than 9 minutes ago (it expires
        // at 10; the app may have been in the background) is read again first, then used. Never a
        // failed press and a toast for an expired handle the person couldn't know about.
        running = id
        scope.launch {
            val read =
                try {
                    host.reread(card.url)
                } finally {
                    running = null
                }
            val fresh = if (read) held(card.url) else null
            val freshHandle = fresh?.handles?.get(id)
            val freshFormChanged = fresh != null && fresh.forms[id] != action.form
            when {
                !read || fresh == null -> host.toast(refreshFailedText)
                freshHandle == null || freshFormChanged -> host.toast(changedText)
                else -> {
                    card = fresh
                    pressWith(host, id, label, action, freshHandle)
                }
            }
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun pressWith(host: ThreadAppLinkCardActionHost, id: String, label: String, action: RaftLinkCardButtonAction.Execute, handle: String) {
        val requestId = appLinkClientRequestId(Uuid.random().toString())
        val unreviewed = card.app.unreviewed
        val form = action.form
        if (action.confirm) {
            running = id
            scope.launch {
                try {
                    when (val prepared = host.prepare(handle, card.claimedRevision)) {
                        is AppLinkActionResult.Ok ->
                            confirming =
                                PendingConfirmation(
                                    actionId = id,
                                    handle = handle,
                                    prepared = prepared.value,
                                    requestId = requestId,
                                    label = label,
                                    unreviewed = unreviewed,
                                    form = form,
                                    sealTo = card.sealsTo[id],
                                )
                        is AppLinkActionResult.Failed -> failure(host, prepared)
                    }
                } finally {
                    running = null
                }
            }
        } else if (unreviewed || form != null) {
            // SecretInput is strictly forbidden on undo-window buttons (RFC 086 §5.1).
            if (form?.hasSecretInput == true) return
            // RFC 076 §17 + RFC 086 §5.1: an unreviewed App or a form button confirms in Raft's dialog first, then counts down.
            confirming =
                PendingConfirmation(
                    actionId = id,
                    handle = handle,
                    prepared = null,
                    requestId = requestId,
                    label = label,
                    unreviewed = unreviewed,
                    undoWindowMs = action.undoWindowMs,
                    form = form,
                )
        } else {
            startUndoWindow(host, id, handle, action.undoWindowMs, requestId)
        }
    }

    private suspend fun run(
        host: ThreadAppLinkCardActionHost,
        id: String,
        handle: String,
        prepared: AppLinkPrepared?,
        requestId: String,
        inputs: Map<String, String>? = null,
        sealedInputs: String? = null,
    ) {
        running = id
        // Subscribe to operation hints BEFORE execute() goes out so a fast `link_card.operation_changed`
        // push arriving during execute() or the initial reread is buffered rather than dropped.
        val hints = if (host.status != null) host.operationHints else null
        val hintChannel = hints?.let { Channel<RaftLinkCardOperationHint>(capacity = Channel.UNLIMITED) }
        val hintCollector =
            if (hints != null && hintChannel != null) {
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    hints.collect { hintChannel.trySend(it) }
                }
            } else {
                null
            }
        try {
            val executed =
                when (val answer = host.executeAction(handle, requestId, prepared, inputs, sealedInputs)) {
                    is AppLinkActionResult.Ok -> answer.value
                    is AppLinkActionResult.Failed -> {
                        failure(host, answer)
                        null
                    }
                }
            if (executed == null) return
            // Finished at once: the App's own words when it sent some (`execute.toast.v1`), else Raft's
            // localized hint for the state; either in the tone Raft gives the state (web `showAppToast`).
            val appToast = executed.appToast?.takeIf { !executed.isOpen }
            val text = appToast?.let { appSaysText(card.app.name.trim(), it) } ?: stateHintText(executed.state) ?: executed.hint
            text?.let { host.toastWithIntent(it, appLinkToastIntent(executed.tone ?: appLinkStateToastTone(executed.state))) }
            if (!executed.isOpen) {
                // Keep `running = id` through the post-execute reread so the pressed button never
                // flashes back to an idle stale state before the refreshed card renders.
                host.reread(card.url)
                return
            }
            // Open operation (`sent` / `accepted`): hand off to `pendingOperationActionId` before
            // clearing `running` so the pressed button stays loading and sibling execute/read
            // buttons remain disabled without any gap until completion + reread.
            pendingOperationActionId = id
            running = null
            try {
                host.reread(card.url)
                followOpenOperation(host, executed, hintChannel)
            } finally {
                pendingOperationActionId = null
            }
        } finally {
            hintCollector?.cancel()
            hintChannel?.close()
            if (running == id) running = null
        }
    }

    /**
     * Follows an open operation until it reaches a terminal receipt or closes (web `linkCardExecute.tsx`
     * `follow` + `onOperationChanged` + `readStatus`): combines live `link_card.operation_changed`
     * socket hints with bounded `POST /link-cards/status` polling so completion finishes immediately
     * when pushed and still settles promptly if a hint is missed. As soon as a terminal receipt or
     * `Closed` arrives, rereads the card and ends the pending state immediately instead of waiting
     * out the remaining schedule.
     */
    private suspend fun followOpenOperation(
        host: ThreadAppLinkCardActionHost,
        executed: AppLinkExecuted,
        hintChannel: Channel<RaftLinkCardOperationHint>?,
    ) {
        val status = host.status
        if (status == null) {
            for (wait in RereadWaitsMs) {
                delay(wait)
                host.reread(card.url)
            }
            return
        }
        val operationId = executed.operationId
        var shownRevision = 0L
        var shownState = executed.state

        fun drainNewerHint(): Boolean {
            hintChannel ?: return false
            var matched = false
            while (true) {
                val hint = hintChannel.tryReceive().getOrNull() ?: break
                if (hint.operationId == operationId && hint.receiptRevision > shownRevision) matched = true
            }
            return matched
        }

        suspend fun checkStatus(): Boolean =
            when (val answer = status(operationId)) {
                is AppLinkOperationStatus.Receipt -> {
                    if (answer.receiptRevision > shownRevision) {
                        val appToast = answer.appToast?.takeIf { answer.isTerminal }
                        val text =
                            appToast?.let { appSaysText(card.app.name.trim(), it) }
                                ?: if (answer.state != shownState) (stateHintText(answer.state) ?: answer.hint) else null
                        text?.let { host.toastWithIntent(it, appLinkToastIntent(answer.tone ?: appLinkStateToastTone(answer.state))) }
                        shownRevision = answer.receiptRevision
                        shownState = answer.state
                    }
                    if (answer.isTerminal) {
                        host.reread(card.url)
                        true
                    } else {
                        false
                    }
                }
                is AppLinkOperationStatus.Closed -> {
                    if (answer.reread) host.reread(card.url)
                    true
                }
                AppLinkOperationStatus.Open -> false
            }

        // Check right away if a hint already arrived while execute() or the initial reread was running.
        if (drainNewerHint() && checkStatus()) return

        for (waitMs in StatusPollStepsMs) {
            if (hintChannel != null) {
                withTimeoutOrNull(waitMs) {
                    while (true) {
                        val hint = hintChannel.receive()
                        if (hint.operationId == operationId && hint.receiptRevision > shownRevision) break
                    }
                }
                drainNewerHint()
            } else {
                delay(waitMs)
            }
            if (checkStatus()) return
        }
        // Bounded follow window ended without a terminal receipt: read the card once more before unlocking.
        host.reread(card.url)
    }

    private suspend fun failure(host: ThreadAppLinkCardActionHost, failed: AppLinkActionResult.Failed) {
        host.errorToast(failed.message ?: failedText, failed.traceId)
        if (failed.outOfDate) host.reread(card.url)
    }

    /**
     * Raft's confirmation: a prepared action shows the App's icon and "From {App}" before the
     * App's own description in Raft's dialog (slock #14724); an unreviewed App (Kirby's v3 design)
     * shows "Run by {App}" with its icon under the title and, last, that this Server made it and
     * Raft has not reviewed it. Its undo-window button is asked about by its label (Run "label"?),
     * confirmed with the button's own label, or Cancel (as the web).
     *
     * A confirmation, so the small dialog (web `LinkCardConfirmDialog`, raft-ui Dialog
     * `size="sm"`, DS PreviewCard › Item card; slock #14958): × in the head, Cancel first and the
     * confirm last, `primary`, as the protocol says only that the action does something.
     */
    @Composable
    fun Confirmation() {
        val pending = confirming ?: return
        val description = pending.prepared?.description
        val app = card.app
        val form = pending.form
        val formState = form?.let { rememberThreadAppLinkCardFormState(pending.requestId, it) }
        val attribution = appLinkConfirmAttribution(unreviewed = pending.unreviewed, hasDescription = description != null)
        val title =
            description?.title
                ?: if (form != null) pending.label else stringResource(Res.string.thread_link_preview_unreviewed_run_title, pending.label)
        val onDismiss: () -> Unit = {
            if (!pending.executing) {
                formState?.clearSecrets()
                dismissConfirmation()
            }
        }
        val onConfirm: () -> Unit = {
            if (!pending.executing) {
                if (formState == null) {
                    confirm()
                } else {
                    val submission = formState.validateAndConsume()
                    if (submission != null) {
                        val (inputs, secrets) = submission
                        if (secrets != null && pending.prepared == null) {
                            secrets.clear()
                        } else {
                            confirm(inputs = inputs, secrets = secrets)
                        }
                    }
                }
            }
        }
        RaftDialog(
            onDismissRequest = onDismiss,
            title = title,
            // Web `data-link-card-confirm`.
            modifier = Modifier.testTag(AppLinkConfirmDialogTestTag),
            size = if (form != null) RaftDialogSize.Md else RaftDialogSize.Sm,
            // Who is speaking goes right under the title, before the App's own body (slock #14724).
            description = description?.body?.takeUnless { attribution != null },
            dismissOnClickOutside = form == null && !pending.executing,
            showClose = !pending.executing,
            footer = {
                RaftButton(
                    text = description?.cancelLabel ?: stringResource(Res.string.common_cancel),
                    onClick = onDismiss,
                    variant = RaftButtonVariant.Ghost,
                    enabled = !pending.executing,
                )
                RaftButton(
                    text = description?.confirmLabel ?: pending.label,
                    onClick = onConfirm,
                    loading = pending.executing,
                    loadingLabel = workingLabel ?: RaftTheme.strings.loading,
                )
            },
            content = {
                if (attribution != null) {
                    Row(
                        modifier = Modifier.testTag(attribution.testTag),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ThreadAppLinkCardHeaderIcon(app.icon)
                        RaftText(
                            text = stringResource(attribution.labelRes, app.name),
                            modifier = Modifier.weight(1f, fill = false),
                            style = RaftTheme.typography.callout.copy(fontWeight = FontWeight.SemiBold),
                            color = RaftTheme.colors.foreground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    description?.body?.let {
                        RaftText(text = it, style = RaftTheme.typography.callout, color = RaftTheme.colors.foreground)
                    }
                }
                description?.rows?.forEach { (label, value) ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        RaftText(text = label, modifier = Modifier.weight(1f, fill = false), style = RaftTheme.typography.callout, color = RaftTheme.colors.foregroundMuted)
                        RaftText(text = value, modifier = Modifier.weight(1f, fill = false), style = RaftTheme.typography.callout, color = RaftTheme.colors.foreground)
                    }
                }
                description?.finePrint?.let {
                    RaftText(text = it, style = RaftTheme.typography.footnote, color = RaftTheme.colors.foregroundMuted)
                }
                if (form != null && pending.unreviewed) {
                    RaftBanner(
                        description = stringResource(Res.string.thread_link_preview_unreviewed_note, app.name),
                        status = RaftBannerStatus.Warning,
                        modifier = Modifier.testTag(AppLinkConfirmUnreviewedBannerTestTag),
                        size = RaftBannerSize.Sm,
                    )
                }
                if (formState != null) {
                    ThreadAppLinkCardFormFields(
                        state = formState,
                        appName = app.name,
                        disabled = pending.executing,
                        onSubmit = onConfirm,
                    )
                }
                if (pending.unreviewed && form == null) {
                    RaftText(
                        text = stringResource(Res.string.thread_link_preview_unreviewed_note, app.name),
                        style = RaftTheme.typography.footnote,
                        color = RaftTheme.colors.foregroundMuted,
                    )
                }
            },
        )
    }

    private companion object {
        /** `undoWindowMs` is always present on an undo button; this only guards a card that lacks it. */
        const val DefaultUndoWindowMs = 5000L

        /** What [running] holds while a `read` button reads: no action_id has a ":" (web `READ_BUSY_KEY`). */
        const val ReadBusyKey = ":read"
        val StatusPollStepsMs = listOf(1000L, 2000L, 5000L, 15_000L, 30_000L)
        val RereadWaitsMs = listOf(2000L, 5000L, 15_000L)
    }
}

/** Localized Raft hint for an operation [state] (web `STATE_HINTS` in `linkCardToasts.ts`). */
internal fun appLinkStateHintText(
    state: String,
    sent: String,
    accepted: String,
    done: String,
    notCarriedOut: String,
    unconfirmed: String,
    resolvedManually: String,
): String? =
    when (state) {
        "sent" -> sent
        "accepted" -> accepted
        "done" -> done
        "refused", "failed_not_sent" -> notCarriedOut
        "unknown", "needs_reconciliation" -> unconfirmed
        "resolved_manually" -> resolvedManually
        else -> null
    }

/** Default toast tone Raft assigns to each operation [state] (web `STATE_HINTS`). */
internal fun appLinkStateToastTone(state: String): String? =
    when (state) {
        "sent", "accepted", "resolved_manually" -> "info"
        "done" -> "success"
        "refused", "failed_not_sent", "unknown", "needs_reconciliation" -> "warning"
        else -> null
    }

/** [ThreadAppLinkCardPress.Confirmation]'s dialog (web `data-link-card-confirm`). */
internal const val AppLinkConfirmDialogTestTag = "link-card-confirm"

/** Warning banner shown above form fields for an unreviewed App in [ThreadAppLinkCardPress.Confirmation]. */
internal const val AppLinkConfirmUnreviewedBannerTestTag = "link-card-confirm-unreviewed-banner"

/**
 * The App attribution row shown before an App's own prepared-action copy in [ThreadAppLinkCardPress.Confirmation]
 * (web `LinkCardActions.tsx`, slock #14724 / W3 of #13978):
 * - unreviewed App -> "Run by {app}" (`data-link-card-confirm-run-by`)
 * - reviewed App with prepared description -> "From {app}" (`data-link-card-confirm-from`)
 */
internal enum class AppLinkConfirmAttribution(
    val labelRes: StringResource,
    val testTag: String,
) {
    RunBy(Res.string.thread_link_preview_unreviewed_run_by, "link-card-confirm-run-by"),
    From(Res.string.thread_link_preview_action_confirm_from, "link-card-confirm-from"),
}

internal fun appLinkConfirmAttribution(
    unreviewed: Boolean,
    hasDescription: Boolean,
): AppLinkConfirmAttribution? =
    when {
        unreviewed -> AppLinkConfirmAttribution.RunBy
        hasDescription -> AppLinkConfirmAttribution.From
        else -> null
    }

/** The tone of Raft's hint for an operation state (`info`, `success`, `warning`) as the toast's intent. */
internal fun appLinkToastIntent(tone: String?): RaftToastIntent? =
    when (tone) {
        "info" -> RaftToastIntent.Info
        "success" -> RaftToastIntent.Success
        "warning" -> RaftToastIntent.Warning
        else -> null
    }
