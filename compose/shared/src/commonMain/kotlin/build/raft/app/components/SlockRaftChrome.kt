package build.raft.app.components

import build.raft.app.LocalSlockTheme
import build.raft.app.SlockColor
import build.raft.app.SlockRadius
import build.raft.app.SlockStroke
import build.raft.app.isAmbient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.shape.RoundedCornerShape
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.draw.shadow
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.graphics.lerp
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.compose.ui.unit.dp

/**
 * raft-ui 0.6.0 chrome shared by the mobile primitives (dist/index.mjs recipes +
 * foundation.css). Lines: hairline (1dp `--line-hairline`) inside content, seam (1dp
 * `--line-seam`) around overlays, control ink (1.5dp `--line-strong`) on controls.
 * Radii: sticker 4 / chip 6 / control 8 / container 10 / dialog 16. Controls cast no
 * shadow; only overlays float.
 */
internal object SlockRaftMetrics {
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
    /** Approximates `--elevation-float` (0 12px 32px -12px ink/32) on Kuikly's elevation shadow. */
    val OverlayElevation = 6.dp
}

/** True when the composition renders the raft-ui register (every non-brutal theme). */
@Composable
@ReadOnlyComposable
internal fun slockRaftActive(): Boolean = LocalSlockTheme.current.isAmbient

/** Lays a translucent [overlay] over an opaque [base] (raft `press-overlay`). */
internal fun slockRaftOverlay(overlay: Color, base: Color): Color =
    if (base.alpha == 0f) overlay else lerp(base, overlay.copy(alpha = 1f), overlay.alpha)

internal enum class SlockRaftButtonKind {
    /** Colour block with an ink edge and ink type (primary/accent/warning/danger). */
    Sticker,
    /** Paper control with a 1.5dp ink line (default/outline/information/muted/success). */
    Outline,
    /** Transparent, ink type, pressed wash only. */
    Ghost
}

internal data class SlockRaftButtonChrome(
    val kind: SlockRaftButtonKind,
    val fill: Color,
    val border: Color,
    val content: Color,
    val pressedOverlay: Color
)

/** True inside a dialog footer row, where raft-ui renders the paper secondary as ghost. */
internal val LocalSlockRaftDialogFooter = androidx.compose.runtime.staticCompositionLocalOf { false }

/**
 * Maps the brutal fill a caller passes onto the raft-ui 0.6.0 button variant.
 * raft-ui's `primary` and `accent` both render the pink accent sticker (yellow is for
 * hints, never an action fill), `danger`/`warning` are their own stickers, and every
 * other tone (cyan information, lime success, stone/cream muted, white default) is the
 * paper outline button. A transparent fill is the ghost button.
 */
internal fun slockRaftButtonChrome(backgroundColor: Color): SlockRaftButtonChrome {
    val stickerFill =
        when (backgroundColor) {
            SlockColor.BrandYellow,
            SlockColor.SoftSignal,
            SlockColor.Brand,
            SlockColor.AccentPink,
            SlockColor.Attention -> SlockColor.Attention
            SlockColor.AccentRed,
            SlockColor.Danger -> SlockColor.Danger
            SlockColor.AccentOrange,
            SlockColor.StateWarningBase,
            SlockColor.Warning -> SlockColor.Warning
            else -> null
        }
    return when {
        stickerFill != null ->
            SlockRaftButtonChrome(
                kind = SlockRaftButtonKind.Sticker,
                fill = stickerFill,
                border = SlockColor.StickerInk,
                content = SlockColor.StickerInk,
                pressedOverlay = SlockColor.StickerPressed
            )
        backgroundColor.alpha == 0f ->
            SlockRaftButtonChrome(
                kind = SlockRaftButtonKind.Ghost,
                fill = Color.Transparent,
                border = Color.Transparent,
                content = SlockColor.TextBlack,
                pressedOverlay = SlockColor.FillPressed
            )
        else ->
            SlockRaftButtonChrome(
                kind = SlockRaftButtonKind.Outline,
                fill = SlockColor.Card,
                border = SlockColor.BorderBlack,
                content = SlockColor.TextBlack,
                pressedOverlay = SlockColor.FillPressed
            )
    }
}

/** Resolves the legacy brutal field height default onto raft's 44dp field. */
@Composable
@ReadOnlyComposable
internal fun slockRaftFieldHeight(height: Dp): Dp =
    if (slockRaftActive() && (height == SlockRaftMetrics.LegacyFieldHeight || height == SlockRaftMetrics.LegacySelectHeight)) {
        SlockRaftMetrics.FieldHeight
    } else {
        height
    }

/**
 * raft overlay surface (menus, select popups, popovers): container radius, popover
 * layer, 1dp seam ring and the float shadow. [radius] is [SlockRadius.Dialog] for dialogs.
 */
internal fun Modifier.slockRaftOverlaySurface(radius: Dp = SlockRadius.Container): Modifier {
    val shape = RoundedCornerShape(radius)
    val shadowColor = SlockColor.OverlayShadow
    return this
        .shadow(
            elevation = SlockRaftMetrics.OverlayElevation,
            shape = shape,
            clip = true,
            ambientColor = shadowColor,
            spotColor = shadowColor
        )
        .background(SlockColor.Popover, shape)
        .border(SlockStroke.Thin, SlockColor.BorderSeam, shape)
}

/** raft content surface (Card `default`): container radius, card layer, 1dp hairline, no shadow. */
internal fun Modifier.slockRaftCardSurface(
    fill: Color = SlockColor.Card,
    radius: Dp = SlockRadius.Container
): Modifier {
    val shape = RoundedCornerShape(radius)
    return this
        .background(fill, shape)
        .border(SlockStroke.Thin, SlockColor.BorderHairline, shape)
}

/** raft control chrome: control radius, [fill], 1.5dp [border]. */
internal fun Modifier.slockRaftControlSurface(
    fill: Color,
    border: Color,
    radius: Dp = SlockRadius.Control,
    borderWidth: Dp = SlockStroke.Control
): Modifier {
    val shape = RoundedCornerShape(radius)
    return this
        .background(fill, shape)
        .border(borderWidth, border, shape)
}
