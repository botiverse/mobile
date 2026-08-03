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
import build.raft.app.components.SlockBrutalCard
import build.raft.app.components.SlockSwitchButton
import build.raft.app.data.ChannelProfileUpdateRequest
import build.raft.app.data.ChannelVisibility
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.DataResult
import build.raft.app.data.HomeChannelRow
import build.raft.app.data.RealChannelRepository
import build.raft.app.data.SharedHomeContentState
import build.raft.app.data.SharedHomeIntent
import build.raft.app.data.SharedHomeState
import build.raft.app.data.SharedServerChannelMembersSlice
import build.raft.app.data.SharedServerMembersLoadStatus
import build.raft.app.data.SlockSharedStoreRegistry
import build.raft.app.data.SlockShellStoreScope
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.app.model.avatarWireValueForAvatarSlot
import build.raft.app.resources.MR
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.clickable
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.PaddingValues
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.Spacer
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.foundation.lazy.LazyColumn
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.pager.Pager
import com.tencent.tmm.kmmresource.compose.stringResource
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

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

@Page(CHANNEL_SETTINGS_PAGE, supportInLocal = true)
internal class ComposeChannelSettingsPage : AuthenticatedProductPager(AuthenticatedProductRoute.ChannelSettings) {
    override fun willInit() {
        super.willInit()
        setAuthenticatedContent {
            SlockInputFocusDismissHost { focusDismissModifier ->
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
        params.slockShellStoreScope(SlockShellStoreScope.Legacy.shellInstanceId)
    }
    val sharedStores = remember(bridge) { SlockSharedStoreRegistry.get(bridge) }
    val homeStore = remember(sharedStores, storeScope) { sharedStores.sharedHomeStore(storeScope) }
    val homeStateFlow = remember(homeStore) { homeStore?.state ?: flowOf(SharedHomeState()) }
    val homeState by homeStateFlow.collectAsState(SharedHomeState())
    val membersStore = remember(sharedStores, storeScope) { sharedStores.serverMembersStore(storeScope) }
    val memberSlice by remember(membersStore, route.serverId, route.channelId) {
        membersStore
            .channelMemberSliceFlow(route.serverId, route.channelId)
            .onStart {
                membersStore.loadChannelMembers(
                    serverId = route.serverId,
                    channelId = route.channelId,
                    priority = DataRequestPriority.Visible,
                    force = false
                )
            }
    }.collectAsState(
        SharedServerChannelMembersSlice(
            serverId = route.serverId.value,
            channelId = route.channelId.value
        )
    )
    val authStore = remember(sharedStores, storeScope) { sharedStores.authStore(storeScope) }
    val networkClient = remember(sharedStores, storeScope) { sharedStores.networkClient(storeScope) }
    val channelRepository = remember(authStore, networkClient) {
        RealChannelRepository(
            sessionStore = authStore,
            networkClient = networkClient,
            warningLogger = { bridge.log("channel_settings_repository $it") }
        )
    }
    val scope = rememberCoroutineScope()
    val content = homeState.content
    val channel = remember(content, route) { channelSettingsChannel(content, route) }
    val canManage = remember(content) { channelSettingsCanManage(content) }
    val pinnedIds = remember(content) {
        (content as? SharedHomeContentState.Content)
            ?.snapshot
            ?.sidebarOrder
            .pinnedChannelConversationIds()
    }
    val pinned = route.channelId.value in pinnedIds
    var dialog by remember(route) { mutableStateOf<ChannelSettingsDialog?>(null) }
    var profileStatus by remember(route) { mutableStateOf(ChannelProfileUpdateStatus.Idle) }
    var profileErrorCode by remember(route) { mutableStateOf<String?>(null) }
    var actionInFlight by remember(route) { mutableStateOf(false) }
    val updateFailedLabel = stringResource(MR.strings.thread_settings_update_failed)
    val archiveFailedLabel = stringResource(MR.strings.thread_error_archive_channel)
    val deleteFailedLabel = stringResource(MR.strings.thread_error_delete_channel)

    LaunchedEffect(homeStore, route.serverId) {
        homeStore?.dispatch(SharedHomeIntent.Start)
    }

    fun finishDestructiveAction() {
        dialog = null
        actionInFlight = false
        homeStore?.dispatch(SharedHomeIntent.Refresh)
        router.closePage()
    }

    Box(modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceCream)) {
        Column(modifier = Modifier.fillMaxSize()) {
            SlockTopPageHeader(
                title = stringResource(MR.strings.channel_settings_title),
                iconName = SlockLucideIconName.Settings,
                backgroundColor = SlockColor.SurfaceWhite,
                refreshing = (content as? SharedHomeContentState.Content)?.contentRefreshing == true,
                leadingContent = {
                    SlockTopPageHeaderAction(
                        icon = SlockLucideIconName.ArrowLeft,
                        onClick = router::closePage
                    )
                }
            )
            when {
                channel != null ->
                    ChannelSettingsContent(
                        channel = channel,
                        memberSlice = memberSlice,
                        pinned = pinned,
                        canManage = canManage,
                        onEdit = { dialog = ChannelSettingsDialog.Edit },
                        onOpenMembers = {
                            pager.openSlockPage(
                                pageName = THREAD_MEMBERS_PAGE,
                                pageData = channelSettingsMembersPageData(route),
                                storeScopeOverride = storeScope
                            )
                        },
                        onToggleMute = {
                            homeStore?.dispatch(
                                SharedHomeIntent.SetChannelActivityMuted(
                                    conversationId = route.channelId.value,
                                    muted = !channel.activityMuted
                                )
                            )
                        },
                        onTogglePin = {
                            homeStore?.dispatch(SharedHomeIntent.TogglePinned(route.channelId.value))
                        },
                        onMakePrivate = { dialog = ChannelSettingsDialog.Visibility },
                        onArchive = { dialog = ChannelSettingsDialog.Archive },
                        onDelete = { dialog = ChannelSettingsDialog.Delete }
                    )
                content is SharedHomeContentState.Error || content is SharedHomeContentState.Empty ->
                    ChannelSettingsMessage(
                        title = stringResource(MR.strings.channel_settings_unavailable),
                        detail = stringResource(MR.strings.channel_settings_unavailable_detail),
                        onRetry = { homeStore?.dispatch(SharedHomeIntent.Refresh) }
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
                        ChannelSettingsDialog.Visibility -> listOf(KuiklyThreadSettingsAction(id = "visibility"))
                        ChannelSettingsDialog.Archive -> listOf(KuiklyThreadSettingsAction(id = "archive"))
                        ChannelSettingsDialog.Delete -> listOf(KuiklyThreadSettingsAction(id = "delete"))
                        else -> emptyList()
                    },
                initialDangerActionId =
                    when (activeDialog) {
                        ChannelSettingsDialog.Visibility -> "visibility"
                        ChannelSettingsDialog.Archive -> "archive"
                        ChannelSettingsDialog.Delete -> "delete"
                        else -> null
                    },
                updateStatus = profileStatus,
                updateErrorCode = profileErrorCode,
                onDismiss = {
                    if (!actionInFlight && profileStatus != ChannelProfileUpdateStatus.InFlight) {
                        dialog = null
                        profileStatus = ChannelProfileUpdateStatus.Idle
                        profileErrorCode = null
                    }
                },
                onSave = { _, _, name, description ->
                    if (profileStatus == ChannelProfileUpdateStatus.InFlight) return@ThreadSettingsModal
                    profileStatus = ChannelProfileUpdateStatus.InFlight
                    profileErrorCode = null
                    scope.launch {
                        when (
                            val result = channelRepository.updateChannelProfile(
                                ChannelProfileUpdateRequest(route.channelId, name, description)
                            )
                        ) {
                            is DataResult.Content -> {
                                homeStore?.dispatch(
                                    SharedHomeIntent.ChannelProfileUpdated(
                                        channelId = result.data.channelId,
                                        name = result.data.name,
                                        description = result.data.description
                                    )
                                )
                                profileStatus = ChannelProfileUpdateStatus.Succeeded
                                dialog = null
                            }
                            is DataResult.Error -> {
                                profileStatus = ChannelProfileUpdateStatus.Failed
                                profileErrorCode = result.error.code
                                bridge.toast(updateFailedLabel)
                            }
                            is DataResult.NoPermission -> {
                                profileStatus = ChannelProfileUpdateStatus.Failed
                                profileErrorCode = result.blocker.code
                                bridge.toast(updateFailedLabel)
                            }
                            is DataResult.Empty -> {
                                profileStatus = ChannelProfileUpdateStatus.Failed
                                profileErrorCode = "channel_update_empty"
                                bridge.toast(updateFailedLabel)
                            }
                        }
                    }
                },
                onOpenAction = { actionId ->
                    if (actionInFlight) return@ThreadSettingsModal
                    actionInFlight = true
                    scope.launch {
                        val result =
                            when (actionId) {
                                "visibility" -> channelRepository.updateChannel(route.channelId, ChannelVisibility.Private)
                                "archive" -> channelRepository.archiveChannel(route.channelId)
                                "delete" -> channelRepository.deleteChannel(route.channelId)
                                else -> null
                            }
                        when (result) {
                            is DataResult.Content -> {
                                if (actionId == "visibility") {
                                    actionInFlight = false
                                    dialog = null
                                    homeStore?.dispatch(SharedHomeIntent.Refresh)
                                } else {
                                    finishDestructiveAction()
                                }
                            }
                            is DataResult.Error -> {
                                actionInFlight = false
                                bridge.toast(
                                    when (actionId) {
                                        "delete" -> deleteFailedLabel
                                        "visibility" -> updateFailedLabel
                                        else -> archiveFailedLabel
                                    }
                                )
                            }
                            is DataResult.NoPermission -> {
                                actionInFlight = false
                                bridge.toast(
                                    when (actionId) {
                                        "delete" -> deleteFailedLabel
                                        "visibility" -> updateFailedLabel
                                        else -> archiveFailedLabel
                                    }
                                )
                            }
                            is DataResult.Empty -> {
                                actionInFlight = false
                                bridge.toast(
                                    when (actionId) {
                                        "delete" -> deleteFailedLabel
                                        "visibility" -> updateFailedLabel
                                        else -> archiveFailedLabel
                                    }
                                )
                            }
                            null -> {
                                actionInFlight = false
                            }
                        }
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
    canManage: Boolean,
    onEdit: () -> Unit,
    onOpenMembers: () -> Unit,
    onToggleMute: () -> Unit,
    onTogglePin: () -> Unit,
    onMakePrivate: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item(key = "identity") {
            ChannelIdentityCard(channel = channel, editable = canManage, onClick = onEdit)
        }
        item(key = "members") {
            ChannelMembersCard(slice = memberSlice, fallbackCount = channel.memberCount, onClick = onOpenMembers)
        }
        item(key = "preferences") {
            ChannelPreferencesCard(
                muteSupported = channel.activityMuteSupported,
                muted = channel.activityMuted,
                pinned = pinned,
                onToggleMute = onToggleMute,
                onTogglePin = onTogglePin
            )
        }
        if (canManage) {
            item(key = "management") {
                ChannelManagementCard(
                    showMakePrivate = !channel.channelType.equals("private", ignoreCase = true),
                    onEdit = onEdit,
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
    SlockBrutalCard(
        modifier = Modifier.clickable(enabled = editable, onClick = onClick),
        contentPadding = 16.dp,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier =
                    Modifier
                        .size(54.dp)
                        .background(SlockColor.BrandYellow)
                        .border(SlockStroke.Default, SlockColor.BorderBlack),
                contentAlignment = Alignment.Center
            ) {
                Text(text = "#", style = SlockType.PageTitle, color = SlockColor.TextBlack)
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "#${channel.name.removePrefix("#")}",
                    style = SlockType.PageTitle.copy(fontSize = 18.sp, lineHeight = 22.sp),
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                ChannelStatusBadge(channel)
            }
            if (editable) {
                SlockLucideIcon(SlockLucideIconName.ChevronRight, Modifier.size(20.dp), color = "#FF111111")
            }
        }
        Text(
            text = channel.subtitle?.trim().takeUnless { it.isNullOrBlank() }
                ?: stringResource(MR.strings.channel_settings_description_empty),
            style = SlockType.Body,
            color = SlockColor.TextMutedGray,
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
            channel.channelType.equals("private", ignoreCase = true) ->
                stringResource(MR.strings.channel_settings_status_private)
            else -> stringResource(MR.strings.channel_settings_status_public)
        }
    Box(
        modifier =
            Modifier
                .background(if (channel.archived) SlockColor.EmptyStateGray else SlockColor.AccentLime)
                .border(SlockStroke.Thin, SlockColor.BorderBlack)
                .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(text = text, style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold), color = SlockColor.TextBlack)
    }
}

@Composable
private fun ChannelMembersCard(
    slice: SharedServerChannelMembersSlice,
    fallbackCount: Int?,
    onClick: () -> Unit
) {
    val members = slice.members.distinctBy { it.id }.take(if (slice.canAddMembers) 4 else 5)
    val count = if (slice.status == SharedServerMembersLoadStatus.Loaded) slice.members.size else fallbackCount ?: slice.members.size
    SlockBrutalCard(
        modifier = Modifier.clickable(onClick = onClick),
        contentPadding = 16.dp,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(MR.strings.channel_settings_members),
                style = SlockType.PageTitle.copy(fontSize = 16.sp, lineHeight = 20.sp),
                color = SlockColor.TextBlack
            )
            Spacer(Modifier.weight(1f))
            Text(text = count.toString(), style = SlockType.Body, color = SlockColor.TextMutedGray)
            SlockLucideIcon(SlockLucideIconName.ChevronRight, Modifier.size(20.dp), color = "#FF666666")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            members.forEach { member -> ChannelSettingsMemberAvatar(member) }
            if (slice.canAddMembers) {
                Box(
                    modifier =
                        Modifier
                            .size(44.dp)
                            .background(SlockColor.SurfaceWhite)
                            .border(SlockStroke.Default, SlockColor.BorderBlack),
                    contentAlignment = Alignment.Center
                ) {
                    SlockLucideIcon(SlockLucideIconName.Plus, Modifier.size(20.dp), color = "#FF111111")
                }
            }
        }
    }
}

@Composable
private fun ChannelSettingsMemberAvatar(member: MemberProfile) {
    SlockAvatar(
        avatarWireValue = member.avatarWireValueForAvatarSlot(),
        label = member.avatarLabel.ifBlank { member.displayName.take(1) },
        avatarSize = 44.dp,
        borderWidth = SlockStroke.Default
    )
}

@Composable
private fun ChannelPreferencesCard(
    muteSupported: Boolean,
    muted: Boolean,
    pinned: Boolean,
    onToggleMute: () -> Unit,
    onTogglePin: () -> Unit
) {
    SlockBrutalCard(contentPadding = 16.dp, verticalArrangement = Arrangement.spacedBy(0.dp)) {
        Text(
            text = stringResource(MR.strings.channel_settings_preferences),
            style = SlockType.PageTitle.copy(fontSize = 16.sp, lineHeight = 20.sp),
            color = SlockColor.TextBlack,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        ChannelPreferenceRow(
            title = stringResource(MR.strings.channel_settings_mute),
            detail = stringResource(MR.strings.channel_settings_mute_detail),
            checked = muted,
            enabled = muteSupported,
            onClick = onToggleMute
        )
        ChannelSettingsDivider()
        ChannelPreferenceRow(
            title = stringResource(MR.strings.channel_settings_pin),
            detail = stringResource(MR.strings.channel_settings_pin_detail),
            checked = pinned,
            enabled = true,
            onClick = onTogglePin
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
            Text(text = title, style = SlockType.Body.withSlockFontWeight(FontWeight.W600), color = SlockColor.TextBlack)
            Text(text = detail, style = SlockType.Caption, color = SlockColor.TextMutedGray)
        }
        SlockSwitchButton(checked = checked, enabled = enabled)
    }
}

@Composable
private fun ChannelManagementCard(
    showMakePrivate: Boolean,
    onEdit: () -> Unit,
    onMakePrivate: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit
) {
    SlockBrutalCard(contentPadding = 16.dp, verticalArrangement = Arrangement.spacedBy(0.dp)) {
        Text(
            text = stringResource(MR.strings.channel_settings_management),
            style = SlockType.PageTitle.copy(fontSize = 16.sp, lineHeight = 20.sp),
            color = SlockColor.TextBlack,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        ChannelManagementRow(stringResource(MR.strings.thread_settings_edit_channel), false, onEdit)
        if (showMakePrivate) {
            ChannelSettingsDivider()
            ChannelManagementRow(stringResource(MR.strings.thread_settings_make_private), false, onMakePrivate)
        }
        ChannelSettingsDivider()
        ChannelManagementRow(stringResource(MR.strings.thread_settings_archive), false, onArchive)
        ChannelSettingsDivider()
        ChannelManagementRow(stringResource(MR.strings.thread_settings_delete), true, onDelete)
    }
}

@Composable
private fun ChannelManagementRow(label: String, danger: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = SlockType.Body.withSlockFontWeight(FontWeight.W600),
            color = if (danger) SlockColor.AccentRed else SlockColor.TextBlack,
            modifier = Modifier.weight(1f)
        )
        if (!danger) {
            SlockLucideIcon(SlockLucideIconName.ChevronRight, Modifier.size(20.dp), color = "#FF666666")
        }
    }
}

@Composable
private fun ChannelSettingsDivider() {
    Box(modifier = Modifier.fillMaxWidth().height(SlockStroke.Thin).background(SlockColor.BorderBlack))
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
                SlockBrutalCard(
                    modifier = Modifier.padding(bottom = if (index == 2) 0.dp else 14.dp),
                    contentPadding = 16.dp
                ) {
                    Box(Modifier.fillMaxWidth().height(if (index == 0) 92.dp else 76.dp).background(SlockColor.EmptyStateGray))
                }
            }
        }
    }
}

@Composable
private fun ChannelSettingsMessage(title: String, detail: String, onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
        SlockBrutalCard(contentPadding = 18.dp, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(text = title, style = SlockType.PageTitle.copy(fontSize = 16.sp, lineHeight = 20.sp), color = SlockColor.TextBlack)
            Text(text = detail, style = SlockType.Body, color = SlockColor.TextMutedGray)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                SlockModalActionButton(
                    label = stringResource(MR.strings.common_retry),
                    icon = SlockLucideIconName.RefreshCw,
                    onClick = onRetry
                )
            }
        }
    }
}

@Composable
private fun ChannelSettingsInvalidRouteScreen(onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceCream)) {
        SlockTopPageHeader(
            title = stringResource(MR.strings.channel_settings_title),
            iconName = SlockLucideIconName.Settings,
            leadingContent = {
                SlockTopPageHeaderAction(icon = SlockLucideIconName.ArrowLeft, onClick = onBack)
            }
        )
        ChannelSettingsMessage(
            title = stringResource(MR.strings.channel_settings_unavailable),
            detail = stringResource(MR.strings.channel_settings_invalid_route),
            onRetry = onBack
        )
    }
}
