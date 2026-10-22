package build.raft.app.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.RaftColor
import build.raft.app.RaftLucideIcon
import build.raft.app.RaftLiveRootAnchorRef
import build.raft.app.RaftLucideIconName
import build.raft.app.RaftPopoverAnchor
import build.raft.app.RaftPopoverAnchorResolver
import build.raft.app.RaftPopoverLocalPoint
import build.raft.app.raftCaptureLiveRootAnchor
import build.raft.app.raftArgbHex
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftIconButtonSize
import build.raft.designsystem.components.RaftPickerTriggerButton
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.designsystem.theme.RaftTheme

/**
 * A Lucide icon in a design-system slot (`RaftButton` / `RaftIconButton` / `RaftTextField` /
 * `RaftMenuItem` / `RaftBadge` icon), drawn in the slot's ink ([LocalRaftContentColor], CSS
 * `currentColor`). Without a [size] it fills the slot's icon box. [color] overrides the ink.
 */
@Composable
internal fun RaftSlotIcon(
    name: RaftLucideIconName,
    modifier: Modifier = Modifier,
    size: Dp? = null,
    color: Color? = null,
    spinning: Boolean = name == RaftLucideIconName.Loader || name == RaftLucideIconName.LoaderCircle
) {
    val ink = color ?: LocalRaftContentColor.current.takeIf { it.isSpecified } ?: RaftTheme.colors.foreground
    RaftLucideIcon(
        name,
        modifier = modifier.then(if (size != null) Modifier.size(size) else Modifier.fillMaxSize()),
        color = raftArgbHex(ink),
        spinning = spinning
    )
}

/**
 * The raft-ui button variant for a legacy brutal fill (see [raftButtonChrome]): the pink /
 * yellow fills are the primary sticker, red the danger and orange the warning sticker, a
 * transparent fill the ghost button and every other fill the default (ink-edged paper)
 * button. Inside a dialog footer the paper secondary is the ghost Cancel, as on raft-ui.
 */
internal fun raftButtonVariantFor(backgroundColor: Color, inDialogFooter: Boolean = false): RaftButtonVariant {
    val chrome = raftButtonChrome(backgroundColor)
    return when (chrome.kind) {
        RaftButtonKind.Ghost -> RaftButtonVariant.Ghost
        RaftButtonKind.Outline ->
            if (inDialogFooter && backgroundColor == RaftColor.SurfaceWhite) RaftButtonVariant.Ghost else RaftButtonVariant.Default
        RaftButtonKind.Sticker ->
            when (chrome.fill) {
                RaftColor.Danger -> RaftButtonVariant.Danger
                RaftColor.Warning -> RaftButtonVariant.Warning
                else -> RaftButtonVariant.Primary
            }
    }
}

/**
 * The raft-ui icon-button variant for a legacy fill: the paper default becomes the ghost icon
 * button (web headers and toolbars draw no frame round an icon); stickers stay stickers.
 */
internal fun raftIconButtonVariantFor(backgroundColor: Color): RaftButtonVariant =
    when (val variant = raftButtonVariantFor(backgroundColor)) {
        RaftButtonVariant.Default -> RaftButtonVariant.Ghost
        else -> variant
    }

/** raft-ui button size for a legacy min height: below 38dp `md` (32), below 52dp `lg` (44), else `xl` (52). */
internal fun raftButtonSizeFor(minHeight: Dp): RaftButtonSize =
    when {
        minHeight >= 52.dp -> RaftButtonSize.Xl
        minHeight >= 38.dp -> RaftButtonSize.Lg
        else -> RaftButtonSize.Md
    }

/**
 * raft-ui icon-button size for a legacy box size: up to 38dp `icon-md` (32; raft-ui 0.21 has no
 * smaller icon button, `icon-xs` and `icon-sm` render as it), above it `icon-lg` (44).
 */
internal fun raftIconButtonSizeFor(size: Dp): RaftIconButtonSize =
    if (size > 38.dp) RaftIconButtonSize.Lg else RaftIconButtonSize.Md

/**
 * A filter trigger in a toolbar filter row (Search, Tasks): the design-system
 * [RaftPickerTriggerButton] (32dp, control radius, `line-seam` ring at rest, `info-soft` once
 * [hasValue]) that opens its popover from its bottom-start corner. [onClick] receives the live
 * anchor; [onAnchorChanged] hands the popover a resolver that follows the trigger.
 */
@Composable
internal fun RaftAnchoredPickerTrigger(
    label: String,
    onClick: (RaftPopoverAnchor) -> Unit,
    anchorIdentity: Any,
    modifier: Modifier = Modifier,
    iconName: RaftLucideIconName? = null,
    hasValue: Boolean = false,
    enabled: Boolean = true,
    onAnchorChanged: ((RaftPopoverAnchorResolver) -> Unit)? = null
) {
    val currentOnAnchorChanged by rememberUpdatedState(onAnchorChanged)
    val anchor = remember(anchorIdentity) { RaftLiveRootAnchorRef(identity = anchorIdentity) }
    val anchorResolver = remember(anchor) { anchor.resolver(RaftPopoverLocalPoint.BottomStart) }
    var resolverNotified by remember(anchorResolver) { mutableStateOf(false) }
    Box(
        modifier =
            modifier.raftCaptureLiveRootAnchor(anchor) {
                if (!resolverNotified) {
                    resolverNotified = true
                    currentOnAnchorChanged?.invoke(anchorResolver)
                }
            }
    ) {
        RaftPickerTriggerButton(
            text = label,
            onClick = { anchor.resolve(RaftPopoverLocalPoint.BottomStart)?.let(onClick) },
            hasValue = hasValue,
            enabled = enabled,
            leadingIcon = iconName?.let { name -> { RaftSlotIcon(name) } }
        )
    }
}
