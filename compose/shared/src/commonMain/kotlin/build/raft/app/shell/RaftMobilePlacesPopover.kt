package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import build.raft.app.data.ArtifactLinkTarget
import build.raft.app.data.RaftRailAppPlacePrefix
import build.raft.app.data.RaftRailPlace
import build.raft.app.data.RaftSharedStoreProvider
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.artifactWebUrl
import build.raft.app.data.resolveAppLinkIconUrl
import build.raft.app.resources.*
import build.raft.app.thread.RaftConnectedAppLogo
import build.raft.app.thread.icon
import build.raft.designsystem.components.RaftAvatarSize
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftGlyph
import build.raft.designsystem.components.RaftGlyphIcon
import build.raft.designsystem.components.RaftMobileNavServerPopoverGroup
import build.raft.designsystem.components.RaftMobileNavServerPopoverRow
import build.raft.designsystem.components.RaftMobileNavServerPopoverSeparator
import build.raft.designsystem.components.RaftMobileNavServerPopoverState
import build.raft.designsystem.components.RaftReorderDefaults
import build.raft.designsystem.components.raftClickable
import build.raft.designsystem.haptics.rememberRaftHaptics
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftOpacity
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Test tags for the upgraded phone Server Tab Popover (`tab_bar_places_v0`). */
internal object RaftMobilePlacesPopoverTags {
    const val RECENTLY_USED_SECTION = "mobile_places_recently_used_section"
    const val MORE_HEADER = "mobile_places_more_header"
    const val EDIT_BUTTON = "mobile_places_edit_button"
    const val EDIT_SURFACE = "mobile_places_edit_surface"
    const val EDIT_RESET_BUTTON = "mobile_places_edit_reset"
    const val EDIT_DONE_BUTTON = "mobile_places_edit_done"
    const val EDIT_TAB_BAR_GRID = "mobile_places_edit_tab_bar_grid"
    const val EDIT_MORE_GRID = "mobile_places_edit_more_grid"
    const val EDIT_SETTINGS_TILE = "mobile_places_edit_settings_tile"

    fun tabTile(placeId: String): String = "mobile_places_edit_tab_$placeId"
    fun moreTile(placeId: String): String = "mobile_places_edit_more_$placeId"
    fun removeBadge(placeId: String): String = "mobile_places_edit_remove_$placeId"
    fun addBadge(placeId: String): String = "mobile_places_edit_add_$placeId"
}

/**
 * Configuration passed to [RaftAppServerMenuOverlay] when `tab_bar_places_v0` is enabled on a
 * phone (`BottomBar` anchor). Null when the flag is OFF, on wide screens, or on Desktop Compose
 * so the legacy popover remains 100% unchanged.
 */
internal data class RaftServerMenuMobilePlacesConfig(
    val mobilePlaces: List<RaftRailPlace>?,
    val artifactsEnabled: Boolean,
    val isGuest: Boolean,
    val recentlyUsed: List<MobileRecentlyUsedEntry>,
    val resolveAppIconUrl: (String?) -> String?,
    val initialEditMode: Boolean = false,
    val onInitialEditModeConsumed: () -> Unit = {},
    val onPickRecent: (MobileRecentlyUsedEntry) -> Unit,
    val onSaveOrder: (List<String>) -> Unit,
    val onResetToDefault: () -> Unit,
)

/**
 * Builds the [RaftServerMenuMobilePlacesConfig] for the phone Server Tab Popover outside
 * `RaftAppShellReadyContent` so `SettingsHomePage.kt` stays small and respects JVM method budgets.
 * Returns null whenever `tab_bar_places_v0` is OFF, on Desktop Compose, or on wide layouts.
 */
@Composable
internal fun rememberRaftServerMenuMobilePlacesConfig(
    mobilePlacesShell: AppShellMobilePlacesShellState,
    navRailActive: Boolean,
    sharedStores: RaftSharedStoreProvider,
    storeScope: RaftShellStoreScope,
    readyAccountId: String?,
    readyServerId: String?,
    linkNavigator: RaftLinkNavigator,
    scope: CoroutineScope,
    initialEditMode: Boolean,
    onInitialEditModeConsumed: () -> Unit,
    log: (String) -> Unit,
): RaftServerMenuMobilePlacesConfig? {
    val active = mobilePlacesShell.enabled && !mobilePlacesShell.isDesktopHost && !navRailActive
    if (!active) return null
    val recentMap by sharedStores.mobileRecentlyUsedStore.state.collectAsState()
    val artifactOpener =
        rememberRaftArtifactOpener(storeScope = storeScope) { target ->
            linkNavigator.open(artifactWebUrl(sharedStores.apiEnvironmentStore.current().webUrl, target))
        }
    val onBarPlaceKeys =
        remember(mobilePlacesShell.activePhoneTabKeys) {
            mobilePlacesShell.activePhoneTabKeys
                .mapNotNull { key ->
                    raftMobilePlaceKeyForShellTab(key)
                        ?: key.takeIf { it.startsWith(RaftRailAppPlacePrefix, ignoreCase = true) }
                }
                .toSet()
        }
    val recentItems =
        remember(recentMap, readyAccountId, readyServerId, onBarPlaceKeys) {
            sharedStores.mobileRecentlyUsedStore.recentItems(
                accountId = readyAccountId,
                serverId = readyServerId,
                excludedPlaceKeys = onBarPlaceKeys,
            )
        }
    val resolveAppIconUrl: (String?) -> String? = { icon ->
        icon?.let { resolveAppLinkIconUrl(it, sharedStores.apiEnvironmentStore.current().apiBaseUrl) }
    }
    return RaftServerMenuMobilePlacesConfig(
        mobilePlaces = mobilePlacesShell.activeSnapshot?.mobilePlaces,
        artifactsEnabled = mobilePlacesShell.artifactsEnabled,
        isGuest = mobilePlacesShell.isGuest,
        recentlyUsed = recentItems,
        resolveAppIconUrl = resolveAppIconUrl,
        initialEditMode = initialEditMode,
        onInitialEditModeConsumed = onInitialEditModeConsumed,
        onPickRecent = { entry ->
            val serverId = readyServerId?.takeIf { it.isNotBlank() }
            when (entry) {
                is MobileRecentlyUsedEntry.Artifact -> {
                    log("app_shell_server_menu_recent_pick kind=artifact id=${entry.id}")
                    sharedStores.mobileRecentlyUsedStore.recordArtifact(
                        accountId = readyAccountId,
                        serverId = serverId,
                        artifactId = entry.id,
                        title = entry.title,
                    )
                    artifactOpener.open(
                        target = ArtifactLinkTarget(entry.id),
                        header = null,
                        source = "mobile_server_panel_recent",
                    )
                }
                is MobileRecentlyUsedEntry.App -> {
                    log("app_shell_server_menu_recent_pick kind=app clientId=${entry.clientId}")
                    sharedStores.mobileRecentlyUsedStore.recordApp(
                        accountId = readyAccountId,
                        serverId = serverId,
                        clientId = entry.clientId,
                        name = entry.name,
                        iconUrl = entry.iconUrl,
                        homepageUrl = entry.homepageUrl,
                    )
                    val scopeOrigin = raftAppEntryOriginOf(entry.homepageUrl)
                    if (scopeOrigin != null) {
                        linkNavigator.openAppEntry(
                            entry.homepageUrl,
                            RaftAppEntryRequest(
                                scopeOrigin = scopeOrigin,
                                appName = entry.name,
                                preferredServerId = serverId,
                                appLogoUrl = resolveAppIconUrl(entry.iconUrl),
                            ),
                        )
                    } else {
                        linkNavigator.open(entry.homepageUrl)
                    }
                }
            }
        },
        onSaveOrder = { visibleOrder ->
            val serverId = readyServerId?.takeIf { it.isNotBlank() }
            if (serverId != null) {
                log("app_shell_mobile_places_edit_save serverId=$serverId count=${visibleOrder.size}")
                scope.launch { mobilePlacesShell.store.saveOrder(serverId, visibleOrder) }
            }
        },
        onResetToDefault = {
            val serverId = readyServerId?.takeIf { it.isNotBlank() }
            if (serverId != null) {
                log("app_shell_mobile_places_edit_reset serverId=$serverId")
                scope.launch { mobilePlacesShell.store.reset(serverId) }
            }
        },
    )
}

/**
 * "Recently used" section inside the phone Server Tab Popover (Kirby #415 v8 §7):
 * omitted completely (including its separator) when [items] is empty; shows up to 4 rows
 * (Artifact or Raft App icon + single-line title), no empty state and no "All" link.
 */
@Composable
internal fun RaftMobilePlacesRecentlyUsedSection(
    items: List<MobileRecentlyUsedEntry>,
    state: RaftMobileNavServerPopoverState,
    resolveAppIconUrl: (String?) -> String?,
    onPick: (MobileRecentlyUsedEntry) -> Unit,
) {
    val shown = items.take(MOBILE_RECENTLY_USED_MAX_ENTRIES)
    if (shown.isEmpty()) return
    val colors = RaftTheme.colors
    RaftMobileNavServerPopoverSeparator()
    RaftMobileNavServerPopoverGroup(modifier = Modifier.testTag(RaftMobilePlacesPopoverTags.RECENTLY_USED_SECTION)) {
        RaftText(
            text = stringResource(Res.string.server_panel_recently_used),
            style = RaftTheme.typography.footnote.copy(fontWeight = FontWeight.Bold),
            color = colors.foregroundMuted,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
        shown.forEach { entry ->
            key(entry.placeKey) {
                RaftMobileNavServerPopoverRow(
                    label = entry.title,
                    onClick = {
                        state.dismiss()
                        onPick(entry)
                    },
                    leading = {
                        when (entry) {
                            is MobileRecentlyUsedEntry.Artifact -> {
                                RaftLucideIcon(
                                    name = RaftLucideIconName.Artifact,
                                    color = raftArgbHex(colors.foregroundMuted),
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                            is MobileRecentlyUsedEntry.App -> {
                                val logoUrl = resolveAppIconUrl(entry.iconUrl)
                                if (logoUrl != null) {
                                    RaftConnectedAppLogo(
                                        name = entry.name,
                                        logoUrl = logoUrl,
                                        size = RaftAvatarSize.Xs,
                                    )
                                } else {
                                    RaftLucideIcon(
                                        name = RaftLucideIconName.Blocks,
                                        color = raftArgbHex(colors.foregroundMuted),
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                    },
                )
            }
        }
    }
}

/** "More" heading on left + "Edit" action button on right above the More destinations grid. */
@Composable
internal fun RaftMobilePlacesMoreHeader(
    onEditClick: () -> Unit,
) {
    val colors = RaftTheme.colors
    Row(
        modifier =
            Modifier
                .testTag(RaftMobilePlacesPopoverTags.MORE_HEADER)
                .fillMaxWidth()
                .padding(start = 14.dp, end = 6.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RaftText(
            text = stringResource(Res.string.tab_more),
            style = RaftTheme.typography.footnote.copy(fontWeight = FontWeight.Bold),
            color = colors.foregroundMuted,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        RaftButton(
            text = stringResource(Res.string.common_edit),
            onClick = onEditClick,
            variant = RaftButtonVariant.Ghost,
            size = RaftButtonSize.Md,
            widenTouchTarget = true,
            modifier = Modifier.testTag(RaftMobilePlacesPopoverTags.EDIT_BUTTON),
        )
    }
}

@Stable
private class MobilePlacesEditDragState {
    val slotBounds = mutableStateMapOf<MobilePlacesSlotKey, Rect>()
    var containerCoordinates: LayoutCoordinates? = null
    var draggedPlaceId by mutableStateOf<String?>(null)
        private set
    var draggedPointerRoot by mutableStateOf(Offset.Zero)
        private set
    var dragTileSize by mutableStateOf(Size.Zero)
        private set
    var hoverTarget by mutableStateOf<MobilePlacesDropTarget?>(null)
        private set

    fun registerSlot(key: MobilePlacesSlotKey, bounds: Rect) {
        if (slotBounds[key] != bounds) slotBounds[key] = bounds
    }

    fun unregisterSlot(key: MobilePlacesSlotKey) {
        slotBounds.remove(key)
    }

    fun startDrag(placeId: String, pointerRoot: Offset, tileSize: Size) {
        draggedPlaceId = placeId
        draggedPointerRoot = pointerRoot
        dragTileSize = tileSize
        hoverTarget = findMobilePlacesDropTarget(pointerRoot, slotBounds)
    }

    /** Updates drag position; returns true if [hoverTarget] moved to a new slot. */
    fun updateDrag(pointerRoot: Offset, gapTolerancePx: Float): Boolean {
        if (draggedPlaceId == null) return false
        draggedPointerRoot = pointerRoot
        val next = findMobilePlacesDropTarget(pointerRoot, slotBounds, gapTolerancePx = gapTolerancePx)
        val changed = next != hoverTarget
        hoverTarget = next
        return changed
    }

    fun endDrag(): Pair<String, MobilePlacesDropTarget?>? {
        val placeId = draggedPlaceId ?: return null
        val target = hoverTarget
        clear()
        return placeId to target
    }

    fun cancelDrag() {
        clear()
    }

    private fun clear() {
        draggedPlaceId = null
        draggedPointerRoot = Offset.Zero
        dragTileSize = Size.Zero
        hoverTarget = null
    }
}

/**
 * In-popover "Edit navigation bar" view (Kirby #415 v8 §7):
 * stays inside the Server Tab Popover so the live phone tab bar underneath updates immediately.
 * - Header: "Edit navigation bar" + Reset + Done.
 * - Section 1: "Tab bar" + "Up to 4 · your server stays last" (4-column dashed tiles with `−`).
 * - Section 2: "More" + "In this order · Settings stays last" (4-column dashed tiles with `+`,
 *   plus static immovable `Settings` tile last).
 * - Cross-section drag-and-drop + `−`/`+` buttons + `Move left`/`Move right` a11y actions.
 * - Bottom helper copy.
 */
@Composable
internal fun RaftMobilePlacesEditPopoverBody(
    editState: MobilePlacesEditState,
    pageLabels: Map<RaftServerMenuEntry, String>,
    onCommitEdit: (MobilePlacesEditState) -> Unit,
    onResetToDefault: () -> Unit,
    onDone: () -> Unit,
) {
    val colors = RaftTheme.colors
    val density = LocalDensity.current
    val dragState = remember { MobilePlacesEditDragState() }
    val moveLeftLabel = stringResource(Res.string.mobile_nav_edit_move_left)
    val moveRightLabel = stringResource(Res.string.mobile_nav_edit_move_right)

    Box(
        modifier =
            Modifier
                .testTag(RaftMobilePlacesPopoverTags.EDIT_SURFACE)
                .fillMaxWidth()
                .onGloballyPositioned { dragState.containerCoordinates = it },
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header: title + Reset + Done
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                RaftText(
                    text = stringResource(Res.string.rail_places_edit_title),
                    style = RaftTheme.typography.subhead.copy(fontWeight = FontWeight.Bold),
                    color = colors.foreground,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                RaftButton(
                    text = stringResource(Res.string.mobile_nav_edit_reset),
                    onClick = onResetToDefault,
                    variant = RaftButtonVariant.Ghost,
                    size = RaftButtonSize.Md,
                    widenTouchTarget = true,
                    modifier = Modifier.testTag(RaftMobilePlacesPopoverTags.EDIT_RESET_BUTTON),
                )
                RaftButton(
                    text = stringResource(Res.string.common_done),
                    onClick = onDone,
                    variant = RaftButtonVariant.Primary,
                    size = RaftButtonSize.Md,
                    widenTouchTarget = true,
                    modifier = Modifier.testTag(RaftMobilePlacesPopoverTags.EDIT_DONE_BUTTON),
                )
            }

            RaftMobileNavServerPopoverSeparator()

            // Section 1: Tab bar
            MobilePlacesEditSectionHeader(
                title = stringResource(Res.string.mobile_nav_edit_tab_bar),
            )
            MobilePlacesEditGrid(
                columns = editState.maxTabBarCount,
                gap = MobilePlacesEditGridGap,
                modifier = Modifier.testTag(RaftMobilePlacesPopoverTags.EDIT_TAB_BAR_GRID).padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                editState.tabBarIds.forEachIndexed { index, placeId ->
                    val entry = raftServerMenuEntryForMobilePlace(placeId) ?: return@forEachIndexed
                    val label = pageLabels[entry] ?: placeId
                    val slotKey = MobilePlacesSlotKey(MobilePlacesEditSection.TabBar, index, placeId)
                    key("tab:$placeId") {
                        MobilePlacesEditableTile(
                            placeId = placeId,
                            entry = entry,
                            label = label,
                            slotKey = slotKey,
                            editState = editState,
                            dragState = dragState,
                            badgeGlyph = RaftGlyph.Minus,
                            badgeEnabled = editState.canRemoveFromTabBar,
                            badgeContentDescription = stringResource(Res.string.mobile_nav_edit_remove_place, label),
                            badgeTestTag = RaftMobilePlacesPopoverTags.removeBadge(placeId),
                            tileTestTag = RaftMobilePlacesPopoverTags.tabTile(placeId),
                            moveLeftLabel = moveLeftLabel,
                            moveRightLabel = moveRightLabel,
                            onBadgeClick = { onCommitEdit(editState.removeFromTabBar(placeId)) },
                            onCommitEdit = onCommitEdit,
                        )
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            RaftMobileNavServerPopoverSeparator()

            // Section 2: More (+ static Settings last)
            MobilePlacesEditSectionHeader(
                title = stringResource(Res.string.tab_more),
            )
            MobilePlacesEditGrid(
                columns = (editState.moreIds.size + 1).coerceIn(1, AppShellPhoneMoreGridColumns),
                gap = MobilePlacesEditGridGap,
                modifier = Modifier.testTag(RaftMobilePlacesPopoverTags.EDIT_MORE_GRID).padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                editState.moreIds.forEachIndexed { index, placeId ->
                    val entry = raftServerMenuEntryForMobilePlace(placeId) ?: return@forEachIndexed
                    val label = pageLabels[entry] ?: placeId
                    val slotKey = MobilePlacesSlotKey(MobilePlacesEditSection.More, index, placeId)
                    key("more:$placeId") {
                        MobilePlacesEditableTile(
                            placeId = placeId,
                            entry = entry,
                            label = label,
                            slotKey = slotKey,
                            editState = editState,
                            dragState = dragState,
                            badgeGlyph = RaftGlyph.Plus,
                            badgeEnabled = true,
                            badgeContentDescription = stringResource(Res.string.mobile_nav_edit_add_place, label),
                            badgeTestTag = RaftMobilePlacesPopoverTags.addBadge(placeId),
                            tileTestTag = RaftMobilePlacesPopoverTags.moreTile(placeId),
                            moveLeftLabel = moveLeftLabel,
                            moveRightLabel = moveRightLabel,
                            onBadgeClick = { onCommitEdit(editState.addToTabBar(placeId)) },
                            onCommitEdit = onCommitEdit,
                        )
                    }
                }
                MobilePlacesStaticSettingsTile(
                    label = pageLabels[RaftServerMenuEntry.Settings] ?: stringResource(Res.string.tab_settings),
                )
            }

            // Bottom helper copy
            RaftText(
                text = stringResource(Res.string.mobile_nav_edit_helper),
                style = RaftTheme.typography.caption,
                color = colors.foregroundMuted,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }

        // Floating dragged tile copy following the pointer across Tab bar and More.
        val draggedId = dragState.draggedPlaceId
        val containerCoords = dragState.containerCoordinates
        if (draggedId != null && containerCoords != null && containerCoords.isAttached) {
            val draggedEntry = raftServerMenuEntryForMobilePlace(draggedId)
            if (draggedEntry != null) {
                val localPointer = dragState.draggedPointerRoot - containerCoords.positionInRoot()
                val tileWidthPx = dragState.dragTileSize.width.takeIf { it > 0f } ?: with(density) { 76.dp.toPx() }
                val tileHeightPx = dragState.dragTileSize.height.takeIf { it > 0f } ?: with(density) { 64.dp.toPx() }
                val tileWidthDp = with(density) { tileWidthPx.toDp() }
                MobilePlacesFloatingDraggedTile(
                    entry = draggedEntry,
                    label = pageLabels[draggedEntry] ?: draggedId,
                    width = tileWidthDp,
                    modifier =
                        Modifier
                            .zIndex(10f)
                            .graphicsLayer {
                                translationX = localPointer.x - tileWidthPx / 2f
                                translationY = localPointer.y - tileHeightPx / 2f
                                scaleX = 1.04f
                                scaleY = 1.04f
                            },
                )
            }
        }
    }
}

@Composable
private fun MobilePlacesEditSectionHeader(
    title: String,
) {
    val colors = RaftTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RaftText(
            text = title,
            style = RaftTheme.typography.footnote.copy(fontWeight = FontWeight.Bold),
            color = colors.foreground,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun MobilePlacesEditableTile(
    placeId: String,
    entry: RaftServerMenuEntry,
    label: String,
    slotKey: MobilePlacesSlotKey,
    editState: MobilePlacesEditState,
    dragState: MobilePlacesEditDragState,
    badgeGlyph: RaftGlyph,
    badgeEnabled: Boolean,
    badgeContentDescription: String,
    badgeTestTag: String,
    tileTestTag: String,
    moveLeftLabel: String,
    moveRightLabel: String,
    onBadgeClick: () -> Unit,
    onCommitEdit: (MobilePlacesEditState) -> Unit,
) {
    val colors = RaftTheme.colors
    val haptics = rememberRaftHaptics()
    val latestEditState by rememberUpdatedState(editState)
    val latestCommitEdit by rememberUpdatedState(onCommitEdit)
    val coordinatesHolder = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val isDragged = dragState.draggedPlaceId == placeId
    val isDropTarget =
        dragState.draggedPlaceId != null &&
            !isDragged &&
            dragState.hoverTarget == MobilePlacesDropTarget(slotKey.section, slotKey.index)
    val canMoveLeft = editState.canMoveLeft(placeId)
    val canMoveRight = editState.canMoveRight(placeId)

    DisposableEffect(dragState, slotKey) {
        onDispose { dragState.unregisterSlot(slotKey) }
    }

    val borderColor = if (isDropTarget) colors.brand else colors.lineStrong
    val fillColor = if (isDropTarget) colors.brandSoft else colors.layerCard
    val shape = RoundedCornerShape(RaftRadius.Chip)

    Box(
        modifier =
            Modifier
                .testTag(tileTestTag)
                .onGloballyPositioned { coords ->
                    coordinatesHolder[0] = coords
                    dragState.registerSlot(slotKey, coords.boundsInRoot())
                }
                .alpha(if (isDragged) 0.35f else 1f)
                .background(fillColor, shape)
                .dashedRoundedBorder(
                    color = borderColor,
                    strokeWidth = if (isDropTarget) RaftStroke.Control else RaftStroke.Hairline,
                    cornerRadius = RaftRadius.Chip,
                )
                .pointerInput(placeId, dragState) {
                    val dragStartPx = MobilePlacesEditDragStartDistance.toPx()
                    val gapTolerancePx = MobilePlacesEditDropGapTolerance.toPx()
                    fun toRoot(local: Offset): Offset? =
                        coordinatesHolder[0]?.takeIf { it.isAttached }?.localToRoot(local)
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (dragState.draggedPlaceId != null) return@awaitEachGesture
                        val downRoot = toRoot(down.position) ?: return@awaitEachGesture
                        var activated = false
                        var latestRoot = downRoot
                        try {
                            withTimeout(RaftReorderDefaults.HoldMillis) {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Main)
                                    if (event.changes.count { it.pressed } > 1) break
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) break
                                    val currentRoot = toRoot(change.position) ?: break
                                    latestRoot = currentRoot
                                    if ((currentRoot - downRoot).getDistance() > dragStartPx) {
                                        activated = true
                                        break
                                    }
                                }
                            }
                        } catch (_: PointerEventTimeoutCancellationException) {
                            activated = true
                        }
                        if (!activated) return@awaitEachGesture
                        val coords = coordinatesHolder[0]?.takeIf { it.isAttached } ?: return@awaitEachGesture
                        haptics.longPress()
                        dragState.startDrag(
                            placeId = placeId,
                            pointerRoot = latestRoot,
                            tileSize = Size(coords.size.width.toFloat(), coords.size.height.toFloat()),
                        )
                        try {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                change.consume()
                                val currentRoot = toRoot(change.position)
                                if (currentRoot != null && dragState.updateDrag(currentRoot, gapTolerancePx)) {
                                    haptics.tick()
                                }
                                if (!change.pressed) {
                                    val finished = dragState.endDrag()
                                    val dropTarget = finished?.second
                                    if (finished != null && dropTarget != null) {
                                        val next =
                                            latestEditState.moveAcrossSections(
                                                placeId = finished.first,
                                                targetSection = dropTarget.section,
                                                targetIndex = dropTarget.index,
                                            )
                                        if (next != latestEditState) {
                                            latestCommitEdit(next)
                                        }
                                    }
                                    return@awaitEachGesture
                                }
                            }
                        } finally {
                            if (dragState.draggedPlaceId == placeId) {
                                dragState.cancelDrag()
                            }
                        }
                    }
                }
                .semantics {
                    contentDescription = label
                    val actions = buildList {
                        if (canMoveLeft) {
                            add(
                                CustomAccessibilityAction(moveLeftLabel) {
                                    val next = latestEditState.moveLeft(placeId)
                                    if (next != latestEditState) {
                                        latestCommitEdit(next)
                                        true
                                    } else {
                                        false
                                    }
                                },
                            )
                        }
                        if (canMoveRight) {
                            add(
                                CustomAccessibilityAction(moveRightLabel) {
                                    val next = latestEditState.moveRight(placeId)
                                    if (next != latestEditState) {
                                        latestCommitEdit(next)
                                        true
                                    } else {
                                        false
                                    }
                                },
                            )
                        }
                    }
                    if (actions.isNotEmpty()) customActions = actions
                },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            RaftLucideIcon(
                name = raftServerMenuEntryIcon(entry),
                color = raftArgbHex(colors.foregroundMuted),
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.height(4.dp))
            RaftText(
                text = label,
                style = RaftTheme.typography.footnote.copy(fontWeight = FontWeight.Medium),
                color = colors.foreground,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clearAndSetSemantics { },
            )
        }
        // Top-right corner − / + action badge
        Box(
            modifier =
                Modifier
                    .testTag(badgeTestTag)
                    .align(Alignment.TopEnd)
                    .padding(3.dp)
                    .size(MobilePlacesEditBadgeDiameter)
                    .background(
                        color = if (badgeEnabled) colors.layerPopover else colors.fillMuted,
                        shape = CircleShape,
                    )
                    .border(
                        width = RaftStroke.Hairline,
                        color = if (badgeEnabled) colors.lineStrong else colors.lineHairline,
                        shape = CircleShape,
                    )
                    .raftClickable(
                        onClick = onBadgeClick,
                        enabled = badgeEnabled,
                        role = Role.Button,
                        shape = CircleShape,
                    )
                    .semantics(mergeDescendants = true) {
                        contentDescription = badgeContentDescription
                    },
            contentAlignment = Alignment.Center,
        ) {
            RaftGlyphIcon(
                glyph = badgeGlyph,
                size = 11.dp,
                color = if (badgeEnabled) colors.foreground else colors.foregroundDisabled,
            )
        }
    }
}

/** Static, non-draggable, non-droppable Settings tile anchored last in the More grid. */
@Composable
private fun MobilePlacesStaticSettingsTile(label: String) {
    val colors = RaftTheme.colors
    val shape = RoundedCornerShape(RaftRadius.Chip)
    Column(
        modifier =
            Modifier
                .testTag(RaftMobilePlacesPopoverTags.EDIT_SETTINGS_TILE)
                .fillMaxWidth()
                .alpha(RaftOpacity.Dimmed)
                .background(colors.fillSubtle, shape)
                .border(RaftStroke.Hairline, colors.lineHairline, shape)
                .padding(horizontal = 4.dp, vertical = 10.dp)
                .semantics(mergeDescendants = true) {
                    contentDescription = label
                },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        RaftLucideIcon(
            name = raftServerMenuEntryIcon(RaftServerMenuEntry.Settings),
            color = raftArgbHex(colors.foregroundHint),
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.height(4.dp))
        RaftText(
            text = label,
            style = RaftTheme.typography.footnote.copy(fontWeight = FontWeight.Medium),
            color = colors.foregroundMuted,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}

@Composable
private fun MobilePlacesFloatingDraggedTile(
    entry: RaftServerMenuEntry,
    label: String,
    width: Dp,
    modifier: Modifier = Modifier,
) {
    val colors = RaftTheme.colors
    val shape = RoundedCornerShape(RaftRadius.Chip)
    Column(
        modifier =
            modifier
                .width(width)
                .shadow(elevation = 8.dp, shape = shape)
                .background(colors.layerPopover, shape)
                .border(RaftStroke.Control, colors.lineStrong, shape)
                .padding(horizontal = 4.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        RaftLucideIcon(
            name = raftServerMenuEntryIcon(entry),
            color = raftArgbHex(colors.foreground),
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.height(4.dp))
        RaftText(
            text = label,
            style = RaftTheme.typography.footnote.copy(fontWeight = FontWeight.SemiBold),
            color = colors.foreground,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Equal-width wrapping grid (4 columns) for Edit navigation bar's Tab bar and More sections. */
@Composable
private fun MobilePlacesEditGrid(
    columns: Int,
    gap: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier.fillMaxWidth()) { measurables, constraints ->
        if (measurables.isEmpty()) return@Layout layout(constraints.minWidth, constraints.minHeight) {}
        val cols = columns.coerceAtLeast(1)
        val gapPx = gap.roundToPx()
        val width = constraints.maxWidth
        val free = (width - gapPx * (cols - 1)).coerceAtLeast(0)
        val widths = List(cols) { free / cols + if (it < free % cols) 1 else 0 }
        val lefts = widths.runningFold(0) { left, cell -> left + cell + gapPx }
        val placeables =
            measurables.mapIndexed { index, measurable ->
                measurable.measure(Constraints.fixedWidth(widths[index % cols]))
            }
        val rows = placeables.chunked(cols)
        val rowHeights = rows.map { row -> row.maxOf { it.height } }
        val height = (rowHeights.sum() + gapPx * (rows.size - 1)).coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height) {
            var top = 0
            rows.forEachIndexed { r, row ->
                row.forEachIndexed { c, placeable -> placeable.placeRelative(lefts[c], top) }
                top += rowHeights[r] + gapPx
            }
        }
    }
}

private fun Modifier.dashedRoundedBorder(
    color: Color,
    strokeWidth: Dp,
    cornerRadius: Dp,
): Modifier =
    drawBehind {
        val strokePx = strokeWidth.toPx()
        val half = strokePx / 2f
        val dashPx = 4.dp.toPx()
        val gapPx = 3.dp.toPx()
        drawRoundRect(
            color = color,
            topLeft = Offset(half, half),
            size = Size((size.width - strokePx).coerceAtLeast(0f), (size.height - strokePx).coerceAtLeast(0f)),
            cornerRadius = CornerRadius(cornerRadius.toPx(), cornerRadius.toPx()),
            style =
                Stroke(
                    width = strokePx,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(dashPx, gapPx), 0f),
                ),
        )
    }

private val MobilePlacesEditGridGap = 6.dp
private val MobilePlacesEditBadgeDiameter = 20.dp
private val MobilePlacesEditDragStartDistance = 8.dp
private val MobilePlacesEditDropGapTolerance = 16.dp
