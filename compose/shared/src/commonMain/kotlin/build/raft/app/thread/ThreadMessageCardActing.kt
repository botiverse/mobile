package build.raft.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import build.raft.app.data.RaftLinkCardOperationHints
import build.raft.app.data.SharedAppLinkPreviewStore
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftSheet
import build.raft.designsystem.components.RaftSheetSurface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AppLinkApp
import build.raft.app.data.AppLinkBlockCard
import build.raft.app.data.AppLinkCardView
import build.raft.app.data.AppLinkConsentAnswer
import build.raft.app.data.AppLinkConsentStatus
import build.raft.app.data.AppLinkResult
import build.raft.app.data.RaftLinkCardButtonAction
import build.raft.app.data.RaftLinkCardButtonVariant
import build.raft.app.data.RaftLinkCardV2Element
import build.raft.app.data.RaftMessageCard
import build.raft.app.data.RaftMessageCardApp
import build.raft.app.data.RaftMessageCardConsent
import build.raft.app.data.RaftMessageCardHandle
import build.raft.app.data.RaftMessageCardLive
import build.raft.app.data.appLinkAppKey
import build.raft.app.data.appLinkCardReadLocale
import build.raft.app.data.appLinkMessageCardKey
import build.raft.app.data.runtimeFormRequestLanguageTag
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.components.RaftToastIntent
import kotlinx.coroutines.launch
import kotlin.time.TimeSource

/*
 * App-sent cards, step 1 (RFC 086 §4.2-§4.4): a card message's acting buttons as this viewer may
 * press them. The stored card draws each acting button as a disabled navigate Button with the
 * reason Raft writes for older clients ("Update Raft to use this button"); this client never shows
 * that reason. It reads the viewer's handles with the batched read (declaring `card.execute.v1`,
 * [build.raft.app.data.SharedAppLinkPreviewStore.readMessageCard]) and draws each button from it:
 *  - a handle: pressable, exactly as a link card's (RFC 076: prepare, Raft's confirmation, execute,
 *    the Undo window, the toast, reading again; [ThreadAppLinkCardPress]);
 *  - `waiting_for` (the card is for someone else): disabled, "Waiting for {name}" under them;
 *  - `consent: "needed"`: enabled; a press asks "Allow {App}?" ([ThreadMessageCardConsentFlow]: Allow,
 *    then the card is read again and the press goes on ([messageCardPressResumes]), or Not now, which
 *    sends nothing and asks again next press);
 *  - `consent: "declined"`: disabled, "You turned off {App}." (web copy, Kirby),
 *    with Allow {App} (allowing lifts the decline);
 *  - otherwise (not read yet, a failed read, the App can't act now): disabled, no reason.
 */

/** What one acting button draws for this viewer. */
internal sealed interface RaftMessageCardActingState {
    /** The viewer may press it now. */
    data class Pressable(val handle: RaftMessageCardHandle) : RaftMessageCardActingState

    /** The viewer hasn't agreed to the App yet: enabled; the press asks first. */
    data class AskConsent(val prompt: AppLinkResult.Consent) : RaftMessageCardActingState

    /** Not for this viewer now (someone else's card, a declined App, no handle, or not read yet). */
    data object Disabled : RaftMessageCardActingState
}

/** The line under a card's buttons that says why its acting buttons are disabled. */
internal sealed interface RaftMessageCardActingNote {
    /** `waiting_for`: the card is for [name] (blank when Raft couldn't name them). */
    data class WaitingFor(val name: String) : RaftMessageCardActingNote

    /** `consent: "declined"`: the viewer turned [app] off. */
    data class Declined(val app: RaftMessageCardApp) : RaftMessageCardActingNote
}

/** [live] when it was read for this revision of [card]; an answer for an older revision is not this card's. */
internal fun RaftMessageCardLive?.forCard(card: RaftMessageCard): RaftMessageCardLive? =
    this?.takeIf { it.cardRevision == card.revision.toString() }

/** The state of the acting button [elementId] (its fallback page [fallbackUrl]) for this viewer's [live] read. */
internal fun messageCardActingState(live: RaftMessageCardLive?, elementId: String, fallbackUrl: String?): RaftMessageCardActingState {
    if (live == null || live.waitingFor != null) return RaftMessageCardActingState.Disabled
    live.handleFor(elementId)?.let { return RaftMessageCardActingState.Pressable(it) }
    if (live.consent == RaftMessageCardConsent.Needed) {
        val prompt = live.prompt ?: live.app?.let { app -> fallbackUrl?.let { AppLinkResult.Consent(it, app.toPromptApp()) } }
        if (prompt != null) return RaftMessageCardActingState.AskConsent(prompt)
    }
    return RaftMessageCardActingState.Disabled
}

/** Why the acting buttons are disabled, when the read says so; null otherwise. */
internal fun messageCardActingNote(live: RaftMessageCardLive?): RaftMessageCardActingNote? {
    live ?: return null
    live.waitingFor?.let { return RaftMessageCardActingNote.WaitingFor(it.name) }
    val app = live.app
    if (live.handles.isEmpty() && live.consent == RaftMessageCardConsent.Declined && app != null) return RaftMessageCardActingNote.Declined(app)
    return null
}

/** How long after Allow the press that asked still goes on by itself (web `MESSAGE_CARD_RESUME_PRESS_MS`). */
internal const val MessageCardResumePressMs = 15_000L

/**
 * Whether the press that asked "Allow {App}?" goes on by itself once the card is read again and the
 * button has a handle (web `messageCardPressResumes`, AppFather): only where Raft's dialog still
 * stands before anything runs (a `confirm: "prepare"` button, or any button of an unreviewed App),
 * for the revision it was pressed on and within [MessageCardResumePressMs]. A reviewed App's
 * undo-window button is not continued (Allow would be the last tap before it runs): it asks to press
 * it again.
 */
internal fun messageCardPressResumes(
    action: RaftLinkCardButtonAction.Execute,
    unreviewed: Boolean,
    allowedRevision: Int,
    revision: Int,
    elapsedMs: Long,
): Boolean {
    if (allowedRevision != revision || elapsedMs > MessageCardResumePressMs) return false
    return action.confirm || unreviewed
}

/**
 * The press of a card message's acting buttons, as a link card's ([ThreadAppLinkCardPress]): the
 * handles by action id, the App (`reviewed: false` confirms every button first), and the card's
 * revision as the prepare's `business_revision` (RFC 086 §4.2).
 */
internal fun messageCardPressCard(card: RaftMessageCard, live: RaftMessageCardLive?): AppLinkResult.Card =
    AppLinkResult.Card(
        url = live?.handles?.firstOrNull()?.url.orEmpty(),
        app = live?.app?.toAppLinkApp() ?: AppLinkApp(name = "", icon = null),
        title = "",
        handles = live?.handles?.associate { it.actionId to it.handle }.orEmpty(),
        block = AppLinkBlockCard(title = "", card = card.card, businessRevision = live?.cardRevision ?: card.revision.toString()),
    )

/** What the card view needs from the conversation to run its acting buttons; null where it can't (fixtures, a share image). */
internal class ThreadMessageCardActingHost(
    val preview: ThreadAppLinkPreviewHost,
    val live: RaftMessageCardLive?,
    val actions: ThreadAppLinkCardActionHost,
    val status: AppLinkConsentStatus?,
)

/**
 * Reads [card]'s acting buttons for this viewer while the row is shown: once per revision of the
 * card, again after the viewer allows or declines its App (the App's epoch or status moves), and
 * after every press ([ThreadAppLinkCardActionHost.reread]). One message view per revision (its
 * `view_instance_id` and tickets).
 */
@Composable
internal fun rememberThreadMessageCardActingHost(messageId: String?, card: RaftMessageCard): ThreadMessageCardActingHost? {
    val host = LocalThreadAppLinkPreviewHost.current
    val capturing = LocalThreadShareCaptureActive.current
    if (host == null || capturing || card.acting == null || messageId == null || !messageId.isAppLinkMessageId()) return null
    val view = remember(messageId, card.revision) { AppLinkCardView.create() }
    val locale = remember { appLinkCardReadLocale(runtimeFormRequestLanguageTag()) }
    val state by host.store.state.collectAsState()
    val live = state.messageCards[appLinkMessageCardKey(host.viewerKey, messageId)]
    val appKey = live?.app?.id?.let { appLinkAppKey(host.viewerKey, it) }
    val epoch = appKey?.let { state.epoch[it] } ?: 0
    val status = appKey?.let { state.status[it] }
    val declined = status == AppLinkConsentStatus.Declined
    LaunchedEffect(host.store, host.viewerKey, host.serverId, messageId, view, card.acting, epoch, declined) {
        host.store.readMessageCard(host.viewerKey, host.serverId, messageId, view, locale, cardRevision = card.revision.toString())
    }
    val actions =
        remember(host, messageId, view, locale) {
            ThreadAppLinkCardActionHost(
                prepare = { handle, revision -> host.store.prepareAction(host.serverId, handle, revision) },
                execute = { handle, requestId, prepared -> host.store.executeAction(host.serverId, handle, requestId, prepared) },
                reread = { _ -> host.store.readMessageCard(host.viewerKey, host.serverId, messageId, view, locale, force = true) },
                toast = host.toast,
                toastWithIntent = host.toastWithIntent,
                status = { operationId -> host.store.operationStatus(host.serverId, operationId) },
                operationHints = RaftLinkCardOperationHints.forServer(host.serverId),
            )
        }
    return ThreadMessageCardActingHost(host, live, actions, status)
}

/** One acting button: pressable through [press], asking first ([onAsk]), or disabled; never the stored reason. */
@Composable
internal fun RaftMessageCardActingButton(
    elementId: String,
    button: RaftLinkCardV2Element.Button,
    action: RaftLinkCardButtonAction.Execute,
    state: RaftMessageCardActingState,
    press: ThreadAppLinkCardPress,
    onAsk: (AppLinkResult.Consent) -> Unit,
) {
    val variant = if (button.variant == RaftLinkCardButtonVariant.Ghost) RaftButtonVariant.Ghost else RaftButtonVariant.Default
    val modifier = Modifier.testTag("$RaftMessageCardActingButtonTestTagPrefix$elementId")
    when (state) {
        is RaftMessageCardActingState.Pressable -> {
            val pressable = press.button(button.copy(disabledReason = null), action)
            RaftButton(
                text = pressable?.label ?: button.label,
                onClick = pressable?.onClick ?: {},
                modifier = modifier,
                variant = if (pressable?.frameless == true) RaftButtonVariant.Ghost else variant,
                // No way to press it here (no conversation): drawn disabled, never a button that does nothing.
                enabled = pressable?.enabled ?: false,
                loading = pressable?.loading ?: false,
            )
        }
        is RaftMessageCardActingState.AskConsent ->
            RaftButton(text = button.label, onClick = { onAsk(state.prompt) }, modifier = modifier, variant = variant)
        RaftMessageCardActingState.Disabled ->
            RaftButton(text = button.label, onClick = {}, modifier = modifier, variant = variant, enabled = false)
    }
}

/** "Waiting for {name}", or that the viewer turned the App off and how to change it, with Allow {App}. */
@Composable
internal fun RaftMessageCardActingNoteView(note: RaftMessageCardActingNote, allowing: Boolean, onAllow: ((RaftMessageCardApp) -> Unit)?) {
    val style = RaftTheme.typography.footnote
    val color = RaftTheme.colors.foregroundMuted
    when (note) {
        is RaftMessageCardActingNote.WaitingFor ->
            RaftText(
                text =
                    if (note.name.isBlank()) {
                        stringResource(Res.string.message_card_waiting_for_someone)
                    } else {
                        stringResource(Res.string.message_card_waiting_for, note.name)
                    },
                modifier = Modifier.testTag(RaftMessageCardActingNoteTestTag),
                style = style,
                color = color,
            )
        is RaftMessageCardActingNote.Declined ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RaftText(
                    text = stringResource(Res.string.message_card_declined, note.app.name),
                    modifier = Modifier.testTag(RaftMessageCardActingNoteTestTag),
                    style = style,
                    color = color,
                )
                if (onAllow != null) {
                    RaftButton(
                        text = stringResource(Res.string.message_card_allow_app, note.app.name),
                        onClick = { onAllow(note.app) },
                        variant = RaftButtonVariant.Ghost,
                        loading = allowing,
                        loadingLabel = stringResource(Res.string.thread_link_preview_prompt_allowing),
                    )
                }
            }
    }
}

/**
 * The question a press asks before the viewer has agreed to the card's App (`consent: "needed"`,
 * RFC 086 §4.2; web copy, AppFather): Allow (`link-preview-consent`; the App's epoch moves, so the
 * card reads again) or Not now, which only closes it: the next press asks again. Not now is never a
 * decline; only the server's `consent: "declined"` says "You turned off {App}.". Unlike the link
 * card prompt, there is no Don't Allow here. Allowed, the press that asked waits in [resuming] until
 * the card is read again ([ThreadMessageCardResume]).
 */
internal class ThreadMessageCardConsentFlow(
    private val store: SharedAppLinkPreviewStore,
    private val viewerKey: String,
    private val serverId: String,
) {
    /** The press that asked: the acting button [elementId] on the card's [revision]. */
    class Press(val elementId: String, val revision: Int)

    /** An allowed [press], waiting for the card's next read; [allowedAt] when Allow was answered. */
    class Resume(val press: Press, val allowedAt: TimeSource.Monotonic.ValueTimeMark)

    /** The question on screen, or null. */
    var asking by mutableStateOf<AppLinkResult.Consent?>(null)
        private set

    private var asker: Press? = null

    /** The press Allow answered, until the card is read again; null otherwise. */
    var resuming by mutableStateOf<Resume?>(null)
        private set

    fun ask(prompt: AppLinkResult.Consent, press: Press? = null) {
        asking = prompt
        asker = press
        resuming = null
    }

    /** Not now: closes the question; nothing is sent, and the press is dropped. */
    fun notNow() {
        asking = null
        asker = null
    }

    /** The waiting press went on, or was dropped. */
    fun resumed() {
        resuming = null
    }

    /**
     * Allow ([SharedAppLinkPreviewStore.allow]): the question closes unless allowing failed (then it
     * offers Retry); allowed, the press that asked waits for the card's next read.
     */
    suspend fun allow(): AppLinkConsentAnswer? {
        val prompt = asking ?: return null
        val press = asker
        val answer = store.allow(viewerKey, serverId, prompt.app).await()
        if (answer != AppLinkConsentAnswer.Failed) {
            asking = null
            asker = null
            if (answer == AppLinkConsentAnswer.Allowed && press != null) resuming = Resume(press, TimeSource.Monotonic.markNow())
        }
        return answer
    }
}

@Composable
internal fun rememberThreadMessageCardConsentFlow(host: ThreadMessageCardActingHost?): ThreadMessageCardConsentFlow? {
    host ?: return null
    val preview = host.preview
    return remember(preview.store, preview.viewerKey, preview.serverId) { ThreadMessageCardConsentFlow(preview.store, preview.viewerKey, preview.serverId) }
}

/** The consent question of [flow] in a sheet: "Allow {App}?", what allowing tells the App, Allow and Not now. */
@Composable
internal fun ThreadMessageCardConsentSheet(flow: ThreadMessageCardConsentFlow?, host: ThreadMessageCardActingHost?) {
    val prompt = flow?.asking ?: return
    host ?: return
    val scope = rememberCoroutineScope()
    val state by host.preview.store.state.collectAsState()
    val status = state.status[appLinkAppKey(host.preview.viewerKey, prompt.app.id)]
    val unavailable = stringResource(Res.string.thread_link_preview_prompt_unavailable, prompt.app.name)
    RaftDesignSystemTheme {
        ThreadMessageCardConsentPrompt(
            appName = prompt.app.name,
            status = status,
            onAllow = {
                scope.launch {
                    if (flow.allow() == AppLinkConsentAnswer.Unavailable) host.preview.toast(unavailable)
                }
            },
            onNotNow = flow::notNow,
        )
    }
}

/** The sheet itself; [inWindow] false draws the surface alone (fixtures). */
@Composable
internal fun ThreadMessageCardConsentPrompt(
    appName: String,
    status: AppLinkConsentStatus?,
    onAllow: () -> Unit,
    onNotNow: () -> Unit,
    inWindow: Boolean = true,
) {
    val title = stringResource(Res.string.message_card_consent_title, appName)
    val description =
        if (status == AppLinkConsentStatus.Failed) {
            stringResource(Res.string.thread_link_preview_prompt_allow_failed, appName)
        } else {
            stringResource(Res.string.message_card_consent_body, appName)
        }
    val actions: @Composable ColumnScope.() -> Unit = {
        RaftButton(
            text = stringResource(if (status == AppLinkConsentStatus.Failed) Res.string.common_retry else Res.string.message_card_consent_allow),
            onClick = onAllow,
            modifier = Modifier.fillMaxWidth(),
            size = RaftButtonSize.Lg,
            loading = status == AppLinkConsentStatus.Allowing,
            loadingLabel = stringResource(Res.string.thread_link_preview_prompt_allowing),
        )
        RaftButton(
            text = stringResource(Res.string.message_card_consent_not_now),
            onClick = onNotNow,
            modifier = Modifier.fillMaxWidth().testTag(ThreadMessageCardConsentNotNowTestTag),
            variant = RaftButtonVariant.Ghost,
            size = RaftButtonSize.Lg,
            enabled = status != AppLinkConsentStatus.Allowing,
        )
    }
    if (inWindow) {
        RaftSheet(onDismissRequest = onNotNow, title = title, description = description, actions = actions, content = {})
    } else {
        RaftSheetSurface(title = title, description = description, actions = actions, content = {})
    }
}

/**
 * The press [flow] holds after Allow, once the card is read again: while the read is on its way (no
 * answer, or one still asking for consent) it waits; a button the new read gives no handle is dropped
 * quietly; one with a handle is pressed again where [messageCardPressResumes] says so (Raft's
 * confirmation or the App's prepared dialog opens), else a toast asks to press it again (web).
 */
@Composable
internal fun ThreadMessageCardResume(
    flow: ThreadMessageCardConsentFlow?,
    host: ThreadMessageCardActingHost?,
    card: RaftMessageCard,
    live: RaftMessageCardLive?,
    press: ThreadAppLinkCardPress?,
) {
    val resuming = flow?.resuming ?: return
    if (host == null || press == null) return
    val acting = card.acting ?: return
    val elementId = resuming.press.elementId
    val button = card.card.elements[elementId] as? RaftLinkCardV2Element.Button
    val action = acting.actions[elementId]
    val pressAgain = stringResource(Res.string.message_card_press_again, live?.app?.name.orEmpty(), button?.label.orEmpty())
    LaunchedEffect(resuming, live, card.revision) {
        if (button == null || action == null) {
            flow.resumed()
            return@LaunchedEffect
        }
        val fallbackUrl = (button.action as? RaftLinkCardButtonAction.Navigate)?.url
        when (messageCardActingState(live, elementId, fallbackUrl)) {
            // Not read again yet.
            is RaftMessageCardActingState.AskConsent -> Unit
            RaftMessageCardActingState.Disabled -> if (live != null) flow.resumed()
            is RaftMessageCardActingState.Pressable -> {
                flow.resumed()
                val resumes =
                    messageCardPressResumes(
                        action = action,
                        unreviewed = press.card.app.unreviewed,
                        allowedRevision = resuming.press.revision,
                        revision = card.revision,
                        elapsedMs = resuming.allowedAt.elapsedNow().inWholeMilliseconds,
                    )
                val pressable = press.button(button.copy(disabledReason = null), action)
                if (resumes && pressable != null && pressable.enabled) {
                    pressable.onClick()
                } else {
                    host.preview.toastWithIntent(pressAgain, RaftToastIntent.Info)
                }
            }
        }
    }
}

internal const val ThreadMessageCardConsentNotNowTestTag = "thread_message_card_consent_not_now"

/** Allow [app] from the declined line: lifts the decline (`link-preview-consent`); the card reads again on the App's new epoch. */
@Composable
internal fun rememberThreadMessageCardAllow(host: ThreadMessageCardActingHost?): ((RaftMessageCardApp) -> Unit)? {
    host ?: return null
    val scope = rememberCoroutineScope()
    val preview = host.preview
    val unavailable = stringResource(Res.string.thread_link_preview_prompt_unavailable, host.live?.app?.name.orEmpty())
    val failed = stringResource(Res.string.thread_link_preview_prompt_allow_failed, host.live?.app?.name.orEmpty())
    return remember(preview, unavailable, failed) {
        { app: RaftMessageCardApp ->
            scope.launch {
                when (preview.store.allow(preview.viewerKey, preview.serverId, app.toPromptApp()).await()) {
                    AppLinkConsentAnswer.Failed -> preview.toast(failed)
                    AppLinkConsentAnswer.Unavailable -> preview.toast(unavailable)
                    else -> Unit
                }
            }
            Unit
        }
    }
}

/** Raft's confirmation of a pressed acting button, in the design system's frame (as a block card's). */
@Composable
internal fun ThreadMessageCardActingConfirmation(press: ThreadAppLinkCardPress) {
    if (press.confirmation == null) return
    RaftDesignSystemTheme { press.Confirmation() }
}

internal const val RaftMessageCardActingButtonTestTagPrefix = "thread_message_card_acting_"
internal const val RaftMessageCardActingNoteTestTag = "thread_message_card_acting_note"
