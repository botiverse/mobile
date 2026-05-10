package ai.slock.android.ui.components

import ai.slock.android.ui.theme.SlockColor
import ai.slock.android.ui.theme.SlockHardShadow
import ai.slock.android.ui.theme.SlockRadius
import ai.slock.android.ui.theme.SlockShadow
import ai.slock.android.ui.theme.SlockStroke
import ai.slock.android.ui.theme.slockHardShadow
import ai.slock.android.ui.theme.slockKeyPressOffset
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role

@Composable
fun SlockKeySurface(
        onClick: () -> Unit,
        modifier: Modifier = Modifier,
        surfaceModifier: Modifier = Modifier,
        enabled: Boolean = true,
        dimmed: Boolean = !enabled,
        backgroundColor: Color = SlockColor.SurfaceWhite,
        pressedBackgroundColor: Color = backgroundColor,
        restingShadow: SlockHardShadow = SlockShadow.Default,
        pressedShadow: SlockHardShadow = SlockShadow.Small,
        shape: Shape = RoundedCornerShape(SlockRadius.Xs),
        contentAlignment: Alignment = Alignment.Center,
        interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
        content: @Composable BoxScope.() -> Unit
) {
    val pressed by interactionSource.collectIsPressedAsState()
    val isPressed = pressed && enabled
    val shadow = if (isPressed) pressedShadow else restingShadow
        val currentBackgroundColor = if (isPressed) pressedBackgroundColor else backgroundColor
    Box(
            modifier =
                    modifier.padding(end = restingShadow.offsetX, bottom = restingShadow.offsetY)
                            .slockKeyPressOffset(
                                    isPressed = isPressed,
                                    restingShadow = restingShadow,
                                    pressedShadow = pressedShadow
                            )
                            .then(surfaceModifier)
                            .slockHardShadow(shadow)
                            .background(currentBackgroundColor, shape)
                            .border(SlockStroke.Default, SlockColor.BorderBlack, shape)
                            .clickable(
                                    enabled = enabled,
                                    interactionSource = interactionSource,
                                    indication = null,
                                    role = Role.Button,
                                    onClick = onClick
                            )
                            .alpha(if (dimmed) 0.45f else 1f),
            contentAlignment = contentAlignment,
            content = content
    )
}
