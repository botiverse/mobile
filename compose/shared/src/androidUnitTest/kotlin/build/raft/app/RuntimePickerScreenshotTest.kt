package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.BundledRuntimeCatalog
import build.raft.app.resources.Res
import build.raft.app.resources.agent_create_label_runtime
import build.raft.app.resources.runtime_picker_show_all_supported
import build.raft.app.resources.runtime_picker_show_installed_only
import build.raft.app.resources.stringResource
import build.raft.designsystem.components.RaftCombobox
import build.raft.designsystem.components.RaftChoicePickerSheetSurface
import build.raft.designsystem.components.RaftComboboxListAction
import build.raft.designsystem.components.RaftField
import build.raft.designsystem.components.RaftFieldLabel
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The create dialog's runtime picker (web parity, botiverse/slock #11783 / #11794 / #11822 /
 * #11771 / #15551 / #15599), light and dark, from the bundled catalog on a computer that runs
 * Claude Code, Codex, Cursor, Pi and Qwen Code:
 *  - `agents.runtime-picker.sheet`: the open list, Ready on this computer (Claude Code and Codex
 *    with the outline Recommended badge, Cursor and Qwen Code with the muted Limited badge, Pi
 *    with none; no Experimental anywhere) and Needs a Raft Computer update, then Show All
 *    Supported as the last row;
 *  - `agents.runtime-picker.sheet.all`: after Show All Supported, Not installed on this computer
 *    joins and the last row reads Show Installed Only;
 *  - `agents.runtime-picker.selected-hint`: the closed field (no badge) with the chosen
 *    runtime's capability hint under it: Qwen Code, Cursor, Pi, and Claude Code (no hint).
 * Verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h760dp-xxhdpi")
class RuntimePickerScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test fun sheetLight() = capture("agents.runtime-picker.sheet", RaftAppAppearance.Light, showAll = false)

    @Test fun sheetDark() = capture("agents.runtime-picker.sheet.dark", RaftAppAppearance.Dark, showAll = false)

    private fun capture(name: String, appearance: RaftAppAppearance, showAll: Boolean) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = ReportDir, activities = activities) {
            RaftDesignSystemTheme { RuntimePickerFixture(showAll) }
        }

    private companion object {
        const val ReportDir = "build/reports/runtime-picker-screenshots"
    }
}

@Composable
private fun RuntimePickerFixture(showAll: Boolean) {
    val rows =
        runtimePickerRows(
            catalog = BundledRuntimeCatalog,
            machineRuntimeIds = FixtureMachineRuntimes,
            selectedRuntimeId = "qwen",
            showAll = showAll
        )
    val options = runtimePickerOptions(rows.rows, rememberRuntimePickerCopy(), mapOf("claude" to "Claude Max · 5h 28% used"))
    val title = stringResource(Res.string.agent_create_label_runtime)
    val action =
        RaftComboboxListAction(
            stringResource(if (showAll) Res.string.runtime_picker_show_installed_only else Res.string.runtime_picker_show_all_supported)
        ) {}
    Column(
        Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        RaftField(label = title) {
            RaftCombobox(value = "qwen", options = options, onValueChange = {}, title = title, emptyText = { "" }, listAction = action)
        }
        RaftChoicePickerSheetSurface(
            value = "qwen",
            options = options,
            onPick = {},
            title = title,
            emptyText = { "" },
            searchable = options.size > 10,
            listAction = action,
            modifier = Modifier.height(600.dp)
        )
    }
}

private val FixtureMachineRuntimes = listOf("claude", "codex", "cursor", "pi", "qwen")

/** Each chosen runtime as the create dialog's field shows it: the closed select, then its hint. */
@Composable
private fun RuntimePickerSelectedFixture() {
    val title = stringResource(Res.string.agent_create_label_runtime)
    val copy = rememberRuntimePickerCopy()
    Column(
        Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        listOf("qwen", "cursor", "pi", "claude").forEach { selected ->
            val rows = runtimePickerRows(BundledRuntimeCatalog, FixtureMachineRuntimes, selectedRuntimeId = selected, showAll = false)
            val options = runtimePickerOptions(rows.rows, copy)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                RaftFieldLabel(text = title)
                RaftCombobox(value = selected, options = options, onValueChange = {}, title = title, emptyText = { "" }, label = title)
                RuntimeCapabilityHintText(rows.selectedCapabilityHint(selected))
            }
        }
    }
}
