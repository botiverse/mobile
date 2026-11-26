package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftSlotIcon
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftAvatarSize
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/*
 * Visual chrome for the unified mobile web panel (#proj-workspace:#397, Option A per
 * kirby-link-sheet-v2.html):
 * - Dimmed 1-line source strip (visible in the 88% default detent; tapping collapses keeping page alive).
 * - Top drag handle lip.
 * - Bottom bar: leading RaftCloseButton (×), center identity (Layer 3 host OR Layer 2 App icon +
 *   name + "Signed in as"), trailing history Back/Forward buttons (`<` / `>`) and `⋮` menu (Share,
 *   Copy link, and Layer 3 only Open in browser ↗).
 * - Keep-open chip shown under the source message (or floating when scrolled off-screen / non-message).
 */

internal const val RAFT_WEB_PANEL_SOURCE_STRIP_TAG = "raft_web_panel_source_strip"
internal const val RAFT_WEB_PANEL_DRAG_HANDLE_TAG = "raft_web_panel_drag_handle"
internal const val RAFT_WEB_PANEL_BOTTOM_BAR_TAG = "raft_web_panel_bottom_bar"
internal const val RAFT_WEB_PANEL_CLOSE_BUTTON_TAG = "raft_web_panel_close_button"
internal const val RAFT_WEB_PANEL_BACK_BUTTON_TAG = "raft_web_panel_back_button"
internal const val RAFT_WEB_PANEL_FORWARD_BUTTON_TAG = "raft_web_panel_forward_button"
internal const val RAFT_WEB_PANEL_MORE_BUTTON_TAG = "raft_web_panel_more_button"
internal const val RAFT_WEB_PANEL_HOST_LABEL_TAG = "raft_web_panel_host_label"
internal const val RAFT_WEB_PANEL_APP_NAME_TAG = "raft_web_panel_app_name"
internal const val RAFT_WEB_PANEL_SIGNED_IN_LABEL_TAG = "raft_web_panel_signed_in_label"
internal const val RAFT_WEB_PANEL_KEEP_OPEN_CHIP_TAG = "raft_web_panel_keep_open_chip"
internal const val RAFT_WEB_PANEL_FLOATING_KEEP_OPEN_CHIP_TAG = "raft_web_panel_floating_keep_open_chip"

/** Top grabber lip of the bottom-rising panel; dragging vertically moves between detents. */
@Composable
internal fun RaftMobileWebPanelDragLip(
    onDragDelta: (Float) -> Unit,
    onDragEnd: (velocityY: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RaftTheme.colors
    var lastDelta by remember { mutableStateOf(0f) }
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(22.dp)
                .testTag(RAFT_WEB_PANEL_DRAG_HANDLE_TAG)
                .pointerInput(onDragDelta, onDragEnd) {
                    detectVerticalDragGestures(
                        onDragStart = { lastDelta = 0f },
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            lastDelta = dragAmount
                            onDragDelta(dragAmount)
                        },
                        onDragEnd = { onDragEnd(lastDelta * 45f) },
                        onDragCancel = { onDragEnd(0f) },
                    )
                },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .width(36.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(colors.foregroundHint.copy(alpha = 0.35f)),
        )
    }
}

/**
 * Dimmed 1-line source context strip shown in the top ~12% above the 88% panel.
 * Tapping anywhere on it collapses the panel back to the conversation keeping the WebView alive.
 */
@Composable
internal fun RaftMobileWebPanelSourceStrip(
    source: RaftMobileWebPanelSource?,
    topSafeArea: Dp,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RaftTheme.colors
    val a11yLabel =
        if (source is RaftMobileWebPanelSource.Message) {
            stringResource(Res.string.mobile_web_panel_return_to_conversation)
        } else {
            stringResource(Res.string.mobile_web_panel_return_to_page)
        }
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(top = topSafeArea)
                .testTag(RAFT_WEB_PANEL_SOURCE_STRIP_TAG)
                .semantics { contentDescription = a11yLabel }
                .raftClickable(onClick = onCollapse, tier = RaftHoverTier.Subtle)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        when (source) {
            is RaftMobileWebPanelSource.Message -> {
                Row(
                    modifier = Modifier.fillMaxWidth().alpha(0.72f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RaftAppAvatar(
                        kind = source.authorAvatarKind,
                        avatarWireValue = source.authorAvatarWireValue,
                        label = source.authorName,
                        avatarSize = RaftAvatarSize.Xs.edge,
                        cacheSubject = source.authorCacheSubject,
                    )
                    RaftText(
                        text = source.authorName,
                        style = RaftTheme.typography.footnote.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.foreground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (source.previewText.isNotBlank()) {
                        RaftText(
                            text = source.previewText,
                            modifier = Modifier.weight(1f),
                            style = RaftTheme.typography.footnote,
                            color = colors.foregroundMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    if (source.timestampLabel.isNotBlank()) {
                        RaftRelativeTimestampText(
                            value = source.timestampLabel,
                            displayGroup = RaftTimestampDisplayGroup.Conversation,
                            style = RaftTheme.typography.caption,
                            color = colors.foregroundHint,
                            maxLines = 1,
                            toggleEnabled = false,
                        )
                    }
                }
            }
            is RaftMobileWebPanelSource.Surface -> {
                RaftText(
                    text = source.label,
                    modifier = Modifier.alpha(0.72f),
                    style = RaftTheme.typography.footnote.copy(fontWeight = FontWeight.Medium),
                    color = colors.foregroundMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            null -> {
                RaftText(
                    text = stringResource(Res.string.mobile_web_panel_return_to_conversation),
                    modifier = Modifier.alpha(0.72f),
                    style = RaftTheme.typography.footnote.copy(fontWeight = FontWeight.Medium),
                    color = colors.foregroundMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Mobile web panel bottom bar:
 * - Leading: `×` (`RaftCloseButton`), isolated from history buttons to prevent accidental close.
 * - Center: Layer 3 formatted ASCII host (`www.` stripped, IDN as `xn--`), OR Layer 2 App icon +
 *   App name + subtle "Signed in as <name>" caption when available.
 * - Trailing: history Back (`<`) and Forward (`>`) buttons disabled when no history exists in that
 *   direction, followed by `⋮` overflow menu (Share, Copy link, and for Layer 3 only: Open in browser ↗).
 */
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

@Composable
internal fun RaftMobileWebPanelBottomBar(
    isRaftApp: Boolean,
    currentUrl: String,
    appName: String?,
    appLogoUrl: String?,
    signedInAsName: String?,
    onClose: () -> Unit,
    onShare: () -> Unit,
    onCopyLink: () -> Unit,
    onOpenInBrowser: (() -> Unit)?,
    modifier: Modifier = Modifier,
    canGoBack: Boolean = false,
    canGoForward: Boolean = false,
    onGoBack: () -> Unit = {},
    onGoForward: () -> Unit = {},
) {
    val colors = RaftTheme.colors
    var menuExpanded by remember { mutableStateOf(false) }
    val displayHost = remember(currentUrl) { raftMobileWebPanelDisplayHost(currentUrl) }
    val cleanAppName = appName?.trim()?.takeIf { it.isNotEmpty() }
    val displayAppName = cleanAppName ?: displayHost
    val isFallbackHost = cleanAppName == null && displayHost.isNotBlank()
    val menuActions = remember(isRaftApp) { raftMobileWebPanelMenuActions(isRaftApp) }

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(colors.layerCanvas)
                .testTag(RAFT_WEB_PANEL_BOTTOM_BAR_TAG),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(RaftStroke.Hairline)
                    .background(colors.lineHairline),
        )
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(52.dp) // app-only: kirby-link-sheet-v2 mobile web panel bottom bar
                    .padding(horizontal = RaftBackButtonDefaults.EdgeInset),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RaftCloseButton(
                onClick = onClose,
                label = stringResource(Res.string.common_close),
                size = RaftCloseButtonSize.Bar,
                modifier = Modifier.testTag(RAFT_WEB_PANEL_CLOSE_BUTTON_TAG),
            )
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                if (isRaftApp) {
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                RaftIconButton(
                    onClick = onGoBack,
                    tooltip = stringResource(Res.string.common_back),
                    variant = RaftButtonVariant.Ghost,
                    size = RaftIconButtonSize.Lg,
                    enabled = canGoBack,
                    modifier = Modifier.testTag(RAFT_WEB_PANEL_BACK_BUTTON_TAG),
                ) {
                    RaftSlotIcon(RaftLucideIconName.ChevronLeft)
                }
                RaftIconButton(
                    onClick = onGoForward,
                    tooltip = stringResource(Res.string.in_app_browser_forward),
                    variant = RaftButtonVariant.Ghost,
                    size = RaftIconButtonSize.Lg,
                    enabled = canGoForward,
                    modifier = Modifier.testTag(RAFT_WEB_PANEL_FORWARD_BUTTON_TAG),
                ) {
                    RaftSlotIcon(RaftLucideIconName.ChevronRight)
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
                            }
                        }
                    }
                }
            }
        }
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

/**
 * Subtle tappable keep-open chip (`"{host} · 页面仍打开"` / `"{host} · Page still open"`) shown
 * under the source message while the panel is collapsed & alive, or floating when the source
 * message is scrolled off-screen / opened from a non-message surface.
 */
@Composable
internal fun RaftMobileWebPanelKeepOpenChip(
    label: String,
    onClick: () -> Unit,
    elevated: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val colors = RaftTheme.colors
    val dark = RaftTheme.isDark
    val shape = RoundedCornerShape(RaftRadius.Chip)
    val surfaceColor = if (elevated) colors.layerPopover else colors.fillSubtle
    val borderColor = if (elevated) colors.lineSeam else colors.lineHairline
    val displayText =
        if (label.isNotBlank()) {
            stringResource(Res.string.mobile_web_panel_page_still_open, label)
        } else {
            stringResource(Res.string.in_app_browser_title)
        }
    Row(
        modifier =
            modifier
                .then(if (elevated) Modifier.shadow(elevation = if (dark) 8.dp else 4.dp, shape = shape) else Modifier)
                .clip(shape)
                .background(surfaceColor, shape)
                .border(RaftStroke.Hairline, borderColor, shape)
                .raftClickable(
                    onClick = onClick,
                    shape = shape,
                    tier = RaftHoverTier.Subtle,
                )
                .padding(horizontal = 10.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
            RaftSlotIcon(RaftLucideIconName.AppWindow, color = colors.foregroundMuted)
        }
        RaftText(
            text = displayText,
            style = RaftTheme.typography.footnote.copy(fontWeight = FontWeight.Medium),
            color = colors.foregroundMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Renders the inline keep-open chip directly under the message that opened a collapsed kept-alive
 * web panel. Subscribes only to the matching [messageId] via `distinctUntilChanged`, so unrelated
 * message rows in a timeline never recompose when web panel state changes.
 */
@Composable
internal fun ThreadMessageKeepOpenBadgeSlot(
    messageId: String,
    modifier: Modifier = Modifier,
) {
    val host = LocalRaftMobileWebPanelHost.current ?: return
    val matchingFlow =
        remember(host, messageId) {
            host.coordinator.session
                .map { session ->
                    session?.takeIf {
                        it.ownerHostId == host.hostId &&
                            it.isCollapsedKeptAlive &&
                            (it.source as? RaftMobileWebPanelSource.Message)?.messageId == messageId
                    }
                }
                .distinctUntilChanged()
        }
    val session by matchingFlow.collectAsState(initial = null)
    val activeSession = session ?: return

    DisposableEffect(host, activeSession.sessionId) {
        onDispose { host.reportInlineBadgeVisibility(activeSession.sessionId, visible = false) }
    }
    RaftMobileWebPanelKeepOpenChip(
        label = activeSession.keepOpenLabel,
        onClick = { host.reopen(activeSession.sessionId) },
        elevated = false,
        modifier =
            modifier
                .testTag(RAFT_WEB_PANEL_KEEP_OPEN_CHIP_TAG)
                .onGloballyPositioned { coordinates ->
                    val visible = coordinates.isAttached && !coordinates.boundsInWindow().isEmpty
                    host.reportInlineBadgeVisibility(activeSession.sessionId, visible = visible)
                },
    )
}
