package build.raft.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import build.raft.app.components.RaftUiAvatar
import build.raft.app.components.RaftUiAvatarGroup
import build.raft.app.components.RaftUiAvatarType
import build.raft.app.components.RaftUiBadge
import build.raft.app.components.RaftUiBadgeVariant
import build.raft.app.components.RaftUiBanner
import build.raft.app.components.RaftUiBannerSize
import build.raft.app.components.RaftUiBannerStatus
import build.raft.app.components.RaftUiButton
import build.raft.app.components.RaftUiButtonVariant
import build.raft.app.components.RaftUiCheckboxLabelRow
import build.raft.app.components.RaftUiControlledMenu
import build.raft.app.components.RaftUiControlledSelect
import build.raft.app.components.RaftUiControlledSelectionPopover
import build.raft.app.components.RaftUiEmptyState
import build.raft.app.components.RaftUiField
import build.raft.app.components.RaftUiInlineCode
import build.raft.app.components.RaftUiInputGroup
import build.raft.app.components.RaftUiKbd
import build.raft.app.components.RaftUiLabel
import build.raft.app.components.RaftUiMenuItem
import build.raft.app.components.RaftUiRadioRow
import build.raft.app.components.RaftUiSegmentedControl
import build.raft.app.components.RaftUiSegmentedItem
import build.raft.app.components.RaftUiSelectTrigger
import build.raft.app.components.RaftUiSelectionOption
import build.raft.app.components.RaftUiSpinner
import build.raft.app.components.RaftAppSpinnerSize
import build.raft.app.components.RaftUiStatusDot
import build.raft.app.components.RaftUiTextarea
import build.raft.app.components.RaftUiMenuRow
import build.raft.app.components.RaftUiMenuSurface
import build.raft.app.components.RaftUiNotificationItem
import build.raft.app.components.RaftUiPopupSurface
import build.raft.app.components.RaftUiTabItem
import build.raft.app.components.RaftUiTabs
import build.raft.app.components.RaftUiTextHeading
import build.raft.app.components.RaftUiTextMono
import build.raft.app.components.RaftUiTextSans
import build.raft.app.components.RaftUiSurfaceLabel
import build.raft.app.components.RaftUiSurfaceStatus
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
                .background(RaftColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RaftUiBanner(
                status = RaftUiBannerStatus.Info,
                title = "Release gate ready",
                description = "Review the latest visual library diff before publishing.",
                iconName = RaftLucideIconName.Info,
                action = {
                    RaftUiButton(label = "Open")
                }
            )
            RaftUiBanner(
                status = RaftUiBannerStatus.Warning,
                title = "Android capture stale",
                description = "Cloud phone artifact is older than React.",
                size = RaftUiBannerSize.Small,
                iconName = RaftLucideIconName.TriangleAlert
            )
            RaftUiBanner(
                status = RaftUiBannerStatus.Destructive,
                description = "Provider failed to render one component.",
                size = RaftUiBannerSize.Small
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
                .background(RaftColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .border(RaftAppStroke.Default, RaftColor.BorderBlack.copy(alpha = 0.30f))
        ) {
            RaftUiEmptyState(
                title = "No visual changes",
                description = "Library fixtures are ready, but no reviewer has opened this run yet.",
                action = {
                    RaftUiButton(label = "Create fixture", variant = RaftUiButtonVariant.Primary)
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
                .background(RaftColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RaftUiTextHeading("Raft UI primitives")
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                RaftUiTextSans("Compare")
                RaftUiInlineCode("library.raft-ui")
                RaftUiTextSans("cases")
            }
            RaftUiTextMono("RGB 95.43% · pixel 38.07%")
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                RaftUiKbd("⌘")
                RaftUiKbd("K")
                RaftUiKbd("Enter")
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
                .background(RaftColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            RaftUiField(
                label = "Workspace",
                required = true,
                description = "Lowercase server slug used in routes."
            ) {
                RaftUiInputGroup(value = "visual-testing")
            }
            RaftUiField(
                label = "Invite URL",
                error = "Invite URL is required."
            ) {
                RaftUiInputGroup(
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
                .background(RaftColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            RaftUiAvatar(label = "AD", type = RaftUiAvatarType.Agent, size = 64.dp, badge = true)
            RaftUiAvatar(label = "AR", type = RaftUiAvatarType.Human, size = 48.dp)
            RaftUiAvatarGroup {
                RaftUiAvatar(label = "C", type = RaftUiAvatarType.Agent, size = 32.dp)
                RaftUiAvatar(label = "P", type = RaftUiAvatarType.Human, size = 32.dp)
                RaftUiAvatar(label = "K", type = RaftUiAvatarType.Agent, size = 32.dp)
                Box(
                    modifier =
                        Modifier
                            .width(32.dp)
                            .height(32.dp)
                            .background(RaftColor.SurfaceCream)
                            .border(RaftAppStroke.Default, RaftColor.BorderBlack),
                    contentAlignment = Alignment.Center
                ) {
                    Text("+4", style = RaftType.ButtonBold.copy(fontSize = 12.sp, lineHeight = 14.sp), color = RaftColor.TextBlack)
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
                .background(RaftColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                RaftUiButton(label = "Publish", variant = RaftUiButtonVariant.Primary)
                RaftUiButton(label = "Preview", variant = RaftUiButtonVariant.Default)
                RaftUiButton(label = "Delete", variant = RaftUiButtonVariant.Danger)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                RaftUiButton(label = "More", variant = RaftUiButtonVariant.Outline)
                RaftUiButton(label = "Disabled", variant = RaftUiButtonVariant.Muted, enabled = false)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                RaftUiBadge(label = "ready", variant = RaftUiBadgeVariant.Success, uppercase = true)
                RaftUiBadge(label = "3 updates", variant = RaftUiBadgeVariant.Information)
                RaftUiBadge(label = "queued", variant = RaftUiBadgeVariant.Warning)
                RaftUiBadge(label = "blocked", variant = RaftUiBadgeVariant.Danger)
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
                .background(RaftColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                RaftUiCheckboxLabelRow(label = "Enabled", checked = true)
                RaftUiCheckboxLabelRow(label = "Partial", checked = true)
                RaftUiCheckboxLabelRow(label = "Disabled", checked = false, enabled = false)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    RaftUiStatusDot(color = RaftColor.AccentLime, size = 11.dp)
                    RaftUiTextSans("Online")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    RaftUiStatusDot(color = RaftColor.AccentOrange, size = 10.dp, pulsing = true)
                    RaftUiTextSans("Pending")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    RaftUiStatusDot(color = RaftColor.AccentRed, size = 8.dp)
                    RaftUiTextSans("Failed")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    RaftUiSpinner(size = RaftAppSpinnerSize.Md)
                    RaftUiTextSans("Syncing")
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
                .background(RaftColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        RaftUiField(
            label = "Release note",
            required = true,
            error = "Keep this note under the publish limit."
        ) {
            RaftUiTextarea(
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
                .background(RaftColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        RaftUiTabs(
            items =
                listOf(
                    RaftUiTabItem("product", "Product"),
                    RaftUiTabItem("library", "Library"),
                    RaftUiTabItem("components", "Components")
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
                .background(RaftColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        RaftUiSegmentedControl(
            selectedValue = "mentions",
            items =
                listOf(
                    RaftUiSegmentedItem(value = "all", label = "All", count = 24),
                    RaftUiSegmentedItem(value = "mentions", label = "Mentions", count = 3),
                    RaftUiSegmentedItem(value = "unread", label = "Unread", count = 9)
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
                .background(RaftColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RaftUiLabel(text = "Notification level", required = true)
                RaftUiLabel(text = "Archive notes", optional = true, small = true)
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RaftUiRadioRow(label = "All activity", selected = false)
                RaftUiRadioRow(label = "Mentions only", selected = true)
                RaftUiRadioRow(label = "Muted", selected = false, enabled = false)
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
                .background(RaftColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            RaftUiSelectTrigger(label = "Raft UI Library")
            RaftUiSelectTrigger(label = "Components Real Diff", enabled = false)
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
                .background(RaftColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        RaftUiMenuSurface(
            items =
                listOf(
                    RaftUiMenuItem(id = "publish", label = "Publish report", trailing = "⌘ P"),
                    RaftUiMenuItem(id = "review", label = "Review differences", trailing = "32"),
                    RaftUiMenuItem(id = "archive", label = "Archive run", trailing = "⇧ A", enabled = false),
                    RaftUiMenuItem(id = "move", label = "Move to components", trailing = "›", dividerBefore = true)
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
                .background(RaftColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        RaftUiPopupSurface(modifier = Modifier.width(310.dp)) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                RaftUiTextMono("CAPTURE CONTRACT")
                RaftUiTextMono("CLOSE")
            }
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(RaftColor.BorderBlack))
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                RaftUiLabel(text = "Portal content is deterministic")
                RaftUiTextSans("Static popup surfaces render without relying on interaction timing.")
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
                .background(RaftColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            RaftUiPopupSurface(modifier = Modifier.width(310.dp)) {
                RaftUiSurfaceLabel("Combobox results")
                RaftUiMenuRow(label = "Raft UI Library")
                RaftUiMenuRow(label = "Components Real Diff", highlighted = true)
                RaftUiMenuRow(label = "Archived runs", disabled = true)
            }
            RaftUiPopupSurface(modifier = Modifier.width(310.dp)) {
                RaftUiSurfaceLabel("Select run")
                RaftUiMenuRow(label = "Latest Android capture", highlighted = true)
                RaftUiMenuRow(label = "Previous baseline")
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
                .background(RaftColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        RaftUiPopupSurface(modifier = Modifier.width(310.dp).height(262.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().background(RaftColor.SurfaceCream).border(RaftAppStroke.Thin, RaftColor.BorderBlack).padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                RaftUiTextMono("NOTIFICATIONS")
                RaftUiTextMono("3 unread")
            }
            RaftUiNotificationItem(
                title = "Raft UI library run finished",
                body = "32 active library cases are ready for review.",
                status = RaftUiSurfaceStatus.Success,
                actionLabel = "Open"
            )
            RaftUiNotificationItem(
                title = "Components diff has one missing case",
                body = "Pending Android fixture remains excluded from this batch.",
                status = RaftUiSurfaceStatus.Warning,
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
                .background(RaftColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RaftUiButton(label = "Resting", modifier = Modifier.weight(1f), variant = RaftUiButtonVariant.Primary)
                RaftUiButton(
                    label = "Pressed",
                    modifier = Modifier.weight(1f).offset(x = 1.dp, y = 1.dp),
                    variant = RaftUiButtonVariant.Primary
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(modifier = Modifier.weight(1f).border(RaftAppStroke.Default, RaftColor.BorderBlack).padding(2.dp)) {
                    RaftUiButton(label = "Focused", modifier = Modifier.fillMaxWidth(), variant = RaftUiButtonVariant.Outline)
                }
                RaftUiButton(label = "Disabled", modifier = Modifier.weight(1f), variant = RaftUiButtonVariant.Muted, enabled = false)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RaftUiSelectTrigger(label = "Library", modifier = Modifier.width(150.dp), expanded = true)
                RaftUiButton(label = "Actions", variant = RaftUiButtonVariant.Outline)
            }
            RaftUiPopupSurface(modifier = Modifier.width(310.dp)) {
                RaftUiSurfaceLabel("Menu states", modifier = Modifier.height(24.dp))
                RaftUiMenuRow(label = "Default row", trailing = "⌘ K")
                RaftUiMenuRow(label = "Highlighted row", trailing = "4", highlighted = true)
                RaftUiMenuRow(label = "Selected row", trailing = "✓", highlighted = true)
                RaftUiMenuRow(label = "Disabled row", trailing = "⇧ D", disabled = true)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Box(
                    modifier =
                        Modifier
                            .width(112.dp)
                            .background(RaftColor.SurfaceWhite)
                            .border(RaftAppStroke.Default, RaftColor.BorderBlack)
                            .padding(horizontal = 10.dp, vertical = 7.dp)
                ) {
                    Text(
                        text = "Tooltip open",
                        style = RaftType.ButtonBold.copy(fontSize = 12.sp, lineHeight = 14.sp),
                        color = RaftColor.TextBlack,
                        maxLines = 1
                    )
                }
            }
            RaftUiPopupSurface(modifier = Modifier.width(310.dp)) {
                Row(modifier = Modifier.fillMaxWidth().height(30.dp).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    RaftUiTextMono("POPOVER OPEN")
                    RaftUiTextMono("ACTIVE")
                }
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(RaftColor.BorderBlack))
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    RaftUiTextSans("Fixed open, highlighted, selected, pressed, focus, and disabled states.")
                }
            }
        }
    }
}

@Composable
internal fun RaftUiControlledOpenOverlaysVisualContent() {
    val dropdownRef = remember { RaftLiveRootAnchorRef(identity = "raft_ui_fixture:dropdown") }
    val popoverRef = remember { RaftLiveRootAnchorRef(identity = "raft_ui_fixture:popover") }
    val selectRef = remember { RaftLiveRootAnchorRef(identity = "raft_ui_fixture:select") }
    val emptySelectRef = remember { RaftLiveRootAnchorRef(identity = "raft_ui_fixture:empty_select") }
    var dropdownAnchor by remember { mutableStateOf<RaftPopoverAnchor?>(null) }
    var popoverAnchor by remember { mutableStateOf<RaftPopoverAnchor?>(null) }
    var selectAnchor by remember { mutableStateOf<RaftPopoverAnchor?>(null) }
    var emptySelectAnchor by remember { mutableStateOf<RaftPopoverAnchor?>(null) }
    Box(
        modifier =
            Modifier
                .width(390.dp)
                .height(760.dp)
                .background(RaftColor.SurfaceWhite)
    ) {
        RaftUiButton(
            label = "Dropdown open",
            modifier =
                Modifier
                    .offset(x = 16.dp, y = 16.dp)
                    .width(170.dp)
                    .raftCaptureLiveRootAnchor(dropdownRef) {
                        dropdownAnchor = dropdownRef.resolve(RaftPopoverLocalPoint.BottomStart)
                    },
            variant = RaftUiButtonVariant.Outline
        )
        dropdownAnchor?.let { anchor ->
            RaftUiControlledMenu(
                expanded = true,
                anchor = anchor,
                items =
                    listOf(
                        RaftUiMenuItem(id = "publish", label = "Publish page", trailing = "⌘ P"),
                        RaftUiMenuItem(id = "history", label = "Keep history", selected = true),
                        RaftUiMenuItem(id = "mobile", label = "Mobile report", selected = true),
                        RaftUiMenuItem(id = "delete", label = "Delete run", destructive = true, enabled = false)
                    ),
                onSelect = {},
                onDismiss = {},
                width = 170.dp
            )
        }

        RaftUiButton(
            label = "Popover open",
            modifier =
                Modifier
                    .offset(x = 204.dp, y = 16.dp)
                    .width(170.dp)
                    .raftCaptureLiveRootAnchor(popoverRef) {
                        popoverAnchor = popoverRef.resolve(RaftPopoverLocalPoint.BottomStart)
                    },
            variant = RaftUiButtonVariant.Outline
        )
        popoverAnchor?.let { anchor ->
        RaftUiControlledSelectionPopover(
            expanded = true,
            anchor = anchor,
            title = "Review gate",
            options =
                listOf(
                    RaftUiSelectionOption(id = "ready", label = "Ready for review", selected = true),
                    RaftUiSelectionOption(id = "blocked", label = "Blocked", enabled = false),
                    RaftUiSelectionOption(id = "draft", label = "Draft")
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

        RaftUiButton(
            label = "Tooltip trigger",
            modifier = Modifier.offset(x = 204.dp, y = 220.dp).width(170.dp),
            variant = RaftUiButtonVariant.Outline
        )
        Box(
            modifier =
                Modifier
                    .offset(x = 204.dp, y = 258.dp)
                    .width(170.dp)
                    .background(RaftColor.SurfaceWhite)
                    .border(RaftAppStroke.Default, RaftColor.BorderBlack)
                    .padding(horizontal = 10.dp, vertical = 7.dp)
        ) {
            RaftUiTextSans("Real Tooltip open state.")
        }

        Box(
            modifier =
                Modifier
                    .offset(x = 16.dp, y = 300.dp)
                    .width(170.dp)
                    .height(72.dp)
                    .background(RaftColor.SurfaceWhite)
                    .border(RaftAppStroke.Default, RaftColor.BorderBlack),
            contentAlignment = Alignment.Center
        ) {
            RaftUiLabel(text = "Context target")
        }
        RaftUiPopupSurface(modifier = Modifier.offset(x = 16.dp, y = 378.dp).width(180.dp)) {
            RaftUiSurfaceLabel("Message menu")
            RaftUiMenuRow("Reply in thread", trailing = "R", highlighted = true)
            RaftUiMenuRow("Follow thread", trailing = "✓")
            RaftUiMenuRow("Archive", disabled = true)
        }

        selectAnchor?.let { anchor -> RaftUiControlledSelect(
            expanded = true,
            anchor = anchor,
            options =
                listOf(
                    RaftUiSelectionOption(id = "library", label = "Raft UI Library", selected = true),
                    RaftUiSelectionOption(id = "components", label = "Components Real Diff"),
                    RaftUiSelectionOption(id = "product", label = "Product Visual Diff", enabled = false)
                ),
            selectedId = "library",
            title = "Report scope",
            modifier =
                Modifier
                    .offset(x = 204.dp, y = 456.dp)
                    .width(170.dp)
                    .raftCaptureLiveRootAnchor(selectRef) {
                        selectAnchor = selectRef.resolve(RaftPopoverLocalPoint.BottomStart)
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
                    .raftCaptureLiveRootAnchor(selectRef) {
                        selectAnchor = selectRef.resolve(RaftPopoverLocalPoint.BottomStart)
                    }
            )
        }

        emptySelectAnchor?.let { anchor -> RaftUiControlledSelect(
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
                    .raftCaptureLiveRootAnchor(emptySelectRef) {
                        emptySelectAnchor = emptySelectRef.resolve(RaftPopoverLocalPoint.BottomStart)
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
                    .raftCaptureLiveRootAnchor(emptySelectRef) {
                        emptySelectAnchor = emptySelectRef.resolve(RaftPopoverLocalPoint.BottomStart)
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
                .background(RaftColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        RaftUiSegmentedControl(
            selectedValue = "wip",
            items =
                listOf(
                    RaftUiSegmentedItem(value = "stable", label = "Stable", count = 36),
                    RaftUiSegmentedItem(value = "wip", label = "WIP", count = 12),
                    RaftUiSegmentedItem(value = "open", label = "Open states", count = 6)
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
                .background(RaftColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RaftUiPopupSurface(modifier = Modifier.width(310.dp)) {
                Row(modifier = Modifier.fillMaxWidth().background(RaftColor.SurfaceWhite).border(RaftAppStroke.Thin, RaftColor.BorderBlack).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.width(36.dp).height(36.dp).background(RaftColor.BrandYellow).border(RaftAppStroke.Default, RaftColor.BorderBlack), contentAlignment = Alignment.Center) {
                        Text("V", style = RaftType.ButtonBold.copy(fontSize = 14.sp, lineHeight = 16.sp), color = RaftColor.TextBlack)
                    }
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        RaftUiLabel(text = "Visual surfaces")
                        RaftUiTextMono("raft-ui/wip package exports")
                    }
                    RaftUiBadge(label = "12", variant = RaftUiBadgeVariant.Information)
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                RaftUiSurfaceLabel(text = "WIP components", modifier = Modifier.width(180.dp))
                RaftUiTextMono("active")
            }
            RaftUiNotificationItem(
                title = "Media list item",
                body = "fixture-ready · selected",
                status = RaftUiSurfaceStatus.Info,
                actionLabel = "new"
            )
            RaftUiMenuRow(label = "List item with metadata", trailing = "RGB 88.57%", highlighted = true)
            RaftUiPopupSurface(modifier = Modifier.width(310.dp)) {
                RaftUiMenuRow(label = "Preview shell muted state", disabled = true)
                Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    Column { RaftUiTextMono("Provider"); RaftUiLabel(text = "Android", small = true) }
                    Column { RaftUiTextMono("Surface"); RaftUiLabel(text = "library", small = true) }
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
                .background(RaftColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RaftUiPopupSurface(modifier = Modifier.width(310.dp)) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        RaftUiTextMono("#visual-testing")
                        RaftUiTextMono("·")
                        RaftUiLabel(text = "Android-Developer-4", small = true)
                        RaftUiTextMono("02:20")
                    }
                    RaftUiTextSans("Added deterministic package-surface cases before moving to WIP exports.")
                    RaftUiBadge(label = "Thread", variant = RaftUiBadgeVariant.Information)
                }
            }
            RaftUiPopupSurface(modifier = Modifier.width(310.dp)) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        RaftUiTextMono("#raft-ui")
                        RaftUiLabel(text = "artin", small = true)
                        RaftUiTextMono("now")
                    }
                    RaftUiTextSans("artin: 做完部署一版")
                    RaftUiTextMono("WIP package exports are being added as library rows.")
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
                .background(RaftColor.SurfaceWhite)
                .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            RaftUiPopupSurface(modifier = Modifier.width(310.dp)) {
                Row(modifier = Modifier.fillMaxWidth().background(RaftColor.SurfaceWhite).border(RaftAppStroke.Thin, RaftColor.BorderBlack).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    RaftUiLabel(text = "Lightbox shell")
                    RaftUiBadge(label = "Esc", variant = RaftUiBadgeVariant.Information)
                }
                Box(modifier = Modifier.fillMaxWidth().height(86.dp).background(RaftColor.BorderBlack.copy(alpha = 0.05f)), contentAlignment = Alignment.Center) {
                    Box(modifier = Modifier.width(192.dp).height(56.dp).background(RaftColor.SurfaceWhite).border(RaftAppStroke.Default, RaftColor.BorderBlack), contentAlignment = Alignment.Center) {
                        RaftUiLabel(text = "Screenshot preview", small = true)
                    }
                }
            }
            RaftUiPopupSurface(modifier = Modifier.width(310.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    RaftUiLabel(text = "Bottom sheet shell")
                    RaftUiTextSans("Static slot capture for mobile actions.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RaftUiButton(label = "Confirm", variant = RaftUiButtonVariant.Primary)
                        RaftUiButton(label = "Cancel", variant = RaftUiButtonVariant.Outline)
                    }
                }
            }
        }
    }
}
