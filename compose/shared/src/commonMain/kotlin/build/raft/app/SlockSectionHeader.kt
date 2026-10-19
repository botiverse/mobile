package build.raft.app

import build.raft.app.resources.MR
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyListState
import build.raft.app.compat.ui.PullToRefreshState
import androidx.compose.material3.Text
import build.raft.app.compat.ui.pullToRefreshItem
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tencent.tmm.kmmresource.compose.stringResource

private val SectionChevronSize = 12.dp
private val LoadingSpinnerSize = 14.dp
private val SectionHeaderActionSize = 30.dp
private val SectionHeaderActionIconSize = 16.dp
private val PullRefreshCarrierColor: Color get() = SlockColor.Canvas
internal val SlockCollapsibleSectionContentGap = 4.dp
internal val SlockCollapsibleSectionCountTextStyle =
    SlockType.SectionLabel.copy(fontFamily = SlockMonoFamily, letterSpacing = 0.sp)
internal val SlockCollapsibleSectionCountColor: Color get() = SlockColor.TextBlack.copy(alpha = 0.4f)
internal val SlockMainTabHeaderStartPadding = 8.dp
// Task #179: getter so the header follows the Settings font option.
internal val SlockMainTabHeaderTitleStyle: TextStyle
    get() = SlockType.PageTitle.copy(fontSize = 16.sp, lineHeight = 20.sp)
internal val SlockTopPageHeaderContentStartPadding = 18.dp
internal val SlockTopPageHeaderContentSpacing = 12.dp

internal enum class SlockMainTabHeaderSurface(
    val iconName: SlockLucideIconName
) {
    Members(SlockLucideIconName.Users),
    Tasks(SlockLucideIconName.SquareCheckBig),
    Settings(SlockLucideIconName.Settings),
    Dms(SlockLucideIconName.MessageSquare),
    More(SlockLucideIconName.Ellipsis)
}

internal object SlockCollapsibleSectionTokens {
    const val AnimationDurationMillis: Int = 180
    const val AnimationDurationSeconds: Float = AnimationDurationMillis / 1000f
}

internal data class SlockTopPageHeaderFrameMetrics(
    val totalHeight: Dp,
    val contentHeight: Dp,
)

internal fun slockTopPageHeaderFrameMetrics(
    topSafeArea: Dp,
    headerHeight: Dp = SlockSize.PageHeaderHeight,
): SlockTopPageHeaderFrameMetrics {
    val boundedHeaderHeight = headerHeight.coerceAtLeast(0.dp)
    val contentHeight = (boundedHeaderHeight - SlockStroke.Default).coerceAtLeast(0.dp)
    return SlockTopPageHeaderFrameMetrics(
        totalHeight = topSafeArea.coerceAtLeast(0.dp) + boundedHeaderHeight,
        contentHeight = topSafeArea.coerceAtLeast(0.dp) + contentHeight,
    )
}

@Composable
internal fun SlockTopPageHeader(
    title: String,
    subtitle: String? = null,
    iconName: SlockLucideIconName? = null,
    iconBackground: Color = SlockColor.Brand,
    // raft-ui 0.6.0: a phone section head sits on the frame layer (Source Yellow in
    // light, the dark frame in dark) — the yellow marks where you are.
    backgroundColor: Color = SlockColor.Frame,
    modifier: Modifier = Modifier,
    applyTopSafeArea: Boolean = true,
    topSafeAreaOverride: Dp? = null,
    headerHeight: Dp = SlockSize.PageHeaderHeight,
    contentStartPadding: Dp = SlockTopPageHeaderContentStartPadding,
    contentSpacing: Dp = SlockTopPageHeaderContentSpacing,
    titleStyle: TextStyle = SlockType.PageTitle.copy(fontSize = 18.sp, lineHeight = 22.sp),
    refreshing: Boolean = false,
    leadingContent: (@Composable BoxScope.() -> Unit)? = null,
    titleLeadingContent: (@Composable RowScope.() -> Unit)? = null,
    titleContent: (@Composable RowScope.() -> Unit)? = null,
    trailingContent: (@Composable BoxScope.() -> Unit)? = null,
    onTitleClick: (() -> Unit)? = null,
    showBodyTopBanner: Boolean = true,
) {
    val topSafeArea =
        (topSafeAreaOverride ?: if (applyTopSafeArea) slockTopSafeAreaPadding() else 0.dp)
            .coerceAtLeast(0.dp)
    Column(modifier = Modifier.fillMaxWidth()) {
        SlockTopPageHeaderFrame(
            modifier = modifier,
            backgroundColor = backgroundColor,
            topSafeArea = topSafeArea,
            headerHeight = headerHeight,
            contentStartPadding = contentStartPadding,
            contentSpacing = contentSpacing,
        ) {
            when {
                leadingContent != null ->
                    Box(
                        modifier = Modifier.size(SlockSize.HeaderIcon),
                        contentAlignment = Alignment.Center,
                        content = leadingContent
                    )
                iconName != null ->
                    Box(
                        modifier =
                            Modifier
                                .size(SlockSize.HeaderIcon)
                                // raft `PanelHeaderIcon`: a Source Yellow sticker, sticker radius, ink edge.
                                .background(iconBackground, RoundedCornerShape(SlockRadius.Sticker))
                                .border(SlockStroke.Control, SlockColor.StickerInk, RoundedCornerShape(SlockRadius.Sticker)),
                        contentAlignment = Alignment.Center
                    ) {
                        SlockLucideIcon(
                            iconName,
                            modifier = Modifier.size(18.dp),
                            color = slockArgbHex(SlockColor.StickerInk),
                            spinning = iconName == SlockLucideIconName.Loader || iconName == SlockLucideIconName.LoaderCircle
                        )
                    }
            }
            titleLeadingContent?.invoke(this)
            if (titleContent != null) {
                titleContent.invoke(this)
            } else {
                Column(
                    modifier = Modifier.weight(1f).clickable(enabled = onTitleClick != null) { onTitleClick?.invoke() },
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = title,
                        style = titleStyle,
                        color = SlockColor.TextBlack,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    subtitle?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            style = SlockType.Caption.copy(fontFamily = SlockMonoFamily, fontSize = 12.sp, lineHeight = 16.sp),
                            color = SlockColor.ForegroundHint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            if (refreshing) {
                SlockHeaderRefreshIndicator()
            }
            if (trailingContent != null) {
                Box(content = trailingContent)
            }
        }
        if (showBodyTopBanner) {
            SlockAppShellBodyTopBanner()
        }
    }
}

@Composable
internal fun SlockTopPageHeaderFrame(
    modifier: Modifier = Modifier,
    backgroundColor: Color = SlockColor.Canvas,
    topSafeArea: Dp = 0.dp,
    headerHeight: Dp = SlockSize.PageHeaderHeight,
    contentStartPadding: Dp = SlockTopPageHeaderContentStartPadding,
    contentEndPadding: Dp = 16.dp,
    contentSpacing: Dp = SlockTopPageHeaderContentSpacing,
    content: @Composable RowScope.() -> Unit,
) {
    val metrics = slockTopPageHeaderFrameMetrics(topSafeArea = topSafeArea, headerHeight = headerHeight)
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
                    .padding(start = contentStartPadding, top = topSafeArea, end = contentEndPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(contentSpacing),
            content = content,
        )
        // raft-ui panel head: a 1dp seam splits the head from the body (no ink rule).
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(SlockStroke.Thin)
                    .background(SlockColor.BorderSeam)
                    .align(Alignment.BottomCenter)
        )
    }
}

@Composable
internal fun SlockMainTabPageHeader(
    surface: SlockMainTabHeaderSurface,
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    applyTopSafeArea: Boolean = true,
    contentStartPadding: Dp = SlockMainTabHeaderStartPadding,
    refreshing: Boolean = false,
    leadingContent: (@Composable BoxScope.() -> Unit)? = null
) {
    SlockTopPageHeader(
        title = title,
        subtitle = subtitle,
        iconName = surface.iconName,
        modifier = modifier,
        applyTopSafeArea = applyTopSafeArea,
        contentStartPadding = contentStartPadding,
        titleStyle = SlockMainTabHeaderTitleStyle,
        refreshing = refreshing,
        leadingContent = leadingContent
    )
}

@Composable
internal fun SlockHeaderRefreshIndicator(
    modifier: Modifier = Modifier
) {
    SlockLucideIcon(
        SlockLucideIconName.LoaderCircle,
        modifier = modifier.size(LoadingSpinnerSize),
        color = SlockColor.TextBlackArgb,
        spinning = true
    )
}

@Composable
internal fun SlockTopPageHeaderAction(
    icon: SlockLucideIconName,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    SlockRaftPanelAction(
        onClick = onClick,
        modifier = modifier.size(SectionHeaderActionSize)
    ) {
        SlockLucideIcon(
            name = icon,
            modifier = Modifier.size(SectionHeaderActionIconSize),
            color = slockArgbHex(SlockColor.TextMuted)
        )
    }
}

/**
 * raft-ui 0.6.0 `PanelAction`: a ghost control on the control radius — no fill, no
 * edge, no shadow, no press offset; a press lays `--fill-pressed` under the glyph.
 */
@Composable
internal fun SlockRaftPanelAction(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    var pressed by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(SlockRadius.Control)
    Button(
        onClick = onClick,
        pressOffset = 0.dp,
        onPressedChange = { pressed = it },
        modifier =
            modifier
                .clip(shape)
                .background(if (pressed) SlockColor.FillPressed else Color.Transparent, shape),
        content = content
    )
}

internal fun LazyListScope.slockRaftPullToRefreshItem(
    state: PullToRefreshState,
    scrollState: LazyListState,
    onRefresh: () -> Unit
) {
    pullToRefreshItem(
        state = state,
        scrollState = scrollState,
        holdRefreshInset = true,
        onRefresh = onRefresh,
        content = { pullProgress, isRefreshing, refreshThreshold ->
            SlockRaftPullRefreshIndicator(
                pullProgress = pullProgress,
                isRefreshing = isRefreshing,
                refreshThreshold = refreshThreshold
            )
        }
    )
}

@Composable
internal fun SlockRaftPullRefreshIndicator(
    pullProgress: Float,
    isRefreshing: Boolean,
    refreshThreshold: Dp,
    modifier: Modifier = Modifier
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(refreshThreshold)
                .background(PullRefreshCarrierColor),
        contentAlignment = Alignment.Center
    ) {
        if (slockPullRefreshIndicatorVisible(pullProgress, isRefreshing)) {
            SlockLucideIcon(
                SlockLucideIconName.LoaderCircle,
                modifier = Modifier.size(LoadingSpinnerSize),
                color = SlockColor.TextBlackArgb,
                spinning = isRefreshing,
                rotationDegrees = slockPullRefreshIndicatorRotationDegrees(pullProgress, isRefreshing)
            )
        }
    }
}

internal fun slockPullRefreshIndicatorVisible(pullProgress: Float, isRefreshing: Boolean): Boolean =
    isRefreshing || pullProgress > 0f

internal fun slockPullRefreshIndicatorRotationDegrees(pullProgress: Float, isRefreshing: Boolean): Float =
    if (isRefreshing) {
        0f
    } else {
        pullProgress.coerceIn(0f, 1f) * 360f
    }

@Composable
internal fun SlockCenteredLoading(
    message: String? = null,
    modifier: Modifier = Modifier.fillMaxSize()
) {
    val resolvedMessage = message ?: stringResource(MR.strings.common_loading)
    Box(
        modifier = modifier.background(SlockColor.Canvas),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SlockLucideIcon(
                SlockLucideIconName.LoaderCircle,
                modifier = Modifier.size(16.dp),
                color = SlockColor.TextMutedGrayArgb,
                spinning = true
            )
            Text(
                text = resolvedMessage,
                style = SlockType.Caption
                    .copy(
                        fontFamily = slockMonoFamilyForWeight(FontWeight.SemiBold),
                        fontSize = 14.sp,
                        lineHeight = 18.sp,
                        fontWeight = FontWeight.Normal
                    ),
                color = SlockColor.TextMutedGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 220.dp)
            )
        }
    }
}

@Composable
internal fun SlockCollapsibleSectionHeader(
    label: String,
    count: Int,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    showCollapsedUnreadDot: Boolean = false,
    minHeight: Dp = 30.dp,
    // raft-ui section titles keep source case (`normal-case`); callers may still opt in.
    uppercaseLabel: Boolean = false,
    countPrefix: String = "",
    sortContentDescription: String? = null,
    onSortClick: ((SlockPopoverAnchor) -> Unit)? = null,
    // task #512: custom section headers open their context menu on long-press
    // (web right-click). Null keeps the plain tap-to-collapse clickable.
    // [longPressAnchorIdentity] must uniquely identify the header (a section
    // id, not its display label) so the popover anchor registry never mixes
    // two same-named sections.
    onLongPress: ((SlockPopoverAnchor) -> Unit)? = null,
    longPressAnchorIdentity: String = label,
    trailingContent: (@Composable BoxScope.() -> Unit)? = null
) {
    val longPressAnchorRef = remember(longPressAnchorIdentity) { SlockLiveRootAnchorRef(identity = "section-header:$longPressAnchorIdentity") }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .then(
                if (onLongPress != null) {
                    Modifier
                        .slockCaptureLiveRootAnchor(longPressAnchorRef)
                        .pointerInput(onExpandedChange, expanded) {
                            detectTapGestures(
                                onTap = { onExpandedChange(!expanded) },
                                onLongPress = {
                                    longPressAnchorRef.resolve(SlockPopoverLocalPoint.BottomStart)?.let(onLongPress)
                                }
                            )
                        }
                } else {
                    Modifier.clickable { onExpandedChange(!expanded) }
                }
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SlockCollapsibleSectionContentGap)
    ) {
        SlockLucideIcon(
            SlockLucideIconName.ChevronRight,
            Modifier.size(SectionChevronSize),
            rotationDegrees = if (expanded) 90f else 0f,
            rotationAnimationDurationSeconds = SlockCollapsibleSectionTokens.AnimationDurationSeconds
        )
        Text(
            if (uppercaseLabel) label.slockDisplayUppercase() else label,
            // raft-ui `SidebarSectionDisclosure`: normal tracking in source case.
            style = if (uppercaseLabel) SlockType.SectionLabel else SlockType.SectionLabel.copy(letterSpacing = 0.sp),
            color = SlockColor.TextBlack
        )
        Text(
            countPrefix + count,
            style = SlockCollapsibleSectionCountTextStyle,
            color = SlockCollapsibleSectionCountColor
        )
        if (!expanded && showCollapsedUnreadDot) {
            SlockStatusIndicator(
                color = SlockColor.AccentPink,
                size = SlockStatusIndicatorTokens.ServerUnreadSize,
                modifier = Modifier.padding(start = SlockSpacing.Xs)
            )
        }
        Spacer(Modifier.weight(1f))
        if (sortContentDescription != null && onSortClick != null) {
            SlockSectionHeaderIconAction(
                icon = SlockLucideIconName.ArrowUpDown,
                contentDescription = sortContentDescription,
                onClick2 = onSortClick
            )
        }
        if (trailingContent != null) {
            Box(content = trailingContent)
        }
    }
}

@Suppress("UNUSED_PARAMETER")
@Composable
internal fun SlockSectionHeaderIconAction(
    icon: SlockLucideIconName,
    contentDescription: String,
    onClick: () -> Unit = {},
    onClick2: (SlockPopoverAnchor) -> Unit = {},
    size: Dp = SectionHeaderActionSize,
    iconSize: Dp = SectionHeaderActionIconSize
) {
    val anchorRef = remember(contentDescription) { SlockLiveRootAnchorRef(identity = contentDescription) }
    Button(
        onClick = {
            onClick()
            anchorRef.resolve(SlockPopoverLocalPoint.BottomStart)?.let(onClick2)
        },
        modifier =
            Modifier
                .size(size)
                .slockCaptureLiveRootAnchor(anchorRef)
    ) {
        SlockLucideIcon(icon, Modifier.size(iconSize))
    }
}

@Composable
internal fun SlockHeaderIconAction(
    icon: SlockLucideIconName,
    contentDescription: String,
    onClick: () -> Unit,
    label: String? = null,
    modifier: Modifier = Modifier
) {
    SlockRaftPanelAction(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = 38.dp)
            .widthIn(min = if (label == null) 38.dp else 54.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = if (label == null) 0.dp else SlockSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs, Alignment.CenterHorizontally)
        ) {
            SlockLucideIcon(
                name = icon,
                modifier = Modifier.size(18.dp),
                color = slockArgbHex(SlockColor.TextMuted)
            )
            label?.let {
                Text(
                    text = it,
                    style = SlockType.Button,
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** Count treatment shared by member-page headers. */
@Composable
internal fun SlockMemberCountBadge(count: Int) {
    Box(Modifier.background(SlockColor.FillMuted, RoundedCornerShape(SlockRadius.Chip))
        .border(SlockStroke.Thin, SlockColor.BorderHairline, RoundedCornerShape(SlockRadius.Chip))
        .padding(horizontal = 6.dp, vertical = 2.dp), contentAlignment = Alignment.Center) {
        Text(count.toString(), style = SlockType.Caption.copy(fontFamily = SlockMonoFamily, fontSize = 10.sp),
            color = SlockColor.TextMuted)
    }
}
