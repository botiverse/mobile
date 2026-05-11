@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package ai.slock.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.slock.android.ui.theme.SlockColor
import ai.slock.android.ui.theme.SlockSize
import ai.slock.android.ui.theme.SlockSpacing
import ai.slock.android.ui.theme.SlockStroke
import ai.slock.android.ui.theme.SlockType

enum class SlockWindowLayoutMode {
    CompactSinglePane,
    MediumListDetail,
    ExpandedThreePane
}

enum class SlockAdaptiveNavigationPlacement {
    BottomBar,
    NavigationRail,
    FloatingDock
}

enum class SlockLargeScreenNavigationStyle {
    NavigationRail,
    FloatingDock
}

@Immutable
data class SlockAdaptiveAppLayoutInfo(
    val mode: SlockWindowLayoutMode,
    val navigationPlacement: SlockAdaptiveNavigationPlacement,
    val width: Dp,
    val height: Dp
) {
    val usesNavigationRail: Boolean
        get() = navigationPlacement == SlockAdaptiveNavigationPlacement.NavigationRail
}

@Composable
fun SlockAdaptiveAppScaffold(
    selectedKey: String,
    items: List<SlockBottomTabItem>,
    onSelected: (String) -> Unit,
    showNavigation: Boolean,
    statusBarColor: Color,
    largeScreenNavigationStyle: SlockLargeScreenNavigationStyle =
        SlockLargeScreenNavigationStyle.NavigationRail,
    modifier: Modifier = Modifier,
    statusBarContent: (@Composable (SlockAdaptiveAppLayoutInfo) -> Unit)? = null,
    content: @Composable (SlockAdaptiveAppLayoutInfo) -> Unit
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) adaptiveWindow@{
        val layoutInfo =
            rememberSlockAdaptiveAppLayoutInfo(
                width = this@adaptiveWindow.maxWidth,
                height = this@adaptiveWindow.maxHeight,
                largeScreenNavigationStyle = largeScreenNavigationStyle
            )
        Row(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(SlockColor.AppBgLightBlue)
        ) {
            if (slockAdaptiveShowsNavigationRail(layoutInfo)) {
                SlockAppNavigationRail(
                    items = items,
                    selectedKey = selectedKey,
                    onSelected = onSelected
                )
            }
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    if (statusBarContent != null) {
                        statusBarContent(layoutInfo)
                    } else {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .windowInsetsTopHeight(WindowInsets.statusBarsIgnoringVisibility)
                                    .background(statusBarColor)
                        )
                    }
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        content(layoutInfo)
                    }
                }
                if (showNavigation &&
                    layoutInfo.navigationPlacement == SlockAdaptiveNavigationPlacement.BottomBar
                ) {
                    SlockAppBottomBarHost(
                        items = items,
                        selectedKey = selectedKey,
                        modifier = Modifier.align(Alignment.BottomCenter),
                        onSelected = onSelected
                    )
                } else if (
                    showNavigation &&
                    layoutInfo.navigationPlacement == SlockAdaptiveNavigationPlacement.FloatingDock
                ) {
                    SlockAppFloatingDockHost(
                        items = items,
                        selectedKey = selectedKey,
                        modifier = Modifier.align(Alignment.BottomCenter),
                        onSelected = onSelected
                    )
                }
            }
        }
    }
}

@Composable
fun rememberSlockAdaptiveAppLayoutInfo(
    width: Dp,
    height: Dp,
    largeScreenNavigationStyle: SlockLargeScreenNavigationStyle =
        SlockLargeScreenNavigationStyle.NavigationRail
): SlockAdaptiveAppLayoutInfo =
    slockAdaptiveAppLayoutInfo(
        width = width,
        height = height,
        largeScreenNavigationStyle = largeScreenNavigationStyle
    )

fun slockAdaptiveAppLayoutInfo(
    width: Dp,
    height: Dp,
    largeScreenNavigationStyle: SlockLargeScreenNavigationStyle =
        SlockLargeScreenNavigationStyle.NavigationRail
): SlockAdaptiveAppLayoutInfo {
    val mode =
        when {
            width >= ExpandedWindowMinWidth -> SlockWindowLayoutMode.ExpandedThreePane
            width >= MediumWindowMinWidth -> SlockWindowLayoutMode.MediumListDetail
            else -> SlockWindowLayoutMode.CompactSinglePane
        }
    val navigationPlacement =
        if (mode == SlockWindowLayoutMode.CompactSinglePane) {
            SlockAdaptiveNavigationPlacement.BottomBar
        } else if (largeScreenNavigationStyle == SlockLargeScreenNavigationStyle.FloatingDock) {
            SlockAdaptiveNavigationPlacement.FloatingDock
        } else {
            SlockAdaptiveNavigationPlacement.NavigationRail
        }
    return SlockAdaptiveAppLayoutInfo(
        mode = mode,
        navigationPlacement = navigationPlacement,
        width = width,
        height = height
    )
}

fun slockAdaptiveShowsNavigationRail(layoutInfo: SlockAdaptiveAppLayoutInfo): Boolean =
    layoutInfo.usesNavigationRail

@Composable
fun SlockAdaptiveBottomContentPadding(
    layoutInfo: SlockAdaptiveAppLayoutInfo,
    showNavigation: Boolean
): Dp =
    if (showNavigation &&
        layoutInfo.navigationPlacement == SlockAdaptiveNavigationPlacement.BottomBar
    ) {
        SlockSize.BottomBarHeight +
            with(LocalDensity.current) {
                WindowInsets.navigationBars.getBottom(this).toDp()
            } +
            SlockSpacing.Xl
    } else if (
        showNavigation &&
        layoutInfo.navigationPlacement == SlockAdaptiveNavigationPlacement.FloatingDock
    ) {
        SlockSize.BottomBarHeight +
            with(LocalDensity.current) {
                WindowInsets.navigationBars.getBottom(this).toDp()
            } +
            SlockSpacing.Xl
    } else {
        SlockSpacing.Lg
    }

@Composable
fun SlockAppBottomBarHost(
    items: List<SlockBottomTabItem>,
    selectedKey: String,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .background(SlockColor.SurfaceWhite)
                .navigationBarsPadding()
                .padding(bottom = SlockSpacing.Xs)
    ) {
        SlockBottomTabBar(
            items = items,
            selectedKey = selectedKey,
            onSelected = onSelected,
            modifier = Modifier.fillMaxWidth().offset(y = -SlockStroke.Default)
        )
    }
}

@Composable
private fun SlockAppFloatingDockHost(
    items: List<SlockBottomTabItem>,
    selectedKey: String,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier =
            modifier
                .navigationBarsPadding()
                .padding(bottom = SlockSpacing.Md)
                .widthIn(max = FloatingDockMaxWidth)
                .fillMaxWidth(FloatingDockWidthFraction)
                .offset(x = SlockStroke.Default, y = SlockStroke.Default)
                .background(SlockColor.BorderBlack)
    ) {
        SlockBottomTabBar(
            items = items,
            selectedKey = selectedKey,
            onSelected = onSelected,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .offset(x = -SlockStroke.Default, y = -SlockStroke.Default)
                    .border(SlockStroke.Default, SlockColor.BorderBlack)
        )
    }
}

@Composable
private fun SlockAppNavigationRail(
    items: List<SlockBottomTabItem>,
    selectedKey: String,
    onSelected: (String) -> Unit
) {
    Column(
        modifier =
            Modifier
                .width(NavigationRailWidth)
                .fillMaxHeight()
                .background(SlockColor.SurfaceWhite)
                .drawWithContent {
                    drawContent()
                    val stroke = SlockStroke.Default.toPx()
                    drawLine(
                        color = SlockColor.BorderBlack,
                        start = androidx.compose.ui.geometry.Offset(size.width - stroke / 2f, 0f),
                        end =
                            androidx.compose.ui.geometry.Offset(
                                size.width - stroke / 2f,
                                size.height
                            ),
                        strokeWidth = stroke
                    )
                },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .windowInsetsTopHeight(WindowInsets.statusBarsIgnoringVisibility)
        )
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(bottom = SlockSpacing.Md),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
        ) {
            items.forEach { item ->
                SlockNavigationRailItem(
                    item = item,
                    selected = item.key == selectedKey,
                    onClick = { onSelected(item.key) }
                )
            }
        }
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .windowInsetsBottomHeight(WindowInsets.navigationBars)
        )
    }
}

@Composable
private fun SlockNavigationRailItem(
    item: SlockBottomTabItem,
    selected: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(NavigationRailItemHeight)
                .background(if (selected) SlockColor.BrandYellow else SlockColor.SurfaceWhite)
                .clickable(onClick = onClick)
                .padding(horizontal = SlockSpacing.Xs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)
    ) {
        Icon(
            painter = painterResource(item.icon),
            contentDescription = item.contentDescription,
            tint = SlockColor.TextBlack,
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = item.label.uppercase(),
            style =
                SlockType.Caption.copy(
                    fontSize = 10.sp,
                    lineHeight = 12.sp,
                    fontWeight = FontWeight.Bold
                ),
            color = SlockColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private val MediumWindowMinWidth = 600.dp
private val ExpandedWindowMinWidth = 840.dp
private val NavigationRailWidth = 72.dp
private val NavigationRailItemHeight = 58.dp
private val FloatingDockMaxWidth = 420.dp
private const val FloatingDockWidthFraction = 0.72f
