package build.raft.app

import build.raft.app.resources.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.data.AppLinkActionResult
import build.raft.app.data.AppLinkExecuted
import build.raft.app.data.AppLinkPrepared
import build.raft.app.data.AppLinkResult
import build.raft.app.data.RaftLinkCardButtonAction
import build.raft.app.data.RaftLinkCardButtonVariant
import build.raft.app.data.RaftLinkCardV2Element
import build.raft.app.data.appLinkClientRequestId
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftDialog
import build.raft.designsystem.components.RaftPreviewCardButton
import build.raft.designsystem.components.RaftPreviewCardButtonKind
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
)

@Composable
internal fun rememberThreadAppLinkCardPress(card: AppLinkResult.Card, actions: ThreadAppLinkCardActionHost?): ThreadAppLinkCardPress {
    val scope = rememberCoroutineScope()
    val failed = stringResource(Res.string.thread_link_preview_action_failed)
    val undo = stringResource(Res.string.common_undo)
    val refreshFailed = stringResource(Res.string.thread_link_preview_refresh_failed)
    val press = remember(card.url, actions) { ThreadAppLinkCardPress(card, actions, scope, failed, undo, refreshFailed) }
    // The card is read again after a press: its handles and revision are the latest one's.
    press.card = card
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
) {
    /** The action whose prepare or execute is on its way. */
    private var running by mutableStateOf<String?>(null)

    /** The action whose undo window is counting down. */
    private var undoing by mutableStateOf<String?>(null)
    private var undoJob: Job? = null
    private var confirming by mutableStateOf<PendingConfirmation?>(null)

    /**
     * Raft's confirmation on screen: [prepared] is the App's description of a `confirm: "prepare"`
     * button; null for an undo-window button of an unreviewed App, asked about by its [label].
     */
    internal class PendingConfirmation(
        val actionId: String,
        val handle: String,
        val prepared: AppLinkPrepared?,
        val requestId: String,
        val label: String,
        val unreviewed: Boolean,
        val undoWindowMs: Long? = null,
    )

    /** The confirmation waiting on the person, or null. */
    internal val confirmation: PendingConfirmation? get() = confirming

    /** Cancel (or dismiss) the confirmation: nothing is sent. */
    internal fun dismissConfirmation() {
        confirming = null
    }

    /**
     * Confirm: a prepared action runs now; an unreviewed App's undo-window button starts its Undo
     * window, as it would have without the dialog.
     */
    internal fun confirm() {
        val pending = confirming ?: return
        val host = actions ?: return
        confirming = null
        if (pending.prepared == null) {
            startUndoWindow(host, pending.actionId, pending.handle, pending.undoWindowMs, pending.requestId)
        } else {
            scope.launch { run(host, pending.actionId, pending.handle, pending.prepared, pending.requestId) }
        }
    }

    private fun startUndoWindow(host: ThreadAppLinkCardActionHost, id: String, handle: String, windowMs: Long?, requestId: String) {
        undoing = id
        undoJob =
            scope.launch {
                delay(windowMs ?: DefaultUndoWindowMs)
                undoing = null
                undoJob = null
                run(host, id, handle, null, requestId)
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
            enabled = counting || (running == null && undoing == null),
            loading = running == id,
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
            enabled = running == null && undoing == null,
            loading = running == ReadBusyKey,
        )
    }

    private fun refresh() {
        val host = actions ?: return
        if (running != null || undoing != null) return
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

    @OptIn(ExperimentalUuidApi::class)
    private fun press(id: String, label: String, action: RaftLinkCardButtonAction.Execute, handle: String) {
        val host = actions ?: return
        if (running != null || undoing != null || confirming != null) return
        val requestId = appLinkClientRequestId(Uuid.random().toString())
        val unreviewed = card.app.unreviewed
        if (action.confirm) {
            running = id
            scope.launch {
                try {
                    when (val prepared = host.prepare(handle, card.block?.businessRevision.orEmpty())) {
                        is AppLinkActionResult.Ok -> confirming = PendingConfirmation(id, handle, prepared.value, requestId, label, unreviewed)
                        is AppLinkActionResult.Failed -> failure(host, prepared)
                    }
                } finally {
                    running = null
                }
            }
        } else if (unreviewed) {
            // RFC 076 section 17: an unreviewed App's undo-window button confirms first, then counts down.
            confirming = PendingConfirmation(id, handle, null, requestId, label, unreviewed = true, undoWindowMs = action.undoWindowMs)
        } else {
            startUndoWindow(host, id, handle, action.undoWindowMs, requestId)
        }
    }

    private suspend fun run(host: ThreadAppLinkCardActionHost, id: String, handle: String, prepared: AppLinkPrepared?, requestId: String) {
        running = id
        val executed =
            try {
                when (val answer = host.execute(handle, requestId, prepared)) {
                    is AppLinkActionResult.Ok -> answer.value
                    is AppLinkActionResult.Failed -> {
                        failure(host, answer)
                        null
                    }
                }
            } finally {
                running = null
            }
        if (executed == null) return
        executed.hint?.let(host.toast)
        host.reread(card.url)
        // The App may not have finished: the card is read again as the operation would be polled.
        if (executed.isOpen) {
            for (wait in RereadWaitsMs) {
                delay(wait)
                host.reread(card.url)
            }
        }
    }

    private suspend fun failure(host: ThreadAppLinkCardActionHost, failed: AppLinkActionResult.Failed) {
        host.toast(failed.message ?: failedText)
        if (failed.outOfDate) host.reread(card.url)
    }

    /**
     * Raft's confirmation: a prepared action shows the App's description in Raft's dialog; an
     * unreviewed App (Kirby's v3 design) adds "Run by {App}" with its icon under the title and, last,
     * that this Server made it and Raft has not reviewed it. Its undo-window button is asked about by
     * its label (Run "label"?), confirmed with the button's own label, or Cancel (as the web).
     */
    @Composable
    fun Confirmation() {
        val pending = confirming ?: return
        val description = pending.prepared?.description
        val app = card.app
        RaftDialog(
            onDismissRequest = ::dismissConfirmation,
            title = description?.title ?: stringResource(Res.string.thread_link_preview_unreviewed_run_title, pending.label),
            // The unreviewed App's line goes right under the title, before the App's body.
            description = description?.body?.takeUnless { pending.unreviewed },
            footer = {
                RaftButton(
                    text = description?.cancelLabel ?: stringResource(Res.string.common_cancel),
                    onClick = ::dismissConfirmation,
                    variant = RaftButtonVariant.Ghost,
                )
                RaftButton(text = description?.confirmLabel ?: pending.label, onClick = ::confirm)
            },
            content = {
                if (pending.unreviewed) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ThreadAppLinkCardHeaderIcon(app.icon)
                        RaftText(
                            text = stringResource(Res.string.thread_link_preview_unreviewed_run_by, app.name),
                            style = RaftTheme.typography.callout,
                            color = RaftTheme.colors.foregroundMuted,
                        )
                    }
                    description?.body?.let {
                        RaftText(text = it, style = RaftTheme.typography.callout, color = RaftTheme.colors.foregroundMuted)
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
                if (pending.unreviewed) {
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
        val RereadWaitsMs = listOf(2000L, 5000L, 15000L)
    }
}
