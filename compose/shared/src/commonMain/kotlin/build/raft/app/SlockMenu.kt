package build.raft.app

import build.raft.app.components.SlockRaftMetrics
import build.raft.app.components.SlockRaftSeparator
import build.raft.app.components.slockRaftActive
import build.raft.app.components.slockRaftOverlaySurface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import build.raft.app.components.SlockBrutalSearchField
import build.raft.app.data.TasksUnassignedAssigneeFilterId
import build.raft.app.data.tasksIdentityFilterId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.avatarWireValueForAvatarSlot
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
    if (slockRaftActive()) {
        SlockRaftBaseMenuOverlay(
            anchor = anchor,
            items = items,
            selectedId = selectedId,
            onDismiss = onDismiss,
            onSelect = onSelect,
            width = slockRaftBaseMenuWidth(items, fixedWidth = width, minWidth = minWidth),
            horizontalOffset = horizontalOffset,
            // The brutal default gap becomes DropdownMenuContent's `sideOffset` 6; a caller's
            // own gap (the 4dp status menu) is kept.
            verticalOffset = if (verticalOffset == SlockBaseMenuVerticalOffset) SlockRaftMetrics.MenuSideOffset else verticalOffset,
            verticalPlacement = verticalPlacement,
            modifier = modifier
        )
        return
    }
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
        // Raft sets the selected label bold, so there 140dp is only the minimum
        // (see slockRaftBaseMenuWidth).
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

/**
 * raft-ui DropdownMenu / Select popup for [SlockBaseMenuOverlay]: overlay surface (container
 * radius, popover layer, seam ring, float shadow) with a `p-1.5` inset; 36dp control-radius
 * rows (`px-2.5`, `gap-2.5`) in callout medium ink with a 16dp muted-ink icon; the selected
 * row is bold with a trailing 16dp check (no selected fill, no uppercase); `dividerBefore`
 * is the menu's full-bleed `-mx-1.5 my-1.5` hairline. Rows are the shared
 * [SlockBrutalDropdownMenuRow] (BrandSoft press wash). The brutal colour / label tuning
 * parameters of the overlay do not apply here.
 */
@Composable
private fun SlockRaftBaseMenuOverlay(
    anchor: SlockPopoverAnchor,
    items: List<SlockBaseMenuItem>,
    selectedId: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    width: Dp,
    horizontalOffset: Dp,
    verticalOffset: Dp,
    verticalPlacement: SlockFloatingVerticalPlacement,
    modifier: Modifier
) {
    val density = LocalDensity.current
    val separatorHeight = SlockRaftMetrics.MenuSeparatorInset * 2 + SlockStroke.Thin
    val menuHeight =
        SlockRaftMetrics.MenuRowHeight * items.size +
            SlockRaftMetrics.PopupPadding * 2 +
            separatorHeight * items.count { it.dividerBefore }
    SlockModalAnchoredPopover(
        anchor = anchor,
        // raft overlays float on a shadow that takes no layout space.
        contentSize = with(density) { Size(width.toPx(), menuHeight.toPx()) },
        viewportGap = with(density) { SlockBaseMenuViewportGap.toPx() },
        horizontalOffset = with(density) { horizontalOffset.toPx() },
        verticalOffset = with(density) { verticalOffset.toPx() },
        verticalPlacement = verticalPlacement,
        horizontalAutoFlip = true,
        onDismiss = onDismiss,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .width(width)
                .slockRaftOverlaySurface()
                // Keeps blank menu space out of the sibling dismiss barrier (see the
                // brutal surface).
                .clickable(onClick = {})
                .padding(vertical = SlockRaftMetrics.PopupPadding)
        ) {
            items.forEach { item ->
                if (item.dividerBefore) {
                    SlockRaftSeparator(verticalSpacing = SlockRaftMetrics.MenuSeparatorInset)
                }
                val selected = item.id == selectedId
                // Rows sit in the popup's 6dp side inset; separators bleed through it.
                Box(modifier = Modifier.padding(horizontal = SlockRaftMetrics.PopupPadding).fillMaxWidth()) {
                    SlockBrutalDropdownMenuRow(
                        item =
                            SlockBrutalDropdownMenuItem(
                                id = item.id,
                                label = item.label,
                                icon = item.icon,
                                trailingIcon = if (selected) SlockLucideIconName.Check else null
                            ),
                        rowHeight = SlockRaftMetrics.MenuRowHeight,
                        emphasized = selected,
                        onClick = { onSelect(item.id) }
                    )
                }
            }
        }
    }
}

/**
 * Width of the raft [SlockBaseMenuOverlay] popup. Without a caller width it is the label
 * estimate, never under `min-w-60`. A caller width (the 140dp status menu) is a minimum,
 * like web's `min-w-[140px]`: the popup grows to fit the widest label set bold (the
 * selected row) plus the row chrome and trailing check, up to the brutal max width.
 */
internal fun slockRaftBaseMenuWidth(items: List<SlockBaseMenuItem>, fixedWidth: Dp?, minWidth: Dp): Dp {
    if (fixedWidth == null) return slockRaftMenuWidth(slockBaseMenuWidth(items, uppercaseLabels = false, minWidth = minWidth))
    // Bold 14sp: ~8.5dp per Latin glyph, a full em for CJK and other wide glyphs.
    val widestLabelWidth =
        items.maxOfOrNull { item ->
            item.label.fold(0f) { total, char -> total + if (char.code > 127) 14f else 8.5f }
        } ?: 0f
    val iconWidth =
        if (items.any { it.icon != null }) (SlockRaftMetrics.MenuIconSize + SlockRaftMetrics.MenuItemGap).value else 0f
    val chrome =
        SlockRaftMetrics.PopupPadding.value * 2 +
            SlockRaftMetrics.MenuItemPaddingHorizontal.value * 2 +
            iconWidth +
            // Trailing check of the selected row.
            SlockRaftMetrics.MenuIconSize.value + SlockRaftMetrics.MenuItemGap.value
    val maxWidth = maxOf(fixedWidth, SlockBaseMenuMaxWidth)
    return kotlin.math.ceil(widestLabelWidth + chrome).dp.coerceIn(fixedWidth, maxWidth)
}

private fun slockBaseMenuWidth(items: List<SlockBaseMenuItem>, uppercaseLabels: Boolean, minWidth: Dp): Dp {
    val widestLabelWidth =
        items.maxOfOrNull { item ->
            val label = if (uppercaseLabels) item.label.slockDisplayUppercase() else item.label
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
                text = if (uppercaseLabel) item.label.slockDisplayUppercase() else item.label,
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
 * Task #258 successor (artin msg=f65282d2 via Mahua msg=cf4c6b0a): the assignee
 * selector is a STANDALONE CENTERED modal (SlockUnifiedDialog, same shell family
 * as the channel add-member dialog), not an anchored expansion. It reuses the
 * channel add-user interaction verbatim: SlockBrutalSearchField with the Search
 * leading icon, SlockModalListShell + SlockModalSectionHeader (AGENTS then
 * HUMANS), and the exact SlockModalCompactMemberRow visuals extracted into
 * RowScope.SlockModalCompactMemberRowContent. One adaptation: rows are wrapped
 * in a native Button shell (same disabled/selected/idle background mapping incl.
 * BrandYellow selection) because plain-`clickable` rows inside a dialog stacked
 * on the task detail dialog lose to the outer dismiss barrier. Single-select
 * semantics: tapping a row applies it immediately and closes (matching the
 * add-member row contract), with the unassigned sentinel kept first.
 */
@Composable
internal fun SlockTaskAssigneeMenuOverlay(
    open: Boolean,
    onDismiss: () -> Unit,
    rows: List<MemberProfile?>,
    selectedId: String,
    agentStatusResolver: SlockAgentStatusResolver,
    searchValue: TextFieldValue,
    onSearchValueChange: (TextFieldValue) -> Unit,
    searchPlaceholder: String,
    modalTitle: String,
    unassignedLabel: String,
    humansHeaderLabel: String,
    agentsHeaderLabel: String,
    onSelect: (String) -> Unit
) {
    if (!open || rows.isEmpty()) return
    val memberRows = rows.filterNotNull()
    val agentCount = memberRows.count { it.kind == MemberKind.Agent }
    val humanCount = memberRows.count { it.kind == MemberKind.Human }
    // Same list height formula as the channel add-member dialog.
    val listHeight = threadMemberListHeight(agentCount = agentCount, humanCount = humanCount).dp
    SlockUnifiedDialog(
        title = modalTitle,
        onDismiss = onDismiss,
        minWidth = 356.dp,
        maxWidth = 356.dp,
        contentPadding = 24.dp,
        titleFontSize = 18,
        titleLineHeight = 22
    ) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Same search field as the channel add-member dialog.
            SlockBrutalSearchField(
                value = searchValue,
                placeholder = searchPlaceholder,
                leadingIcon = SlockLucideIconName.Search,
                enabled = true,
                onValueChange = onSearchValueChange
            )
            SlockModalListShell(height = listHeight) {
                if (memberRows.isEmpty()) {
                    item(key = "no-match") {
                        Text(
                            text = searchPlaceholder,
                            style = SlockType.Caption,
                            color = SlockColor.TextMutedGray,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 18.dp)
                        )
                    }
                }
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
        // Task #258 (artin msg=be381a13): same 12dp horizontal inset as the
        // channel SlockModalCompactMemberRow shell so member rows indent alike.
        // Task #269 (artin msg=d04b1d19): and the same 8dp vertical padding -- the
        // port kept only the horizontal value, so rows sat avatar-to-avatar with no
        // gap. The native Button shell contributes no padding of its own here.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            content()
        }
    }
}
