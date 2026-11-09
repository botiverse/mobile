package build.raft.designsystem.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.debugInspectorInfo
import androidx.compose.ui.semantics.Role

/**
 * A tappable surface with the design system's states, for app parts no component covers yet
 * (a disclosure row, a card that opens, a chip, a small text action). It is
 * [rememberRaftStateOverlay] + [raftStateOverlay] + [raftPointerHover] + `clickable` on one
 * interaction source, with no ambient indication:
 *
 * - Press lays the [surface]'s medium tier (`fill-medium` on paper, at once and for at least
 *   120ms) in [shape], behind the content and over any fill chained before it; a mouse or
 *   trackpad hover lays [tier]'s hover overlay ([RaftHoverTier.Subtle] for rows and readable
 *   cards, [RaftHoverTier.Medium] for buttons and chips, [RaftHoverTier.Highlight] for menu
 *   and select rows, [RaftHoverTier.None] for no hover). [selected] and [enabled] = false
 *   follow [rememberRaftStateOverlay].
 * - Screen readers get [role] (a button by default; `null` leaves the role to a `semantics`
 *   block of the caller) and [onClickLabel].
 *
 * Chain it after the fill (`background`, a control surface) and before the padding, where a
 * bare `clickable` would go: it takes no layout room and changes nothing at rest. Its touch
 * target is the element's own box (no widening: use [RaftIconButton] for small icon actions
 * that need the 44dp target).
 *
 * Not for gestures that are not a tap on a control: scrims, tap sinks that keep taps off what
 * is under a popup, drags, message rows (their own tap, long press and selection) or hidden
 * shortcuts that show no press (a title's scroll to top). Pass [interactionSource] when the
 * caller reads the same presses (a pressed ink).
 */
fun Modifier.raftClickable(
    onClick: () -> Unit,
    enabled: Boolean = true,
    role: Role? = Role.Button,
    onClickLabel: String? = null,
    shape: Shape = RectangleShape,
    tier: RaftHoverTier = RaftHoverTier.Medium,
    surface: RaftPressSurface = RaftPressSurface.Paper,
    selected: Boolean = false,
    interactionSource: MutableInteractionSource? = null,
): Modifier =
    composed(
        inspectorInfo =
            debugInspectorInfo {
                name = "raftClickable"
                properties["enabled"] = enabled
                properties["role"] = role
                properties["onClickLabel"] = onClickLabel
                properties["shape"] = shape
                properties["tier"] = tier
                properties["onClick"] = onClick
            },
    ) {
        val ownSource = remember { MutableInteractionSource() }
        val source = interactionSource ?: ownSource
        val overlay = rememberRaftStateOverlay(source, tier, surface = surface, enabled = enabled, selected = selected)
        Modifier
            .raftStateOverlay(overlay, shape)
            .raftPointerHover(source, enabled = enabled)
            .clickable(
                interactionSource = source,
                indication = null,
                enabled = enabled,
                onClickLabel = onClickLabel,
                role = role,
                onClick = onClick,
            )
    }
