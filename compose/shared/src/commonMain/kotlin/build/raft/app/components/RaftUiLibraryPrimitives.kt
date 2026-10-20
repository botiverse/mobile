package build.raft.app.components

import build.raft.app.RaftAppAvatar
import build.raft.app.RaftAvatarStep
import build.raft.app.RaftAvatarWithStatus
import build.raft.app.RaftBrutalDropdownMenuItem
import build.raft.app.RaftMonoFamily
import build.raft.app.RaftBrutalDropdownMenuSurface
import build.raft.app.RaftColor
import build.raft.app.RaftLucideIcon
import build.raft.app.RaftLucideIconName
import build.raft.app.RaftAppRadius
import build.raft.app.RaftShadow
import build.raft.app.RaftAppStroke
import build.raft.app.RaftType
import build.raft.app.withRaftFontWeight
import androidx.compose.runtime.Composable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal enum class RaftUiBannerStatus(val background: Color) {
    // deliberate react-parity wash (web bg-*/N tint family); triaged token-audit #1042
    Info(RaftColor.BrandYellow.copy(alpha = 0.30f)),
    // deliberate react-parity wash (web bg-*/N tint family); triaged token-audit #1042
    Warning(RaftColor.AccentOrange.copy(alpha = 0.20f)),
    // deliberate react-parity wash (web bg-*/N tint family); triaged token-audit #1042
    Destructive(RaftColor.AccentRed.copy(alpha = 0.20f)),
    // deliberate react-parity wash (web bg-*/N tint family); triaged token-audit #1042
    Success(RaftColor.AccentLime.copy(alpha = 0.20f))
}

/** raft-ui 0.6.0 Banner fills: the status `-soft` wash (raft `info` is cyan, not yellow). */
internal val RaftUiBannerStatus.raftFill: Color
    get() =
        when (this) {
            RaftUiBannerStatus.Info -> RaftColor.InfoSoft
            RaftUiBannerStatus.Warning -> RaftColor.WarningSoft
            RaftUiBannerStatus.Destructive -> RaftColor.DangerSoft
            RaftUiBannerStatus.Success -> RaftColor.SuccessSoft
        }

internal enum class RaftUiBannerSize(val padding: Dp, val gap: Dp, val titleSize: Int, val bodySize: Int) {
    Small(8.dp, 8.dp, 12, 12),
    Medium(12.dp, 12.dp, 14, 14)
}

internal enum class RaftUiButtonVariant(val background: Color) {
    Primary(RaftColor.BrandYellow),
    Default(RaftColor.SurfaceWhite),
    Danger(RaftColor.AccentRed),
    Muted(RaftColor.SurfaceCream),
    Outline(RaftColor.SurfaceWhite)
}

internal enum class RaftUiBadgeVariant(val tone: RaftBrutalTone) {
    Success(RaftBrutalTone.Lime),
    Information(RaftBrutalTone.Cyan),
    Warning(RaftBrutalTone.Warning),
    Danger(RaftBrutalTone.Red)
}

internal data class RaftUiTabItem(val value: String, val label: String)

internal data class RaftUiSegmentedItem(val value: String, val label: String, val count: Int? = null)

internal data class RaftUiMenuItem(
    val id: String,
    val label: String,
    val icon: RaftLucideIconName? = null,
    val trailing: String? = null,
    val selected: Boolean = false,
    val destructive: Boolean = false,
    val enabled: Boolean = true,
    val dividerBefore: Boolean = false
)

@Composable
internal fun RaftUiButton(
    label: String,
    modifier: Modifier = Modifier,
    variant: RaftUiButtonVariant = RaftUiButtonVariant.Default,
    enabled: Boolean = true,
    size: RaftUiBannerSize = RaftUiBannerSize.Small
) {
    RaftBrutalActionButton(
        label = label,
        onClick = {},
        modifier = modifier,
        enabled = enabled,
        backgroundColor = variant.background,
        minHeight = if (size == RaftUiBannerSize.Small) 28.dp else 32.dp,
        minWidth = 0.dp,
        shadowOffset = if (variant == RaftUiButtonVariant.Outline) RaftShadow.BrutalSmall else RaftShadow.Brutal,
        textStyle = RaftType.ButtonBold.copy(fontSize = if (size == RaftUiBannerSize.Small) 12.sp else 14.sp, lineHeight = 14.sp)
    )
}

@Composable
internal fun RaftUiBadge(
    label: String,
    modifier: Modifier = Modifier,
    variant: RaftUiBadgeVariant = RaftUiBadgeVariant.Information,
    uppercase: Boolean = false
) {
    RaftBrutalBadge(label = label, modifier = modifier, tone = variant.tone, uppercase = uppercase)
}

@Composable
internal fun RaftUiLabel(
    text: String,
    modifier: Modifier = Modifier,
    required: Boolean = false,
    optional: Boolean = false,
    small: Boolean = false,
    disabled: Boolean = false
) {
    RaftLookFieldLabel(
        label = text,
        required = required,
        optional = optional,
        compact = small,
        modifier = modifier,
        disabled = disabled
    )
}

@Composable
internal fun RaftUiRadioRow(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        RaftCheckMarker(
            checked = selected,
            shape = RaftCheckMarkerShape.Circle,
            tone = RaftCheckMarkerTone.YellowFill,
            disabled = !enabled,
            // raft radio: `size-5`.
            size = RaftMetrics.CheckboxSize
        )
        Text(
            text = label,
            style =
                RaftType.Body
                    .copy(fontSize = 14.sp, lineHeight = 20.sp)
                    .withRaftFontWeight(FontWeight.SemiBold),
            color = if (enabled) RaftColor.TextBlack else RaftColor.ForegroundDisabled,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun RaftUiCheckboxLabelRow(
    label: String,
    checked: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 16.dp
) {
    RaftCheckboxLabelRow(label = label, checked = checked, modifier = modifier, enabled = enabled, size = size)
}

@Composable
internal fun RaftUiStatusDot(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 10.dp,
    pulsing: Boolean = false
) {
    RaftAppStatusDot(modifier = modifier, size = size, color = color, pulsing = pulsing)
}

@Composable
internal fun RaftUiSpinner(
    modifier: Modifier = Modifier,
    size: RaftAppSpinnerSize = RaftAppSpinnerSize.Sm,
    inverse: Boolean = false
) {
    RaftAppSpinner(modifier = modifier, size = size, inverse = inverse)
}

@Composable
internal fun RaftUiSegmentedControl(
    items: List<RaftUiSegmentedItem>,
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
internal fun RaftUiBanner(
    status: RaftUiBannerStatus,
    title: String? = null,
    description: String,
    modifier: Modifier = Modifier,
    size: RaftUiBannerSize = RaftUiBannerSize.Medium,
    iconName: RaftLucideIconName? = null,
    action: (@Composable () -> Unit)? = null
) {
    RaftLookBanner(
        status = status,
        title = title,
        description = description,
        modifier = modifier,
        size = size,
        iconName = iconName,
        action = action
    )
}

/**
 * raft-ui 0.6.0 Banner: container radius 10dp, status `-soft` fill, 1dp inset
 * `--line-hairline` ring, ink type. `md`: 14/12dp padding, 10dp gap, callout 14/20, 20dp
 * icon; `sm`: 12/8dp padding, 8dp gap, footnote 13/18, 16dp icon. Title is bold.
 */
@Composable
private fun RaftLookBanner(
    status: RaftUiBannerStatus,
    title: String?,
    description: String,
    modifier: Modifier,
    size: RaftUiBannerSize,
    iconName: RaftLucideIconName?,
    action: (@Composable () -> Unit)?
) {
    val small = size == RaftUiBannerSize.Small
    val shape = RoundedCornerShape(RaftAppRadius.Container)
    val fontSize = if (small) 13.sp else 14.sp
    val lineHeight = if (small) 18.sp else 20.sp
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(status.raftFill, shape)
                .border(RaftAppStroke.Thin, RaftColor.BorderHairline, shape)
                .padding(horizontal = if (small) 12.dp else 14.dp, vertical = if (small) 8.dp else 12.dp),
        horizontalArrangement = Arrangement.spacedBy(if (small) 8.dp else 10.dp),
        verticalAlignment = Alignment.Top
    ) {
        iconName?.let {
            RaftLucideIcon(it, modifier = Modifier.size(if (small) 16.dp else 20.dp), color = RaftColor.TextBlackArgb)
        }
        Column(modifier = Modifier.weight(1f)) {
            title?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = RaftType.Body.copy(fontSize = fontSize, lineHeight = lineHeight).withRaftFontWeight(FontWeight.Bold),
                    color = RaftColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = description,
                style = RaftType.Body.copy(fontSize = fontSize, lineHeight = lineHeight),
                color = RaftColor.TextBlack,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            action?.let {
                Box(modifier = Modifier.padding(top = 8.dp)) {
                    it()
                }
            }
        }
    }
}

@Composable
internal fun RaftUiSuccessBanner(
    description: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    size: RaftUiBannerSize = RaftUiBannerSize.Small
) {
    RaftUiBanner(
        status = RaftUiBannerStatus.Success,
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
                    style = RaftType.Caption.withRaftFontWeight(FontWeight.Bold),
                    color = RaftColor.TextBlack,
                    maxLines = 1
                )
                RaftLucideIcon(
                    RaftLucideIconName.ChevronRight,
                    modifier = Modifier.size(16.dp),
                    color = RaftColor.TextBlackArgb
                )
            }
        }
    )
}

@Composable
internal fun RaftUiEmptyState(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    iconText: String = "∅",
    action: (@Composable () -> Unit)? = null
) {
    // raft-ui EmptyState: px-6 py-12, 8dp gap, hint-ink icon (36), headline 17/22 bold
    // muted title, callout 14/20 hint description (max 300dp), actions 20dp below.
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = iconText,
            style = RaftType.PageTitle.copy(fontSize = 34.sp, lineHeight = 38.sp),
            color = RaftColor.ForegroundHint,
            maxLines = 1
        )
        Text(
            text = title,
            style = RaftType.PageTitle.copy(fontSize = 17.sp, lineHeight = 22.sp).withRaftFontWeight(FontWeight.Bold),
            color = RaftColor.TextMuted,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = description,
            modifier = Modifier.widthIn(max = 300.dp),
            style = RaftType.Body.copy(fontSize = 14.sp, lineHeight = 20.sp),
            color = RaftColor.ForegroundHint,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
        action?.let {
            Box(modifier = Modifier.padding(top = 12.dp)) {
                it()
            }
        }
    }
}

@Composable
internal fun RaftUiTextHeading(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        style = RaftType.PageTitle.copy(fontSize = 24.sp, lineHeight = 32.sp).withRaftFontWeight(FontWeight.Medium),
        color = RaftColor.TextBlack,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
internal fun RaftUiTextSans(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        style = RaftType.Body.copy(fontSize = 14.sp, lineHeight = 20.sp),
        color = RaftColor.TextBlack.copy(alpha = 0.70f),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
internal fun RaftUiTextMono(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        style = RaftType.Caption.copy(fontFamily = RaftMonoFamily, fontSize = 12.sp, lineHeight = 16.sp),
        color = RaftColor.TextBlack.copy(alpha = 0.55f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
internal fun RaftUiInlineCode(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier =
            modifier
                // deliberate react-parity wash (web bg-*/N tint family); triaged token-audit #1042
                .background(RaftColor.BrandYellow.copy(alpha = 0.40f))
                .border(RaftAppStroke.Thin, RaftColor.BorderBlack)
                .padding(horizontal = 4.dp)
    ) {
        Text(
            text = text,
            style = RaftType.Caption.copy(fontFamily = RaftMonoFamily, fontSize = 13.sp, lineHeight = 18.sp),
            color = RaftColor.TextBlack,
            maxLines = 1
        )
    }
}

@Composable
internal fun RaftUiKbd(text: String, modifier: Modifier = Modifier) {
    // raft-ui Kbd: mono 12/16 hint ink, 3dp radius, 4dp side padding, 1dp inset
    // `--line-hairline` ring, min 16dp wide.
    val shape = RoundedCornerShape(3.dp)
    Box(
        modifier =
            modifier
                .widthIn(min = 16.dp)
                .border(RaftAppStroke.Thin, RaftColor.BorderHairline, shape)
                .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = RaftType.Caption.copy(fontFamily = RaftMonoFamily, fontSize = 12.sp, lineHeight = 16.sp),
            color = RaftColor.ForegroundHint,
            maxLines = 1
        )
    }
}

@Composable
internal fun RaftUiField(
    label: String,
    modifier: Modifier = Modifier,
    required: Boolean = false,
    description: String? = null,
    error: String? = null,
    content: @Composable () -> Unit
) {
    RaftFormField(
        label = label,
        modifier = modifier,
        required = required,
        hint = description,
        error = error,
        labelStyle = RaftFormFieldLabelStyle.Plain
    ) {
        content()
    }
}

@Composable
internal fun RaftUiTextarea(
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
    RaftBrutalTextarea(
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
internal fun RaftUiInputGroup(
    value: String,
    modifier: Modifier = Modifier,
    leadingText: String? = null,
    trailingText: String? = null,
    invalid: Boolean = false
) {
    RaftUiInputGroupRaft(value = value, modifier = modifier, leadingText = leadingText, trailingText = trailingText, invalid = invalid)
}

/**
 * raft-ui InputGroup with `container` text addons: 44dp, control radius, 1.5dp
 * `--line-strong` edge (`--danger-strong` when invalid) on the card layer; addons are
 * `--fill-muted` cells behind 1dp `--line-hairline` dividers, mono 13/20 placeholder ink.
 */
@Composable
private fun RaftUiInputGroupRaft(
    value: String,
    modifier: Modifier,
    leadingText: String?,
    trailingText: String?,
    invalid: Boolean
) {
    val shape = RoundedCornerShape(RaftAppRadius.Control)
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(RaftMetrics.FieldHeight)
                .clip(shape)
                .background(RaftColor.Card, shape)
                .border(RaftAppStroke.Control, if (invalid) RaftColor.DangerStrong else RaftColor.LineField, shape),
        verticalAlignment = Alignment.CenterVertically
    ) {
        leadingText?.let {
            RaftUiInputGroupAddonCell(it)
            Box(modifier = Modifier.width(RaftAppStroke.Thin).fillMaxHeight().background(RaftColor.BorderHairline))
        }
        Text(
            text = value,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            style = RaftType.Input.copy(fontSize = 16.sp, lineHeight = 24.sp),
            color = RaftColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        trailingText?.let {
            Box(modifier = Modifier.width(RaftAppStroke.Thin).fillMaxHeight().background(RaftColor.BorderHairline))
            RaftUiInputGroupAddonCell(it)
        }
    }
}

@Composable
private fun RaftUiInputGroupAddonCell(text: String) {
    Box(
        modifier =
            Modifier
                .fillMaxHeight()
                .background(RaftColor.FillMuted)
                .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = RaftType.Caption.copy(fontFamily = RaftMonoFamily, fontSize = 13.sp, lineHeight = 20.sp),
            color = RaftColor.ForegroundHint,
            maxLines = 1
        )
    }
}

@Composable
internal fun RaftUiAvatar(
    label: String,
    modifier: Modifier = Modifier,
    type: RaftUiAvatarType = RaftUiAvatarType.Agent,
    size: Dp = 32.dp,
    badge: Boolean = false
) {
    // raft-ui Avatar: the app avatar's square sticker (4dp radius, 1.5dp sticker-ink
    // edge, sticker-edge ring; agents on `inactive`, humans lavender, initials sized per
    // step) with the AvatarBadge presence light scaled to the nearest raft-ui step.
    // Reserve what the light passes the avatar's edge by: its overhang plus the cut-out.
    val step = RaftAvatarStep.nearest(size)
    Box(modifier = modifier.size(size + if (badge) step.badgeOverhang + step.badgeCutout else 0.dp)) {
        RaftAvatarWithStatus(
            statusColor = if (badge) RaftColor.PresenceOnline else null,
            avatarSize = size
        ) {
            RaftAppAvatar(
                avatarWireValue = null,
                label = label,
                avatarSize = size,
                fallbackFill = if (type == RaftUiAvatarType.Agent) RaftColor.Inactive else RaftColor.AccentLavender
            )
        }
    }
}

internal enum class RaftUiAvatarType {
    Agent,
    Human
}

internal enum class RaftUiSurfaceStatus(val icon: RaftLucideIconName, val background: Color) {
    Success(RaftLucideIconName.CircleCheck, RaftColor.AccentLime),
    Warning(RaftLucideIconName.TriangleAlert, RaftColor.AccentOrange),
    Info(RaftLucideIconName.Info, RaftColor.BrandYellow)
}

@Composable
internal fun RaftUiAvatarGroup(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy((-6).dp), verticalAlignment = Alignment.CenterVertically) {
        content()
    }
}

@Composable
internal fun RaftUiTabs(
    items: List<RaftUiTabItem>,
    selectedValue: String,
    modifier: Modifier = Modifier,
    panelText: String
) {
    // raft-ui Tabs (`variant="default"`): a 2dp-gap row of 32dp control-radius tabs;
    // the active tab is the yellow `--brand` sticker (ink edge, bold ink type). The
    // panel has no chrome of its own.
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
            items.forEach { item ->
                RaftSegmentedItem(
                    label = item.label,
                    selected = item.value == selectedValue,
                    enabled = true,
                    onClick = {}
                )
            }
        }
        RaftUiTextSans(panelText)
    }
}

@Composable
internal fun RaftUiSelectTrigger(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    expanded: Boolean = false,
    // Keep in sync with RaftAppSelectTrigger's 40dp react-parity default (task #370).
    height: Dp = 40.dp,
    onClick: () -> Unit = {}
) {
    RaftAppSelectTrigger(
        label = label,
        modifier = modifier,
        enabled = enabled,
        expanded = expanded,
        height = height,
        onClick = onClick
    )
}

@Composable
internal fun RaftUiMenuSurface(
    items: List<RaftUiMenuItem>,
    modifier: Modifier = Modifier,
    width: Dp? = 310.dp,
    rowHeight: Dp = 36.dp,
    shadowOffset: Dp = RaftShadow.Brutal,
    showDividers: Boolean = false,
    onSelect: (String) -> Unit = {},
    onDismiss: () -> Unit = {}
) {
    RaftBrutalDropdownMenuSurface(
        items =
            items.map { item ->
                RaftBrutalDropdownMenuItem(
                    id = item.id,
                    label = item.label,
                    icon = item.icon,
                    trailing = item.trailing,
                    trailingIcon = if (item.selected && item.trailing == null) RaftLucideIconName.Check else null,
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
internal fun RaftUiPopupSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    // raft popup: container radius, popover layer, seam ring, float shadow, 6dp inset.
    Column(modifier = modifier.raftLookOverlaySurface().padding(RaftMetrics.PopupPadding)) {
        content()
    }
}

@Composable
internal fun RaftUiSurfaceLabel(text: String, modifier: Modifier = Modifier) {
    // raft menu group label: px-2.5 pt-2 pb-1, caption 12/16 bold hint ink, no rule.
    Box(modifier = modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 4.dp)) {
        Text(
            text = text,
            style = RaftType.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp).withRaftFontWeight(FontWeight.Bold),
            color = RaftColor.ForegroundHint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun RaftUiMenuRow(
    label: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    highlighted: Boolean = false,
    disabled: Boolean = false
) {
    // raft menu item: min 36dp, control radius, px-2.5, callout 14/20 medium; the
    // highlighted row is `--brand-soft`; shortcut text is mono 12/16 hint.
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(RaftMetrics.MenuRowHeight)
                .background(
                    if (highlighted && !disabled) RaftColor.BrandSoft else Color.Transparent,
                    RoundedCornerShape(RaftAppRadius.Control)
                )
                .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = RaftType.Body.copy(fontSize = 14.sp, lineHeight = 20.sp).withRaftFontWeight(FontWeight.Medium),
            color = if (disabled) RaftColor.ForegroundDisabled else RaftColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        trailing?.let {
            Text(
                text = it,
                style = RaftType.Caption.copy(fontFamily = RaftMonoFamily, fontSize = 12.sp, lineHeight = 16.sp),
                color = if (disabled) RaftColor.ForegroundDisabled else RaftColor.ForegroundHint,
                maxLines = 1
            )
        }
    }
}

@Composable
internal fun RaftUiNotificationItem(
    title: String,
    body: String,
    status: RaftUiSurfaceStatus,
    modifier: Modifier = Modifier,
    read: Boolean = false,
    actionLabel: String? = null
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .alpha(if (read) 0.70f else 1f)
                .then(Modifier.raftCardSurface())
                .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        val tileShape = RoundedCornerShape(RaftAppRadius.Sticker)
        Box(
            modifier =
                Modifier
                    .size(24.dp)
                    .background(status.background, tileShape)
                    .border(RaftAppStroke.Default, RaftColor.BorderBlack, tileShape),
            contentAlignment = Alignment.Center
        ) {
            RaftLucideIcon(status.icon, modifier = Modifier.size(14.dp), color = RaftColor.TextBlackArgb)
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = title,
                style = RaftType.ButtonBold.copy(fontSize = 14.sp, lineHeight = 18.sp),
                color = RaftColor.TextBlack,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = body,
                style = RaftType.Body.copy(fontSize = 13.sp, lineHeight = 18.sp),
                color = RaftColor.TextBlack.copy(alpha = 0.70f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            actionLabel?.let {
                Box(modifier = Modifier.padding(top = 6.dp).align(Alignment.End)) {
                    RaftUiButton(label = it, variant = RaftUiButtonVariant.Primary)
                }
            }
        }
    }
}
