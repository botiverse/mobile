package build.raft.app

import build.raft.app.components.SegmentedControl
import build.raft.app.components.SegmentedControlOption
import build.raft.app.components.SlockAvatarListRow
import build.raft.app.components.SlockBrutalActionButton
import build.raft.app.components.SlockBrutalBadge
import build.raft.app.components.SlockBrutalBadgeButton
import build.raft.app.components.SlockBrutalInputField
import build.raft.app.components.SlockBrutalTextarea
import build.raft.app.components.SlockCheckboxLabelRow
import build.raft.app.components.SlockFormField
import build.raft.app.components.SlockFormFieldLabelStyle
import build.raft.app.components.SlockFormFieldSize
import build.raft.app.components.SlockMenuItemRow
import build.raft.app.components.SlockBrutalTone
import build.raft.app.components.SlockSectionHeader
import build.raft.app.components.SlockSectionEyebrow
import build.raft.app.components.SlockSelectTrigger
import build.raft.app.components.SlockSelectionPopover
import build.raft.app.components.SlockSelectionPopoverOption
import build.raft.app.components.SlockSlugInput
import build.raft.app.components.SlockSurfaceListItem
import androidx.compose.runtime.Composable
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.text.font.FontFamily
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp

@Composable
internal fun UiSegmentedControlVisualContent() {
    Box(
        modifier =
            Modifier
                .width(342.dp)
                .background(SlockColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        SegmentedControl(
            selectedValue = "mentions",
            options =
                listOf(
                    SegmentedControlOption(value = "all", label = "All", count = 24),
                    SegmentedControlOption(value = "mentions", label = "Mentions", count = 3),
                    SegmentedControlOption(value = "unread", label = "Unread", count = 9)
                ),
            onValueSelected = {}
        )
    }
}

@Composable
internal fun UiButtonVisualContent() {
    Box(
        modifier =
            Modifier
                .width(342.dp)
                .height(150.dp)
                .background(SlockColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                SlockBrutalActionButton(label = "Save", onClick = {}, minHeight = 28.dp, minWidth = 0.dp, shadowOffset = 2.dp, textStyle = SlockType.ButtonBold.copy(fontSize = 12.sp, lineHeight = 14.sp))
                SlockBrutalActionButton(label = "Sync", onClick = {}, backgroundColor = SlockColor.BrandYellow, minHeight = 28.dp, minWidth = 0.dp, shadowOffset = 2.dp, textStyle = SlockType.ButtonBold.copy(fontSize = 12.sp, lineHeight = 14.sp))
                SlockBrutalActionButton(label = "Delete", onClick = {}, backgroundColor = SlockColor.AccentPink, minHeight = 28.dp, minWidth = 0.dp, shadowOffset = 2.dp, textStyle = SlockType.ButtonBold.copy(fontSize = 12.sp, lineHeight = 14.sp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                SlockBrutalActionButton(label = "Add", onClick = {}, backgroundColor = SlockColor.AccentCyan, minHeight = 24.dp, minWidth = 0.dp, shadowOffset = 2.dp, textStyle = SlockType.ButtonBold.copy(fontSize = 11.sp, lineHeight = 12.sp))
                SlockBrutalActionButton(label = "Continue", onClick = {}, backgroundColor = SlockColor.AccentLime, minHeight = 32.dp, minWidth = 0.dp, shadowOffset = 2.dp, textStyle = SlockType.ButtonBold.copy(fontSize = 14.sp, lineHeight = 16.sp))
                SlockBrutalActionButton(label = "Disabled", onClick = {}, backgroundColor = SlockColor.SurfaceCream, enabled = false, minHeight = 28.dp, minWidth = 0.dp, shadowOffset = 2.dp, textStyle = SlockType.ButtonBold.copy(fontSize = 12.sp, lineHeight = 14.sp))
            }
        }
    }
}

@Composable
internal fun UiFormFieldVisualContent() {
    Box(
        modifier =
            Modifier
                .width(342.dp)
                .height(226.dp)
                .background(SlockColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SlockFormField(
                label = "Email",
                required = true,
                labelStyle = SlockFormFieldLabelStyle.Plain
            ) {
                SlockBrutalInputField(
                    value = "cindy@slock.ai",
                    onValueChange = {},
                    placeholder = "",
                    autoFocus = false,
                    fieldMinHeight = 42.dp
                )
            }
            SlockFormField(
                label = "Description",
                optional = true,
                hint = "Shown in channel discovery."
            ) {
                SlockBrutalInputField(
                    value = "Visual parity fixture",
                    onValueChange = {},
                    placeholder = "",
                    autoFocus = false,
                    fieldMinHeight = 42.dp
                )
            }
            SlockFormField(
                label = "Server Name",
                size = SlockFormFieldSize.Compact,
                error = "Name is required"
            ) {
                SlockBrutalInputField(
                    value = "",
                    onValueChange = {},
                    placeholder = "",
                    autoFocus = false,
                    fieldMinHeight = 42.dp,
                    borderColor = SlockColor.AccentRed
                )
            }
        }
    }
}

@Composable
internal fun UiTextareaVisualContent() {
    Box(
        modifier =
            Modifier
                .width(342.dp)
                .height(240.dp)
                .background(SlockColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SlockBrutalTextarea(
                value = "Capture the Android visual artifact and compare it with React.",
                onValueChange = {},
                hint = "Plain helper text",
                showCounter = true,
                limit = 120,
                fieldHeight = 90.dp,
                maxLines = 3
            )
            SlockBrutalTextarea(
                value = "This agreement copy is too long for the configured limit.",
                onValueChange = {},
                error = "Agreement body must be shorter.",
                showCounter = true,
                limit = 40,
                fieldHeight = 60.dp,
                maxLines = 2
            )
        }
    }
}

@Composable
internal fun UiCheckboxVisualContent() {
    Box(
        modifier =
            Modifier
                .width(342.dp)
                .height(132.dp)
                .background(SlockColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SlockCheckboxLabelRow(label = "As Task selected", checked = true, size = 14.dp)
            SlockCheckboxLabelRow(label = "Permission row unchecked", checked = false, size = 16.dp)
            SlockCheckboxLabelRow(label = "Disabled checked", checked = true, enabled = false, size = 16.dp)
        }
    }
}

@Composable
internal fun UiSlugInputVisualContent() {
    Box(
        modifier =
            Modifier
                .width(342.dp)
                .height(122.dp)
                .background(SlockColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SlockSlugInput(value = "design-lab", onValueChange = {})
            SlockSlugInput(value = "partner-workspace", onValueChange = {}, enabled = false)
        }
    }
}

@Composable
internal fun UiSectionEyebrowVisualContent() {
    Box(
        modifier =
            Modifier
                .width(342.dp)
                .height(92.dp)
                .background(SlockColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SlockSectionEyebrow(label = "Recent Activity")
            SlockSectionEyebrow(label = "Connected Apps", color = SlockColor.TextBlack)
            Box(modifier = Modifier.background(SlockColor.SurfaceWhite.copy(alpha = 0.5f)).padding(horizontal = 8.dp, vertical = 4.dp)) {
                SlockSectionEyebrow(label = "Choose Avatar")
            }
        }
    }
}

@Composable
internal fun UiSelectionPopoverVisualContent() {
    Box(
        modifier =
            Modifier
                .width(342.dp)
                .height(252.dp)
                .background(SlockColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        SlockSelectionPopover(
            title = "Channels",
            showClear = true,
            search = "des",
            searchPlaceholder = "Search channels",
            options =
                listOf(
                    SlockSelectionPopoverOption("design", "design", checked = true, reserveLeadingSlot = true),
                    SlockSelectionPopoverOption("visual-testing", "visual-testing", reserveLeadingSlot = true),
                    SlockSelectionPopoverOption("archive", "archived channel", enabled = false, reserveLeadingSlot = true),
                    SlockSelectionPopoverOption("none", "No channel", italic = true, reserveLeadingSlot = true)
                )
        )
    }
}

@Composable
internal fun UiMenuItemVisualContent() {
    Box(
        modifier =
            Modifier
                .width(342.dp)
                .height(154.dp)
                .background(SlockColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(SlockColor.SurfaceWhite)
                    .border(SlockStroke.Default, SlockColor.BorderBlack)
                    .padding(bottom = 3.dp),
            verticalArrangement = Arrangement.Top
        ) {
            SlockMenuItemRow(label = "Open Channel", trailing = "⌘K")
            SlockMenuItemRow(label = "Mark as Read")
            SlockMenuItemRow(label = "Archive unavailable", enabled = false)
            SlockMenuItemRow(label = "Delete Message", dividerBefore = true)
        }
    }
}

@Composable
internal fun UiSelectVisualContent() {
    Box(
        modifier =
            Modifier
                .width(342.dp)
                .height(194.dp)
                .background(SlockColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SlockSelectTrigger(label = "Codex")
            SlockSelectTrigger(label = "Select...", enabled = false)
        }
    }
}

@Composable
internal fun UiBadgeVisualContent() {
    Box(
        modifier =
            Modifier
                .width(342.dp)
                .height(92.dp)
                .background(SlockColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SlockBrutalBadge("Shared", tone = SlockBrutalTone.White)
                SlockBrutalBadge("Installed", tone = SlockBrutalTone.Lime)
                SlockBrutalBadge("Update", tone = SlockBrutalTone.Orange)
                SlockBrutalBadge("Built In", tone = SlockBrutalTone.Black)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SlockBrutalBadge("task #273", tone = SlockBrutalTone.Red, uppercase = false)
                SlockBrutalBadgeButton("Install", tone = SlockBrutalTone.Pink, onClick = {})
            }
        }
    }
}

@Composable
internal fun UiSectionHeaderVisualContent() {
    Box(
        modifier =
            Modifier
                .width(342.dp)
                .height(88.dp)
                .background(SlockColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SlockSectionHeader(
                label = "Connected Apps",
                count = 3,
                modifier = Modifier.fillMaxWidth(),
                action = {
                    SlockBrutalActionButton(label = "Add", onClick = {}, minHeight = 24.dp, minWidth = 0.dp, shadowOffset = 2.dp, textStyle = SlockType.ButtonBold.copy(fontSize = 11.sp, lineHeight = 12.sp))
                }
            )
            Box(modifier = Modifier.fillMaxWidth().height(SlockStroke.Default).background(SlockColor.BorderBlack))
        }
    }
}

@Composable
internal fun UiSurfaceListItemVisualContent() {
    Box(
        modifier =
            Modifier
                .width(342.dp)
                .height(150.dp)
                .background(SlockColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SlockSurfaceListItem(interactive = false) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Private app", style = SlockType.ButtonBold.copy(fontSize = 14.sp, lineHeight = 18.sp), color = SlockColor.TextBlack, maxLines = 1)
                    Text("Available to this server", style = SlockType.Caption.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 14.sp), color = SlockColor.TextBlack.copy(alpha = 0.5f), maxLines = 1)
                }
            }
            SlockSurfaceListItem(selected = true, interactive = false) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Active integration", style = SlockType.ButtonBold.copy(fontSize = 14.sp, lineHeight = 18.sp), color = SlockColor.TextBlack, maxLines = 1)
                    Text("Selected list item state", style = SlockType.Caption.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 14.sp), color = SlockColor.TextBlack.copy(alpha = 0.5f), maxLines = 1)
                }
            }
        }
    }
}

@Composable
internal fun UiAvatarListRowVisualContent() {
    Box(
        modifier =
            Modifier
                .width(342.dp)
                .height(168.dp)
                .background(SlockColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SlockAvatarListRow(
                avatarWireValue = "pixel:random:cindy",
                avatarLabel = "Cindy",
                name = "Cindy",
                subtitle = "Claude Code",
                rightContent = {
                    SlockBrutalBadge("Online", tone = SlockBrutalTone.Lime)
                }
            )
            SlockAvatarListRow(
                avatarWireValue = null,
                avatarLabel = "Product UX Designer",
                name = "Product UX Designer",
                subtitle = "product@slock.ai",
                selected = true,
                rightContent = {
                    SlockBrutalActionButton(label = "Open", onClick = {}, minHeight = 24.dp, minWidth = 0.dp, shadowOffset = 2.dp, textStyle = SlockType.ButtonBold.copy(fontSize = 11.sp, lineHeight = 12.sp))
                }
            )
        }
    }
}
