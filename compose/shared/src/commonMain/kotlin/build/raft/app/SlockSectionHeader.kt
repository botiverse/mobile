package build.raft.app

import build.raft.app.resources.MR
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.tencent.kuikly.compose.foundation.clickable
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.BoxScope
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.RowScope
import com.tencent.kuikly.compose.foundation.layout.Spacer
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.heightIn
import com.tencent.kuikly.compose.foundation.layout.offset
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.foundation.layout.widthIn
import com.tencent.kuikly.compose.foundation.lazy.LazyListScope
import com.tencent.kuikly.compose.foundation.lazy.LazyListState
import com.tencent.kuikly.compose.material3.PullToRefreshState
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.material3.pullToRefreshItem
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.geometry.Offset
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.text.font.FontFamily
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.TextStyle
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.tmm.kmmresource.compose.stringResource

private val SectionChevronSize = 12.dp
private val LoadingSpinnerSize = 14.dp
private val HeaderActionShadowOffset = 2.dp
private val SectionHeaderActionSize = 30.dp
private val SectionHeaderActionIconSize = 16.dp
private val PullRefreshCarrierColor = SlockColor.SurfaceWhite
internal val SlockCollapsibleSectionContentGap = 4.dp
internal val SlockCollapsibleSectionCountTextStyle =
    SlockType.SectionLabel.copy(fontFamily = SlockMonoFamily, letterSpacing = 0.sp)
internal val SlockCollapsibleSectionCountColor = SlockColor.TextBlack.copy(alpha = 0.4f)
internal val SlockMainTabHeaderStartPadding = 8.dp
internal val SlockMainTabHeaderTitleStyle = SlockType.PageTitle.copy(fontSize = 16.sp, lineHeight = 20.sp)
internal val SlockTopPageHeaderContentStartPadding = 18.dp
internal val SlockTopPageHeaderContentSpacing = 12.dp

internal enum class SlockMainTabHeaderSurface(
    val iconName: SlockLucideIconName
) {
    Members(SlockLucideIconName.Users),
    Tasks(SlockLucideIconName.SquareCheckBig),
    Settings(SlockLucideIconName.Settings),
    Dms(SlockLucideIconName.MessageSquare)
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
    iconBackground: Color = SlockColor.BrandYellow,
    backgroundColor: Color = SlockColor.BrandYellow,
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
                                .background(iconBackground)
                                .border(SlockStroke.Default, SlockColor.BorderBlack),
                        contentAlignment = Alignment.Center
                    ) {
                        SlockLucideIcon(
                            iconName,
                            modifier = Modifier.size(18.dp),
                            color = "#FF000000",
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
                            color = SlockColor.TextBlack.copy(alpha = 0.55f),
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
    backgroundColor: Color = SlockColor.SurfaceWhite,
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
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(SlockStroke.Default)
                    .background(SlockColor.BorderBlack)
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
    Box(modifier = modifier.padding(end = HeaderActionShadowOffset, bottom = HeaderActionShadowOffset)) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .offset(x = HeaderActionShadowOffset, y = HeaderActionShadowOffset)
                .background(SlockColor.ShadowBlack)
        )
        Button(
            onClick = onClick,
            modifier = Modifier
                .size(SectionHeaderActionSize)
                .background(SlockColor.SurfaceWhite)
                .border(SlockStroke.Default, SlockColor.BorderBlack)
        ) {
            SlockLucideIcon(
                name = icon,
                modifier = Modifier.size(SectionHeaderActionIconSize),
                color = "#FF000000"
            )
        }
    }
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
        modifier = modifier.background(SlockColor.SurfaceWhite),
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
    sortContentDescription: String? = null,
    onSortClick: ((SlockPopoverAnchor) -> Unit)? = null,
    trailingContent: (@Composable BoxScope.() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .clickable { onExpandedChange(!expanded) },
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
            label.uppercase(),
            style = SlockType.SectionLabel,
            color = SlockColor.TextBlack
        )
        Text(
            count.toString(),
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
    Box(modifier = modifier.padding(end = HeaderActionShadowOffset, bottom = HeaderActionShadowOffset)) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .offset(x = HeaderActionShadowOffset, y = HeaderActionShadowOffset)
                .background(SlockColor.ShadowBlack)
        )
        Button(
            onClick = onClick,
            modifier = Modifier
                .heightIn(min = 38.dp)
                .widthIn(min = if (label == null) 38.dp else 54.dp)
                .background(SlockColor.SurfaceWhite)
                .border(SlockStroke.Default, SlockColor.BorderBlack)
                .padding(horizontal = if (label == null) 0.dp else SlockSpacing.Sm)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs, Alignment.CenterHorizontally)
            ) {
                SlockLucideIcon(
                    name = icon,
                    modifier = Modifier.size(18.dp),
                    color = "#FF000000"
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
}
