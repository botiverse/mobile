package build.raft.designsystem.screenshot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftChoicePicker
import build.raft.designsystem.components.RaftChoicePickerSheetSurface
import build.raft.designsystem.components.RaftComboboxListAction
import build.raft.designsystem.components.RaftComboboxOption
import build.raft.designsystem.components.RaftField
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test

/**
 * [RaftChoicePicker] by option count (conventions in [ComponentScreenshotTest]): up to 5
 * options are radios with their descriptions; 6 to 10 a Select whose sheet lists them without
 * search; more the searchable sheet (the `combobox_sheet` golden). Sheets are captured as their
 * surface (no window, no scrim).
 */
class ChoicePickerScreenshotTest {
    private val sheetHeight = 560.dp

    private fun capture(name: String, content: @Composable () -> Unit) =
        listOf(RaftAppearance.Light, RaftAppearance.Dark).forEach { appearance ->
            RaftScreenshot.capture("${name}_${appearance.name.lowercase()}", appearance, maxWidthDp = 1400, content = content)
        }

    private fun computers(count: Int): List<RaftComboboxOption<String>> =
        (1..count).map { index ->
            RaftComboboxOption(
                value = "c$index",
                label = "computer-$index",
                description =
                    when (index % 3) {
                        1 -> "Claude Max · 5h 28% used · Week 60% used"
                        2 -> "Claude Max · 5h used up, resets in 3 hours · Week 88% used"
                        else -> null
                    },
                enabled = index != 4,
            )
        } + RaftComboboxOption("hosted", "Hosted", description = "Raft runs the agent in the cloud")

    @Test
    fun choicePicker() =
        capture("choice_picker") {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.width(358.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Caption("5 options: radios with descriptions, one disabled")
                    RaftChoicePicker(value = "c2", options = computers(4), onValueChange = {}, title = "Target", emptyText = { "" })
                    Caption("6 options: the Select trigger")
                    RaftField(label = "Target") {
                        RaftChoicePicker(value = "c2", options = computers(5), onValueChange = {}, title = "Target", emptyText = { "" })
                    }
                }
                Column(Modifier.width(358.dp).padding(bottom = 24.dp)) {
                    Caption("6-10 options: the sheet lists them, no search", Modifier.padding(bottom = 12.dp))
                    RaftChoicePickerSheetSurface(
                        value = "c2",
                        options = computers(7),
                        onPick = {},
                        title = "Target",
                        emptyText = { "" },
                        searchable = false,
                        modifier = Modifier.height(sheetHeight),
                    )
                }
                Column(Modifier.width(358.dp).padding(bottom = 24.dp)) {
                    Caption("More than 10: the sheet with search", Modifier.padding(bottom = 12.dp))
                    RaftChoicePickerSheetSurface(
                        value = "c2",
                        options = computers(11),
                        onPick = {},
                        title = "Target",
                        emptyText = { "No computers match “$it”" },
                        searchable = true,
                        searchPlaceholder = "Search computers",
                        modifier = Modifier.height(sheetHeight),
                    )
                }
            }
        }

    private fun runtimes(): List<RaftComboboxOption<String>> =
        listOf(
            RaftComboboxOption("claude", "Claude Code", description = "Claude Max · 5h 28% used", group = "Ready on this computer"),
            RaftComboboxOption("codex", "Codex CLI", group = "Ready on this computer"),
            RaftComboboxOption("qwen", "Qwen Code", group = "Ready on this computer", badge = "Experimental"),
            RaftComboboxOption("qoder", "Qoder", group = "Not available on this server", enabled = false, badge = "Experimental"),
            RaftComboboxOption(
                "builtin",
                "Built-in Pi",
                group = "Needs a Raft Computer update",
                enabled = false,
                triggerLabel = "Built-in Pi (update Raft Computer)",
            ),
            RaftComboboxOption("pi", "Pi", group = "Not installed on this computer", enabled = false, triggerLabel = "Pi (not installed)"),
            RaftComboboxOption("opencode", "OpenCode", group = "Not installed on this computer", enabled = false),
        )

    /**
     * Grouped options (the runtime picker, web #11783 / #11794 / #11822): group headings among
     * radios too; in the sheet a separator between groups, never under a heading; the
     * Experimental badge after a label and in the trigger; the list action as the last row.
     */
    @Test
    fun choicePickerGrouped() =
        capture("choice_picker_grouped") {
            val action = RaftComboboxListAction("Show All Supported") {}
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.width(358.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Caption("Up to 5: grouped radios, a badge, the list action")
                    RaftChoicePicker(
                        value = "qwen",
                        options = runtimes().take(4),
                        onValueChange = {},
                        title = "Runtime",
                        emptyText = { "" },
                        listAction = action,
                    )
                    Caption("The trigger keeps the badge")
                    RaftField(label = "Runtime") {
                        RaftChoicePicker(value = "qwen", options = runtimes(), onValueChange = {}, title = "Runtime", emptyText = { "" })
                    }
                }
                Column(Modifier.width(358.dp).padding(bottom = 24.dp)) {
                    Caption("6-10: the sheet with groups and the list action", Modifier.padding(bottom = 12.dp))
                    RaftChoicePickerSheetSurface(
                        value = "qwen",
                        options = runtimes(),
                        onPick = {},
                        title = "Runtime",
                        emptyText = { "" },
                        searchable = false,
                        listAction = RaftComboboxListAction("Show Installed Only") {},
                        modifier = Modifier.height(sheetHeight),
                    )
                }
            }
        }

    @Composable
    private fun Caption(text: String, modifier: Modifier = Modifier) {
        RaftText(text = text, modifier = modifier, style = RaftTheme.typography.monoMeta, color = RaftTheme.colors.foregroundHint)
    }
}
