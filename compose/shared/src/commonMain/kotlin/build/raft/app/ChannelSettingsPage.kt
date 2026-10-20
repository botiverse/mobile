package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import build.raft.app.base.Utils
import build.raft.app.components.RaftBrutalCard
import build.raft.app.components.RaftSwitchButton
import build.raft.app.data.HomeChannelRow
import build.raft.app.data.SharedHomeContentState
import build.raft.app.data.SharedHomeIntent
import build.raft.app.data.SharedServerChannelMembersSlice
import build.raft.app.data.SharedServerMembersLoadStatus
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.app.model.avatarWireValueForAvatarSlot
import build.raft.app.resources.MR
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import build.raft.app.compat.pager.LocalActivity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import build.raft.app.compat.pager.Page
import build.raft.app.compat.module.RouterModule
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.Pager
import com.tencent.tmm.kmmresource.compose.stringResource

internal const val CHANNEL_SETTINGS_PAGE = "compose_channel_settings"

internal data class ChannelSettingsRoute(
    val serverId: ServerId,
    val channelId: ChannelId
)

internal fun channelSettingsPageData(serverId: ServerId, channelId: ChannelId): JSONObject =
    JSONObject().apply {
        put("pageName", CHANNEL_SETTINGS_PAGE)
        put("serverId", serverId.value)
        put("channelId", channelId.value)
    }

internal fun JSONObject.toChannelSettingsRouteOrNull(): ChannelSettingsRoute? {
    val serverId = optString("serverId").trim().takeIf(String::isNotBlank)?.let(::ServerId) ?: return null
    val channelId = optString("channelId").trim().takeIf(String::isNotBlank)?.let(::ChannelId) ?: return null
    return ChannelSettingsRoute(serverId = serverId, channelId = channelId)
}

internal fun channelSettingsCanManage(content: SharedHomeContentState): Boolean =
    (content as? SharedHomeContentState.Content)?.snapshot?.server?.permissions?.canManageSettings == true

internal fun channelSettingsChannel(
    content: SharedHomeContentState,
    route: ChannelSettingsRoute
): HomeChannelRow? =
    (content as? SharedHomeContentState.Content)
        ?.snapshot
        ?.takeIf { it.server.id == route.serverId }
        ?.channels
        ?.firstOrNull { it.channelId == route.channelId }

internal fun channelSettingsSectionKeys(canManage: Boolean): List<String> =
    buildList {
        add("identity")
        add("members")
        add("preferences")
        if (canManage) add("management")
    }

internal fun channelSettingsUsesStandalonePage(newDesignSettingsEnabled: Boolean): Boolean =
    newDesignSettingsEnabled

internal fun channelSettingsIsJoint(channel: HomeChannelRow): Boolean =
    channel.kind == ConversationKind.Joint ||
        channel.channelType.equals("joint", ignoreCase = true)

internal fun channelSettingsIsPrivate(channel: HomeChannelRow): Boolean =
    channelSettingsIsJoint(channel) ||
        channel.channelType.equals("private", ignoreCase = true)

internal fun channelSettingsCanMakePrivate(channel: HomeChannelRow): Boolean =
    !channelSettingsIsPrivate(channel)

internal fun channelSettingsCanDelete(channel: HomeChannelRow): Boolean =
    !channelSettingsIsJoint(channel)

@Page(CHANNEL_SETTINGS_PAGE, supportInLocal = true)
internal class ComposeChannelSettingsPage : AuthenticatedProductPager(AuthenticatedProductRoute.ChannelSettings) {
    override fun willInit() {
        super.willInit()
        setAuthenticatedContent {
            RaftInputFocusDismissHost { focusDismissModifier ->
                Box(modifier = focusDismissModifier.fillMaxSize()) {
                    ChannelSettingsPageScreen()
                }
            }
        }
    }
}

private enum class ChannelSettingsDialog {
    Edit,
    Visibility,
    Archive,
    Delete
}

@Composable
private fun ChannelSettingsPageScreen() {
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = Utils.bridgeModule(pager.pagerId)
    val params = pager.pageData.params
    val route = remember(params.toString()) { params.toChannelSettingsRouteOrNull() }
    val router = remember(pager) { pager.acquireModule<RouterModule>(RouterModule.MODULE_NAME) }
    if (route == null) {
        ChannelSettingsInvalidRouteScreen(onBack = router::closePage)
        return
    }

    val storeScope = remember(params.toString(), pager.pagerId) {
        params.raftShellStoreScope(RaftShellStoreScope.Legacy.shellInstanceId)
    }
    val sharedStores = remember(bridge) { RaftSharedStoreRegistry.get(bridge) }
    val routeOwner = remember(params.toString()) { params.authenticatedRouteOwnerOrNull() }
    if (routeOwner == null || routeOwner.serverId != route.serverId.value) {
        ChannelSettingsInvalidRouteScreen(onBack = router::closePage)
        return
    }
    val liveOwner by
        remember(sharedStores, storeScope) {
            sharedStores.authenticatedRouteOwnerFlow(storeScope)
        }.collectAsState(sharedStores.currentAuthenticatedRouteOwner(storeScope))
    val authStore = remember(sharedStores, storeScope) { sharedStores.authStore(storeScope) }
    val authorityGateway = remember(sharedStores, storeScope) {
        sharedStores.authenticatedRouteAuthorityGateway(storeScope)
    }
    val snapshotCache = remember(sharedStores) { sharedStores.channelSettingsSnapshotCache }
    val homeStore = remember(sharedStores, storeScope) { sharedStores.sharedHomeStore(storeScope) }
    val membersStore = remember(sharedStores, storeScope) { sharedStores.serverMembersStore(storeScope) }
    val initialSharedHandoff = remember(route, routeOwner, homeStore, membersStore) {
        channelSettingsSharedHandoffSnapshot(
            owner = routeOwner,
            route = route,
            homeSnapshot = homeStore?.snapshotFor(route.serverId),
            membersHandoff = membersStore.currentChannelMembersHandoff(routeOwner, route.channelId),
        )
    }
    val scope = rememberCoroutineScope()
    val dataSource = remember(route, routeOwner, authStore, authorityGateway) {
        RealChannelSettingsDataSource(
            route = route,
            authStore = authStore,
            owner = routeOwner,
            gateway = authorityGateway,
            logger = { bridge.log("channel_settings_store $it") },
        )
    }
    val settingsStore = remember(
        route,
        routeOwner,
        dataSource,
        snapshotCache,
        sharedStores,
        storeScope,
        scope,
    ) {
        ChannelSettingsStore(
            route = route,
            owner = routeOwner,
            dataSource = dataSource,
            snapshotCache = snapshotCache,
            initialSharedHandoff = initialSharedHandoff,
            initialAuthorityAccepted =
                sharedStores.currentAuthenticatedRouteOwner(storeScope) == routeOwner,
            scope = scope,
        )
    }
    val settingsState by settingsStore.state.collectAsState()
    val authorityAccepted = liveOwner == routeOwner
    val channel = settingsState.channel.takeIf { authorityAccepted }
    val memberSlice = settingsState.memberSlice
    val canManage = settingsState.canManage
    val pinned = settingsState.isPinned(route.channelId.value)
    var dialog by remember(route) { mutableStateOf<ChannelSettingsDialog?>(null) }
    val longMessageCollapseSetting =
        rememberRaftLongMessageCollapseConversationSetting(
            serverId = route.serverId.value,
            conversationId = route.channelId.value
        )
    val updateFailedLabel = stringResource(MR.strings.thread_settings_update_failed)
    val archiveFailedLabel = stringResource(MR.strings.thread_error_archive_channel)
    val deleteFailedLabel = stringResource(MR.strings.thread_error_delete_channel)

    LaunchedEffect(settingsStore, liveOwner, routeOwner) {
        if (liveOwner == routeOwner) {
            settingsStore.dispatch(ChannelSettingsIntent.Refresh)
        } else {
            dialog = null
            settingsStore.invalidateAuthority()
        }
    }

    LaunchedEffect(settingsState.status) {
        if (settingsState.status == ChannelSettingsLoadStatus.AuthorityRejected) {
            dialog = null
        }
    }

    LaunchedEffect(settingsState.effect) {
        when (val effect = settingsState.effect) {
            ChannelSettingsEffect.ProfileSaved -> {
                dialog = null
                settingsState.channel?.let { updated ->
                    homeStore?.dispatch(
                        SharedHomeIntent.ChannelProfileUpdated(
                            channelId = updated.channelId,
                            name = updated.name.removePrefix("#"),
                            description = updated.subtitle,
                        )
                    )
                }
            }
            is ChannelSettingsEffect.Updated -> {
                if (effect.action == ChannelSettingsAction.Visibility) dialog = null
                homeStore?.dispatch(SharedHomeIntent.Refresh)
            }
            is ChannelSettingsEffect.Failure -> {
                bridge.toast(
                    when (effect.action) {
                        ChannelSettingsAction.Delete -> deleteFailedLabel
                        ChannelSettingsAction.Archive -> archiveFailedLabel
                        else -> updateFailedLabel
                    }
                )
            }
            ChannelSettingsEffect.Close -> {
                dialog = null
                homeStore?.dispatch(SharedHomeIntent.Refresh)
                router.closePage()
            }
            null -> Unit
        }
        if (settingsState.effect != null) settingsStore.dispatch(ChannelSettingsIntent.ConsumeEffect)
    }

    Box(modifier = Modifier.fillMaxSize().background(RaftColor.SurfaceCream)) {
        Column(modifier = Modifier.fillMaxSize()) {
            RaftTopPageHeader(
                title = stringResource(MR.strings.channel_settings_title),
                iconName = RaftLucideIconName.Settings,
                backgroundColor = RaftColor.SurfaceWhite,
                refreshing = settingsState.refreshing,
                onBack = router::closePage
            )
            when {
                channel != null ->
                    ChannelSettingsContent(
                        channel = channel,
                        memberSlice = memberSlice,
                        pinned = pinned,
                        longMessageCollapseSetting = longMessageCollapseSetting,
                        canManage = canManage,
                        writeActionsEnabled = settingsState.writeActionsEnabled && authorityAccepted,
                        onEdit = { dialog = ChannelSettingsDialog.Edit },
                        onOpenMembers = {
                            pager.openRaftPage(
                                pageName = THREAD_MEMBERS_PAGE,
                                pageData = channelSettingsMembersPageData(route),
                                storeScopeOverride = storeScope,
                                authenticatedRouteAuthorityRequired = true,
                            )
                        },
                        onToggleMute = { settingsStore.dispatch(ChannelSettingsIntent.ToggleMute) },
                        onTogglePin = { settingsStore.dispatch(ChannelSettingsIntent.TogglePin) },
                        onMakePrivate = { dialog = ChannelSettingsDialog.Visibility },
                        onArchive = { dialog = ChannelSettingsDialog.Archive },
                        onDelete = { dialog = ChannelSettingsDialog.Delete }
                    )
                settingsState.status == ChannelSettingsLoadStatus.Error ||
                    settingsState.status == ChannelSettingsLoadStatus.AuthorityRejected ->
                    ChannelSettingsMessage(
                        title = stringResource(MR.strings.channel_settings_unavailable),
                        detail = stringResource(MR.strings.channel_settings_unavailable_detail),
                        onRetry = { settingsStore.dispatch(ChannelSettingsIntent.Refresh) }
                    )
                else -> ChannelSettingsLoading()
            }
        }

        val currentChannel = channel
        if (currentChannel != null && dialog != null) {
            val activeDialog = dialog
            ThreadSettingsModal(
                channelName = currentChannel.name.removePrefix("#"),
                subtitle = currentChannel.subtitle,
                actions =
                    when (activeDialog) {
                        ChannelSettingsDialog.Visibility -> listOf(RaftThreadSettingsAction(id = "visibility"))
                        ChannelSettingsDialog.Archive -> listOf(RaftThreadSettingsAction(id = "archive"))
                        ChannelSettingsDialog.Delete -> listOf(RaftThreadSettingsAction(id = "delete"))
                        else -> emptyList()
                    },
                initialDangerActionId =
                    when (activeDialog) {
                        ChannelSettingsDialog.Visibility -> "visibility"
                        ChannelSettingsDialog.Archive -> "archive"
                        ChannelSettingsDialog.Delete -> "delete"
                        else -> null
                    },
                updateStatus = settingsState.profileStatus,
                updateErrorCode = settingsState.profileErrorCode,
                onDismiss = {
                    if (
                        settingsState.actionInFlight == null &&
                        settingsState.profileStatus != ChannelProfileUpdateStatus.InFlight
                    ) {
                        dialog = null
                        settingsStore.dispatch(ChannelSettingsIntent.ClearProfileStatus)
                    }
                },
                onSave = { _, _, name, description ->
                    settingsStore.dispatch(ChannelSettingsIntent.UpdateProfile(name, description))
                },
                onOpenAction = { actionId ->
                    when (actionId) {
                        "visibility" -> settingsStore.dispatch(ChannelSettingsIntent.MakePrivate)
                        "archive" -> settingsStore.dispatch(ChannelSettingsIntent.Archive)
                        "delete" -> settingsStore.dispatch(ChannelSettingsIntent.Delete)
                    }
                }
            )
        }
    }
}

internal fun channelSettingsMembersPageData(route: ChannelSettingsRoute): JSONObject =
    JSONObject().apply {
        put("pageName", THREAD_MEMBERS_PAGE)
        put("instanceId", "channel-settings-members-${route.channelId.value}")
        put("serverId", route.serverId.value)
        put("channelId", route.channelId.value)
    }

@Composable
private fun ChannelSettingsContent(
    channel: HomeChannelRow,
    memberSlice: SharedServerChannelMembersSlice,
    pinned: Boolean,
    longMessageCollapseSetting: RaftLongMessageCollapseConversationSetting,
    canManage: Boolean,
    writeActionsEnabled: Boolean,
    onEdit: () -> Unit,
    onOpenMembers: () -> Unit,
    onToggleMute: () -> Unit,
    onTogglePin: () -> Unit,
    onMakePrivate: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit
) {
    // task #177: standalone page; the last row clears the system navigation bar.
    val bottomSafeArea = raftBottomSafeAreaPadding()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 16.dp + bottomSafeArea),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item(key = "identity") {
            ChannelIdentityCard(
                channel = channel,
                editable = canManage && writeActionsEnabled,
                onClick = onEdit,
            )
        }
        item(key = "members") {
            ChannelMembersCard(slice = memberSlice, fallbackCount = channel.memberCount, onClick = onOpenMembers)
        }
        item(key = "preferences") {
            ChannelPreferencesCard(
                muteSupported = channel.activityMuteSupported,
                muted = channel.activityMuted,
                pinned = pinned,
                enabled = writeActionsEnabled,
                longMessageCollapseSetting = longMessageCollapseSetting,
                onToggleMute = onToggleMute,
                onTogglePin = onTogglePin
            )
        }
        if (canManage && writeActionsEnabled) {
            item(key = "management") {
                ChannelManagementCard(
                    showMakePrivate = channelSettingsCanMakePrivate(channel),
                    showDelete = channelSettingsCanDelete(channel),
                    onMakePrivate = onMakePrivate,
                    onArchive = onArchive,
                    onDelete = onDelete
                )
            }
        }
    }
}

@Composable
private fun ChannelIdentityCard(channel: HomeChannelRow, editable: Boolean, onClick: () -> Unit) {
    RaftBrutalCard(
        modifier = Modifier.clickable(enabled = editable, onClick = onClick),
        contentPadding = 16.dp,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            RaftChannelIdentityTile(
                channelKind =
                    channel
                        .toThreadLaunchConversationIdentity()
                        .threadRouteChannelKind(),
                tileSize = 54.dp,
                iconSize = 26.dp
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "#${channel.name.removePrefix("#")}",
                    style = RaftType.PageTitle.copy(fontSize = 18.sp, lineHeight = 22.sp),
                    color = RaftColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                ChannelStatusBadge(channel)
            }
            if (editable) {
                RaftLucideIcon(RaftLucideIconName.ChevronRight, Modifier.size(20.dp), color = RaftColor.TextBlackArgb)
            }
        }
        Text(
            text = channel.subtitle?.trim().takeUnless { it.isNullOrBlank() }
                ?: stringResource(MR.strings.channel_settings_description_empty),
            style = RaftType.Body,
            color = RaftColor.TextMutedGray,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ChannelStatusBadge(channel: HomeChannelRow) {
    val text =
        when {
            channel.archived -> stringResource(MR.strings.channel_settings_status_archived)
            channelSettingsIsPrivate(channel) ->
                stringResource(MR.strings.channel_settings_status_private)
            else -> stringResource(MR.strings.channel_settings_status_public)
        }
    Box(
        modifier =
            Modifier
                .background(if (channel.archived) RaftColor.EmptyStateGray else RaftColor.AccentLime)
                .border(RaftAppStroke.Thin, if (channel.archived) RaftColor.BorderBlack else RaftColor.StickerInk)
                .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(text = text, style = RaftType.Caption.withRaftFontWeight(FontWeight.Bold), color = if (channel.archived) RaftColor.TextBlack else RaftColor.StickerInk)
    }
}

@Composable
private fun ChannelMembersCard(
    slice: SharedServerChannelMembersSlice,
    fallbackCount: Int?,
    onClick: () -> Unit
) {
    val members = slice.members.distinctBy { it.id }.take(if (slice.canAddMembers) 4 else 5)
    val countLabel =
        channelSettingsMemberCountLabel(
            slice = slice,
            fallbackCount = fallbackCount,
            loadingLabel = stringResource(MR.strings.common_loading),
        )
    RaftBrutalCard(
        modifier = Modifier.clickable(onClick = onClick),
        contentPadding = 16.dp,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(MR.strings.channel_settings_members),
                style = RaftType.PageTitle.copy(fontSize = 16.sp, lineHeight = 20.sp),
                color = RaftColor.TextBlack
            )
            Spacer(Modifier.weight(1f))
            Text(text = countLabel, style = RaftType.Body, color = RaftColor.TextMutedGray)
            RaftLucideIcon(RaftLucideIconName.ChevronRight, Modifier.size(20.dp), color = if (RaftAppThemeState.isDark) RaftColor.TextMutedGrayArgb else "#FF666666")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            members.forEach { member -> ChannelSettingsMemberAvatar(member) }
            if (slice.canAddMembers) {
                Box(
                    modifier =
                        Modifier
                            .size(44.dp)
                            .background(RaftColor.SurfaceWhite)
                            .border(RaftAppStroke.Default, RaftColor.BorderBlack),
                    contentAlignment = Alignment.Center
                ) {
                    RaftLucideIcon(RaftLucideIconName.Plus, Modifier.size(20.dp), color = RaftColor.TextBlackArgb)
                }
            }
        }
    }
}

internal fun channelSettingsMemberCountLabel(
    slice: SharedServerChannelMembersSlice,
    fallbackCount: Int?,
    loadingLabel: String,
): String =
    when {
        slice.status == SharedServerMembersLoadStatus.Loaded -> slice.members.size.toString()
        slice.status == SharedServerMembersLoadStatus.InFlight && slice.members.isEmpty() -> loadingLabel
        else -> (fallbackCount ?: slice.members.size).toString()
    }

@Composable
private fun ChannelSettingsMemberAvatar(member: MemberProfile) {
    RaftAppAvatar(
        avatarWireValue = member.avatarWireValueForAvatarSlot(),
        label = member.raftAvatarName(),
        avatarSize = 44.dp,
        cacheSubject = member.raftAvatarCacheSubject(),
        cacheServerId = member.serverId?.value,
        fallbackFill = raftAvatarFallbackFill(member.kind)
    )
}

@Composable
private fun ChannelPreferencesCard(
    muteSupported: Boolean,
    muted: Boolean,
    pinned: Boolean,
    enabled: Boolean,
    longMessageCollapseSetting: RaftLongMessageCollapseConversationSetting,
    onToggleMute: () -> Unit,
    onTogglePin: () -> Unit
) {
    RaftBrutalCard(contentPadding = 16.dp, verticalArrangement = Arrangement.spacedBy(0.dp)) {
        Text(
            text = stringResource(MR.strings.channel_settings_preferences),
            style = RaftType.PageTitle.copy(fontSize = 16.sp, lineHeight = 20.sp),
            color = RaftColor.TextBlack,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        ChannelPreferenceRow(
            title = stringResource(MR.strings.channel_settings_mute),
            detail = stringResource(MR.strings.channel_settings_mute_detail),
            checked = muted,
            enabled = muteSupported && enabled,
            onClick = onToggleMute
        )
        ChannelSettingsDivider()
        ChannelPreferenceRow(
            title = stringResource(MR.strings.channel_settings_pin),
            detail = stringResource(MR.strings.channel_settings_pin_detail),
            checked = pinned,
            enabled = enabled,
            onClick = onTogglePin
        )
        // Device-local display preference: available without write permission on the channel.
        ChannelSettingsDivider()
        ChannelPreferenceRow(
            title = stringResource(MR.strings.conversation_long_message_collapse),
            detail = longMessageCollapseSetting.preference.localizedDetail(),
            checked = longMessageCollapseSetting.effective,
            enabled = longMessageCollapseSetting.available,
            onClick = { longMessageCollapseSetting.onSetEnabled(!longMessageCollapseSetting.effective) }
        )
    }
}

@Composable
private fun ChannelPreferenceRow(
    title: String,
    detail: String,
    checked: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, onClick = onClick)
                .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(text = title, style = RaftType.Body.withRaftFontWeight(FontWeight.W600), color = RaftColor.TextBlack)
            Text(text = detail, style = RaftType.Caption, color = RaftColor.TextMutedGray)
        }
        RaftSwitchButton(checked = checked, enabled = enabled)
    }
}

@Composable
private fun ChannelManagementCard(
    showMakePrivate: Boolean,
    showDelete: Boolean,
    onMakePrivate: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit
) {
    val actions = channelSettingsManagementActions(showMakePrivate, showDelete)
    RaftBrutalCard(contentPadding = 16.dp, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(MR.strings.channel_settings_management),
            style = RaftType.PageTitle.copy(fontSize = 16.sp, lineHeight = 20.sp),
            color = RaftColor.TextBlack,
            modifier = Modifier.padding(bottom = 2.dp)
        )
        ThreadSettingsChannelActionButtons(
            actions = actions,
            onOpenAction = { actionId ->
                when (actionId) {
                    "visibility" -> onMakePrivate()
                    "archive" -> onArchive()
                    "delete" -> onDelete()
                }
            }
        )
    }
}

internal fun channelSettingsManagementActions(
    showMakePrivate: Boolean,
    showDelete: Boolean = true,
): List<RaftThreadSettingsAction> =
    buildList {
        if (showMakePrivate) {
            add(RaftThreadSettingsAction(id = "visibility"))
        }
        add(RaftThreadSettingsAction(id = "archive"))
        if (showDelete) {
            add(RaftThreadSettingsAction(id = "delete"))
        }
    }

@Composable
private fun ChannelSettingsDivider() {
    Box(modifier = Modifier.fillMaxWidth().height(RaftAppStroke.Thin).background(RaftColor.BorderBlack))
}

@Composable
private fun ChannelSettingsLoading() {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            repeat(3) { index ->
                RaftBrutalCard(
                    modifier = Modifier.padding(bottom = if (index == 2) 0.dp else 14.dp),
                    contentPadding = 16.dp
                ) {
                    Box(Modifier.fillMaxWidth().height(if (index == 0) 92.dp else 76.dp).background(RaftColor.EmptyStateGray))
                }
            }
        }
    }
}

@Composable
private fun ChannelSettingsMessage(title: String, detail: String, onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
        RaftBrutalCard(contentPadding = 18.dp, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(text = title, style = RaftType.PageTitle.copy(fontSize = 16.sp, lineHeight = 20.sp), color = RaftColor.TextBlack)
            Text(text = detail, style = RaftType.Body, color = RaftColor.TextMutedGray)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                RaftModalActionButton(
                    label = stringResource(MR.strings.common_retry),
                    icon = RaftLucideIconName.RefreshCw,
                    onClick = onRetry
                )
            }
        }
    }
}

@Composable
private fun ChannelSettingsInvalidRouteScreen(onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().background(RaftColor.SurfaceCream)) {
        RaftTopPageHeader(
            title = stringResource(MR.strings.channel_settings_title),
            iconName = RaftLucideIconName.Settings,
            onBack = onBack
        )
        ChannelSettingsMessage(
            title = stringResource(MR.strings.channel_settings_unavailable),
            detail = stringResource(MR.strings.channel_settings_invalid_route),
            onRetry = onBack
        )
    }
}
