package build.raft.app

import build.raft.app.components.RaftProvideDesignSystemMisuse
import build.raft.app.components.raftLookOverlaySurface
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftAvatar
import build.raft.designsystem.components.RaftAvatarSize
import build.raft.designsystem.components.RaftAvatarType
import build.raft.designsystem.components.RaftBadge
import build.raft.designsystem.components.RaftBadgeAppearance
import build.raft.designsystem.components.RaftBadgeVariant
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftCheckbox
import build.raft.designsystem.components.RaftCheckboxIndicator
import build.raft.designsystem.components.RaftCheckboxShape
import build.raft.designsystem.components.RaftListRow
import build.raft.designsystem.components.RaftListRowGroup
import build.raft.designsystem.components.RaftMenuItem
import build.raft.designsystem.tokens.RaftStroke
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftFonts
import build.raft.designsystem.theme.RaftTheme

// components.ui.* fixtures drawn with compose/designsystem (raft-ui 0.8.0) components, so
// the Android capture of each case is the design-system component the pages migrate to,
// not the RaftBrutal* / build.raft.app.components helpers (AGENTS.md, Shared UI Component
// Discipline). Each frame is the web case's: 342dp wide, `layer-canvas`, 16dp padding.

/**
 * The designsystem theme pinned light (every components.ui.* page pins light), in the
 * app's bundled Space Grotesk / Space Mono faces, as [build.raft.app.components.RaftDesignSystemTheme].
 * Design-system misuse fails the capture whatever the build type.
 */
@Composable
private fun UiDesignSystemFrame(height: Dp, content: @Composable () -> Unit) {
    val fonts =
        remember {
            RaftFonts(
                sans = raftMultiWeightFontFamily("sans"),
                mono = raftMultiWeightFontFamily("mono")
            )
        }
    RaftTheme(appearance = RaftAppearance.Light, fonts = fonts) {
        RaftProvideDesignSystemMisuse(failOnMisuse = true) {
            Box(
                modifier =
                    Modifier
                        .width(342.dp)
                        .height(height)
                        .background(RaftTheme.colors.layerCanvas)
                        .padding(16.dp)
            ) {
                content()
            }
        }
    }
}

/** Tailwind `text-sm font-bold`: `text-callout` (14/20) at 700. */
private val UiCalloutBold
    @Composable get() = RaftTheme.typography.callout.copy(fontWeight = FontWeight.Bold)

@Composable
internal fun UiCheckMarkerVisualContent() {
    // web `ui-check-marker`: three raft-ui <CheckboxIndicator> rows (the fixed 20px box) in
    // a `flex flex-col gap-3 text-sm font-bold` stack, each row `gap-3`. The third is
    // `shape="circle"` checked: a black disc with the check knocked out, not a radio dot.
    // The web passes no tone, so "Circle yellow" is only the label.
    UiDesignSystemFrame(height = 112.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            UiCheckMarkerRow("Square checked", checked = true, shape = RaftCheckboxShape.Square)
            UiCheckMarkerRow("Square unchecked", checked = false, shape = RaftCheckboxShape.Square)
            UiCheckMarkerRow("Circle yellow", checked = true, shape = RaftCheckboxShape.Circle)
        }
    }
}

@Composable
private fun UiCheckMarkerRow(label: String, checked: Boolean, shape: RaftCheckboxShape) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        RaftCheckboxIndicator(checked = checked, shape = shape)
        RaftText(label, style = UiCalloutBold, color = RaftTheme.colors.foreground)
    }
}

@Composable
internal fun UiButtonVisualContent() {
    // web `ui-button` (342x150 canvas frame): two rows of `size="sm"` raft-ui <Button>s 12px
    // apart. raft-ui 0.8.0 (components/button.md, Variants) renders `accent` as `primary`,
    // and `outline`, `information`, `muted` and `success` as `default`: so Save, Add and
    // Continue are the ink-bordered default button and Sync / Delete the pink sticker;
    // there is no cyan or lime button. `sm` is an old name of the 32dp md size.
    UiDesignSystemFrame(height = 150.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                RaftButton("Save", onClick = {}, variant = RaftButtonVariant.Default)
                RaftButton("Sync", onClick = {}, variant = RaftButtonVariant.Primary)
                RaftButton("Delete", onClick = {}, variant = RaftButtonVariant.Primary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                RaftButton("Add", onClick = {}, variant = RaftButtonVariant.Default)
                RaftButton("Continue", onClick = {}, variant = RaftButtonVariant.Default)
                RaftButton("Disabled", onClick = {}, variant = RaftButtonVariant.Default, enabled = false)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun UiBadgeVisualContent() {
    // web `ui-badge`: one `flex flex-wrap gap-2` run of six raft-ui <Badge>s in source
    // case. "Shared" is `appearance="outline"`: a transparent chip with a 1dp hairline and
    // Space Mono `foreground-muted` type (components/badge.md, Variants), not a solid paper
    // sticker. The rest are solid stickers by colour role; Install is the accent badge
    // rendered as a button, drawn here as its badge (RaftBadge has no button form).
    UiDesignSystemFrame(height = 92.dp) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            RaftBadge("Shared", appearance = RaftBadgeAppearance.Outline)
            RaftBadge("Installed", variant = RaftBadgeVariant.Success)
            RaftBadge("Update", variant = RaftBadgeVariant.Warning)
            RaftBadge("Built In", variant = RaftBadgeVariant.Muted)
            RaftBadge("task #273", variant = RaftBadgeVariant.Danger)
            RaftBadge("Install", variant = RaftBadgeVariant.Accent)
        }
    }
}

@Composable
internal fun UiAvatarListRowVisualContent() {
    // web `ui-avatar-list-row`: two AvatarListRows flush inside one raft-ui <ListRowGroup>
    // (container radius, 1dp hairline, `layer-card`, clipped; components/list-row.md), not
    // two surface cards 12dp apart. Each row: the 35dp human placeholder avatar, a 16/24
    // semibold title over a Space Mono subtitle, the trailing slot centred. The second row
    // is selected (`info-soft` across the row) and carries the hairline from the text
    // column (63dp). "Online" is a solid success badge, "Open" a 32dp default button.
    UiDesignSystemFrame(height = 168.dp) {
        RaftListRowGroup(Modifier.fillMaxWidth()) {
            RaftListRow(
                title = "Cindy",
                subtitle = "Claude Code",
                onClick = {},
                leading = { RaftAvatar(RaftAvatarType.Human, RaftAvatarSize.Md) },
                trailing = { RaftBadge("Online", variant = RaftBadgeVariant.Success) }
            )
            RaftListRow(
                title = "Product UX Designer",
                subtitle = "product@slock.ai",
                onClick = {},
                divider = true,
                selected = true,
                leading = { RaftAvatar(RaftAvatarType.Human, RaftAvatarSize.Md) },
                trailing = { RaftButton("Open", onClick = {}) }
            )
        }
    }
}

@Composable
internal fun UiCheckboxVisualContent() {
    // web `ui-checkbox`: three <label> rows (`text-sm font-bold`, 14/20 at 700; the box 8dp
    // before the text, as the audited fixture had it) around a raft-ui <Checkbox> (the
    // fixed 20px box). The disabled row is the
    // case's own composition: the whole <label> at `opacity-60` over a disabled Checkbox,
    // its text still `foreground`. This mirrors that markup with RaftCheckbox and a
    // RaftText label. RaftCheckboxRow keeps raft-ui's own disabled label
    // (`foreground-disabled`, components/label.md and field.md, States: Disabled), which
    // is a different composition from the case's, not a component gap.
    UiDesignSystemFrame(height = 132.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            UiCheckboxLabelRow("As Task selected", checked = true)
            UiCheckboxLabelRow("Permission row unchecked", checked = false)
            UiCheckboxLabelRow("Disabled checked", checked = true, enabled = false, modifier = Modifier.alpha(0.6f))
        }
    }
}

@Composable
private fun UiCheckboxLabelRow(label: String, checked: Boolean, enabled: Boolean = true, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        RaftCheckbox(checked = checked, onCheckedChange = {}, enabled = enabled)
        RaftText(label, style = UiCalloutBold, color = RaftTheme.colors.foreground, maxLines = 1)
    }
}

@Composable
internal fun UiMenuItemVisualContent() {
    // web `ui-menu-item`: four 36dp menu rows (px-2.5, callout medium, mono hint shortcut)
    // flush inside a `rounded-container border border-line-seam bg-layer-popover
    // shadow-float` card, no popup inset. The last row carries its own `border-t
    // border-line-hairline` inside its 36dp box, so its text centres 0.5dp lower:
    // RaftMenuItem(divider = true). 342 x 178dp = 16 + 1 + 4 x 36 + 1 + 16.
    UiDesignSystemFrame(height = 178.dp) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .raftLookOverlaySurface()
                    .padding(RaftStroke.Hairline)
        ) {
            RaftMenuItem("Open Channel", onClick = {}, shortcut = "⌘K")
            RaftMenuItem("Mark as Read", onClick = {})
            RaftMenuItem("Archive unavailable", onClick = {}, enabled = false)
            RaftMenuItem("Delete Message", onClick = {}, divider = true)
        }
    }
}
