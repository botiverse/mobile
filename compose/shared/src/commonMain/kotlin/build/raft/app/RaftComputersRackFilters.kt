package build.raft.app

import build.raft.app.data.ComputersRackGroupBy
import build.raft.app.data.ComputersRackSort
import build.raft.app.data.ComputersRackSortDirection
import build.raft.app.data.ComputersRackSortKey
import build.raft.app.data.ComputersRackView
import build.raft.app.data.KeyValueComputersRackPreferenceRepository
import build.raft.app.data.RuntimeAccountUsageHealth
import build.raft.app.data.RuntimeAccountUsageLoad
import build.raft.app.data.RuntimeAccountUsageProvider
import build.raft.app.data.RuntimeAccountUsageRead
import build.raft.app.data.RuntimeAccountUsageWindowStatus
import build.raft.app.data.SettingsComputerSizeStatusSnapshot
import build.raft.app.data.SettingsComputerSnapshot
import build.raft.app.data.isMyComputer
import build.raft.app.data.raftMineFirst
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/*
 * The Computers page's toolbar logic (task #proj-mobile:#60 step 1), pure, so the toolbar and the
 * list read the same answer. A port of botiverse/slock packages/web/src/:
 *
 * - features/computer/computersRackFilter.ts: search, Status, Runtime, Group;
 * - features/computer/computersRackSort.ts: Sort and what a runtime has left;
 * - features/computer/computersRackToolbarModel.ts: the menus' items and Sort's direction words;
 * - features/computer/ComputersRackList.tsx: filter, sort, the Offline section, then the groups;
 * - store/computersRackFilterPreference.ts: session filters, Clear all, the reset on another server.
 *
 * Words are the screen's: groups take their fallback titles as [RaftRackGroupLabels].
 */

/** Usage reads by machine id, then provider (the page's `usageLoads`). */
internal typealias RaftRackUsageLoads = Map<String, Map<RuntimeAccountUsageProvider, RuntimeAccountUsageLoad>>

// ---------------------------------------------------------------------------------------------
// Filters

/** Web `ComputersRackStatusFilter`, in the menu's order (`COMPUTERS_RACK_STATUS_FILTERS`). */
internal enum class RaftComputersRackStatusFilter { All, Online, Offline, Attention }

/** Web `ComputersRackRuntimeFilter`: a usage provider, or Other (a runtime that reports no usage). */
internal enum class RaftComputersRackRuntimeFilter(val provider: RuntimeAccountUsageProvider?) {
    Claude(RuntimeAccountUsageProvider.Claude),
    Codex(RuntimeAccountUsageProvider.Codex),
    Kimi(RuntimeAccountUsageProvider.Kimi),
    Grok(RuntimeAccountUsageProvider.Grok),
    Other(null);

    companion object {
        fun of(provider: RuntimeAccountUsageProvider): RaftComputersRackRuntimeFilter = entries.first { it.provider == provider }

        /** Web `usageProviderForRuntime(runtimeId) ?? "other"`. */
        fun forRuntime(runtimeId: String): RaftComputersRackRuntimeFilter =
            RuntimeAccountUsageProvider.forRuntime(runtimeId)?.let(::of) ?: Other
    }
}

/** Web `ComputersRackFilters` less the grouping: what Clear all resets. */
internal data class RaftComputersRackFilters(
    val query: String = "",
    val status: RaftComputersRackStatusFilter = RaftComputersRackStatusFilter.All,
    val runtimes: List<RaftComputersRackRuntimeFilter> = emptyList(),
) {
    /** Web `hasActiveRackFilters`: Clear all shows while the search, Status or Runtime differs from its default. */
    val active: Boolean get() = query.trim().isNotEmpty() || status != RaftComputersRackStatusFilter.All || runtimes.isNotEmpty()
}

/** Web `RACK_PROVIDER_NAMES`: a provider as the filter and the sort name it, the product, not one of its runtimes. */
internal fun raftRackProviderName(provider: RuntimeAccountUsageProvider): String =
    when (provider) {
        RuntimeAccountUsageProvider.Claude -> "Claude Code"
        RuntimeAccountUsageProvider.Codex -> "Codex"
        RuntimeAccountUsageProvider.Kimi -> "Kimi"
        RuntimeAccountUsageProvider.Grok -> "Grok"
    }

/**
 * Web `rackRuntimeColumns(machines)` over the whole list: the usage providers any computer has, in
 * catalog order, and the runtimes that report none.
 */
internal fun raftRackFleetRuntimeColumns(computers: List<SettingsComputerSnapshot>, catalogOrder: List<String>): RaftRackRuntimeColumns =
    raftRackRuntimeColumns(computers.flatMap { it.runtimes }, catalogOrder)

/** Web `runtimeFilterOptions`: the providers these computers have, then Other when any runtime reports none. */
internal fun raftRackRuntimeFilterOptions(columns: RaftRackRuntimeColumns): List<RaftComputersRackRuntimeFilter> =
    columns.usage.map { RaftComputersRackRuntimeFilter.of(it.provider) } +
        listOfNotNull(RaftComputersRackRuntimeFilter.Other.takeIf { columns.other.isNotEmpty() })

/**
 * Web `effectiveRuntimeFilter`: the chosen runtimes these computers still have. One chosen earlier
 * whose computers have gone filters nothing, in the list, the chip and Clear all alike.
 */
internal fun raftRackEffectiveRuntimeFilter(
    chosen: List<RaftComputersRackRuntimeFilter>,
    options: List<RaftComputersRackRuntimeFilter>,
): List<RaftComputersRackRuntimeFilter> = chosen.filter { it in options }

/**
 * Web `rackNeedsAttention`: an upgrade waiting (`getComputerAttentionStatus` "upgrade": Update
 * available and Update required alike, raft-design patterns/status.md), a Computer that is not
 * online (unless it is only restarting for an update in flight, which is in progress), or an
 * online one low on disk (shared `isMachineDiskLow`).
 */
internal fun raftRackNeedsAttention(computer: SettingsComputerSnapshot): Boolean {
    if (computer.upgradeAvailable) return true
    if (computer.isComputer && raftComputerVersionTier(computer) is RaftComputerVersionTier.Notice) return true
    if (!computer.online) return computer.isComputer && !computer.raftComputerUpdating()
    return raftComputerDiskLow(computer)
}

/** Web `ownerName`: the owner's display name (else name), only while the Computer has an owner. */
private fun SettingsComputerSnapshot.rackOwnerName(): String? =
    if (ownerUserId == null) null else owner?.let { it.displayName?.takeIf(String::isNotEmpty) ?: it.name }

/**
 * Web `filterRackMachines`: the search (case-insensitive, within the name, the hostname or the
 * owner's display name or name), then Status, then Runtime (any chosen runtime). The order is kept.
 */
internal fun raftFilterRackComputers(
    computers: List<SettingsComputerSnapshot>,
    filters: RaftComputersRackFilters,
): List<SettingsComputerSnapshot> {
    val query = filters.query.trim().lowercase()
    if (query.isEmpty() && filters.status == RaftComputersRackStatusFilter.All && filters.runtimes.isEmpty()) return computers
    return computers.filter { computer ->
        if (query.isNotEmpty()) {
            val haystack = listOfNotNull(computer.name, computer.hostname, computer.rackOwnerName(), computer.owner?.name)
            if (haystack.none { it.isNotEmpty() && it.lowercase().contains(query) }) return@filter false
        }
        val statusKept =
            when (filters.status) {
                RaftComputersRackStatusFilter.All -> true
                RaftComputersRackStatusFilter.Online -> computer.online
                RaftComputersRackStatusFilter.Offline -> !computer.online
                RaftComputersRackStatusFilter.Attention -> raftRackNeedsAttention(computer)
            }
        if (!statusKept) return@filter false
        if (filters.runtimes.isNotEmpty()) {
            val own = computer.runtimes.map(RaftComputersRackRuntimeFilter::forRuntime).toSet()
            if (filters.runtimes.none { it in own }) return@filter false
        }
        true
    }
}

// ---------------------------------------------------------------------------------------------
// Text order

/**
 * Web `new Intl.Collator(locale, { numeric: true, sensitivity: "base" }).compare`, in common code:
 * runs of digits compare by their value ("web-2" before "web-10"); otherwise case and the common
 * Latin accents are ignored, and punctuation and symbols come before digits, digits before letters
 * (CLDR root order). Equal names compare 0, so a stable sort keeps the list's own order.
 * Not covered: locale tailoring (zh pinyin order for Han names compares by code point here).
 */
internal fun raftRackCompareText(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val ca = a[i]
        val cb = b[j]
        if (ca.isAsciiDigit() && cb.isAsciiDigit()) {
            val endA = a.digitRunEnd(i)
            val endB = b.digitRunEnd(j)
            val digitsA = a.substring(i, endA).trimStart('0')
            val digitsB = b.substring(j, endB).trimStart('0')
            val order = if (digitsA.length != digitsB.length) digitsA.length - digitsB.length else digitsA.compareTo(digitsB)
            if (order != 0) return order.sign()
            i = endA
            j = endB
            continue
        }
        val fa = ca.rackFold()
        val fb = cb.rackFold()
        if (fa != fb) {
            val classOrder = fa.rackClass() - fb.rackClass()
            return if (classOrder != 0) classOrder.sign() else fa.compareTo(fb).sign()
        }
        i++
        j++
    }
    return (a.length - i).compareTo(b.length - j).sign()
}

private fun Int.sign(): Int = if (this < 0) -1 else if (this > 0) 1 else 0

private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

private fun String.digitRunEnd(start: Int): Int {
    var end = start
    while (end < length && this[end].isAsciiDigit()) end++
    return end
}

/** Punctuation and symbols 0, digits 1, letters 2 (CLDR root's primary groups). */
private fun Char.rackClass(): Int =
    when {
        isAsciiDigit() || isDigit() -> 1
        isLetter() -> 2
        else -> 0
    }

private const val RACK_ACCENTED = "àáâãäåāăąçćčďèéêëēėęěìíîïīįłñńňòóôõöøōőŕřśšşťùúûüūůűųýÿźżž"
private const val RACK_ACCENT_BASE = "aaaaaaaaacccdeeeeeeeeiiiiiilnnnoooooooorrsssttuuuuuuuuyyzzz"

/** The base letter of a character, for base sensitivity: lower case, the common Latin accents dropped. */
private fun Char.rackFold(): Char {
    val lower = lowercaseChar()
    val accented = RACK_ACCENTED.indexOf(lower)
    return if (accented >= 0) RACK_ACCENT_BASE[accented] else lower
}

// ---------------------------------------------------------------------------------------------
// Sort

/*
 * No sort chosen (null; web #14479, artin #triage:#332) is the default, the Sort's "Mine first":
 * the list keeps the Computers list's own order, the viewer's own computers (attached or owned,
 * `isMyComputer`) first, then the rest, each in the server's order; grouped, the groups holding
 * the viewer's own lead. A sort someone chose (Name, Disk, what a runtime has left) stays theirs.
 */

/** Web `firstSortDirection`: text starts A to Z, numbers high to low. */
internal fun raftRackFirstSortDirection(key: ComputersRackSortKey): ComputersRackSortDirection =
    if (key == ComputersRackSortKey.Name) ComputersRackSortDirection.Asc else ComputersRackSortDirection.Desc

/**
 * Web `chooseRackSortKey`: a new key starts at its own first direction; null (Mine first, web
 * `"default"`) is no sort.
 */
internal fun raftRackChooseSortKey(key: ComputersRackSortKey?): ComputersRackSort? = key?.let { ComputersRackSort(it, raftRackFirstSortDirection(it)) }

/**
 * Web `effectiveRackSort`: the stored sort while these computers still have its runtime, else null,
 * no sort (Mine first).
 */
internal fun raftRackEffectiveSort(sort: ComputersRackSort?, columns: RaftRackRuntimeColumns): ComputersRackSort? {
    if (sort == null) return null
    val key = sort.key as? ComputersRackSortKey.Remaining ?: return sort
    return if (columns.usage.any { it.provider == key.provider }) sort else null
}

/**
 * Web `sortMenuSections`: Mine first (null, the default) and Name; what each runtime these
 * computers have has left; Disk, Memory. Empty sections dropped.
 */
internal fun raftRackSortMenuSections(columns: RaftRackRuntimeColumns): List<List<ComputersRackSortKey?>> =
    listOf(
        listOf(null, ComputersRackSortKey.Name),
        columns.usage.map { ComputersRackSortKey.Remaining(it.provider) },
        listOf(ComputersRackSortKey.Disk, ComputersRackSortKey.Memory),
    ).filter { it.isNotEmpty() }

/** Web `sortDirectionLabel`'s message: A to Z / Z to A, Most / Least remaining first, Most / Least used first. */
internal enum class RaftRackSortDirectionCopy { NameAsc, NameDesc, RemainingDesc, RemainingAsc, UsedDesc, UsedAsc }

/** The two directions of [key], in the menu's order (its first direction first). */
internal fun raftRackSortDirections(key: ComputersRackSortKey): List<ComputersRackSortDirection> =
    raftRackFirstSortDirection(key).let { first -> listOf(first) + ComputersRackSortDirection.entries.filter { it != first } }

internal fun raftRackSortDirectionCopy(key: ComputersRackSortKey, direction: ComputersRackSortDirection): RaftRackSortDirectionCopy {
    val desc = direction == ComputersRackSortDirection.Desc
    return when (key) {
        ComputersRackSortKey.Name -> if (desc) RaftRackSortDirectionCopy.NameDesc else RaftRackSortDirectionCopy.NameAsc
        is ComputersRackSortKey.Remaining -> if (desc) RaftRackSortDirectionCopy.RemainingDesc else RaftRackSortDirectionCopy.RemainingAsc
        ComputersRackSortKey.Disk, ComputersRackSortKey.Memory -> if (desc) RaftRackSortDirectionCopy.UsedDesc else RaftRackSortDirectionCopy.UsedAsc
    }
}

/**
 * Web `rackRemaining`: what a provider's accounts on a computer have left, 0 to 1. The tightest
 * account, and in each its tightest window, read by status, never by place: only `ok` and
 * `limit_reached` windows count (a reached limit without a ratio is fully used). A rate-limited
 * account has nothing left (0); one that needs signing in again, is unsupported or failed says
 * nothing, as does one with no window to read. Null: nothing to read for any account, or no read.
 */
internal fun raftRackRemaining(load: RuntimeAccountUsageLoad?): Double? {
    val read = (load as? RuntimeAccountUsageLoad.Loaded)?.read as? RuntimeAccountUsageRead.Available ?: return null
    var tightest: Double? = null
    read.snapshot.accounts.forEach { account ->
        val remaining =
            when (account.health) {
                RuntimeAccountUsageHealth.RateLimited -> 0.0
                RuntimeAccountUsageHealth.Ok ->
                    account.windows
                        .mapNotNull { window ->
                            when (window.status) {
                                RuntimeAccountUsageWindowStatus.Ok -> window.usedRatio
                                RuntimeAccountUsageWindowStatus.LimitReached -> window.usedRatio ?: 1.0
                                RuntimeAccountUsageWindowStatus.Unavailable -> null
                            }
                        }
                        .maxOrNull()
                        ?.let { maxOf(0.0, 1.0 - it) }
                else -> null
            }
        if (remaining != null && (tightest == null || remaining < tightest!!)) tightest = remaining
    }
    return tightest
}

/** Web `usedRatio(liveDiskStatus(machine))`: only an online computer's report counts. */
private fun SettingsComputerSizeStatusSnapshot?.rackUsedRatio(online: Boolean): Double? {
    val status = this?.takeIf { online && it.totalBytes > 0.0 } ?: return null
    return 1.0 - status.availableBytes / status.totalBytes
}

/** One computer's value for a sort key: text for Name, a ratio otherwise; null sorts last both ways. */
internal sealed interface RaftRackSortValue {
    data class Text(val value: String) : RaftRackSortValue

    data class Number(val value: Double) : RaftRackSortValue
}

/** Web `rackSortValue`. A computer without the key's runtime has nothing left of it to sort by. */
internal fun raftRackSortValue(
    computer: SettingsComputerSnapshot,
    key: ComputersRackSortKey,
    usage: RaftRackUsageLoads,
): RaftRackSortValue? =
    when (key) {
        ComputersRackSortKey.Name ->
            (computer.name.trim().takeIf(String::isNotEmpty) ?: computer.hostname?.trim()?.takeIf(String::isNotEmpty))
                ?.let(RaftRackSortValue::Text)
        ComputersRackSortKey.Disk -> computer.diskStatus.rackUsedRatio(computer.online)?.let(RaftRackSortValue::Number)
        ComputersRackSortKey.Memory -> computer.memoryStatus.rackUsedRatio(computer.online)?.let(RaftRackSortValue::Number)
        is ComputersRackSortKey.Remaining ->
            if (computer.runtimes.none { RuntimeAccountUsageProvider.forRuntime(it) == key.provider }) {
                null
            } else {
                raftRackRemaining(usage[computer.id]?.get(key.provider))?.let(RaftRackSortValue::Number)
            }
    }

/** Web `sortRackMachines`: [computers] in [sort]'s order; no value is last either way; ties keep the list's order. */
internal fun raftSortRackComputers(
    computers: List<SettingsComputerSnapshot>,
    sort: ComputersRackSort,
    usage: RaftRackUsageLoads,
): List<SettingsComputerSnapshot> {
    val sign = if (sort.direction == ComputersRackSortDirection.Asc) 1 else -1
    return computers
        .map { it to raftRackSortValue(it, sort.key, usage) }
        .sortedWith { (_, a), (_, b) ->
            when {
                a == null || b == null -> if (a == b) 0 else if (a == null) 1 else -1
                a is RaftRackSortValue.Text && b is RaftRackSortValue.Text -> sign * raftRackCompareText(a.value, b.value)
                a is RaftRackSortValue.Number && b is RaftRackSortValue.Number -> sign * a.value.compareTo(b.value)
                else -> 0
            }
        }
        .map { it.first }
}

// ---------------------------------------------------------------------------------------------
// Group

/** Shared `HOST_PROVIDER_LABELS` (packages/shared/src/index.ts): a cloud / VM platform id and its name. */
internal val RAFT_HOST_PROVIDER_LABELS: Map<String, String> =
    linkedMapOf(
        "aws" to "AWS",
        "gcp" to "Google Cloud",
        "hetzner" to "Hetzner Cloud",
        "digitalocean" to "DigitalOcean",
        "vultr" to "Vultr",
        "linode" to "Akamai (Linode)",
        "scaleway" to "Scaleway",
        "alibaba" to "Alibaba Cloud",
        "upcloud" to "UpCloud",
        "exoscale" to "Exoscale",
        "openstack" to "OpenStack",
        "exe_dev" to "exe.dev",
        "sprites" to "Sprites",
        "fly" to "Fly.io",
    )

/** One word prefix of a name: [key] compares (lower-case words joined by "-"), [title] is the name's own text up to there. */
internal data class RaftRackNamePrefix(val key: String, val title: String)

private val RACK_NAME_WORD = Regex("[^-_.]+")

/** Web `namePrefixes`: a name's word prefixes, shortest first; words split on "-", "_" and ".". */
private fun rackNamePrefixes(name: String): List<RaftRackNamePrefix> {
    val trimmed = name.trim()
    val words = RACK_NAME_WORD.findAll(trimmed).toList()
    return words.mapIndexed { index, word ->
        RaftRackNamePrefix(
            key = words.subList(0, index + 1).joinToString("-") { it.value.lowercase() },
            title = trimmed.substring(0, word.range.last + 1),
        )
    }
}

/**
 * Web `rackNamePrefixGroups` (#triage:#291): each name's group, in the order given, or null for
 * Other. Prefixes compare case-insensitively; a group's title is its prefix as most of its members
 * write it (a tie: the smallest by code point), so neither depends on the order given.
 *
 * 1. A name's candidates are its word prefixes shorter than the whole name, plus the whole name
 *    when it is a shorter prefix of another name or another name is the same, kept only when at
 *    least two names have them.
 * 2. Each name takes its longest candidate.
 * 3. While a group has one member (the longest such group first, then A to Z): if other groups sit
 *    under its prefix, the smallest of them (then A to Z) moves up into it; otherwise the lone name
 *    falls back to its next shorter candidate, or to Other.
 */
internal fun raftRackNamePrefixGroups(names: List<String>): List<RaftRackNamePrefix?> {
    val prefixes = names.map(::rackNamePrefixes)
    val shorterThanAName = prefixes.flatMap { own -> own.dropLast(1).map { it.key } }.toSet()
    val wholeNames = HashMap<String, Int>()
    prefixes.forEach { own -> own.lastOrNull()?.let { wholeNames[it.key] = (wholeNames[it.key] ?: 0) + 1 } }
    val ownCandidates =
        prefixes.map { own ->
            own.filterIndexed { index, prefix ->
                index < own.size - 1 || prefix.key in shorterThanAName || (wholeNames[prefix.key] ?: 0) > 1
            }
        }
    val support = HashMap<String, Int>()
    ownCandidates.forEach { own -> own.forEach { support[it.key] = (support[it.key] ?: 0) + 1 } }
    // Longest first, only those another name shares.
    val candidates = ownCandidates.map { own -> own.filter { (support[it.key] ?: 0) > 1 }.map { it.key }.reversed() }
    val assigned = candidates.map { it.firstOrNull() }.toMutableList()
    fun depth(key: String) = key.split("-").size
    while (true) {
        val groups = LinkedHashMap<String, MutableList<Int>>()
        assigned.forEachIndexed { index, key -> if (key != null) groups.getOrPut(key) { mutableListOf() } += index }
        val lone =
            groups.filterValues { it.size == 1 }.keys
                .sortedWith(compareByDescending<String> { depth(it) }.thenBy { it })
                .firstOrNull() ?: break
        val under =
            groups.entries.filter { it.key.startsWith("$lone-") }
                .sortedWith(compareBy<Map.Entry<String, MutableList<Int>>> { it.value.size }.thenBy { it.key })
                .firstOrNull()
        if (under != null) {
            under.value.forEach { assigned[it] = lone }
        } else {
            val index = groups.getValue(lone).first()
            val own = candidates[index]
            assigned[index] = own.getOrNull(own.indexOf(lone) + 1)
        }
    }
    // Each group's spellings, counted; the most common wins, then the smallest by code point.
    val spellings = LinkedHashMap<String, LinkedHashMap<String, Int>>()
    assigned.forEachIndexed { index, key ->
        if (key == null) return@forEachIndexed
        val title = prefixes[index].first { it.key == key }.title
        val counts = spellings.getOrPut(key) { LinkedHashMap() }
        counts[title] = (counts[title] ?: 0) + 1
    }
    val titles =
        spellings.mapValues { (_, counts) ->
            counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).first().key
        }
    return assigned.map { key -> key?.let { RaftRackNamePrefix(it, titles.getValue(it)) } }
}

/** The screen's words for the fallback groups (`machine.rack.group.personal` / `.other`). */
internal data class RaftRackGroupLabels(val personal: String, val other: String)

/** Web `RackGroup`: [fallback] groups (Personal computers, Other) always come last; [key] is stable. */
internal data class RaftRackGroup(
    val key: String,
    val title: String,
    val fallback: Boolean,
    val computers: List<SettingsComputerSnapshot>,
)

/**
 * Web `groupRackMachines`: [computers] (already sorted) in groups, each keeping the sorted order.
 * Groups follow [rank], a group's best member under the sort (lower first); groups with no ranked
 * member, and every group when [rank] is null, go A to Z; fallback groups are last.
 */
internal fun raftGroupRackComputers(
    computers: List<SettingsComputerSnapshot>,
    groupBy: ComputersRackGroupBy,
    labels: RaftRackGroupLabels,
    rank: ((SettingsComputerSnapshot) -> Int?)?,
): List<RaftRackGroup> {
    if (groupBy == ComputersRackGroupBy.None) return listOf(RaftRackGroup("all", "", fallback = false, computers = computers))
    val prefixes = if (groupBy == ComputersRackGroupBy.Prefix) raftRackNamePrefixGroups(computers.map { it.name }) else null

    class Building(val key: String, val title: String, val fallback: Boolean) {
        val members = mutableListOf<SettingsComputerSnapshot>()
        var best: Int? = null
    }
    val groups = LinkedHashMap<String, Building>()
    computers.forEachIndexed { index, computer ->
        val (key, title, fallback) =
            if (groupBy == ComputersRackGroupBy.Provider) {
                val label = computer.hostProvider?.let(RAFT_HOST_PROVIDER_LABELS::get)
                if (label != null) Triple("provider:${computer.hostProvider}", label, false) else Triple("fallback:provider", labels.personal, true)
            } else {
                val prefix = prefixes?.get(index)
                if (prefix != null) Triple("prefix:${prefix.key}", prefix.title, false) else Triple("fallback:prefix", labels.other, true)
            }
        val group = groups.getOrPut(key) { Building(key, title, fallback) }
        group.members += computer
        val value = rank?.invoke(computer)
        if (value != null && (group.best == null || value < group.best!!)) group.best = value
    }
    return groups.values
        .sortedWith { a, b ->
            val aBest = a.best
            val bBest = b.best
            when {
                a.fallback != b.fallback -> if (a.fallback) 1 else -1
                aBest == null && bBest != null -> 1
                aBest != null && bBest == null -> -1
                aBest != null && bBest != null && aBest != bBest -> aBest.compareTo(bBest)
                else -> raftRackCompareText(a.title, b.title)
            }
        }
        .map { RaftRackGroup(it.key, it.title, it.fallback, it.members.toList()) }
}

/**
 * Web `ComputersRackList` `rank`: under a sort by a number, a group goes by its best member (its
 * place in [sorted]); a member with no value has no rank. With no sort (Mine first) the groups
 * holding the viewer's own computers ([userId], attached or owned) come first. Null under the Name
 * sort: A to Z.
 */
internal fun raftRackGroupRank(
    sorted: List<SettingsComputerSnapshot>,
    sort: ComputersRackSort?,
    usage: RaftRackUsageLoads,
    userId: String?,
): ((SettingsComputerSnapshot) -> Int?)? {
    if (sort == null) return { computer -> if (computer.isMyComputer(userId)) 0 else null }
    if (sort.key == ComputersRackSortKey.Name) return null
    val rankOf = sorted.withIndex().associate { (index, computer) -> computer.id to index }
    return { computer -> if (raftRackSortValue(computer, sort.key, usage) == null) null else rankOf[computer.id] }
}

// ---------------------------------------------------------------------------------------------
// The list

/** What the list shows: the online computers' groups, then the Offline section (empty under Status: Offline). */
internal data class RaftComputersRackLayout(
    val groups: List<RaftRackGroup>,
    val offline: List<SettingsComputerSnapshot>,
) {
    /** Nothing matches the search and filters (web `data-empty="filters"`). */
    val empty: Boolean get() = offline.isEmpty() && groups.all { it.computers.isEmpty() }

    /** The Offline section opens by default (artin 2026-11-13, #proj-mobile:0095da7c; web follows). */
    val offlineOpenByDefault: Boolean get() = true
}

/**
 * Web `ComputersRackList` (phone, no "This device"): filter, sort (with no sort chosen, Mine first:
 * the viewer's own computers, [userId] attached or owns, then the rest, each in the list's order),
 * then the offline computers apart in one section at the end (whatever the grouping and the sort;
 * with Status: Offline they are the list itself), then the online ones grouped, groups ranked by
 * the sort.
 */
internal fun raftComputersRackLayout(
    computers: List<SettingsComputerSnapshot>,
    model: RaftComputersRackToolbarModel,
    groupBy: ComputersRackGroupBy,
    usage: RaftRackUsageLoads,
    labels: RaftRackGroupLabels,
    userId: String?,
): RaftComputersRackLayout {
    val filtered = raftFilterRackComputers(computers, model.filters)
    val sort = model.sort
    val sorted = if (sort != null) raftSortRackComputers(filtered, sort, usage) else filtered.raftMineFirst { it.isMyComputer(userId) }
    val offlineSection = model.filters.status != RaftComputersRackStatusFilter.Offline
    // An updating Computer stays in place while it restarts (raft-design patterns/status.md).
    val online = if (offlineSection) sorted.filter { it.online || it.raftComputerUpdating() } else sorted
    val offline = if (offlineSection) sorted.filter { !it.online && !it.raftComputerUpdating() } else emptyList()
    val groups = raftGroupRackComputers(online, groupBy, labels, raftRackGroupRank(online, sort, usage, userId))
    return RaftComputersRackLayout(groups, offline)
}

// ---------------------------------------------------------------------------------------------
// Toolbar state

/**
 * The toolbar's state: [filters] (search, Status, Runtime) for this page session on [serverId]
 * only; [groupBy], [storedSort] and [view] as kept on this device.
 */
internal data class RaftComputersRackToolbarState(
    val serverId: String? = null,
    val filters: RaftComputersRackFilters = RaftComputersRackFilters(),
    val groupBy: ComputersRackGroupBy = ComputersRackGroupBy.Default,
    /** The stored sort; null is the default. Read it through [raftComputersRackToolbarModel]. */
    val storedSort: ComputersRackSort? = null,
    val view: ComputersRackView = ComputersRackView.Default,
) {
    /** Web `clearFilters` (Clear all): the search, Status and Runtime; never Group, Sort or View. */
    fun clearedFilters(): RaftComputersRackToolbarState = copy(filters = RaftComputersRackFilters())

    /** Another server: its computers, so none of this server's search or filters. */
    fun onServer(serverId: String?): RaftComputersRackToolbarState =
        if (serverId == this.serverId) this else clearedFilters().copy(serverId = serverId)
}

/** Web `useRackToolbarModel`: what the toolbar reads, with Runtime and Sort as they apply to these computers. */
internal data class RaftComputersRackToolbarModel(
    val runtimeOptions: List<RaftComputersRackRuntimeFilter>,
    /** The filters as they apply (Runtime without runtimes the computers no longer have). */
    val filters: RaftComputersRackFilters,
    /** The sort as it applies; null is none, the default (Mine first). */
    val sort: ComputersRackSort?,
    /** The Sort's choices in sections; null is Mine first. */
    val sortMenuSections: List<List<ComputersRackSortKey?>>,
) {
    /** Clear all shows. */
    val filtered: Boolean get() = filters.active

    /** Sort is filled: a sort is chosen (web `applied={sort !== null}`). */
    val sortApplied: Boolean get() = sort != null
}

internal fun raftComputersRackToolbarModel(
    state: RaftComputersRackToolbarState,
    columns: RaftRackRuntimeColumns,
): RaftComputersRackToolbarModel {
    val options = raftRackRuntimeFilterOptions(columns)
    return RaftComputersRackToolbarModel(
        runtimeOptions = options,
        filters = state.filters.copy(runtimes = raftRackEffectiveRuntimeFilter(state.filters.runtimes, options)),
        sort = raftRackEffectiveSort(state.storedSort, columns),
        sortMenuSections = raftRackSortMenuSections(columns),
    )
}

/** What the Computers page's toolbar asks of [RaftComputersRackToolbarStore] (the UI's only way in). */
internal sealed interface RaftComputersRackToolbarIntent {
    data class Search(val query: String) : RaftComputersRackToolbarIntent

    data class SetStatus(val status: RaftComputersRackStatusFilter) : RaftComputersRackToolbarIntent

    /** The Runtime sheet's checkbox, against the [options] the sheet offered. */
    data class ToggleRuntime(
        val runtime: RaftComputersRackRuntimeFilter,
        val options: List<RaftComputersRackRuntimeFilter>,
    ) : RaftComputersRackToolbarIntent

    data object ClearFilters : RaftComputersRackToolbarIntent

    data class SetGroupBy(val groupBy: ComputersRackGroupBy) : RaftComputersRackToolbarIntent

    /** The sort sheet's key item ([key] null: Mine first), against the [current] (effective) sort. */
    data class ChooseSortKey(val key: ComputersRackSortKey?, val current: ComputersRackSort?) : RaftComputersRackToolbarIntent

    /** The sort sheet's direction item, against the [current] (effective) sort. */
    data class ChooseSortDirection(val direction: ComputersRackSortDirection, val current: ComputersRackSort?) : RaftComputersRackToolbarIntent

    data class SetView(val view: ComputersRackView) : RaftComputersRackToolbarIntent
}

/**
 * Web `useComputersRackFilters` with `computersRackSortPreference` and the view preference: one
 * per app session, so the search and filters last while the app runs and Group, Sort and View
 * across launches. Call [onServer] whenever the current server is known or changes.
 *
 * Owned by the shell's store provider, one per shell window
 * (`RaftSharedStoreProvider.computersRackToolbarStore`): it outlives the Computers page, as the
 * web's zustand store outlives its panel, and goes with the window (or the account) that held it.
 */
internal class RaftComputersRackToolbarStore(private val preferences: KeyValueComputersRackPreferenceRepository) {
    private val mutableState =
        MutableStateFlow(
            RaftComputersRackToolbarState(
                groupBy = preferences.readGroupBy(),
                storedSort = preferences.readSort(),
                view = preferences.getComputersRackViewSync(),
            )
        )
    val state: StateFlow<RaftComputersRackToolbarState> = mutableState.asStateFlow()

    fun onServer(serverId: String?) = mutableState.update { it.onServer(serverId) }

    fun setQuery(query: String) = mutableState.update { it.copy(filters = it.filters.copy(query = query)) }

    fun setStatus(status: RaftComputersRackStatusFilter) = mutableState.update { it.copy(filters = it.filters.copy(status = status)) }

    fun setRuntimes(runtimes: List<RaftComputersRackRuntimeFilter>) =
        mutableState.update { it.copy(filters = it.filters.copy(runtimes = runtimes.distinct())) }

    /**
     * The Runtime sheet's checkbox (web `RackRuntimeSelect`): turns [runtime] on or off among the
     * runtimes chosen that are still [options], kept in the options' order; a runtime chosen
     * earlier that is no longer offered is dropped.
     */
    fun toggleRuntime(runtime: RaftComputersRackRuntimeFilter, options: List<RaftComputersRackRuntimeFilter>) =
        mutableState.update {
            val chosen = options.filter { option -> option in it.filters.runtimes }
            val next = if (runtime in chosen) chosen - runtime else options.filter { option -> option in chosen || option == runtime }
            it.copy(filters = it.filters.copy(runtimes = next))
        }

    fun clearFilters() = mutableState.update { it.clearedFilters() }

    fun setGroupBy(groupBy: ComputersRackGroupBy) {
        mutableState.update { it.copy(groupBy = groupBy) }
        preferences.writeGroupBy(groupBy)
    }

    /** Keeps [sort]; null goes back to the default (Mine first), forgetting the choice. */
    fun setSort(sort: ComputersRackSort?) {
        mutableState.update { it.copy(storedSort = sort) }
        preferences.writeSort(sort)
    }

    /**
     * The sort sheet's key item (web `RackSortSelect`): a new key starts at its own first direction,
     * Mine first ([key] null) is no sort; the [current] (effective) choice picked again changes
     * nothing.
     */
    fun chooseSortKey(key: ComputersRackSortKey?, current: ComputersRackSort?) {
        if (key != current?.key) setSort(raftRackChooseSortKey(key))
    }

    /** The sort sheet's direction item: keeps the [current] (effective) key; Mine first has none. */
    fun chooseSortDirection(direction: ComputersRackSortDirection, current: ComputersRackSort?) {
        if (current != null && direction != current.direction) setSort(current.copy(direction = direction))
    }

    fun setView(view: ComputersRackView) {
        mutableState.update { it.copy(view = preferences.selectComputersRackViewSync(view)) }
    }

    fun dispatch(intent: RaftComputersRackToolbarIntent) {
        when (intent) {
            is RaftComputersRackToolbarIntent.Search -> setQuery(intent.query)
            is RaftComputersRackToolbarIntent.SetStatus -> setStatus(intent.status)
            is RaftComputersRackToolbarIntent.ToggleRuntime -> toggleRuntime(intent.runtime, intent.options)
            RaftComputersRackToolbarIntent.ClearFilters -> clearFilters()
            // The same choice again writes nothing.
            is RaftComputersRackToolbarIntent.SetGroupBy -> if (intent.groupBy != state.value.groupBy) setGroupBy(intent.groupBy)
            is RaftComputersRackToolbarIntent.ChooseSortKey -> chooseSortKey(intent.key, intent.current)
            is RaftComputersRackToolbarIntent.ChooseSortDirection -> chooseSortDirection(intent.direction, intent.current)
            is RaftComputersRackToolbarIntent.SetView -> if (intent.view != state.value.view) setView(intent.view)
        }
    }
}
