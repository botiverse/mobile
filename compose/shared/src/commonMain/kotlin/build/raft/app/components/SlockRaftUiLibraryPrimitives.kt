package build.raft.app.components

import build.raft.app.SlockAvatar
import build.raft.app.SlockBrutalDropdownMenuItem
import build.raft.app.SlockMonoFamily
import build.raft.app.SlockBrutalDropdownMenuSurface
import build.raft.app.SlockColor
import build.raft.app.SlockLucideIcon
import build.raft.app.SlockLucideIconName
import build.raft.app.SlockShadow
import build.raft.app.SlockStroke
import build.raft.app.SlockType
import build.raft.app.slockSquareCorners
import build.raft.app.withSlockFontWeight
import build.raft.app.resources.MR
import androidx.compose.runtime.Composable
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.clickable
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
import com.tencent.kuikly.compose.ui.semantics.Role
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.graphics.RectangleShape
import com.tencent.kuikly.compose.ui.text.font.FontFamily
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.style.TextAlign
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.tmm.kmmresource.compose.stringResource

internal enum class SlockRaftUiBannerStatus(val background: Color) {
    // deliberate react-parity wash (web bg-*/N tint family); triaged token-audit #1042
    Info(SlockColor.BrandYellow.copy(alpha = 0.30f)),
    // deliberate react-parity wash (web bg-*/N tint family); triaged token-audit #1042
    Warning(SlockColor.AccentOrange.copy(alpha = 0.20f)),
    // deliberate react-parity wash (web bg-*/N tint family); triaged token-audit #1042
    Destructive(SlockColor.AccentRed.copy(alpha = 0.20f)),
    // deliberate react-parity wash (web bg-*/N tint family); triaged token-audit #1042
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
    Warning(SlockBrutalTone.Warning),
    Danger(SlockBrutalTone.Red)
}

internal data class SlockRaftUiTabItem(val value: String, val label: String)

internal data class SlockRaftUiSegmentedItem(val value: String, val label: String, val count: Int? = null)

internal data class SlockRaftUiMenuItem(
    val id: String,
    val label: String,
    val icon: SlockLucideIconName? = null,
    val trailing: String? = null,
    val selected: Boolean = false,
    val destructive: Boolean = false,
    val enabled: Boolean = true,
    val dividerBefore: Boolean = false
)

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
        shadowOffset = if (variant == SlockRaftUiButtonVariant.Outline) SlockShadow.BrutalSmall else SlockShadow.Brutal,
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
internal fun SlockRaftUiLabel(
    text: String,
    modifier: Modifier = Modifier,
    required: Boolean = false,
    optional: Boolean = false,
    small: Boolean = false,
    disabled: Boolean = false
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = text,
            style =
                SlockType.ButtonBold.copy(
                    fontSize = if (small) 12.sp else 14.sp,
                    lineHeight = if (small) 14.sp else 18.sp
                ),
            color = if (disabled) SlockColor.TextMutedGray else SlockColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (required) {
            Text(
                text = "*",
                style = SlockType.ButtonBold.copy(fontSize = if (small) 12.sp else 14.sp, lineHeight = if (small) 14.sp else 18.sp),
                color = if (disabled) SlockColor.TextMutedGray else SlockColor.AccentPink,
                maxLines = 1
            )
        }
        if (optional) {
            Text(
                text = stringResource(MR.strings.common_optional_suffix),
                style =
                    SlockType.Caption.copy(fontSize = if (small) 11.sp else 12.sp, lineHeight = if (small) 13.sp else 14.sp)
                        .withSlockFontWeight(FontWeight.Bold),
                color = if (disabled) SlockColor.TextMutedGray else SlockColor.TextBlack.copy(alpha = 0.40f),
                maxLines = 1
            )
        }
    }
}

@Composable
internal fun SlockRaftUiRadioRow(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        SlockCheckMarker(
            checked = selected,
            shape = SlockCheckMarkerShape.Circle,
            tone = SlockCheckMarkerTone.YellowFill,
            disabled = !enabled,
            size = 16.dp
        )
        Text(
            text = label,
            style = SlockType.Body.copy(fontSize = 14.sp, lineHeight = 18.sp).withSlockFontWeight(FontWeight.Medium),
            color = if (enabled) SlockColor.TextBlack else SlockColor.TextBlack.copy(alpha = 0.45f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun SlockRaftUiCheckboxLabelRow(
    label: String,
    checked: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 16.dp
) {
    SlockCheckboxLabelRow(label = label, checked = checked, modifier = modifier, enabled = enabled, size = size)
}

@Composable
internal fun SlockRaftUiStatusDot(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 10.dp,
    pulsing: Boolean = false
) {
    SlockStatusDot(modifier = modifier, size = size, color = color, pulsing = pulsing)
}

@Composable
internal fun SlockRaftUiSpinner(
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    inverse: Boolean = false
) {
    SlockSpinner(modifier = modifier, size = size, inverse = inverse)
}

@Composable
internal fun SlockRaftUiSegmentedControl(
    items: List<SlockRaftUiSegmentedItem>,
    selectedValue: String,
    modifier: Modifier = Modifier
) {
    SegmentedControl(
        selectedValue = selectedValue,
        options = items.map { SegmentedControlOption(value = it.value, label = it.label, count = it.count) },
        onValueSelected = {},
        modifier = modifier
    )
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
                .slockSquareCorners()
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
internal fun SlockRaftUiSuccessBanner(
    description: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    size: SlockRaftUiBannerSize = SlockRaftUiBannerSize.Small
) {
    SlockRaftUiBanner(
        status = SlockRaftUiBannerStatus.Success,
        description = description,
        modifier = modifier,
        size = size,
        action = {
            Row(
                modifier = Modifier.clickable(role = Role.Button, onClick = onAction),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = actionLabel,
                    style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold),
                    color = SlockColor.TextBlack,
                    maxLines = 1
                )
                SlockLucideIcon(
                    SlockLucideIconName.ChevronRight,
                    modifier = Modifier.size(16.dp),
                    color = SlockColor.TextBlackArgb
                )
            }
        }
    )
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
        style = SlockType.Caption.copy(fontFamily = SlockMonoFamily, fontSize = 12.sp, lineHeight = 16.sp),
        color = SlockColor.TextBlack.copy(alpha = 0.55f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
internal fun SlockRaftUiInlineCode(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier =
            modifier
                // deliberate react-parity wash (web bg-*/N tint family); triaged token-audit #1042
                .background(SlockColor.BrandYellow.copy(alpha = 0.40f))
                .border(SlockStroke.Thin, SlockColor.BorderBlack)
                .padding(horizontal = 4.dp)
    ) {
        Text(
            text = text,
            style = SlockType.Caption.copy(fontFamily = SlockMonoFamily, fontSize = 13.sp, lineHeight = 18.sp),
            color = SlockColor.TextBlack,
            maxLines = 1
        )
    }
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
internal fun SlockRaftUiTextarea(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    hint: String? = null,
    error: String? = null,
    showCounter: Boolean = false,
    limit: Int? = null,
    enabled: Boolean = true,
    fieldHeight: Dp = 76.dp,
    maxLines: Int = 3
) {
    SlockBrutalTextarea(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        placeholder = placeholder,
        hint = hint,
        error = error,
        showCounter = showCounter,
        limit = limit,
        enabled = enabled,
        fieldHeight = fieldHeight,
        maxLines = maxLines
    )
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
                // deliberate react-parity wash (web bg-*/N tint family); triaged token-audit #1042
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

internal enum class SlockRaftUiSurfaceStatus(val icon: SlockLucideIconName, val background: Color) {
    Success(SlockLucideIconName.CircleCheck, SlockColor.AccentLime),
    Warning(SlockLucideIconName.TriangleAlert, SlockColor.AccentOrange),
    Info(SlockLucideIconName.Info, SlockColor.BrandYellow)
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

@Composable
internal fun SlockRaftUiSelectTrigger(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    expanded: Boolean = false,
    // Keep in sync with SlockSelectTrigger's 40dp react-parity default (task #370).
    height: Dp = 40.dp,
    onClick: () -> Unit = {}
) {
    SlockSelectTrigger(
        label = label,
        modifier = modifier,
        enabled = enabled,
        expanded = expanded,
        height = height,
        onClick = onClick
    )
}

@Composable
internal fun SlockRaftUiMenuSurface(
    items: List<SlockRaftUiMenuItem>,
    modifier: Modifier = Modifier,
    width: Dp? = 310.dp,
    rowHeight: Dp = 36.dp,
    shadowOffset: Dp = SlockShadow.Brutal,
    showDividers: Boolean = false,
    onSelect: (String) -> Unit = {},
    onDismiss: () -> Unit = {}
) {
    SlockBrutalDropdownMenuSurface(
        items =
            items.map { item ->
                SlockBrutalDropdownMenuItem(
                    id = item.id,
                    label = item.label,
                    icon = item.icon,
                    trailing = item.trailing,
                    trailingIcon = if (item.selected && item.trailing == null) SlockLucideIconName.Check else null,
                    destructive = item.destructive,
                    enabled = item.enabled
                )
            },
        onSelect = onSelect,
        modifier = modifier,
        width = width,
        rowHeight = rowHeight,
        shadowOffset = shadowOffset,
        showDividers = showDividers,
        dividerBeforeIds = items.filter { it.dividerBefore }.map { it.id }.toSet(),
        onDismiss = onDismiss
    )
}

@Composable
internal fun SlockRaftUiPopupSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Column(
        modifier =
            modifier
                .background(SlockColor.SurfaceWhite)
                .border(SlockStroke.Default, SlockColor.BorderBlack)
    ) {
        content()
    }
}

@Composable
internal fun SlockRaftUiSurfaceLabel(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .border(SlockStroke.Thin, SlockColor.BorderBlack)
                .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            text = text,
            style = SlockType.Caption.copy(fontSize = 10.sp, lineHeight = 12.sp).withSlockFontWeight(FontWeight.Bold),
            color = SlockColor.TextBlack.copy(alpha = 0.60f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun SlockRaftUiMenuRow(
    label: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    highlighted: Boolean = false,
    disabled: Boolean = false
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(36.dp)
                // deliberate react-parity wash (web bg-*/N tint family); triaged token-audit #1042
                .background(if (highlighted) SlockColor.BrandYellow.copy(alpha = 0.30f) else SlockColor.SurfaceWhite)
                .border(SlockStroke.Thin, SlockColor.BorderBlack.copy(alpha = 0.10f))
                .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = SlockType.Body.copy(fontSize = 14.sp, lineHeight = 18.sp).withSlockFontWeight(FontWeight.SemiBold),
            color = if (disabled) SlockColor.TextBlack.copy(alpha = 0.30f) else SlockColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        trailing?.let {
            Text(
                text = it,
                style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 14.sp).withSlockFontWeight(FontWeight.Bold),
                color = if (disabled) SlockColor.TextBlack.copy(alpha = 0.30f) else SlockColor.TextBlack.copy(alpha = 0.60f),
                maxLines = 1
            )
        }
    }
}

@Composable
internal fun SlockRaftUiNotificationItem(
    title: String,
    body: String,
    status: SlockRaftUiSurfaceStatus,
    modifier: Modifier = Modifier,
    read: Boolean = false,
    actionLabel: String? = null
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .alpha(if (read) 0.70f else 1f)
                .border(SlockStroke.Thin, SlockColor.BorderBlack)
                .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier =
                Modifier
                    .size(24.dp)
                    .background(status.background)
                    .border(SlockStroke.Default, SlockColor.BorderBlack),
            contentAlignment = Alignment.Center
        ) {
            SlockLucideIcon(status.icon, modifier = Modifier.size(14.dp), color = SlockColor.TextBlackArgb)
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = title,
                style = SlockType.ButtonBold.copy(fontSize = 14.sp, lineHeight = 18.sp),
                color = SlockColor.TextBlack,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = body,
                style = SlockType.Body.copy(fontSize = 13.sp, lineHeight = 18.sp),
                color = SlockColor.TextBlack.copy(alpha = 0.70f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            actionLabel?.let {
                Box(modifier = Modifier.padding(top = 6.dp).align(Alignment.End)) {
                    SlockRaftUiButton(label = it, variant = SlockRaftUiButtonVariant.Primary)
                }
            }
        }
    }
}
