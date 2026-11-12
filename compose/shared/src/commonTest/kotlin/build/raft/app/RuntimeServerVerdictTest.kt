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
    fun createOffersAndLetsYouPickAntigravityWhereTheServerAdmitsItThoughTheBundledTableDeprecatesIt() {
        check(BundledRuntimeCatalog.entry("antigravity")?.deprecated == true) { "the bundled table deprecates Antigravity" }
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
    fun aRowTheServerKeepsOffHereIsNotAvailableAndNotCreatable() {
        val rows = listOf(newRow("claude", installed = true), newRow("qwen", installed = true).copy(canSelectInThisContext = false, availableForNew = true))
        val picker = runtimePickerRows(BundledRuntimeCatalog, listOf("claude", "qwen"), selectedRuntimeId = "qwen", showAll = false, options = rows)
        val qwen = picker.rows.single { it.id == "qwen" }
        assertEquals(RuntimePickerGroup.NotAvailable, qwen.group)
        assertFalse(qwen.enabled)
        assertEquals(setOf("claude"), BundledRuntimeCatalog.creatableRuntimeIds(options = rows))
    }

    @Test
    fun withoutRowsTheServerCatalogDecidesAndTheBundledTableOnlyWhenTheServerDidNotAnswer() {
        val machine = listOf("claude", "antigravity")
        // GET /servers/:id/runtimes answered: Antigravity is `deprecated: false` on this server.
        val served =
            BundledRuntimeCatalog.copy(
                catalogVersion = 1,
                runtimes = BundledRuntimeCatalog.runtimes.map { if (it.id == "antigravity") it.copy(deprecated = false) else it },
                source = RuntimeCatalogSource.Server
            )
        val fromServer = runtimePickerRows(served, machine, selectedRuntimeId = "claude", showAll = false)
        assertEquals(RuntimePickerGroup.Ready, fromServer.rows.single { it.id == "antigravity" }.group)
        assertTrue("antigravity" in served.creatableRuntimeIds())

        // It failed (no cache either): the bundled table is the fallback, and it deprecates Antigravity.
        val fallback = runtimePickerRows(BundledRuntimeCatalog, machine, selectedRuntimeId = "claude", showAll = true)
        assertTrue(fallback.rows.none { it.id == "antigravity" })
        assertFalse("antigravity" in BundledRuntimeCatalog.creatableRuntimeIds())

        // Once the computer's rows land they decide, even over the bundled fallback.
        val rows = listOf(newRow("claude", installed = true), newRow("antigravity", installed = true))
        val withRows = runtimePickerRows(BundledRuntimeCatalog, machine, selectedRuntimeId = "claude", showAll = false, options = rows)
        assertEquals(RuntimePickerGroup.Ready, withRows.rows.single { it.id == "antigravity" }.group)
    }

    @Test
    fun switchListsTheAgentsRowsAndARevivedRuntimeIsSelectable() {
        // An agent on Claude Code; the server offers Antigravity again (its OAR flag is on).
        val rows = existingRows(current = "claude", installed = setOf("claude", "antigravity"), revived = setOf("antigravity"))
        val picker =
            runtimePickerRows(
                BundledRuntimeCatalog,
                listOf("claude", "antigravity"),
                selectedRuntimeId = "claude",
                showAll = false,
                currentRuntimeId = "claude",
                options = rows.options
            )
        assertEquals("claude" to RuntimePickerGroup.Current, picker.rows.first().let { it.id to it.group })
        val antigravity = picker.rows.single { it.id == "antigravity" }
        assertEquals(RuntimePickerGroup.Ready, antigravity.group)
        assertTrue(antigravity.enabled)
        assertFalse(antigravity.deprecated)
    }

    @Test
    fun theCurrentRowSaysDeprecatedAndARevivedCurrentRuntimeIsNot() {
        val onCopilot = existingRows(current = "copilot", installed = setOf("copilot", "claude"))
        val copilot =
            runtimePickerRows(BundledRuntimeCatalog, listOf("copilot", "claude"), "copilot", showAll = false, currentRuntimeId = "copilot", options = onCopilot.options)
                .rows.first()
        assertEquals("copilot" to RuntimePickerGroup.Current, copilot.id to copilot.group)
        assertTrue(copilot.deprecated)
        assertTrue(runtimeDeprecatedForAgent("copilot", onCopilot, catalogDeprecated = { false }))

        // Antigravity revived on this server: its own row has no `admissionReason`, whatever the bundle says.
        val onAntigravity = existingRows(current = "antigravity", installed = setOf("antigravity"), revived = setOf("antigravity"))
        assertFalse(runtimeDeprecatedForAgent("antigravity", onAntigravity, catalogDeprecated = { true }))
        val row =
            runtimePickerRows(BundledRuntimeCatalog, listOf("antigravity"), "antigravity", showAll = false, currentRuntimeId = "antigravity", options = onAntigravity.options)
                .rows.first()
        assertFalse(row.deprecated)
    }

    @Test
    fun afterASwitchTheOldCurrentRowSaysNothingAboutTheNewRuntime() {
        // Rows read while the agent ran Copilot (deprecated); it has since switched to Codex CLI.
        val stale = existingRows(current = "copilot", installed = setOf("copilot", "codex"))
        assertFalse(runtimeDeprecatedForAgent("codex", stale, catalogDeprecated = { false }))
        val picker =
            runtimePickerRows(BundledRuntimeCatalog, listOf("copilot", "codex"), "codex", showAll = false, currentRuntimeId = "codex", options = stale.options)
        val codex = picker.rows.first()
        assertEquals("codex" to RuntimePickerGroup.Current, codex.id to codex.group)
        assertFalse(codex.deprecated)
        assertTrue(picker.rows.none { it.id == "copilot" && (it.group == RuntimePickerGroup.Current || it.deprecated) })

        // The other way round: rows that still mark Codex current say nothing about Copilot, so the
        // catalog answers for it.
        val staleCodex = existingRows(current = "codex", installed = setOf("copilot", "codex"))
        assertTrue(runtimeDeprecatedForAgent("copilot", staleCodex, catalogDeprecated = { it == "copilot" }))
        assertFalse(runtimeDeprecatedForAgent("copilot", staleCodex, catalogDeprecated = { false }))
        // No rows at all: the catalog.
        assertTrue(runtimeDeprecatedForAgent("copilot", null, catalogDeprecated = { it == "copilot" }))
    }

    private val newAgentRuntimeIds = listOf("builtin", "claude", "codex", "cursor", "grok", "kimi-sdk", "opencode", "pi", "qoder", "qwen")

    /** `projectNewAgentRuntimeOptions`: today's creatable runtimes, plus [extra] (a revived one). */
    private fun newAgentRows(installed: Set<String>, extra: List<SharedCreateAgentRuntimeOption> = emptyList()) =
        newAgentRuntimeIds.map { newRow(it, installed = it in installed) } + extra

    private fun newRow(runtimeId: String, installed: Boolean) =
        SharedCreateAgentRuntimeOption(
            runtimeId = runtimeId,
            capabilityStatus = if (installed) "available" else if (runtimeId == "builtin") "update_required" else "not_installed",
            admissionStatus = "available_for_new",
            admissionReason = null,
            current = false,
            availableForNew = true,
            manageableForCurrentAgent = false,
            canSelectInThisContext = installed,
            runtimeFormV2 = true
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
                    manageableForCurrentAgent = isCurrent && available,
                    canSelectInThisContext = available,
                    runtimeFormV2 = true
                )
            }
        return ExistingAgentRuntimeOptions(agentId = "agent-1", machineId = "m-1", options = options)
    }
}
