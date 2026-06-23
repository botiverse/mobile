package ai.slock.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import com.tencent.kuikly.compose.animation.core.RepeatMode
import com.tencent.kuikly.compose.animation.core.animateFloat
import com.tencent.kuikly.compose.animation.core.animateFloatAsState
import com.tencent.kuikly.compose.animation.core.infiniteRepeatable
import com.tencent.kuikly.compose.animation.core.rememberInfiniteTransition
import com.tencent.kuikly.compose.animation.core.tween
import com.tencent.kuikly.compose.foundation.clickable
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.BoxScope
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.Row
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
import com.tencent.kuikly.compose.ui.graphics.graphicsLayer
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.geometry.Offset
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.text.font.FontFamily
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp

private val SectionChevronSize = 12.dp
private val HeaderActionShadowOffset = 2.dp
private val SectionHeaderActionSize = 30.dp
private val SectionHeaderActionIconSize = 16.dp
private val HeaderRefreshIndicatorWidth = 96.dp
private val HeaderRefreshIndicatorHeight = 28.dp
private val HeaderRefreshIconSize = 14.dp

internal object SlockCollapsibleSectionTokens {
    const val AnimationDurationMillis: Int = 180
}

@Composable
internal fun SlockTopPageHeader(
    title: String,
    subtitle: String? = null,
    iconName: SlockLucideIconName? = null,
    iconBackground: Color = SlockColor.BrandYellow,
    backgroundColor: Color = SlockColor.BrandYellow,
    modifier: Modifier = Modifier,
    refreshing: Boolean = false,
    refreshLabel: String = "Updating...",
    leadingContent: (@Composable BoxScope.() -> Unit)? = null,
    trailingContent: (@Composable BoxScope.() -> Unit)? = null
) {
    val topSafeArea = slockTopSafeAreaPadding()
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(topSafeArea + SlockSize.PageHeaderHeight)
                .background(backgroundColor)
                .padding(start = 20.dp, top = topSafeArea, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
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
                    SlockLucideIcon(iconName, modifier = Modifier.size(18.dp), color = "#FF000000")
                }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = SlockType.PageTitle.copy(fontSize = 18.sp, lineHeight = 22.sp),
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            subtitle?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = SlockType.Caption.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp),
                    color = SlockColor.TextBlack.copy(alpha = 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (refreshing) {
            SlockHeaderRefreshIndicator(label = refreshLabel)
        }
        if (trailingContent != null) {
            Box(content = trailingContent)
        }
    }
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(SlockStroke.Default)
                .background(SlockColor.BorderBlack)
    )
}

@Composable
internal fun SlockHeaderRefreshIndicator(
    modifier: Modifier = Modifier,
    label: String = "Updating..."
) {
    val transition = rememberInfiniteTransition(label = "slock-header-refresh-spinner")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = 900),
                repeatMode = RepeatMode.Restart
            ),
        label = "slock-header-refresh-spinner-rotation"
    )
    Row(
        modifier =
            modifier
                .width(HeaderRefreshIndicatorWidth)
                .height(HeaderRefreshIndicatorHeight)
                .background(SlockColor.SurfaceWhite)
                .border(SlockStroke.Default, SlockColor.BorderBlack)
                .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        SlockLucideIcon(
            SlockLucideIconName.LoaderCircle,
            modifier =
                Modifier
                    .size(HeaderRefreshIconSize)
                    .graphicsLayer(rotationZ = rotation),
            color = "#FF000000"
        )
        Text(
            text = label,
            style = SlockType.Caption.copy(fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 12.sp),
            color = SlockColor.TextBlack.copy(alpha = 0.64f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun SlockCenteredLoading(message: String = "Loading...") {
    Box(
        modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            style = SlockType.Caption.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp),
            color = SlockColor.TextMutedGray,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun SlockCollapsibleSectionHeader(
    label: String,
    count: Int,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    sortContentDescription: String? = null,
    onSortClick: ((Offset) -> Unit)? = null,
    trailingContent: (@Composable BoxScope.() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 30.dp)
            .clickable { onExpandedChange(!expanded) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
    ) {
        val rotation by animateFloatAsState(
            targetValue = if (expanded) 90f else 0f,
            animationSpec = tween(durationMillis = SlockCollapsibleSectionTokens.AnimationDurationMillis),
            label = "slock-collapsible-section-chevron-rotation"
        )
        SlockLucideIcon(
            SlockLucideIconName.ChevronRight,
            Modifier
                .size(SectionChevronSize)
                .graphicsLayer(rotationZ = rotation)
        )
        Text(
            label.uppercase(),
            style = SlockType.SectionLabel,
            color = SlockColor.TextBlack,
            modifier = Modifier.padding(start = SlockSpacing.Xs)
        )
        Text(
            count.toString(),
            style = SlockType.Caption,
            color = SlockColor.TextMutedGray,
            modifier = Modifier.padding(start = SlockSpacing.Xs)
        )
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
    onClick2: (Offset) -> Unit = {}
) {
    Button(
        onClick = onClick,
        onClick2 = onClick2,
        modifier = Modifier.size(SectionHeaderActionSize)
    ) {
        SlockLucideIcon(icon, Modifier.size(SectionHeaderActionIconSize))
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
