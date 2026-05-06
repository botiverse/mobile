package ai.slock.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.slock.android.ui.theme.SlockColor
import ai.slock.android.ui.theme.SlockRadius
import ai.slock.android.ui.theme.SlockShadow
import ai.slock.android.ui.theme.SlockSpacing
import ai.slock.android.ui.theme.SlockStroke
import ai.slock.android.ui.theme.SlockType
import ai.slock.android.ui.theme.slockHardShadow

enum class SlockButtonVariant {
    Pink,
    Yellow,
    Cyan,
    Black,
    Orange,
    Red,
    Outline
}

@Composable
fun SlockButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: SlockButtonVariant = SlockButtonVariant.Pink,
    enabled: Boolean = true,
    loading: Boolean = false,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val active = enabled && !loading
    val shadow = if (pressed && active) SlockShadow.Small else SlockShadow.Default
    val shadowPadding = SlockShadow.Default
    val background = variant.backgroundColor
    val contentColor = variant.contentColor

    Box(
        modifier = modifier
            .padding(end = shadowPadding.offsetX, bottom = shadowPadding.offsetY)
            .slockHardShadow(shadow)
            .background(background, RoundedCornerShape(SlockRadius.Xs))
            .border(SlockStroke.Default, SlockColor.BorderBlack, RoundedCornerShape(SlockRadius.Xs))
            .clickable(
                enabled = active,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick
            )
            .alpha(if (enabled) 1f else 0.45f)
            .defaultMinSize(minHeight = 48.dp)
            .padding(horizontal = SlockSpacing.Md, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (leadingContent != null) {
                Box(
                    modifier = Modifier.padding(end = SlockSpacing.Sm),
                    contentAlignment = Alignment.Center
                ) {
                    leadingContent()
                }
            }
            Text(
                text = if (loading && !text.endsWith("...")) "$text..." else text,
                color = contentColor,
                style = SlockType.Button.copy(fontWeight = FontWeight.Bold),
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (trailingContent != null) {
                Box(
                    modifier = Modifier.padding(start = SlockSpacing.Sm),
                    contentAlignment = Alignment.Center
                ) {
                    trailingContent()
                }
            }
        }
    }
}

private val SlockButtonVariant.backgroundColor: Color
    get() = when (this) {
        SlockButtonVariant.Pink -> SlockColor.AccentPink
        SlockButtonVariant.Yellow -> SlockColor.BrandYellow
        SlockButtonVariant.Cyan -> SlockColor.AccentCyan
        SlockButtonVariant.Black -> SlockColor.TextBlack
        SlockButtonVariant.Orange -> SlockColor.AccentOrange
        SlockButtonVariant.Red -> SlockColor.AccentRed
        SlockButtonVariant.Outline -> SlockColor.SurfaceWhite
    }

private val SlockButtonVariant.contentColor: Color
    get() = when (this) {
        SlockButtonVariant.Black -> SlockColor.SurfaceWhite
        else -> SlockColor.TextBlack
    }
