package build.raft.app.members

import build.raft.app.data.AgentBlockedState
import build.raft.app.resources.*
import build.raft.app.strictMemberKindOrNull
import build.raft.app.strictProjectionDiagnosticsLogger
import build.raft.app.toRaftAvatarLabel
import build.raft.app.withoutActivityDetail
import build.raft.app.data.AgentActivityEntry
import build.raft.app.data.AgentCreatorSummary
import build.raft.app.data.AgentDetailSnapshot
import build.raft.app.data.AgentDetailSummary
import build.raft.app.data.defaultNowMillis
import build.raft.app.data.normalizedAgentRuntimeStatus
import build.raft.app.get
import build.raft.app.model.AgentDeliveryHold
import build.raft.app.model.AgentStatus
import build.raft.app.model.AvatarReference
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Presence
import build.raft.app.model.supportsRuntimeFastMode
import build.raft.app.model.supportsRuntimeReasoningEffort
import build.raft.app.raftAgentRuntimeDisplayName
import build.raft.app.raftAgentStatusIndicatorState
import build.raft.app.model.ServerSummary
import androidx.compose.runtime.key
import androidx.compose.ui.graphics.Color
import build.raft.app.AgentRuntimeErrorDisplay
import build.raft.app.AgentRuntimeErrorKind
import build.raft.app.MemberKindWireContract
import build.raft.app.RaftAgentStatusIndicatorState
import build.raft.app.RaftColor
import build.raft.app.activeDeliveryHold
import build.raft.app.agentRuntimeErrorDisplay
import build.raft.app.agentTypedRuntimeErrorKind
import build.raft.app.busyThroughWorkers
import org.jetbrains.compose.resources.StringResource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import build.raft.app.model.isExternalAgentRuntime

internal data class MembersAgentStatusDisplayState(
    val status: AgentStatus?,
    val color: Color,
    val external: Boolean
)

internal val MembersUnknownAgentStatusDisplayState =
    MembersAgentStatusDisplayState(
        status = null,
        color = RaftColor.StatusNeutralGray,
        external = false
    )

internal fun MemberProfile.membersAgentStatusDisplayState(
    summaryStatus: String? = null,
    summaryActivity: String? = null
): MembersAgentStatusDisplayState =
    toFixtureRaftAgentStatusIndicatorState(
        summaryStatus = summaryStatus,
        summaryActivity = summaryActivity
    ).toMembersAgentStatusDisplayState()

/**
 * Fixture fallback goes through the single canonical mapping so the external
 * short-circuit (task #1119) applies here exactly as it does to the live resolver.
 */
internal fun MemberProfile.toFixtureRaftAgentStatusIndicatorState(
    summaryStatus: String? = null,
    summaryActivity: String? = null
): RaftAgentStatusIndicatorState =
    raftAgentStatusIndicatorState(
        status = membersResolvedAgentStatus(summaryStatus, summaryActivity),
        presence = presence,
        external = isExternal
    )

/**
 * Observed status from `GET /api/agents/:id` (`status` / `activity`) for a cross-server
 * joint-channel agent ([MemberDetailAccessMode.RemoteProfileOnly]) when no live status is
 * bound. Returns null when the server gives no runtime status (for example `status="active"`
 * with no `activity`, or a failed profile read), so the hero renders no chip rather than an
 * empty "—" placeholder.
 */
internal fun membersRemoteProfileAgentStatusIndicatorStateOrNull(
    summaryStatus: String?,
    summaryActivity: String?,
    external: Boolean = false,
    runtime: String? = null,
    model: String? = null,
    deliveryHold: AgentDeliveryHold? = null,
    runningWorkers: Int? = null,
    runningWorkersPresenceOnly: Boolean = false,
    serviceTier: String? = null,
): RaftAgentStatusIndicatorState? {
    val status =
        normalizedAgentRuntimeStatus(
            activity = summaryActivity,
            lifecycleStatus = summaryStatus
        ) ?: return null
    val normalizedWorkers = build.raft.app.data.normalizeAgentRunningWorkers(runningWorkers)
    val resolvedWorkers =
        when {
            normalizedWorkers == null -> null
            runningWorkersPresenceOnly -> 1
            else -> normalizedWorkers
        }
    val base =
        raftAgentStatusIndicatorState(
            status = status,
            presence = status.presence,
            external = external
        ).copy(
            runtime = runtime,
            model = model,
            deliveryHold = deliveryHold,
            runningWorkers = resolvedWorkers,
            runningWorkersPresenceOnly = resolvedWorkers != null && runningWorkersPresenceOnly,
            serviceTier = build.raft.app.data.normalizeAgentServiceTier(serviceTier),
        )
    val nowMs = defaultNowMillis()
    return when {
        deliveryHold != null && base.activeDeliveryHold(nowMs = nowMs) != null ->
            base.copy(color = RaftColor.AccentOrange)
        base.busyThroughWorkers(nowMs = nowMs) != null ->
            base.copy(color = RaftColor.BrandYellow)
        else -> base
    }
}

internal val MemberProfile.detailHandle: String
    get() = handle.takeIf { it.isNotBlank() }?.let { if (it.startsWith("@")) it else "@$it" } ?: id.value

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
/**
 * The profile hero's status chip text (web AgentStatusBadge). With [showActivityDetail] (web
 * `showDetail`), the chip carries the agent's current activity: the live label when the store
 * supplied one richer than the bare status name, else the summary's detail while the summary
 * agrees with the live status. Without it, only the coarse status words (web
 * `formatActivityText(activity, "")`).
 */
/** Labels that only name a status (the words [localizedMemberStatusLabel] translates), not an activity. */
private val MembersCoarseAgentStatusWords =
    setOf("online", "active", "offline", "idle", "away", "disconnected", "error", "external", "working", "thinking", "unknown")

internal fun membersHeroAgentStatusLabel(
    indicatorState: RaftAgentStatusIndicatorState?,
    summaryStatus: String?,
    summaryActivity: String?,
    summaryActivityDetail: String?,
    showActivityDetail: Boolean
): String {
    if (!showActivityDetail) {
        return membersLiveAgentStatusLabel(
            indicatorState = indicatorState?.withoutActivityDetail(),
            summaryStatus = summaryStatus,
            summaryActivity = summaryActivity,
            summaryActivityDetail = null,
            fallback = MembersUnknownAgentStatusLabel
        )
    }
    val liveStatus = indicatorState?.status
    val liveDetail =
        indicatorState?.label?.trim()?.takeIf { label ->
            liveStatus != null &&
                !indicatorState.external &&
                !indicatorState.likelyOnline &&
                label.isNotBlank() &&
                label.lowercase() !in MembersCoarseAgentStatusWords
        }
    if (liveDetail != null) return indicatorState.label
    return membersLiveAgentStatusLabel(
        indicatorState = indicatorState,
        summaryStatus = summaryStatus,
        summaryActivity = summaryActivity,
        summaryActivityDetail = summaryActivityDetail,
        fallback = MembersUnknownAgentStatusLabel
    )
}

internal fun membersLiveAgentStatusLabel(
    indicatorState: RaftAgentStatusIndicatorState?,
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

/**
 * Web AgentMigrationSection `placesWithoutSource` for an agent on a Computer whose access was
 * revoked (botiverse/slock #13575): a Computer agent (`executionMode` `byoc`, or not sent) is
 * placed on the target, with nothing brought over. A cloud agent never is.
 */
internal fun membersMigrationPlacesWithoutSource(executionMode: String?, blocked: AgentBlockedState?): Boolean =
    blocked == AgentBlockedState.ComputerAccessRevoked &&
        (executionMode.isNullOrBlank() || executionMode.trim() == "byoc")

/**
 * What the agent page's error notice says, or null for none (web AgentDetailPanel `hasRuntimeError`
 * / `activityErrorText`). As on the web, the error is the live one while the agent is in error, else
 * the one the server kept (`lastRuntimeError`; a failed start leaves the agent offline): not signed
 * in, sign-in failed, the runtime missing, or any other kept message.
 */
internal fun MemberProfile.membersAgentRuntimeErrorDisplay(
    detail: AgentDetailSnapshot?,
    statusIndicatorState: RaftAgentStatusIndicatorState?,
    /** The viewer may see the agent's details (web `canViewPrivateAgentSurfaces`). */
    canViewDetails: Boolean = true
): AgentRuntimeErrorDisplay? {
    if (kind != MemberKind.Agent) return null
    val summary = detail?.summary
    // A kept BillingError (botiverse/slock #13435) follows the stored error, as on the web: Start /
    // Starting does not clear it, and the server clears it only once the provider answers again,
    // then sends `agent:updated`, which re-reads this profile. Its banner is catalog copy, so it
    // needs no message of its own.
    val keptBillingError =
        agentTypedRuntimeErrorKind(summary?.lastRuntimeErrorClass, summary?.lastRuntimeErrorReason) ==
            AgentRuntimeErrorKind.BillingExhausted
    // A held or attention agent's Error is its status words, not a runtime error (web reads the raw
    // activity here): only the kept error, as for an offline agent.
    val liveStatus =
        statusIndicatorState?.status.takeIf {
            statusIndicatorState?.blocked == null && statusIndicatorState?.attention == null
        }
    val runtimeError =
        when (liveStatus) {
            AgentStatus.Error -> summary?.activityDetail.orEmpty()
            // The kept error only while the agent is not running: the profile is not re-read when
            // it starts again, so a running agent must not keep showing an old failure.
            null, AgentStatus.Offline, AgentStatus.Disconnected ->
                summary?.lastRuntimeErrorMessage ?: if (keptBillingError) "" else return null
            else -> if (keptBillingError) summary?.lastRuntimeErrorMessage.orEmpty() else return null
        }
    // Web AgentDetailPanel `runtimeErrorCode` (#13985): from the same source as the text, the error
    // activity's code, else the kept error's.
    val errorCode =
        if (liveStatus == AgentStatus.Error) {
            statusIndicatorState?.errorCode ?: summary?.activityErrorCode
        } else {
            summary?.lastRuntimeErrorCode
        }
    val runtime = (summary?.runtime ?: statusIndicatorState?.runtime)?.trim()?.takeIf { it.isNotEmpty() }
    return agentRuntimeErrorDisplay(
        runtimeError = runtimeError,
        typedErrorClass = summary?.lastRuntimeErrorClass,
        typedErrorReason = summary?.lastRuntimeErrorReason,
        spawnFailure = summary?.spawnFailure,
        runtimeName = runtime?.let(::raftAgentRuntimeDisplayName),
        errorCode = errorCode,
        canViewDetails = canViewDetails,
    )
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
    statusIndicatorState: RaftAgentStatusIndicatorState?
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
    ).plus(
        // Web `diagnosticErrorMessage`: the kept runtime error the notice shows, with its typed diagnostic.
        listOfNotNull(
            summary?.lastRuntimeErrorMessage?.let { "lastRuntimeError=$it" },
            summary?.lastRuntimeErrorClass?.let { cls ->
                summary.lastRuntimeErrorReason?.let { reason -> "class=$cls reason=$reason" }
            },
        )
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
        avatar = AvatarReference.fromWireValue(avatarUrl),
        avatarLabel = display.toRaftAvatarLabel()
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

internal val MemberProfile.membersComputerName: String?
    get() =
        permissionBadges.firstNotNullOfOrNull { badge ->
            badge.removePrefix("computer:")
                .takeIf { badge.startsWith("computer:") && it.isNotBlank() }
        }

internal val MemberProfile.membersComputerId: String?
    get() =
        permissionBadges.firstNotNullOfOrNull { badge ->
            badge.removePrefix("computer-id:")
                .takeIf { badge.startsWith("computer-id:") && it.isNotBlank() }
        }

internal fun String?.orDash(): String = this?.takeIf { it.isNotBlank() } ?: "—"

private fun String?.orBadgeFallback(fallback: String?): String =
    this?.takeIf { it.isNotBlank() } ?: fallback?.takeIf { it.isNotBlank() } ?: "—"

/**
 * Web `reasoningEffortDisplayLabel` (utils/reasoningEffortOptions.ts, botiverse/slock #15691): how an
 * agent's reasoning effort reads wherever it is shown, the profile's Reasoning row and the Members
 * list's agent rows alike. Null when [runtime] takes no reasoning effort (no row, nothing in the
 * list); "Default" when no level is set; a shared level's label ([membersReasoningEffortLabel]); any
 * other level as the runtime reported it (Kimi Code's model-reported levels, as its picker lists them).
 */
internal fun membersReasoningEffortDisplayLabel(runtime: String, reasoningEffort: String?): StringDesc? {
    if (!membersRuntimeTakesReasoningEffort(runtime)) return null
    val effort = reasoningEffort?.takeIf { it.isNotBlank() } ?: return Res.string.member_label_default.desc()
    return membersReasoningEffortLabel(effort)?.desc() ?: effort.desc()
}

/**
 * Shared `REASONING_EFFORT_RUNTIMES` whole: [supportsRuntimeReasoningEffort] plus `kimi-sdk`, which the
 * app's pickers leave out (its levels follow the live model, task #275) but whose saved level reads
 * like any other wherever it is shown.
 */
private fun membersRuntimeTakesReasoningEffort(runtime: String): Boolean =
    supportsRuntimeReasoningEffort(runtime) || runtime == "kimi-sdk"

/**
 * Web `agentReasoningEffortFact` (features/members/memberEntryFacts.ts): an agent's reasoning effort
 * as its profile's Reasoning row shows it ([membersReasoningEffortDisplayLabel] over
 * [MemberProfile.agentReasoning]). Null for an App's identity, a runtime that takes no effort, or a
 * row that carries no agent runtime facts.
 */
internal fun MemberProfile.membersAgentReasoningEffortLabel(): StringDesc? {
    if (kind != MemberKind.Agent || !managedByAppInstallId.isNullOrBlank()) return null
    val setting = agentReasoning ?: return null
    return membersReasoningEffortDisplayLabel(setting.runtime, setting.reasoningEffort)
}

/**
 * The Members agent row's Reasoning part, "Reasoning: High" (web `members.page.reasoningEffortDescription`,
 * which describes the level on the web's row): a level alone says nothing in the row's line. Null
 * where [membersAgentReasoningEffortLabel] is.
 */
internal fun MemberProfile.membersAgentReasoningEffortLine(): StringDesc? =
    membersAgentReasoningEffortLabel()?.let { Res.string.members_agent_reasoning_effort.format(it) }

/**
 * Web `REASONING_EFFORT_LABEL_ID` (packages/web/src/utils/reasoningEffortOptions.ts): the shared
 * effort id's display label (`xhigh` -> "Extra High"); null for an id the catalog doesn't know,
 * which [membersReasoningEffortDisplayLabel] shows as-is.
 */
internal fun membersReasoningEffortLabel(effort: String): StringResource? =
    when (effort) {
        "off" -> Res.string.agent_create_reasoning_off
        "minimal" -> Res.string.agent_create_reasoning_minimal
        "low" -> Res.string.agent_create_reasoning_low
        "medium" -> Res.string.agent_create_reasoning_medium
        "high" -> Res.string.agent_create_reasoning_high
        "xhigh" -> Res.string.agent_create_reasoning_extra_high
        "max" -> Res.string.agent_create_reasoning_max
        "ultra" -> Res.string.agent_create_reasoning_ultra
        else -> null
    }

internal fun String.toMembersDisplayLabel(): String =
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

/** Web SubagentBadge: a sub-agent's tool call, status or system row carries "Subagent" (and its type). */
internal fun AgentActivityEntry.membersActivityHasSubagentBadge(): Boolean =
    isSubagent && (type == "tool_start" || type == "status" || type == "system")

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
        avatar = AvatarReference.fromWireValue(avatarUrl),
        avatarLabel = label.toRaftAvatarLabel(),
        presence = Presence.Offline,
        agentStatus = null,
        // Detail summaries carry the runtime sentinel only (no projected boolean).
        isExternal = isExternalAgentRuntime(summary?.runtime),
        managedByAppInstallId = summary?.managedByAppInstallId?.takeIf { it.isNotBlank() },
        managedByAppClientId = summary?.managedByAppClientId?.takeIf { it.isNotBlank() },
        managedByAppName = summary?.managedByAppName?.takeIf { it.isNotBlank() },
        managedByAppLogoUrl = summary?.managedByAppLogoUrl?.takeIf { it.isNotBlank() },
        managedByAppBotStatusText = summary?.managedByAppBotStatusText?.takeIf { it.isNotBlank() },
        assistantOwnerUserId = summary?.assistantOwnerUserId?.takeIf { it.isNotBlank() },
        assistantPausedAt = summary?.assistantPausedAt?.takeIf { it.isNotBlank() },
        assistantPausedByName = summary?.assistantPausedByName?.takeIf { it.isNotBlank() },
        profileProjection = summary?.profileProjection?.takeIf { it.isNotBlank() },
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
            avatarUrl = member.avatar?.wireValue ?: summary?.avatarUrl,
            managedByAppInstallId = member.managedByAppInstallId ?: summary?.managedByAppInstallId,
            managedByAppClientId = member.managedByAppClientId ?: summary?.managedByAppClientId,
            managedByAppName = member.managedByAppName ?: summary?.managedByAppName,
            managedByAppLogoUrl = member.managedByAppLogoUrl ?: summary?.managedByAppLogoUrl,
            managedByAppBotStatusText = member.managedByAppBotStatusText ?: summary?.managedByAppBotStatusText,
            assistantOwnerUserId = member.assistantOwnerUserId ?: summary?.assistantOwnerUserId,
            assistantPausedAt = if (summary != null) summary.assistantPausedAt else member.assistantPausedAt,
            assistantPausedByName = if (summary != null) summary.assistantPausedByName else member.assistantPausedByName,
            profileProjection = member.profileProjection ?: summary?.profileProjection,
        )
    return copy(summary = nextSummary)
}

/** Web `GRANT_SOURCE_LABEL`; an unknown source shows no line. */
internal fun membersAgentAppGrantSourceLabel(source: String): StringResource? =
    when (source) {
        "person" -> Res.string.member_app_grant_source_person
        "agent_login" -> Res.string.member_app_grant_source_agent_login
        "app_request" -> Res.string.member_app_grant_source_app_request
        "legacy_import" -> Res.string.member_app_grant_source_legacy_import
        else -> null
    }
