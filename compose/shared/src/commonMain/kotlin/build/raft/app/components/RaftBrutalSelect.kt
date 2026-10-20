package build.raft.app.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftDropdownMenu
import build.raft.designsystem.components.RaftMenuItem
import build.raft.designsystem.components.RaftMenuLabel
import build.raft.designsystem.components.RaftSelectTrigger

internal data class RaftBrutalSelectOption(
    val id: String,
    val label: String,
    val enabled: Boolean = true,
    /** A group heading inside the menu: never selectable, drawn muted in regular weight. */
    val header: Boolean = false
)

internal fun raftSelectMenuWidth(
    triggerWidthPx: Float,
    density: Float,
    fallbackWidth: Dp = 220.dp
): Dp =
    if (triggerWidthPx > 0f && density > 0f) {
        (triggerWidthPx / density).dp
    } else {
        fallbackWidth
    }

/**
 * Legacy-named select; renders the design-system Select: a [RaftSelectTrigger] (44dp field,
 * value or placeholder, chevron) opening a [RaftDropdownMenu] as wide as the trigger (at
 * least 220dp) with a [RaftMenuItem] per option (the current one bold with a check) and a
 * [RaftMenuLabel] per group heading. [selectedLabel] overrides the trigger text (a value
 * the options do not list). [rowHeight], [triggerHeight] and [maxMenuHeight] only shaped
 * the retired register and are ignored: the menu scrolls inside the design-system cap.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
internal fun RaftBrutalSelectField(
    options: List<RaftBrutalSelectOption>,
    selectedId: String,
    onSelect: (RaftBrutalSelectOption) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Select...",
    selectedLabel: String = "",
    enabled: Boolean = true,
    rowHeight: Dp = 36.dp,
    triggerHeight: Dp = 40.dp,
    maxMenuHeight: Dp = 220.dp
) {
    val selectedOption = options.firstOrNull { it.id == selectedId }
    val value = selectedLabel.ifBlank { selectedOption?.label.orEmpty() }.ifBlank { null }
    var expanded by remember { mutableStateOf(false) }
    var triggerWidthPx by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val trigger = remember { FocusRequester() }
    Box(modifier = modifier.fillMaxWidth()) {
        RaftSelectTrigger(
            value = value,
            onClick = { expanded = !expanded },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .focusRequester(trigger)
                    .onSizeChanged { triggerWidthPx = it.width },
            placeholder = placeholder,
            enabled = enabled,
            expanded = expanded,
            onCollapse = { expanded = false }
        )
        RaftDropdownMenu(
            expanded = expanded && enabled,
            onDismissRequest = { expanded = false },
            width = raftSelectMenuWidth(triggerWidthPx.toFloat(), density.density).coerceAtLeast(220.dp),
            offset = 4.dp,
            returnFocusTo = trigger
        ) {
            options.forEach { option ->
                if (option.header) {
                    RaftMenuLabel(option.label)
                } else {
                    RaftMenuItem(
                        text = option.label,
                        onClick = {
                            expanded = false
                            onSelect(option)
                        },
                        enabled = option.enabled,
                        checked = option.id == selectedId
                    )
                }
            }
        }
    }
}
