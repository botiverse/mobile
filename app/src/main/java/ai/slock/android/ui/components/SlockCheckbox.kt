package ai.slock.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ai.slock.android.ui.theme.SlockColor
import ai.slock.android.ui.theme.SlockRadius
import ai.slock.android.ui.theme.SlockSpacing
import ai.slock.android.ui.theme.SlockStroke
import ai.slock.android.ui.theme.SlockType

@Composable
fun SlockCheckbox(
    checked: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 20.dp
) {
    val backgroundColor = when {
        !enabled && checked -> SlockColor.TextMutedGray
        !enabled -> SlockColor.EmptyStateGray
        checked -> SlockColor.TextBlack
        else -> SlockColor.SurfaceWhite
    }
    val borderColor = if (enabled) SlockColor.BorderBlack else SlockColor.TextMutedGray
    Box(
        modifier = modifier
            .size(size)
            .background(backgroundColor)
            .border(SlockStroke.Default, borderColor),
        contentAlignment = Alignment.Center
    ) {
        if (checked) {
            Icon(
                imageVector = SlockIcons.Check,
                contentDescription = null,
                tint = if (enabled) SlockColor.SurfaceWhite else Color.White.copy(alpha = 0.75f),
                modifier = Modifier.size(size * 0.68f)
            )
        }
    }
}

@Composable
fun SlockLabeledCheckbox(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 20.dp
) {
    Row(
        modifier = modifier
            .slockSelectionClickable(
                selected = checked,
                enabled = enabled,
                role = Role.Checkbox,
                onClick = { onCheckedChange(!checked) }
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        SlockCheckbox(
            checked = checked,
            enabled = enabled,
            size = size
        )
        Text(
            text = label,
            style = SlockType.Button,
            color = if (enabled) SlockColor.TextMutedGray else SlockColor.TextMutedGray.copy(alpha = 0.6f)
        )
    }
}

@Composable
fun SlockToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val trackColor = when {
        !enabled && checked -> SlockColor.TextMutedGray
        !enabled -> SlockColor.EmptyStateGray
        checked -> SlockColor.TextBlack
        else -> SlockColor.SurfaceWhite
    }
    val knobColor = if (checked) SlockColor.SurfaceWhite else SlockColor.TextBlack
    Row(
        modifier = modifier
            .size(width = 42.dp, height = 22.dp)
            .background(trackColor, androidx.compose.foundation.shape.RoundedCornerShape(SlockRadius.Xs))
            .border(
                SlockStroke.Default,
                if (enabled) SlockColor.BorderBlack else SlockColor.TextMutedGray,
                androidx.compose.foundation.shape.RoundedCornerShape(SlockRadius.Xs)
            )
            .slockSelectionClickable(
                selected = checked,
                enabled = enabled,
                role = Role.Switch,
                onClick = { onCheckedChange(!checked) }
            )
            .padding(horizontal = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (checked) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .background(knobColor)
                .border(SlockStroke.Thin, if (checked) SlockColor.SurfaceWhite else SlockColor.BorderBlack)
        )
    }
}

@Composable
fun SlockRadio(
    selected: Boolean,
    onSelectedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 20.dp
) {
    Box(
        modifier = modifier
            .size(size)
            .background(if (selected) SlockColor.TextBlack else SlockColor.SurfaceWhite)
            .border(SlockStroke.Default, if (enabled) SlockColor.BorderBlack else SlockColor.TextMutedGray)
            .slockSelectionClickable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = { onSelectedChange(true) }
            ),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Box(
                modifier = Modifier
                    .size(size * 0.38f)
                    .background(SlockColor.SurfaceWhite)
            )
        }
    }
}

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
            .slockSelectionClickable(
                selected = selected,
                enabled = enabled,
                role = Role.Tab,
                onClick = onSelected
            )
            .padding(horizontal = SlockSpacing.Md, vertical = SlockSpacing.Sm)
    )
}

private fun Modifier.slockSelectionClickable(
    selected: Boolean,
    enabled: Boolean,
    role: Role,
    onClick: () -> Unit
): Modifier =
    semantics {
        this.selected = selected
    }.clickable(
        enabled = enabled,
        role = role,
        onClick = onClick
    )
