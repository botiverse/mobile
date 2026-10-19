package build.raft.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.theme.RaftTypography
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke
import build.raft.designsystem.tokens.RaftUiPalette
import build.raft.designsystem.tokens.RaftUiThemeColors

/**
 * raft-ui Button `variant`, as the Button doc names them. raft-ui's other names render
 * the same and are not repeated here: `accent` is [Primary]; `outline`, `information`,
 * `muted` and `success` are [Default]. `danger-secondary` / `danger-outline` (red text on
 * an outline) are left out: the doc forbids them.
 */
enum class RaftButtonVariant {
    /** The one action the screen exists for: a pink (`attention-400`) sticker. */
    Primary,

    /** Every other action: an ink (`line-strong`) border on `layer-card`. */
    Default,

    /** Cancel and minor actions inside a surface that draws its own edge: no frame. */
    Ghost,

    /** Interrupts but can be undone (Stop, Leave, Reset): an orange sticker. */
    Warning,

    /** Cannot be undone, on the confirm button of a confirmation dialog: a red sticker. */
    Danger,

    /** Frameless, on the yellow frame (rail, top bar): `foreground-on-frame` ink. */
    OnFrame,
}

/** raft-ui Button text sizes (`xs` / `sm` are old names of [Md]). */
enum class RaftButtonSize {
    /** 32dp: actions in list rows, cards, banners, toolbars and panel headers. */
    Md,

    /** 44dp: dialog and sheet footers, form submit buttons. */
    Lg,

    /** 52dp: only the full-width pink button of a sign-in, sign-up or setup form. */
    Xl,
}

/** raft-ui Button icon sizes (`icon-xs` / `icon-sm` are old names of [Md]). */
enum class RaftIconButtonSize {
    /** 32dp with an 18dp icon (36dp / 20dp on the frame). */
    Md,

    /** 44dp with a 22dp icon: a phone top bar, next to a 44dp input. */
    Lg,
}

/** Resolved colours of one variant x state (raft-ui button.tsx). */
internal data class RaftButtonLook(
    val fill: Color,
    val ink: Color,
    /** The 1.5dp border (transparent for frameless variants). */
    val edge: Color,
    /** The die-cut edge outside a sticker (`ring-sticker-edge`): transparent in light. */
    val outerRing: Color,
    /** Laid over [fill] while pressed (CSS `press-overlay*`); transparent when disabled. */
    val pressOverlay: Color,
)

/**
 * [frameless] is a ghost button at an icon size: disabled, it only greys its icon.
 * An `on-frame` button is always frameless.
 */
internal fun raftButtonLook(
    variant: RaftButtonVariant,
    enabled: Boolean,
    frameless: Boolean,
    colors: RaftUiThemeColors,
): RaftButtonLook {
    fun sticker(fill: Color) =
        RaftButtonLook(
            fill = fill,
            ink = RaftUiPalette.stickerInk,
            edge = RaftUiPalette.stickerInk,
            outerRing = colors.stickerEdge,
            pressOverlay = raftPressOverlayColor(RaftPressSurface.Sticker, colors),
        )
    if (!enabled) {
        val noBox = variant == RaftButtonVariant.OnFrame || (variant == RaftButtonVariant.Ghost && frameless)
        return RaftButtonLook(
            fill = if (noBox) Color.Transparent else colors.fillMuted,
            ink = colors.foregroundDisabled,
            edge = if (noBox) Color.Transparent else colors.lineHairline,
            outerRing = Color.Transparent,
            pressOverlay = Color.Transparent,
        )
    }
    return when (variant) {
        RaftButtonVariant.Primary -> sticker(RaftUiPalette.attention400)
        RaftButtonVariant.Warning -> sticker(RaftUiPalette.warning)
        RaftButtonVariant.Danger -> sticker(RaftUiPalette.danger)
        RaftButtonVariant.Default ->
            RaftButtonLook(
                fill = colors.layerCard,
                ink = colors.foreground,
                edge = colors.lineStrong,
                outerRing = Color.Transparent,
                pressOverlay = raftPressOverlayColor(RaftPressSurface.Paper, colors),
            )
        RaftButtonVariant.Ghost ->
            RaftButtonLook(
                fill = Color.Transparent,
                ink = colors.foreground,
                edge = Color.Transparent,
                outerRing = Color.Transparent,
                pressOverlay = raftPressOverlayColor(RaftPressSurface.Paper, colors),
            )
        RaftButtonVariant.OnFrame ->
            RaftButtonLook(
                fill = Color.Transparent,
                ink = colors.foregroundOnFrame,
                edge = Color.Transparent,
                outerRing = Color.Transparent,
                pressOverlay = raftPressOverlayColor(RaftPressSurface.Frame, colors),
            )
    }
}

/** Text-button metrics of one size (button.tsx `textMd`, `lg`, `xl`, on-frame compound). */
internal data class RaftButtonMetrics(
    val height: Dp,
    val paddingX: Dp,
    /** Padding on the side of a leading / trailing icon (`has-data-[icon=…]`). */
    val iconSidePaddingX: Dp,
    val iconSize: Dp,
)

internal fun raftButtonMetrics(size: RaftButtonSize, variant: RaftButtonVariant): RaftButtonMetrics =
    when {
        // On the frame the 32dp button is the 36dp frame item: 10dp each side.
        size == RaftButtonSize.Md && variant == RaftButtonVariant.OnFrame -> RaftButtonMetrics(36.dp, 10.dp, 10.dp, 16.dp)
        size == RaftButtonSize.Md -> RaftButtonMetrics(32.dp, 12.dp, 10.dp, 16.dp)
        size == RaftButtonSize.Lg -> RaftButtonMetrics(44.dp, 16.dp, 14.dp, 18.dp)
        // xl centres its content in the full width: no tighter icon side.
        else -> RaftButtonMetrics(52.dp, 20.dp, 20.dp, 18.dp)
    }

private fun raftButtonTextStyle(size: RaftButtonSize, variant: RaftButtonVariant, typography: RaftTypography): TextStyle {
    // raft `text-footnote` (md), `text-subhead` (lg, and md on the frame), `text-body` (xl).
    val base =
        when {
            size == RaftButtonSize.Md && variant == RaftButtonVariant.OnFrame -> typography.subhead
            size == RaftButtonSize.Md -> typography.footnote
            size == RaftButtonSize.Lg -> typography.subhead
            else -> typography.body
        }
    return base.copy(fontWeight = FontWeight.Bold)
}

/**
 * The shared box of [RaftButton] and [RaftIconButton]: a 1.5dp border inside the box
 * (transparent on frameless variants, so every variant has the same box), control radius,
 * the sticker's die-cut edge, the press overlay, the focus ring outside it all, and
 * [LocalRaftContentColor] set to the ink for the content.
 */
@Composable
private fun RaftButtonBox(
    look: RaftButtonLook,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    loading: Boolean,
    loadingLabel: String,
    interactionSource: MutableInteractionSource?,
    content: @Composable () -> Unit,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val active = enabled && !loading
    val pressed = active && raftIsPressed(source)
    // `focus-visible:shadow-focus`. raft-ui keeps a loading button focusable but
    // `aria-disabled`: the clickable stays enabled (so focus stays on it when it turns busy),
    // drops the tap while busy, and semantics say disabled, so no click action is offered.
    val focused = enabled && raftIsFocused(source)
    val shape = RoundedCornerShape(RaftRadius.Control)
    Box(
        modifier =
            modifier
                .raftFocusRing(focused, RaftRadius.Control, RaftTheme.colors)
                .raftDieCutEdge(look.outerRing, RaftRadius.Control)
                .background(look.fill, shape)
                .raftPressOverlay(pressed, look.pressOverlay, RaftRadius.Control)
                .border(RaftStroke.Control, look.edge, shape)
                .semantics { if (loading) disabled() }
                .clickable(
                    interactionSource = source,
                    indication = null,
                    enabled = enabled,
                    role = Role.Button,
                    onClick = { if (!loading) onClick() },
                ),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalRaftContentColor provides look.ink) {
            // `data-[loading=true]:invisible`: the content keeps the width, the spinner sits on top.
            Box(Modifier.alpha(if (loading) 0f else 1f), contentAlignment = Alignment.Center) { content() }
            if (loading) RaftSpinner(contentDescription = loadingLabel)
        }
    }
}

/**
 * raft-ui 0.8.0 Button. Its colour says what kind of action it is ([RaftButtonVariant]),
 * its size where it sits ([RaftButtonSize]). Every button is flat: the ink border or the
 * sticker marks it as a control, never a shadow.
 *
 * - Box: 1.5dp border, `radius-control`, Space Grotesk bold, one line. `md` 32dp tall,
 *   12dp padding (10dp on an icon's side), 13/18 text, 16dp icons; `lg` 44dp, 16dp
 *   (14dp), 15/20, 18dp icons; `xl` 52dp, 20dp, 16/24, 18dp icons; gap 8dp.
 * - Stickers ([RaftButtonVariant.Primary], `Warning`, `Danger`): `sticker-ink` border and
 *   type, plus a 1.5dp `sticker-edge` ring outside (the die-cut edge in dark).
 * - Pressed: one overlay over the resting fill (`sticker-pressed` on a sticker,
 *   `fill-pressed` on paper and ghost, `fill-frame-pressed` on the frame).
 * - Focus (keyboard only): the focus ring outside the box, under a sticker's die-cut edge.
 * - Disabled: `fill-muted`, `line-hairline` border, `foreground-disabled` type, no die-cut
 *   edge (a disabled button is no longer a sticker). An `OnFrame` button only greys its type.
 * - Busy ([loading]): the content is hidden (keeping the width) under a pixel
 *   [RaftSpinner] named [loadingLabel]; the button keeps its colour (Button > States),
 *   stays focusable (keyboard focus doesn't jump away when it turns busy) and ignores taps;
 *   semantics mark it disabled. Write the label as what is happening ("Creating…").
 *
 * [leadingIcon] / [trailingIcon] are drawn in an icon-size box with
 * [LocalRaftContentColor] set to the button ink. The box is exactly 32 / 44 / 52dp: raft-ui
 * widens a 32dp button's hit area to 44dp with an overhanging `::after`; here the caller
 * leaves room around it (Compose foundation has no layout-free hit-area expansion).
 */
@Composable
fun RaftButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: RaftButtonVariant = RaftButtonVariant.Default,
    size: RaftButtonSize = RaftButtonSize.Md,
    enabled: Boolean = true,
    loading: Boolean = false,
    loadingLabel: String = "Loading",
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    interactionSource: MutableInteractionSource? = null,
) {
    val look = raftButtonLook(variant, enabled, frameless = false, colors = RaftTheme.colors)
    val metrics = raftButtonMetrics(size, variant)
    val textStyle = raftButtonTextStyle(size, variant, RaftTheme.typography)
    // CSS padding sits inside the 1.5dp border.
    val start = (if (leadingIcon != null) metrics.iconSidePaddingX else metrics.paddingX) + RaftStroke.Control
    val end = (if (trailingIcon != null) metrics.iconSidePaddingX else metrics.paddingX) + RaftStroke.Control
    RaftButtonBox(
        look = look,
        onClick = onClick,
        modifier = modifier.height(metrics.height),
        enabled = enabled,
        loading = loading,
        loadingLabel = loadingLabel,
        interactionSource = interactionSource,
    ) {
        Row(
            modifier = Modifier.padding(start = start, end = end),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leadingIcon != null) {
                Box(Modifier.size(metrics.iconSize), contentAlignment = Alignment.Center) { leadingIcon() }
            }
            RaftText(
                text = text,
                style = textStyle,
                color = look.ink,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
            )
            if (trailingIcon != null) {
                Box(Modifier.size(metrics.iconSize), contentAlignment = Alignment.Center) { trailingIcon() }
            }
        }
    }
}

/**
 * raft-ui 0.8.0 Button at an icon size (the doc's IconButton): a square of the same
 * variants as [RaftButton]. `md` is 32dp with an 18dp icon (36dp / 20dp for `OnFrame`),
 * `lg` 44dp with a 22dp icon.
 *
 * A phone top bar uses `variant = Ghost, size = Lg`; a tool alone in content `Default`;
 * Send `Primary`. A disabled ghost or on-frame icon button only greys its icon; the
 * others take the shared disabled look. [contentDescription] names the action in Title
 * Case ("Add Reaction"). [icon] is drawn in an icon-size box with
 * [LocalRaftContentColor] set to the button ink.
 */
@Composable
fun RaftIconButton(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    variant: RaftButtonVariant = RaftButtonVariant.Default,
    size: RaftIconButtonSize = RaftIconButtonSize.Md,
    enabled: Boolean = true,
    loading: Boolean = false,
    loadingLabel: String = "Loading",
    interactionSource: MutableInteractionSource? = null,
    icon: @Composable () -> Unit,
) {
    val look = raftButtonLook(variant, enabled, frameless = variant == RaftButtonVariant.Ghost, colors = RaftTheme.colors)
    val onFrame = variant == RaftButtonVariant.OnFrame && size == RaftIconButtonSize.Md
    val (box, glyph) =
        when {
            onFrame -> 36.dp to 20.dp
            size == RaftIconButtonSize.Md -> 32.dp to 18.dp
            else -> 44.dp to 22.dp
        }
    RaftButtonBox(
        look = look,
        onClick = onClick,
        modifier = modifier.size(box).semantics { this.contentDescription = contentDescription },
        enabled = enabled,
        loading = loading,
        loadingLabel = loadingLabel,
        interactionSource = interactionSource,
    ) {
        Box(Modifier.size(glyph), contentAlignment = Alignment.Center) { icon() }
    }
}
