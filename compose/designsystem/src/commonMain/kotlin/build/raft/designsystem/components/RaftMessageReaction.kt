package build.raft.designsystem.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius

// raft-ui MessageItem > reactions and footer (raft-design packages/ui/src/components/
// message-item/message-reaction.recipe.ts `messageReaction` chip / count / addButton, and
// message-footer.recipe.ts `savedIndicator`). The chips under a message: one per emoji, the
// add-reaction chip after them, and the saved marker.

/** The chip's height (`h-6`); the add chip matches it. */
internal val RaftMessageReactionHeight = 24.dp

/** The emoji's box (`[&_[data-slot=message-reaction-glyph]>svg]:size-4`). */
val RaftMessageReactionGlyphSize = 16.dp

/** raft-ui `reactionCountTiming`: 180ms on `cubic-bezier(0.2, 0, 0.2, 1)`. */
private const val RaftMessageReactionCountBumpMillis = 180
private val RaftMessageReactionCountBumpEasing = CubicBezierEasing(0.2f, 0f, 0.2f, 1f)

/** One frame of raft-ui `reactionCountKeyframes`. */
internal data class RaftMessageReactionCountFrame(val alpha: Float, val translateYDp: Float, val scale: Float)

/**
 * raft-ui `reactionCountKeyframes` at [progress] (0..1, already eased): from 70% and 0.92
 * a pixel low, up to 1.16 a pixel high at 45%, back to rest. 1 is rest.
 */
internal fun raftMessageReactionCountFrame(progress: Float): RaftMessageReactionCountFrame {
    val p = progress.coerceIn(0f, 1f)
    fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    return if (p <= 0.45f) {
        val t = p / 0.45f
        RaftMessageReactionCountFrame(lerp(0.7f, 1f, t), lerp(1f, -1f, t), lerp(0.92f, 1.16f, t))
    } else {
        val t = (p - 0.45f) / 0.55f
        RaftMessageReactionCountFrame(1f, lerp(-1f, 0f, t), lerp(1.16f, 1f, t))
    }
}

/**
 * raft-ui `MessageReactionChip`: one emoji under a message and how many reacted with it.
 * 24dp tall, `rounded-chip` (6), 7dp each side, the 16dp [glyph] 4dp before the count in
 * Space Mono `mono-meta` bold with tabular figures (ink). At rest `fill-muted`; [mine] (you
 * reacted) the solid `attention-soft` pale pink; [failed] (your toggle did not go through)
 * the `warning-soft` wash. A press or a mouse hover lays `overlay-medium` over the fill.
 * When [count] changes the number bumps (180ms, raft-ui `reactionCountKeyframes`); a count
 * of 0 shows the emoji alone.
 *
 * With [onClick] the chip is a toggle (on while [mine]). Without it, it only draws, and the
 * host owns the gestures (a tap that toggles and a long press that names who reacted);
 * pass the host's [interactionSource] so a hover still shows.
 */
@Composable
fun RaftMessageReaction(
    count: Int,
    mine: Boolean,
    glyph: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    failed: Boolean = false,
    onClick: (() -> Unit)? = null,
    interactionSource: MutableInteractionSource? = null,
) {
    val colors = RaftTheme.colors
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val pressed = raftIsPressed(source)
    val hovered = raftIsHovered(source)
    val focused = raftIsFocused(source)
    val shape = RoundedCornerShape(RaftRadius.Chip)
    val fill =
        when {
            failed -> colors.warningSoft
            mine -> colors.attentionSoft
            else -> colors.fillMuted
        }
    Row(
        modifier =
            modifier
                .raftFocusRing(focused, RaftRadius.Chip, colors)
                .height(RaftMessageReactionHeight)
                .background(fill, shape)
                .raftPressOverlay(pressed || hovered, colors.fillMedium, RaftRadius.Chip)
                // With no onClick the host owns the gestures; the hover is still the chip's.
                .raftPointerHover(source)
                .then(
                    if (onClick != null) {
                        Modifier.toggleable(
                            value = mine,
                            interactionSource = source,
                            indication = null,
                            role = Role.Button,
                            onValueChange = { onClick() },
                        )
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(RaftMessageReactionGlyphSize), contentAlignment = Alignment.Center) { glyph() }
        if (count > 0) RaftMessageReactionCount(count)
    }
}

@Composable
private fun RaftMessageReactionCount(count: Int) {
    val bump = remember { Animatable(1f) }
    val shown = remember { mutableIntStateOf(count) }
    LaunchedEffect(count) {
        if (shown.intValue == count) return@LaunchedEffect
        shown.intValue = count
        bump.snapTo(0f)
        bump.animateTo(1f, tween(RaftMessageReactionCountBumpMillis, easing = RaftMessageReactionCountBumpEasing))
    }
    RaftText(
        text = count.toString(),
        modifier =
            Modifier.graphicsLayer {
                val frame = raftMessageReactionCountFrame(bump.value)
                alpha = frame.alpha
                scaleX = frame.scale
                scaleY = frame.scale
                translationY = frame.translateYDp * density
            },
        style = RaftTheme.typography.monoMeta.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
        color = RaftTheme.colors.foreground,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
    )
}

/**
 * raft-ui `MessageReactionAddButton`: the chip after the reactions that opens the picker.
 * 24dp tall, 6dp each side of a 16dp `plus` in `foreground-muted`, `fill-muted`,
 * `rounded-chip`; a press or a mouse hover lays `overlay-medium` over it. [label] names it
 * ("Add Reaction"). With [onClick] = null it only draws and the host owns the tap (a
 * picker anchored to it); pass the host's [interactionSource] for the hover.
 */
@Composable
fun RaftMessageReactionAdd(
    label: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    interactionSource: MutableInteractionSource? = null,
) {
    val colors = RaftTheme.colors
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val pressed = raftIsPressed(source)
    val hovered = raftIsHovered(source)
    val focused = raftIsFocused(source)
    val shape = RoundedCornerShape(RaftRadius.Chip)
    Box(
        modifier =
            modifier
                .raftFocusRing(focused, RaftRadius.Chip, colors)
                .height(RaftMessageReactionHeight)
                .background(colors.fillMuted, shape)
                .raftPressOverlay(pressed || hovered, colors.fillMedium, RaftRadius.Chip)
                // With no onClick the host owns the gestures; the hover is still the chip's.
                .raftPointerHover(source)
                .then(
                    if (onClick != null) {
                        Modifier.clickable(source, indication = null, role = Role.Button, onClick = onClick)
                    } else {
                        Modifier
                    },
                )
                .semantics { contentDescription = label }
                .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        RaftGlyphIcon(RaftGlyph.Plus, size = 16.dp, color = colors.foregroundMuted)
    }
}

/**
 * raft-ui `MessageItemSavedIndicator`: the footer's mark of a message you saved. 24dp (the
 * reactions' height), `rounded-chip`, 5.5dp before and 6.5dp after, `fill-muted`, a filled
 * 14dp bookmark in ink 4dp before [label] ("Saved") in `footnote` bold `foreground-muted`
 * (raft-design #923, 0.56: it was 22dp in caption semibold and read as a fainter grey). With
 * [onClick] (unsave, or open Saved) a press or hover lays `overlay-medium` over it.
 */
@Composable
fun RaftMessageSavedIndicator(
    label: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    interactionSource: MutableInteractionSource? = null,
) {
    val colors = RaftTheme.colors
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val pressed = raftIsPressed(source)
    val hovered = raftIsHovered(source)
    val focused = raftIsFocused(source)
    val shape = RoundedCornerShape(RaftRadius.Chip)
    Row(
        modifier =
            modifier
                .raftFocusRing(focused, RaftRadius.Chip, colors)
                .height(RaftMessageReactionHeight)
                .background(colors.fillMuted, shape)
                .raftPressOverlay(onClick != null && (pressed || hovered), colors.fillMedium, RaftRadius.Chip)
                .raftPointerHover(source)
                .then(
                    if (onClick != null) {
                        Modifier.clickable(source, indication = null, role = Role.Button, onClick = onClick)
                    } else {
                        Modifier
                    },
                )
                .padding(start = 5.5.dp, end = 6.5.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RaftFilledGlyph(RaftGlyph.Bookmark, colors.foreground)
        RaftText(
            text = label,
            style = RaftTheme.typography.footnote.copy(fontWeight = FontWeight.Bold),
            color = colors.foregroundMuted,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A [RaftGlyph] at 14dp, filled and stroked in [ink] (Lucide with `fill="currentColor"`). */
@Composable
private fun RaftFilledGlyph(glyph: RaftGlyph, ink: androidx.compose.ui.graphics.Color) {
    Canvas(Modifier.size(14.dp)) {
        val unit = size.minDimension / 24f
        scale(unit, unit, pivot = Offset.Zero) {
            val stroke = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            glyph.parsed.forEach {
                drawPath(it, ink, style = Fill)
                drawPath(it, ink, style = stroke)
            }
        }
    }
}
