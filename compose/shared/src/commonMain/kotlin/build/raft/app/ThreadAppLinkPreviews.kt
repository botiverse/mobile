package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AppLinkConsentAnswer
import build.raft.app.data.AppLinkConsentStatus
import build.raft.app.data.AppLinkPromptApp
import build.raft.app.data.AppLinkResult
import build.raft.app.data.SharedAppLinkPreviewStore
import build.raft.app.data.appLinkAppKey
import build.raft.app.data.appLinkDomain
import build.raft.app.data.appLinkPreviewsApply
import build.raft.app.data.messageView
import build.raft.app.markdown.LocalRaftAppLinkChips
import build.raft.app.markdown.RaftAppLinkChipHost
import build.raft.app.markdown.raftAppLinkKeepEnd
import build.raft.app.resources.MR
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftIconTile
import build.raft.designsystem.components.RaftIconTileSize
import build.raft.designsystem.components.RaftSheet
import build.raft.designsystem.components.RaftSheetSurface
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import com.tencent.tmm.kmmresource.compose.stringResource
import coil3.compose.AsyncImagePainter
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
)

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
    content: @Composable () -> Unit,
) {
    val host = LocalThreadAppLinkPreviewHost.current
    if (host == null || !enabled || !appLinkPreviewsApply(body) || !messageId.isAppLinkMessageId()) {
        content()
        return
    }
    val state by host.store.state.collectAsState()
    val view =
        remember(state, host.viewerKey, messageId, body, host.conversationId) {
            state.messageView(host.viewerKey, messageId, body, host.conversationId)
        }
    LaunchedEffect(host.store, host.viewerKey, messageId, body, view.wantsRead, view.readSignature) {
        if (view.wantsRead) host.store.read(host.viewerKey, host.serverId, messageId, body, host.conversationId)
    }
    var asking by remember(messageId) { mutableStateOf<AppLinkResult.Consent?>(null) }
    var carding by remember(messageId) { mutableStateOf<String?>(null) }
    val chipHost =
        remember(view.results) {
            RaftAppLinkChipHost(view.results, onAsk = { asking = it }, onOpenCard = { carding = it.url })
        }
    CompositionLocalProvider(LocalRaftAppLinkChips provides chipHost, content = content)
    // #proj-mobile: a chip whose App sent a card body opens its item card in a sheet.
    ThreadAppLinkCardSheetHost(results = view.results, url = carding, onDismiss = { carding = null })
    val prompt = asking
    // The prompt closes itself once its chip is gone (answered, or asked on another message).
    val promptShown = prompt != null && view.results[prompt.url] is AppLinkResult.Consent
    if (prompt != null && promptShown) {
        ThreadAppLinkConsentSheet(host = host, prompt = prompt, onDismiss = { asking = null })
    }
    LaunchedEffect(prompt, promptShown) {
        if (prompt != null && !promptShown) asking = null
    }
}

/** Raft message ids are UUIDs; a message still being sent has a local id the server doesn't know. */
private fun String.isAppLinkMessageId(): Boolean =
    length == 36 && withIndex().all { (index, char) ->
        if (index == 8 || index == 13 || index == 18 || index == 23) char == '-' else char.isLetterOrDigit()
    }

/**
 * The first-view question (web `LinkPreviewConsentPrompt` in the chip's Popover; on a phone a
 * bottom sheet): may this App show previews of its links here? It names the App and says what
 * allowing tells it, then where the link goes, with Open Link. Allow is the ink-bordered
 * button, Don't Allow the frameless one, neither pink nor orange.
 */
@Composable
private fun ThreadAppLinkConsentSheet(
    host: ThreadAppLinkPreviewHost,
    prompt: AppLinkResult.Consent,
    onDismiss: () -> Unit,
) {
    val state by host.store.state.collectAsState()
    val status = state.status[appLinkAppKey(host.viewerKey, prompt.app.id)]
    val scope = rememberCoroutineScope()
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val unavailable = stringResource(MR.strings.thread_link_preview_prompt_unavailable, prompt.app.name)
    val declined = stringResource(MR.strings.thread_link_preview_prompt_declined, prompt.app.name)
    val declineFailed = stringResource(MR.strings.thread_link_preview_prompt_decline_failed, prompt.app.name)
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
                uriHandler.openUri(prompt.url)
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
    val title = stringResource(MR.strings.thread_link_preview_prompt_title, app.name)
    val description =
        if (status == AppLinkConsentStatus.Failed) {
            stringResource(MR.strings.thread_link_preview_prompt_allow_failed, app.name)
        } else {
            stringResource(MR.strings.thread_link_preview_prompt_disclosure, app.name)
        }
    val actions: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = {
        RaftButton(
            text = stringResource(if (status == AppLinkConsentStatus.Failed) MR.strings.common_retry else MR.strings.thread_link_preview_prompt_allow),
            onClick = onAllow,
            modifier = Modifier.fillMaxWidth(),
            variant = RaftButtonVariant.Default,
            size = RaftButtonSize.Lg,
            enabled = status != AppLinkConsentStatus.Declining,
            loading = status == AppLinkConsentStatus.Allowing,
            loadingLabel = stringResource(MR.strings.thread_link_preview_prompt_allowing),
        )
        RaftButton(
            text = stringResource(MR.strings.thread_link_preview_prompt_dont_allow),
            onClick = onDecline,
            modifier = Modifier.fillMaxWidth(),
            variant = RaftButtonVariant.Ghost,
            size = RaftButtonSize.Lg,
            enabled = status != AppLinkConsentStatus.Allowing,
            loading = status == AppLinkConsentStatus.Declining,
            loadingLabel = stringResource(MR.strings.thread_link_preview_prompt_saving),
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
    val domain = appLinkDomain(url).orEmpty()
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
            text = stringResource(MR.strings.thread_link_preview_open_link),
            onClick = onOpenLink,
            variant = RaftButtonVariant.Ghost,
        )
    }
}

/** web ConnectedAppLogo `xs` (27dp): the App's logo, or the generic App tile. Decorative. */
@Composable
private fun ThreadAppLinkAppLogo(app: AppLinkPromptApp) {
    val icon = app.icon
    var broken by remember(icon) { mutableStateOf(false) }
    if (icon == null || broken) {
        RaftIconTile(size = RaftIconTileSize.Sm) {
            RaftContentLucideIcon(RaftLucideIconName.AppWindow, size = RaftIconTileSize.Sm.icon)
        }
        return
    }
    val shape = RoundedCornerShape(RaftAppRadius.Sticker)
    Box(
        Modifier.size(RaftIconTileSize.Sm.edge)
            .background(RaftColor.StickerPaper, shape)
            .border(RaftAppStroke.Control, RaftColor.StickerInk, shape),
    ) {
        RaftCachedImage(
            source = icon,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            onImageStateChange = { if (it is AsyncImagePainter.State.Error) broken = true },
        )
    }
}
