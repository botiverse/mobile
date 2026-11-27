package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftSlotIcon
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftAvatarSize
import build.raft.designsystem.components.RaftBackButton
import build.raft.designsystem.components.RaftBackButtonDefaults
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftCloseButton
import build.raft.designsystem.components.RaftCloseButtonSize
import build.raft.designsystem.components.RaftDropdownMenu
import build.raft.designsystem.components.RaftHoverTier
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftIconButtonSize
import build.raft.designsystem.components.RaftMenuItem
import build.raft.designsystem.components.RaftMenuSeparator
import build.raft.designsystem.components.raftClickable
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke

/*
 * Top bar for the right-to-left pushed in-app browser (#proj-mobile:#398, #proj-workspace:#412 /
 * raft-design #1417), aligned with Feishu/WeChat mini-app navigation (no redundant bottom toolbar):
 * - Installed Raft App page: leading ← steps back through WebView history first and suspends to
 *   the right-edge floating pill at root (a compact × appears beside ← once web history exists so
 *   deep pages can return to Raft in one tap; in wide side column, ← appears when canGoBack and
 *   trailing × always exits the column). Center shows App icon + name + "Signed in as"; trailing
 *   shows [N] multi-page switcher trigger when ≥2 pages are open and ⋮ overflow menu (Reload,
 *   Forward to Raft, Share, Copy link, Open in browser ↗, Close page).
 * - Cross-origin outbound child page: leading ← steps back through WebView history then returns to
 *   parent App (with compact × quick-return beside ← when deep in history), center displays ONLY
 *   the start-truncated LTR domain (no App branding or Raft sign-in status), trailing shows ⋮ menu.
 */

internal const val RAFT_APP_BROWSER_TOP_BAR_TAG = "raft_app_browser_top_bar"
internal const val RAFT_APP_BROWSER_BACK_NAV_TAG = "raft_app_browser_back_nav"
internal const val RAFT_APP_BROWSER_EXIT_NAV_TAG = "raft_app_browser_exit_nav"
internal const val RAFT_APP_BROWSER_SWITCHER_BUTTON_TAG = "raft_app_browser_switcher_button"
internal const val RAFT_WEB_PANEL_MORE_BUTTON_TAG = "raft_web_panel_more_button"
internal const val RAFT_WEB_PANEL_HOST_LABEL_TAG = "raft_web_panel_host_label"
internal const val RAFT_WEB_PANEL_APP_NAME_TAG = "raft_web_panel_app_name"
internal const val RAFT_WEB_PANEL_SIGNED_IN_LABEL_TAG = "raft_web_panel_signed_in_label"

/** Text layout policy for a site domain vs. ordinary title (botiverse/slock #16701 / #16703). */
internal data class RaftSiteDomainTextSpec(
    val overflow: TextOverflow,
    val textDirection: TextDirection,
    val maxLines: Int,
    val softWrap: Boolean,
)

/**
 * Domains truncate from their START ([TextOverflow.StartEllipsis]) in left-to-right order so the
 * trailing registrable domain is never hidden (`accounts.google.com.….evil-example.net` displays
 * `…evil-example.net`, never `accounts.google.com…`).
 */
internal fun raftSiteDomainTextSpec(isDomain: Boolean): RaftSiteDomainTextSpec =
    if (isDomain) {
        RaftSiteDomainTextSpec(
            overflow = TextOverflow.StartEllipsis,
            textDirection = TextDirection.Ltr,
            maxLines = 1,
            softWrap = false,
        )
    } else {
        RaftSiteDomainTextSpec(
            overflow = TextOverflow.Ellipsis,
            textDirection = TextDirection.Unspecified,
            maxLines = 1,
            softWrap = false,
        )
    }

/**
 * Renders a site's domain where Raft tells the user which site a page is on (mirrors web
 * `SiteDomain.tsx`, botiverse/slock #16701 / #16703): single-line LTR text that truncates from the
 * start ([TextOverflow.StartEllipsis]) so the real trailing domain is never cut off.
 */
@Composable
internal fun RaftSiteDomainText(
    host: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val spec = raftSiteDomainTextSpec(isDomain = true)
    RaftText(
        text = host,
        modifier = modifier,
        style = style.copy(textDirection = spec.textDirection),
        color = color,
        maxLines = spec.maxLines,
        softWrap = spec.softWrap,
        overflow = spec.overflow,
    )
}

/**
 * Top bar for both installed Raft App stack pages and cross-origin unauthenticated child pages.
 */
@Composable
internal fun RaftAppBrowserTopBar(
    isRaftApp: Boolean,
    isOutboundChild: Boolean,
    currentUrl: String,
    appName: String?,
    appLogoUrl: String?,
    signedInAsName: String?,
    onBack: () -> Unit,
    onClosePage: () -> Unit,
    onReload: () -> Unit,
    onShare: () -> Unit,
    onCopyLink: () -> Unit,
    onOpenInBrowser: (() -> Unit)?,
    modifier: Modifier = Modifier,
    inColumn: Boolean = false,
    canGoBack: Boolean = false,
    onExitToHost: (() -> Unit)? = null,
    openTabCount: Int = 1,
    onOpenSwitcher: (() -> Unit)? = null,
    onForwardToRaft: (() -> Unit)? = null,
) {
    val colors = RaftTheme.colors
    var menuExpanded by remember { mutableStateOf(false) }
    val showAppIdentity = isRaftApp && !isOutboundChild
    val showLeadingBack = !inColumn || isOutboundChild || canGoBack
    val showLeadingQuickExit = (!inColumn || isOutboundChild) && canGoBack && onExitToHost != null
    val displayHost = remember(currentUrl) { raftMobileWebPanelDisplayHost(currentUrl) }
    val cleanAppName = appName?.trim()?.takeIf { it.isNotEmpty() }
    val displayAppName = cleanAppName ?: displayHost
    val isFallbackHost = cleanAppName == null && displayHost.isNotBlank()
    val menuActions =
        remember(showAppIdentity, isOutboundChild) {
            raftMobileWebPanelMenuActions(isRaftApp = showAppIdentity, isOutboundChild = isOutboundChild)
        }

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(colors.layerCanvas)
                .testTag(RAFT_APP_BROWSER_TOP_BAR_TAG),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(RaftTheme.controls.headHeight)
                    .padding(horizontal = RaftBackButtonDefaults.EdgeInset),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showLeadingBack) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RaftBackButton(
                        onClick = onBack,
                        label = stringResource(Res.string.common_back),
                        modifier = Modifier.testTag(RAFT_APP_BROWSER_BACK_NAV_TAG),
                    )
                    if (showLeadingQuickExit) {
                        RaftCloseButton(
                            onClick = onExitToHost!!,
                            label = stringResource(Res.string.common_close),
                            size = RaftCloseButtonSize.Bar,
                            modifier = Modifier.testTag(RAFT_APP_BROWSER_EXIT_NAV_TAG),
                        )
                    }
                }
            }
            Box(
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (showAppIdentity) {
                    RaftMobileWebPanelAppIdentity(
                        appName = displayAppName,
                        appLogoUrl = appLogoUrl,
                        signedInAsName = signedInAsName,
                        isFallbackHost = isFallbackHost,
                    )
                } else if (displayHost.isNotBlank()) {
                    RaftSiteDomainText(
                        host = displayHost,
                        modifier = Modifier.testTag(RAFT_WEB_PANEL_HOST_LABEL_TAG),
                        style = RaftTheme.typography.callout.copy(fontWeight = FontWeight.Medium),
                        color = colors.foreground,
                    )
                } else {
                    RaftText(
                        text = stringResource(Res.string.in_app_browser_title),
                        modifier = Modifier.testTag(RAFT_WEB_PANEL_HOST_LABEL_TAG),
                        style = RaftTheme.typography.callout.copy(fontWeight = FontWeight.Medium),
                        color = colors.foreground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showAppIdentity && openTabCount >= 2 && onOpenSwitcher != null) {
                    RaftAppBrowserTabCountButton(
                        count = openTabCount,
                        onClick = onOpenSwitcher,
                    )
                }
                Box {
                    RaftIconButton(
                        onClick = { menuExpanded = true },
                        tooltip = stringResource(Res.string.common_more_actions),
                        variant = RaftButtonVariant.Ghost,
                        size = RaftIconButtonSize.Lg,
                        modifier = Modifier.testTag(RAFT_WEB_PANEL_MORE_BUTTON_TAG),
                    ) {
                        RaftSlotIcon(RaftLucideIconName.EllipsisVertical)
                    }
                    RaftDropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                        alignEnd = true,
                    ) {
                        menuActions.forEach { action ->
                            when (action) {
                                RaftMobileWebPanelMenuAction.Reload ->
                                    RaftMenuItem(
                                        text = stringResource(Res.string.in_app_browser_reload),
                                        leadingIcon = { RaftSlotIcon(RaftLucideIconName.RefreshCw) },
                                        onClick = {
                                            menuExpanded = false
                                            onReload()
                                        },
                                    )
                                RaftMobileWebPanelMenuAction.ForwardToRaft -> {
                                    if (onForwardToRaft != null) {
                                        RaftMenuItem(
                                            text = stringResource(Res.string.mobile_web_panel_forward_to_raft),
                                            leadingIcon = { RaftSlotIcon(RaftLucideIconName.Forward) },
                                            onClick = {
                                                menuExpanded = false
                                                onForwardToRaft()
                                            },
                                        )
                                    }
                                }
                                RaftMobileWebPanelMenuAction.Share ->
                                    RaftMenuItem(
                                        text = stringResource(Res.string.common_share),
                                        leadingIcon = { RaftSlotIcon(RaftLucideIconName.Share) },
                                        onClick = {
                                            menuExpanded = false
                                            onShare()
                                        },
                                    )
                                RaftMobileWebPanelMenuAction.CopyLink ->
                                    RaftMenuItem(
                                        text = stringResource(Res.string.thread_link_menu_copy_link),
                                        leadingIcon = { RaftSlotIcon(RaftLucideIconName.Link) },
                                        onClick = {
                                            menuExpanded = false
                                            onCopyLink()
                                        },
                                    )
                                RaftMobileWebPanelMenuAction.OpenInBrowser -> {
                                    if (onOpenInBrowser != null) {
                                        RaftMenuSeparator()
                                        RaftMenuItem(
                                            text = stringResource(Res.string.thread_link_menu_open_in_browser),
                                            leadingIcon = { RaftSlotIcon(RaftLucideIconName.Globe) },
                                            trailingIcon = { RaftSlotIcon(RaftLucideIconName.ArrowUpRight) },
                                            onClick = {
                                                menuExpanded = false
                                                onOpenInBrowser()
                                            },
                                        )
                                    }
                                }
                                RaftMobileWebPanelMenuAction.ClosePage -> {
                                    RaftMenuSeparator()
                                    RaftMenuItem(
                                        text = stringResource(Res.string.in_app_browser_close_page),
                                        leadingIcon = { RaftSlotIcon(RaftLucideIconName.X) },
                                        onClick = {
                                            menuExpanded = false
                                            onClosePage()
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                if (inColumn && !isOutboundChild) {
                    RaftCloseButton(
                        onClick = onExitToHost ?: onBack,
                        label = stringResource(Res.string.common_close),
                        size = RaftCloseButtonSize.Bar,
                    )
                }
            }
        }
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(RaftStroke.Hairline)
                    .background(colors.lineHairline),
        )
    }
}

/** Compact `[N]` badge trigger in the App top bar opening the multi-page switcher sheet. */
@Composable
private fun RaftAppBrowserTabCountButton(
    count: Int,
    onClick: () -> Unit,
) {
    val colors = RaftTheme.colors
    val label = stringResource(Res.string.in_app_browser_open_pages_a11y, count)
    val shape = RoundedCornerShape(RaftRadius.Chip)
    Box(
        modifier =
            Modifier
                .heightIn(min = 32.dp) // app-only: top-bar tab count pill target
                .widthIn(min = 32.dp)
                .testTag(RAFT_APP_BROWSER_SWITCHER_BUTTON_TAG)
                .semantics { contentDescription = label }
                .clip(shape)
                .background(colors.fillSubtle, shape)
                .border(RaftStroke.Hairline, colors.lineSeam, shape)
                .raftClickable(onClick = onClick, shape = shape, tier = RaftHoverTier.Subtle)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        RaftText(
            text = count.toString(),
            style = RaftTheme.typography.footnote.copy(fontWeight = FontWeight.SemiBold),
            color = colors.foreground,
            maxLines = 1,
        )
    }
}

@Composable
private fun RaftMobileWebPanelAppIdentity(
    appName: String,
    appLogoUrl: String?,
    signedInAsName: String?,
    isFallbackHost: Boolean = false,
) {
    val colors = RaftTheme.colors
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RaftConnectedAppLogo(
                name = appName,
                logoUrl = appLogoUrl,
                size = RaftAvatarSize.Xs,
            )
            if (isFallbackHost) {
                RaftSiteDomainText(
                    host = appName,
                    modifier = Modifier.weight(1f, fill = false).testTag(RAFT_WEB_PANEL_APP_NAME_TAG),
                    style = RaftTheme.typography.callout.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.foreground,
                )
            } else {
                RaftText(
                    text = appName,
                    modifier = Modifier.testTag(RAFT_WEB_PANEL_APP_NAME_TAG),
                    style = RaftTheme.typography.callout.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.foreground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        val cleanUser = signedInAsName?.trim()?.takeIf { it.isNotEmpty() }
        if (cleanUser != null) {
            RaftText(
                text = stringResource(Res.string.mobile_web_panel_signed_in_as, cleanUser),
                modifier = Modifier.testTag(RAFT_WEB_PANEL_SIGNED_IN_LABEL_TAG),
                style = RaftTheme.typography.caption,
                color = colors.foregroundMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
