package build.raft.app

import build.raft.app.format.RaftFormat
import build.raft.app.data.COMPUTER_ACTION_DELETE
import build.raft.app.data.PermissionBlocker
import build.raft.app.data.ScreenError
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.SettingsComputerCreatorSnapshot
import build.raft.app.data.SettingsComputerDetailSnapshot
import build.raft.app.data.SettingsComputerSizeStatusSnapshot
import build.raft.app.data.SettingsComputerSnapshot
import build.raft.app.data.SettingsComputersSnapshot
import build.raft.app.data.SettingsSectionErrorSnapshot
import build.raft.app.data.SharedSettingsActionState
import build.raft.app.data.SharedSettingsDestination
import build.raft.app.data.SharedSettingsState
import build.raft.app.model.ComputerRestartDone
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.data.ServerRuntimeCatalog
import build.raft.app.data.RuntimeAccountUsageAccount
import build.raft.app.data.RuntimeAccountUsageHealth
import build.raft.app.data.RuntimeAccountUsageLoad
import build.raft.app.data.RuntimeAccountUsageProvider
import build.raft.app.data.RuntimeAccountUsageRead
import build.raft.app.data.RuntimeAccountUsageWindowStatus
import build.raft.app.model.avatarWireValueForAvatarSlot
import build.raft.app.model.RealtimeEvent
import build.raft.app.model.getModelLabel
import build.raft.app.model.RealtimeRefreshRequired
import build.raft.app.model.RealtimeRefreshScope

/*
 * Locale-free state mapping for the native Computers settings pages
 * ([RaftSettingsComputersScreen], [RaftSettingsComputerDetailScreen]). Everything here is a pure
 * function of the shared settings store state, so the list/detail/error decisions are unit tested
 * without composing UI; the screens only resolve the string resources.
 */

/** What runs on the machine: the Raft Computer app, or the bare `raft-daemon`. */
internal enum class RaftComputerKind { Computer, Daemon }

/** One computer as the list and the detail header show it. */
internal data class RaftComputerRowModel(
    val id: String,
    val name: String,
    val online: Boolean,
    val kind: RaftComputerKind,
    /** The running Computer / daemon version, or null when the machine never reported one. */
    val version: String?,
    /**
     * The server's policy-aware `computerUpgradeAvailable` decision for a managed Computer (web
     * `shouldShowComputerUpgradeIndicator`); a bare daemon never shows an update.
     */
    val updateAvailable: Boolean,
    /** `hostname · OS`, or null when neither is known. */
    val hostDetail: String?,
    val description: String?,
    val runtimes: List<String>,
    /** Last heartbeat (ISO timestamp) for an offline machine; null while online. */
    val lastSeenAt: String?,
    /** The OS as one fact (web `computerOsName`): the Linux release, else macOS / Windows; null otherwise. */
    val osName: String? = null,
    /** What follows the version on the card's facts line (web `ComputerRowVersionFact`); null for nothing. */
    val versionNotice: RaftComputerVersionNotice? = null,
    /** The light on the device icon's corner (web `ComputerRowStatusDot`). */
    val dot: RaftComputerDot = if (online) RaftComputerDot.Online else RaftComputerDot.Offline,
    /** Online with less than 10% and under 20 GiB free (shared `isMachineDiskLow`): the "Low disk" badge. */
    val diskLow: Boolean = false,
    /** Web `liveDiskStatus` as the card's meter: online and reported only; warns when [diskLow]. */
    val disk: RaftComputerResourceMeter? = null,
    /** Web `liveMemoryStatus` as the card's meter: online and reported only; never warns. */
    val memory: RaftComputerResourceMeter? = null,
    /** The viewer may read this computer's runtime usage (the detail page's rule). */
    val canViewRuntimeUsage: Boolean = false,
)

/**
 * A disk or memory on a card (web `RackResource`): "62% used" over a bar. A Progress, not a Meter
 * (web: a disk or memory is not an allowance that resets): the disk warns by the low-disk rule,
 * memory never does.
 */
internal data class RaftComputerResourceMeter(val percentUsed: Int, val warn: Boolean)

/** Web `resourceUsedPercent` of a reported size; null when there is no readable report. */
internal fun SettingsComputerSizeStatusSnapshot.toRaftComputerResourceMeter(warn: Boolean): RaftComputerResourceMeter? {
    if (totalBytes <= 0.0) return null
    val percent = kotlin.math.floor((1.0 - availableBytes / totalBytes) * 100.0 + 0.5).toInt().coerceIn(0, 100)
    return RaftComputerResourceMeter(percentUsed = percent, warn = warn)
}

/** Web `ComputerRowVersionFact`: the version floor's chip, else the newer version to point at. */
internal sealed interface RaftComputerVersionNotice {
    /** `→ v{version}`. */
    data class Available(val version: String) : RaftComputerVersionNotice

    /** "Needs upgrade" (below the floor, web can upgrade it) or "Needs reinstall" (too old for that). */
    data class BelowFloor(val reinstall: Boolean) : RaftComputerVersionNotice
}

/** Web `getComputerRowDotStatus`: pink while an upgrade waits, else the computer's presence. */
internal enum class RaftComputerDot { Online, Offline, Upgrade }

/** Why a runtime the computer could run isn't detected on it (web `runtimeAvailabilitySuffix`). */
internal enum class RaftComputerRuntimeAvailability {
    Detected,

    /** The computer looks for a binary and didn't find it. */
    NotInstalled,

    /** A runtime with no binary (built into the Computer): the computer's version predates it. */
    UpdateComputer,
}

internal data class RaftComputerRuntimeChip(
    val id: String,
    val name: String,
    val availability: RaftComputerRuntimeAvailability,
    /** Named with " (experimental)" in the chip (web #11771 / #11822: a separate badge in a row of chips reads as one more runtime). */
    val experimental: Boolean = false,
)

/**
 * Web MachineDetailPanel "Detected Runtimes": every runtime a computer can run
 * (`getMachineRuntimeDisplayOptions`: supported, not deprecated), in catalog order, each
 * marked detected or why not, so the row shows what is missing as well as what is there.
 * The runtimes are [catalog]'s: the computer's own server's ([rememberServerRuntimeCatalog]).
 */
internal fun raftComputerRuntimeChips(
    detected: List<String>,
    catalog: ServerRuntimeCatalog,
): List<RaftComputerRuntimeChip> =
    catalog.runtimes.filter { it.supported && !it.deprecated }.map { runtime ->
        RaftComputerRuntimeChip(
            id = runtime.id,
            name = runtime.displayName,
            availability =
                when {
                    runtime.id in detected -> RaftComputerRuntimeAvailability.Detected
                    runtime.binary.isEmpty() -> RaftComputerRuntimeAvailability.UpdateComputer
                    else -> RaftComputerRuntimeAvailability.NotInstalled
                },
            experimental = runtime.experimental,
        )
    }

internal sealed interface RaftComputersListModel {
    data object Loading : RaftComputersListModel

    data class Failed(val failure: RaftComputersFailure) : RaftComputersListModel

    data class Empty(val canAddComputer: Boolean) : RaftComputersListModel

    data class Content(
        val rows: List<RaftComputerRowModel>,
        val onlineCount: Int,
        val canAddComputer: Boolean,
    ) : RaftComputersListModel
}

/** A load that produced no page: an error the user can retry, or a permission wall. */
internal data class RaftComputersFailure(
    val noAccess: Boolean,
    val retryable: Boolean,
    val code: String,
    val screenError: ScreenError? = null,
    val permissionBlocker: PermissionBlocker? = null,
)

internal fun raftComputersListModel(state: ScreenLoadState<SettingsComputersSnapshot>): RaftComputersListModel =
    when (state) {
        ScreenLoadState.Loading -> RaftComputersListModel.Loading
        is ScreenLoadState.Error -> RaftComputersListModel.Failed(state.error.toComputersFailure())
        is ScreenLoadState.NoPermission -> RaftComputersListModel.Failed(state.blocker.toComputersFailure())
        // The repository answers Empty when no server is selected or the server has no machine
        // list; neither offers an add-computer entry, which needs a loaded server snapshot.
        is ScreenLoadState.Empty -> RaftComputersListModel.Empty(canAddComputer = false)
        is ScreenLoadState.Content -> {
            val snapshot = state.data
            if (snapshot.computers.isEmpty()) {
                RaftComputersListModel.Empty(canAddComputer = snapshot.canAddComputer)
            } else {
                val rows = snapshot.computers.map { it.toRaftComputerRow() }
                RaftComputersListModel.Content(
                    rows = rows,
                    onlineCount = rows.count { it.online },
                    canAddComputer = snapshot.canAddComputer,
                )
            }
        }
    }

/**
 * Web `getComputerAvailableVersion`: the newer version a Computer row points at (`→ v1.0.53`), or
 * null. The version comparison, plus the server's eligible verdict for a Computer whose version
 * the app has not received.
 */
internal fun raftComputerAvailableVersion(computer: SettingsComputerSnapshot): String? {
    if (!computer.isComputer) return null
    val fact = raftComputerVersionFact(computer)
    if (fact is RaftComputerVersionFact.Outdated) return fact.availableVersion
    val policy = computer.broadcastPolicy
    if (fact == RaftComputerVersionFact.Unknown && computer.upgradeAvailable && policy?.eligible == true) {
        return policy.targetVersion?.trim()?.takeIf { it.isNotBlank() }
    }
    return null
}

/**
 * Web `computerOsName`: the reported release ("Ubuntu 24.04", Linux only), else "macOS" / "Windows"
 * from the platform (`darwin arm64`, `win32 x64`); null for anything else, so the line shows no OS.
 */
internal fun raftComputerOsName(osRelease: String?, platform: String?): String? {
    osRelease?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
    val os = platform?.trim()?.lowercase().orEmpty()
    return when {
        os.startsWith("darwin") -> "macOS"
        os.startsWith("win") -> "Windows"
        else -> null
    }
}

/** Shared `MACHINE_DISK_LOW_FREE_PERCENT`. */
internal const val RAFT_COMPUTER_DISK_LOW_FREE_PERCENT = 10.0

/** Shared `MACHINE_DISK_LOW_MAX_AVAILABLE_BYTES` (20 GiB). */
internal const val RAFT_COMPUTER_DISK_LOW_MAX_AVAILABLE_BYTES = 20.0 * 1024 * 1024 * 1024

/** Web `machineDiskLowPresentation != null`: online, and under both the percent and the size bound. */
internal fun raftComputerDiskLow(computer: SettingsComputerSnapshot): Boolean {
    val disk = computer.diskStatus ?: return false
    if (!computer.online || disk.totalBytes <= 0.0) return false
    return disk.availableBytes < RAFT_COMPUTER_DISK_LOW_MAX_AVAILABLE_BYTES &&
        disk.availableBytes / disk.totalBytes * 100.0 < RAFT_COMPUTER_DISK_LOW_FREE_PERCENT
}

internal fun SettingsComputerSnapshot.toRaftComputerRow(): RaftComputerRowModel {
    val kind = if (isComputer) RaftComputerKind.Computer else RaftComputerKind.Daemon
    val version =
        when (kind) {
            RaftComputerKind.Computer -> computerVersion?.trim()?.takeIf { it.isNotBlank() }
            RaftComputerKind.Daemon -> daemonVersion.trim().takeIf { it.isNotBlank() }
        }
    val availableVersion = raftComputerAvailableVersion(this)
    val tier = raftComputerVersionTier(this)
    val versionNotice =
        when {
            tier is RaftComputerVersionTier.Notice -> RaftComputerVersionNotice.BelowFloor(reinstall = tier.reinstall)
            availableVersion != null -> RaftComputerVersionNotice.Available(availableVersion)
            else -> null
        }
    return RaftComputerRowModel(
        id = id,
        name = name.trim().ifBlank { id },
        online = online,
        kind = kind,
        version = version,
        updateAvailable = kind == RaftComputerKind.Computer && upgradeAvailable,
        hostDetail =
            listOfNotNull(hostname?.trim()?.takeIf { it.isNotBlank() }, platform?.trim()?.takeIf { it.isNotBlank() })
                .joinToString(" · ")
                .takeIf { it.isNotBlank() },
        description = description?.trim()?.takeIf { it.isNotBlank() },
        runtimes = runtimes.map { it.trim() }.filter { it.isNotBlank() }.distinct(),
        lastSeenAt = if (online) null else (lastHeartbeat ?: lastSeenLabel)?.trim()?.takeIf { it.isNotBlank() },
        osName = raftComputerOsName(osRelease, platform),
        versionNotice = versionNotice,
        dot =
            when {
                kind == RaftComputerKind.Computer && versionNotice != null -> RaftComputerDot.Upgrade
                online -> RaftComputerDot.Online
                else -> RaftComputerDot.Offline
            },
        diskLow = raftComputerDiskLow(this),
        disk = diskStatus?.takeIf { online }?.toRaftComputerResourceMeter(warn = raftComputerDiskLow(this)),
        memory = memoryStatus?.takeIf { online }?.toRaftComputerResourceMeter(warn = false),
        canViewRuntimeUsage = canViewRuntimeUsage,
    )
}

/** A usage column on a card (web `RackUsageColumn`): one provider's account, named by [runtimeId]. */
internal data class RaftRackUsageColumn(val provider: RuntimeAccountUsageProvider, val runtimeId: String)

/** Web `RackRuntimeColumns` of one computer: a line per usage provider, then Other runtimes. */
internal data class RaftRackRuntimeColumns(val usage: List<RaftRackUsageColumn>, val other: List<String>)

/**
 * Web `rackRuntimeColumns([machine])`: the computer's runtimes in [catalogOrder] (unknown ids
 * last, by id); the first runtime of each usage provider names its line (Kimi Code and Kimi CLI
 * share Kimi's), every runtime that reports no usage is in Other.
 */
internal fun raftRackRuntimeColumns(runtimes: List<String>, catalogOrder: List<String>): RaftRackRuntimeColumns {
    val index = catalogOrder.withIndex().associate { (position, id) -> id to position }
    val present =
        runtimes.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            .sortedWith(compareBy<String> { index[it] ?: Int.MAX_VALUE }.thenBy { it })
    val usage = mutableListOf<RaftRackUsageColumn>()
    val other = mutableListOf<String>()
    present.forEach { runtimeId ->
        val provider = RuntimeAccountUsageProvider.forRuntime(runtimeId)
        when {
            provider == null -> other += runtimeId
            usage.none { it.provider == provider } -> usage += RaftRackUsageColumn(provider, runtimeId)
        }
    }
    return RaftRackRuntimeColumns(usage, other)
}

/** Web `RACK_USAGE_WARN_PERCENT` (raft-ui Meter's default `warnAt`, 0.8): warns from 80% used. */
internal const val RAFT_RACK_USAGE_WARN_PERCENT = 80

/** One usage line on a card (web `RackRuntimeCell` without `absent`: a card has no line then). */
internal sealed interface RaftRackUsageCell {
    val column: RaftRackUsageColumn

    /** An account needs attention (sign in again): the warning pixel beside the value or "—". */
    val attention: Boolean

    /** Nothing to read (not allowed, not read yet, missing, failed, or every account needs attention): "—". */
    data class Unavailable(override val column: RaftRackUsageColumn, override val attention: Boolean) : RaftRackUsageCell

    /** The tightest account's share used: [warn] from 80%, [usedUp] at its limit, [stale] dimmed. */
    data class Value(
        override val column: RaftRackUsageColumn,
        val used: Int,
        val warn: Boolean,
        val usedUp: Boolean,
        val stale: Boolean,
        override val attention: Boolean,
    ) : RaftRackUsageCell
}

/** Web `BulkRuntimeUsageSummary` of one account. */
private sealed interface RaftRackAccountSummary {
    data object Limit : RaftRackAccountSummary

    data class Used(val percent: Int) : RaftRackAccountSummary

    data object Attention : RaftRackAccountSummary
}

/** Web `summarizeRuntimeUsage` of a single account. */
private fun RuntimeAccountUsageAccount.rackSummary(): RaftRackAccountSummary? {
    if (windows.any { it.status == RuntimeAccountUsageWindowStatus.LimitReached }) return RaftRackAccountSummary.Limit
    if (health != RuntimeAccountUsageHealth.Ok) return RaftRackAccountSummary.Attention
    val ratios = windows.filter { it.status == RuntimeAccountUsageWindowStatus.Ok }.mapNotNull { it.usedRatio }
    if (ratios.isEmpty()) return null
    return RaftRackAccountSummary.Used(runtimeUsagePercentUsed(ratios.max()))
}

/**
 * Web `rackRuntimeCell`: what a provider's line shows from its read ([load] null: not allowed or
 * not read yet). Several accounts: the tightest one (the sheet lists them all); at a tie, a
 * reached limit.
 */
internal fun raftRackUsageCell(column: RaftRackUsageColumn, load: RuntimeAccountUsageLoad?): RaftRackUsageCell {
    val read = (load as? RuntimeAccountUsageLoad.Loaded)?.read as? RuntimeAccountUsageRead.Available
        ?: return RaftRackUsageCell.Unavailable(column, attention = false)
    var tightestUsed: Int? = null
    var tightestLimit = false
    var attention = false
    read.snapshot.accounts.forEach { account ->
        val summary = account.rackSummary()
        if (summary == RaftRackAccountSummary.Attention) attention = true
        val used =
            when (summary) {
                RaftRackAccountSummary.Limit -> 100
                is RaftRackAccountSummary.Used -> summary.percent
                else -> null
            } ?: return@forEach
        val limit = summary == RaftRackAccountSummary.Limit
        val current = tightestUsed
        if (current == null || used > current || (used == current && limit)) {
            tightestUsed = used
            tightestLimit = limit
        }
    }
    val used = tightestUsed ?: return RaftRackUsageCell.Unavailable(column, attention)
    return RaftRackUsageCell.Value(
        column = column,
        used = used,
        warn = used >= RAFT_RACK_USAGE_WARN_PERCENT,
        usedUp = tightestLimit,
        stale = read.stale,
        attention = attention,
    )
}

/**
 * The facts line under a card's name (web ComputersRack card `CardDescription`): online, the OS,
 * the version and its notice; offline, "Offline · Last seen …" instead.
 */
internal sealed interface RaftComputerCardFacts {
    data class Online(
        val osName: String?,
        val kind: RaftComputerKind,
        val version: String?,
        val notice: RaftComputerVersionNotice?,
    ) : RaftComputerCardFacts

    data class Offline(val lastSeenAt: String?) : RaftComputerCardFacts
}

internal fun RaftComputerRowModel.cardFacts(): RaftComputerCardFacts =
    if (online) {
        RaftComputerCardFacts.Online(osName = osName, kind = kind, version = version, notice = versionNotice)
    } else {
        RaftComputerCardFacts.Offline(lastSeenAt)
    }

/** One agent on a computer card: its avatar opens its profile. */
internal data class RaftComputerCardAgent(
    val id: String,
    val name: String,
    val avatarWireValue: String?,
)

private const val RAFT_COMPUTER_ID_BADGE_PREFIX = "computer-id:"

/**
 * Web ComputersRack `useAgentsByMachine`: the live (not deleted) agents of the members store per
 * computer, in the store's order. The computer is the agent's `computer-id:` badge, as the Members
 * page groups them; an agent without one is on no computer.
 */
internal fun raftComputerAgentsByMachine(members: List<MemberProfile>): Map<String, List<RaftComputerCardAgent>> {
    val byMachine = LinkedHashMap<String, MutableList<RaftComputerCardAgent>>()
    val seen = HashSet<String>()
    members.forEach { member ->
        if (member.kind != MemberKind.Agent) return@forEach
        if (!member.agentDeletedAt.isNullOrBlank() || member.membershipStatus == "deleted") return@forEach
        val machineId =
            member.permissionBadges.firstNotNullOfOrNull { badge ->
                badge.removePrefix(RAFT_COMPUTER_ID_BADGE_PREFIX).trim().takeIf { badge.startsWith(RAFT_COMPUTER_ID_BADGE_PREFIX) && it.isNotBlank() }
            } ?: return@forEach
        if (!seen.add(member.id.value)) return@forEach
        byMachine.getOrPut(machineId) { mutableListOf() } +=
            RaftComputerCardAgent(
                id = member.id.value,
                name = member.displayName.trim().ifBlank { member.handle },
                avatarWireValue = member.avatarWireValueForAvatarSlot(),
            )
    }
    return byMachine
}

/** The page header's counts (web `ComputersRackCounts`): computers, online ones, and their agents. */
internal data class RaftComputersCounts(val computers: Int, val online: Int, val agents: Int)

internal fun raftComputersCounts(
    rows: List<RaftComputerRowModel>,
    agentsByMachine: Map<String, List<RaftComputerCardAgent>>,
): RaftComputersCounts =
    RaftComputersCounts(
        computers = rows.size,
        online = rows.count { it.online },
        agents = rows.sumOf { agentsByMachine[it.id]?.size ?: 0 },
    )

/**
 * A card's agents on exactly one line (cindyz, #triage): [visible] avatars, then "+[overflow]" in
 * the last slot when they don't all fit. The overflow takes one slot of its own, so it replaces
 * the last avatar that would have fit.
 */
internal data class RaftComputerAgentStrip(val visible: Int, val overflow: Int)

internal fun raftComputerAgentStrip(total: Int, capacity: Int): RaftComputerAgentStrip {
    val count = total.coerceAtLeast(0)
    val slots = capacity.coerceAtLeast(1)
    if (count <= slots) return RaftComputerAgentStrip(visible = count, overflow = 0)
    val visible = slots - 1
    return RaftComputerAgentStrip(visible = visible, overflow = count - visible)
}

/** How many [slot]-wide slots, [gap] apart, fit in [width] (all in dp). */
internal fun raftComputerAgentSlotCapacity(width: Float, slot: Float, gap: Float): Int {
    if (width <= 0f || slot <= 0f) return 0
    return ((width + gap) / (slot + gap)).toInt()
}

internal sealed interface RaftComputerDetailModel {
    data object Loading : RaftComputerDetailModel

    /** The machine is not on the current server (deleted, or a link from another server). */
    data object Unavailable : RaftComputerDetailModel

    data class Failed(val failure: RaftComputersFailure) : RaftComputerDetailModel

    data class Content(
        val computer: RaftComputerRowModel,
        val snapshot: SettingsComputerDetailSnapshot,
        val createdAt: String?,
        /** Rename / description editing (server `editMachines` or machine creator). */
        val canEdit: Boolean = snapshot.canEditMachine,
        /** The Delete action (server `removeMachines` or machine creator). */
        val canRemove: Boolean = snapshot.canRemoveMachine,
        /**
         * Agents still assigned to the machine. While non-zero, Delete opens web's "Cannot delete"
         * notice instead of the destructive confirm (the server would answer 409 anyway).
         */
        val assignedAgentCount: Int = raftComputerAssignedAgentCount(snapshot),
        /** Web: a managed Computer shows a Creator fact beside Created; a raw daemon keeps Created only. */
        val showsCreator: Boolean = snapshot.computer.isComputer,
        /** The human who attached it, opening their profile; null shows "No creator". */
        val creator: RaftComputerCreatorModel? = snapshot.computer.creator?.toRaftComputerCreator(),
    ) : RaftComputerDetailModel
}

/**
 * A Computer's creator as the detail page shows it (web MachineDetailPanel): the avatar, the
 * display name (the handle when there is none) and `@name`.
 */
internal data class RaftComputerCreatorModel(
    val id: String,
    val name: String,
    val displayName: String,
    /** The uploaded avatar, else the Gravatar; null draws the person placeholder. */
    val avatarWireValue: String?,
)

internal fun SettingsComputerCreatorSnapshot.toRaftComputerCreator(): RaftComputerCreatorModel? {
    val cleanId = id.trim().takeIf { it.isNotBlank() } ?: return null
    val cleanName = name.trim().takeIf { it.isNotBlank() } ?: return null
    return RaftComputerCreatorModel(
        id = cleanId,
        name = cleanName,
        displayName = displayName?.trim()?.takeIf { it.isNotBlank() } ?: cleanName,
        avatarWireValue =
            avatarWireValueForAvatarSlot(
                kind = MemberKind.Human,
                id = cleanId,
                avatarWireValue = null,
                avatarUrl = avatarUrl,
                gravatarHash = gravatarHash,
            ),
    )
}

/**
 * The web panel counts the machine's loaded agent list; when that section failed to load the
 * server's `agentCount` stands in so a failed side-load never offers a delete that will 409.
 */
internal fun raftComputerAssignedAgentCount(snapshot: SettingsComputerDetailSnapshot): Int =
    if (snapshot.agentsError == null) {
        snapshot.agents.size
    } else {
        snapshot.computer.agentCount.coerceAtLeast(0)
    }

/** The server's description limit (PATCH /servers/:id/machines/:machineId rejects > 500). */
internal const val RAFT_COMPUTER_DESCRIPTION_MAX_LENGTH = 500

/**
 * A failed refresh while content is on screen: the store keeps the content and records only the
 * route error code, which the page surfaces as a banner above the content.
 */
internal fun raftComputersRefreshErrorCode(hasContent: Boolean, routeErrorCode: String?, refreshing: Boolean): String? =
    routeErrorCode?.trim()?.takeIf { hasContent && !refreshing && it.isNotBlank() }

/**
 * What an editable field shows after its saved value changes underneath it ([incoming] replaces
 * [baseline], e.g. a realtime or refresh reload): an untouched field follows the new value, a field
 * with unsaved edits keeps them.
 */
internal fun raftComputerEditableTextAfterReload(current: String, baseline: String, incoming: String): String =
    if (current == baseline) incoming else current

/**
 * Whether an open Name/Description editor goes back to text: its save just finished
 * ([wasSaving] -> not [saving]) without an error, like web `handleSaveRename` closing the editor
 * once the PATCH resolves. A failed save keeps the editor open with the draft.
 */
internal fun raftComputerEditorClosesAfterSave(wasSaving: Boolean, saving: Boolean, failed: Boolean): Boolean =
    wasSaving && !saving && !failed

/**
 * The subtitle of an agent row on a computer (web `MachineAgentRow` `agentIdentityLabel`): the
 * runtime's display name and the model's label, joined by " · ", either one dropped when absent.
 * The model label follows web `agentModelLabel`: the bundled catalog label, else the raw id.
 */
internal fun raftComputerAgentIdentityLabel(runtime: String?, model: String?): String? {
    val runtimeId = runtime?.trim()?.takeIf { it.isNotBlank() }
    val modelId = model?.trim()?.takeIf { it.isNotBlank() }
    val runtimeLabel = runtimeId?.let(::raftAgentRuntimeDisplayName)
    val modelLabel = modelId?.let { getModelLabel(runtimeId = runtimeId.orEmpty(), modelId = it).ifBlank { it } }
    return listOfNotNull(runtimeLabel, modelLabel).joinToString(" · ").ifBlank { null }
}

internal fun raftComputerDetailModel(
    state: ScreenLoadState<SettingsComputerDetailSnapshot>,
    machineId: String,
    targetServerId: String?,
    currentServerId: String?,
): RaftComputerDetailModel {
    // The page is opened for one server's machine (from a member profile or search). The settings
    // store reads the current server, so a target on any other server must not render whichever
    // machine of the current server happens to share the id.
    val target = targetServerId?.trim()?.takeIf { it.isNotBlank() }
    if (target != null && target != currentServerId?.trim()) return RaftComputerDetailModel.Unavailable
    return when (state) {
        ScreenLoadState.Loading -> RaftComputerDetailModel.Loading
        is ScreenLoadState.Empty -> RaftComputerDetailModel.Unavailable
        is ScreenLoadState.Error ->
            if (state.error.code == "settings_computer_required") {
                RaftComputerDetailModel.Unavailable
            } else {
                RaftComputerDetailModel.Failed(state.error.toComputersFailure())
            }
        is ScreenLoadState.NoPermission -> RaftComputerDetailModel.Failed(state.blocker.toComputersFailure())
        is ScreenLoadState.Content -> {
            val snapshot = state.data
            if (snapshot.computer.id != machineId.trim()) {
                RaftComputerDetailModel.Loading
            } else {
                RaftComputerDetailModel.Content(
                    computer = snapshot.computer.toRaftComputerRow(),
                    snapshot = snapshot,
                    createdAt = snapshot.computer.createdAt?.trim()?.takeIf { it.isNotBlank() },
                )
            }
        }
    }
}

/**
 * The store pops a deleted computer to the Computers route and records a finished, error-free
 * delete for it; the standalone detail page closes on exactly that.
 */
internal fun raftComputerDeleteCompleted(state: SharedSettingsState, machineId: String): Boolean {
    val action = state.actionState ?: return false
    return action.action == COMPUTER_ACTION_DELETE &&
        action.itemId == machineId &&
        !action.inProgress &&
        !action.hasComputerActionError() &&
        state.route.destination == SharedSettingsDestination.Computers
}

/** The in-flight or failed computer action on [machineId], if the latest action is [action]. */
internal fun SharedSettingsState.computerAction(action: String, machineId: String): SharedSettingsActionState? =
    actionState?.takeIf { it.action == action && it.itemId == machineId }

internal fun SharedSettingsActionState.hasComputerActionError(): Boolean =
    screenError != null || permissionBlocker != null || emptyReason != null || !errorCode.isNullOrBlank()

/** Computers-specific copy for a failure; [Generic] defers to the shared error presentation. */
internal enum class RaftComputerErrorCopy {
    Offline,
    Replica,
    HasAgents,
    BadRequest,
    Forbidden,
    SessionExpired,
    ServerUnavailable,
    Generic,
}

internal fun raftComputerErrorCopy(code: String?): RaftComputerErrorCopy =
    when (code?.trim()) {
        "machine_offline" -> RaftComputerErrorCopy.Offline
        "machine_offline_replay" -> RaftComputerErrorCopy.Replica
        "computer_has_agents" -> RaftComputerErrorCopy.HasAgents
        "computer_bad_request" -> RaftComputerErrorCopy.BadRequest
        "server_forbidden", "server_admin_required", "http_403" -> RaftComputerErrorCopy.Forbidden
        "auth_required", "http_401" -> RaftComputerErrorCopy.SessionExpired
        "server_unavailable" -> RaftComputerErrorCopy.ServerUnavailable
        else -> RaftComputerErrorCopy.Generic
    }

internal val SharedSettingsActionState.computerErrorCode: String?
    get() = screenError?.code ?: permissionBlocker?.code ?: errorCode ?: emptyReason?.name

internal val SettingsSectionErrorSnapshot.computerErrorCode: String
    get() = screenError?.code ?: permissionBlocker?.code ?: code

private fun ScreenError.toComputersFailure(): RaftComputersFailure =
    RaftComputersFailure(
        noAccess = raftComputerErrorCopy(code) == RaftComputerErrorCopy.Forbidden,
        retryable = retryable,
        code = code,
        screenError = this,
    )

private fun PermissionBlocker.toComputersFailure(): RaftComputersFailure =
    RaftComputersFailure(
        noAccess = code != "auth_required",
        retryable = false,
        code = code,
        permissionBlocker = this,
    )

/** Bytes as the web workspace list writes them (`12.3 MB`). */
internal fun raftComputerWorkspaceSize(bytes: Long?): String? = bytes?.takeIf { it >= 0L }?.let { RaftFormat.bytes(it) }

/**
 * Whether a realtime event concerns the Computers pages of [serverId]: a Machines-scope refresh
 * (machine:status / machine:updated / machine:upgrade-request) naming a machine on that server, or a
 * `computer:restart:done`. Every
 * other event is dropped before it reaches the settings store.
 */
internal fun raftComputersRealtimeEventApplies(event: RealtimeEvent, serverId: String): Boolean {
    // A web restart's result is relayed to the server's room only, so it carries no server id.
    if (event is ComputerRestartDone) return event.machineId.isNotBlank()
    if (event !is RealtimeRefreshRequired || event.scope != RealtimeRefreshScope.Machines) return false
    if (event.subjectId.isNullOrBlank()) return false
    val eventServerId = event.serverId?.value?.trim()?.takeIf { it.isNotBlank() } ?: return true
    return eventServerId == serverId.trim()
}
