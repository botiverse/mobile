package build.raft.app

import build.raft.designsystem.components.raftSlot
import build.raft.designsystem.theme.RaftScale
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.components.RaftAvatarSize
import build.raft.designsystem.components.LocalRaftBarButtons
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.CompositionLocalProvider
import build.raft.designsystem.components.RaftStatusBarBackdropClaim
import build.raft.app.resources.*
import build.raft.app.components.RaftIconSpinner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import build.raft.designsystem.components.LocalRaftPaneGridHeadTabs
import build.raft.designsystem.components.RaftBackButton
import build.raft.designsystem.components.RaftCloseButton
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftIconButtonSize
import build.raft.designsystem.components.RaftPaneGridHeadRowTabs
import build.raft.designsystem.components.raftCopyable
import build.raft.designsystem.components.raftPaneGridFitHead
import build.raft.designsystem.components.raftSafeAreaHorizontalPadding
import build.raft.designsystem.components.RaftBackButtonDefaults

// raft-ui `sidebarSectionDisclosure`: a 14px chevron (`size-3.5`), `gap-1.5`, all in ink-3.
private val SectionChevronSize = 14.dp
private val LoadingSpinnerSize = 14.dp
private val SectionHeaderActionSize = 30.dp
private val SectionHeaderActionIconSize = 16.dp
internal val RaftCollapsibleSectionContentGap = 6.dp
// raft-ui sidebarSectionCount: `font-mono text-mono-meta font-normal` (12/16 Space Mono).
internal val RaftCollapsibleSectionCountTextStyle: TextStyle
    // Getter: the UI-text locale and rendering defaults can change at runtime.
    get() =
        RaftType.Caption.copy(fontFamily = RaftMonoFamily, letterSpacing = 0.sp)
// raft-ui `sidebarSectionCount`: `text-foreground-hint`.
internal val RaftCollapsibleSectionCountColor: Color get() = RaftColor.ForegroundHint
// raft-ui top-level page head: no icon sticker (the selected tab already shows it); the title
// starts on the 16dp phone margin, like Activity.
internal val RaftMainTabHeaderStartPadding = 16.dp
// Task #179: getter so the header follows the Settings font option.
internal val RaftMainTabHeaderTitleStyle: TextStyle
    // Web TabRootBar: `text-headline` 17/22 700 (it was 16/20).
    get() = RaftType.Headline
internal val RaftTopPageHeaderContentStartPadding = 18.dp
internal val RaftTopPageHeaderContentSpacing = 12.dp
// After a 44dp Back: its 22dp arrow keeps 11dp of its own inset, so the title sits 17dp past it.
private val RaftTopPageHeaderBackContentSpacing = 6.dp
internal val RaftTopPageHeaderContentEndPadding = 16.dp
// raft-ui `PanelHeaderContent` `md:gap-2`: between a one-line head's title and its meta.
internal val RaftPanelHeadInlineMetaGap = 8.dp

internal object RaftCollapsibleSectionTokens {
    const val AnimationDurationMillis: Int = 180
    const val AnimationDurationSeconds: Float = AnimationDurationMillis / 1000f
}

internal data class RaftTopPageHeaderFrameMetrics(
    val totalHeight: Dp,
    val contentHeight: Dp,
)

internal fun raftTopPageHeaderFrameMetrics(
    topSafeArea: Dp,
    headerHeight: Dp = RaftAppSize.PageHeaderHeight,
): RaftTopPageHeaderFrameMetrics {
    val boundedHeaderHeight = headerHeight.coerceAtLeast(0.dp)
    val contentHeight = (boundedHeaderHeight - RaftAppStroke.Default).coerceAtLeast(0.dp)
    return RaftTopPageHeaderFrameMetrics(
        totalHeight = topSafeArea.coerceAtLeast(0.dp) + boundedHeaderHeight,
        contentHeight = topSafeArea.coerceAtLeast(0.dp) + contentHeight,
    )
}

/**
 * A top bar's leading Back, for heads that place the Back themselves (the member profile). In a wide side pane
 * ([LocalRaftSidePanePage]) only over a page of the pane's own stack, where it goes back one page in the pane; the
 * pane's first page has none ([raftSidePaneShowsBack]), and its head ends in [RaftSidePaneCloseButton] instead.
 * [RaftTopPageHeader] and [RaftPanelBackHeader] decide their Back the same way.
 */
@Composable
internal fun RaftPaneAwareBackButton(onClick: () -> Unit) {
    if (raftSidePaneShowsBack()) {
        RaftBackButton(onClick = onClick, label = stringResource(Res.string.common_back))
    }
}

/**
 * A wide side pane's Close (×, [RaftCloseButton]), the last control of its page's head (web PanelHeader actions: …,
 * ⋯, Expand, Close): closes the pane, and only it, the conversation beside it staying ([RaftSidePanePage.close]).
 * Nothing outside a side pane.
 */
@Composable
internal fun RaftSidePaneCloseButton(onBeforeClose: (() -> Unit)? = null) {
    val pane = LocalRaftSidePanePage.current ?: return
    RaftCloseButton(
        onClick = {
            onBeforeClose?.invoke()
            pane.close()
        },
        label = stringResource(Res.string.common_close),
    )
}

@Composable
internal fun RaftTopPageHeader(
    title: String,
    // The design-system top-bar Back ([RaftBackButton], 44dp), in place of the header icon.
    onBack: (() -> Unit)? = null,
    subtitle: String? = null,
    iconName: RaftLucideIconName? = null,
    iconBackground: Color = RaftColor.Brand,
    // raft-ui 0.6.0: a phone section head sits on the frame layer (Source Yellow in
    // light, the dark frame in dark) — the yellow marks where you are.
    backgroundColor: Color = RaftColor.Frame,
    modifier: Modifier = Modifier,
    applyTopSafeArea: Boolean = true,
    topSafeAreaOverride: Dp? = null,
    // 56 with a finger, 44 under a mouse or trackpad (sizing v12, raft-ui 0.65 desktop density).
    headerHeight: Dp = RaftTheme.controls.headHeight,
    // [onBack] sets the Back button's 3dp edge inset and the 6dp gap after it (none on a wide side pane's first page,
    // which has no Back: raftSidePaneShowsBack).
    contentStartPadding: Dp = if (onBack != null && raftSidePaneShowsBack()) RaftBackButtonDefaults.EdgeInset else RaftTopPageHeaderContentStartPadding,
    // In a wide side pane the pane's Close ends the head: its glyph on the 16dp line, as Back's at the start.
    contentEndPadding: Dp = if (raftSidePaneHeadCloses(onBack)) RaftBackButtonDefaults.EdgeInset else RaftTopPageHeaderContentEndPadding,
    contentSpacing: Dp = if (onBack != null && raftSidePaneShowsBack()) RaftTopPageHeaderBackContentSpacing else RaftTopPageHeaderContentSpacing,
    // Web PanelHeader: `text-headline` 17/22 700 (it was 18/22).
    titleStyle: TextStyle = RaftType.Headline,
    refreshing: Boolean = false,
    leadingContent: (@Composable BoxScope.() -> Unit)? = null,
    titleLeadingContent: (@Composable RowScope.() -> Unit)? = null,
    titleContent: (@Composable RowScope.() -> Unit)? = null,
    trailingContent: (@Composable BoxScope.() -> Unit)? = null,
    onTitleClick: (() -> Unit)? = null,
    showBodyTopBanner: Boolean = true,
    // raft-ui `PanelHeaderContent` from `md` up (`md:flex-row md:items-baseline md:gap-2`): the
    // [subtitle] sits after the title on its baseline instead of under it, and gives up its room
    // first (`md:shrink-1000`). The wide layout's column heads (raft-design desktop-density rule 2:
    // "by touch from 768px a head is one line too").
    subtitleInline: Boolean = RaftTheme.scale == RaftScale.Wide,
    // Long-press copy (raftCopyable) of the title when it is an identifier (a file name).
    titleCopyText: String? = null,
    // PROTOTYPE (native nav bar): what a host-drawn UINavigationBar shows for this head.
    nativeSubtitle: String? = subtitle,
    nativeActions: List<RaftNativeNavBarAction> = emptyList(),
    nativeTitleExtras: RaftNativeNavBarTitleExtras = RaftNativeNavBarTitleExtras(),
    nativeTitleMenu: List<RaftNativeNavBarAction> = emptyList(),
    nativeOnTitleClick: (() -> Unit)? = null,
    // A tap on the bar's subtitle line (a reply thread's conversation name: View in Channel).
    nativeOnSubtitleClick: (() -> Unit)? = null,
    // Keep prefilled bar actions while a temporary LoadingShell stands in ahead of FeatureSurfaces.
    nativePreservePrefilledActions: Boolean = false,
    // PROTOTYPE: the head's Back when it draws it itself (in [titleLeadingContent]).
    nativeOnBack: (() -> Unit)? = null,
) {
    val topSafeArea =
        (topSafeAreaOverride ?: if (applyTopSafeArea) raftTopSafeAreaPadding() else 0.dp)
            .coerceAtLeast(0.dp)
    // A wide side pane (LocalRaftSidePanePage, web layout-memory "Close and Back"): Back only over a page of the pane's
    // own stack, and the pane's Close ends a head that has a way out of its page.
    val shownBack = onBack?.takeIf { raftSidePaneShowsBack() }
    val paneCloses = raftSidePaneHeadCloses(onBack)
    // Rule 4 crowded split view: row tabs stand in this header's title slot (web PanelHeader + RowTabs).
    val headRowTabs = LocalRaftPaneGridHeadTabs.current
    // iOS native navigation (mobile_ios_native_nav_v1): the host's UINavigationBar draws this head
    // (title, Back, actions). The page's top safe area already includes the bar, so only that space
    // is kept. A head with Compose buttons but no bar buttons mapped keeps its Compose head (and
    // the host hides its bar) instead of losing those buttons.
    // iPhone Duo side column: the column holds Back and the actions, so the Compose head stays.
    val nativeHeadMapped = headRowTabs == null && !raftSideColumnActive() && raftNativeNavBarHeadMapped(trailingContent != null || paneCloses, nativeActions)
    if (!nativeHeadMapped) RaftNativeNavBarDecline()
    // The title's channel tile / DM avatar as this head draws it, captured offscreen for the bar.
    val nativeTitleLeading = nativeTitleExtras.leading
    var nativeTitleImage by remember(nativeTitleLeading) { mutableStateOf<RaftNativeTabBarAvatar?>(null) }
    if (
        nativeHeadMapped &&
        raftNativeNavBarPublish(
            title = title,
            subtitle = nativeSubtitle,
            actions = nativeActions,
            extras = nativeTitleExtras.copy(refreshing = nativeTitleExtras.refreshing || refreshing),
            titleMenu = nativeTitleMenu,
            onBack = onBack ?: nativeOnBack,
            onTitleClick = onTitleClick ?: nativeOnTitleClick,
            onSubtitleClick = nativeOnSubtitleClick,
            titleImage = nativeTitleImage,
            preservePrefilledActions = nativePreservePrefilledActions,
        )
    ) {
        RaftNativeNavBarHeadSpacer(
            bridge = LocalRaftNativeNavBar.current!!,
            modifier = modifier.fillMaxWidth(),
            // The page's top safe area includes the host bar; a head below the top keeps none.
            liveTop = topSafeArea,
        ) {
            if (nativeTitleLeading != null) {
                RaftNativeNavBarTitleImageCapture(nativeTitleLeading) { nativeTitleImage = it }
            }
        }
        if (showBodyTopBanner) {
            RaftAppShellBodyTopBanner()
        }
        return
    }
    // iPhone Duo (raft-design #859): with the system's side column, Back is the column's first
    // button, and the header keeps the title from the 16dp margin.
    val backInColumn = shownBack != null && raftSideColumnControls(back = raftSideColumnBackAction(shownBack))
    // In a pane of the wide row laid out wider than it shows (#189 rule 2), the head fits what shows: never clipped.
    Column(modifier = Modifier.raftPaneGridFitHead().fillMaxWidth()) {
        RaftTopPageHeaderFrame(
            modifier = modifier,
            backgroundColor = backgroundColor,
            topSafeArea = topSafeArea,
            headerHeight = headerHeight,
            contentStartPadding = if (backInColumn) RaftTopPageHeaderContentStartPadding else contentStartPadding,
            contentEndPadding = contentEndPadding,
            contentSpacing = if (backInColumn) RaftTopPageHeaderContentSpacing else contentSpacing,
        ) {
            when {
                shownBack != null && !backInColumn -> RaftPaneAwareBackButton(onClick = shownBack)
                headRowTabs != null -> Unit
                leadingContent != null ->
                    Box(
                        modifier = Modifier.raftSlot("panel-header-icon").size(raftHeaderIconTileSize()),
                        contentAlignment = Alignment.Center,
                        content = leadingContent
                    )
                iconName != null ->
                    Box(
                        modifier =
                            Modifier
                                .raftSlot("panel-header-icon")
                                .size(raftHeaderIconTileSize())
                                // raft `PanelHeaderIcon`: a Source Yellow sticker, sticker radius, ink edge.
                                .background(iconBackground, RoundedCornerShape(RaftAppRadius.Sticker))
                                .border(RaftAppStroke.Control, RaftColor.StickerInk, RoundedCornerShape(RaftAppRadius.Sticker)),
                        contentAlignment = Alignment.Center
                    ) {
                        RaftLucideIcon(
                            iconName,
                            modifier = Modifier.size(raftHeaderIconGlyphSize()),
                            color = raftArgbHex(RaftColor.StickerInk)
                        )
                    }
            }
            titleLeadingContent?.invoke(this)
            if (headRowTabs != null) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    RaftPaneGridHeadRowTabs(
                        tabs = headRowTabs,
                        contentDescription = stringResource(Res.string.pane_grid_row_tabs),
                    )
                }
            } else if (titleContent != null) {
                titleContent.invoke(this)
            } else {
                val titleTapModifier =
                    if (onTitleClick != null) {
                        // Title tap is a hidden shortcut (scroll to top), not a control: no press wash.
                        // raw-click: hidden shortcut, the title's tap scrolls to top; shows no press
                        Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onTitleClick,
                        )
                    } else {
                        // No title action: no clickable at all. A disabled clickable still takes the
                        // tap, so the header's own [modifier] click (Settings' 7-tap Developer entry)
                        // never fired from the title.
                        Modifier
                    }
                val shownSubtitle = subtitle?.takeIf { it.isNotBlank() }
                if (subtitleInline && shownSubtitle != null) {
                    Row(
                        modifier = Modifier.raftSlot("panel-header-content").weight(1f).then(titleTapModifier),
                        horizontalArrangement = Arrangement.spacedBy(RaftPanelHeadInlineMetaGap),
                    ) {
                        // The title is measured first and keeps its room; the meta takes what is left.
                        Text(
                            text = title,
                            modifier = Modifier.raftSlot("panel-title").alignByBaseline().raftCopyable(titleCopyText),
                            style = titleStyle,
                            color = RaftColor.TextBlack,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = shownSubtitle,
                            modifier = Modifier.raftSlot("panel-meta").weight(1f).alignByBaseline(),
                            // raft-ui PanelMeta: font-sans text-caption (12/16 500).
                            style = RaftType.Caption,
                            color = RaftColor.ForegroundHint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier.raftSlot("panel-header-content").weight(1f).then(titleTapModifier),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = title,
                            modifier = Modifier.raftSlot("panel-title").raftCopyable(titleCopyText),
                            style = titleStyle,
                            color = RaftColor.TextBlack,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        shownSubtitle?.let {
                            Text(
                                text = it,
                                modifier = Modifier.raftSlot("panel-meta"),
                                // raft-ui PanelMeta: font-sans text-caption (12/16 500).
                                style = RaftType.Caption,
                                color = RaftColor.ForegroundHint,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
            if (refreshing) {
                RaftHeaderRefreshSlot()
            }
            if (paneCloses) {
                RaftSidePaneHeadEnd(trailingContent)
            } else if (trailingContent != null) {
                Box(Modifier.raftSlot("panel-actions"), content = trailingContent)
            }
        }
        if (showBodyTopBanner) {
            RaftAppShellBodyTopBanner()
        }
    }
}

// raft-ui `panelHeadActions` (`gap-0.5`): between a head's last action and the side pane's Close after it.
private val RaftSidePaneHeadActionGap = 2.dp

/** The end of a wide side pane's head: its own [actions], then the pane's Close ([RaftSidePaneCloseButton]). */
@Composable
private fun RaftSidePaneHeadEnd(actions: (@Composable BoxScope.() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RaftSidePaneHeadActionGap)) {
        if (actions != null) {
            Box(content = actions)
        }
        RaftSidePaneCloseButton()
    }
}

/**
 * raft-ui PanelHeader as a phone column head (web `PanelHeader` with `onMobileBack`): a 56dp
 * canvas row with a 1dp seam, 16dp start edge; the 44dp ghost Back (22dp glyph) is pulled 13dp
 * into that edge (`-ml-[13px]`), then an 8dp gap, the `text-headline` title over the
 * `text-caption` hint meta line.
 *
 * [leadingContent] takes Back's place (a 44dp icon button on the same edge: a side card's
 * collapse chevron); [trailingContent] holds the head's actions, its last 44dp icon button's
 * glyph ending on the 16dp line (web `PanelActions`).
 */
@Composable
internal fun RaftPanelBackHeader(
    title: String,
    subtitle: String?,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    topSafeArea: Dp = 0.dp,
    refreshing: Boolean = false,
    onTitleClick: (() -> Unit)? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable RowScope.() -> Unit)? = null,
    // PROTOTYPE (native nav bar): the head's buttons on the host bar.
    nativeActions: List<RaftNativeNavBarAction> = emptyList(),
) {
    val safeTop = topSafeArea.coerceAtLeast(0.dp)
    // A wide side pane: Back only over a page of the pane's own stack, and the pane's Close ends the head (as
    // RaftTopPageHeader).
    val shownBack = onBack?.takeIf { raftSidePaneShowsBack() }
    val paneCloses = raftSidePaneHeadCloses(onBack)
    val headRowTabs = LocalRaftPaneGridHeadTabs.current
    // iOS native navigation: the host's UINavigationBar draws this head (unless its Compose
    // buttons have no bar buttons mapped, see RaftTopPageHeader).
    val nativeHeadMapped =
        headRowTabs == null && leadingContent == null && !raftSideColumnActive() && raftNativeNavBarHeadMapped(trailingContent != null || paneCloses, nativeActions)
    if (!nativeHeadMapped) RaftNativeNavBarDecline()
    if (
        nativeHeadMapped &&
        raftNativeNavBarPublish(title, subtitle, nativeActions, RaftNativeNavBarTitleExtras(refreshing = refreshing), onBack = onBack, onTitleClick = onTitleClick)
    ) {
        RaftNativeNavBarHeadSpacer(
            bridge = LocalRaftNativeNavBar.current!!,
            modifier = modifier.fillMaxWidth(),
            liveTop = safeTop,
        )
        return
    }
    // iPhone Duo: Back moves to the system's side column (raft-design #859).
    val backInColumn = shownBack != null && raftSideColumnControls(back = raftSideColumnBackAction(shownBack))
    @Suppress("NAME_SHADOWING")
    val onBack = shownBack.takeUnless { backInColumn }
    Box(
        modifier =
            // raft-ui's slot names on the head's parts, for the visual-testing component comparison.
            modifier
                .raftSlot("panel-header")
                // In a pane of the wide row laid out wider than it shows (#189 rule 2): what shows, never clipped.
                .raftPaneGridFitHead()
                .fillMaxWidth()
                .height(safeTop + RaftPanelHeaderHeight)
                .background(RaftColor.Canvas)
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = safeTop)
                    .height(RaftPanelHeaderHeight - RaftAppStroke.Thin)
                    // The canvas and seam run edge to edge; the content stays out of the
                    // horizontal safe area.
                    .raftSafeAreaHorizontalPadding()
                    // The last 44dp icon button's 22dp glyph ends on the 16dp line, like Back's.
                    .padding(
                        start = if (onBack != null || leadingContent != null) RaftBackButtonDefaults.EdgeInset else 16.dp,
                        end = RaftBackButtonDefaults.EdgeInset,
                    ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // The head's icon-lg buttons are bar buttons: 28dp under a mouse or trackpad (sizing v12).
            CompositionLocalProvider(LocalRaftBarButtons provides true) {
                if (onBack != null) {
                    RaftBackButton(onClick = onBack, label = stringResource(Res.string.common_back))
                }
                leadingContent?.invoke()
                if (headRowTabs != null) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        RaftPaneGridHeadRowTabs(
                            tabs = headRowTabs,
                            contentDescription = stringResource(Res.string.pane_grid_row_tabs),
                        )
                    }
                } else {
                    val titleTapModifier =
                        if (onTitleClick != null) {
                            // raw-click: hidden shortcut, the title's tap scrolls to top; shows no press
                            Modifier.weight(1f).clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onTitleClick,
                            )
                        } else {
                            // No title action: no clickable at all, matching RaftTopPageHeader (a
                            // disabled clickable still takes taps and merges child semantics).
                            Modifier.weight(1f)
                        }
                    val shownSubtitle = subtitle?.takeIf { it.isNotBlank() }
                    if (RaftTheme.scale == RaftScale.Wide && shownSubtitle != null) {
                        Row(
                            // Title tap is a hidden shortcut (scroll to top), not a control: no press wash.
                            modifier = Modifier.raftSlot("panel-header-content").then(titleTapModifier),
                            horizontalArrangement = Arrangement.spacedBy(RaftPanelHeadInlineMetaGap),
                        ) {
                            Text(
                                text = title,
                                modifier = Modifier.raftSlot("panel-title").alignByBaseline(),
                                style = RaftPanelHeaderTitleStyle,
                                color = RaftColor.TextBlack,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = shownSubtitle,
                                modifier = Modifier.raftSlot("panel-meta").weight(1f).alignByBaseline(),
                                style = RaftType.Caption,
                                color = RaftColor.ForegroundHint,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    } else {
                        Column(
                            // Title tap is a hidden shortcut (scroll to top), not a control: no press wash.
                            modifier = Modifier.raftSlot("panel-header-content").then(titleTapModifier),
                        ) {
                            Text(
                                text = title,
                                modifier = Modifier.raftSlot("panel-title"),
                                style = RaftPanelHeaderTitleStyle,
                                color = RaftColor.TextBlack,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            shownSubtitle?.let {
                                Text(
                                    text = it,
                                    modifier = Modifier.raftSlot("panel-meta"),
                                    style = RaftType.Caption,
                                    color = RaftColor.ForegroundHint,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
                if (refreshing) {
                    RaftHeaderRefreshSlot()
                }
                if (paneCloses) {
                    // The head's actions, then the side pane's Close.
                    Row(Modifier.raftSlot("panel-actions"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RaftSidePaneHeadActionGap)) {
                        trailingContent?.invoke(this)
                        RaftSidePaneCloseButton()
                    }
                } else if (trailingContent != null) {
                    Row(Modifier.raftSlot("panel-actions"), verticalAlignment = Alignment.CenterVertically, content = trailingContent)
                }
            }
        }
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(RaftAppStroke.Thin)
                    .background(RaftColor.BorderSeam)
                    .align(Alignment.BottomCenter)
        )
    }
}

// 56 with a finger, 44 under a mouse or trackpad (sizing v12, raft-ui 0.65 desktop density).
private val RaftPanelHeaderHeight: Dp
    @Composable @ReadOnlyComposable
    get() = RaftTheme.controls.headHeight


// raft-ui `text-headline`: 17/22 700, no tracking.
private val RaftPanelHeaderTitleStyle: TextStyle
    get() = RaftType.Headline

@Composable
internal fun RaftTopPageHeaderFrame(
    modifier: Modifier = Modifier,
    backgroundColor: Color = RaftColor.Canvas,
    topSafeArea: Dp = 0.dp,
    // 56 with a finger, 44 under a mouse or trackpad (sizing v12, raft-ui 0.65 desktop density).
    headerHeight: Dp = RaftTheme.controls.headHeight,
    contentStartPadding: Dp = RaftTopPageHeaderContentStartPadding,
    contentEndPadding: Dp = RaftTopPageHeaderContentEndPadding,
    contentSpacing: Dp = RaftTopPageHeaderContentSpacing,
    content: @Composable RowScope.() -> Unit,
) {
    // iPhone Duo (raft-design #859, "Foldable phones"): with the system's side column a top-level
    // page has no yellow navigation row. Its name (the search field on Search) is the page's first
    // header line, on paper, 56pt with the seam under it like every top-level head, so tops line up
    // across pages (Kirby, 2026-10-28: spacing.md Heights, page-structure.md).
    val sideColumnTitleLine = raftSideColumnActive() && backgroundColor == RaftColor.Frame
    @Suppress("NAME_SHADOWING")
    val backgroundColor = if (sideColumnTitleLine) RaftColor.Canvas else backgroundColor
    val metrics = raftTopPageHeaderFrameMetrics(topSafeArea = topSafeArea, headerHeight = headerHeight)
    // A head that pads for the status bar paints under it: the host's status bar icons follow.
    RaftStatusBarBackdropClaim(backgroundColor, enabled = topSafeArea > 0.dp)
    Box(
        modifier =
            // raft-ui's `panel-header` slot, for the visual-testing component comparison.
            modifier
                .raftSlot("panel-header")
                .fillMaxWidth()
                .height(metrics.totalHeight)
                .background(backgroundColor)
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(metrics.contentHeight)
                    .padding(top = topSafeArea)
                    // The frame and seam run edge to edge (into the landscape sensor housing's
                    // side); the title and controls stay out of the horizontal safe area.
                    .raftSafeAreaHorizontalPadding()
                    .padding(start = contentStartPadding, end = contentEndPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(contentSpacing),
        ) {
            // The head's icon-lg buttons are bar buttons: 28dp under a mouse or trackpad (sizing v12).
            CompositionLocalProvider(LocalRaftBarButtons provides true) { content() }
        }
        // raft-ui panel head: a 1dp seam splits the head from the body (no ink rule). Flattened
        // onto the canvas first so it rounds like the web (a draw-time 8-bit blend lands a level dark).
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(RaftAppStroke.Thin)
                    .background(RaftColor.BorderSeam.compositeOver(RaftColor.Canvas))
                    .align(Alignment.BottomCenter)
        )
    }
}

internal fun raftMainTabHeaderUsesCanvasBackground(scale: RaftScale, underAppShellTopBar: Boolean): Boolean =
    scale == RaftScale.Wide || underAppShellTopBar

@Composable
internal fun RaftMainTabPageHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    applyTopSafeArea: Boolean = true,
    onBack: (() -> Unit)? = null,
    contentStartPadding: Dp = if (onBack != null) RaftBackButtonDefaults.EdgeInset else RaftMainTabHeaderStartPadding,
    refreshing: Boolean = false,
    leadingContent: (@Composable BoxScope.() -> Unit)? = null,
    // PROTOTYPE (native nav bar): a title tap on the host bar (the head's own modifier click).
    nativeOnTitleClick: (() -> Unit)? = null,
) {
    // Wide main tabs sit under the wide top bar / beside the rail on the canvas surface (matching
    // Activity, Artifacts, Members and Computers); only phone top-level tabs wear the yellow Frame band.
    val useCanvasBackground = raftMainTabHeaderUsesCanvasBackground(RaftTheme.scale, raftUnderAppShellTopBar())
    RaftTopPageHeader(
        title = title,
        onBack = onBack,
        subtitle = subtitle,
        modifier = modifier,
        backgroundColor = if (useCanvasBackground) RaftColor.Canvas else RaftColor.Frame,
        applyTopSafeArea = applyTopSafeArea,
        contentStartPadding = contentStartPadding,
        titleStyle = RaftMainTabHeaderTitleStyle,
        refreshing = refreshing,
        leadingContent = leadingContent,
        nativeOnTitleClick = nativeOnTitleClick,
    )
}

/**
 * The refresh spinner in a page head: it takes a header action's slot (the 44dp icon-button box,
 * spinner centred), so it lines up with the head's action glyphs and keeps their distance from
 * the edge instead of sitting on the head's padding line (artin 11-19, 11-20: "the loading in the
 * top right lacks right padding", on Saved, channel details and more).
 */
@Composable
internal fun RaftHeaderRefreshSlot(modifier: Modifier = Modifier) {
    Box(modifier = modifier.size(RaftTheme.controls.barButtonSize), contentAlignment = Alignment.Center) {
        RaftHeaderRefreshIndicator()
    }
}

/** A page header's top-right refresh spinner; screen readers hear "Refreshing". */
@Composable
internal fun RaftHeaderRefreshIndicator(
    modifier: Modifier = Modifier
) {
    RaftIconSpinner(
        modifier = modifier,
        size = LoadingSpinnerSize,
        color = RaftColor.TextBlack,
        contentDescription = stringResource(Res.string.common_refreshing)
    )
}

/**
 * raft-ui 0.6.0 `PanelAction`: a ghost control on the control radius — no fill, no
 * edge, no shadow, no press offset; a press lays `--fill-pressed` under the glyph.
 */
@Composable
internal fun RaftPanelAction(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    open: Boolean = false,
    content: @Composable () -> Unit
) {
    build.raft.designsystem.components.RaftPanelAction(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        open = open,
        content = content
    )
}

@Composable
internal fun RaftCenteredLoading(
    message: String? = null,
    modifier: Modifier = Modifier.fillMaxSize()
) {
    val resolvedMessage = message ?: stringResource(Res.string.common_loading)
    Box(
        modifier = modifier.background(RaftColor.Canvas),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            RaftIconSpinner(size = 16.dp, color = RaftColor.TextMutedGray)
            Text(
                text = resolvedMessage,
                // Web ContentLoading: sans text-callout (14/20 400).
                style = RaftType.Callout,
                color = RaftColor.TextMutedGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 220.dp)
            )
        }
    }
}

@Composable
internal fun RaftHeaderIconAction(
    icon: RaftLucideIconName,
    contentDescription: String,
    onClick: () -> Unit,
    label: String? = null,
    modifier: Modifier = Modifier
) {
    RaftPanelAction(
        onClick = onClick,
        // The icon's name for accessibility and tests: it was taken but never set (#119).
        modifier = modifier
            .semantics { this.contentDescription = contentDescription }
            // 38 in the phone's head; the bar button (36) in the wide touch head, 28 under a mouse.
            .heightIn(min = minOf(38.dp, RaftTheme.controls.barButtonSize))
            .widthIn(min = if (label == null) minOf(38.dp, RaftTheme.controls.barButtonSize) else 54.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = if (label == null) 0.dp else RaftAppSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(RaftAppSpacing.Xs, Alignment.CenterHorizontally)
        ) {
            RaftLucideIcon(
                name = icon,
                modifier = Modifier.size(minOf(18.dp, RaftTheme.controls.barIconSize)),
                color = raftArgbHex(RaftColor.TextMuted)
            )
            label?.let {
                Text(
                    text = it,
                    style = RaftType.Button,
                    color = RaftColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * A page head's leading tile (raft `PanelHeaderIcon`): 36 in the phone's 56 head; the 27 location
 * tile of a conversation head in the 48 short head and under a fine pointer
 * ([RaftControlMetrics.headAvatar] is `sm` there), so the tiles and avatars of the heads side by
 * side are one size.
 */
@Composable
@ReadOnlyComposable
internal fun raftHeaderIconTileSize(): Dp =
    if (RaftTheme.controls.headAvatar == RaftAvatarSize.Sm) RaftAvatarSize.Sm.edge else RaftAppSize.HeaderIcon

/** The glyph in [raftHeaderIconTileSize]: 18 in the 36 tile, 14 in the 27 one. */
@Composable
@ReadOnlyComposable
internal fun raftHeaderIconGlyphSize(): Dp =
    if (RaftTheme.controls.headAvatar == RaftAvatarSize.Sm) 14.dp else 18.dp
