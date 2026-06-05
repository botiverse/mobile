package ai.slock.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.slock.android.ui.theme.SlockColor
import ai.slock.android.ui.theme.SlockSpacing
import ai.slock.android.ui.theme.SlockType

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
    val active = enabled && !loading
    val background = variant.backgroundColor
    val contentColor = variant.contentColor

    SlockKeySurface(
        onClick = onClick,
        modifier = modifier,
        surfaceModifier = Modifier.defaultMinSize(minHeight = 48.dp),
        enabled = active,
        dimmed = !enabled,
        backgroundColor = background,
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier.padding(horizontal = SlockSpacing.Md, vertical = 12.dp),
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
