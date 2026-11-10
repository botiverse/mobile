package build.raft.app

import build.raft.app.data.ComputersRackGroupBy
import build.raft.app.data.ComputersRackSort
import build.raft.app.data.ComputersRackSortDirection
import build.raft.app.data.ComputersRackSortDirection.Asc
import build.raft.app.data.ComputersRackSortDirection.Desc
import build.raft.app.data.ComputersRackSortKey
import build.raft.app.data.ComputersRackView
import build.raft.app.data.InMemoryRaftKeyValueStore
import build.raft.app.data.KeyValueComputersRackPreferenceRepository
import build.raft.app.data.RuntimeAccountUsageAccount
import build.raft.app.data.RuntimeAccountUsageHealth
import build.raft.app.data.RuntimeAccountUsageLoad
import build.raft.app.data.RuntimeAccountUsageProvider
import build.raft.app.data.RuntimeAccountUsageRead
import build.raft.app.data.RuntimeAccountUsageSnapshot
import build.raft.app.data.RuntimeAccountUsageWindow
import build.raft.app.data.RuntimeAccountUsageWindowStatus
import build.raft.app.data.SettingsComputerCreatorSnapshot
import build.raft.app.data.SettingsComputerSizeStatusSnapshot
import build.raft.app.data.SettingsComputerSnapshot
import build.raft.app.RaftComputersRackRuntimeFilter as Runtime
import build.raft.app.RaftComputersRackStatusFilter as Status
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Computers toolbar's logic, against the web's own tests (botiverse/slock
 * packages/web/src/features/computer/tests/computersRackFilter.behavior.test.ts,
 * computersRackSort.behavior.test.tsx and computersRackToolbar.behavior.test.tsx): the same
 * fleets and the same expected orders, as tables.
 */
class RaftComputersRackFiltersTest {
    private val catalog = listOf("claude", "codex", "kimi-sdk", "kimi", "grok", "cursor")
    private val labels = RaftRackGroupLabels(personal = "Personal computers", other = "Other")
    private val half = SettingsComputerSizeStatusSnapshot(availableBytes = 50.0, totalBytes = 100.0)

    // computersRackFilter.behavior.test.ts `fleet`.
    private val fleet =
        listOf(
            computer("hetzner-ax42-1", hostProvider = "hetzner", runtimes = listOf("claude", "codex"), owner = owner("u-stdrc", "stdrc")),
            computer("hetzner-ax42-2", hostProvider = "hetzner", runtimes = listOf("claude", "codex"), owner = owner("u-me", "artin")),
            computer("aws-gpu-1", hostProvider = "aws", runtimes = listOf("codex"), online = false, owner = owner("u-ricky", "ricky")),
            computer("mac-studio", runtimes = listOf("claude", "cursor"), owner = owner("u-stdrc", "stdrc")),
            computer("kimi-box-1", hostProvider = "vultr", runtimes = listOf("kimi-sdk")),
        )

    // computersRackToolbar.behavior.test.tsx `machines`: two Hetzner computers sharing a prefix, a
    // personal Mac (owner stdrc, Cursor too: Other), an AWS box with Codex only, an offline laptop.
    private val toolbarFleet =
        listOf(
            computer("ax1", "hetzner-ax42-1", hostProvider = "hetzner", runtimes = listOf("claude", "codex"), disk = half, memory = half),
            computer("ax2", "hetzner-ax42-2", hostProvider = "hetzner", disk = half, memory = half),
            computer("mac", "mac-studio", runtimes = listOf("claude", "cursor"), owner = owner("user-2", "stdrc"), disk = half, memory = half),
            computer("gpu", "gpu-box", hostProvider = "aws", runtimes = listOf("codex"), disk = half, memory = half),
            computer("old", "old-laptop", online = false),
        )

    // Claude used: ax1 90% (10% left), ax2 20% (80% left), mac 50% (50% left).
    private val toolbarUsage = claudeUsage("ax1" to 0.9, "ax2" to 0.2, "mac" to 0.5)

    // ---- Search, Status, Runtime ----

    @Test
    fun searchMatchesTheNameTheHostnameOrTheOwnerCaseInsensitively() {
        val cases =
            listOf(
                "AX42" to listOf("hetzner-ax42-1", "hetzner-ax42-2"),
                "ricky" to listOf("aws-gpu-1"),
                "mac-studio.local" to listOf("mac-studio"),
                "  STDRC " to listOf("hetzner-ax42-1", "mac-studio"),
                "" to fleet.map { it.id },
                "   " to fleet.map { it.id },
                "mainframe" to emptyList(),
            )
        cases.forEach { (query, expected) ->
            assertEquals(expected, ids(raftFilterRackComputers(fleet, RaftComputersRackFilters(query = query))), "query \"$query\"")
        }
    }

    @Test
    fun searchReadsTheOwnersDisplayNameOnlyWhileTheComputerHasAnOwner() {
        // Web `ownerName` needs `ownerUserId`; `machine.owner?.name` is searched either way.
        val named = SettingsComputerCreatorSnapshot(id = "u-1", name = "tenny", displayName = "Tenny Z")
        val owned = computer("a", owner = named)
        val unowned = computer("b", owner = named, ownerUserId = null)
        val cases = listOf("tenny z" to listOf("a"), "tenny" to listOf("a", "b"))
        cases.forEach { (query, expected) ->
            assertEquals(expected, ids(raftFilterRackComputers(listOf(owned, unowned), RaftComputersRackFilters(query = query))), query)
        }
    }

    @Test
    fun runtimeKeepsAComputerWithAnyChosenRuntimeAndOtherIsARuntimeWithNoUsageProvider() {
        val cases =
            listOf(
                RaftComputersRackFilters(runtimes = listOf(Runtime.Kimi)) to listOf("kimi-box-1"),
                RaftComputersRackFilters(runtimes = listOf(Runtime.Other)) to listOf("mac-studio"),
                RaftComputersRackFilters(runtimes = listOf(Runtime.Claude, Runtime.Codex), status = Status.Online) to
                    listOf("hetzner-ax42-1", "hetzner-ax42-2", "mac-studio"),
                RaftComputersRackFilters(runtimes = listOf(Runtime.Grok)) to emptyList(),
            )
        cases.forEach { (filters, expected) -> assertEquals(expected, ids(raftFilterRackComputers(fleet, filters)), "$filters") }
    }

    @Test
    fun statusKeepsOnlineOfflineOrThoseNeedingAttention() {
        val gib = 1024.0 * 1024.0 * 1024.0
        val computers =
            listOf(
                computer("fine"),
                computer("upgrade", upgradeAvailable = true),
                computer("low-disk", disk = SettingsComputerSizeStatusSnapshot(availableBytes = 5 * gib, totalBytes = 500 * gib)),
                computer("roomy", disk = SettingsComputerSizeStatusSnapshot(availableBytes = 150 * gib, totalBytes = 1000 * gib)),
                computer("away", online = false),
                computer("away-daemon", online = false, isComputer = false),
            )
        val cases =
            mapOf(
                Status.All to computers.map { it.id },
                Status.Online to listOf("fine", "upgrade", "low-disk", "roomy"),
                Status.Offline to listOf("away", "away-daemon"),
                // An upgrade waiting, low disk, or a Computer that is offline; a bare daemon offline is not.
                Status.Attention to listOf("upgrade", "low-disk", "away"),
            )
        assertEquals(Status.entries.toSet(), cases.keys, "every Status is covered")
        cases.forEach { (status, expected) ->
            assertEquals(expected, ids(raftFilterRackComputers(computers, RaftComputersRackFilters(status = status))), "$status")
        }
    }

    @Test
    fun runtimeOptionsAreTheProvidersPresentThenOtherAndAStaleChoiceFiltersNothing() {
        val columns = raftRackFleetRuntimeColumns(toolbarFleet, catalog)
        assertEquals(listOf(Runtime.Claude, Runtime.Codex, Runtime.Other), raftRackRuntimeFilterOptions(columns))
        assertEquals(emptyList(), raftRackRuntimeFilterOptions(raftRackFleetRuntimeColumns(emptyList(), catalog)))
        assertEquals(listOf(Runtime.Other), raftRackRuntimeFilterOptions(raftRackFleetRuntimeColumns(listOf(computer("x", runtimes = listOf("gemini"))), catalog)))

        // computersRackToolbar: "a runtime chosen earlier whose computers have gone filters nothing".
        val state = RaftComputersRackToolbarState(filters = RaftComputersRackFilters(runtimes = listOf(Runtime.Kimi)))
        val withKimi = toolbarFleet + computer("kim", "kimi-box", runtimes = listOf("kimi-sdk"))
        val applied = raftComputersRackToolbarModel(state, raftRackFleetRuntimeColumns(withKimi, catalog))
        assertEquals(listOf("kim"), rows(raftComputersRackLayout(withKimi, applied, ComputersRackGroupBy.None, emptyMap(), labels)))
        assertTrue(applied.filtered)

        val gone = raftComputersRackToolbarModel(state, raftRackFleetRuntimeColumns(toolbarFleet, catalog))
        assertEquals(emptyList(), gone.filters.runtimes)
        assertFalse(gone.filtered, "Clear all hides")
        assertEquals(listOf("gpu", "ax1", "ax2", "mac", "old"), rows(raftComputersRackLayout(toolbarFleet, gone, ComputersRackGroupBy.None, emptyMap(), labels)))
    }

    @Test
    fun clearAllShowsOnlyWhileSearchStatusOrRuntimeDiffersFromItsDefault() {
        val cases =
            listOf(
                RaftComputersRackFilters() to false,
                RaftComputersRackFilters(query = "   ") to false,
                RaftComputersRackFilters(query = "ax") to true,
                RaftComputersRackFilters(status = Status.Online) to true,
                RaftComputersRackFilters(runtimes = listOf(Runtime.Other)) to true,
            )
        cases.forEach { (filters, active) -> assertEquals(active, filters.active, "$filters") }
    }

    // ---- The list (computersRackToolbar.behavior.test.tsx) ----

    @Test
    fun theToolbarFleetRowsUnderEachSearchStatusAndRuntime() {
        val cases =
            listOf(
                // Name A to Z: gpu-box, hetzner-ax42-1, hetzner-ax42-2, mac-studio; old-laptop in the Offline section.
                RaftComputersRackFilters() to listOf("gpu", "ax1", "ax2", "mac", "old"),
                RaftComputersRackFilters(query = "AX42") to listOf("ax1", "ax2"),
                RaftComputersRackFilters(query = "gpu.local") to listOf("gpu"),
                RaftComputersRackFilters(query = "stdrc") to listOf("mac"),
                RaftComputersRackFilters(query = "mainframe") to emptyList(),
                RaftComputersRackFilters(status = Status.Offline) to listOf("old"),
                RaftComputersRackFilters(status = Status.Online) to listOf("gpu", "ax1", "ax2", "mac"),
                RaftComputersRackFilters(runtimes = listOf(Runtime.Codex)) to listOf("gpu", "ax1"),
                RaftComputersRackFilters(runtimes = listOf(Runtime.Codex, Runtime.Other)) to listOf("gpu", "ax1", "mac"),
            )
        cases.forEach { (filters, expected) ->
            val layout = layout(toolbarFleet, RaftComputersRackToolbarState(filters = filters))
            assertEquals(expected, rows(layout), "$filters")
            assertEquals(expected.isEmpty(), layout.empty)
        }
    }

    @Test
    fun offlineComputersWaitInOneSectionAtTheEndExceptUnderStatusOffline() {
        val all = layout(toolbarFleet, RaftComputersRackToolbarState())
        assertEquals(listOf("old"), ids(all.offline))
        assertFalse(all.offlineOpenByDefault, "folded while something is online")
        // Whatever the sort: Name Z to A still leaves it at the end.
        val reversed = layout(toolbarFleet, RaftComputersRackToolbarState(storedSort = ComputersRackSort(ComputersRackSortKey.Name, Desc)))
        assertEquals(listOf("mac", "ax2", "ax1", "gpu", "old"), rows(reversed))

        val offlineOnly = layout(toolbarFleet, RaftComputersRackToolbarState(filters = RaftComputersRackFilters(status = Status.Offline)))
        assertEquals(emptyList(), offlineOnly.offline, "no section under Status: Offline")
        assertEquals(listOf("old"), ids(offlineOnly.groups.single().computers))

        // A search that leaves only offline computers opens the section by default.
        val onlyAway = layout(toolbarFleet, RaftComputersRackToolbarState(filters = RaftComputersRackFilters(query = "old")))
        assertTrue(onlyAway.offlineOpenByDefault)
        assertEquals(listOf("old"), ids(onlyAway.offline))
    }

    // ---- Group ----

    @Test
    fun groupOptionsAndTheToolbarFleetsGroups() {
        assertEquals(listOf(ComputersRackGroupBy.None, ComputersRackGroupBy.Prefix, ComputersRackGroupBy.Provider), ComputersRackGroupBy.entries)
        val cases =
            mapOf(
                ComputersRackGroupBy.None to listOf("all"),
                ComputersRackGroupBy.Prefix to listOf("prefix:hetzner-ax42", "fallback:prefix"),
                ComputersRackGroupBy.Provider to listOf("provider:aws", "provider:hetzner", "fallback:provider"),
            )
        cases.forEach { (groupBy, expected) ->
            val groups = layout(toolbarFleet, RaftComputersRackToolbarState(groupBy = groupBy)).groups
            assertEquals(expected, groups.map { it.key }, "$groupBy")
        }
        val prefix = layout(toolbarFleet, RaftComputersRackToolbarState(groupBy = ComputersRackGroupBy.Prefix)).groups
        assertEquals(2, prefix.first().computers.size)
        val provider = layout(toolbarFleet, RaftComputersRackToolbarState(groupBy = ComputersRackGroupBy.Provider)).groups
        assertEquals(RaftRackGroup("fallback:provider", "Personal computers", fallback = true, computers = listOf(toolbarFleet[2])), provider.last())
    }

    @Test
    fun aNamePrefixDropsATrailingNumberAndAPrefixOfOneGoesToOtherLast() {
        val groups = raftGroupRackComputers(fleet, ComputersRackGroupBy.Prefix, labels, rank = null)
        assertEquals(listOf("hetzner-ax42" to 2, "Other" to 3), groups.map { it.title to it.computers.size })
    }

    @Test
    fun namePrefixGroupsMatchTheWebsTable() {
        // computersRackFilter.behavior.test.ts: each case's groups as titles then members, Other last.
        val cases: List<Pair<List<String>, List<List<String>>>> =
            listOf(
                // #triage:#291: real names; artin-hostinger-root alone at "artin" takes the artin-fluence pair up.
                listOf(
                    "armbian-1", "armbian-jojo", "armbian-jzm",
                    "artin-fluence-claude", "artin-fluence-root", "artin-hostinger-root",
                    "Cindys-MacBook-Air.local", "DESKTOP-F431GV8",
                    "hetzner-ax42-1", "hetzner-ax42-4",
                    "hetzner-ex63-3", "hetzner-ex63-4", "hetzner-ex63-6",
                ) to
                    listOf(
                        listOf("armbian", "armbian-1", "armbian-jojo", "armbian-jzm"),
                        listOf("artin", "artin-fluence-claude", "artin-fluence-root", "artin-hostinger-root"),
                        listOf("hetzner-ax42", "hetzner-ax42-1", "hetzner-ax42-4"),
                        listOf("hetzner-ex63", "hetzner-ex63-3", "hetzner-ex63-4", "hetzner-ex63-6"),
                        listOf("Other", "Cindys-MacBook-Air.local", "DESKTOP-F431GV8"),
                    ),
                // Without the lone one, the pair keeps its longer prefix.
                listOf("artin-fluence-claude", "artin-fluence-root", "solo") to
                    listOf(listOf("artin-fluence", "artin-fluence-claude", "artin-fluence-root"), listOf("Other", "solo")),
                // The title is the most common spelling, then the smallest by code point ("B" < "b").
                listOf("Build-a", "build-b") to listOf(listOf("Build", "Build-a", "build-b")),
                listOf("build-b", "Build-a") to listOf(listOf("Build", "build-b", "Build-a")),
                listOf("build-c", "Build-a", "build-b") to listOf(listOf("build", "build-c", "Build-a", "build-b")),
                // "_" and "." split words too.
                listOf("gpu_node_1", "gpu_node_2", "gpu.node.3") to listOf(listOf("gpu_node", "gpu_node_1", "gpu_node_2", "gpu.node.3")),
                // A whole name that prefixes another joins it.
                listOf("armbian", "armbian-2", "pi") to listOf(listOf("armbian", "armbian", "armbian-2"), listOf("Other", "pi")),
                listOf("web-1", "web-2", "web-10") to listOf(listOf("web", "web-1", "web-2", "web-10")),
                // All unique: all Other.
                listOf("alpha", "beta-1", "gamma.local") to listOf(listOf("Other", "alpha", "beta-1", "gamma.local")),
                // Two families under one word keep their own groups.
                listOf("gpu-a-1", "gpu-a-2", "gpu-b-1", "gpu-b-2") to
                    listOf(listOf("gpu-a", "gpu-a-1", "gpu-a-2"), listOf("gpu-b", "gpu-b-1", "gpu-b-2")),
                // A lone name under them takes the smallest family up with it; the larger keeps its prefix.
                listOf("gpu-a-1", "gpu-a-2", "gpu-a-3", "gpu-b-1", "gpu-b-2", "gpu-c") to
                    listOf(listOf("gpu", "gpu-b-1", "gpu-b-2", "gpu-c"), listOf("gpu-a", "gpu-a-1", "gpu-a-2", "gpu-a-3")),
                // The same name twice groups (default hostnames).
                listOf("raspberrypi", "raspberrypi", "ubuntu") to
                    listOf(listOf("raspberrypi", "raspberrypi", "raspberrypi"), listOf("Other", "ubuntu")),
            )
        cases.forEach { (names, expected) ->
            val groups = raftGroupRackComputers(names.mapIndexed { index, name -> computer("$index", name) }, ComputersRackGroupBy.Prefix, labels, rank = null)
            assertEquals(expected, groups.map { listOf(it.title) + it.computers.map(SettingsComputerSnapshot::name) }, "$names")
        }
    }

    @Test
    fun namePrefixGroupsOfOddNames() {
        val cases: List<Pair<List<String>, List<RaftRackNamePrefix?>>> =
            listOf(
                emptyList<String>() to emptyList(),
                listOf("x.local", "X-local") to List(2) { RaftRackNamePrefix("x-local", "X-local") },
                // A name with no words goes to Other.
                listOf("", "  ", "-", "a") to List(4) { null },
            )
        cases.forEach { (names, expected) -> assertEquals(expected, raftRackNamePrefixGroups(names), "$names") }
    }

    @Test
    fun namePrefixGroupsDoNotDependOnTheOrderGiven() {
        val names =
            listOf(
                "armbian-1", "Armbian-jojo", "armbian-jzm", "artin-fluence-claude", "artin-fluence-root", "artin-hostinger-root",
                "gpu-a-1", "gpu-a-2", "gpu-b-1", "gpu-b-2", "gpu-c", "raspberrypi", "RaspberryPi", "solo",
            )
        fun byName(order: List<String>) = order.zip(raftRackNamePrefixGroups(order)).toMap()
        val expected = byName(names)
        assertEquals(RaftRackNamePrefix("artin", "artin"), expected["artin-hostinger-root"])
        assertEquals(RaftRackNamePrefix("armbian", "armbian"), expected["Armbian-jojo"])
        // The web test's shuffles.
        for (seed in 1..20) {
            val shuffled = names.toMutableList()
            for (i in shuffled.size - 1 downTo 1) {
                val j = (seed * 7919 + i * 104729) % (i + 1)
                shuffled[i] = shuffled[j].also { shuffled[j] = shuffled[i] }
            }
            assertEquals(expected, byName(shuffled), "order $shuffled")
        }
    }

    @Test
    fun aFallbackGroupNeverMergesWithARealOneOfTheSameName() {
        val byPrefix = raftGroupRackComputers(listOf(computer("other-1"), computer("other-2"), computer("solo")), ComputersRackGroupBy.Prefix, labels, rank = null)
        assertEquals(listOf(Triple("prefix:other", "other", 2), Triple("fallback:prefix", "Other", 1)), byPrefix.map { Triple(it.key, it.title, it.computers.size) })
        // An id outside HOST_PROVIDER_LABELS is no provider.
        val byProvider =
            raftGroupRackComputers(listOf(computer("a", hostProvider = "none"), computer("b")), ComputersRackGroupBy.Provider, labels, rank = null)
        assertEquals(listOf("fallback:provider" to 2), byProvider.map { it.key to it.computers.size })
    }

    @Test
    fun providerGroupsGoAToZWithNoProviderLastOrByTheirBestMemberUnderASort() {
        assertEquals(
            listOf("AWS", "Hetzner Cloud", "Vultr", "Personal computers"),
            raftGroupRackComputers(fleet, ComputersRackGroupBy.Provider, labels, rank = null).map { it.title },
        )
        // Under a sort, the group holding the first-ranked computer leads; unranked groups follow A to Z.
        val rank: (SettingsComputerSnapshot) -> Int? = { if (it.id == "hetzner-ax42-2") 0 else if (it.id == "kimi-box-1") 1 else null }
        assertEquals(
            listOf("Hetzner Cloud", "Vultr", "AWS", "Personal computers"),
            raftGroupRackComputers(fleet, ComputersRackGroupBy.Provider, labels, rank).map { it.title },
        )
    }

    @Test
    fun providerGroupsUnderARemainingSortFollowTheirBestComputerAndTurnRoundWithTheDirection() {
        // computersRackToolbar: Claude left Hetzner ax1 80%, ax2 70%; AWS gpu 10%; the Mac 50% (Personal, last).
        val list = toolbarFleet.map { if (it.id == "gpu") it.copy(runtimes = listOf("claude", "codex")) else it }
        val usage = claudeUsage("ax1" to 0.2, "ax2" to 0.3, "gpu" to 0.9, "mac" to 0.5)
        val cases =
            mapOf(
                Desc to (listOf("provider:hetzner", "provider:aws", "fallback:provider") to listOf("ax1", "ax2", "gpu", "mac", "old")),
                Asc to (listOf("provider:aws", "provider:hetzner", "fallback:provider") to listOf("gpu", "ax2", "ax1", "mac", "old")),
            )
        cases.forEach { (direction, expected) ->
            val state =
                RaftComputersRackToolbarState(
                    groupBy = ComputersRackGroupBy.Provider,
                    storedSort = ComputersRackSort(ComputersRackSortKey.Remaining(RuntimeAccountUsageProvider.Claude), direction),
                )
            val result = layout(list, state, usage)
            assertEquals(expected, result.groups.map { it.key } to rows(result), "$direction")
        }
    }

    @Test
    fun underTheNameSortGroupsHaveNoRank() {
        assertNull(raftRackGroupRank(fleet, RAFT_RACK_DEFAULT_SORT, emptyMap()))
        val rank = raftRackGroupRank(fleet, ComputersRackSort(ComputersRackSortKey.Disk, Desc), emptyMap())
        // No disk report: no rank.
        assertNull(rank?.invoke(fleet.first()))
    }

    // ---- Sort ----

    @Test
    fun remainingIsTheTightestWindowOfTheTightestAccountReadByStatus() {
        val cases: List<Triple<String, RuntimeAccountUsageLoad?, Double?>> =
            listOf(
                // Windows in any order and with any ids: the most used counts.
                Triple("most used", loaded(account(win("w", 0.2), win("s", 0.75))), 0.25),
                Triple("most used, reversed", loaded(account(win("s", 0.75), win("w", 0.2))), 0.25),
                // A window that can't be read doesn't count; none left to read is no value.
                Triple("unreadable skipped", loaded(account(unreadable(), win("w", 0.4))), 0.6),
                Triple("only unreadable", loaded(account(unreadable())), null),
                // A reached limit is fully used, with or without its ratio.
                Triple("limit, no ratio", loaded(account(limit(null), win("w", 0.1))), 0.0),
                Triple("limit with ratio", loaded(account(limit(1.0))), 0.0),
                // Several accounts: the one with the least left.
                Triple("tightest account", loaded(account(win("s", 0.3)), account(win("s", 0.9))), 0.1),
                // Rate limited has nothing left; reauth / unsupported / error say nothing.
                Triple("rate limited", loaded(account(win("s", 0.2), health = RuntimeAccountUsageHealth.RateLimited)), 0.0),
                Triple("reauth", loaded(account(win("s", 0.2), health = RuntimeAccountUsageHealth.ReauthRequired)), null),
                Triple("unsupported", loaded(account(win("s", 0.2), health = RuntimeAccountUsageHealth.Unsupported)), null),
                Triple("error", loaded(account(win("s", 0.2), health = RuntimeAccountUsageHealth.Error)), null),
                Triple("unknown health", loaded(account(win("s", 0.2), health = RuntimeAccountUsageHealth.Unknown)), null),
                // Another account still counts beside one that needs signing in.
                Triple("reauth beside ok", loaded(account(health = RuntimeAccountUsageHealth.ReauthRequired), account(win("s", 0.5))), 0.5),
                Triple("missing", RuntimeAccountUsageLoad.Loaded(RuntimeAccountUsageRead.Missing), null),
                Triple("not read", null, null),
                Triple("forbidden", RuntimeAccountUsageLoad.Forbidden, null),
                Triple("failed", RuntimeAccountUsageLoad.Failed("x", retryable = true), null),
            )
        cases.forEach { (name, load, expected) -> assertNear(expected, raftRackRemaining(load), name) }
    }

    @Test
    fun aRemainingSortPutsMostRemainingFirstAndNoValueLastBothWays() {
        val computers = listOf(computer("a"), computer("b"), computer("c"), computer("d", runtimes = listOf("codex")), computer("e"), computer("f"))
        val usage =
            mapOf(
                "a" to claudeLoad(account(win("s", 0.2), win("w", 0.9))), // 0.1 left
                "b" to claudeLoad(account(limit(null))), // 0 left
                "c" to claudeLoad(account(win("s", 0.4))), // 0.6 left
                "e" to claudeLoad(account(win("s", 0.1), health = RuntimeAccountUsageHealth.ReauthRequired)), // no value
                "f" to claudeLoad(account(win("s", 0.1), health = RuntimeAccountUsageHealth.RateLimited)), // 0 left
            )
        val key = ComputersRackSortKey.Remaining(RuntimeAccountUsageProvider.Claude)
        assertEquals(Desc, raftRackFirstSortDirection(key))
        val cases = mapOf(Desc to listOf("c", "a", "b", "f", "d", "e"), Asc to listOf("b", "f", "a", "c", "d", "e"))
        cases.forEach { (direction, expected) ->
            assertEquals(expected, ids(raftSortRackComputers(computers, ComputersRackSort(key, direction), usage)), "$direction")
        }
    }

    @Test
    fun everySortKeyBothWaysOnTheSortFleet() {
        // computersRackSort.behavior.test.tsx: disk used bravo 90%, alpha 50%, charlie 20%; delta offline
        // (no disk, memory or usage). Names differ in case, which A to Z ignores. Claude used: charlie
        // 90%, alpha 30%; bravo has nothing to read.
        val computers =
            listOf(
                sortComputer("charlie", "charlie-box", 0.2, 0.7),
                sortComputer("delta", "delta-box", null, null, online = false),
                sortComputer("alpha", "alpha-box", 0.5, 0.1),
                sortComputer("bravo", "Bravo-box", 0.9, 0.4),
            )
        val usage = claudeUsage("charlie" to 0.9, "alpha" to 0.3)
        val claude = ComputersRackSortKey.Remaining(RuntimeAccountUsageProvider.Claude)
        // The rows as the list shows them: the Offline section (delta) last whichever way the sort goes.
        val cases =
            listOf(
                ComputersRackSort(ComputersRackSortKey.Name, Asc) to listOf("alpha", "bravo", "charlie", "delta"),
                ComputersRackSort(ComputersRackSortKey.Name, Desc) to listOf("charlie", "bravo", "alpha", "delta"),
                ComputersRackSort(ComputersRackSortKey.Disk, Desc) to listOf("bravo", "alpha", "charlie", "delta"),
                ComputersRackSort(ComputersRackSortKey.Disk, Asc) to listOf("charlie", "alpha", "bravo", "delta"),
                ComputersRackSort(ComputersRackSortKey.Memory, Desc) to listOf("charlie", "bravo", "alpha", "delta"),
                ComputersRackSort(ComputersRackSortKey.Memory, Asc) to listOf("alpha", "bravo", "charlie", "delta"),
                ComputersRackSort(claude, Desc) to listOf("alpha", "charlie", "bravo", "delta"),
                ComputersRackSort(claude, Asc) to listOf("charlie", "alpha", "bravo", "delta"),
            )
        cases.forEach { (sort, expected) ->
            assertEquals(expected, rows(layout(computers, RaftComputersRackToolbarState(storedSort = sort), usage)), "$sort")
        }
        // The pure sort: a key with no value (offline delta's disk) is last both ways.
        ComputersRackSortDirection.entries.forEach { direction ->
            assertEquals("delta", raftSortRackComputers(computers, ComputersRackSort(ComputersRackSortKey.Disk, direction), usage).last().id)
        }
    }

    @Test
    fun theToolbarFleetUnderARemainingSort() {
        val claude = ComputersRackSortKey.Remaining(RuntimeAccountUsageProvider.Claude)
        // Most remaining first: ax2 80%, mac 50%, ax1 10%; gpu has no Claude: last.
        val cases = mapOf(Desc to listOf("ax2", "mac", "ax1", "gpu", "old"), Asc to listOf("ax1", "mac", "ax2", "gpu", "old"))
        cases.forEach { (direction, expected) ->
            assertEquals(expected, rows(layout(toolbarFleet, RaftComputersRackToolbarState(storedSort = ComputersRackSort(claude, direction)), toolbarUsage)))
        }
    }

    @Test
    fun theNameSortFallsBackToTheHostnameAndTiesKeepTheListsOrder() {
        val computers = listOf(computer("b", " ", hostname = "Beta"), computer("a2", "Alpha"), computer("a1", "alpha"), computer("none", " ", hostname = null))
        assertEquals(listOf("a2", "a1", "b", "none"), ids(raftSortRackComputers(computers, RAFT_RACK_DEFAULT_SORT, emptyMap())))
        assertEquals(listOf("b", "a2", "a1", "none"), ids(raftSortRackComputers(computers, ComputersRackSort(ComputersRackSortKey.Name, Desc), emptyMap())))
    }

    @Test
    fun theDefaultSortIsNameAToZAndAStoredRuntimeSortWhoseRuntimeIsGoneFallsBack() {
        val columns = RaftRackRuntimeColumns(usage = listOf(RaftRackUsageColumn(RuntimeAccountUsageProvider.Claude, "claude")), other = emptyList())
        val claudeAsc = ComputersRackSort(ComputersRackSortKey.Remaining(RuntimeAccountUsageProvider.Claude), Asc)
        val cases =
            listOf(
                null to RAFT_RACK_DEFAULT_SORT,
                ComputersRackSort(ComputersRackSortKey.Remaining(RuntimeAccountUsageProvider.Codex), Desc) to RAFT_RACK_DEFAULT_SORT,
                claudeAsc to claudeAsc,
                ComputersRackSort(ComputersRackSortKey.Disk, Asc) to ComputersRackSort(ComputersRackSortKey.Disk, Asc),
            )
        assertEquals(ComputersRackSort(ComputersRackSortKey.Name, Asc), RAFT_RACK_DEFAULT_SORT)
        cases.forEach { (stored, expected) -> assertEquals(expected, raftRackEffectiveSort(stored, columns), "$stored") }
    }

    @Test
    fun sortMenuDirectionsAndTheirWords() {
        val columns = raftRackFleetRuntimeColumns(toolbarFleet, catalog)
        val claude = ComputersRackSortKey.Remaining(RuntimeAccountUsageProvider.Claude)
        val codex = ComputersRackSortKey.Remaining(RuntimeAccountUsageProvider.Codex)
        assertEquals(
            listOf(listOf(ComputersRackSortKey.Name), listOf(claude, codex), listOf(ComputersRackSortKey.Disk, ComputersRackSortKey.Memory)),
            raftRackSortMenuSections(columns),
        )
        // No usage runtime: the section goes.
        assertEquals(2, raftRackSortMenuSections(RaftRackRuntimeColumns(emptyList(), listOf("cursor"))).size)
        assertEquals(listOf("Claude Code", "Codex", "Kimi", "Grok"), RuntimeAccountUsageProvider.entries.map(::raftRackProviderName))

        val cases =
            listOf(
                ComputersRackSortKey.Name to listOf(RaftRackSortDirectionCopy.NameAsc, RaftRackSortDirectionCopy.NameDesc),
                claude to listOf(RaftRackSortDirectionCopy.RemainingDesc, RaftRackSortDirectionCopy.RemainingAsc),
                ComputersRackSortKey.Disk to listOf(RaftRackSortDirectionCopy.UsedDesc, RaftRackSortDirectionCopy.UsedAsc),
                ComputersRackSortKey.Memory to listOf(RaftRackSortDirectionCopy.UsedDesc, RaftRackSortDirectionCopy.UsedAsc),
            )
        cases.forEach { (key, expected) ->
            // The sheet lists the key's first direction first, and a new key starts at it.
            val directions = raftRackSortDirections(key)
            assertEquals(expected, directions.map { raftRackSortDirectionCopy(key, it) }, "$key")
            assertEquals(ComputersRackSort(key, directions.first()), raftRackChooseSortKey(key))
        }
    }

    @Test
    fun textOrderIsNumericAndIgnoresCaseAndAccents() {
        val cases =
            listOf(
                Triple("web-2", "web-10", -1),
                Triple("web-010", "web-10", 0),
                Triple("alpha-box", "Bravo-box", -1),
                Triple("Alpha", "alpha", 0),
                Triple("émile", "Emile", 0),
                Triple("émile", "fred", -1),
                Triple("a-1", "a1", -1), // punctuation before digits
                Triple("a1", "aa", -1), // digits before letters
                Triple("AWS", "Hetzner Cloud", -1),
                Triple("box", "box-2", -1),
                Triple("", "a", -1),
            )
        cases.forEach { (a, b, expected) ->
            assertEquals(expected, raftRackCompareText(a, b), "$a vs $b")
            assertEquals(-expected, raftRackCompareText(b, a), "$b vs $a")
        }
    }

    // ---- Toolbar state and persistence ----

    @Test
    fun clearAllResetsSearchStatusAndRuntimeButNeverGroupSortOrView() {
        val toolbar = RaftComputersRackToolbarStore(KeyValueComputersRackPreferenceRepository(InMemoryRaftKeyValueStore()))
        toolbar.onServer("server-1")
        toolbar.setGroupBy(ComputersRackGroupBy.Prefix)
        toolbar.setSort(ComputersRackSort(ComputersRackSortKey.Disk, Desc))
        toolbar.setView(ComputersRackView.List)
        assertFalse(toolbar.model().filtered, "Group, Sort and View are not filters")

        toolbar.setQuery("ax")
        assertTrue(toolbar.model().filtered)
        toolbar.setQuery("")
        assertFalse(toolbar.model().filtered)

        toolbar.setQuery("hetzner")
        toolbar.setStatus(Status.Online)
        toolbar.toggleRuntime(Runtime.Claude, listOf(Runtime.Claude, Runtime.Codex, Runtime.Other))
        assertTrue(toolbar.model().filtered)

        toolbar.clearFilters()
        val state = toolbar.state.value
        assertEquals(RaftComputersRackFilters(), state.filters)
        assertFalse(toolbar.model().filtered)
        assertEquals(ComputersRackGroupBy.Prefix, state.groupBy)
        assertEquals(ComputersRackSort(ComputersRackSortKey.Disk, Desc), state.storedSort)
        assertEquals(ComputersRackView.List, state.view)
    }

    @Test
    fun anotherServerStartsWithNoSearchOrFiltersAndKeepsGroupSortAndView() {
        val toolbar = RaftComputersRackToolbarStore(KeyValueComputersRackPreferenceRepository(InMemoryRaftKeyValueStore()))
        toolbar.onServer("server-1")
        toolbar.setGroupBy(ComputersRackGroupBy.Prefix)
        toolbar.setQuery("hetzner")
        toolbar.setStatus(Status.Online)

        // The same server again (the page shown again) keeps them.
        toolbar.onServer("server-1")
        assertEquals(RaftComputersRackFilters(query = "hetzner", status = Status.Online), toolbar.state.value.filters)

        val cases = listOf("server-2", null, "server-1")
        cases.forEach { server ->
            toolbar.setQuery("x")
            toolbar.onServer(server)
            assertEquals(RaftComputersRackFilters(), toolbar.state.value.filters, "on $server")
            assertEquals(server, toolbar.state.value.serverId)
            assertEquals(ComputersRackGroupBy.Prefix, toolbar.state.value.groupBy)
        }
    }

    @Test
    fun groupSortAndViewAreKeptOnThisDeviceAndComeBackOnTheNextVisit() {
        val backing = InMemoryRaftKeyValueStore()
        val first = RaftComputersRackToolbarStore(KeyValueComputersRackPreferenceRepository(backing))
        assertEquals(RaftComputersRackToolbarState(), first.state.value, "defaults: no grouping, Name A to Z, cards")
        first.setGroupBy(ComputersRackGroupBy.Provider)
        first.setSort(ComputersRackSort(ComputersRackSortKey.Disk, Asc))
        first.setView(ComputersRackView.List)
        first.setQuery("kept for this session only")

        val next = RaftComputersRackToolbarStore(KeyValueComputersRackPreferenceRepository(backing)).state.value
        assertEquals(ComputersRackGroupBy.Provider, next.groupBy)
        assertEquals(ComputersRackSort(ComputersRackSortKey.Disk, Asc), next.storedSort)
        assertEquals(ComputersRackView.List, next.view)
        assertEquals(RaftComputersRackFilters(), next.filters, "search, Status and Runtime are never stored")
    }

    @Test
    fun theSortSheetStartsANewKeyAtItsFirstDirectionAndADirectionKeepsTheKey() {
        val toolbar = RaftComputersRackToolbarStore(KeyValueComputersRackPreferenceRepository(InMemoryRaftKeyValueStore()))
        val columns = raftRackFleetRuntimeColumns(toolbarFleet, catalog)
        fun sort() = raftComputersRackToolbarModel(toolbar.state.value, columns).sort
        assertFalse(raftComputersRackToolbarModel(toolbar.state.value, columns).sortApplied)

        val claude = ComputersRackSortKey.Remaining(RuntimeAccountUsageProvider.Claude)
        val steps: List<Pair<() -> Unit, ComputersRackSort>> =
            listOf(
                { toolbar.chooseSortKey(ComputersRackSortKey.Disk, sort()) } to ComputersRackSort(ComputersRackSortKey.Disk, Desc),
                { toolbar.chooseSortDirection(Asc, sort()) } to ComputersRackSort(ComputersRackSortKey.Disk, Asc),
                // The key picked again changes nothing.
                { toolbar.chooseSortKey(ComputersRackSortKey.Disk, sort()) } to ComputersRackSort(ComputersRackSortKey.Disk, Asc),
                { toolbar.chooseSortKey(claude, sort()) } to ComputersRackSort(claude, Desc),
                { toolbar.chooseSortKey(ComputersRackSortKey.Name, sort()) } to ComputersRackSort(ComputersRackSortKey.Name, Asc),
                { toolbar.chooseSortDirection(Desc, sort()) } to ComputersRackSort(ComputersRackSortKey.Name, Desc),
            )
        steps.forEachIndexed { index, (step, expected) ->
            step()
            assertEquals(expected, sort(), "step $index")
            assertEquals(expected != RAFT_RACK_DEFAULT_SORT, raftComputersRackToolbarModel(toolbar.state.value, columns).sortApplied)
        }
    }

    @Test
    fun theRuntimeSheetKeepsTheOptionsOrderAndDropsARuntimeNoLongerOffered() {
        val toolbar = RaftComputersRackToolbarStore(KeyValueComputersRackPreferenceRepository(InMemoryRaftKeyValueStore()))
        val options = listOf(Runtime.Claude, Runtime.Codex, Runtime.Other)
        toolbar.setRuntimes(listOf(Runtime.Kimi))
        val steps =
            listOf(
                Runtime.Other to listOf(Runtime.Other),
                Runtime.Codex to listOf(Runtime.Codex, Runtime.Other),
                Runtime.Other to listOf(Runtime.Codex),
                Runtime.Codex to emptyList(),
            )
        steps.forEach { (runtime, expected) ->
            toolbar.toggleRuntime(runtime, options)
            assertEquals(expected, toolbar.state.value.filters.runtimes, "after $runtime")
        }
    }

    // ---- Helpers ----

    private fun RaftComputersRackToolbarStore.model(): RaftComputersRackToolbarModel =
        raftComputersRackToolbarModel(state.value, raftRackFleetRuntimeColumns(toolbarFleet, catalog))

    private fun layout(
        computers: List<SettingsComputerSnapshot>,
        state: RaftComputersRackToolbarState,
        usage: RaftRackUsageLoads = toolbarUsage,
    ): RaftComputersRackLayout =
        raftComputersRackLayout(
            computers,
            raftComputersRackToolbarModel(state, raftRackFleetRuntimeColumns(computers, catalog)),
            state.groupBy,
            usage,
            labels,
        )

    /** The rows top to bottom: the groups, then the Offline section unfolded. */
    private fun rows(layout: RaftComputersRackLayout): List<String> = ids(layout.groups.flatMap { it.computers } + layout.offline)

    private fun ids(computers: List<SettingsComputerSnapshot>): List<String> = computers.map { it.id }

    private fun owner(id: String, name: String) = SettingsComputerCreatorSnapshot(id = id, name = name, displayName = name)

    private fun computer(
        id: String,
        name: String = id,
        online: Boolean = true,
        runtimes: List<String> = listOf("claude"),
        hostname: String? = "$id.local",
        hostProvider: String? = null,
        owner: SettingsComputerCreatorSnapshot? = null,
        ownerUserId: String? = owner?.id,
        isComputer: Boolean = true,
        upgradeAvailable: Boolean = false,
        disk: SettingsComputerSizeStatusSnapshot? = null,
        memory: SettingsComputerSizeStatusSnapshot? = null,
    ) = SettingsComputerSnapshot(
        id = id,
        name = name,
        daemonVersion = "",
        computerVersion = "1.0.53",
        isComputer = isComputer,
        online = online,
        hostname = hostname,
        runtimes = runtimes,
        upgradeAvailable = upgradeAvailable,
        ownerUserId = ownerUserId,
        owner = owner,
        hostProvider = hostProvider,
        diskStatus = disk,
        memoryStatus = memory,
    )

    private fun sortComputer(id: String, name: String, diskUsed: Double?, memoryUsed: Double?, online: Boolean = true): SettingsComputerSnapshot {
        val gb = 1024.0 * 1024.0 * 1024.0
        return computer(
            id,
            name,
            online = online,
            disk = diskUsed?.let { SettingsComputerSizeStatusSnapshot(availableBytes = (1 - it) * 100 * gb, totalBytes = 100 * gb) },
            memory = memoryUsed?.let { SettingsComputerSizeStatusSnapshot(availableBytes = (1 - it) * 32 * gb, totalBytes = 32 * gb) },
        )
    }

    private fun win(id: String, used: Double) = RuntimeAccountUsageWindow(id, id, RuntimeAccountUsageWindowStatus.Ok, used, resetsAt = null)

    private fun limit(used: Double?) = RuntimeAccountUsageWindow("s", "s", RuntimeAccountUsageWindowStatus.LimitReached, used, resetsAt = null)

    private fun unreadable() = RuntimeAccountUsageWindow("x", "x", RuntimeAccountUsageWindowStatus.Unavailable, null, resetsAt = null)

    private fun account(vararg windows: RuntimeAccountUsageWindow, health: RuntimeAccountUsageHealth = RuntimeAccountUsageHealth.Ok) =
        RuntimeAccountUsageAccount(accountKey = "k${windows.size}", planLabel = null, identityLabel = null, health = health, windows = windows.toList())

    private fun loaded(vararg accounts: RuntimeAccountUsageAccount): RuntimeAccountUsageLoad =
        RuntimeAccountUsageLoad.Loaded(
            RuntimeAccountUsageRead.Available(stale = false, snapshot = RuntimeAccountUsageSnapshot("claude", collectedAt = null, accounts = accounts.toList()))
        )

    private fun claudeLoad(vararg accounts: RuntimeAccountUsageAccount) = mapOf(RuntimeAccountUsageProvider.Claude to loaded(*accounts))

    private fun claudeUsage(vararg used: Pair<String, Double>): RaftRackUsageLoads =
        used.associate { (id, ratio) -> id to claudeLoad(account(win("session", ratio))) }

    /** Remaining to the 1e-9, as a ratio's complement isn't exact in floating point. */
    private fun assertNear(expected: Double?, actual: Double?, message: String) {
        assertTrue(expected == actual || (expected != null && actual != null && abs(expected - actual) < 1e-9), "$message: $actual vs $expected")
    }
}
