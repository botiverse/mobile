package build.raft.app

import build.raft.app.resources.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import build.raft.app.base.Utils
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.Pager
import build.raft.app.components.RaftSlotIcon
import build.raft.app.data.RaftClientConfigAppHosts
import build.raft.app.links.toParsedRaftPermalink
import build.raft.app.markdown.RaftMarkdownLinkLongPressHandler
import build.raft.designsystem.components.RaftMenuItem
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftMenuMetrics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource

/*
 * A long press on a link in a message opens that message's menu (Classic, or the Compact "New
 * message menu"), with a section for the link on top: the address on one line, then Open, Copy
 * link, Share and, for a link the app does not open itself, Open in browser (artin, 1.24). The web
 * leaves a link's right-click and long press to the browser's own link menu (MessageItem
 * `a[target="_blank"]`, botiverse/slock task #489); the words are the web catalog's.
 */

/** One action of a message menu's link section, in menu order. */
internal enum class ThreadMessageLinkMenuAction {
    /** Opens the link as a tap on it does: a Raft link in the app, any other by the link setting. */
    Open,
    CopyLink,
    Share,
    /** A link that is not Raft's own, in the system browser whatever the link setting. */
    OpenInBrowser,
}

/**
 * The link section's actions: Open, Copy link and Share for every link, and Open in browser for a
 * link that is not a Raft link (a Raft link opens in the app, which is what Open already does).
 */
internal fun threadMessageLinkMenuActions(isRaftLink: Boolean): List<ThreadMessageLinkMenuAction> =
    buildList {
        add(ThreadMessageLinkMenuAction.Open)
        add(ThreadMessageLinkMenuAction.CopyLink)
        add(ThreadMessageLinkMenuAction.Share)
        if (!isRaftLink) add(ThreadMessageLinkMenuAction.OpenInBrowser)
    }

/**
 * Whether [url] is a Raft link: one a tap opens in the app, a message, channel or DM link on the
 * environment's web ([webUrl], [raftMarkdownUrlOpen]) or a Raft Desktop link to one, or an
 * Artifact's address or share link ([parseRaftArtifactDeepLink]: built-in hosts, [appHosts], [webUrl]).
 */
internal fun threadMessageLinkIsRaft(url: String, webUrl: String?, appHosts: Collection<String>): Boolean {
    val open = raftMarkdownUrlOpen(url, webUrl)
    return open.target != null ||
        open.url.toParsedRaftPermalink(configuredWebUrl = webUrl, appHosts = appHosts) != null ||
        parseRaftArtifactDeepLink(open.url, webUrl, appHosts.toSet()) != null
}

internal fun ThreadMessageLinkMenuAction.label(): StringResource =
    when (this) {
        ThreadMessageLinkMenuAction.Open -> Res.string.common_open
        ThreadMessageLinkMenuAction.CopyLink -> Res.string.thread_link_menu_copy_link
        ThreadMessageLinkMenuAction.Share -> Res.string.common_share
        ThreadMessageLinkMenuAction.OpenInBrowser -> Res.string.thread_link_menu_open_in_browser
    }

internal fun ThreadMessageLinkMenuAction.icon(): RaftLucideIconName =
    when (this) {
        ThreadMessageLinkMenuAction.Open -> RaftLucideIconName.ArrowUpRight
        ThreadMessageLinkMenuAction.CopyLink -> RaftLucideIconName.Link
        ThreadMessageLinkMenuAction.Share -> RaftLucideIconName.Share
        ThreadMessageLinkMenuAction.OpenInBrowser -> RaftLucideIconName.Globe
    }

/** One row of the link section: what it is and what it does (the menu closes first). */
internal class ThreadMessageLinkMenuItem(
    val action: ThreadMessageLinkMenuAction,
    val onClick: () -> Unit,
)

/** The link section as the menus draw it: the link's address and its actions. */
internal class ThreadMessageLinkMenuSection(
    val url: String,
    val items: List<ThreadMessageLinkMenuItem>,
)

/**
 * The message row's handler for a finger's long press on a link in its text: the row's long-press
 * menu ([ThreadMessageLongPressGestureOwner.showActionsForLink]), with the link published for that
 * menu's section on [selectionHost] when the link has an address (an @mention, #channel or task
 * chip has none: its menu is the message's own). With in-place selection ([inPlaceSelection], the
 * New message menu) the press is the menu's, not a text selection's.
 */
internal fun threadMessageLinkLongPressHandler(
    messageId: String,
    gestureOwner: ThreadMessageLongPressGestureOwner,
    selectionHost: ThreadInPlaceTextSelectionHost?,
    inPlaceSelection: ThreadInPlaceSelectionController? = null,
): RaftMarkdownLinkLongPressHandler =
    RaftMarkdownLinkLongPressHandler { link, source, position ->
        gestureOwner.showActionsForLink(source, position) { anchor ->
            inPlaceSelection?.linkLongPressOwnsGesture = true
            selectionHost?.contextMenuLink = link.url?.let { url -> ThreadContextMenuLink(messageId, anchor, url) }
        }
    }

/**
 * The link section for [url] in an open message menu. [close] closes the menu (each action does
 * first); [openLink] opens a link as a tap on it does (the page's existing link handling, which
 * opens a Raft link in the app); [webUrl] is the environment's web URL that handling reads too.
 * Copy uses the clipboard (no toast, like the web's Copy link); Share hands the address to the
 * system share sheet (or copies it where the host has none) from [scope], which must outlive the
 * menu (the menu closes first); Open in browser goes to the system browser whatever the link
 * setting.
 */
@Composable
internal fun rememberThreadMessageLinkMenuSection(
    url: String,
    webUrl: String?,
    close: () -> Unit,
    openLink: (String) -> Unit,
    scope: CoroutineScope,
    diagnosticsLogger: (String) -> Unit = {},
): ThreadMessageLinkMenuSection {
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = remember(pager) { Utils.bridgeModule(pager.pagerId) }
    val navigator = LocalRaftLinkNavigator.current
    val copiedLabel = stringResource(Res.string.common_copied)
    val isRaftLink = remember(url, webUrl) { threadMessageLinkIsRaft(url, webUrl, RaftClientConfigAppHosts.current) }
    return remember(url, isRaftLink, bridge, navigator, scope, copiedLabel, close, openLink, diagnosticsLogger) {
        val items =
            threadMessageLinkMenuActions(isRaftLink).map { action ->
                ThreadMessageLinkMenuItem(action) {
                    close()
                    diagnosticsLogger("thread_message_link_menu_action action=${action.name} raftLink=$isRaftLink")
                    when (action) {
                        ThreadMessageLinkMenuAction.Open -> openLink(url)
                        ThreadMessageLinkMenuAction.CopyLink -> bridge.copyToPasteboard(url)
                        ThreadMessageLinkMenuAction.Share ->
                            scope.launch {
                                val shared =
                                    bridge.settingsCapability(JSONObject().put("action", "shareText").put("text", url))
                                        ?.optString("status") == "ok"
                                if (!shared) {
                                    // A host without a share sheet (desktop): the address is copied instead.
                                    bridge.copyToPasteboard(url)
                                    bridge.briefToast(copiedLabel)
                                }
                            }
                        ThreadMessageLinkMenuAction.OpenInBrowser ->
                            if (navigator != null) navigator.open(url, RaftLinkOpenIntent.ForceExternal) else bridge.openUrl(url, inBrowser = true)
                    }
                }
            }
        ThreadMessageLinkMenuSection(url, items)
    }
}

/** The address line's height: a caption line in a menu label's 8dp above and 4dp below. */
internal val ThreadMessageLinkMenuUrlRowHeight = 28.dp

/** The link section's first line: the address, on one line, cut in the middle when it is long. */
@Composable
internal fun ThreadMessageLinkMenuUrl(url: String, modifier: Modifier = Modifier) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(ThreadMessageLinkMenuUrlRowHeight)
                .padding(
                    start = RaftMenuMetrics.ItemPaddingHorizontal,
                    end = RaftMenuMetrics.ItemPaddingHorizontal,
                    top = RaftMenuMetrics.LabelPaddingTop,
                    bottom = RaftMenuMetrics.LabelPaddingBottom,
                ),
        contentAlignment = Alignment.CenterStart,
    ) {
        RaftText(
            url,
            style = RaftTheme.typography.caption,
            color = RaftTheme.colors.foregroundMuted,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
        )
    }
}

/** The Classic menu's link section: the address, then a menu row per action. */
@Composable
internal fun ThreadMessageLinkMenuRows(section: ThreadMessageLinkMenuSection) {
    Column(Modifier.fillMaxWidth()) {
        ThreadMessageLinkMenuUrl(section.url)
        section.items.forEach { item ->
            RaftMenuItem(
                text = stringResource(item.action.label()),
                onClick = item.onClick,
                leadingIcon = { RaftSlotIcon(item.action.icon()) },
            )
        }
    }
}
