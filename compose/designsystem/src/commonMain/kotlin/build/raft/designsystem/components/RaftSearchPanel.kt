package build.raft.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.designsystem.theme.LocalRaftStrings
import build.raft.designsystem.theme.LocalRaftSurfaceColor
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke

/**
 * The key glyphs a [RaftKbd] shows (web `searchConstants` / `SearchOverlay`): not translated.
 */
object RaftKeyGlyph {
    const val ArrowUp: String = "↑"
    const val ArrowDown: String = "↓"
    const val Return: String = "↵"
    const val Escape: String = "Esc"

    /**
     * The global search shortcut as the top bar's [RaftSearchTrigger] shows it (web
     * `getGlobalSearchShortcutLabel`): ⌘K where the shortcuts use Command ([apple]: macOS,
     * iPadOS), Ctrl+K elsewhere.
     */
    fun searchShortcut(apple: Boolean): String = if (apple) "⌘K" else "Ctrl+K"
}

/**
 * One hint of a [RaftSearchPanelFooter]: the [keys] (each its own [RaftKbd]) and what they do
 * ([label], "Select", "Open").
 */
@Immutable
data class RaftKeyHint(val keys: List<String>, val label: String)

/** Metrics of [RaftSearchPanel] (web `searchOverlayAnchor.ts`, raft-ui `DialogSurface`). */
object RaftSearchPanelDefaults {
    /** `min(720px, 92vw)`. */
    val MaxWidth: Dp = 720.dp
    const val WindowWidthFraction: Float = 0.92f

    /** `min(680px, 80vh)`: a fixed height, so the panel never grows or shrinks with its results. */
    val MaxHeight: Dp = 680.dp
    const val WindowHeightFraction: Float = 0.8f

    /** `calc(100vh - top - 24px)`: room kept under the panel when the anchor sits low. */
    val BottomMargin: Dp = 24.dp

    /** A row's hit height on a touch screen with no keyboard (raft-ui's 44px target). */
    val RowMinHeight: Dp = 44.dp

    /**
     * The panel's width in a window [windowWidth] wide: as wide as the anchor field
     * ([anchorWidth]), never narrower than `min(720, 92% of the window)`.
     */
    fun width(windowWidth: Dp, anchorWidth: Dp = 0.dp): Dp =
        maxOf(anchorWidth, minOf(MaxWidth, windowWidth * WindowWidthFraction)).coerceAtMost(windowWidth)

    /**
     * The panel's height when its top is [top] in a window [windowHeight] tall: `min(680, 80%
     * of the window, the room under the top less 24)`, never below zero.
     */
    fun height(windowHeight: Dp, top: Dp): Dp =
        minOf(MaxHeight, windowHeight * WindowHeightFraction, windowHeight - top - BottomMargin).coerceAtLeast(0.dp)

    /**
     * Where the panel's top goes without an anchor (web `SEARCH_OVERLAY_FALLBACK_TOP`):
     * `max(8% of the window + 16, centred)`.
     */
    fun fallbackTop(windowHeight: Dp): Dp {
        val panel = minOf(MaxHeight, windowHeight * WindowHeightFraction)
        return maxOf(windowHeight * 0.08f + 16.dp, (windowHeight - panel) / 2)
    }
}

/**
 * The web's desktop search panel frame (`SearchOverlay`: a centred modal on raft-ui
 * `DialogSurface`), hanging from the top bar's search field over the page.
 *
 * - The page behind is dimmed by [RaftTheme.scrim]; a tap or click on it calls
 *   [onDismissRequest] (named [dismissLabel] for screen readers). Escape is the host's (the
 *   window or dialog the panel sits in), so a filter menu can take it first.
 * - The card: `layer-popover`, the dialog radius (16dp), a 1dp `line-seam` ring and
 *   `shadow-float`, with no open animation. It is [RaftSearchPanelDefaults.width] wide and
 *   [RaftSearchPanelDefaults.height] tall (fixed, so it never jumps as results arrive), its top
 *   at [anchorTop] (the field's top edge in this layout's coordinates;
 *   [RaftSearchPanelDefaults.fallbackTop] when null), centred on the layout horizontally.
 * - Inside, top to bottom: [head] (the search field), [filters] when given, [body] filling the
 *   rest (the caller scrolls it), [footer] when given ([RaftSearchPanelFooter]); a 1dp
 *   `line-seam` between each.
 *
 * Fill the window with it (a [RaftWindowDialog] without its own scrim, or the top of a screen).
 */
@Composable
fun RaftSearchPanel(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    anchorTop: Dp? = null,
    anchorWidth: Dp = 0.dp,
    dismissLabel: String = LocalRaftStrings.current.close,
    filters: (@Composable ColumnScope.() -> Unit)? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    head: @Composable ColumnScope.() -> Unit,
    body: @Composable ColumnScope.() -> Unit,
) {
    val colors = RaftTheme.colors
    BoxWithConstraints(modifier.fillMaxSize()) {
        val top = anchorTop ?: RaftSearchPanelDefaults.fallbackTop(maxHeight)
        val width = RaftSearchPanelDefaults.width(maxWidth, anchorWidth)
        val height = RaftSearchPanelDefaults.height(maxHeight, top)
        Box(
            Modifier
                .fillMaxSize()
                .background(RaftTheme.scrim)
                .pointerInput(onDismissRequest) { detectTapGestures { onDismissRequest() } }
                .clearAndSetSemantics {
                    contentDescription = dismissLabel
                    onClick(label = dismissLabel) { onDismissRequest(); true }
                },
        )
        CompositionLocalProvider(
            LocalRaftContentColor provides colors.foreground,
            LocalRaftSurfaceColor provides colors.layerPopover,
        ) {
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = top)
                    .size(width, height)
                    .raftOverlaySurface(colors.layerPopover, colors.lineSeam, RaftTheme.isDark, RaftRadius.Dialog)
                    // The card takes every pointer event that reaches it: a click on its blank
                    // parts never falls through to the scrim and closes it.
                    .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
            ) {
                head()
                RaftSearchPanelSeam()
                if (filters != null) {
                    filters()
                    RaftSearchPanelSeam()
                }
                Column(Modifier.fillMaxWidth().weight(1f)) { body() }
                if (footer != null) {
                    RaftSearchPanelSeam()
                    footer()
                }
            }
        }
    }
}

@Composable
private fun RaftSearchPanelSeam() {
    Box(Modifier.fillMaxWidth().height(RaftStroke.Hairline).background(RaftTheme.colors.lineSeam))
}

/**
 * The search panel's key-hint line (web `desktop-search-overlay-footer`): each [RaftKeyHint]'s
 * keys as [RaftKbd]s then its label, `text-caption` `foreground-hint`, 4dp inside a hint and
 * 16dp between hints, `px-3 py-2`. The hints are decoration; screen readers skip them.
 */
@Composable
fun RaftSearchPanelFooter(hints: List<RaftKeyHint>, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).clearAndSetSemantics {},
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        hints.forEach { hint -> RaftKeyHintItem(hint) }
    }
}

/** One [RaftKeyHint]: its keys, then its label. */
@Composable
fun RaftKeyHintItem(hint: RaftKeyHint, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        hint.keys.forEach { key -> RaftKbd(key) }
        RaftText(hint.label, style = RaftTheme.typography.caption, color = RaftTheme.colors.foregroundHint, maxLines = 1)
    }
}

/**
 * raft-ui list cell's keyboard highlight (`data-[selected=true]:before:bg-brand-soft`): the row's
 * shape, inset 8dp from each side with the control radius, filled `brand-soft` (the web's yellow
 * active row) while [selected]. For a search row the arrow keys moved to; a row of the caller's
 * own may wear it too.
 */
@Composable
fun Modifier.raftSearchRowSelected(selected: Boolean): Modifier {
    if (!selected) return this
    val fill = RaftTheme.colors.brandSoft
    return this
        .semantics { this.selected = true }
        .drawBehind { drawRaftListCellShape(fill) }
}

/** The list cell's shape (8dp in from each side, the control radius) in [color]. */
internal fun DrawScope.drawRaftListCellShape(color: Color) {
    val inset = RaftSearchRowDefaults.CellInset.toPx()
    val radius = RaftRadius.Control.toPx()
    drawRoundRect(
        color = color,
        topLeft = Offset(inset, 0f),
        size = Size((size.width - inset * 2).coerceAtLeast(0f), size.height),
        cornerRadius = CornerRadius(radius, radius),
    )
}

/** Metrics of [RaftSearchResultRow] (raft-ui `search-entity` on a `list-cell`). */
object RaftSearchRowDefaults {
    /** `--cell-inset-x`: the shape's inset from the list's sides. */
    val CellInset: Dp = 8.dp

    /** `--cell-pad-x`: the content's padding inside the shape. */
    val CellPadding: Dp = 12.dp

    /** `py-3`. */
    val PaddingVertical: Dp = 12.dp

    /** `gap-3` between the leading slot and the text. */
    val Gap: Dp = 12.dp

    /** `SearchEntityResultIcon`: a 27dp sticker-radius tile on `fill-muted`, 14dp glyph. */
    val IconTile: Dp = 27.dp
    val IconGlyph: Dp = 14.dp
}

/**
 * raft-ui `SearchEntityResult` (search-entity recipe on a list cell), the panel's action and
 * destination row: [leading] (an avatar, or a [RaftSearchResultIcon]), [title] (`text-body`
 * semibold) with an optional [badge] after it, an optional [description] (`text-callout`
 * `foreground-muted`), and an optional [trailing] at the end (the "Search for" row's ↵
 * [RaftKbd]). Its parts are slots, so a caller with highlighted text passes its own.
 *
 * - [selected]: the keyboard cursor is on it ([raftSearchRowSelected], the web's yellow row).
 * - A press lays `fill-cell-press` on the same shape. At least
 *   [RaftSearchPanelDefaults.RowMinHeight] tall, so a finger can hit it without a keyboard.
 */
@Composable
fun RaftSearchResultRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    badge: (@Composable () -> Unit)? = null,
    description: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    title: @Composable () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed = raftIsPressed(source)
    val pressFill = RaftTheme.colors.fillCellPress
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = RaftSearchPanelDefaults.RowMinHeight)
            .raftSearchRowSelected(selected)
            .then(if (pressed && !selected) Modifier.drawBehind { drawRaftListCellShape(pressFill) } else Modifier)
            .clickable(source, indication = null, role = Role.Button, onClick = onClick)
            .padding(
                horizontal = RaftSearchRowDefaults.CellInset + RaftSearchRowDefaults.CellPadding,
                vertical = RaftSearchRowDefaults.PaddingVertical,
            ),
        horizontalArrangement = Arrangement.spacedBy(RaftSearchRowDefaults.Gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                CompositionLocalProvider(LocalRaftContentColor provides RaftTheme.colors.foreground) {
                    Box(Modifier.weight(1f, fill = false)) { title() }
                }
                badge?.invoke()
            }
            if (description != null) {
                CompositionLocalProvider(LocalRaftContentColor provides RaftTheme.colors.foregroundMuted) { description() }
            }
        }
        trailing?.invoke()
    }
}

/** [RaftSearchResultRow]'s plain title: `text-body` semibold `foreground`, one line. */
@Composable
fun RaftSearchResultTitle(text: String, modifier: Modifier = Modifier) {
    RaftText(
        text,
        modifier = modifier,
        style = RaftTheme.typography.body.copy(fontWeight = FontWeight.SemiBold),
        color = RaftTheme.colors.foreground,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** [RaftSearchResultRow]'s plain description: `text-callout` `foreground-muted`, one line. */
@Composable
fun RaftSearchResultDescription(text: String, modifier: Modifier = Modifier) {
    RaftText(
        text,
        modifier = modifier,
        style = RaftTheme.typography.callout,
        color = RaftTheme.colors.foregroundMuted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * raft-ui `SearchEntityResultIcon`: a [RaftSearchRowDefaults.IconTile] tile with the sticker
 * radius on `fill-muted`, [glyph] at 14dp in `foreground-muted` (the "Search for" row's search
 * glyph, a channel's #).
 */
@Composable
fun RaftSearchResultIcon(glyph: RaftGlyph, modifier: Modifier = Modifier) {
    val colors = RaftTheme.colors
    Box(
        modifier
            .size(RaftSearchRowDefaults.IconTile)
            .background(colors.fillMuted, RoundedCornerShape(RaftRadius.Sticker)),
        contentAlignment = Alignment.Center,
    ) {
        RaftGlyphIcon(glyph, size = RaftSearchRowDefaults.IconGlyph, color = colors.foregroundMuted)
    }
}

/**
 * raft-ui `SearchResultsSectionHeading`: [label] in `text-footnote` bold `foreground-hint`,
 * after an optional 14dp [icon] (Recent conversations' clock) and before an optional [count]
 * (`text-mono-meta`), 8dp apart; `px-5 pt-3 pb-2`. A heading for screen readers.
 */
@Composable
fun RaftSearchSectionHeader(
    label: String,
    modifier: Modifier = Modifier,
    icon: RaftGlyph? = null,
    count: Int? = null,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 8.dp)
            .semantics(mergeDescendants = true) { heading() },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) RaftGlyphIcon(icon, size = 14.dp, color = colors.foregroundHint)
        RaftText(
            label,
            modifier = Modifier.weight(1f, fill = false),
            style = typography.footnote.copy(fontWeight = FontWeight.Bold),
            color = colors.foregroundHint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (count != null) RaftText(count.toString(), style = typography.monoMeta, color = colors.foregroundHint, maxLines = 1)
    }
}

/**
 * The web's `ShowMoreToggle` under a capped search section ("Show All 12" / "Show Fewer"): a
 * muted [RaftTextLink] in `text-footnote`, `px-5 py-2`. Lists the rest in place.
 */
@Composable
fun RaftSearchShowAll(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().heightIn(min = RaftSearchPanelDefaults.RowMinHeight).padding(horizontal = 20.dp, vertical = 8.dp), contentAlignment = Alignment.CenterStart) {
        RaftTextLink(label, onClick = onClick, variant = RaftTextLinkVariant.Muted, style = RaftTheme.typography.footnote, maxLines = 1)
    }
}

/** [RaftSearchPanel]'s two standard footer hints: ↑ ↓ [select], ↵ [open]. */
fun raftSearchPanelKeyHints(select: String, open: String): List<RaftKeyHint> =
    listOf(
        RaftKeyHint(listOf(RaftKeyGlyph.ArrowUp, RaftKeyGlyph.ArrowDown), select),
        RaftKeyHint(listOf(RaftKeyGlyph.Return), open),
    )
