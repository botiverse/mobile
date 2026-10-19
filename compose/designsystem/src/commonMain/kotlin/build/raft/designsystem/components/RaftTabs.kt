package build.raft.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke
import build.raft.designsystem.tokens.RaftUiThemeColors

// Navigation selection (States > Selected: navigation): the selected tab, segment or
// pressed toggle is the yellow active sticker (RaftStates.kt) in every one of these.

/** raft-ui Tabs list `variant`. */
enum class RaftTabsVariant {
    /** Each tab a 32dp ghost; the selected one the yellow sticker. No track. */
    Default,

    /** 44dp text tabs 20dp apart; the selected one `foreground` with a 2dp ink line under it. */
    Underline,
}

private val LocalRaftTabsVariant = staticCompositionLocalOf { RaftTabsVariant.Default }

/**
 * raft-ui 0.8.0 Tabs (`placement="top"`): a row of [RaftTab]s that switch the panel below
 * them. [RaftTabsVariant.Default] puts the tabs 2dp apart, [RaftTabsVariant.Underline]
 * 20dp. The row hugs its tabs and does not scroll; for a panel's tab strip use
 * [RaftTabStrip].
 *
 * Not done: long-press drag to reorder (raft-ui SortableTabsList; the agent-detail
 * strip). Vertical placements are not ported.
 */
@Composable
fun RaftTabs(
    modifier: Modifier = Modifier,
    variant: RaftTabsVariant = RaftTabsVariant.Default,
    content: @Composable RowScope.() -> Unit,
) {
    CompositionLocalProvider(LocalRaftTabsVariant provides variant) {
        Row(
            modifier.selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(if (variant == RaftTabsVariant.Default) 2.dp else 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/**
 * The tab strip of a panel (agent detail, conversation panel tabs; page hand-off §8):
 * [RaftTabs] on `layer-canvas` with 16 / 8dp padding and a 1dp `line-seam` under it,
 * 49dp tall with default tabs. With more tabs than room it scrolls sideways; the padding
 * scrolls with the tabs, so the selected sticker's die-cut edge and the focus ring are
 * never clipped.
 */
@Composable
fun RaftTabStrip(
    modifier: Modifier = Modifier,
    variant: RaftTabsVariant = RaftTabsVariant.Default,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = RaftTheme.colors
    Column(modifier.fillMaxWidth().background(colors.layerCanvas)) {
        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            RaftTabs(
                Modifier.padding(horizontal = 16.dp, vertical = if (variant == RaftTabsVariant.Default) 8.dp else 0.dp),
                variant = variant,
                content = content,
            )
        }
        Box(Modifier.fillMaxWidth().height(RaftStroke.Hairline).background(colors.lineSeam))
    }
}

/**
 * One tab of [RaftTabs]: a `text-callout` semibold `foreground-muted` label (Title Case,
 * one or two words) with an optional 16dp [icon] 6dp before it.
 *
 * - Default variant: 32dp tall, 12dp padding, hugging its label. [selected] is the active
 *   sticker: `brand`, 1.5dp `sticker-ink` edge, bold `sticker-ink` label, the die-cut edge
 *   in dark. Pressed lays `fill-pressed` (`sticker-pressed` on the sticker) over it.
 * - Underline variant: 44dp tall, no padding or fill. [selected] turns the label
 *   `foreground` and draws a 2dp `foreground` line along the tab's bottom.
 * - Keyboard focus draws the focus ring outside it. Disabled: `foreground-disabled`.
 */
@Composable
fun RaftTab(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: (@Composable () -> Unit)? = null,
    interactionSource: MutableInteractionSource? = null,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val select = Modifier.selectable(selected, source, indication = null, enabled = enabled, role = Role.Tab, onClick = onClick)
    when (LocalRaftTabsVariant.current) {
        RaftTabsVariant.Default -> RaftStickerItem(text, selected, enabled, source, modifier.then(select), icon = icon)
        RaftTabsVariant.Underline -> RaftUnderlineTab(text, selected, enabled, source, modifier.then(select), icon)
    }
}

@Composable
private fun RaftUnderlineTab(
    text: String,
    selected: Boolean,
    enabled: Boolean,
    source: MutableInteractionSource,
    modifier: Modifier,
    icon: (@Composable () -> Unit)?,
) {
    val colors = RaftTheme.colors
    val focused = enabled && raftIsFocused(source)
    val ink =
        when {
            !enabled -> colors.foregroundDisabled
            selected || raftIsPressed(source) -> colors.foreground
            else -> colors.foregroundMuted
        }
    val line = colors.foreground
    Row(
        modifier
            .height(44.dp)
            .raftFocusRing(focused, 0.dp, colors)
            .drawWithContent {
                drawContent()
                // `data-active:shadow-[inset_0_-2px_0_var(--foreground)]`
                if (selected && enabled) {
                    val h = 2.dp.toPx()
                    drawRect(line, topLeft = Offset(0f, size.height - h), size = Size(size.width, h))
                }
            },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalRaftContentColor provides ink) {
            if (icon != null) Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) { icon() }
            RaftText(text, style = RaftTheme.typography.body.copy(fontWeight = FontWeight.SemiBold), color = ink, maxLines = 1, softWrap = false)
        }
    }
}

/**
 * The item of default [RaftTabs], [RaftSegmentedControl] and [RaftToggleGroup] (their
 * recipes are the same): 32dp, control radius, 12dp padding inside a 1.5dp edge that is
 * transparent at rest, 6dp gap, a `text-callout` semibold `foreground-muted` label; when
 * [active], the yellow sticker with a bold `sticker-ink` label. A [count] follows in
 * Space Mono `text-mono-meta`, regular weight.
 */
@Composable
internal fun RaftStickerItem(
    text: String,
    active: Boolean,
    enabled: Boolean,
    source: MutableInteractionSource,
    modifier: Modifier,
    icon: (@Composable () -> Unit)? = null,
    count: String? = null,
) {
    val colors = RaftTheme.colors
    val look = raftStickerItemLook(active, enabled, colors)
    val pressed = enabled && raftIsPressed(source)
    val focused = enabled && raftIsFocused(source)
    val typography = RaftTheme.typography
    Row(
        modifier
            .height(32.dp)
            .raftSticker(look, RaftRadius.Control, pressed = pressed, focused = focused, colors = colors)
            .padding(horizontal = 12.dp + RaftStroke.Control),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalRaftContentColor provides look.ink) {
            if (icon != null) Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) { icon() }
            RaftText(
                text,
                style = typography.body.copy(fontWeight = if (active && enabled) FontWeight.Bold else FontWeight.SemiBold),
                color = look.ink,
                maxLines = 1,
                softWrap = false,
            )
            if (count != null) RaftText(count, style = typography.monoMeta, color = look.ink, maxLines = 1, softWrap = false)
        }
    }
}

/**
 * The active sticker when [active], else a ghost in `foreground-muted`. A disabled item
 * is text only (`foreground-disabled`, no fill, no sticker).
 */
internal fun raftStickerItemLook(active: Boolean, enabled: Boolean, colors: RaftUiThemeColors): RaftStickerLook =
    if (enabled) {
        raftActiveStickerLook(active, colors, restingInk = colors.foregroundMuted)
    } else {
        raftActiveStickerLook(false, colors, restingInk = colors.foregroundDisabled)
    }

/**
 * The grey track of [RaftSegmentedControl] and [RaftToggleGroup]: `fill-muted`, radius
 * 11, 3dp padding (room for a sticker's die-cut edge), items 2dp apart, hugging them.
 */
@Composable
private fun RaftTrack(modifier: Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier
            .background(RaftTheme.colors.fillMuted, RoundedCornerShape(11.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * raft-ui 0.8.0 SegmentedControl: two to five views of the same content (All / Mentions /
 * Unread), one always picked. A `fill-muted` track holding [RaftSegment]s; the picked
 * one is the yellow sticker. It hugs its segments; to fill a width pass
 * `Modifier.fillMaxWidth()` and give each segment `Modifier.weight(1f)`.
 */
@Composable
fun RaftSegmentedControl(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    RaftTrack(modifier.selectableGroup(), content)
}

/**
 * One option of a [RaftSegmentedControl]: the sticker item (see [RaftTab]) with an
 * optional [count] in Space Mono after the Title Case label. A radio for accessibility.
 */
@Composable
fun RaftSegment(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    count: String? = null,
    enabled: Boolean = true,
    icon: (@Composable () -> Unit)? = null,
    interactionSource: MutableInteractionSource? = null,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    RaftStickerItem(
        text,
        selected,
        enabled,
        source,
        modifier.selectable(selected, source, indication = null, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        icon = icon,
        count = count,
    )
}

/**
 * raft-ui 0.8.0 ToggleGroup: buttons that each switch something on or off (any number
 * pressed, or at most one when the caller says so), on the segmented control's track.
 * A pressed [RaftToggleGroupItem] is the yellow sticker.
 */
@Composable
fun RaftToggleGroup(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    RaftTrack(modifier, content)
}

/** One button of a [RaftToggleGroup]; [pressed] is its on state. */
@Composable
fun RaftToggleGroupItem(
    text: String,
    pressed: Boolean,
    onPressedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: (@Composable () -> Unit)? = null,
    interactionSource: MutableInteractionSource? = null,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    RaftStickerItem(
        text,
        pressed,
        enabled,
        source,
        modifier.toggleable(pressed, source, indication = null, enabled = enabled, role = Role.Switch, onValueChange = onPressedChange),
        icon = icon,
    )
}
