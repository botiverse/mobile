package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.components.RaftPublicPageGlobe
import build.raft.app.thread.ForwardPickerBodyPadding
import build.raft.designsystem.components.RaftHoverTier
import build.raft.designsystem.components.RaftTopBarDefaults
import build.raft.designsystem.components.RaftWorkspaceButtonDefaults
import build.raft.designsystem.components.raftClickable
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme

private val PickerServerSwitcherPopoverEstimateWidth = 300.dp
private val PickerServerSwitcherPopoverVerticalGap = 4.dp
private val PickerServerSwitcherPopoverPadding = 8.dp
private val PickerServerSwitcherTriggerStartInset = 6.dp
private val PickerServerSwitcherTriggerEndInset = 8.dp

internal data class RaftServerSwitcherControlState(
    val selectedServer: RaftServerSwitcherItem,
    val canOpenMenu: Boolean
)

internal fun resolveRaftServerSwitcherControlState(
    servers: List<RaftServerSwitcherItem>,
    selectedServerId: String?,
    enabled: Boolean
): RaftServerSwitcherControlState? {
    val selected = servers.firstOrNull { it.id == selectedServerId } ?: servers.firstOrNull() ?: return null
    return RaftServerSwitcherControlState(
        selectedServer = selected,
        canOpenMenu = enabled && servers.size > 1
    )
}

/**
 * Reusable compact Server Switcher trigger + anchored dropdown menu for flows that pick a server
 * without mutating the app-wide selected server (e.g. External Share target picker). Reuses Home's
 * [ServerSwitcherAvatarTile] and [RaftServerSwitcherSelectionPanel] chrome, without unread badges,
 * drag-reorder handles, or server-management actions.
 */
@Composable
internal fun RaftServerSwitcherControl(
    servers: List<RaftServerSwitcherItem>,
    selectedServerId: String?,
    accessibilityLabel: String,
    enabled: Boolean = true,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val controlState = resolveRaftServerSwitcherControlState(servers, selectedServerId, enabled) ?: return
    val selected = controlState.selectedServer
    val canOpenMenu = controlState.canOpenMenu
    var expanded by remember { mutableStateOf(false) }
    val anchorRef = remember { RaftLiveRootAnchorRef(identity = "picker-server-switcher") }
    var anchorResolver by remember { mutableStateOf<RaftPopoverAnchorResolver?>(null) }

    LaunchedEffect(canOpenMenu, selectedServerId) {
        if (!canOpenMenu) expanded = false
    }

    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val triggerShape = RoundedCornerShape(RaftAppRadius.Control)
    val triggerDescription =
        accessibilityLabel.takeIf { it.isNotBlank() }?.let { "$it: ${selected.name}" } ?: selected.name

    // Align the 27dp avatar flush with the picker's 12dp body edge (6dp outer + 6dp trigger inset)
    // so the press fill can expand gently past the avatar just like Home's workspace switcher.
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(
                    start = ForwardPickerBodyPadding - PickerServerSwitcherTriggerStartInset,
                    top = 8.dp,
                    end = ForwardPickerBodyPadding
                )
    ) {
        Box(modifier = Modifier.raftCaptureLiveRootAnchor(anchorRef)) {
            Row(
                modifier =
                    Modifier
                        .height(RaftTopBarDefaults.itemSize)
                        .alpha(if (enabled) 1f else HomeServerSwitchPendingAlpha)
                        .background(if (expanded) RaftColor.FillPressed else Color.Transparent, triggerShape)
                        .then(
                            if (canOpenMenu) {
                                Modifier.raftClickable(
                                    onClick = {
                                        anchorResolver = anchorRef.resolver(RaftPopoverLocalPoint.BottomStart)
                                        expanded = !expanded
                                    },
                                    role = Role.DropdownList,
                                    shape = triggerShape,
                                    tier = RaftHoverTier.Subtle,
                                    selected = expanded
                                )
                            } else {
                                Modifier
                            }
                        )
                        .semantics { contentDescription = triggerDescription }
                        .padding(
                            start = PickerServerSwitcherTriggerStartInset,
                            end = PickerServerSwitcherTriggerEndInset
                        ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ServerSwitcherAvatarTile(selected, size = RaftWorkspaceButtonDefaults.AvatarSize)
                Spacer(Modifier.width(8.dp))
                RaftText(
                    text = selected.name,
                    style = typography.subhead.copy(fontWeight = FontWeight.Bold),
                    color = colors.foreground,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).widthIn(max = RaftWorkspaceButtonDefaults.NameMaxWidth)
                )
                if (selected.publicPageLive) {
                    Spacer(Modifier.width(HomeServerPublicPageGlobeGap))
                    RaftPublicPageGlobe(
                        live = true,
                        size = HomeServerPublicPageGlobeSize,
                        color = raftArgbHex(colors.foreground)
                    )
                }
                if (servers.size > 1) {
                    Spacer(Modifier.width(4.dp))
                    RaftLucideIcon(
                        name = RaftLucideIconName.ChevronDown,
                        modifier = Modifier.size(16.dp),
                        color = raftArgbHex(colors.foreground),
                        rotationDegrees = if (expanded) 180f else 0f
                    )
                }
            }
        }
    }

    if (expanded && canOpenMenu) {
        val anchor = anchorResolver?.resolveCurrentPlacement()
        if (anchor == null) {
            LaunchedEffect(Unit) { expanded = false }
        } else {
            val density = LocalDensity.current
            val containerWidthPx = LocalWindowInfo.current.containerSize.width
            val popoverWidth =
                if (containerWidthPx > 0) {
                    val viewportWidthDp = with(density) { containerWidthPx.toDp() }
                    val availableWidth = (viewportWidthDp - HomeServerSwitcherMenuInset * 2).coerceAtLeast(276.dp)
                    minOf(availableWidth, HomeServerSwitcherMenuMaxWidth)
                } else {
                    PickerServerSwitcherPopoverEstimateWidth
                }
            var measuredSize by remember { mutableStateOf(Size.Zero) }
            val estimatedHeight =
                (homeServerSwitcherServerListMaxHeight(servers.size) ?: (HomeServerSwitcherMenuRowHeight * servers.size)) +
                    PickerServerSwitcherPopoverPadding
            val contentSize =
                if (measuredSize != Size.Zero) {
                    measuredSize
                } else {
                    with(density) { Size(popoverWidth.toPx(), estimatedHeight.toPx()) }
                }
            RaftModalAnchoredPopover(
                anchor = anchor,
                contentSize = contentSize,
                onDismiss = { expanded = false },
                viewportGap = with(density) { HomeServerSwitcherMenuInset.toPx() },
                verticalOffset = with(density) { PickerServerSwitcherPopoverVerticalGap.toPx() },
                horizontalAutoFlip = false,
                verticalPlacement = RaftFloatingVerticalPlacement.Auto,
                dismissActivationDelayMillis = RaftFloatingOverlayClickTriggeredDismissDelayMillis
            ) {
                RaftDesignSystemTheme {
                    Box(
                        modifier =
                            Modifier
                                .width(popoverWidth)
                                .onSizeChanged { size ->
                                    measuredSize = Size(size.width.toFloat(), size.height.toFloat())
                                }
                                .raftFloatingContentClickTarget()
                    ) {
                        RaftServerSwitcherSelectionPanel(
                            servers = servers,
                            selectedServerId = selected.id,
                            maxWidth = popoverWidth,
                            onSelect = { picked ->
                                expanded = false
                                if (picked.id != selectedServerId) {
                                    onSelect(picked.id)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}
