package build.raft.app

import build.raft.app.data.RuntimeCatalogEntry
import build.raft.app.data.RuntimeCatalogForm
import build.raft.app.data.RuntimeCatalogSource
import build.raft.app.data.ServerRuntimeCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Web `groupRuntimePickerRows` parity (botiverse/slock #11783 / #11794 / #11822 / #11771). */
class RuntimePickerOptionsTest {
    private val catalog =
        ServerRuntimeCatalog(
            catalogVersion = 1,
            runtimes =
                listOf(
                    entry("claude", "Claude Code", binary = "claude"),
                    entry("codex", "Codex CLI", binary = "codex"),
                    entry("builtin", "Built-in Pi", binary = ""),
                    entry("copilot", "Copilot CLI", binary = "copilot", deprecated = true),
                    entry("pi", "Pi", binary = "pi"),
                    entry("qwen", "Qwen Code", binary = "qwen", experimental = true),
                    entry("future", "Future CLI", binary = "future", forms = listOf(RuntimeCatalogForm.Unknown("v3")))
                ),
            source = RuntimeCatalogSource.Server
        )

    @Test
    fun groupsByWhatThisComputerRunsAndHidesNotInstalledUntilAsked() {
        val picker =
            runtimePickerRows(catalog, machineRuntimeIds = listOf("claude", "qwen", "codex"), selectedRuntimeId = "claude", showAll = false)
        assertEquals(
            listOf(
                "claude" to RuntimePickerGroup.Ready,
                "codex" to RuntimePickerGroup.Ready,
                "qwen" to RuntimePickerGroup.Ready,
                "builtin" to RuntimePickerGroup.NeedsComputerUpdate,
                "future" to RuntimePickerGroup.NeedsAppUpdate
            ),
            picker.rows.map { it.id to it.group }
        )
        // Pi is not installed: hidden behind Show All Supported. Copilot is deprecated: never offered.
        assertTrue(picker.canToggle)
        assertTrue(picker.rows.none { it.id == "pi" || it.id == "copilot" })
        assertTrue(picker.rows.filter { it.group == RuntimePickerGroup.Ready }.all { it.enabled })
        assertFalse(picker.rows.single { it.id == "builtin" }.enabled)
        assertFalse(picker.rows.single { it.id == "future" }.enabled)

        val all = runtimePickerRows(catalog, listOf("claude", "qwen", "codex"), selectedRuntimeId = "claude", showAll = true)
        val pi = all.rows.single { it.id == "pi" }
        assertEquals(RuntimePickerGroup.NotInstalled, pi.group)
        assertEquals(RuntimePickerSuffix.NotInstalled, pi.suffix)
        assertFalse(pi.enabled)
        assertFalse(pi.suffixInList)
        assertEquals(RuntimePickerGroup.NotInstalled, all.rows.last().group)
    }

    @Test
    fun theSelectedRuntimeStaysListedAndNothingElseMeansShowEverything() {
        val selectedUninstalled = runtimePickerRows(catalog, listOf("claude"), selectedRuntimeId = "pi", showAll = false)
        assertTrue(selectedUninstalled.rows.any { it.id == "pi" })

        val onlyNotInstalled =
            ServerRuntimeCatalog(1, listOf(entry("pi", "Pi", binary = "pi"), entry("codex", "Codex CLI", binary = "codex")), RuntimeCatalogSource.Server)
        val picker = runtimePickerRows(onlyNotInstalled, emptyList(), selectedRuntimeId = "", showAll = false)
        assertEquals(listOf("pi", "codex"), picker.rows.map { it.id })
        assertFalse(picker.canToggle)
    }

    @Test
    fun aRuntimeTheServerKeepsOffHereIsListedOnlyAsTheFieldsValue() {
        // web #14543 (slock f553f366): a runtime this server doesn't offer is not listed...
        val picker =
            runtimePickerRows(
                catalog,
                machineRuntimeIds = listOf("claude", "qwen"),
                selectedRuntimeId = "claude",
                showAll = false,
                serverSelectable = { id -> if (id == "qwen") false else null }
            )
        assertTrue(picker.rows.none { it.id == "qwen" })
        // ...unless it is the field's value: then it shows, disabled, under Not available on this server.
        val selected =
            runtimePickerRows(
                catalog,
                machineRuntimeIds = listOf("claude", "qwen"),
                selectedRuntimeId = "qwen",
                showAll = false,
                serverSelectable = { id -> if (id == "qwen") false else null }
            )
        val qwen = selected.rows.single { it.id == "qwen" }
        assertEquals(RuntimePickerGroup.NotAvailable, qwen.group)
        assertFalse(qwen.enabled)
    }

    @Test
    fun creatableRuntimesNeedSupportNoDeprecationAndAKnownForm() {
        assertEquals(setOf("claude", "codex", "builtin", "pi", "qwen"), catalog.creatableRuntimeIds())
    }

    @Test
    fun offlineComputerCatalogFallbackOmitsNotInstalledSuffixOnCurrentRuntimeOnly() {
        val picker =
            runtimePickerRows(
                catalog = catalog,
                machineRuntimeIds = emptyList(),
                selectedRuntimeId = "copilot",
                showAll = true,
                currentRuntimeId = "copilot",
                isMachineOffline = true
            )
        val current = picker.rows.single { it.id == "copilot" }
        assertEquals(RuntimePickerGroup.Current, current.group)
        assertTrue(current.enabled)
        assertTrue(current.deprecated)
        assertEquals(RuntimePickerSuffix.None, current.suffix)
        val pi = picker.rows.single { it.id == "pi" }
        assertEquals(RuntimePickerGroup.NotInstalled, pi.group)
        assertFalse(pi.enabled)
        assertEquals(RuntimePickerSuffix.NotInstalled, pi.suffix)
    }

    private fun entry(
        id: String,
        name: String,
        binary: String,
        deprecated: Boolean = false,
        experimental: Boolean = false,
        forms: List<RuntimeCatalogForm> = listOf(RuntimeCatalogForm.V2)
    ) = RuntimeCatalogEntry(id = id, displayName = name, binary = binary, deprecated = deprecated, experimental = experimental, forms = forms)
}
