package build.raft.app

import build.raft.app.base.Utils
import build.raft.app.base.BridgeModule
import build.raft.app.components.SlockBrutalActionButton
import build.raft.app.components.SlockBrutalBadge
import build.raft.app.components.SlockBrutalIconButton
import build.raft.app.components.SlockBrutalTextField
import build.raft.app.components.SlockAnchorPlacement
import build.raft.app.components.SlockBrutalInputField
import build.raft.app.components.SlockBrutalTone
import build.raft.app.PixelAvatarActiveDefaultKey
import build.raft.app.PixelAvatarPickerKeys
import build.raft.app.components.SlockBrutalCard
import build.raft.app.components.SlockCheckbox
import build.raft.app.components.SlockCheckMarker
import build.raft.app.components.SlockCheckMarkerShape
import build.raft.app.components.SlockCheckMarkerTone
import build.raft.app.components.SegmentedControl
import build.raft.app.components.SegmentedControlOption
import build.raft.app.components.SlockSectionEyebrow
import build.raft.app.components.SlockRaftUiControlledMenu
import build.raft.app.components.SlockFormField
import build.raft.app.components.SlockRaftUiMenuItem
import build.raft.app.components.SlockRaftUiSelectionPopover
import build.raft.app.components.SlockRaftUiSelectionOption
import build.raft.app.components.TabPager
import build.raft.app.components.TabPagerGesturePolicy
import build.raft.app.data.PlatformFile
import build.raft.app.data.AgentActivityEntry
import build.raft.app.data.AgentArchiveWorkspaceChoice
import build.raft.app.data.AgentIssueReportRepository
import build.raft.app.data.AgentIssueReportResult
import build.raft.app.data.AgentIssueReportSubmission
import build.raft.app.data.AgentAppLoginItem
import build.raft.app.data.AgentAppLoginLoadState
import build.raft.app.data.AgentAppLoginSnapshot
import build.raft.app.data.AgentAppLoginType
import build.raft.app.data.AgentAvatarUploadRequest
import build.raft.app.data.AgentCreatedSummary
import build.raft.app.data.AgentCreatorSummary
import build.raft.app.data.AgentDetailSnapshot
import build.raft.app.data.AgentScopeSet
import build.raft.app.data.AgentDetailSummary
import build.raft.app.data.normalizedAgentRuntimeStatus
import build.raft.app.data.AgentChannelSummary
import build.raft.app.data.AgentDmSummary
import build.raft.app.data.AgentLifecycleActionResult
import build.raft.app.data.AgentMachineAssignmentRequest
import build.raft.app.data.AgentReminderSummary
import build.raft.app.data.AgentResetMode
import build.raft.app.data.AgentSkillGroup
import build.raft.app.data.AgentSkillPathGroup
import build.raft.app.data.AgentSkillSummary
import build.raft.app.data.AgentSkillsSnapshot
import build.raft.app.data.AgentWorkspaceChildrenSnapshot
import build.raft.app.data.AgentWorkspaceTreeSnapshot
import build.raft.app.data.AgentProfileUpdateRequest
import build.raft.app.data.BridgeSettingsPlatformCapabilities
import build.raft.app.data.BridgeSlockAvatarNativeCache
import build.raft.app.data.ChannelRepository
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.DataResult
import build.raft.app.data.EmptyReason
import build.raft.app.data.HomeDirectMessageRow
import build.raft.app.data.MemberRepository
import build.raft.app.data.OpenDirectMessageResult
import build.raft.app.data.OpenedDirectMessageAuthority
import build.raft.app.data.SharedAgentDetailActions
import build.raft.app.data.SharedAgentDetailIntent
import build.raft.app.data.SharedAgentDetailLoadStatus
import build.raft.app.data.SharedAgentDetailOwner
import build.raft.app.data.SharedAgentDetailState
import build.raft.app.data.SharedAgentDetailStore
import build.raft.app.data.SharedAgentIssueReportRequest
import build.raft.app.data.SharedAgentPanelTabOrderStore
import build.raft.app.data.SlockAuthSessionStore
import build.raft.app.data.SlockAvatarCacheRuntime
import build.raft.app.data.SlockAvatarNativeCache
import build.raft.app.data.SlockShellStoreScope
import build.raft.app.data.toSharedConversationDirectoryState
import build.raft.app.data.SharedConversationDirectoryState
import build.raft.app.data.mergedSharedConversationDirectoryState
import build.raft.app.data.parseSharedThreadPickedPlatformFiles
import build.raft.app.data.RealChannelRepository
import build.raft.app.data.RealMemberRepository
import build.raft.app.data.PermissionBlocker
import build.raft.app.data.ScreenError
import build.raft.app.data.SettingsServerActionResult
import build.raft.app.data.SettingsServerMemberRoleUpdateRequest
import build.raft.app.data.SlockAgentsApiClient
import build.raft.app.data.SlockAgentsNetworkApi
import build.raft.app.data.SlockComputersApiClient
import build.raft.app.data.SlockComputersNetworkApi
import build.raft.app.data.SharedComputersIntent
import build.raft.app.data.SharedComputersPickerSlice
import build.raft.app.data.SharedComputersStore
import build.raft.app.data.ComputerStatus
import build.raft.app.data.ComputerSummary
import build.raft.app.data.SharedComputersLoadStatus
import build.raft.app.data.selectComputerServerSlice
import build.raft.app.data.SharedComputersOwner
import build.raft.app.data.SharedRuntimeAccountUsageStore
import build.raft.app.data.SharedCreateAgentFormStore
import build.raft.app.data.SharedApiEnvironmentStore
import build.raft.app.data.SharedHomeStore
import build.raft.app.data.CREATE_AGENT_BUILTIN_RUNTIME_ID
import build.raft.app.data.CREATE_AGENT_KIMI_SDK_RUNTIME_ID
import build.raft.app.data.SharedRuntimeFormDefinitionRef
import build.raft.app.data.runtimeform.v2.RuntimeFormV2SubmitBody
import build.raft.app.data.toAgentDetailSummary
import build.raft.app.data.buildBuiltInRuntimeConfig
import build.raft.app.data.AGENT_CREATE_FORM_REFRESH_FAILED
import build.raft.app.data.AGENT_CREATE_NAME_TAKEN
import build.raft.app.data.createAgentFallbackErrorText
import build.raft.app.data.AGENT_CREATE_FORM_REFRESH_REQUIRED
import build.raft.app.data.SharedServerMembersEntry
import build.raft.app.data.SharedServerMembersState
import build.raft.app.data.SelectedServerStore
import build.raft.app.data.sharedAgentDetailOwner
import build.raft.app.data.kuiklyBridgeCallContext
import build.raft.app.markdown.SlockMarkdownBody
import build.raft.app.model.AgentProfile
import build.raft.app.model.AgentStatus
import build.raft.app.model.AvatarReference
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.RUNTIME_PROVIDER_MODE_CUSTOM
import build.raft.app.model.RUNTIME_PROVIDER_MODE_DEFAULT
import build.raft.app.model.RUNTIME_MODELS
import build.raft.app.model.RUNTIMES
import build.raft.app.model.RuntimeConfig
import build.raft.app.model.RuntimeModeConfig
import build.raft.app.model.RuntimeModelConfig
import build.raft.app.model.RuntimeProviderConfig
import build.raft.app.model.getModelLabel
import build.raft.app.model.getDefaultModel
import build.raft.app.model.getRuntimeDisplayName
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Presence
import build.raft.app.model.buildRuntimeConfig
import build.raft.app.model.preferredAvatarWireValue
import build.raft.app.model.supportsRuntimeFastMode
import build.raft.app.model.supportsRuntimeReasoningEffort
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.SlockWorkspaceFileKind
import build.raft.app.model.SlockWorkspaceNode
import build.raft.app.model.SlockTimeContext
import build.raft.app.model.SlockTimeFormat12Hour
import build.raft.app.model.SlockTimeFormat24Hour
import build.raft.app.model.ThreadId
import build.raft.app.model.avatarWireValueForAvatarSlot
import build.raft.app.model.formatSlockTimestampClockWithSeconds
import build.raft.app.model.hidesHumanDirectoryForViewer
import build.raft.app.model.parseSlockTimestampEpochMillisOrNull
import build.raft.app.model.toMemberProfile
import build.raft.app.resources.MR
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.clickable
import com.tencent.kuikly.compose.foundation.gestures.detectDragGesturesAfterLongPress
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.BoxWithConstraints
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.ColumnScope
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.Spacer
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.ExperimentalLayoutApi
import com.tencent.kuikly.compose.foundation.layout.FlowRow
import com.tencent.kuikly.compose.foundation.layout.fillMaxHeight
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.heightIn
import com.tencent.kuikly.compose.foundation.layout.offset
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.requiredWidth
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.foundation.layout.widthIn
import com.tencent.kuikly.compose.foundation.lazy.LazyColumn
import com.tencent.kuikly.compose.foundation.lazy.LazyListState
import com.tencent.kuikly.compose.foundation.lazy.LazyRow
import com.tencent.kuikly.compose.foundation.lazy.rememberLazyListState
import com.tencent.kuikly.compose.foundation.shape.RoundedCornerShape
import com.tencent.kuikly.compose.extension.NestedScrollMode
import com.tencent.kuikly.compose.extension.nestedScroll
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.material3.rememberPullToRefreshState
import com.tencent.kuikly.compose.setContent
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.layout.onGloballyPositioned
import com.tencent.kuikly.compose.ui.layout.boundsInRoot
import com.tencent.kuikly.compose.ui.geometry.Rect
import com.tencent.kuikly.compose.ui.geometry.Offset
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.compose.ui.input.pointer.pointerInput
import com.tencent.kuikly.compose.ui.text.font.FontFamily
import com.tencent.kuikly.compose.ui.text.font.FontStyle
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.TextRange
import com.tencent.kuikly.compose.ui.text.input.TextFieldValue
import com.tencent.kuikly.compose.ui.text.style.TextAlign
import com.tencent.kuikly.compose.ui.text.style.TextDecoration
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.core.views.KeyboardParams
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.module.CodecModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.pager.IPagerEventObserver
import com.tencent.tmm.kmmresource.compose.localized
import com.tencent.tmm.kmmresource.compose.stringResource
import com.tencent.tmm.kmmresource.resource.StringResource
import com.tencent.tmm.kmmresource.resource.desc.StringDesc
import com.tencent.tmm.kmmresource.resource.desc.desc
import com.tencent.kuikly.core.pager.Pager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import build.raft.app.model.isExternalAgentRuntime
import build.raft.app.data.defaultNowMillis

const val MEMBERS_PAGE = "compose_members"
const val MEMBER_DETAIL_PAGE = "compose_member_detail"
const val MEMBERS_SMOKE_PAGE = "compose_members_smoke"
private const val MEMBERS_PAGE_DATA_EVENT = "slockPageData"

private val MembersPageInset = SlockSpacing.Md
// React Sidebar members view: scroll container `px-2` (8px) + per-row/section `px-2` (8px) = 16px
// total; the container half lives on the LazyColumn, the row half on each row/header.
internal val MembersListContainerInset = 8.dp

/**
 * Spec (task #12 §3): single-pane body content may not stretch unbounded on wide
 * windows — at most 720dp and centered, with side margins >=24dp on DualPane
 * windows. The cap is measured against the actual content-slot width, not the
 * whole window, so any future chrome (e.g. the nav rail) that narrows the slot
 * automatically keeps margins honest instead of over-claiming space.
 *
 * Compact windows are untouched: when the slot is <=720dp the content fills it
 * exactly as before (no new padding is introduced for narrow screens).
 */
internal val MembersListContentMaxWidth = 720.dp
internal val MembersListContentMinSideMargin = 24.dp

/**
 * §3 cap, gated on the window's DualPane layout fact and measured against the
 * actual content-slot width:
 * - Compact windows (paneLayout != DualPane): return slotWidth unchanged — no
 *   new padding or cap on narrow screens, whatever the slot happens to be.
 * - DualPane windows: content = min(720dp, slotWidth - 48dp), so the >=24dp
 *   side margins hold even if rail chrome narrows the slot below 840+48.
 */
internal fun membersListContentMaxWidth(
    slotWidth: Dp,
    paneLayout: SlockAdaptivePaneLayout,
): Dp {
    if (paneLayout != SlockAdaptivePaneLayout.DualPane) return slotWidth
    val cap = slotWidth - MembersListContentMinSideMargin * 2
    return if (cap < MembersListContentMaxWidth) cap else MembersListContentMaxWidth
}
internal val MembersListHorizontalInset = 8.dp
internal val MembersListAvatarSize = 18.dp
internal val MembersListRowMinHeight = 34.dp
internal val MembersListRowSpacing = 4.dp
internal val MembersListContentGap = 6.dp
internal const val MembersListNameMaxWidthFraction = 0.7f
internal val MembersListNameTextStyle =
    SlockType.Body.withSlockFontWeight(FontWeight.Medium).copy(fontSize = 14.sp, lineHeight = 20.sp)
internal val MembersListDescriptionTextStyle =
    SlockType.Body.withSlockFontWeight(FontWeight.Normal).copy(fontSize = 12.sp, lineHeight = 16.sp)
internal val MembersListSectionHeaderMinHeight = 24.dp
// React Sidebar's Agents/Humans section `+` is a compact 24px root-list action.
// This is intentionally separate from the 28dp member-detail message/menu actions below.
internal val MembersListSectionAddActionSize = 24.dp
internal val MembersListSectionAddActionIconSize = 14.dp
internal val MembersListMachineLabelInset = 12.dp
internal val MembersListMachineLabelIconSize = 9.dp
internal val MembersListMachineLabelTextStyle =
    SlockType.Caption.copy(fontFamily = SlockMonoFamily, fontSize = 10.sp, lineHeight = 14.sp)
private val MemberStatusDotSize = 10.dp
private val MembersActionSize = 28.dp
// Web AgentActivityLog row: `gap-2` (8) + StatusDot sm (`size-2` = 8) + `gap-2` (8).
private val MembersAgentActivityIndicatorLaneWidth = 24.dp
private val MembersAgentActivityContentGap = 0.dp

/** The two create flows exposed by the web Agents section action menu. */
internal enum class MembersCreateAgentMode {
    Managed,
    External
}

internal fun MembersCreateAgentMode.requiresComputerSelection(): Boolean = this == MembersCreateAgentMode.Managed

internal enum class MembersAgentDetailTab(
    val label: String,
    val iconName: SlockLucideIconName,
    val privateSurface: Boolean = true
) {
    Profile("Profile", SlockLucideIconName.Bot, privateSurface = false),
    Activity("Activity", SlockLucideIconName.Activity),
    Chat("Chat", SlockLucideIconName.MessageSquare),
    Reminders("Reminders", SlockLucideIconName.BellRing),
    Workspace("Workspace", SlockLucideIconName.FolderOpen),
    Apps("Apps", SlockLucideIconName.Link2)
}

@Composable
private fun MembersAgentDetailTab.localizedLabel(): String =
    when (this) {
        MembersAgentDetailTab.Profile -> stringResource(MR.strings.member_label_profile)
        MembersAgentDetailTab.Activity -> stringResource(MR.strings.member_tab_activity)
        MembersAgentDetailTab.Chat -> stringResource(MR.strings.member_tab_chat)
        MembersAgentDetailTab.Reminders -> stringResource(MR.strings.member_tab_reminders)
        MembersAgentDetailTab.Workspace -> stringResource(MR.strings.member_tab_workspace)
        MembersAgentDetailTab.Apps -> stringResource(MR.strings.member_tab_apps)
    }

internal fun membersHeaderMessageUsesIconOnly(kind: MemberKind): Boolean =
    kind == MemberKind.Agent || kind == MemberKind.Human

internal enum class MembersAgentServerRole(
    val wireValue: String,
    val label: String,
    val backgroundColor: Color
) {
    Admin("admin", "Admin", SlockColor.AccentPink),
    Member("member", "Member", SlockColor.AccentLavender)
}

internal enum class MembersHumanServerRole(
    val wireValue: String,
    val backgroundColor: Color
) {
    Owner("owner", SlockColor.AccentOrange),
    Admin("admin", SlockColor.AccentPink),
    Member("member", SlockColor.AccentLavender)
}

@Composable
private fun MembersHumanServerRole.localizedLabel(): String =
    when (this) {
        MembersHumanServerRole.Owner -> stringResource(MR.strings.member_role_owner)
        MembersHumanServerRole.Admin -> stringResource(MR.strings.member_role_admin)
        MembersHumanServerRole.Member -> stringResource(MR.strings.member_role_member)
    }

internal fun String?.toMembersHumanServerRoleOrNull(): MembersHumanServerRole? =
    when (this?.trim()?.lowercase()) {
        MembersHumanServerRole.Owner.wireValue -> MembersHumanServerRole.Owner
        MembersHumanServerRole.Admin.wireValue -> MembersHumanServerRole.Admin
        MembersHumanServerRole.Member.wireValue -> MembersHumanServerRole.Member
        else -> null
    }

internal fun membersEditableHumanServerRoles(
    actorRole: String?,
    targetRole: String?,
    targetMembershipStatus: String?,
    accessMode: MemberDetailAccessMode,
    targetMemberId: String,
    currentUserId: String?,
    targetServerId: String?,
    currentServerId: String?,
    ownerCount: Int? = null
): List<MembersHumanServerRole> {
    if (targetMembershipStatus != "active") return emptyList()
    if (accessMode != MemberDetailAccessMode.LocalFull) return emptyList()
    val normalizedTargetId = targetMemberId.trim()
    if (normalizedTargetId.isBlank() || currentUserId?.trim() == normalizedTargetId) return emptyList()
    val normalizedCurrentServerId = currentServerId?.trim().orEmpty()
    val normalizedTargetServerId = targetServerId?.trim().orEmpty()
    if (
        normalizedTargetServerId.isBlank() ||
        normalizedCurrentServerId.isBlank() ||
        normalizedTargetServerId != normalizedCurrentServerId
    ) {
        return emptyList()
    }
    val currentRole = targetRole.toMembersHumanServerRoleOrNull() ?: return emptyList()
    return when (actorRole.normalizedMembersServerRole()) {
        "owner" -> MembersHumanServerRole.entries.filter { candidate ->
            candidate != currentRole &&
                !(currentRole == MembersHumanServerRole.Owner && (ownerCount == null || ownerCount <= 1))
        }
        "admin" ->
            if (currentRole == MembersHumanServerRole.Member) listOf(MembersHumanServerRole.Admin)
            else emptyList()
        else -> emptyList()
    }
}

internal enum class MembersHumanRoleSaveOutcome { Success, NoPermission, Error }

internal fun membersHumanRoleUpdateServerMatches(
    expectedServerId: String?,
    selectedServerId: String?
): Boolean {
    val expected = expectedServerId?.trim()?.takeIf { it.isNotBlank() } ?: return false
    val selected = selectedServerId?.trim()?.takeIf { it.isNotBlank() } ?: return false
    return expected == selected
}

internal fun DataResult<SettingsServerActionResult>.membersHumanRoleSaveOutcome(): MembersHumanRoleSaveOutcome =
    when (this) {
        is DataResult.Content ->
            if (data.success) MembersHumanRoleSaveOutcome.Success else MembersHumanRoleSaveOutcome.Error
        is DataResult.Empty -> MembersHumanRoleSaveOutcome.Error
        is DataResult.NoPermission -> MembersHumanRoleSaveOutcome.NoPermission
        is DataResult.Error -> MembersHumanRoleSaveOutcome.Error
    }

@Composable
private fun MembersAgentServerRole.localizedLabel(): String =
    when (this) {
        MembersAgentServerRole.Admin -> stringResource(MR.strings.member_role_admin)
        MembersAgentServerRole.Member -> stringResource(MR.strings.member_role_member)
    }

internal fun String?.toMembersAgentServerRoleOrNull(): MembersAgentServerRole? =
    when (this?.trim()?.lowercase()) {
        MembersAgentServerRole.Admin.wireValue -> MembersAgentServerRole.Admin
        MembersAgentServerRole.Member.wireValue -> MembersAgentServerRole.Member
        else -> null
    }

/**
 * What the role chip should show. Three outcomes, deliberately distinct.
 *
 * The wire meaning of an absent `serverRole` is **no server-membership row** -- not "role
 * unknown" and not "member by default". `server_agent_members.role` is NOT NULL with a
 * default and a (serverId, agentId) primary key, so "is a member with no role set" cannot
 * exist; `getAgentMemberRole` returns null only when the row is missing, and the server
 * counts that case as an anomaly (`countActiveAgentsMissingServerMembership`). Verified by
 * LiBai against slock-source@01d0064d9; task #261.
 */
internal sealed interface MembersAgentServerRoleDisplay {
    /** A role we recognise: render its own label and colour. */
    data class Known(val role: MembersAgentServerRole) : MembersAgentServerRoleDisplay

    /**
     * The server sent a role we do not recognise (a future "moderator", say). Show the name
     * it sent -- claiming "No role" here would deny a role we were explicitly told about
     * (@artin: "那就展示成角色名").
     */
    data class Unrecognized(val rawName: String) : MembersAgentServerRoleDisplay

    /**
     * No membership row, or the agent is deleted. Render nothing at all (@artin: "隐藏显示").
     * Two different causes, one outcome -- kept as one case only because both hide. If either
     * ever needs its own wording they must split again, since a label written for the anomaly
     * would be wrong on a deleted agent, which is an ordinary state the DELETED block already
     * explains.
     */
    data object Hidden : MembersAgentServerRoleDisplay
}

/**
 * What the machine status row on the agent detail page should show. Mirrors web's
 * `machineRunLabel.ts`, which is a SIX-way choice, not four (LiBai, task #259):
 *
 *   status != online                   -> ComputerOffline / DaemonOffline
 *   status == online && version != null -> ComputerVersion / DaemonVersion
 *   status == online && version == null -> ComputerOnline  / DaemonOnline
 *
 * The last pair exists because a non-owner REST read returns `status = online` with
 * `version = null`. An earlier web build routed that into the offline label and showed a
 * live Computer as offline (#wg-raft-computer:de94165c). Do not collapse those two cases.
 *
 * The status word (Connected / Offline) and the run label are TWO independent facts from
 * the same machine record and are asserted separately; the " · " separator between them is
 * layout, not part of either string (web renders it as literal JSX text).
 */
internal sealed interface MembersMachineRunLabel {
    data class ComputerVersion(val version: String) : MembersMachineRunLabel
    data class DaemonVersion(val version: String) : MembersMachineRunLabel
    data object ComputerOnline : MembersMachineRunLabel
    data object DaemonOnline : MembersMachineRunLabel
    data object ComputerOffline : MembersMachineRunLabel
    data object DaemonOffline : MembersMachineRunLabel
}

internal sealed interface MembersMachineStatusRow {
    /** computers store has not answered yet: render nothing for this row (artin: 甲). */
    data object Loading : MembersMachineStatusRow
    /** No machineId, or machineId not in the loaded store: "no computer assigned", no dot. */
    data object NoComputer : MembersMachineStatusRow
    data class Machine(
        val online: Boolean,
        val runLabel: MembersMachineRunLabel
    ) : MembersMachineStatusRow
}

internal fun membersMachineRunLabel(machine: ComputerSummary): MembersMachineRunLabel {
    val online = machine.status == ComputerStatus.Online
    val version = (if (machine.isComputer) machine.computerVersion else machine.daemonVersion)
        ?.trim()
        ?.takeIf { it.isNotBlank() }
    return when {
        !online && machine.isComputer -> MembersMachineRunLabel.ComputerOffline
        !online -> MembersMachineRunLabel.DaemonOffline
        version != null && machine.isComputer -> MembersMachineRunLabel.ComputerVersion(version)
        version != null -> MembersMachineRunLabel.DaemonVersion(version)
        machine.isComputer -> MembersMachineRunLabel.ComputerOnline
        else -> MembersMachineRunLabel.DaemonOnline
    }
}

/**
 * Cell 6 of the task #259 table. `machineId` present but absent from a *loaded* store and
 * "store not loaded yet" look identical on the map; only the load status tells them apart.
 * The loading case must not fall through to NoComputer -- web does exactly that and flashes
 * "no computer assigned" on every open (its own defect, not a reference to copy).
 */
internal fun membersMachineStatusRow(
    machineId: String?,
    machines: List<ComputerSummary>,
    loadStatus: SharedComputersLoadStatus,
    hasSnapshot: Boolean
): MembersMachineStatusRow {
    val id = machineId?.trim()?.takeIf { it.isNotBlank() }
        ?: return MembersMachineStatusRow.NoComputer
    if (!hasSnapshot && loadStatus != SharedComputersLoadStatus.Loaded) {
        return MembersMachineStatusRow.Loading
    }
    val machine = machines.firstOrNull { it.id == id } ?: return MembersMachineStatusRow.NoComputer
    return MembersMachineStatusRow.Machine(
        online = machine.status == ComputerStatus.Online,
        runLabel = membersMachineRunLabel(machine)
    )
}

internal fun membersAgentDisplayServerRole(
    memberServerRole: String?,
    summaryServerRole: String?,
    deleted: Boolean = false
): MembersAgentServerRoleDisplay {
    val rawRole = memberServerRole?.trim()?.takeIf { it.isNotBlank() }
        ?: summaryServerRole?.trim()?.takeIf { it.isNotBlank() }
    // Deleted first, and this branch is NOT dead -- it guards a *client-cache* path, not a
    // wire one. Reading only the wire says the combination cannot happen: once an agent is
    // deleted the server stops sending a role (getServerAgentRoleMap filters on
    // isNull(agents.deletedAt)), so deleted-with-a-role never arrives. But
    // SharedServerMembersStore.mergeSeedProfile merges `serverRole = serverRole ?: seed`,
    // fill-only, and mergeAuthoritativeProfile begins by calling it -- so a role cached
    // before deletion survives every later update, including authoritative ones. Client
    // state therefore does hold deletedAt != null together with serverRole = "admin", and
    // without this branch that agent would show an Admin chip (LiBai, task #261).
    //
    // Keep the reason attached: verifying reachability at the wire layer alone concludes
    // this is dead code and deletes it. That merge is separately wrong for a mutable field
    // -- a demotion never reaches the UI -- tracked apart from this card.
    if (deleted || rawRole == null) return MembersAgentServerRoleDisplay.Hidden
    return rawRole.toMembersAgentServerRoleOrNull()
        ?.let(MembersAgentServerRoleDisplay::Known)
        ?: MembersAgentServerRoleDisplay.Unrecognized(rawRole)
}

internal fun membersAgentServerRoleOptions(
    selectedRole: MembersAgentServerRole?
): List<SlockRaftUiSelectionOption> =
    MembersAgentServerRole.entries.map { role ->
        SlockRaftUiSelectionOption(
            id = role.wireValue,
            label = role.label,
            selected = role == selectedRole
        )
    }

internal enum class MembersAgentServerRoleSaveOutcome {
    Success,
    Empty,
    MismatchedContent,
    NoPermission,
    Error
}

internal fun DataResult<MemberProfile>.membersAgentServerRoleSaveOutcome(
    expectedMemberId: MemberId,
    expectedServerId: ServerId,
    expectedRole: MembersAgentServerRole
): MembersAgentServerRoleSaveOutcome =
    when (this) {
        is DataResult.Content ->
            if (
                data.id == expectedMemberId &&
                data.serverId == expectedServerId &&
                    data.serverRole.toMembersAgentServerRoleOrNull() == expectedRole
            ) {
                MembersAgentServerRoleSaveOutcome.Success
            } else {
                MembersAgentServerRoleSaveOutcome.MismatchedContent
            }
        is DataResult.Empty -> MembersAgentServerRoleSaveOutcome.Empty
        is DataResult.NoPermission -> MembersAgentServerRoleSaveOutcome.NoPermission
        is DataResult.Error -> MembersAgentServerRoleSaveOutcome.Error
    }

private fun String?.normalizedMembersServerRole(): String =
    this?.trim()?.lowercase().orEmpty()

// OHOS's zero-argument native string lookup routes through the variadic
// OH_ResourceManager_GetStringByName API. Reading a resource that contains
// %1$s without an argument makes the platform formatter dereference a missing
// vararg. Resolve templates through KmmResource's formatted service path with
// a sentinel, then restore the placeholder for callbacks that supply the
// eventual value outside composition.
private const val MembersFormatSentinel = "__RAFT_MEMBER_FORMAT_VALUE__"

@Composable
private fun membersStringTemplate(resource: StringResource): String =
    stringResource(resource, MembersFormatSentinel)
        .replace(MembersFormatSentinel, "%1\$s")

private fun String.membersFormat(value: String): String = replace("%1\$s", value)

internal fun membersFormattedScreenError(
    template: String,
    error: ScreenError,
    errorStrings: ErrorPresentationStrings
): String =
    template.membersFormat(error.userFacingMessage(errorStrings))

internal fun membersFormattedPermissionError(
    template: String,
    blocker: PermissionBlocker,
    errorStrings: ErrorPresentationStrings
): String =
    template.membersFormat(blocker.userFacingMessage(errorStrings))

internal fun membersFirstScreenErrorOrFallback(
    results: List<DataResult<*>>,
    fallbackCode: String
): ScreenError =
    results.firstNotNullOfOrNull { (it as? DataResult.Error)?.error }
        ?: ScreenError(code = fallbackCode, retryable = true)

internal fun membersCanRemoveHumanMember(
    actorRole: String?,
    targetRole: String?,
    targetMembershipStatus: String?,
    targetMemberId: String,
    currentUserId: String?,
    targetServerId: String?,
    currentServerId: String?,
    ownerCount: Int? = null
): Boolean {
    if (targetMembershipStatus != "active") return false
    val normalizedTargetId = targetMemberId.trim()
    if (normalizedTargetId.isBlank()) return false
    if (currentUserId?.trim() == normalizedTargetId) return false
    val normalizedCurrentServerId = currentServerId?.trim().orEmpty()
    val normalizedTargetServerId = targetServerId?.trim().orEmpty()
    if (normalizedTargetServerId.isNotBlank() && normalizedTargetServerId != normalizedCurrentServerId) {
        return false
    }

    return when (actorRole.normalizedMembersServerRole()) {
        "owner" -> when (targetRole.normalizedMembersServerRole()) {
            "owner" -> ownerCount != null && ownerCount > 1
            "admin", "member", "" -> true
            else -> false
        }
        "admin" -> targetRole.normalizedMembersServerRole() == "member"
        else -> false
    }
}

internal enum class MembersDirectMessageEligibility {
    Self,
    Peer,
    Unavailable
}

internal fun membersDirectMessageEligibility(
    member: MemberProfile,
    currentUserId: String?,
    currentServerId: String?,
    deleted: Boolean = false
): MembersDirectMessageEligibility {
    val memberId = member.id.value.trim()
    if (memberId.isBlank() || member.kind == MemberKind.System || deleted) {
        return MembersDirectMessageEligibility.Unavailable
    }
    val membershipStatus = member.membershipStatus?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
    if (membershipStatus != null && membershipStatus != "active") {
        return MembersDirectMessageEligibility.Unavailable
    }
    if (member.kind == MemberKind.Human) {
        val memberServerId = member.serverId?.value?.trim().orEmpty()
        val activeServerId = currentServerId?.trim().orEmpty()
        if (memberServerId.isNotBlank() && activeServerId.isNotBlank() && memberServerId != activeServerId) {
            return MembersDirectMessageEligibility.Unavailable
        }
        if (memberId == currentUserId?.trim()) {
            return MembersDirectMessageEligibility.Self
        }
    }
    return MembersDirectMessageEligibility.Peer
}

internal fun membersVisibleAgentTabs(canViewPrivateSurfaces: Boolean, deleted: Boolean): List<MembersAgentDetailTab> =
    MembersAgentDetailTab.entries.filter { tab ->
        when {
            tab == MembersAgentDetailTab.Profile -> true
            else -> canViewPrivateSurfaces
        }
    }

internal fun membersAgentDetailTabsForAccess(
    accessMode: MemberDetailAccessMode,
    canViewPrivateSurfaces: Boolean,
    deleted: Boolean
): List<MembersAgentDetailTab> =
    if (!accessMode.allowsPrivateAgentSurfaces) {
        listOf(MembersAgentDetailTab.Profile)
    } else {
        membersVisibleAgentTabs(canViewPrivateSurfaces, deleted)
    }

internal fun membersAgentDetailShowsProfileStateNotice(
    selectedTab: MembersAgentDetailTab,
    avatarManagementMode: Boolean
): Boolean =
    selectedTab == MembersAgentDetailTab.Profile &&
        !avatarManagementMode

internal fun membersOrderedAgentTabs(
    visibleTabs: List<MembersAgentDetailTab>,
    storedOrder: List<String>
): List<MembersAgentDetailTab> {
    if (visibleTabs.isEmpty() || storedOrder.isEmpty()) return visibleTabs
    val visibleByKey = visibleTabs.associateBy { it.persistedOrderKey }
    val ordered = mutableListOf<MembersAgentDetailTab>()
    storedOrder.forEach { rawKey ->
        val tab = visibleByKey[rawKey.toMembersAgentTabOrderKey()] ?: return@forEach
        if (tab !in ordered) ordered += tab
    }
    return ordered + visibleTabs.filterNot { it in ordered }
}

private val MembersAgentDetailTab.persistedOrderKey: String
    get() =
        when (this) {
            MembersAgentDetailTab.Chat -> "chat"
            else -> name.lowercase()
        }

internal val MembersAgentDetailTab.serverOrderKey: String
    get() =
        when (this) {
            MembersAgentDetailTab.Apps -> "integrations"
            else -> name.lowercase()
        }

internal fun List<MembersAgentDetailTab>.toMembersAgentPanelTabOrderWireKeys(): List<String> =
    map { it.serverOrderKey }

/**
 * Pager index the agent-detail [TabPager] must show for [selectedTab]. Index-
 * aligned with [visibleTabs] exactly like the pager's pages; a selection that
 * is not visible (stale override while tabs are recomputed) pins page 0
 * rather than a negative index, mirroring `selectedTab`'s own fallback.
 */
internal fun membersAgentDetailPagerIndex(
    visibleTabs: List<MembersAgentDetailTab>,
    selectedTab: MembersAgentDetailTab
): Int = visibleTabs.indexOf(selectedTab).coerceAtLeast(0)

/**
 * Tab a pager page renders, or null when the page index has no tab (pager
 * page count and [visibleTabs] can disagree for one frame while visibility
 * changes) — the caller composes an empty page instead of crashing.
 */
internal fun membersAgentDetailPageTab(
    visibleTabs: List<MembersAgentDetailTab>,
    page: Int
): MembersAgentDetailTab? = visibleTabs.getOrNull(page)

/**
 * Whether [pageTab] should compose its full tab body inside the agent-detail
 * [TabPager]. Unvisited tabs render a lightweight loading placeholder so
 * opening Agent Detail only composes the initial [selectedTab]; once a tab has
 * been selected (added to [visitedTabs]), it remains composed across swipes
 * (`keepItemAlive = true`) so swiping back and forth never unmounts or flashes
 * a loading spinner.
 */
internal fun membersAgentDetailShouldComposeTab(
    pageTab: MembersAgentDetailTab?,
    selectedTab: MembersAgentDetailTab,
    visitedTabs: Set<MembersAgentDetailTab>,
): Boolean = pageTab != null && (pageTab == selectedTab || pageTab in visitedTabs)

internal fun List<MembersAgentDetailTab>.membersMoveTab(
    fromIndex: Int,
    toIndex: Int
): List<MembersAgentDetailTab> {
    if (fromIndex !in indices || toIndex !in indices || fromIndex == toIndex) return this
    val mutable = toMutableList()
    val item = mutable.removeAt(fromIndex)
    mutable.add(toIndex, item)
    return mutable
}

internal fun membersAgentTabVisibleAnchorIndex(
    tabs: List<MembersAgentDetailTab>,
    selectedTab: MembersAgentDetailTab
): Int {
    val selectedIndex = tabs.indexOf(selectedTab)
    return when {
        selectedIndex <= 0 -> 0
        else -> selectedIndex - 1
    }
}

/**
 * Stable identity for the lazy body item on an agent detail page.
 *
 * The body swaps whole composable surfaces (Profile/Chat/…); giving each surface a
 * distinct identity prevents the Kuikly lazy provider from reusing a different
 * surface's remember-slot table during a tab transition.
 */
internal fun membersAgentDetailBodyItemKey(memberId: String, selectedTab: MembersAgentDetailTab): String =
    "member-detail-${memberId.trim()}-tab-${selectedTab.name}"

private fun String.toMembersAgentTabOrderKey(): String =
    trim().lowercase()
        .replace("-", "_")
        .let { key ->
            when (key) {
                "dms", "chat", "channels", "agent_dms", "agentdms", "dm", "direct_messages", "directmessages" -> "chat"
                "integrations", "connected_apps", "connectedapps", "app_logins", "applogins" -> "apps"
                else -> key
            }
        }

private fun String?.toMembersAgentDetailTabOrNull(): MembersAgentDetailTab? {
    val normalized = this?.toMembersAgentTabOrderKey() ?: return null
    return MembersAgentDetailTab.entries.firstOrNull { tab ->
        tab.persistedOrderKey == normalized ||
        tab.name.lowercase() == normalized || tab.label.lowercase().replace(" ", "_") == normalized
    }
}

internal fun membersInitialAgentDetailTabForLaunch(
    explicitInitialTab: String?,
    agentPanelTabOrder: List<String>,
    selectedMemberKind: MemberKind?
): MembersAgentDetailTab? {
    if (selectedMemberKind != MemberKind.Agent) return explicitInitialTab.toMembersAgentDetailTabOrNull()
    return explicitInitialTab.toMembersAgentDetailTabOrNull()
        ?: agentPanelTabOrder.firstNotNullOfOrNull { it.toMembersAgentDetailTabOrNull() }
}

internal fun membersInitialAgentDetailTabForRender(
    expectedKind: MemberKind?,
    explicitInitialTab: MembersAgentDetailTab?,
    payloadAgentPanelTabOrder: List<String>,
    storeServerMatches: Boolean,
    storeLoaded: Boolean
): MembersAgentDetailTab? {
    if (expectedKind != MemberKind.Agent) return explicitInitialTab
    explicitInitialTab?.let { return it }
    payloadAgentPanelTabOrder.firstNotNullOfOrNull { it.toMembersAgentDetailTabOrNull() }?.let { return it }
    return if (!storeServerMatches || !storeLoaded) MembersAgentDetailTab.Profile else null
}

internal fun memberDetailAgentPanelTabOrderServerId(
    routeServerId: String?,
    payloadSourceServerId: String
): String? =
    routeServerId.cleanMemberDetailServerId()
        ?: payloadSourceServerId.cleanMemberDetailServerId()

/**
 * Task #336: `machineKnown` is the "this machineId is registered on the current
 * server" gate. The web Computers surface 404s when the App opens a machineId
 * the server does not list, so the link must not exist then. The gate is a
 * three-state enum mirroring [membersMachineStatusRow]: [Unknown] while the
 * computers store has not answered (keep the affordance), [Absent] once a
 * loaded snapshot proves the machine is not on this server (withdraw it).
 */
internal enum class MembersAgentComputerKnownState {
    Unknown,
    Present,
    Absent,
}

internal fun membersAgentComputerKnownState(
    machineId: String?,
    machines: List<ComputerSummary>,
    loadStatus: SharedComputersLoadStatus,
    hasSnapshot: Boolean
): MembersAgentComputerKnownState {
    val id = machineId?.trim()?.takeIf { it.isNotBlank() }
        ?: return MembersAgentComputerKnownState.Absent
    if (!hasSnapshot && loadStatus != SharedComputersLoadStatus.Loaded) {
        return MembersAgentComputerKnownState.Unknown
    }
    return if (machines.any { it.id == id }) {
        MembersAgentComputerKnownState.Present
    } else {
        MembersAgentComputerKnownState.Absent
    }
}

internal fun membersAgentComputerNavigationTarget(
    serverId: String?,
    summaryMachineId: String?,
    memberMachineId: String?,
    accessMode: MemberDetailAccessMode,
    machineKnown: MembersAgentComputerKnownState = MembersAgentComputerKnownState.Unknown,
): SettingsComputerRouteIdentity? {
    if (!accessMode.allowsPrivateAgentSurfaces) return null
    if (machineKnown == MembersAgentComputerKnownState.Absent) return null
    val normalizedServerId = serverId.cleanMemberDetailServerId() ?: return null
    val normalizedMachineId =
        summaryMachineId?.trim()?.takeIf { it.isNotBlank() }
            ?: memberMachineId?.trim()?.takeIf { it.isNotBlank() }
            ?: return null
    return SettingsComputerRouteIdentity(
        serverId = normalizedServerId,
        machineId = normalizedMachineId,
    )
}

private fun String?.cleanMemberDetailServerId(): String? =
    this?.trim()?.takeIf { it.isNotBlank() }

private sealed interface MembersAgentDetailLoadState {
    data object Idle : MembersAgentDetailLoadState
    data object Loading : MembersAgentDetailLoadState
    data class Content(
        val detail: AgentDetailSnapshot,
        val refreshing: Boolean = false
    ) : MembersAgentDetailLoadState
    data class NoPermission(val blocker: PermissionBlocker) : MembersAgentDetailLoadState
    data class Error(val error: ScreenError) : MembersAgentDetailLoadState
    data object Empty : MembersAgentDetailLoadState
}

internal sealed interface MembersAgentActivityLoadState {
    data object Idle : MembersAgentActivityLoadState
    data object Loading : MembersAgentActivityLoadState
    data object Refreshing : MembersAgentActivityLoadState
    data class Error(val error: ScreenError) : MembersAgentActivityLoadState
}

internal data class MembersAgentActivityRequestFence(
    val memberId: MemberId,
    val serverId: ServerId,
    val generation: Long
) {
    fun matches(
        identity: MemberDetailIdentity?,
        routeServerId: ServerId?,
        currentGeneration: Long
    ): Boolean =
        identity != null &&
            identity.memberId == memberId &&
            identity.kind == MemberKind.Agent &&
            (identity.sourceServerId == null || identity.sourceServerId == serverId) &&
            routeServerId == serverId &&
            currentGeneration == generation
}

internal data class MembersAgentProfileRequestFence(
    val memberId: MemberId,
    val serverId: ServerId,
    val generation: Long
) {
    fun matches(
        identity: MemberDetailIdentity?,
        activeServerId: String?,
        currentGeneration: Long
    ): Boolean =
        identity != null &&
            identity.memberId == memberId &&
            identity.kind == MemberKind.Agent &&
            membersAgentProfileRequestServerId(identity, activeServerId) == serverId &&
            currentGeneration == generation
}

internal fun membersAgentProfileRequestServerId(
    identity: MemberDetailIdentity,
    activeServerId: String?
): ServerId? =
    when (identity.memberDetailAccessMode(activeServerId)) {
        MemberDetailAccessMode.LocalFull ->
            activeServerId?.trim()?.takeIf { it.isNotBlank() }?.let(::ServerId)
        MemberDetailAccessMode.RemoteProfileOnly -> identity.sourceServerId
    }

internal data class MembersAgentRuntimeOptionsRequestFence(
    val memberId: MemberId,
    val serverId: ServerId,
    val machineId: String,
    val runtimeId: String,
    val profileGeneration: Long,
    val generation: Long
) {
    fun matches(
        identity: MemberDetailIdentity?,
        activeServerId: String?,
        currentMachineId: String?,
        currentRuntimeId: String?,
        currentProfileGeneration: Long,
        currentGeneration: Long
    ): Boolean =
        identity != null &&
            identity.memberId == memberId &&
            identity.kind == MemberKind.Agent &&
            membersAgentProfileRequestServerId(identity, activeServerId) == serverId &&
            currentMachineId?.trim() == machineId &&
            currentRuntimeId?.trim() == runtimeId &&
            currentProfileGeneration == profileGeneration &&
            currentGeneration == generation
}

internal fun membersAgentRuntimeRequestCompletionInFlight(
    inFlight: Set<Pair<String, String>>,
    generations: Map<Pair<String, String>, Long>,
    key: Pair<String, String>,
    requestGeneration: Long
): Set<Pair<String, String>> =
    if (generations[key] == requestGeneration) inFlight - key else inFlight

internal data class MembersAgentProfileEditAuthority(
    val memberId: MemberId,
    val environmentId: String,
    val accountId: String,
    val serverId: ServerId,
    val profileGeneration: Long
) {
    fun ownerOrNull(): SharedAgentDetailOwner? =
        sharedAgentDetailOwner(environmentId, accountId, serverId)
}

internal fun membersAgentProfileEditAuthorityMatches(
    opened: MembersAgentProfileEditAuthority?,
    current: MembersAgentProfileEditAuthority?,
    openedCatalogGeneration: Long,
    currentCatalogGeneration: Long
): Boolean =
    opened != null &&
        opened == current &&
        openedCatalogGeneration == currentCatalogGeneration

internal fun membersAgentProfileStoreMatchesAuthority(
    state: SharedAgentDetailState,
    authority: MembersAgentProfileEditAuthority
): Boolean =
    state.detailOwner == authority.ownerOrNull() &&
        state.detailProfileGeneration == authority.profileGeneration &&
        state.agentId == authority.memberId &&
        state.detailServerId == authority.serverId &&
        state.detail?.agentId == authority.memberId

internal fun membersAgentActivityLoadStart(hasCachedActivity: Boolean): MembersAgentActivityLoadState =
    if (hasCachedActivity) MembersAgentActivityLoadState.Refreshing else MembersAgentActivityLoadState.Loading

internal fun DataResult<List<AgentActivityEntry>>.toMembersAgentActivityLoadState(): MembersAgentActivityLoadState =
    when (this) {
        is DataResult.Content,
        is DataResult.Empty -> MembersAgentActivityLoadState.Idle
        is DataResult.Error -> MembersAgentActivityLoadState.Error(error)
        is DataResult.NoPermission ->
            MembersAgentActivityLoadState.Error(ScreenError(blocker.code, retryable = false))
    }

private fun SharedAgentDetailState.toMembersAgentDetailLoadState(): MembersAgentDetailLoadState =
    when (detailLoadStatus) {
        SharedAgentDetailLoadStatus.Idle -> MembersAgentDetailLoadState.Idle
        SharedAgentDetailLoadStatus.Loading -> detail?.let { MembersAgentDetailLoadState.Content(it, refreshing = true) }
            ?: MembersAgentDetailLoadState.Loading
        SharedAgentDetailLoadStatus.Content -> detail?.let(MembersAgentDetailLoadState::Content)
            ?: MembersAgentDetailLoadState.Empty
        SharedAgentDetailLoadStatus.NoPermission ->
            MembersAgentDetailLoadState.NoPermission(
                detailPermissionBlocker ?: PermissionBlocker("server_forbidden")
            )
        SharedAgentDetailLoadStatus.Error ->
            MembersAgentDetailLoadState.Error(
                detailError ?: ScreenError("member_detail_load_failed")
            )
        SharedAgentDetailLoadStatus.Empty -> MembersAgentDetailLoadState.Empty
    }

private sealed interface MembersAgentWorkspaceTreeState {
    data object Idle : MembersAgentWorkspaceTreeState
    data object Loading : MembersAgentWorkspaceTreeState
    data class Content(val snapshot: AgentWorkspaceTreeSnapshot) : MembersAgentWorkspaceTreeState
    data class NoPermission(val blocker: PermissionBlocker) : MembersAgentWorkspaceTreeState
    data class Error(val error: ScreenError) : MembersAgentWorkspaceTreeState
    data object Empty : MembersAgentWorkspaceTreeState
}

internal enum class MembersAgentLifecycleAction(
    val actionKey: String,
    val label: String,
    val iconName: SlockLucideIconName,
    val successLabel: String,
    val destructive: Boolean = false
) {
    Start("start", "Start", SlockLucideIconName.Play, "Started"),
    Stop("stop", "Stop", SlockLucideIconName.Square, "Stopped"),
    Restart("restart", "Restart", SlockLucideIconName.RotateCw, "Restarted"),
    ResetSession("reset_session", "Reset session", SlockLucideIconName.RefreshCw, "Session reset"),
    ResetFull("reset_full", "Full reset", SlockLucideIconName.Eraser, "Full reset"),
    // task #515: the action is the web "archive" (former delete). Enum labels are
    // non-localized fallbacks; user-visible text comes from MR strings.
    Delete("delete", "Archive Agent", SlockLucideIconName.Archive, "Archived", destructive = true),
    UnassignMachine("assign_machine", "Unassign machine", SlockLucideIconName.MonitorX, "Machine unassigned");

    val resetMode: AgentResetMode?
        get() =
            when (this) {
                Restart -> AgentResetMode.Restart
                ResetSession -> AgentResetMode.Session
                ResetFull -> AgentResetMode.Full
                Start,
                Stop,
                Delete,
                UnassignMachine -> null
            }

    val requiresConfirmation: Boolean
        get() = this != Start
}

@Composable
private fun MembersAgentLifecycleAction.localizedLabel(): String =
    when (this) {
        MembersAgentLifecycleAction.Start -> stringResource(MR.strings.member_action_start_agent)
        MembersAgentLifecycleAction.Stop -> stringResource(MR.strings.member_action_stop_agent)
        MembersAgentLifecycleAction.Restart -> stringResource(MR.strings.member_action_restart_agent)
        MembersAgentLifecycleAction.ResetSession -> stringResource(MR.strings.member_action_reset_runtime_session)
        MembersAgentLifecycleAction.ResetFull -> stringResource(MR.strings.member_action_full_reset_restart)
        MembersAgentLifecycleAction.Delete -> stringResource(MR.strings.member_action_delete_agent)
        MembersAgentLifecycleAction.UnassignMachine -> stringResource(MR.strings.member_action_unassign_machine)
    }

@Composable
private fun MembersAgentLifecycleAction.localizedBusyLabel(): String =
    when (this) {
        MembersAgentLifecycleAction.Start -> stringResource(MR.strings.member_action_starting)
        MembersAgentLifecycleAction.Stop -> stringResource(MR.strings.member_action_stopping)
        MembersAgentLifecycleAction.Delete -> stringResource(MR.strings.member_action_deleting)
        MembersAgentLifecycleAction.Restart -> stringResource(MR.strings.member_action_restarting)
        MembersAgentLifecycleAction.ResetSession,
        MembersAgentLifecycleAction.ResetFull -> stringResource(MR.strings.member_action_resetting)
        MembersAgentLifecycleAction.UnassignMachine -> stringResource(MR.strings.member_action_unassigning)
    }

private data class MembersAgentActionState(
    val action: MembersAgentLifecycleAction,
    val inProgress: Boolean,
    val error: ScreenError? = null,
    val successLabel: String? = null
)

private data class MembersAgentDiagnosticActionState(
    val actionKey: String,
    val inProgress: Boolean,
    val error: ScreenError? = null,
    val successLabel: String? = null
)

private const val MembersUnknownAgentStatusLabel = "—"

internal fun membersPrimaryLifecycleAction(status: AgentStatus?, deleted: Boolean): MembersAgentLifecycleAction? =
    if (deleted) {
        null
    } else {
        when (status) {
            AgentStatus.Offline,
            AgentStatus.Disconnected,
            AgentStatus.Error,
            null -> MembersAgentLifecycleAction.Start
            AgentStatus.Online,
            AgentStatus.Working,
            AgentStatus.Thinking,
            AgentStatus.Idle -> MembersAgentLifecycleAction.Stop
        }
    }

internal fun membersResolvedPrimaryLifecycleAction(
    resolvedStatus: AgentStatus?,
    detailFallbackStatus: AgentStatus?,
    deleted: Boolean,
    hasResolvedPresence: Boolean = resolvedStatus != null
): MembersAgentLifecycleAction? =
    when {
        deleted -> null
        // During reconnect reconciliation the presence SSOT deliberately
        // publishes an explicit unknown state. Do not turn an old detail
        // fallback into an unsafe Start/Stop action in that window.
        hasResolvedPresence && resolvedStatus == null -> null
        else ->
            membersPrimaryLifecycleAction(
                status = if (hasResolvedPresence) resolvedStatus else detailFallbackStatus,
                deleted = false
            )
    }

/**
 * Detail-page dot + caption for an external agent. Tiers mirror web
 * `AgentStatusBadge`; the caption is localized here because the pure mapping
 * ([externalAgentDetailIndicator]) must stay free of string resources.
 * A missing status (non-external, 403, failure) renders plain cyan "External".
 */
@Composable
private fun rememberExternalAgentDetailIndicatorState(
    detail: AgentDetailSnapshot?,
    timeContext: SlockTimeContext
): SlockAgentStatusIndicatorState {
    val externalStatus = detail?.externalStatus
    // task #410: lastSeenAt comes from the detail summary (GET /agents/:id
    // carries it for external agents) and realtime agent:seen updates it in
    // SharedAgentDetailStore — no extra request.
    val lastSeenAt = detail?.summary?.lastSeenAt
    val now = timeContext.nowEpochMillis ?: remember(externalStatus) { defaultNowMillis() }
    val indicator = remember(externalStatus, lastSeenAt, now) {
        externalAgentDetailIndicator(externalStatus, now, lastSeenAt)
    }
    val label =
        when (val caption = indicator.caption) {
            ExternalAgentDetailCaption.External -> stringResource(MR.strings.member_external_status)
            ExternalAgentDetailCaption.Online -> stringResource(MR.strings.member_external_online)
            ExternalAgentDetailCaption.SetupRequired -> stringResource(MR.strings.member_external_setup_required)
            ExternalAgentDetailCaption.Active -> stringResource(MR.strings.member_external_active)
            is ExternalAgentDetailCaption.LastActivity ->
                membersStringTemplate(MR.strings.member_external_last_activity)
                    .membersFormat(timeContext.formatTimestampLabel(caption.iso).ifBlank { caption.iso })
            is ExternalAgentDetailCaption.LastSeen ->
                membersStringTemplate(MR.strings.member_external_last_seen)
                    .membersFormat(timeContext.formatTimestampLabel(caption.iso).ifBlank { caption.iso })
            ExternalAgentDetailCaption.NoRaftActivity -> stringResource(MR.strings.member_external_no_raft_activity)
            ExternalAgentDetailCaption.NotConfigured -> stringResource(MR.strings.member_external_not_configured)
        }
    return indicator.state.copy(label = label)
}

private fun unknownMembersAgentStatusIndicatorState(): SlockAgentStatusIndicatorState =
    slockAgentStatusIndicatorState(
        status = null,
        presence = Presence.Offline,
        statusLabel = MembersUnknownAgentStatusLabel,
        external = false
    )

/**
 * The observed state, or null when nothing authoritative (or fixture-supplied) exists.
 *
 * Surfaces that paint a dot must use this, never [resolveMembersAgentStatusIndicatorState]:
 * that one substitutes [unknownMembersAgentStatusIndicatorState], whose colour maps through
 * `Presence.Offline` to StatusNeutralGray, so an unknown agent would render the same dot as
 * an offline one while its label reads "—" (task #256; @LiBai's review of PR #2325 caught the
 * first attempt fixing only the elvis-fallback spelling of this defect and missing this one).
 */
internal fun resolveMembersAgentStatusIndicatorStateOrNull(
    canonicalState: SlockAgentStatusIndicatorState?,
    fixtureFallback: SlockAgentStatusIndicatorState?,
    fixtureStatusFallbackEnabled: Boolean
): SlockAgentStatusIndicatorState? =
    canonicalState ?: fixtureFallback.takeIf { fixtureStatusFallbackEnabled }

/**
 * Label-facing resolution: never null, because an explicit unknown must outrank a stale
 * summary caption (see `explicitCanonicalUnknownCannotBeCaptionedByStaleSummaryActivity`).
 * Do not use it to choose a dot colour.
 */
internal fun resolveMembersAgentStatusIndicatorState(
    canonicalState: SlockAgentStatusIndicatorState?,
    fixtureFallback: SlockAgentStatusIndicatorState?,
    fixtureStatusFallbackEnabled: Boolean
): SlockAgentStatusIndicatorState =
    resolveMembersAgentStatusIndicatorStateOrNull(
        canonicalState = canonicalState,
        fixtureFallback = fixtureFallback,
        fixtureStatusFallbackEnabled = fixtureStatusFallbackEnabled
    ) ?: unknownMembersAgentStatusIndicatorState()

private fun SlockAgentStatusIndicatorState.toMembersAgentStatusDisplayState(): MembersAgentStatusDisplayState =
    MembersAgentStatusDisplayState(
        status = status,
        color = color,
        pulsing = pulsing,
        external = external
    )

@Page(MEMBERS_SMOKE_PAGE, supportInLocal = true)
internal class ComposeMembersSmokePage : build.raft.app.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent {
            MembersRouteContent(
                payload = membersSmokePayload,
                onAddHuman = {},
                onOpenMemberDetail = {}
            )
        }
    }
}

@Page(MEMBERS_PAGE, supportInLocal = true)
internal class ComposeMembersPage : AuthenticatedProductPager(AuthenticatedProductRoute.Members) {
    private var adaptivePane by mutableStateOf(SlockAdaptivePaneState())

    override fun willInit() {
        super.willInit()
        setAuthenticatedContent { MembersPageContent(paneLayout = adaptivePane.layout) }
    }

    override fun onSlockAdaptivePaneChanged(eventData: JSONObject) {
        val event = eventData.toSlockAdaptivePaneEventOrNull() ?: return
        adaptivePane = reduceSlockAdaptivePane(adaptivePane, event)
    }
}

@Page(MEMBER_DETAIL_PAGE, supportInLocal = true)
internal class ComposeMemberDetailPage : AuthenticatedProductPager(AuthenticatedProductRoute.MemberDetail) {
    override fun willInit() {
        super.willInit()
        setAuthenticatedContent { MemberDetailPageContent(slockPageVisibleResumeTick) }
    }
}

@Serializable
data class KuiklyMembersPayload(
    val instanceId: String = "",
    val title: String = "Members",
    val labels: KuiklyMembersLabels = KuiklyMembersLabels(),
    val members: List<MemberProfile> = emptyList(),
    val initialAgentTab: String? = null,
    val localOffsetMinutes: Int = 0,
    val todayEpochDay: Long? = null,
    val nowEpochMillis: Long? = null,
    val timeFormat: String = SlockTimeFormat24Hour,
    val agentPanelTabOrder: List<String> = emptyList(),
    /**
     * Whether the active session can create agents. Driven by the host from
     * server capabilities (mirrors React's `useServerPermissions().capabilities`).
     * When false, the agents "+" is hidden. When null/true, the "+" is rendered.
     * Field was added by #43 to address QA-2 from #42 review.
     */
    val canCreateAgents: Boolean = true,
    /**
     * Whether the active session can invite human members. Driven by the host
     * from server capabilities. When false, the humans "+" is hidden and the
     * invite dialog cannot be opened from this page.
     */
    val canInviteHumans: Boolean = true,
    /** Server-wide human directory visibility; conversation member lists are separate. */
    val showHumanDirectory: Boolean = true
)

internal data class KuiklyMemberDetailPayload(
    val instanceId: String = "",
    val identity: MemberDetailIdentity? = null,
    val initialAgentTab: String? = null,
    val visualMode: String? = null,
    val localOffsetMinutes: Int = 0,
    val todayEpochDay: Long? = null,
    val nowEpochMillis: Long? = null,
    val timeFormat: String = SlockTimeFormat24Hour,
    val agentPanelTabOrder: List<String> = emptyList()
)

@Serializable
data class KuiklyMembersLabels(
    val agents: String = "AGENTS",
    val humans: String = "HUMANS",
    val addHuman: String = "Add human"
)

internal fun canonicalMembersDisplayText(
    value: String,
    canonicalDefault: String,
    localizedDefault: String
): String = if (value == canonicalDefault) localizedDefault else value

private val membersSmokePayload = KuiklyMembersPayload(
    title = "Members",
    members = listOf(
        MemberProfile(
            id = build.raft.app.model.MemberId("cindy"),
            displayName = "Cindy",
            handle = "Onboarding Assistant",
            kind = MemberKind.Agent,
            description = "Onboarding Assistant",
            roleDescription = "agent",
            avatar = build.raft.app.model.AvatarReference.Pixel("heart"),
            avatarLabel = "C",
            presence = build.raft.app.model.Presence.Active,
            agentStatus = build.raft.app.model.AgentStatus.Online,
            permissionBadges = listOf("computer:MacBook Pro")
        ),
        MemberProfile(
            id = build.raft.app.model.MemberId("builder"),
            displayName = "Builder",
            handle = "@builder",
            kind = MemberKind.Agent,
            description = "File and repo automation",
            avatar = build.raft.app.model.AvatarReference.Pixel("robot"),
            avatarLabel = "B",
            presence = build.raft.app.model.Presence.Away,
            agentStatus = build.raft.app.model.AgentStatus.Working,
            permissionBadges = listOf("computer:MacBook Pro")
        ),
        MemberProfile(
            id = build.raft.app.model.MemberId("artin"),
            displayName = "Artin",
            handle = "@artin",
            kind = MemberKind.Human,
            description = "Owner",
            avatarLabel = "A",
            presence = build.raft.app.model.Presence.Active
        ),
        MemberProfile(
            id = build.raft.app.model.MemberId("jiacheng"),
            displayName = "Jiacheng",
            handle = "@jiacheng",
            kind = MemberKind.Human,
            description = "Android",
            avatarLabel = "J",
            presence = build.raft.app.model.Presence.Away
        )
    )
)

/**
 * The four machines from packages/visual-testing/shared/fixtureData.json, as the computers
 * store would hold them, so the visual routes resolve the status row from a loaded store
 * rather than from an empty one (which would render every fixture agent as "no computer
 * assigned" -- the exact defect #2355 removed from the avatar grid).
 *
 * Why literals and not an injected list: the machine-status capture cases run on the
 * production `compose_member_detail` page (MEMBER_DETAIL_PAGE, this file), whose `visual-`
 * seeds are built here in commonMain. That route has no gated wrapper that could read
 * VisualFixtureData and pass a `List<ComputerSummary>` in. (The gated
 * `compose_members_agent_detail_visual` page *could* inject -- it just doesn't carry these
 * cells.) The drift gate (visual-testing.yml "Check fixture data drift") covers
 * VisualFixtureData.kt, NOT these lines; a separate lint (XiShi, task #259) asserts each row
 * below equals VisualFixtureData.Machines.* field for field, with `computer-missing`
 * listed as the deliberate absent id. Keep the two in lockstep.
 */
private val membersVisualFixtureMachines: List<ComputerSummary> =
    listOf(
        membersVisualFixtureMachine("computer-mbp", "Jiachengs-MacBook-Pro", ComputerStatus.Online, isComputer = true, computerVersion = "0.0.48", daemonVersion = "0.65.0"),
        membersVisualFixtureMachine("computer-studio", "Studio Test Rig", ComputerStatus.Offline, isComputer = true, computerVersion = "0.0.50", daemonVersion = "0.65.0"),
        membersVisualFixtureMachine("computer-long-name", "ci-visual-capture-runner-us-west-2b_node17_macbookpro_m4max_buildhost_2026", ComputerStatus.Online, isComputer = true, computerVersion = "0.0.48", daemonVersion = "0.65.0"),
        membersVisualFixtureMachine("computer-daemon-only", "build-box-bare-daemon", ComputerStatus.Online, isComputer = false, computerVersion = null, daemonVersion = "0.65.0")
    )

private fun membersVisualFixtureMachine(
    id: String,
    name: String,
    status: ComputerStatus,
    isComputer: Boolean,
    computerVersion: String?,
    daemonVersion: String?
): ComputerSummary =
    ComputerSummary(
        id = id,
        name = name,
        status = status,
        statusVersion = 0L,
        apiKeyPrefix = null,
        runtimes = emptyList(),
        daemonVersion = daemonVersion,
        computerVersion = computerVersion,
        isComputer = isComputer,
        hostname = null,
        os = null,
        description = null,
        lastHeartbeat = null,
        createdAt = null
    )

/** Fixture routes: the store is "loaded" with the fixture machines; `computer-missing` resolves to NoComputer by design. */
private val membersVisualFixtureMachineStatusRow: (String?) -> MembersMachineStatusRow = { machineId ->
    membersMachineStatusRow(
        machineId = machineId,
        machines = membersVisualFixtureMachines,
        loadStatus = SharedComputersLoadStatus.Loaded,
        hasSnapshot = true
    )
}

private val membersAgentDetailVisualMember = MemberProfile(
    id = MemberId("agent-product-ux"),
    displayName = "Product UX Designer",
    handle = "@Product-UX-Designer",
    kind = MemberKind.Agent,
    description = "Turns product feedback, screenshots, and release notes into actionable UX specs.",
    roleDescription = "Product / UX agent",
    avatar = AvatarReference.Pixel("robot"),
    avatarLabel = "P",
    presence = Presence.Active,
    agentStatus = AgentStatus.Working,
    permissionBadges = listOf("computer:MacBook Pro", "runtime:Codex")
)

private val membersHumanDetailVisualMember = MemberProfile(
    id = MemberId("visual-human-1"),
    displayName = "artin",
    handle = "@artin",
    kind = MemberKind.Human,
    description = "Product design and server administration.",
    roleDescription = "Member",
    serverRole = "member",
    serverId = ServerId("visual-server"),
    email = "artin@slock.ai",
    avatarLabel = "A",
    presence = Presence.Active,
    joinedAt = "2026-06-18T00:00:00.000Z",
    membershipStatus = "active"
)

private val membersAgentDetailVisualScopeSet = AgentScopeSet(
    agentId = membersAgentDetailVisualMember.id,
    granted = listOf(
        "inbox:receive",
        "server:read",
        "channel:read",
        "message:read",
        "message:send",
        "task:read",
        "task:write",
        "knowledge:read"
    ),
    mode = "custom",
    revision = 7L,
    updatedAt = "2026-06-19T12:28:00.000Z"
)

/**
 * task #535 / #537 — Computer-row oracle variants of the agent-detail visual seed.
 * Same page, same data except the identity and the Computer row inputs, so a
 * capture difference against the default seed is attributable to that row.
 * Values mirror packages/visual-testing/shared/fixtureData.json (agents
 * computerOffline / computerMissing / noComputer / longMachine, machines
 * studio / longName); the slock-side test pins them there.
 */
private data class MembersAgentDetailVisualVariant(
    val id: String,
    val name: String,
    val displayName: String,
    val avatarLabel: String,
    val description: String,
    val machineId: String?,
    val machineName: String?,
    val daemonVersion: String?,
    /** Server role as the wire sends it. Every fixture agent is a server member;
        one is admin so the role chip cell can fail (task #261). Without this the
        default seed leaves it null and membersAgentDisplayServerRole draws Member. */
    val serverRole: String
)

private val membersAgentDetailVisualVariants = listOf(
    // store knows the machine and reports it offline: web = gray dot + Offline
    MembersAgentDetailVisualVariant(
        id = "agent-computer-offline",
        name = "Computer-Offline-Probe",
        displayName = "Computer Offline Probe",
        avatarLabel = "C",
        description = "Bound to a computer the store knows and reports offline: web shows a gray dot and Offline.",
        machineId = "computer-studio",
        machineName = "Studio Test Rig",
        daemonVersion = "0.65.0",
        serverRole = "member"
    ),
    // machineId points at a machine the store never loaded: web = "No computer assigned", no dot
    MembersAgentDetailVisualVariant(
        id = "agent-computer-missing",
        name = "Computer-Missing-Probe",
        displayName = "Computer Missing Probe",
        avatarLabel = "C",
        description = "machineId points at a computer the store has never seen: web shows No computer assigned, no dot.",
        machineId = "computer-missing",
        machineName = null,
        daemonVersion = null,
        serverRole = "member"
    ),
    // no machineId at all: web = "No computer assigned", no dot
    MembersAgentDetailVisualVariant(
        id = "agent-no-computer",
        name = "No-Computer-Probe",
        displayName = "No Computer Probe",
        avatarLabel = "N",
        description = "No machineId at all: web shows No computer assigned, no dot.",
        machineId = null,
        machineName = null,
        daemonVersion = null,
        serverRole = "member"
    ),
    // a machine name that cannot fit one 390dp line (44-char run with no break opportunity)
    MembersAgentDetailVisualVariant(
        id = "agent-long-machine",
        name = "Long-Machine-Name-Probe",
        displayName = "Long Machine Name Probe",
        avatarLabel = "L",
        description = "Bound to a computer whose name cannot fit one 390dp line, so wrap vs truncate is observable.",
        machineId = "computer-long-name",
        machineName = "ci-visual-capture-runner-us-west-2b_node17_macbookpro_m4max_buildhost_2026",
        daemonVersion = "0.65.0",
        serverRole = "admin"
    ),
    // a bare daemon (isComputer=false) with no computer version: web = green dot + Online + "daemon v0.65.0"
    MembersAgentDetailVisualVariant(
        id = "agent-daemon-only",
        name = "Daemon-Only-Probe",
        displayName = "Daemon Only Probe",
        avatarLabel = "DO",
        description = "Bound to a bare daemon (not a managed Computer): web shows Connected · daemon vX, never a computer version.",
        machineId = "computer-daemon-only",
        machineName = "build-box-bare-daemon",
        daemonVersion = "0.65.0",
        serverRole = "member"
    )
)

private fun MembersAgentDetailVisualVariant.toMember(): MemberProfile =
    membersAgentDetailVisualMember.copy(
        id = MemberId(id),
        displayName = displayName,
        handle = "@$name",
        description = description,
        avatarLabel = avatarLabel,
        serverRole = serverRole,
        permissionBadges = listOfNotNull(machineName?.let { "computer:$it" }, "runtime:Codex")
    )

private fun MembersAgentDetailVisualVariant.toSeed(): MemberDetailVisualSeed {
    val member = toMember()
    // The default seed always carries a summary; a null here would mean the
    // default case itself lost its Computer row, which is the wrong place to hide.
    val summary = checkNotNull(membersAgentDetailVisualSnapshot.summary).copy(
        id = id,
        name = name,
        displayName = displayName,
        machineId = machineId,
        machineName = machineName,
        daemonVersion = daemonVersion,
        serverRole = serverRole,
        activityDetail = "Holding a fixed Computer-row state for the visual oracle"
    )
    return MemberDetailVisualSeed(
        member = member,
        agentDetailState = MembersAgentDetailLoadState.Content(
            membersAgentDetailVisualSnapshot.copy(agentId = member.id, summary = summary)
        ),
        agentScopeSet = membersAgentDetailVisualScopeSet.copy(agentId = member.id)
    )
}

private val membersAgentDetailVisualSnapshot = AgentDetailSnapshot(
    agentId = membersAgentDetailVisualMember.id,
    summary = AgentDetailSummary(
        id = membersAgentDetailVisualMember.id.value,
        name = "Product-UX-Designer",
        displayName = membersAgentDetailVisualMember.displayName,
        createdAt = "2026-06-18T00:00:00.000Z",
        status = "working",
        runtime = "codex",
        model = "gpt-5-codex",
        reasoning = "medium",
        executionMode = "byoc",
        machineId = "computer-mbp",
        machineName = "Jiachengs-MacBook-Pro",
        daemonVersion = "0.65.0",
        creatorId = "artin",
        creatorType = "user",
        creator = AgentCreatorSummary(
            type = "human",
            id = "artin",
            name = "artin",
            displayName = "artin",
            avatarUrl = "pixel:user"
        ),
        envVarNames = listOf("RAFT_PROFILE", "SLOCK_VISUAL_PROVIDER"),
        createdAgents = listOf(
            AgentCreatedSummary(
                id = "agent-visual-qa",
                name = "visual-qa",
                displayName = "Visual QA",
                runtime = "codex",
                status = "idle",
                isExternal = false
            )
        ),
        activity = "working",
        activityDetail = "Capturing deterministic Activity tab state"
    ),
    directMessages = listOf(
        AgentDmSummary(
            id = "dm-agent-product-ux-artin",
            peerId = "artin",
            peerName = "artin",
            lastMessageAt = "2026-06-19T12:24:00Z",
            lastMessagePreview = "201继续推进"
        )
    ),
    channels = listOf(
        AgentChannelSummary(
            id = "channel-product-design",
            name = "product-design",
            description = "Product design reviews and implementation handoffs."
        ),
        AgentChannelSummary(
            id = "channel-visual-testing",
            name = "visual-testing",
            description = "Visual validation results and capture evidence.",
            activityMuted = true,
            muteFromSeq = 1842L
        )
    ),
    reminders = listOf(
        AgentReminderSummary(
            id = "reminder-product-ux-memory",
            title = "Roll 1-6; if 3, tidy Product-UX memory/files",
            fireAt = "2026-06-19T18:30:00Z"
        )
    ),
    activity = listOf(
        AgentActivityEntry(
            id = "activity-agent-detail-working",
            type = "status",
            title = "Working",
            body = "Capturing deterministic Activity tab state",
            timestamp = "2026-06-19T12:28:00Z",
            statusActivity = "working",
            statusDetail = "Capturing deterministic Activity tab state"
        ),
        AgentActivityEntry(
            id = "activity-agent-detail-thinking",
            type = "thinking",
            title = "Thinking",
            body = "Comparing React and Android Members Activity screenshots before publishing.",
            timestamp = "2026-06-19T12:29:00Z"
        ),
        AgentActivityEntry(
            id = "activity-agent-detail-command",
            type = "tool_start",
            title = "Running command",
            body = "node scripts/slock-visual-testing.mjs diff --case screens.members.agent-detail.activity",
            timestamp = "2026-06-19T12:30:00Z",
            toolName = "shell",
            toolInput = "node scripts/slock-visual-testing.mjs diff --case screens.members.agent-detail.activity"
        ),
        AgentActivityEntry(
            id = "activity-agent-detail-report",
            type = "slock_action",
            title = "Posted status update",
            body = "Reported capture progress in #product:d4870bc3 and kept #225 in review.",
            timestamp = "2026-06-19T12:31:00Z",
            refTextEnabled = true
        )
    ),
    skills = AgentSkillsSnapshot(
        agentId = membersAgentDetailVisualMember.id,
        groups = listOf(
            AgentSkillGroup(
                title = "Product review",
                count = 2,
                paths = listOf(
                    AgentSkillPathGroup(
                        path = "notes/product",
                        count = 2,
                        skills = listOf(
                            AgentSkillSummary("feature-map", "React/KMP parity maps"),
                            AgentSkillSummary("visual-testing", "Cross-platform fixture ownership")
                        )
                    )
                )
            )
        )
    ),
    appLogins = AgentAppLoginSnapshot(
        agentId = membersAgentDetailVisualMember.id,
        items = listOf(
            AgentAppLoginItem(
                id = "grant-raft-actions-product-ux",
                type = AgentAppLoginType.Active,
                clientId = "raft-actions",
                clientKey = "raft-actions",
                clientName = "Raft Actions",
                clientDescription = "Executes approved action cards and task updates through Raft Agent Login.",
                clientHomepageUrl = "https://raft.local/actions",
                clientAgentManifestUrl = "https://raft.local/.well-known/agent.json",
                scopes = listOf("messages:read", "tasks:write"),
                remember = true,
                createdAt = "2026-06-19T12:20:00Z",
                resolvedAt = "2026-06-19T12:21:00Z",
                resolvedByUserId = "artin"
            ),
            AgentAppLoginItem(
                id = "grant-calendar-product-ux",
                type = AgentAppLoginType.Active,
                clientId = "calendar",
                clientKey = "calendar",
                clientName = "Calendar",
                clientDescription = "Reads launch review reminders and scheduled UX follow-ups.",
                clientHomepageUrl = "https://calendar.local",
                scopes = listOf("calendar:read"),
                remember = false,
                createdAt = "2026-06-18T09:00:00Z",
                resolvedAt = "2026-06-18T09:01:00Z",
                resolvedByUserId = "artin"
            ),
            AgentAppLoginItem(
                id = "request-drafts-product-ux",
                type = AgentAppLoginType.Pending,
                clientId = "drafts",
                clientName = "Drafts",
                clientDescription = "Pending request hidden to match React active-only Apps tab.",
                scopes = listOf("documents:read"),
                createdAt = "2026-06-20T09:00:00Z"
            )
        )
    )
)

private val membersAgentDetailVisualWorkspaceTreeSnapshot = AgentWorkspaceTreeSnapshot(
    agentId = membersAgentDetailVisualMember.id,
    rootPath = "~/.slock/agents/agent-product-ux",
    root = SlockWorkspaceNode(
        id = "~/.slock/agents/agent-product-ux",
        name = "agent-product-ux",
        path = "~/.slock/agents/agent-product-ux",
        kind = SlockWorkspaceFileKind.Folder,
        children = listOf(
            SlockWorkspaceNode(
                id = "MEMORY.md",
                name = "MEMORY.md",
                path = "MEMORY.md",
                kind = SlockWorkspaceFileKind.Markdown,
                meta = "86 B"
            ),
            SlockWorkspaceNode(
                id = "notes",
                name = "notes",
                path = "notes",
                kind = SlockWorkspaceFileKind.Folder,
                meta = "folder"
            )
        )
    )
)

private val membersAgentDetailVisualWorkspaceNotesChildren = AgentWorkspaceChildrenSnapshot(
    agentId = membersAgentDetailVisualMember.id,
    dirPath = "notes",
    children = listOf(
        SlockWorkspaceNode(
            id = "notes/visual-parity.md",
            name = "visual-parity.md",
            path = "notes/visual-parity.md",
            kind = SlockWorkspaceFileKind.Markdown,
            meta = "1.2 KB"
        )
    )
)

private enum class MembersAgentDetailVisualMode {
    Profile,
    LifecycleActions,
    AvatarManagement,
    ReportIssue,
    ReportIssueSuccess
}

private fun String.toMembersAgentDetailVisualMode(): MembersAgentDetailVisualMode =
    when (trim().lowercase()) {
        "lifecycle_actions" -> MembersAgentDetailVisualMode.LifecycleActions
        "avatar_management" -> MembersAgentDetailVisualMode.AvatarManagement
        "report_issue" -> MembersAgentDetailVisualMode.ReportIssue
        "report_issue_success" -> MembersAgentDetailVisualMode.ReportIssueSuccess
        else -> MembersAgentDetailVisualMode.Profile
    }

@Composable
internal fun MembersPageContent(
    paneLayout: SlockAdaptivePaneLayout = SlockAdaptivePaneLayout.Compact,
) {
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = Utils.bridgeModule(pager.pagerId)
    val errorStrings = localizedErrorPresentationStrings()
    val storeScope = remember(pager.pageData.params.toString(), pager.pagerId) {
        pager.pageData.params.slockShellStoreScope(SlockShellStoreScope.Legacy.shellInstanceId)
    }
    var payloadText by remember { mutableStateOf(pager.pageData.params.optString("payload")) }
    var instanceId by remember { mutableStateOf(pager.pageData.params.optString("instanceId")) }
    DisposableEffect(pager) {
        val observer =
            object : IPagerEventObserver {
                override fun onPagerEvent(pagerEvent: String, eventData: JSONObject) {
                    if (pagerEvent != MEMBERS_PAGE_DATA_EVENT) return
                    eventData.optString("payload").takeIf { it.isNotBlank() }?.let {
                        payloadText = it
                    }
                    eventData.optString("instanceId").takeIf { it.isNotBlank() }?.let {
                        instanceId = it
                    }
                }
            }
        pager.addPagerEventObserver(observer)
        onDispose { pager.removePagerEventObserver(observer) }
    }
    val payload = remember(payloadText, instanceId) {
        val decodedPayload = payloadText.toKuiklyMembersPayload()
        if (instanceId.isBlank() || decodedPayload.instanceId == instanceId) {
            decodedPayload
        } else {
            decodedPayload.copy(instanceId = instanceId)
        }
    }
    val bridgeCallScope = rememberCoroutineScope()
    val linkNavigator = rememberSlockLinkNavigator(pager, bridge)
    val copiedMessage = stringResource(MR.strings.common_copied)
    val settingsCapabilities =
        remember(bridge, bridgeCallScope, linkNavigator, copiedMessage) {
            BridgeSettingsPlatformCapabilities(
                bridge = bridge,
                copiedMessage = copiedMessage,
                bridgeCallContext = bridgeCallScope.kuiklyBridgeCallContext(),
                openUrlHandler = { url -> linkNavigator.open(url) }
            )
        }

    // Build the KMP dataSource + coordinator directly (no bridge event round-trip).
    // Use the shared provider network client so request scheduling and transport swaps
    // stay centralized instead of being recreated per page.
    val sharedStores = remember { build.raft.app.data.SlockSharedStoreRegistry.get(bridge) }
    val authStore = remember(sharedStores, storeScope) { sharedStores.authStore(storeScope) }
    val networkClient = remember(sharedStores, storeScope) { sharedStores.networkClient(storeScope) }
    val serverMembersStore = remember(sharedStores, storeScope) { sharedStores.serverMembersStore(storeScope) }
    val agentDetailStore = remember(sharedStores, storeScope) { sharedStores.agentDetailStore(storeScope) }
    val createAgentFormStore = remember(sharedStores, storeScope) { sharedStores.createAgentFormStore(storeScope) }
    // Task #409: members-page agent machine groups persist their collapse
    // state in the same slock:sidebarCollapsed:<userId>:agent-machine-group:*
    // key family the web sidebar uses.
    val membersAgentGroupCollapseStore = remember {
        build.raft.app.data.KeyValueMembersAgentMachineGroupCollapseStore(
            build.raft.app.data.slockSharedPreferenceStore()
        )
    }
    val agentsApiClient = remember(networkClient) {
        build.raft.app.data.SlockAgentsApiClient(
            transport = build.raft.app.data.SlockAgentsNetworkApi(networkClient),
            diagnosticsLogger = { message -> bridge.log("members_agents_api $message") }
        )
    }
    val computersApiClient = remember(networkClient) {
        build.raft.app.data.SlockComputersApiClient(
            build.raft.app.data.SlockComputersNetworkApi(networkClient)
        )
    }
    val scope = rememberCoroutineScope()
    val manageInvitesForbidden = stringResource(MR.strings.member_error_manage_invites_forbidden)
    val loadJoinLinkFailed = stringResource(MR.strings.member_error_load_join_link)
    val openProfileFailed = stringResource(MR.strings.member_error_open_profile)
    val computersStore = remember(computersApiClient, scope) {
        SharedComputersStore(
            computersApiClient = computersApiClient,
            scope = scope,
            logger = { message -> bridge.log("members_$message") },
            ownerProvider = {
                sharedStores.computersOwner(storeScope)
            }
        )
    }
    val createAgentCoordinator = remember(agentsApiClient, computersApiClient) {
        build.raft.app.data.SharedCreateAgentCoordinator(
            agentsApiClient = agentsApiClient,
            computersApiClient = computersApiClient,
            logger = { message -> bridge.log("members_create_agent $message") }
        )
    }
    val settingsRepository = remember(networkClient, authStore) {
        build.raft.app.data.RealSettingsRepository(
            sessionStore = authStore,
            networkClient = networkClient,
            logger = { message -> bridge.log("members_settings_repository $message") }
        )
    }
    val membersDataSource = remember(networkClient, authStore, settingsRepository, serverMembersStore) {
        build.raft.app.data.SharedMembersDataSource(
            memberRepository = build.raft.app.data.RealMemberRepository(
                sessionStore = authStore,
                networkClient = networkClient,
                logger = { message -> bridge.log("members_member_repository $message") }
            ),
            settingsRepository = settingsRepository,
            serverMembersStore = serverMembersStore
        )
    }
    var maxAgents by remember { mutableStateOf(-1) }
    var optimisticMembers by remember { mutableStateOf<List<MemberProfile>>(emptyList()) }
    // Derive the active serverId from the auth session. Replaces the prior
    // `readCurrentServerId()` stub (fixed QA-3): machines load + plan limit
    // checks now use the real selected server.
    var activeServerId by remember { mutableStateOf<String?>(null) }
    var membersCurrentUserId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(authStore) {
        val session = authStore.readSession()
        activeServerId = session?.currentServerId
        membersCurrentUserId = session?.user?.id
    }
    val serverId = activeServerId ?: ""
    val agentPanelTabOrderStore = sharedStores.agentPanelTabOrderStore
    val agentPanelTabOrderState by agentPanelTabOrderStore.state.collectAsState()
    suspend fun loadAgentPanelTabOrderForMembersPage(): List<String>? =
        when (val result = settingsRepository.getServerSidebarOrder()) {
            is DataResult.Content -> result.data.agentPanelTabOrder
            is DataResult.Empty -> null
            is DataResult.Error -> {
                bridge.log("members_agent_tab_order_load_failed code=${result.error.code}")
                null
            }
            is DataResult.NoPermission -> {
                bridge.log("members_agent_tab_order_load_blocked code=${result.blocker.code}")
                null
            }
        }
    LaunchedEffect(agentPanelTabOrderStore, serverId, payload.agentPanelTabOrder) {
        agentPanelTabOrderStore.bindServer(
            serverId = serverId,
            initialOrder = payload.agentPanelTabOrder,
            source = "members_page_bind_payload",
            logger = { message -> bridge.log(message) }
        )
    }
    LaunchedEffect(
        settingsRepository,
        agentPanelTabOrderStore,
        serverId,
        agentPanelTabOrderState.serverId,
        agentPanelTabOrderState.loaded
    ) {
        if (serverId.trim().isBlank()) return@LaunchedEffect
        if (agentPanelTabOrderState.serverId == serverId && agentPanelTabOrderState.loaded) return@LaunchedEffect
        loadAgentPanelTabOrderForMembersPage()?.let { order ->
            agentPanelTabOrderStore.applyLoaded(
                serverId = serverId,
                order = order,
                source = "members_page_sidebar_order_get",
                logger = { message -> bridge.log(message) }
            )
        }
    }
    val serverMembersEntry by remember(serverMembersStore, serverId) {
        val cleanServerId = serverId.trim()
        if (cleanServerId.isBlank()) {
            flowOf(SharedServerMembersEntry(serverId = ""))
        } else {
            serverMembersStore.serverMembersEntryFlow(ServerId(cleanServerId))
        }
    }.collectAsState(SharedServerMembersEntry(serverId = serverId))
    val localMembers =
        remember(serverMembersEntry.members, optimisticMembers) {
            val merged = LinkedHashMap<String, MemberProfile>()
            serverMembersEntry.members.forEach { member ->
                member.id.value.trim().takeIf { it.isNotBlank() }?.let { merged[it] = member }
            }
            optimisticMembers.forEach { member ->
                member.id.value.trim().takeIf { it.isNotBlank() && it !in merged }?.let { merged[it] = member }
            }
            merged.values.toList()
        }
    val currentAgentCount = localMembers.count { it.kind == MemberKind.Agent }
    // Task #148: which agent rows reach the list without a computer id, and from where (store vs
    // the row added right after creating an agent), so a device run can locate where the id is lost.
    val agentsWithoutComputer =
        remember(localMembers) {
            val storeIds = serverMembersEntry.members.mapTo(HashSet()) { it.id.value.trim() }
            localMembers
                .filter { it.kind == MemberKind.Agent && it.membersComputerId == null }
                .map { agent ->
                    val source = if (agent.id.value.trim() in storeIds) "store" else "optimistic"
                    "${agent.id.value.slockAvatarDiagnosticsHash()}:$source:badges=${agent.permissionBadges.size}"
                }
        }
    LaunchedEffect(agentsWithoutComputer, serverMembersEntry.status) {
        if (agentsWithoutComputer.isNotEmpty()) {
            bridge.log(
                "members_agents_without_computer status=${serverMembersEntry.status} " +
                    "count=${agentsWithoutComputer.size} rows=${agentsWithoutComputer.joinToString(",")}"
            )
        }
    }
    LaunchedEffect(serverId) {
        optimisticMembers = emptyList()
    }
    val agentStatusResolver =
        remember(serverMembersStore, serverId) {
            slockScopedAgentStatusResolver(serverMembersStore, serverId)
        }
    val memberAvatarResolver =
        remember(serverMembersStore, serverId) {
            slockMemberAvatarResolver(serverMembersStore, serverId)
        }
    val membersRefreshing = serverMembersEntry.status == build.raft.app.data.SharedServerMembersLoadStatus.InFlight
    val refreshMembers: () -> Unit = remember(serverMembersStore, serverId) {
        {
            val cleanServerId = serverId.trim()
            if (cleanServerId.isNotBlank()) {
                scope.launch {
                    serverMembersStore.refreshMembers(
                        ServerId(cleanServerId),
                        DataRequestPriority.Foreground,
                        force = true
                    )
                }
            }
        }
    }
    LaunchedEffect(serverMembersStore, serverId) {
        val cleanServerId = serverId.trim().takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        serverMembersStore.refreshMembers(ServerId(cleanServerId), DataRequestPriority.Background)
    }
    val computersSlice by computersStore
        .computerPickerSliceFlow(serverId)
        .collectAsState(SharedComputersPickerSlice())
    LaunchedEffect(computersStore, serverId) {
        val cleanServerId = serverId.trim().takeIf { it.isNotBlank() }
        computersStore.reconcileOwner()
        if (cleanServerId != null) {
            computersStore.ensureComputerPickerForServer(
                serverId = cleanServerId,
                priority = DataRequestPriority.Visible
            )
        }
    }
    val createAgentSubmit: (suspend (SharedCreateAgentDialogSubmit) -> DataResult<Unit>)? =
        remember(createAgentCoordinator, createAgentFormStore, serverId) {
            { submit ->
                val result =
                    createAgentCoordinator
                        .createAgent(submit.toCreateAgentRequest())
                        .recoverRejectedCreateAgentFormOrRetry(createAgentFormStore, serverId, submit) { retrySubmit ->
                            createAgentCoordinator.createAgent(retrySubmit.toCreateAgentRequest())
                        }
                if (result is DataResult.Content) {
                    val profile = createdAgentMemberProfile(result.data, submit, computersSlice.machines)
                    optimisticMembers = optimisticMembers + profile
                    serverId.trim().takeIf { it.isNotBlank() }?.let { cleanServerId ->
                        serverMembersStore.refreshMembers(
                            ServerId(cleanServerId),
                            DataRequestPriority.Background,
                            force = true
                        )
                    }
                }
                when (result) {
                    is DataResult.Content -> DataResult.Content(Unit)
                    is DataResult.Empty -> DataResult.Empty(result.reason)
                    is DataResult.Error -> result
                    is DataResult.NoPermission -> result
                }
            }
        }
    // MEMBERS_COMPUTERS_ERROR_RETRY_WIRING_START
    val loadMachines: () -> Unit = remember(computersStore, serverId) {
        {
            if (serverId.isNotBlank()) {
                computersStore.dispatch(
                    SharedComputersIntent.EnsureComputerPicker(
                        serverId = serverId,
                        priority = DataRequestPriority.Visible
                    )
                )
            }
        }
    }
    val retryMachines: () -> Unit = remember(computersStore, serverId) {
        {
            if (serverId.isNotBlank()) {
                computersStore.dispatch(
                    SharedComputersIntent.RefreshComputerPicker(
                        serverId = serverId,
                        priority = DataRequestPriority.Visible
                    )
                )
            }
        }
    }
    var createAgentRuntimeModels by remember(serverId) {
        mutableStateOf<Map<Pair<String, String>, SharedCreateAgentRuntimeModelCatalog>>(emptyMap())
    }
    var createAgentRuntimeModelLoadsInFlight by remember(serverId) {
        mutableStateOf<Set<Pair<String, String>>>(emptySet())
    }
    val loadRuntimeModels: (String, String, Boolean) -> Unit = { machineId, runtime, force ->
        val key = machineId.trim() to runtime.trim()
        if (serverId.isBlank() || key.first.isBlank() || key.second.isBlank()) {
            // no-op: the dialog keeps using the static runtime catalog fallback
        } else if (!force && (key in createAgentRuntimeModels || key in createAgentRuntimeModelLoadsInFlight)) {
            // no-op: cached or already loading
        } else {
            createAgentRuntimeModelLoadsInFlight = createAgentRuntimeModelLoadsInFlight + key
            scope.launch {
                when (val result = createAgentCoordinator.loadRuntimeModels(serverId, key.first, key.second)) {
                    is DataResult.Content -> {
                        createAgentRuntimeModels = createAgentRuntimeModels + (key to result.data)
                    }
                    is DataResult.Empty -> Unit
                    is DataResult.Error ->
                        bridge.log("create_agent_runtime_models_failed source=members_page code=${result.error.code}")
                    is DataResult.NoPermission ->
                        bridge.log("create_agent_runtime_models_blocked source=members_page code=${result.blocker.code}")
                }
                createAgentRuntimeModelLoadsInFlight = createAgentRuntimeModelLoadsInFlight - key
            }
        }
    }

    // Task #43 step 3: humans "+" invite flow state. Mirrors the React
    // `InviteHumanDialog.tsx` (join link + emails + send). Join link
    // auto-loads on first open; if none exist, the data source creates
    // one (no expiry, no max-uses). Sending POSTs one `/servers/{id}/invites`
    // per non-empty email.
    var inviteJoinLinkLoading by remember { mutableStateOf(false) }
    var inviteJoinLinkUrl by remember { mutableStateOf<String?>(null) }
    var inviteJoinLinkWebBaseUrl by remember { mutableStateOf<String?>(null) }
    var inviteJoinLinkCopied by remember { mutableStateOf(false) }
    fun loadInviteJoinLink() {
        if (serverId.isBlank() || inviteJoinLinkLoading) return
        inviteJoinLinkLoading = true
        scope.launch {
            val webBaseUrl =
                when (val developer = settingsCapabilities.loadDeveloper()) {
                    is DataResult.Content -> developer.data.webUrl
                    else -> null
                }
            val listed = membersDataSource.loadJoinLinks(serverId)
            val existing = (listed as? DataResult.Content)?.data.orEmpty().firstOrNull()
            val result = if (existing != null) {
                DataResult.Content(existing)
            } else {
                membersDataSource.ensureJoinLink(serverId)
            }
            inviteJoinLinkLoading = false
            when (result) {
                is DataResult.Content -> {
                    inviteJoinLinkWebBaseUrl = webBaseUrl
                    inviteJoinLinkUrl = normalizeInviteHumanJoinUrl(result.data.url, webBaseUrl)
                }
                is DataResult.NoPermission ->
                    bridge.toast(manageInvitesForbidden)
                is DataResult.Error ->
                    bridge.toast("$loadJoinLinkFailed: ${result.error.userFacingMessage(errorStrings)}")
                is DataResult.Empty -> Unit
            }
        }
    }
    val inviteHumanSubmitHandler: suspend (List<String>) -> DataResult<Unit> =
        remember(membersDataSource, serverId) {
            { emails ->
                if (serverId.isBlank()) {
                    DataResult.Error(ScreenError(code = "server_id_missing", retryable = false))
                } else {
                    val results = emails.map { email ->
                        membersDataSource.sendInvite(serverId, email)
                    }
                    when {
                        results.isEmpty() ->
                            DataResult.Empty(EmptyReason.NoSettingsAvailable)
                        results.all { it is DataResult.Content } ->
                            DataResult.Content(Unit)
                        else ->
                            DataResult.Error(
                                membersFirstScreenErrorOrFallback(results, "invite_send_failed")
                            )
                    }
                }
            }
        }
    val inviteHumanSubmit: (suspend (List<String>) -> DataResult<Unit>)? =
        inviteHumanSubmitHandler.takeIf { payload.canInviteHumans }

    val agentPanelTabOrder =
        if (agentPanelTabOrderState.serverId == serverId) {
            agentPanelTabOrderState.order
        } else {
            payload.agentPanelTabOrder
        }
    val membersPayload =
        payload.copy(
            members = localMembers,
            agentPanelTabOrder = agentPanelTabOrder,
            showHumanDirectory = serverMembersEntry.server?.hidesHumanDirectoryForViewer() != true,
        )

    MembersRouteContent(
        payload = membersPayload,
        refreshing = membersRefreshing,
        onBack = { bridge.closePage() },
        onRefresh = refreshMembers,
        paneLayout = paneLayout,
        onAddHuman = { /* humans "+" wired via addHumanSubmit below */ },
        onOpenMemberDetail = { member ->
            scope.launch {
                val identity = MemberDetailIdentity(member.id, member.kind, member.serverId)
                val accessMode = identity.memberDetailAccessMode(serverId)
                val launchPayload =
                    if (
                        member.kind == MemberKind.Agent &&
                        accessMode.allowsPrivateAgentSurfaces &&
                        (agentPanelTabOrderState.serverId != serverId || !agentPanelTabOrderState.loaded)
                    ) {
                        loadAgentPanelTabOrderForMembersPage()
                            ?.also { order ->
                                agentPanelTabOrderStore.applyLoaded(
                                    serverId = serverId,
                                    order = order,
                                    source = "members_detail_open_preload",
                                    logger = { message -> bridge.log(message) }
                                )
                            }
                            ?.let { order -> membersPayload.copy(agentPanelTabOrder = order) }
                            ?: membersPayload
                    } else {
                        membersPayload
                    }
                val pageData =
                    launchPayload.toMemberDetailPageData(
                        identity
                    )
                if (pageData == null) {
                    bridge.toast(openProfileFailed)
                    bridge.log("members_detail_open_skip reason=missing_member_id instanceId=${payload.instanceId}")
                } else {
                    val seedMember =
                        member.copy(
                            serverId = member.serverId ?: serverId.trim().takeIf { it.isNotBlank() }?.let(::ServerId)
                        )
                    agentDetailStore.dispatch(SharedAgentDetailIntent.SeedDetailFromMember(seedMember))
                    pager.openSlockPage(
                        pageName = MEMBER_DETAIL_PAGE,
                        pageData = pageData,
                        storeScopeOverride = storeScope,
                        onMissingScope = {
                            bridge.toast(openProfileFailed)
                            bridge.log("members_detail_open_skip reason=missing_shell_scope instanceId=${payload.instanceId}")
                        }
                    )
                }
            }
        },
        agentStatusResolver = agentStatusResolver,
        memberAvatarResolver = memberAvatarResolver,
        canCreateAgents = payload.canCreateAgents,
        createAgent = MembersCreateAgentInputs(
            createAgentSubmit = createAgentSubmit,
            createAgentMachines = computersSlice.machines,
            createAgentMachinesLoading = computersSlice.initialLoadInFlight,
            createAgentMachinesErrorMessage = computersSlice.initialLoadError?.userFacingMessage(errorStrings),
            createAgentCurrentAgentCount = currentAgentCount,
            createAgentMaxAgents = maxAgents,
            createAgentLoadMachines = loadMachines,
            createAgentRetryMachines = retryMachines,
            createAgentRuntimeModelsByMachineAndRuntime = createAgentRuntimeModels,
            createAgentRuntimeModelLoadsInFlight = createAgentRuntimeModelLoadsInFlight,
            createAgentLoadRuntimeModels = loadRuntimeModels,
            createAgentFormStore = createAgentFormStore,
            createAgentServerId = serverId,
            createAgentTakenAgentNames = localMembers.takenAgentNames()
        ),
        inviteHuman = MembersInviteHumanInputs(
            inviteHumanSubmit = inviteHumanSubmit,
            inviteHumanJoinLinkLoading = inviteJoinLinkLoading,
            inviteHumanJoinLinkUrl = inviteJoinLinkUrl,
            inviteHumanJoinLinkCopied = inviteJoinLinkCopied,
            inviteHumanJoinLinkCopy = { url ->
                bridge.copyToPasteboard(url)
                inviteJoinLinkCopied = true
            },
            inviteHumanLoadJoinLink = { loadInviteJoinLink() }
        ),
        // Task #409: persist per-machine collapse under the session user id.
        currentUserId = membersCurrentUserId,
        agentGroupCollapseStore = membersAgentGroupCollapseStore,
        // #89: pass agentsApiClient so Permissions tab can call
        // `agentScopes` / `updateAgentScopes` / `resetAgentScopes` directly.
        agentsApiClient = agentsApiClient
    )
    // MEMBERS_COMPUTERS_ERROR_RETRY_WIRING_END
}

internal sealed interface MemberDetailRouteState {
    data object Loading : MemberDetailRouteState
    data class Error(val error: ScreenError) : MemberDetailRouteState {
        constructor(code: String) : this(ScreenError(code = code, retryable = false))
    }
    data class Agent(
        val server: ServerSummary,
        val currentUserId: String?,
        val accessMode: MemberDetailAccessMode = MemberDetailAccessMode.LocalFull
    ) : MemberDetailRouteState
    data class Human(
        val server: ServerSummary,
        val currentUserId: String?,
        val member: MemberProfile,
        val accessMode: MemberDetailAccessMode = MemberDetailAccessMode.LocalFull
    ) : MemberDetailRouteState
}

internal fun memberDetailStatusResolverServerId(
    routeState: MemberDetailRouteState
): String? =
    when (routeState) {
        is MemberDetailRouteState.Agent ->
            routeState.server.id.value.takeIf { routeState.accessMode.allowsPrivateAgentSurfaces }
        is MemberDetailRouteState.Human -> routeState.server.id.value
        MemberDetailRouteState.Loading,
        is MemberDetailRouteState.Error -> null
    }
        ?.trim()
        ?.takeIf { it.isNotBlank() }

internal data class MemberDetailLoadPlan(
    val priority: DataRequestPriority,
    val showInitialLoading: Boolean,
    val keepCachedContentOnFailure: Boolean
)

internal fun memberDetailLoadPlan(hasCachedDetail: Boolean): MemberDetailLoadPlan =
    if (hasCachedDetail) {
        MemberDetailLoadPlan(
            priority = DataRequestPriority.Background,
            showInitialLoading = false,
            keepCachedContentOnFailure = true
        )
    } else {
        MemberDetailLoadPlan(
            priority = DataRequestPriority.Visible,
            showInitialLoading = true,
            keepCachedContentOnFailure = false
        )
    }

internal enum class MemberDetailFailureKind {
    Empty,
    Error,
    NoPermission
}

internal enum class MemberDetailFailureTransition {
    KeepCached,
    SetDetailEmpty,
    SetDetailError,
    SetDetailNoPermission
}

internal data class MemberDetailFailureResolution(
    val transition: MemberDetailFailureTransition,
    val routeErrorCode: String? = null
)

/**
 * Keep an already rendered agent detail authoritative while its best-effort refresh fails.
 * A cold route must still surface the terminal result instead of presenting stale content.
 */
internal fun memberDetailFailureResolution(
    loadPlan: MemberDetailLoadPlan,
    failure: MemberDetailFailureKind,
    code: String = ""
): MemberDetailFailureResolution {
    if (loadPlan.keepCachedContentOnFailure) {
        return MemberDetailFailureResolution(MemberDetailFailureTransition.KeepCached)
    }
    return when (failure) {
        MemberDetailFailureKind.Empty ->
            MemberDetailFailureResolution(
                transition = MemberDetailFailureTransition.SetDetailEmpty,
                routeErrorCode = "member_not_found"
            )
        MemberDetailFailureKind.Error ->
            MemberDetailFailureResolution(
                transition = MemberDetailFailureTransition.SetDetailError,
                routeErrorCode = code
            )
        MemberDetailFailureKind.NoPermission ->
            MemberDetailFailureResolution(
                transition = MemberDetailFailureTransition.SetDetailNoPermission,
                routeErrorCode = code
            )
    }
}

private data class MemberDetailVisualSeed(
    val member: MemberProfile,
    val agentDetailState: MembersAgentDetailLoadState,
    val agentScopeSet: AgentScopeSet? = null
)

internal fun memberDetailInitialRouteState(
    identity: MemberDetailIdentity?,
    activeServerId: String?,
    agentDetailState: SharedAgentDetailState,
    serverMembersState: SharedServerMembersState
): MemberDetailRouteState? {
    identity ?: return null
    val sourceServerId = identity.sourceServerId?.value?.trim()?.takeIf { it.isNotBlank() }
    val activeServer = activeServerId?.trim()?.takeIf { it.isNotBlank() }
    val expectedServerId = sourceServerId ?: activeServer
    val entries =
        when {
            sourceServerId != null -> listOfNotNull(serverMembersState.entriesByServerId[sourceServerId])
            activeServer != null -> listOfNotNull(serverMembersState.entriesByServerId[activeServer])
            else -> serverMembersState.entriesByServerId.values.toList()
        }
    val matches =
        entries.mapNotNull { entry ->
            val server = entry.server ?: return@mapNotNull null
            val serverId = server.id.value.trim()
            if (entry.serverId.trim() != serverId || (expectedServerId != null && serverId != expectedServerId)) {
                return@mapNotNull null
            }
            val member =
                entry.members.firstOrNull {
                    it.id == identity.memberId && it.kind == identity.kind
                } ?: return@mapNotNull null
            val memberServerId = member.serverId?.value?.trim()?.takeIf { it.isNotBlank() }
            if (memberServerId != null && memberServerId != serverId) return@mapNotNull null
            server to member
        }
    if (matches.size != 1) return null
    val (server, member) = matches.single()
    return when (identity.kind) {
        // Roster projections are not authoritative agent-profile cache entries. In particular,
        // their `roleDescription` historically carried a runtime-shaped string and could make the
        // first frame look Loaded before GET /agents/:id completed. Agent detail therefore always
        // starts cold; only the generation-fenced canonical response may enter Content.
        MemberKind.Agent -> null
        MemberKind.Human ->
            MemberDetailRouteState.Human(
                server = server,
                currentUserId = null,
                member = member.copy(serverId = identity.sourceServerId ?: member.serverId),
                accessMode = identity.memberDetailAccessMode(activeServer)
            )
        MemberKind.System -> null
        // External projections never open a member detail route.
        MemberKind.External -> null
    }
}

internal fun seedRemoteAgentProfile(
    agentDetailStore: SharedAgentDetailStore,
    member: MemberProfile
) {
    // Agent detail state is keyed by member id, while joint-channel identities are
    // server-scoped. Clear a same-id local detail before installing the remote public
    // profile so data from the active server cannot win the seed no-op guard.
    agentDetailStore.dispatch(SharedAgentDetailIntent.DeselectMember)
    agentDetailStore.dispatch(SharedAgentDetailIntent.SeedDetailFromMember(member))
}

internal fun membersAgentDetailOwnerForSession(
    environmentId: String?,
    accountId: String?,
    currentServerId: String?,
    joinedServerIds: Collection<String>,
    expectedServerId: ServerId,
    requireCurrentServer: Boolean
): SharedAgentDetailOwner? {
    val normalizedCurrentServerId =
        currentServerId?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val normalizedExpectedServerId =
        expectedServerId.value.trim().takeIf { it.isNotBlank() } ?: return null
    if (
        requireCurrentServer &&
        (
            normalizedCurrentServerId != normalizedExpectedServerId ||
                joinedServerIds.none { it.trim() == normalizedExpectedServerId }
        )
    ) {
        return null
    }
    return sharedAgentDetailOwner(
        environmentId = environmentId,
        accountId = accountId,
        serverId = ServerId(normalizedExpectedServerId)
    )
}

private class MemberDetailRouteProvider(
    val bridge: BridgeModule,
    val reportIssueFailedMessage: String,
    val authStore: SlockAuthSessionStore,
    val apiEnvironmentStore: SharedApiEnvironmentStore,
    val memberRepository: MemberRepository,
    val channelRepository: ChannelRepository,
    val settingsRepository: build.raft.app.data.SettingsServerManagementRepository,
    val agentIssueReportRepository: AgentIssueReportRepository,
    val agentDetailStore: SharedAgentDetailStore,
    val selectedServerStore: SelectedServerStore,
    val agentPanelTabOrderStore: SharedAgentPanelTabOrderStore,
    val serverMembersStore: build.raft.app.data.SharedServerMembersStore,
    val computersStore: SharedComputersStore,
    val createAgentCoordinator: build.raft.app.data.SharedCreateAgentCoordinator,
    val createAgentFormStore: SharedCreateAgentFormStore,
    val avatarCacheRuntime: SlockAvatarCacheRuntime,
    val avatarNativeCache: SlockAvatarNativeCache,
    val conversationIdentityProvider: build.raft.app.data.SharedConversationIdentityProvider?,
    val sharedHomeStore: SharedHomeStore?,
    val storeScope: SlockShellStoreScope,
    val agentsApiClient: build.raft.app.data.SlockAgentsApiClient? = null,
    val runtimeAccountUsageStore: SharedRuntimeAccountUsageStore? = null,
    val computersOwner: suspend () -> SharedComputersOwner? = { null }
) {
    /** The identity the shared network client currently authenticates as (task #137 usage reads). */
    suspend fun currentRuntimeAccountUsageOwner(): SharedComputersOwner? = computersOwner()

    suspend fun currentAgentDetailOwner(
        expectedServerId: ServerId,
        requireCurrentServer: Boolean = false
    ): SharedAgentDetailOwner? {
        val session = authStore.readSession() ?: return null
        return membersAgentDetailOwnerForSession(
            environmentId = apiEnvironmentStore.current().apiBaseUrl,
            accountId = session.user.id,
            currentServerId = session.currentServerId,
            joinedServerIds = session.servers.map { it.id },
            expectedServerId = expectedServerId,
            requireCurrentServer = requireCurrentServer
        )
    }

    suspend fun applyRuntimeConfigProjection(
        authority: MembersAgentProfileEditAuthority,
        config: RuntimeConfig
    ): Boolean {
        val expectedOwner = authority.ownerOrNull() ?: return false
        if (currentAgentDetailOwner(authority.serverId, requireCurrentServer = true) != expectedOwner) return false
        agentDetailStore.dispatch(
            SharedAgentDetailIntent.ApplyRuntimeConfigProjection(
                owner = expectedOwner,
                agentId = authority.memberId,
                config = config,
                profileGeneration = authority.profileGeneration
            )
        )
        val projection = agentDetailStore.state.value.runtimeConfigProjection
        return projection?.owner == expectedOwner &&
            projection.agentId == authority.memberId &&
            projection.profileGeneration == authority.profileGeneration &&
            projection.config == config
    }

    suspend fun updateHumanRole(
        expectedServerId: String,
        memberId: String,
        role: MembersHumanServerRole
    ): DataResult<SettingsServerActionResult> {
        if (!membersHumanRoleUpdateServerMatches(expectedServerId, selectedServerStore.selectedServerId.value)) {
            return DataResult.Error(ScreenError("member_role_server_changed", retryable = false))
        }
        return settingsRepository.updateServerMemberRole(
            SettingsServerMemberRoleUpdateRequest(memberId = memberId, role = role.wireValue)
        )
    }

    suspend fun cachedContext(
        identity: MemberDetailIdentity
    ): MemberDetailRouteState.Agent? {
        if (identity.kind != MemberKind.Agent) return null
        val session = authStore.readSession() ?: return null
        val serverId = session.currentServerId?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (!identity.memberDetailAccessMode(serverId).allowsPrivateAgentSurfaces) return null
        val selectedId = identity.memberId
        val state = agentDetailStore.state.value
        val detail = state.detail
        if (
            state.agentId != selectedId ||
            state.detailServerId?.value?.trim() != serverId ||
            detail == null ||
            detail.agentId != selectedId ||
            detail.summary?.id?.trim()?.takeIf { it.isNotBlank() }?.let { it != selectedId.value } == true
        ) {
            return null
        }
        val server = session.servers.firstOrNull { it.id == serverId }?.toServerSummary() ?: return null
        return MemberDetailRouteState.Agent(
            server = server,
            currentUserId = session.user.id
        )
    }

    suspend fun loadMember(
        identity: MemberDetailIdentity,
        agentOwner: SharedAgentDetailOwner? = null,
        agentProfileGeneration: Long = 0L,
        isCurrentRequest: () -> Boolean = { true }
    ): MemberDetailRouteState {
        val session = authStore.readSession()
        val serverId = session?.currentServerId
        val server = session?.servers?.firstOrNull { it.id == serverId }?.toServerSummary()
        if (session == null || serverId.isNullOrBlank() || server == null) {
            return MemberDetailRouteState.Error("server_required")
        }
        return identity.dispatchMemberDetailLookup(
            currentServerId = serverId,
            loadHuman = { selectedId, sourceServerId, accessMode ->
                loadHumanMember(
                    selectedId = selectedId,
                    profileServerId = sourceServerId ?: ServerId(serverId),
                    server = server,
                    currentUserId = session.user.id,
                    accessMode = accessMode
                )
            },
            loadAgent = { selectedId, accessMode ->
                when (accessMode) {
                    MemberDetailAccessMode.LocalFull ->
                        loadAgentMember(
                            selectedId,
                            server,
                            session.user.id,
                            agentOwner,
                            agentProfileGeneration,
                            isCurrentRequest
                        )
                    MemberDetailAccessMode.RemoteProfileOnly -> {
                        val sourceServerId = checkNotNull(identity.sourceServerId)
                        loadAgentProfileOnly(
                            selectedId = selectedId,
                            profileServerId = sourceServerId,
                            profileServer =
                                session.servers
                                    .firstOrNull { it.id == sourceServerId.value }
                                    ?.toServerSummary()
                                    ?: ServerSummary(
                                        id = sourceServerId,
                                        name = sourceServerId.value
                            ),
                            currentUserId = session.user.id,
                            owner = agentOwner,
                            profileGeneration = agentProfileGeneration,
                            isCurrentRequest = isCurrentRequest
                        )
                    }
                }
            },
            unsupported = { _, _ -> MemberDetailRouteState.Error("member_kind_unsupported") }
        )
    }

    private suspend fun loadAgentProfileOnly(
        selectedId: MemberId,
        profileServerId: ServerId,
        profileServer: ServerSummary,
        currentUserId: String?,
        owner: SharedAgentDetailOwner?,
        profileGeneration: Long,
        isCurrentRequest: () -> Boolean
    ): MemberDetailRouteState {
        val client = agentsApiClient ?: return MemberDetailRouteState.Error("agent_profile_unavailable")
        return when (val detailResult = client.publicAgentDetail(selectedId)) {
            is DataResult.Content -> {
                if (
                    !isCurrentRequest() ||
                    owner == null ||
                    currentAgentDetailOwner(profileServerId) != owner
                ) {
                    return MemberDetailRouteState.Error("member_request_stale")
                }
                val detail = detailResult.data
                if (detail.agentId != selectedId || detail.summary?.id != selectedId.value) {
                    return MemberDetailRouteState.Error("member_identity_mismatch")
                }
                agentDetailStore.dispatch(SharedAgentDetailIntent.DeselectMember)
                agentDetailStore.dispatch(
                    SharedAgentDetailIntent.SelectMember(selectedId, owner, profileGeneration)
                )
                agentDetailStore.dispatch(
                    SharedAgentDetailIntent.SetDetail(
                        agentId = selectedId,
                        detail = detail,
                        serverId = profileServerId,
                        owner = owner,
                        profileGeneration = profileGeneration
                    )
                )
                // Agent detail hydration carries the canonical avatarUrl; seed it back so
                // the scoped roster cache drops a stale pixel fallback in-session.
                serverMembersStore.seedProfiles(
                    profileServerId,
                    listOf(detail.toStaticMembersProfile(profileServer.copy(id = profileServerId)))
                )
                MemberDetailRouteState.Agent(
                    server = profileServer.copy(id = profileServerId),
                    currentUserId = currentUserId,
                    accessMode = MemberDetailAccessMode.RemoteProfileOnly
                )
            }
            is DataResult.Empty -> MemberDetailRouteState.Error("member_not_found")
            is DataResult.Error -> MemberDetailRouteState.Error(detailResult.error)
            is DataResult.NoPermission -> MemberDetailRouteState.Error(detailResult.blocker.code)
        }
    }

    private suspend fun loadAgentMember(
        selectedId: MemberId,
        server: ServerSummary,
        currentUserId: String?,
        owner: SharedAgentDetailOwner?,
        profileGeneration: Long,
        isCurrentRequest: () -> Boolean
    ): MemberDetailRouteState {
        // Agent profile content is deliberately cold-loaded. A roster seed has no revision or
        // canonical runtime authority and may never be kept as if it were a successful response.
        val loadPlan = memberDetailLoadPlan(hasCachedDetail = false)
        val presenceRequest =
            serverMembersStore.beginAgentDetailPresenceProjection(server.id, selectedId)
        val detailResult = memberRepository.getAgentDetail(loadPlan.priority, selectedId)
        if (
            !isCurrentRequest() ||
            owner == null ||
            currentAgentDetailOwner(server.id, requireCurrentServer = true) != owner
        ) {
            return MemberDetailRouteState.Error("member_request_stale")
        }
        return when (detailResult) {
            is DataResult.Content -> {
                agentDetailStore.dispatch(
                    SharedAgentDetailIntent.SelectMember(selectedId, owner, profileGeneration)
                )
                agentDetailStore.dispatch(
                    SharedAgentDetailIntent.SetDetail(
                        agentId = selectedId,
                        detail = detailResult.data,
                        serverId = server.id,
                        owner = owner,
                        profileGeneration = profileGeneration
                    )
                )
                serverMembersStore.applyAgentDetailSnapshot(presenceRequest, detailResult.data)
                // Same reason as the remote-profile branch: publish the freshly loaded
                // avatar/profile fields so in-session roster observers update.
                serverMembersStore.seedProfiles(
                    server.id,
                    listOf(detailResult.data.toStaticMembersProfile(server))
                )
                MemberDetailRouteState.Agent(
                    server = server,
                    currentUserId = currentUserId
                )
            }
            is DataResult.Empty -> {
                resolveAgentDetailFailure(
                    selectedId = selectedId,
                    server = server,
                    currentUserId = currentUserId,
                    resolution = memberDetailFailureResolution(loadPlan, MemberDetailFailureKind.Empty),
                    resultLabel = "empty"
                )
            }
            is DataResult.Error -> {
                resolveAgentDetailFailure(
                    selectedId = selectedId,
                    server = server,
                    currentUserId = currentUserId,
                    resolution = memberDetailFailureResolution(
                        loadPlan,
                        MemberDetailFailureKind.Error,
                        detailResult.error.code
                    ),
                    resultLabel = "error",
                    diagnosticCode = detailResult.error.code,
                    screenError = detailResult.error
                )
            }
            is DataResult.NoPermission -> {
                resolveAgentDetailFailure(
                    selectedId = selectedId,
                    server = server,
                    currentUserId = currentUserId,
                    resolution = memberDetailFailureResolution(
                        loadPlan,
                        MemberDetailFailureKind.NoPermission,
                        detailResult.blocker.code
                    ),
                    resultLabel = "no_permission",
                    diagnosticCode = detailResult.blocker.code,
                    permissionBlocker = detailResult.blocker
                )
            }
        }
    }

    private fun resolveAgentDetailFailure(
        selectedId: MemberId,
        server: ServerSummary,
        currentUserId: String?,
        resolution: MemberDetailFailureResolution,
        resultLabel: String,
        diagnosticCode: String? = null,
        screenError: ScreenError? = null,
        permissionBlocker: PermissionBlocker? = null
    ): MemberDetailRouteState =
        when (resolution.transition) {
            MemberDetailFailureTransition.KeepCached -> {
                bridge.log(
                    "member_detail_background_refresh_kept_cached agentId=${selectedId.value} " +
                        "result=$resultLabel" +
                        diagnosticCode?.let { " code=$it" }.orEmpty()
                )
                MemberDetailRouteState.Agent(server = server, currentUserId = currentUserId)
            }
            MemberDetailFailureTransition.SetDetailEmpty -> {
                agentDetailStore.dispatch(SharedAgentDetailIntent.SetDetailEmpty(selectedId))
                MemberDetailRouteState.Error(requireNotNull(resolution.routeErrorCode))
            }
            MemberDetailFailureTransition.SetDetailError -> {
                val resolvedError = screenError
                    ?: ScreenError(requireNotNull(resolution.routeErrorCode), retryable = false)
                agentDetailStore.dispatch(
                    SharedAgentDetailIntent.SetDetailError(selectedId, resolvedError)
                )
                MemberDetailRouteState.Error(resolvedError)
            }
            MemberDetailFailureTransition.SetDetailNoPermission -> {
                val resolvedBlocker = permissionBlocker
                    ?: PermissionBlocker(requireNotNull(resolution.routeErrorCode))
                agentDetailStore.dispatch(
                    SharedAgentDetailIntent.SetDetailNoPermission(selectedId, resolvedBlocker)
                )
                MemberDetailRouteState.Error(
                    ScreenError(resolvedBlocker.code, retryable = false)
                )
            }
        }

    suspend fun loadAgentActivity(
        agentId: MemberId
    ): DataResult<List<AgentActivityEntry>> {
        return when (val activityResult = memberRepository.getAgentActivity(DataRequestPriority.Visible, agentId)) {
            is DataResult.Content -> DataResult.Content(activityResult.data.activity)
            is DataResult.Empty -> activityResult
            is DataResult.Error -> {
                bridge.log("member_detail_agent_activity_load_failed agentId=${agentId.value} code=${activityResult.error.code}")
                activityResult
            }
            is DataResult.NoPermission -> {
                bridge.log("member_detail_agent_activity_load_blocked agentId=${agentId.value} code=${activityResult.blocker.code}")
                activityResult
            }
        }
    }

    private suspend fun loadHumanMember(
        selectedId: MemberId,
        profileServerId: ServerId,
        server: ServerSummary,
        currentUserId: String?,
        accessMode: MemberDetailAccessMode
    ): MemberDetailRouteState {
        agentDetailStore.dispatch(SharedAgentDetailIntent.DeselectMember)
        return when (val memberResult = memberRepository.getMemberProfile(DataRequestPriority.Visible, selectedId, profileServerId)) {
            is DataResult.Content -> {
                val member = memberResult.data.copy(serverId = profileServerId)
                serverMembersStore.seedProfiles(profileServerId, listOf(member))
                MemberDetailRouteState.Human(server, currentUserId, member, accessMode)
            }
            is DataResult.Empty ->
                MemberDetailRouteState.Error("member_not_found")
            is DataResult.Error ->
                MemberDetailRouteState.Error(memberResult.error)
            is DataResult.NoPermission ->
                MemberDetailRouteState.Error(memberResult.blocker.code)
        }
    }

    fun updateCachedMemberProfile(member: MemberProfile, expectedServerId: ServerId) {
        val state = agentDetailStore.state.value
        val detail = state.detail ?: return
        if (
            state.agentId != member.id ||
            state.detailServerId != expectedServerId ||
            detail.agentId != member.id
        ) {
            return
        }
        agentDetailStore.dispatch(
            SharedAgentDetailIntent.SetDetail(
                member.id,
                detail.withMemberProfileProjection(member),
                expectedServerId,
                owner = state.detailOwner,
                profileGeneration = state.detailProfileGeneration
            )
        )
    }

    suspend fun runLifecycleAction(
        serverId: ServerId,
        agentId: MemberId,
        action: MembersAgentLifecycleAction,
        workspaceChoice: AgentArchiveWorkspaceChoice = AgentArchiveWorkspaceChoice.Keep
    ): DataResult<AgentLifecycleActionResult> {
        val result =
            when (action) {
                MembersAgentLifecycleAction.Start ->
                    memberRepository.startAgent(DataRequestPriority.Foreground, agentId)
                MembersAgentLifecycleAction.Stop ->
                    memberRepository.stopAgent(DataRequestPriority.Foreground, agentId)
                MembersAgentLifecycleAction.Restart,
                MembersAgentLifecycleAction.ResetSession,
                MembersAgentLifecycleAction.ResetFull ->
                    memberRepository.resetAgent(
                        DataRequestPriority.Foreground,
                        build.raft.app.data.AgentResetRequest(
                            agentId,
                            action.resetMode ?: AgentResetMode.Restart
                        )
                    )
                MembersAgentLifecycleAction.Delete ->
                    serverMembersStore.deleteAgent(
                        serverId = serverId,
                        agentId = agentId,
                        workspaceChoice = workspaceChoice,
                        priority = DataRequestPriority.Foreground
                    )
                MembersAgentLifecycleAction.UnassignMachine ->
                    memberRepository.assignAgentMachine(
                        DataRequestPriority.Foreground,
                        AgentMachineAssignmentRequest(agentId, machineId = null)
                    )
            }
        return result
    }

    suspend fun updateAgentProfile(
        serverId: ServerId,
        request: AgentProfileUpdateRequest
    ): DataResult<MemberProfile> =
        serverMembersStore.updateAgentProfile(
            serverId = serverId,
            request = request,
            priority = DataRequestPriority.Foreground
        )

    suspend fun openDirectMessage(member: MemberProfile): DataResult<OpenDirectMessageResult> {
        val authority =
            authStore.readSession()?.let { session ->
                val serverId = session.currentServerId ?: return@let null
                OpenedDirectMessageAuthority(
                    serverId = ServerId(serverId),
                    accountId = MemberId(session.user.id)
                )
            }
        val result =
            when (member.kind) {
                MemberKind.Agent -> channelRepository.openAgentDirectMessage(member.id)
                MemberKind.Human -> channelRepository.openUserDirectMessage(member.id)
                MemberKind.System -> DataResult.Empty(EmptyReason.NoMembers)
                MemberKind.External -> DataResult.Empty(EmptyReason.NoMembers)
            }
        if (result is DataResult.Content && authority != null) {
            sharedHomeStore?.supplementOpenedDirectMessage(
                authority = authority,
                conversation = result.data.conversation
            )
        }
        return result
    }

    suspend fun uploadAgentAvatar(
        serverId: ServerId,
        agentId: MemberId,
        file: PlatformFile
    ): DataResult<MemberProfile> =
        withContext(Dispatchers.Default) {
            serverMembersStore.uploadAgentAvatarWithAvatarCacheInvalidation(
                serverId = serverId,
                priority = DataRequestPriority.Foreground,
                request = AgentAvatarUploadRequest(agentId = agentId, file = file),
                avatarCacheRuntime = avatarCacheRuntime,
                avatarCacheNativeCache = avatarNativeCache
            )
        }

    suspend fun pickAgentAvatarFile(): DataResult<PlatformFile> {
        val response = bridge.threadAttachmentUpload(channelId = "agent-avatar-picker", imageOnly = true)
        val code = response?.optInt("code", 0) ?: 0
        return if (code == 204) {
            DataResult.Empty(EmptyReason.NoMembers)
        } else if (code !in 200..299) {
            val errorCode = response?.optString("errorCode").orEmpty().ifBlank { "agent_avatar_picker_failed" }
            DataResult.Error(ScreenError(code = errorCode, retryable = true))
        } else {
            val file = parseSharedThreadPickedPlatformFiles(response).firstOrNull()
            if (file != null) {
                DataResult.Content(file)
            } else {
                DataResult.Error(ScreenError(code = "agent_avatar_picker_empty", retryable = true))
            }
        }
    }

    fun copyMemberId(member: MemberProfile, successMessage: String) {
        bridge.copyToPasteboard(member.id.value)
        bridge.toast(successMessage)
    }

    fun copyText(text: String, successMessage: String) {
        bridge.copyToPasteboard(text)
        bridge.toast(successMessage)
    }

    fun copyDiagnostics(diagnostics: String, successMessage: String) =
        copyText(diagnostics, successMessage)

    suspend fun loadAgentPanelTabOrder(): DataResult<List<String>> =
        when (val result = settingsRepository.getServerSidebarOrder()) {
            is DataResult.Content -> DataResult.Content(result.data.agentPanelTabOrder)
            is DataResult.Empty -> DataResult.Empty(result.reason)
            is DataResult.Error -> DataResult.Error(result.error)
            is DataResult.NoPermission -> DataResult.NoPermission(result.blocker)
        }

    suspend fun updateAgentPanelTabOrder(tabs: List<MembersAgentDetailTab>): DataResult<List<String>> =
        when (
            val result = settingsRepository.updateServerSidebarOrder(
                build.raft.app.data.SettingsServerSidebarOrderUpdateRequest(
                    agentPanelTabOrder = tabs.toMembersAgentPanelTabOrderWireKeys()
                )
            )
        ) {
            is DataResult.Content -> DataResult.Content(result.data.agentPanelTabOrder)
            is DataResult.Empty -> DataResult.Empty(result.reason)
            is DataResult.Error -> DataResult.Error(result.error)
            is DataResult.NoPermission -> DataResult.NoPermission(result.blocker)
        }

    suspend fun reportIssue(
        server: ServerSummary,
        member: MemberProfile,
        detail: AgentDetailSnapshot?,
        request: SharedAgentIssueReportRequest
    ): DataResult<AgentIssueReportResult> {
        bridge.log("agent_diagnostics_report ${request.diagnostics.toMemberDetailDiagnosticsLine()}")
        val result = agentIssueReportRepository.submit(
            AgentIssueReportSubmission(
                server = server,
                member = member,
                detail = detail,
                description = request.description,
                diagnostics = request.diagnostics,
                includeRecentMessages = request.includeRecentMessages,
                includeLiveActivity = request.includeLiveActivity,
                includeActivityHistory = request.includeActivityHistory,
                includeRuntimeTranscript = request.includeRuntimeTranscript,
                liveActivity = request.liveActivity
            )
        )
        if (result !is DataResult.Content) {
            bridge.toast(reportIssueFailedMessage)
        }
        return result
    }
}

@Composable
private fun rememberMemberDetailRouteProvider(
    pager: Pager,
    bridge: BridgeModule,
    storeScopeOverride: SlockShellStoreScope? = null
): MemberDetailRouteProvider {
    val reportIssueFailedMessage = stringResource(MR.strings.member_error_report_issue)
    val sharedStores = remember { build.raft.app.data.SlockSharedStoreRegistry.get(bridge) }
    val storeScope = storeScopeOverride ?: remember(pager.pageData.params.toString(), pager.pagerId) {
        pager.pageData.params.slockShellStoreScope(SlockShellStoreScope.Legacy.shellInstanceId)
    }
    val authStore = remember(sharedStores, storeScope) { sharedStores.authStore(storeScope) }
    val networkClient = remember(sharedStores, storeScope) { sharedStores.networkClient(storeScope) }
    val memberRepository: MemberRepository =
        remember(networkClient, authStore) {
            RealMemberRepository(
                sessionStore = authStore,
                networkClient = networkClient,
                logger = { message -> bridge.log("member_detail_repository $message") }
            )
        }
    val channelRepository: ChannelRepository =
        remember(networkClient, authStore) {
            RealChannelRepository(
                sessionStore = authStore,
                networkClient = networkClient,
                warningLogger = { message -> bridge.log("member_detail_channel_repository $message") }
            )
        }
    val settingsRepository: build.raft.app.data.SettingsServerManagementRepository =
        remember(networkClient, authStore) {
            build.raft.app.data.RealSettingsRepository(
                sessionStore = authStore,
                networkClient = networkClient,
                logger = { message -> bridge.log("member_detail_settings_repository $message") }
            )
        }
    val agentDetailStore = remember(sharedStores, storeScope) { sharedStores.agentDetailStore(storeScope) }
    val selectedServerStore = remember(sharedStores, storeScope) { sharedStores.selectedServerStore(storeScope) }
    val agentPanelTabOrderStore = sharedStores.agentPanelTabOrderStore
    val serverMembersStore = remember(sharedStores, storeScope) { sharedStores.serverMembersStore(storeScope) }
    val scope = rememberCoroutineScope()
    val codecModule = remember(pager.pagerId) { pager.acquireModule<CodecModule>(CodecModule.MODULE_NAME) }
    val agentIssueReportRepository = remember(networkClient, authStore, selectedServerStore, codecModule) {
        AgentIssueReportRepository(
            sessionStore = authStore,
            selectedServerStore = selectedServerStore,
            networkClient = networkClient,
            sha256Hex = codecModule::sha256,
            logger = { message -> bridge.log("member_detail_agent_issue $message") }
        )
    }
    val computersApiClient = remember(networkClient) {
        SlockComputersApiClient(
            SlockComputersNetworkApi(networkClient)
        )
    }
    val computersStore = remember(computersApiClient, scope) {
        SharedComputersStore(
            computersApiClient = computersApiClient,
            scope = scope,
            logger = { message -> bridge.log("member_detail_$message") },
            ownerProvider = {
                sharedStores.computersOwner(storeScope)
            }
        )
    }
    val agentsApiClient = remember(networkClient) {
        build.raft.app.data.SlockAgentsApiClient(
            transport = build.raft.app.data.SlockAgentsNetworkApi(networkClient),
            diagnosticsLogger = { message -> bridge.log("member_detail_agents_api $message") }
        )
    }
    val createAgentCoordinator = remember(agentsApiClient, computersApiClient) {
        build.raft.app.data.SharedCreateAgentCoordinator(
            agentsApiClient = agentsApiClient,
            computersApiClient = computersApiClient,
            logger = { message -> bridge.log("member_detail_runtime_models $message") }
        )
    }
    val createAgentFormStore = remember(sharedStores, storeScope) {
        sharedStores.createAgentFormStore(storeScope)
    }
    val runtimeAccountUsageStore = remember(computersApiClient) {
        SharedRuntimeAccountUsageStore(
            client = computersApiClient,
            currentIdentity = {
                sharedStores.computersOwner(storeScope)
            }
        )
    }
    val avatarNativeCache =
        remember(bridge, scope) {
            BridgeSlockAvatarNativeCache(
                bridge = bridge,
                bridgeCallContext = scope.kuiklyBridgeCallContext()
            )
        }
    return remember(
        bridge,
        reportIssueFailedMessage,
        authStore,
        sharedStores.apiEnvironmentStore,
        memberRepository,
        channelRepository,
        settingsRepository,
        agentIssueReportRepository,
        agentDetailStore,
        selectedServerStore,
        agentPanelTabOrderStore,
        serverMembersStore,
        computersStore,
        createAgentCoordinator,
        createAgentFormStore,
        avatarNativeCache,
        sharedStores.conversationIdentityProvider(storeScope),
        sharedStores.sharedHomeStore(storeScope),
        storeScope,
        agentsApiClient,
        runtimeAccountUsageStore
    ) {
        MemberDetailRouteProvider(
            computersOwner = { sharedStores.computersOwner(storeScope) },
            bridge = bridge,
            reportIssueFailedMessage = reportIssueFailedMessage,
            authStore = authStore,
            apiEnvironmentStore = sharedStores.apiEnvironmentStore,
            memberRepository = memberRepository,
            channelRepository = channelRepository,
            settingsRepository = settingsRepository,
            agentIssueReportRepository = agentIssueReportRepository,
            agentDetailStore = agentDetailStore,
            selectedServerStore = selectedServerStore,
            agentPanelTabOrderStore = agentPanelTabOrderStore,
            serverMembersStore = serverMembersStore,
            computersStore = computersStore,
            createAgentCoordinator = createAgentCoordinator,
            createAgentFormStore = createAgentFormStore,
            avatarCacheRuntime = sharedStores.avatarCacheRuntime,
            avatarNativeCache = avatarNativeCache,
            conversationIdentityProvider = sharedStores.conversationIdentityProvider(storeScope),
            sharedHomeStore = sharedStores.sharedHomeStore(storeScope),
            storeScope = storeScope,
            agentsApiClient = agentsApiClient,
            runtimeAccountUsageStore = runtimeAccountUsageStore
        )
    }
}

@Composable
internal fun MemberDetailPageContent(
    pageVisibleTick: Int,
    embeddedPayload: KuiklyMemberDetailPayload? = null,
    storeScopeOverride: SlockShellStoreScope? = null,
    onClose: (() -> Unit)? = null,
) {
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = Utils.bridgeModule(pager.pagerId)
    val errorStrings = localizedErrorPresentationStrings()
    val linkNavigator = rememberSlockLinkNavigator(pager, bridge)
    val memberIdCopied = stringResource(MR.strings.member_notice_member_id_copied)
    val diagnosticsCopied = stringResource(MR.strings.member_notice_diagnostics_copied)
    val removeFromSettings = stringResource(MR.strings.member_notice_remove_from_settings)
    val tabOrderSaveError = stringResource(MR.strings.member_error_tab_order_save)
    val tabOrderSaveErrorTemplate = membersStringTemplate(MR.strings.member_error_tab_order_save_detail)
    val tabOrderPermissionError = stringResource(MR.strings.member_error_tab_order_permission)
    val reminderOpenError = stringResource(MR.strings.member_error_open_reminder)
    val directMessageOpenError = stringResource(MR.strings.member_error_open_direct_message)
    val directMessageOpenErrorTemplate = membersStringTemplate(MR.strings.member_error_open_direct_message_detail)
    val directMessagePermissionError = stringResource(MR.strings.member_error_open_direct_message_permission)
    val payload = embeddedPayload ?: remember(pager.pageData.params.toString()) {
        pager.pageData.params.toKuiklyMemberDetailPayload()
    }
    val visualSeed = remember(payload) { payload.toMemberDetailVisualSeedOrNull() }
    if (embeddedPayload == null && visualSeed != null) {
        MemberDetailVisualSeedContent(
            payload = payload,
            seed = visualSeed,
            pageVisibleTick = pageVisibleTick
        )
        return
    }
    val provider = rememberMemberDetailRouteProvider(pager, bridge, storeScopeOverride)
    val closeDetail = onClose ?: { provider.bridge.closePage() }
    val routeScope = rememberCoroutineScope()
    val memberIdentity = payload.identity
    val activeServerId by provider.selectedServerStore.currentServerId.collectAsState()
    var routeState by remember(memberIdentity) {
        mutableStateOf(
            memberDetailInitialRouteState(
                identity = memberIdentity,
                activeServerId = provider.selectedServerStore.currentServerId.value,
                agentDetailState = provider.agentDetailStore.state.value,
                serverMembersState = provider.serverMembersStore.state.value
            ) ?: MemberDetailRouteState.Loading
        )
    }
    var localAgentDetailRefreshKey by remember { mutableStateOf(0) }
    var agentProfileRequestGeneration by remember { mutableStateOf(0L) }
    var agentActivityLoadState by remember(memberIdentity) {
        mutableStateOf<MembersAgentActivityLoadState>(MembersAgentActivityLoadState.Idle)
    }
    var agentActivityRequestGeneration by remember { mutableStateOf(0L) }
    val agentDetailStoreState by provider.agentDetailStore.state.collectAsState()
    val agentPanelTabOrderState by provider.agentPanelTabOrderStore.state.collectAsState()
    val serverMembersState by provider.serverMembersStore.state.collectAsState()
    val selectedMemberId = memberIdentity?.memberId
    val selectedAgentRouteServerId = (routeState as? MemberDetailRouteState.Agent)?.server?.id
    val selectedAgentDetailStoreStateMatches =
        selectedMemberId != null &&
            selectedAgentRouteServerId != null &&
            agentDetailStoreState.agentId == selectedMemberId &&
            agentDetailStoreState.detailServerId == selectedAgentRouteServerId
    val selectedAgentDetail = agentDetailStoreState.detail?.takeIf {
        selectedAgentDetailStoreStateMatches && it.agentId == selectedMemberId
    }
    val agentDetailState =
        if (selectedAgentDetailStoreStateMatches) {
            agentDetailStoreState.toMembersAgentDetailLoadState()
        } else {
            MembersAgentDetailLoadState.Loading
        }
    val timeContext = payload.toSlockTimeContext()
    val initialAgentTab = remember(payload.initialAgentTab) { payload.initialAgentTab.toMembersAgentDetailTabOrNull() }
    val expectedKind = memberIdentity?.kind
    val statusLookupServerId =
        when (val current = routeState) {
            is MemberDetailRouteState.Agent ->
                current.server.id.value.takeIf { current.accessMode.allowsPrivateAgentSurfaces }
            else -> null
        }
    // Human details can render created-agent rows, but they must not opt into the
    // agent-only computers/runtime surface. Bind only the status/avatar read edge
    // to the route's canonical server; remote profile-only agents remain unbound.
    val statusResolverServerId = memberDetailStatusResolverServerId(routeState)
    val agentPanelTabOrderServerId =
        when (val current = routeState) {
            is MemberDetailRouteState.Agent ->
                current.server.id.value.takeIf { current.accessMode.allowsPrivateAgentSurfaces }
            else -> null
        }
    val computerPickerSlice by provider.computersStore.computerPickerSliceFlow(statusLookupServerId).collectAsState(
        SharedComputersPickerSlice()
    )
    var agentRuntimeModelCatalogs by remember(memberIdentity, statusLookupServerId) {
        mutableStateOf<Map<Pair<String, String>, SharedCreateAgentRuntimeModelCatalog>>(emptyMap())
    }
    var agentRuntimeModelLoadsInFlight by remember(memberIdentity, statusLookupServerId) {
        mutableStateOf<Set<Pair<String, String>>>(emptySet())
    }
    var agentRuntimeModelErrors by remember(memberIdentity, statusLookupServerId) {
        mutableStateOf<Map<Pair<String, String>, String>>(emptyMap())
    }
    var agentRuntimeOptionRequestGenerations by remember(memberIdentity, statusLookupServerId) {
        mutableStateOf<Map<Pair<String, String>, Long>>(emptyMap())
    }
    LaunchedEffect(provider.computersStore, statusLookupServerId) {
        val serverId = statusLookupServerId?.trim()?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        provider.computersStore.dispatch(SharedComputersIntent.EnsureComputerPicker(serverId))
    }
    // Task #259: the status row's truth source is the computers store, not `machineId != null`.
    // The slice for this server is what EnsureComputerPicker above is loading; the resolver
    // distinguishes "store has not answered" (render nothing) from "answered, machine absent"
    // ("no computer assigned") -- web collapses the two and flashes the latter on every open.
    val computersState by provider.computersStore.state.collectAsState()
    val machineStatusRow: (String?) -> MembersMachineStatusRow =
        remember(computersState, statusLookupServerId) {
            // selectComputerServerSlice normalises the key; indexing serverSlices directly
            // would miss silently and read as "store never answered".
            val slice = computersState.selectComputerServerSlice(statusLookupServerId)
            ;
            { machineId ->
                membersMachineStatusRow(
                    machineId = machineId,
                    machines = slice.machines,
                    loadStatus = slice.status,
                    hasSnapshot = slice.hasSnapshot
                )
            }
        }
    LaunchedEffect(provider.agentPanelTabOrderStore, agentPanelTabOrderServerId, payload.agentPanelTabOrder) {
        val serverId = agentPanelTabOrderServerId ?: return@LaunchedEffect
        provider.agentPanelTabOrderStore.bindServer(
            serverId = serverId,
            initialOrder = payload.agentPanelTabOrder,
            source = "member_detail_bind_payload",
            logger = { message -> bridge.log(message) }
        )
    }
    val agentPanelTabOrder =
        if (agentPanelTabOrderState.serverId == agentPanelTabOrderServerId) {
            agentPanelTabOrderState.order
        } else {
            payload.agentPanelTabOrder
        }
    val initialAgentTabForRender = remember(memberIdentity) {
        membersInitialAgentDetailTabForRender(
            expectedKind = expectedKind,
            explicitInitialTab = initialAgentTab,
            payloadAgentPanelTabOrder = payload.agentPanelTabOrder,
            storeServerMatches = agentPanelTabOrderState.serverId == agentPanelTabOrderServerId,
            storeLoaded = agentPanelTabOrderState.loaded
        )
    }
    val agentStatusResolver =
        remember(provider.serverMembersStore, statusResolverServerId) {
            slockScopedAgentStatusResolver(provider.serverMembersStore, statusResolverServerId)
        }
    val memberAvatarResolver =
        remember(provider.serverMembersStore, statusResolverServerId) {
            if (statusResolverServerId == null) {
                EmptySlockMemberAvatarResolver
            } else {
                slockMemberAvatarResolver(provider.serverMembersStore, statusResolverServerId)
            }
        }

    suspend fun refreshVisibleAgentActivity(
        current: MemberDetailRouteState.Agent,
        agentId: MemberId
    ) {
        if (!current.accessMode.allowsPrivateAgentSurfaces) return
        val detailState = provider.agentDetailStore.state.value
        val hasCachedActivity =
            detailState.detail
                ?.takeIf {
                    it.agentId == agentId &&
                        detailState.detailServerId == current.server.id
                }
                ?.activity
                ?.isNotEmpty() == true
        val generation = agentActivityRequestGeneration + 1L
        agentActivityRequestGeneration = generation
        val fence =
            MembersAgentActivityRequestFence(
                memberId = agentId,
                serverId = current.server.id,
                generation = generation
            )
        val presenceRequest =
            provider.serverMembersStore.beginAgentActivityPresenceProjection(current.server.id, agentId)
        agentActivityLoadState = membersAgentActivityLoadStart(hasCachedActivity)
        val result = provider.loadAgentActivity(agentId)
        val routeServerId = (routeState as? MemberDetailRouteState.Agent)?.server?.id
        if (!fence.matches(memberIdentity, routeServerId, agentActivityRequestGeneration)) return
        if (result is DataResult.Content) {
            provider.agentDetailStore.dispatch(
                SharedAgentDetailIntent.SetAgentActivity(
                    agentId = agentId,
                    activity = result.data,
                    serverId = current.server.id
                )
            )
            provider.serverMembersStore.applyAgentActivitySnapshot(presenceRequest, agentId, result.data)
        }
        agentActivityLoadState = result.toMembersAgentActivityLoadState()
    }

    LaunchedEffect(memberIdentity, activeServerId, localAgentDetailRefreshKey) {
        if (memberIdentity == null) {
            routeState = MemberDetailRouteState.Error("member_identity_missing")
            return@LaunchedEffect
        }
        if (memberIdentity.kind == MemberKind.Agent || routeState !is MemberDetailRouteState.Human) {
            routeState = MemberDetailRouteState.Loading
        }
        val generation = agentProfileRequestGeneration + 1L
        agentProfileRequestGeneration = generation
        val requestServerId = membersAgentProfileRequestServerId(memberIdentity, activeServerId)
        val requestOwner =
            if (memberIdentity.kind == MemberKind.Agent) {
                requestServerId?.let { serverId ->
                    provider.currentAgentDetailOwner(
                        expectedServerId = serverId,
                        requireCurrentServer =
                            memberIdentity.memberDetailAccessMode(activeServerId).allowsPrivateAgentSurfaces
                    )
                }
            } else {
                null
            }
        val requestFence =
            requestServerId?.let { serverId ->
                MembersAgentProfileRequestFence(
                    memberId = memberIdentity.memberId,
                    serverId = serverId,
                    generation = generation
                )
            }
        if (memberIdentity.kind == MemberKind.Agent) {
            if (requestOwner == null) {
                routeState = MemberDetailRouteState.Error("member_request_stale")
                return@LaunchedEffect
            }
            // Remove roster-derived seeds before the canonical request. They are useful for list
            // rows, but have neither a profile revision nor runtime-config authority.
            provider.agentDetailStore.dispatch(SharedAgentDetailIntent.DeselectMember)
            provider.agentDetailStore.dispatch(
                SharedAgentDetailIntent.SelectMember(memberIdentity.memberId, requestOwner, generation)
            )
            provider.agentDetailStore.dispatch(SharedAgentDetailIntent.SetDetailLoading(memberIdentity.memberId))
        }
        val loaded = provider.loadMember(memberIdentity, requestOwner, generation) {
            requestFence?.matches(memberIdentity, activeServerId, agentProfileRequestGeneration) == true
        }
        if (requestFence != null &&
            !requestFence.matches(memberIdentity, activeServerId, agentProfileRequestGeneration)
        ) {
            return@LaunchedEffect
        }
        routeState = loaded
        // The canonical detail request already includes the freshly merged durable/current
        // activity snapshot. Do not immediately repeat the same visible activity request.
    }
    LaunchedEffect(provider.serverMembersStore, statusResolverServerId) {
        val serverId = statusResolverServerId ?: return@LaunchedEffect
        provider.serverMembersStore.refreshMembers(ServerId(serverId), DataRequestPriority.Background)
    }
    LaunchedEffect(
        provider,
        memberIdentity,
        statusLookupServerId,
        expectedKind,
        agentPanelTabOrderState.serverId,
        agentPanelTabOrderState.loaded
    ) {
        statusLookupServerId?.trim()?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        if (expectedKind == MemberKind.Human) return@LaunchedEffect
        if (agentPanelTabOrderState.serverId == statusLookupServerId && agentPanelTabOrderState.loaded) {
            return@LaunchedEffect
        }
        when (val result = provider.loadAgentPanelTabOrder()) {
            is DataResult.Content ->
                provider.agentPanelTabOrderStore.applyLoaded(
                    serverId = statusLookupServerId,
                    order = result.data,
                    source = "member_detail_sidebar_order_get",
                    logger = { message -> bridge.log(message) }
                )
            is DataResult.Empty ->
                provider.agentPanelTabOrderStore.applyLoaded(
                    serverId = statusLookupServerId,
                    order = emptyList(),
                    source = "member_detail_sidebar_order_empty",
                    logger = { message -> bridge.log(message) }
                )
            is DataResult.Error -> {
                provider.agentPanelTabOrderStore.applyLoaded(
                    serverId = statusLookupServerId,
                    order = emptyList(),
                    source = "member_detail_sidebar_order_error_fallback",
                    logger = { message -> bridge.log(message) }
                )
                bridge.log("member_detail_agent_tab_order_load_failed code=${result.error.code}")
            }
            is DataResult.NoPermission -> {
                provider.agentPanelTabOrderStore.applyLoaded(
                    serverId = statusLookupServerId,
                    order = emptyList(),
                    source = "member_detail_sidebar_order_blocked_fallback",
                    logger = { message -> bridge.log(message) }
                )
                bridge.log("member_detail_agent_tab_order_load_blocked code=${result.blocker.code}")
            }
        }
    }

    val runtimeAccountUsageOwnerTracker = remember(provider) {
        MembersRuntimeAccountUsageOwnerTracker { provider.currentRuntimeAccountUsageOwner() }
    }
    LaunchedEffect(runtimeAccountUsageOwnerTracker, activeServerId, pageVisibleTick) {
        runtimeAccountUsageOwnerTracker.refresh()
    }
    // Live identity boundary: a sign-out / account / environment change clears a usage card that
    // was already loaded for the previous identity, even while this page stays composed.
    LaunchedEffect(runtimeAccountUsageOwnerTracker, provider.authStore) {
        (provider.authStore as? build.raft.app.data.SlockAuthSessionEventSource)
            ?.authSessionEvents
            ?.collect { event -> runtimeAccountUsageOwnerTracker.onAuthSessionEvent(event) }
    }
    val runtimeAccountUsageOwner by runtimeAccountUsageOwnerTracker.owner.collectAsState()
    // Task #137: read-only runtime account usage for the agent's Runtime Config chip. Only
    // routes that allow private agent surfaces get a server id; the server still decides
    // access (403 hides the entry).
    val runtimeAccountUsageAccess =
        remember(provider.runtimeAccountUsageStore, runtimeAccountUsageOwner, statusLookupServerId, runtimeAccountUsageOwnerTracker) {
            provider.runtimeAccountUsageStore?.let { store ->
                MembersRuntimeAccountUsageAccess(
                    store = store,
                    owner = runtimeAccountUsageOwner,
                    serverId = statusLookupServerId,
                    onIdentityChanged = { routeScope.launch { runtimeAccountUsageOwnerTracker.invalidate() } },
                    log = { message -> bridge.log(message) }
                )
            }
        }
    CompositionLocalProvider(LocalMembersRuntimeAccountUsageAccess provides runtimeAccountUsageAccess) {
        when (val current = routeState) {
            MemberDetailRouteState.Loading ->
                MemberDetailLoadingPageShell(onBack = { closeDetail() })
            is MemberDetailRouteState.Error ->
                MemberDetailErrorPageShell(
                    title = stringResource(MR.strings.member_error_load_member),
                    detail = current.error.userFacingMessage(errorStrings),
                    onBack = { closeDetail() },
                    onRetry = { localAgentDetailRefreshKey += 1 }
                )
            is MemberDetailRouteState.Agent -> {
                val member = selectedAgentDetail?.toStaticMembersProfile(current.server)
                if (member == null) {
                    Box(
                        modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite),
                        contentAlignment = Alignment.Center
                    ) {
                        SlockCenteredLoading()
                    }
                } else {
                    val agentMachineId =
                        selectedAgentDetail.summary?.machineId
                            ?: member.membersComputerId
                    // Task #336: gate the "Computer" link on the machine actually being
                    // registered on this server. Same three-state source as
                    // machineStatusRow: while the computers store has not answered the
                    // link stays (unknown), once a snapshot proves the machine is not
                    // here the link is withdrawn instead of opening a guaranteed 404.
                    val computerSliceForLink = computersState.selectComputerServerSlice(statusLookupServerId)
                    val computerNavigationTarget =
                        membersAgentComputerNavigationTarget(
                            serverId = current.server.id.value,
                            summaryMachineId = selectedAgentDetail.summary?.machineId,
                            memberMachineId = member.membersComputerId,
                            accessMode = current.accessMode,
                            machineKnown = membersAgentComputerKnownState(
                                machineId = agentMachineId,
                                machines = computerSliceForLink.machines,
                                loadStatus = computerSliceForLink.status,
                                hasSnapshot = computerSliceForLink.hasSnapshot,
                            ),
                        )
                    val onOpenComputer: (() -> Unit)? =
                        computerNavigationTarget?.let { target ->
                            {
                                pager.openSettingsComputerPage(
                                    storeScope = provider.storeScope,
                                    target = target,
                                )
                            }
                        }
                    val agentRuntimeModelsByRuntime =
                        agentMachineId
                            ?.let { machineId ->
                                agentRuntimeModelCatalogs
                                    .filterKeys { (catalogMachineId, _) -> catalogMachineId == machineId }
                                    .mapKeys { (key, _) -> key.second }
                            }
                            .orEmpty()
                    val agentRuntimeModelLoadsByRuntime =
                        agentMachineId
                            ?.let { machineId ->
                                agentRuntimeModelLoadsInFlight
                                    .filter { (catalogMachineId, _) -> catalogMachineId == machineId }
                                    .mapTo(linkedSetOf()) { (_, runtime) -> runtime }
                            }
                            .orEmpty()
                    val agentRuntimeModelErrorsByRuntime =
                        agentMachineId
                            ?.let { machineId ->
                                agentRuntimeModelErrors
                                    .filterKeys { (catalogMachineId, _) -> catalogMachineId == machineId }
                                    .mapKeys { (key, _) -> key.second }
                            }
                            .orEmpty()
                    val agentRuntimeModelGenerationsByRuntime =
                        agentMachineId
                            ?.let { machineId ->
                                agentRuntimeOptionRequestGenerations
                                    .filterKeys { (catalogMachineId, _) -> catalogMachineId == machineId }
                                    .mapKeys { (key, _) -> key.second }
                            }
                            .orEmpty()
                    val agentProfileEditAuthority =
                        agentDetailStoreState.detailOwner
                            ?.takeIf { owner ->
                                selectedAgentDetailStoreStateMatches &&
                                    owner.serverId == current.server.id &&
                                    agentDetailStoreState.detailProfileGeneration == agentProfileRequestGeneration
                            }
                            ?.let { owner ->
                                MembersAgentProfileEditAuthority(
                                    memberId = member.id,
                                    environmentId = owner.environmentId,
                                    accountId = owner.accountId,
                                    serverId = owner.serverId,
                                    profileGeneration = agentDetailStoreState.detailProfileGeneration
                                )
                            }
                    val loadAgentRuntimeModels: (String, Boolean) -> Unit = { runtime, force ->
                        val key = agentMachineId?.trim().orEmpty() to runtime.trim()
                        val serverId = statusLookupServerId?.trim().orEmpty()
                        if (serverId.isNotBlank() && key.first.isNotBlank() && key.second.isNotBlank() &&
                            (force || (key !in agentRuntimeModelCatalogs && key !in agentRuntimeModelLoadsInFlight))
                        ) {
                            val requestGeneration = (agentRuntimeOptionRequestGenerations[key] ?: 0L) + 1L
                            agentRuntimeOptionRequestGenerations =
                                agentRuntimeOptionRequestGenerations + (key to requestGeneration)
                            val requestFence =
                                MembersAgentRuntimeOptionsRequestFence(
                                    memberId = member.id,
                                    serverId = ServerId(serverId),
                                    machineId = key.first,
                                    runtimeId = key.second,
                                    profileGeneration = agentProfileRequestGeneration,
                                    generation = requestGeneration
                                )
                            agentRuntimeModelLoadsInFlight = agentRuntimeModelLoadsInFlight + key
                            agentRuntimeModelErrors = agentRuntimeModelErrors - key
                            routeScope.launch {
                                val result =
                                    provider.createAgentCoordinator.loadRuntimeModels(
                                        serverId = serverId,
                                        machineId = key.first,
                                        runtime = key.second
                                    )
                                if (!requestFence.matches(
                                        identity = memberIdentity,
                                        activeServerId = activeServerId,
                                        currentMachineId = agentMachineId,
                                        currentRuntimeId = key.second,
                                        currentProfileGeneration = agentProfileRequestGeneration,
                                        currentGeneration = agentRuntimeOptionRequestGenerations[key] ?: 0L
                                    )
                                ) {
                                    agentRuntimeModelLoadsInFlight =
                                        membersAgentRuntimeRequestCompletionInFlight(
                                            inFlight = agentRuntimeModelLoadsInFlight,
                                            generations = agentRuntimeOptionRequestGenerations,
                                            key = key,
                                            requestGeneration = requestGeneration
                                        )
                                    return@launch
                                }
                                when (result) {
                                    is DataResult.Content ->
                                        if (result.data.models.isNotEmpty()) {
                                            agentRuntimeModelCatalogs = agentRuntimeModelCatalogs + (key to result.data)
                                        } else {
                                            agentRuntimeModelErrors = agentRuntimeModelErrors +
                                                (key to "runtime_models_unavailable".userFacingErrorCodeMessage(errorStrings))
                                        }
                                    is DataResult.Empty ->
                                        agentRuntimeModelErrors = agentRuntimeModelErrors +
                                            (key to "runtime_models_unavailable".userFacingErrorCodeMessage(errorStrings))
                                    is DataResult.Error -> {
                                        agentRuntimeModelErrors = agentRuntimeModelErrors +
                                            (key to result.error.userFacingMessage(errorStrings))
                                        bridge.log(
                                            "member_detail_runtime_models_failed runtime=${key.second} " +
                                                "code=${result.error.code}"
                                        )
                                    }
                                    is DataResult.NoPermission -> {
                                        agentRuntimeModelErrors = agentRuntimeModelErrors +
                                            (key to result.blocker.userFacingMessage(errorStrings))
                                        bridge.log(
                                            "member_detail_runtime_models_blocked runtime=${key.second} " +
                                                "code=${result.blocker.code}"
                                        )
                                    }
                                }
                                agentRuntimeModelLoadsInFlight =
                                    membersAgentRuntimeRequestCompletionInFlight(
                                        inFlight = agentRuntimeModelLoadsInFlight,
                                        generations = agentRuntimeOptionRequestGenerations,
                                        key = key,
                                        requestGeneration = requestGeneration
                                    )
                            }
                        }
                    }
                    val agentDetailActions =
                        remember(provider, current.server.id) {
                            object : SharedAgentDetailActions {
                                override suspend fun runLifecycleAction(
                                    agentId: MemberId,
                                    action: MembersAgentLifecycleAction,
                                    workspaceChoice: AgentArchiveWorkspaceChoice
                                ): DataResult<AgentLifecycleActionResult> {
                                    val result = provider.runLifecycleAction(current.server.id, agentId, action, workspaceChoice)
                                    if (result is DataResult.Content && result.data.success) {
                                        localAgentDetailRefreshKey += 1
                                        if (action == MembersAgentLifecycleAction.Delete) {
                                            closeDetail()
                                        }
                                    }
                                    return result
                                }

                                override fun copyDiagnostics(diagnostics: String) {
                                    provider.copyDiagnostics(diagnostics, diagnosticsCopied)
                                }

                                override suspend fun reportIssue(
                                    request: SharedAgentIssueReportRequest
                                ): DataResult<AgentIssueReportResult> {
                                    return provider.reportIssue(
                                        server = current.server,
                                        member = member,
                                        detail = selectedAgentDetail,
                                        request = request
                                    )
                                }

                                override suspend fun updateAgentProfile(
                                    request: AgentProfileUpdateRequest
                                ): DataResult<MemberProfile> =
                                    provider.updateAgentProfile(current.server.id, request)
                            }
                        }
                    MembersDetailContent(
                        member = member,
                        currentUserId = current.currentUserId,
                        currentServerId = current.server.id.value,
                        serverMembersState = serverMembersState,
                        agentDetailState = agentDetailState,
                        agentActivityLoadState = agentActivityLoadState,
                        runtimeModels = MembersAgentRuntimeModelInputs(
                            agentMachineRuntimes =
                                membersAgentMachineRuntimeIds(
                                    machines = computerPickerSlice.machines,
                                    machineId = selectedAgentDetail.summary?.machineId ?: member.membersComputerId
                                ),
                            agentRuntimeModelsByRuntime = agentRuntimeModelsByRuntime,
                            agentRuntimeModelLoadsInFlight = agentRuntimeModelLoadsByRuntime,
                            agentRuntimeModelErrorsByRuntime = agentRuntimeModelErrorsByRuntime,
                            agentRuntimeModelGenerationsByRuntime = agentRuntimeModelGenerationsByRuntime,
                            onAgentRuntimeModelsRequest = loadAgentRuntimeModels
                        ),
                        createAgentFormStore = provider.createAgentFormStore,
                        externalSetupServerUrl = provider.apiEnvironmentStore.current().webUrl,
                        agentProfileEditAuthority = agentProfileEditAuthority,
                        canViewPrivateAgentSurfaces = { _, detail ->
                            current.accessMode.allowsPrivateAgentSurfaces &&
                                (current.server.permissions.canManageSettings ||
                                detail?.summary?.creatorId == current.currentUserId
                                )
                        },
                        canManageAgent = { _, detail ->
                            current.accessMode.allowsPrivateAgentSurfaces &&
                                (current.server.permissions.canManageSettings ||
                                detail?.summary?.creatorId == current.currentUserId
                                )
                        },
                        machineStatusRow = machineStatusRow,
                        actions = MembersDetailActions(
                            onBack = { closeDetail() },
                            onOpenDirectMessage = {
                                routeScope.launch {
                                    provider.openDirectMessage(member).openMembersDirectMessageResult(
                                        pager = pager,
                                        member = member,
                                        timeContext = timeContext,
                                        unavailableMessage = directMessageOpenError,
                                        errorTemplate = directMessageOpenErrorTemplate,
                                        permissionMessage = directMessagePermissionError,
                                        errorStrings = errorStrings,
                                        onUnavailableAction = { bridge.toast(it) }
                                    )
                                }
                            },
                            onOpenComputer = onOpenComputer,
                            onCopyMemberId = {
                                provider.copyMemberId(member, memberIdCopied)
                            },
                            onRemoveMember = {
                                provider.bridge.toast(removeFromSettings)
                            },
                            onOpenUrl = { linkNavigator.open(it) },
                            onOpenReminderMessage = { permalink ->
                                pager.openMembersReminderMessage(
                                    permalink = permalink,
                                    server = current.server,
                                    conversationIdentityProvider = provider.conversationIdentityProvider,
                                    storeScope = provider.storeScope,
                                    timeContext = timeContext,
                                    onOpenExternal = {
                                        linkNavigator.openMembersReminderForceExternalFallback(it)
                                    },
                                    unavailableMessage = reminderOpenError,
                                    onUnavailableAction = { provider.bridge.toast(it) }
                                )
                            },
                            onUnavailableAction = { bridge.toast(it) }
                        ),
                        agentDetailActions = agentDetailActions,
                        agentCallbacks = MembersAgentDetailCallbacks(
                            onUpdateAgentProfile = { request ->
                                agentDetailActions.updateAgentProfile(request)
                            },
                            onApplyAgentProfileResult = { updatedMember ->
                                provider.updateCachedMemberProfile(updatedMember, current.server.id)
                            },
                            onApplyAgentRuntimeConfigProjection = provider::applyRuntimeConfigProjection,
                            onPickAgentAvatarFile = {
                                provider.pickAgentAvatarFile()
                            },
                            onUploadAgentAvatar = { agentId, file ->
                                val result = provider.uploadAgentAvatar(current.server.id, agentId, file)
                                if (result is DataResult.Content) {
                                    provider.updateCachedMemberProfile(result.data, current.server.id)
                                }
                                result
                            },
                            onAgentLifecycleAction = { agentId, action, workspaceChoice ->
                                agentDetailActions.runLifecycleAction(agentId, action, workspaceChoice)
                            },
                            agentWorkspaceTreeLoader = { agentId ->
                                provider.memberRepository.getAgentWorkspaceTree(DataRequestPriority.Visible, agentId)
                            },
                            agentWorkspaceChildrenLoader = { agentId, dirPath ->
                                provider.memberRepository.getAgentWorkspaceChildren(DataRequestPriority.Visible, agentId, dirPath)
                            },
                            onCopyAgentDiagnostics = agentDetailActions::copyDiagnostics,
                            onCopyAgentIssueReference = provider.bridge::copyToPasteboard,
                            onReportAgentIssue = agentDetailActions::reportIssue
                        ),
                        timeContext = timeContext,
                        presentation = MembersAgentDetailPresentation(
                            pageVisibleTick = pageVisibleTick,
                            initialAgentTab =
                                if (current.accessMode.allowsPrivateAgentSurfaces) {
                                    initialAgentTabForRender
                                } else {
                                    MembersAgentDetailTab.Profile
                                },
                            agentPanelTabOrder = agentPanelTabOrder,
                            onAgentPanelTabOrderChange = { orderedTabs ->
                                val previousOrder = agentPanelTabOrder
                                val nextOrder = orderedTabs.toMembersAgentPanelTabOrderWireKeys()
                                if (nextOrder != previousOrder) {
                                    provider.agentPanelTabOrderStore.applyOptimistic(
                                        serverId = statusLookupServerId,
                                        order = nextOrder,
                                        source = "member_detail_user_reorder_optimistic",
                                        logger = { message -> bridge.log(message) }
                                    )
                                    routeScope.launch {
                                        when (val result = provider.updateAgentPanelTabOrder(orderedTabs)) {
                                            is DataResult.Content ->
                                                provider.agentPanelTabOrderStore.applyLoaded(
                                                    serverId = statusLookupServerId,
                                                    order = result.data,
                                                    source = "member_detail_user_reorder_save_success",
                                                    logger = { message -> bridge.log(message) }
                                                )
                                            is DataResult.Empty -> {
                                                provider.agentPanelTabOrderStore.applyLoaded(
                                                    serverId = statusLookupServerId,
                                                    order = previousOrder,
                                                    source = "member_detail_user_reorder_save_empty_rollback",
                                                    logger = { message -> bridge.log(message) }
                                                )
                                                bridge.toast(tabOrderSaveError)
                                            }
                                            is DataResult.Error -> {
                                                provider.agentPanelTabOrderStore.applyLoaded(
                                                    serverId = statusLookupServerId,
                                                    order = previousOrder,
                                                    source = "member_detail_user_reorder_save_error_rollback",
                                                    logger = { message -> bridge.log(message) }
                                                )
                                                bridge.toast(
                                                    tabOrderSaveErrorTemplate
                                                        .membersFormat(result.error.userFacingMessage(errorStrings))
                                                )
                                            }
                                            is DataResult.NoPermission -> {
                                                provider.agentPanelTabOrderStore.applyLoaded(
                                                    serverId = statusLookupServerId,
                                                    order = previousOrder,
                                                    source = "member_detail_user_reorder_save_blocked_rollback",
                                                    logger = { message -> bridge.log(message) }
                                                )
                                                bridge.toast(tabOrderPermissionError)
                                            }
                                        }
                                    }
                                }
                            }
                        ),
                        sharedAgentDetailStore = provider.agentDetailStore,
                        accessMode = current.accessMode,
                        agentStatusResolver = agentStatusResolver,
                        memberAvatarResolver = memberAvatarResolver,
                        agentsApiClient = provider.agentsApiClient
                    )
                }
            }
            is MemberDetailRouteState.Human -> {
                val serverMembers = serverMembersState.entryFor(current.server.id)?.members.orEmpty()
                val ownerCount = serverMembers.count {
                    it.kind == MemberKind.Human &&
                        it.membershipStatus == "active" &&
                        it.serverRole.normalizedMembersServerRole() == "owner"
                }
                val canRemoveHumanMember =
                    membersCanRemoveHumanMember(
                        actorRole = current.server.role,
                        targetRole = current.member.serverRole,
                        targetMembershipStatus = current.member.membershipStatus,
                        targetMemberId = current.member.id.value,
                        currentUserId = current.currentUserId,
                        targetServerId = current.member.serverId?.value,
                        currentServerId = current.server.id.value,
                        ownerCount = ownerCount.takeIf { it > 0 }
                    )
                MembersDetailContent(
                    member = current.member,
                    currentUserId = current.currentUserId,
                    currentServerId = current.server.id.value,
                    agentDetailState = MembersAgentDetailLoadState.Idle,
                    canViewPrivateAgentSurfaces = { _, _ -> false },
                    canManageAgent = { _, _ -> false },
                    machineStatusRow = machineStatusRow,
                    actions = MembersDetailActions(
                        onBack = { closeDetail() },
                        onOpenDirectMessage = {
                            routeScope.launch {
                                provider.openDirectMessage(current.member).openMembersDirectMessageResult(
                                    pager = pager,
                                    member = current.member,
                                    timeContext = timeContext,
                                    unavailableMessage = directMessageOpenError,
                                    errorTemplate = directMessageOpenErrorTemplate,
                                    permissionMessage = directMessagePermissionError,
                                    errorStrings = errorStrings,
                                    onUnavailableAction = { bridge.toast(it) }
                                )
                            }
                        },
                        onCopyMemberId = {
                            provider.copyMemberId(current.member, memberIdCopied)
                        },
                        onRemoveMember = if (canRemoveHumanMember) ({
                            provider.bridge.toast(removeFromSettings)
                        }) else null,
                        onOpenUrl = { linkNavigator.open(it) },
                        onOpenReminderMessage = { permalink ->
                            pager.openMembersReminderMessage(
                                permalink = permalink,
                                server = current.server,
                                conversationIdentityProvider = provider.conversationIdentityProvider,
                                storeScope = provider.storeScope,
                                timeContext = timeContext,
                                onOpenExternal = {
                                    linkNavigator.openMembersReminderForceExternalFallback(it)
                                },
                                unavailableMessage = reminderOpenError,
                                onUnavailableAction = { provider.bridge.toast(it) }
                            )
                        },
                        onUnavailableAction = { bridge.toast(it) }
                    ),
                    humanRole = MembersHumanRoleInputs(
                        humanActorRole = current.server.role,
                        humanOwnerCount = ownerCount.takeIf { it > 0 },
                        humanAccessMode = current.accessMode,
                        onUpdateHumanRole = { memberId, role ->
                            provider.updateHumanRole(current.server.id.value, memberId, role)
                        }
                    ),
                    agentCallbacks = MembersAgentDetailCallbacks(
                        onUpdateAgentProfile = null,
                        onUploadAgentAvatar = null,
                        onAgentLifecycleAction = null,
                        agentWorkspaceTreeLoader = null,
                        agentWorkspaceChildrenLoader = null,
                        onCopyAgentDiagnostics = {},
                        onReportAgentIssue = null
                    ),
                    timeContext = timeContext,
                    presentation = MembersAgentDetailPresentation(
                        pageVisibleTick = pageVisibleTick,
                        initialAgentTab = initialAgentTab,
                        agentPanelTabOrder = payload.agentPanelTabOrder
                    ),
                    agentStatusResolver = agentStatusResolver,
                    memberAvatarResolver = memberAvatarResolver
                )
            }
        }
    }
}

private fun KuiklyMemberDetailPayload.toMemberDetailVisualSeedOrNull(): MemberDetailVisualSeed? {
    if (!instanceId.startsWith("visual-")) return null
    return when (identity?.memberId?.value) {
        membersAgentDetailVisualMember.id.value ->
            MemberDetailVisualSeed(
                member = membersAgentDetailVisualMember,
                agentDetailState = MembersAgentDetailLoadState.Content(membersAgentDetailVisualSnapshot),
                agentScopeSet = membersAgentDetailVisualScopeSet
            )
        membersHumanDetailVisualMember.id.value ->
            MemberDetailVisualSeed(
                member = membersHumanDetailVisualMember,
                agentDetailState = MembersAgentDetailLoadState.Idle
            )
        else -> membersAgentDetailVisualVariants
            .firstOrNull { it.id == identity?.memberId?.value }
            ?.toSeed()
    }
}

@Composable
private fun MemberDetailVisualSeedContent(
    payload: KuiklyMemberDetailPayload,
    seed: MemberDetailVisualSeed,
    pageVisibleTick: Int
) {
    val initialAgentTab = remember(payload.initialAgentTab) {
        payload.initialAgentTab.toMembersAgentDetailTabOrNull()
    }
    val visualMode = remember(payload.visualMode) {
        payload.visualMode.orEmpty().toMembersAgentDetailVisualMode()
    }
    MembersDetailContent(
        member = seed.member,
        currentUserId = "visual-owner",
        currentServerId = "visual-server",
        agentDetailState = seed.agentDetailState,
        canViewPrivateAgentSurfaces = { member, _ -> member.kind == MemberKind.Agent },
        canManageAgent = { member, _ -> member.kind == MemberKind.Agent },
        machineStatusRow = membersVisualFixtureMachineStatusRow,
        actions = MembersDetailActions(
            onBack = {},
            onOpenDirectMessage = {},
            onCopyMemberId = {},
            onOpenUrl = {},
            onUnavailableAction = {}
        ),
        humanRole = MembersHumanRoleInputs(
            humanActorRole = "owner",
            humanOwnerCount = 2,
            onUpdateHumanRole = { _, _ -> DataResult.Content(SettingsServerActionResult(success = true)) }
        ),
        agentCallbacks = MembersAgentDetailCallbacks(
            onUpdateAgentProfile = { DataResult.Content(seed.member) },
            onUploadAgentAvatar = null,
            onAgentLifecycleAction = { agentId, action, _ ->
                DataResult.Content(
                    AgentLifecycleActionResult(
                        agentId = agentId,
                        action = action.actionKey,
                        success = true
                    )
                )
            },
            agentWorkspaceTreeLoader = { DataResult.Content(membersAgentDetailVisualWorkspaceTreeSnapshot) },
            agentWorkspaceChildrenLoader = { _, _ ->
                DataResult.Content(membersAgentDetailVisualWorkspaceNotesChildren)
            },
            onCopyAgentDiagnostics = {},
            onReportAgentIssue = {
                DataResult.Content(
                    AgentIssueReportResult(
                        reportId = "7e91c120-f424-42d7-96e5-21eb816866d0",
                        artifactId = "95d629bd-3ed7-42ff-97eb-3ed35c6dc6ab",
                        serverId = "65598229-cd0a-4e92-96f3-c33e7e2e4cc1",
                        issueDescription = "The agent stopped responding after a long-running command.",
                        transcriptRequested = true
                    )
                )
            }
        ),
        timeContext = payload.toSlockTimeContext(),
        presentation = MembersAgentDetailPresentation(
            pageVisibleTick = pageVisibleTick,
            initialVisualMode = visualMode,
            initialAgentTab = initialAgentTab,
            agentPanelTabOrder = payload.agentPanelTabOrder,
            initialAgentScopeSet = seed.agentScopeSet,
            stableVisualCapture = true
        )
    )
}

@Composable
private fun MemberDetailLoadingPageShell(
    onBack: () -> Unit
) {
    MemberDetailRoutePageShell(onBack = onBack) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            SlockCenteredLoading()
        }
    }
}

@Composable
private fun MemberDetailErrorPageShell(
    title: String,
    detail: String,
    onBack: () -> Unit,
    onRetry: () -> Unit
) {
    MemberDetailRoutePageShell(onBack = onBack) {
        SlockEmptyState(
            title = title,
            detail = detail,
            iconName = SlockLucideIconName.TriangleAlert,
            actionLabel = stringResource(MR.strings.common_retry),
            onAction = onRetry,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun MemberDetailRoutePageShell(
    onBack: () -> Unit,
    content: @Composable () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite)) {
        SlockTopPageHeader(
            title = stringResource(MR.strings.member_role_member),
            subtitle = stringResource(MR.strings.member_label_profile),
            iconName = SlockLucideIconName.Users,
            backgroundColor = SlockColor.SurfaceWhite,
            leadingContent = {
                SlockTopPageHeaderAction(icon = SlockLucideIconName.ArrowLeft, onClick = onBack)
            }
        )
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            content()
        }
    }
}

@Composable
internal fun MembersAgentDetailVisualContent() {
    val pager = LocalActivity.current.getPager() as Pager
    val visualMode = pager.pageData.params.optString("visualMode").toMembersAgentDetailVisualMode()
    MembersDetailContent(
        member = membersAgentDetailVisualMember,
        agentDetailState = MembersAgentDetailLoadState.Content(membersAgentDetailVisualSnapshot),
        canViewPrivateAgentSurfaces = { _, _ -> true },
        canManageAgent = { _, _ -> true },
        // Without this the avatar picker never expands under capture. MembersAgentProfileTab
        // gates editing on `agentProfileEditAuthority != null` (canEdit), and the header only
        // draws MembersAgentAvatarChoiceGrid when canEdit && showAvatarPicker -- so the
        // avatar-management visual case was photographing the *collapsed* state while web
        // photographed the expanded grid. Its 23.83% mismatch was "expanded vs collapsed",
        // not a spacing difference (XiShi, task #264). The values are fixture identity;
        // nothing here is authoritative for any real server.
        agentProfileEditAuthority =
            MembersAgentProfileEditAuthority(
                memberId = membersAgentDetailVisualMember.id,
                environmentId = "visual-fixture",
                accountId = "visual-fixture",
                serverId = ServerId("visual-fixture"),
                profileGeneration = 0L
            ),
        machineStatusRow = membersVisualFixtureMachineStatusRow,
        actions = MembersDetailActions(
            onBack = {},
            onOpenDirectMessage = {},
            onCopyMemberId = {},
            onOpenUrl = {},
            onUnavailableAction = {}
        ),
        agentCallbacks = MembersAgentDetailCallbacks(
            onUpdateAgentProfile = { DataResult.Content(membersAgentDetailVisualMember) },
            onAgentLifecycleAction = { _, action, _ ->
                DataResult.Content(
                    AgentLifecycleActionResult(
                        agentId = membersAgentDetailVisualMember.id,
                        action = action.actionKey,
                        success = true
                    )
                )
            },
            agentWorkspaceTreeLoader = null,
            agentWorkspaceChildrenLoader = null,
            onCopyAgentDiagnostics = {},
            onReportAgentIssue = null,
            onUploadAgentAvatar = null
        ),
        timeContext = SlockTimeContext(
            localOffsetMinutes = 480,
            todayEpochDay = 20623,
            nowEpochMillis = 1781872200000,
            timeFormat = SlockTimeFormat24Hour
        ),
        presentation = MembersAgentDetailPresentation(
            initialVisualMode = visualMode,
            stableVisualCapture = true
        )
    )
}

private fun readCurrentServerId(): String? = null

internal fun SlockLinkNavigator.openMembersReminderForceExternalFallback(
    rawUrl: String
): SlockLinkOpenDecision =
    open(rawUrl, SlockLinkOpenIntent.ForceExternal)

private fun Pager.openMembersReminderMessage(
    permalink: String,
    server: ServerSummary,
    conversationIdentityProvider: build.raft.app.data.SharedConversationIdentityProvider?,
    storeScope: SlockShellStoreScope,
    timeContext: SlockTimeContext,
    onOpenExternal: (String) -> Unit,
    unavailableMessage: String,
    onUnavailableAction: (String) -> Unit
) {
    val target = permalink.toSlockThreadWebLinkTarget()
    if (target == null) {
        onOpenExternal(permalink)
        return
    }
    val messageId = target.messageId?.takeIf { it.isNotBlank() }
    if (messageId == null) {
        onUnavailableAction(unavailableMessage)
        return
    }
    val launchPayload =
        target.toMembersReminderThreadLaunchPayload(
            messageId = messageId,
            timeContext = timeContext,
            server = server,
            conversationIdentityProvider = conversationIdentityProvider
        )
            ?: run {
                onUnavailableAction(unavailableMessage)
                return
            }
    openSlockPage(
        pageName = THREAD_PAGE,
        pageData = launchPayload.toThreadPageData(),
        storeScopeOverride = storeScope,
        onMissingScope = { onUnavailableAction(unavailableMessage) }
    )
}

private fun DataResult<OpenDirectMessageResult>.openMembersDirectMessageResult(
    pager: Pager,
    member: MemberProfile,
    timeContext: SlockTimeContext,
    unavailableMessage: String,
    errorTemplate: String,
    permissionMessage: String,
    errorStrings: ErrorPresentationStrings,
    onUnavailableAction: (String) -> Unit
) {
    when (this) {
        is DataResult.Content -> {
            val launchPayload =
                buildMembersDirectMessageThreadLaunchPayload(
                    member = member,
                    conversation = data.conversation,
                    timeContext = timeContext
                )
            if (launchPayload == null) {
                onUnavailableAction(unavailableMessage)
            } else {
                pager.openSlockPage(
                    pageName = THREAD_PAGE,
                    pageData = launchPayload.toThreadPageData(),
                    onMissingScope = { onUnavailableAction(unavailableMessage) }
                )
            }
        }
        is DataResult.Empty ->
            onUnavailableAction(unavailableMessage)
        is DataResult.Error ->
            onUnavailableAction(membersFormattedScreenError(errorTemplate, error, errorStrings))
        is DataResult.NoPermission ->
            onUnavailableAction(permissionMessage)
    }
}

internal fun buildMembersDirectMessageThreadLaunchPayload(
    member: MemberProfile,
    conversation: HomeDirectMessageRow,
    timeContext: SlockTimeContext
): WireThreadLaunchPayload? {
    val threadId =
        conversation.threadId.value.trim().takeIf { it.isNotBlank() }
            ?: conversation.channelId.value.trim().takeIf { it.isNotBlank() }
            ?: return null
    return buildStandaloneConversationThreadLaunchPayload(
        conversation =
            ThreadLaunchConversationIdentity(
                threadId = ThreadId(threadId),
                channelId = ChannelId(threadId),
                channelType = KuiklyThreadSurfaceKind.DirectMessage,
                kind = ConversationKind.DirectMessage,
                dmPeerIdentity =
                    memberDetailIdentity(
                        memberId = member.id.value,
                        kind = member.kind,
                        sourceServerId = member.serverId?.value
                    )
            ),
        timeContext = timeContext,
        instanceIdOverride = "members-message-${member.id.value}-$threadId"
    )
}

internal fun SlockThreadWebLinkTarget.toMembersReminderThreadLaunchPayload(
    messageId: String,
    timeContext: SlockTimeContext,
    server: ServerSummary,
    conversationIdentityProvider: build.raft.app.data.SharedConversationIdentityProvider?,
    conversationDirectory: SharedConversationDirectoryState = SharedConversationDirectoryState()
): WireThreadLaunchPayload? {
    val instanceId = "members-reminder-${channelId}-${messageId}"
    val serverId = server.id.value.takeIf { it.isNotBlank() } ?: return null
    val providerDirectory =
        listOfNotNull(conversationIdentityProvider?.resolveProjection(server.id, channelId))
            .toSharedConversationDirectoryState()
    val directory =
        listOf(conversationDirectory, providerDirectory).mergedSharedConversationDirectoryState()
    val request = resolveMarkdownOpenRequest(serverId, directory) ?: return null
    return buildStandaloneConversationThreadLaunchPayload(
        conversation = request.conversationIdentity,
        timeContext = timeContext,
        anchorMessageId = request.anchorMessageId ?: messageId,
        instanceIdOverride = instanceId
    )
}

internal fun String.toKuiklyMembersPayload(): KuiklyMembersPayload {
    if (isBlank()) return KuiklyMembersPayload()
    val payload = JSONObject(this)
    val labelsJson = payload.optJSONObject("labels")
    return KuiklyMembersPayload(
        instanceId = payload.optString("instanceId"),
        title = payload.optString("title").ifBlank { "Members" },
        localOffsetMinutes = payload.optString("localOffsetMinutes").toIntOrNull() ?: 0,
        todayEpochDay = payload.optString("todayEpochDay").toLongOrNull(),
        nowEpochMillis = payload.optString("nowEpochMillis").toLongOrNull(),
        timeFormat = payload.optString("timeFormat").ifBlank { SlockTimeFormat24Hour },
        initialAgentTab = payload.optNullableString("initialAgentTab"),
        agentPanelTabOrder = payload.optStringArray("agentPanelTabOrder"),
        canCreateAgents = (payload.opt("canCreateAgents") as? Boolean) ?: true,
        canInviteHumans = (payload.opt("canInviteHumans") as? Boolean) ?: true,
        labels = KuiklyMembersLabels(
            agents = labelsJson?.optString("agents").orEmpty().ifBlank { "AGENTS" },
            humans = labelsJson?.optString("humans").orEmpty().ifBlank { "HUMANS" },
            addHuman = labelsJson?.optString("addHuman").orEmpty().ifBlank { "Add human" }
        )
    )
}

internal fun KuiklyMembersPayload.toMemberDetailPageData(
    identity: MemberDetailIdentity
): JSONObject? {
    val selected = identity.memberId.value.trim().takeIf { it.isNotBlank() } ?: return null
    val selectedMember = members.firstOrNull { it.id.value == selected }
    val launchInitialAgentTab =
        membersInitialAgentDetailTabForLaunch(
            explicitInitialTab = initialAgentTab,
            agentPanelTabOrder = agentPanelTabOrder,
            selectedMemberKind = identity.kind
        )
    return JSONObject().apply {
        put("pageName", MEMBER_DETAIL_PAGE)
        put("instanceId", "members-detail-${instanceId.ifBlank { "members" }}-$selected")
        put("memberId", selected)
        put("memberKind", identity.kind.memberKindWireValue())
        (identity.sourceServerId ?: selectedMember?.serverId)?.value?.trim()?.takeIf { it.isNotBlank() }?.let {
            put("sourceServerId", it)
        }
        launchInitialAgentTab?.let { put("initialAgentTab", it.serverOrderKey) }
        put("localOffsetMinutes", localOffsetMinutes)
        todayEpochDay?.let { put("todayEpochDay", it) }
        nowEpochMillis?.let { put("nowEpochMillis", it) }
        put("timeFormat", timeFormat)
        if (agentPanelTabOrder.isNotEmpty()) {
            put("agentPanelTabOrder", JSONArray().apply {
                agentPanelTabOrder.forEach(::put)
            })
        }
    }
}

internal fun JSONObject.toKuiklyMemberDetailPayload(): KuiklyMemberDetailPayload {
    val payloadText = optString("payload")
    val payload = if (payloadText.isNotBlank()) JSONObject(payloadText) else this
    return KuiklyMemberDetailPayload(
        instanceId = payload.optString("instanceId").ifBlank { optString("instanceId") },
        identity =
            memberDetailIdentityFromWire(
                memberId = payload.optString("memberId").ifBlank { payload.optString("selectedMemberId") },
                kindWireValue =
                    payload.optString("memberKind")
                        .ifBlank { payload.optString("selectedMemberKind") }
                        .ifBlank { payload.optString("kind") },
                sourceServerId = payload.optString("sourceServerId").ifBlank { payload.optString("serverId") },
                source = MemberDetailIdentitySource.LegacyPageData
            ),
        initialAgentTab = payload.optNullableString("initialAgentTab"),
        visualMode = payload.optNullableString("visualMode") ?: optNullableString("visualMode"),
        localOffsetMinutes = payload.optString("localOffsetMinutes").toIntOrNull() ?: 0,
        todayEpochDay = payload.optString("todayEpochDay").toLongOrNull(),
        nowEpochMillis = payload.optString("nowEpochMillis").toLongOrNull(),
        timeFormat = payload.optString("timeFormat").ifBlank { SlockTimeFormat24Hour },
        agentPanelTabOrder = payload.optStringArray("agentPanelTabOrder")
    )
}

private fun JSONObject.optNullableString(key: String): String? =
    optString(key).takeIf { it.isNotBlank() && it != "null" }

private fun JSONObject.optStringArray(key: String): List<String> =
    buildList {
        val array = optJSONArray(key) ?: return@buildList
        for (index in 0 until array.length()) {
            array.optString(index)?.takeIf { it.isNotBlank() }?.let(::add)
        }
    }

/**
 * Parameter holders for [MembersRouteContent] / [MembersListContent] (task #1130): both took 33
 * parameters, past the 31-per-int `$changed` / `$default` mask boundary. Same caveats as
 * [MembersAgentRuntimeModelInputs]: plain unstable classes, behaviour unchanged, no
 * `@Stable` / `@Immutable` later.
 */
internal class MembersCreateAgentInputs(
    /**
     * Optional create-agent flow. When non-null, the agents section "+" opens a
     * SharedCreateAgentDialog and submit is forwarded to this host callback.
     * The host is responsible for the actual API call and refreshing the members
     * payload.
     */
    val createAgentSubmit: (suspend (SharedCreateAgentDialogSubmit) -> DataResult<Unit>)? = null,
    val createAgentMachines: List<SharedCreateAgentMachineOption> = emptyList(),
    val createAgentMachinesLoading: Boolean = false,
    val createAgentMachinesErrorMessage: String? = null,
    val createAgentCurrentAgentCount: Int = 0,
    val createAgentMaxAgents: Int = -1,
    val createAgentLoadMachines: () -> Unit = {},
    val createAgentRetryMachines: () -> Unit = {},
    val createAgentRuntimeModelsByMachineAndRuntime: Map<Pair<String, String>, SharedCreateAgentRuntimeModelCatalog> = emptyMap(),
    val createAgentRuntimeModelLoadsInFlight: Set<Pair<String, String>> = emptySet(),
    val createAgentLoadRuntimeModels: (machineId: String, runtime: String, force: Boolean) -> Unit = { _, _, _ -> },
    val createAgentFormStore: build.raft.app.data.SharedCreateAgentFormStore? = null,
    val createAgentServerId: String? = null,
    /** Agent @names already used on this server, so a suggested name avoids them. */
    val createAgentTakenAgentNames: List<String> = emptyList()
)

/** Agent @names (handles) of live agents, so a suggested create-agent name avoids them. */
internal fun List<MemberProfile>.takenAgentNames(): List<String> =
    filter { it.kind == MemberKind.Agent && it.agentDeletedAt.isNullOrBlank() }.map { it.handle }

internal class MembersInviteHumanInputs(
    /**
     * Optional invite-human flow. When non-null, the humans section "+" opens
     * a [SharedInviteHumanDialog] and submit is forwarded to this host
     * callback. Mirrors the [MembersCreateAgentInputs.createAgentSubmit] pattern.
     */
    val inviteHumanSubmit: (suspend (emails: List<String>) -> DataResult<Unit>)? = null,
    val inviteHumanJoinLinkLoading: Boolean = false,
    val inviteHumanJoinLinkUrl: String? = null,
    val inviteHumanJoinLinkCopied: Boolean = false,
    val inviteHumanJoinLinkCopy: (String) -> Unit = {},
    val inviteHumanLoadJoinLink: () -> Unit = {}
)

@Composable
internal fun MembersRouteContent(
    payload: KuiklyMembersPayload,
    refreshing: Boolean = false,
    onBack: (() -> Unit)? = null,
    onAddHuman: () -> Unit,
    onOpenMemberDetail: (MemberProfile) -> Unit,
    agentStatusResolver: SlockAgentStatusResolver = EmptySlockAgentStatusResolver,
    memberAvatarResolver: SlockMemberAvatarResolver = EmptySlockMemberAvatarResolver,
    createAgent: MembersCreateAgentInputs = MembersCreateAgentInputs(),
    inviteHuman: MembersInviteHumanInputs = MembersInviteHumanInputs(),
    /**
     * Permission gate for showing the agents "+". Defaults to false (no create).
     * When true, the agents "+" is rendered. When false, the "+" is hidden.
     */
    canCreateAgents: Boolean = false,
    agentsApiClient: build.raft.app.data.SlockAgentsApiClient? = null,
    /**
     * Task #409: current session's user id, used as the persistence scope for
     * per-machine agent-group collapse (same `slock:sidebarCollapsed:<userId>`
     * key family as web). Null keeps the groups collapsible but unpersisted
     * (smoke/test surfaces).
     */
    currentUserId: String? = null,
    agentGroupCollapseStore: build.raft.app.data.MembersAgentMachineGroupCollapseStore? = null,
    bottomContentPadding: Dp = SlockSpacing.Lg,
    scrollToTopRequest: Int = 0,
    initialListState: AppShellMembersListState = AppShellMembersListState(),
    onListStateSnapshot: (AppShellMembersListState) -> Unit = {},
    paneLayout: SlockAdaptivePaneLayout = SlockAdaptivePaneLayout.Compact,
    selectedIdentity: MemberDetailIdentity? = null,
    selectedLocalServerId: ServerId? = null,
    onRefresh: () -> Unit = {}
) {
    MembersListContent(
        payload = payload,
        selectedIdentity = selectedIdentity,
        selectedLocalServerId = selectedLocalServerId,
        refreshing = refreshing,
        onBack = onBack,
        onRefresh = onRefresh,
        onAddHuman = onAddHuman,
        onOpenMember = onOpenMemberDetail,
        agentStatusResolver = agentStatusResolver,
        memberAvatarResolver = memberAvatarResolver,
        canCreateAgents = canCreateAgents,
        createAgent = createAgent,
        inviteHuman = inviteHuman,
        bottomContentPadding = bottomContentPadding,
        scrollToTopRequest = scrollToTopRequest,
        initialListState = initialListState,
        onListStateSnapshot = onListStateSnapshot,
        paneLayout = paneLayout,
        currentUserId = currentUserId,
        agentGroupCollapseStore = agentGroupCollapseStore
    )
}

@Composable
internal fun MembersListContent(
    payload: KuiklyMembersPayload,
    selectedIdentity: MemberDetailIdentity? = null,
    selectedLocalServerId: ServerId? = null,
    refreshing: Boolean = false,
    onBack: (() -> Unit)? = null,
    onAddHuman: () -> Unit,
    onOpenMember: (MemberProfile) -> Unit,
    agentStatusResolver: SlockAgentStatusResolver = EmptySlockAgentStatusResolver,
    memberAvatarResolver: SlockMemberAvatarResolver = EmptySlockMemberAvatarResolver,
    canCreateAgents: Boolean = false,
    createAgent: MembersCreateAgentInputs = MembersCreateAgentInputs(),
    inviteHuman: MembersInviteHumanInputs = MembersInviteHumanInputs(),
    agentsApiClient: build.raft.app.data.SlockAgentsApiClient? = null,
    bottomContentPadding: Dp = SlockSpacing.Lg,
    scrollToTopRequest: Int = 0,
    initialListState: AppShellMembersListState = AppShellMembersListState(),
    onListStateSnapshot: (AppShellMembersListState) -> Unit = {},
    paneLayout: SlockAdaptivePaneLayout = SlockAdaptivePaneLayout.Compact,
    onRefresh: () -> Unit = {},
    currentUserId: String? = null,
    agentGroupCollapseStore: build.raft.app.data.MembersAgentMachineGroupCollapseStore? = null
) {
    val createAgentSubmit = createAgent.createAgentSubmit
    val createAgentMachines = createAgent.createAgentMachines
    val createAgentMachinesLoading = createAgent.createAgentMachinesLoading
    val createAgentMachinesErrorMessage = createAgent.createAgentMachinesErrorMessage
    val createAgentCurrentAgentCount = createAgent.createAgentCurrentAgentCount
    val createAgentMaxAgents = createAgent.createAgentMaxAgents
    val createAgentLoadMachines = createAgent.createAgentLoadMachines
    val createAgentRetryMachines = createAgent.createAgentRetryMachines
    val createAgentRuntimeModelsByMachineAndRuntime = createAgent.createAgentRuntimeModelsByMachineAndRuntime
    val createAgentRuntimeModelLoadsInFlight = createAgent.createAgentRuntimeModelLoadsInFlight
    val createAgentLoadRuntimeModels = createAgent.createAgentLoadRuntimeModels
    val createAgentFormStore = createAgent.createAgentFormStore
    val createAgentServerId = createAgent.createAgentServerId
    val createAgentTakenAgentNames = createAgent.createAgentTakenAgentNames
    val inviteHumanSubmit = inviteHuman.inviteHumanSubmit
    val inviteHumanJoinLinkLoading = inviteHuman.inviteHumanJoinLinkLoading
    val inviteHumanJoinLinkUrl = inviteHuman.inviteHumanJoinLinkUrl
    val inviteHumanJoinLinkCopied = inviteHuman.inviteHumanJoinLinkCopied
    val inviteHumanJoinLinkCopy = inviteHuman.inviteHumanJoinLinkCopy
    val inviteHumanLoadJoinLink = inviteHuman.inviteHumanLoadJoinLink
    val errorStrings = localizedErrorPresentationStrings()
    val displayTitle =
        canonicalMembersDisplayText(
            value = payload.title,
            canonicalDefault = "Members",
            localizedDefault = stringResource(MR.strings.members_page_title)
        )
    val displayAgentsLabel =
        canonicalMembersDisplayText(
            value = payload.labels.agents,
            canonicalDefault = "AGENTS",
            localizedDefault = stringResource(MR.strings.members_section_agents)
        )
    val displayHumansLabel =
        canonicalMembersDisplayText(
            value = payload.labels.humans,
            canonicalDefault = "HUMANS",
            localizedDefault = stringResource(MR.strings.members_section_humans)
        )
    val groupedMembers = remember(payload.members) {
        payload.members.groupBy {
            when (it.kind) {
                MemberKind.Human -> payload.labels.humans
                MemberKind.Agent, MemberKind.System, MemberKind.External -> payload.labels.agents
            }
        }
    }
    val agents = groupedMembers[payload.labels.agents].orEmpty()
    val humans = groupedMembers[payload.labels.humans].orEmpty()
    var agentsExpanded by remember(payload.instanceId) { mutableStateOf(initialListState.agentsExpanded) }
    var humansExpanded by remember(payload.instanceId) { mutableStateOf(initialListState.humansExpanded) }
    val membersCurrentUserId = currentUserId?.trim().orEmpty()
    // Task #409: collapse state = persisted default + this page's user taps.
    // The members payload and the session user id both arrive asynchronously,
    // so the stored state cannot be read once eagerly: a group that does not
    // exist yet when the store is read would render expanded forever. Resolve
    // per group at render instead: user taps this session override the stored
    // value, untouched groups read the store. This survives members arriving
    // after the user id (and vice versa) on the standalone members page.
    var agentGroupCollapseOverrides by remember(payload.instanceId, membersCurrentUserId) {
        mutableStateOf<Map<String, Boolean>>(emptyMap())
    }
    fun isAgentGroupCollapsed(groupKey: String): Boolean =
        resolveAgentGroupCollapsed(groupKey, agentGroupCollapseOverrides, membersCurrentUserId, agentGroupCollapseStore)
    var activeCreateAgentMode by remember(payload.instanceId) { mutableStateOf<MembersCreateAgentMode?>(null) }
    var createAgentMenuOpen by remember(payload.instanceId) { mutableStateOf(false) }
    var createAgentMenuAnchor by remember(payload.instanceId) { mutableStateOf<SlockPopoverAnchor?>(null) }
    var createAgentSubmitting by remember { mutableStateOf(false) }
    var createAgentError by remember { mutableStateOf<String?>(null) }
    // Runtime form v2: fields a rejected create pointed at (`/formValues/<field>`).
    var createAgentErrorFields by remember { mutableStateOf(emptySet<String>()) }
    var activeInviteHuman by remember(payload.instanceId) { mutableStateOf(false) }
    var inviteHumanSubmitting by remember { mutableStateOf(false) }
    var inviteHumanError by remember { mutableStateOf<String?>(null) }
    val emptyResponseMessage = stringResource(MR.strings.member_error_empty_response)
    val createAgentFailed = stringResource(MR.strings.member_error_create_agent)
    val createAgentForbidden = stringResource(MR.strings.member_error_create_agent_forbidden)
    val createAgentFormRefreshed = stringResource(MR.strings.agent_create_error_form_refreshed)
    val createAgentNameTaken = stringResource(MR.strings.agent_create_error_name_taken)
    val createAgentFormRefreshFailed = stringResource(MR.strings.agent_create_error_form_refresh_failed)
    val sendInvitesFailed = stringResource(MR.strings.member_error_send_invites)
    val sendInvitesForbidden = stringResource(MR.strings.member_error_send_invites_forbidden)
    val canInviteHumans = payload.canInviteHumans && inviteHumanSubmit != null
    LaunchedEffect(canInviteHumans) {
        if (!canInviteHumans) {
            activeInviteHuman = false
            inviteHumanError = null
        }
    }
    val showPullToRefreshItem = slockPlatformSupportsKuiklyPullToRefreshItem()
    val lazyItemCount =
        membersLazyItemCount(
            agents = agents,
            humansCount = humans.size,
            agentsExpanded = agentsExpanded,
            humansExpanded = humansExpanded,
            collapsedAgentGroupKeys =
                groupedAgentMembers(agents)
                    .mapNotNull { group -> group.key.takeIf { isAgentGroupCollapsed(it) } }
                    .toSet(),
            showHumanDirectory = payload.showHumanDirectory,
            hasPullToRefreshItem = showPullToRefreshItem
        )

    val listState =
        rememberLazyListState(
            initialFirstVisibleItemIndex = initialListState.listPosition.firstVisibleItemIndex,
            initialFirstVisibleItemScrollOffset = initialListState.listPosition.firstVisibleItemScrollOffset
        )
    val scrollToTopRequestFence = remember { AppShellScrollToTopRequestFence(scrollToTopRequest) }
    LaunchedEffect(scrollToTopRequest) {
        if (scrollToTopRequestFence.consume(scrollToTopRequest)) {
            listState.animateScrollToTopExpressive()
        }
    }
    val latestAgentsExpanded by rememberUpdatedState(agentsExpanded)
    val latestHumansExpanded by rememberUpdatedState(humansExpanded)
    val latestOnListStateSnapshot by rememberUpdatedState(onListStateSnapshot)
    DisposableEffect(listState) {
        onDispose {
            latestOnListStateSnapshot(
                AppShellMembersListState(
                    listPosition = listState.toAppShellLazyListPosition(),
                    agentsExpanded = latestAgentsExpanded,
                    humansExpanded = latestHumansExpanded
                )
            )
        }
    }
    var pullRefreshActive by remember(payload.instanceId) { mutableStateOf(false) }
    LaunchedEffect(refreshing) {
        if (!refreshing) {
            pullRefreshActive = false
        }
    }
    val pullRefreshing = refreshing && pullRefreshActive
    val pullToRefreshState = rememberPullToRefreshState(pullRefreshing)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SlockColor.SurfaceWhite)
    ) {
        MembersPageHeader(title = displayTitle, refreshing = refreshing, onBack = onBack)
        BoxWithConstraints(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.TopCenter,
        ) {
        val lazyMaxWidth = maxWidth
        LazyColumn(
            state = listState,
            // React parity: the web members view pads the scroll container itself (Sidebar
            // sidebarScrollClassName `px-2`) on top of the per-row/section `px-2`, totalling 16px.
            // Rows/headers here carry MembersListHorizontalInset (8dp), so the container level was
            // the missing half — without it the list sits 8dp from the screen edge (task #140).
            modifier = Modifier
                .widthIn(max = membersListContentMaxWidth(lazyMaxWidth, paneLayout))
                .fillMaxSize()
                .padding(horizontal = MembersListContainerInset),
            verticalArrangement = Arrangement.spacedBy(MembersListRowSpacing),
            beyondBoundsItemCount = sharedListBeyondBoundsItemCount(lazyItemCount)
        ) {
            if (showPullToRefreshItem) {
                slockRaftPullToRefreshItem(
                    state = pullToRefreshState,
                    scrollState = listState,
                    onRefresh = {
                        pullRefreshActive = true
                        onRefresh()
                    }
                )
            }
            item { Spacer(Modifier.height(SlockSpacing.Md)) }
            item {
                SlockCollapsibleSectionHeader(
                    label = displayAgentsLabel,
                    count = agents.size,
                    expanded = agentsExpanded,
                    onExpandedChange = { agentsExpanded = it },
                    modifier = Modifier.padding(horizontal = MembersListHorizontalInset),
                    minHeight = MembersListSectionHeaderMinHeight,
                    trailingContent = {
                        if (canCreateAgents) {
                            SlockSectionHeaderIconAction(
                                icon = SlockLucideIconName.Plus,
                                contentDescription = stringResource(MR.strings.agent_create_action_create),
                                size = MembersListSectionAddActionSize,
                                iconSize = MembersListSectionAddActionIconSize,
                                onClick2 = { anchor ->
                                    if (createAgentSubmit != null) {
                                        createAgentMenuAnchor = anchor
                                        createAgentMenuOpen = true
                                    }
                                }
                            )
                        }
                    }
                )
            }
            if (agentsExpanded) {
                groupedAgentMembers(agents) { machineId ->
                    createAgentMachines.firstOrNull { it.id == machineId }?.name
                }.forEach { group ->
                    item("group_${group.key}") {
                        val groupExpanded = !isAgentGroupCollapsed(group.key)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = MembersListMachineLabelInset)
                                .padding(top = SlockSpacing.Xs)
                                .clickable {
                                    // Slot stores *collapsed*; toggling writes !collapsed
                                    // (writing !groupExpanded stored the old value and made the
                                    // row a no-op — task #409 regression).
                                    val collapseNow =
                                        resolveAgentGroupCollapseToggle(
                                            isAgentGroupCollapsed(group.key)
                                        )
                                    agentGroupCollapseOverrides =
                                        agentGroupCollapseOverrides + (group.key to collapseNow)
                                    agentGroupCollapseStore?.setCollapsed(
                                        membersCurrentUserId,
                                        group.key,
                                        collapseNow
                                    )
                                },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
                        ) {
                            SlockLucideIcon(
                                SlockLucideIconName.ChevronRight,
                                modifier = Modifier.size(MembersListMachineLabelIconSize),
                                color = SlockColor.TextMutedGrayArgb,
                                rotationDegrees = if (groupExpanded) 90f else 0f,
                                rotationAnimationDurationSeconds =
                                    SlockCollapsibleSectionTokens.AnimationDurationSeconds
                            )
                            SlockLucideIcon(
                                SlockLucideIconName.Monitor,
                                modifier = Modifier.size(MembersListMachineLabelIconSize),
                                color = SlockColor.TextMutedGrayArgb
                            )
                            Text(
                                text = membersListMachineLabel(group.label),
                                style = MembersListMachineLabelTextStyle,
                                color = SlockColor.TextMutedGray,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Text(
                                text = group.members.size.toString(),
                                style = MembersListMachineLabelTextStyle,
                                color = SlockColor.TextMutedGray
                            )
                        }
                    }
                    if (!isAgentGroupCollapsed(group.key)) {
                        items(group.members.size, key = { "agent_${group.members[it].id.value}" }) { index ->
                            val member = group.members[index]
                            MembersListRow(
                                member = member,
                            selected = member.matchesMemberSelection(selectedIdentity, selectedLocalServerId),
                                agentStatusResolver = agentStatusResolver,
                                memberAvatarResolver = memberAvatarResolver,
                                modifier = Modifier.padding(horizontal = MembersListHorizontalInset),
                                onClick = { onOpenMember(member) }
                            )
                        }
                    }
                }
            }
            if (payload.showHumanDirectory) {
                item {
                    Spacer(Modifier.height(SlockSpacing.Sm))
                    SlockCollapsibleSectionHeader(
                        label = displayHumansLabel,
                        count = humans.size,
                        expanded = humansExpanded,
                        onExpandedChange = { humansExpanded = it },
                        modifier = Modifier.padding(horizontal = MembersListHorizontalInset),
                        minHeight = MembersListSectionHeaderMinHeight,
                        trailingContent = {
                            if (canInviteHumans) {
                                SlockSectionHeaderIconAction(
                                    icon = SlockLucideIconName.Plus,
                                    contentDescription = stringResource(MR.strings.member_action_invite_human),
                                    size = MembersListSectionAddActionSize,
                                    iconSize = MembersListSectionAddActionIconSize,
                                    onClick = { activeInviteHuman = true }
                                )
                            }
                        }
                    )
                }
                if (humansExpanded) {
                    items(humans.size, key = { "human_${humans[it].id.value}" }) { index ->
                        val member = humans[index]
                        MembersListRow(
                            member = member,
                            selected = member.matchesMemberSelection(selectedIdentity, selectedLocalServerId),
                            agentStatusResolver = agentStatusResolver,
                            memberAvatarResolver = memberAvatarResolver,
                            modifier = Modifier.padding(horizontal = MembersListHorizontalInset),
                            onClick = { onOpenMember(member) }
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(bottomContentPadding)) }
        }
        }
    }
    if (createAgentMenuOpen && createAgentMenuAnchor != null && createAgentSubmit != null) {
        SlockRaftUiControlledMenu(
            expanded = true,
            anchor = createAgentMenuAnchor!!,
            width = 220.dp,
            items = listOf(
                SlockRaftUiMenuItem(
                    id = MembersCreateAgentMode.Managed.name,
                    label = stringResource(MR.strings.agent_create_action_create),
                    icon = SlockLucideIconName.Bot
                ),
                SlockRaftUiMenuItem(
                    id = MembersCreateAgentMode.External.name,
                    label = stringResource(MR.strings.agent_create_action_create_external),
                    icon = SlockLucideIconName.Link2
                )
            ),
            onSelect = { id ->
                createAgentMenuOpen = false
                createAgentMenuAnchor = null
                MembersCreateAgentMode.entries.firstOrNull { it.name == id }?.let { mode ->
                    activeCreateAgentMode = mode
                    if (mode.requiresComputerSelection()) createAgentLoadMachines()
                }
            },
            onDismiss = {
                createAgentMenuOpen = false
                createAgentMenuAnchor = null
            }
        )
    }
    val submit: (suspend (SharedCreateAgentDialogSubmit) -> DataResult<Unit>)? = createAgentSubmit
    val activeCreateAgent = activeCreateAgentMode != null
    if (activeCreateAgent && submit != null) {
        val scope = rememberCoroutineScope()
        SharedCreateAgentDialog(
            external = activeCreateAgentMode == MembersCreateAgentMode.External,
            submitting = createAgentSubmitting,
            errorMessage = createAgentError,
            errorFieldKeys = createAgentErrorFields,
            machines = createAgentMachines,
            machinesLoading = createAgentMachinesLoading,
            machinesErrorMessage = createAgentMachinesErrorMessage,
            onMachinesRetry = createAgentRetryMachines,
            runtimeModelsByMachineAndRuntime = createAgentRuntimeModelsByMachineAndRuntime,
            runtimeModelLoadsInFlight = createAgentRuntimeModelLoadsInFlight,
            onRuntimeModelsRequest = createAgentLoadRuntimeModels,
            createAgentFormStore = createAgentFormStore,
            createAgentServerId = createAgentServerId,
            takenAgentNames = createAgentTakenAgentNames,
            currentAgentCount = createAgentCurrentAgentCount,
            maxAgents = createAgentMaxAgents,
            onDismiss = {
                if (!createAgentSubmitting) {
                    activeCreateAgentMode = null
                    createAgentError = null
                }
            },
            onSubmit = { request ->
                createAgentSubmitting = true
                createAgentError = null
                createAgentErrorFields = emptySet()
                scope.launch {
                    val result = submit(request)
                    createAgentSubmitting = false
                    createAgentErrorFields = (result as? DataResult.Error)?.error?.formFieldKeys.orEmpty().toSet()
                    when (result) {
                        is DataResult.Content -> {
                            activeCreateAgentMode = null
                            createAgentError = null
                        }
                        is DataResult.Empty -> {
                            createAgentError = emptyResponseMessage
                        }
                        is DataResult.Error -> {
                            createAgentError =
                                when (result.error.code) {
                                    AGENT_CREATE_NAME_TAKEN ->
                                        createAgentNameTaken
                                    AGENT_CREATE_FORM_REFRESH_REQUIRED ->
                                        createAgentFormRefreshed
                                    AGENT_CREATE_FORM_REFRESH_FAILED ->
                                        createAgentFormRefreshFailed
                                    else -> createAgentFallbackErrorText(result.error, createAgentFailed)
                                }
                        }
                        is DataResult.NoPermission -> {
                            createAgentError = createAgentForbidden
                        }
                    }
                }
            }
        )
    }
    val inviteSubmit: (suspend (List<String>) -> DataResult<Unit>)? = inviteHumanSubmit
    if (activeInviteHuman && inviteSubmit != null) {
        val scope = rememberCoroutineScope()
        // Load the join link when the dialog opens. Cheap: one GET (and at
        // most one POST if the list is empty), both gated on the user's
        // manage-invite permission via the data source.
        LaunchedEffect(Unit) {
            if (inviteHumanJoinLinkUrl == null && !inviteHumanJoinLinkLoading) {
                inviteHumanLoadJoinLink()
            }
        }
        SharedInviteHumanDialog(
            submitting = inviteHumanSubmitting,
            errorMessage = inviteHumanError,
            joinLinkLoading = inviteHumanJoinLinkLoading,
            joinLinkUrl = inviteHumanJoinLinkUrl,
            copied = inviteHumanJoinLinkCopied,
            onCopyJoinLink = inviteHumanJoinLinkCopy,
            onDismiss = {
                if (!inviteHumanSubmitting) {
                    activeInviteHuman = false
                    inviteHumanError = null
                }
            },
            onSubmit = { emails ->
                inviteHumanSubmitting = true
                inviteHumanError = null
                scope.launch {
                    val result = inviteSubmit(emails)
                    inviteHumanSubmitting = false
                    when (result) {
                        is DataResult.Content -> {
                            activeInviteHuman = false
                            inviteHumanError = null
                        }
                        is DataResult.Empty -> {
                            inviteHumanError = emptyResponseMessage
                        }
                        is DataResult.Error -> {
                            inviteHumanError = "$sendInvitesFailed: ${result.error.userFacingMessage(errorStrings)}"
                        }
                        is DataResult.NoPermission -> {
                            inviteHumanError = sendInvitesForbidden
                        }
                    }
                }
            }
        )
    }
}

/**
 * Task #409: a group's collapsed state is the user's tap this page session if
 * they made one, otherwise the persisted value under `userId`. Resolving per
 * group (rather than snapshotting the stored set once) keeps groups that
 * arrive after the first read — the standalone members page fills its list
 * asynchronously — from silently rendering expanded.
 */
/**
 * The collapsed value a machine-group tap should write: the opposite of the
 * currently resolved collapsed state. Kept as a named pure function because a
 * previous implementation wrote the non-inverted state, which made taps a
 * no-op (task #409 regression).
 */
internal fun resolveAgentGroupCollapseToggle(currentlyCollapsed: Boolean): Boolean =
    !currentlyCollapsed

internal fun resolveAgentGroupCollapsed(
    groupKey: String,
    overrides: Map<String, Boolean>,
    userId: String,
    store: build.raft.app.data.MembersAgentMachineGroupCollapseStore?
): Boolean {
    overrides[groupKey]?.let { return it }
    if (userId.isBlank() || store == null) return false
    return store.isCollapsed(userId, groupKey)
}

internal fun membersLazyItemCount(
    agents: List<MemberProfile>,
    humansCount: Int,
    agentsExpanded: Boolean,
    humansExpanded: Boolean,
    collapsedAgentGroupKeys: Set<String> = emptySet(),
    showHumanDirectory: Boolean = true,
    hasPullToRefreshItem: Boolean = true
): Int {
    val fixedItems =
        (if (hasPullToRefreshItem) 1 else 0) +
            1 + // top spacer
            1 + // agents header
            (if (showHumanDirectory) 1 else 0) + // humans header
            1 // bottom spacer
    val agentItems =
        if (agentsExpanded) {
            groupedAgentMembers(agents).sumOf { group ->
                // Task #409: a collapsed machine group still renders its header
                // row (computer name + count) but none of its member rows.
                if (collapsedAgentGroupKeys.contains(group.key)) 1 else 1 + group.members.size
            }
        } else {
            0
        }
    return fixedItems + agentItems + (if (showHumanDirectory && humansExpanded) humansCount else 0)
}

/**
 * Parameter holders for [MembersDetailContent] (task #1130): the composable took 53 parameters,
 * past the same 31-per-int `$changed` / `$default` mask boundary as [MembersAgentProfileTab].
 * Same shape and same caveats as [MembersAgentRuntimeModelInputs]: plain unstable classes,
 * behaviour unchanged, no `@Stable` / `@Immutable` later.
 */
private class MembersHumanRoleInputs(
    val humanActorRole: String? = null,
    val humanOwnerCount: Int? = null,
    val humanAccessMode: MemberDetailAccessMode = MemberDetailAccessMode.LocalFull,
    val onUpdateHumanRole: (suspend (String, MembersHumanServerRole) -> DataResult<SettingsServerActionResult>)? = null
)

private class MembersAgentDetailCallbacks(
    val onUpdateAgentProfile: (suspend (AgentProfileUpdateRequest) -> DataResult<MemberProfile>)?,
    val onApplyAgentProfileResult: (MemberProfile) -> Unit = {},
    val onApplyAgentRuntimeConfigProjection:
        suspend (MembersAgentProfileEditAuthority, RuntimeConfig) -> Boolean = { _, _ -> false },
    val onUploadAgentAvatar: (suspend (MemberId, PlatformFile) -> DataResult<MemberProfile>)?,
    val onPickAgentAvatarFile: suspend () -> DataResult<PlatformFile> = { DataResult.Empty(EmptyReason.NoMembers) },
    val onAgentLifecycleAction: (suspend (MemberId, MembersAgentLifecycleAction, AgentArchiveWorkspaceChoice) -> DataResult<AgentLifecycleActionResult>)?,
    val agentWorkspaceTreeLoader: (suspend (MemberId) -> DataResult<AgentWorkspaceTreeSnapshot>)?,
    val agentWorkspaceChildrenLoader: (suspend (MemberId, String) -> DataResult<AgentWorkspaceChildrenSnapshot>)?,
    val onCopyAgentDiagnostics: (String) -> Unit,
    val onCopyAgentIssueReference: (String) -> Unit = onCopyAgentDiagnostics,
    val onReportAgentIssue: (suspend (SharedAgentIssueReportRequest) -> DataResult<AgentIssueReportResult>)?
)

private class MembersDetailActions(
    val onBack: () -> Unit,
    val onOpenDirectMessage: () -> Unit,
    val onOpenComputer: (() -> Unit)? = null,
    val onCopyMemberId: () -> Unit,
    val onRemoveMember: (() -> Unit)? = null,
    val onOpenUrl: (String) -> Unit,
    val onOpenReminderMessage: (String) -> Unit = {},
    val onUnavailableAction: (String) -> Unit
)

private class MembersAgentDetailPresentation(
    val pageVisibleTick: Int = 0,
    val initialVisualMode: MembersAgentDetailVisualMode = MembersAgentDetailVisualMode.Profile,
    val initialAgentTab: MembersAgentDetailTab? = null,
    val agentPanelTabOrder: List<String> = emptyList(),
    val onAgentPanelTabOrderChange: ((List<MembersAgentDetailTab>) -> Unit)? = null,
    val initialAgentScopeSet: AgentScopeSet? = null,
    val stableVisualCapture: Boolean = false
)

@Composable
private fun MembersDetailContent(
    member: MemberProfile,
    currentUserId: String? = null,
    currentServerId: String? = null,
    serverMembersState: SharedServerMembersState = SharedServerMembersState(),
    agentDetailState: MembersAgentDetailLoadState,
    agentActivityLoadState: MembersAgentActivityLoadState = MembersAgentActivityLoadState.Idle,
    runtimeModels: MembersAgentRuntimeModelInputs = MembersAgentRuntimeModelInputs(),
    createAgentFormStore: SharedCreateAgentFormStore? = null,
    agentProfileEditAuthority: MembersAgentProfileEditAuthority? = null,
    canViewPrivateAgentSurfaces: (MemberProfile, AgentDetailSnapshot?) -> Boolean,
    canManageAgent: (MemberProfile, AgentDetailSnapshot?) -> Boolean,
    /** Task #259: resolves the machine status row from the computers store. No default on purpose -- a silent fallback here is the #253 shape. */
    machineStatusRow: (machineId: String?) -> MembersMachineStatusRow,
    actions: MembersDetailActions,
    humanRole: MembersHumanRoleInputs = MembersHumanRoleInputs(),
    agentDetailActions: SharedAgentDetailActions? = null,
    agentCallbacks: MembersAgentDetailCallbacks,
    timeContext: SlockTimeContext,
    sharedAgentDetailStore: SharedAgentDetailStore? = null,
    presentation: MembersAgentDetailPresentation = MembersAgentDetailPresentation(),
    accessMode: MemberDetailAccessMode = MemberDetailAccessMode.LocalFull,
    agentStatusResolver: SlockAgentStatusResolver = EmptySlockAgentStatusResolver,
    memberAvatarResolver: SlockMemberAvatarResolver = EmptySlockMemberAvatarResolver,
    // #89: forwarded to the Permissions tab so it can call
    // `agentScopes` / `updateAgentScopes` / `resetAgentScopes`. When null
    // the Permissions tab renders an "Agent API not available" error banner.
    agentsApiClient: build.raft.app.data.SlockAgentsApiClient? = null,
    externalSetupServerUrl: String? = null
) {
    val agentMachineRuntimes = runtimeModels.agentMachineRuntimes
    val agentRuntimeModelsByRuntime = runtimeModels.agentRuntimeModelsByRuntime
    val agentRuntimeModelLoadsInFlight = runtimeModels.agentRuntimeModelLoadsInFlight
    val agentRuntimeModelErrorsByRuntime = runtimeModels.agentRuntimeModelErrorsByRuntime
    val agentRuntimeModelGenerationsByRuntime = runtimeModels.agentRuntimeModelGenerationsByRuntime
    val onAgentRuntimeModelsRequest = runtimeModels.onAgentRuntimeModelsRequest
    val humanActorRole = humanRole.humanActorRole
    val humanOwnerCount = humanRole.humanOwnerCount
    val humanAccessMode = humanRole.humanAccessMode
    val onUpdateHumanRole = humanRole.onUpdateHumanRole
    val onUpdateAgentProfile = agentCallbacks.onUpdateAgentProfile
    val onApplyAgentProfileResult = agentCallbacks.onApplyAgentProfileResult
    val onApplyAgentRuntimeConfigProjection = agentCallbacks.onApplyAgentRuntimeConfigProjection
    val onUploadAgentAvatar = agentCallbacks.onUploadAgentAvatar
    val onPickAgentAvatarFile = agentCallbacks.onPickAgentAvatarFile
    val onAgentLifecycleAction = agentCallbacks.onAgentLifecycleAction
    val agentWorkspaceTreeLoader = agentCallbacks.agentWorkspaceTreeLoader
    val agentWorkspaceChildrenLoader = agentCallbacks.agentWorkspaceChildrenLoader
    val onCopyAgentDiagnostics = agentCallbacks.onCopyAgentDiagnostics
    val onCopyAgentIssueReference = agentCallbacks.onCopyAgentIssueReference
    val onReportAgentIssue = agentCallbacks.onReportAgentIssue
    val onBack = actions.onBack
    val onOpenDirectMessage = actions.onOpenDirectMessage
    val onOpenComputer = actions.onOpenComputer
    val onCopyMemberId = actions.onCopyMemberId
    val onRemoveMember = actions.onRemoveMember
    val onOpenUrl = actions.onOpenUrl
    val onOpenReminderMessage = actions.onOpenReminderMessage
    val onUnavailableAction = actions.onUnavailableAction
    val pageVisibleTick = presentation.pageVisibleTick
    val initialVisualMode = presentation.initialVisualMode
    val initialAgentTab = presentation.initialAgentTab
    val agentPanelTabOrder = presentation.agentPanelTabOrder
    val onAgentPanelTabOrderChange = presentation.onAgentPanelTabOrderChange
    val initialAgentScopeSet = presentation.initialAgentScopeSet
    val stableVisualCapture = presentation.stableVisualCapture
    val errorStrings = localizedErrorPresentationStrings()
    val detail = (agentDetailState as? MembersAgentDetailLoadState.Content)?.detail
    val managementUnavailable = stringResource(MR.strings.member_error_action_unavailable)
    val issueReportingUnavailable = stringResource(MR.strings.member_error_issue_unavailable)
    val avatarEditingUnavailable = stringResource(MR.strings.member_error_avatar_edit_unavailable)
    val avatarUnchanged = stringResource(MR.strings.member_error_avatar_unchanged)
    val permissionErrorTemplate = membersStringTemplate(MR.strings.member_error_permission)
    val avatarSaveErrorTemplate = membersStringTemplate(MR.strings.member_error_avatar_save)
    val diagnosticsCopied = stringResource(MR.strings.member_action_diagnostics_copied)
    val issueReported = stringResource(MR.strings.member_action_issue_reported)
    val deleted = detail?.summary?.deletedAt?.isNotBlank() == true
    // task #256: observed-or-null drives the dot. The label-facing resolution below may
    // substitute the unknown placeholder, which is grey and would impersonate Offline.
    val managedAgentStatusObservedState =
        if (member.kind == MemberKind.Agent) {
            val canonicalState = rememberSlockAgentStatusIndicatorState(agentStatusResolver, member.id.value)
            resolveMembersAgentStatusIndicatorStateOrNull(
                canonicalState = canonicalState,
                fixtureFallback =
                    if (stableVisualCapture) {
                        member.toFixtureSlockAgentStatusIndicatorState(
                            summaryStatus = detail?.summary?.status,
                            summaryActivity = detail?.summary?.activity
                        )
                    } else {
                        null
                    },
                fixtureStatusFallbackEnabled = stableVisualCapture
            )
        } else {
            null
        }
    val managedAgentStatusIndicatorState =
        if (member.kind == MemberKind.Agent) {
            managedAgentStatusObservedState ?: unknownMembersAgentStatusIndicatorState()
        } else {
            null
        }
    val isExternalAgent =
        member.kind == MemberKind.Agent && (member.isExternal || detail?.summary?.isExternal == true)
    // task #1119 PR 2: the detail page (and only the detail page) reads the
    // external agent's setup/activity status — React AgentDetailPanel parity.
    val agentStatusIndicatorState =
        if (isExternalAgent) {
            rememberExternalAgentDetailIndicatorState(detail = detail, timeContext = timeContext)
        } else {
            managedAgentStatusIndicatorState
        }
    // task #256 x #1119: the no-manufactured-dot rule covers the MANAGED branch only. An
    // external agent's tiers are cyan/green — never StatusNeutralGray — so that state is an
    // observation in its own right and must keep painting its dot. Suppressing it here would
    // silently undo #1119's setup/activity tiers, which is exactly what resolving this
    // conflict in favour of either side alone would have done.
    val agentStatusObservedState =
        if (isExternalAgent) agentStatusIndicatorState else managedAgentStatusObservedState
    val canViewPrivate = member.kind == MemberKind.Agent && canViewPrivateAgentSurfaces(member, detail)
    val canManage = member.kind == MemberKind.Agent && !deleted && canManageAgent(member, detail)
    val scope = rememberCoroutineScope()
    val pager = LocalActivity.current.getPager() as Pager
    val fallbackAgentDetailStore = remember { SharedAgentDetailStore() }
    val storeState by (sharedAgentDetailStore ?: fallbackAgentDetailStore).state.collectAsState()
    var actionState by remember(member.id.value, initialVisualMode) {
        mutableStateOf<MembersAgentActionState?>(null)
    }
    var diagnosticActionState by remember(member.id.value) { mutableStateOf<MembersAgentDiagnosticActionState?>(null) }
    var pendingConfirmation by remember(member.id.value) {
        mutableStateOf<MembersAgentLifecycleAction?>(null)
    }
    var restartResetDialogOpen by remember(member.id.value, initialVisualMode) {
        mutableStateOf(false)
    }
    var reportIssueDialogOpen by remember(member.id.value) { mutableStateOf(false) }
    var issueReportReceipt by remember(member.id.value) { mutableStateOf<AgentIssueReportResult?>(null) }
    var issueReportReferenceCopied by remember(member.id.value) { mutableStateOf(false) }
    var restartResetSelection by remember(member.id.value) {
        mutableStateOf(MembersAgentLifecycleAction.Restart)
    }
    // Mirrors React AgentDetailPanel: the active tab is derived — an explicit
    // selection (deep link or user tap) wins only while visible; otherwise the
    // first server-ordered tab is the default.
    var selectedTabOverride by remember(member.id.value, initialAgentTab) {
        mutableStateOf(initialAgentTab)
    }
    // task #232 PR2 (@LiBai review): each tab's list scroll position lives here,
    // keyed by TAB rather than by pager index so it survives both a swipe away
    // and a tab reorder. Inside the page the LazyColumn is discarded whenever
    // the page is not the selected one, so a state remembered there would
    // reset to the top on every return — the Activity caller hoists its
    // per-page states for the same reason (SharedInboxPanel pageListStates).
    // rememberLazyListState (saveable, like Activity's) rather than a bare
    // LazyListState so rotation keeps the position too; key() re-creates the
    // set when the detail switches member, so one member's scroll offsets
    // never show up on another (same pattern as ThreadPage's listState). The
    // map covers the FULL enum, so getValue below is total whatever the
    // visible-tab set is.
    val agentTabListStates = key(member.id.value) {
        MembersAgentDetailTab.entries.associateWith { rememberLazyListState() }
    }
    var managementMenuOpen by remember(member.id.value, initialVisualMode) { mutableStateOf(false) }
    val storeLifecycleInFlight: String? = storeState.lifecycleActionInFlight
    val storeDiagnosticInFlight: String? = storeState.diagnosticActionInFlight
    val lifecycleInFlight: String? = if (sharedAgentDetailStore != null) {
        storeLifecycleInFlight
    } else {
        actionState?.takeIf { it.inProgress }?.action?.actionKey
    }
    val diagnosticInFlight: String? = if (sharedAgentDetailStore != null) {
        storeDiagnosticInFlight
    } else {
        diagnosticActionState?.takeIf { it.inProgress }?.actionKey
    }
    val storePendingConfirmation: MembersAgentLifecycleAction? =
        storeState.pendingConfirmation.takeIf { sharedAgentDetailStore != null }
    val effectiveDiagnosticActionState =
        if (sharedAgentDetailStore != null) {
            storeState.diagnosticActionInFlight?.let {
                MembersAgentDiagnosticActionState(actionKey = it, inProgress = true)
            } ?: storeState.lastActionResult
                ?.takeIf { it.actionKey == "copy" || it.actionKey == "report" }
                ?.let {
                    MembersAgentDiagnosticActionState(
                        actionKey = it.actionKey,
                        inProgress = false,
                        error = it.error,
                        successLabel = it.successLabel
                    )
                }
        } else {
            diagnosticActionState
        }
    var managementMenuAnchor by remember(member.id.value) { mutableStateOf<SlockPopoverAnchor?>(null) }
    var managementMenuAnchorResolver by remember(member.id.value) {
        mutableStateOf<SlockPopoverAnchorResolver?>(null)
    }
    var showAvatarPicker by remember(member.id.value, initialVisualMode) { mutableStateOf(false) }
    var workspaceTreeState by remember(member.id.value) {
        mutableStateOf<MembersAgentWorkspaceTreeState>(MembersAgentWorkspaceTreeState.Idle)
    }
    var expandedWorkspaceDirs by remember(member.id.value) { mutableStateOf<Set<String>>(emptySet()) }
    var loadedWorkspaceDirs by remember(member.id.value) { mutableStateOf<Map<String, List<SlockWorkspaceNode>>>(emptyMap()) }
    var loadingWorkspaceDirs by remember(member.id.value) { mutableStateOf<Set<String>>(emptySet()) }
    var workspaceDirErrors by remember(member.id.value) { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(member.id.value, initialVisualMode) {
        when (initialVisualMode) {
            MembersAgentDetailVisualMode.LifecycleActions -> {
                Unit
            }
            MembersAgentDetailVisualMode.AvatarManagement -> {
                showAvatarPicker = true
            }
            MembersAgentDetailVisualMode.ReportIssue -> {
                reportIssueDialogOpen = true
            }
            MembersAgentDetailVisualMode.ReportIssueSuccess -> {
                issueReportReceipt =
                    AgentIssueReportResult(
                        reportId = "7e91c120-f424-42d7-96e5-21eb816866d0",
                        artifactId = "95d629bd-3ed7-42ff-97eb-3ed35c6dc6ab",
                        serverId = "65598229-cd0a-4e92-96f3-c33e7e2e4cc1",
                        issueDescription = "The agent stopped responding after a long-running command.",
                        transcriptRequested = true
                    )
                issueReportReferenceCopied = false
            }
            MembersAgentDetailVisualMode.Profile -> Unit
        }
    }
    LaunchedEffect(member.id.value, initialVisualMode, managementMenuAnchorResolver) {
        if (initialVisualMode == MembersAgentDetailVisualMode.LifecycleActions) {
            managementMenuAnchorResolver?.resolve()?.let { anchor ->
                managementMenuAnchor = anchor
                managementMenuOpen = true
            }
        }
    }
    val visibleTabs = remember(accessMode, canViewPrivate, deleted, member.kind, agentPanelTabOrder) {
        if (member.kind == MemberKind.Agent) {
            membersOrderedAgentTabs(
                visibleTabs =
                    membersAgentDetailTabsForAccess(
                        accessMode = accessMode,
                        canViewPrivateSurfaces = canViewPrivate,
                        deleted = deleted
                    ),
                storedOrder = agentPanelTabOrder
            )
        } else {
            listOf(MembersAgentDetailTab.Profile)
        }
    }
    val selectedTab = selectedTabOverride?.takeIf { it in visibleTabs }
        ?: visibleTabs.firstOrNull()
        ?: MembersAgentDetailTab.Profile
    var visitedAgentTabs by remember(member.id.value) {
        mutableStateOf(setOf(selectedTab))
    }
    if (selectedTab !in visitedAgentTabs) {
        visitedAgentTabs = visitedAgentTabs + selectedTab
    }
    fun loadWorkspaceRoot() {
        val callback = agentWorkspaceTreeLoader
        if (callback == null) {
            workspaceTreeState = MembersAgentWorkspaceTreeState.Error(ScreenError("workspace_loader_unavailable"))
            return
        }
        scope.launch {
            workspaceTreeState = MembersAgentWorkspaceTreeState.Loading
            expandedWorkspaceDirs = emptySet()
            loadedWorkspaceDirs = emptyMap()
            loadingWorkspaceDirs = emptySet()
            workspaceDirErrors = emptyMap()
            workspaceTreeState =
                try {
                    callback(member.id).toMembersWorkspaceTreeState()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    MembersAgentWorkspaceTreeState.Error(ScreenError("workspace_root_failed"))
                }
        }
    }
    fun toggleWorkspaceDirectory(path: String) {
        val expanded = path in expandedWorkspaceDirs
        expandedWorkspaceDirs =
            if (expanded) {
                expandedWorkspaceDirs - path
            } else {
                expandedWorkspaceDirs + path
            }
        if (!expanded && path !in loadedWorkspaceDirs && path !in loadingWorkspaceDirs) {
            val callback = agentWorkspaceChildrenLoader
            if (callback == null) {
                workspaceDirErrors = workspaceDirErrors +
                    (path to "workspace_children_unavailable".userFacingErrorCodeMessage(errorStrings))
                return
            }
            scope.launch {
                loadingWorkspaceDirs = loadingWorkspaceDirs + path
                workspaceDirErrors = workspaceDirErrors - path
                when (
                    val result =
                        try {
                            callback(member.id, path)
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (_: Throwable) {
                            DataResult.Error(ScreenError("workspace_children_failed"))
                        }
                ) {
                    is DataResult.Content ->
                        loadedWorkspaceDirs = loadedWorkspaceDirs + (path to result.data.children)
                    is DataResult.Empty ->
                        loadedWorkspaceDirs = loadedWorkspaceDirs + (path to emptyList())
                    is DataResult.NoPermission ->
                        workspaceDirErrors = workspaceDirErrors +
                            (path to result.blocker.userFacingMessage(errorStrings))
                    is DataResult.Error ->
                        workspaceDirErrors = workspaceDirErrors +
                            (path to result.error.userFacingMessage(errorStrings))
                }
                loadingWorkspaceDirs = loadingWorkspaceDirs - path
            }
        }
    }
    fun selectWorkspaceFile(path: String) {
        pager.openSlockFilePreviewPage(agentWorkspaceLazyPreviewPayload(agentId = member.id.value, path = path))
    }
    LaunchedEffect(member.id.value, selectedTab, canViewPrivate, agentWorkspaceTreeLoader) {
        if (
            selectedTab == MembersAgentDetailTab.Workspace &&
            canViewPrivate &&
            workspaceTreeState is MembersAgentWorkspaceTreeState.Idle
        ) {
            loadWorkspaceRoot()
        }
    }
    fun runLifecycleAction(
        action: MembersAgentLifecycleAction,
        confirmed: Boolean = false,
        workspaceChoice: AgentArchiveWorkspaceChoice = AgentArchiveWorkspaceChoice.Keep
    ) {
        if (lifecycleInFlight != null) return
        val callback = onAgentLifecycleAction
        if (!canManage || (agentDetailActions == null && callback == null)) {
            onUnavailableAction(managementUnavailable)
            return
        }
        if (action.requiresConfirmation && !confirmed) {
            actionState = null
            if (sharedAgentDetailStore != null) {
                sharedAgentDetailStore.dispatch(SharedAgentDetailIntent.ShowConfirmation(action))
            } else {
                pendingConfirmation = action
            }
            return
        }
        scope.launch {
            pendingConfirmation = null
            actionState = MembersAgentActionState(action, inProgress = true)
            val result =
                if (sharedAgentDetailStore != null && agentDetailActions != null) {
                    sharedAgentDetailStore.runLifecycleAction(member.id, action, agentDetailActions, workspaceChoice)
                } else {
                    try {
                        requireNotNull(callback).invoke(member.id, action, workspaceChoice)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        DataResult.Error(ScreenError("agent_${action.actionKey}_failed"))
                    }
                }
            val error = result.membersLifecycleError(action)
            if (error == null && action.isRestartResetDialogAction()) {
                restartResetDialogOpen = false
                restartResetSelection = MembersAgentLifecycleAction.Restart
            }
            actionState =
                MembersAgentActionState(
                    action = action,
                    inProgress = false,
                    error = error,
                    successLabel = action.successLabel.takeIf { error == null }
                )
        }
    }
    fun copyDiagnostics() {
        val diagnostics = buildMembersAgentDiagnostics(member, detail, agentStatusIndicatorState)
        if (sharedAgentDetailStore != null && agentDetailActions != null) {
            sharedAgentDetailStore.copyDiagnostics(diagnostics, agentDetailActions)
        } else {
            onCopyAgentDiagnostics(diagnostics)
            diagnosticActionState =
                MembersAgentDiagnosticActionState(
                    actionKey = "copy",
                    inProgress = false,
                    successLabel = diagnosticsCopied
                )
        }
    }
    fun submitIssueReport(
        description: String,
        includeRecentMessages: Boolean,
        includeLiveActivity: Boolean,
        includeActivityHistory: Boolean,
        includeRuntimeTranscript: Boolean
    ) {
        val callback = onReportAgentIssue
        if (diagnosticInFlight != null) return
        if (agentDetailActions == null && callback == null) {
            onUnavailableAction(issueReportingUnavailable)
            return
        }
        scope.launch {
            diagnosticActionState =
                MembersAgentDiagnosticActionState(actionKey = "report", inProgress = true)
            val request =
                SharedAgentIssueReportRequest(
                    description = description.trim(),
                    diagnostics = buildMembersAgentDiagnostics(member, detail, agentStatusIndicatorState),
                    includeRecentMessages = includeRecentMessages,
                    includeLiveActivity = includeLiveActivity,
                    includeActivityHistory = includeActivityHistory,
                    includeRuntimeTranscript = includeRuntimeTranscript,
                    liveActivity = storeState.agentRealtimeActivityLogs[member.id].orEmpty()
                )
            val result =
                if (sharedAgentDetailStore != null && agentDetailActions != null) {
                    sharedAgentDetailStore.reportIssue(request, agentDetailActions)
                } else {
                    try {
                        requireNotNull(callback).invoke(request)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        DataResult.Error(ScreenError("agent_issue_report_failed"))
                    }
                }
            val error =
                when (result) {
                    is DataResult.Content -> null
                    is DataResult.Empty -> ScreenError(result.reason.name, retryable = false)
                    is DataResult.Error -> result.error
                    is DataResult.NoPermission -> ScreenError(result.blocker.code, retryable = false)
                }
            diagnosticActionState =
                MembersAgentDiagnosticActionState(
                    actionKey = "report",
                    inProgress = false,
                    error = error,
                    successLabel = issueReported.takeIf { error == null }
                )
            if (result is DataResult.Content) {
                reportIssueDialogOpen = false
                issueReportReceipt = result.data
                issueReportReferenceCopied = false
            }
        }
    }
    fun openRestartResetDialog() {
        restartResetSelection = MembersAgentLifecycleAction.Restart
        actionState = null
        restartResetDialogOpen = true
    }
    fun saveAvatar(avatarUrl: String?) {
        val callback = onUpdateAgentProfile
        if (!canManage || deleted || callback == null) {
            onUnavailableAction(avatarEditingUnavailable)
            return
        }
        scope.launch {
            val result =
                try {
                    callback(
                        AgentProfileUpdateRequest(
                            agentId = member.id,
                            displayName = member.displayName,
                            description = member.description?.takeIf { it.isNotBlank() },
                            avatarUrl = avatarUrl,
                            includeAvatarUrl = true
                        )
                    )
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    DataResult.Error(ScreenError("agent_avatar_save_failed"))
                }
            when (result) {
                is DataResult.Content -> {
                    showAvatarPicker = false
                }
                is DataResult.Empty -> {
                    onUnavailableAction(avatarUnchanged)
                }
                is DataResult.NoPermission -> {
                    onUnavailableAction(
                        membersFormattedPermissionError(permissionErrorTemplate, result.blocker, errorStrings)
                    )
                }
                is DataResult.Error -> {
                    onUnavailableAction(
                        membersFormattedScreenError(avatarSaveErrorTemplate, result.error, errorStrings)
                    )
                }
            }
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SlockColor.SurfaceWhite)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            val runtimeErrorText = member.membersAgentRuntimeErrorText(detail, agentStatusIndicatorState)
            MembersDetailHeader(
                member = member,
                currentUserId = currentUserId,
                currentServerId = currentServerId,
                detail = detail,
                deleted = deleted,
                canManage = canManage,
                onBack = onBack,
                onAvatarClick = if (canManage && !deleted) ({ showAvatarPicker = true }) else null,
                onOpenDirectMessage = onOpenDirectMessage,
                onCopyMemberId = onCopyMemberId,
                agentStatusIndicatorState = agentStatusIndicatorState,
                memberAvatarResolver = memberAvatarResolver,
                onManagementMenuAnchorResolverReady = { managementMenuAnchorResolver = it },
                onRequestManagementMenu = { anchor ->
                    if (anchor != null) {
                        managementMenuAnchor = anchor
                        managementMenuOpen = true
                    }
                }
            )
            val runtimeErrorBanner =
                membersAgentRuntimeErrorBannerState(
                    memberKind = member.kind,
                    selectedTab = selectedTab,
                    runtimeErrorText = runtimeErrorText
                )
            if (runtimeErrorBanner != null) {
                MembersAgentRuntimeErrorBanner(
                    errorText = runtimeErrorBanner.errorText,
                    canViewPrivate = canViewPrivate,
                    showViewLogs = runtimeErrorBanner.showViewLogs,
                    onViewLogs = { selectedTabOverride = MembersAgentDetailTab.Activity },
                    onCopyInfo = { copyDiagnostics() }
                )
            }
            if (member.kind == MemberKind.Agent) {
                // task #232 PR2: the six agent tabs share TabPager with Activity
                // (PR1, #2224). The tab bar stays the tap surface and selection
                // owner; a horizontal swipe on the body settles on a neighbour
                // tab. Gating matches the Activity caller exactly: a page
                // composes real content only while it is the pager's current
                // page AND the selection has settled on it (`active` is the
                // pager's own current-page flag, and onSelectedIndexChange
                // fires once per settle), so an incoming page shows the
                // loading body for the whole drag, then fills — and a
                // keepItemAlive-hidden page stops composing its tab content
                // instead of keeping its effects alive. Per-tab cost therefore equals the
                // previous single `when (selectedTab)`. State that must survive
                // a swipe is hoisted above: workspace tree, chat tick, profile
                // editing, and (agentTabListStates) each tab's scroll position.
                TabPager(
                    selectedIndex = membersAgentDetailPagerIndex(visibleTabs, selectedTab),
                    onSelectedIndexChange = { index ->
                        membersAgentDetailPageTab(visibleTabs, index)?.let { selectedTabOverride = it }
                    },
                    pageCount = visibleTabs.size.coerceAtLeast(1),
                    gesturePolicy = TabPagerGesturePolicy(
                        startEdgeInset = slockPlatformTabPagerStartEdgeInsetDp().dp,
                    ),
                    keepItemAlive = true,
                    tabBar = {
                        MembersAgentTabBar(
                            tabs = visibleTabs,
                            selectedTab = selectedTab,
                            onSelectedTabChange = { selectedTabOverride = it },
                            onReorderTabs = onAgentPanelTabOrderChange,
                            autoScrollSelectedTab = !stableVisualCapture
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) { page, active ->
                    val pageTab = membersAgentDetailPageTab(visibleTabs, page)
                    if (pageTab == null || !active || !membersAgentDetailShouldComposeTab(pageTab, selectedTab, visitedAgentTabs)) {
                        // SlockCenteredLoading renders common_loading when message
                        // is null — only shown for tabs that have not been visited yet.
                        SlockCenteredLoading(modifier = Modifier.fillMaxSize())
                    } else if (pageTab == MembersAgentDetailTab.Activity) {
                        MembersAgentActivityTab(
                            detail = detail,
                            loadState = agentActivityLoadState,
                            diagnosticActionState = effectiveDiagnosticActionState,
                            onCopyDiagnostics = { copyDiagnostics() },
                            timeContext = timeContext,
                            onOpenUrl = onOpenUrl,
                            autoScrollToLatest = !stableVisualCapture,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        LazyColumn(
                            state = agentTabListStates.getValue(pageTab),
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md),
                            beyondBoundsItemCount = sharedListBeyondBoundsItemCount(12)
                        ) {
                            if (initialVisualMode != MembersAgentDetailVisualMode.AvatarManagement) {
                                item(key = "member-detail-${member.id.value}-top-spacer") {
                                    Spacer(Modifier.height(SlockSpacing.Sm))
                                }
                            }
                            if (
                                membersAgentDetailShowsProfileStateNotice(
                                    selectedTab = pageTab,
                                    avatarManagementMode = initialVisualMode == MembersAgentDetailVisualMode.AvatarManagement
                                )
                            ) {
                                item(key = "member-detail-${member.id.value}-state-notice") {
                                    MembersAgentDetailStateNotice(
                                        state = agentDetailState,
                                        modifier = Modifier.padding(horizontal = MembersPageInset)
                                    )
                                }
                            }
                            // This item switches between composables with different remember slots
                            // as the selected tab/member changes.  An implicit index key lets
                            // Kuikly's lazy provider reuse the previous tab's slot table; on
                            // Android that can make rememberCoroutineScope read a Boolean (or
                            // another tab state) as CompositionScopedCoroutineScopeCanceller and
                            // crash while entering the profile.  Keep each member/tab surface a
                            // distinct lazy item so state is disposed/recreated at the boundary.
                            item(key = membersAgentDetailBodyItemKey(member.id.value, pageTab)) {
                                // Kuikly's lazy item key is not sufficient to fence the nested
                                // composition on every renderer.  Give each tab surface its own
                                // Compose group as well, so remember slots from Profile/Chat/etc.
                                // can never be reused across a tab switch.
                                key(pageTab) {
                                    when (pageTab) {
                                    MembersAgentDetailTab.Profile ->
                                        MembersAgentProfileTab(
                                            member = member,
                                            detail = detail,
                                            runtimeModels = MembersAgentRuntimeModelInputs(
                                                agentMachineRuntimes = agentMachineRuntimes,
                                                agentRuntimeModelsByRuntime = agentRuntimeModelsByRuntime,
                                                agentRuntimeModelLoadsInFlight = agentRuntimeModelLoadsInFlight,
                                                agentRuntimeModelErrorsByRuntime = agentRuntimeModelErrorsByRuntime,
                                                agentRuntimeModelGenerationsByRuntime = agentRuntimeModelGenerationsByRuntime,
                                                onAgentRuntimeModelsRequest = onAgentRuntimeModelsRequest
                                            ),
                                            createAgentFormStore = createAgentFormStore,
                                            agentProfileEditAuthority = agentProfileEditAuthority,
                                            canManage = canManage,
                                            agentStatusIndicatorState = agentStatusIndicatorState,
                                            agentStatusObservedState = agentStatusObservedState,
                                            agentStatusResolver = agentStatusResolver,
                                            memberAvatarResolver = memberAvatarResolver,
                                            showAvatarPicker = showAvatarPicker && canManage && !deleted,
                                            onToggleAvatarPicker = {
                                                showAvatarPicker = !showAvatarPicker
                                            },
                                            onSelectAvatar = { key ->
                                                saveAvatar("pixel:$key")
                                            },
                                            onClearAvatar = {
                                                saveAvatar(null)
                                            },
                                            onUploadAvatar = {
                                                val uploadCallback = onUploadAgentAvatar
                                                if (uploadCallback == null) {
                                                    DataResult.Empty(EmptyReason.NoMembers)
                                                } else {
                                                    val picked = onPickAgentAvatarFile()
                                                    when (picked) {
                                                        is DataResult.Content ->
                                                            uploadCallback(member.id, picked.data)
                                                        is DataResult.Empty -> DataResult.Empty(picked.reason)
                                                        is DataResult.Error -> DataResult.Error(picked.error)
                                                        is DataResult.NoPermission -> DataResult.NoPermission(picked.blocker)
                                                    }
                                                }
                                            },
                                            sharedAgentDetailStore = sharedAgentDetailStore,
                                            storeState = storeState.takeIf { sharedAgentDetailStore != null },
                                            agentDetailActions = agentDetailActions,
                                            onUpdateAgentProfile = onUpdateAgentProfile,
                                            onApplyAgentProfileResult = onApplyAgentProfileResult,
                                            onApplyAgentRuntimeConfigProjection = onApplyAgentRuntimeConfigProjection,
                                            agentsApiClient = agentsApiClient,
                                            externalSetupServerUrl = externalSetupServerUrl,
                                            onUnavailableAction = onUnavailableAction,
                                            lifecycle = MembersAgentLifecycleInputs(
                                                lifecycleInFlight = lifecycleInFlight,
                                                diagnosticActionState = effectiveDiagnosticActionState,
                                                onRunLifecycleAction = { runLifecycleAction(it) },
                                                onOpenRestartReset = { openRestartResetDialog() },
                                                onCopyDiagnostics = { copyDiagnostics() },
                                                onCopyText = { text -> agentDetailActions?.copyDiagnostics(text) ?: Unit },
                                                onReportIssue = {
                                                    issueReportReceipt = null
                                                    issueReportReferenceCopied = false
                                                    reportIssueDialogOpen = true
                                                },
                                                onOpenComputer = onOpenComputer
                                            ),
                                            machineStatusRow = machineStatusRow,
                                            timeContext = timeContext,
                                            fixtureStatusFallbackEnabled = stableVisualCapture,
                                            modifier = Modifier.padding(horizontal = MembersPageInset)
                                        )
                                    MembersAgentDetailTab.Chat ->
                                        MembersAgentChatTab(
                                            detail = detail,
                                            currentServerId = currentServerId,
                                            serverMembersState = serverMembersState,
                                            timeContext = timeContext,
                                            pageVisibleTick = pageVisibleTick,
                                            modifier = Modifier.padding(horizontal = MembersPageInset)
                                        )
                                    MembersAgentDetailTab.Reminders ->
                                        MembersAgentRemindersTab(
                                            detail = detail,
                                            timeContext = timeContext,
                                            onOpenMessageRef = onOpenReminderMessage,
                                            modifier = Modifier.padding(horizontal = MembersPageInset)
                                        )
                                    MembersAgentDetailTab.Workspace ->
                                        MembersAgentWorkspaceTab(
                                            treeState = workspaceTreeState,
                                            expandedDirs = expandedWorkspaceDirs,
                                            loadedDirs = loadedWorkspaceDirs,
                                            loadingDirs = loadingWorkspaceDirs,
                                            dirErrors = workspaceDirErrors,
                                            onRefresh = { loadWorkspaceRoot() },
                                            onToggleDirectory = { toggleWorkspaceDirectory(it) },
                                            onSelectFile = { selectWorkspaceFile(it) },
                                            modifier = Modifier.padding(horizontal = MembersPageInset)
                                        )
                                    MembersAgentDetailTab.Apps ->
                                        MembersAgentAppsTab(
                                            detail = detail,
                                            timeContext = timeContext,
                                            onOpenUrl = onOpenUrl,
                                            modifier = Modifier.padding(horizontal = MembersPageInset)
                                        )
                                        MembersAgentDetailTab.Activity -> Unit
                                    }
                                }
                            }
                            item(key = "member-detail-${member.id.value}-bottom-spacer") {
                                Spacer(Modifier.height(180.dp))
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md),
                    beyondBoundsItemCount = sharedListBeyondBoundsItemCount(12)
                ) {
                    item(key = "member-detail-${member.id.value}-human-content") {
                        MembersHumanDetailSections(
                            member = member,
                            timeContext = timeContext,
                            memberAvatarResolver = memberAvatarResolver,
                            agentStatusResolver = agentStatusResolver,
                            fixtureStatusFallbackEnabled = stableVisualCapture,
                            actorRole = humanActorRole,
                            accessMode = humanAccessMode,
                            currentUserId = currentUserId,
                            currentServerId = currentServerId,
                            ownerCount = humanOwnerCount,
                            onUpdateRole = onUpdateHumanRole,
                            onRemoveMember = onRemoveMember?.takeIf {
                                member.membershipStatus != "removed"
                            },
                            modifier = Modifier.padding(horizontal = MembersPageInset)
                        )
                    }
                    item(key = "member-detail-${member.id.value}-bottom-spacer") {
                        Spacer(Modifier.height(180.dp))
                    }
                }
            }
        }
        val dropdownAnchor = managementMenuAnchor
        if (managementMenuOpen && member.kind == MemberKind.Agent && dropdownAnchor != null) {
            val menuPrimary =
                membersResolvedPrimaryLifecycleAction(
                    resolvedStatus = agentStatusIndicatorState?.status,
                    detailFallbackStatus = null,
                    deleted = deleted,
                    hasResolvedPresence = true
                )
            val menuItems =
                buildMembersManagementMenuItems(
                    primaryAction = menuPrimary,
                    canManage = canManage,
                    deleted = deleted,
                    inProgress = lifecycleInFlight != null
                ).map { item ->
                    SlockRaftUiMenuItem(
                        id = item.id,
                        label = item.label.localized(),
                        icon = item.icon,
                        enabled = item.enabled
                    )
                }
            SlockRaftUiControlledMenu(
                expanded = managementMenuOpen,
                anchor = dropdownAnchor,
                items = menuItems,
                onSelect = { id ->
                    managementMenuOpen = false
                    handleMembersManagementMenuSelection(
                        id = id,
                        primaryAction = menuPrimary,
                        openRestartResetDialog = { openRestartResetDialog() },
                        runLifecycleAction = { runLifecycleAction(it) }
                    )
                },
                onDismiss = {
                    managementMenuOpen = false
                }
            )
        }
        if (restartResetDialogOpen && member.kind == MemberKind.Agent) {
            val selectedActionResult =
                if (sharedAgentDetailStore != null) {
                    storeState.lastActionResult
                        ?.takeIf { it.actionKey == restartResetSelection.actionKey }
                        ?.let {
                            MembersAgentActionState(
                                action = restartResetSelection,
                                inProgress = false,
                                error = it.error,
                                successLabel = it.successLabel
                            )
                        }
                } else {
                    actionState?.takeIf { it.action == restartResetSelection }
                }
            MembersRestartResetDialog(
                agentName = member.displayName,
                selectedAction = restartResetSelection,
                busy = lifecycleInFlight != null,
                errorMessage = selectedActionResult?.error?.userFacingMessage(errorStrings),
                onSelectedActionChange = { restartResetSelection = it },
                onConfirm = { runLifecycleAction(restartResetSelection, confirmed = true) },
                onDismiss = {
                    if (lifecycleInFlight == null) {
                        restartResetDialogOpen = false
                        restartResetSelection = MembersAgentLifecycleAction.Restart
                        actionState = null
                    }
                }
            )
        }
        if (reportIssueDialogOpen && member.kind == MemberKind.Agent) {
            val reportState = effectiveDiagnosticActionState?.takeIf { it.actionKey == "report" }
            MembersAgentIssueReportDialog(
                agentName = member.displayName,
                runtimeTranscriptAvailable = detail?.summary?.machineId?.isNotBlank() == true,
                busy = reportState?.inProgress == true,
                errorMessage = reportState?.error?.userFacingMessage(errorStrings),
                onSubmit = { description, includeRecentMessages, includeLiveActivity, includeActivityHistory, includeRuntimeTranscript ->
                    submitIssueReport(
                        description,
                        includeRecentMessages,
                        includeLiveActivity,
                        includeActivityHistory,
                        includeRuntimeTranscript
                    )
                },
                onDismiss = {
                    if (reportState?.inProgress != true) {
                        reportIssueDialogOpen = false
                    }
                }
            )
        }
        issueReportReceipt?.let { receipt ->
            MembersAgentIssueReportReceiptDialog(
                receipt = receipt,
                referenceCopied = issueReportReferenceCopied,
                onCopyReference = {
                    onCopyAgentIssueReference(buildMembersAgentIssueReportReference(receipt))
                    issueReportReferenceCopied = true
                },
                onDismiss = {
                    issueReportReceipt = null
                    issueReportReferenceCopied = false
                }
            )
        }
        val activePendingConfirmation =
            if (sharedAgentDetailStore != null) storePendingConfirmation else pendingConfirmation
        activePendingConfirmation?.let { action ->
            // task #515: archive (the former delete) gains the web
            // `AgentArchiveDialog` contract — "keep on the Computer" vs "delete
            // the workspace" — only when this agent actually has a workspace on
            // a Computer, matching the web gate `hasComputerWorkspace`
            // (`machineId` set, not an external runtime).
            val archiveHasComputerWorkspace =
                action == MembersAgentLifecycleAction.Delete &&
                    membersAgentArchiveHasComputerWorkspace(
                        machineId = detail?.summary?.machineId,
                        isExternalAgent = isExternalAgent
                    )
            var archiveWorkspaceChoice by remember(member.id.value, detail?.summary?.machineId) {
                mutableStateOf(AgentArchiveWorkspaceChoice.Keep)
            }
            // Reuse the shared unified dialog (React ConfirmDialog is a plain
            // card: uppercase 18px title + X, no header divider, max-w-sm).
            SlockUnifiedDialog(
                title = membersLifecycleConfirmTitle(action, member.displayName).slockDisplayUppercase(),
                dismissEnabled = lifecycleInFlight != action.actionKey,
                maxWidth = 384.dp,
                contentPadding = 24.dp,
                titleFontSize = 18,
                titleLineHeight = 22,
                onDismiss = {
                    if (sharedAgentDetailStore != null) {
                        sharedAgentDetailStore.dispatch(SharedAgentDetailIntent.DismissConfirmation)
                    } else {
                        pendingConfirmation = null
                    }
                }
            ) {
                MembersLifecycleConfirmation(
                    action = action,
                    agentName = member.displayName,
                    busy = lifecycleInFlight == action.actionKey,
                    archiveHasComputerWorkspace = archiveHasComputerWorkspace,
                    archiveComputerName = detail?.summary?.machineName?.takeIf { it.isNotBlank() }
                        ?: stringResource(MR.strings.member_archive_unknown_computer),
                    archiveWorkspaceChoice = archiveWorkspaceChoice,
                    onArchiveWorkspaceChoiceChange = { archiveWorkspaceChoice = it },
                    onConfirm = { runLifecycleAction(action, confirmed = true, workspaceChoice = archiveWorkspaceChoice) },
                    onCancel = {
                        if (sharedAgentDetailStore != null) {
                            sharedAgentDetailStore.dispatch(SharedAgentDetailIntent.DismissConfirmation)
                        } else {
                            pendingConfirmation = null
                            actionState = null
                        }
                    }
                )
            }
        }
    }
}

private fun DataResult<AgentLifecycleActionResult>.membersLifecycleError(action: MembersAgentLifecycleAction): ScreenError? =
    when (this) {
        is DataResult.Content ->
            if (data.success) null else ScreenError("agent_${action.actionKey}_failed")
        is DataResult.Empty -> ScreenError(reason.name, retryable = false)
        is DataResult.Error -> error
        is DataResult.NoPermission -> ScreenError(blocker.code, retryable = false)
    }

internal data class MembersManagementMenuItemSpec(
    val id: String,
    val label: StringDesc,
    val icon: SlockLucideIconName,
    val enabled: Boolean
)

internal fun buildMembersManagementMenuItems(
    primaryAction: MembersAgentLifecycleAction?,
    canManage: Boolean,
    deleted: Boolean,
    inProgress: Boolean
): List<MembersManagementMenuItemSpec> {
    if (deleted || !canManage) return emptyList()
    val items = mutableListOf<MembersManagementMenuItemSpec>()
    if (primaryAction != null) {
        items += MembersManagementMenuItemSpec(
            id = primaryAction.actionKey,
            label = when (primaryAction) {
                MembersAgentLifecycleAction.Start -> MR.strings.member_action_start_agent.desc()
                MembersAgentLifecycleAction.Stop -> MR.strings.member_action_stop_agent.desc()
                else -> primaryAction.label.desc()
            },
            icon = primaryAction.iconName,
            enabled = !inProgress
        )
    }
    items += MembersManagementMenuItemSpec(
        id = MembersAgentLifecycleAction.Restart.actionKey,
        label = MR.strings.member_label_restart_reset.desc(),
        icon = SlockLucideIconName.RotateCcw,
        enabled = !inProgress
    )
    return items
}

private fun handleMembersManagementMenuSelection(
    id: String,
    primaryAction: MembersAgentLifecycleAction?,
    openRestartResetDialog: () -> Unit,
    runLifecycleAction: (MembersAgentLifecycleAction) -> Unit
) {
    if (id == MembersAgentLifecycleAction.Restart.actionKey) {
        openRestartResetDialog()
        return
    }
    val action = MembersAgentLifecycleAction.values().firstOrNull { it.actionKey == id }
        ?: primaryAction?.takeIf { it.actionKey == id }
    if (action != null) {
        runLifecycleAction(action)
    }
}

private fun DataResult<AgentWorkspaceTreeSnapshot>.toMembersWorkspaceTreeState(): MembersAgentWorkspaceTreeState =
    when (this) {
        is DataResult.Content -> MembersAgentWorkspaceTreeState.Content(data)
        is DataResult.Empty -> MembersAgentWorkspaceTreeState.Empty
        is DataResult.Error -> MembersAgentWorkspaceTreeState.Error(error)
        is DataResult.NoPermission -> MembersAgentWorkspaceTreeState.NoPermission(blocker)
    }

@Composable
private fun MembersDetailHeader(
    member: MemberProfile,
    currentUserId: String?,
    currentServerId: String?,
    detail: AgentDetailSnapshot?,
    deleted: Boolean,
    canManage: Boolean,
    agentStatusIndicatorState: SlockAgentStatusIndicatorState?,
    memberAvatarResolver: SlockMemberAvatarResolver = EmptySlockMemberAvatarResolver,
    onBack: () -> Unit,
    onAvatarClick: (() -> Unit)?,
    onOpenDirectMessage: () -> Unit,
    onCopyMemberId: () -> Unit,
    onManagementMenuAnchorResolverReady: (SlockPopoverAnchorResolver) -> Unit,
    onRequestManagementMenu: (SlockPopoverAnchor?) -> Unit
) {
    val summary = detail?.summary
    val directMessageEligibility =
        membersDirectMessageEligibility(
            member = member,
            currentUserId = currentUserId,
            currentServerId = currentServerId,
            deleted = deleted
        )
    val threeDotButtonAnchor =
        remember(member.id.value) { SlockLiveRootAnchorRef(identity = "member:${member.id.value}:management") }
    val threeDotButtonAnchorResolver =
        remember(threeDotButtonAnchor) { threeDotButtonAnchor.resolver(SlockPopoverLocalPoint.BottomEnd) }
    val currentOnManagementMenuAnchorResolverReady by
        rememberUpdatedState(onManagementMenuAnchorResolverReady)
    val agentStatusDisplayState =
        if (member.kind == MemberKind.Agent) {
            agentStatusIndicatorState?.toMembersAgentStatusDisplayState()
                ?: unknownMembersAgentStatusIndicatorState().toMembersAgentStatusDisplayState()
        } else {
            member.membersAgentStatusDisplayState(summary?.status, summary?.activity)
        }
    val headerMetaLabel =
        when {
            deleted -> stringResource(MR.strings.member_status_deleted)
            member.kind == MemberKind.Agent ->
                summary?.description?.takeIf { it.isNotBlank() }
                    ?: member.description?.takeIf { it.isNotBlank() }
            else -> null
        }
    val avatarClick = onAvatarClick
    val topSafeArea = slockTopSafeAreaPadding()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(topSafeArea + SlockSize.PageHeaderHeight)
            .background(SlockColor.SurfaceWhite)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(topSafeArea + SlockSize.PageHeaderContentHeight)
                .padding(start = 12.dp, top = topSafeArea + 8.dp, end = 24.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
        ) {
            SlockTopPageHeaderAction(icon = SlockLucideIconName.ArrowLeft, onClick = onBack)
            MembersAvatar(
                member = member,
                avatarSize = 36.dp,
                statusSize = 10.dp,
                statusDisplayState = agentStatusDisplayState,
                statusIndicatorState = agentStatusIndicatorState,
                memberAvatarResolver = memberAvatarResolver,
                showStatusOverlay = false,
                modifier = if (avatarClick != null) {
                    Modifier.clickable(onClick = avatarClick)
                } else {
                    Modifier
                }
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = member.displayName,
                    style = SlockType.Body.copy(fontSize = 16.sp, lineHeight = 20.sp)
                        .withSlockFontWeight(FontWeight.Bold),
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!headerMetaLabel.isNullOrBlank()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = headerMetaLabel,
                            style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 16.sp),
                            color = if (deleted) SlockColor.AccentOrange else SlockColor.TextMutedGray,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                }
            }
            if (
                directMessageEligibility != MembersDirectMessageEligibility.Unavailable &&
                membersHeaderMessageUsesIconOnly(member.kind)
            ) {
                SlockBrutalIconButton(
                    iconName = SlockLucideIconName.MessageSquare,
                    onClick = onOpenDirectMessage,
                    contentDescription = stringResource(MR.strings.member_label_message),
                    size = MembersActionSize
                )
            } else if (directMessageEligibility != MembersDirectMessageEligibility.Unavailable) {
                SlockBrutalActionButton(
                    label = stringResource(MR.strings.member_label_message),
                    leadingIcon = SlockLucideIconName.MessageSquare,
                    minHeight = MembersActionSize,
                    modifier = Modifier.widthIn(min = 92.dp),
                    onClick = onOpenDirectMessage
                )
            }
            if (member.kind == MemberKind.Agent) {
                SlockBrutalIconButton(
                    iconName = SlockLucideIconName.Menu,
                    onClick = {
                        onRequestManagementMenu(
                            threeDotButtonAnchorResolver.resolve()
                        )
                    },
                    size = MembersActionSize,
                    modifier =
                        Modifier.slockCaptureLiveRootAnchor(threeDotButtonAnchor) {
                            currentOnManagementMenuAnchorResolverReady(threeDotButtonAnchorResolver)
                        }
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(SlockStroke.Default)
                .background(SlockColor.BorderBlack)
                .align(Alignment.BottomCenter)
        )
    }
}

@Composable
private fun MembersDetailSummary(
    member: MemberProfile,
    detail: AgentDetailSnapshot?,
    deleted: Boolean,
    agentStatusIndicatorState: SlockAgentStatusIndicatorState?,
    memberAvatarResolver: SlockMemberAvatarResolver = EmptySlockMemberAvatarResolver,
    modifier: Modifier = Modifier
) {
    val summary = detail?.summary
    val statusLabel =
        if (deleted) {
            stringResource(MR.strings.member_status_deleted)
        } else {
            membersLiveAgentStatusLabel(
                indicatorState = agentStatusIndicatorState,
                summaryStatus = summary?.status,
                summaryActivity = summary?.activity,
                summaryActivityDetail = summary?.activityDetail,
                fallback = MembersUnknownAgentStatusLabel
            )
        }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .border(SlockStroke.Default, SlockColor.BorderBlack)
            .background(if (deleted) SlockColor.EmptyStateGray else SlockColor.SurfaceCream)
            .padding(SlockSpacing.Md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
    ) {
        MembersAvatar(
            member = member,
            avatarSize = 58.dp,
            statusSize = 12.dp,
            statusDisplayState =
                agentStatusIndicatorState?.toMembersAgentStatusDisplayState()
                    ?: unknownMembersAgentStatusIndicatorState().toMembersAgentStatusDisplayState(),
            statusIndicatorState = agentStatusIndicatorState,
            memberAvatarResolver = memberAvatarResolver
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)) {
            Row(horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = member.displayName,
                    style = SlockType.PageTitle.copy(fontSize = 20.sp, lineHeight = 24.sp),
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                MemberKindBadge(member.kind)
            }
            Text(
                text = member.detailHandle,
                style = SlockType.Body.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 13.sp, lineHeight = 18.sp),
                color = SlockColor.TextMutedGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = member.description?.takeIf { it.isNotBlank() } ?: member.detailRoleLabel,
                style = SlockType.Body.withSlockFontWeight(FontWeight.Normal).copy(fontSize = 13.sp, lineHeight = 18.sp),
                color = SlockColor.TextBlack,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = statusLabel,
                style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 16.sp),
                color = if (deleted) SlockColor.AccentRed else SlockColor.TextMutedGray,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun MembersDetailActions(
    member: MemberProfile,
    deleted: Boolean,
    onOpenDirectMessage: () -> Unit,
    onCopyMemberId: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        MembersDetailActionButton(
            label = stringResource(MR.strings.member_label_message),
            iconName = SlockLucideIconName.MessageSquare,
            enabled = member.kind != MemberKind.System && !deleted,
            onClick = onOpenDirectMessage,
            modifier = Modifier.weight(1f)
        )
        MembersDetailActionButton(
            label = stringResource(MR.strings.member_action_copy_id),
            iconName = SlockLucideIconName.Copy,
            enabled = true,
            onClick = onCopyMemberId,
            modifier = Modifier.weight(1f)
        )
    }
}


/**
 * task #515 — web `AgentArchiveDialog` gate `hasComputerWorkspace`
 * (`Boolean(agent.machineId) && agent.external !== true && !isExternalAgentRuntime(runtime)`):
 * the workspace keep/delete choice is offered only when a managed agent really
 * has a workspace on a Computer. External runtimes never do (server would 409
 * `archive_workspace_unavailable`), and a missing machineId hides the radio
 * entirely — the request then archives with the workspace kept.
 */
internal fun membersAgentArchiveHasComputerWorkspace(
    machineId: String?,
    isExternalAgent: Boolean
): Boolean = !isExternalAgent && machineId?.isNotBlank() == true

/**
 * Wire body for `DELETE /agents/:id` — web always sends `{"workspace":...}`.
 */
internal fun membersAgentArchiveRequestBody(choice: AgentArchiveWorkspaceChoice): String =
    choice.toRequestBody()

// Mirrors React ConfirmDialog usage in AgentDetailPanel: title matches the
// action button label, message is user-facing copy, warning banner shows the
// message only (never repeats the title), and the confirm button carries the
// action label with warning/destructive color.
@Composable
private fun membersLifecycleConfirmTitle(action: MembersAgentLifecycleAction, agentName: String): String =
    when (action) {
        MembersAgentLifecycleAction.Stop -> stringResource(MR.strings.member_action_stop_agent)
        // task #515: the archive dialog's title carries the agent name, matching
        // web `agent.archive.title` ("Archive {name}?"); other actions keep the
        // action-label title.
        MembersAgentLifecycleAction.Delete ->
            stringResource(MR.strings.member_archive_title).replace("%1\$s", agentName)
        else -> action.localizedLabel()
    }

@Composable
private fun membersLifecycleConfirmMessage(action: MembersAgentLifecycleAction, agentName: String): String =
    when (action) {
        MembersAgentLifecycleAction.Stop ->
            stringResource(MR.strings.member_dialog_confirm_stop).replace("%1\$s", agentName)
        MembersAgentLifecycleAction.Delete ->
            stringResource(MR.strings.member_archive_description)
        MembersAgentLifecycleAction.UnassignMachine ->
            stringResource(MR.strings.member_dialog_confirm_unassign).replace("%1\$s", agentName)
        else -> "Are you sure you want to ${action.localizedLabel().lowercase()} \"$agentName\"?"
    }

@Composable
private fun MembersLifecycleConfirmation(
    action: MembersAgentLifecycleAction,
    agentName: String,
    busy: Boolean,
    archiveHasComputerWorkspace: Boolean = false,
    archiveComputerName: String = "",
    archiveWorkspaceChoice: AgentArchiveWorkspaceChoice = AgentArchiveWorkspaceChoice.Keep,
    onArchiveWorkspaceChoiceChange: (AgentArchiveWorkspaceChoice) -> Unit = {},
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    // Web `AgentArchiveDialog`: confirm button is warning when the workspace is
    // kept, danger once the user picks "delete the workspace". For non-archive
    // actions the classic destructive flag rules unchanged.
    val isArchive = action == MembersAgentLifecycleAction.Delete
    // Archive overrides the enum's blanket `destructive=true`: web keeps the
    // confirm button at warning until the user picks "delete the workspace".
    val destructive =
        if (isArchive) archiveWorkspaceChoice == AgentArchiveWorkspaceChoice.Delete else action.destructive
    val accent = if (destructive) SlockColor.AccentRed else SlockColor.AccentOrange
    // Hosted inside SlockUnifiedDialog, which owns the card padding.
    // Banner metrics mirror React Banner density=md: p-3 (12), gap-3 (12),
    // text-sm (14/20), border-2, and ConfirmDialog's mb-5 (20) to the buttons.
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        if (isArchive) {
            // Web order: description → workspace question + radio rows → the
            // destructive banner ONLY when "delete" is selected (the keep path
            // carries no warning at all).
            Text(
                text = stringResource(MR.strings.member_archive_description),
                style = SlockType.Caption.copy(fontSize = 14.sp, lineHeight = 20.sp),
                color = SlockColor.TextBlack
            )
            if (archiveHasComputerWorkspace) {
                Text(
                    text = stringResource(MR.strings.member_archive_workspace_question),
                    style = SlockType.Caption.copy(fontSize = 14.sp, lineHeight = 20.sp),
                    color = SlockColor.TextMutedGray
                )
                Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    MembersArchiveWorkspaceOption(
                        label = stringResource(MR.strings.member_archive_option_keep),
                        description = stringResource(MR.strings.member_archive_option_keep_desc)
                            .replace("%1\$s", archiveComputerName),
                        selected = archiveWorkspaceChoice == AgentArchiveWorkspaceChoice.Keep,
                        enabled = !busy,
                        onSelect = { onArchiveWorkspaceChoiceChange(AgentArchiveWorkspaceChoice.Keep) }
                    )
                    MembersArchiveWorkspaceOption(
                        label = stringResource(MR.strings.member_archive_option_delete),
                        description = stringResource(MR.strings.member_archive_option_delete_desc)
                            .replace("%1\$s", archiveComputerName),
                        selected = archiveWorkspaceChoice == AgentArchiveWorkspaceChoice.Delete,
                        enabled = !busy,
                        onSelect = { onArchiveWorkspaceChoiceChange(AgentArchiveWorkspaceChoice.Delete) }
                    )
                }
            }
            if (archiveWorkspaceChoice == AgentArchiveWorkspaceChoice.Delete && archiveHasComputerWorkspace) {
                MembersLifecycleWarningBanner(
                    destructive = true,
                    text = stringResource(MR.strings.member_archive_delete_warning)
                )
            }
        } else {
            MembersLifecycleWarningBanner(
                destructive = destructive,
                text = membersLifecycleConfirmMessage(action, agentName)
            )
        }
        SlockDialogFooter {
            SlockBrutalActionButton(
                label = stringResource(MR.strings.common_cancel),
                enabled = !busy,
                onClick = onCancel
            )
            SlockBrutalActionButton(
                label = if (isArchive) {
                    stringResource(MR.strings.member_action_delete_agent)
                } else {
                    membersLifecycleConfirmTitle(action, agentName)
                },
                enabled = !busy,
                backgroundColor = accent,
                // Web ConfirmDialog carries confirmIcon=<Archive> on the archive
                // confirm; other actions stay icon-less.
                leadingIcon = if (busy) SlockLucideIconName.Loader else if (isArchive) SlockLucideIconName.Archive else null,
                onClick = onConfirm
            )
        }
    }
}

/** Banner row extracted so the archive flow can show it conditionally (web renders it only for the delete choice). */
@Composable
private fun MembersLifecycleWarningBanner(destructive: Boolean, text: String) {
    val accent = if (destructive) SlockColor.AccentRed else SlockColor.AccentOrange
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(accent.copy(alpha = 0.2f))
            .border(SlockStroke.Default, SlockColor.BorderBlack)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        // React Banner icons: destructive → AlertTriangle, warning →
        // CircleAlert (closest available catalog glyph is Info).
        SlockLucideIcon(
            if (destructive) SlockLucideIconName.TriangleAlert else SlockLucideIconName.Info,
            modifier = Modifier.size(18.dp),
            color = SlockColor.TextBlackArgb
        )
        Text(
            text = text,
            style = SlockType.Caption.copy(fontSize = 14.sp, lineHeight = 20.sp),
            color = SlockColor.TextBlack
        )
    }
}

/**
 * One workspace-disposition row of the archive dialog — web renders a
 * `RadioGroupItem` + 15px/600 label + 13px muted description; the closest
 * shared primitive is the circle `SlockCheckMarker` used by
 * `SlockRaftUiRadioRow`, with the whole row tappable.
 */
@Composable
private fun MembersArchiveWorkspaceOption(
    label: String,
    description: String,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.clickable(onClick = onSelect) else Modifier)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        SlockCheckMarker(
            checked = selected,
            shape = SlockCheckMarkerShape.Circle,
            tone = SlockCheckMarkerTone.YellowFill,
            disabled = !enabled,
            size = 16.dp,
            modifier = Modifier.padding(top = 2.dp)
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = label,
                style = SlockType.Body.copy(fontSize = 15.sp, lineHeight = 20.sp).withSlockFontWeight(FontWeight.SemiBold),
                color = if (enabled) SlockColor.TextBlack else SlockColor.TextBlack.copy(alpha = 0.45f)
            )
            Text(
                text = description,
                style = SlockType.Caption.copy(fontSize = 13.sp, lineHeight = 18.sp),
                color = SlockColor.TextMutedGray
            )
        }
    }
}

@Composable
private fun MembersAgentIssueReportDialog(
    agentName: String,
    runtimeTranscriptAvailable: Boolean,
    busy: Boolean,
    errorMessage: String?,
    onSubmit: (String, Boolean, Boolean, Boolean, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var description by remember(agentName) { mutableStateOf(TextFieldValue()) }
    var consented by remember(agentName) { mutableStateOf(false) }
    var includeRecentMessages by remember(agentName) { mutableStateOf(true) }
    var includeLiveActivity by remember(agentName) { mutableStateOf(true) }
    var includeActivityHistory by remember(agentName) { mutableStateOf(true) }
    var includeRuntimeTranscript by remember(agentName, runtimeTranscriptAvailable) {
        mutableStateOf(runtimeTranscriptAvailable)
    }
    SlockUnifiedDialog(
        title = stringResource(MR.strings.member_report_issue_title).slockDisplayUppercase(),
        dismissEnabled = !busy,
        keyboardAvoidanceEnabled = true,
        maxWidth = 384.dp,
        contentPadding = 24.dp,
        titleFontSize = 18,
        titleLineHeight = 22,
        onDismiss = onDismiss
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
        ) {
            item {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .border(SlockStroke.Default, SlockColor.BorderBlack)
                            .background(SlockColor.SurfaceWhite)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    SlockSectionEyebrow(label = stringResource(MR.strings.member_report_issue_agent_label))
                    Text(
                        text = agentName,
                        style = SlockType.ButtonBold,
                        color = SlockColor.TextBlack
                    )
                }
            }
            item {
                SlockBrutalTextField(
                    label = stringResource(MR.strings.member_report_issue_description_label),
                    value = description,
                    placeholder = stringResource(MR.strings.member_report_issue_description_placeholder),
                    onValueChange = { description = it },
                    optional = true,
                    enabled = !busy,
                    maxLines = 4,
                    fieldMinHeight = 88.dp,
                    password = false
                )
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SlockSectionEyebrow(label = stringResource(MR.strings.member_report_issue_include))
                    MembersAgentIssueReportOption(
                        label = stringResource(MR.strings.member_report_issue_recent_messages),
                        checked = includeRecentMessages,
                        enabled = !busy,
                        onCheckedChange = { includeRecentMessages = it }
                    )
                    MembersAgentIssueReportOption(
                        label = stringResource(MR.strings.member_report_issue_live_activity),
                        checked = includeLiveActivity,
                        enabled = !busy,
                        onCheckedChange = { includeLiveActivity = it }
                    )
                    MembersAgentIssueReportOption(
                        label = stringResource(MR.strings.member_report_issue_activity_history),
                        checked = includeActivityHistory,
                        enabled = !busy,
                        onCheckedChange = { includeActivityHistory = it }
                    )
                    MembersAgentIssueReportOption(
                        label = stringResource(MR.strings.member_report_issue_runtime_transcript),
                        checked = includeRuntimeTranscript,
                        enabled = runtimeTranscriptAvailable && !busy,
                        onCheckedChange = { includeRuntimeTranscript = it }
                    )
                    if (!runtimeTranscriptAvailable) {
                        Text(
                            text = stringResource(MR.strings.member_report_issue_runtime_transcript_unavailable),
                            modifier = Modifier.padding(start = 26.dp),
                            style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 15.sp),
                            color = SlockColor.TextMutedGray
                        )
                    }
                }
            }
            item {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            // deliberate react-parity wash (web bg-*/N tint family); triaged token-audit #1043
                            .background(SlockColor.AccentOrange.copy(alpha = 0.2f))
                            .border(SlockStroke.Default, SlockColor.BorderBlack)
                            .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        SlockLucideIcon(
                            SlockLucideIconName.TriangleAlert,
                            modifier = Modifier.size(18.dp),
                            color = SlockColor.TextBlackArgb
                        )
                        Text(
                            text = stringResource(MR.strings.member_report_issue_disclosure),
                            modifier = Modifier.weight(1f),
                            style = SlockType.Caption.copy(fontSize = 13.sp, lineHeight = 18.sp),
                            color = SlockColor.TextBlack
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable(enabled = !busy) { consented = !consented },
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SlockCheckbox(
                            checked = consented,
                            onCheckedChange = { consented = it },
                            enabled = !busy,
                            size = 16.dp
                        )
                        Text(
                            text = stringResource(MR.strings.member_report_issue_consent),
                            modifier = Modifier.weight(1f),
                            style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 12.sp, lineHeight = 16.sp),
                            color = SlockColor.TextBlack
                        )
                    }
                }
            }
            errorMessage?.takeIf { it.isNotBlank() }?.let { message ->
                item {
                    Text(
                        text = stringResource(MR.strings.member_report_issue_failed, message),
                        style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold),
                        color = SlockColor.AccentRed
                    )
                }
            }
        }
        SlockDialogFooter {
            SlockBrutalActionButton(
                label = stringResource(MR.strings.common_cancel),
                enabled = !busy,
                backgroundColor = SlockColor.SurfaceWhite,
                onClick = onDismiss
            )
            SlockBrutalActionButton(
                label =
                    if (busy) {
                        stringResource(MR.strings.member_report_issue_submitting)
                    } else {
                        stringResource(MR.strings.member_report_issue_submit)
                    },
                leadingIcon = if (busy) SlockLucideIconName.Loader else SlockLucideIconName.Bug,
                enabled = consented && !busy,
                // Web ReportIssueDialog submits with the pink primary action.
                backgroundColor = SlockColor.AccentPink,
                onClick = {
                    onSubmit(
                        description.text,
                        includeRecentMessages,
                        includeLiveActivity,
                        includeActivityHistory,
                        includeRuntimeTranscript
                    )
                }
            )
        }
    }
}

@Composable
private fun MembersAgentIssueReportOption(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled) { onCheckedChange(!checked) },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SlockCheckbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            size = 16.dp
        )
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            // Web option labels are text-sm, regular weight.
            style = SlockType.Body.copy(fontSize = 14.sp, lineHeight = 20.sp),
            color = if (enabled) SlockColor.TextBlack else SlockColor.TextMutedGray
        )
    }
}

internal fun buildMembersAgentIssueReportReference(
    receipt: AgentIssueReportResult
): String = buildList {
    add("reportId: ${receipt.reportId}")
    add("serverId: ${receipt.serverId}")
    receipt.artifactId?.takeIf { it.isNotBlank() }?.let { artifactId ->
        add("artifactId: $artifactId")
    }
    receipt.issueDescription?.takeIf { it.isNotBlank() }?.let { description ->
        add("issueDescription:\n$description")
    }
}.joinToString("\n")

@Composable
private fun MembersAgentIssueReportReferenceRow(
    label: String,
    value: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label.slockDisplayUppercase(),
            style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 11.sp, lineHeight = 14.sp),
            color = SlockColor.TextBlack.copy(alpha = 0.6f)
        )
        Text(
            text = value,
            style = SlockType.Caption.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp),
            color = SlockColor.TextBlack
        )
    }
}

@Composable
private fun MembersAgentIssueReportReceiptDialog(
    receipt: AgentIssueReportResult,
    referenceCopied: Boolean,
    onCopyReference: () -> Unit,
    onDismiss: () -> Unit
) {
    SlockUnifiedDialog(
        title = stringResource(MR.strings.member_report_issue_success_title).slockDisplayUppercase(),
        maxWidth = 384.dp,
        contentPadding = 24.dp,
        titleFontSize = 18,
        titleLineHeight = 22,
        onDismiss = onDismiss
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(SlockColor.AccentLime)
                    .border(SlockStroke.Default, SlockColor.BorderBlack),
                contentAlignment = Alignment.Center
            ) {
                SlockLucideIcon(
                    SlockLucideIconName.CircleCheckBig,
                    modifier = Modifier.size(24.dp),
                    color = SlockColor.TextBlackArgb
                )
            }
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = stringResource(MR.strings.member_report_issue_success_body),
                    style = SlockType.ButtonBold,
                    color = SlockColor.TextBlack,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = stringResource(MR.strings.member_report_issue_success_hint),
                    style = SlockType.Caption.copy(fontSize = 12.sp, lineHeight = 17.sp),
                    color = SlockColor.TextBlack.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SlockColor.SurfaceWhite)
                    .border(SlockStroke.Default, SlockColor.BorderBlack)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SlockSectionEyebrow(label = stringResource(MR.strings.member_report_issue_reference_label))
                MembersAgentIssueReportReferenceRow(
                    label = stringResource(MR.strings.member_report_issue_report_id),
                    value = receipt.reportId
                )
                receipt.artifactId?.takeIf { it.isNotBlank() }?.let { artifactId ->
                    MembersAgentIssueReportReferenceRow(
                        label = stringResource(MR.strings.member_report_issue_artifact_id),
                        value = artifactId
                    )
                }
                MembersAgentIssueReportReferenceRow(
                    label = stringResource(MR.strings.member_report_issue_server_id),
                    value = receipt.serverId
                )
                if (receipt.transcriptRequested) {
                    MembersAgentIssueReportReferenceRow(
                        label = stringResource(MR.strings.member_report_issue_runtime_transcript_label),
                        value = stringResource(MR.strings.member_report_issue_runtime_transcript_queued)
                    )
                }
                SlockBrutalActionButton(
                    label =
                        if (referenceCopied) {
                            stringResource(MR.strings.member_report_issue_reference_copied)
                        } else {
                            stringResource(MR.strings.member_report_issue_copy_reference)
                        },
                    leadingIcon = if (referenceCopied) SlockLucideIconName.Check else SlockLucideIconName.Copy,
                    backgroundColor = SlockColor.SurfaceWhite,
                    minHeight = 28.dp,
                    onClick = onCopyReference
                )
            }
            SlockDialogFooter {
                SlockBrutalActionButton(
                    label = stringResource(MR.strings.common_done),
                    backgroundColor = SlockColor.AccentLime,
                    onClick = onDismiss
                )
            }
        }
    }
}

@Composable
internal fun MembersRestartResetDialog(
    agentName: String,
    selectedAction: MembersAgentLifecycleAction,
    busy: Boolean,
    errorMessage: String?,
    onSelectedActionChange: (MembersAgentLifecycleAction) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    SlockUnifiedDialog(
        title = stringResource(MR.strings.member_dialog_restart_title).replace("%1\$s", agentName),
        onDismiss = onDismiss,
        dismissEnabled = !busy,
        maxWidth = 520.dp,
        // Web ResetAgentDialog is a ConfirmDialog: 18px title, text-sm option
        // labels, text-xs descriptions. Match the sibling lifecycle confirm dialog.
        titleFontSize = 18,
        titleLineHeight = 22,
        titleMaxLines = 2,
        contentSpacing = SlockSpacing.Md
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
        ) {
            errorMessage?.takeIf { it.isNotBlank() }?.let {
                item {
                    MembersRestartResetNotice(
                        text = stringResource(MR.strings.member_error_reset_agent, it),
                        color = SlockColor.AccentOrange
                    )
                }
            }
            item {
                MembersRestartResetActionCard(
                    action = MembersAgentLifecycleAction.Restart,
                    selected = selectedAction == MembersAgentLifecycleAction.Restart,
                    enabled = !busy,
                    onClick = { onSelectedActionChange(MembersAgentLifecycleAction.Restart) }
                )
            }
            item {
                MembersRestartResetActionCard(
                    action = MembersAgentLifecycleAction.ResetSession,
                    selected = selectedAction == MembersAgentLifecycleAction.ResetSession,
                    enabled = !busy,
                    onClick = { onSelectedActionChange(MembersAgentLifecycleAction.ResetSession) }
                )
            }
            item {
                MembersRestartResetActionCard(
                    action = MembersAgentLifecycleAction.ResetFull,
                    selected = selectedAction == MembersAgentLifecycleAction.ResetFull,
                    enabled = !busy,
                    onClick = { onSelectedActionChange(MembersAgentLifecycleAction.ResetFull) }
                )
            }
            if (selectedAction == MembersAgentLifecycleAction.ResetFull) {
                item {
                    MembersRestartResetNotice(
                        text = stringResource(MR.strings.member_dialog_full_reset_warning),
                        color = SlockColor.AccentOrange
                    )
                }
            }
        }
        // Same footer as the app's other confirm dialogs (and web ConfirmDialog):
        // right-aligned buttons at their natural width. The old per-action min
        // widths (124dp + up to 220dp) overflowed narrow phones.
        SlockDialogFooter {
            SlockBrutalActionButton(
                label = stringResource(MR.strings.common_cancel),
                leadingIcon = SlockLucideIconName.X,
                enabled = !busy,
                backgroundColor = SlockColor.SurfaceWhite,
                onClick = onDismiss
            )
            SlockBrutalActionButton(
                label = if (busy) stringResource(MR.strings.member_action_restarting) else selectedAction.restartResetButtonLabel(),
                leadingIcon = SlockLucideIconName.RotateCcw,
                enabled = !busy,
                backgroundColor = selectedAction.restartResetAccentColor(),
                onClick = onConfirm
            )
        }
    }
}

@Composable
private fun MembersRestartResetActionCard(
    action: MembersAgentLifecycleAction,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    // Every card reserves the shadow gutter and keeps the shadow box; only its color
    // follows selection. Reserving it only on the selected card made that card 4dp
    // narrower than its siblings and shifted the list whenever the selection changed.
    val shadowOffset = 4.dp
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(end = shadowOffset, bottom = shadowOffset)
    ) {
        Box(
            modifier =
                Modifier
                    .matchParentSize()
                    .offset(x = shadowOffset, y = shadowOffset)
                    .background(if (selected) SlockColor.ShadowBlack else Color.Transparent)
        )
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(if (selected) action.restartResetTintColor() else SlockColor.SurfaceWhite)
                    .border(
                        if (selected) SlockStroke.Default else SlockStroke.Thin,
                        if (selected) SlockColor.BorderBlack else SlockColor.BorderBlack.copy(alpha = 0.32f)
                    )
                    .clickable(enabled = enabled, onClick = onClick)
                    .padding(horizontal = SlockSpacing.Md, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
        ) {
            Text(
                text = action.restartResetTitle(),
                style = SlockType.ButtonBold.copy(fontSize = 14.sp, lineHeight = 20.sp),
                color = SlockColor.TextBlack,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = action.restartResetDescription(),
                style = SlockType.Body.copy(fontSize = 12.sp, lineHeight = 16.sp),
                color = SlockColor.TextMutedGray
            )
        }
    }
}

@Composable
private fun MembersRestartResetNotice(text: String, color: Color) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(color)
                .border(SlockStroke.Thin, SlockColor.BorderBlack)
                .padding(SlockSpacing.Sm),
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs),
        verticalAlignment = Alignment.Top
    ) {
        SlockLucideIcon(SlockLucideIconName.TriangleAlert, modifier = Modifier.size(16.dp), color = SlockColor.TextBlackArgb)
        Text(
            text = text,
            style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 16.sp),
            color = SlockColor.TextBlack
        )
    }
}

private fun MembersAgentLifecycleAction.isRestartResetDialogAction(): Boolean =
    this == MembersAgentLifecycleAction.Restart ||
        this == MembersAgentLifecycleAction.ResetSession ||
        this == MembersAgentLifecycleAction.ResetFull

@Composable
private fun MembersAgentLifecycleAction.restartResetTitle(): String =
    when (this) {
        MembersAgentLifecycleAction.Restart -> stringResource(MR.strings.member_action_restart_agent).slockDisplayUppercase()
        MembersAgentLifecycleAction.ResetSession -> stringResource(MR.strings.member_action_restart_session).slockDisplayUppercase()
        MembersAgentLifecycleAction.ResetFull -> stringResource(MR.strings.member_action_full_reset_restart).slockDisplayUppercase()
        else -> localizedLabel().slockDisplayUppercase()
    }

@Composable
private fun MembersAgentLifecycleAction.restartResetButtonLabel(): String =
    when (this) {
        MembersAgentLifecycleAction.Restart -> stringResource(MR.strings.member_action_restart_agent)
        MembersAgentLifecycleAction.ResetSession -> stringResource(MR.strings.member_action_restart_session)
        MembersAgentLifecycleAction.ResetFull -> stringResource(MR.strings.member_action_full_reset_restart)
        else -> localizedLabel()
    }

@Composable
private fun MembersAgentLifecycleAction.restartResetDescription(): String =
    when (this) {
        MembersAgentLifecycleAction.Restart ->
            stringResource(MR.strings.member_dialog_restart_description)
        MembersAgentLifecycleAction.ResetSession ->
            stringResource(MR.strings.member_dialog_reset_session_description)
        MembersAgentLifecycleAction.ResetFull ->
            stringResource(MR.strings.member_dialog_full_reset_description)
        else -> ""
    }

private fun MembersAgentLifecycleAction.restartResetAccentColor(): Color =
    when (this) {
        MembersAgentLifecycleAction.ResetFull -> SlockColor.AccentRed
        MembersAgentLifecycleAction.ResetSession -> SlockColor.AccentOrange
        else -> SlockColor.AccentCyan
    }

private fun MembersAgentLifecycleAction.restartResetTintColor(): Color =
    SlockColor.AppBgLightBlue

@Composable
private fun MembersAgentTabBar(
    tabs: List<MembersAgentDetailTab>,
    selectedTab: MembersAgentDetailTab,
    onSelectedTabChange: (MembersAgentDetailTab) -> Unit,
    onReorderTabs: ((List<MembersAgentDetailTab>) -> Unit)? = null,
    autoScrollSelectedTab: Boolean = true,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    var draggedTab by remember(tabs) { mutableStateOf<MembersAgentDetailTab?>(null) }
    var dragStartIndex by remember(tabs) { mutableStateOf(-1) }
    var dragOffsetX by remember(tabs) { mutableStateOf(0f) }
    if (autoScrollSelectedTab) {
        LaunchedEffect(tabs, selectedTab) {
            if (tabs.isNotEmpty() && !listState.isScrollInProgress) {
                listState.animateScrollToItem(membersAgentTabVisibleAnchorIndex(tabs, selectedTab))
            }
        }
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(SlockTopTabBarDefaults.Height)
            .background(SlockColor.SurfaceWhite)
    ) {
        LazyRow(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) {
            items(tabs.size, key = { "members-agent-tab-${tabs[it].name}" }) { index ->
                val tab = tabs[index]
                val spec = tab.toSlockTabSpec()
                val selected = tab == selectedTab
                val dragging = tab == draggedTab
                Row(
                    modifier = Modifier
                        .widthIn(min = spec.width)
                        .fillMaxHeight()
                        .background(
                            when {
                                dragging -> SlockColor.AccentCyan
                                selected -> SlockColor.BrandYellow
                                else -> SlockColor.SurfaceWhite
                            }
                        )
                        .clickable { onSelectedTabChange(tab) }
                        .pointerInput(tabs, tab, onReorderTabs) {
                            if (onReorderTabs == null) return@pointerInput
                            val stepWidthPx = spec.width.toPx().coerceAtLeast(1f)
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    draggedTab = tab
                                    dragStartIndex = index
                                    dragOffsetX = 0f
                                },
                                onDragEnd = {
                                    val fromIndex = dragStartIndex
                                    val toIndex = (fromIndex + (dragOffsetX / stepWidthPx).roundToInt())
                                        .coerceIn(0, tabs.lastIndex)
                                    if (fromIndex in tabs.indices && toIndex != fromIndex) {
                                        onReorderTabs(tabs.membersMoveTab(fromIndex, toIndex))
                                    }
                                    draggedTab = null
                                    dragStartIndex = -1
                                    dragOffsetX = 0f
                                },
                                onDragCancel = {
                                    draggedTab = null
                                    dragStartIndex = -1
                                    dragOffsetX = 0f
                                }
                            ) { _, dragAmount ->
                                dragOffsetX += dragAmount.x
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = SlockSpacing.Xs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally)
                ) {
                    SlockLucideIcon(spec.iconName, modifier = Modifier.size(14.dp), color = SlockColor.TextBlackArgb)
                    Text(
                        text = spec.label,
                        style = SlockType.ButtonBold.copy(fontSize = 12.sp, lineHeight = 14.sp, color = SlockColor.TextBlack),
                        color = SlockColor.TextBlack,
                        maxLines = 1
                    )
                }
                Box(
                    modifier = Modifier
                        .height(SlockTopTabBarDefaults.Height - SlockStroke.Default)
                        .width(SlockStroke.Default)
                        .background(SlockColor.BorderBlack)
                )
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(SlockStroke.Default).background(SlockColor.BorderBlack))
    }
}

internal data class MembersAgentRuntimeErrorBannerState(
    val errorText: String,
    val showViewLogs: Boolean
)

internal fun membersAgentRuntimeErrorBannerState(
    memberKind: MemberKind,
    selectedTab: MembersAgentDetailTab,
    runtimeErrorText: String?
): MembersAgentRuntimeErrorBannerState? {
    if (memberKind != MemberKind.Agent) return null
    val errorText = runtimeErrorText?.takeIf { it.isNotBlank() } ?: return null
    return MembersAgentRuntimeErrorBannerState(
        errorText = errorText,
        showViewLogs = selectedTab != MembersAgentDetailTab.Activity
    )
}

@Composable
private fun MembersAgentDetailTab.toSlockTabSpec(): SlockTabSpec<MembersAgentDetailTab> =
    SlockTabSpec(
        key = this,
        label = localizedLabel(),
        iconName = iconName,
        width = membersAgentTabMinimumWidth(this)
    )

internal fun membersAgentTabMinimumWidth(tab: MembersAgentDetailTab): Dp =
    when (tab) {
        MembersAgentDetailTab.Profile,
        MembersAgentDetailTab.Activity,
        MembersAgentDetailTab.Apps -> SlockTopTabBarDefaults.TabWidth
        MembersAgentDetailTab.Chat -> 78.dp
        MembersAgentDetailTab.Reminders,
        MembersAgentDetailTab.Workspace -> 112.dp
    }

@Composable
private fun MembersAgentDetailStateNotice(
    state: MembersAgentDetailLoadState,
    modifier: Modifier = Modifier
) {
    val errorStrings = localizedErrorPresentationStrings()
    val message =
        when (state) {
            MembersAgentDetailLoadState.Loading -> stringResource(MR.strings.member_state_loading_agent_profile)
            MembersAgentDetailLoadState.Empty -> stringResource(MR.strings.member_state_agent_profile_unavailable)
            is MembersAgentDetailLoadState.Error ->
                "${stringResource(MR.strings.member_error_load_agent_profile)}: ${state.error.userFacingMessage(errorStrings)}"
            is MembersAgentDetailLoadState.NoPermission ->
                "${stringResource(MR.strings.member_error_private_surfaces_hidden)}: ${state.blocker.userFacingMessage(errorStrings)}"
            MembersAgentDetailLoadState.Idle -> null
            is MembersAgentDetailLoadState.Content ->
                if (state.refreshing) stringResource(MR.strings.member_state_refreshing_agent_profile) else null
        }
    val resolved = message ?: return
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .background(SlockColor.SurfaceCream)
            .border(SlockStroke.Default, SlockColor.BorderBlack),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = resolved,
            style = SlockType.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp).withSlockFontWeight(FontWeight.Bold),
            color = SlockColor.TextBlack,
            modifier = Modifier.padding(horizontal = SlockSpacing.Sm, vertical = SlockSpacing.Xs)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MembersAgentRuntimeErrorBanner(
    errorText: String,
    canViewPrivate: Boolean,
    showViewLogs: Boolean,
    onViewLogs: () -> Unit,
    onCopyInfo: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            // deliberate react-parity wash (web bg-*/N tint family); triaged token-audit #1043
            .background(SlockColor.AccentOrange.copy(alpha = 0.2f))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = MembersPageInset, vertical = SlockSpacing.Sm),
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm),
            verticalAlignment = Alignment.Top
        ) {
            SlockStatusIndicator(
                color = SlockColor.AccentOrange,
                size = 9.dp,
                pulsing = false
            )
            Text(
                text = if (canViewPrivate) errorText else stringResource(MR.strings.member_state_agent_error),
                style = SlockType.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp)
                    .withSlockFontWeight(FontWeight.Bold),
                color = SlockColor.TextBlack,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (canViewPrivate) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm),
                    verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
                ) {
                    MembersAgentRuntimeErrorAction(
                        label = stringResource(MR.strings.member_action_copy_info),
                        onClick = onCopyInfo
                    )
                    if (showViewLogs) {
                        MembersAgentRuntimeErrorAction(
                            label = stringResource(MR.strings.member_action_view_logs),
                            onClick = onViewLogs
                        )
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(SlockStroke.Default).background(SlockColor.BorderBlack))
    }
}

@Composable
private fun MembersAgentRuntimeErrorAction(
    label: String,
    onClick: () -> Unit
) {
    Text(
        text = label,
        style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 14.sp)
            .withSlockFontWeight(FontWeight.Bold)
            .copy(textDecoration = TextDecoration.Underline),
        color = SlockColor.TextBlack.copy(alpha = 0.6f),
        modifier = Modifier.clickable(onClick = onClick)
    )
}

@Composable
private fun MembersHumanDetailSections(
    member: MemberProfile,
    timeContext: SlockTimeContext,
    memberAvatarResolver: SlockMemberAvatarResolver = EmptySlockMemberAvatarResolver,
    agentStatusResolver: SlockAgentStatusResolver = EmptySlockAgentStatusResolver,
    fixtureStatusFallbackEnabled: Boolean = false,
    actorRole: String? = null,
    accessMode: MemberDetailAccessMode = MemberDetailAccessMode.LocalFull,
    currentUserId: String? = null,
    currentServerId: String? = null,
    ownerCount: Int? = null,
    onUpdateRole: (suspend (String, MembersHumanServerRole) -> DataResult<SettingsServerActionResult>)? = null,
    onRemoveMember: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        MembersHumanProfileSummary(member, memberAvatarResolver)
        MembersHumanDescriptionSection(member)
        MembersHumanInfoSection(
            member = member,
            timeContext = timeContext,
            actorRole = actorRole,
            accessMode = accessMode,
            currentUserId = currentUserId,
            currentServerId = currentServerId,
            ownerCount = ownerCount,
            onUpdateRole = onUpdateRole
        )
        MembersHumanCreatedAgentsSection(
            member = member,
            agentStatusResolver = agentStatusResolver,
            memberAvatarResolver = memberAvatarResolver,
            fixtureStatusFallbackEnabled = fixtureStatusFallbackEnabled
        )
        if (onRemoveMember != null) {
            MembersHumanActionsSection(onRemoveMember)
        }
    }
}

@Composable
private fun MembersHumanProfileSummary(
    member: MemberProfile,
    memberAvatarResolver: SlockMemberAvatarResolver = EmptySlockMemberAvatarResolver
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = SlockSpacing.Md),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
    ) {
        MembersAvatar(
            member = member,
            avatarSize = 74.dp,
            statusSize = 0.dp,
            memberAvatarResolver = memberAvatarResolver,
            showStatusOverlay = false
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
        ) {
            Text(
                text = member.displayName,
                style = SlockType.PageTitle.copy(fontSize = 20.sp, lineHeight = 24.sp),
                color = SlockColor.TextBlack,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = member.detailHandle,
                    style = SlockType.Caption.copy(fontFamily = SlockMonoFamily, fontSize = 14.sp, lineHeight = 18.sp),
                    color = SlockColor.TextMutedGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (member.membershipStatus == "removed") {
                    MembersHumanRoleChip(stringResource(MR.strings.member_role_removed), SlockColor.EmptyStateGray)
                }
            }
        }
    }
}

@Composable
private fun MembersHumanDescriptionSection(member: MemberProfile) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        MembersDetailSectionDivider()
        MembersDetailSectionTitle(stringResource(MR.strings.member_section_description).slockDisplayUppercase())
        val descriptionMissing = member.description.isNullOrBlank()
        Text(
            text = member.description?.takeIf { it.isNotBlank() } ?: stringResource(MR.strings.member_state_no_description),
            style = SlockType.Body.copy(
                fontSize = 15.sp,
                lineHeight = 22.sp,
                fontStyle = if (descriptionMissing) FontStyle.Italic else FontStyle.Normal
            ),
            color = if (descriptionMissing) {
                SlockColor.TextMutedGray.copy(alpha = 0.75f)
            } else {
                SlockColor.TextBlack
            }
        )
    }
}

@Composable
private fun MembersHumanInfoSection(
    member: MemberProfile,
    timeContext: SlockTimeContext,
    actorRole: String?,
    accessMode: MemberDetailAccessMode,
    currentUserId: String?,
    currentServerId: String?,
    ownerCount: Int?,
    onUpdateRole: (suspend (String, MembersHumanServerRole) -> DataResult<SettingsServerActionResult>)?
) {
    val errorStrings = localizedErrorPresentationStrings()
    val joined = member.joinedAt?.takeIf { it.isNotBlank() }?.membersAgentCreatedDate(timeContext)
    val initialRole = member.serverRole.toMembersHumanServerRoleOrNull()
        ?: member.roleDescription.toMembersHumanServerRoleOrNull()
    var displayedRole by remember(member.id.value, initialRole?.wireValue) { mutableStateOf(initialRole) }
    var draftRole by remember(member.id.value, initialRole?.wireValue) {
        mutableStateOf(initialRole ?: MembersHumanServerRole.Member)
    }
    var editingRole by remember(member.id.value) { mutableStateOf(false) }
    var roleMenuExpanded by remember(member.id.value) { mutableStateOf(false) }
    var savingRole by remember(member.id.value) { mutableStateOf(false) }
    var roleError by remember(member.id.value) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val permissionErrorTemplate = membersStringTemplate(MR.strings.member_error_permission)
    val saveErrorTemplate = membersStringTemplate(MR.strings.member_error_save)
    val editableRoles = membersEditableHumanServerRoles(
        actorRole = actorRole,
        accessMode = accessMode,
        targetRole = displayedRole?.wireValue,
        targetMembershipStatus = member.membershipStatus,
        targetMemberId = member.id.value,
        currentUserId = currentUserId,
        targetServerId = member.serverId?.value,
        currentServerId = currentServerId,
        ownerCount = ownerCount
    )
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
    ) {
        MembersDetailSectionDivider()
        MembersDetailSectionTitle(stringResource(MR.strings.member_section_info).slockDisplayUppercase())
        MembersHumanInfoItem(label = stringResource(MR.strings.member_label_role)) {
            if (editingRole) {
                Column(verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)) {
                    MembersServerRoleEditor(
                        displayValue = draftRole.localizedLabel(),
                        badgeBackgroundColor = draftRole.backgroundColor,
                        expanded = roleMenuExpanded,
                        enabled = !savingRole,
                        saving = savingRole,
                        saveEnabled = draftRole != displayedRole,
                        options = editableRoles.map { role ->
                            SlockRaftUiSelectionOption(
                                id = role.wireValue,
                                label = role.localizedLabel(),
                                selected = role == draftRole
                            )
                        },
                        selectedId = draftRole.wireValue,
                        error = roleError,
                        onToggle = { roleMenuExpanded = !roleMenuExpanded },
                        onSelect = { roleId ->
                            roleId.toMembersHumanServerRoleOrNull()?.let {
                                draftRole = it
                                roleMenuExpanded = false
                                roleError = null
                            }
                        },
                        onSave = {
                                onUpdateRole?.let { update -> scope.launch {
                                    savingRole = true
                                    roleError = null
                                    val result = try {
                                        update(member.id.value, draftRole)
                                    } catch (cancellation: CancellationException) {
                                        throw cancellation
                                    } catch (_: Throwable) {
                                        DataResult.Error(ScreenError("member_role_save_failed"))
                                    }
                                    when (result.membersHumanRoleSaveOutcome()) {
                                        MembersHumanRoleSaveOutcome.Success -> {
                                            displayedRole = draftRole
                                            editingRole = false
                                            roleMenuExpanded = false
                                        }
                                        MembersHumanRoleSaveOutcome.NoPermission -> {
                                            val blocker = (result as DataResult.NoPermission).blocker
                                            roleError =
                                                membersFormattedPermissionError(
                                                    permissionErrorTemplate,
                                                    blocker,
                                                    errorStrings
                                                )
                                        }
                                        MembersHumanRoleSaveOutcome.Error -> {
                                            val message =
                                                (result as? DataResult.Error)?.error?.userFacingMessage(errorStrings)
                                                ?: (result as? DataResult.Content)?.data?.message
                                                ?: "member_role_save_failed".userFacingErrorCodeMessage(errorStrings)
                                            roleError = saveErrorTemplate.membersFormat(message)
                                        }
                                    }
                                    savingRole = false
                                } }
                        },
                        onCancel = {
                            draftRole = displayedRole ?: MembersHumanServerRole.Member
                            roleError = null
                            roleMenuExpanded = false
                            editingRole = false
                        }
                    )
                }
            } else {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MembersHumanRoleChip(
                        label = displayedRole?.localizedLabel()
                            ?: member.roleDescription?.takeIf { it.isNotBlank() }
                            ?: stringResource(MR.strings.member_role_member),
                        backgroundColor = displayedRole?.backgroundColor ?: member.membersHumanRoleChipColor()
                    )
                    if (onUpdateRole != null && editableRoles.isNotEmpty()) {
                        MembersProfilePencilButton(
                            onClick = {
                                draftRole = editableRoles.first()
                                roleError = null
                                roleMenuExpanded = true
                                editingRole = true
                            }
                        )
                    }
                }
            }
        }
        member.email?.takeIf { it.isNotBlank() }?.let { email ->
            MembersHumanInfoItem(label = stringResource(MR.strings.member_label_email)) {
                Text(
                    text = email,
                    style = SlockType.Input.copy(fontFamily = SlockMonoFamily, fontSize = 16.sp, lineHeight = 22.sp),
                    color = SlockColor.TextBlack,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        joined?.takeIf { it.isNotBlank() }?.let { joinedDate ->
            MembersHumanInfoItem(label = stringResource(MR.strings.member_label_joined)) {
                Text(
                    text = joinedDate,
                    style = SlockType.Input.copy(fontFamily = SlockMonoFamily, fontSize = 16.sp, lineHeight = 22.sp),
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun MembersHumanInfoItem(
    label: String,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
    ) {
        Text(
            text = label,
            style = SlockType.Body.copy(fontSize = 14.sp, lineHeight = 20.sp),
            color = SlockColor.TextMutedGray,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        content()
    }
}

@Composable
private fun MembersHumanRoleChip(
    label: String,
    backgroundColor: Color
) {
    Box(
        modifier = Modifier
            .border(SlockStroke.Default, SlockColor.BorderBlack)
            .background(backgroundColor)
            .padding(horizontal = SlockSpacing.Sm, vertical = 3.dp)
    ) {
        Text(
            text = label,
            style = SlockType.ButtonBold.copy(fontSize = 13.sp, lineHeight = 18.sp),
            color = SlockColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun MembersServerRoleEditor(
    displayValue: String,
    badgeBackgroundColor: Color,
    expanded: Boolean,
    enabled: Boolean,
    saving: Boolean,
    saveEnabled: Boolean,
    options: List<SlockRaftUiSelectionOption>,
    selectedId: String,
    error: String?,
    onToggle: () -> Unit,
    onSelect: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
    ) {
        Row(
            modifier = Modifier
                .widthIn(min = 160.dp)
                .height(33.dp)
                .background(badgeBackgroundColor)
                .border(SlockStroke.Default, SlockColor.BorderBlack)
                .clickable(enabled = enabled, onClick = onToggle)
                .padding(horizontal = SlockSpacing.Sm, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = displayValue,
                style = SlockType.ButtonBold.copy(fontSize = 13.sp, lineHeight = 18.sp),
                color = SlockColor.TextBlack,
                modifier = Modifier.weight(1f)
            )
            SlockLucideIcon(
                SlockLucideIconName.ChevronDown,
                modifier = Modifier.size(14.dp),
                color = SlockColor.TextBlackArgb,
                rotationDegrees = if (expanded) 180f else 0f
            )
        }
        if (expanded && enabled) {
            SlockRaftUiSelectionPopover(
                title = stringResource(MR.strings.member_label_role),
                options = options,
                selectedId = selectedId,
                modifier = Modifier.widthIn(min = 160.dp),
                showHeader = false,
                onSelect = { option -> onSelect(option.id) }
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)) {
            SlockBrutalActionButton(
                label = if (saving) stringResource(MR.strings.member_action_saving) else stringResource(MR.strings.common_save),
                onClick = onSave,
                enabled = enabled && saveEnabled,
                backgroundColor = SlockColor.BrandYellow
            )
            SlockBrutalActionButton(
                label = stringResource(MR.strings.common_cancel),
                onClick = onCancel,
                enabled = enabled,
                backgroundColor = SlockColor.SurfaceWhite
            )
        }
        error?.let {
            Text(
                text = it,
                style = SlockType.Caption.copy(fontSize = 10.sp, lineHeight = 14.sp)
                    .withSlockFontWeight(FontWeight.Bold),
                color = SlockColor.AccentRed
            )
        }
    }
}

/**
 * Title-cases a role name we do not recognise so it sits beside "Admin"/"Member" without
 * looking like a different kind of value. The parser lowercases every role
 * (`normalizedServerRoleOrNull`), so the server's original casing is already gone by here --
 * this restores presentation only, it does not recover the wire form.
 */
private fun String.membersRoleDisplayName(): String =
    replace('_', ' ')
        .replace('-', ' ')
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

private fun MembersAgentServerRoleDisplay.rememberKey(): String =
    when (this) {
        is MembersAgentServerRoleDisplay.Known -> role.wireValue
        is MembersAgentServerRoleDisplay.Unrecognized -> "unrecognized:$rawName"
        MembersAgentServerRoleDisplay.Hidden -> "hidden"
    }

@Composable
private fun MembersAgentServerRoleChip(display: MembersAgentServerRoleDisplay) {
    // Hidden renders nothing: no membership row, or a deleted agent. Any chip here would
    // assert a membership that does not exist (#261).
    if (display is MembersAgentServerRoleDisplay.Hidden) return
    val role = (display as? MembersAgentServerRoleDisplay.Known)?.role
    val label =
        when (display) {
            is MembersAgentServerRoleDisplay.Known -> display.role.localizedLabel()
            // Shown as sent, only title-cased to match the recognised chips. We have no
            // colour for a role we do not know, so it takes the neutral background.
            is MembersAgentServerRoleDisplay.Unrecognized -> display.rawName.membersRoleDisplayName()
            MembersAgentServerRoleDisplay.Hidden -> ""
        }
    val backgroundColor = role?.backgroundColor ?: SlockColor.EmptyStateGray
    Box(
        modifier = Modifier
            .border(SlockStroke.Default, SlockColor.BorderBlack)
            .background(backgroundColor)
            .padding(horizontal = SlockSpacing.Sm, vertical = 3.dp)
    ) {
        Text(
            text = label,
            style = SlockType.ButtonBold.copy(fontSize = 13.sp, lineHeight = 18.sp),
            color = SlockColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun MembersAgentRoleHelpDialog(onDismiss: () -> Unit) {
    SlockUnifiedDialog(
        title = stringResource(MR.strings.member_title_agent_roles).slockDisplayUppercase(),
        onDismiss = onDismiss,
        // Web RolePermissionHelpDialog: DialogCard max-w-md, rows space-y-3.
        maxWidth = 448.dp
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MembersAgentRoleHelpRow(
                role = stringResource(MR.strings.member_role_admin),
                summary = stringResource(MR.strings.member_roles_help_admin_summary),
                details = stringResource(MR.strings.member_roles_help_agent_admin_details)
            )
            MembersAgentRoleHelpRow(
                role = stringResource(MR.strings.member_role_member),
                summary = stringResource(MR.strings.member_roles_help_member_summary),
                details = stringResource(MR.strings.member_roles_help_agent_member_details)
            )
        }
    }
}

@Composable
private fun MembersAgentRoleHelpRow(
    role: String,
    summary: String,
    details: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(SlockStroke.Default, SlockColor.BorderBlack.copy(alpha = 0.20f))
            .background(SlockColor.SurfaceWhite)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = role,
                style = SlockType.Body.copy(fontSize = 14.sp, lineHeight = 18.sp)
                    .withSlockFontWeight(FontWeight.Bold),
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = summary,
                style = SlockType.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp)
                    .withSlockFontWeight(FontWeight.Bold),
                color = SlockColor.TextBlack.copy(alpha = 0.60f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = details,
            style = SlockType.Body.copy(fontSize = 14.sp, lineHeight = 22.sp),
            color = SlockColor.TextBlack.copy(alpha = 0.75f)
        )
    }
}

@Composable
private fun MembersHumanCreatedAgentsSection(
    member: MemberProfile,
    agentStatusResolver: SlockAgentStatusResolver = EmptySlockAgentStatusResolver,
    memberAvatarResolver: SlockMemberAvatarResolver = EmptySlockMemberAvatarResolver,
    fixtureStatusFallbackEnabled: Boolean = false
) {
    val createdAgents = member.createdAgents
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        MembersDetailSectionDivider()
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
        ) {
            MembersDetailSectionTitle(stringResource(MR.strings.member_section_created_agents).slockDisplayUppercase())
            Text(
                text = createdAgents.size.toString(),
                style = SlockType.Input.copy(fontSize = 13.sp, lineHeight = 18.sp),
                color = SlockColor.TextMutedGray.copy(alpha = 0.7f)
            )
        }
        if (createdAgents.isEmpty()) {
            Text(
                text = stringResource(MR.strings.member_state_no_created_agents),
                style = SlockType.Body.copy(
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                    fontStyle = FontStyle.Italic
                ),
                color = SlockColor.TextMutedGray.copy(alpha = 0.75f)
            )
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
            ) {
                createdAgents.forEach { agent ->
                    MembersHumanCreatedAgentRow(
                        agent = agent,
                        agentStatusResolver = agentStatusResolver,
                        memberAvatarResolver = memberAvatarResolver,
                        fixtureStatusFallbackEnabled = fixtureStatusFallbackEnabled
                    )
                }
            }
        }
    }
}

@Composable
private fun MembersHumanActionsSection(onRemoveMember: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        MembersDetailSectionDivider()
        MembersDetailSectionTitle(stringResource(MR.strings.member_section_actions).slockDisplayUppercase())
        SlockBrutalActionButton(
            label = stringResource(MR.strings.member_action_remove_member),
            leadingIcon = SlockLucideIconName.Trash2,
            minHeight = 40.dp,
            backgroundColor = SlockColor.AccentRed,
            textStyle = SlockType.ButtonBold.copy(fontSize = 14.sp, lineHeight = 18.sp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 3.dp),
            onClick = onRemoveMember
        )
    }
}

@Composable
private fun MembersHumanCreatedAgentRow(
    agent: AgentProfile,
    agentStatusResolver: SlockAgentStatusResolver = EmptySlockAgentStatusResolver,
    memberAvatarResolver: SlockMemberAvatarResolver = EmptySlockMemberAvatarResolver,
    fixtureStatusFallbackEnabled: Boolean = false
) {
    val profile = remember(agent) { agent.toStaticMembersProfile() }
    val observedIndicatorState =
        resolveMembersAgentStatusIndicatorStateOrNull(
            canonicalState = rememberSlockAgentStatusIndicatorState(agentStatusResolver, profile.id.value),
            fixtureFallback =
                if (fixtureStatusFallbackEnabled) {
                    agent.toFixtureSlockAgentStatusIndicatorState()
                } else {
                    null
                },
            fixtureStatusFallbackEnabled = fixtureStatusFallbackEnabled
        )
    val statusIndicatorState = observedIndicatorState ?: unknownMembersAgentStatusIndicatorState()
    val statusDisplayState = statusIndicatorState.toMembersAgentStatusDisplayState()
    val observedDisplayState = observedIndicatorState?.toMembersAgentStatusDisplayState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(SlockStroke.Thin, SlockColor.BorderBlack.copy(alpha = 0.25f))
            .background(SlockColor.SurfaceWhite)
            .padding(horizontal = 12.dp, vertical = SlockSpacing.Sm),
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MembersAvatar(
            member = profile,
            avatarSize = 32.dp,
            statusSize = 0.dp,
            memberAvatarResolver = memberAvatarResolver,
            showStatusOverlay = false
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = profile.displayName,
                style = SlockType.Body.copy(fontSize = 13.sp, lineHeight = 18.sp)
                    .withSlockFontWeight(FontWeight.Bold),
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = agent.runtime?.takeIf { it.isNotBlank() }?.let { getRuntimeDisplayName(it) }
                    ?: profile.detailRoleLabel,
                style = SlockType.Input.copy(fontSize = 11.sp, lineHeight = 15.sp),
                color = SlockColor.TextMutedGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        // Unknown is not Offline: no dot until something is observed (task #256).
        observedDisplayState?.let { observed ->
            SlockStatusIndicator(
                color = observed.color,
                size = 10.dp,
                pulsing = observed.pulsing
            )
        }
    }
}

private val MembersAgentAvatarPickerCellSize = 40.dp
private val MembersAgentAvatarPickerAvatarSize = 36.dp

@Composable
private fun MembersAgentAvatarChoiceGrid(
    member: MemberProfile,
    uploading: Boolean,
    onUploadClick: () -> Unit,
    onClear: () -> Unit,
    onSelect: (String) -> Unit
) {
    // React parity: each cell renders the actual pixel art (or upload icon
    // for the first cell) inside a 40dp cell with a 2dp border, mirroring
    // `AgentDetailPanel.tsx`. Active selection gets a pink border
    // + pink tint background (React `border-brutal-pink bg-brutal-pink/20`).
    val activeCustomAvatar = member.avatarUrl != null
    val activePixelKey = if (activeCustomAvatar) {
        null
    } else {
        (member.avatar?.wireValue?.removePrefix("pixel:") ?: PixelAvatarActiveDefaultKey)
    }
    @OptIn(ExperimentalLayoutApi::class)
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
    ) {
        Box(
            modifier = Modifier
                .size(MembersAgentAvatarPickerCellSize)
                .border(SlockStroke.Default, if (activeCustomAvatar) SlockColor.AccentPink else SlockColor.BorderBlack)
                // deliberate react-parity wash (web bg-*/N tint family); triaged token-audit #1043
                .background(if (activeCustomAvatar) SlockColor.AccentPink.copy(alpha = 0.2f) else Color.Transparent)
                .clickable(enabled = !uploading) { onUploadClick() },
            contentAlignment = Alignment.Center
        ) {
            if (activeCustomAvatar) {
                SlockAvatar(
                    avatarWireValue = member.avatarUrl,
                    label = stringResource(MR.strings.member_label_custom_avatar),
                    avatarSize = MembersAgentAvatarPickerAvatarSize,
                    borderWidth = 0.dp,
                    cacheSubject = member.slockAvatarCacheSubject(),
                    cacheServerId = member.serverId?.value
                )
            } else {
                SlockLucideIcon(
                    if (uploading) SlockLucideIconName.LoaderCircle else SlockLucideIconName.Upload,
                    modifier = Modifier.size(16.dp),
                    color = "#99000000",
                    spinning = uploading
                )
            }
        }
        MembersAgentAvatarPickerCell(
            wireValue = "pixel:$PixelAvatarActiveDefaultKey",
            fallbackLabel = PixelAvatarActiveDefaultKey,
            active = activePixelKey == PixelAvatarActiveDefaultKey,
            onClick = onClear
        )
        PixelAvatarPickerKeys.filter { it != PixelAvatarActiveDefaultKey }.forEach { key ->
            MembersAgentAvatarPickerCell(
                wireValue = "pixel:$key",
                fallbackLabel = key,
                active = activePixelKey == key,
                onClick = { onSelect(key) }
            )
        }
    }
}

@Composable
private fun MembersAgentAvatarPickerCell(
    wireValue: String,
    fallbackLabel: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(MembersAgentAvatarPickerCellSize)
            .border(SlockStroke.Default, if (active) SlockColor.AccentPink else SlockColor.BorderBlack)
            // deliberate react-parity wash (web bg-*/N tint family); triaged token-audit #1043
            .background(if (active) SlockColor.AccentPink.copy(alpha = 0.2f) else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        SlockAvatar(
            avatarWireValue = wireValue,
            label = fallbackLabel,
            avatarSize = MembersAgentAvatarPickerAvatarSize,
            borderWidth = 0.dp
        )
    }
}

private const val MembersAgentProfileDescriptionMaxLength = 3000

// Web AgentDetailPanel's INFO block uses five different spacings here. They are
// kept as five separate constants on purpose: collapsing them onto shared tokens
// (SlockSpacing.Xs/Sm) reads as tidier and can even score better at 390dp, but it
// removes the ability to change one without the others, and the layout only holds
// at narrow widths because these differ. See task #257.
private val MembersInfoBlockGap = 6.dp // web space-y-1.5, within one field
private val MembersInfoLabelGap = 4.dp // web mb-1, under a label — deliberately not the block gap
private val MembersInfoFieldGapX = 32.dp // web gap-x-8, between Created and Creator
private val MembersInfoFieldGapY = 12.dp // web gap-y-3, when those two wrap onto separate lines
private val MembersInfoStatusGapX = 6.dp // web gap-x-1.5, inside the machine status line
private val MembersInfoStatusGapY = 4.dp // web gap-y-1, when that status line wraps

// Task #259: web's status line is text-xs (12px); the old row was 10sp. Dot matches web StatusDot.
private val MembersMachineStatusFontSize = 12.sp
private val MembersMachineStatusLineHeight = 16.sp
private val MembersMachineStatusDotSize = 8.dp

/**
 * Label -> string resource pairing, kept out of the Composable so the six pairs are unit-testable
 * (MembersMachineStatusRowTest). Three of the six branches have no visual fixture until Batch 3,
 * so without this a swapped resource would compile, pass the logic tests and only show in pixels.
 */
internal fun MembersMachineRunLabel.resource(): StringResource =
    when (this) {
        is MembersMachineRunLabel.ComputerVersion -> MR.strings.member_machine_run_computer_version
        is MembersMachineRunLabel.DaemonVersion -> MR.strings.member_machine_run_daemon_version
        MembersMachineRunLabel.ComputerOnline -> MR.strings.member_machine_run_computer_online
        MembersMachineRunLabel.DaemonOnline -> MR.strings.member_machine_run_daemon_online
        MembersMachineRunLabel.ComputerOffline -> MR.strings.member_machine_run_computer_offline
        MembersMachineRunLabel.DaemonOffline -> MR.strings.member_machine_run_daemon_offline
    }

/** The single `%1$s` argument for the two version labels; null for the four argument-free ones. */
internal fun MembersMachineRunLabel.formatArg(): String? =
    when (this) {
        is MembersMachineRunLabel.ComputerVersion -> version
        is MembersMachineRunLabel.DaemonVersion -> version
        MembersMachineRunLabel.ComputerOnline,
        MembersMachineRunLabel.DaemonOnline,
        MembersMachineRunLabel.ComputerOffline,
        MembersMachineRunLabel.DaemonOffline -> null
    }

@Composable
private fun MembersMachineRunLabel.localized(): String {
    val arg = formatArg()
    return if (arg != null) stringResource(resource(), arg) else stringResource(resource())
}

// Value text is 14sp/black; the label is 12sp/muted. The previous rows used 13sp
// values and 11sp labels with an 86dp label gutter.
private val MembersInfoValueFontSize = 14.sp
private val MembersInfoValueLineHeight = 20.sp

/**
 * One INFO metadata field: label above value, matching web's stacked block rather
 * than the fixed-gutter two-column row this replaced.
 *
 * The Computer block previously hand-wrote its label styling instead of sharing it,
 * which is how its label drifted from the others. Routing every field through here
 * means a future label change cannot land on some rows and miss one.
 */
@Composable
private fun MembersAgentInfoField(
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = SlockType.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp),
            color = SlockColor.TextMutedGray,
            modifier = Modifier.padding(bottom = MembersInfoLabelGap)
        )
        content()
    }
}

@Composable
private fun MembersAgentProfileHeader(
    member: MemberProfile,
    summary: AgentDetailSummary?,
    canEdit: Boolean,
    agentStatusObservedState: SlockAgentStatusIndicatorState?,
    agentStatusIndicatorState: SlockAgentStatusIndicatorState?,
    memberAvatarResolver: SlockMemberAvatarResolver = EmptySlockMemberAvatarResolver,
    showAvatarPicker: Boolean,
    onToggleAvatarPicker: () -> Unit,
    onSelectAvatar: (String) -> Unit,
    onClearAvatar: () -> Unit,
    onUploadAvatar: suspend () -> DataResult<MemberProfile>,
    onUnavailableAction: (String) -> Unit
) {
    val statusLabel = membersLiveAgentStatusLabel(
        indicatorState = agentStatusIndicatorState,
        summaryStatus = summary?.status,
        summaryActivity = summary?.activity,
        summaryActivityDetail = null,
        fallback = MembersUnknownAgentStatusLabel
    )
    val scope = rememberCoroutineScope()
    val errorStrings = localizedErrorPresentationStrings()
    val avatarUploadCancelled = stringResource(MR.strings.member_error_avatar_upload_cancelled)
    val avatarUploadFailed = membersStringTemplate(MR.strings.member_error_avatar_upload)
    val permissionFailed = membersStringTemplate(MR.strings.member_error_permission)
    var uploading by remember(member.id.value) { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = SlockSpacing.Sm),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Md),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp),
                contentAlignment = Alignment.Center
            ) {
                MembersAvatar(
                    member = member,
                    avatarSize = 64.dp,
                    memberAvatarResolver = memberAvatarResolver,
                    showStatusOverlay = false
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = member.displayName,
                    style = SlockType.Body.copy(fontSize = 18.sp, lineHeight = 22.sp)
                        .withSlockFontWeight(FontWeight.Bold),
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = member.detailHandle,
                    style = SlockType.Caption.copy(fontFamily = SlockMonoFamily, fontSize = 14.sp, lineHeight = 18.sp),
                    color = SlockColor.TextMutedGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // A null resolution is Unknown/not-ready, and StatusNeutralGray is the
                    // Offline/Disconnected colour — falling back to it makes the dot claim
                    // offline while the label beside it says Unknown, the disagreement
                    // SlockAgentStatusIndicator's own contract forbids. Render nothing until
                    // something authoritative arrives (task #253/#256).
                    agentStatusObservedState?.let { state ->
                        SlockStatusIndicator(
                            color = state.color,
                            size = 8.dp,
                            pulsing = state.pulsing
                        )
                    }
                    Text(
                        text = statusLabel,
                        style = SlockType.Caption.copy(fontFamily = SlockMonoFamily, fontSize = 12.sp, lineHeight = 16.sp),
                        color = SlockColor.TextMutedGray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        if (canEdit && showAvatarPicker) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = SlockSpacing.Sm),
                verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
            ) {
                MembersDetailSectionDivider()
                SlockSectionEyebrow(
                    label = stringResource(MR.strings.member_action_choose_avatar).slockDisplayUppercase(),
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )
                MembersAgentAvatarChoiceGrid(
                    member = member,
                    uploading = uploading,
                    onUploadClick = {
                        if (!uploading) {
                            uploading = true
                            scope.launch {
                                when (val result = onUploadAvatar()) {
                                    is DataResult.Content -> uploading = false
                                    is DataResult.Empty -> {
                                        uploading = false
                                        onUnavailableAction(avatarUploadCancelled)
                                    }
                                    is DataResult.Error -> {
                                        uploading = false
                                        onUnavailableAction(
                                            membersFormattedScreenError(avatarUploadFailed, result.error, errorStrings)
                                        )
                                    }
                                    is DataResult.NoPermission -> {
                                        uploading = false
                                        onUnavailableAction(
                                            membersFormattedPermissionError(permissionFailed, result.blocker, errorStrings)
                                        )
                                    }
                                }
                            }
                        }
                    },
                    onClear = onClearAvatar,
                    onSelect = onSelectAvatar
                )
            }
        }
    }
}

/**
 * Parameter holders for [MembersAgentProfileTab]. They are deliberate and must stay.
 *
 * Why: the tab used to take 36 parameters, past the Compose compiler's 31-parameters-per-int
 * packing boundary for the generated `$changed` / `$default` masks (Composer + 4 `$changed`
 * + 2 `$default` ints), and its body is ~1070 lines, which compiles to one method with
 * ~270 registers and ~5.6k dex instructions. On release 1.11.0 Android alpha builds this
 * function crashed on cold entry with a ClassCastException inside [rememberCoroutineScope]
 * (a Boolean came back from the slot where the scope canceller was expected). Two
 * diagnostic builds that only added an annotation or one read at the function head were
 * rejected outright by the ART verifier on this function: the dex showed the live
 * `$default1` mask register being reused as a reference temporary inside the default
 * lambda `remember {}` blocks. The generated code for this signature sits on a register
 * allocation boundary; every probe that changed this function's shape changed the outcome,
 * while probes on the callers or the lazy list did not.
 *
 * Bundling the 6 runtime-model inputs and the 7 lifecycle/diagnostic inputs brings the
 * signature to 25 parameters (1 `$changed` int fewer, `$default1` gone) and changes the
 * register allocation of the whole method. This is a mitigation with two effects at once
 * (a different signature/register shape and holder-based comparison); it does not establish
 * a safe parameter threshold (26–31 were not tested). The holders are unpacked into same-named locals
 * at the top of the function so the body is unchanged. Cost: the holders are plain
 * classes holding Map/List/lambda members, so Compose treats them as unstable, and the
 * caller builds new instances on each recomposition; the tab therefore no longer skips
 * (the same cost as `@NonSkippableComposable`). That is acceptable for one screen of
 * content. Do not add `@Stable` / `@Immutable` to the holders later as an "optimisation":
 * it changes the comparison semantics. Splitting the function into smaller composables is the
 * long-term shape and is tracked separately (task #1130), together with the other
 * >31-parameter composables in this file.
 *
 * Verification is device-only (compiler layer): alpha build 1110013 (this exact code on
 * 8a8ca1b11e) on cloud device vjdp, Members → agent detail, 6 cold starts, 0 crashes,
 * 0 VerifyError, pid unchanged; the unbundled base crashed 6/6 on the same path in earlier
 * rounds (1110001–1110005, 1110007), not all on the same device. The other >31-parameter
 * composables in this file were not exercised by this verification.
 */
private class MembersAgentRuntimeModelInputs(
    val agentMachineRuntimes: List<String> = emptyList(),
    val agentRuntimeModelsByRuntime: Map<String, SharedCreateAgentRuntimeModelCatalog> = emptyMap(),
    val agentRuntimeModelLoadsInFlight: Set<String> = emptySet(),
    val agentRuntimeModelErrorsByRuntime: Map<String, String> = emptyMap(),
    val agentRuntimeModelGenerationsByRuntime: Map<String, Long> = emptyMap(),
    val onAgentRuntimeModelsRequest: (runtime: String, force: Boolean) -> Unit = { _, _ -> }
)

private class MembersAgentLifecycleInputs(
    val lifecycleInFlight: String? = null,
    val diagnosticActionState: MembersAgentDiagnosticActionState? = null,
    val onRunLifecycleAction: (MembersAgentLifecycleAction) -> Unit = {},
    val onOpenRestartReset: () -> Unit = {},
    val onCopyDiagnostics: () -> Unit = {},
    val onCopyText: (String) -> Unit = {},
    val onReportIssue: () -> Unit = {},
    val onOpenComputer: (() -> Unit)? = null
)

/**
 * Inputs shared by the editable sections of [MembersAgentProfileTab] (task #1130 part 2).
 * Plain unstable class like the other Members holders; do not add `@Stable`/`@Immutable`.
 */
private class MembersAgentProfileEditInputs(
    val canEdit: Boolean,
    val agentProfileEditAuthority: MembersAgentProfileEditAuthority?,
    val agentDetailActions: SharedAgentDetailActions?,
    val onUpdateAgentProfile: (suspend (AgentProfileUpdateRequest) -> DataResult<MemberProfile>)?,
    val onApplyAgentProfileResult: (MemberProfile) -> Unit,
    val onUnavailableAction: (String) -> Unit,
    val errorStrings: ErrorPresentationStrings
)

/**
 * PROFILE section of the agent detail tab: display name + description with their independent
 * pencil-edit state machines (task #1130 part 2, moved verbatim out of [MembersAgentProfileTab]).
 */
@Composable
private fun MembersAgentProfileFieldsSection(
    member: MemberProfile,
    edit: MembersAgentProfileEditInputs,
    sharedAgentDetailStore: SharedAgentDetailStore?,
    storeState: SharedAgentDetailState?
) {
    val canEdit = edit.canEdit
    val agentProfileEditAuthority = edit.agentProfileEditAuthority
    val agentDetailActions = edit.agentDetailActions
    val onUpdateAgentProfile = edit.onUpdateAgentProfile
    val onApplyAgentProfileResult = edit.onApplyAgentProfileResult
    val onUnavailableAction = edit.onUnavailableAction
    val errorStrings = edit.errorStrings
    val scope = rememberCoroutineScope()
    val currentProfileEditAuthority by rememberUpdatedState(agentProfileEditAuthority)
    val profileEditingUnavailable = stringResource(MR.strings.member_error_profile_edit_unavailable)
    val displayNameRequired = stringResource(MR.strings.member_error_display_name_required)
    val permissionErrorTemplate = membersStringTemplate(MR.strings.member_error_permission)
    val saveErrorTemplate = membersStringTemplate(MR.strings.member_error_save)
    val descriptionTooLongTemplate = membersStringTemplate(MR.strings.member_error_description_too_long)

    // Display Name: independent edit state machine, mirrors React `editingDisplayName`
    // (AgentDetailPanel.tsx ~L1073). Pencil icon toggles edit mode; inline Save/Cancel
    // dispatch the update; Enter submits, Escape cancels.
    val profileStore = sharedAgentDetailStore
    val profileState = storeState
    val storeBackedProfileEdit = profileStore != null && profileState != null
    var localEditingDisplayName by remember(member.id.value) { mutableStateOf(false) }
    var localDraftDisplayName by remember(member.id.value, member.displayName) { mutableStateOf(member.displayName) }
    var localSavingDisplayName by remember(member.id.value) { mutableStateOf(false) }
    var localDisplayNameError by remember(member.id.value) { mutableStateOf<String?>(null) }
    var displayNameEditorAuthority by remember(member.id.value) {
        mutableStateOf<MembersAgentProfileEditAuthority?>(null)
    }
    val editingDisplayName =
        if (storeBackedProfileEdit) profileState.displayNameEditing else localEditingDisplayName
    val draftDisplayName =
        if (storeBackedProfileEdit) profileState.displayNameDraft ?: member.displayName else localDraftDisplayName
    val savingDisplayName =
        if (storeBackedProfileEdit) profileState.displayNameSaving else localSavingDisplayName
    val displayNameError =
        if (storeBackedProfileEdit) profileState.displayNameError else localDisplayNameError

    // Description: independent edit state machine, mirrors React `editingRole`
    // (~L1180). Same shape as display name but uses textarea + char counter.
    var localEditingRole by remember(member.id.value) { mutableStateOf(false) }
    var localDraftRole by remember(member.id.value, member.description) { mutableStateOf(member.description.orEmpty()) }
    var localSavingRole by remember(member.id.value) { mutableStateOf(false) }
    var localRoleError by remember(member.id.value) { mutableStateOf<String?>(null) }
    var roleEditorAuthority by remember(member.id.value) {
        mutableStateOf<MembersAgentProfileEditAuthority?>(null)
    }
    val editingRole =
        if (storeBackedProfileEdit) profileState.roleEditing else localEditingRole
    val draftRole =
        if (storeBackedProfileEdit) profileState.roleDraft ?: member.description.orEmpty() else localDraftRole
    val savingRole =
        if (storeBackedProfileEdit) profileState.roleSaving else localSavingRole
    val roleError =
        if (storeBackedProfileEdit) profileState.roleError else localRoleError

    fun saveDisplayName() {
        val callback = onUpdateAgentProfile
        if (
            !canEdit ||
            displayNameEditorAuthority != agentProfileEditAuthority ||
            (agentDetailActions == null && callback == null) ||
            savingDisplayName
        ) {
            onUnavailableAction(profileEditingUnavailable)
            return
        }
        val next = draftDisplayName.trim()
        if (next.isBlank()) {
            if (storeBackedProfileEdit) {
                profileStore.dispatch(
                    SharedAgentDetailIntent.SetDisplayNameError(displayNameRequired)
                )
            } else {
                localDisplayNameError = displayNameRequired
            }
            return
        }
        scope.launch {
            if (displayNameEditorAuthority != currentProfileEditAuthority) {
                onUnavailableAction(profileEditingUnavailable)
                return@launch
            }
            if (storeBackedProfileEdit) {
                profileStore.dispatch(SharedAgentDetailIntent.StartSaveDisplayName(next))
            } else {
                localSavingDisplayName = true
                localDisplayNameError = null
            }
            val result =
                try {
                    val request = AgentProfileUpdateRequest(
                        agentId = member.id,
                        displayName = next,
                        description = member.description?.takeIf { it.isNotBlank() }
                    )
                    if (agentDetailActions != null) {
                        agentDetailActions.updateAgentProfile(request)
                    } else {
                        requireNotNull(callback).invoke(request)
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    DataResult.Error(ScreenError("agent_profile_save_failed"))
                }
            if (displayNameEditorAuthority != currentProfileEditAuthority) return@launch
            when (result) {
                is DataResult.Content -> {
                    onApplyAgentProfileResult(result.data)
                    if (storeBackedProfileEdit) {
                        profileStore.dispatch(SharedAgentDetailIntent.FinishSaveDisplayName)
                    } else {
                        localEditingDisplayName = false
                    }
                }
                is DataResult.Empty -> {
                    if (storeBackedProfileEdit) {
                        profileStore.dispatch(SharedAgentDetailIntent.FinishSaveDisplayName)
                    } else {
                        localEditingDisplayName = false
                    }
                }
                is DataResult.NoPermission -> {
                    val error =
                        membersFormattedPermissionError(permissionErrorTemplate, result.blocker, errorStrings)
                    if (storeBackedProfileEdit) {
                        profileStore.dispatch(SharedAgentDetailIntent.SetDisplayNameError(error))
                    } else {
                        localDisplayNameError = error
                    }
                }
                is DataResult.Error -> {
                    val error = membersFormattedScreenError(saveErrorTemplate, result.error, errorStrings)
                    if (storeBackedProfileEdit) {
                        profileStore.dispatch(SharedAgentDetailIntent.SetDisplayNameError(error))
                    } else {
                        localDisplayNameError = error
                    }
                }
            }
            if (!storeBackedProfileEdit) {
                localSavingDisplayName = false
            }
        }
    }

    fun saveRole() {
        val callback = onUpdateAgentProfile
        if (
            !canEdit ||
            roleEditorAuthority != agentProfileEditAuthority ||
            (agentDetailActions == null && callback == null) ||
            savingRole
        ) {
            onUnavailableAction(profileEditingUnavailable)
            return
        }
        val next = draftRole.trim()
        if (next.length > MembersAgentProfileDescriptionMaxLength) {
            val error = descriptionTooLongTemplate.membersFormat(MembersAgentProfileDescriptionMaxLength.toString())
            if (storeBackedProfileEdit) {
                profileStore.dispatch(SharedAgentDetailIntent.SetRoleError(error))
            } else {
                localRoleError = error
            }
            return
        }
        scope.launch {
            if (roleEditorAuthority != currentProfileEditAuthority) {
                onUnavailableAction(profileEditingUnavailable)
                return@launch
            }
            if (storeBackedProfileEdit) {
                profileStore.dispatch(SharedAgentDetailIntent.StartSaveRole(next))
            } else {
                localSavingRole = true
                localRoleError = null
            }
            val result =
                try {
                    val request = AgentProfileUpdateRequest(
                        agentId = member.id,
                        displayName = member.displayName,
                        description = next.takeIf { it.isNotBlank() }
                    )
                    if (agentDetailActions != null) {
                        agentDetailActions.updateAgentProfile(request)
                    } else {
                        requireNotNull(callback).invoke(request)
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    DataResult.Error(ScreenError("agent_profile_save_failed"))
                }
            if (roleEditorAuthority != currentProfileEditAuthority) return@launch
            when (result) {
                is DataResult.Content -> {
                    onApplyAgentProfileResult(result.data)
                    if (storeBackedProfileEdit) {
                        profileStore.dispatch(SharedAgentDetailIntent.FinishSaveRole)
                    } else {
                        localEditingRole = false
                    }
                }
                is DataResult.Empty -> {
                    if (storeBackedProfileEdit) {
                        profileStore.dispatch(SharedAgentDetailIntent.FinishSaveRole)
                    } else {
                        localEditingRole = false
                    }
                }
                is DataResult.NoPermission -> {
                    val error =
                        membersFormattedPermissionError(permissionErrorTemplate, result.blocker, errorStrings)
                    if (storeBackedProfileEdit) {
                        profileStore.dispatch(SharedAgentDetailIntent.SetRoleError(error))
                    } else {
                        localRoleError = error
                    }
                }
                is DataResult.Error -> {
                    val error = membersFormattedScreenError(saveErrorTemplate, result.error, errorStrings)
                    if (storeBackedProfileEdit) {
                        profileStore.dispatch(SharedAgentDetailIntent.SetRoleError(error))
                    } else {
                        localRoleError = error
                    }
                }
            }
            if (!storeBackedProfileEdit) {
                localSavingRole = false
            }
        }
    }

    // PROFILE section — light divider style (no box border) per React parity.
    // Each field is its own column with eyebrow label + content, mirroring React's
    // `<div className="px-5 py-3"><SectionEyebrow>...</SectionEyebrow><p>...</p></div>`
    // pattern. Display Name and Description each have an independent pencil-edit
    // state machine (mirrors React `editingDisplayName` + `editingRole`).
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        // Display Name field
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MembersEditableSectionTitleRow(
                title = stringResource(MR.strings.member_label_display_name).slockDisplayUppercase(),
                showEdit = canEdit && !editingDisplayName,
                onEditClick = {
                    displayNameEditorAuthority = agentProfileEditAuthority
                    if (storeBackedProfileEdit) {
                        profileStore.dispatch(
                            SharedAgentDetailIntent.BeginEditDisplayName(member.displayName)
                        )
                    } else {
                        localDraftDisplayName = member.displayName
                        localDisplayNameError = null
                        localEditingDisplayName = true
                    }
                }
            )
        }
        if (editingDisplayName) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
            ) {
                SlockFormField(
                    label = stringResource(MR.strings.member_label_display_name),
                    error = displayNameError,
                    showLabel = false
                ) {
                    SlockBrutalInputField(
                        value = draftDisplayName,
                        placeholder = stringResource(MR.strings.member_label_display_name),
                        onValueChange = {
                            if (storeBackedProfileEdit) {
                                profileStore.dispatch(
                                    SharedAgentDetailIntent.UpdateDisplayNameDraft(it)
                                )
                            } else {
                                localDraftDisplayName = it
                                if (localDisplayNameError != null) localDisplayNameError = null
                            }
                        },
                        enabled = !savingDisplayName,
                        autoFocus = true,
                        borderColor = if (displayNameError != null) SlockColor.AccentRed else SlockColor.BorderBlack,
                        enabledBackgroundColor = if (displayNameError != null) Color(0xFFFFF4F4) else SlockColor.SurfaceWhite
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)) {
                    SlockBrutalActionButton(
                        label = if (savingDisplayName) stringResource(MR.strings.member_action_saving) else stringResource(MR.strings.common_save),
                        onClick = { saveDisplayName() },
                        enabled = !savingDisplayName,
                        backgroundColor = SlockColor.BrandYellow
                    )
                    SlockBrutalActionButton(
                        label = stringResource(MR.strings.common_cancel),
                        onClick = {
                            if (storeBackedProfileEdit) {
                                profileStore.dispatch(
                                    SharedAgentDetailIntent.CancelEditDisplayName
                                )
                            } else {
                                localDraftDisplayName = member.displayName
                                localDisplayNameError = null
                                localEditingDisplayName = false
                            }
                            displayNameEditorAuthority = null
                        },
                        enabled = !savingDisplayName,
                        backgroundColor = SlockColor.SurfaceWhite
                    )
                }
            }
        } else {
            Text(
                text = member.displayName,
                style = SlockType.Body.copy(fontSize = 13.sp, lineHeight = 18.sp),
                color = SlockColor.TextBlack,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Description field (independent edit state machine)
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = SlockSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MembersEditableSectionTitleRow(
                title = stringResource(MR.strings.member_section_description).slockDisplayUppercase(),
                showEdit = canEdit && !editingRole,
                onEditClick = {
                    roleEditorAuthority = agentProfileEditAuthority
                    if (storeBackedProfileEdit) {
                        profileStore.dispatch(
                            SharedAgentDetailIntent.BeginEditRole(member.description.orEmpty())
                        )
                    } else {
                        localDraftRole = member.description.orEmpty()
                        localRoleError = null
                        localEditingRole = true
                    }
                }
            )
        }
        if (editingRole) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
            ) {
                SlockFormField(
                    label = stringResource(MR.strings.member_section_description),
                    error = roleError,
                    showLabel = false
                ) {
                    SlockBrutalInputField(
                        value = draftRole,
                        placeholder = stringResource(MR.strings.member_placeholder_description),
                        onValueChange = {
                            if (storeBackedProfileEdit) {
                                profileStore.dispatch(
                                    SharedAgentDetailIntent.UpdateRoleDraft(it)
                                )
                            } else {
                                localDraftRole = it
                                if (localRoleError != null) localRoleError = null
                            }
                        },
                        enabled = !savingRole,
                        autoFocus = true,
                        maxLines = 4,
                        fieldMinHeight = 86.dp,
                        borderColor = if (roleError != null) SlockColor.AccentRed else SlockColor.BorderBlack,
                        enabledBackgroundColor = if (roleError != null) Color(0xFFFFF4F4) else SlockColor.SurfaceWhite
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SlockBrutalActionButton(
                        label = if (savingRole) stringResource(MR.strings.member_action_saving) else stringResource(MR.strings.common_save),
                        onClick = { saveRole() },
                        enabled = !savingRole,
                        backgroundColor = SlockColor.BrandYellow
                    )
                    SlockBrutalActionButton(
                        label = stringResource(MR.strings.common_cancel),
                        onClick = {
                            if (storeBackedProfileEdit) {
                                profileStore.dispatch(
                                    SharedAgentDetailIntent.CancelEditRole
                                )
                            } else {
                                localDraftRole = member.description.orEmpty()
                                localRoleError = null
                                localEditingRole = false
                            }
                        },
                        enabled = !savingRole,
                        backgroundColor = SlockColor.SurfaceWhite
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "${draftRole.trim().length}/$MembersAgentProfileDescriptionMaxLength",
                        style = SlockType.Caption.copy(fontFamily = SlockMonoFamily, fontSize = 10.sp, lineHeight = 14.sp),
                        color = SlockColor.TextMutedGray
                    )
                }
            }
        } else {
            Text(
                text = member.description?.takeIf { it.isNotBlank() }
                    ?: stringResource(MR.strings.member_state_no_description),
                style = SlockType.Body.copy(
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    fontStyle = if (member.description.isNullOrBlank()) FontStyle.Italic else FontStyle.Normal
                ),
                color = if (member.description.isNullOrBlank()) SlockColor.TextMutedGray else SlockColor.TextBlack,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * INFO section of the agent detail tab: server role (with its edit state machine), computer
 * status, created date and creator (task #1130 part 2, moved verbatim out of [MembersAgentProfileTab]).
 */
@Composable
private fun MembersAgentInfoSection(
    member: MemberProfile,
    summary: AgentDetailSummary?,
    deleted: Boolean,
    edit: MembersAgentProfileEditInputs,
    machineStatusRow: (machineId: String?) -> MembersMachineStatusRow,
    onOpenComputer: (() -> Unit)?,
    timeContext: SlockTimeContext,
    memberAvatarResolver: SlockMemberAvatarResolver
) {
    val canEdit = edit.canEdit
    val agentProfileEditAuthority = edit.agentProfileEditAuthority
    val agentDetailActions = edit.agentDetailActions
    val onUpdateAgentProfile = edit.onUpdateAgentProfile
    val onApplyAgentProfileResult = edit.onApplyAgentProfileResult
    val onUnavailableAction = edit.onUnavailableAction
    val errorStrings = edit.errorStrings
    val scope = rememberCoroutineScope()
    val currentProfileEditAuthority by rememberUpdatedState(agentProfileEditAuthority)
    val roleEditingUnavailable = stringResource(MR.strings.member_error_role_edit_unavailable)
    val permissionErrorTemplate = membersStringTemplate(MR.strings.member_error_permission)
    val saveErrorTemplate = membersStringTemplate(MR.strings.member_error_save)
    val emptyResponseMessage = stringResource(MR.strings.member_error_empty_response)
    val roleUpdateUnconfirmedMessage = stringResource(MR.strings.member_error_role_update_unconfirmed)
    val serverRoleDisplay =
        membersAgentDisplayServerRole(member.serverRole, summary?.serverRole, deleted = deleted)
    val currentServerRole = (serverRoleDisplay as? MembersAgentServerRoleDisplay.Known)?.role
    // The editor can only express Admin/Member. Offering it while the server holds a role we
    // do not recognise would let a save silently rewrite that role to one of ours, so the
    // affordance is withdrawn for Unrecognized -- the chip still shows the real name (#261).
    val canEditServerRole = canEdit && serverRoleDisplay !is MembersAgentServerRoleDisplay.Unrecognized
    val serverRoleRememberKey = serverRoleDisplay.rememberKey()
    var editingServerRole by remember(member.id.value, serverRoleRememberKey) { mutableStateOf(false) }
    var draftServerRole by remember(member.id.value, serverRoleRememberKey) {
        mutableStateOf(currentServerRole ?: MembersAgentServerRole.Member)
    }
    var serverRoleMenuExpanded by remember(member.id.value, serverRoleRememberKey) { mutableStateOf(false) }
    var roleHelpOpen by remember(member.id.value) { mutableStateOf(false) }
    var savingServerRole by remember(member.id.value) { mutableStateOf(false) }
    var serverRoleError by remember(member.id.value, serverRoleRememberKey) { mutableStateOf<String?>(null) }
    var serverRoleEditorAuthority by remember(member.id.value) {
        mutableStateOf<MembersAgentProfileEditAuthority?>(null)
    }

    fun saveServerRole() {
        val callback = onUpdateAgentProfile
        if (
            !canEditServerRole ||
            serverRoleEditorAuthority != agentProfileEditAuthority ||
            (agentDetailActions == null && callback == null) ||
            savingServerRole
        ) {
            onUnavailableAction(roleEditingUnavailable)
            return
        }
        val next = draftServerRole
        if (next == currentServerRole) {
            editingServerRole = false
            serverRoleError = null
            return
        }
        val expectedServerId = serverRoleEditorAuthority?.serverId
            ?: run {
                onUnavailableAction(roleEditingUnavailable)
                return
            }
        scope.launch {
            if (serverRoleEditorAuthority != currentProfileEditAuthority) {
                onUnavailableAction(roleEditingUnavailable)
                return@launch
            }
            savingServerRole = true
            serverRoleError = null
            val result =
                try {
                    val request = AgentProfileUpdateRequest(
                        agentId = member.id,
                        displayName = member.displayName,
                        description = member.description?.takeIf { it.isNotBlank() },
                        serverRole = next.wireValue,
                        includeServerRole = true
                    )
                    if (agentDetailActions != null) {
                        agentDetailActions.updateAgentProfile(request)
                    } else {
                        requireNotNull(callback).invoke(request)
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    DataResult.Error(ScreenError("agent_server_role_save_failed"))
                }
            if (serverRoleEditorAuthority != currentProfileEditAuthority) return@launch
            when (result) {
                is DataResult.Content -> {
                    when (result.membersAgentServerRoleSaveOutcome(member.id, expectedServerId, next)) {
                        MembersAgentServerRoleSaveOutcome.Success -> {
                            onApplyAgentProfileResult(result.data)
                            editingServerRole = false
                            serverRoleMenuExpanded = false
                        }
                        MembersAgentServerRoleSaveOutcome.MismatchedContent ->
                            serverRoleError = roleUpdateUnconfirmedMessage
                        else -> serverRoleError = roleUpdateUnconfirmedMessage
                    }
                }
                is DataResult.Empty -> serverRoleError = emptyResponseMessage
                is DataResult.NoPermission ->
                    serverRoleError =
                        membersFormattedPermissionError(
                            permissionErrorTemplate,
                            result.blocker,
                            errorStrings
                        )
                is DataResult.Error ->
                    serverRoleError = membersFormattedScreenError(saveErrorTemplate, result.error, errorStrings)
            }
            savingServerRole = false
        }
    }

    // INFO section — mirrors React AgentDetailPanel's Computer / Created / Creator rows.
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = SlockSpacing.Sm),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        MembersDetailSectionDivider()
        MembersDetailSectionTitle(stringResource(MR.strings.member_section_info).slockDisplayUppercase())
        // Role row: React keeps this as a label affordance plus an inline role chip/editor,
        // not the compact two-column metadata row used by Computer/Created/Creator.
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(MR.strings.member_label_role),
                    style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 16.sp),
                    color = SlockColor.TextMutedGray
                )
                SlockLucideIcon(
                    SlockLucideIconName.Info,
                    modifier = Modifier
                        .size(14.dp)
                        .clickable { roleHelpOpen = true },
                    color = SlockColor.TextMutedGrayArgb
                )
                if (canEditServerRole && !editingServerRole) {
                    MembersProfilePencilButton(
                        onClick = {
                            serverRoleEditorAuthority = agentProfileEditAuthority
                            draftServerRole = currentServerRole ?: MembersAgentServerRole.Member
                            serverRoleError = null
                            serverRoleMenuExpanded = true
                            editingServerRole = true
                        }
                    )
                }
            }
            if (editingServerRole) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
                ) {
                    MembersServerRoleEditor(
                        displayValue = draftServerRole.localizedLabel(),
                        badgeBackgroundColor = draftServerRole.backgroundColor,
                        expanded = serverRoleMenuExpanded,
                        enabled = !savingServerRole,
                        saving = savingServerRole,
                        saveEnabled = draftServerRole != currentServerRole,
                        options = membersAgentServerRoleOptions(draftServerRole),
                        selectedId = draftServerRole.wireValue,
                        error = serverRoleError,
                        onToggle = { serverRoleMenuExpanded = !serverRoleMenuExpanded },
                        onSelect = { roleId ->
                            roleId.toMembersAgentServerRoleOrNull()?.let { nextRole ->
                                draftServerRole = nextRole
                                serverRoleMenuExpanded = false
                                serverRoleError = null
                            }
                        },
                        onSave = { saveServerRole() },
                        onCancel = {
                            draftServerRole = currentServerRole ?: MembersAgentServerRole.Member
                            serverRoleError = null
                            serverRoleMenuExpanded = false
                            editingServerRole = false
                        }
                    )
                }
            } else {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MembersAgentServerRoleChip(serverRoleDisplay)
                }
            }
        }
        if (roleHelpOpen) {
            MembersAgentRoleHelpDialog(onDismiss = { roleHelpOpen = false })
        }
        // Computer / Created / Creator — web AgentDetailPanel stacks label ABOVE
        // value here rather than using a fixed left gutter. The old 86dp label
        // column is what made a long machine name ellipsize: the gutter never
        // yields, so the value gets whatever is left. Stacked, the value owns the
        // full width and wraps instead.
        MembersAgentInfoField(label = stringResource(MR.strings.member_label_computer)) {
            val machineName = summary?.machineName.orBadgeFallback(member.membersComputerName)
            val machineNameStyle =
                SlockType.Body.copy(
                    fontFamily = slockMonoFamilyForWeight(FontWeight.SemiBold),
                    fontSize = MembersInfoValueFontSize,
                    lineHeight = MembersInfoValueLineHeight,
                    fontWeight = FontWeight.Normal
                ).let { base ->
                    // Web renders the name as a button with a hover underline. Android
                    // has no hover, so the underline is shown whenever it is actually
                    // tappable — the affordance has to be visible without a pointer.
                    if (onOpenComputer != null) base.copy(textDecoration = TextDecoration.Underline) else base
                }
            Text(
                text = machineName,
                style = machineNameStyle,
                color = SlockColor.TextBlack,
                // Web sets break-all on this specific value: machine names are
                // unbroken tokens, so without it a long one overflows its box. No
                // maxLines/ellipsis here on purpose — truncating a machine name
                // hides exactly the part that distinguishes two similar hosts.
                softWrap = true,
                modifier = (if (onOpenComputer != null) Modifier.clickable(onClick = onOpenComputer) else Modifier)
                    .fillMaxWidth()
            )
            // Status line (task #259). Truth source is the computers store, not
            // `machineId != null`; the six-way run label mirrors web machineRunLabel.ts.
            // Two independent facts -- status word and run label -- rendered as two
            // spans with a literal " · " between them, exactly as web does in JSX.
            when (val statusRow = machineStatusRow(summary?.machineId)) {
                // artin: 甲 -- until the store has answered, render nothing here. Web
                // falls through to "no computer assigned" during this window; that is
                // its defect, not a reference to copy.
                MembersMachineStatusRow.Loading -> Unit
                MembersMachineStatusRow.NoComputer ->
                    Text(
                        text = stringResource(MR.strings.member_machine_no_computer_assigned),
                        style = SlockType.Caption.copy(
                            fontSize = MembersMachineStatusFontSize,
                            lineHeight = MembersMachineStatusLineHeight,
                            fontStyle = FontStyle.Italic
                        ),
                        color = SlockColor.TextMutedGray,
                        modifier = Modifier.padding(top = MembersInfoBlockGap)
                    )
                is MembersMachineStatusRow.Machine -> {
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(top = MembersInfoBlockGap),
                        horizontalArrangement = Arrangement.spacedBy(MembersInfoStatusGapX),
                        verticalArrangement = Arrangement.spacedBy(MembersInfoStatusGapY)
                    ) {
                        // web: <StatusDot class="shrink-0"> -- fixed size, never compressed.
                        Box(
                            modifier = Modifier
                                .size(MembersMachineStatusDotSize)
                                .background(
                                    if (statusRow.online) SlockColor.AccentLime else SlockColor.StatusNeutralGray,
                                    shape = RoundedCornerShape(MembersMachineStatusDotSize / 2)
                                )
                                // FlowRow in this fork has no per-item vertical alignment; the
                                // dot sits on the row's top edge. Web centres it (items-center).
                                // Recorded as a known, visible divergence for the capture.
                        )
                        Text(
                            text = stringResource(
                                if (statusRow.online) MR.strings.member_label_connected else MR.strings.member_label_offline
                            ),
                            style = SlockType.Caption.copy(
                                fontSize = MembersMachineStatusFontSize,
                                lineHeight = MembersMachineStatusLineHeight
                            ),
                            color = SlockColor.TextMutedGray
                        )
                        // web: <span class="font-mono">· {runLabel}</span> -- the separator is
                        // layout text inside the mono span, not part of any i18n string.
                        Text(
                            text = "· " + statusRow.runLabel.localized(),
                            style = SlockType.Caption.copy(
                                fontFamily = SlockMonoFamily,
                                fontSize = MembersMachineStatusFontSize,
                                lineHeight = MembersMachineStatusLineHeight
                            ),
                            color = SlockColor.TextMutedGray
                        )
                    }
                }
            }
        }
        // Created and Creator sit side by side while they fit and drop to their own
        // lines when they do not. A fixed two-column Row would keep them side by
        // side at any width, which is the failure this section is meant to avoid.
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MembersInfoFieldGapX),
            verticalArrangement = Arrangement.spacedBy(MembersInfoFieldGapY)
        ) {
            summary?.createdAt?.membersAgentCreatedDate(timeContext)?.takeIf { it.isNotBlank() }?.let { createdDate ->
                MembersAgentInfoField(label = stringResource(MR.strings.member_label_created)) {
                    Text(
                        text = createdDate,
                        style = SlockType.Body.copy(
                            fontFamily = SlockMonoFamily,
                            fontSize = MembersInfoValueFontSize,
                            lineHeight = MembersInfoValueLineHeight
                        ),
                        color = SlockColor.TextBlack,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            // Creator value stays a clickable identity chip (web opens the profile).
            MembersAgentInfoField(label = stringResource(MR.strings.member_label_creator)) {
                val creatorProfile = summary?.creator?.toStrictMemberProfile()
                if (creatorProfile != null) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(MembersInfoStatusGapX),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MembersAvatar(
                            member = creatorProfile,
                            avatarSize = 22.dp,
                            statusSize = 0.dp,
                            memberAvatarResolver = memberAvatarResolver,
                            showStatusOverlay = false
                        )
                        Text(
                            text = creatorProfile.displayName,
                            style = SlockType.Body.copy(
                                fontSize = MembersInfoValueFontSize,
                                lineHeight = MembersInfoValueLineHeight
                            ).withSlockFontWeight(FontWeight.Bold),
                            color = SlockColor.TextBlack,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            // fill = false so the name shrinks to its content and the
                            // handle beside it is not pushed out; without this the
                            // name would claim the row and squeeze its sibling.
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Text(
                            text = creatorProfile.detailHandle,
                            style = SlockType.Caption.copy(
                                fontFamily = SlockMonoFamily,
                                fontSize = 11.sp,
                                lineHeight = 16.sp
                            ),
                            color = SlockColor.TextMutedGray,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } else {
                    Text(
                        text = stringResource(MR.strings.member_state_no_creator_assigned),
                        style = SlockType.Body.copy(
                            fontSize = MembersInfoValueFontSize,
                            lineHeight = MembersInfoValueLineHeight,
                            fontStyle = FontStyle.Italic
                        ),
                        color = SlockColor.TextMutedGray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * RUNTIME CONFIG section of the agent detail tab: read-only rows plus the shared runtime-config
 * editor dialog and its save flow (task #1130 part 2, moved verbatim out of [MembersAgentProfileTab]).
 */
@Composable
private fun MembersAgentRuntimeConfigSection(
    member: MemberProfile,
    summary: AgentDetailSummary?,
    agentStatusIndicatorState: SlockAgentStatusIndicatorState?,
    edit: MembersAgentProfileEditInputs,
    runtimeModels: MembersAgentRuntimeModelInputs,
    createAgentFormStore: SharedCreateAgentFormStore?,
    onApplyAgentRuntimeConfigProjection: suspend (MembersAgentProfileEditAuthority, RuntimeConfig) -> Boolean
) {
    val canEdit = edit.canEdit
    val agentProfileEditAuthority = edit.agentProfileEditAuthority
    val agentDetailActions = edit.agentDetailActions
    val onUpdateAgentProfile = edit.onUpdateAgentProfile
    val onApplyAgentProfileResult = edit.onApplyAgentProfileResult
    val onUnavailableAction = edit.onUnavailableAction
    val errorStrings = edit.errorStrings
    val agentMachineRuntimes = runtimeModels.agentMachineRuntimes
    val agentRuntimeModelsByRuntime = runtimeModels.agentRuntimeModelsByRuntime
    val agentRuntimeModelLoadsInFlight = runtimeModels.agentRuntimeModelLoadsInFlight
    val agentRuntimeModelErrorsByRuntime = runtimeModels.agentRuntimeModelErrorsByRuntime
    val agentRuntimeModelGenerationsByRuntime = runtimeModels.agentRuntimeModelGenerationsByRuntime
    val onAgentRuntimeModelsRequest = runtimeModels.onAgentRuntimeModelsRequest
    val runtimeConfigResolution = remember(summary) { summary.toMembersRuntimeConfigResolution() }
    val canonicalRuntimeConfig =
        (runtimeConfigResolution as? MembersAgentRuntimeConfigResolution.Content)?.config
    val canonicalRuntimeId = canonicalRuntimeConfig?.runtime
    val canonicalRuntimeModel = canonicalRuntimeConfig?.model
    // Runtime mutations need an explicit canonical status. A null status is
    // deliberately neutral in the renderer; treating it as "inactive" here
    // would allow a reset-sensitive edit while presence is still unknown.
    val canonicalRuntimeStatus = agentStatusIndicatorState?.status
    val runtimeStatusResolved = membersAgentRuntimeStatusResolved(canonicalRuntimeStatus)
    val activeAgent = membersAgentRuntimeActive(canonicalRuntimeStatus)
    val runtimeEditorReady =
        runtimeStatusResolved &&
            canonicalRuntimeConfig != null &&
            membersAgentRuntimeConfigSaveAttemptEnabled(
                runtime = canonicalRuntimeConfig.runtime,
                model = canonicalRuntimeConfig.toMembersRuntimeModelValue(),
                customModelMode = canonicalRuntimeModel is RuntimeModelConfig.Custom,
                customModelName = (canonicalRuntimeModel as? RuntimeModelConfig.Custom)?.name.orEmpty(),
                machineRuntimeIds = agentMachineRuntimes
            )
    val scope = rememberCoroutineScope()
    val currentProfileEditAuthority by rememberUpdatedState(agentProfileEditAuthority)
    val currentRuntimeModelGenerations by rememberUpdatedState(agentRuntimeModelGenerationsByRuntime)
    val runtimeEditingUnavailable = stringResource(MR.strings.member_error_runtime_edit_unavailable)
    val runtimeFormV2SavedRefresh = stringResource(MR.strings.runtime_form_v2_saved_refresh)
    val permissionErrorTemplate = membersStringTemplate(MR.strings.member_error_permission)
    val saveErrorTemplate = membersStringTemplate(MR.strings.member_error_save)
    var editingRuntimeConfig by remember(member.id.value) { mutableStateOf(false) }
    var runtimeEditorAuthority by remember(member.id.value) {
        mutableStateOf<MembersAgentProfileEditAuthority?>(null)
    }
    var savingRuntimeConfig by remember(member.id.value) { mutableStateOf(false) }
    var runtimeConfigError by remember(member.id.value) { mutableStateOf<String?>(null) }
    // Runtime form v2 edit (flag `runtime_form_v2_web`); any v2 load failure or a runtime
    // change hands the same editing session to the v1 dialog below.
    val runtimeFormV2Access =
        agentProfileEditAuthority?.serverId?.value?.let { createAgentFormStore?.runtimeFormV2Access(it) }
    var runtimeFormV2Declined by remember(member.id.value, editingRuntimeConfig) { mutableStateOf(false) }
    var runtimeFormV2ErrorFields by remember(member.id.value, editingRuntimeConfig) { mutableStateOf(emptySet<String>()) }

    LaunchedEffect(canonicalRuntimeId) {
        canonicalRuntimeId?.let { onAgentRuntimeModelsRequest(it, false) }
    }


    fun saveRuntimeConfig(
        nextConfig: RuntimeConfig,
        openedCatalogGeneration: Long,
        retainBuiltInProviderSecret: Boolean,
        kimiSdkFormDefinitionRef: SharedRuntimeFormDefinitionRef? = null
    ) {
        val callback = onUpdateAgentProfile
        if (
            !canEdit ||
                !runtimeStatusResolved ||
                (agentDetailActions == null && callback == null) ||
                savingRuntimeConfig
        ) {
            onUnavailableAction(runtimeEditingUnavailable)
            return
        }
        val currentConfig = canonicalRuntimeConfig
        if (currentConfig == null) {
            runtimeConfigError = "agent_runtime_config_unavailable"
            onUnavailableAction(runtimeEditingUnavailable)
            return
        }
        if (!membersAgentProfileEditAuthorityMatches(
                opened = runtimeEditorAuthority,
                current = agentProfileEditAuthority,
                openedCatalogGeneration = openedCatalogGeneration,
                currentCatalogGeneration = agentRuntimeModelGenerationsByRuntime[nextConfig.runtime] ?: 0L
            )
        ) {
            runtimeConfigError = "agent_runtime_editor_stale"
            onUnavailableAction(runtimeEditingUnavailable)
            return
        }
        val restartMode = membersRuntimeConfigResetMode(currentConfig, nextConfig, activeAgent)
        scope.launch {
            savingRuntimeConfig = true
            runtimeConfigError = null
            if (!membersAgentProfileEditAuthorityMatches(
                    opened = runtimeEditorAuthority,
                    current = currentProfileEditAuthority,
                    openedCatalogGeneration = openedCatalogGeneration,
                    currentCatalogGeneration = currentRuntimeModelGenerations[nextConfig.runtime] ?: 0L
                )
            ) {
                savingRuntimeConfig = false
                runtimeConfigError = "agent_runtime_editor_stale"
                onUnavailableAction(runtimeEditingUnavailable)
                return@launch
            }
            val result =
                try {
                    val request = AgentProfileUpdateRequest(
                        agentId = member.id,
                        displayName = member.displayName,
                        description = member.description?.takeIf { it.isNotBlank() },
                        runtimeConfig = nextConfig,
                        includeRuntimeConfig = true,
                        retainOmittedBuiltInProviderSecret = retainBuiltInProviderSecret,
                        runtimeResetMode = restartMode,
                        formDefinitionRef = kimiSdkFormDefinitionRef
                    )
                    if (agentDetailActions != null) {
                        agentDetailActions.updateAgentProfile(request)
                    } else {
                        requireNotNull(callback).invoke(request)
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    DataResult.Error(ScreenError("agent_runtime_config_save_failed"))
                }
            if (!membersAgentProfileEditAuthorityMatches(
                    opened = runtimeEditorAuthority,
                    current = currentProfileEditAuthority,
                    openedCatalogGeneration = openedCatalogGeneration,
                    currentCatalogGeneration = currentRuntimeModelGenerations[nextConfig.runtime] ?: 0L
                )
            ) {
                return@launch
            }
            when (result) {
                is DataResult.Content -> {
                    onApplyAgentProfileResult(result.data)
                    val authority = runtimeEditorAuthority
                    if (
                        authority != null &&
                        onApplyAgentRuntimeConfigProjection(authority, nextConfig)
                    ) {
                        editingRuntimeConfig = false
                    } else {
                        runtimeConfigError = "agent_runtime_editor_stale"
                        onUnavailableAction(runtimeEditingUnavailable)
                    }
                }
                is DataResult.Empty -> {
                    val authority = runtimeEditorAuthority
                    if (
                        authority != null &&
                        onApplyAgentRuntimeConfigProjection(authority, nextConfig)
                    ) {
                        editingRuntimeConfig = false
                    } else {
                        runtimeConfigError = "agent_runtime_editor_stale"
                        onUnavailableAction(runtimeEditingUnavailable)
                    }
                }
                is DataResult.NoPermission ->
                    runtimeConfigError =
                        membersFormattedPermissionError(permissionErrorTemplate, result.blocker, errorStrings)
                is DataResult.Error ->
                    runtimeConfigError = membersFormattedScreenError(saveErrorTemplate, result.error, errorStrings)
            }
            savingRuntimeConfig = false
        }
    }

    fun saveRuntimeConfigV2(submit: RuntimeFormV2SubmitBody) {
        val callback = onUpdateAgentProfile
        val access = runtimeFormV2Access
        if (
            !canEdit ||
                !runtimeStatusResolved ||
                access == null ||
                (agentDetailActions == null && callback == null) ||
                savingRuntimeConfig
        ) {
            onUnavailableAction(runtimeEditingUnavailable)
            return
        }
        val openedAuthority = runtimeEditorAuthority
        val authorityCurrent = {
            membersAgentProfileEditAuthorityMatches(
                opened = openedAuthority,
                current = currentProfileEditAuthority,
                openedCatalogGeneration = 0L,
                currentCatalogGeneration = 0L
            )
        }
        if (!authorityCurrent()) {
            runtimeConfigError = "agent_runtime_editor_stale"
            onUnavailableAction(runtimeEditingUnavailable)
            return
        }
        scope.launch {
            savingRuntimeConfig = true
            runtimeConfigError = null
            runtimeFormV2ErrorFields = emptySet()
            val result =
                try {
                    // Same runtime only (a runtime switch goes through v1), so an active agent restarts
                    // to pick the change up, exactly as the v1 editor decides.
                    val request = AgentProfileUpdateRequest(
                        agentId = member.id,
                        displayName = member.displayName,
                        description = member.description?.takeIf { it.isNotBlank() },
                        runtimeResetMode = if (activeAgent) AgentResetMode.Restart else null,
                        runtimeFormV2 = submit
                    )
                    if (agentDetailActions != null) {
                        agentDetailActions.updateAgentProfile(request)
                    } else {
                        requireNotNull(callback).invoke(request)
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    DataResult.Error(ScreenError("agent_runtime_config_save_failed"))
                }
            if (!authorityCurrent()) return@launch
            when (result) {
                is DataResult.Content, is DataResult.Empty -> {
                    (result as? DataResult.Content)?.let { onApplyAgentProfileResult(it.data) }
                    // The server assembled the config; show what it stored rather than guessing locally.
                    val stored = (access.loader.agent(member.id) as? DataResult.Content)?.data
                    val config = (stored?.toAgentDetailSummary().toMembersRuntimeConfigResolution()
                        as? MembersAgentRuntimeConfigResolution.Content)?.config
                    val authority = runtimeEditorAuthority
                    val projected =
                        config != null && authority != null && onApplyAgentRuntimeConfigProjection(authority, config)
                    editingRuntimeConfig = false
                    // The save landed; only the read-back failed, so the rows may lag until a refresh.
                    if (!projected) onUnavailableAction(runtimeFormV2SavedRefresh)
                }
                is DataResult.NoPermission ->
                    runtimeConfigError =
                        membersFormattedPermissionError(permissionErrorTemplate, result.blocker, errorStrings)
                is DataResult.Error -> {
                    runtimeFormV2ErrorFields = result.error.formFieldKeys.toSet()
                    runtimeConfigError = membersFormattedScreenError(saveErrorTemplate, result.error, errorStrings)
                }
            }
            savingRuntimeConfig = false
        }
    }

    // RUNTIME CONFIG section — mirrors React `AgentDetailPanel` runtime block:
    // key/value rows with brutal semantic chips, env vars embedded in the same
    // section, and a pencil entry point for the shared runtime-config editor.
    // No catalog-retry affordance in this read-only view (React parity): a failed
    // model-catalog prefetch only matters inside the editor dialog, which
    // re-requests on open and renders its own error + Retry (task #96).
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = SlockSpacing.Sm),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        MembersDetailSectionDivider()
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
        ) {
            MembersEditableSectionTitleRow(
                title = stringResource(MR.strings.member_section_runtime_config).slockDisplayUppercase(),
                showEdit = canEdit && runtimeEditorReady && !editingRuntimeConfig,
                onEditClick = {
                    runtimeConfigError = null
                    runtimeEditorAuthority = agentProfileEditAuthority
                    editingRuntimeConfig = true
                }
            )
        }
        MembersAgentRuntimeConfigRows(
            resolution = runtimeConfigResolution,
            runtimeModelsCatalog =
                canonicalRuntimeId?.let(agentRuntimeModelsByRuntime::get),
            machineId = summary?.machineId ?: member.membersComputerId
        )
        MembersAgentRuntimeEnvVars(summary)
    }

    val runtimeFormV2MachineId = (summary?.machineId ?: member.membersComputerId)?.takeIf { it.isNotBlank() }
    if (
        editingRuntimeConfig && canonicalRuntimeConfig != null && runtimeStatusResolved &&
            runtimeFormV2Access != null && !runtimeFormV2Declined && runtimeFormV2MachineId != null
    ) {
        MembersAgentRuntimeFormV2Dialog(
            access = runtimeFormV2Access,
            agentId = member.id,
            machineId = runtimeFormV2MachineId,
            saving = savingRuntimeConfig,
            error = runtimeConfigError,
            errorFieldKeys = runtimeFormV2ErrorFields,
            onDismiss = {
                if (!savingRuntimeConfig) {
                    editingRuntimeConfig = false
                    runtimeEditorAuthority = null
                    runtimeConfigError = null
                }
            },
            onLoadFailed = { runtimeFormV2Declined = true },
            onChangeRuntime = {
                runtimeConfigError = null
                runtimeFormV2Declined = true
            },
            onSave = ::saveRuntimeConfigV2
        )
    } else if (editingRuntimeConfig && canonicalRuntimeConfig != null && runtimeStatusResolved) {
        MembersAgentRuntimeConfigDialog(
            initialConfig = canonicalRuntimeConfig,
            memberId = member.id,
            activeAgent = activeAgent,
            machineRuntimeIds = agentMachineRuntimes,
            runtimeModelsByRuntime = agentRuntimeModelsByRuntime,
            runtimeModelLoadsInFlight = agentRuntimeModelLoadsInFlight,
            runtimeModelErrorsByRuntime = agentRuntimeModelErrorsByRuntime,
            runtimeModelGenerationsByRuntime = agentRuntimeModelGenerationsByRuntime,
            createAgentFormStore = createAgentFormStore,
            currentServerId = agentProfileEditAuthority?.serverId?.value,
            machineId = summary?.machineId ?: member.membersComputerId,
            openedProfileAuthority = runtimeEditorAuthority,
            currentProfileAuthority = agentProfileEditAuthority,
            onRuntimeModelsRequest = onAgentRuntimeModelsRequest,
            saving = savingRuntimeConfig,
            error = runtimeConfigError,
            onDismiss = {
                if (!savingRuntimeConfig) {
                    editingRuntimeConfig = false
                    runtimeEditorAuthority = null
                    runtimeConfigError = null
                }
            },
            onSave = { nextConfig, catalogGeneration, retainBuiltInProviderSecret, kimiSdkFormDefinitionRef ->
                saveRuntimeConfig(
                    nextConfig,
                    catalogGeneration,
                    retainBuiltInProviderSecret,
                    kimiSdkFormDefinitionRef
                )
            }
        )
    }
}

@Composable
private fun MembersExternalAgentSetupSection(
    agentId: MemberId,
    profileName: String,
    serverUrl: String?,
    agentsApiClient: build.raft.app.data.SlockAgentsApiClient?,
    canManage: Boolean,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val tokenFailed = stringResource(MR.strings.member_external_setup_token_failed)
    var token by remember(agentId) { mutableStateOf<String?>(null) }
    var tokenLoading by remember(agentId) { mutableStateOf(false) }
    var error by remember(agentId) { mutableStateOf<String?>(null) }
    val cleanServer = serverUrl?.trim()?.trimEnd('/')?.removeSuffix("/api")?.takeIf { it.isNotBlank() }
        ?: "<server-url>"
    val profile = profileName.trim().replace(Regex("[^A-Za-z0-9_-]+"), "-").trim('-').ifBlank { agentId.value.take(8) }
    val loginCommand = "raft agent login --server $cleanServer --agent ${agentId.value} --profile-slug $profile"
    val installCommand =
        "npm i -g @botiverse/raft@latest && " +
            "claude plugin marketplace add botiverse/raft-external-agents && " +
            "claude plugin marketplace update raft && " +
            "claude plugin install raft-channel@raft && " +
            "claude plugin update raft-channel@raft"
    val startCommand =
        "RAFT_PROFILE=$profile claude --append-system-prompt 'You are connected to Raft, a shared workspace for humans and agents. Treat Raft as your primary collaboration surface.' --dangerously-load-development-channels plugin:raft-channel@raft"
    val hermesCommand = "hermes gateway setup"
    // task #4 (Hands 1c6885d8): each external runtime is a separate harness with its own
    // setup commands, so they are tabs rather than one flat list. Claude Code and Hermes
    // share the CLI install and the login step, then diverge on how the runtime is started.
    var selectedHarness by remember(agentId) {
        mutableStateOf(MembersExternalHarness.ClaudeCode)
    }
    Column(
        modifier = modifier.fillMaxWidth().border(SlockStroke.Default, SlockColor.BorderBlack)
            .background(SlockColor.AccentCyan.copy(alpha = 0.12f)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        SlockSectionEyebrow(label = stringResource(MR.strings.member_external_setup_title))
        Text(stringResource(MR.strings.member_external_setup_description), style = SlockType.Caption, color = SlockColor.TextBlack)
        // task #4: the bootstrap token gates every later step, so it comes first. The
        // generate action must be reachable without scrolling past the copy commands.
        if (canManage && agentsApiClient != null) {
            SlockBrutalActionButton(
                label = stringResource(if (tokenLoading) MR.strings.member_external_setup_generating else MR.strings.member_external_setup_generate),
                enabled = !tokenLoading,
                onClick = {
                    tokenLoading = true
                    error = null
                    scope.launch {
                        when (val result = agentsApiClient.issueAgentLoginToken(agentId)) {
                            is DataResult.Content -> {
                                token = result.data.apiKey
                            }
                            else -> error = tokenFailed
                        }
                        tokenLoading = false
                    }
                }
            )
            token?.let { value ->
                Text(stringResource(MR.strings.member_external_setup_token_once), style = SlockType.Caption, color = SlockColor.TextBlack)
                MembersExternalSetupCommand(stringResource(MR.strings.member_external_setup_token_label), value, onCopy)
            }
            error?.let { Text(it, style = SlockType.Caption, color = SlockColor.AccentRed) }
        }
        SegmentedControl(
            selectedValue = selectedHarness.value,
            options = MembersExternalHarness.entries.map { harness ->
                SegmentedControlOption(
                    value = harness.value,
                    label = stringResource(harness.labelRes)
                )
            },
            onValueSelected = { value ->
                MembersExternalHarness.entries.firstOrNull { it.value == value }?.let { selectedHarness = it }
            }
        )
        MembersExternalSetupCommand(stringResource(MR.strings.member_external_setup_install), installCommand, onCopy)
        MembersExternalSetupCommand(stringResource(MR.strings.member_external_setup_login), loginCommand, onCopy)
        when (selectedHarness) {
            MembersExternalHarness.ClaudeCode ->
                MembersExternalSetupCommand(stringResource(MR.strings.member_external_setup_start), startCommand, onCopy)
            MembersExternalHarness.Hermes ->
                MembersExternalSetupCommand(stringResource(MR.strings.member_external_setup_hermes), hermesCommand, onCopy)
        }
    }
}

/** External runtimes offered in the setup section, one tab each (task #4). */
internal enum class MembersExternalHarness(val value: String, val labelRes: StringResource) {
    ClaudeCode("claude-code", MR.strings.member_external_setup_harness_claude),
    Hermes("hermes", MR.strings.member_external_setup_harness_hermes)
}

@Composable
private fun MembersExternalSetupCommand(label: String, command: String, onCopy: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold), color = SlockColor.TextBlack)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)) {
            Text(command, modifier = Modifier.weight(1f), style = SlockType.Caption.copy(fontFamily = FontFamily.Monospace), color = SlockColor.TextBlack)
            SlockBrutalActionButton(label = stringResource(MR.strings.member_external_setup_copy), minHeight = 28.dp, onClick = { onCopy(command) })
        }
    }
}

@Composable
private fun MembersAgentProfileTab(
    member: MemberProfile,
    detail: AgentDetailSnapshot?,
    runtimeModels: MembersAgentRuntimeModelInputs = MembersAgentRuntimeModelInputs(),
    createAgentFormStore: SharedCreateAgentFormStore? = null,
    agentProfileEditAuthority: MembersAgentProfileEditAuthority? = null,
    canManage: Boolean,
    agentStatusObservedState: SlockAgentStatusIndicatorState?,
    agentStatusIndicatorState: SlockAgentStatusIndicatorState?,
    agentStatusResolver: SlockAgentStatusResolver = EmptySlockAgentStatusResolver,
    memberAvatarResolver: SlockMemberAvatarResolver = EmptySlockMemberAvatarResolver,
    showAvatarPicker: Boolean,
    onToggleAvatarPicker: () -> Unit,
    onSelectAvatar: (String) -> Unit,
    onClearAvatar: () -> Unit,
    onUploadAvatar: suspend () -> DataResult<MemberProfile>,
    sharedAgentDetailStore: SharedAgentDetailStore? = null,
    storeState: SharedAgentDetailState? = null,
    agentDetailActions: SharedAgentDetailActions? = null,
    onUpdateAgentProfile: (suspend (AgentProfileUpdateRequest) -> DataResult<MemberProfile>)?,
    onApplyAgentProfileResult: (MemberProfile) -> Unit = {},
    onApplyAgentRuntimeConfigProjection:
        suspend (MembersAgentProfileEditAuthority, RuntimeConfig) -> Boolean = { _, _ -> false },
    agentsApiClient: build.raft.app.data.SlockAgentsApiClient? = null,
    externalSetupServerUrl: String? = null,
    onUnavailableAction: (String) -> Unit,
    lifecycle: MembersAgentLifecycleInputs = MembersAgentLifecycleInputs(),
    machineStatusRow: (machineId: String?) -> MembersMachineStatusRow,
    timeContext: SlockTimeContext,
    fixtureStatusFallbackEnabled: Boolean = false,
    modifier: Modifier = Modifier
) {
    val lifecycleInFlight = lifecycle.lifecycleInFlight
    val diagnosticActionState = lifecycle.diagnosticActionState
    val onRunLifecycleAction = lifecycle.onRunLifecycleAction
    val onOpenRestartReset = lifecycle.onOpenRestartReset
    val onCopyDiagnostics = lifecycle.onCopyDiagnostics
    val onCopyText = lifecycle.onCopyText
    val onReportIssue = lifecycle.onReportIssue
    val onOpenComputer = lifecycle.onOpenComputer
    val errorStrings = localizedErrorPresentationStrings()
    val summary = detail?.summary
    val deleted = summary?.deletedAt?.isNotBlank() == true
    // Roster metadata is never edit authority. Only a canonical loaded profile summary may enter
    // an editor or construct an update request.
    val canEdit = canManage && summary != null && agentProfileEditAuthority != null && !deleted
    val edit =
        MembersAgentProfileEditInputs(
            canEdit = canEdit,
            agentProfileEditAuthority = agentProfileEditAuthority,
            agentDetailActions = agentDetailActions,
            onUpdateAgentProfile = onUpdateAgentProfile,
            onApplyAgentProfileResult = onApplyAgentProfileResult,
            onUnavailableAction = onUnavailableAction,
            errorStrings = errorStrings
        )

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)) {
        if (deleted) {
            MembersDetailSection(title = stringResource(MR.strings.member_status_deleted).slockDisplayUppercase()) {
                MembersDetailField(stringResource(MR.strings.member_label_status), stringResource(MR.strings.member_status_deleted))
                MembersDetailField(stringResource(MR.strings.member_label_deleted_at), summary.deletedAt.orDash())
            }
        }

        MembersAgentProfileHeader(
            member = member,
            summary = summary,
            canEdit = canEdit,
            agentStatusIndicatorState = agentStatusIndicatorState,
            agentStatusObservedState = agentStatusObservedState,
            memberAvatarResolver = memberAvatarResolver,
            showAvatarPicker = showAvatarPicker,
            onToggleAvatarPicker = onToggleAvatarPicker,
            onSelectAvatar = onSelectAvatar,
            onClearAvatar = onClearAvatar,
            onUploadAvatar = onUploadAvatar,
            onUnavailableAction = onUnavailableAction
        )

        MembersAgentProfileFieldsSection(
            member = member,
            edit = edit,
            sharedAgentDetailStore = sharedAgentDetailStore,
            storeState = storeState
        )
        MembersAgentInfoSection(
            member = member,
            summary = summary,
            deleted = deleted,
            edit = edit,
            machineStatusRow = machineStatusRow,
            onOpenComputer = onOpenComputer,
            timeContext = timeContext,
            memberAvatarResolver = memberAvatarResolver
        )
        MembersAgentRuntimeConfigSection(
            member = member,
            summary = summary,
            agentStatusIndicatorState = agentStatusIndicatorState,
            edit = edit,
            runtimeModels = runtimeModels,
            createAgentFormStore = createAgentFormStore,
            onApplyAgentRuntimeConfigProjection = onApplyAgentRuntimeConfigProjection
        )

        if (member.isExternal || summary?.isExternal == true || isExternalAgentRuntime(summary?.runtime)) {
            MembersExternalAgentSetupSection(
                agentId = member.id,
                profileName = member.displayName,
                serverUrl = externalSetupServerUrl,
                agentsApiClient = agentsApiClient,
                canManage = canManage,
                onCopy = onCopyText
            )
        }

        // Avatar editing is surfaced from the top-left avatar in `MembersDetailHeader`
        // (PUX #49 spec v4 lock). Clicking it opens the floating `MembersAgentAvatarPicker`
        // overlay, owned by `MembersDetailContent`. The body AVATAR section + the in-page
        // picker invocation that previously lived here were removed in this commit so the
        // avatar entry point is consistent with React `AgentDetailPanel.tsx`.
        MembersAgentCreatedAgentsSection(
            detail = detail,
            agentStatusResolver = agentStatusResolver,
            memberAvatarResolver = memberAvatarResolver,
            fixtureStatusFallbackEnabled = fixtureStatusFallbackEnabled
        )
        MembersAgentSkillsSection(detail)
        MembersAgentProfileActionsSection(
            member = member,
            detail = detail,
            resolvedStatus = agentStatusIndicatorState,
            canManage = canManage,
            lifecycleInFlight = lifecycleInFlight,
            diagnosticActionState = diagnosticActionState,
            onRunLifecycleAction = onRunLifecycleAction,
            onOpenRestartReset = onOpenRestartReset,
            onCopyDiagnostics = onCopyDiagnostics,
            onReportIssue = onReportIssue
        )
    }
}

@Composable
private fun MembersAgentProfileActionsSection(
    member: MemberProfile,
    detail: AgentDetailSnapshot?,
    resolvedStatus: SlockAgentStatusIndicatorState?,
    canManage: Boolean,
    lifecycleInFlight: String?,
    diagnosticActionState: MembersAgentDiagnosticActionState?,
    onRunLifecycleAction: (MembersAgentLifecycleAction) -> Unit,
    onOpenRestartReset: () -> Unit,
    onCopyDiagnostics: () -> Unit,
    onReportIssue: () -> Unit
) {
    val summary = detail?.summary
    val deleted = summary?.deletedAt?.isNotBlank() == true
    if (!canManage || deleted) return

    // The action reads the same process presence SSOT as the header/avatar.
    // Detail/profile snapshots never decide a production lifecycle action.
    val primaryAction =
        membersResolvedPrimaryLifecycleAction(
            resolvedStatus = resolvedStatus?.status,
            detailFallbackStatus = null,
            deleted = false,
            hasResolvedPresence = true
        )
    val primaryLabel =
        when (primaryAction) {
            MembersAgentLifecycleAction.Start -> stringResource(MR.strings.member_action_start_agent)
            MembersAgentLifecycleAction.Stop -> stringResource(MR.strings.member_action_stop_agent)
            null -> stringResource(MR.strings.member_action_start_agent)
            else -> primaryAction.localizedLabel()
        }
    val primaryBusy = lifecycleInFlight == primaryAction?.actionKey
    val copyState = diagnosticActionState?.takeIf { it.actionKey == "copy" }
    val reportState = diagnosticActionState?.takeIf { it.actionKey == "report" }
    val diagnosticBusy = diagnosticActionState?.inProgress == true

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        MembersDetailSectionDivider()
        MembersDetailSectionTitle(stringResource(MR.strings.member_section_actions).slockDisplayUppercase())
        if (primaryAction != null) {
            SlockBrutalActionButton(
                label = if (primaryBusy) primaryAction.localizedBusyLabel() else primaryLabel,
                leadingIcon = primaryAction.iconName,
                enabled = lifecycleInFlight == null,
                minHeight = 42.dp,
                modifier = Modifier.fillMaxWidth(),
                onClick = { onRunLifecycleAction(primaryAction) }
            )
        }
        SlockBrutalActionButton(
            label = stringResource(MR.strings.member_label_restart_reset),
            leadingIcon = SlockLucideIconName.RotateCcw,
            enabled = lifecycleInFlight == null,
            minHeight = 42.dp,
            modifier = Modifier.fillMaxWidth(),
            onClick = onOpenRestartReset
        )
        SlockBrutalActionButton(
            label = copyState?.successLabel ?: stringResource(MR.strings.member_action_copy_diagnostic_info),
            leadingIcon = if (copyState?.successLabel != null) SlockLucideIconName.Check else SlockLucideIconName.Clipboard,
            enabled = !diagnosticBusy,
            minHeight = 42.dp,
            modifier = Modifier.fillMaxWidth(),
            onClick = onCopyDiagnostics
        )
        SlockBrutalActionButton(
            label = if (reportState?.inProgress == true) stringResource(MR.strings.member_action_reporting) else stringResource(MR.strings.member_action_report_issue),
            leadingIcon = SlockLucideIconName.Bug,
            enabled = !diagnosticBusy,
            backgroundColor = SlockColor.AccentOrange,
            minHeight = 42.dp,
            modifier = Modifier.fillMaxWidth(),
            onClick = onReportIssue
        )
        SlockBrutalActionButton(
            label = if (lifecycleInFlight == MembersAgentLifecycleAction.Delete.actionKey) stringResource(MR.strings.member_action_deleting) else stringResource(MR.strings.member_action_delete_agent),
            leadingIcon = SlockLucideIconName.Trash2,
            enabled = lifecycleInFlight == null,
            backgroundColor = SlockColor.AccentRed,
            minHeight = 42.dp,
            modifier = Modifier.fillMaxWidth(),
            onClick = { onRunLifecycleAction(MembersAgentLifecycleAction.Delete) }
        )
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun MembersAgentRuntimeConfigRows(
    resolution: MembersAgentRuntimeConfigResolution,
    runtimeModelsCatalog: SharedCreateAgentRuntimeModelCatalog?,
    machineId: String?
) {
    val errorStrings = localizedErrorPresentationStrings()
    val runtimeConfig = (resolution as? MembersAgentRuntimeConfigResolution.Content)?.config
    if (runtimeConfig == null) {
        Text(
            text = stringResource(MR.strings.member_state_runtime_config_unavailable),
            style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold),
            color = SlockColor.AccentRed
        )
        Text(
            text = (resolution as MembersAgentRuntimeConfigResolution.Unavailable)
                .code
                .userFacingErrorCodeMessage(errorStrings),
            style = SlockType.Caption,
            color = SlockColor.TextMutedGray
        )
        return
    }
    val runtimeId = runtimeConfig.runtime
    val modelLabel =
        membersAgentRuntimeModelDisplayLabel(
            runtimeId = runtimeId,
            modelId = runtimeConfig.toMembersRuntimeModelValue(),
            runtimeModelsCatalog = runtimeModelsCatalog
        )
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        MembersAgentRuntimeConfigField(stringResource(MR.strings.member_label_runtime)) {
            MembersAgentRuntimeUsageChip(
                label = getRuntimeDisplayName(runtimeId),
                runtimeId = runtimeId,
                machineId = machineId
            )
        }
        if (modelLabel != null) {
            MembersAgentRuntimeConfigField(stringResource(MR.strings.member_label_model)) {
                MembersAgentRuntimeChip(
                    label = modelLabel,
                    kind = MembersAgentRuntimeChipKind.Model
                )
            }
        }
        // kimi-sdk (task #275): the chip is gated on the selected model's LIVE
        // catalog capability (never the static runtime set); submit-time
        // authority additionally requires the resolved kimi-sdk form ref.
        if (
            supportsRuntimeReasoningEffort(runtimeId) ||
            kimiSdkLiveReasoningCapability(
                runtime = runtimeId,
                model = runtimeConfig.toMembersRuntimeModelValue(),
                runtimeModelsOverride = runtimeModelsCatalog?.models.orEmpty()
            ) != null
        ) {
            MembersAgentRuntimeConfigField(stringResource(MR.strings.member_label_reasoning)) {
                MembersAgentRuntimeChip(
                    label = runtimeConfig.reasoningEffort?.toMembersDisplayLabel() ?: stringResource(MR.strings.member_label_default),
                    kind = MembersAgentRuntimeChipKind.Reasoning
                )
            }
        }
        if (supportsRuntimeFastMode(runtimeId)) {
            MembersAgentRuntimeConfigField(stringResource(MR.strings.member_label_mode)) {
                MembersAgentRuntimeChip(
                    label = if (runtimeConfig.mode is RuntimeModeConfig.Fast) stringResource(MR.strings.member_label_fast) else stringResource(MR.strings.member_label_default),
                    kind = MembersAgentRuntimeChipKind.Mode
                )
            }
        }
        if (runtimeId == CREATE_AGENT_BUILTIN_RUNTIME_ID) {
            MembersAgentRuntimeConfigField(stringResource(MR.strings.member_label_provider)) {
                val providerId = runtimeConfig.provider.membersBuiltInProviderId()
                Text(
                    text = providerId.ifBlank { stringResource(MR.strings.member_label_default) },
                    style = SlockType.Caption.copy(
                        fontFamily = if (providerId.isBlank()) SlockType.Caption.fontFamily else SlockMonoFamily,
                        fontSize = 11.sp,
                        lineHeight = 16.sp
                    ),
                    color = if (providerId.isBlank()) SlockColor.TextMutedGray else SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (runtimeId == "pi") {
            MembersAgentRuntimeConfigField(stringResource(MR.strings.member_label_provider)) {
                val providerId =
                    (runtimeConfig.provider as? RuntimeProviderConfig.PiBuiltin)?.providerId
                Text(
                    text = providerId ?: stringResource(MR.strings.member_label_default),
                    style = SlockType.Caption.copy(
                        fontFamily = if (providerId == null) SlockType.Caption.fontFamily else SlockMonoFamily,
                        fontSize = 11.sp,
                        lineHeight = 16.sp
                    ),
                    color = if (providerId == null) SlockColor.TextMutedGray else SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (runtimeId == "claude") {
            MembersAgentRuntimeConfigField(stringResource(MR.strings.member_label_provider)) {
                val provider = runtimeConfig.provider as? RuntimeProviderConfig.Custom
                Text(
                    text = provider?.apiUrl?.takeIf { it.isNotBlank() } ?: stringResource(MR.strings.member_label_default),
                    style = SlockType.Caption.copy(
                        fontFamily = if (provider == null) SlockType.Caption.fontFamily else SlockMonoFamily,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        fontStyle = if (provider == null) FontStyle.Italic else FontStyle.Normal
                    ),
                    color = if (provider == null) SlockColor.TextMutedGray else SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            MembersAgentRuntimeConfigField(stringResource(MR.strings.member_label_command)) {
                val command = runtimeConfig.command?.takeIf { it.isNotBlank() }
                Text(
                    text = command ?: stringResource(MR.strings.member_label_default),
                    style = SlockType.Caption.copy(
                        fontFamily = if (command == null) SlockType.Caption.fontFamily else SlockMonoFamily,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        fontStyle = if (command == null) FontStyle.Italic else FontStyle.Normal
                    ),
                    color = if (command == null) SlockColor.TextMutedGray else SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

internal fun membersAgentRuntimeModelDisplayLabel(
    runtimeId: String,
    modelId: String,
    runtimeModelsCatalog: SharedCreateAgentRuntimeModelCatalog?
): String? {
    val normalizedRuntimeId = runtimeId.trim()
    val normalizedModelId = modelId.trim()
    if (normalizedRuntimeId.isBlank() || normalizedModelId.isBlank()) return null
    fun String?.verifiedDisplayLabel(): String? =
        this?.trim()?.takeIf { it.isNotBlank() && it != normalizedModelId }

    return runtimeModelsCatalog
        ?.models
        ?.firstOrNull { it.id.trim() == normalizedModelId }
        ?.label
        .verifiedDisplayLabel()
        ?: RUNTIME_MODELS[normalizedRuntimeId]
            ?.firstOrNull { it.id == normalizedModelId }
            ?.label
            .verifiedDisplayLabel()
        // The agent genuinely carries this model id; hiding it entirely reads as
        // "model missing" (task #284). Fall back to the raw id rather than nothing.
        ?: normalizedModelId
}

@Composable
private fun MembersAgentRuntimeConfigField(
    label: String,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier.width(122.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(
            text = label.slockDisplayUppercase(),
            style = SlockType.Caption.copy(fontSize = 10.sp, lineHeight = 14.sp),
            color = SlockColor.TextMutedGray,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        content()
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun MembersAgentRuntimeEnvVars(summary: AgentDetailSummary?) {
    val envVarNames = summary.membersRuntimeEnvVarNames()
    val envVars = summary?.envVars.orEmpty()
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        SlockSectionEyebrow(
            label = stringResource(MR.strings.member_section_environment_variables),
            fontSize = 11.sp,
            lineHeight = 16.sp
        )
        if (envVarNames.isEmpty()) {
            Text(
                text = stringResource(MR.strings.member_state_no_environment_variables),
                style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 16.sp),
                color = SlockColor.TextMutedGray,
                fontStyle = FontStyle.Italic
            )
        } else {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm),
                verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
            ) {
                envVarNames.forEach { name ->
                    Box(
                        modifier = Modifier
                            .background(SlockColor.SurfaceWhite)
                            .border(SlockStroke.Default, SlockColor.BorderBlack)
                            .padding(horizontal = SlockSpacing.Sm, vertical = 3.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "$name=",
                                style = SlockType.Caption.copy(
                                    fontFamily = SlockMonoFamily,
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp
                                ),
                                color = SlockColor.TextBlack,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = membersMaskedEnvValue(envVars[name]),
                                style = SlockType.Caption.copy(
                                    fontFamily = SlockMonoFamily,
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp
                                ),
                                color = SlockColor.TextBlack.copy(alpha = 0.4f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun membersMaskedEnvValue(value: String?): String =
    "•".repeat(value?.length?.coerceIn(1, 8) ?: 3)

@Composable
private fun MembersAgentRuntimeConfigDialog(
    initialConfig: RuntimeConfig,
    memberId: MemberId,
    activeAgent: Boolean,
    createAgentFormStore: SharedCreateAgentFormStore?,
    currentServerId: String?,
    machineId: String?,
    machineRuntimeIds: List<String> = emptyList(),
    runtimeModelsByRuntime: Map<String, SharedCreateAgentRuntimeModelCatalog> = emptyMap(),
    runtimeModelLoadsInFlight: Set<String> = emptySet(),
    runtimeModelErrorsByRuntime: Map<String, String> = emptyMap(),
    runtimeModelGenerationsByRuntime: Map<String, Long> = emptyMap(),
    openedProfileAuthority: MembersAgentProfileEditAuthority?,
    currentProfileAuthority: MembersAgentProfileEditAuthority?,
    onRuntimeModelsRequest: (runtime: String, force: Boolean) -> Unit = { _, _ -> },
    saving: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSave: (
        RuntimeConfig,
        catalogGeneration: Long,
        retainBuiltInProviderSecret: Boolean,
        kimiSdkFormDefinitionRef: SharedRuntimeFormDefinitionRef?
    ) -> Unit
) {
    val errorStrings = localizedErrorPresentationStrings()
    val stableInitialConfig = remember(memberId.value, initialConfig) { initialConfig }
    var runtime by remember(stableInitialConfig) { mutableStateOf(stableInitialConfig.runtime) }
    val initialModelSelection = remember(stableInitialConfig) {
        when (val initialModel = stableInitialConfig.model) {
            is RuntimeModelConfig.Preset -> false to initialModel.id
            is RuntimeModelConfig.Custom -> true to initialModel.name
        }
    }
    var model by remember(stableInitialConfig) { mutableStateOf(initialModelSelection.second) }
    var customModelMode by remember(stableInitialConfig) { mutableStateOf(initialModelSelection.first) }
    var customModelName by remember(stableInitialConfig) {
        mutableStateOf(if (initialModelSelection.first) initialModelSelection.second else "")
    }
    val initialProvider = stableInitialConfig.provider as? RuntimeProviderConfig.Custom
    var providerMode by remember(stableInitialConfig) {
        mutableStateOf(
            if (initialProvider != null) RUNTIME_PROVIDER_MODE_CUSTOM else RUNTIME_PROVIDER_MODE_DEFAULT
        )
    }
    var providerApiUrl by remember(stableInitialConfig) { mutableStateOf(initialProvider?.apiUrl.orEmpty()) }
    var providerApiKey by remember(stableInitialConfig) {
        val initialText = initialProvider?.apiKey.orEmpty()
        mutableStateOf(TextFieldValue(initialText, selection = TextRange(initialText.length)))
    }
    val initialPiProvider = stableInitialConfig.provider as? RuntimeProviderConfig.PiBuiltin
    var piProviderId by remember(stableInitialConfig) {
        mutableStateOf(initialPiProvider?.providerId ?: "configured")
    }
    var piProviderApiKey by remember(stableInitialConfig) {
        val initialText = initialPiProvider?.apiKey.orEmpty()
        mutableStateOf(TextFieldValue(initialText, selection = TextRange(initialText.length)))
    }
    val initialBuiltInProvider = stableInitialConfig.provider
        .takeIf { stableInitialConfig.runtime == CREATE_AGENT_BUILTIN_RUNTIME_ID }
    var builtInProviderId by remember(stableInitialConfig) {
        mutableStateOf(initialBuiltInProvider.membersBuiltInProviderId())
    }
    // Built-in provider keys are write-only. Agent reads deliberately project
    // an empty string; a non-empty draft always means the user supplied a replacement.
    var builtInApiKey by remember(stableInitialConfig) { mutableStateOf(TextFieldValue("")) }
    var builtInBaseUrl by remember(stableInitialConfig) {
        mutableStateOf((initialBuiltInProvider as? RuntimeProviderConfig.Gateway)?.baseUrl.orEmpty())
    }
    var builtInSupportsImageInput by remember(stableInitialConfig) {
        mutableStateOf((initialBuiltInProvider as? RuntimeProviderConfig.Gateway)?.supportsImageInput == true)
    }
    var builtInLoadLocalPlugins by remember(stableInitialConfig) {
        mutableStateOf(stableInitialConfig.loadLocalPlugins == true)
    }
    var fastMode by remember(stableInitialConfig) { mutableStateOf(stableInitialConfig.mode is RuntimeModeConfig.Fast) }
    var reasoningEffort by remember(stableInitialConfig) { mutableStateOf(stableInitialConfig.reasoningEffort) }
    // kimi-sdk (task #275): only an explicit selector pick counts as "changed";
    // reconciles from model switches or capability arrivals never set this.
    var reasoningEffortTouched by remember(stableInitialConfig) { mutableStateOf(false) }
    // Tracks whether the kimi capability was ever live in this editing session,
    // so "never had it" (still loading) is not treated as "lost mid-edit".
    var kimiSdkCapabilitySeen by remember(stableInitialConfig) { mutableStateOf(false) }
    var command by remember(stableInitialConfig) { mutableStateOf(stableInitialConfig.command.orEmpty()) }
    var envVarEntries by remember(stableInitialConfig) {
        mutableStateOf(stableInitialConfig.envVars.orEmpty().entries.map { it.key to it.value })
    }
    var pendingRuntimeSwitchConfig by remember(stableInitialConfig) { mutableStateOf<RuntimeConfig?>(null) }
    var pendingRetainBuiltInProviderSecret by remember(stableInitialConfig) { mutableStateOf(false) }
    var pendingKimiSdkFormDefinitionRef by remember(stableInitialConfig) {
        mutableStateOf<SharedRuntimeFormDefinitionRef?>(null)
    }
    var awaitingServerModelForRuntime by remember(stableInitialConfig) { mutableStateOf(false) }
    var acceptedCatalogGeneration by remember(stableInitialConfig) {
        mutableStateOf(runtimeModelGenerationsByRuntime[stableInitialConfig.runtime] ?: 0L)
    }
    var pendingCatalogValidatedSave by remember(stableInitialConfig) {
        mutableStateOf<MembersPendingRuntimeConfigSave?>(null)
    }
    var saveValidationError by remember(stableInitialConfig) { mutableStateOf<String?>(null) }
    var advancedOpen by remember(stableInitialConfig) {
        mutableStateOf(command.isNotBlank() || envVarEntries.isNotEmpty() || builtInLoadLocalPlugins)
    }
    val formStoreState = createAgentFormStore?.state?.collectAsState()?.value
    val cleanServerId = currentServerId?.trim().orEmpty()
    val cleanMachineId = machineId?.trim().orEmpty()
    val builtInForm = formStoreState?.resolvedForms?.entries
        ?.singleOrNull { (key, _) ->
            key.serverId == cleanServerId &&
                key.machineId == cleanMachineId &&
                key.ref.runtimeId == CREATE_AGENT_BUILTIN_RUNTIME_ID
        }
        ?.value
    val builtInFormLoading = formStoreState?.loadingForms?.any { key ->
        key.serverId == cleanServerId &&
            key.machineId == cleanMachineId &&
            key.ref.runtimeId == CREATE_AGENT_BUILTIN_RUNTIME_ID
    } == true
    // kimi-sdk (task #275): resolved exact form for the current agent's
    // computer. The store's owner/generation fencing keeps a stale or
    // out-of-order form from replacing a newer resolution.
    val kimiSdkForm = formStoreState?.resolvedForms?.entries
        ?.singleOrNull { (key, _) ->
            key.serverId == cleanServerId &&
                key.machineId == cleanMachineId &&
                key.ref.runtimeId == CREATE_AGENT_KIMI_SDK_RUNTIME_ID
        }
        ?.value
        ?.takeIf { it.definition.ref.isSupportedKimiSdk }
    val kimiSdkFormRef = kimiSdkReasoningFormRef(kimiSdkForm)
    var builtInFormError by remember(stableInitialConfig) { mutableStateOf<String?>(null) }
    var builtInFormReloadGeneration by remember(stableInitialConfig) { mutableStateOf(0) }
    val customModelMissing = customModelMode && customModelName.trim().isBlank()
    val providerUrlInvalid =
        runtime == "claude" &&
            providerMode == RUNTIME_PROVIDER_MODE_CUSTOM &&
            !Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(providerApiUrl.trim())
    val providerKeyInvalid =
        runtime == "claude" &&
            providerMode == RUNTIME_PROVIDER_MODE_CUSTOM &&
            providerApiKey.text.isBlank()
    val piProviderKeyInvalid =
        runtime == "pi" && piProviderId != "configured" && piProviderApiKey.text.isBlank()
    val runtimeModelsCatalog = runtimeModelsByRuntime[runtime]
    val runtimeModelsOverride = runtimeModelsCatalog?.models.orEmpty()
    val runtimeModelsLoading = runtime in runtimeModelLoadsInFlight
    val runtimeModelsError = runtimeModelErrorsByRuntime[runtime]
    // kimi-sdk (task #275): selector/save capability = selected model's LIVE
    // catalog entry + the resolved exact form ref. No static fallback.
    val kimiSdkCatalogCapability = kimiSdkLiveReasoningCapability(runtime, model, runtimeModelsOverride)
    val kimiSdkEditCapability = kimiSdkCatalogCapability.takeIf { kimiSdkFormRef != null }
    val catalogValidationInFlight = pendingCatalogValidatedSave != null
    val retainBuiltInProviderSecret =
        membersCanRetainBuiltInProviderSecret(
            initialConfig = stableInitialConfig,
            runtime = runtime,
            providerId = builtInProviderId,
            baseUrl = builtInBaseUrl,
            replacementApiKey = builtInApiKey.text
        )
    val builtInConfigForSave =
        if (runtime == CREATE_AGENT_BUILTIN_RUNTIME_ID) {
            builtInForm?.buildBuiltInRuntimeConfig(
                providerId = builtInProviderId,
                apiKey = builtInApiKey.text.ifBlank {
                    if (retainBuiltInProviderSecret) MembersRetainedBuiltInSecretPlaceholder else ""
                },
                baseUrl = builtInBaseUrl,
                model = model,
                envVars = envVarEntries.toMembersRuntimeEnvVars(),
                supportsImageInput = builtInSupportsImageInput,
                loadLocalPlugins = builtInLoadLocalPlugins
            )?.membersProjectRetainedBuiltInSecret(retainBuiltInProviderSecret)
        } else {
            null
        }
    val runtimeConfigSaveEnabled =
        if (runtime == CREATE_AGENT_BUILTIN_RUNTIME_ID) {
            builtInConfigForSave != null && !builtInFormLoading && builtInFormError == null
        } else membersAgentRuntimeConfigSaveAttemptEnabled(
            runtime = runtime,
            model = model,
            customModelMode = customModelMode,
            customModelName = customModelName,
            machineRuntimeIds = machineRuntimeIds
        )

    LaunchedEffect(runtime) {
        if (runtime.isNotBlank() && runtime != CREATE_AGENT_BUILTIN_RUNTIME_ID) {
            onRuntimeModelsRequest(runtime, false)
        }
    }
    LaunchedEffect(
        runtime,
        createAgentFormStore,
        cleanServerId,
        cleanMachineId,
        builtInFormReloadGeneration
    ) {
        if (
            runtime == CREATE_AGENT_BUILTIN_RUNTIME_ID &&
            createAgentFormStore != null &&
            cleanServerId.isNotBlank() &&
            cleanMachineId.isNotBlank()
        ) {
            builtInFormError = null
            when (
                val result = createAgentFormStore.loadSupportedBuiltInFormForEdit(
                    serverId = cleanServerId,
                    machineId = cleanMachineId,
                    force = builtInFormReloadGeneration > 0
                )
            ) {
                is DataResult.Content -> Unit
                is DataResult.Empty ->
                    builtInFormError = "runtime_form_unavailable".userFacingErrorCodeMessage(errorStrings)
                is DataResult.Error -> builtInFormError = result.error.userFacingMessage(errorStrings)
                is DataResult.NoPermission -> builtInFormError = result.blocker.userFacingMessage(errorStrings)
            }
        }
    }
    // kimi-sdk (task #275): parallel form load with the same store-level
    // generation fencing. Fail-closed: a missing/failed form just keeps the
    // reasoning selector hidden (it is optional, unlike Built-in's apiKey).
    LaunchedEffect(runtime, createAgentFormStore, cleanServerId, cleanMachineId) {
        if (
            runtime == CREATE_AGENT_KIMI_SDK_RUNTIME_ID &&
            createAgentFormStore != null &&
            cleanServerId.isNotBlank() &&
            cleanMachineId.isNotBlank()
        ) {
            createAgentFormStore.loadSupportedKimiSdkFormForEdit(cleanServerId, cleanMachineId)
        }
    }
    // kimi-sdk: reconcile the draft effort against the live capability once it
    // arrives; if it is lost mid-edit (catalog refreshed empty, form dropped),
    // fail closed back to Default with no explicit selection left to submit.
    LaunchedEffect(runtime, kimiSdkEditCapability) {
        if (runtime != CREATE_AGENT_KIMI_SDK_RUNTIME_ID) return@LaunchedEffect
        val capability = kimiSdkEditCapability
        if (capability != null) {
            kimiSdkCapabilitySeen = true
            val reconciled = reconcileKimiSdkReasoningEffort(capability, reasoningEffort)
            if (reconciled != reasoningEffort) {
                reasoningEffort = reconciled
            }
        } else if (kimiSdkCapabilitySeen) {
            reasoningEffort = null
            reasoningEffortTouched = false
        }
    }
    LaunchedEffect(runtime, builtInForm) {
        if (runtime == CREATE_AGENT_BUILTIN_RUNTIME_ID && builtInForm != null) {
            val providerSource =
                builtInForm.optionSources["provider"] as?
                    build.raft.app.data.SharedAgentCreateFormOptionSource.Select
            val selectedProvider = providerSource?.options?.singleOrNull { it.value == builtInProviderId }
            if (selectedProvider == null) {
                val nextProvider = providerSource?.defaultValue.orEmpty()
                builtInProviderId = nextProvider
                builtInBaseUrl = ""
                builtInApiKey = TextFieldValue("")
                builtInSupportsImageInput = false
                builtInLoadLocalPlugins = false
                val modelSource =
                    builtInForm.optionSources["model"] as?
                        build.raft.app.data.SharedAgentCreateFormOptionSource.DependentSelect
                model = modelSource?.defaultValueByValue?.get(nextProvider).orEmpty()
                customModelMode = modelSource?.customValueAllowedByValue?.get(nextProvider) == true
                customModelName = if (customModelMode) model else ""
            }
        }
    }
    LaunchedEffect(runtime, runtimeModelsCatalog, awaitingServerModelForRuntime) {
        if (awaitingServerModelForRuntime && runtimeModelsCatalog != null) {
            val nextModel =
                runtimeModelsCatalog.defaultModel
                    ?.takeIf { default -> runtimeModelsCatalog.models.any { it.id == default } }
                    ?: runtimeModelsCatalog.models.firstOrNull()?.id.orEmpty()
            val selection =
                createAgentRuntimeModelReasoningSelection(
                    runtime = runtime,
                    model = nextModel,
                    currentReasoningEffort = reasoningEffort,
                    runtimeModelsOverride = runtimeModelsCatalog.models
                )
            model = selection.model
            reasoningEffort = selection.reasoningEffort
            acceptedCatalogGeneration = runtimeModelGenerationsByRuntime[runtime] ?: 0L
            awaitingServerModelForRuntime = false
        }
    }
    LaunchedEffect(
        runtime,
        model,
        customModelMode,
        customModelName,
        providerMode,
        providerApiUrl,
        providerApiKey.text,
        piProviderId,
        piProviderApiKey.text,
        fastMode,
        reasoningEffort,
        command,
        envVarEntries
    ) {
        saveValidationError = null
    }
    LaunchedEffect(
        pendingCatalogValidatedSave,
        runtimeModelsLoading,
        runtimeModelsError,
        runtimeModelsCatalog,
        runtimeModelGenerationsByRuntime
    ) {
        val pending = pendingCatalogValidatedSave ?: return@LaunchedEffect
        val currentGeneration = runtimeModelGenerationsByRuntime[pending.runtime] ?: 0L
        if (currentGeneration < pending.requestGeneration || runtimeModelsLoading) {
            return@LaunchedEffect
        }
        val catalog = runtimeModelsByRuntime[pending.runtime]
        val validationError = runtimeModelErrorsByRuntime[pending.runtime]
        if (validationError != null || catalog == null) {
            saveValidationError =
                validationError
                    ?: "runtime_models_unavailable".userFacingErrorCodeMessage(errorStrings)
            pendingCatalogValidatedSave = null
            return@LaunchedEffect
        }
        if (!membersAgentRuntimeConfigSaveEnabled(
                runtime = pending.runtime,
                model = pending.model,
                customModelMode = pending.customModelMode,
                customModelName = pending.customModelName,
                machineRuntimeIds = machineRuntimeIds,
                runtimeModelsCatalog = catalog,
                runtimeModelsLoading = false,
                runtimeModelsError = null
            )
        ) {
            saveValidationError = "runtime_models_unavailable".userFacingErrorCodeMessage(errorStrings)
            pendingCatalogValidatedSave = null
            return@LaunchedEffect
        }
        acceptedCatalogGeneration = currentGeneration
        pendingCatalogValidatedSave = null
        // kimi-sdk: recompute the effort/ref decision against the catalog that
        // was just validated, so the save never targets a stale capability.
        val completedKimiSdkDecision =
            if (pending.runtime == CREATE_AGENT_KIMI_SDK_RUNTIME_ID && runtime == pending.runtime) {
                membersKimiSdkReasoningSaveDecision(
                    capability =
                        kimiSdkLiveReasoningCapability(pending.runtime, pending.model, catalog.models)
                            .takeIf { kimiSdkFormRef != null },
                    formRef = kimiSdkFormRef,
                    effortTouched = reasoningEffortTouched,
                    modelChanged = pending.model != stableInitialConfig.toMembersRuntimeModelValue(),
                    currentEffort = reasoningEffort
                )
            } else {
                null
            }
        val completedConfig =
            if (completedKimiSdkDecision != null) {
                pending.config.copy(reasoningEffort = completedKimiSdkDecision.reasoningEffort)
            } else {
                pending.config
            }
        if (membersRuntimeConfigSwitchConfirmation(stableInitialConfig, completedConfig, activeAgent) != null) {
            pendingRuntimeSwitchConfig = completedConfig
            pendingRetainBuiltInProviderSecret = pending.retainBuiltInProviderSecret
            pendingKimiSdkFormDefinitionRef = completedKimiSdkDecision?.formDefinitionRef
        } else {
            onSave(
                completedConfig,
                currentGeneration,
                pending.retainBuiltInProviderSecret,
                completedKimiSdkDecision?.formDefinitionRef
            )
        }
    }
    val runtimeEditorProfileAuthorityCurrent =
        membersAgentProfileEditAuthorityMatches(
            opened = openedProfileAuthority,
            current = currentProfileAuthority,
            // Catalog freshness is checked by the Save attempt. A background refresh must
            // never disable the editor before the user asks to validate the draft.
            openedCatalogGeneration = 0L,
            currentCatalogGeneration = 0L
        )

    val runtimeConfigListState = rememberLazyListState()
    val runtimeConfigScrollScope = rememberCoroutineScope()
    var runtimeConfigViewport by remember { mutableStateOf<Rect?>(null) }
    val runtimeConfigScrollCoordinator = remember {
        SlockFocusedEditorScrollRequestCoordinator()
    }
    DisposableEffect(runtimeConfigScrollCoordinator) {
        onDispose { runtimeConfigScrollCoordinator.invalidate() }
    }

    SlockUnifiedDialog(
        title = stringResource(MR.strings.member_dialog_edit_runtime_config).slockDisplayUppercase(),
        dismissEnabled = !saving,
        keyboardAvoidanceEnabled = true,
        onDismiss = onDismiss
    ) {
        val focusedRuntimeEditorBounds = rememberSlockFocusedInputBoundsResolver()
        val dialogKeyboardHeightChange = LocalSlockDialogKeyboardHeightChange.current

        fun keepFocusedRuntimeEditorVisible(params: KeyboardParams) {
            dialogKeyboardHeightChange(params)
            val requestGeneration = runtimeConfigScrollCoordinator.begin()
            if (params.height <= 0f) return
            val job = runtimeConfigScrollScope.launch {
                slockUnifiedDialogKeepFocusedEditorVisible(
                    keyboardHeight = params.height,
                    awaitRelayoutFrame = { withFrameNanos { } },
                    focusedBounds = focusedRuntimeEditorBounds,
                    viewportBounds = { runtimeConfigViewport },
                    currentScrollPosition = {
                        SlockUnifiedDialogListScrollPosition(
                            index = runtimeConfigListState.firstVisibleItemIndex,
                            scrollOffset = runtimeConfigListState.firstVisibleItemScrollOffset
                        )
                    },
                    scrollToItem = { index, scrollOffset ->
                        runtimeConfigListState.scrollToItem(index, scrollOffset)
                    },
                    isScrollInProgress = { runtimeConfigListState.isScrollInProgress },
                    isRequestCurrent = {
                        runtimeConfigScrollCoordinator.isCurrent(requestGeneration)
                    }
                )
            }
            runtimeConfigScrollCoordinator.attach(job)
        }

        CompositionLocalProvider(
            LocalSlockDialogKeyboardHeightChange provides ::keepFocusedRuntimeEditorVisible
        ) {
        // Kuikly's row/column measure policy hands the remaining height to unweighted
        // children first, so a capped LazyColumn can swallow the whole keyboard-reduced
        // viewport and leave the trailing Save/Cancel row at zero height.
        // We use LazyColumn(weight(1f, fill = false)) so the dialog shrink-wraps when
        // small and scrolls while preserving bottom actions when content or keyboard grows.
        LazyColumn(
            state = runtimeConfigListState,
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false)
                .onGloballyPositioned { runtimeConfigViewport = it.boundsInRoot() },
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
                item {
                    if (runtime == CREATE_AGENT_BUILTIN_RUNTIME_ID) {
                        BuiltInRuntimeSection(
                            runtime = runtime,
                            onRuntimeChange = { nextRuntime ->
                                val nextModel =
                                    if (nextRuntime == CREATE_AGENT_BUILTIN_RUNTIME_ID) {
                                        stableInitialConfig.toMembersRuntimeModelValue()
                                    } else {
                                        ""
                                    }
                                val selection =
                                    createAgentRuntimeModelReasoningSelection(
                                        runtime = nextRuntime,
                                        model = nextModel,
                                        currentReasoningEffort = reasoningEffort,
                                        runtimeModelsOverride = runtimeModelsByRuntime[nextRuntime]?.models.orEmpty()
                                    )
                                runtime = selection.runtime
                                model = selection.model
                                reasoningEffort = selection.reasoningEffort
                                if (nextRuntime != CREATE_AGENT_BUILTIN_RUNTIME_ID) {
                                    awaitingServerModelForRuntime = true
                                    customModelMode = false
                                    customModelName = ""
                                }
                            },
                            machineRuntimeIds = machineRuntimeIds,
                            runtimeAvailabilityError = null,
                            form = builtInForm,
                            loading = builtInFormLoading,
                            providerId = builtInProviderId,
                            onProviderIdChange = { nextProvider ->
                                if (nextProvider != builtInProviderId) {
                                    builtInApiKey = TextFieldValue("")
                                    builtInBaseUrl = ""
                                    builtInSupportsImageInput = false
                                    builtInLoadLocalPlugins = false
                                }
                                builtInProviderId = nextProvider
                            },
                            apiKey = builtInApiKey,
                            onApiKeyChange = { builtInApiKey = it },
                            baseUrl = builtInBaseUrl,
                            onBaseUrlChange = { builtInBaseUrl = it },
                            supportsImageInput = builtInSupportsImageInput,
                            onSupportsImageInputChange = { builtInSupportsImageInput = it },
                            loadLocalPlugins = builtInLoadLocalPlugins,
                            onLoadLocalPluginsChange = { builtInLoadLocalPlugins = it },
                            model = model,
                            onModelChange = { model = it },
                            envVarEntries = envVarEntries,
                            onEnvVarEntriesChange = { envVarEntries = it },
                            advancedOpen = advancedOpen,
                            onAdvancedOpenChange = { advancedOpen = it },
                            enabled = !saving && !catalogValidationInFlight
                        )
                        builtInFormError?.let { formError ->
                            Text(
                                text = formError,
                                style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold)
                                    .copy(color = SlockColor.AccentRed),
                                color = SlockColor.AccentRed
                            )
                            SlockBrutalActionButton(
                                label = stringResource(MR.strings.common_retry),
                                leadingIcon = SlockLucideIconName.RefreshCw,
                                enabled = !builtInFormLoading && !saving,
                                backgroundColor = SlockColor.SurfaceWhite,
                                onClick = { builtInFormReloadGeneration += 1 }
                            )
                        }
                    } else AdvancedRuntimeSection(
                        runtime = runtime,
                        existingRuntimeId = stableInitialConfig.runtime,
                        onRuntimeChange = { nextRuntime ->
                            val nextModel =
                                if (nextRuntime == CREATE_AGENT_BUILTIN_RUNTIME_ID) {
                                    stableInitialConfig.toMembersRuntimeModelValue()
                                } else {
                                    ""
                                }
                            val selection =
                                createAgentRuntimeModelReasoningSelection(
                                    runtime = nextRuntime,
                                    model = nextModel,
                                    currentReasoningEffort = reasoningEffort,
                                    runtimeModelsOverride = runtimeModelsByRuntime[nextRuntime]?.models.orEmpty()
                                )
                            runtime = selection.runtime
                            model = selection.model
                            reasoningEffort = selection.reasoningEffort
                            if (nextRuntime == CREATE_AGENT_BUILTIN_RUNTIME_ID) {
                                awaitingServerModelForRuntime = false
                            } else {
                                // A runtime switch must wait for this machine's canonical model catalog;
                                // enum/static defaults are not server truth and cannot seed a save.
                                awaitingServerModelForRuntime = true
                                customModelMode = false
                                customModelName = ""
                            }
                            providerMode = RUNTIME_PROVIDER_MODE_DEFAULT
                            providerApiUrl = ""
                            providerApiKey = TextFieldValue("")
                            piProviderId = "configured"
                            piProviderApiKey = TextFieldValue("")
                            fastMode = false
                            command = ""
                        },
                        model = model,
                        onModelSelectionChange = { selection ->
                            model = selection.model
                            // kimi-sdk: reconcile against the NEW model's live
                            // catalog entry; never leak the old model's effort.
                            reasoningEffort =
                                if (runtime == CREATE_AGENT_KIMI_SDK_RUNTIME_ID) {
                                    reconcileKimiSdkReasoningEffort(
                                        kimiSdkLiveReasoningCapability(runtime, selection.model, runtimeModelsOverride),
                                        reasoningEffort
                                    )
                                } else {
                                    selection.reasoningEffort
                                }
                            customModelName = selection.model
                        },
                        customModelMode = customModelMode,
                        onCustomModelModeChange = { enabled ->
                            customModelMode = enabled
                            if (enabled && customModelName.isBlank()) {
                                customModelName = model
                            }
                            if (enabled) {
                                reasoningEffort =
                                    reconcileCreateAgentReasoningEffort(
                                        runtime = runtime,
                                        model = customModelName.trim(),
                                        current = reasoningEffort,
                                        runtimeModelsOverride = emptyList()
                                    )
                            }
                        },
                        customModelName = customModelName,
                        onCustomModelNameChange = {
                            customModelName = it
                            model = it
                        },
                        runtimeModelsOverride = runtimeModelsOverride,
                        runtimeModelsLoading = runtimeModelsLoading,
                        // Catalog prefetch is advisory while editing. Only a Save attempt may
                        // surface its failure; the persisted draft must remain visible/editable.
                        runtimeAvailabilityError = null,
                        onRuntimeModelsRefresh = {
                            onRuntimeModelsRequest(runtime, true)
                        },
                        retainUnavailableSelectedPreset = true,
                        machineRuntimeIds = machineRuntimeIds,
                        enabled = !saving && !catalogValidationInFlight,
                        providerMode = providerMode,
                        onProviderModeChange = { providerMode = it },
                        providerApiUrl = providerApiUrl,
                        onProviderApiUrlChange = { providerApiUrl = it },
                        providerApiKey = providerApiKey,
                        onProviderApiKeyChange = { providerApiKey = it },
                        piProviderId = piProviderId,
                        onPiProviderIdChange = {
                            piProviderId = it
                            if (it == "configured") piProviderApiKey = TextFieldValue("")
                        },
                        piProviderApiKey = piProviderApiKey,
                        onPiProviderApiKeyChange = { piProviderApiKey = it },
                        fastMode = fastMode,
                        onFastModeChange = { fastMode = it },
                        reasoningEffort = reasoningEffort,
                        onReasoningEffortChange = { nextEffort ->
                            reasoningEffort = nextEffort
                            if (runtime == CREATE_AGENT_KIMI_SDK_RUNTIME_ID) {
                                reasoningEffortTouched = true
                            }
                        },
                        kimiSdkReasoningCapability = kimiSdkEditCapability,
                        envVarEntries = envVarEntries,
                        onEnvVarEntriesChange = { envVarEntries = it },
                        command = command,
                        onCommandChange = { command = it },
                        advancedOpen = advancedOpen,
                        onAdvancedOpenChange = { advancedOpen = it }
                    )
                }
                item {
                    Text(
                        text = stringResource(MR.strings.member_hint_runtime_restart),
                        style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 16.sp),
                        color = SlockColor.TextMutedGray
                    )
                }
                error?.takeIf { it.isNotBlank() }?.let { message ->
                    item {
                        Text(
                            text = message,
                            style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold)
                                .copy(color = SlockColor.AccentRed),
                            color = SlockColor.AccentRed
                        )
                    }
                }
                saveValidationError?.takeIf { it.isNotBlank() }?.let { message ->
                    item {
                        Text(
                            text = message,
                            style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold)
                                .copy(color = SlockColor.AccentRed),
                            color = SlockColor.AccentRed
                        )
                    }
                }
            }
            SlockDialogFooter {
                SlockBrutalActionButton(
                    label = stringResource(MR.strings.common_cancel),
                    enabled = !saving,
                    backgroundColor = SlockColor.SurfaceWhite,
                    onClick = onDismiss
                )
                SlockBrutalActionButton(
                    label = if (saving) stringResource(MR.strings.member_action_saving) else stringResource(MR.strings.member_action_save_runtime_config),
                    enabled =
                        !saving &&
                            !catalogValidationInFlight &&
                            runtimeConfigSaveEnabled &&
                            runtimeEditorProfileAuthorityCurrent &&
                            !customModelMissing &&
                            !providerUrlInvalid &&
                            !providerKeyInvalid &&
                            !piProviderKeyInvalid,
                    backgroundColor = SlockColor.AccentPink,
                    minWidth = 166.dp,
                    onClick = {
                        val effectiveModel = if (customModelMode) customModelName.trim() else model
                        val reasoningMetadata =
                            createAgentReasoningModelMetadata(
                                runtime = runtime,
                                model = effectiveModel,
                                runtimeModelsOverride = runtimeModelsOverride
                            )
                        val nextConfig =
                            if (runtime == CREATE_AGENT_BUILTIN_RUNTIME_ID) {
                                builtInConfigForSave ?: return@SlockBrutalActionButton
                            } else buildRuntimeConfig(
                                runtime = runtime,
                                model = if (customModelMode) customModelName else model,
                                customModelMode = customModelMode,
                                customModelName = customModelName.takeIf { customModelMode },
                                providerMode = providerMode,
                                providerApiUrl = providerApiUrl,
                                providerApiKey = providerApiKey.text,
                                piProviderId = piProviderId,
                                piProviderApiKey = piProviderApiKey.text,
                                fastMode = fastMode,
                                reasoningEffort = reasoningEffort,
                                reasoningSupportedEfforts = reasoningMetadata.supportedReasoningEfforts,
                                reasoningDefaultEffort = reasoningMetadata.defaultReasoningEffort,
                                envVars = envVarEntries.toMembersRuntimeEnvVars(),
                                command = command
                            )
                        // kimi-sdk (task #275): buildRuntimeConfig keeps kimi
                        // efforts null (static gate); the live-capability save
                        // decision is the only channel for an explicit effort
                        // and for the exact form ref.
                        val kimiSdkSaveDecision =
                            if (runtime == CREATE_AGENT_KIMI_SDK_RUNTIME_ID) {
                                membersKimiSdkReasoningSaveDecision(
                                    capability = kimiSdkEditCapability,
                                    formRef = kimiSdkFormRef,
                                    effortTouched = reasoningEffortTouched,
                                    modelChanged =
                                        effectiveModel != stableInitialConfig.toMembersRuntimeModelValue(),
                                    currentEffort = reasoningEffort
                                )
                            } else {
                                null
                            }
                        val saveConfig =
                            if (kimiSdkSaveDecision != null) {
                                nextConfig.copy(reasoningEffort = kimiSdkSaveDecision.reasoningEffort)
                            } else {
                                nextConfig
                            }
                        val saveFormDefinitionRef = kimiSdkSaveDecision?.formDefinitionRef
                        if (runtime != CREATE_AGENT_BUILTIN_RUNTIME_ID &&
                            !membersAgentRuntimeConfigSaveEnabled(
                                runtime = runtime,
                                model = model,
                                customModelMode = customModelMode,
                                customModelName = customModelName,
                                machineRuntimeIds = machineRuntimeIds,
                                runtimeModelsCatalog = runtimeModelsCatalog,
                                runtimeModelsLoading = runtimeModelsLoading,
                                runtimeModelsError = runtimeModelsError
                            )
                        ) {
                            saveValidationError = null
                            val requestGeneration =
                                (runtimeModelGenerationsByRuntime[runtime] ?: 0L) + 1L
                            pendingCatalogValidatedSave =
                                MembersPendingRuntimeConfigSave(
                                    config = saveConfig,
                                    runtime = runtime,
                                    model = model,
                                    customModelMode = customModelMode,
                                    customModelName = customModelName,
                                    retainBuiltInProviderSecret = retainBuiltInProviderSecret,
                                    requestGeneration = requestGeneration
                                )
                            onRuntimeModelsRequest(runtime, true)
                            return@SlockBrutalActionButton
                        }
                        val validatedCatalogGeneration =
                            runtimeModelGenerationsByRuntime[runtime] ?: 0L
                        acceptedCatalogGeneration = validatedCatalogGeneration
                        if (membersRuntimeConfigSwitchConfirmation(stableInitialConfig, saveConfig, activeAgent) != null) {
                            pendingRuntimeSwitchConfig = saveConfig
                            pendingRetainBuiltInProviderSecret = retainBuiltInProviderSecret
                            pendingKimiSdkFormDefinitionRef = saveFormDefinitionRef
                        } else {
                            onSave(saveConfig, validatedCatalogGeneration, retainBuiltInProviderSecret, saveFormDefinitionRef)
                        }
                    }
                )
            }
        }
    }

    pendingRuntimeSwitchConfig?.let { pendingConfig ->
        membersRuntimeConfigSwitchConfirmation(stableInitialConfig, pendingConfig, activeAgent)?.let { confirmation ->
            MembersRuntimeConfigSwitchConfirmationDialog(
                confirmation = confirmation,
                onConfirm = {
                    pendingRuntimeSwitchConfig = null
                    onSave(
                        pendingConfig,
                        acceptedCatalogGeneration,
                        pendingRetainBuiltInProviderSecret,
                        pendingKimiSdkFormDefinitionRef
                    )
                    pendingRetainBuiltInProviderSecret = false
                    pendingKimiSdkFormDefinitionRef = null
                },
                onCancel = {
                    if (!saving) {
                        pendingRuntimeSwitchConfig = null
                        pendingRetainBuiltInProviderSecret = false
                        pendingKimiSdkFormDefinitionRef = null
                    }
                }
            )
        } ?: run {
            pendingRuntimeSwitchConfig = null
        }
    }
}

private data class MembersPendingRuntimeConfigSave(
    val config: RuntimeConfig,
    val runtime: String,
    val model: String,
    val customModelMode: Boolean,
    val customModelName: String,
    val retainBuiltInProviderSecret: Boolean,
    val requestGeneration: Long
)

internal data class MembersRuntimeConfigSwitchConfirmation(
    val title: StringDesc,
    val message: String,
    val confirmLabel: StringDesc
)

internal fun membersAgentRuntimeActive(status: AgentStatus?): Boolean =
    status != null && status.presence != Presence.Offline

internal fun membersAgentRuntimeStatusResolved(status: AgentStatus?): Boolean = status != null

internal fun membersRuntimeConfigResetMode(
    currentConfig: RuntimeConfig,
    nextConfig: RuntimeConfig,
    activeAgent: Boolean
): AgentResetMode? =
    when {
        nextConfig.runtime != currentConfig.runtime -> AgentResetMode.Session
        activeAgent -> AgentResetMode.Restart
        else -> null
    }

internal data class MembersKimiSdkReasoningSaveDecision(
    val reasoningEffort: String?,
    val formDefinitionRef: SharedRuntimeFormDefinitionRef?
)

/**
 * kimi-sdk edit-save wire split (task #275), driven ONLY by live capability:
 * - effort untouched AND model unchanged → null effort and NO formDefinitionRef:
 *   the server's legacy same-model retention preserves any stored effort.
 * - effort explicitly picked, or model switched → explicit effort (reconciled
 *   against the selected model's live catalog entry, so an old model's value
 *   can never leak) plus the current exact kimi-sdk form ref.
 * - any capability gap (no resolved form ref, empty live efforts, invalid
 *   default) fails closed: null effort, no ref.
 */
internal fun membersKimiSdkReasoningSaveDecision(
    capability: KimiSdkLiveReasoningCapability?,
    formRef: SharedRuntimeFormDefinitionRef?,
    effortTouched: Boolean,
    modelChanged: Boolean,
    currentEffort: String?
): MembersKimiSdkReasoningSaveDecision {
    val ref = formRef?.takeIf { it.isSupportedKimiSdk }
    if (!effortTouched && !modelChanged) {
        return MembersKimiSdkReasoningSaveDecision(reasoningEffort = null, formDefinitionRef = null)
    }
    if (capability == null || ref == null) {
        return MembersKimiSdkReasoningSaveDecision(reasoningEffort = null, formDefinitionRef = null)
    }
    return MembersKimiSdkReasoningSaveDecision(
        reasoningEffort = currentEffort?.takeIf { it in capability.supportedReasoningEfforts },
        formDefinitionRef = ref
    )
}

internal fun membersRuntimeConfigSwitchConfirmation(
    currentConfig: RuntimeConfig,
    nextConfig: RuntimeConfig,
    activeAgent: Boolean
): MembersRuntimeConfigSwitchConfirmation? {
    if (nextConfig.runtime == currentConfig.runtime) return null

    val fromRuntimeLabel = getRuntimeDisplayName(currentConfig.runtime)
    val nextRuntimeLabel = getRuntimeDisplayName(nextConfig.runtime)
    val nextModelLabel = getModelLabel(nextConfig.runtime, nextConfig.toMembersRuntimeModelValue())
    val reasoningCopy =
        if (supportsRuntimeReasoningEffort(nextConfig.runtime)) {
            val label = nextConfig.reasoningEffort?.toMembersDisplayLabel() ?: "Default"
            if (activeAgent) " with $label reasoning" else " and $label reasoning"
        } else {
            ""
        }
    val modeCopy =
        if (supportsRuntimeFastMode(nextConfig.runtime) && nextConfig.mode is RuntimeModeConfig.Fast) {
            " in fast mode"
        } else {
            ""
        }
    val message =
        if (activeAgent) {
            "Changing runtime configuration from $fromRuntimeLabel to $nextRuntimeLabel will reset the current runtime session. " +
                "The next launch will use model $nextModelLabel$reasoningCopy$modeCopy; MEMORY.md and workspace files are preserved."
        } else {
            "Changing runtime configuration from $fromRuntimeLabel to $nextRuntimeLabel will apply on the next start with a fresh runtime session. " +
                "The agent will launch with model $nextModelLabel$reasoningCopy$modeCopy; MEMORY.md and workspace files are preserved."
        }
    return MembersRuntimeConfigSwitchConfirmation(
        title = MR.strings.member_action_switch_runtime_config.desc(),
        message = message,
        confirmLabel =
            if (activeAgent) {
                MR.strings.member_action_reset_runtime_session.desc()
            } else {
                MR.strings.member_action_save_runtime_change.desc()
            }
    )
}

@Composable
private fun MembersRuntimeConfigSwitchConfirmationDialog(
    confirmation: MembersRuntimeConfigSwitchConfirmation,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    SlockUnifiedDialog(
        title = confirmation.title.localized(),
        maxWidth = 384.dp,
        contentPadding = 24.dp,
        titleFontSize = 18,
        titleLineHeight = 22,
        onDismiss = onCancel
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // deliberate react-parity wash (web bg-*/N tint family); triaged token-audit #1043
                    .background(SlockColor.AccentOrange.copy(alpha = 0.2f))
                    .border(SlockStroke.Default, SlockColor.BorderBlack)
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                SlockLucideIcon(
                    SlockLucideIconName.Info,
                    modifier = Modifier.size(18.dp),
                    color = SlockColor.TextBlackArgb
                )
                Text(
                    text = confirmation.message,
                    style = SlockType.Caption.copy(fontSize = 14.sp, lineHeight = 20.sp),
                    color = SlockColor.TextBlack
                )
            }
            SlockDialogFooter {
                SlockBrutalActionButton(
                    label = stringResource(MR.strings.common_cancel),
                    backgroundColor = SlockColor.SurfaceWhite,
                    onClick = onCancel
                )
                SlockBrutalActionButton(
                    label = confirmation.confirmLabel.localized(),
                    backgroundColor = SlockColor.AccentOrange,
                    onClick = onConfirm
                )
            }
        }
    }
}

internal sealed interface MembersAgentRuntimeConfigResolution {
    data class Content(val config: RuntimeConfig) : MembersAgentRuntimeConfigResolution
    data class Unavailable(val code: String) : MembersAgentRuntimeConfigResolution
}

private const val MembersRetainedBuiltInSecretPlaceholder = "retained-by-server"

private fun RuntimeProviderConfig?.membersBuiltInProviderId(): String =
    when (this) {
        is RuntimeProviderConfig.Preset -> providerId
        is RuntimeProviderConfig.Gateway -> providerId
        else -> ""
    }

internal fun membersCanRetainBuiltInProviderSecret(
    initialConfig: RuntimeConfig,
    runtime: String,
    providerId: String,
    baseUrl: String,
    replacementApiKey: String
): Boolean {
    if (runtime != CREATE_AGENT_BUILTIN_RUNTIME_ID || replacementApiKey.isNotBlank()) return false
    if (initialConfig.runtime != CREATE_AGENT_BUILTIN_RUNTIME_ID) return false
    val cleanProviderId = providerId.trim()
    return when (val provider = initialConfig.provider) {
        is RuntimeProviderConfig.Preset -> provider.providerId == cleanProviderId
        is RuntimeProviderConfig.Gateway ->
            provider.providerId == cleanProviderId && provider.baseUrl.trim() == baseUrl.trim()
        else -> false
    }
}

private fun RuntimeConfig.membersProjectRetainedBuiltInSecret(retain: Boolean): RuntimeConfig {
    if (!retain || runtime != CREATE_AGENT_BUILTIN_RUNTIME_ID) return this
    val projectedProvider =
        when (val current = provider) {
            is RuntimeProviderConfig.Preset -> current.copy(apiKey = "")
            is RuntimeProviderConfig.Gateway -> current.copy(apiKey = "")
            else -> current
        }
    return copy(provider = projectedProvider)
}

internal fun membersAgentRuntimeConfigSaveEnabled(
    runtime: String,
    model: String,
    customModelMode: Boolean,
    customModelName: String,
    machineRuntimeIds: List<String>,
    runtimeModelsCatalog: SharedCreateAgentRuntimeModelCatalog?,
    runtimeModelsLoading: Boolean,
    runtimeModelsError: String?
): Boolean {
    if (
        runtime !in machineRuntimeIds ||
        runtimeModelsCatalog == null ||
        runtimeModelsLoading ||
        runtimeModelsError != null
    ) {
        return false
    }
    return if (customModelMode) {
        customModelName.trim().isNotBlank()
    } else {
        runtimeModelsCatalog.models.any { it.id == model }
    }
}

internal fun membersAgentRuntimeConfigSaveAttemptEnabled(
    runtime: String,
    model: String,
    customModelMode: Boolean,
    customModelName: String,
    machineRuntimeIds: List<String>
): Boolean {
    if (runtime !in machineRuntimeIds) return false
    return if (customModelMode) {
        customModelName.trim().isNotBlank()
    } else {
        model.trim().isNotBlank()
    }
}

internal fun AgentDetailSummary?.toMembersRuntimeConfigResolution(): MembersAgentRuntimeConfigResolution {
    val summary = this ?: return MembersAgentRuntimeConfigResolution.Unavailable("agent_runtime_profile_missing")
    val runtime = summary.runtime?.trim()?.takeIf { it.isNotBlank() }
        ?: return MembersAgentRuntimeConfigResolution.Unavailable("agent_runtime_missing")
    val model = summary.model?.trim()?.takeIf { it.isNotBlank() }
        ?: return MembersAgentRuntimeConfigResolution.Unavailable("agent_runtime_model_missing")
    return MembersAgentRuntimeConfigResolution.Content(RuntimeConfig(
        runtime = runtime,
        provider = summary.runtimeProvider,
        model = membersRuntimeModelConfig(runtime, model, summary.runtimeModelKind),
        mode = if (summary.executionMode?.trim()?.lowercase() == "fast") {
            RuntimeModeConfig.Fast
        } else {
            RuntimeModeConfig.Default
        },
        reasoningEffort = summary.reasoning?.takeIf { it.isNotBlank() },
        envVars = summary.envVars.takeIf { it.isNotEmpty() }
    ))
}

internal fun membersRuntimeModelConfig(
    runtime: String,
    model: String,
    modelKind: String?
): RuntimeModelConfig =
    when (modelKind?.trim()?.lowercase()) {
        "custom" -> RuntimeModelConfig.Custom(model)
        "preset" -> RuntimeModelConfig.Preset(model)
        else -> {
            val customModelMode = createAgentModelSelectionForValue(runtime, model).first
            if (customModelMode) RuntimeModelConfig.Custom(model) else RuntimeModelConfig.Preset(model)
        }
    }

private fun RuntimeConfig.toMembersRuntimeModelValue(): String =
    when (val modelConfig = model) {
        is RuntimeModelConfig.Preset -> modelConfig.id
        is RuntimeModelConfig.Custom -> modelConfig.name
}

internal fun membersAgentMachineRuntimeIds(
    machines: List<SharedCreateAgentMachineOption>,
    machineId: String?
): List<String> {
    val normalizedMachineId = machineId?.trim()?.takeIf { it.isNotBlank() } ?: return emptyList()
    return machines.firstOrNull { it.id == normalizedMachineId }?.runtimes.orEmpty()
}

private fun AgentDetailSummary?.membersRuntimeEnvVarNames(): List<String> =
    if (this == null) {
        emptyList()
    } else {
        (envVars.keys + envVarNames)
            .mapNotNull { it.trim().takeIf(String::isNotBlank) }
            .distinct()
            .sorted()
    }

private fun List<Pair<String, String>>.toMembersRuntimeEnvVars(): Map<String, String>? {
    val values =
        mapNotNull { (key, value) ->
            key.trim().takeIf { it.isNotBlank() }?.let { it to value }
        }.toMap()
    return values.takeIf { it.isNotEmpty() }
}

@Composable
private fun MembersAgentSkillsSection(detail: AgentDetailSnapshot?) {
    val groups = detail?.skills?.groups.orEmpty()
    val totalSkills = groups.sumOf { group -> group.paths.sumOf { path -> path.skills.size } }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
    ) {
        MembersDetailSectionDivider()
        MembersDetailSectionTitle(
            stringResource(MR.strings.member_section_skills_count).replace("%1\$s", totalSkills.toString())
        )
        if (groups.isEmpty()) {
            Text(
                text = stringResource(MR.strings.member_state_no_skills),
                style = SlockType.Body.copy(
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    fontStyle = FontStyle.Italic
                ),
                color = SlockColor.TextMutedGray
            )
        } else {
            groups.forEach { group ->
                MembersAgentSkillSubSection(group)
            }
        }
    }
}

@Composable
private fun MembersAgentSkillSubSection(group: AgentSkillGroup) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SlockLucideIcon(
                name = if (group.title.equals("workspace", ignoreCase = true)) {
                    SlockLucideIconName.FolderOpen
                } else {
                    SlockLucideIconName.Globe
                },
                modifier = Modifier.size(13.dp),
                color = SlockColor.TextMutedGrayArgb
            )
            Text(
                text = group.title,
                style = SlockType.Body.copy(fontSize = 13.sp, lineHeight = 18.sp),
                color = SlockColor.TextMutedGray
            )
            Text(
                text = "(${group.paths.sumOf { path -> path.skills.size }})",
                style = SlockType.Input.copy(fontSize = 12.sp, lineHeight = 16.sp),
                color = SlockColor.TextMutedGray.copy(alpha = 0.6f)
            )
        }
        if (group.paths.isEmpty()) {
            Text(
                text = if (group.title.equals("workspace", ignoreCase = true)) {
                    stringResource(MR.strings.member_state_no_workspace_skills)
                } else {
                    stringResource(MR.strings.member_state_no_global_skills)
                },
                style = SlockType.Caption.copy(
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    fontStyle = FontStyle.Italic
                ),
                color = SlockColor.TextMutedGray.copy(alpha = 0.7f)
            )
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
            ) {
                group.paths.forEach { pathGroup ->
                    MembersAgentSkillPathGroup(pathGroup)
                }
            }
        }
    }
}

@Composable
private fun MembersAgentSkillPathGroup(pathGroup: AgentSkillPathGroup) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = pathGroup.path,
                style = SlockType.Input.copy(fontSize = 11.sp, lineHeight = 15.sp),
                color = SlockColor.TextMutedGray.copy(alpha = 0.8f),
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "(${pathGroup.skills.size})",
                style = SlockType.Input.copy(fontSize = 11.sp, lineHeight = 15.sp),
                color = SlockColor.TextMutedGray.copy(alpha = 0.6f)
            )
        }
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
        ) {
            pathGroup.skills.forEach { skill ->
                MembersAgentSkillCard(skill)
            }
        }
    }
}

@Composable
private fun MembersAgentSkillCard(skill: AgentSkillSummary) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(SlockStroke.Default, SlockColor.BorderBlack.copy(alpha = 0.3f))
            .background(SlockColor.SurfaceWhite)
            .padding(horizontal = SlockSpacing.Md, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
    ) {
        Text(
            text = skill.name,
            style = SlockType.Body.copy(fontSize = 14.sp, lineHeight = 20.sp)
                .withSlockFontWeight(FontWeight.Bold),
            color = SlockColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        skill.detail?.takeIf { it.isNotBlank() }?.let { detail ->
            Text(
                text = detail,
                style = SlockType.Body.copy(fontSize = 12.sp, lineHeight = 17.sp),
                color = SlockColor.TextMutedGray,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun MembersAgentCreatedAgentsSection(
    detail: AgentDetailSnapshot?,
    agentStatusResolver: SlockAgentStatusResolver = EmptySlockAgentStatusResolver,
    memberAvatarResolver: SlockMemberAvatarResolver = EmptySlockMemberAvatarResolver,
    fixtureStatusFallbackEnabled: Boolean = false
) {
    val createdAgents = detail?.summary?.createdAgents.orEmpty()
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        MembersDetailSectionDivider()
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
        ) {
            MembersDetailSectionTitle(
                stringResource(MR.strings.member_section_created_agents).slockDisplayUppercase(),
                modifier = Modifier.weight(1f)
            )
            Text(
                text = createdAgents.size.toString(),
                style = SlockType.Input.copy(fontSize = 13.sp, lineHeight = 18.sp),
                color = SlockColor.TextMutedGray.copy(alpha = 0.7f)
            )
        }
        if (createdAgents.isEmpty()) {
            Text(
                text = stringResource(MR.strings.member_state_no_created_agents),
                style = SlockType.Body.copy(
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    fontStyle = FontStyle.Italic
                ),
                color = SlockColor.TextMutedGray.copy(alpha = 0.7f)
            )
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
            ) {
                createdAgents.forEach { agent ->
                    MembersAgentCreatedAgentRow(
                        agent = agent,
                        agentStatusResolver = agentStatusResolver,
                        memberAvatarResolver = memberAvatarResolver,
                        fixtureStatusFallbackEnabled = fixtureStatusFallbackEnabled
                    )
                }
            }
        }
    }
}

@Composable
private fun MembersAgentCreatedAgentRow(
    agent: AgentCreatedSummary,
    agentStatusResolver: SlockAgentStatusResolver = EmptySlockAgentStatusResolver,
    memberAvatarResolver: SlockMemberAvatarResolver = EmptySlockMemberAvatarResolver,
    fixtureStatusFallbackEnabled: Boolean = false
) {
    val profile = remember(agent) { agent.toStaticMembersProfile() }
    val observedIndicatorState =
        resolveMembersAgentStatusIndicatorStateOrNull(
            canonicalState = rememberSlockAgentStatusIndicatorState(agentStatusResolver, profile.id.value),
            fixtureFallback =
                if (fixtureStatusFallbackEnabled) {
                    agent.toFixtureSlockAgentStatusIndicatorState()
                } else {
                    null
                },
            fixtureStatusFallbackEnabled = fixtureStatusFallbackEnabled
        )
    val statusIndicatorState = observedIndicatorState ?: unknownMembersAgentStatusIndicatorState()
    val statusDisplayState = statusIndicatorState.toMembersAgentStatusDisplayState()
    val observedDisplayState = observedIndicatorState?.toMembersAgentStatusDisplayState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(SlockStroke.Default, SlockColor.BorderBlack.copy(alpha = 0.3f))
            .background(SlockColor.SurfaceWhite)
            .padding(horizontal = 12.dp, vertical = SlockSpacing.Sm),
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MembersAvatar(
            member = profile,
            avatarSize = 32.dp,
            statusSize = 0.dp,
            memberAvatarResolver = memberAvatarResolver,
            showStatusOverlay = false
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = profile.displayName,
                style = SlockType.Body.copy(fontSize = 13.sp, lineHeight = 18.sp)
                    .withSlockFontWeight(FontWeight.Bold),
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = agent.runtime?.takeIf { it.isNotBlank() }?.let { getRuntimeDisplayName(it) }
                    ?: stringResource(MR.strings.member_label_agent),
                style = SlockType.Input.copy(fontSize = 11.sp, lineHeight = 15.sp),
                color = SlockColor.TextMutedGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        // Unknown is not Offline: no dot until something is observed (task #256).
        observedDisplayState?.let { observed ->
            SlockStatusIndicator(
                color = observed.color,
                size = 10.dp,
                pulsing = observed.pulsing
            )
        }
    }
}

@Composable
private fun MembersAgentChatTab(
    detail: AgentDetailSnapshot?,
    currentServerId: String?,
    serverMembersState: SharedServerMembersState,
    timeContext: SlockTimeContext,
    pageVisibleTick: Int,
    modifier: Modifier = Modifier
) {
    val inheritedTimeContextProvider = LocalSlockTimeContextProvider.current
    val fallbackTimeCapabilities = remember(timeContext) { timeContext.toSlockTimeCapabilities() }
    val fallbackTimeContextProvider =
        rememberLiveSlockTimeContext(
            capabilities = fallbackTimeCapabilities,
            pageVisibleTick = pageVisibleTick,
            active = inheritedTimeContextProvider == null
        )
    val timeContextProvider = inheritedTimeContextProvider ?: fallbackTimeContextProvider
    CompositionLocalProvider(LocalSlockTimeContextProvider provides timeContextProvider) {
        MembersAgentChatTabContent(
            detail = detail,
            currentServerId = currentServerId,
            serverMembersState = serverMembersState,
            modifier = modifier
        )
    }
}

@Composable
private fun MembersAgentChatTabContent(
    detail: AgentDetailSnapshot?,
    currentServerId: String?,
    serverMembersState: SharedServerMembersState,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        Text(
            text = stringResource(MR.strings.member_section_agent_channels_dms).slockDisplayUppercase(),
            style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 16.sp)
                .withSlockFontWeight(FontWeight.Bold),
            color = SlockColor.TextMutedGray
        )
        Text(
            text = stringResource(MR.strings.member_section_channels).slockDisplayUppercase(),
            style = SlockType.Caption.copy(fontSize = 10.sp, lineHeight = 14.sp)
                .withSlockFontWeight(FontWeight.Bold),
            color = SlockColor.TextMutedGray
        )
        val channels = detail?.channels.orEmpty().filter { it.archivedAt.isNullOrBlank() }
        if (channels.isEmpty()) {
            Text(
                text = stringResource(MR.strings.member_state_no_active_channels),
                style = SlockType.Body.copy(fontSize = 13.sp, lineHeight = 18.sp),
                color = SlockColor.TextMutedGray
            )
        } else {
            channels.forEach { channel ->
                MembersAgentChannelRow(channel)
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(SlockStroke.Thin)
                .background(SlockColor.BorderBlack.copy(alpha = 0.15f))
        )
        Text(
            text = stringResource(MR.strings.member_section_private_chats).slockDisplayUppercase(),
            style = SlockType.Caption.copy(fontSize = 10.sp, lineHeight = 14.sp)
                .withSlockFontWeight(FontWeight.Bold),
            color = SlockColor.TextMutedGray
        )
        Text(
            text = stringResource(MR.strings.member_hint_chat_audit_only),
            style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 16.sp),
            color = SlockColor.TextMutedGray.copy(alpha = 0.7f)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(SlockStroke.Thin)
                .background(SlockColor.BorderBlack.copy(alpha = 0.15f))
        )
        val rows = detail?.directMessages.orEmpty()
        if (rows.isEmpty()) {
            Text(
                text = stringResource(MR.strings.member_state_no_agent_dms),
                style = SlockType.Body.copy(fontSize = 13.sp, lineHeight = 18.sp),
                color = SlockColor.TextMutedGray
            )
        } else {
            rows.take(8).forEach { row ->
                MembersAgentDmRow(row, currentServerId, serverMembersState)
            }
        }
    }
}

@Composable
private fun MembersAgentChannelRow(channel: AgentChannelSummary) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(SlockStroke.Thin, SlockColor.BorderBlack.copy(alpha = 0.2f))
            .background(SlockColor.SurfaceWhite)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SlockLucideIcon(
                channel.type.threadChannelHeaderIcon(),
                modifier = Modifier.size(15.dp),
                color = SlockColor.TextBlackArgb
            )
            Text(
                text = channel.name,
                style = SlockType.Body.copy(fontSize = 13.sp, lineHeight = 18.sp)
                    .withSlockFontWeight(FontWeight.Bold),
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (channel.activityMuted) {
                SlockLucideIcon(
                    SlockLucideIconName.BellOff,
                    modifier = Modifier.size(15.dp),
                    color = SlockColor.TextMutedGrayArgb
                )
                Text(
                    text = stringResource(MR.strings.member_label_muted).slockDisplayUppercase(),
                    style = SlockType.Caption.copy(fontSize = 9.sp, lineHeight = 12.sp)
                        .withSlockFontWeight(FontWeight.Bold),
                    color = SlockColor.TextMutedGray
                )
            }
        }
        channel.description?.takeIf { it.isNotBlank() }?.let { description ->
            Text(
                text = description,
                style = SlockType.Body.copy(fontSize = 12.sp, lineHeight = 17.sp),
                color = SlockColor.TextMutedGray,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

internal fun SharedServerMembersState.membersAgentDmPeerProfile(
    row: AgentDmSummary,
    currentServerId: String?
): MemberProfile? {
    val cleanServerId = currentServerId?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val cleanPeerId = row.peerId?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val profile = memberProfile(ServerId(cleanServerId), cleanPeerId) ?: return null
    return profile.takeIf {
        it.id.value.trim() == cleanPeerId &&
            it.serverId?.value?.trim() == cleanServerId
    }
}

@Composable
private fun MembersAgentDmRow(
    row: AgentDmSummary,
    currentServerId: String?,
    serverMembersState: SharedServerMembersState
) {
    val avatarCacheIdentity =
        remember(row.peerId, currentServerId, serverMembersState) {
            serverMembersState
                .membersAgentDmPeerProfile(row, currentServerId)
                ?.slockAvatarCacheIdentity(currentServerId)
        }
    // React parity: subtle bordered card (alpha 0.2 border, no brutal shadow)
    // with avatar + name + handle + relative time on the top row, body text
    // on the second row, and an ACTIVITY ONLY tag on the third row.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(SlockStroke.Thin, SlockColor.BorderBlack.copy(alpha = 0.2f))
            .background(SlockColor.SurfaceWhite)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(MR.strings.member_label_agent_dm),
                style = SlockType.Caption.copy(fontSize = 10.sp, lineHeight = 14.sp),
                color = SlockColor.TextMutedGray
            )
            SlockAvatar(
                avatarWireValue = row.peerAvatarUrl,
                label = row.peerName.take(1).uppercase(),
                avatarSize = 22.dp,
                borderWidth = SlockStroke.Thin,
                cacheSubject = avatarCacheIdentity?.subject,
                cacheServerId = avatarCacheIdentity?.serverId
            )
            Text(
                text = row.peerName,
                style = SlockType.Body.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 12.sp, lineHeight = 16.sp),
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "@${row.peerName}",
                style = SlockType.Caption.copy(fontSize = 10.sp, lineHeight = 14.sp),
                color = SlockColor.TextMutedGray,
                maxLines = 1
            )
            SlockRelativeTimestampText(
                value = row.lastMessageAt,
                style = SlockType.Caption.copy(fontSize = 10.sp, lineHeight = 14.sp),
                color = SlockColor.TextMutedGray,
                maxLines = 1
            )
        }
        if (!row.lastMessagePreview.isNullOrBlank()) {
            Text(
                text = row.lastMessagePreview,
                style = SlockType.Body.copy(fontSize = 13.sp, lineHeight = 18.sp),
                color = SlockColor.TextBlack,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = stringResource(MR.strings.member_label_activity_only).slockDisplayUppercase(),
            style = SlockType.Caption.copy(fontSize = 9.sp, lineHeight = 12.sp)
                .withSlockFontWeight(FontWeight.Bold),
            color = SlockColor.TextMutedGray.copy(alpha = 0.7f)
        )
    }
}

@Composable
private fun MembersAgentRemindersTab(
    detail: AgentDetailSnapshot?,
    timeContext: SlockTimeContext,
    onOpenMessageRef: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val rows = detail?.reminders.orEmpty()
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (rows.isEmpty()) {
            SlockEmptyState(
                title = stringResource(MR.strings.member_state_no_reminders),
                detail = stringResource(MR.strings.member_hint_no_scheduled_reminders),
                iconName = SlockLucideIconName.BellRing,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 220.dp, max = 520.dp)
            )
        } else {
            rows.take(8).forEach { reminder ->
                MembersAgentReminderCard(reminder, timeContext, onOpenMessageRef)
            }
        }
    }
}

@Composable
private fun MembersAgentReminderCard(
    reminder: AgentReminderSummary,
    timeContext: SlockTimeContext,
    onOpenMessageRef: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(SlockStroke.Default, SlockColor.BorderBlack.copy(alpha = 0.3f))
            .background(SlockColor.SurfaceWhite)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
    ) {
        Text(
            text = reminder.title,
            style = SlockType.Body.copy(fontSize = 14.sp, lineHeight = 20.sp)
                .withSlockFontWeight(FontWeight.Bold),
            color = SlockColor.TextBlack,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SlockLucideIcon(
                    SlockLucideIconName.Clock3,
                    modifier = Modifier.size(12.dp),
                    color = "#99000000"
                )
                Text(
                    text = reminder.fireAt.membersReminderRelativeTime(timeContext),
                    style = SlockType.Caption.withSlockFontWeight(FontWeight.Medium)
                        .copy(fontSize = 12.sp, lineHeight = 16.sp),
                    color = SlockColor.TextBlack.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = reminder.fireAt.membersReminderShortDateTime(timeContext),
                style = SlockType.Caption.copy(fontFamily = SlockMonoFamily, fontSize = 12.sp, lineHeight = 16.sp),
                color = SlockColor.TextBlack.copy(alpha = 0.5f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        reminder.recurrenceDescription?.takeIf { it.isNotBlank() }?.let { recurrence ->
            MembersAgentReminderChip(
                iconName = SlockLucideIconName.RefreshCw,
                text = recurrence,
                // deliberate react-parity wash (web bg-*/N tint family); triaged token-audit #1043
                background = SlockColor.AccentLavender.copy(alpha = 0.3f),
                textColor = SlockColor.TextBlack
            )
        }
        reminder.messageRef?.takeIf { it.isNotBlank() }?.let { messageRef ->
            val permalink = reminder.messagePermalink?.takeIf { it.isNotBlank() }
            MembersAgentReminderChip(
                iconName = SlockLucideIconName.Link2,
                text = messageRef,
                background = SlockColor.SurfaceWhite,
                textColor =
                    if (permalink != null) {
                        SlockColor.TextBlack
                    } else {
                        SlockColor.TextBlack.copy(alpha = 0.7f)
                    },
                onClick = permalink?.let { link -> { onOpenMessageRef(link) } }
            )
        }
    }
}

@Composable
private fun MembersAgentReminderChip(
    iconName: SlockLucideIconName,
    text: String,
    background: Color,
    textColor: Color,
    onClick: (() -> Unit)? = null
) {
    val interactionModifier =
        if (onClick != null) {
            Modifier.clickable(onClick = onClick)
        } else {
            Modifier
        }
    Row(
        modifier = interactionModifier
            .border(SlockStroke.Thin, SlockColor.BorderBlack)
            .background(background)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SlockLucideIcon(iconName, modifier = Modifier.size(11.dp), color = SlockColor.TextBlackArgb)
        Text(
            text = text,
            style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 14.sp),
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun MembersAgentWorkspaceTab(
    treeState: MembersAgentWorkspaceTreeState,
    expandedDirs: Set<String>,
    loadedDirs: Map<String, List<SlockWorkspaceNode>>,
    loadingDirs: Set<String>,
    dirErrors: Map<String, String>,
    onRefresh: () -> Unit,
    onToggleDirectory: (String) -> Unit,
    onSelectFile: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val errorStrings = localizedErrorPresentationStrings()
    MembersDetailSection(
        title = stringResource(MR.strings.member_section_workspace).slockDisplayUppercase(),
        modifier = modifier,
        framed = false,
        trailingContent = {
            SlockBrutalIconButton(
                iconName = SlockLucideIconName.RefreshCw,
                onClick = onRefresh,
                size = 30.dp,
                shadowOffset = 2.dp
            )
        }
    ) {
        when (treeState) {
            MembersAgentWorkspaceTreeState.Idle,
            MembersAgentWorkspaceTreeState.Loading ->
                MembersDetailField(
                    stringResource(MR.strings.member_label_status),
                    stringResource(MR.strings.member_state_workspace_loading)
                )
            MembersAgentWorkspaceTreeState.Empty ->
                MembersWorkspaceEmptyState(stringResource(MR.strings.member_state_no_files), onRefresh)
            is MembersAgentWorkspaceTreeState.Error ->
                MembersWorkspaceErrorState(
                    stringResource(
                        MR.strings.member_error_workspace_load,
                        treeState.error.userFacingMessage(errorStrings)
                    ),
                    onRefresh
                )
            is MembersAgentWorkspaceTreeState.NoPermission ->
                MembersWorkspaceErrorState(
                    stringResource(
                        MR.strings.member_error_workspace_hidden,
                        treeState.blocker.userFacingMessage(errorStrings)
                    ),
                    onRefresh
                )
            is MembersAgentWorkspaceTreeState.Content ->
                MembersWorkspaceTreeView(
                    root = treeState.snapshot.root,
                    expandedDirs = expandedDirs,
                    loadedDirs = loadedDirs,
                    loadingDirs = loadingDirs,
                    dirErrors = dirErrors,
                    onToggleDirectory = onToggleDirectory,
                    onSelectFile = onSelectFile
                )
        }
    }
}

@Composable
private fun MembersWorkspaceTreeView(
    root: SlockWorkspaceNode,
    expandedDirs: Set<String>,
    loadedDirs: Map<String, List<SlockWorkspaceNode>>,
    loadingDirs: Set<String>,
    dirErrors: Map<String, String>,
    onToggleDirectory: (String) -> Unit,
    onSelectFile: (String) -> Unit
) {
    val rows = remember(root, expandedDirs, loadedDirs) {
        membersWorkspaceRows(root.children, expandedDirs, loadedDirs)
    }
    if (rows.isEmpty()) {
        MembersDetailField(stringResource(MR.strings.member_section_workspace), stringResource(MR.strings.member_state_no_files))
        return
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 280.dp, max = 560.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        beyondBoundsItemCount = sharedListBeyondBoundsItemCount(rows.size)
    ) {
        items(rows.size, key = { "workspace-${rows[it].node.path}" }) { index ->
            val row = rows[index]
            MembersWorkspaceNodeRow(
                row = row,
                expanded = row.node.path in expandedDirs,
                loading = row.node.path in loadingDirs,
                errorMessage = dirErrors[row.node.path],
                onToggleDirectory = onToggleDirectory,
                onSelectFile = onSelectFile
            )
        }
    }
}

@Composable
private fun MembersWorkspaceNodeRow(
    row: MembersWorkspaceFlatNode,
    expanded: Boolean,
    loading: Boolean,
    errorMessage: String?,
    onToggleDirectory: (String) -> Unit,
    onSelectFile: (String) -> Unit
) {
    val node = row.node
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 30.dp)
                .clickable {
                    if (node.kind == SlockWorkspaceFileKind.Folder) {
                        onToggleDirectory(node.path)
                    } else {
                        onSelectFile(node.path)
                    }
                }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Spacer(Modifier.width((row.depth * 14).dp))
            if (node.kind == SlockWorkspaceFileKind.Folder) {
                if (loading) {
                    // #70: replace the chevron with a rotating Loader spinner
                    // while the directory contents are being fetched. Mirrors
                    // React's `AgentWorkspace.tsx` spinner pattern (but KMP
                    // per @artin's 06-23 20:51 directive shows the spinner
                    // inline as the chevron substitute instead of alongside
                    // a "Loading..." text label).
                    SlockSpinnerIcon(size = 13.dp)
                } else {
                    SlockLucideIcon(
                        if (expanded) SlockLucideIconName.ChevronDown else SlockLucideIconName.ChevronRight,
                        Modifier.size(13.dp),
                    )
                }
            } else {
                Spacer(Modifier.width(13.dp))
            }
            SlockLucideIcon(node.membersWorkspaceIconName(expanded), Modifier.size(14.dp))
            Text(
                text = node.name.ifBlank { node.path },
                style = SlockType.Body.copy(fontSize = 13.sp, lineHeight = 18.sp)
                    .withSlockFontWeight(if (node.name == "memory.md") FontWeight.Bold else FontWeight.Normal),
                color = SlockColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            node.meta?.takeIf { it.isNotBlank() }?.let { meta ->
                Text(
                    text = meta,
                    style = SlockType.Caption.copy(fontSize = 9.sp, lineHeight = 12.sp),
                    color = SlockColor.TextMutedGray,
                    maxLines = 1
                )
            }
            if (loading) {
                Text(
                    text = stringResource(MR.strings.common_loading),
                    style = SlockType.Caption.copy(fontSize = 9.sp, lineHeight = 12.sp),
                    color = SlockColor.TextMutedGray,
                    maxLines = 1
                )
            }
        }
        errorMessage?.let {
            Text(
                text = stringResource(MR.strings.member_error_workspace_child_load, it),
                style = SlockType.Caption.copy(fontSize = 10.sp, lineHeight = 14.sp),
                color = SlockColor.AccentOrange,
                modifier = Modifier.padding(start = ((row.depth + 1) * 14).dp)
            )
        }
    }
}

@Composable
private fun MembersWorkspaceEmptyState(message: String, onRefresh: () -> Unit) {
    MembersDetailField(stringResource(MR.strings.member_label_files), message)
    SlockBrutalActionButton(
        label = stringResource(MR.strings.common_retry),
        leadingIcon = SlockLucideIconName.RefreshCw,
        enabled = true,
        modifier = Modifier.fillMaxWidth(),
        onClick = onRefresh
    )
}

@Composable
private fun MembersWorkspaceErrorState(message: String, onRefresh: () -> Unit) {
    MembersDetailField(stringResource(MR.strings.member_label_state), message)
    SlockBrutalActionButton(
        label = stringResource(MR.strings.common_retry),
        leadingIcon = SlockLucideIconName.RefreshCw,
        enabled = true,
        modifier = Modifier.fillMaxWidth(),
        onClick = onRefresh
    )
}

@Composable
private fun MembersAgentAppsTab(
    detail: AgentDetailSnapshot?,
    timeContext: SlockTimeContext,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val errorStrings = localizedErrorPresentationStrings()
    MembersDetailSection(title = stringResource(MR.strings.member_section_connected_apps).slockDisplayUppercase(), modifier = modifier) {
        Text(
            text = stringResource(MR.strings.member_hint_connected_apps),
            style = SlockType.Body.copy(fontSize = 13.sp, lineHeight = 18.sp),
            color = SlockColor.TextMutedGray
        )
        val appLogins = detail?.appLogins
        when (appLogins?.loadState) {
            AgentAppLoginLoadState.NoPermission ->
                MembersDetailField(
                    stringResource(MR.strings.member_tab_apps),
                    appLogins.permissionBlocker?.userFacingMessage(errorStrings)
                        ?: appLogins.errorCode?.userFacingErrorCodeMessage(errorStrings)
                        ?: stringResource(MR.strings.member_error_apps_private)
                )
            AgentAppLoginLoadState.Error ->
                MembersDetailField(
                    stringResource(MR.strings.member_tab_apps),
                    stringResource(
                        MR.strings.member_error_apps_load,
                        appLogins.screenError?.userFacingMessage(errorStrings)
                            ?: appLogins.errorCode.userFacingErrorCodeMessage(errorStrings)
                    )
                )
            else -> {
                val activeItems = appLogins?.items.orEmpty()
                    .filter { it.type == AgentAppLoginType.Active && it.revokedAt.isNullOrBlank() }
                if (activeItems.isEmpty()) {
                    MembersDetailField(
                        stringResource(MR.strings.member_tab_apps),
                        stringResource(MR.strings.member_state_no_connected_apps)
                    )
                } else {
                    activeItems.forEach { item ->
                        MembersAgentAppLoginCard(
                            item = item,
                            timeContext = timeContext,
                            onOpenUrl = onOpenUrl
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MembersAgentAppLoginCard(
    item: AgentAppLoginItem,
    timeContext: SlockTimeContext,
    onOpenUrl: (String) -> Unit
) {
    SlockBrutalCard(
        shadowOffset = 3.dp,
        contentPadding = SlockSpacing.Md,
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm),
            verticalAlignment = Alignment.Top
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = item.clientName,
                    style = SlockType.ButtonBold.copy(fontSize = 14.sp, lineHeight = 18.sp),
                    color = SlockColor.TextBlack,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                item.createdAt?.let {
                    Text(
                        text = stringResource(
                            MR.strings.member_label_connected_since,
                            it.membersActivityDisplayTime(timeContext)
                        ),
                        style = SlockType.Caption.copy(fontSize = 10.sp, lineHeight = 14.sp),
                        color = SlockColor.TextMutedGray
                    )
                }
            }
            MembersAgentAppLoginStatusBadge(stringResource(MR.strings.member_label_active))
        }
        item.clientDescription?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                style = SlockType.Body.copy(fontSize = 12.sp, lineHeight = 17.sp),
                color = SlockColor.TextBlack
            )
        }
        item.clientAgentManifestUrl?.takeIf { it.isNotBlank() }?.let {
            MembersDetailField(stringResource(MR.strings.member_label_agent_manifest), it)
        }
        if (item.scopes.isNotEmpty()) {
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs),
                verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
            ) {
                item.scopes.forEach { scope ->
                    MembersAgentAppLoginScopeChip(scope)
                }
            }
        }
        item.clientHomepageUrl?.takeIf { it.isNotBlank() }?.let { homepageUrl ->
            SlockBrutalActionButton(
                label = stringResource(MR.strings.member_action_view_service),
                leadingIcon = SlockLucideIconName.ExternalLink,
                enabled = true,
                backgroundColor = SlockColor.SurfaceWhite,
                modifier = Modifier.fillMaxWidth(),
                onClick = { onOpenUrl(homepageUrl) }
            )
        }
    }
}

@Composable
private fun MembersAgentAppLoginStatusBadge(label: String) {
    SlockBrutalBadge(label = label, tone = SlockBrutalTone.Lime)
}

@Composable
private fun MembersAgentAppLoginScopeChip(scope: String) {
    SlockBrutalBadge(label = scope, tone = SlockBrutalTone.White)
}

@Composable
private fun MembersAgentActivityTab(
    detail: AgentDetailSnapshot?,
    loadState: MembersAgentActivityLoadState,
    diagnosticActionState: MembersAgentDiagnosticActionState?,
    onCopyDiagnostics: () -> Unit,
    timeContext: SlockTimeContext,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
    autoScrollToLatest: Boolean = true
) {
    val errorStrings = localizedErrorPresentationStrings()
    val copySucceeded =
        diagnosticActionState?.actionKey == "copy" && diagnosticActionState.successLabel != null
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(SlockColor.SurfaceWhite),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = MembersPageInset,
                    vertical = SlockSpacing.Xs
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
        ) {
            MembersDetailSectionTitle(
                stringResource(MR.strings.member_section_activity_diagnostics).slockDisplayUppercase(),
                modifier = Modifier.weight(1f)
            )
            SlockBrutalActionButton(
                label = if (copySucceeded) stringResource(MR.strings.common_done) else stringResource(MR.strings.member_action_copy_info),
                leadingIcon = if (copySucceeded) SlockLucideIconName.Check else SlockLucideIconName.Clipboard,
                enabled = diagnosticActionState?.inProgress != true,
                minHeight = 32.dp,
                minWidth = 72.dp,
                shadowOffset = 2.dp,
                textStyle = SlockType.ButtonBold.copy(fontSize = 11.sp, lineHeight = 14.sp),
                onClick = onCopyDiagnostics
            )
        }
        MembersDetailSectionDivider()
        val rows =
            remember(detail?.agentId, detail?.activity) {
                detail?.activity.orEmpty()
                    .filterNot { it.type == "tool_start" && it.toolName?.normalizedMembersToolName() == "send_message" }
                    .sortedWith(compareBy<AgentActivityEntry> { it.timestamp.membersActivitySortKey() }.thenBy { it.id })
            }
        val hasRows = rows.isNotEmpty()
        if (hasRows && (loadState is MembersAgentActivityLoadState.Refreshing || loadState is MembersAgentActivityLoadState.Error)) {
            MembersAgentActivityLoadNotice(loadState)
        }
        if (!hasRows && (loadState is MembersAgentActivityLoadState.Loading || loadState is MembersAgentActivityLoadState.Refreshing)) {
            SlockCenteredLoading(
                message = stringResource(MR.strings.member_state_loading_activity),
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
        } else if (!hasRows && loadState is MembersAgentActivityLoadState.Error) {
            SlockEmptyState(
                title = stringResource(MR.strings.member_error_load_activity),
                detail = loadState.error.userFacingMessage(errorStrings),
                iconName = SlockLucideIconName.Activity,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        } else if (rows.isEmpty()) {
            SlockEmptyState(
                title = stringResource(MR.strings.member_state_no_activity),
                detail = stringResource(MR.strings.member_state_activity_start_agent),
                iconName = SlockLucideIconName.Activity,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        } else {
            val initialActivityIndex = membersAgentActivityInitialFirstVisibleIndex(rows.size)
            val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialActivityIndex)
            var allowBottomAnchor by remember(detail?.agentId) { mutableStateOf(true) }
            if (autoScrollToLatest) {
                LaunchedEffect(detail?.agentId, rows.size) {
                    snapshotFlow {
                        listState.isScrollInProgress to listState.isMembersActivityAtBottom(rows.lastIndex)
                    }
                        .distinctUntilChanged()
                        .collect { (isScrollInProgress, isAtBottom) ->
                            val nextAllow = membersAgentActivityNextBottomAnchor(
                                current = allowBottomAnchor,
                                isScrollInProgress = isScrollInProgress,
                                isAtBottom = isAtBottom
                            )
                            if (allowBottomAnchor != nextAllow) {
                                allowBottomAnchor = nextAllow
                            }
                        }
                }
                LaunchedEffect(detail?.agentId, rows.size, rows.lastOrNull()?.activityTimelineKey()) {
                    if (rows.isNotEmpty() && allowBottomAnchor && !listState.isScrollInProgress) {
                        listState.scrollToItem(rows.lastIndex)
                    }
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .nestedScroll(
                        scrollUp = NestedScrollMode.SELF_FIRST,
                        scrollDown = NestedScrollMode.SELF_FIRST
                    ),
                verticalArrangement = Arrangement.spacedBy(0.dp),
                beyondBoundsItemCount = sharedListBeyondBoundsItemCount(rows.size)
            ) {
                items(rows.size, key = { rows[it].activityTimelineKey() }) { index ->
                    MembersAgentActivityRow(rows[index], timeContext, onOpenUrl)
                }
            }
        }
    }
}

@Composable
private fun MembersAgentActivityLoadNotice(
    state: MembersAgentActivityLoadState,
    modifier: Modifier = Modifier
) {
    val errorStrings = localizedErrorPresentationStrings()
    val message =
        when (state) {
            MembersAgentActivityLoadState.Refreshing -> stringResource(MR.strings.member_state_refreshing_activity)
            is MembersAgentActivityLoadState.Error ->
                "${stringResource(MR.strings.member_error_load_activity)}: ${state.error.userFacingMessage(errorStrings)}"
            MembersAgentActivityLoadState.Idle,
            MembersAgentActivityLoadState.Loading -> return
        }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(SlockColor.SurfaceCream)
            .padding(horizontal = MembersPageInset, vertical = SlockSpacing.Xs),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = message,
            style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 15.sp)
                .withSlockFontWeight(FontWeight.Bold),
            color = SlockColor.TextBlack
        )
    }
}

@Composable
private fun MembersAgentActivityRow(
    activity: AgentActivityEntry,
    timeContext: SlockTimeContext,
    onOpenUrl: (String) -> Unit
) {
    val key = activity.activityTimelineKey()
    var expanded by remember(key) { mutableStateOf(false) }
    val body = activity.activityPrimaryDetail().orEmpty()
    val longBody = body.length > 200 || body.count { it == '\n' } > 2
    val displayBody =
        if (!expanded && longBody) {
            body.take(200).trimEnd() + "…"
        } else {
            body
        }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SlockColor.SurfaceWhite)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        // Web: `shrink-0 font-mono text-xs whitespace-nowrap` — natural width, no fixed column.
        Text(
            text = activity.timestamp.membersActivityDisplayTime(timeContext),
            style = SlockType.Caption.copy(fontFamily = SlockMonoFamily, fontSize = 12.sp, lineHeight = 16.sp),
            color = SlockColor.TextMutedGray,
            maxLines = 1
        )
        Box(
            modifier = Modifier.requiredWidth(MembersAgentActivityIndicatorLaneWidth),
            contentAlignment = Alignment.TopCenter
        ) {
            SlockStatusIndicator(
                color = (activity.statusActivity ?: activity.type).membersActivityAccentColor(),
                size = 8.dp,
                pulsing = (activity.statusActivity ?: activity.type).membersActivityPulsing(),
                modifier = Modifier.padding(top = 6.dp)
            )
        }
        Column(
            modifier = Modifier
                .padding(start = MembersAgentActivityContentGap)
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = activity.title,
                    style = SlockType.Body.copy(fontSize = 14.sp, lineHeight = 20.sp)
                        .withSlockFontWeight(FontWeight.Medium),
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                activity.membersActivitySubagentBadgeLabel()?.let { subagentLabel ->
                    SlockBrutalBadge(
                        label = subagentLabel,
                        tone = SlockBrutalTone.White,
                        uppercase = false
                    )
                }
            }
            if (displayBody.isNotBlank()) {
                // #64 owner override (artin 06-23 17:41): '改成用 SlockMarkdownBody
                // 比较好，它能展示出来 #xxx 这些东西'. Restore the markdown branch so
                // the activity body can render task refs / issue refs (e.g. `#225`,
                // `#task-7`) as styled inline text + clickable onOpenUrl links
                // when `refTextEnabled` is true.
                if (activity.refTextEnabled) {
                    SlockMarkdownBody(
                        body = displayBody,
                        textStyle = SlockType.Caption.copy(fontFamily = SlockMonoFamily, fontSize = 12.sp, lineHeight = 16.sp),
                        onOpenUrl = onOpenUrl,
                    )
                } else {
                    Text(
                        text = displayBody,
                        style = SlockType.Caption.copy(fontFamily = SlockMonoFamily, fontSize = 12.sp, lineHeight = 16.sp),
                        color = SlockColor.TextMutedGray,
                        maxLines = if (expanded) 12 else 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (longBody) {
                Text(
                    text = if (expanded) stringResource(MR.strings.member_label_show_less) else stringResource(MR.strings.member_label_show_more),
                    style = SlockType.Caption.copy(fontSize = 10.sp, lineHeight = 14.sp)
                        .withSlockFontWeight(FontWeight.Bold),
                    color = SlockColor.TextBlack,
                    modifier = Modifier.clickable { expanded = !expanded }
                )
            }
        }
    }
}

@Composable
private fun MembersAgentPlaceholderTab(title: String, detail: String, modifier: Modifier = Modifier) {
    MembersDetailSection(title = title, modifier = modifier) {
        MembersDetailField(stringResource(MR.strings.member_label_state), detail)
    }
}

// #89 React parity: agent scope grant list + per-group row definitions.
// Mirrors `packages/shared/src/agentScopes.ts` AGENT_SCOPE_GROUPS. Kept local
// (private to MembersPage.kt) for now — owner pending whether to lift to
// shared model module if other surfaces need it. Two sections
// ("notifications" / "cli") group 14 grantable scopes into 8 resources;
// each row carries the scope token, a short verbLabel and a one-sentence
// description that mirrors the React `AgentScopeGroup.scopes[]` shape.
private data class MembersAgentScopeRow(
    val scope: String,
    val verbLabel: String,
    val description: String
)

private data class MembersAgentScopeGroup(
    val resource: String,
    val section: String,
    val label: String,
    val description: String,
    val scopes: List<MembersAgentScopeRow>
)

private val membersAgentScopeGroups: List<MembersAgentScopeGroup> = listOf(
    MembersAgentScopeGroup(
        resource = "inbox",
        section = "notifications",
        label = "Inbox",
        description = "Control whether new activity can be delivered to this agent and wake it up.",
        scopes = listOf(
            MembersAgentScopeRow(
                scope = "inbox:receive",
                verbLabel = "Receive notifications",
                description = "Receive new inbox events and wake for activity from other people and agents."
            )
        )
    ),
    MembersAgentScopeGroup(
        resource = "server",
        section = "cli",
        label = "Server",
        description = "Discover channels, members, and other agents in this server.",
        scopes = listOf(
            MembersAgentScopeRow(
                scope = "server:read",
                verbLabel = "Read",
                description = "List channels, members, and agents (slock server info)."
            )
        )
    ),
    MembersAgentScopeGroup(
        resource = "channel",
        section = "cli",
        label = "Channels",
        description = "View channel rosters, join visible public channels, and leave channels the agent is no longer using.",
        scopes = listOf(
            MembersAgentScopeRow("channel:read", "Read", "View channel membership and metadata."),
            MembersAgentScopeRow("channel:join", "Join", "Join visible public channels."),
            MembersAgentScopeRow("channel:leave", "Leave", "Leave channels the agent is no longer using.")
        )
    ),
    MembersAgentScopeGroup(
        resource = "thread",
        section = "cli",
        label = "Threads",
        description = "Follow and unfollow thread updates.",
        scopes = listOf(
            MembersAgentScopeRow("thread:unfollow", "Unfollow", "Unfollow a thread to stop receiving updates.")
        )
    ),
    MembersAgentScopeGroup(
        resource = "message",
        section = "cli",
        label = "Messages",
        description = "Read, search, and send messages across channels, threads, and DMs.",
        scopes = listOf(
            MembersAgentScopeRow("message:read", "Read", "Read / search / resolve messages."),
            MembersAgentScopeRow("message:send", "Send", "Send messages (DM / channel / thread).")
        )
    ),
    MembersAgentScopeGroup(
        resource = "attachment",
        section = "cli",
        label = "Attachments",
        description = "Upload and view file attachments in conversation.",
        scopes = listOf(
            MembersAgentScopeRow("attachment:upload", "Upload", "Upload files to conversation."),
            MembersAgentScopeRow("attachment:view", "View", "View uploaded files in conversation.")
        )
    ),
    MembersAgentScopeGroup(
        resource = "task",
        section = "cli",
        label = "Tasks",
        description = "List, create, and update tasks on the board.",
        scopes = listOf(
            MembersAgentScopeRow("task:read", "Read", "List tasks."),
            MembersAgentScopeRow("task:write", "Write", "Create, claim, unclaim, update tasks.")
        )
    ),
    MembersAgentScopeGroup(
        resource = "knowledge",
        section = "cli",
        label = "Knowledge",
        description = "Read agent manual / knowledge base entries.",
        scopes = listOf(
            MembersAgentScopeRow("knowledge:read", "Read", "Read slock knowledge base entries.")
        )
    ),
    MembersAgentScopeGroup(
        resource = "action",
        section = "cli",
        label = "Actions",
        description = "Prepare action cards for the user to commit.",
        scopes = listOf(
            MembersAgentScopeRow("action:prepare", "Prepare", "Prepare an action card; the human commits the action.")
        )
    )
)

@Composable
private fun MembersAgentScopeGroup.localizedLabel(): String =
    when (resource) {
        "inbox" -> stringResource(MR.strings.member_permission_inbox)
        "server" -> stringResource(MR.strings.member_permission_server)
        "channel" -> stringResource(MR.strings.member_permission_channels)
        "thread" -> stringResource(MR.strings.member_permission_threads)
        "message" -> stringResource(MR.strings.member_permission_messages)
        "attachment" -> stringResource(MR.strings.member_permission_attachments)
        "task" -> stringResource(MR.strings.member_permission_tasks)
        "knowledge" -> stringResource(MR.strings.member_permission_knowledge)
        "action" -> stringResource(MR.strings.member_permission_actions)
        else -> label
    }

@Composable
private fun MembersAgentScopeGroup.localizedDescription(): String =
    when (resource) {
        "inbox" -> stringResource(MR.strings.member_permission_inbox_hint)
        "server" -> stringResource(MR.strings.member_permission_server_hint)
        "channel" -> stringResource(MR.strings.member_permission_channels_hint)
        "thread" -> stringResource(MR.strings.member_permission_threads_hint)
        "message" -> stringResource(MR.strings.member_permission_messages_hint)
        "attachment" -> stringResource(MR.strings.member_permission_attachments_hint)
        "task" -> stringResource(MR.strings.member_permission_tasks_hint)
        "knowledge" -> stringResource(MR.strings.member_permission_knowledge_hint)
        "action" -> stringResource(MR.strings.member_permission_actions_hint)
        else -> description
    }

@Composable
private fun MembersAgentScopeRow.localizedVerbLabel(): String =
    when (scope) {
        "inbox:receive" -> stringResource(MR.strings.member_permission_receive)
        "channel:join" -> stringResource(MR.strings.member_permission_join)
        "channel:leave" -> stringResource(MR.strings.member_permission_leave)
        "thread:unfollow" -> stringResource(MR.strings.member_permission_unfollow)
        "message:send" -> stringResource(MR.strings.member_permission_send)
        "attachment:upload" -> stringResource(MR.strings.member_permission_upload)
        "attachment:view" -> stringResource(MR.strings.member_permission_view)
        "task:write" -> stringResource(MR.strings.member_permission_write)
        "action:prepare" -> stringResource(MR.strings.member_permission_prepare)
        else -> stringResource(MR.strings.member_permission_read)
    }

@Composable
private fun MembersAgentScopeRow.localizedDescription(): String =
    when (scope) {
        "inbox:receive" -> stringResource(MR.strings.member_permission_receive_hint)
        "server:read" -> stringResource(MR.strings.member_permission_server_read_hint)
        "channel:read" -> stringResource(MR.strings.member_permission_channel_read_hint)
        "channel:join" -> stringResource(MR.strings.member_permission_channel_join_hint)
        "channel:leave" -> stringResource(MR.strings.member_permission_channel_leave_hint)
        "thread:unfollow" -> stringResource(MR.strings.member_permission_thread_unfollow_hint)
        "message:read" -> stringResource(MR.strings.member_permission_message_read_hint)
        "message:send" -> stringResource(MR.strings.member_permission_message_send_hint)
        "attachment:upload" -> stringResource(MR.strings.member_permission_attachment_upload_hint)
        "attachment:view" -> stringResource(MR.strings.member_permission_attachment_view_hint)
        "task:read" -> stringResource(MR.strings.member_permission_task_read_hint)
        "task:write" -> stringResource(MR.strings.member_permission_task_write_hint)
        "knowledge:read" -> stringResource(MR.strings.member_permission_knowledge_read_hint)
        "action:prepare" -> stringResource(MR.strings.member_permission_action_prepare_hint)
        else -> description
    }

@Composable
private fun MembersAgentPermissionsTab(
    member: MemberProfile,
    detail: AgentDetailSnapshot?,
    canManage: Boolean,
    agentsApiClient: build.raft.app.data.SlockAgentsApiClient?,
    initialScopeSet: AgentScopeSet? = null,
    onUnavailableAction: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val errorStrings = localizedErrorPresentationStrings()
    // #89 React parity: mirror `AgentScopesPanel.tsx` state machine.
    // Local state only (per owner 06-25 04:08 — `SharedAgentDetailStore`
    // integration deferred to AD4 #56 lane).
    var serverSet by remember(member.id, initialScopeSet) { mutableStateOf<AgentScopeSet?>(initialScopeSet) }
    var draft by remember(member.id, initialScopeSet) { mutableStateOf(initialScopeSet?.granted?.toSet()) }
    var loading by remember(member.id, initialScopeSet) { mutableStateOf(initialScopeSet == null) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val agentApiUnavailable = stringResource(MR.strings.member_error_agent_api_unavailable)
    val loadPermissionsFailed = stringResource(MR.strings.member_error_load_permissions)
    val noPermissionsData = stringResource(MR.strings.member_state_no_permissions_data)
    val viewPermissionsForbidden = stringResource(MR.strings.member_error_view_permissions)
    val savePermissionsFailed = stringResource(MR.strings.member_error_save_permissions)
    val emptyResponse = stringResource(MR.strings.member_error_empty_response)
    val savePermissionsForbidden = stringResource(MR.strings.member_error_save_permissions_forbidden)
    val resetPermissionsFailed = stringResource(MR.strings.member_error_reset_permissions)
    val resetPermissionsForbidden = stringResource(MR.strings.member_error_reset_permissions_forbidden)

    suspend fun loadScopes() {
        if (agentsApiClient == null) {
            error = agentApiUnavailable
            loading = false
            return
        }
        when (val result = agentsApiClient.agentScopes(member.id)) {
            is DataResult.Content -> {
                serverSet = result.data
                draft = result.data.granted.toSet()
                error = null
            }
            is DataResult.Error -> {
                error = "$loadPermissionsFailed: ${result.error.userFacingMessage(errorStrings)}"
            }
            is DataResult.Empty -> error = noPermissionsData
            is DataResult.NoPermission -> error = viewPermissionsForbidden
        }
        loading = false
    }

    LaunchedEffect(member.id) {
        if (initialScopeSet == null) {
            loadScopes()
        }
    }

    fun toggle(scope: String) {
        val current = draft ?: return
        val next = if (scope in current) current - scope else current + scope
        draft = next
    }

    suspend fun save() {
        val current = draft ?: return
        val currentServer = serverSet ?: return
        if (agentsApiClient == null) return
        saving = true
        error = null
        when (val result = agentsApiClient.updateAgentScopes(member.id, current.toList())) {
            is DataResult.Content -> {
                serverSet = result.data
                draft = result.data.granted.toSet()
            }
            is DataResult.Error -> error = "$savePermissionsFailed: ${result.error.userFacingMessage(errorStrings)}"
            is DataResult.Empty -> error = emptyResponse
            is DataResult.NoPermission -> error = savePermissionsForbidden
        }
        saving = false
    }

    suspend fun resetToDefault() {
        if (agentsApiClient == null) return
        saving = true
        error = null
        when (val result = agentsApiClient.resetAgentScopes(member.id)) {
            is DataResult.Content -> {
                serverSet = result.data
                draft = result.data.granted.toSet()
            }
            is DataResult.Error -> error = "$resetPermissionsFailed: ${result.error.userFacingMessage(errorStrings)}"
            is DataResult.Empty -> error = emptyResponse
            is DataResult.NoPermission -> error = resetPermissionsForbidden
        }
        saving = false
    }

    fun discard() {
        val currentServer = serverSet ?: return
        draft = currentServer.granted.toSet()
    }

    val currentServer = serverSet
    val currentDraft = draft
    val isDirty = currentServer != null && currentDraft != null && !currentDraft.toSet().equals(currentServer.granted.toSet())

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)) {
        if (error != null && currentServer == null) {
            // Initial-load error: brutal warning card + Retry button. Return
            // early after rendering so the rest of the panel is not drawn
            // over the error.
            ErrorBanner(
                text = error ?: "",
                action = stringResource(MR.strings.common_retry),
                onAction = { scope.launch { loadScopes() } }
            )
            return
        }
        if (loading || currentServer == null || currentDraft == null) {
            // Loading state: text "Loading permissions…"
            Text(
                text = stringResource(MR.strings.member_state_loading_permissions),
                style = SlockType.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp),
                color = SlockColor.TextMutedGray,
                modifier = Modifier.padding(SlockSpacing.Sm)
            )
            return
        }

        if (error != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(SlockStroke.Default, SlockColor.BorderBlack)
                    .background(SlockColor.SurfaceCream)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SlockLucideIcon(
                    SlockLucideIconName.TriangleAlert,
                    modifier = Modifier.size(16.dp),
                    color = "#FFD32F2F"
                )
                Text(
                    text = error ?: "",
                    style = SlockType.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp),
                    color = SlockColor.TextBlack,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Mode banner (custom / default)
        val isCustom = currentServer.mode == "custom"
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(SlockStroke.Default, SlockColor.BorderBlack)
                .background(SlockColor.SurfaceWhite)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = if (isCustom) stringResource(MR.strings.member_status_permissions_custom) else stringResource(MR.strings.member_status_permissions_default),
                    style = SlockType.Body.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 13.sp, lineHeight = 17.sp),
                    color = SlockColor.TextBlack
                )
                Text(
                    text = if (isCustom) stringResource(MR.strings.member_hint_permissions_custom) else stringResource(MR.strings.member_hint_permissions_default),
                    style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 15.sp),
                    color = SlockColor.TextMutedGray
                )
            }
            if (isCustom) {
                SlockBrutalActionButton(
                    label = stringResource(MR.strings.member_action_use_default),
                    onClick = {
                        scope.launch { resetToDefault() }
                    },
                    enabled = !saving,
                    minWidth = 88.dp,
                    minHeight = 32.dp
                )
            }
        }

        // Notifications section
        val notificationGroups = membersAgentScopeGroups.filter { it.section == "notifications" }
        val cliGroups = membersAgentScopeGroups.filter { it.section == "cli" }

        PermissionsSection(title = stringResource(MR.strings.member_label_notifications_inbox), description = stringResource(MR.strings.member_hint_permissions_notifications), groups = notificationGroups, draft = currentDraft, canManage = canManage, onToggle = ::toggle)
        PermissionsSection(title = stringResource(MR.strings.member_role_raft_cli), description = stringResource(MR.strings.member_hint_permissions_cli), groups = cliGroups, draft = currentDraft, canManage = canManage, onToggle = ::toggle)

        // Action bar
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SlockBrutalActionButton(
                label = if (saving) stringResource(MR.strings.common_loading) else stringResource(MR.strings.member_action_save_permissions),
                onClick = {
                    scope.launch { save() }
                },
                enabled = !saving && isDirty,
                backgroundColor = SlockColor.AccentPink,
                minWidth = 132.dp
            )
            SlockBrutalActionButton(
                label = stringResource(MR.strings.member_action_discard),
                onClick = ::discard,
                enabled = !saving && isDirty,
                minWidth = 88.dp
            )
            Text(
                text = stringResource(MR.strings.member_label_revision, currentServer.revision.toString()),
                style = SlockType.Caption.copy(fontSize = 10.sp, lineHeight = 12.sp),
                color = SlockColor.TextMutedGray.copy(alpha = 0.4f),
                modifier = Modifier.padding(start = 4.dp)
            )
        }
    }
}

@Composable
private fun PermissionsSection(
    title: String,
    description: String,
    groups: List<MembersAgentScopeGroup>,
    draft: Set<String>,
    canManage: Boolean,
    onToggle: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(modifier = Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = SlockType.Body.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 13.sp, lineHeight = 17.sp),
                color = SlockColor.TextBlack
            )
            Text(
                text = description,
                style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 15.sp),
                color = SlockColor.TextMutedGray
            )
        }
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            groups.forEach { group ->
                PermissionsGroupBox(group = group, draft = draft, canManage = canManage, onToggle = onToggle)
            }
        }
    }
}

@Composable
private fun PermissionsGroupBox(
    group: MembersAgentScopeGroup,
    draft: Set<String>,
    canManage: Boolean,
    onToggle: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(SlockStroke.Default, SlockColor.BorderBlack)
            .background(SlockColor.SurfaceWhite)
    ) {
        Column(modifier = Modifier.fillMaxWidth().border(width = 0.dp, color = Color.Transparent).padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                text = group.localizedLabel(),
                style = SlockType.Body.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 13.sp, lineHeight = 16.sp),
                color = SlockColor.TextBlack
            )
            Text(
                text = group.localizedDescription(),
                style = SlockType.Caption.copy(fontSize = 10.sp, lineHeight = 14.sp),
                color = SlockColor.TextMutedGray.copy(alpha = 0.5f)
            )
        }
        // Hairline divider between header and rows.
        Box(modifier = Modifier.fillMaxWidth().height(SlockStroke.Thin).background(SlockColor.BorderBlack.copy(alpha = 0.1f)))
        group.scopes.forEachIndexed { index, row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(width = 0.dp, color = Color.Transparent)
                    .let {
                        if (index < group.scopes.lastIndex) {
                            it.border(width = 0.dp, color = Color.Transparent)
                        } else it
                    }
                    .clickable(enabled = canManage) { onToggle(row.scope) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SlockCheckbox(
                    checked = row.scope in draft,
                    onCheckedChange = { onToggle(row.scope) },
                    enabled = canManage,
                    size = 14.dp
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = row.localizedVerbLabel(),
                            style = SlockType.Body.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 12.sp, lineHeight = 16.sp),
                            color = SlockColor.TextBlack
                        )
                        Text(
                            text = row.scope,
                            style = SlockType.Caption.copy(fontSize = 9.sp, lineHeight = 12.sp),
                            color = SlockColor.TextMutedGray.copy(alpha = 0.5f)
                        )
                    }
                    Text(
                        text = row.localizedDescription(),
                        style = SlockType.Caption.copy(fontSize = 10.sp, lineHeight = 14.sp),
                        color = SlockColor.TextMutedGray.copy(alpha = 0.6f)
                    )
                }
            }
            if (index < group.scopes.lastIndex) {
                Box(modifier = Modifier.fillMaxWidth().height(SlockStroke.Thin).background(SlockColor.BorderBlack.copy(alpha = 0.1f)))
            }
        }
    }
}

@Composable
private fun MemberKindBadge(kind: MemberKind) {
    // Mirrors React `AgentDetailPanel.tsx` header agent/human tag style:
    // inline span with brutal pill (border-2 border-black + bg-[color] + px-2 py-0.5 +
    // text-xs font-bold). The header tag is not its own React component, so we don't
    // introduce a generic `SlockBrutalTag` wrapper either — we render the same shape
    // inline so KMP stays aligned with the React source pattern.
    Box(
        modifier = Modifier
            .background(kind.membersAvatarColor)
            .border(SlockStroke.Thin, SlockColor.BorderBlack)
            .padding(horizontal = SlockSpacing.Xs, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = kind.name.uppercase(),
            style = SlockType.Caption.copy(fontSize = 10.sp, lineHeight = 14.sp)
                .withSlockFontWeight(FontWeight.Bold),
            color = SlockColor.TextBlack,
            maxLines = 1,
            overflow = TextOverflow.Clip
        )
    }
}

@Composable
private fun MembersDetailActionButton(
    label: String,
    iconName: SlockLucideIconName,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    SlockBrutalActionButton(
        label = label,
        leadingIcon = iconName,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        backgroundColor = SlockColor.AccentPink
    )
}

@Composable
private fun MembersProfilePencilButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(18.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        SlockLucideIcon(
            SlockLucideIconName.Pencil,
            modifier = Modifier.size(14.dp),
            color = SlockColor.TextMutedGrayArgb
        )
    }
}

@Composable
private fun MembersEditableSectionTitleRow(
    title: String,
    showEdit: Boolean,
    onEditClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
    ) {
        MembersDetailSectionTitle(title)
        if (showEdit) {
            MembersProfilePencilButton(onClick = onEditClick)
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun MembersDetailSectionDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(SlockStroke.Thin)
            .background(SlockColor.BorderBlack.copy(alpha = 0.1f))
    )
}

@Composable
private fun MembersDetailSectionTitle(
    title: String,
    modifier: Modifier = Modifier
) {
    SlockSectionEyebrow(label = title, modifier = modifier)
}

@Composable
private fun MembersDetailSection(
    title: String,
    modifier: Modifier = Modifier,
    framed: Boolean = true,
    trailingContent: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)) {
        MembersDetailSectionDivider()
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
        ) {
            MembersDetailSectionTitle(title, modifier = Modifier.weight(1f))
            trailingContent?.invoke()
        }
        val contentModifier =
            if (framed) {
                Modifier
                    .fillMaxWidth()
                    .border(SlockStroke.Default, SlockColor.BorderBlack)
                    .background(SlockColor.SurfaceWhite)
                    .padding(SlockSpacing.Md)
            } else {
                Modifier.fillMaxWidth()
            }
        Column(
            modifier = contentModifier,
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
        ) {
            content()
        }
    }
}

@Composable
private fun MembersDetailField(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        Text(
            text = label.slockDisplayUppercase(),
            style = SlockType.Caption.copy(fontSize = 10.sp, lineHeight = 14.sp),
            color = SlockColor.TextMutedGray,
            maxLines = 1,
            modifier = Modifier.width(86.dp)
        )
        Text(
            text = value.ifBlank { "—" },
            style = SlockType.Body.withSlockFontWeight(FontWeight.Normal).copy(fontSize = 13.sp, lineHeight = 18.sp),
            color = SlockColor.TextBlack,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
internal fun MembersPageHeader(
    title: String,
    refreshing: Boolean = false,
    onBack: (() -> Unit)? = null
) {
    SlockMainTabPageHeader(
        surface = SlockMainTabHeaderSurface.Members,
        title = title,
        subtitle = null,
        refreshing = refreshing,
        leadingContent = if (onBack != null) {
            {
                SlockTopPageHeaderAction(icon = SlockLucideIconName.ArrowLeft, onClick = onBack)
            }
        } else {
            null
        }
    )
}

internal fun membersListMachineLabel(label: String): String = label.lowercase()

@Composable
private fun MembersListRow(
    member: MemberProfile,
    selected: Boolean = false,
    modifier: Modifier = Modifier,
    agentStatusResolver: SlockAgentStatusResolver = EmptySlockAgentStatusResolver,
    memberAvatarResolver: SlockMemberAvatarResolver = EmptySlockMemberAvatarResolver,
    onClick: () -> Unit = {}
) {
    val agentStatusDisplayState =
        if (member.kind == MemberKind.Agent) {
            rememberSlockAgentStatusIndicatorState(agentStatusResolver, member.id.value)
        } else {
            null
        }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = MembersListRowMinHeight)
            .background(if (selected) LocalSlockTheme.current.surfaceMuted else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = SlockSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MembersListContentGap)
    ) {
        MembersAvatar(
            member = member,
            showStatusOverlay = false,
            statusIndicatorState = agentStatusDisplayState,
            memberAvatarResolver = memberAvatarResolver
        )
        BoxWithConstraints(modifier = Modifier.weight(1f)) {
            val maxNameWidth = maxWidth * MembersListNameMaxWidthFraction
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
            ) {
                Text(
                    text = member.displayName,
                    style = MembersListNameTextStyle,
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = maxNameWidth)
                )
                Text(
                    text = member.description ?: member.handle,
                    style = MembersListDescriptionTextStyle,
                    color = SlockColor.TextMutedGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        if (member.kind == MemberKind.Agent) {
            // Same contract as the detail header: null is Unknown, not Offline, so the row
            // shows no dot rather than a grey one until a resolution exists (task #253/#256).
            agentStatusDisplayState?.let { state ->
                SlockStatusIndicator(
                    color = state.color,
                    size = MemberStatusDotSize,
                    pulsing = state.pulsing
                )
            }
        }
    }
}

@Composable
private fun MembersAvatar(
    member: MemberProfile,
    avatarSize: Dp = MembersListAvatarSize,
    statusSize: Dp = MemberStatusDotSize,
    statusDisplayState: MembersAgentStatusDisplayState = MembersUnknownAgentStatusDisplayState,
    statusIndicatorState: SlockAgentStatusIndicatorState? = null,
    memberAvatarResolver: SlockMemberAvatarResolver = EmptySlockMemberAvatarResolver,
    showStatusOverlay: Boolean = true,
    modifier: Modifier = Modifier
) {
    val resolvedAvatarWireValue = rememberSlockMemberAvatarWireValue(memberAvatarResolver, member.id.value)
    val fallbackAvatarWireValue = remember(member.avatar, member.avatarUrl, member.gravatarHash, member.kind, member.id) {
        member.avatarWireValueForAvatarSlot()
    }
    val avatarWireValue = resolvedAvatarWireValue ?: fallbackAvatarWireValue
    SlockAvatarWithStatus(
        modifier = modifier,
        statusColor =
            if (showStatusOverlay && member.kind == MemberKind.Agent) {
                statusIndicatorState?.color ?: statusDisplayState.color
            } else {
                null
            },
        avatarSize = avatarSize,
        statusSize = statusSize,
        statusPulsing = statusIndicatorState?.pulsing ?: statusDisplayState.pulsing
    ) {
        SlockAvatar(
            avatarWireValue = avatarWireValue,
            label = member.avatarLabel,
            avatarSize = avatarSize,
            cacheSubject = member.slockAvatarCacheSubject(),
            cacheServerId = member.serverId?.value
        )
    }
}

private val MemberKind.membersAvatarColor: Color
    get() = when (this) {
        MemberKind.Human -> SlockColor.AccentLavender
        MemberKind.Agent -> SlockColor.AccentPink
        MemberKind.System -> SlockColor.AccentCyan
        MemberKind.External -> SlockColor.AccentCyan
    }

internal data class MembersAgentStatusDisplayState(
    val status: AgentStatus?,
    val color: Color,
    val pulsing: Boolean,
    val external: Boolean
)

private val MembersUnknownAgentStatusDisplayState =
    MembersAgentStatusDisplayState(
        status = null,
        color = SlockColor.StatusNeutralGray,
        pulsing = false,
        external = false
    )

internal fun MemberProfile.membersAgentStatusDisplayState(
    summaryStatus: String? = null,
    summaryActivity: String? = null
): MembersAgentStatusDisplayState =
    toFixtureSlockAgentStatusIndicatorState(
        summaryStatus = summaryStatus,
        summaryActivity = summaryActivity
    ).toMembersAgentStatusDisplayState()

/**
 * Fixture fallback goes through the single canonical mapping so the external
 * short-circuit (task #1119) applies here exactly as it does to the live resolver.
 */
private fun MemberProfile.toFixtureSlockAgentStatusIndicatorState(
    summaryStatus: String? = null,
    summaryActivity: String? = null
): SlockAgentStatusIndicatorState =
    slockAgentStatusIndicatorState(
        status = membersResolvedAgentStatus(summaryStatus, summaryActivity),
        presence = presence,
        external = isExternal
    )

private fun AgentCreatedSummary.toFixtureSlockAgentStatusIndicatorState(): SlockAgentStatusIndicatorState =
    slockAgentStatusIndicatorState(
        status = normalizedAgentRuntimeStatus(activity = null, lifecycleStatus = status),
        presence = Presence.Offline,
        external = isExternal
    )

private fun AgentProfile.toFixtureSlockAgentStatusIndicatorState(): SlockAgentStatusIndicatorState =
    slockAgentStatusIndicatorState(
        status = agentStatus,
        presence = presence,
        statusLabel = statusLabel,
        external = isExternal
    )

private val MemberProfile.detailHandle: String
    get() = handle.takeIf { it.isNotBlank() }?.let { if (it.startsWith("@")) it else "@$it" } ?: id.value

private val MemberProfile.detailRoleLabel: String
    get() =
        roleDescription?.takeIf { it.isNotBlank() }
            ?: when (kind) {
                MemberKind.Agent -> "Agent"
                MemberKind.Human -> "Human member"
                MemberKind.System -> "System"
                MemberKind.External -> "External"
            }

private fun MemberProfile.membersHumanRoleChipColor(): Color =
    when (roleDescription?.trim()?.lowercase()) {
        "owner" -> SlockColor.AccentOrange
        "admin" -> SlockColor.AccentPink
        "member" -> SlockColor.AccentLavender
        else -> if (membershipStatus == "removed") SlockColor.EmptyStateGray else SlockColor.AccentLavender
    }

private fun MemberProfile.membersResolvedAgentStatus(
    summaryStatus: String?,
    summaryActivity: String?
): AgentStatus? =
    normalizedAgentRuntimeStatus(
        activity = summaryActivity,
        lifecycleStatus = summaryStatus
    )
        ?: agentStatus

internal fun membersAgentStatusLabel(
    status: AgentStatus?,
    summaryActivity: String?,
    fallback: String
): String =
    status?.label
        ?: summaryActivity?.takeIf { it.isNotBlank() }?.toMembersDisplayLabel()
        ?: fallback

/**
 * Status label that can never disagree with the live status dot (#198/#73).
 *
 * When a live indicator state exists, the label derives from the same
 * resolved status the dot renders; the richer summary activity/detail text is
 * shown only while the summary agrees with the live status — a stale
 * AgentDetailSummary (refreshed on navigation) must not caption a live dot
 * with an outdated state (e.g. green Online dot labelled "Working").
 */
internal fun membersLiveAgentStatusLabel(
    indicatorState: SlockAgentStatusIndicatorState?,
    summaryStatus: String?,
    summaryActivity: String?,
    summaryActivityDetail: String?,
    fallback: String
): String {
    val liveStatus = indicatorState?.status
    if (indicatorState != null && liveStatus == null) {
        return indicatorState.label
    }
    if (liveStatus == null) {
        return summaryActivityDetail?.takeIf { it.isNotBlank() }
            ?: summaryActivity?.takeIf { it.isNotBlank() }?.toMembersDisplayLabel()
            ?: indicatorState?.label
            ?: fallback
    }
    val summaryResolved =
        normalizedAgentRuntimeStatus(activity = summaryActivity, lifecycleStatus = summaryStatus)
    if (summaryResolved == liveStatus) {
        return summaryActivityDetail?.takeIf { it.isNotBlank() }
            ?: summaryActivity?.takeIf { it.isNotBlank() }?.toMembersDisplayLabel()
            ?: indicatorState.label
    }
    return indicatorState.label
}

private fun MemberProfile.membersAgentRuntimeErrorText(
    detail: AgentDetailSnapshot?,
    statusIndicatorState: SlockAgentStatusIndicatorState?
): String? {
    if (kind != MemberKind.Agent) return null
    val summary = detail?.summary
    val status = statusIndicatorState?.status
    return if (status == AgentStatus.Error) {
        membersAgentActivityErrorText(summary?.activityDetail)
    } else {
        null
    }
}

internal fun membersAgentActivityErrorText(detail: String?): String {
    val displayDetail = membersAgentActivityErrorDisplayDetail(detail)
    return if (displayDetail.isBlank()) "Error" else "Error: $displayDetail"
}

internal fun membersAgentActivityErrorDisplayDetail(detail: String?): String {
    val trimmed = detail?.trim().orEmpty()
    if (trimmed.isBlank()) return ""
    val parsed =
        if (trimmed.firstOrNull() in setOf('{', '[', '"')) {
            runCatching { MembersAgentActivityErrorJson.parseToJsonElement(trimmed) }.getOrNull()
        } else {
            null
        }
    return parsed?.membersAgentActivityErrorMessage()?.takeIf { it.isNotBlank() } ?: trimmed
}

private val MembersAgentActivityErrorJson = Json { ignoreUnknownKeys = true }

private fun JsonElement.membersAgentActivityErrorMessage(): String? =
    when (this) {
        is JsonObject -> membersAgentActivityErrorMessage()
        is JsonArray -> firstNotNullOfOrNull { it.membersAgentActivityErrorMessage() }
        is JsonPrimitive -> contentOrNull?.trim()?.takeIf { it.isNotBlank() }
    }

private fun JsonObject.membersAgentActivityErrorMessage(): String? {
    listOf("message", "error_description").forEach { key ->
        this[key].membersAgentActivityStringOrNull()?.let { return it }
    }
    val error = this["error"]
    error.membersAgentActivityStringOrNull()?.let { return it }
    error?.membersAgentActivityErrorMessage()?.let { return it }
    (this["errors"] as? JsonArray)
        ?.firstNotNullOfOrNull { it.membersAgentActivityErrorMessage() }
        ?.let { return it }
    return null
}

private fun JsonElement?.membersAgentActivityStringOrNull(): String? =
    (this as? JsonPrimitive)
        ?.contentOrNull
        ?.trim()
        ?.takeIf { it.isNotBlank() }

internal fun buildMembersAgentDiagnostics(
    member: MemberProfile,
    detail: AgentDetailSnapshot?,
    statusIndicatorState: SlockAgentStatusIndicatorState?
): String {
    val summary = detail?.summary
    return listOf(
        "Raft agent diagnostics",
        "agentId=${member.id.value}",
        "displayName=${member.displayName}",
        "handle=${member.detailHandle}",
        "status=${statusIndicatorState?.label ?: MembersUnknownAgentStatusLabel}",
        "activity=${summary?.activity.orDash()}",
        "activityDetail=${summary?.activityDetail.orDash()}",
        "runtime=${summary?.runtime.orDash()}",
        "model=${summary?.model.orDash()}",
        "machineId=${summary?.machineId.orBadgeFallback(member.membersComputerId)}",
        "machineName=${summary?.machineName.orBadgeFallback(member.membersComputerName)}",
        "daemon=${summary?.daemonVersion.orDash()}",
        "deleted=${summary?.deletedAt?.isNotBlank() == true}"
    ).joinToString("\n")
}

internal fun AgentCreatorSummary.toStrictMemberProfile(
    diagnostics: (String) -> Unit = strictProjectionDiagnosticsLogger,
): MemberProfile? {
    if (id.isBlank() || name.isBlank()) return null
    val kind =
        type.strictMemberKindOrNull(
            source = "agent_creator",
            diagnostics = diagnostics,
            contract = MemberKindWireContract.AgentCreator,
        ) ?: return null
    val display = displayName?.takeIf { it.isNotBlank() } ?: name
    return MemberProfile(
        id = MemberId(id),
        displayName = display,
        handle = name,
        kind = kind,
        avatarUrl = avatarUrl,
        avatar = AvatarReference.fromWireValue(avatarUrl),
        avatarLabel = display.take(1).uppercase()
    )
}

private fun AgentProfile.toStaticMembersProfile(): MemberProfile =
    toMemberProfile().copy(
        presence = Presence.Offline,
        agentStatus = null
    )

private fun AgentCreatedSummary.toStaticMembersProfile(): MemberProfile {
    val display = displayName?.takeIf { it.isNotBlank() } ?: name.ifBlank { id }
    val avatarWireValue = avatarUrl?.takeIf { it.isNotBlank() }
    return MemberProfile(
        id = MemberId(id),
        displayName = display,
        handle = name.takeIf { it.isNotBlank() } ?: id,
        kind = MemberKind.Agent,
        avatarUrl = avatarWireValue,
        avatar = AvatarReference.fromWireValue(avatarWireValue),
        avatarLabel = display.take(1).uppercase(),
        roleDescription = runtime,
        isExternal = isExternal
    )
}

private fun String?.toMembersRuntimeModeChipLabel(runtimeId: String?): String? {
    if (runtimeId == null || !supportsRuntimeFastMode(runtimeId)) return null
    return when (this?.trim()?.lowercase()) {
        "fast" -> "Fast"
        "default" -> "Default"
        else -> null
    }
}

private val MemberProfile.membersComputerName: String?
    get() =
        permissionBadges.firstNotNullOfOrNull { badge ->
            badge.removePrefix("computer:")
                .takeIf { badge.startsWith("computer:") && it.isNotBlank() }
        }

private val MemberProfile.membersComputerId: String?
    get() =
        permissionBadges.firstNotNullOfOrNull { badge ->
            badge.removePrefix("computer-id:")
                .takeIf { badge.startsWith("computer-id:") && it.isNotBlank() }
        }

private fun String?.orDash(): String = this?.takeIf { it.isNotBlank() } ?: "—"

private fun String?.orBadgeFallback(fallback: String?): String =
    this?.takeIf { it.isNotBlank() } ?: fallback?.takeIf { it.isNotBlank() } ?: "—"

private fun String.toMembersDisplayLabel(): String =
    replace("_", " ")
        .replace("-", " ")
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

internal fun AgentActivityEntry.membersActivitySecondaryText(): String? =
    when (type) {
        // Web renders a tool's display label plus only its real input. The
        // "Tool" detail is metadata for diagnostics, not visible secondary
        // content; falling back to it produced Check messages/check_messages.
        "tool_start" -> toolInput?.takeIf { it.isNotBlank() }
            ?: body?.takeIf { it.isNotBlank() }
        else -> body?.takeIf { it.isNotBlank() }
            ?: statusDetail?.takeIf { it.isNotBlank() }
            ?: details.firstOrNull()?.let { (_, value) -> value.takeIf { it.isNotBlank() } }
    }

internal fun AgentActivityEntry.membersActivitySubagentBadgeLabel(): String? {
    if (!isSubagent || (type != "tool_start" && type != "status")) return null
    return subagentType?.takeIf { it.isNotBlank() }?.let { "Subagent: $it" } ?: "Subagent"
}

private fun AgentActivityEntry.activityPrimaryDetail(): String? =
    membersActivitySecondaryText()

private fun AgentActivityEntry.activityTimelineKey(): String =
    id.ifBlank { "${type}:${timestamp.orEmpty()}:${body.orEmpty()}:${toolName.orEmpty()}" }

private data class MembersWorkspaceFlatNode(
    val node: SlockWorkspaceNode,
    val depth: Int
)

private fun membersWorkspaceRows(
    nodes: List<SlockWorkspaceNode>,
    expandedDirs: Set<String>,
    loadedDirs: Map<String, List<SlockWorkspaceNode>>
): List<MembersWorkspaceFlatNode> =
    buildList {
        fun visit(current: List<SlockWorkspaceNode>, depth: Int) {
            current.forEach { node ->
                add(MembersWorkspaceFlatNode(node, depth))
                if (node.kind == SlockWorkspaceFileKind.Folder && node.path in expandedDirs) {
                    visit(loadedDirs[node.path] ?: node.children, depth + 1)
                }
            }
        }
        visit(nodes, 0)
    }

private fun SlockWorkspaceNode.membersWorkspaceIconName(expanded: Boolean): String =
    when (kind) {
        SlockWorkspaceFileKind.Folder -> if (expanded) "folder-open" else "folder"
        SlockWorkspaceFileKind.Markdown,
        SlockWorkspaceFileKind.Text -> "file-text"
        SlockWorkspaceFileKind.Image -> "image"
        SlockWorkspaceFileKind.Unsupported -> "file"
    }

private fun Long?.membersWorkspaceSizeLabel(): String? {
    val bytes = this ?: return null
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "${bytes / (1024 * 1024)} MB"
    }
}

private fun String?.membersActivitySortKey(): Long =
    parseSlockTimestampEpochMillisOrNull(this) ?: Long.MIN_VALUE

private fun KuiklyMembersPayload.toSlockTimeContext(): SlockTimeContext =
    SlockTimeContext(
        localOffsetMinutes = localOffsetMinutes,
        todayEpochDay = todayEpochDay,
        nowEpochMillis = nowEpochMillis,
        timeFormat = timeFormat
    )

private fun KuiklyMemberDetailPayload.toSlockTimeContext(): SlockTimeContext =
    SlockTimeContext(
        localOffsetMinutes = localOffsetMinutes,
        todayEpochDay = todayEpochDay,
        nowEpochMillis = nowEpochMillis,
        timeFormat = timeFormat
    )

internal fun AgentDetailSnapshot.toStaticMembersProfile(server: ServerSummary): MemberProfile {
    val summary = summary
    val handle = summary?.name?.takeIf { it.isNotBlank() } ?: agentId.value
    val label = summary?.displayName?.takeIf { it.isNotBlank() } ?: handle
    val avatarUrl = summary?.avatarUrl?.takeIf { it.isNotBlank() }
    return MemberProfile(
        id = agentId,
        displayName = label,
        handle = handle,
        kind = MemberKind.Agent,
        description = summary?.description?.takeIf { it.isNotBlank() },
        roleDescription = summary?.runtime?.takeIf { it.isNotBlank() },
        serverRole = summary?.serverRole?.takeIf { it.isNotBlank() },
        avatarUrl = avatarUrl,
        avatar = AvatarReference.fromWireValue(avatarUrl),
        avatarLabel = label.take(1).uppercase(),
        presence = Presence.Offline,
        agentStatus = null,
        // Detail summaries carry the runtime sentinel only (no projected boolean).
        isExternal = isExternalAgentRuntime(summary?.runtime),
        permissionBadges = listOfNotNull(
            summary?.runtime?.takeIf { it.isNotBlank() },
            summary?.machineName?.takeIf { it.isNotBlank() }?.let { "computer:$it" },
            summary?.machineId?.takeIf { it.isNotBlank() }?.let { "computer-id:$it" }
        ),
        serverId = server.id,
        serverName = server.name.takeIf { it.isNotBlank() },
        serverSlug = server.slug?.takeIf { it.isNotBlank() }
    )
}

internal fun AgentDetailSnapshot.withMemberProfileProjection(member: MemberProfile): AgentDetailSnapshot {
    val fallbackName = member.handle.ifBlank { member.displayName.ifBlank { member.id.value } }
    val nextSummary =
        (summary ?: AgentDetailSummary(id = member.id.value, name = fallbackName)).copy(
            name = fallbackName,
            displayName = member.displayName.ifBlank { fallbackName },
            description = member.description?.takeIf { it.isNotBlank() },
            runtime = member.roleDescription ?: summary?.runtime,
            avatarUrl = preferredAvatarWireValue(member.avatar, member.avatarUrl) ?: summary?.avatarUrl
        )
    return copy(summary = nextSummary)
}

private fun String.toMemberDetailDiagnosticsLine(): String =
    lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .joinToString(" | ")
        .take(1200)

private fun String?.membersActivityDisplayTime(timeContext: SlockTimeContext): String =
    formatSlockTimestampClockWithSeconds(this, timeContext)
        .takeIf { it.isNotBlank() }
        ?: "No time"

private fun String?.membersReminderRelativeTime(timeContext: SlockTimeContext): String {
    val trimmed = this?.trim().orEmpty()
    if (trimmed.isEmpty()) return ""
    val fireAtMillis =
        parseSlockTimestampEpochMillisOrNull(trimmed, timeContext.localOffsetMinutes) ?: return trimmed
    val nowMillis = timeContext.nowEpochMillis ?: return trimmed.membersReminderShortDateTime(timeContext)
    val diffMillis = fireAtMillis - nowMillis
    val absMillis = abs(diffMillis)
    val unitMillis =
        when {
            absMillis < MembersReminderMillisPerHour -> MembersReminderMillisPerMinute
            absMillis < MembersReminderMillisPerDay -> MembersReminderMillisPerHour
            else -> MembersReminderMillisPerDay
        }
    val unit =
        when (unitMillis) {
            MembersReminderMillisPerMinute -> "minute"
            MembersReminderMillisPerHour -> "hour"
            else -> "day"
        }
    val amount = (diffMillis.toDouble() / unitMillis.toDouble()).roundToLong()
    val absoluteAmount = abs(amount)
    if (absoluteAmount == 0L) return "now"
    val label = "$absoluteAmount $unit${if (absoluteAmount == 1L) "" else "s"}"
    return if (amount > 0L) "in $label" else "$label ago"
}

private fun String?.membersReminderShortDateTime(timeContext: SlockTimeContext): String {
    val trimmed = this?.trim().orEmpty()
    if (trimmed.isEmpty()) return ""
    val epochMillis =
        parseSlockTimestampEpochMillisOrNull(trimmed, timeContext.localOffsetMinutes) ?: return trimmed
    val localMillis = epochMillis + timeContext.localOffsetMinutes.toLong() * MembersReminderMillisPerMinute
    val epochDay = membersReminderFloorDiv(localMillis, MembersReminderMillisPerDay)
    val millisOfDay = membersReminderFloorMod(localMillis, MembersReminderMillisPerDay)
    val date = membersReminderCivilDateFromEpochDay(epochDay)
    val hour = (millisOfDay / MembersReminderMillisPerHour).toInt()
    val minute = ((millisOfDay % MembersReminderMillisPerHour) / MembersReminderMillisPerMinute).toInt()
    return "${MembersReminderMonthNames[date.month - 1]} ${date.day}, ${membersReminderClock(hour, minute, timeContext.timeFormat)}"
}

private fun String.membersAgentCreatedDate(timeContext: SlockTimeContext): String {
    val trimmed = trim()
    if (trimmed.isEmpty()) return ""
    val epochMillis =
        parseSlockTimestampEpochMillisOrNull(trimmed, timeContext.localOffsetMinutes) ?: return trimmed
    val localMillis = epochMillis + timeContext.localOffsetMinutes.toLong() * MembersReminderMillisPerMinute
    val epochDay = membersReminderFloorDiv(localMillis, MembersReminderMillisPerDay)
    val date = membersReminderCivilDateFromEpochDay(epochDay)
    return "${MembersReminderMonthNames[date.month - 1]} ${date.day}, ${date.year}"
}

private fun membersReminderClock(hour: Int, minute: Int, timeFormat: String): String {
    if (timeFormat != SlockTimeFormat12Hour) {
        return "${hour.membersReminderTwoDigits()}:${minute.membersReminderTwoDigits()}"
    }
    val displayHour = (hour % 12).let { if (it == 0) 12 else it }
    return "${displayHour.membersReminderTwoDigits()}:${minute.membersReminderTwoDigits()} ${if (hour < 12) "AM" else "PM"}"
}

private data class MembersReminderCivilDate(
    val year: Int,
    val month: Int,
    val day: Int
)

private fun membersReminderCivilDateFromEpochDay(epochDay: Long): MembersReminderCivilDate {
    val zeroDay = epochDay + 719_468L
    val era = membersReminderFloorDiv(zeroDay, 146_097L)
    val dayOfEra = zeroDay - era * 146_097L
    val yearOfEra =
        (dayOfEra - dayOfEra / 1_460L + dayOfEra / 36_524L - dayOfEra / 146_096L) / 365L
    val year = yearOfEra + era * 400L
    val dayOfYear = dayOfEra - (365L * yearOfEra + yearOfEra / 4L - yearOfEra / 100L)
    val monthPrime = (5L * dayOfYear + 2L) / 153L
    val day = dayOfYear - (153L * monthPrime + 2L) / 5L + 1L
    val month = monthPrime + if (monthPrime < 10L) 3L else -9L
    val adjustedYear = year + if (month <= 2L) 1L else 0L
    return MembersReminderCivilDate(adjustedYear.toInt(), month.toInt(), day.toInt())
}

private fun membersReminderFloorDiv(value: Long, divisor: Long): Long {
    val quotient = value / divisor
    val remainder = value % divisor
    return if (remainder != 0L && (value xor divisor) < 0L) quotient - 1 else quotient
}

private fun membersReminderFloorMod(value: Long, divisor: Long): Long =
    value - membersReminderFloorDiv(value, divisor) * divisor

private fun Int.membersReminderTwoDigits(): String =
    if (this < 10) "0$this" else toString()

private val MembersReminderMonthNames =
    listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

private const val MembersReminderMillisPerMinute = 60_000L
private const val MembersReminderMillisPerHour = 3_600_000L
private const val MembersReminderMillisPerDay = 86_400_000L

private fun String.membersActivityAccentColor(): Color =
    when (membersActivityStatusKey()) {
        "error", "failed", "failure", "subagent_failed" -> SlockColor.AccentOrange
        "tool_start", "tool", "thinking", "working", "busy", "subagent_spawned", "subagent_started" ->
            SlockColor.BrandYellow
        "running", "active", "online", "compaction_finished", "subagent_completed" -> SlockColor.AccentLime
        "text", "message", "output" -> SlockColor.AccentCyan
        "offline", "disconnected", "subagent_suspended" -> SlockColor.StatusNeutralGray
        "system", "slock_action", "compaction_started" -> SlockColor.AccentOrange
        else -> SlockColor.BrandYellow
    }

private fun String.membersActivityPulsing(): Boolean =
    when (membersActivityStatusKey()) {
        "tool_start",
        "tool",
        "thinking",
        "working",
        "running",
        "busy",
        "subagent_spawned",
        "subagent_started" -> true
        else -> false
    }

private fun String.membersActivityStatusKey(): String =
    trim().lowercase().replace('-', '_').replace('.', '_')

private fun String.normalizedMembersToolName(): String =
    trim()
        .substringAfterLast("__")
        .substringAfterLast("/")
        .substringAfterLast(".")
        .ifBlank { trim() }

private fun LazyListState.isMembersActivityAtBottom(lastIndex: Int): Boolean {
    if (lastIndex <= 0) return true
    if (!canScrollForward) return true
    return layoutInfo.visibleItemsInfo.any { item ->
        item.index == lastIndex && item.offset + item.size <= layoutInfo.viewportEndOffset + 4
    }
}

internal fun membersAgentActivityInitialFirstVisibleIndex(itemCount: Int): Int =
    (itemCount - 1).coerceAtLeast(0)

internal fun membersAgentActivityNextBottomAnchor(
    current: Boolean,
    isScrollInProgress: Boolean,
    isAtBottom: Boolean
): Boolean =
    when {
        isAtBottom -> true
        isScrollInProgress -> false
        else -> current
    }

internal data class MembersAgentGroup(
    /**
     * Task #409: the persisted-collapse key — the machine's `computer-id:`
     * badge when present (stable across renames, same reason web keys by
     * `machineId`), otherwise the display name, otherwise the shared
     * [build.raft.app.data.MembersAgentMachineGroupNoMachineKey] sentinel.
     */
    val key: String,
    val label: String,
    val members: List<MemberProfile>
)

/**
 * #70: Rotating Loader spinner used by the workspace file tree as a chevron
 * substitute while a directory's children are being fetched
 * (`loadingDirs.contains(node.path)`). Mirrors the React
 * `AgentWorkspace.tsx` `<Spinner size="xs" />` pattern, but the KMP form
 * drops the React "Loading..." text per @artin's 06-23 20:51 directive
 * ("不要用文字做到最后，就把那个文件夹的 icon 改成 loading spinner 就行").
 *
 * 900ms rotation cycle matches the visual rhythm of the React inline
 * spinner without being so fast it looks nervous on the cheap-ohm
 * emulator display.
 */
@Composable
private fun ErrorBanner(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(SlockStroke.Default, SlockColor.BorderBlack)
            .background(SlockColor.SurfaceCream)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SlockLucideIcon(
            SlockLucideIconName.TriangleAlert,
            modifier = Modifier.size(16.dp),
            color = "#FFD32F2F"
        )
        Text(
            text = text,
            style = SlockType.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp),
            color = SlockColor.TextBlack,
            modifier = Modifier.weight(1f)
        )
        if (action != null && onAction != null) {
            SlockBrutalActionButton(
                label = action,
                onClick = onAction,
                minWidth = 72.dp,
                minHeight = 32.dp
            )
        }
    }
}

@Composable
private fun SlockSpinnerIcon(
    size: Dp = 14.dp,
    color: String = SlockColor.TextBlackArgb
) {
    SlockLucideIcon(
        SlockLucideIconName.LoaderCircle,
        modifier = Modifier.size(size),
        color = color,
        spinning = true
    )
}

/**
 * Groups agents by computer. The list row of an agent carries its `machineId` but a name only via
 * `runtimeProfile.current.machineName`, which is null until the agent first runs; such an agent is
 * still on a computer, so its group is named from [machineName] (the server's computer list), then
 * any row's own name, then the id. Only an agent without a `machineId` is "no computer" (task #147
 * follow-up #148: a new Built-in Pi agent on daybed-earth was listed under "no computer").
 */
internal fun groupedAgentMembers(
    members: List<MemberProfile>,
    machineName: (machineId: String) -> String? = { null }
): List<MembersAgentGroup> =
    members
        .groupBy { member ->
            member.membersComputerId
                ?: member.membersComputerName
                ?: build.raft.app.data.MembersAgentMachineGroupNoMachineKey
        }
        .map { (key, groupedMembers) ->
            val machineId = groupedMembers.firstNotNullOfOrNull { it.membersComputerId }
            MembersAgentGroup(
                key = key,
                label = machineId?.let(machineName)?.takeIf { it.isNotBlank() }
                    ?: groupedMembers.firstNotNullOfOrNull { it.membersComputerName }
                    ?: machineId
                    ?: membersNoComputerGroupLabel,
                members = groupedMembers
            )
        }

internal const val membersNoComputerGroupLabel = "no computer"

/**
 * The members row for an agent that was just created, shown until the members refresh lands. It
 * carries the agent's computer (the create response's machineId, else the one the dialog sent; the
 * name from the response, else the dialog's machine list) so the row never groups under "no
 * computer" (task #148; the shell members screen used to add it without any computer).
 */
internal fun createdAgentMemberProfile(
    agent: build.raft.app.data.AgentSummary,
    submit: SharedCreateAgentDialogSubmit,
    machines: List<SharedCreateAgentMachineOption>
): MemberProfile {
    val machineId = agent.machineId?.takeIf { it.isNotBlank() }
        ?: submit.machineId?.takeIf { it.isNotBlank() }
    val machineName = agent.machineName?.takeIf { it.isNotBlank() }
        ?: machineId?.let { id -> machines.firstOrNull { it.id == id }?.name?.takeIf { name -> name.isNotBlank() } }
    return MemberProfile(
        id = MemberId(agent.id),
        displayName = agent.displayName?.takeIf { it.isNotBlank() } ?: agent.name,
        handle = agent.name,
        kind = MemberKind.Agent,
        description = agent.description,
        roleDescription = agent.model,
        avatarUrl = agent.avatarUrl,
        avatar = AvatarReference.fromWireValue(agent.avatarUrl),
        presence = Presence.Offline,
        permissionBadges = listOfNotNull(
            machineName?.let { "computer:$it" },
            machineId?.let { "computer-id:$it" }
        )
    )
}
