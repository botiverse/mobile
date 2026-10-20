package build.raft.app.cmp

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import kotlinx.coroutines.launch

/**
 * The app-wide default press feedback for a bare `clickable`: the raft-ui `fill-pressed` wash
 * (8% ink) over the pressed element while a finger is down. Without a provided
 * `LocalIndication`, Compose Multiplatform falls back to its debug indication, a 30% black
 * rectangle, which is what bare clickables showed after the switch from Kuikly. Components
 * with a designed press state (stickers, buttons, rows) pass `indication = null` and draw
 * their own overlay.
 */
class RaftPressIndication(private val wash: Color) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        RaftPressIndicationNode(interactionSource, wash)

    override fun equals(other: Any?): Boolean = other is RaftPressIndication && other.wash == wash

    override fun hashCode(): Int = wash.hashCode()
}

private class RaftPressIndicationNode(
    private val interactionSource: InteractionSource,
    private val wash: Color,
) : Modifier.Node(), DrawModifierNode {
    private val presses = mutableSetOf<PressInteraction.Press>()

    override fun onAttach() {
        coroutineScope.launch {
            interactionSource.interactions.collect { interaction ->
                val changed =
                    when (interaction) {
                        is PressInteraction.Press -> presses.add(interaction)
                        is PressInteraction.Release -> presses.remove(interaction.press)
                        is PressInteraction.Cancel -> presses.remove(interaction.press)
                        else -> false
                    }
                if (changed) invalidateDraw()
            }
        }
    }

    override fun onDetach() {
        presses.clear()
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        if (presses.isNotEmpty() && wash.alpha > 0f) drawRect(wash)
    }
}
