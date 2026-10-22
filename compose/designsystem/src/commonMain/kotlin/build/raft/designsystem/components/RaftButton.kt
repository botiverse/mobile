package build.raft.designsystem.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.LayoutDirection
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
import build.raft.designsystem.theme.LocalRaftStrings
import build.raft.designsystem.theme.LocalRaftMisuse
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

/**
 * raft-ui Button icon sizes (0.21: `icon-md` and `icon-lg`; `icon-xs` / `icon-sm` are old
 * names of `icon-md`). There is no smaller icon button: a component that needs a 28dp one
 * (the sidebar section control) sizes it itself.
 */
enum class RaftIconButtonSize(internal val box: Dp, internal val glyph: Dp) {
    /** 32dp with an 18dp icon (36dp / 20dp on the frame). */
    Md(32.dp, 18.dp),

    /** 44dp with a 22dp icon: a phone top bar, next to a 44dp input. */
    Lg(44.dp, 22.dp),
}

/** Resolved colours of one variant x state (raft-ui button.tsx). */
internal data class RaftButtonLook(
    val fill: Color,
    val ink: Color,
    /** The 1.5dp border (transparent for frameless variants). */
    val edge: Color,
    /** The die-cut edge outside a sticker (`ring-sticker-edge`): transparent in light. */
    val outerRing: Color,
    /**
     * Laid over [fill] while pressed or hovered (CSS `hover:` / `active:` `press-overlay*`,
     * `bg-fill-pressed`, `bg-fill-frame-pressed`); transparent when disabled.
     */
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

internal fun raftButtonTextStyle(size: RaftButtonSize, variant: RaftButtonVariant, typography: RaftTypography): TextStyle {
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
 * the sticker's die-cut edge, the press overlay (also on mouse / trackpad hover, as on the
 * web; never a Material ripple), the focus ring outside it all, and [LocalRaftContentColor]
 * set to the ink for the content.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun RaftButtonBox(
    look: RaftButtonLook,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    loading: Boolean,
    loadingLabel: String,
    interactionSource: MutableInteractionSource?,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    widenTouchTarget: Boolean = false,
    join: RaftButtonJoin = RaftButtonJoin.None,
    content: @Composable () -> Unit,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val active = enabled && !loading
    // raft-ui shows the same overlay on `hover:` and `active:`.
    val isPressed = raftIsPressed(source)
    val isHovered = raftIsHovered(source)
    val pressed = active && (isPressed || isHovered)
    // `focus-visible:shadow-focus`. raft-ui keeps a loading button focusable but
    // `aria-disabled`: the clickable stays enabled (so focus stays on it when it turns busy),
    // drops the tap while busy, and semantics say disabled, so no click action is offered.
    val isFocused = raftIsFocused(source)
    val focused = enabled && isFocused
    val radius = if (LocalRaftEmbeddedControl.current) RaftRadius.Chip else RaftRadius.Control
    val shape = join.shape(radius)
    Box(
        modifier =
            modifier
                .raftFocusRing(focused, radius, RaftTheme.colors)
                .raftDieCutEdge(look.outerRing, radius)
                .background(look.fill, shape)
                .then(
                    if (join == RaftButtonJoin.None) {
                        Modifier.raftPressOverlay(pressed, look.pressOverlay, radius)
                    } else {
                        Modifier.background(if (pressed) look.pressOverlay else Color.Transparent, shape)
                    },
                )
                .then(
                    if (join == RaftButtonJoin.Start) {
                        Modifier.raftBorderWithoutStartEdge(RaftStroke.Control, look.edge, radius)
                    } else {
                        Modifier.border(RaftStroke.Control, look.edge, shape)
                    },
                )
                .semantics { if (loading) disabled() }
                .then(
                    if (widenTouchTarget) {
                        Modifier.raftTouchTarget(
                            enabled && !loading,
                            source,
                            onClick,
                            onLongClick = onLongClick?.let { long -> { if (!loading) long() } },
                            // A control joined on its start edge leaves that side's taps to its neighbour.
                            expandStart = join != RaftButtonJoin.Start,
                        )
                    } else {
                        Modifier
                    },
                )
                .then(
                    if (onLongClick != null) {
                        Modifier.combinedClickable(
                            interactionSource = source,
                            indication = null,
                            enabled = enabled,
                            role = Role.Button,
                            onLongClickLabel = onLongClickLabel,
                            onLongClick = { if (!loading) onLongClick() },
                            onClick = { if (!loading) onClick() },
                        )
                    } else {
                        Modifier.clickable(
                            interactionSource = source,
                            indication = null,
                            enabled = enabled,
                            role = Role.Button,
                            onClick = { if (!loading) onClick() },
                        )
                    },
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
 * Which edge a button shares with a neighbour (raft-ui split buttons such as
 * MessageListJumpPillGroup): [End] squares the end corners (`rounded-e-none`), [Start] squares
 * the start corners and draws no start edge (`rounded-s-none border-s-0`), so the two read as
 * one control split by the first one's edge.
 */
internal enum class RaftButtonJoin {
    None,
    End,
    Start,
}

internal fun RaftButtonJoin.shape(r: Dp = RaftRadius.Control): Shape =
    when (this) {
        RaftButtonJoin.None -> RoundedCornerShape(r)
        RaftButtonJoin.End -> RoundedCornerShape(topStart = r, bottomStart = r, topEnd = 0.dp, bottomEnd = 0.dp)
        RaftButtonJoin.Start -> RoundedCornerShape(topStart = 0.dp, bottomStart = 0.dp, topEnd = r, bottomEnd = r)
    }

/** Whether the buttons here sit inside another component's frame ([RaftEmbeddedControls]). */
internal val LocalRaftEmbeddedControl = staticCompositionLocalOf { false }

/**
 * raft-design Shape > Inside a frame: the buttons in [content] are embedded in another
 * component's frame (the end buttons of a field, the composer's buttons and its send
 * button, the corner action of a card or of media) and wear `radius-chip` instead of
 * `radius-control`, so one kind of small button has one corner wherever it sits. The frame
 * keeps its own radius; place the buttons 6dp inside its outer edge and 6dp apart.
 */
@Composable
fun RaftEmbeddedControls(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalRaftEmbeddedControl provides true, content = content)
}

/**
 * The [width] border of a [RaftButtonJoin.Start] button: top, end and bottom, inside the box,
 * with [radius] on the end corners and none on the start side (`border-s-0`).
 */
internal fun Modifier.raftBorderWithoutStartEdge(width: Dp, color: Color, radius: Dp): Modifier =
    drawBehind {
        val stroke = width.toPx()
        val half = stroke / 2f
        val corner = CornerRadius((radius.toPx() - half).coerceAtLeast(0f))
        val ltr = layoutDirection == LayoutDirection.Ltr
        // The start side runs a stroke past the box, so the clip below removes that edge.
        val left = if (ltr) -stroke else half
        val right = if (ltr) size.width - half else size.width + stroke
        val rect =
            RoundRect(
                left = left,
                top = half,
                right = right,
                bottom = size.height - half,
                topLeftCornerRadius = if (ltr) CornerRadius.Zero else corner,
                topRightCornerRadius = if (ltr) corner else CornerRadius.Zero,
                bottomRightCornerRadius = if (ltr) corner else CornerRadius.Zero,
                bottomLeftCornerRadius = if (ltr) CornerRadius.Zero else corner,
            )
        clipRect { drawPath(Path().apply { addRoundRect(rect) }, color, style = Stroke(stroke)) }
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
 * - Pressed, and hovered by a mouse or trackpad: one overlay over the resting fill
 *   (`sticker-pressed` on a sticker, `fill-pressed` on paper and ghost,
 *   `fill-frame-pressed` on the frame), inside the button's radius. No ripple.
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
    loadingLabel: String = LocalRaftStrings.current.loading,
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
 * others take the shared disabled look.
 *
 * Pressed (and hovered by a mouse or trackpad) it lays the variant's press overlay inside
 * its radius, as the web does on hover: `fill-pressed` on a ghost or default button,
 * `fill-frame-pressed` on the frame, `sticker-pressed` on a sticker. Disabled, no fill.
 *
 * [tooltip] names the action in Title Case ("Add Reaction"): an icon alone does not say
 * what it does. It is the button's content description for screen readers, and the label
 * of a [RaftTooltip] shown on a long press, or while a mouse hovers (also when disabled, as
 * raft-ui's tooltip on a disabled button). A long press that opens the tooltip does not
 * click. [icon] is drawn in an icon-size box with [LocalRaftContentColor] set to the button
 * ink.
 *
 * - [tooltip] = null shows no tooltip; the button is then named by [contentDescription]
 *   (which defaults to the tooltip). Pass one unless the button sits in a row whose own
 *   label already says what it does (the row merges it); raft-ui's Button doc still asks
 *   for an `aria-label` on every icon-only button.
 * - [onLongClick] is a second action on a long press (named [longClickLabel] for screen
 *   readers). The long press then belongs to the action: the tooltip opens only under a
 *   mouse or trackpad pointer.
 */
@Composable
fun RaftIconButton(
    onClick: () -> Unit,
    tooltip: String?,
    modifier: Modifier = Modifier,
    variant: RaftButtonVariant = RaftButtonVariant.Default,
    size: RaftIconButtonSize = RaftIconButtonSize.Md,
    enabled: Boolean = true,
    loading: Boolean = false,
    loadingLabel: String = LocalRaftStrings.current.loading,
    interactionSource: MutableInteractionSource? = null,
    contentDescription: String? = tooltip,
    onLongClick: (() -> Unit)? = null,
    longClickLabel: String? = null,
    icon: @Composable () -> Unit,
) {
    val onFrame = variant == RaftButtonVariant.OnFrame && size == RaftIconButtonSize.Md
    RaftSizedIconButton(
        onClick = onClick,
        tooltip = tooltip,
        box = if (onFrame) 36.dp else size.box,
        glyph = if (onFrame) 20.dp else size.glyph,
        modifier = modifier,
        variant = variant,
        enabled = enabled,
        loading = loading,
        loadingLabel = loadingLabel,
        interactionSource = interactionSource,
        contentDescription = contentDescription,
        onLongClick = onLongClick,
        longClickLabel = longClickLabel,
        icon = icon,
    )
}

/**
 * A [RaftIconButton] of a size its component sets ([box], with a [glyph] icon) rather than a
 * [RaftIconButtonSize]: the sidebar section control's 28dp (raft-ui: "28px is not a Button
 * size"). A box under 32dp widens its touch target to 44dp without growing (a tap there
 * clicks; a long press needs the box); keep the space round it free.
 */
@Composable
internal fun RaftSizedIconButton(
    onClick: () -> Unit,
    tooltip: String?,
    box: Dp,
    glyph: Dp,
    modifier: Modifier = Modifier,
    variant: RaftButtonVariant = RaftButtonVariant.Default,
    enabled: Boolean = true,
    loading: Boolean = false,
    loadingLabel: String = LocalRaftStrings.current.loading,
    interactionSource: MutableInteractionSource? = null,
    contentDescription: String? = tooltip,
    onLongClick: (() -> Unit)? = null,
    longClickLabel: String? = null,
    icon: @Composable () -> Unit,
) {
    val look = raftButtonLook(variant, enabled, frameless = variant == RaftButtonVariant.Ghost, colors = RaftTheme.colors)
    val description = contentDescription
    val misuse = LocalRaftMisuse.current
    if (description == null) misuse("RaftIconButton needs a name: pass tooltip or contentDescription")
    val button: @Composable (Modifier) -> Unit = { outer ->
        RaftButtonBox(
            look = look,
            onClick = onClick,
            modifier =
                outer
                    .size(box)
                    .then(if (description != null) Modifier.semantics { this.contentDescription = description } else Modifier),
            enabled = enabled,
            loading = loading,
            loadingLabel = loadingLabel,
            interactionSource = interactionSource,
            onLongClick = onLongClick,
            onLongClickLabel = longClickLabel,
            widenTouchTarget = box < RaftIconButtonSize.Md.box,
        ) {
            Box(Modifier.size(glyph), contentAlignment = Alignment.Center) { icon() }
        }
    }
    if (tooltip != null) {
        RaftTooltip(text = tooltip, modifier = modifier, openOnLongPress = onLongClick == null) { button(Modifier) }
    } else {
        button(modifier)
    }
}
