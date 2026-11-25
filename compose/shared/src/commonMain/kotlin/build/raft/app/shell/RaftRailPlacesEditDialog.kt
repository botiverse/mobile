package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.RaftRailPlace
import build.raft.app.data.RaftRailPlaceApp
import build.raft.app.data.RaftRailPlacesSnapshot
import build.raft.app.data.RailPlacesSaveOutcome
import build.raft.app.data.SharedRailPlacesStore
import build.raft.app.data.isRaftRailBuiltInPlaceId
import build.raft.app.data.raftRailAppsNotOnList
import build.raft.app.data.raftRailBuiltInsNotOnList
import build.raft.app.data.raftRailPlacesForKeys
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftAvatarSize
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftConversationKindGlyph
import build.raft.designsystem.components.RaftConversationKindIcon
import build.raft.designsystem.components.RaftDialog
import build.raft.designsystem.components.RaftDialogSize
import build.raft.designsystem.components.RaftGlyph
import build.raft.designsystem.components.RaftGlyphIcon
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftIconButtonSize
import build.raft.designsystem.components.RaftSeparator
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** An App place on [serverId] that was no longer usable when tapped (web `UnavailableRailApp`). */
internal data class RaftUnavailableRailApp(
    val serverId: String,
    val clientId: String,
    val appName: String,
    val serverName: String,
)

/**
 * Opens an installed App place tapped on the wide rail (botiverse/slock #16684): checks 15 s
 * freshness via [railPlacesStore], opens the App's homepage in Raft's authenticated web container
 * when still placeable, or reports [onUnavailable] when the App is no longer available here.
 */
internal fun appShellOpenRailAppPlace(
    scope: CoroutineScope,
    railPlacesStore: SharedRailPlacesStore,
    linkNavigator: RaftLinkNavigator,
    serverId: String,
    serverName: String,
    clientId: String,
    fallbackAppName: String,
    resolveIconUrl: (String?) -> String?,
    onUnavailable: (RaftUnavailableRailApp) -> Unit,
    log: (String) -> Unit,
) {
    scope.launch {
        val usable = railPlacesStore.ensureFreshAndResolveApp(serverId, clientId)
        if (usable == null) {
            log("app_shell_rail_app_place_unavailable serverId=$serverId clientId=$clientId")
            onUnavailable(
                RaftUnavailableRailApp(
                    serverId = serverId,
                    clientId = clientId,
                    appName = fallbackAppName,
                    serverName = serverName,
                ),
            )
            return@launch
        }
        val scopeOrigin = raftAppEntryOriginOf(usable.homepageUrl)
        log("app_shell_rail_app_place_open serverId=$serverId clientId=${usable.clientId}")
        if (scopeOrigin != null) {
            linkNavigator.openAppEntry(
                usable.homepageUrl,
                RaftAppEntryRequest(
                    scopeOrigin = scopeOrigin,
                    appName = usable.name,
                    preferredServerId = serverId,
                    appLogoUrl = resolveIconUrl(usable.iconUrl),
                ),
            )
        } else {
            linkNavigator.open(usable.homepageUrl)
        }
    }
}

/**
 * Edit navigation bar dialog for per-server rail places (web `EditNavigationBarDialog.tsx`,
 * botiverse/slock #16684): Chat and Activity are shown fixed first; editable places can be moved
 * up/down or removed, and remaining visible built-ins plus `placeableApps` can be added.
 * On a 409 conflict the dialog stays open with the server's latest list and an inline notice.
 */
@Composable
internal fun RaftRailPlacesEditDialog(
    open: Boolean,
    snapshot: RaftRailPlacesSnapshot?,
    visibleBuiltInItems: List<RaftBottomTabItemPayload>,
    resolveIconUrl: (String?) -> String?,
    onDismiss: () -> Unit,
    onSave: suspend (List<String>) -> RailPlacesSaveOutcome,
) {
    if (!open || snapshot == null) return
    val visibleBuiltInByPlaceId =
        remember(visibleBuiltInItems) {
            visibleBuiltInItems
                .filter { it.key !in RaftRailPlacesFixedItemKeys }
                .mapNotNull { item -> raftRailTabIdForKey(item.key)?.let { id -> id to item } }
                .toMap()
        }
    val snapshotKeys = remember(snapshot.places) { snapshot.places.map { it.key } }
    var draftKeys by remember(open) { mutableStateOf(snapshotKeys) }
    var conflictNotice by remember(open) { mutableStateOf(false) }
    var saving by remember(open) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(open, snapshot.placesVersion) {
        if (conflictNotice) {
            draftKeys = snapshot.places.map { it.key }
        }
    }

    val draftPlaces =
        remember(draftKeys, snapshot.places, snapshot.placeableApps) {
            raftRailPlacesForKeys(draftKeys, snapshot.places, snapshot.placeableApps)
        }
    val visibleDraftPlaces =
        remember(draftPlaces, visibleBuiltInByPlaceId) {
            draftPlaces.filter { place -> !place.isBuiltIn || place.key in visibleBuiltInByPlaceId }
        }
    val addableBuiltIns =
        remember(draftKeys, visibleBuiltInByPlaceId) {
            raftRailBuiltInsNotOnList(draftKeys) { id -> id in visibleBuiltInByPlaceId }
        }
    val addableApps =
        remember(draftKeys, snapshot.placeableApps) {
            raftRailAppsNotOnList(draftKeys, snapshot.placeableApps)
        }
    val hasChanges = draftKeys != snapshotKeys

    fun moveVisible(key: String, direction: Int) {
        val visibleKeys = visibleDraftPlaces.map { it.key }
        val index = visibleKeys.indexOf(key)
        val neighbour = visibleKeys.getOrNull(index + direction) ?: return
        val from = draftKeys.indexOf(key)
        val to = draftKeys.indexOf(neighbour)
        if (from < 0 || to < 0 || from == to) return
        val next = draftKeys.toMutableList()
        val moved = next.removeAt(from)
        next.add(to, moved)
        draftKeys = next
        conflictNotice = false
    }

    RaftDesignSystemTheme {
        RaftDialog(
            onDismissRequest = { if (!saving) onDismiss() },
            title = stringResource(Res.string.rail_places_edit_title),
            size = RaftDialogSize.Md,
            footer = {
                RaftButton(
                    text = stringResource(Res.string.common_cancel),
                    onClick = onDismiss,
                    variant = RaftButtonVariant.Ghost,
                    size = RaftButtonSize.Lg,
                    enabled = !saving,
                )
                Spacer(Modifier.width(8.dp))
                RaftButton(
                    text = stringResource(Res.string.rail_places_edit_save),
                    onClick = {
                        if (saving) return@RaftButton
                        saving = true
                        scope.launch {
                            try {
                                when (onSave(draftKeys)) {
                                    RailPlacesSaveOutcome.Saved -> onDismiss()
                                    RailPlacesSaveOutcome.Conflict -> conflictNotice = true
                                    RailPlacesSaveOutcome.Failed,
                                    RailPlacesSaveOutcome.Skipped -> Unit
                                }
                            } finally {
                                saving = false
                            }
                        }
                    },
                    variant = RaftButtonVariant.Primary,
                    size = RaftButtonSize.Lg,
                    enabled = !saving && hasChanges,
                    loading = saving,
                )
            },
        ) {
            val colors = RaftTheme.colors
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (conflictNotice) {
                    RaftText(
                        text = stringResource(Res.string.rail_places_edit_conflict),
                        style = RaftTheme.typography.callout,
                        color = colors.foreground,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .background(colors.layerCard, RoundedCornerShape(8.dp))
                                .border(1.dp, colors.lineStrong, RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }

                RaftText(
                    text = stringResource(Res.string.rail_places_edit_on_bar),
                    style = RaftTheme.typography.caption.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.foregroundMuted,
                )

                val fixedLabel = stringResource(Res.string.rail_places_edit_fixed)
                FixedPlaceRow(
                    label = stringResource(Res.string.tab_home),
                    fixedBadge = fixedLabel,
                    icon = { RaftConversationKindIcon(RaftConversationKindGlyph.Chat, size = 18.dp, color = colors.foregroundMuted) },
                )
                FixedPlaceRow(
                    label = stringResource(Res.string.tab_activity),
                    fixedBadge = fixedLabel,
                    icon = { RaftContentLucideIcon(RaftLucideIconName.Bell, size = 18.dp) },
                )

                visibleDraftPlaces.forEachIndexed { index, place ->
                    val label =
                        if (place.isBuiltIn) {
                            visibleBuiltInByPlaceId[place.key]?.label ?: place.key
                        } else {
                            place.app?.name ?: place.key
                        }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PlaceRowLeadingIcon(
                            place = place,
                            builtIn = visibleBuiltInByPlaceId[place.key],
                            resolveIconUrl = resolveIconUrl,
                        )
                        RaftText(
                            text = label,
                            style = RaftTheme.typography.callout,
                            color = colors.foreground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        RaftIconButton(
                            onClick = { moveVisible(place.key, -1) },
                            tooltip = stringResource(Res.string.rail_places_edit_move_up, label),
                            variant = RaftButtonVariant.Ghost,
                            size = RaftIconButtonSize.Xs,
                            enabled = !saving && index > 0,
                        ) {
                            RaftGlyphIcon(RaftGlyph.ArrowUp, size = 16.dp, color = colors.foregroundMuted)
                        }
                        RaftIconButton(
                            onClick = { moveVisible(place.key, 1) },
                            tooltip = stringResource(Res.string.rail_places_edit_move_down, label),
                            variant = RaftButtonVariant.Ghost,
                            size = RaftIconButtonSize.Xs,
                            enabled = !saving && index < visibleDraftPlaces.lastIndex,
                        ) {
                            RaftGlyphIcon(RaftGlyph.ArrowDown, size = 16.dp, color = colors.foregroundMuted)
                        }
                        RaftIconButton(
                            onClick = {
                                draftKeys = draftKeys.filter { it != place.key }
                                conflictNotice = false
                            },
                            tooltip = stringResource(Res.string.rail_places_edit_remove, label),
                            variant = RaftButtonVariant.Ghost,
                            size = RaftIconButtonSize.Xs,
                            enabled = !saving,
                        ) {
                            RaftGlyphIcon(RaftGlyph.X, size = 16.dp, color = colors.foregroundMuted)
                        }
                    }
                }

                if (addableBuiltIns.isNotEmpty() || addableApps.isNotEmpty()) {
                    RaftSeparator(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                    RaftText(
                        text = stringResource(Res.string.rail_places_edit_add),
                        style = RaftTheme.typography.caption.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.foregroundMuted,
                    )
                    addableBuiltIns.forEach { key ->
                        val builtIn = visibleBuiltInByPlaceId[key] ?: return@forEach
                        AddablePlaceRow(
                            label = builtIn.label,
                            enabled = !saving,
                            icon = {
                                PlaceRowLeadingIcon(
                                    place = RaftRailPlace.builtIn(key),
                                    builtIn = builtIn,
                                    resolveIconUrl = resolveIconUrl,
                                )
                            },
                            onAdd = {
                                if (key !in draftKeys) {
                                    draftKeys = draftKeys + key
                                    conflictNotice = false
                                }
                            },
                        )
                    }
                    addableApps.forEach { app ->
                        val place = RaftRailPlace.app(app)
                        AddablePlaceRow(
                            label = app.name,
                            enabled = !saving,
                            icon = {
                                PlaceRowLeadingIcon(
                                    place = place,
                                    builtIn = null,
                                    resolveIconUrl = resolveIconUrl,
                                )
                            },
                            onAdd = {
                                if (place.key !in draftKeys) {
                                    draftKeys = draftKeys + place.key
                                    conflictNotice = false
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

/** Dialog shown when a tapped App place is no longer usable on the server (web `RailPlaceUnavailable`). */
@Composable
internal fun RaftRailUnavailableAppDialog(
    unavailable: RaftUnavailableRailApp?,
    onDismiss: () -> Unit,
    onGoToApps: () -> Unit,
) {
    if (unavailable == null) return
    RaftDesignSystemTheme {
        RaftDialog(
            onDismissRequest = onDismiss,
            title = stringResource(Res.string.rail_places_unavailable_title, unavailable.appName, unavailable.serverName),
            size = RaftDialogSize.Sm,
            footer = {
                RaftButton(
                    text = stringResource(Res.string.common_close),
                    onClick = onDismiss,
                    variant = RaftButtonVariant.Ghost,
                    size = RaftButtonSize.Lg,
                )
                Spacer(Modifier.width(8.dp))
                RaftButton(
                    text = stringResource(Res.string.rail_places_unavailable_go_to_apps),
                    onClick = {
                        onDismiss()
                        onGoToApps()
                    },
                    variant = RaftButtonVariant.Primary,
                    size = RaftButtonSize.Lg,
                )
            },
        )
    }
}

@Composable
private fun FixedPlaceRow(
    label: String,
    fixedBadge: String,
    icon: @Composable () -> Unit,
) {
    val colors = RaftTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        icon()
        RaftText(
            text = label,
            style = RaftTheme.typography.callout,
            color = colors.foregroundMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        RaftText(
            text = fixedBadge,
            style = RaftTheme.typography.caption,
            color = colors.foregroundMuted,
        )
    }
}

@Composable
private fun AddablePlaceRow(
    label: String,
    enabled: Boolean,
    icon: @Composable () -> Unit,
    onAdd: () -> Unit,
) {
    val colors = RaftTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        icon()
        RaftText(
            text = label,
            style = RaftTheme.typography.callout,
            color = colors.foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        RaftIconButton(
            onClick = onAdd,
            tooltip = stringResource(Res.string.rail_places_edit_add_place, label),
            variant = RaftButtonVariant.Ghost,
            size = RaftIconButtonSize.Xs,
            enabled = enabled,
        ) {
            RaftGlyphIcon(RaftGlyph.Plus, size = 16.dp, color = colors.foreground)
        }
    }
}

@Composable
private fun PlaceRowLeadingIcon(
    place: RaftRailPlace,
    builtIn: RaftBottomTabItemPayload?,
    resolveIconUrl: (String?) -> String?,
) {
    val app = place.app
    if (place.isApp && app != null) {
        RaftConnectedAppLogo(
            name = app.name,
            logoUrl = resolveIconUrl(app.iconUrl),
            size = RaftAvatarSize.Xs,
        )
    } else if (isRaftRailBuiltInPlaceId(place.key) && raftNavShowsOttie(RaftNavPresentation.Rail, place.key)) {
        HomeAssistantFace(Modifier.size(20.dp), size = 20.dp)
    } else {
        RaftContentLucideIcon(
            name = builtIn?.iconName ?: RaftLucideIconName.Blocks,
            size = 18.dp,
        )
    }
}
