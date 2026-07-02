package build.raft.app.components

import build.raft.app.SlockAvatar
import build.raft.app.SlockColor
import build.raft.app.SlockLucideIcon
import build.raft.app.SlockLucideIconName
import build.raft.app.SlockStroke
import build.raft.app.SlockType
import build.raft.app.withSlockFontWeight
import androidx.compose.runtime.Composable
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.RowScope
import com.tencent.kuikly.compose.foundation.layout.fillMaxHeight
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.offset
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.foundation.layout.widthIn
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.draw.alpha
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.graphics.RectangleShape
import com.tencent.kuikly.compose.ui.text.font.FontFamily
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.style.TextAlign
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp

internal enum class SlockRaftUiBannerStatus(val background: Color) {
    Info(SlockColor.BrandYellow.copy(alpha = 0.30f)),
    Warning(SlockColor.AccentOrange.copy(alpha = 0.20f)),
    Destructive(SlockColor.AccentRed.copy(alpha = 0.20f)),
    Success(SlockColor.AccentLime.copy(alpha = 0.20f))
}

internal enum class SlockRaftUiBannerSize(val padding: Dp, val gap: Dp, val titleSize: Int, val bodySize: Int) {
    Small(8.dp, 8.dp, 12, 12),
    Medium(12.dp, 12.dp, 14, 14)
}

internal enum class SlockRaftUiButtonVariant(val background: Color) {
    Primary(SlockColor.BrandYellow),
    Default(SlockColor.SurfaceWhite),
    Danger(SlockColor.AccentRed),
    Muted(SlockColor.SurfaceCream),
    Outline(SlockColor.SurfaceWhite)
}

internal enum class SlockRaftUiBadgeVariant(val tone: SlockBrutalTone) {
    Success(SlockBrutalTone.Lime),
    Information(SlockBrutalTone.Cyan),
    Warning(SlockBrutalTone.Orange),
    Danger(SlockBrutalTone.Red)
}

internal data class SlockRaftUiTabItem(val value: String, val label: String)

@Composable
internal fun SlockRaftUiButton(
    label: String,
    modifier: Modifier = Modifier,
    variant: SlockRaftUiButtonVariant = SlockRaftUiButtonVariant.Default,
    enabled: Boolean = true,
    size: SlockRaftUiBannerSize = SlockRaftUiBannerSize.Small
) {
    SlockBrutalActionButton(
        label = label,
        onClick = {},
        modifier = modifier,
        enabled = enabled,
        backgroundColor = variant.background,
        minHeight = if (size == SlockRaftUiBannerSize.Small) 28.dp else 32.dp,
        minWidth = 0.dp,
        shadowOffset = if (variant == SlockRaftUiButtonVariant.Outline) 2.dp else 3.dp,
        textStyle = SlockType.ButtonBold.copy(fontSize = if (size == SlockRaftUiBannerSize.Small) 12.sp else 14.sp, lineHeight = 14.sp)
    )
}

@Composable
internal fun SlockRaftUiBadge(
    label: String,
    modifier: Modifier = Modifier,
    variant: SlockRaftUiBadgeVariant = SlockRaftUiBadgeVariant.Information,
    uppercase: Boolean = false
) {
    SlockBrutalBadge(label = label, modifier = modifier, tone = variant.tone, uppercase = uppercase)
}

@Composable
internal fun SlockRaftUiBanner(
    status: SlockRaftUiBannerStatus,
    title: String? = null,
    description: String,
    modifier: Modifier = Modifier,
    size: SlockRaftUiBannerSize = SlockRaftUiBannerSize.Medium,
    iconName: SlockLucideIconName? = null,
    action: (@Composable () -> Unit)? = null
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(status.background)
                .border(SlockStroke.Default, SlockColor.BorderBlack, RectangleShape)
                .padding(size.padding),
        horizontalArrangement = Arrangement.spacedBy(size.gap),
        verticalAlignment = Alignment.Top
    ) {
        iconName?.let {
            SlockLucideIcon(it, modifier = Modifier.size(18.dp), color = SlockColor.TextBlackArgb)
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            title?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = SlockType.ButtonBold.copy(fontSize = size.titleSize.sp, lineHeight = (size.titleSize + 4).sp),
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = description,
                style = SlockType.Body.copy(fontSize = size.bodySize.sp, lineHeight = (size.bodySize + 4).sp),
                color = SlockColor.TextBlack,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        action?.invoke()
    }
}

@Composable
internal fun SlockRaftUiEmptyState(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    iconText: String = "∅",
    action: (@Composable () -> Unit)? = null
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = iconText,
            style = SlockType.PageTitle.copy(fontSize = 34.sp, lineHeight = 38.sp),
            color = SlockColor.TextBlack.copy(alpha = 0.55f),
            maxLines = 1
        )
        Text(
            text = title,
            modifier = Modifier.padding(top = 6.dp),
            style = SlockType.PageTitle.copy(fontSize = 18.sp, lineHeight = 22.sp),
            color = SlockColor.TextBlack.copy(alpha = 0.60f),
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = description,
            modifier = Modifier.widthIn(max = 260.dp).padding(top = 6.dp),
            style = SlockType.Body.copy(fontSize = 14.sp, lineHeight = 20.sp),
            color = SlockColor.TextBlack.copy(alpha = 0.60f),
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
        action?.let {
            Box(modifier = Modifier.padding(top = 18.dp)) {
                it()
            }
        }
    }
}

@Composable
internal fun SlockRaftUiTextHeading(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        style = SlockType.PageTitle.copy(fontSize = 24.sp, lineHeight = 32.sp).withSlockFontWeight(FontWeight.Medium),
        color = SlockColor.TextBlack,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
internal fun SlockRaftUiTextSans(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        style = SlockType.Body.copy(fontSize = 14.sp, lineHeight = 20.sp),
        color = SlockColor.TextBlack.copy(alpha = 0.70f),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
internal fun SlockRaftUiTextMono(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        style = SlockType.Caption.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp),
        color = SlockColor.TextBlack.copy(alpha = 0.55f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
internal fun SlockRaftUiInlineCode(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier =
            modifier
                .background(SlockColor.BrandYellow.copy(alpha = 0.40f))
                .border(SlockStroke.Thin, SlockColor.BorderBlack)
                .padding(horizontal = 4.dp),
        style = SlockType.Caption.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp),
        color = SlockColor.TextBlack,
        maxLines = 1
    )
}

@Composable
internal fun SlockRaftUiKbd(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.padding(horizontal = 2.dp, vertical = 1.dp),
        style = SlockType.Body.copy(fontSize = 13.sp, lineHeight = 16.sp).withSlockFontWeight(FontWeight.Medium),
        color = SlockColor.TextBlack.copy(alpha = 0.70f),
        maxLines = 1
    )
}

@Composable
internal fun SlockRaftUiField(
    label: String,
    modifier: Modifier = Modifier,
    required: Boolean = false,
    description: String? = null,
    error: String? = null,
    content: @Composable () -> Unit
) {
    SlockFormField(
        label = label,
        modifier = modifier,
        required = required,
        hint = description,
        error = error,
        labelStyle = SlockFormFieldLabelStyle.Plain
    ) {
        content()
    }
}

@Composable
internal fun SlockRaftUiInputGroup(
    value: String,
    modifier: Modifier = Modifier,
    leadingText: String? = null,
    trailingText: String? = null,
    invalid: Boolean = false
) {
    val fieldHeight = 42.dp
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(fieldHeight)
                .background(if (invalid) Color(0xFFFFF4F4) else SlockColor.SurfaceWhite)
                .border(SlockStroke.Default, if (invalid) SlockColor.AccentRed else SlockColor.BorderBlack),
        verticalAlignment = Alignment.CenterVertically
    ) {
        leadingText?.let {
            SlockRaftUiInputGroupAddon(it, alignStart = true)
        }
        Text(
            text = value,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            style = SlockType.Input.copy(fontSize = 14.sp, lineHeight = 18.sp),
            color = SlockColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        trailingText?.let {
            SlockRaftUiInputGroupAddon(it, alignStart = false)
        }
    }
}

@Composable
private fun SlockRaftUiInputGroupAddon(text: String, alignStart: Boolean) {
    Box(
        modifier =
            Modifier
                .fillMaxHeight()
                .background(SlockColor.BrandYellow.copy(alpha = 0.30f))
                .border(SlockStroke.Thin, SlockColor.BorderBlack)
                .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = SlockType.ButtonBold.copy(fontSize = 14.sp, lineHeight = 18.sp),
            color = SlockColor.TextBlack.copy(alpha = 0.70f),
            maxLines = 1
        )
    }
}

@Composable
internal fun SlockRaftUiAvatar(
    label: String,
    modifier: Modifier = Modifier,
    type: SlockRaftUiAvatarType = SlockRaftUiAvatarType.Agent,
    size: Dp = 32.dp,
    badge: Boolean = false
) {
    Box(modifier = modifier.size(size + if (badge) 8.dp else 0.dp)) {
        SlockAvatar(
            avatarWireValue = null,
            label = label,
            avatarSize = size,
            borderWidth = if (size >= 32.dp) SlockStroke.Default else SlockStroke.Thin,
            modifier = Modifier.alpha(if (type == SlockRaftUiAvatarType.Agent) 1f else 0.95f)
        )
        if (badge) {
            Box(
                modifier =
                    Modifier
                        .size(if (size >= 48.dp) 14.dp else 10.dp)
                        .align(Alignment.BottomEnd)
                        .offset(x = 1.dp, y = 1.dp)
                        .background(SlockColor.AccentLime)
                        .border(SlockStroke.Thin, SlockColor.SurfaceWhite)
            )
        }
    }
}

internal enum class SlockRaftUiAvatarType {
    Agent,
    Human
}

@Composable
internal fun SlockRaftUiAvatarGroup(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy((-6).dp), verticalAlignment = Alignment.CenterVertically) {
        content()
    }
}

@Composable
internal fun SlockRaftUiTabs(
    items: List<SlockRaftUiTabItem>,
    selectedValue: String,
    modifier: Modifier = Modifier,
    panelText: String
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(
            modifier =
                Modifier
                    .background(SlockColor.SurfaceWhite)
                    .border(SlockStroke.Default, SlockColor.BorderBlack),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEachIndexed { index, item ->
                val selected = item.value == selectedValue
                Box(
                    modifier =
                        Modifier
                            .background(if (selected) SlockColor.BrandYellow else SlockColor.SurfaceWhite)
                            .border(if (index == 0) 0.dp else SlockStroke.Thin, SlockColor.BorderBlack)
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = item.label,
                        style = SlockType.ButtonBold.copy(fontSize = 12.sp, lineHeight = 16.sp),
                        color = SlockColor.TextBlack,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(SlockColor.SurfaceWhite)
                    .border(SlockStroke.Default, SlockColor.BorderBlack)
                    .padding(12.dp)
        ) {
            SlockRaftUiTextSans(panelText)
        }
    }
}
