package build.raft.app

import build.raft.app.resources.MR
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
internal val RaftCollapsibleSectionContentGap = 4.dp
internal val RaftCollapsibleSectionCountTextStyle =
    RaftType.SectionLabel.copy(fontFamily = RaftMonoFamily, letterSpacing = 0.sp)
internal val RaftCollapsibleSectionCountColor: Color get() = RaftColor.TextBlack.copy(alpha = 0.4f)
internal val RaftMainTabHeaderStartPadding = 8.dp
// Task #179: getter so the header follows the Settings font option.
internal val RaftMainTabHeaderTitleStyle: TextStyle
    get() = RaftType.PageTitle.copy(fontSize = 16.sp, lineHeight = 20.sp)
internal val RaftTopPageHeaderContentStartPadding = 18.dp
internal val RaftTopPageHeaderContentSpacing = 12.dp
internal val RaftTopPageHeaderContentEndPadding = 16.dp

internal enum class RaftMainTabHeaderSurface(
    val iconName: RaftLucideIconName
) {
    Members(RaftLucideIconName.Users),
    Tasks(RaftLucideIconName.SquareCheckBig),
    // Activity as a bottom tab: the same Lucide `activity` glyph as the web Activity nav item.
    Activity(RaftLucideIconName.Activity),
    Settings(RaftLucideIconName.Settings),
    Dms(RaftLucideIconName.MessageSquare),
    More(RaftLucideIconName.Ellipsis)
}

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

@Composable
internal fun RaftTopPageHeader(
    title: String,
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
    contentStartPadding: Dp = RaftTopPageHeaderContentStartPadding,
    contentEndPadding: Dp = RaftTopPageHeaderContentEndPadding,
    contentSpacing: Dp = RaftTopPageHeaderContentSpacing,
    titleStyle: TextStyle = RaftType.PageTitle.copy(fontSize = 18.sp, lineHeight = 22.sp),
    refreshing: Boolean = false,
    leadingContent: (@Composable BoxScope.() -> Unit)? = null,
    titleLeadingContent: (@Composable RowScope.() -> Unit)? = null,
    titleContent: (@Composable RowScope.() -> Unit)? = null,
    trailingContent: (@Composable BoxScope.() -> Unit)? = null,
    onTitleClick: (() -> Unit)? = null,
    showBodyTopBanner: Boolean = true,
) {
    val topSafeArea =
        (topSafeAreaOverride ?: if (applyTopSafeArea) raftTopSafeAreaPadding() else 0.dp)
            .coerceAtLeast(0.dp)
    Column(modifier = Modifier.fillMaxWidth()) {
        RaftTopPageHeaderFrame(
            modifier = modifier,
            backgroundColor = backgroundColor,
            topSafeArea = topSafeArea,
            headerHeight = headerHeight,
            contentStartPadding = contentStartPadding,
            contentEndPadding = contentEndPadding,
            contentSpacing = contentSpacing,
        ) {
            when {
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
                            color = raftArgbHex(RaftColor.StickerInk),
                            spinning = iconName == RaftLucideIconName.Loader || iconName == RaftLucideIconName.LoaderCircle
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
    val metrics = raftTopPageHeaderFrameMetrics(topSafeArea = topSafeArea, headerHeight = headerHeight)
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
                    .height(RaftAppStroke.Thin)
                    .background(RaftColor.BorderSeam)
                    .align(Alignment.BottomCenter)
        )
    }
}

@Composable
internal fun RaftMainTabPageHeader(
    surface: RaftMainTabHeaderSurface,
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    applyTopSafeArea: Boolean = true,
    contentStartPadding: Dp = RaftMainTabHeaderStartPadding,
    refreshing: Boolean = false,
    leadingContent: (@Composable BoxScope.() -> Unit)? = null
) {
    RaftTopPageHeader(
        title = title,
        subtitle = subtitle,
        iconName = surface.iconName,
        modifier = modifier,
        applyTopSafeArea = applyTopSafeArea,
        contentStartPadding = contentStartPadding,
        titleStyle = RaftMainTabHeaderTitleStyle,
        refreshing = refreshing,
        leadingContent = leadingContent
    )
}

@Composable
internal fun RaftHeaderRefreshIndicator(
    modifier: Modifier = Modifier
) {
    RaftLucideIcon(
        RaftLucideIconName.LoaderCircle,
        modifier = modifier.size(LoadingSpinnerSize),
        color = RaftColor.TextBlackArgb,
        spinning = true
    )
}

@Composable
internal fun RaftTopPageHeaderAction(
    icon: RaftLucideIconName,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    RaftPanelAction(
        onClick = onClick,
        modifier = modifier.size(SectionHeaderActionSize)
    ) {
        RaftLucideIcon(
            name = icon,
            modifier = Modifier.size(SectionHeaderActionIconSize),
            color = raftArgbHex(RaftColor.TextMuted)
        )
    }
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
    var pressed by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(RaftAppRadius.Control)
    Button(
        onClick = onClick,
        pressOffset = 0.dp,
        onPressedChange = { pressed = it },
        modifier =
            modifier
                .clip(shape)
                .background(if (pressed) RaftColor.FillPressed else Color.Transparent, shape),
        content = content
    )
}

@Composable
internal fun RaftCenteredLoading(
    message: String? = null,
    modifier: Modifier = Modifier.fillMaxSize()
) {
    val resolvedMessage = message ?: stringResource(MR.strings.common_loading)
    Box(
        modifier = modifier.background(RaftColor.Canvas),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            RaftLucideIcon(
                RaftLucideIconName.LoaderCircle,
                modifier = Modifier.size(16.dp),
                color = RaftColor.TextMutedGrayArgb,
                spinning = true
            )
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
internal fun RaftCollapsibleSectionHeader(
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
    onSortClick: ((RaftPopoverAnchor) -> Unit)? = null,
    // task #512: custom section headers open their context menu on long-press
    // (web right-click). Null keeps the plain tap-to-collapse clickable.
    // [longPressAnchorIdentity] must uniquely identify the header (a section
    // id, not its display label) so the popover anchor registry never mixes
    // two same-named sections.
    onLongPress: ((RaftPopoverAnchor) -> Unit)? = null,
    longPressAnchorIdentity: String = label,
    trailingContent: (@Composable BoxScope.() -> Unit)? = null
) {
    val longPressAnchorRef = remember(longPressAnchorIdentity) { RaftLiveRootAnchorRef(identity = "section-header:$longPressAnchorIdentity") }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .then(
                if (onLongPress != null) {
                    Modifier
                        .raftCaptureLiveRootAnchor(longPressAnchorRef)
                        .pointerInput(onExpandedChange, expanded) {
                            detectTapGestures(
                                onTap = { onExpandedChange(!expanded) },
                                onLongPress = {
                                    longPressAnchorRef.resolve(RaftPopoverLocalPoint.BottomStart)?.let(onLongPress)
                                }
                            )
                        }
                } else {
                    Modifier.clickable { onExpandedChange(!expanded) }
                }
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RaftCollapsibleSectionContentGap)
    ) {
        RaftLucideIcon(
            RaftLucideIconName.ChevronRight,
            Modifier.size(SectionChevronSize),
            rotationDegrees = if (expanded) 90f else 0f,
            rotationAnimationDurationSeconds = RaftCollapsibleSectionTokens.AnimationDurationSeconds
        )
        Text(
            if (uppercaseLabel) label.raftDisplayUppercase() else label,
            // raft-ui `SidebarSectionDisclosure`: normal tracking in source case.
            style = if (uppercaseLabel) RaftType.SectionLabel else RaftType.SectionLabel.copy(letterSpacing = 0.sp),
            color = RaftColor.TextBlack
        )
        Text(
            countPrefix + count,
            style = RaftCollapsibleSectionCountTextStyle,
            color = RaftCollapsibleSectionCountColor
        )
        if (!expanded && showCollapsedUnreadDot) {
            RaftStatusIndicator(
                color = RaftColor.AccentPink,
                size = RaftStatusIndicatorTokens.ServerUnreadSize,
                modifier = Modifier.padding(start = RaftAppSpacing.Xs)
            )
        }
        Spacer(Modifier.weight(1f))
        if (sortContentDescription != null && onSortClick != null) {
            RaftSectionHeaderIconAction(
                icon = RaftLucideIconName.ArrowUpDown,
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
internal fun RaftSectionHeaderIconAction(
    icon: RaftLucideIconName,
    contentDescription: String,
    onClick: () -> Unit = {},
    onClick2: (RaftPopoverAnchor) -> Unit = {},
    size: Dp = SectionHeaderActionSize,
    iconSize: Dp = SectionHeaderActionIconSize
) {
    val anchorRef = remember(contentDescription) { RaftLiveRootAnchorRef(identity = contentDescription) }
    Button(
        onClick = {
            onClick()
            anchorRef.resolve(RaftPopoverLocalPoint.BottomStart)?.let(onClick2)
        },
        modifier =
            Modifier
                .size(size)
                .raftCaptureLiveRootAnchor(anchorRef)
    ) {
        RaftLucideIcon(icon, Modifier.size(iconSize))
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

/** Count treatment shared by member-page headers. */
@Composable
internal fun RaftMemberCountBadge(count: Int) {
    Box(Modifier.background(RaftColor.FillMuted, RoundedCornerShape(RaftAppRadius.Chip))
        .border(RaftAppStroke.Thin, RaftColor.BorderHairline, RoundedCornerShape(RaftAppRadius.Chip))
        .padding(horizontal = 6.dp, vertical = 2.dp), contentAlignment = Alignment.Center) {
        Text(count.toString(), style = RaftType.Caption.copy(fontFamily = RaftMonoFamily, fontSize = 10.sp),
            color = RaftColor.TextMuted)
    }
}
