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
import build.raft.app.thread.key
import build.raft.app.RaftComputersRackRuntimeFilter as Runtime
import build.raft.app.RaftComputersRackStatusFilter as Status
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Computers toolbar's logic, against the web's own tests (botiverse/slock
 * packages/web/src/features/computer/tests/computersRackFilter.behavior.test.ts,
 * computersRackSort.behavior.test.tsx and computersRackToolbar.behavior.test.tsx): the same
 * fleets and the same expected orders, as tables.
 */
class RaftComputersRackFiltersTest {
    private val nameAToZ = ComputersRackSort(ComputersRackSortKey.Name, Asc)
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

    // ---- The list (computersRackToolbar.behavior.test.tsx) ----

    @Test
    fun offlineComputersWaitInOneSectionAtTheEndExceptUnderStatusOffline() {
        val all = layout(toolbarFleet, RaftComputersRackToolbarState())
        assertEquals(listOf("old"), ids(all.offline))
        assertTrue(all.offlineOpenByDefault, "open by default even while something is online")
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
    fun theDefaultIsNoSortMineFirstAndAStoredRuntimeSortWhoseRuntimeIsGoneFallsBackToIt() {
        // web computersRackFilter.behavior.test.ts (#14479): no sort is the default; a chosen Name A to Z is kept.
        val columns = RaftRackRuntimeColumns(usage = listOf(RaftRackUsageColumn(RuntimeAccountUsageProvider.Claude, "claude")), other = emptyList())
        val claudeAsc = ComputersRackSort(ComputersRackSortKey.Remaining(RuntimeAccountUsageProvider.Claude), Asc)
        val cases =
            listOf(
                null to null,
                ComputersRackSort(ComputersRackSortKey.Remaining(RuntimeAccountUsageProvider.Codex), Desc) to null,
                nameAToZ to nameAToZ,
                claudeAsc to claudeAsc,
                ComputersRackSort(ComputersRackSortKey.Disk, Asc) to ComputersRackSort(ComputersRackSortKey.Disk, Asc),
            )
        cases.forEach { (stored, expected) -> assertEquals(expected, raftRackEffectiveSort(stored, columns), "$stored") }
    }

    // ---- Mine first (web #14479, artin #triage:#332) ----

    // web computersRackSort.behavior.test.tsx `mixed`: names run against the server's order. Mine
    // (user-1): yankee (owned), mike (attached); xray is Public (no owner), nobody's.
    private val mixed =
        listOf(
            computer("zulu", "zulu-box", owner = owner("user-2", "stdrc")),
            computer("yankee", "yankee-box", owner = owner("user-1", "artin")),
            computer("xray", "xray-box"),
            computer("mike", "mike-box", attached = true),
            computer("lima", "lima-box", owner = owner("user-2", "stdrc")),
        )

    @Test
    fun withNoSortChosenTheViewersOwnComputersLeadEachPartInTheListsOrder() {
        val cases =
            listOf(
                // Attached or owned leads; the rest, the Public one included, keep the server's order.
                Triple(RaftComputersRackToolbarState(), "user-1", listOf("yankee", "mike", "zulu", "xray", "lima")),
                Triple(RaftComputersRackToolbarState(), "user-2", listOf("zulu", "mike", "lima", "yankee", "xray")),
                // Signed out: only the attach flag counts, never a Public Computer's null owner.
                Triple(RaftComputersRackToolbarState(), null, listOf("mike", "zulu", "yankee", "xray", "lima")),
                // A sort someone chose is followed, mine or not.
                Triple(RaftComputersRackToolbarState(storedSort = nameAToZ), "user-1", listOf("lima", "mike", "xray", "yankee", "zulu")),
            )
        cases.forEach { (state, userId, expected) ->
            assertEquals(expected, rows(layout(mixed, state, emptyMap(), userId)), "${state.storedSort} as $userId")
        }
    }

    // ---- Needs attention first (web #15534, artin #triage:#527) ----

    @Test
    fun attentionFirstPutsComputersNeedingAttentionFirstKeepingEachPartsOrder() {
        // web computersRackAttentionOrder.behavior.test.ts
        val computers =
            listOf(
                computer("mine-ok"),
                computer("mine-update", upgradeAvailable = true),
                computer("other-ok"),
                computer("other-offline", online = false),
                computer("other-update", upgradeAvailable = true),
                computer("other-ok-2"),
            )
        assertEquals(
            listOf("mine-update", "other-offline", "other-update", "mine-ok", "other-ok", "other-ok-2"),
            ids(raftAttentionFirst(computers)),
        )
    }

    @Test
    fun attentionFirstFollowsServerVerdictWhenPresent() {
        val computers =
            listOf(
                computer("update-but-server-clear", upgradeAvailable = true, serverNeedsAttention = false),
                computer("server-flagged", serverNeedsAttention = true),
                computer("plain"),
            )
        assertEquals(
            listOf("server-flagged", "update-but-server-clear", "plain"),
            ids(raftAttentionFirst(computers)),
        )
        assertEquals(1, computers.count(::raftComputerNeedsAttention))
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
        assertEquals(RaftComputersRackToolbarState(), first.state.value, "defaults: no grouping, no sort (Mine first), cards")
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

    // ---- Helpers ----

    private fun RaftComputersRackToolbarStore.model(): RaftComputersRackToolbarModel =
        raftComputersRackToolbarModel(state.value, raftRackFleetRuntimeColumns(toolbarFleet, catalog))

    private fun layout(
        computers: List<SettingsComputerSnapshot>,
        state: RaftComputersRackToolbarState,
        usage: RaftRackUsageLoads = toolbarUsage,
        userId: String? = null,
    ): RaftComputersRackLayout =
        raftComputersRackLayout(
            computers,
            raftComputersRackToolbarModel(state, raftRackFleetRuntimeColumns(computers, catalog)),
            state.groupBy,
            usage,
            labels,
            userId,
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
        attached: Boolean = false,
        upgradeAvailable: Boolean = false,
        serverNeedsAttention: Boolean? = null,
        disk: SettingsComputerSizeStatusSnapshot? = null,
        memory: SettingsComputerSizeStatusSnapshot? = null,
    ) = SettingsComputerSnapshot(
        id = id,
        name = name,
        daemonVersion = "",
        computerVersion = "1.0.53",
        isComputer = isComputer,
        attachedByCurrentUser = attached,
        online = online,
        hostname = hostname,
        runtimes = runtimes,
        upgradeAvailable = upgradeAvailable,
        ownerUserId = ownerUserId,
        owner = owner,
        hostProvider = hostProvider,
        diskStatus = disk,
        memoryStatus = memory,
        serverNeedsAttention = serverNeedsAttention,
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
