package ai.slock.android.ui.components

import ai.slock.android.ui.theme.SlockColor
import ai.slock.android.ui.theme.SlockShadow
import ai.slock.android.ui.theme.SlockSpacing
import ai.slock.android.ui.theme.SlockStroke
import ai.slock.android.ui.theme.SlockType
import ai.slock.android.ui.theme.slockHardShadow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class SlockMenuOverlayAction(
        val label: String,
        val icon: ImageVector? = null,
        val group: String = "default",
        val onClick: () -> Unit
)

object SlockMenuOverlayDefaults {
    val Width = 208.dp
    const val DividerTag = "slock-menu-overlay-divider"
}

@Composable
fun SlockMenuOverlay(
        actions: List<SlockMenuOverlayAction>,
        modifier: Modifier = Modifier,
        width: Dp = SlockMenuOverlayDefaults.Width
) {
    Column(
            modifier =
                    modifier
                            .padding(
                                    end = SlockShadow.Default.offsetX,
                                    bottom = SlockShadow.Default.offsetY
                            )
                            .slockHardShadow(SlockShadow.Default)
                            .width(width)
                            .background(SlockColor.SurfaceWhite)
                            .border(SlockStroke.Default, SlockColor.BorderBlack)
    ) {
        actions.forEachIndexed { index, action ->
            val previousGroup = actions.getOrNull(index - 1)?.group
            if (index > 0 && previousGroup != action.group) {
                SlockMenuOverlayDivider()
            }
            SlockMenuOverlayItem(action)
        }
    }
}

@Composable
private fun SlockMenuOverlayItem(action: SlockMenuOverlayAction) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val coroutineScope = rememberCoroutineScope()
    val background = if (pressed) SlockColor.AppBgLightBlue else SlockColor.SurfaceWhite
    Row(
            modifier =
                    Modifier.fillMaxWidth()
                            .defaultMinSize(minHeight = 46.dp)
                            .background(background)
                            .clickable(
                                    interactionSource = interactionSource,
                                    indication = null,
                                    role = Role.Button,
                                    onClick = {
                                        coroutineScope.launch {
                                            delay(90)
                                            action.onClick()
                                        }
                                    }
                            )
                            .padding(horizontal = SlockSpacing.Md, vertical = SlockSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
) {
        action.icon?.let { icon ->
            Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = SlockColor.TextBlack,
                    modifier = Modifier.size(20.dp)
            )
        }
        Text(
                text = action.label,
                style = SlockType.Body.copy(fontWeight = FontWeight.Normal),
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SlockMenuOverlayDivider() {
    Box(
            modifier =
                    Modifier.fillMaxWidth()
                            .height(SlockStroke.Default)
                            .background(SlockColor.BorderBlack)
                            .testTag(SlockMenuOverlayDefaults.DividerTag)
    )
}
