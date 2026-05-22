package ai.slock.android.ui.components

import ai.slock.android.ui.theme.SlockColor
import ai.slock.android.ui.theme.SlockSpacing
import ai.slock.android.ui.theme.SlockStroke
import ai.slock.android.ui.theme.SlockType
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight

@Composable
fun SlockSegmentOption(
    text: String,
    selected: Boolean,
    onSelected: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Text(
        text = text.uppercase(),
        style = SlockType.Button.copy(fontWeight = FontWeight.Bold),
        color = when {
            !enabled -> SlockColor.TextMutedGray
            selected -> SlockColor.SurfaceWhite
            else -> SlockColor.TextBlack
        },
        modifier = modifier
            .background(if (selected) SlockColor.TextBlack else SlockColor.SurfaceWhite)
            .border(SlockStroke.Default, SlockColor.BorderBlack)
            .semantics {
                this.selected = selected
            }
            .clickable(
                enabled = enabled,
                role = Role.Tab,
                onClick = onSelected
            )
            .padding(horizontal = SlockSpacing.Md, vertical = SlockSpacing.Sm)
    )
}
