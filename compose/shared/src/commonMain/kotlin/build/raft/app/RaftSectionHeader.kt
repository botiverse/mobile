package build.raft.app

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import build.raft.designsystem.components.RaftBackButton
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftIconButtonSize
import build.raft.designsystem.components.raftCopyable
import build.raft.designsystem.components.raftSafeAreaHorizontalPadding
import build.raft.designsystem.components.RaftBackButtonDefaults
import build.raft.designsystem.components.RaftGlyph

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
    get() = RaftType.PageTitle.copy(fontSize = 16.sp, lineHeight = 20.sp)
internal val RaftTopPageHeaderContentStartPadding = 18.dp
internal val RaftTopPageHeaderContentSpacing = 12.dp
// After a 44dp Back: its 22dp arrow keeps 11dp of its own inset, so the title sits 17dp past it.
private val RaftTopPageHeaderBackContentSpacing = 6.dp
internal val RaftTopPageHeaderContentEndPadding = 16.dp

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
 * A top bar's leading Back. On the first page of the wide supporting pane Back closes the pane,
 * so it is the design system's leading Close (web drawer ×); pages pushed in the pane, and every
 * page elsewhere, keep Back. For heads that place the Back themselves (the member profile).
 */
@Composable
internal fun RaftPaneAwareBackButton(onClick: () -> Unit) {
    if (LocalRaftPaneBackCloses.current) {
        RaftBackButton(onClick = onClick, label = stringResource(Res.string.common_close), glyph = RaftGlyph.X)
    } else {
        RaftBackButton(onClick = onClick, label = stringResource(Res.string.common_back))
    }
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
    headerHeight: Dp = RaftAppSize.PageHeaderHeight,
    // [onBack] sets the Back button's 3dp edge inset and the 6dp gap after it.
    contentStartPadding: Dp = if (onBack != null) RaftBackButtonDefaults.EdgeInset else RaftTopPageHeaderContentStartPadding,
    contentEndPadding: Dp = RaftTopPageHeaderContentEndPadding,
    contentSpacing: Dp = if (onBack != null) RaftTopPageHeaderBackContentSpacing else RaftTopPageHeaderContentSpacing,
    titleStyle: TextStyle = RaftType.PageTitle.copy(fontSize = 18.sp, lineHeight = 22.sp),
    refreshing: Boolean = false,
    leadingContent: (@Composable BoxScope.() -> Unit)? = null,
    titleLeadingContent: (@Composable RowScope.() -> Unit)? = null,
    titleContent: (@Composable RowScope.() -> Unit)? = null,
    trailingContent: (@Composable BoxScope.() -> Unit)? = null,
    onTitleClick: (() -> Unit)? = null,
    showBodyTopBanner: Boolean = true,
    // Long-press copy (raftCopyable) of the title when it is an identifier (a file name).
    titleCopyText: String? = null,
) {
    val topSafeArea =
        (topSafeAreaOverride ?: if (applyTopSafeArea) raftTopSafeAreaPadding() else 0.dp)
            .coerceAtLeast(0.dp)
    // iPhone Duo (raft-design #859): with the system's side column, Back is the column's first
    // button, and the header keeps the title from the 16dp margin.
    val backInColumn = onBack != null && raftSideColumnControls(back = raftSideColumnBackAction(onBack))
    Column(modifier = Modifier.fillMaxWidth()) {
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
                onBack != null && !backInColumn -> RaftPaneAwareBackButton(onClick = onBack)
                leadingContent != null ->
                    Box(
                        modifier = Modifier.size(RaftAppSize.HeaderIcon),
                        contentAlignment = Alignment.Center,
                        content = leadingContent
                    )
                iconName != null ->
                    Box(
                        modifier =
                            Modifier
                                .size(RaftAppSize.HeaderIcon)
                                // raft `PanelHeaderIcon`: a Source Yellow sticker, sticker radius, ink edge.
                                .background(iconBackground, RoundedCornerShape(RaftAppRadius.Sticker))
                                .border(RaftAppStroke.Control, RaftColor.StickerInk, RoundedCornerShape(RaftAppRadius.Sticker)),
                        contentAlignment = Alignment.Center
                    ) {
                        RaftLucideIcon(
                            iconName,
                            modifier = Modifier.size(18.dp),
                            color = raftArgbHex(RaftColor.StickerInk)
                        )
                    }
            }
            titleLeadingContent?.invoke(this)
            if (titleContent != null) {
                titleContent.invoke(this)
            } else {
                Column(
                    // Title tap is a hidden shortcut (scroll to top), not a control: no press wash.
                    modifier = Modifier.weight(1f).clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = onTitleClick != null
                    ) { onTitleClick?.invoke() },
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = title,
                        modifier = Modifier.raftCopyable(titleCopyText),
                        style = titleStyle,
                        color = RaftColor.TextBlack,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    subtitle?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            style = RaftType.Caption.copy(fontFamily = RaftMonoFamily, fontSize = 12.sp, lineHeight = 16.sp),
                            color = RaftColor.ForegroundHint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            if (refreshing) {
                RaftHeaderRefreshIndicator()
            }
            if (trailingContent != null) {
                Box(content = trailingContent)
            }
        }
        if (showBodyTopBanner) {
            RaftAppShellBodyTopBanner()
        }
    }
}

/**
 * raft-ui PanelHeader as a phone column head (web `PanelHeader` with `onMobileBack`): a 56dp
 * canvas row with a 1dp seam, 16dp start edge; the 44dp ghost Back (22dp glyph) is pulled 13dp
 * into that edge (`-ml-[13px]`), then an 8dp gap, the `text-headline` title over the
 * `text-caption` hint meta line.
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
) {
    val safeTop = topSafeArea.coerceAtLeast(0.dp)
    // iPhone Duo: Back moves to the system's side column (raft-design #859).
    val backInColumn = onBack != null && raftSideColumnControls(back = raftSideColumnBackAction(onBack))
    @Suppress("NAME_SHADOWING")
    val onBack = onBack.takeUnless { backInColumn }
    Box(
        modifier =
            modifier
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
                    .padding(start = if (onBack != null) RaftBackButtonDefaults.EdgeInset else 16.dp, end = RaftBackButtonDefaults.EdgeInset),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (onBack != null) {
                RaftBackButton(onClick = onBack, label = stringResource(Res.string.common_back))
            }
            Column(
                // Title tap is a hidden shortcut (scroll to top), not a control: no press wash.
                modifier =
                    Modifier.weight(1f).clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = onTitleClick != null
                    ) { onTitleClick?.invoke() },
            ) {
                Text(
                    text = title,
                    style = RaftPanelHeaderTitleStyle,
                    color = RaftColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                subtitle?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = RaftType.Caption,
                        color = RaftColor.ForegroundHint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (refreshing) {
                RaftHeaderRefreshIndicator()
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

private val RaftPanelHeaderHeight = 56.dp


// raft-ui `text-headline`: 17/22 700, no tracking.
private val RaftPanelHeaderTitleStyle: TextStyle
    get() = RaftType.PageTitle.copy(fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = 0.sp)

@Composable
internal fun RaftTopPageHeaderFrame(
    modifier: Modifier = Modifier,
    backgroundColor: Color = RaftColor.Canvas,
    topSafeArea: Dp = 0.dp,
    headerHeight: Dp = RaftAppSize.PageHeaderHeight,
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
            modifier
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
            content = content,
        )
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

@Composable
internal fun RaftMainTabPageHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    applyTopSafeArea: Boolean = true,
    onBack: (() -> Unit)? = null,
    contentStartPadding: Dp = if (onBack != null) RaftBackButtonDefaults.EdgeInset else RaftMainTabHeaderStartPadding,
    refreshing: Boolean = false,
    leadingContent: (@Composable BoxScope.() -> Unit)? = null
) {
    RaftTopPageHeader(
        title = title,
        onBack = onBack,
        subtitle = subtitle,
        modifier = modifier,
        applyTopSafeArea = applyTopSafeArea,
        contentStartPadding = contentStartPadding,
        titleStyle = RaftMainTabHeaderTitleStyle,
        refreshing = refreshing,
        leadingContent = leadingContent
    )
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
    content: @Composable () -> Unit
) {
    build.raft.designsystem.components.RaftPanelAction(onClick = onClick, modifier = modifier, content = content)
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
                style = RaftType.Caption
                    .copy(
                        fontFamily = raftMonoFamilyForWeight(FontWeight.SemiBold),
                        fontSize = 14.sp,
                        lineHeight = 18.sp,
                        fontWeight = FontWeight.Normal
                    ),
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
        modifier = modifier
            .heightIn(min = 38.dp)
            .widthIn(min = if (label == null) 38.dp else 54.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = if (label == null) 0.dp else RaftAppSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(RaftAppSpacing.Xs, Alignment.CenterHorizontally)
        ) {
            RaftLucideIcon(
                name = icon,
                modifier = Modifier.size(18.dp),
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
