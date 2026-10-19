package build.raft.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.designsystem.theme.LocalRaftSurfaceColor
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.theme.RaftTypography
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke
import build.raft.designsystem.tokens.RaftUiThemeColors

/** raft-ui Card `variant`. */
enum class RaftCardVariant {
    /** A card on its own, or one with little inside it. */
    Default,

    /** Many controls or nested parts: a hairline above each [RaftCardSection]. */
    Inset,

    /**
     * A choice inside a form: control radius, the 1.5dp field border and a field-size
     * title. The pick is shown by its checkbox or radio, never by the card; with an
     * `onClick` the whole card is the target (see [RaftCard]).
     */
    Option,
}

/** Resolved chrome of one card variant (raft-ui card.recipe.ts). */
@Immutable
internal data class RaftCardLook(
    val radius: Dp,
    val border: Dp,
    val borderColor: Color,
    val header: PaddingValues,
    /** Padding of the body under a header. */
    val content: PaddingValues,
    val headerGap: Dp,
)

internal fun raftCardLook(variant: RaftCardVariant, colors: RaftUiThemeColors): RaftCardLook =
    when (variant) {
        // `p-4`, and the header drops its bottom padding when content follows (`pb-0`).
        RaftCardVariant.Default ->
            RaftCardLook(RaftRadius.Container, RaftStroke.Hairline, colors.lineHairline, PaddingValues(16.dp), PaddingValues(16.dp), 16.dp)
        RaftCardVariant.Inset ->
            RaftCardLook(
                RaftRadius.Container,
                RaftStroke.Hairline,
                colors.lineHairline,
                PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                PaddingValues(16.dp),
                16.dp,
            )
        RaftCardVariant.Option ->
            RaftCardLook(
                RaftRadius.Control,
                RaftStroke.Control,
                colors.lineField,
                PaddingValues(12.dp),
                PaddingValues(start = 12.dp, end = 12.dp, bottom = 12.dp),
                12.dp,
            )
    }

private fun raftCardTitleStyle(variant: RaftCardVariant, typography: RaftTypography): TextStyle =
    when (variant) {
        // `text-callout font-semibold`: a field label, so it can't outrank the label above.
        RaftCardVariant.Option -> typography.body.copy(fontWeight = FontWeight.SemiBold)
        // `--card-title-*`: 16/24 600.
        else -> typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
    }

/**
 * raft-ui 0.8.0 Card: one subject on a page. Flat on `layer-card` with no shadow:
 * [RaftCardVariant.Default] and `Inset` have a 1dp `line-hairline` edge and the container
 * radius, `Option` the 1.5dp `line-field` edge and the control radius. The card clips its
 * content (`overflow-hidden`), so don't put stickers on its edge.
 *
 * The header is [title] (sentence case) with an optional [description] line under it,
 * [leading] (a 16dp icon, an avatar or a switch spanning both lines) and [trailing] (a
 * status, badge or switch centred on the title line). [content] is the body: padded for
 * `Default` and `Option`; for `Inset`, fill it with [RaftCardSection]s. [footer] is a
 * note under a hairline, in `text-caption` `foreground-hint`. The card provides `layer-card`
 * as [LocalRaftSurfaceColor].
 *
 * An [RaftCardVariant.Option] card with [onClick] is one choice of a set, the whole card
 * the target (its leading radio or checkbox is drawn, not tapped: pass it no handler).
 * Press lays `fill-pressed` over the card, keyboard focus draws the focus ring outside
 * it, and semantics carry [role] (`RadioButton` selected, or `Checkbox` checked) with
 * [selected]; the card itself never shows the pick. [enabled] = false drops press, focus
 * and taps. Other variants ignore [onClick].
 */
@Composable
fun RaftCard(
    modifier: Modifier = Modifier,
    variant: RaftCardVariant = RaftCardVariant.Default,
    title: String? = null,
    description: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    footer: (@Composable RowScope.() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    selected: Boolean = false,
    role: Role = Role.RadioButton,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val colors = RaftTheme.colors
    val look = raftCardLook(variant, colors)
    val shape = RoundedCornerShape(look.radius)
    val clickable = variant == RaftCardVariant.Option && onClick != null
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val pressed = clickable && enabled && raftIsPressed(source)
    val focused = clickable && enabled && raftIsFocused(source)
    CompositionLocalProvider(LocalRaftContentColor provides colors.foreground, LocalRaftSurfaceColor provides colors.layerCard) {
        Column(
            modifier
                .raftFocusRing(focused, look.radius, colors)
                .clip(shape)
                .background(colors.layerCard, shape)
                .raftPressOverlay(pressed, raftPressOverlayColor(RaftPressSurface.Paper, colors), look.radius)
                .border(look.border, look.borderColor, shape)
                .then(onClick?.takeIf { clickable }?.let { Modifier.raftOptionTarget(selected, role, enabled, source, it) } ?: Modifier),
        ) {
            val hasHeader = title != null || description != null || leading != null || trailing != null
            if (hasHeader) {
                val headerPadding =
                    if (variant == RaftCardVariant.Default && content != null) {
                        PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp)
                    } else {
                        look.header
                    }
                RaftCardHeader(variant, look, title, description, leading, trailing, Modifier.padding(headerPadding))
            }
            if (content != null) {
                when (variant) {
                    RaftCardVariant.Inset -> Column(Modifier.fillMaxWidth(), content = content)
                    else ->
                        Column(
                            Modifier.fillMaxWidth().padding(if (hasHeader || variant != RaftCardVariant.Option) look.content else look.header),
                            content = content,
                        )
                }
            }
            if (footer != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .topHairline(colors.lineHairline)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CompositionLocalProvider(LocalRaftContentColor provides colors.foregroundHint) { footer() }
                }
            }
        }
    }
}

/** The option card's target: `selectable`, or `toggleable` for a [Role.Checkbox] card. */
private fun Modifier.raftOptionTarget(
    selected: Boolean,
    role: Role,
    enabled: Boolean,
    source: MutableInteractionSource,
    onClick: () -> Unit,
): Modifier =
    if (role == Role.Checkbox) {
        toggleable(value = selected, interactionSource = source, indication = null, enabled = enabled, role = role, onValueChange = { onClick() })
    } else {
        selectable(selected = selected, interactionSource = source, indication = null, enabled = enabled, role = role, onClick = onClick)
    }

/** A block of an [RaftCardVariant.Inset] card: `p-4` under a hairline. */
@Composable
fun RaftCardSection(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .topHairline(RaftTheme.colors.lineHairline)
            .padding(16.dp),
        content = content,
    )
}

/** Footer text: `text-caption` in `foreground-hint`. */
@Composable
fun RaftCardFooterText(text: String, modifier: Modifier = Modifier) {
    RaftText(text, modifier = modifier, style = RaftTheme.typography.caption, color = RaftTheme.colors.foregroundHint)
}

@Composable
private fun RaftCardHeader(
    variant: RaftCardVariant,
    look: RaftCardLook,
    title: String?,
    description: String?,
    leading: (@Composable () -> Unit)?,
    trailing: (@Composable () -> Unit)?,
    modifier: Modifier,
) {
    val typography = RaftTheme.typography
    val colors = RaftTheme.colors
    val titleStyle = raftCardTitleStyle(variant, typography)
    // The leading part is centred on the first line (`min-h-(--card-title-line-height)`).
    val titleLine = with(LocalDensity.current) { titleStyle.lineHeight.toDp() }
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(look.headerGap),
        verticalAlignment = Alignment.Top,
    ) {
        if (leading != null) {
            Box(Modifier.heightIn(min = titleLine), contentAlignment = Alignment.Center) { leading() }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (title != null) {
                RaftText(title, modifier = Modifier.semantics { heading() }, style = titleStyle, color = colors.foreground)
            }
            if (description != null) {
                RaftText(
                    description,
                    style = if (variant == RaftCardVariant.Option) typography.footnote else typography.body,
                    color = colors.foregroundMuted,
                )
            }
        }
        if (trailing != null) {
            Box(Modifier.heightIn(min = titleLine), contentAlignment = Alignment.Center) { trailing() }
        }
    }
}

/** A 1dp line along the top edge (CSS `border-t`). */
internal fun Modifier.topHairline(color: Color, start: Dp = 0.dp): Modifier =
    drawBehind {
        val y = RaftStroke.Hairline.toPx() / 2f
        drawLine(color, Offset(start.toPx(), y), Offset(size.width, y), strokeWidth = RaftStroke.Hairline.toPx())
    }
