package build.raft.app.members

import build.raft.app.resources.*
import build.raft.app.settings.iconName
import build.raft.app.settings.matches
import build.raft.app.thread.label
import build.raft.app.thread.localizedLabel
import build.raft.app.userFacingMessage
import androidx.compose.runtime.setValue
import build.raft.app.components.RaftUiSelectionOption
import build.raft.app.components.TabPager
import build.raft.app.currentRaftTimeContext
import build.raft.app.data.AgentActivityEntry
import build.raft.app.data.AgentDetailSnapshot
import build.raft.app.data.AgentDetailSummary
import build.raft.app.data.AgentResetMode
import build.raft.app.data.AgentWorkspaceTreeSnapshot
import build.raft.app.data.DataResult
import build.raft.app.data.SharedAgentDetailOwner
import build.raft.app.data.SharedAgentDetailState
import build.raft.app.data.PermissionBlocker
import build.raft.app.data.ScreenError
import build.raft.app.data.SettingsServerActionResult
import build.raft.app.data.RaftAgentsApiClient
import build.raft.app.data.ComputerStatus
import build.raft.app.data.ComputerSummary
import build.raft.app.data.SharedComputersLoadStatus
import build.raft.app.data.sharedAgentDetailOwner
import build.raft.app.get
import build.raft.app.iconName
import build.raft.app.label
import build.raft.app.localized
import build.raft.app.localizedLabel
import build.raft.app.matches
import build.raft.app.memberDetailAccessMode
import build.raft.app.memberKindWireValue
import build.raft.app.members.iconName
import build.raft.app.members.localized
import build.raft.app.members.localizedLabel
import build.raft.app.model.AgentStatus
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Presence
import build.raft.app.model.ServerId
import build.raft.app.model.RaftRelativeTimeLabel
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.civilDateFromEpochDay
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull
import build.raft.app.model.resolveRaftRelativeTimeLabel
import build.raft.app.raftAgentStatusIndicatorState
import build.raft.app.raftLocalizedShortDateTime
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.layout.layout
import build.raft.designsystem.components.RaftSidebarItem
import build.raft.designsystem.components.RaftSidebarSectionHeader
import build.raft.designsystem.components.RaftBadgeVariant
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.ErrorPresentationStrings
import build.raft.app.MemberDetailAccessMode
import build.raft.app.MemberDetailIdentity
import build.raft.app.MembersAgentLifecycleAction
import build.raft.app.RaftAdaptivePaneLayout
import build.raft.app.RaftAgentStatusIndicatorState
import build.raft.app.RaftAppSpacing
import build.raft.app.RaftLucideIconName
import build.raft.app.SettingsComputerRouteIdentity
import build.raft.app.compat.json.JSONArray
import build.raft.app.compat.json.JSONObject
import org.jetbrains.compose.resources.StringResource
import build.raft.app.compat.pager.Pager
import build.raft.app.data.map
import kotlinx.coroutines.launch
import androidx.compose.ui.semantics.selected

const val MEMBERS_PAGE = "compose_members"

const val MEMBER_DETAIL_PAGE = "compose_member_detail"

const val MEMBERS_SMOKE_PAGE = "compose_members_smoke"

internal val MembersPageInset = RaftAppSpacing.Md

// Web AgentDetailPanel profile tab: hero and every section are `px-5`.
internal val MembersAgentProfileInset = 20.dp

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
    paneLayout: RaftAdaptivePaneLayout,
): Dp {
    if (paneLayout != RaftAdaptivePaneLayout.DualPane) return slotWidth
    val cap = slotWidth - MembersListContentMinSideMargin * 2
    return if (cap < MembersListContentMaxWidth) cap else MembersListContentMaxWidth
}

// The rows and section headers are the design system's RaftSidebarItem /
// RaftSidebarSectionHeader (raft-ui SidebarItem at its phone size: 44dp, `text-body` 16/24
// at 600), as the web members page draws them; they carry the row/section `px-2` themselves.
/** The avatar in a row's leading slot: raft-ui Avatar `2xs` (19dp, `AvatarSlot` `sidebar-list`). */
internal val MembersListAvatarSize = 19.dp

// Web members machine-group toggle (Sidebar.tsx): `mt-1.5 mb-0.5 px-2`, `gap-1.5`, 12px
// ChevronRight and Monitor, `font-mono text-mono-meta` (12/16) in `foreground-hint`.
internal val MembersListMachineLabelInset = 8.dp

internal val MembersListMachineLabelTop = 6.dp

internal val MembersListMachineLabelBottom = 2.dp

internal val MembersListMachineLabelGap = 6.dp

internal val MembersListMachineLabelIconSize = 12.dp

/** Web create-agent menu `min-w-[190px]`. */
internal val MembersCreateAgentMenuMinWidth = 190.dp

private val MembersActionSize = 28.dp

// Web AgentActivityLog row: `gap-2` (8) + StatusDot sm (`size-2` = 8) + `gap-2` (8).

/** The two create flows exposed by the web Agents section action menu. */
internal enum class MembersCreateAgentMode {
    Managed,
    External
}

internal fun MembersCreateAgentMode.requiresComputerSelection(): Boolean = this == MembersCreateAgentMode.Managed

internal enum class MembersAgentDetailTab(
    val label: String,
    val iconName: RaftLucideIconName,
    val privateSurface: Boolean = true
) {
    Profile("Profile", RaftLucideIconName.Bot, privateSurface = false),
    Activity("Activity", RaftLucideIconName.Activity),
    Usage("Usage", RaftLucideIconName.ChartColumn),
    // raft-ui `MessageIcon` (web AGENT_TABS chat).
    Chat("Chat", RaftLucideIconName.Message),
    Reminders("Reminders", RaftLucideIconName.AlarmClock),
    Workspace("Workspace", RaftLucideIconName.FolderOpen),
    Apps("Apps", RaftLucideIconName.AppWindow)
}

@Composable
internal fun MembersAgentDetailTab.localizedLabel(): String =
    when (this) {
        MembersAgentDetailTab.Profile -> stringResource(Res.string.member_label_profile)
        MembersAgentDetailTab.Activity -> stringResource(Res.string.member_tab_activity)
        MembersAgentDetailTab.Usage -> stringResource(Res.string.member_tab_usage)
        MembersAgentDetailTab.Chat -> stringResource(Res.string.member_tab_chat)
        MembersAgentDetailTab.Reminders -> stringResource(Res.string.member_tab_reminders)
        MembersAgentDetailTab.Workspace -> stringResource(Res.string.member_tab_workspace)
        MembersAgentDetailTab.Apps -> stringResource(Res.string.member_tab_apps)
    }

internal fun membersHeaderMessageUsesIconOnly(kind: MemberKind): Boolean =
    kind == MemberKind.Agent || kind == MemberKind.Human

internal enum class MembersAgentServerRole(
    val wireValue: String,
    val label: String
) {
    Admin("admin", "Admin"),
    Member("member", "Member")
}

internal enum class MembersHumanServerRole(
    val wireValue: String
) {
    Owner("owner"),
    Admin("admin"),
    Member("member")
}

@Composable
internal fun MembersHumanServerRole.localizedLabel(): String =
    when (this) {
        MembersHumanServerRole.Owner -> stringResource(Res.string.member_role_owner)
        MembersHumanServerRole.Admin -> stringResource(Res.string.member_role_admin)
        MembersHumanServerRole.Member -> stringResource(Res.string.member_role_member)
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
    // A Guest is never a target, but an existing Guest can be made a member or admin (web
    // `humanRoleTransitions` / shared `canTransitionServerRole`).
    val targetIsGuest = targetRole.normalizedMembersServerRole() == "guest"
    val currentRole = targetRole.toMembersHumanServerRoleOrNull()
    if (currentRole == null && !targetIsGuest) return emptyList()
    return when (actorRole.normalizedMembersServerRole()) {
        "owner" -> MembersHumanServerRole.entries.filter { candidate ->
            candidate != currentRole &&
                !(currentRole == MembersHumanServerRole.Owner && (ownerCount == null || ownerCount <= 1))
        }
        "admin" ->
            when {
                targetIsGuest -> listOf(MembersHumanServerRole.Admin, MembersHumanServerRole.Member)
                currentRole == MembersHumanServerRole.Member -> listOf(MembersHumanServerRole.Admin)
                else -> emptyList()
            }
        else -> emptyList()
    }
}

/** Web `getHumanDepartureLabel`: a person who left or was removed shows that instead of a role. */
internal enum class MembersHumanDeparture { Left, Removed }

internal fun membersHumanDeparture(membershipStatus: String?): MembersHumanDeparture? =
    when (membershipStatus?.trim()?.lowercase()) {
        "left" -> MembersHumanDeparture.Left
        "removed" -> MembersHumanDeparture.Removed
        else -> null
    }

internal fun membersHumanIsSelf(member: MemberProfile, currentUserId: String?): Boolean {
    val selfId = currentUserId?.trim()?.takeIf { it.isNotBlank() } ?: return false
    return member.kind == MemberKind.Human && member.id.value.trim() == selfId
}

/**
 * Web HumanDetailPanel (`isSelf && human.membershipStatus === "active"`): a signed-in person
 * can edit their own profile description only while their membership is active.
 */
internal fun membersHumanCanEditDescription(member: MemberProfile, currentUserId: String?): Boolean {
    if (!membersHumanIsSelf(member, currentUserId)) return false
    val status = member.membershipStatus?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
    return status == "active"
}

/**
 * Web shows Info and Created Agents only for a person of this server; a profile reached
 * through another server (`isRemoteJointHuman`) shows the name, handle and description.
 */
internal fun membersHumanShowsServerFacts(accessMode: MemberDetailAccessMode): Boolean =
    accessMode == MemberDetailAccessMode.LocalFull

/**
 * The role chip of a human profile (web HumanDetailPanel `ROLE_CONFIG`): a role is a fact
 * about the person, so owner, admin and member are the same neutral soft badge; a guest
 * comes from outside and reads as the information badge; no role is the muted "Unknown".
 */
internal enum class MembersHumanRoleBadge(val badgeVariant: RaftBadgeVariant) {
    Owner(RaftBadgeVariant.Default),
    Admin(RaftBadgeVariant.Default),
    Member(RaftBadgeVariant.Default),
    Guest(RaftBadgeVariant.Information),
    Unknown(RaftBadgeVariant.Muted)
}

internal fun membersHumanRoleBadge(serverRole: String?, roleDescription: String?): MembersHumanRoleBadge {
    fun String?.toBadgeOrNull(): MembersHumanRoleBadge? =
        when (this?.trim()?.lowercase()) {
            "owner" -> MembersHumanRoleBadge.Owner
            "admin" -> MembersHumanRoleBadge.Admin
            "member" -> MembersHumanRoleBadge.Member
            "guest" -> MembersHumanRoleBadge.Guest
            else -> null
        }
    return serverRole.toBadgeOrNull() ?: roleDescription.toBadgeOrNull() ?: MembersHumanRoleBadge.Unknown
}

internal data class MembersJoinedDate(val year: Int, val month: Int, val day: Int)

/** The local calendar day of a `joinedAt` timestamp, or null when it is missing or unparseable. */
internal fun String?.membersJoinedDateOrNull(timeContext: RaftTimeContext): MembersJoinedDate? {
    val trimmed = this?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val epochMillis = parseRaftTimestampEpochMillisOrNull(trimmed, timeContext.localOffsetMinutes) ?: return null
    val date = civilDateFromEpochDay(timeContext.localEpochDay(epochMillis))
    return MembersJoinedDate(year = date.year, month = date.month, day = date.day)
}

/**
 * The month token of `member_joined_date` (web `formatDate` with `month: "short"`): "Sep" in
 * English, "9" where the locale writes 2026年9月11日. [monthsCsv] is the localized twelve.
 */
internal fun membersJoinedMonthLabel(monthsCsv: String, month: Int): String =
    monthsCsv.split(',').getOrNull(month - 1)?.trim()?.takeIf { it.isNotEmpty() } ?: month.toString()

/** [date] in the UI language (`member_joined_date`): "Aug 4, 2026" in English, 2026年8月4日 in zh-CN and ja. */
@Composable
internal fun membersLocalizedDate(date: MembersJoinedDate): String =
    stringResource(
        Res.string.member_joined_date,
        membersJoinedMonthLabel(stringResource(Res.string.member_joined_date_months), date.month),
        date.day.toString(),
        date.year.toString()
    )

/**
 * A `createdAt` timestamp as the local calendar day in the UI language; the raw text when it does
 * not parse; null when it is blank.
 */
@Composable
internal fun String?.membersLocalizedCreatedDateOrNull(timeContext: RaftTimeContext): String? {
    val trimmed = this?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return trimmed.membersJoinedDateOrNull(timeContext)?.let { membersLocalizedDate(it) } ?: trimmed
}

/**
 * Route data for the profile of an agent listed under a human's Created Agents (web
 * CreatedAgentRow opens the agent profile). Keeps the human page's time context and
 * source server so a remote server's agent resolves against the same server.
 */
internal fun RaftMemberDetailPayload.toCreatedAgentDetailPageData(
    agentId: MemberId,
    sourceServerId: ServerId?
): JSONObject? {
    val selected = agentId.value.trim().takeIf { it.isNotBlank() } ?: return null
    return JSONObject().apply {
        put("pageName", MEMBER_DETAIL_PAGE)
        put("instanceId", "members-detail-created-agent-${instanceId.ifBlank { "member" }}-$selected")
        put("memberId", selected)
        put("memberKind", MemberKind.Agent.memberKindWireValue())
        (sourceServerId ?: identity?.sourceServerId)?.value?.trim()?.takeIf { it.isNotBlank() }?.let {
            put("sourceServerId", it)
        }
        localOffsetMinutes?.let { put("localOffsetMinutes", it) }
        todayEpochDay?.let { put("todayEpochDay", it) }
        nowEpochMillis?.let { put("nowEpochMillis", it) }
        timeFormat?.takeIf { it.isNotBlank() }?.let { put("timeFormat", it) }
        if (agentPanelTabOrder.isNotEmpty()) {
            put("agentPanelTabOrder", JSONArray().apply { agentPanelTabOrder.forEach(::put) })
        }
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
internal fun MembersAgentServerRole.localizedLabel(): String =
    when (this) {
        MembersAgentServerRole.Admin -> stringResource(Res.string.member_role_admin)
        MembersAgentServerRole.Member -> stringResource(Res.string.member_role_member)
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
    /** A role we recognise: render its own label. */
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
        val runLabel: MembersMachineRunLabel,
        /** The requester attached this machine's Computer (web `computerAttachedByCurrentUser`). */
        val attachedByCurrentUser: Boolean = false
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
        runLabel = membersMachineRunLabel(machine),
        attachedByCurrentUser = machine.attachedByCurrentUser
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

/** [label] is the role's localized name ([MembersAgentServerRole.localizedLabel]), never the enum's English. */
internal fun membersAgentServerRoleOptions(
    selectedRole: MembersAgentServerRole?,
    label: (MembersAgentServerRole) -> String
): List<RaftUiSelectionOption> =
    MembersAgentServerRole.entries.map { role ->
        RaftUiSelectionOption(
            id = role.wireValue,
            label = label(role),
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

internal fun String?.normalizedMembersServerRole(): String =
    this?.trim()?.lowercase().orEmpty()

// A zero-argument native string lookup that routes through a variadic
// platform API can dereference a missing vararg when the resource contains
// %1$s. Resolve templates through KmmResource's formatted service path with
// a sentinel, then restore the placeholder for callbacks that supply the
// eventual value outside composition.
internal const val MembersFormatSentinel = "__RAFT_MEMBER_FORMAT_VALUE__"

internal const val MembersFormatSentinel2 = "__RAFT_MEMBER_FORMAT_VALUE_2__"

@Composable
internal fun membersStringTemplate(resource: StringResource): String =
    stringResource(resource, MembersFormatSentinel)
        .replace(MembersFormatSentinel, "%1\$s")

internal fun String.membersFormat(value: String): String = replace("%1\$s", value)

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
    deleted: Boolean = false,
    /** The viewer's server role: a Guest has no DMs with people (web `humanDmAccess.canMessageHuman`). */
    viewerRole: String? = null
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
        // A Guest has no DMs (#wg-rbac:#15), neither as the viewer nor as the other person, even
        // if a DM channel exists from before (web `humanDmAccess.canMessageHuman`).
        if (viewerRole.normalizedMembersServerRole() == "guest" || member.serverRole.normalizedMembersServerRole() == "guest") {
            return MembersDirectMessageEligibility.Unavailable
        }
    }
    return MembersDirectMessageEligibility.Peer
}

internal fun membersVisibleAgentTabs(
    canViewPrivateSurfaces: Boolean,
    deleted: Boolean,
    managedByApp: Boolean = false,
): List<MembersAgentDetailTab> =
    MembersAgentDetailTab.entries.filter { tab ->
        when {
            tab == MembersAgentDetailTab.Profile -> true
            // Web AgentDetailPanel: Usage is private (same rule as Activity),
            // and never shown for App Bots or archived/deleted agents.
            tab == MembersAgentDetailTab.Usage ->
                canViewPrivateSurfaces && !deleted && !managedByApp
            else -> canViewPrivateSurfaces
        }
    }

internal fun membersAgentDetailTabsForAccess(
    accessMode: MemberDetailAccessMode,
    canViewPrivateSurfaces: Boolean,
    deleted: Boolean,
    managedByApp: Boolean = false,
): List<MembersAgentDetailTab> =
    if (!accessMode.allowsPrivateAgentSurfaces) {
        listOf(MembersAgentDetailTab.Profile)
    } else {
        membersVisibleAgentTabs(
            canViewPrivateSurfaces = canViewPrivateSurfaces,
            deleted = deleted,
            managedByApp = managedByApp,
        )
    }

/**
 * Web AgentDetailPanel `isRemoteJointAgent` / `isBoundedPublicProjection`: the profile of an
 * agent of another server (reached through a joint channel) shows identity only — no Info,
 * runtime, skills, actions or Message. Its public profile (description, status) is read from the
 * viewer's server, which answers `GET /agents/:id` for a joint channel's peer agent.
 */
internal fun membersAgentProfileIsRemote(accessMode: MemberDetailAccessMode): Boolean =
    accessMode == MemberDetailAccessMode.RemoteProfileOnly

/**
 * Web AgentProfileInfo (`isBoundedPublicProjection`): a profile from another server of a joint
 * channel ([MemberDetailAccessMode.RemoteProfileOnly]) or a bounded channel-summary projection
 * (`profileProjection == "channel_summary"`). When empty on such a profile, the whole Description
 * section is omitted rather than showing "No description".
 */
internal fun membersAgentIsBoundedPublicProjection(
    accessMode: MemberDetailAccessMode,
    profileProjection: String?
): Boolean =
    membersAgentProfileIsRemote(accessMode) ||
        profileProjection?.trim()?.equals("channel_summary", ignoreCase = true) == true

internal fun membersShouldShowAgentDescriptionSection(
    accessMode: MemberDetailAccessMode,
    profileProjection: String?,
    description: String?
): Boolean =
    !membersAgentIsBoundedPublicProjection(accessMode, profileProjection) || !description.isNullOrBlank()

/** pageData key of [RaftMemberDetailPayload.profileChannelId]. */
internal const val MemberDetailProfileChannelIdKey = "profileChannelId"

/**
 * A person's profile "From" row (web HumanDetailPanel `sourceServerLabel`, utils/
 * channelLocalMembership.ts `memberServerBadgeLabels`): the servers they are from, joined by ", ",
 * when any is not the viewer's server; null for a purely local person. [MemberProfile.servers] is
 * the server's answer exactly, an empty list included (then nothing shows); without it, the single
 * server fields.
 */
internal fun membersHumanSourceServerLabel(member: MemberProfile, viewerServerId: String?): String? {
    return build.raft.app.data.memberServerBadgeLabels(member, viewerServerId)
        .joinToString(", ")
        .ifEmpty { null }
}

/** The "From" row's server name (web `agent.serverName || agent.serverSlug`); never a raw id. */
internal fun membersRemoteAgentSourceServerName(member: MemberProfile): String? {
    val serverId = member.serverId?.value?.trim()
    return listOf(member.serverName, member.serverSlug)
        .firstNotNullOfOrNull { value -> value?.trim()?.takeIf { it.isNotBlank() && it != serverId } }
}

/**
 * The roster facts a joint channel already holds for an agent of another server, the remote
 * profile's fallback when the viewer's server's joint profile read fails: the open's own seed (thread roster / author row) first, then a roster
 * profile, then the joint actor row. Null only when nothing knows the agent.
 */
internal fun membersRemoteAgentProfileSnapshot(
    agentId: MemberId,
    seed: AgentDetailSnapshot?,
    rosterProfile: MemberProfile?,
    jointActor: build.raft.app.data.ResolvedActor?
): AgentDetailSnapshot? {
    seed?.takeIf { it.agentId == agentId }?.let { return it }
    val summary =
        rosterProfile
            ?.takeIf { it.id == agentId && it.kind == MemberKind.Agent }
            ?.let { profile ->
                val name = profile.handle.ifBlank { profile.displayName.ifBlank { agentId.value } }
                AgentDetailSummary(
                    id = agentId.value,
                    name = name,
                    displayName = profile.displayName.ifBlank { name },
                    description = profile.description?.takeIf { it.isNotBlank() },
                    avatarUrl = profile.avatar?.wireValue
                )
            }
            ?: jointActor
                ?.takeIf { it.id == agentId.value && it.kind == MemberKind.Agent }
                ?.let { actor ->
                    AgentDetailSummary(
                        id = agentId.value,
                        name = actor.name,
                        displayName = actor.displayName?.takeIf { it.isNotBlank() } ?: actor.name,
                        avatarUrl = actor.avatarUrl?.takeIf { it.isNotBlank() }
                    )
                }
            ?: return null
    return AgentDetailSnapshot(
        agentId = agentId,
        summary = summary,
        directMessages = emptyList(),
        reminders = emptyList(),
        activity = emptyList()
    )
}

/**
 * The viewer's server's joint profile of a remote agent ([RaftAgentsApiClient.publicAgentDetail]),
 * with the roster's name/avatar where the answer left them blank.
 */
internal fun AgentDetailSnapshot.withRemoteRosterFallback(roster: AgentDetailSnapshot?): AgentDetailSnapshot {
    val remote = summary ?: return this
    val fallback = roster?.summary?.takeIf { it.id == remote.id } ?: return this
    return copy(
        summary =
            remote.copy(
                displayName = remote.displayName?.takeIf { it.isNotBlank() } ?: fallback.displayName,
                avatarUrl = remote.avatarUrl?.takeIf { it.isNotBlank() } ?: fallback.avatarUrl,
                description = remote.description?.takeIf { it.isNotBlank() } ?: fallback.description
            )
    )
}

internal fun membersAgentDetailShowsProfileStateNotice(
    selectedTab: MembersAgentDetailTab,
    avatarManagementMode: Boolean,
    state: MembersAgentDetailLoadState,
): Boolean =
    selectedTab == MembersAgentDetailTab.Profile &&
        !avatarManagementMode &&
        membersAgentDetailStateHasNotice(state)

/**
 * Whether [MembersAgentDetailStateNotice] draws a banner for [state]. An empty notice must not
 * take a list slot: its 16dp gap pushed the loaded profile 16dp below the web's hero.
 */
internal fun membersAgentDetailStateHasNotice(state: MembersAgentDetailLoadState): Boolean =
    when (state) {
        MembersAgentDetailLoadState.Idle -> false
        is MembersAgentDetailLoadState.Content -> state.refreshing
        else -> true
    }

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

/** The active agent tab: an explicit selection while it is visible, else the first ordered tab. */
internal fun membersAgentDetailSelectedTab(
    override: MembersAgentDetailTab?,
    visibleTabs: List<MembersAgentDetailTab>
): MembersAgentDetailTab =
    override?.takeIf { it in visibleTabs }
        ?: visibleTabs.firstOrNull()
        ?: MembersAgentDetailTab.Profile

/**
 * Task #22: the selection to keep. While the strip is hidden (order still unknown) nothing is
 * pinned, so the order that arrives decides the default tab; once shown, the visible tab is pinned.
 */
internal fun membersAgentDetailPinnedSelection(
    override: MembersAgentDetailTab?,
    shownTab: MembersAgentDetailTab,
    stripShown: Boolean
): MembersAgentDetailTab? =
    if (stripShown && override == null) shownTab else override

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

/**
 * Drop index for a long-press tab drag (raft-ui SortableTabsList): the dragged tab's centre,
 * shifted by [dragOffsetPx], moves past every neighbour whose midpoint it crosses. Uses the
 * measured widths of all tabs (in order, [gapPx] apart), so tabs of different widths reorder
 * by the neighbour actually crossed rather than by the dragged tab's own width. A missing
 * (unmeasured) width counts as 0.
 */
internal fun membersAgentTabDropIndex(
    widthsPx: List<Int>,
    gapPx: Float,
    fromIndex: Int,
    dragOffsetPx: Float
): Int {
    if (fromIndex !in widthsPx.indices) return fromIndex
    val starts = FloatArray(widthsPx.size)
    var x = 0f
    widthsPx.forEachIndexed { i, w ->
        starts[i] = x
        x += w.coerceAtLeast(0) + gapPx
    }
    fun mid(i: Int): Float = starts[i] + widthsPx[i].coerceAtLeast(0) / 2f
    val draggedCentre = mid(fromIndex) + dragOffsetPx
    var target = fromIndex
    if (dragOffsetPx > 0f) {
        for (i in fromIndex + 1..widthsPx.lastIndex) {
            if (draggedCentre > mid(i)) target = i else break
        }
    } else if (dragOffsetPx < 0f) {
        for (i in fromIndex - 1 downTo 0) {
            if (draggedCentre < mid(i)) target = i else break
        }
    }
    return target
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

internal fun String?.toMembersAgentDetailTabOrNull(): MembersAgentDetailTab? {
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

/**
 * Initial agent tab. With the order known the first ordered tab is the default (null), like web's
 * AgentDetailPanel; only an open that gave up waiting for the order pins Profile, so the order
 * arriving later reorders the strip without moving the selection.
 */
internal fun membersInitialAgentDetailTabForRender(
    expectedKind: MemberKind?,
    explicitInitialTab: MembersAgentDetailTab?,
    payloadAgentPanelTabOrder: List<String>,
    orderKnown: Boolean
): MembersAgentDetailTab? {
    if (expectedKind != MemberKind.Agent) return explicitInitialTab
    explicitInitialTab?.let { return it }
    payloadAgentPanelTabOrder.firstNotNullOfOrNull { it.toMembersAgentDetailTabOrNull() }?.let { return it }
    return if (!orderKnown) MembersAgentDetailTab.Profile else null
}

/** How long a cold-start agent profile waits for the user's tab order before showing the default. */
internal const val MembersAgentTabOrderWaitMillis = 1_200L

/** Task #22: the agent tab order to draw and whether it is the user's order (not a guess). */
internal data class MembersAgentTabOrderRender(
    val order: List<String>,
    val known: Boolean
)

/**
 * [orderServerId] is null when the detail shows no private agent tabs (only Profile), so there is
 * nothing to wait for. Otherwise the store/device order wins, then the launch payload's order; with
 * neither the order is unknown and the tab strip waits.
 */
internal fun membersAgentTabOrderForRender(
    orderServerId: String?,
    knownOrder: List<String>?,
    payloadOrder: List<String>
): MembersAgentTabOrderRender =
    when {
        orderServerId == null -> MembersAgentTabOrderRender(payloadOrder, known = true)
        knownOrder != null -> MembersAgentTabOrderRender(knownOrder, known = true)
        payloadOrder.isNotEmpty() -> MembersAgentTabOrderRender(payloadOrder, known = true)
        else -> MembersAgentTabOrderRender(emptyList(), known = false)
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

internal sealed interface MembersAgentDetailLoadState {
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

internal sealed interface MembersAgentWorkspaceTreeState {
    data object Idle : MembersAgentWorkspaceTreeState
    data object Loading : MembersAgentWorkspaceTreeState
    data class Content(val snapshot: AgentWorkspaceTreeSnapshot) : MembersAgentWorkspaceTreeState
    data class NoPermission(val blocker: PermissionBlocker) : MembersAgentWorkspaceTreeState
    data class Error(val error: ScreenError) : MembersAgentWorkspaceTreeState
    data object Empty : MembersAgentWorkspaceTreeState
}

internal val MembersAgentLifecycleAction.iconName: RaftLucideIconName
    get() =
        when (this) {
            MembersAgentLifecycleAction.Start -> RaftLucideIconName.Play
            MembersAgentLifecycleAction.Stop -> RaftLucideIconName.Square
            MembersAgentLifecycleAction.Restart -> RaftLucideIconName.RotateCw
            MembersAgentLifecycleAction.ResetSession -> RaftLucideIconName.RefreshCw
            MembersAgentLifecycleAction.ResetFull -> RaftLucideIconName.Eraser
            MembersAgentLifecycleAction.Delete -> RaftLucideIconName.Archive
            MembersAgentLifecycleAction.UnassignMachine -> RaftLucideIconName.MonitorX
        }

@Composable
internal fun MembersAgentLifecycleAction.localizedLabel(): String =
    when (this) {
        MembersAgentLifecycleAction.Start -> stringResource(Res.string.member_action_start_agent)
        MembersAgentLifecycleAction.Stop -> stringResource(Res.string.member_action_stop_agent)
        MembersAgentLifecycleAction.Restart -> stringResource(Res.string.member_action_restart_agent)
        MembersAgentLifecycleAction.ResetSession -> stringResource(Res.string.member_action_reset_runtime_session)
        MembersAgentLifecycleAction.ResetFull -> stringResource(Res.string.member_action_full_reset_restart)
        MembersAgentLifecycleAction.Delete -> stringResource(Res.string.member_action_delete_agent)
        MembersAgentLifecycleAction.UnassignMachine -> stringResource(Res.string.member_action_unassign_machine)
    }

internal data class MembersAgentDiagnosticActionState(
    val actionKey: String,
    val inProgress: Boolean,
    val error: ScreenError? = null,
    val successLabel: String? = null
)

internal const val MembersUnknownAgentStatusLabel = "—"

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

internal fun unknownMembersAgentStatusIndicatorState(): RaftAgentStatusIndicatorState =
    raftAgentStatusIndicatorState(
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
 * `Presence.Offline` to PresenceOffline, so an unknown agent would render the same dot as
 * an offline one while its label reads "—" (task #256; @LiBai's review of PR #2325 caught the
 * first attempt fixing only the elvis-fallback spelling of this defect and missing this one).
 */
internal fun resolveMembersAgentStatusIndicatorStateOrNull(
    canonicalState: RaftAgentStatusIndicatorState?,
    fixtureFallback: RaftAgentStatusIndicatorState?,
    fixtureStatusFallbackEnabled: Boolean,
    remoteProfileFallback: RaftAgentStatusIndicatorState? = null
): RaftAgentStatusIndicatorState? =
    canonicalState
        ?: remoteProfileFallback
        ?: fixtureFallback.takeIf { fixtureStatusFallbackEnabled }

/**
 * Label-facing resolution: never null, because an explicit unknown must outrank a stale
 * summary caption (see `explicitCanonicalUnknownCannotBeCaptionedByStaleSummaryActivity`).
 * Do not use it to choose a dot colour.
 */
internal fun resolveMembersAgentStatusIndicatorState(
    canonicalState: RaftAgentStatusIndicatorState?,
    fixtureFallback: RaftAgentStatusIndicatorState?,
    fixtureStatusFallbackEnabled: Boolean,
    remoteProfileFallback: RaftAgentStatusIndicatorState? = null
): RaftAgentStatusIndicatorState =
    resolveMembersAgentStatusIndicatorStateOrNull(
        canonicalState = canonicalState,
        fixtureFallback = fixtureFallback,
        fixtureStatusFallbackEnabled = fixtureStatusFallbackEnabled,
        remoteProfileFallback = remoteProfileFallback
    ) ?: unknownMembersAgentStatusIndicatorState()

internal fun RaftAgentStatusIndicatorState.toMembersAgentStatusDisplayState(): MembersAgentStatusDisplayState =
    MembersAgentStatusDisplayState(
        status = status,
        color = color,
        external = external
    )

/**
 * Pure seam for [membersReminderRelativeTime]: uses shared [resolveRaftRelativeTimeLabel] so
 * half-step rounding (`floor(x + 0.5)`) matches web `AgentRemindersSection.tsx` identically on
 * JVM and Native/iOS. Returns `null` when [value] is unparseable or [timeContext] lacks `nowEpochMillis`.
 */
internal fun resolveMembersReminderRelativeTimeLabel(
    value: String?,
    timeContext: RaftTimeContext?,
): RaftRelativeTimeLabel.Relative? {
    val trimmed = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val context = timeContext?.takeIf { it.nowEpochMillis != null } ?: return null
    parseRaftTimestampEpochMillisOrNull(trimmed, context.localOffsetMinutes) ?: return null
    return resolveRaftRelativeTimeLabel(trimmed, context) as? RaftRelativeTimeLabel.Relative
}

/**
 * The reminder's fire time relative to now, in this locale (the shared relative-time catalog):
 * "in 3 hours", "5 minutes ago", "now"; subscribes to the enclosing live [LocalRaftTimeContextProvider]
 * when [timeContext] has no clock (`nowEpochMillis == null`), falls back to the localized short
 * date-time when no clock is available, and returns the trimmed raw value when unparseable.
 */
@Composable
internal fun String?.membersReminderRelativeTime(timeContext: RaftTimeContext): String {
    val trimmed = this?.trim().orEmpty()
    if (trimmed.isEmpty()) return ""
    val liveContext = currentRaftTimeContext(timeContext)
    val effectiveContext = liveContext ?: timeContext
    val resolved =
        if (effectiveContext.nowEpochMillis != null && !trimmed.equals("Now", ignoreCase = true)) {
            resolveRaftRelativeTimeLabel(trimmed, effectiveContext)
        } else {
            null
        }
    return when (resolved) {
        is RaftRelativeTimeLabel.Relative -> resolved.localized()
        else -> trimmed.membersReminderShortDateTime(effectiveContext)
    }
}

/**
 * Localized short month/day + clock (web `formatShortDateTime`) for reminder and app-grant rows.
 * Delegates to [raftLocalizedShortDateTime] (8 locales, respecting 12h/24h preference and DI timezone).
 */
@Composable
internal fun String?.membersReminderShortDateTime(timeContext: RaftTimeContext): String =
    raftLocalizedShortDateTime(this, timeContext)
