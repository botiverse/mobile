package build.raft.app

import build.raft.app.resources.*
import build.raft.app.format.RaftFormat
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import build.raft.app.data.ComputerSizeStatus
import build.raft.app.data.RuntimeAccountUsageLoad
import build.raft.app.data.RuntimeAccountUsageRead
import build.raft.app.data.RuntimeQuotaLinePart
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.avatarWireValueForAvatarSlot
import build.raft.app.data.runtimeCatalogDisplayName
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull
import build.raft.designsystem.components.RaftAvatarGroupCount
import build.raft.designsystem.components.RaftAvatarKind
import build.raft.designsystem.components.RaftAvatarSize
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftChoiceList
import build.raft.designsystem.components.RaftChoiceListItem
import build.raft.designsystem.components.RaftGlyph
import build.raft.designsystem.components.RaftGlyphIcon
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftListRow
import build.raft.designsystem.components.RaftListRowText
import build.raft.designsystem.components.RaftPreviewCard
import build.raft.designsystem.components.raftClickable
import build.raft.designsystem.components.rememberRaftPreviewCardState
import build.raft.designsystem.components.RaftSeparator
import build.raft.designsystem.components.RaftSheet
import build.raft.designsystem.components.RaftSheetSurface
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.LocalRaftSurfaceColor
import build.raft.designsystem.theme.RaftTheme

/*
 * The Move dialog's target list (web TargetComputerList / targetComputerInfo /
 * TargetComputerDetails, botiverse/slock 424c5203 #11038; raft-design #767 and ChoiceList): an
 * always-open radio list of the computers, each with its quota line and its own line (agents,
 * memory used, disk used), an info button opening its details, and a search field above
 * 5 options.
 */

/** Web `liveDiskStatus`: the disk report of an online computer only (an offline one's is old). */
internal fun AgentMigrationComputer.liveDiskStatus(): ComputerSizeStatus? = diskStatus.takeIf { online }

/** Web `liveMemoryStatus`: the memory report on the same terms as the disk. */
internal fun AgentMigrationComputer.liveMemoryStatus(): ComputerSizeStatus? = memoryStatus.takeIf { online }

/**
 * Who is on each computer, for the details' Agents section (artin 11-14): the server's live
 * agents per computer ([agentMigrationAgentsByMachine], the Computers page's grouping), their live
 * status, and how to open one's profile. Null: the section says only how many.
 */
internal class AgentMigrationComputerAgents(
    val byMachine: Map<String, List<MemberProfile>>,
    /** The server the dialog is on: where an agent's profile card reads it from. */
    val serverId: String?,
    val agentStatusResolver: RaftAgentStatusResolver,
    val onOpenProfile: (MemberProfile) -> Unit
)

/**
 * The live (not deleted) agents of [members] per computer, in the members' order, as the
 * Computers page groups them ([raftComputerAgentsByMachine]); an agent on no computer is left out.
 */
internal fun agentMigrationAgentsByMachine(members: List<MemberProfile>): Map<String, List<MemberProfile>> {
    val byMachine = LinkedHashMap<String, MutableList<MemberProfile>>()
    val seen = HashSet<String>()
    members.forEach { member ->
        if (member.kind != MemberKind.Agent) return@forEach
        if (!member.agentDeletedAt.isNullOrBlank() || member.membershipStatus == "deleted") return@forEach
        val machineId = member.raftComputerMachineId() ?: return@forEach
        if (!seen.add(member.id.value)) return@forEach
        byMachine.getOrPut(machineId) { mutableListOf() } += member
    }
    return byMachine
}

/**
 * Web `resourceUsedPercent` (botiverse/slock #11855): how much of a disk or memory is used, in
 * whole percent, JavaScript rounding (floor(x + 0.5)), as the Computers rack shows it.
 */
internal fun agentMigrationResourceUsedPercent(status: ComputerSizeStatus): Int =
    kotlin.math.floor((1.0 - status.availableBytes / status.totalBytes) * 100.0 + 0.5).toInt()

/** The words of a computer's own line ([agentMigrationTargetInfoLine]). */
internal class AgentMigrationTargetInfoCopy(
    val offline: String,
    /** "RAM 63% used": ("63%"). */
    val memoryUsed: (percent: String) -> String,
    /** "Disk 76% used": ("76%"). */
    val diskUsed: (percent: String) -> String,
    val agents: (count: Int) -> String
)

/**
 * Web `targetComputerInfoLine` (botiverse/slock #11086, #11855): "Offline" first for an offline
 * computer, then its agents (what people compare by, so never the part a narrow row cuts off),
 * how much of its memory is used (older Computers report none: left out) and of its disk, joined
 * by " · " ("3 agents · RAM 63% used · Disk 76% used"): what is used, as the Computers rack says
 * it (stdrc 10-29); the details have the free sizes. Memory and disk only while online.
 * Knowing nothing, there is no line.
 */
internal fun agentMigrationTargetInfoLine(computer: AgentMigrationComputer, copy: AgentMigrationTargetInfoCopy): String? {
    val parts = mutableListOf<String>()
    if (computer.offline) parts += copy.offline
    computer.agentCount?.let { parts += copy.agents(it) }
    computer.liveMemoryStatus()?.let { parts += copy.memoryUsed("${agentMigrationResourceUsedPercent(it)}%") }
    computer.liveDiskStatus()?.let { parts += copy.diskUsed("${agentMigrationResourceUsedPercent(it)}%") }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/** The unit of a coarse offline duration (web `offlineDuration`). */
internal enum class AgentMigrationDurationUnit { Minutes, Hours, Days }

/**
 * Web `offlineDuration`: how long a computer has been offline, from its last heartbeat,
 * minutes under an hour (at least 1), hours under a day, then days. Null with no heartbeat.
 */
internal fun agentMigrationOfflineDuration(lastHeartbeat: String?, nowMillis: Long): Pair<AgentMigrationDurationUnit, Int>? {
    val since = lastHeartbeat?.let { parseRaftTimestampEpochMillisOrNull(it) } ?: return null
    val elapsed = (nowMillis - since).coerceAtLeast(0L)
    val minute = 60_000L
    val hour = 60 * minute
    val day = 24 * hour
    return when {
        elapsed < hour -> AgentMigrationDurationUnit.Minutes to maxOf(1, kotlin.math.round(elapsed.toDouble() / minute).toInt())
        elapsed < day -> AgentMigrationDurationUnit.Hours to kotlin.math.round(elapsed.toDouble() / hour).toInt()
        else -> AgentMigrationDurationUnit.Days to kotlin.math.round(elapsed.toDouble() / day).toInt()
    }
}

@Composable
private fun rememberAgentMigrationTargetInfoCopy(): AgentMigrationTargetInfoCopy {
    val offline = stringResource(Res.string.agent_migration_target_offline)
    val memory = stringResource(Res.string.agent_migration_target_memory_used, "\u0001")
    val disk = stringResource(Res.string.agent_migration_target_disk_used, "\u0001")
    val none = stringResource(Res.string.agent_migration_target_agents_none)
    val one = stringResource(Res.string.agent_migration_target_agents_one, "\u0001")
    val other = stringResource(Res.string.agent_migration_target_agents_other, "\u0001")
    return remember(offline, memory, disk, none, one, other) {
        AgentMigrationTargetInfoCopy(
            offline = offline,
            memoryUsed = { memory.replace("\u0001", it) },
            diskUsed = { disk.replace("\u0001", it) },
            agents = { count ->
                when (count) {
                    0 -> none
                    1 -> one.replace("\u0001", "1")
                    else -> other.replace("\u0001", count.toString())
                }
            }
        )
    }
}

/** The quota line with a used-up window in `warning-strong` (raft-ui ChoiceList > Content). */
@Composable
private fun agentMigrationQuotaLine(parts: List<RuntimeQuotaLinePart>): AnnotatedString {
    val warning = RaftTheme.colors.warningStrong
    return buildAnnotatedString {
        parts.forEachIndexed { index, part ->
            if (index > 0) append(" · ")
            if (part.usedUp) withStyle(SpanStyle(color = warning)) { append(part.text) } else append(part.text)
        }
    }
}

/**
 * The target list: the [targets] (and the cloud when [hostedOption] is given) as a
 * [RaftChoiceList], each computer with its quota line for [agentRuntime] (where [usage] lets the
 * viewer read it) and its own line, and an info button opening its details in a sheet.
 */
@Composable
internal fun AgentMigrationTargetList(
    targets: List<AgentMigrationComputer>,
    hostedOption: String?,
    selected: String?,
    enabled: Boolean,
    agentRuntime: String?,
    usage: MembersRuntimeAccountUsageAccess?,
    onSelect: (String) -> Unit,
    initialQuery: String = "",
    computerAgents: AgentMigrationComputerAgents? = null
) {
    val requests =
        remember(targets, agentRuntime) {
            targets.map { computer ->
                RuntimeQuotaRequest(
                    optionId = computer.id,
                    machineId = computer.id,
                    runtimeId = agentRuntime,
                    machineFactsKnown = true,
                    attachedByCurrentUser = computer.attachedByCurrentUser,
                    creatorId = computer.creatorUserId
                )
            }
        }
    val quota = rememberRuntimeQuotaLoads(usage, requests)
    val quotaLines = rememberRuntimeQuotaLineParts(quota)
    val infoCopy = rememberAgentMigrationTargetInfoCopy()
    val items =
        targets.map { computer ->
            RaftChoiceListItem(
                value = computer.id,
                label = computer.name,
                // Web: an offline computer can't receive the agent, so it shows but can't be chosen.
                enabled = computer.online,
                descriptions =
                    listOfNotNull(
                        quotaLines[computer.id]?.let { agentMigrationQuotaLine(it) },
                        agentMigrationTargetInfoLine(computer, infoCopy)?.let(::AnnotatedString)
                    )
            )
        } + listOfNotNull(hostedOption?.let { RaftChoiceListItem(value = HOSTED_TARGET_CHOICE, label = it) })
    var details by remember { mutableStateOf<String?>(null) }
    RaftChoiceList(
        items = items,
        value = selected,
        onValueChange = onSelect,
        searchPlaceholder = stringResource(Res.string.agent_migration_target_search),
        clearSearchLabel = stringResource(Res.string.agent_migration_target_clear_search),
        noMatchLabel = stringResource(Res.string.agent_migration_target_no_matches),
        emptyLabel = stringResource(Res.string.agent_migration_detail_no_other_attached_computer),
        enabled = enabled,
        initialQuery = initialQuery,
        action = { item ->
            // The cloud has no details.
            if (item.value != HOSTED_TARGET_CHOICE) {
                RaftIconButton(
                    onClick = { details = item.value },
                    tooltip = stringResource(Res.string.agent_migration_target_details, item.label),
                    variant = RaftButtonVariant.Ghost
                ) {
                    RaftGlyphIcon(RaftGlyph.Info, size = 18.dp)
                }
            }
        }
    )
    val timeContext = currentRaftTimeContextProvider?.current
    targets.firstOrNull { it.id == details }?.let { computer ->
        val state = agentMigrationTargetUsageState(computer.id, quota)
        RaftSheet(
            onDismissRequest = { details = null },
            title = computer.name,
            content = { AgentMigrationTargetDetailsContent(computer, agentRuntime, state, timeContext, computerAgents) }
        )
    }
}

/** The usage the details show: null when the viewer may not read it there (no section). */
private fun agentMigrationTargetUsageState(machineId: String, quota: RuntimeQuotaLoads): ComputerRuntimeUsageSheetState? {
    if (machineId !in quota.keys) return null
    val load = quota.loads[machineId]
    if (load is RuntimeAccountUsageLoad.Forbidden) return null
    return ComputerRuntimeUsageSheetState(load = load, loading = load == null, refresh = ComputerRuntimeUsageRefreshStatus.Idle, cooldownLeftMillis = 0L)
}

/** The details sheet without its window, for screenshots. */
@Composable
internal fun AgentMigrationTargetDetailsSurface(
    computer: AgentMigrationComputer,
    agentRuntime: String?,
    usage: ComputerRuntimeUsageSheetState?,
    timeContext: RaftTimeContext?,
    modifier: Modifier = Modifier,
    computerAgents: AgentMigrationComputerAgents? = null
) {
    RaftSheetSurface(
        title = computer.name,
        modifier = modifier,
        content = { AgentMigrationTargetDetailsContent(computer, agentRuntime, usage, timeContext, computerAgents) }
    )
}

/**
 * Web TargetComputerDetails: the computer page's usage card for the agent's runtime (when the
 * viewer may read it), then Agents (left out while the count is unknown), Memory and Disk, each "free of total", "Not reported yet", or for
 * an offline computer how long it has been offline. An offline computer's usage says when it
 * last reported.
 */
@Composable
private fun AgentMigrationTargetDetailsContent(
    computer: AgentMigrationComputer,
    agentRuntime: String?,
    usage: ComputerRuntimeUsageSheetState?,
    timeContext: RaftTimeContext?,
    computerAgents: AgentMigrationComputerAgents?
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (usage != null && agentRuntime != null) {
            RaftText(
                stringResource(Res.string.settings_computers_runtime_usage_title, runtimeCatalogDisplayName(agentRuntime)),
                style = typography.footnote.copy(fontWeight = FontWeight.SemiBold),
                color = colors.foregroundMuted
            )
            ComputerRuntimeUsageContents(usage, timeContext)
            val available = (usage.load as? RuntimeAccountUsageLoad.Loaded)?.read as? RuntimeAccountUsageRead.Available
            if (computer.offline && available != null) {
                val reported =
                    available.snapshot.collectedAt
                        ?.let { raftRelativeTimestampLabel(it, timeContext, clampFuture = true) }
                        ?.takeIf { it.isNotEmpty() }
                        ?: stringResource(Res.string.settings_computers_runtime_usage_recently)
                RaftText(
                    stringResource(Res.string.agent_migration_target_last_reported, reported),
                    style = typography.caption,
                    color = colors.foregroundHint
                )
            }
            // The usage card closes a snapshot with its own separator.
            if (available == null) RaftSeparator()
        }
        val offlineFor =
            if (computer.offline) {
                val now = timeContext?.nowEpochMillis ?: build.raft.app.data.defaultNowMillis()
                agentMigrationOfflineDuration(computer.lastHeartbeat, now)?.let { (unit, count) -> agentMigrationDurationText(unit, count) }
            } else {
                null
            }
        // In the row's order: agents, memory, disk (web #11086). Opened for one row, nothing is cut off.
        val infoCopy = rememberAgentMigrationTargetInfoCopy()
        val agentsTitle = stringResource(Res.string.agent_migration_target_agents_title)
        computer.agentCount?.let { count ->
            // Which agents, not only how many (artin 11-14): their faces on one line after the count.
            val agents = computerAgents?.byMachine?.get(computer.id).orEmpty()
            AgentMigrationSizeSection(title = agentsTitle, text = infoCopy.agents(count)) {
                if (computerAgents != null && agents.isNotEmpty()) AgentMigrationAgentAvatarRow(agents, computerAgents, agentsTitle)
            }
        }
        AgentMigrationSizeSection(
            title = stringResource(Res.string.agent_migration_target_memory),
            text =
                agentMigrationSizeText(
                    computer.liveMemoryStatus(),
                    computer.offline,
                    offlineFor,
                    SizeWords(
                        Res.string.agent_migration_target_memory_unknown,
                        Res.string.agent_migration_target_memory_offline,
                        Res.string.agent_migration_target_memory_offline_for
                    )
                )
        )
        AgentMigrationSizeSection(
            title = stringResource(Res.string.agent_migration_target_disk),
            text =
                agentMigrationSizeText(
                    computer.liveDiskStatus(),
                    computer.offline,
                    offlineFor,
                    SizeWords(
                        Res.string.agent_migration_target_disk_unknown,
                        Res.string.agent_migration_target_disk_offline,
                        Res.string.agent_migration_target_disk_offline_for
                    )
                )
        )
    }
}

private class SizeWords(
    val unknown: org.jetbrains.compose.resources.StringResource,
    val offline: org.jetbrains.compose.resources.StringResource,
    val offlineFor: org.jetbrains.compose.resources.StringResource
)

@Composable
private fun agentMigrationSizeText(status: ComputerSizeStatus?, offline: Boolean, offlineFor: String?, words: SizeWords): String =
    when {
        // What is used, as the row says it, then free of total (web #11855 `usedDetail`).
        status != null ->
            stringResource(
                Res.string.agent_migration_target_used_detail,
                "${agentMigrationResourceUsedPercent(status)}%",
                RaftFormat.bytes(status.availableBytes),
                RaftFormat.bytes(status.totalBytes)
            )
        offline && offlineFor != null -> stringResource(words.offlineFor, offlineFor)
        offline -> stringResource(words.offline)
        else -> stringResource(words.unknown)
    }

@Composable
private fun agentMigrationDurationText(unit: AgentMigrationDurationUnit, count: Int): String {
    val one = count == 1
    return when (unit) {
        AgentMigrationDurationUnit.Minutes ->
            stringResource(if (one) Res.string.agent_migration_target_duration_minutes_one else Res.string.agent_migration_target_duration_minutes_other, count)
        AgentMigrationDurationUnit.Hours ->
            stringResource(if (one) Res.string.agent_migration_target_duration_hours_one else Res.string.agent_migration_target_duration_hours_other, count)
        AgentMigrationDurationUnit.Days ->
            stringResource(if (one) Res.string.agent_migration_target_duration_days_one else Res.string.agent_migration_target_duration_days_other, count)
    }
}

@Composable
private fun AgentMigrationSizeSection(title: String, text: String, extra: @Composable () -> Unit = {}) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        RaftText(title, style = RaftTheme.typography.callout.copy(fontWeight = FontWeight.Bold), color = RaftTheme.colors.foreground)
        RaftText(text, style = RaftTheme.typography.footnote, color = RaftTheme.colors.foregroundMuted)
        extra()
    }
}

private val AgentMigrationAgentAvatarSize = RaftAvatarSize.Md
private val AgentMigrationAgentAvatarGap = 8.dp

/**
 * The computer's agents on one line: as many faces as fit, each with its live light; a tap opens
 * the agent's profile card (the @mention card, pinned) with View Profile. When they don't all fit,
 * the last place is a "+N" chip opening the whole list.
 */
@Composable
private fun AgentMigrationAgentAvatarRow(agents: List<MemberProfile>, source: AgentMigrationComputerAgents, title: String) {
    var showAll by remember { mutableStateOf(false) }
    // Room for the lights, which hang past the faces' right and bottom edges.
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 6.dp, end = 4.dp, bottom = 4.dp)) {
        val slot = AgentMigrationAgentAvatarSize.edge
        val gap = AgentMigrationAgentAvatarGap
        val fit = maxOf(1, ((maxWidth + gap) / (slot + gap)).toInt())
        val shown = if (agents.size <= fit) agents else agents.take(fit - 1)
        val more = agents.size - shown.size
        Row(horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
            shown.forEach { agent -> key(agent.id.value) { AgentMigrationAgentAvatar(agent, source) } }
            if (more > 0) {
                val label = stringResource(Res.string.agent_migration_target_agents_show_all, agents.size)
                RaftAvatarGroupCount(
                    text = "+$more",
                    size = AgentMigrationAgentAvatarSize,
                    modifier =
                        Modifier
                            .raftClickable(onClick = { showAll = true }, onClickLabel = label, shape = RoundedCornerShape(4.dp))
                            .semantics { contentDescription = label }
                )
            }
        }
    }
    if (showAll) {
        RaftSheet(
            onDismissRequest = { showAll = false },
            title = title,
            content = {
                Column(Modifier.fillMaxWidth()) {
                    agents.forEach { agent ->
                        key(agent.id.value) {
                            AgentMigrationAgentListRow(agent, source) {
                                showAll = false
                                source.onOpenProfile(agent)
                            }
                        }
                    }
                }
            }
        )
    }
}

private fun MemberProfile.agentMigrationName(): String = displayName.trim().ifBlank { handle }

@Composable
private fun AgentMigrationAgentFace(agent: MemberProfile, status: RaftAgentStatusIndicatorState?) {
    val size = AgentMigrationAgentAvatarSize
    RaftAvatarWithStatus(statusColor = status?.color, avatarSize = size.edge, cutoutColor = LocalRaftSurfaceColor.current) {
        RaftAppAvatarSticker(
            kind = RaftAvatarKind.Agent,
            avatarWireValue = agent.avatarWireValueForAvatarSlot(),
            label = agent.agentMigrationName(),
            size = size,
            cacheSubject = raftMemberAvatarCacheSubject(MemberKind.Agent, agent.id.value)
        )
    }
}

/** One face of the row: a tap (any pointer) pins the agent's profile card; View Profile opens the profile. */
@Composable
private fun AgentMigrationAgentAvatar(agent: MemberProfile, source: AgentMigrationComputerAgents) {
    val status = rememberRaftAgentStatusIndicatorState(source.agentStatusResolver, agent.id.value)
    val state = rememberRaftPreviewCardState()
    val name = agent.agentMigrationName()
    RaftPreviewCard(
        state = state,
        pinOnClick = true,
        card = {
            RaftProfilePreviewCardContent(
                model = remember(agent, status, source.serverId) { raftProfilePreviewCardModel(agent, status, viewerServerId = source.serverId) },
                onViewProfile = {
                    state.dismiss()
                    source.onOpenProfile(agent)
                }
            )
        }
    ) {
        // A screen reader's activation has no pointer: it pins the card the same way.
        Row(
            Modifier
                .raftClickable(onClick = { state.pin() }, onClickLabel = name, shape = RoundedCornerShape(4.dp))
                .semantics { contentDescription = name }
        ) {
            AgentMigrationAgentFace(agent, status)
        }
    }
}

/** A row of the whole list: face, name and live status; a tap opens the profile. */
@Composable
private fun AgentMigrationAgentListRow(agent: MemberProfile, source: AgentMigrationComputerAgents, onClick: () -> Unit) {
    val status = rememberRaftAgentStatusIndicatorState(source.agentStatusResolver, agent.id.value)
    RaftListRow(
        title = agent.agentMigrationName(),
        onClick = onClick,
        subtitle = status?.localizedMemberStatusLabel(),
        text = RaftListRowText.Member,
        leading = { AgentMigrationAgentFace(agent, status) }
    )
}
