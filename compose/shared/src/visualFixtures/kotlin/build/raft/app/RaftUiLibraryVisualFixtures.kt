package build.raft.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import build.raft.app.components.SlockRaftUiAvatar
import build.raft.app.components.SlockRaftUiAvatarGroup
import build.raft.app.components.SlockRaftUiAvatarType
import build.raft.app.components.SlockRaftUiBadge
import build.raft.app.components.SlockRaftUiBadgeVariant
import build.raft.app.components.SlockRaftUiBanner
import build.raft.app.components.SlockRaftUiBannerSize
import build.raft.app.components.SlockRaftUiBannerStatus
import build.raft.app.components.SlockRaftUiButton
import build.raft.app.components.SlockRaftUiButtonVariant
import build.raft.app.components.SlockRaftUiCheckboxLabelRow
import build.raft.app.components.SlockRaftUiControlledMenu
import build.raft.app.components.SlockRaftUiControlledSelect
import build.raft.app.components.SlockRaftUiControlledSelectionPopover
import build.raft.app.components.SlockRaftUiEmptyState
import build.raft.app.components.SlockRaftUiField
import build.raft.app.components.SlockRaftUiInlineCode
import build.raft.app.components.SlockRaftUiInputGroup
import build.raft.app.components.SlockRaftUiKbd
import build.raft.app.components.SlockRaftUiLabel
import build.raft.app.components.SlockRaftUiMenuItem
import build.raft.app.components.SlockRaftUiRadioRow
import build.raft.app.components.SlockRaftUiSegmentedControl
import build.raft.app.components.SlockRaftUiSegmentedItem
import build.raft.app.components.SlockRaftUiSelectTrigger
import build.raft.app.components.SlockRaftUiSelectionOption
import build.raft.app.components.SlockRaftUiSpinner
import build.raft.app.components.SlockSpinnerSize
import build.raft.app.components.SlockRaftUiStatusDot
import build.raft.app.components.SlockRaftUiTextarea
import build.raft.app.components.SlockRaftUiMenuRow
import build.raft.app.components.SlockRaftUiMenuSurface
import build.raft.app.components.SlockRaftUiNotificationItem
import build.raft.app.components.SlockRaftUiPopupSurface
import build.raft.app.components.SlockRaftUiTabItem
import build.raft.app.components.SlockRaftUiTabs
import build.raft.app.components.SlockRaftUiTextHeading
import build.raft.app.components.SlockRaftUiTextMono
import build.raft.app.components.SlockRaftUiTextSans
import build.raft.app.components.SlockRaftUiSurfaceLabel
import build.raft.app.components.SlockRaftUiSurfaceStatus
import androidx.compose.runtime.Composable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun RaftUiBannerVisualContent() {
    Box(
        modifier =
            Modifier
                .width(342.dp)
                .height(176.dp)
                .background(SlockColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SlockRaftUiBanner(
                status = SlockRaftUiBannerStatus.Info,
                title = "Release gate ready",
                description = "Review the latest visual library diff before publishing.",
                iconName = SlockLucideIconName.Info,
                action = {
                    SlockRaftUiButton(label = "Open")
                }
            )
            SlockRaftUiBanner(
                status = SlockRaftUiBannerStatus.Warning,
                title = "Android capture stale",
                description = "Cloud phone artifact is older than React.",
                size = SlockRaftUiBannerSize.Small,
                iconName = SlockLucideIconName.TriangleAlert
            )
            SlockRaftUiBanner(
                status = SlockRaftUiBannerStatus.Destructive,
                description = "Provider failed to render one component.",
                size = SlockRaftUiBannerSize.Small
            )
        }
    }
}

@Composable
internal fun RaftUiEmptyStateVisualContent() {
    Box(
        modifier =
            Modifier
                .width(342.dp)
                .height(248.dp)
                .background(SlockColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .border(SlockStroke.Default, SlockColor.BorderBlack.copy(alpha = 0.30f))
        ) {
            SlockRaftUiEmptyState(
                title = "No visual changes",
                description = "Library fixtures are ready, but no reviewer has opened this run yet.",
                action = {
                    SlockRaftUiButton(label = "Create fixture", variant = SlockRaftUiButtonVariant.Primary)
                }
            )
        }
    }
}

@Composable
internal fun RaftUiTypographyVisualContent() {
    Box(
        modifier =
            Modifier
                .width(342.dp)
                .height(178.dp)
                .background(SlockColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SlockRaftUiTextHeading("Raft UI primitives")
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                SlockRaftUiTextSans("Compare")
                SlockRaftUiInlineCode("library.raft-ui")
                SlockRaftUiTextSans("cases")
            }
            SlockRaftUiTextMono("RGB 95.43% · pixel 38.07%")
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                SlockRaftUiKbd("⌘")
                SlockRaftUiKbd("K")
                SlockRaftUiKbd("Enter")
            }
        }
    }
}

@Composable
internal fun RaftUiFieldInputVisualContent() {
    Box(
        modifier =
            Modifier
                .width(342.dp)
                .height(236.dp)
                .background(SlockColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SlockRaftUiField(
                label = "Workspace",
                required = true,
                description = "Lowercase server slug used in routes."
            ) {
                SlockRaftUiInputGroup(value = "visual-testing")
            }
            SlockRaftUiField(
                label = "Invite URL",
                error = "Invite URL is required."
            ) {
                SlockRaftUiInputGroup(
                    value = "slock.ai/join/",
                    leadingText = "https://",
                    trailingText = "copy",
                    invalid = true
                )
            }
        }
    }
}

@Composable
internal fun RaftUiAvatarVisualContent() {
    Box(
        modifier =
            Modifier
                .width(342.dp)
                .height(116.dp)
                .background(SlockColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            SlockRaftUiAvatar(label = "AD", type = SlockRaftUiAvatarType.Agent, size = 64.dp, badge = true)
            SlockRaftUiAvatar(label = "AR", type = SlockRaftUiAvatarType.Human, size = 48.dp)
            SlockRaftUiAvatarGroup {
                SlockRaftUiAvatar(label = "C", type = SlockRaftUiAvatarType.Agent, size = 32.dp)
                SlockRaftUiAvatar(label = "P", type = SlockRaftUiAvatarType.Human, size = 32.dp)
                SlockRaftUiAvatar(label = "K", type = SlockRaftUiAvatarType.Agent, size = 32.dp)
                Box(
                    modifier =
                        Modifier
                            .width(32.dp)
                            .height(32.dp)
                            .background(SlockColor.SurfaceCream)
                            .border(SlockStroke.Default, SlockColor.BorderBlack),
                    contentAlignment = Alignment.Center
                ) {
                    Text("+4", style = SlockType.ButtonBold.copy(fontSize = 12.sp, lineHeight = 14.sp), color = SlockColor.TextBlack)
                }
            }
        }
    }
}

@Composable
internal fun RaftUiButtonBadgeVisualContent() {
    Box(
        modifier =
            Modifier
                .width(342.dp)
                .height(142.dp)
                .background(SlockColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SlockRaftUiButton(label = "Publish", variant = SlockRaftUiButtonVariant.Primary)
                SlockRaftUiButton(label = "Preview", variant = SlockRaftUiButtonVariant.Default)
                SlockRaftUiButton(label = "Delete", variant = SlockRaftUiButtonVariant.Danger)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SlockRaftUiButton(label = "More", variant = SlockRaftUiButtonVariant.Outline)
                SlockRaftUiButton(label = "Disabled", variant = SlockRaftUiButtonVariant.Muted, enabled = false)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SlockRaftUiBadge(label = "ready", variant = SlockRaftUiBadgeVariant.Success, uppercase = true)
                SlockRaftUiBadge(label = "3 updates", variant = SlockRaftUiBadgeVariant.Information)
                SlockRaftUiBadge(label = "queued", variant = SlockRaftUiBadgeVariant.Warning)
                SlockRaftUiBadge(label = "blocked", variant = SlockRaftUiBadgeVariant.Danger)
            }
        }
    }
}

@Composable
internal fun RaftUiCheckboxStatusVisualContent() {
    Box(
        modifier =
            Modifier
                .width(342.dp)
                .height(122.dp)
                .background(SlockColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                SlockRaftUiCheckboxLabelRow(label = "Enabled", checked = true)
                SlockRaftUiCheckboxLabelRow(label = "Partial", checked = true)
                SlockRaftUiCheckboxLabelRow(label = "Disabled", checked = false, enabled = false)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    SlockRaftUiStatusDot(color = SlockColor.AccentLime, size = 11.dp)
                    SlockRaftUiTextSans("Online")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    SlockRaftUiStatusDot(color = SlockColor.AccentOrange, size = 10.dp, pulsing = true)
                    SlockRaftUiTextSans("Pending")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    SlockRaftUiStatusDot(color = SlockColor.AccentRed, size = 8.dp)
                    SlockRaftUiTextSans("Failed")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    SlockRaftUiSpinner(size = SlockSpinnerSize.Md)
                    SlockRaftUiTextSans("Syncing")
                }
            }
        }
    }
}

@Composable
internal fun RaftUiTextareaVisualContent() {
    Box(
        modifier =
            Modifier
                .width(342.dp)
                .height(178.dp)
                .background(SlockColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        SlockRaftUiField(
            label = "Release note",
            required = true,
            error = "Keep this note under the publish limit."
        ) {
            SlockRaftUiTextarea(
                value = "Add library-level visual fixtures for the remaining stable Raft UI exports.",
                onValueChange = {},
                error = "",
                showCounter = true,
                limit = 72,
                fieldHeight = 92.dp,
                maxLines = 4
            )
        }
    }
}

@Composable
internal fun RaftUiTabsVisualContent() {
    Box(
        modifier =
            Modifier
                .width(342.dp)
                .height(144.dp)
                .background(SlockColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        SlockRaftUiTabs(
            items =
                listOf(
                    SlockRaftUiTabItem("product", "Product"),
                    SlockRaftUiTabItem("library", "Library"),
                    SlockRaftUiTabItem("components", "Components")
                ),
            selectedValue = "library",
            panelText = "25 active raft-ui library cases · no missing Android capture."
        )
    }
}

@Composable
internal fun RaftUiSegmentedControlVisualContent() {
    Box(
        modifier =
            Modifier
                .width(342.dp)
                .height(96.dp)
                .background(SlockColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        SlockRaftUiSegmentedControl(
            selectedValue = "mentions",
            items =
                listOf(
                    SlockRaftUiSegmentedItem(value = "all", label = "All", count = 24),
                    SlockRaftUiSegmentedItem(value = "mentions", label = "Mentions", count = 3),
                    SlockRaftUiSegmentedItem(value = "unread", label = "Unread", count = 9)
                )
        )
    }
}

@Composable
internal fun RaftUiLabelRadioVisualContent() {
    Box(
        modifier =
            Modifier
                .width(342.dp)
                .height(154.dp)
                .background(SlockColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SlockRaftUiLabel(text = "Notification level", required = true)
                SlockRaftUiLabel(text = "Archive notes", optional = true, small = true)
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SlockRaftUiRadioRow(label = "All activity", selected = false)
                SlockRaftUiRadioRow(label = "Mentions only", selected = true)
                SlockRaftUiRadioRow(label = "Muted", selected = false, enabled = false)
            }
        }
    }
}

@Composable
internal fun RaftUiSelectVisualContent() {
    Box(
        modifier =
            Modifier
                .width(342.dp)
                .height(148.dp)
                .background(SlockColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SlockRaftUiSelectTrigger(label = "Raft UI Library")
            SlockRaftUiSelectTrigger(label = "Components Real Diff", enabled = false)
        }
    }
}

@Composable
internal fun RaftUiMenuSurfacesVisualContent() {
    Box(
        modifier =
            Modifier
                .width(342.dp)
                .height(210.dp)
                .background(SlockColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        SlockRaftUiMenuSurface(
            items =
                listOf(
                    SlockRaftUiMenuItem(id = "publish", label = "Publish report", trailing = "⌘ P"),
                    SlockRaftUiMenuItem(id = "review", label = "Review differences", trailing = "32"),
                    SlockRaftUiMenuItem(id = "archive", label = "Archive run", trailing = "⇧ A", enabled = false),
                    SlockRaftUiMenuItem(id = "move", label = "Move to components", trailing = "›", dividerBefore = true)
                ),
            width = 310.dp,
            rowHeight = 36.dp,
            shadowOffset = 3.dp,
            showDividers = false
        )
    }
}

@Composable
internal fun RaftUiPopoverSurfaceVisualContent() {
    Box(
        modifier =
            Modifier
                .width(342.dp)
                .height(174.dp)
                .background(SlockColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        SlockRaftUiPopupSurface(modifier = Modifier.width(310.dp)) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                SlockRaftUiTextMono("CAPTURE CONTRACT")
                SlockRaftUiTextMono("CLOSE")
            }
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(SlockColor.BorderBlack))
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SlockRaftUiLabel(text = "Portal content is deterministic")
                SlockRaftUiTextSans("Static popup surfaces render without relying on interaction timing.")
            }
        }
    }
}

@Composable
internal fun RaftUiComboboxSurfaceVisualContent() {
    Box(
        modifier =
            Modifier
                .width(342.dp)
                .height(258.dp)
                .background(SlockColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SlockRaftUiPopupSurface(modifier = Modifier.width(310.dp)) {
                SlockRaftUiSurfaceLabel("Combobox results")
                SlockRaftUiMenuRow(label = "Raft UI Library")
                SlockRaftUiMenuRow(label = "Components Real Diff", highlighted = true)
                SlockRaftUiMenuRow(label = "Archived runs", disabled = true)
            }
            SlockRaftUiPopupSurface(modifier = Modifier.width(310.dp)) {
                SlockRaftUiSurfaceLabel("Select run")
                SlockRaftUiMenuRow(label = "Latest Android capture", highlighted = true)
                SlockRaftUiMenuRow(label = "Previous baseline")
            }
        }
    }
}

@Composable
internal fun RaftUiNotificationCenterVisualContent() {
    Box(
        modifier =
            Modifier
                .width(342.dp)
                .height(294.dp)
                .background(SlockColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        SlockRaftUiPopupSurface(modifier = Modifier.width(310.dp).height(262.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().background(SlockColor.SurfaceCream).border(SlockStroke.Thin, SlockColor.BorderBlack).padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SlockRaftUiTextMono("NOTIFICATIONS")
                SlockRaftUiTextMono("3 unread")
            }
            SlockRaftUiNotificationItem(
                title = "Raft UI library run finished",
                body = "32 active library cases are ready for review.",
                status = SlockRaftUiSurfaceStatus.Success,
                actionLabel = "Open"
            )
            SlockRaftUiNotificationItem(
                title = "Components diff has one missing case",
                body = "Pending Android fixture remains excluded from this batch.",
                status = SlockRaftUiSurfaceStatus.Warning,
                read = true
            )
        }
    }
}

@Composable
internal fun RaftUiInteractionStatesVisualContent() {
    Box(
        modifier =
            Modifier
                .width(342.dp)
                .height(424.dp)
                .background(SlockColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SlockRaftUiButton(label = "Resting", modifier = Modifier.weight(1f), variant = SlockRaftUiButtonVariant.Primary)
                SlockRaftUiButton(
                    label = "Pressed",
                    modifier = Modifier.weight(1f).offset(x = 1.dp, y = 1.dp),
                    variant = SlockRaftUiButtonVariant.Primary
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(modifier = Modifier.weight(1f).border(SlockStroke.Default, SlockColor.BorderBlack).padding(2.dp)) {
                    SlockRaftUiButton(label = "Focused", modifier = Modifier.fillMaxWidth(), variant = SlockRaftUiButtonVariant.Outline)
                }
                SlockRaftUiButton(label = "Disabled", modifier = Modifier.weight(1f), variant = SlockRaftUiButtonVariant.Muted, enabled = false)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SlockRaftUiSelectTrigger(label = "Library", modifier = Modifier.width(150.dp), expanded = true)
                SlockRaftUiButton(label = "Actions", variant = SlockRaftUiButtonVariant.Outline)
            }
            SlockRaftUiPopupSurface(modifier = Modifier.width(310.dp)) {
                SlockRaftUiSurfaceLabel("Menu states", modifier = Modifier.height(24.dp))
                SlockRaftUiMenuRow(label = "Default row", trailing = "⌘ K")
                SlockRaftUiMenuRow(label = "Highlighted row", trailing = "4", highlighted = true)
                SlockRaftUiMenuRow(label = "Selected row", trailing = "✓", highlighted = true)
                SlockRaftUiMenuRow(label = "Disabled row", trailing = "⇧ D", disabled = true)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Box(
                    modifier =
                        Modifier
                            .width(112.dp)
                            .background(SlockColor.SurfaceWhite)
                            .border(SlockStroke.Default, SlockColor.BorderBlack)
                            .padding(horizontal = 10.dp, vertical = 7.dp)
                ) {
                    Text(
                        text = "Tooltip open",
                        style = SlockType.ButtonBold.copy(fontSize = 12.sp, lineHeight = 14.sp),
                        color = SlockColor.TextBlack,
                        maxLines = 1
                    )
                }
            }
            SlockRaftUiPopupSurface(modifier = Modifier.width(310.dp)) {
                Row(modifier = Modifier.fillMaxWidth().height(30.dp).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    SlockRaftUiTextMono("POPOVER OPEN")
                    SlockRaftUiTextMono("ACTIVE")
                }
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(SlockColor.BorderBlack))
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    SlockRaftUiTextSans("Fixed open, highlighted, selected, pressed, focus, and disabled states.")
                }
            }
        }
    }
}

@Composable
internal fun RaftUiControlledOpenOverlaysVisualContent() {
    val dropdownRef = remember { SlockLiveRootAnchorRef(identity = "raft_ui_fixture:dropdown") }
    val popoverRef = remember { SlockLiveRootAnchorRef(identity = "raft_ui_fixture:popover") }
    val selectRef = remember { SlockLiveRootAnchorRef(identity = "raft_ui_fixture:select") }
    val emptySelectRef = remember { SlockLiveRootAnchorRef(identity = "raft_ui_fixture:empty_select") }
    var dropdownAnchor by remember { mutableStateOf<SlockPopoverAnchor?>(null) }
    var popoverAnchor by remember { mutableStateOf<SlockPopoverAnchor?>(null) }
    var selectAnchor by remember { mutableStateOf<SlockPopoverAnchor?>(null) }
    var emptySelectAnchor by remember { mutableStateOf<SlockPopoverAnchor?>(null) }
    Box(
        modifier =
            Modifier
                .width(390.dp)
                .height(760.dp)
                .background(SlockColor.SurfaceWhite)
    ) {
        SlockRaftUiButton(
            label = "Dropdown open",
            modifier =
                Modifier
                    .offset(x = 16.dp, y = 16.dp)
                    .width(170.dp)
                    .slockCaptureLiveRootAnchor(dropdownRef) {
                        dropdownAnchor = dropdownRef.resolve(SlockPopoverLocalPoint.BottomStart)
                    },
            variant = SlockRaftUiButtonVariant.Outline
        )
        dropdownAnchor?.let { anchor ->
            SlockRaftUiControlledMenu(
                expanded = true,
                anchor = anchor,
                items =
                    listOf(
                        SlockRaftUiMenuItem(id = "publish", label = "Publish page", trailing = "⌘ P"),
                        SlockRaftUiMenuItem(id = "history", label = "Keep history", selected = true),
                        SlockRaftUiMenuItem(id = "mobile", label = "Mobile report", selected = true),
                        SlockRaftUiMenuItem(id = "delete", label = "Delete run", destructive = true, enabled = false)
                    ),
                onSelect = {},
                onDismiss = {},
                width = 170.dp
            )
        }

        SlockRaftUiButton(
            label = "Popover open",
            modifier =
                Modifier
                    .offset(x = 204.dp, y = 16.dp)
                    .width(170.dp)
                    .slockCaptureLiveRootAnchor(popoverRef) {
                        popoverAnchor = popoverRef.resolve(SlockPopoverLocalPoint.BottomStart)
                    },
            variant = SlockRaftUiButtonVariant.Outline
        )
        popoverAnchor?.let { anchor ->
        SlockRaftUiControlledSelectionPopover(
            expanded = true,
            anchor = anchor,
            title = "Review gate",
            options =
                listOf(
                    SlockRaftUiSelectionOption(id = "ready", label = "Ready for review", selected = true),
                    SlockRaftUiSelectionOption(id = "blocked", label = "Blocked", enabled = false),
                    SlockRaftUiSelectionOption(id = "draft", label = "Draft")
                ),
            selectedId = "ready",
            search = "review",
            showClear = true,
            onClear = {},
            onSelect = {},
            onDismiss = {},
            width = 170.dp,
            verticalOffset = 16.dp
        )
        }

        SlockRaftUiButton(
            label = "Tooltip trigger",
            modifier = Modifier.offset(x = 204.dp, y = 220.dp).width(170.dp),
            variant = SlockRaftUiButtonVariant.Outline
        )
        Box(
            modifier =
                Modifier
                    .offset(x = 204.dp, y = 258.dp)
                    .width(170.dp)
                    .background(SlockColor.SurfaceWhite)
                    .border(SlockStroke.Default, SlockColor.BorderBlack)
                    .padding(horizontal = 10.dp, vertical = 7.dp)
        ) {
            SlockRaftUiTextSans("Real Tooltip open state.")
        }

        Box(
            modifier =
                Modifier
                    .offset(x = 16.dp, y = 300.dp)
                    .width(170.dp)
                    .height(72.dp)
                    .background(SlockColor.SurfaceWhite)
                    .border(SlockStroke.Default, SlockColor.BorderBlack),
            contentAlignment = Alignment.Center
        ) {
            SlockRaftUiLabel(text = "Context target")
        }
        SlockRaftUiPopupSurface(modifier = Modifier.offset(x = 16.dp, y = 378.dp).width(180.dp)) {
            SlockRaftUiSurfaceLabel("Message menu")
            SlockRaftUiMenuRow("Reply in thread", trailing = "R", highlighted = true)
            SlockRaftUiMenuRow("Follow thread", trailing = "✓")
            SlockRaftUiMenuRow("Archive", disabled = true)
        }

        selectAnchor?.let { anchor -> SlockRaftUiControlledSelect(
            expanded = true,
            anchor = anchor,
            options =
                listOf(
                    SlockRaftUiSelectionOption(id = "library", label = "Raft UI Library", selected = true),
                    SlockRaftUiSelectionOption(id = "components", label = "Components Real Diff"),
                    SlockRaftUiSelectionOption(id = "product", label = "Product Visual Diff", enabled = false)
                ),
            selectedId = "library",
            title = "Report scope",
            modifier =
                Modifier
                    .offset(x = 204.dp, y = 456.dp)
                    .width(170.dp)
                    .slockCaptureLiveRootAnchor(selectRef) {
                        selectAnchor = selectRef.resolve(SlockPopoverLocalPoint.BottomStart)
                    },
            triggerHeight = 32.dp,
            menuWidth = 170.dp,
            onSelect = {},
            onDismiss = {},
            onTriggerClick = {}
        ) }

        if (selectAnchor == null) {
            Box(
                Modifier
                    .offset(x = 204.dp, y = 456.dp)
                    .width(170.dp)
                    .height(32.dp)
                    .slockCaptureLiveRootAnchor(selectRef) {
                        selectAnchor = selectRef.resolve(SlockPopoverLocalPoint.BottomStart)
                    }
            )
        }

        emptySelectAnchor?.let { anchor -> SlockRaftUiControlledSelect(
            expanded = true,
            anchor = anchor,
            options = emptyList(),
            selectedId = "",
            selectedLabel = "Android capture",
            title = "Matching reports",
            modifier =
                Modifier
                    .offset(x = 16.dp, y = 590.dp)
                    .width(170.dp)
                    .slockCaptureLiveRootAnchor(emptySelectRef) {
                        emptySelectAnchor = emptySelectRef.resolve(SlockPopoverLocalPoint.BottomStart)
                    },
            triggerHeight = 32.dp,
            menuWidth = 220.dp,
            search = "and",
            emptyLabel = "No reports match",
            showClear = true,
            onClear = {},
            onSelect = {},
            onDismiss = {},
            onTriggerClick = {}
        ) }
        if (emptySelectAnchor == null) {
            Box(
                Modifier
                    .offset(x = 16.dp, y = 590.dp)
                    .width(170.dp)
                    .height(32.dp)
                    .slockCaptureLiveRootAnchor(emptySelectRef) {
                        emptySelectAnchor = emptySelectRef.resolve(SlockPopoverLocalPoint.BottomStart)
                    }
            )
        }
    }
}

@Composable
internal fun RaftUiWipToggleGroupVisualContent() {
    Box(
        modifier =
            Modifier
                .width(342.dp)
                .height(112.dp)
                .background(SlockColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        SlockRaftUiSegmentedControl(
            selectedValue = "wip",
            items =
                listOf(
                    SlockRaftUiSegmentedItem(value = "stable", label = "Stable", count = 36),
                    SlockRaftUiSegmentedItem(value = "wip", label = "WIP", count = 12),
                    SlockRaftUiSegmentedItem(value = "open", label = "Open states", count = 6)
                )
        )
    }
}

@Composable
internal fun RaftUiWipListPanelsVisualContent() {
    Box(
        modifier =
            Modifier
                .width(342.dp)
                .height(388.dp)
                .background(SlockColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SlockRaftUiPopupSurface(modifier = Modifier.width(310.dp)) {
                Row(modifier = Modifier.fillMaxWidth().background(SlockColor.SurfaceWhite).border(SlockStroke.Thin, SlockColor.BorderBlack).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.width(36.dp).height(36.dp).background(SlockColor.BrandYellow).border(SlockStroke.Default, SlockColor.BorderBlack), contentAlignment = Alignment.Center) {
                        Text("V", style = SlockType.ButtonBold.copy(fontSize = 14.sp, lineHeight = 16.sp), color = SlockColor.TextBlack)
                    }
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        SlockRaftUiLabel(text = "Visual surfaces")
                        SlockRaftUiTextMono("raft-ui/wip package exports")
                    }
                    SlockRaftUiBadge(label = "12", variant = SlockRaftUiBadgeVariant.Information)
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                SlockRaftUiSurfaceLabel(text = "WIP components", modifier = Modifier.width(180.dp))
                SlockRaftUiTextMono("active")
            }
            SlockRaftUiNotificationItem(
                title = "Media list item",
                body = "fixture-ready · selected",
                status = SlockRaftUiSurfaceStatus.Info,
                actionLabel = "new"
            )
            SlockRaftUiMenuRow(label = "List item with metadata", trailing = "RGB 88.57%", highlighted = true)
            SlockRaftUiPopupSurface(modifier = Modifier.width(310.dp)) {
                SlockRaftUiMenuRow(label = "Preview shell muted state", disabled = true)
                Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    Column { SlockRaftUiTextMono("Provider"); SlockRaftUiLabel(text = "Android", small = true) }
                    Column { SlockRaftUiTextMono("Surface"); SlockRaftUiLabel(text = "library", small = true) }
                }
            }
        }
    }
}

@Composable
internal fun RaftUiWipMessageCardsVisualContent() {
    Box(
        modifier =
            Modifier
                .width(342.dp)
                .height(250.dp)
                .background(SlockColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SlockRaftUiPopupSurface(modifier = Modifier.width(310.dp)) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        SlockRaftUiTextMono("#visual-testing")
                        SlockRaftUiTextMono("·")
                        SlockRaftUiLabel(text = "Android-Developer-4", small = true)
                        SlockRaftUiTextMono("02:20")
                    }
                    SlockRaftUiTextSans("Added deterministic package-surface cases before moving to WIP exports.")
                    SlockRaftUiBadge(label = "Thread", variant = SlockRaftUiBadgeVariant.Information)
                }
            }
            SlockRaftUiPopupSurface(modifier = Modifier.width(310.dp)) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        SlockRaftUiTextMono("#raft-ui")
                        SlockRaftUiLabel(text = "artin", small = true)
                        SlockRaftUiTextMono("now")
                    }
                    SlockRaftUiTextSans("artin: 做完部署一版")
                    SlockRaftUiTextMono("WIP package exports are being added as library rows.")
                }
            }
        }
    }
}

@Composable
internal fun RaftUiWipOverlayShellsVisualContent() {
    Box(
        modifier =
            Modifier
                .width(342.dp)
                .height(284.dp)
                .background(SlockColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SlockRaftUiPopupSurface(modifier = Modifier.width(310.dp)) {
                Row(modifier = Modifier.fillMaxWidth().background(SlockColor.SurfaceWhite).border(SlockStroke.Thin, SlockColor.BorderBlack).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    SlockRaftUiLabel(text = "Lightbox shell")
                    SlockRaftUiBadge(label = "Esc", variant = SlockRaftUiBadgeVariant.Information)
                }
                Box(modifier = Modifier.fillMaxWidth().height(86.dp).background(SlockColor.BorderBlack.copy(alpha = 0.05f)), contentAlignment = Alignment.Center) {
                    Box(modifier = Modifier.width(192.dp).height(56.dp).background(SlockColor.SurfaceWhite).border(SlockStroke.Default, SlockColor.BorderBlack), contentAlignment = Alignment.Center) {
                        SlockRaftUiLabel(text = "Screenshot preview", small = true)
                    }
                }
            }
            SlockRaftUiPopupSurface(modifier = Modifier.width(310.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SlockRaftUiLabel(text = "Bottom sheet shell")
                    SlockRaftUiTextSans("Static slot capture for mobile actions.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SlockRaftUiButton(label = "Confirm", variant = SlockRaftUiButtonVariant.Primary)
                        SlockRaftUiButton(label = "Cancel", variant = SlockRaftUiButtonVariant.Outline)
                    }
                }
            }
        }
    }
}
