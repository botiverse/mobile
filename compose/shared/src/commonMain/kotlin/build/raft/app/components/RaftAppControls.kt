package build.raft.app.components

import build.raft.app.Button as RaftAnchorButton
import build.raft.app.RaftColor
import build.raft.app.RaftAppThemeState
import build.raft.app.RaftLucideIcon
import build.raft.app.RaftMonoFamily
import build.raft.app.RaftLucideIconName
import build.raft.app.RaftLiveRootAnchorRef
import build.raft.app.RaftPopoverAnchor
import build.raft.app.RaftPopoverLocalPoint
import build.raft.app.RaftAppRadius
import build.raft.app.RaftAppSpacing
import build.raft.app.RaftAppStroke
import build.raft.app.RaftType
import build.raft.app.raftCaptureLiveRootAnchor
import build.raft.app.withRaftFontWeight
import build.raft.app.raftArgbHex
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import build.raft.app.compat.ui.raftColorFromArgbHex

internal data class SegmentedControlOption(
    val value: String,
    val label: String,
    val iconName: RaftLucideIconName? = null,
    val count: Int? = null
)

internal val RaftDisabledActionBackground: Color
    get() = if (RaftAppThemeState.isDark) RaftColor.FillMuted else Color(0xFFFFD8E5) // raft-color: light legacy disabled fill
internal val RaftDisabledDestructiveActionBackground: Color
    get() = if (RaftAppThemeState.isDark) RaftColor.FillMuted else Color(0xFFFFE5E5) // raft-color: light legacy disabled fill
internal val RaftDisabledActionBorder: Color
    get() = if (RaftAppThemeState.isDark) RaftColor.BorderHairline else Color(0xFFB3B3B3) // raft-color: light legacy disabled edge
internal val RaftDisabledActionShadow: Color
    get() = if (RaftAppThemeState.isDark) RaftColor.BorderHairline else Color(0xFFB8B8B8) // raft-color: light legacy disabled shadow
internal val RaftDisabledActionContent: Color
    get() = if (RaftAppThemeState.isDark) RaftColor.ForegroundDisabled else RaftColor.SurfaceWhite
internal val RaftDisabledActionContentArgb: String
    get() = raftArgbHex(RaftDisabledActionContent)

internal fun raftDisabledActionBackgroundFor(activeColor: Color): Color =
    when (activeColor) {
        RaftColor.AccentRed -> RaftDisabledDestructiveActionBackground
        else -> RaftDisabledActionBackground
    }

internal enum class RaftAnchorPlacement {
    Tap,
    BottomStart,
    BottomEnd
}

internal enum class RaftMetaChipClickDispatch {
    Disabled,
    Plain,
    Anchored
}

internal fun resolveRaftMetaChipClickDispatch(
    hasPlainClick: Boolean,
    hasAnchoredClick: Boolean
): RaftMetaChipClickDispatch =
    when {
        hasPlainClick -> RaftMetaChipClickDispatch.Plain
        hasAnchoredClick -> RaftMetaChipClickDispatch.Anchored
        else -> RaftMetaChipClickDispatch.Disabled
    }

@Composable
internal fun RaftChipLoadingIndicator(
    modifier: Modifier = Modifier,
    size: Dp = 12.dp,
    color: String = RaftColor.TextMutedGrayArgb
) {
    RaftIconSpinner(modifier = modifier, size = size, color = raftColorFromArgbHex(color))
}

@Composable
internal fun RaftAnchoredMetaChip(
    label: String,
    iconName: RaftLucideIconName?,
    color: Color,
    onClick: ((RaftPopoverAnchor) -> Unit)?,
    onClickWithoutAnchor: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    iconSize: Dp = 10.dp,
    contentColor: Color = RaftColor.TextBlack,
    iconColorArgb: String = RaftColor.TextBlackArgb,
    iconFillArgb: String? = null,
    trailingIconName: RaftLucideIconName? = null,
    trailingIconSize: Dp = iconSize,
    borderWidth: Dp = RaftAppStroke.Thin,
    minHeight: Dp = 20.dp,
    horizontalPadding: Dp = 6.dp,
    verticalPadding: Dp = 2.dp,
    labelFontSize: TextUnit = 10.sp,
    labelLineHeight: TextUnit = 12.sp,
    pressFeedback: Boolean = onClick != null || onClickWithoutAnchor != null,
    anchorPlacement: RaftAnchorPlacement = RaftAnchorPlacement.Tap,
    clickIdentity: Any? = null,
    anchorOwnerIdentity: Any? = null,
    labelFontWeight: FontWeight = FontWeight.Bold,
    trailingIconColorArgb: String = RaftColor.TextMutedGrayArgb,
    leadingContent: (@Composable () -> Unit)? = null,
    contentSpacing: Dp = RaftAppSpacing.Xs
) {
    val clickDispatch =
        resolveRaftMetaChipClickDispatch(
            hasPlainClick = onClickWithoutAnchor != null,
            hasAnchoredClick = onClick != null
        )
    val resolvedAnchorOwnerIdentity = anchorOwnerIdentity ?: clickIdentity ?: "anchored_meta_chip:$label"
    val geometryAnchor =
        remember(resolvedAnchorOwnerIdentity) { RaftLiveRootAnchorRef(identity = resolvedAnchorOwnerIdentity) }
    // raft: chips are 6dp-radius tags (`rounded-chip`); a colour block keeps its ink edge.
    val chipShape = RoundedCornerShape(RaftAppRadius.Chip)
    val chipModifier =
        modifier
            .heightIn(min = minHeight)
            // A zero width means no edge (a soft chip); Compose would draw 0.dp as a hairline.
            .then(if (borderWidth > 0.dp) Modifier.border(borderWidth, raftInkOnFill(color, RaftColor.BorderBlack), chipShape) else Modifier)
            .background(color, chipShape)
            .padding(horizontal = horizontalPadding, vertical = verticalPadding)
            .let { base ->
                if (
                    clickDispatch != RaftMetaChipClickDispatch.Anchored ||
                    anchorPlacement == RaftAnchorPlacement.Tap
                ) {
                    base
                } else {
                    base.raftCaptureLiveRootAnchor(geometryAnchor)
                }
            }
    RaftAnchorButton(
        onClick = {
            if (clickDispatch == RaftMetaChipClickDispatch.Plain) {
                onClickWithoutAnchor?.invoke()
            }
        },
        onClick2 =
            if (clickDispatch == RaftMetaChipClickDispatch.Anchored) {
                { tapAnchor ->
                    val anchor =
                        when (anchorPlacement) {
                            RaftAnchorPlacement.Tap -> tapAnchor
                            RaftAnchorPlacement.BottomStart ->
                                geometryAnchor.resolve(RaftPopoverLocalPoint.BottomStart)
                            RaftAnchorPlacement.BottomEnd ->
                                geometryAnchor.resolve(RaftPopoverLocalPoint.BottomEnd)
                        }
                    anchor?.let { onClick?.invoke(it) }
                }
            } else {
                null
            },
        enabled = clickDispatch != RaftMetaChipClickDispatch.Disabled,
        pressFeedback = pressFeedback,
        clickIdentity = clickIdentity,
        anchorOwnerIdentity = resolvedAnchorOwnerIdentity,
        modifier = chipModifier
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(contentSpacing)
        ) {
            leadingContent?.invoke()
            iconName?.let {
                RaftLucideIcon(
                    it,
                    modifier = Modifier.size(iconSize),
                    color =
                        if (iconColorArgb == RaftColor.TextBlackArgb) {
                            raftArgbHex(raftInkOnFill(color, RaftColor.TextBlack))
                        } else {
                            iconColorArgb
                        },
                    fill = iconFillArgb
                )
            }
            Text(
                text = label,
                style = RaftType.Caption.withRaftFontWeight(labelFontWeight).copy(fontSize = labelFontSize, lineHeight = labelLineHeight),
                color = if (contentColor == RaftColor.TextBlack) raftInkOnFill(color, contentColor) else contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            trailingIconName?.let {
                RaftLucideIcon(
                    it,
                    modifier = Modifier.size(trailingIconSize),
                    color =
                        if (trailingIconColorArgb == RaftColor.TextMutedGrayArgb) {
                            raftArgbHex(raftInkOnFill(color, RaftColor.TextMutedGray))
                        } else {
                            trailingIconColorArgb
                        }
                )
            }
        }
    }
}

@Composable
internal fun SegmentedControl(
    selectedValue: String,
    options: List<SegmentedControlOption>,
    onValueSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    // Share the given width equally (labels ellipsize) instead of each item taking its own
    // width, for a control that must fit a card at large font sizes.
    fillWidth: Boolean = false
) {
    // raft-ui SegmentedControl: a `--fill-muted` tray (radius 11, 3dp inset, 2dp gap);
    // the checked item is the yellow sticker with an ink edge.
    Row(
        modifier =
            modifier
                .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
                .background(RaftColor.FillMuted, RoundedCornerShape(11.dp))
                .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        options.forEach { option ->
            RaftSegmentedItem(
                label = option.label,
                iconName = option.iconName,
                count = option.count,
                selected = option.value == selectedValue,
                enabled = enabled,
                onClick = { onValueSelected(option.value) },
                modifier = if (fillWidth) Modifier.weight(1f) else Modifier
            )
        }
    }
}

/**
 * One raft-ui segmented / tab item (`segmented-control` item, `tabs` default tab): 32dp,
 * control radius, 1.5dp edge that is transparent at rest and sticker ink when checked,
 * checked fill = the yellow `--selected` sticker (yellow in every theme, raft-ui #791) with bold
 * ink type, pressed = fill wash.
 */
@Composable
internal fun RaftSegmentedItem(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    iconName: RaftLucideIconName? = null,
    count: Int? = null,
    modifier: Modifier = Modifier
) {
    var pressed by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(RaftAppRadius.Control)
    val fill =
        when {
            selected -> if (pressed) raftOverlay(RaftColor.StickerPressed, RaftColor.Selected) else RaftColor.Selected
            pressed && enabled -> RaftColor.FillPressed
            else -> Color.Transparent
        }
    val ink =
        when {
            !enabled -> RaftColor.ForegroundDisabled
            selected -> RaftColor.StickerInk
            else -> RaftColor.TextMuted
        }
    RaftAnchorButton(
        onClick = onClick,
        enabled = enabled,
        pressFeedback = enabled,
        pressOffset = 0.dp,
        onPressedChange = { pressed = it },
        modifier =
            modifier
                .height(RaftMetrics.ControlHeightMd)
                .background(fill, shape)
                .border(RaftAppStroke.Control, if (selected) RaftColor.StickerInk else Color.Transparent, shape)
                // CSS `px-3` sits inside the 1.5dp (transparent at rest) border box.
                .padding(horizontal = 12.dp + RaftAppStroke.Control)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            iconName?.let {
                RaftLucideIcon(name = it, modifier = Modifier.size(16.dp), color = raftArgbHex(ink))
            }
            Text(
                text = label,
                modifier = Modifier.weight(1f, fill = false),
                style =
                    RaftType.Body
                        .copy(fontSize = 14.sp, lineHeight = 20.sp)
                        .withRaftFontWeight(if (selected) FontWeight.Bold else FontWeight.SemiBold),
                color = ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            count?.takeIf { it > 0 }?.let {
                Text(
                    text = it.toString(),
                    style = RaftType.Caption.copy(fontFamily = RaftMonoFamily, fontSize = 12.sp, lineHeight = 16.sp),
                    color = ink,
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * [uppercase], [inactiveBackgroundColor], [borderColor], [disabledBorderColor] and the
 * activeShadow* parameters are ignored: they only shaped the retired brutal register.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
internal fun RaftToolbarChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconName: RaftLucideIconName? = null,
    trailingIconName: RaftLucideIconName? = null,
    active: Boolean = false,
    enabled: Boolean = true,
    uppercase: Boolean = true,
    count: Int? = null,
    activeBackgroundColor: Color = RaftColor.AccentCyan,
    inactiveBackgroundColor: Color = RaftColor.SurfaceWhite,
    borderColor: Color = RaftColor.EmptyStateGray,
    disabledBorderColor: Color = RaftColor.EmptyStateGray,
    activeShadowOffset: Dp = 0.dp,
    reserveActiveShadowSpace: Boolean = false,
    activeShadowColor: Color = RaftColor.ShadowBlack
) {
    RaftPickerChip(
        label = label,
        onClick = onClick,
        modifier = modifier,
        iconName = iconName,
        trailingIconName = trailingIconName,
        active = active,
        enabled = enabled,
        count = count,
        activeBackgroundColor =
            if (activeBackgroundColor == RaftColor.AccentCyan) RaftColor.InfoSoft else activeBackgroundColor
    )
}

/**
 * raft-ui `PickerTriggerButton` (filter / toolbar chip): 32dp, control radius, 1dp
 * `--line-seam` ring at rest, 13/18 semibold muted type in sentence case; a chip with a
 * value is `--info-soft` with ink type and no ring; pressed lays the fill wash; disabled
 * is `--fill-muted` with disabled ink. Counts are mono meta text, not a boxed badge.
 */
@Composable
private fun RaftPickerChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier,
    iconName: RaftLucideIconName?,
    trailingIconName: RaftLucideIconName?,
    active: Boolean,
    enabled: Boolean,
    count: Int?,
    activeBackgroundColor: Color
) {
    var pressed by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(RaftAppRadius.Control)
    val restingFill = if (active) activeBackgroundColor else Color.Transparent
    val fill =
        when {
            !enabled -> RaftColor.FillMuted
            pressed -> raftOverlay(RaftColor.FillPressed, restingFill)
            else -> restingFill
        }
    val ink =
        when {
            !enabled -> RaftColor.ForegroundDisabled
            active -> raftInkOnFill(restingFill, RaftColor.TextBlack)
            else -> RaftColor.TextMuted
        }
    // Count / trailing chevron: hint ink, or sticker ink on a bright active fill (BrandYellow) in dark.
    val metaInk =
        when {
            !enabled -> RaftColor.ForegroundDisabled
            active -> raftInkOnFill(restingFill, RaftColor.ForegroundHint)
            else -> RaftColor.ForegroundHint
        }
    val ring = if (!enabled || active) Color.Transparent else RaftColor.BorderSeam
    RaftAnchorButton(
        onClick = onClick,
        enabled = enabled,
        pressFeedback = enabled,
        pressOffset = 0.dp,
        onPressedChange = { pressed = it },
        modifier =
            modifier
                .height(RaftMetrics.ControlHeightMd)
                .background(fill, shape)
                .border(RaftAppStroke.Thin, ring, shape)
                .padding(horizontal = 10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            iconName?.let {
                RaftLucideIcon(it, modifier = Modifier.size(14.dp), color = raftArgbHex(ink), strokeWidth = 2f)
            }
            Text(
                text = label,
                style = RaftType.Body.copy(fontSize = 13.sp, lineHeight = 18.sp).withRaftFontWeight(FontWeight.SemiBold),
                color = ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            count?.let {
                Text(
                    text = it.toString(),
                    style = RaftType.Caption.copy(fontFamily = RaftMonoFamily, fontSize = 12.sp, lineHeight = 16.sp),
                    color = metaInk,
                    maxLines = 1
                )
            }
            trailingIconName?.let {
                RaftLucideIcon(
                    it,
                    modifier = Modifier.size(14.dp),
                    color = raftArgbHex(metaInk),
                    strokeWidth = 2f
                )
            }
        }
    }
}

// The brutal primitives' hard-coded black icon ink; raft swaps it for the themed ink.
internal const val RaftLegacyIconInkArgb = "#FF000000"

