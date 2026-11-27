package build.raft.app

import build.raft.app.data.BundledRuntimeCatalog
import build.raft.app.data.ExistingAgentRuntimeOptions
import build.raft.app.data.RuntimeCatalogSource
import build.raft.app.data.SharedCreateAgentRuntimeCatalog
import build.raft.app.data.SharedCreateAgentRuntimeOption
import build.raft.app.data.createAgentRuntimeForOptions
import build.raft.app.data.runtimeDeprecatedForAgent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #proj-runtime:#57 (web botiverse/slock #14568 334246a2e6 and #14584 d759141c94): whether a runtime
 * can be picked on a server is that server's answer, its runtime-options rows; the catalog (server,
 * cache, or the bundled table when neither answers) only names them. Rows are shaped as slock
 * runtimeAdmissionService `projectNewAgentRuntimeOptions` / `projectExistingAgentRuntimeOptions`
 * project them at d759141c94 (runtimeAdmissionService.test.ts, "agent_runtime_oar_antigravity on").
 */
class RuntimeServerVerdictTest {
    @Test
    fun createOffersAndLetsYouPickAntigravityWhereTheServerAdmitsIt() {
        // The bundled table no longer deprecates Antigravity (slock #15445); the server's rows decide.
        assertFalse(BundledRuntimeCatalog.entry("antigravity")!!.deprecated)
        val rows = newAgentRows(installed = setOf("claude", "codex", "antigravity"), extra = listOf(newRow("antigravity", installed = true)))
        val picker =
            runtimePickerRows(BundledRuntimeCatalog, listOf("claude", "codex", "antigravity"), selectedRuntimeId = "claude", showAll = false, options = rows)
        val antigravity = picker.rows.single { it.id == "antigravity" }
        assertEquals(RuntimePickerGroup.Ready, antigravity.group)
        assertTrue(antigravity.enabled)
        assertFalse(antigravity.deprecated)
        assertEquals("Antigravity CLI", antigravity.name)

        val supported = BundledRuntimeCatalog.creatableRuntimeIds(options = rows)
        assertTrue("antigravity" in supported, "supported=$supported")
        // Picking it sticks: the dialog keeps a runtime the server's rows offer.
        val catalog = SharedCreateAgentRuntimeCatalog(machineId = "m-1", options = rows)
        assertEquals("antigravity", createAgentRuntimeForOptions("antigravity", "m-1", catalog, supported, remembered = null))
    }

    @Test
    fun createDoesNotOfferAntigravityWhereTheServerDoesNotThoughTheComputerHasIt() {
        val rows = newAgentRows(installed = setOf("claude", "codex", "antigravity"))
        val picker =
            runtimePickerRows(BundledRuntimeCatalog, listOf("claude", "codex", "antigravity"), selectedRuntimeId = "claude", showAll = true, options = rows)
        assertTrue(picker.rows.none { it.id == "antigravity" }, "rows=${picker.rows.map { it.id }}")
        assertFalse("antigravity" in BundledRuntimeCatalog.creatableRuntimeIds(options = rows))
    }

    @Test
    fun withoutRowsTheServerCatalogDecidesAndTheBundledTableOnlyWhenTheServerDidNotAnswer() {
        val machine = listOf("claude", "antigravity")
        // GET /servers/:id/runtimes answered from a server before slock #15445: Antigravity is
        // `deprecated: true` there, so it is not offered, whatever the bundled table says.
        val served =
            BundledRuntimeCatalog.copy(
                catalogVersion = 1,
                runtimes = BundledRuntimeCatalog.runtimes.map { if (it.id == "antigravity") it.copy(deprecated = true) else it },
                source = RuntimeCatalogSource.Server
            )
        val fromServer = runtimePickerRows(served, machine, selectedRuntimeId = "claude", showAll = true)
        assertTrue(fromServer.rows.none { it.id == "antigravity" })
        assertFalse("antigravity" in served.creatableRuntimeIds())

        // It failed (no cache either): the bundled table is the fallback, and it offers Antigravity.
        val fallback = runtimePickerRows(BundledRuntimeCatalog, machine, selectedRuntimeId = "claude", showAll = false)
        assertEquals(RuntimePickerGroup.Ready, fallback.rows.single { it.id == "antigravity" }.group)
        assertTrue("antigravity" in BundledRuntimeCatalog.creatableRuntimeIds())

        // Once the computer's rows land they decide, even over the bundled fallback.
        val rows = listOf(newRow("claude", installed = true), newRow("antigravity", installed = true))
        val withRows = runtimePickerRows(BundledRuntimeCatalog, machine, selectedRuntimeId = "claude", showAll = false, options = rows)
        assertEquals(RuntimePickerGroup.Ready, withRows.rows.single { it.id == "antigravity" }.group)
    }

    @Test
    fun afterASwitchTheOldCurrentRowSaysNothingAboutTheNewRuntime() {
        // Rows read while the agent ran Copilot (deprecated); it has since switched to Codex CLI.
        val stale = existingRows(current = "copilot", installed = setOf("copilot", "codex"))
        assertFalse(runtimeDeprecatedForAgent("codex", stale, serverId = null, catalogDeprecated = { false }))
        val picker =
            runtimePickerRows(BundledRuntimeCatalog, listOf("copilot", "codex"), "codex", showAll = false, currentRuntimeId = "codex", options = stale.options)
        val codex = picker.rows.first()
        assertEquals("codex" to RuntimePickerGroup.Current, codex.id to codex.group)
        assertFalse(codex.deprecated)
        assertTrue(picker.rows.none { it.id == "copilot" && (it.group == RuntimePickerGroup.Current || it.deprecated) })

        // The other way round: rows that still mark Codex current say nothing about Copilot, so the
        // catalog answers for it.
        val staleCodex = existingRows(current = "codex", installed = setOf("copilot", "codex"))
        assertTrue(runtimeDeprecatedForAgent("copilot", staleCodex, serverId = null, catalogDeprecated = { it == "copilot" }))
        assertFalse(runtimeDeprecatedForAgent("copilot", staleCodex, serverId = null, catalogDeprecated = { false }))
        // No rows at all: the catalog.
        assertTrue(runtimeDeprecatedForAgent("copilot", null, serverId = null, catalogDeprecated = { it == "copilot" }))
    }

    @Test
    fun pickerRowsUsePerComputerCapabilitiesFromOptionsAndFallBackToCatalogOrBundledTable() {
        val opencodeOar =
            ResolvedRuntimeRaftCapabilities(
                midTurnSteering = true,
                subagents = false,
                compactionReported = false,
                source = RuntimeCapabilitySource.Machine
            )
        val codexMachineGap =
            ResolvedRuntimeRaftCapabilities(
                midTurnSteering = true,
                subagents = true,
                compactionReported = false,
                source = RuntimeCapabilitySource.Machine
            )
        val codexDefaultGap = codexMachineGap.copy(source = RuntimeCapabilitySource.Default)
        val copy =
            RuntimePickerCopy(
                groupCurrent = "Current",
                groupReady = "Ready",
                groupNotAvailable = "Not available",
                groupNeedsComputerUpdate = "Update",
                groupNeedsAppUpdate = "App update",
                groupNotInstalled = "Not installed",
                suffixNotInstalled = " (not installed)",
                suffixUpdateComputer = " (update)",
                suffixComingSoon = " (coming soon)",
                suffixDeprecated = " (deprecated)",
                needsAppUpdate = "Update app",
                recommended = "Recommended",
                limited = "Limited"
            )

        // 1. Machine-reported capabilities on runtime-options rows clear OpenCode's Limited badge
        //    and show Codex's machine-reported hint while keeping Recommended; absent capabilities
        //    on Cursor fall back to the bundled table (Limited + NoSteeringNoSubagents).
        val rows =
            listOf(
                newRow("codex", installed = true, raftCapabilities = codexMachineGap),
                newRow("opencode", installed = true, raftCapabilities = opencodeOar),
                newRow("cursor", installed = true, raftCapabilities = null)
            )
        val picker =
            runtimePickerRows(
                catalog = BundledRuntimeCatalog,
                machineRuntimeIds = listOf("codex", "opencode", "cursor"),
                selectedRuntimeId = "opencode",
                showAll = false,
                options = rows
            )
        val comboboxOptions = runtimePickerOptions(picker.rows, copy).associateBy { it.value }
        assertEquals("Recommended", comboboxOptions.getValue("codex").badge)
        assertEquals(RuntimeCapabilityHint.CompactionUnreported, picker.selectedCapabilityHint("codex"))
        assertEquals(null, comboboxOptions.getValue("opencode").badge)
        assertEquals(RuntimeCapabilityHint.NoSubagentsCompactionUnreported, picker.selectedCapabilityHint("opencode"))
        assertEquals("Limited", comboboxOptions.getValue("cursor").badge)
        assertEquals(RuntimeCapabilityHint.NoSteeringNoSubagents, picker.selectedCapabilityHint("cursor"))

        // 2. Default-source gap on Recommended stays hint-free.
        val defaultCodexPicker =
            runtimePickerRows(
                catalog = BundledRuntimeCatalog,
                machineRuntimeIds = listOf("codex"),
                selectedRuntimeId = "codex",
                showAll = false,
                options = listOf(newRow("codex", installed = true, raftCapabilities = codexDefaultGap))
            )
        assertEquals("Recommended", runtimePickerOptions(defaultCodexPicker.rows, copy).single().badge)
        assertEquals(null, defaultCodexPicker.selectedCapabilityHint("codex"))

        // 3. When runtime-options rows have not loaded (options == null), server catalog v2
        //    `entry.raftCapabilities` drives the fallback before the bundled table.
        val serverCatalogV2 =
            BundledRuntimeCatalog.copy(
                catalogVersion = 2,
                runtimes =
                    BundledRuntimeCatalog.runtimes.map { entry ->
                        when (entry.id) {
                            "opencode" ->
                                entry.copy(
                                    raftCapabilities =
                                        ResolvedRuntimeRaftCapabilities(
                                            midTurnSteering = true,
                                            subagents = false,
                                            compactionReported = false,
                                            source = RuntimeCapabilitySource.Default
                                        )
                                )
                            "codex" -> entry.copy(raftCapabilities = codexDefaultGap)
                            else -> entry
                        }
                    },
                source = RuntimeCatalogSource.Server
            )
        val catalogFallback =
            runtimePickerRows(
                catalog = serverCatalogV2,
                machineRuntimeIds = listOf("codex", "opencode", "cursor"),
                selectedRuntimeId = "opencode",
                showAll = false,
                options = null
            )
        val fallbackOptions = runtimePickerOptions(catalogFallback.rows, copy).associateBy { it.value }
        assertEquals(null, fallbackOptions.getValue("opencode").badge)
        assertEquals(RuntimeCapabilityHint.NoSubagentsCompactionUnreported, catalogFallback.selectedCapabilityHint("opencode"))
        assertEquals("Recommended", fallbackOptions.getValue("codex").badge)
        assertEquals(null, catalogFallback.selectedCapabilityHint("codex"))
        assertEquals("Limited", fallbackOptions.getValue("cursor").badge)
    }

    private val newAgentRuntimeIds = listOf("builtin", "claude", "codex", "cursor", "grok", "kimi-sdk", "opencode", "pi", "qoder", "qwen")

    /** `projectNewAgentRuntimeOptions`: today's creatable runtimes, plus [extra] (a revived one). */
    private fun newAgentRows(installed: Set<String>, extra: List<SharedCreateAgentRuntimeOption> = emptyList()) =
        newAgentRuntimeIds.map { newRow(it, installed = it in installed) } + extra

    private fun newRow(
        runtimeId: String,
        installed: Boolean,
        raftCapabilities: ResolvedRuntimeRaftCapabilities? = null
    ) =
        SharedCreateAgentRuntimeOption(
            runtimeId = runtimeId,
            capabilityStatus = if (installed) "available" else if (runtimeId == "builtin") "update_required" else "not_installed",
            admissionStatus = "available_for_new",
            admissionReason = null,
            current = false,
            availableForNew = true,
            manageableForCurrentAgent = false,
            canSelectInThisContext = installed,
            runtimeFormV2 = true,
            raftCapabilities = raftCapabilities
        )

    /**
     * `projectExistingAgentRuntimeOptions`: today's creatable runtimes, the agent's [current] one (with
     * `admissionReason: "deprecated"` where the bundle deprecates it and the server did not revive it),
     * and the [revived] ones.
     */
    private fun existingRows(current: String, installed: Set<String>, revived: Set<String> = emptySet()): ExistingAgentRuntimeOptions {
        val ids = (newAgentRuntimeIds + current + revived).distinct()
        val options =
            ids.map { id ->
                val isCurrent = id == current
                val deprecated = isCurrent && BundledRuntimeCatalog.entry(id)?.deprecated == true && id !in revived
                val available = id in installed
                SharedCreateAgentRuntimeOption(
                    runtimeId = id,
                    capabilityStatus = if (available) "available" else "not_installed",
                    admissionStatus = if (deprecated) "grandfathered_current" else "available_for_new",
                    admissionReason = if (deprecated) "deprecated" else null,
                    current = isCurrent,
                    availableForNew = !deprecated,
                    manageableForCurrentAgent = isCurrent,
                    canSelectInThisContext = isCurrent || available,
                    runtimeFormV2 = true
                )
            }
        return ExistingAgentRuntimeOptions(agentId = "agent-1", machineId = "m-1", options = options)
    }

    @Test
    fun offlineComputerKeepsCurrentRuntimeEditableWithoutNotInstalledSuffix() {
        val existing = existingRows(current = "cursor", installed = emptySet())
        val offline =
            runtimePickerRows(
                catalog = BundledRuntimeCatalog,
                machineRuntimeIds = emptyList(),
                selectedRuntimeId = "cursor",
                showAll = true,
                currentRuntimeId = "cursor",
                options = existing.options,
                isMachineOffline = true
            )
        val offlineCursor = offline.rows.single { it.id == "cursor" }
        assertEquals(RuntimePickerGroup.Current, offlineCursor.group)
        assertTrue(offlineCursor.enabled)
        assertEquals("Cursor", offlineCursor.name)
        assertEquals(RuntimePickerSuffix.None, offlineCursor.suffix)
        val offlinePi = offline.rows.single { it.id == "pi" }
        assertEquals(RuntimePickerGroup.NotInstalled, offlinePi.group)
        assertFalse(offlinePi.enabled)
        assertEquals(RuntimePickerSuffix.NotInstalled, offlinePi.suffix)

        val onlineMissing =
            runtimePickerRows(
                catalog = BundledRuntimeCatalog,
                machineRuntimeIds = emptyList(),
                selectedRuntimeId = "cursor",
                showAll = true,
                currentRuntimeId = "cursor",
                options = existing.options,
                isMachineOffline = false
            )
        val onlineCursor = onlineMissing.rows.single { it.id == "cursor" }
        assertEquals(RuntimePickerGroup.Current, onlineCursor.group)
        assertTrue(onlineCursor.enabled)
        assertEquals(RuntimePickerSuffix.NotInstalled, onlineCursor.suffix)

        val offlineDeprecated =
            runtimePickerRows(
                catalog = BundledRuntimeCatalog,
                machineRuntimeIds = emptyList(),
                selectedRuntimeId = "copilot",
                showAll = false,
                currentRuntimeId = "copilot",
                options = existingRows(current = "copilot", installed = emptySet()).options,
                isMachineOffline = true
            )
        val copilot = offlineDeprecated.rows.single { it.id == "copilot" }
        assertEquals(RuntimePickerGroup.Current, copilot.group)
        assertTrue(copilot.enabled)
        assertTrue(copilot.deprecated)
        assertEquals(RuntimePickerSuffix.None, copilot.suffix)
    }
}
