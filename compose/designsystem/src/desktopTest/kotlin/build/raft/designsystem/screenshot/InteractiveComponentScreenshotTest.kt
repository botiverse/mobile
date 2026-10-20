package build.raft.designsystem.screenshot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.LocalRaftFocusedPreview
import build.raft.designsystem.components.LocalRaftPressedPreview
import build.raft.designsystem.components.RaftAvatar
import build.raft.designsystem.components.RaftAvatarSize
import build.raft.designsystem.components.RaftAvatarType
import build.raft.designsystem.components.RaftBanner
import build.raft.designsystem.components.RaftBannerSize
import build.raft.designsystem.components.RaftBannerStatus
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftCheckbox
import build.raft.designsystem.components.RaftCheckboxIndicator
import build.raft.designsystem.components.RaftCheckboxRow
import build.raft.designsystem.components.RaftCheckboxShape
import build.raft.designsystem.components.RaftDialogSize
import build.raft.designsystem.components.RaftDialogSurface
import build.raft.designsystem.components.RaftDropdownMenu
import build.raft.designsystem.components.RaftField
import build.raft.designsystem.components.RaftFieldItem
import build.raft.designsystem.components.RaftFieldLabelSize
import build.raft.designsystem.components.RaftFormSection
import build.raft.designsystem.components.RaftGlyph
import build.raft.designsystem.components.RaftGlyphIcon
import build.raft.designsystem.components.RaftListRow
import build.raft.designsystem.components.RaftListRowGroup
import build.raft.designsystem.components.RaftListRowVariant
import build.raft.designsystem.components.RaftMenuItem
import build.raft.designsystem.components.RaftMenuLabel
import build.raft.designsystem.components.RaftMenuSearchField
import build.raft.designsystem.components.RaftMenuSeparator
import build.raft.designsystem.components.RaftMenuSurface
import build.raft.designsystem.components.RaftPickerTriggerButton
import build.raft.designsystem.components.RaftRadio
import build.raft.designsystem.components.RaftRadioGroup
import build.raft.designsystem.components.RaftRadioRow
import build.raft.designsystem.components.RaftSegment
import build.raft.designsystem.components.RaftSegmentedControl
import build.raft.designsystem.components.RaftSelectTrigger
import build.raft.designsystem.components.RaftSheetSurface
import build.raft.designsystem.components.RaftStatusDot
import build.raft.designsystem.components.RaftStatusVariant
import build.raft.designsystem.components.RaftSwitch
import build.raft.designsystem.components.RaftTab
import build.raft.designsystem.components.RaftTabStrip
import build.raft.designsystem.components.RaftTabs
import build.raft.designsystem.components.RaftTabsVariant
import build.raft.designsystem.components.RaftTextField
import build.raft.designsystem.components.RaftTextarea
import build.raft.designsystem.components.RaftTextLink
import build.raft.designsystem.components.RaftTextLinkVariant
import build.raft.designsystem.components.appendRaftTextLink
import build.raft.designsystem.components.RaftToast
import build.raft.designsystem.components.RaftToastSurface
import build.raft.designsystem.components.RaftToggleGroup
import build.raft.designsystem.components.RaftToggleGroupItem
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test

/**
 * PR 3 interactive components: one golden per component x appearance holding its state
 * matrix (conventions in [ComponentScreenshotTest]). Pressed and focused cells are forced
 * through [LocalRaftPressedPreview] / [LocalRaftFocusedPreview]; a real tap's focus ring
 * on an input is checked by `RaftInteractionTest`. Popups and dialogs are captured as
 * their surfaces (no window, no scrim).
 *
 * Visual-testing case ids each fixture holds the content of:
 * - [textField]: `components.ui.form-field.states`, `library.raft-ui.field-input.states`,
 *   `components.ui.slug-input.states` (the `/` input group).
 * - [textarea]: `components.ui.textarea.states`, `library.raft-ui.textarea.states`.
 * - [field]: `components.ui.form-field.states`; [dialogForm]:
 *   `components.home.create-channel.dialog`, `components.members.create-agent.dialog`
 *   and `.dialog-error`.
 * - [checkbox]: `components.ui.checkbox.states`, `library.raft-ui.checkbox-status.states`,
 *   `components.ui.check-marker.states` (the indicator, square and circle).
 * - [radio]: `library.raft-ui.label-radio.states`.
 * - [switch]: `components.settings.appearance.page` (switch rows).
 * - [tabs]: `library.raft-ui.tabs.states`, `screens.members.agent-detail.workspace`.
 * - [segmentedControl]: `components.ui.segmented-control.states`,
 *   `library.raft-ui.segmented-control.states`, `library.raft-ui.wip-toggle-group.states`.
 * - [select]: `components.ui.select.states`, `library.raft-ui.select.states`.
 * - [menu]: `components.ui.menu-item.states`, `library.raft-ui.menu-surfaces.states`,
 *   `components.ui.selection-popover.states`.
 * - [overlays]: `library.raft-ui.wip-overlay-shells.states`,
 *   `library.raft-ui.controlled-open-overlays`.
 * - [textLink]: raft-ui 0.8.0 TextLink (`text-link/compositions/preview`, `disabled`) and
 *   the banner's inline action (`banner/compositions/action`).
 */
class InteractiveComponentScreenshotTest {
    private val appearances = listOf(RaftAppearance.Light, RaftAppearance.Dark)

    private fun capture(name: String, settleFrames: Int = 0, content: @Composable () -> Unit) =
        appearances.forEach { appearance ->
            RaftScreenshot.capture("${name}_${appearance.name.lowercase()}", appearance, settleFrames = settleFrames, content = content)
        }

    @Test
    fun textField() =
        capture("text_field") {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.width(310.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Caption("Input: placeholder, value, focused")
                    RaftTextField("", {}, placeholder = "Wug")
                    RaftTextField("cindy@slock.ai", {})
                    StateCell(focused = true) { RaftTextField("cindy@slock.ai", {}) }
                    Caption("Invalid, invalid and focused, disabled")
                    RaftTextField("cindy@", {}, invalid = true)
                    StateCell(focused = true) { RaftTextField("cindy@", {}, invalid = true) }
                    RaftTextField("cindy@slock.ai", {}, enabled = false)
                    Caption("Read-only")
                    RaftTextField("agt_7f3k29xq", {}, readOnly = true)
                }
                Column(Modifier.width(310.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Caption("InputGroup: prefix, focused (the whole group)")
                    RaftTextField("design-lab", {}, prefix = "/")
                    StateCell(focused = true) { RaftTextField("partner-workspace", {}, prefix = "/") }
                    Caption("Icon addons, suffix, invalid, disabled")
                    RaftTextField("", {}, placeholder = "Search channels", leading = { RaftGlyphIcon(RaftGlyph.Search, size = 18.dp) })
                    RaftTextField("wug", {}, suffix = "@slock.ai", trailing = { RaftGlyphIcon(RaftGlyph.Info, size = 18.dp) })
                    RaftTextField("design lab", {}, prefix = "/", invalid = true)
                    RaftTextField("design-lab", {}, prefix = "/", enabled = false)
                }
            }
        }

    @Test
    fun textarea() =
        capture("textarea") {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.width(310.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Caption("Placeholder, value with counter")
                    RaftTextarea("", {}, placeholder = "What should the agent do first?")
                    RaftTextarea("Review the release notes and post a summary in #design.", {}, limit = 280)
                    Caption("Focused")
                    StateCell(focused = true) { RaftTextarea("Review the release notes.", {}) }
                }
                Column(Modifier.width(310.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Caption("Over the limit, in an invalid field")
                    RaftField(label = "Agreement", error = "Agreement body must be shorter.") {
                        RaftTextarea("This agreement copy is too long for the configured limit.", {}, limit = 40)
                    }
                    Caption("Disabled")
                    RaftTextarea("Review the release notes.", {}, enabled = false, limit = 280)
                }
            }
        }

    @Test
    fun field() =
        capture("field") {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.width(310.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    RaftField(label = "Email") { RaftTextField("cindy@slock.ai", {}) }
                    RaftField(label = "Description", description = "Shown in channel discovery.", optional = true) {
                        RaftTextField("Visual parity fixture", {})
                    }
                    RaftField(label = "Server name", error = "Name is required", required = true) {
                        RaftTextField("", {}, placeholder = "Raft Labs")
                    }
                    RaftField(label = "Workspace", description = "Set by your admin.", enabled = false) {
                        RaftTextField("raft-labs", {}, prefix = "/")
                    }
                    RaftField(label = "Small label", labelSize = RaftFieldLabelSize.Sm) { RaftTextField("", {}, placeholder = "Wug") }
                    Caption("FieldItem md, sm: the control on the label's first line")
                    RaftFieldItem("Don't remind me again") { RaftCheckbox(true, {}) }
                    RaftFieldItem("Don't remind me again", size = RaftFieldLabelSize.Sm) { RaftCheckbox(true, {}) }
                    RaftFieldItem("Send as a task", description = "Leaving this off posts an ordinary message.") { RaftCheckbox(false, {}) }
                }
                Column(Modifier.width(340.dp)) {
                    Caption("FormSection: sections of a wide dialog", Modifier.padding(bottom = 12.dp))
                    RaftFormSection("Identity", first = true, description = "How people see the agent.") {
                        RaftField(label = "Name") { RaftTextField("Wug", {}) }
                    }
                    RaftFormSection("Runtime", status = "Optional") {
                        RaftField(label = "Model") { RaftSelectTrigger("Claude Sonnet", {}) }
                    }
                }
            }
        }

    @Test
    fun dialogForm() =
        capture("dialog_form") {
            Row(horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.width(358.dp).padding(bottom = 24.dp)) {
                    RaftDialogSurface(
                        title = "Create Channel",
                        onClose = {},
                        footer = {
                            RaftButton("Cancel", {}, variant = RaftButtonVariant.Ghost, size = RaftButtonSize.Lg)
                            RaftButton("Create Channel", {}, variant = RaftButtonVariant.Primary, size = RaftButtonSize.Lg)
                        },
                    ) {
                        RaftField(label = "Name") { RaftTextField("launch-week", {}, prefix = "#") }
                        RaftField(label = "Description", optional = true, description = "Shown in channel discovery.") {
                            RaftTextField("", {}, placeholder = "What is this channel about?")
                        }
                        RaftCheckboxRow("Make private", checked = false, onCheckedChange = {}, description = "Only invited people can find it.")
                    }
                }
                Box(Modifier.width(358.dp).padding(bottom = 24.dp)) {
                    RaftDialogSurface(
                        title = "Create Agent",
                        onClose = {},
                        footer = {
                            RaftButton("Cancel", {}, variant = RaftButtonVariant.Ghost, size = RaftButtonSize.Lg)
                            RaftButton("Create Agent", {}, variant = RaftButtonVariant.Primary, size = RaftButtonSize.Lg)
                        },
                    ) {
                        RaftField(label = "Name", error = "Enter a name for the agent.") {
                            RaftTextField("", {}, placeholder = "Wug")
                        }
                        RaftField(label = "Runtime") { RaftSelectTrigger(null, {}, placeholder = "Select a runtime") }
                        RaftBanner("Agents run on this computer while the app is open.", RaftBannerStatus.Info, size = RaftBannerSize.Sm)
                    }
                }
            }
        }

    @Test
    fun checkbox() =
        capture("checkbox") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Caption("Unchecked, checked, indeterminate x rest, focused, disabled")
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf(false to false, true to false, false to true).forEach { (checked, mixed) ->
                        RaftCheckbox(checked, {}, indeterminate = mixed)
                        StateCell(focused = true) { RaftCheckbox(checked, {}, indeterminate = mixed) }
                        RaftCheckbox(checked, {}, indeterminate = mixed, enabled = false)
                    }
                }
                Caption("Indicator (check marker): square, circle x unchecked, checked, indeterminate, disabled")
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf(RaftCheckboxShape.Square, RaftCheckboxShape.Circle).forEach { shape ->
                        RaftCheckboxIndicator(checked = false, shape = shape)
                        RaftCheckboxIndicator(checked = true, shape = shape)
                        RaftCheckboxIndicator(checked = false, shape = shape, indeterminate = true)
                        RaftCheckboxIndicator(checked = true, shape = shape, enabled = false)
                    }
                }
                Caption("components.ui.check-marker.states")
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf(
                        Triple("Square checked", true, RaftCheckboxShape.Square),
                        Triple("Square unchecked", false, RaftCheckboxShape.Square),
                        Triple("Circle yellow", true, RaftCheckboxShape.Circle),
                    ).forEach { (label, checked, shape) ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            RaftCheckboxIndicator(checked = checked, shape = shape)
                            RaftText(label, style = RaftTheme.typography.callout.copy(fontWeight = FontWeight.Bold))
                        }
                    }
                }
                Caption("Rows: label, description, focused, disabled")
                Column(Modifier.width(340.dp)) {
                    RaftCheckboxRow("As task", checked = true, onCheckedChange = {})
                    RaftCheckboxRow("Notify the channel", checked = false, onCheckedChange = {}, description = "Everyone in #design gets a notification.")
                    StateCell(focused = true) { RaftCheckboxRow("Pin to the top", checked = false, onCheckedChange = {}) }
                    RaftCheckboxRow("Disabled checked", checked = true, onCheckedChange = {}, enabled = false)
                }
                Caption("A label that wraps: the box stays on its first line")
                Column(Modifier.width(200.dp)) {
                    RaftCheckboxRow("Notify everyone in the channel when this is done", checked = true, onCheckedChange = {})
                }
                Caption("FieldItem md, sm; wrapping label; with a description")
                Column(Modifier.width(240.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    RaftFieldItem("Send as a task") { RaftCheckbox(true, {}) }
                    RaftFieldItem("Send as a task", size = RaftFieldLabelSize.Sm) { RaftCheckbox(true, {}) }
                    RaftFieldItem("A label long enough that it has to wrap onto a second line") { RaftCheckbox(false, {}) }
                    RaftFieldItem("Send as a task", description = "Leaving this off posts an ordinary message.") { RaftCheckbox(false, {}) }
                }
            }
        }

    @Test
    fun radio() =
        capture("radio") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Caption("Off, on x rest, focused, disabled")
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf(false, true).forEach { selected ->
                        RaftRadio(selected, {})
                        StateCell(focused = true) { RaftRadio(selected, {}) }
                        RaftRadio(selected, {}, enabled = false)
                    }
                }
                Caption("RadioGroup rows and the warning under them (reset agent)")
                Column(Modifier.width(360.dp)) {
                    RaftRadioGroup {
                        RaftRadioRow("Restart", selected = true, onClick = {}, description = "Stops the agent and starts it again. Nothing is lost.")
                        RaftRadioRow("Reset session", selected = false, onClick = {}, description = "Starts a new conversation. Its memory stays.")
                        RaftRadioRow("Full reset", selected = false, onClick = {}, description = "Clears memory and files. You can't undo this.", enabled = false)
                    }
                    RaftBanner("Wug stops for a few seconds while it restarts.", RaftBannerStatus.Warning, size = RaftBannerSize.Sm)
                }
                Caption("FieldItem md, sm: the radio on the label's first line")
                RaftRadioGroup(Modifier.width(240.dp)) {
                    RaftFieldItem("Everyone", Modifier.padding(vertical = 4.dp)) { RaftRadio(true, {}) }
                    RaftFieldItem("Only people I follow", Modifier.padding(vertical = 4.dp), size = RaftFieldLabelSize.Sm) { RaftRadio(false, {}) }
                }
            }
        }

    @Test
    fun switch() =
        capture("switch") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Caption("Off, on x rest, focused, disabled")
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf(false, true).forEach { on ->
                        RaftSwitch(on, {})
                        StateCell(focused = true) { RaftSwitch(on, {}) }
                        RaftSwitch(on, {}, enabled = false)
                    }
                }
                Caption("In settings rows")
                RaftListRowGroup(Modifier.width(360.dp)) {
                    RaftListRow("Desktop notifications", variant = RaftListRowVariant.Plain, trailing = { RaftSwitch(true, {}) })
                    RaftListRow(
                        "Sound",
                        variant = RaftListRowVariant.Plain,
                        divider = true,
                        description = "Play a sound for mentions.",
                        trailing = { RaftSwitch(false, {}) },
                    )
                }
                Caption("FieldItem md, sm, with a description: the 30dp switch on the label's first line")
                Column(Modifier.width(280.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    RaftFieldItem("Send as a task") { RaftSwitch(true, {}) }
                    RaftFieldItem("Send as a task", size = RaftFieldLabelSize.Sm) { RaftSwitch(false, {}) }
                    RaftFieldItem("Send as a task", description = "Leaving this off posts an ordinary message.") { RaftSwitch(false, {}) }
                }
            }
        }

    @Test
    fun tabs() =
        capture("tabs") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Caption("Default: selected, rest, pressed, focused, disabled")
                RaftTabs {
                    RaftTab("Workspace", selected = true, onClick = {}, icon = { RaftGlyphIcon(RaftGlyph.Copy) })
                    RaftTab("Profile", selected = false, onClick = {}, icon = { RaftGlyphIcon(RaftGlyph.Info) })
                    StateCell(pressed = true) { RaftTab("Activity", selected = false, onClick = {}) }
                    StateCell(focused = true) { RaftTab("Tasks", selected = false, onClick = {}) }
                    RaftTab("Billing", selected = false, onClick = {}, enabled = false)
                }
                Caption("Selected pressed, selected focused")
                RaftTabs {
                    StateCell(pressed = true) { RaftTab("Workspace", selected = true, onClick = {}) }
                    StateCell(focused = true) { RaftTab("Profile", selected = true, onClick = {}) }
                }
                Caption("Tab strip of a panel (agent detail), 390dp")
                Box(Modifier.width(390.dp)) {
                    RaftTabStrip {
                        RaftTab("Profile", selected = false, onClick = {})
                        RaftTab("Workspace", selected = true, onClick = {}, icon = { RaftGlyphIcon(RaftGlyph.Copy) })
                        RaftTab("Activity", selected = false, onClick = {})
                        RaftTab("Settings", selected = false, onClick = {})
                    }
                }
                Caption("Overflowing strip, 260dp: first tab selected and focused, the end fades")
                Box(Modifier.width(260.dp)) {
                    RaftTabStrip {
                        StateCell(focused = true) { RaftTab("Profile", selected = true, onClick = {}) }
                        RaftTab("Workspace", selected = false, onClick = {})
                        RaftTab("Activity", selected = false, onClick = {})
                        RaftTab("Settings", selected = false, onClick = {})
                        RaftTab("Billing", selected = false, onClick = {})
                    }
                }
                Caption("Underline: selected, rest, pressed, focused, disabled")
                RaftTabs(variant = RaftTabsVariant.Underline) {
                    RaftTab("Messages", selected = true, onClick = {})
                    RaftTab("Files", selected = false, onClick = {})
                    StateCell(pressed = true) { RaftTab("Tasks", selected = false, onClick = {}) }
                    StateCell(focused = true) { RaftTab("Pins", selected = false, onClick = {}) }
                    RaftTab("Links", selected = false, onClick = {}, enabled = false)
                }
            }
        }

    @Test
    fun segmentedControl() =
        capture("segmented_control") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Caption("Segments with counts")
                RaftSegmentedControl {
                    RaftSegment("All", selected = false, onClick = {}, count = "24")
                    RaftSegment("Mentions", selected = true, onClick = {}, count = "3")
                    RaftSegment("Unread", selected = false, onClick = {}, count = "9")
                }
                Caption("Pressed, selected pressed, focused, disabled")
                RaftSegmentedControl {
                    StateCell(pressed = true) { RaftSegment("Light", selected = false, onClick = {}) }
                    StateCell(pressed = true) { RaftSegment("Dark", selected = true, onClick = {}) }
                    StateCell(focused = true) { RaftSegment("System", selected = false, onClick = {}) }
                    RaftSegment("Custom", selected = false, onClick = {}, enabled = false)
                }
                Caption("Full width, 342dp")
                RaftSegmentedControl(Modifier.width(342.dp)) {
                    RaftSegment("Light", selected = true, onClick = {}, modifier = Modifier.weight(1f))
                    RaftSegment("Dark", selected = false, onClick = {}, modifier = Modifier.weight(1f))
                    RaftSegment("System", selected = false, onClick = {}, modifier = Modifier.weight(1f))
                }
                Caption("In a 200dp row: the segments keep their width and run past it (shrink-0)")
                Column(Modifier.width(200.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    RaftSegmentedControl {
                        RaftSegment("All", selected = false, onClick = {}, count = "24")
                        RaftSegment("Mentions", selected = true, onClick = {}, count = "3")
                        RaftSegment("Unread", selected = false, onClick = {}, count = "9")
                    }
                    // The row's 200dp.
                    Box(Modifier.fillMaxWidth().height(2.dp).background(RaftTheme.colors.foregroundHint))
                }
                Caption("ToggleGroup: two pressed, focused, disabled")
                RaftToggleGroup {
                    RaftToggleGroupItem("Bold", pressed = true, onPressedChange = {})
                    RaftToggleGroupItem("Italic", pressed = true, onPressedChange = {})
                    StateCell(focused = true) { RaftToggleGroupItem("Code", pressed = false, onPressedChange = {}) }
                    RaftToggleGroupItem("Quote", pressed = false, onPressedChange = {}, enabled = false)
                }
            }
        }

    @Test
    fun textLink() =
        capture("text_link") {
            val colors = RaftTheme.colors
            val type = RaftTheme.typography
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(RaftTextLinkVariant.Muted, RaftTextLinkVariant.Strong).forEach { variant ->
                    Caption("${variant.name}: rest, pressed, focused, disabled (text-footnote)")
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        RaftTextLink("Forgot Password", {}, variant = variant, style = type.footnote)
                        StateCell(pressed = true) { RaftTextLink("Forgot Password", {}, variant = variant, style = type.footnote) }
                        StateCell(focused = true) { RaftTextLink("Forgot Password", {}, variant = variant, style = type.footnote) }
                        RaftTextLink("Log Out", {}, variant = variant, style = type.footnote, enabled = false)
                    }
                }
                Caption("Takes the size of the text around it: caption, footnote, callout, body")
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    listOf(type.caption, type.footnote, type.callout, type.body).forEach { RaftTextLink("See All", {}, style = it) }
                }
                Caption("Inside a sentence (strong): rest, pressed, focused")
                listOf(false to false, true to false, false to true).forEach { (pressed, focused) ->
                    StateCell(pressed = pressed, focused = focused) { Sentence(type.footnote, colors.foregroundMuted) }
                }
                Caption("In body text, wrapping at 240dp: the link stays on one line and the line keeps its height")
                RaftText(
                    buildAnnotatedString {
                        append("Your trial ends in three days. To keep your agents running, ")
                        appendRaftTextLink("Choose a Plan", {})
                        append(" or talk to us.")
                    },
                    Modifier.width(240.dp),
                    style = type.body,
                )
                Caption("Centred under a full-width button (fillMaxWidth, TextAlign.Center)")
                Column(Modifier.width(340.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    RaftButton("Sign In", {}, variant = RaftButtonVariant.Primary, size = RaftButtonSize.Xl, modifier = Modifier.fillMaxWidth())
                    RaftText(
                        buildAnnotatedString {
                            append("Don't have an account? ")
                            appendRaftTextLink("Create One", {})
                        },
                        Modifier.fillMaxWidth(),
                        style = type.footnote,
                        color = colors.foregroundMuted,
                        textAlign = TextAlign.Center,
                    )
                }
                Caption("A link longer than its line wraps; each line is underlined")
                RaftText(
                    buildAnnotatedString {
                        appendRaftTextLink("Choose a Plan for Your Whole Team", {})
                        append(" now")
                    },
                    Modifier.width(150.dp),
                    style = type.footnote,
                )
                Caption("Banner: a text action inside the description")
                RaftBanner(
                    buildAnnotatedString {
                        append("Remove an inactive machine before adding another. ")
                        appendRaftTextLink("Manage Machines", {})
                    },
                    RaftBannerStatus.Warning,
                    title = "Plan limit reached",
                    modifier = Modifier.width(340.dp),
                )
            }
        }

    @Composable
    private fun Sentence(style: TextStyle, color: androidx.compose.ui.graphics.Color) {
        RaftText(
            buildAnnotatedString {
                append("Don't have an account? ")
                appendRaftTextLink("Create One", {})
            },
            style = style,
            color = color,
        )
    }

    @Test
    fun select() =
        capture("select") {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.width(310.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Caption("Select trigger: value, placeholder, pressed")
                    RaftSelectTrigger("Codex", {})
                    RaftSelectTrigger(null, {}, placeholder = "Select a role")
                    StateCell(pressed = true) { RaftSelectTrigger("Codex", {}) }
                    Caption("Focused, invalid, disabled")
                    StateCell(focused = true) { RaftSelectTrigger("Codex", {}) }
                    RaftSelectTrigger(null, {}, placeholder = "Select a role", invalid = true)
                    RaftSelectTrigger(null, {}, placeholder = "Select…", enabled = false)
                }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Caption("PickerTriggerButton: rest, applied (info-soft)")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RaftPickerTriggerButton("Status", {})
                        RaftPickerTriggerButton("Status: Open", {}, hasValue = true)
                    }
                    Caption("Pressed, applied pressed, focused")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StateCell(pressed = true) { RaftPickerTriggerButton("Assignee", {}) }
                        StateCell(pressed = true) { RaftPickerTriggerButton("Assignee: Wug", {}, hasValue = true) }
                        StateCell(focused = true) { RaftPickerTriggerButton("Channel", {}) }
                    }
                    Caption("Invalid, disabled, icon, no chevron")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RaftPickerTriggerButton("Due date", {}, invalid = true)
                        RaftPickerTriggerButton("Label", {}, enabled = false)
                        RaftPickerTriggerButton("Mine", {}, hasValue = true, showIndicator = false, leadingIcon = { RaftGlyphIcon(RaftGlyph.AtSign, size = 14.dp) })
                    }
                }
            }
        }

    @Test
    fun menu() =
        capture("menu") {
            Row(horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.width(260.dp).padding(bottom = 24.dp)) {
                    Caption("Menu: highlighted (pressed), focused, disabled, divider", Modifier.padding(bottom = 8.dp))
                    RaftMenuSurface(Modifier.fillMaxWidth()) {
                        RaftMenuItem("Open Channel", {}, shortcut = "⌘K", leadingIcon = { RaftGlyphIcon(RaftGlyph.ChevronRight) })
                        StateCell(pressed = true) { RaftMenuItem("Mark as Read", {}, leadingIcon = { RaftGlyphIcon(RaftGlyph.Check) }) }
                        StateCell(focused = true) { RaftMenuItem("Copy Link", {}, leadingIcon = { RaftGlyphIcon(RaftGlyph.Copy) }) }
                        RaftMenuItem("Archive Channel", {}, enabled = false)
                        RaftMenuItem("Delete Message", {}, divider = true)
                        RaftMenuSeparator()
                        RaftMenuLabel("Sort by")
                        RaftMenuItem("Recent Activity", {}, checked = true)
                        RaftMenuItem("Name", {})
                    }
                }
                Column(Modifier.width(260.dp).padding(bottom = 24.dp)) {
                    Caption("Selection popover: focused search row", Modifier.padding(bottom = 8.dp))
                    StateCell(focused = true) {
                        RaftMenuSurface(
                            Modifier.fillMaxWidth(),
                            header = { RaftMenuSearchField("des", {}, placeholder = "Search channels") },
                        ) {
                            CompositionLocalProvider(LocalRaftFocusedPreview provides false) {
                                RaftMenuItem("design", {}, checked = true)
                                RaftMenuItem("visual-testing", {})
                                RaftMenuItem("archived channel", {}, enabled = false)
                            }
                        }
                    }
                }
            }
        }

    @Test
    fun overlays() =
        capture("overlays") {
            Row(horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.width(358.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Caption("Dialog sm: a decision")
                    RaftDialogSurface(
                        title = "Leave #design?",
                        size = RaftDialogSize.Sm,
                        description = "You can join again any time from Browse Channels.",
                        footer = {
                            RaftButton("Stay", {}, variant = RaftButtonVariant.Ghost, size = RaftButtonSize.Lg)
                            RaftButton("Leave Channel", {}, variant = RaftButtonVariant.Warning, size = RaftButtonSize.Lg)
                        },
                    )
                    Caption("Toast: title only, icon and action, description", Modifier.padding(top = 20.dp))
                    RaftToast("Link copied.")
                    RaftToast(
                        "Wug finished reviewing #25.",
                        icon = { RaftAvatar(RaftAvatarType.Agent, RaftAvatarSize.Xs, initials = "W") },
                        actionLabel = "View",
                        onAction = {},
                    )
                    RaftToast(
                        "Settings saved.",
                        description = "Your teammates see the new name now.",
                        icon = { RaftStatusDot(variant = RaftStatusVariant.Success) },
                    )
                    Caption("Foreground banner (popover surface)", Modifier.padding(top = 8.dp))
                    RaftToast(
                        "Ada in #design",
                        description = "The new tabs are in. Can you review?",
                        surface = RaftToastSurface.Popover,
                        icon = { RaftAvatar(RaftAvatarType.Human, RaftAvatarSize.Xs, initials = "A") },
                        modifier = Modifier.padding(bottom = 24.dp),
                    )
                }
                Column(Modifier.width(358.dp)) {
                    Caption("Bottom sheet", Modifier.padding(bottom = 12.dp))
                    RaftSheetSurface(
                        title = "Switch server",
                        description = "Pick the server to open.",
                        actions = {
                            RaftButton("Got It", {}, variant = RaftButtonVariant.Primary, size = RaftButtonSize.Lg, modifier = Modifier.fillMaxWidth())
                        },
                    ) {
                        RaftRadioGroup {
                            RaftRadioRow("Raft Labs", selected = true, onClick = {})
                            RaftRadioRow("Acme", selected = false, onClick = {})
                        }
                    }
                }
            }
        }

    /**
     * A real [RaftDropdownMenu] and an open [RaftSelect] in their popups, in a window much
     * wider than the menu: the popup hugs its rows (and the select's list its trigger)
     * instead of taking the window's width.
     */
    @Test
    fun menuPopup() =
        capture("menu_popup", settleFrames = 2) {
            // Opened by touch: no row focused, so the frame doesn't depend on the input mode.
            CompositionLocalProvider(LocalInputModeManager provides TouchMode) {
            Box(Modifier.size(720.dp, 330.dp)) {
                Box {
                    RaftPickerTriggerButton("Sort", {})
                    RaftDropdownMenu(expanded = true, onDismissRequest = {}) {
                        RaftMenuItem("Recent Activity", {}, checked = true)
                        RaftMenuItem("Name", {})
                        RaftMenuSeparator()
                        RaftMenuItem("Mark All as Read", {}, shortcut = "⇧⌘A")
                    }
                }
                Box(Modifier.align(Alignment.TopEnd).width(300.dp)) {
                    RaftSelectOpen()
                }
            }
            }
        }

    @OptIn(ExperimentalComposeUiApi::class)
    private object TouchMode : InputModeManager {
        override val inputMode: InputMode = InputMode.Touch

        override fun requestInputMode(inputMode: InputMode): Boolean = false
    }

    @Composable
    private fun RaftSelectOpen() {
        // RaftSelect opens on a tap; the fixture opens it straight away.
        var value by remember { mutableStateOf<String?>("Codex") }
        Box {
            RaftSelectTrigger(value, {}, expanded = true)
            RaftDropdownMenu(expanded = true, onDismissRequest = {}, width = 300.dp, offset = 4.dp) {
                listOf("Claude Code", "Codex", "Gemini CLI").forEach { option ->
                    RaftMenuItem(option, { value = option }, checked = option == value)
                }
            }
        }
    }

    /** 200% font scale: fields, triggers, tabs and choice rows grow instead of clipping. */
    @Test
    fun largeFont() =
        capture("large_font") {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                Column(Modifier.width(420.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    RaftField(label = "Name") { RaftTextField("Wug", {}) }
                    RaftField(label = "Runtime") { RaftSelectTrigger("Codex", {}) }
                    RaftTabs {
                        RaftTab("Profile", selected = true, onClick = {})
                        RaftTab("Tasks", selected = false, onClick = {})
                    }
                    RaftTabs(variant = RaftTabsVariant.Underline) {
                        RaftTab("Messages", selected = true, onClick = {})
                        RaftTab("Files", selected = false, onClick = {})
                    }
                    RaftPickerTriggerButton("Status", {})
                    RaftCheckboxRow("Make private", checked = true, onCheckedChange = {})
                }
            }
        }

    // Fixture parts ------------------------------------------------------------------

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
