package build.raft.app

import build.raft.app.resources.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.designsystem.components.RaftToastIntent
import build.raft.app.data.AppLinkConsentAnswer
import build.raft.app.data.AppLinkConsentStatus
import build.raft.app.data.AppLinkPromptApp
import build.raft.app.data.AppLinkResult
import build.raft.app.data.ArtifactLinkTarget
import build.raft.app.data.ArtifactSummary
import build.raft.app.data.ArtifactSummaryAnswer
import build.raft.app.data.SharedArtifactScreenshotStore
import build.raft.app.data.SharedArtifactSummaryStore
import build.raft.app.data.SharedServerFeatureFlags
import build.raft.app.data.APP_LINK_READ_MODE_V1
import build.raft.app.data.AppLinkBatchRead
import build.raft.app.data.AppLinkCardView
import build.raft.app.data.appLinkCardReadLocale
import build.raft.app.data.runtimeFormRequestLanguageTag
import kotlinx.coroutines.awaitCancellation
import build.raft.app.data.WORKSPACE_ARTIFACTS_FEATURE_FLAG_KEY
import build.raft.app.data.answersFor
import build.raft.app.links.planRaftMessageLinks
import build.raft.app.data.artifactWebUrl
import build.raft.app.data.rememberServerFeatureLatched
import build.raft.app.model.ServerId
import kotlinx.coroutines.flow.StateFlow
import build.raft.app.data.SharedAppLinkPreviewStore
import build.raft.app.data.appLinkAppKey
import build.raft.app.data.appLinkDomain
import build.raft.app.data.appLinkPreviewsApply
import build.raft.app.data.messageView
import build.raft.app.markdown.LocalRaftAppLinkChips
import build.raft.app.markdown.LocalRaftMarkdownBlockCardHost
import build.raft.app.markdown.RaftMarkdownBlockCardHost
import build.raft.app.markdown.RaftAppLinkChipHost
import build.raft.app.markdown.raftAppLinkAsciiHost
import build.raft.app.markdown.raftAppLinkKeepEnd
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftSheet
import build.raft.designsystem.components.RaftSheetSurface
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import kotlinx.coroutines.launch

/**
 * The conversation's App link previews (artin #20 lane L): whose they are (viewer and Server),
 * the conversation an App is asked once in (the channel, DM or thread on screen), and the
 * thread's toast. Null where previews aren't read (no session, no Server).
 */
@Immutable
internal data class ThreadAppLinkPreviewHost(
    val store: SharedAppLinkPreviewStore,
    val viewerKey: String,
    val serverId: String,
    val conversationId: String,
    val toast: (String) -> Unit,
    /** [toast] with an intent (an operation state's tone); plain where none is wired (fixtures). */
    val toastWithIntent: (String, RaftToastIntent?) -> Unit = { text, _ -> toast(text) },
    /** Workspace Artifacts links; null where they aren't read (fixtures, no provider). */
    val artifacts: ThreadArtifactLinkHost? = null,
    /** The batched link-card read (App link cards v2); false: always the GET (fixtures). */
    val batchedRead: Boolean = false,
)

/**
 * What a message needs to show its Artifact links as chips (web `ArtifactMessageRefs.tsx`
 * `useArtifactLinkTargets` + core's `useMessageLinkCards`): the summaries, Raft's own app hosts
 * (`GET /api/public/client-config`), the flag, and the environment's web origin a chip opens on.
 */
@Immutable
internal data class ThreadArtifactLinkHost(
    val summaries: SharedArtifactSummaryStore,
    val appHosts: StateFlow<List<String>>,
    val featureFlags: SharedServerFeatureFlags?,
    val webBaseUrl: String,
    /** The cards' screenshots (in memory for this viewer); null where none are read (fixtures). */
    val screenshots: SharedArtifactScreenshotStore? = null,
)

/** One message's Artifact links answered `ok`, and the hosts they were split on. */
@Immutable
internal data class ThreadArtifactLinks(
    val summaries: Map<String, ArtifactSummary>,
    val appHosts: List<String>,
) {
    companion object {
        val None = ThreadArtifactLinks(emptyMap(), emptyList())
    }
}

/**
 * One message's Artifact references (web core `useMessageLinkCards`: its visible links on the app
 * hosts, bare or with the author's words, in message order, each reference once, at most [build.raft.app.links.MESSAGE_OBJECT_LINK_LIMIT])
 * and their answers so far, by [build.raft.app.data.ArtifactLinkTarget.key]; a target with no
 * answer is still loading.
 */
@Immutable
internal data class ThreadArtifactLinkAnswers(
    val targets: List<ArtifactLinkTarget>,
    val answers: Map<String, ArtifactSummaryAnswer>,
    val appHosts: List<String>,
) {
    companion object {
        val None = ThreadArtifactLinkAnswers(emptyList(), emptyMap(), emptyList())
    }
}

/**
 * The message's Artifact links (workspace_artifacts_v0, latched for this composition: a flag that
 * turns off leaves what is shown until the next entry): split on the app hosts and read in the
 * message's batch (the store's one request per window; a reference already asked is not asked
 * again, so the chips in the text and the cards under it share one read).
 */
@Composable
internal fun rememberThreadArtifactLinkAnswers(host: ThreadArtifactLinkHost?, serverId: String, body: String): ThreadArtifactLinkAnswers {
    if (host == null) return ThreadArtifactLinkAnswers.None
    val enabled = rememberServerFeatureLatched(host.featureFlags, ServerId(serverId), WORKSPACE_ARTIFACTS_FEATURE_FLAG_KEY)
    val appHosts by host.appHosts.collectAsState()
    val targets = remember(enabled, appHosts, body) { if (enabled) planRaftMessageLinks(body, appHosts).objectTargets else emptyList() }
    if (targets.isEmpty()) return ThreadArtifactLinkAnswers.None
    val answersFlow = remember(host.summaries, serverId, targets) { host.summaries.answersFlow(serverId, targets) }
    val answers by answersFlow.collectAsState(initial = host.summaries.state.value.answersFor(serverId, targets))
    LaunchedEffect(host.summaries, serverId, targets) { host.summaries.request(serverId, targets) }
    return remember(targets, answers, appHosts) { ThreadArtifactLinkAnswers(targets, answers, appHosts) }
}

/** The message's Artifact links answered `ok` ([rememberThreadArtifactLinkAnswers]): the chips in its text. */
@Composable
internal fun rememberThreadArtifactLinks(host: ThreadArtifactLinkHost?, serverId: String, body: String): ThreadArtifactLinks {
    val links = rememberThreadArtifactLinkAnswers(host, serverId, body)
    if (links.targets.isEmpty()) return ThreadArtifactLinks.None
    return remember(links) {
        ThreadArtifactLinks(
            summaries = links.answers.mapNotNull { (key, answer) -> (answer as? ArtifactSummaryAnswer.Ok)?.let { key to it.summary } }.toMap(),
            appHosts = links.appHosts,
        )
    }
}

internal val LocalThreadAppLinkPreviewHost = staticCompositionLocalOf<ThreadAppLinkPreviewHost?> { null }

/**
 * One message's App links (web MessageItem `useAppLinkPreviews` + `AppLinkResultsProvider`):
 * read once the row is composed (the timeline composes the rows on screen), then shown as
 * chips by the message's Markdown links in [content]. [enabled] is false where the web reads
 * nothing: a forwarded bundle, a read-only projection, a message not sent yet.
 */
@Composable
internal fun ThreadAppLinkPreviewScope(
    messageId: String,
    body: String,
    enabled: Boolean,
    /** Opens a link as the message's own links open (the app's link setting, or Raft's own page). */
    onOpenLink: (String) -> Unit,
    content: @Composable () -> Unit,
) {
    val host = LocalThreadAppLinkPreviewHost.current
    // A share image keeps the links the author wrote: no chips, item card, prompt or placeholder.
    val previewsEnabled = threadAppLinkPreviewsEnabled(enabled, LocalThreadShareCaptureActive.current)
    if (host == null || !previewsEnabled || !messageId.isAppLinkMessageId()) {
        content()
        return
    }
    val artifacts = rememberThreadArtifactLinks(host.artifacts, host.serverId, body)
    if (!appLinkPreviewsApply(body) && artifacts.summaries.isEmpty()) {
        content()
        return
    }
    val batch = rememberThreadAppLinkBatchRead(host, messageId, body)
    val state by host.store.state.collectAsState()
    val view =
        remember(state, host.viewerKey, messageId, body, host.conversationId, batch) {
            state.messageView(
                host.viewerKey, messageId, body, host.conversationId, host.store.artifactHosts(),
                mode = batch.read?.mode ?: APP_LINK_READ_MODE_V1,
                viewId = batch.read?.view?.id,
            )
        }
    LaunchedEffect(host.store, host.viewerKey, messageId, body, view.wantsRead, view.readSignature, batch) {
        if (view.wantsRead) host.store.read(host.viewerKey, host.serverId, messageId, body, host.conversationId, batch.read)
    }
    // Links Raft answered rate_limited are read again only while this row is on screen.
    LaunchedEffect(host.store, batch, view.readsLimited) {
        val read = batch.read
        if (read != null && view.readsLimited) {
            val job = host.store.keepReadingLimited(host.viewerKey, host.serverId, messageId, body, read)
            try {
                awaitCancellation()
            } finally {
                job.cancel()
            }
        }
    }
    var asking by remember(messageId) { mutableStateOf<AppLinkResult.Consent?>(null) }
    // #proj-mobile: a chip whose App sent a card body opens its item card beside it.
    val cards = rememberThreadAppLinkCards(messageId, onOpenLink)
    val artifactWebBaseUrl = host.artifacts?.webBaseUrl.orEmpty()
    // An Artifact link the author gave words to opens in place, as its card under the message does
    // (#proj-workspace:#289); the browser only when no page can open.
    val openArtifactWebPage = rememberRaftWebPageOpener()
    val artifactOpener = rememberRaftArtifactOpener { openArtifactWebPage(artifactWebUrl(artifactWebBaseUrl, it)) }
    val chipHost =
        remember(view.results, cards, artifacts, artifactWebBaseUrl, artifactOpener) {
            RaftAppLinkChipHost(
                view.results,
                onAsk = { asking = it },
                onOpenCard = cards::open,
                cardAnchor = cards::anchor,
                artifacts = artifacts.summaries,
                artifactHosts = artifacts.appHosts,
                onOpenArtifact = { target -> cards.openArtifact(target, artifactWebUrl(artifactWebBaseUrl, target)) },
                artifactAnchor = { key -> cards.anchor(threadArtifactCardKey(key)) },
                onOpenArtifactInPlace = { target, summary ->
                    artifactOpener.open(target, RaftArtifactViewerHeader.of(summary, target), "message_labelled_link")
                },
            )
        }
    // raft-design #1010: the block cards of this message, drawn after their paragraphs. Only the
    // message itself: quotes, forwards, search and Activity have no scope, so they show chips.
    val openInSystemBrowser = rememberRaftSystemBrowserOpener()
    val currentOnOpenLink by rememberUpdatedState(onOpenLink)
    val read = batch.read
    val actionHost =
        remember(host, messageId, body, read) {
            read?.let { batchRead ->
                ThreadAppLinkCardActionHost(
                    prepare = { handle, revision -> host.store.prepareAction(host.serverId, handle, revision) },
                    execute = { handle, requestId, prepared -> host.store.executeAction(host.serverId, handle, requestId, prepared) },
                    reread = { url -> host.store.rereadLink(host.viewerKey, host.serverId, messageId, body, batchRead, url) },
                    toast = host.toast,
                    toastWithIntent = host.toastWithIntent,
                )
            }
        }
    val blockHost =
        remember(view.results, openInSystemBrowser, actionHost) {
            if (view.results.values.none { it is AppLinkResult.Card && it.block != null }) {
                null
            } else {
                RaftMarkdownBlockCardHost(view.results) { card ->
                    ThreadAppLinkBlockCard(
                        card,
                        openUri = { address ->
                            openThreadAppLinkAddress(address, card.connectUrl, currentOnOpenLink, openInSystemBrowser)
                        },
                        actions = actionHost,
                    )
                }
            }
        }
    CompositionLocalProvider(
        LocalRaftAppLinkChips provides chipHost,
        LocalRaftMarkdownBlockCardHost provides blockHost,
        content = content,
    )
    ThreadAppLinkCardPopoverHost(results = view.results, cards = cards, artifacts = artifacts.summaries, artifactWebBaseUrl = artifactWebBaseUrl)
    // An Artifact chip on screen: boot the in-app viewer's web page ahead of a tap.
    RaftArtifactViewerPrewarm(enabled = artifacts.summaries.isNotEmpty())
    val prompt = asking
    // The prompt closes itself once its chip is gone (answered, or asked on another message).
    val promptShown = prompt != null && view.results[prompt.url] is AppLinkResult.Consent
    if (prompt != null && promptShown) {
        ThreadAppLinkConsentSheet(host = host, prompt = prompt, onOpenLink = onOpenLink, onDismiss = { asking = null })
    }
    LaunchedEffect(prompt, promptShown) {
        if (prompt != null && !promptShown) asking = null
    }
}

/** How a message row reads its App links: [read] null is the GET. */
@Immutable
internal data class ThreadAppLinkBatch(val read: AppLinkBatchRead?)

/**
 * This message view of the batched read: its `view_instance_id` made once when the row is shown
 * and kept while it stays, in the app's language. The GET where the host has no batched read
 * (fixtures).
 */
@Composable
private fun rememberThreadAppLinkBatchRead(host: ThreadAppLinkPreviewHost, messageId: String, body: String): ThreadAppLinkBatch {
    if (!host.batchedRead) return remember { ThreadAppLinkBatch(read = null) }
    val locale = remember { appLinkCardReadLocale(runtimeFormRequestLanguageTag()) }
    return remember(host.serverId, messageId, body, locale) {
        ThreadAppLinkBatch(read = AppLinkBatchRead(AppLinkCardView.create(), locale))
    }
}

/** Raft message ids are UUIDs; a message still being sent has a local id the server doesn't know. */
internal fun String.isAppLinkMessageId(): Boolean =
    length == 36 && withIndex().all { (index, char) ->
        if (index == 8 || index == 13 || index == 18 || index == 23) char == '-' else char.isLetterOrDigit()
    }

/**
 * The first-view question (web `LinkPreviewConsentPrompt` in the chip's Popover; on a phone a
 * bottom sheet): may this App show previews of its links here? It names the App and says what
 * allowing tells it, then where the link goes, with Open Link. Allow is the ink-bordered
 * button, Don't Allow the frameless one, neither pink nor orange.
 */
/**
 * A card's address opens as a message link does; a connect_account card's connect URL goes to the
 * system browser, where the account's sign-in is.
 */
internal fun openThreadAppLinkAddress(
    address: String,
    connectUrl: String?,
    openLink: (String) -> Unit,
    openInSystemBrowser: (String) -> Unit,
) {
    if (address == connectUrl) openInSystemBrowser(address) else openLink(address)
}

@Composable
private fun ThreadAppLinkConsentSheet(
    host: ThreadAppLinkPreviewHost,
    prompt: AppLinkResult.Consent,
    onOpenLink: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val state by host.store.state.collectAsState()
    val status = state.status[appLinkAppKey(host.viewerKey, prompt.app.id)]
    val scope = rememberCoroutineScope()
    val unavailable = stringResource(Res.string.thread_link_preview_prompt_unavailable, prompt.app.name)
    val declined = stringResource(Res.string.thread_link_preview_prompt_declined, prompt.app.name)
    val declineFailed = stringResource(Res.string.thread_link_preview_prompt_decline_failed, prompt.app.name)
    RaftDesignSystemTheme {
        ThreadAppLinkConsentPrompt(
            app = prompt.app,
            url = prompt.url,
            status = status,
            onDismiss = onDismiss,
            onAllow = {
                scope.launch {
                    when (host.store.allow(host.viewerKey, host.serverId, prompt.app).await()) {
                        // Failed: the sheet stays, its Allow button now Retry.
                        AppLinkConsentAnswer.Failed -> Unit
                        AppLinkConsentAnswer.Unavailable -> {
                            host.toast(unavailable)
                            onDismiss()
                        }
                        else -> onDismiss()
                    }
                }
            },
            onDecline = {
                scope.launch {
                    when (host.store.decline(host.viewerKey, host.serverId, prompt.app).await()) {
                        AppLinkConsentAnswer.Declined -> {
                            host.toast(declined)
                            onDismiss()
                        }
                        AppLinkConsentAnswer.Unavailable -> {
                            host.toast(unavailable)
                            onDismiss()
                        }
                        else -> host.toast(declineFailed)
                    }
                }
            },
            onOpenLink = {
                onDismiss()
                onOpenLink(prompt.url)
            },
        )
    }
}

/** The prompt in its sheet; [inWindow] false draws the surface alone (fixtures). */
@Composable
internal fun ThreadAppLinkConsentPrompt(
    app: AppLinkPromptApp,
    url: String,
    status: AppLinkConsentStatus?,
    onDismiss: () -> Unit,
    onAllow: () -> Unit,
    onDecline: () -> Unit,
    onOpenLink: () -> Unit,
    inWindow: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val title = stringResource(Res.string.thread_link_preview_prompt_title, app.name)
    val description =
        if (status == AppLinkConsentStatus.Failed) {
            stringResource(Res.string.thread_link_preview_prompt_allow_failed, app.name)
        } else {
            stringResource(Res.string.thread_link_preview_prompt_disclosure, app.name)
        }
    val actions: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = {
        RaftButton(
            text = stringResource(if (status == AppLinkConsentStatus.Failed) Res.string.common_retry else Res.string.thread_link_preview_prompt_allow),
            onClick = onAllow,
            modifier = Modifier.fillMaxWidth(),
            variant = RaftButtonVariant.Default,
            size = RaftButtonSize.Lg,
            enabled = status != AppLinkConsentStatus.Declining,
            loading = status == AppLinkConsentStatus.Allowing,
            loadingLabel = stringResource(Res.string.thread_link_preview_prompt_allowing),
        )
        RaftButton(
            text = stringResource(Res.string.thread_link_preview_prompt_dont_allow),
            onClick = onDecline,
            modifier = Modifier.fillMaxWidth(),
            variant = RaftButtonVariant.Ghost,
            size = RaftButtonSize.Lg,
            enabled = status != AppLinkConsentStatus.Allowing,
            loading = status == AppLinkConsentStatus.Declining,
            loadingLabel = stringResource(Res.string.thread_link_preview_prompt_saving),
        )
    }
    val body: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = {
        ThreadAppLinkConsentDestination(app = app, url = url, onOpenLink = onOpenLink)
    }
    if (inWindow) {
        RaftSheet(onDismissRequest = onDismiss, title = title, description = description, modifier = modifier, actions = actions, content = body)
    } else {
        RaftSheetSurface(title = title, description = description, modifier = modifier, actions = actions, content = body)
    }
}

/** The App's logo and where the link goes (its domain, which loses its start, never its end), with Open Link. */
@Composable
private fun ThreadAppLinkConsentDestination(app: AppLinkPromptApp, url: String, onOpenLink: () -> Unit) {
    val colors = RaftTheme.colors
    // raft-design #755: the host in ASCII (punycode), never a look-alike Unicode name.
    val domain = appLinkDomain(url)?.let(::raftAppLinkAsciiHost).orEmpty()
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ThreadAppLinkAppLogo(app)
        RaftText(
            text = raftAppLinkKeepEnd(domain, 40),
            modifier = Modifier.weight(1f),
            style = RaftTheme.typography.footnote,
            color = colors.foregroundMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        RaftButton(
            text = stringResource(Res.string.thread_link_preview_open_link),
            onClick = onOpenLink,
            variant = RaftButtonVariant.Ghost,
        )
    }
}

/** web ConnectedAppLogo `xs` (27dp): the App's logo, or its initials. Decorative. */
@Composable
private fun ThreadAppLinkAppLogo(app: AppLinkPromptApp) {
    RaftConnectedAppLogo(name = app.name.ifBlank { app.id }, logoUrl = app.icon)
}
