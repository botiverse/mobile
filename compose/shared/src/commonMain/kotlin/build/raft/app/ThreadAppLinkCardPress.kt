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

    private class PendingConfirmation(val actionId: String, val handle: String, val prepared: AppLinkPrepared, val requestId: String)

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
                if (counting) cancelUndo() else if (handle != null) press(id, action, handle)
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
    private fun press(id: String, action: RaftLinkCardButtonAction.Execute, handle: String) {
        val host = actions ?: return
        if (running != null || undoing != null) return
        val requestId = appLinkClientRequestId(Uuid.random().toString())
        if (action.confirm) {
            running = id
            scope.launch {
                try {
                    when (val prepared = host.prepare(handle, card.block?.businessRevision.orEmpty())) {
                        is AppLinkActionResult.Ok -> confirming = PendingConfirmation(id, handle, prepared.value, requestId)
                        is AppLinkActionResult.Failed -> failure(host, prepared)
                    }
                } finally {
                    running = null
                }
            }
        } else {
            undoing = id
            undoJob =
                scope.launch {
                    delay(action.undoWindowMs ?: DefaultUndoWindowMs)
                    undoing = null
                    undoJob = null
                    run(host, id, handle, null, requestId)
                }
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

    /** Raft's confirmation of a prepared action: the App's description in Raft's dialog. */
    @Composable
    fun Confirmation() {
        val pending = confirming ?: return
        val description = pending.prepared.description
        val host = actions ?: return
        RaftDialog(
            onDismissRequest = { confirming = null },
            title = description.title,
            description = description.body,
            footer = {
                RaftButton(text = description.cancelLabel, onClick = { confirming = null }, variant = RaftButtonVariant.Ghost)
                RaftButton(
                    text = description.confirmLabel,
                    onClick = {
                        confirming = null
                        scope.launch { run(host, pending.actionId, pending.handle, pending.prepared, pending.requestId) }
                    },
                )
            },
            content = {
                description.rows.forEach { (label, value) ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        RaftText(text = label, modifier = Modifier.weight(1f, fill = false), style = RaftTheme.typography.callout, color = RaftTheme.colors.foregroundMuted)
                        RaftText(text = value, modifier = Modifier.weight(1f, fill = false), style = RaftTheme.typography.callout, color = RaftTheme.colors.foreground)
                    }
                }
                description.finePrint?.let {
                    RaftText(text = it, style = RaftTheme.typography.footnote, color = RaftTheme.colors.foregroundMuted)
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
