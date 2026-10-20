package build.raft.app.components

import build.raft.app.LocalRaftTheme
import build.raft.app.RaftColor
import build.raft.app.RaftAppRadius
import build.raft.app.RaftAppStroke
import build.raft.app.isAmbient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * raft-ui 0.6.0 chrome shared by the mobile primitives (dist/index.mjs recipes +
 * foundation.css). Lines: hairline (1dp `--line-hairline`) inside content, seam (1dp
 * `--line-seam`) around overlays, control ink (1.5dp `--line-strong`) on controls.
 * Radii: sticker 4 / chip 6 / control 8 / container 10 / dialog 16. Controls cast no
 * shadow; only overlays float.
 */
internal object RaftMetrics {
    /** Button `md` (`h-8`); raft renders `xs`/`sm` as `md`. */
    val ControlHeightMd = 32.dp
    /** Button `lg` / icon-lg (`h-11`). */
    val ControlHeightLg = 44.dp
    /** Button `xl` (`h-13`). */
    val ControlHeightXl = 52.dp
    /** Input / textarea / select field (`h-11`). */
    val FieldHeight = 44.dp
    /** The brutal text-field default; a caller still on it gets [FieldHeight]. */
    val LegacyFieldHeight = 42.dp
    /** The brutal select-trigger default; a caller still on it gets [FieldHeight]. */
    val LegacySelectHeight = 40.dp
    /** Field horizontal padding (`px-3`). */
    val FieldPaddingHorizontal = 12.dp
    /** Checkbox / radio (`size-5`). */
    val CheckboxSize = 20.dp
    /** Menu / select / selection rows (`min-h-9`). */
    val MenuRowHeight = 36.dp
    /** Menu / select popup padding (`p-1.5`). */
    val PopupPadding = 6.dp
    /** Vertical inset on each side of a menu separator (`my-1.5`). */
    val MenuSeparatorInset = 6.dp
    /** Menu item horizontal padding (`px-2.5`). */
    val MenuItemPaddingHorizontal = 10.dp
    /** Menu item icon-to-label gap (`gap-2.5`). */
    val MenuItemGap = 10.dp
    /** Menu item icon (`[&>svg]:size-4`). */
    val MenuIconSize = 16.dp
    /** DropdownMenu / ContextMenu popup minimum width (`min-w-60`). */
    val MenuMinWidth = 240.dp
    /** DropdownMenu default side offset (`sideOffset ?? 6`). */
    val MenuSideOffset = 6.dp
    /** Menu group label padding (`px-2.5 pt-2 pb-1`). */
    val MenuLabelPaddingTop = 8.dp
    val MenuLabelPaddingBottom = 4.dp
    /** Status / attention pixel (`size-[9px]`). */
    val StatusSize = 9.dp
    /** `--elevation-float`: 0 12px 32px -12px (offset, blur, spread). */
    val OverlayShadowOffsetY = 12.dp
    val OverlayShadowBlur = 32.dp
    val OverlayShadowSpread = (-12).dp
}

/** True when the composition renders the raft-ui register (every non-brutal theme). */
@Composable
@ReadOnlyComposable
internal fun raftActive(): Boolean = LocalRaftTheme.current.isAmbient

/** Lays a translucent [overlay] over an opaque [base] (raft `press-overlay`). */
internal fun raftOverlay(overlay: Color, base: Color): Color =
    if (base.alpha == 0f) overlay else lerp(base, overlay.copy(alpha = 1f), overlay.alpha)

internal enum class RaftButtonKind {
    /** Colour block with an ink edge and ink type (primary/accent/warning/danger). */
    Sticker,
    /** Paper control with a 1.5dp ink line (default/outline/information/muted/success). */
    Outline,
    /** Transparent, ink type, pressed wash only. */
    Ghost
}

internal data class RaftButtonChrome(
    val kind: RaftButtonKind,
    val fill: Color,
    val border: Color,
    val content: Color,
    val pressedOverlay: Color
)

/** True inside a dialog footer row, where raft-ui renders the paper secondary as ghost. */
internal val LocalRaftDialogFooter = androidx.compose.runtime.staticCompositionLocalOf { false }

/**
 * Maps the brutal fill a caller passes onto the raft-ui 0.6.0 button variant.
 * raft-ui's `primary` and `accent` both render the pink accent sticker (yellow is for
 * hints, never an action fill), `danger`/`warning` are their own stickers, and every
 * other tone (cyan information, lime success, stone/cream muted, white default) is the
 * paper outline button. A transparent fill is the ghost button.
 */
internal fun raftButtonChrome(backgroundColor: Color): RaftButtonChrome {
    val stickerFill =
        when (backgroundColor) {
            RaftColor.BrandYellow,
            RaftColor.SoftSignal,
            RaftColor.Brand,
            RaftColor.AccentPink,
            RaftColor.Attention -> RaftColor.Attention
            RaftColor.AccentRed,
            RaftColor.Danger -> RaftColor.Danger
            RaftColor.AccentOrange,
            RaftColor.StateWarningBase,
            RaftColor.Warning -> RaftColor.Warning
            else -> null
        }
    return when {
        stickerFill != null ->
            RaftButtonChrome(
                kind = RaftButtonKind.Sticker,
                fill = stickerFill,
                border = RaftColor.StickerInk,
                content = RaftColor.StickerInk,
                pressedOverlay = RaftColor.StickerPressed
            )
        backgroundColor.alpha == 0f ->
            RaftButtonChrome(
                kind = RaftButtonKind.Ghost,
                fill = Color.Transparent,
                border = Color.Transparent,
                content = RaftColor.TextBlack,
                pressedOverlay = RaftColor.FillPressed
            )
        else ->
            RaftButtonChrome(
                kind = RaftButtonKind.Outline,
                fill = RaftColor.Card,
                border = RaftColor.BorderBlack,
                content = RaftColor.TextBlack,
                pressedOverlay = RaftColor.FillPressed
            )
    }
}

/** Resolves the legacy brutal field height default onto raft's 44dp field. */
@Composable
@ReadOnlyComposable
internal fun raftFieldHeight(height: Dp): Dp =
    if (raftActive() && (height == RaftMetrics.LegacyFieldHeight || height == RaftMetrics.LegacySelectHeight)) {
        RaftMetrics.FieldHeight
    } else {
        height
    }

/**
 * raft overlay surface (menus, select popups, popovers): container radius, popover
 * layer, 1dp seam ring and the float shadow. [radius] is [RaftAppRadius.Dialog] for dialogs.
 */
internal fun Modifier.raftLookOverlaySurface(radius: Dp = RaftAppRadius.Container): Modifier {
    val shape = RoundedCornerShape(radius)
    val shadowColor = RaftColor.OverlayShadow
    return this
        // `--elevation-float` is a CSS box-shadow (0 12px 32px -12px ink/32): a blurred
        // drop shadow, drawn as one. An elevation shadow only approximates it, and is not
        // drawn at all where a window is rendered in software.
        .dropShadow(
            shape = shape,
            shadow =
                Shadow(
                    radius = RaftMetrics.OverlayShadowBlur,
                    color = shadowColor,
                    spread = RaftMetrics.OverlayShadowSpread,
                    offset = DpOffset(0.dp, RaftMetrics.OverlayShadowOffsetY)
                )
        )
        .clip(shape)
        .background(RaftColor.Popover, shape)
        .border(RaftAppStroke.Thin, RaftColor.BorderSeam, shape)
}

/** raft content surface (Card `default`): container radius, card layer, 1dp hairline, no shadow. */
internal fun Modifier.raftCardSurface(
    fill: Color = RaftColor.Card,
    radius: Dp = RaftAppRadius.Container
): Modifier {
    val shape = RoundedCornerShape(radius)
    return this
        .background(fill, shape)
        .border(RaftAppStroke.Thin, RaftColor.BorderHairline, shape)
}

/** raft control chrome: control radius, [fill], 1.5dp [border]. */
internal fun Modifier.raftControlSurface(
    fill: Color,
    border: Color,
    radius: Dp = RaftAppRadius.Control,
    borderWidth: Dp = RaftAppStroke.Control
): Modifier {
    val shape = RoundedCornerShape(radius)
    return this
        .background(fill, shape)
        .border(borderWidth, border, shape)
}

/**
 * raft option card that shows the pick itself (raft-ui RadioGroup `appearance="none"`
 * option card): container radius, `layer-card` with a 1dp `line-hairline` edge at rest;
 * checked, the `info-soft` wash with a 1dp `line-seam` edge (raft "Selected: content").
 * Disabled is `fill-muted` with a hairline. No ink block fill, no offset shadow; it clips
 * so a press wash follows the radius.
 */
internal fun Modifier.raftOptionSurface(
    selected: Boolean,
    enabled: Boolean = true,
    radius: Dp = RaftAppRadius.Container
): Modifier {
    val shape = RoundedCornerShape(radius)
    val fill =
        when {
            !enabled -> RaftColor.FillMuted
            selected -> RaftColor.InfoSoft
            else -> RaftColor.Card
        }
    val edge = if (selected && enabled) RaftColor.BorderSeam else RaftColor.BorderHairline
    return this
        .clip(shape)
        .background(fill, shape)
        .border(RaftAppStroke.Thin, edge, shape)
}

/**
 * The raft-ui `-soft` wash for a legacy saturated block fill (yellow / pink / cyan / lime /
 * orange / red / stone / lavender), for badges, chips and notices that used to paint the
 * solid palette colour behind an ink frame. Paper fills become `fill-muted`; any other
 * colour is returned unchanged.
 */
internal fun raftSoftWash(fill: Color): Color =
    when (fill) {
        RaftColor.BrandYellow, RaftColor.Brand -> RaftColor.BrandSoft
        RaftColor.AccentPink, RaftColor.Attention -> RaftColor.AttentionSoft
        RaftColor.AccentCyan, RaftColor.Info -> RaftColor.InfoSoft
        RaftColor.AccentLime, RaftColor.Success -> RaftColor.SuccessSoft
        RaftColor.AccentOrange, RaftColor.StateWarningBase, RaftColor.Warning -> RaftColor.WarningSoft
        RaftColor.AccentRed, RaftColor.Danger -> RaftColor.DangerSoft
        RaftColor.AccentStone, RaftColor.Inactive, RaftColor.EmptyStateGray, RaftColor.StatusNeutralGray -> RaftColor.InactiveSoft
        RaftColor.AccentLavender -> RaftColor.AccentLavender.copy(alpha = 0.28f)
        RaftColor.SurfaceWhite, RaftColor.SurfaceCream -> RaftColor.FillMuted
        else -> fill
    }
