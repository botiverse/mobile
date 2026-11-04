package build.raft.app.components

import build.raft.app.RaftColor
import build.raft.app.RaftAppThemeState
import build.raft.app.RaftLucideIcon
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import build.raft.app.compat.ui.raftColorFromArgbHex
import build.raft.designsystem.components.RaftSegment
import build.raft.designsystem.components.RaftSegmentedControl
import build.raft.designsystem.components.raftPointerHover

internal data class SegmentedControlOption(
    val value: String,
    val label: String,
    val iconName: RaftLucideIconName? = null,
    val count: Int? = null
)

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
    /**
     * A second label [contentSpacing] after [label] (raft-ui TaskChip's assignee beside its
     * number): it gives way first, truncating while [label] stays whole.
     */
    secondaryLabel: String? = null,
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
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnClickWithoutAnchor by rememberUpdatedState(onClickWithoutAnchor)
    val resolvedAnchorOwnerIdentity = anchorOwnerIdentity ?: clickIdentity ?: "anchored_meta_chip:$label"
    // Tap placement: the popover anchors where the finger landed, resolved through the chip's
    // live root coordinates at the moment of the click (the reaction add chip's contract).
    val tapAnchor =
        remember(resolvedAnchorOwnerIdentity) { RaftLiveRootAnchorRef(identity = resolvedAnchorOwnerIdentity) }
    val pressPoint = remember(resolvedAnchorOwnerIdentity) { arrayOfNulls<Offset>(1) }
    // Bottom placements: the chip's content box, inside its padding.
    val geometryAnchor =
        remember(resolvedAnchorOwnerIdentity) { RaftLiveRootAnchorRef(identity = resolvedAnchorOwnerIdentity) }
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val interactive = clickDispatch != RaftMetaChipClickDispatch.Disabled
    val anchored = clickDispatch == RaftMetaChipClickDispatch.Anchored
    // Press lays the raft press overlay over the chip's fill (never moves it): sticker-pressed on
    // a colour block, fill-pressed on a clear chip.
    val fill =
        if (interactive && pressFeedback && pressed) {
            raftOverlay(if (color.alpha == 0f) RaftColor.FillPressed else RaftColor.StickerPressed, color)
        } else {
            color
        }
    // raft: chips are 6dp-radius tags (`rounded-chip`); a colour block keeps its ink edge.
    val chipShape = RoundedCornerShape(RaftAppRadius.Chip)
    Box(
        modifier =
            Modifier
                .then(
                    if (anchored) {
                        Modifier
                            .raftCaptureLiveRootAnchor(tapAnchor)
                            // Observes (never consumes) the down so the clickable keeps the tap.
                            .pointerInput(tapAnchor) {
                                awaitEachGesture {
                                    pressPoint[0] =
                                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial).position
                                }
                            }
                    } else {
                        Modifier
                    }
                )
                .then(
                    if (interactive) {
                        Modifier
                            .raftPointerHover(interactionSource)
                            .clickable(
                                interactionSource = interactionSource,
                                indication = null,
                                role = Role.Button,
                                onClick = {
                                    when (clickDispatch) {
                                        RaftMetaChipClickDispatch.Plain -> currentOnClickWithoutAnchor?.invoke()
                                        RaftMetaChipClickDispatch.Anchored -> {
                                            // A keyboard / accessibility click has no finger point: anchor under the chip.
                                            val tapPoint =
                                                pressPoint[0]?.let { RaftPopoverLocalPoint.Exact(it) }
                                                    ?: RaftPopoverLocalPoint.BottomStart
                                            pressPoint[0] = null
                                            val anchor =
                                                when (anchorPlacement) {
                                                    RaftAnchorPlacement.Tap -> tapAnchor.resolve(tapPoint)
                                                    RaftAnchorPlacement.BottomStart ->
                                                        geometryAnchor.resolve(RaftPopoverLocalPoint.BottomStart)
                                                    RaftAnchorPlacement.BottomEnd ->
                                                        geometryAnchor.resolve(RaftPopoverLocalPoint.BottomEnd)
                                                }
                                            anchor?.let { currentOnClick?.invoke(it) }
                                        }
                                        RaftMetaChipClickDispatch.Disabled -> Unit
                                    }
                                }
                            )
                    } else {
                        Modifier
                    }
                )
                .then(modifier)
                .heightIn(min = minHeight)
                // A zero width means no edge (a soft chip); Compose would draw 0.dp as a hairline.
                .then(if (borderWidth > 0.dp) Modifier.border(borderWidth, raftInkOnFill(color, RaftColor.BorderBlack), chipShape) else Modifier)
                .background(fill, chipShape)
                .padding(horizontal = horizontalPadding, vertical = verticalPadding)
                .then(
                    if (anchored && anchorPlacement != RaftAnchorPlacement.Tap) {
                        Modifier.raftCaptureLiveRootAnchor(geometryAnchor)
                    } else {
                        Modifier
                    }
                ),
        contentAlignment = Alignment.Center
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
            val labelStyle =
                RaftType.Caption.withRaftFontWeight(labelFontWeight).copy(fontSize = labelFontSize, lineHeight = labelLineHeight)
            val labelColor = if (contentColor == RaftColor.TextBlack) raftInkOnFill(color, contentColor) else contentColor
            Text(
                text = label,
                style = labelStyle,
                color = labelColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            secondaryLabel?.let { secondary ->
                Text(
                    text = secondary,
                    style = labelStyle,
                    color = labelColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }
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

/**
 * The app's option-list adapter over the design system's [RaftSegmentedControl]: one
 * [RaftSegment] per [SegmentedControlOption] (its Lucide icon in the segment's ink, a positive
 * count as the mono count). [fillWidth] shares the width equally between the segments.
 */
@Composable
internal fun SegmentedControl(
    selectedValue: String,
    options: List<SegmentedControlOption>,
    onValueSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    fillWidth: Boolean = false
) {
    RaftDesignSystemTheme {
        RaftSegmentedControl(modifier = modifier.then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)) {
            options.forEach { option ->
                RaftSegment(
                    text = option.label,
                    selected = option.value == selectedValue,
                    onClick = { onValueSelected(option.value) },
                    modifier = if (fillWidth) Modifier.weight(1f) else Modifier,
                    count = option.count?.takeIf { it > 0 }?.toString(),
                    enabled = enabled,
                    icon = option.iconName?.let { name -> { RaftSlotIcon(name) } }
                )
            }
        }
    }
}

// The brutal primitives' hard-coded black icon ink; raft swaps it for the themed ink.
internal const val RaftLegacyIconInkArgb = "#FF000000"

