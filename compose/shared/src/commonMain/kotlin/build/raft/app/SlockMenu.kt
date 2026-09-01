package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.clickable
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.RowScope
import com.tencent.kuikly.compose.foundation.layout.Spacer
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.offset
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.foundation.layout.heightIn
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.geometry.Offset
import com.tencent.kuikly.compose.ui.geometry.Size
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.platform.LocalDensity
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.compose.ui.unit.TextUnit
import build.raft.app.components.SlockBrutalSearchField
import build.raft.app.data.TasksUnassignedAssigneeFilterId
import build.raft.app.data.tasksIdentityFilterId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.avatarWireValueForAvatarSlot
import com.tencent.kuikly.compose.ui.text.input.TextFieldValue
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp

internal data class SlockBaseMenuItem(
    val id: String,
    val label: String,
    val icon: SlockLucideIconName? = null,
    val dividerBefore: Boolean = false
)

private val SlockBaseMenuMinWidth = 136.dp
private val SlockBaseMenuMaxWidth = 280.dp
private val SlockBaseMenuRowHeight = 34.dp
private val SlockBaseMenuIconSize = 14.dp
private val SlockBaseMenuShadowOffset = 4.dp
private val SlockBaseMenuHorizontalOffset = (-8).dp
private val SlockBaseMenuVerticalOffset = 8.dp
private val SlockBaseMenuViewportGap = 8.dp
private val SlockBaseMenuForegroundColor = Color(0xFF000000)
private const val SlockBaseMenuChromeWidthValue = 84

// Status editors can be opened from a card near either viewport edge. React measures
// the trigger and flips the portal above when needed; retain that contract here.
internal val SlockTaskStatusMenuVerticalPlacement = SlockFloatingVerticalPlacement.Auto

internal enum class SlockMenuSelectedIndicatorPlacement {
    Leading,
    Trailing
}

@Composable
internal fun SlockBaseMenuOverlay(
    anchor: SlockPopoverAnchor,
    items: List<SlockBaseMenuItem>,
    selectedId: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    surfaceBorderColor: Color = SlockBaseMenuForegroundColor,
    surfaceShadowColor: Color = SlockBaseMenuForegroundColor,
    selectedRowColor: Color = SlockColor.BrandYellow,
    uppercaseLabels: Boolean = true,
    minWidth: Dp = SlockBaseMenuMinWidth,
    // Exact menu width (border-box, like react's fixed-width menus). When null the
    // width is estimated from the widest label via slockBaseMenuWidth (task #370).
    width: Dp? = null,
    rowHeight: Dp = SlockBaseMenuRowHeight,
    labelFontSize: TextUnit = 12.sp,
    labelLineHeight: TextUnit = 14.sp,
    labelFontWeight: FontWeight = FontWeight.SemiBold,
    labelLetterSpacing: TextUnit = 0.8.sp,
    selectedIndicatorPlacement: SlockMenuSelectedIndicatorPlacement = SlockMenuSelectedIndicatorPlacement.Leading,
    modifier: Modifier = Modifier,
    horizontalOffset: Dp = SlockBaseMenuHorizontalOffset,
    verticalOffset: Dp = SlockBaseMenuVerticalOffset,
    // #223: default to Auto so the menu flips above the trigger when
    // there's no room below. The trigger (e.g. row top-left) is
    // passed in as `anchor`, so the menu's natural reading is "drop
    // below the trigger; if no room, flip above".
    verticalPlacement: SlockFloatingVerticalPlacement = SlockFloatingVerticalPlacement.Auto
) {
    if (items.isEmpty()) return
    val density = LocalDensity.current
    val shadowOffset = if (surfaceShadowColor == Color.Transparent) 0.dp else SlockBaseMenuShadowOffset
    val menuWidth = width ?: slockBaseMenuWidth(items, uppercaseLabels, minWidth)
    val menuSize = with(density) {
        Size(
            width = (menuWidth + shadowOffset).toPx(),
            height =
                rowHeight.toPx() * items.size +
                    // Rows sit inside the border (react content-box height), see the
                    // Column's border padding below (task #370).
                    SlockStroke.Default.toPx() * 2 +
                    SlockStroke.Default.toPx() * items.count { it.dividerBefore } +
                    shadowOffset.toPx()
        )
    }
    val horizontalOffsetPx = with(density) { horizontalOffset.toPx() }
    val verticalOffsetPx = with(density) { verticalOffset.toPx() }
    val viewportGapPx = with(density) { SlockBaseMenuViewportGap.toPx() }
    SlockModalAnchoredPopover(
        anchor = anchor,
        contentSize = menuSize,
        viewportGap = viewportGapPx,
        horizontalOffset = horizontalOffsetPx,
        verticalOffset = verticalOffsetPx,
        verticalPlacement = verticalPlacement,
        horizontalAutoFlip = true,
        onDismiss = onDismiss,
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .padding(end = shadowOffset, bottom = shadowOffset)
        ) {
            if (shadowOffset > 0.dp) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .offset(x = shadowOffset, y = shadowOffset)
                        .background(surfaceShadowColor)
                )
            }
            Column(
                modifier = Modifier
                    .width(menuWidth)
                    .background(SlockColor.SurfaceWhite)
                    .border(SlockStroke.Default, surfaceBorderColor)
                    // This surface is the parent of actual Button rows. Native dispatch
                    // capture here wins before child rows on Android, so only use the
                    // no-op clickable needed to keep blank menu space out of the
                    // sibling dismiss barrier.
                    .clickable(onClick = {})
                    // react draws its 2px border around the rows (content-box), so
                    // the menu is rows + 2*2dp tall and the rows are inset by the
                    // stroke; Compose paints the border over the content instead —
                    // pad the rows in by the stroke to match (task #370).
                    .padding(SlockStroke.Default)
            ) {
                items.forEach { item ->
                    if (item.dividerBefore) {
                        Box(Modifier.fillMaxWidth().height(SlockStroke.Default).background(SlockBaseMenuForegroundColor))
                    }
                    val selected = item.id == selectedId
                    SlockBaseMenuRow(
                        item = item,
                        selected = selected,
                        selectedRowColor = selectedRowColor,
                        uppercaseLabel = uppercaseLabels,
                        rowHeight = rowHeight,
                        labelFontSize = labelFontSize,
                        labelLineHeight = labelLineHeight,
                        labelFontWeight = labelFontWeight,
                        labelLetterSpacing = labelLetterSpacing,
                        selectedIndicatorPlacement = selectedIndicatorPlacement,
                        onClick = { onSelect(item.id) }
                    )
                }
            }
        }
    }
}

/**
 * The task-status dropdown, aligned to react (#370/#112). This is the single
 * source of truth for that menu's styling — the Tasks page and the thread
 * conversation flow (#394) both go through here so the two can never drift back
 * to the SlockBaseMenuOverlay defaults (uppercase labels, yellow selected row,
 * leading check, estimated width) that read as the "old" menu. Callers pass only
 * their own status items + selection/dispatch; the visual style lives here.
 */
@Composable
internal fun SlockTaskStatusMenuOverlay(
    anchor: SlockPopoverAnchor,
    items: List<SlockBaseMenuItem>,
    selectedId: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    SlockBaseMenuOverlay(
        anchor = anchor,
        items = items,
        selectedId = selectedId,
        selectedRowColor = SlockColor.SurfaceWhite,
        uppercaseLabels = false,
        // react's status menu renders at exactly its 140px min-width (the longest
        // label "In Progress" is narrower); the char estimate overshoots to 172dp.
        width = 140.dp,
        rowHeight = 36.dp,
        labelFontSize = 14.sp,
        labelLineHeight = 20.sp,
        labelFontWeight = FontWeight.Normal,
        labelLetterSpacing = 0.sp,
        selectedIndicatorPlacement = SlockMenuSelectedIndicatorPlacement.Trailing,
        horizontalOffset = 0.dp,
        verticalOffset = 4.dp,
        verticalPlacement = SlockTaskStatusMenuVerticalPlacement,
        onDismiss = onDismiss,
        onSelect = onSelect
    )
}

private fun slockBaseMenuWidth(items: List<SlockBaseMenuItem>, uppercaseLabels: Boolean, minWidth: Dp): Dp {
    val widestLabelWidth =
        items.maxOfOrNull { item ->
            val label = if (uppercaseLabels) item.label.uppercase() else item.label
            label.fold(0) { total, char -> total + if (char.code > 127) 14 else 8 }
        } ?: 0
    return (widestLabelWidth + SlockBaseMenuChromeWidthValue)
        .coerceIn(minWidth.value.toInt(), SlockBaseMenuMaxWidth.value.toInt())
        .dp
}

@Composable
internal fun SlockBaseMenuRow(
    item: SlockBaseMenuItem,
    selected: Boolean,
    selectedRowColor: Color,
    uppercaseLabel: Boolean,
    rowHeight: Dp,
    labelFontSize: TextUnit,
    labelLineHeight: TextUnit,
    labelFontWeight: FontWeight,
    labelLetterSpacing: TextUnit,
    selectedIndicatorPlacement: SlockMenuSelectedIndicatorPlacement,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(rowHeight)
            .background(if (selected) selectedRowColor else SlockColor.SurfaceWhite)
            .padding(horizontal = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
        ) {
            val leadingIcon =
                item.icon ?: SlockLucideIconName.Check.takeIf {
                    selected && selectedIndicatorPlacement == SlockMenuSelectedIndicatorPlacement.Leading
                }
            if (leadingIcon != null) {
                SlockLucideIcon(leadingIcon, modifier = Modifier.size(SlockBaseMenuIconSize), color = SlockColor.TextBlackArgb)
            } else if (selectedIndicatorPlacement == SlockMenuSelectedIndicatorPlacement.Leading) {
                Spacer(modifier = Modifier.width(SlockBaseMenuIconSize))
            }
            Text(
                text = if (uppercaseLabel) item.label.uppercase() else item.label,
                style = SlockType.Caption.copy(
                    fontSize = labelFontSize,
                    lineHeight = labelLineHeight,
                    letterSpacing = labelLetterSpacing
                ).withSlockFontWeight(labelFontWeight),
                color = SlockBaseMenuForegroundColor,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            if (selectedIndicatorPlacement == SlockMenuSelectedIndicatorPlacement.Trailing) {
                if (selected) {
                    SlockLucideIcon(SlockLucideIconName.Check, modifier = Modifier.size(SlockBaseMenuIconSize), color = SlockColor.TextBlackArgb)
                } else {
                    Spacer(modifier = Modifier.width(SlockBaseMenuIconSize))
                }
            }
        }
    }
}

/*
 * Task #258 successor (artin msgs ed597ee6/478e8978): the thread detail dialog's
 * assignee selector REUSES the channel add-member interaction code verbatim —
 * SlockBrutalSearchField with the Search leading icon, SlockModalListShell +
 * SlockModalSectionHeader sections (AGENTS first, then HUMANS), and the row
 * visuals of SlockModalCompactMemberRow (SlockModalCompactMemberRowContent).
 * Two adaptations, everything else identical to the channel picker:
 *  1. Rows are wrapped in a native Button because plain-`clickable` rows lose to
 *     the outer dismiss barrier inside the detail dialog (SlockMenu dispatch
 *     contract); the Button shell carries the exact same selected/unabled
 *     background mapping (BrandYellow selected) as SlockModalCompactMemberRow.
 *  2. A single-select unassigned sentinel row is kept (the task domain needs it).
 * The anchored in-dialog popover shell is retained — a second stacked Modal
 * window is what swallowed row taps before (commit 5989e833).
 */
@Composable
internal fun SlockTaskAssigneeMenuOverlay(
    anchor: SlockPopoverAnchor,
    rows: List<MemberProfile?>,
    selectedId: String,
    agentStatusResolver: SlockAgentStatusResolver,
    searchValue: TextFieldValue,
    onSearchValueChange: (TextFieldValue) -> Unit,
    searchPlaceholder: String,
    unassignedLabel: String,
    humansHeaderLabel: String,
    agentsHeaderLabel: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (rows.isEmpty()) return
    val density = LocalDensity.current
    val shadowOffset = SlockBaseMenuShadowOffset
    // Same width as the channel add-member dialog.
    val menuWidth = 356.dp
    val memberRows = rows.filterNotNull()
    val agentCount = memberRows.count { it.kind == MemberKind.Agent }
    val humanCount = memberRows.count { it.kind == MemberKind.Human }
    // Same list height formula as the channel add-member dialog.
    val listHeight = threadMemberListHeight(agentCount = agentCount, humanCount = humanCount).dp
    val searchRowHeight = 46.dp
    val menuSize = with(density) {
        Size(
            width = (menuWidth + shadowOffset).toPx(),
            height =
                listHeight.toPx() +
                    searchRowHeight.toPx() +
                    SlockStroke.Default.toPx() * 2 +
                    shadowOffset.toPx()
        )
    }
    SlockModalAnchoredPopover(
        anchor = anchor,
        contentSize = menuSize,
        viewportGap = with(density) { SlockBaseMenuViewportGap.toPx() },
        verticalPlacement = SlockTaskStatusMenuVerticalPlacement,
        horizontalAutoFlip = true,
        onDismiss = onDismiss,
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .padding(end = shadowOffset, bottom = shadowOffset)
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .offset(x = shadowOffset, y = shadowOffset)
                    .background(SlockBaseMenuForegroundColor)
            )
            Column(
                modifier = Modifier
                    .width(menuWidth)
                    .background(SlockColor.SurfaceWhite)
                    .border(SlockStroke.Default, SlockBaseMenuForegroundColor)
                    // Blank menu space must stay out of the sibling dismiss
                    // barrier (same contract as SlockBaseMenuOverlay); the empty
                    // clickable also keeps the nested LazyColumn scrollable.
                    .clickable(onClick = {})
                    .padding(SlockStroke.Default)
            ) {
                // Same search field as the channel add-member dialog.
                SlockBrutalSearchField(
                    value = searchValue,
                    placeholder = searchPlaceholder,
                    leadingIcon = SlockLucideIconName.Search,
                    enabled = true,
                    onValueChange = onSearchValueChange,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
                )
                SlockModalListShell(height = listHeight) {
                    rows.forEach { row ->
                        if (row == null) {
                            item(key = TasksUnassignedAssigneeFilterId) {
                                SlockTaskAssigneeMemberButton(
                                    selected = selectedId == TasksUnassignedAssigneeFilterId,
                                    onClick = { onSelect(TasksUnassignedAssigneeFilterId) }
                                ) {
                                    Box(
                                        modifier = Modifier.size(26.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        SlockLucideIcon(
                                            SlockLucideIconName.UserMinus,
                                            modifier = Modifier.size(15.dp),
                                            color = "#FF000000"
                                        )
                                    }
                                    Text(
                                        text = unassignedLabel,
                                        style = SlockType.Body.withSlockFontWeight(FontWeight.Medium)
                                            .copy(fontSize = 14.sp, lineHeight = 18.sp),
                                        color = SlockColor.TextBlack,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    if (selectedId == TasksUnassignedAssigneeFilterId) {
                                        SlockLucideIcon(
                                            SlockLucideIconName.Check,
                                            modifier = Modifier.size(15.dp),
                                            color = "#FF000000"
                                        )
                                    } else {
                                        Spacer(modifier = Modifier.size(15.dp))
                                    }
                                }
                            }
                        }
                    }
                    var lastKind: MemberKind? = null
                    rows.forEach { row ->
                        val member = row ?: return@forEach
                        if (lastKind != member.kind) {
                            lastKind = member.kind
                            item(key = "header-${member.kind.name.lowercase()}") {
                                // Same section header as the channel add-member dialog.
                                SlockModalSectionHeader(
                                    label = if (member.kind == MemberKind.Human) humansHeaderLabel else agentsHeaderLabel
                                )
                            }
                        }
                        item(key = member.tasksIdentityFilterId()) {
                            val selected = member.tasksIdentityFilterId() == selectedId
                            val statusIndicatorState =
                                if (member.kind == MemberKind.Agent) {
                                    resolveThreadMemberAgentStatusIndicatorState(
                                        memberKind = member.kind.name.lowercase(),
                                        canonicalState = rememberSlockAgentStatusIndicatorState(
                                            agentStatusResolver,
                                            member.id.value
                                        )
                                    )
                                } else {
                                    null
                                }
                            SlockTaskAssigneeMemberButton(
                                selected = selected,
                                onClick = { onSelect(member.tasksIdentityFilterId()) }
                            ) {
                                SlockModalCompactMemberRowContent(
                                    title = member.displayName,
                                    subtitle = member.roleDescription
                                        ?.takeIf { it.isNotBlank() }
                                        ?: "${member.kind.name.lowercase()} · @${member.handle}",
                                    avatarWireValue = member.avatarWireValueForAvatarSlot(),
                                    avatarLabel = member.avatarLabel.ifBlank { member.displayName },
                                    statusColor = statusIndicatorState?.color,
                                    statusPulsing = statusIndicatorState?.pulsing == true,
                                    avatarSize = 26.dp,
                                    sourceBadgeLabel = null,
                                    enabled = true,
                                    selected = selected,
                                    trailingText = "",
                                    trailingIcon = if (selected) SlockLucideIconName.Check else null,
                                    onTrailingClick = null,
                                    cacheSubject = member.slockAvatarCacheSubject(),
                                    cacheServerId = member.serverId?.value
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Native-Button row shell carrying SlockModalCompactMemberRow's exact
 * background mapping (SurfaceCream disabled / BrandYellow selected /
 * SurfaceWhite idle). Button rows win native dispatch inside the detail dialog
 * where plain-`clickable` rows lose to the outer dismiss barrier.
 */
@Composable
private fun SlockTaskAssigneeMemberButton(
    selected: Boolean,
    onClick: () -> Unit,
    // RowScope receiver: the row content reuses SlockModalCompactMemberRowContent,
    // a RowScope extension whose children rely on Row-scope weight modifiers.
    content: @Composable RowScope.() -> Unit
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) SlockColor.BrandYellow else SlockColor.SurfaceWhite
            )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            content()
        }
    }
}
