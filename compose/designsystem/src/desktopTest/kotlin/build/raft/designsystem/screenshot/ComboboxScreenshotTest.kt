package build.raft.designsystem.screenshot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.LocalRaftFocusedPreview
import build.raft.designsystem.components.LocalRaftPressedPreview
import build.raft.designsystem.components.RaftAvatar
import build.raft.designsystem.components.RaftAvatarSize
import build.raft.designsystem.components.RaftAvatarType
import build.raft.designsystem.components.raftAvatarInitials
import build.raft.designsystem.components.LocalRaftMenuFieldType
import build.raft.designsystem.components.RaftComboboxChipsTrigger
import build.raft.designsystem.components.RaftComboboxMenuRows
import build.raft.designsystem.components.RaftComboboxOption
import build.raft.designsystem.components.RaftComboboxSheetSurface
import build.raft.designsystem.components.RaftField
import build.raft.designsystem.components.RaftGlyph
import build.raft.designsystem.components.RaftGlyphIcon
import build.raft.designsystem.components.RaftMenuSurface
import build.raft.designsystem.components.RaftSelectTrigger
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test

/**
 * Combobox (raft-ui 0.10.3 Combobox / ComboboxSelect, the phone's bottom sheet): one
 * golden per fixture x appearance (conventions in [ComponentScreenshotTest]). The sheet is
 * captured as its surface (no window, no scrim), as `overlays_*` captures the sheet.
 */
class ComboboxScreenshotTest {
    /** The sheet fills its window's 82%; without a window it takes this height. */
    private val sheetHeight = 560.dp

    private val appearances = listOf(RaftAppearance.Light, RaftAppearance.Dark)

    private fun capture(name: String, maxWidthDp: Int = 1200, content: @Composable () -> Unit) =
        appearances.forEach { appearance ->
            RaftScreenshot.capture("${name}_${appearance.name.lowercase()}", appearance, maxWidthDp = maxWidthDp, content = content)
        }

    private val models =
        listOf(
            RaftComboboxOption("opus", "Claude Opus", group = "Anthropic", description = "Best for long, careful work"),
            RaftComboboxOption("sonnet", "Claude Sonnet", group = "Anthropic"),
            RaftComboboxOption("codex", "GPT-5 Codex", group = "OpenAI"),
            RaftComboboxOption("o3", "o3", group = "OpenAI", enabled = false),
            RaftComboboxOption("gemini", "Gemini 2.5 Pro", group = "Google"),
        )

    private val people =
        listOf(
            RaftComboboxOption("wug", "Wug", keywords = listOf("agent")),
            RaftComboboxOption("bugen", "Bugen", keywords = listOf("agent")),
            RaftComboboxOption("cindy", "Cindy", description = "cindy@slock.ai"),
            RaftComboboxOption("clara", "Clara"),
        )

    /** The field: placeholder, value, chips (wrapping), pressed, focused, invalid, disabled. */
    @Test
    fun trigger() =
        capture("combobox_trigger") {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.width(310.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Caption("Single: placeholder, value")
                    RaftField(label = "Model") { RaftSelectTrigger(null, {}, placeholder = "Choose a model") }
                    RaftField(label = "Model") { RaftSelectTrigger("Claude Opus", {}) }
                    Caption("Invalid, disabled")
                    RaftField(label = "Model", error = "Choose a model") { RaftSelectTrigger(null, {}, placeholder = "Choose a model") }
                    RaftField(label = "Model", enabled = false) { RaftSelectTrigger("Claude Opus", {}) }
                }
                Column(Modifier.width(310.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Caption("Multiple: placeholder, chips, wrapping")
                    RaftField(label = "Reviewers") { RaftComboboxChipsTrigger(emptyList(), {}, placeholder = "Add people") }
                    RaftField(label = "Reviewers") { RaftComboboxChipsTrigger(listOf("Wug", "Bugen"), {}, onRemove = {}) }
                    RaftField(label = "Reviewers") {
                        RaftComboboxChipsTrigger(listOf("Wug", "Bugen", "Cindy", "Clara", "Ada Lovelace"), {}, onRemove = {})
                    }
                    Caption("Pressed, focused")
                    StateCell(pressed = true) { RaftComboboxChipsTrigger(listOf("Wug"), {}, onRemove = {}) }
                    StateCell(focused = true) { RaftComboboxChipsTrigger(listOf("Wug"), {}, onRemove = {}) }
                    Caption("Invalid, disabled")
                    RaftField(label = "Reviewers", error = "Add a reviewer") { RaftComboboxChipsTrigger(emptyList(), {}, placeholder = "Add people") }
                    RaftField(label = "Reviewers", enabled = false) { RaftComboboxChipsTrigger(listOf("Wug", "Bugen"), {}, onRemove = {}) }
                }
            }
        }

    /**
     * The open sheet: groups with a description, a disabled row and the chosen value (and
     * Clear); a search narrowing a multiple pick with avatars and Done; nothing matching;
     * a create combobox offering the typed text first.
     */
    @Test
    fun sheet() =
        // Four 358dp sheets side by side.
        capture("combobox_sheet", maxWidthDp = 1600) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.width(358.dp).padding(bottom = 24.dp)) {
                    Caption("One value: groups, chosen, Clear", Modifier.padding(bottom = 12.dp))
                    RaftComboboxSheetSurface(
                        title = "Model",
                        query = "",
                        onQueryChange = {},
                        options = models,
                        isSelected = { it == "sonnet" },
                        onPick = {},
                        emptyText = { "No models match “$it”" },
                        searchPlaceholder = "Search models",
                        onClear = {},
                        modifier = Modifier.height(sheetHeight),
                    )
                }
                Column(Modifier.width(358.dp).padding(bottom = 24.dp)) {
                    Caption("Multiple: searched, avatars, Done", Modifier.padding(bottom = 12.dp))
                    RaftComboboxSheetSurface(
                        title = "Reviewers",
                        query = "c",
                        onQueryChange = {},
                        options = people,
                        isSelected = { it == "bugen" || it == "cindy" },
                        onPick = {},
                        emptyText = { "No one matches “$it”" },
                        searchPlaceholder = "Search people",
                        onClear = {},
                        onDone = {},
                        optionLeading = { option ->
                            RaftAvatar(RaftAvatarType.Human, RaftAvatarSize.Xs, initials = raftAvatarInitials(option.label, RaftAvatarSize.Xs))
                        },
                        modifier = Modifier.height(sheetHeight),
                    )
                }
                Column(Modifier.width(358.dp).padding(bottom = 24.dp)) {
                    Caption("No match", Modifier.padding(bottom = 12.dp))
                    RaftComboboxSheetSurface(
                        title = "Channel",
                        query = "zz",
                        onQueryChange = {},
                        options = listOf(RaftComboboxOption("design", "#design"), RaftComboboxOption("dev", "#dev")),
                        isSelected = { false },
                        onPick = {},
                        emptyText = { "No channels match “$it”" },
                        searchPlaceholder = "Search channels",
                        optionLeading = { RaftGlyphIcon(RaftGlyph.AtSign) },
                        modifier = Modifier.height(sheetHeight),
                    )
                }
                Column(Modifier.width(358.dp).padding(bottom = 24.dp)) {
                    Caption("Create: the typed value first", Modifier.padding(bottom = 12.dp))
                    RaftComboboxSheetSurface(
                        title = "Model",
                        query = "claude",
                        onQueryChange = {},
                        options = models,
                        isSelected = { false },
                        onPick = {},
                        emptyText = { "No models match “$it”" },
                        searchPlaceholder = "Search or type a model",
                        onCreate = {},
                        modifier = Modifier.height(sheetHeight),
                    )
                }
            }
        }

    /**
     * A few values (at most RaftComboboxSheetThreshold): the Select's anchored list, no
     * search. Captured as the menu surface at the trigger's width, as `menu_*` does; in a
     * field its options take the field type.
     */
    @Test
    fun anchoredList() =
        capture("combobox_list") {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.width(310.dp).padding(bottom = 24.dp)) {
                    Caption("In a field: groups, chosen, disabled", Modifier.padding(bottom = 8.dp))
                    RaftMenuSurface(width = 310.dp) {
                        CompositionLocalProvider(LocalRaftMenuFieldType provides true) {
                            RaftComboboxMenuRows(models, isSelected = { it == "sonnet" }, onPick = {}, emptyText = { "No models" })
                        }
                    }
                }
                Column(Modifier.width(310.dp).padding(bottom = 24.dp)) {
                    Caption("Outside a field: descriptions, avatars", Modifier.padding(bottom = 8.dp))
                    RaftMenuSurface(width = 310.dp) {
                        RaftComboboxMenuRows(
                            people,
                            isSelected = { it == "cindy" },
                            onPick = {},
                            emptyText = { "No one" },
                            optionLeading = { option -> RaftAvatar(RaftAvatarType.Human, RaftAvatarSize.Xs, initials = raftAvatarInitials(option.label, RaftAvatarSize.Xs)) },
                        )
                    }
                }
            }
        }

    @Composable
    private fun StateCell(pressed: Boolean = false, focused: Boolean = false, content: @Composable () -> Unit) {
        CompositionLocalProvider(
            LocalRaftPressedPreview provides pressed,
            LocalRaftFocusedPreview provides focused,
            content = content,
        )
    }

    @Composable
    private fun Caption(text: String, modifier: Modifier = Modifier) {
        RaftText(text = text, modifier = modifier, style = RaftTheme.typography.monoMeta, color = RaftTheme.colors.foregroundHint)
    }
}
