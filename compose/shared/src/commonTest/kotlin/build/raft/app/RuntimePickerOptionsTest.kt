package build.raft.app

import build.raft.app.data.BundledRuntimeCatalog
import build.raft.app.data.RuntimeCatalogEntry
import build.raft.app.data.RuntimeCatalogForm
import build.raft.app.data.RuntimeCatalogSource
import build.raft.app.data.ServerRuntimeCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
        assertTrue(picker.rows.single { it.id == "qwen" }.experimental)

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
    fun aRuntimeTheServerKeepsOffHereIsNotAvailableOnThisServer() {
        val picker =
            runtimePickerRows(
                catalog,
                machineRuntimeIds = listOf("claude", "qwen"),
                selectedRuntimeId = "claude",
                showAll = false,
                serverSelectable = { id -> if (id == "qwen") false else null }
            )
        val qwen = picker.rows.single { it.id == "qwen" }
        assertEquals(RuntimePickerGroup.NotAvailable, qwen.group)
        assertFalse(qwen.enabled)
    }

    @Test
    fun switchingPinsTheCurrentRuntimeFirstEvenWhenDeprecatedOrAbsent() {
        val deprecated = runtimePickerRows(catalog, listOf("claude", "copilot"), selectedRuntimeId = "copilot", showAll = false, currentRuntimeId = "copilot")
        assertEquals("copilot" to RuntimePickerGroup.Current, deprecated.rows.first().let { it.id to it.group })
        assertTrue(deprecated.rows.first().enabled)
        assertTrue(deprecated.rows.first().deprecated)

        // Turned off on this server (not in its catalog): still the current runtime, named from the bundled table.
        val absent = runtimePickerRows(catalog, listOf("claude", "cursor"), selectedRuntimeId = "cursor", showAll = false, currentRuntimeId = "cursor")
        assertEquals("Cursor CLI", absent.rows.first().name)
        assertEquals(RuntimePickerGroup.Current, absent.rows.first().group)

        // Known nowhere: the raw id, never an error.
        val unknown = runtimePickerRows(catalog, listOf("claude"), selectedRuntimeId = "mystery", showAll = false, currentRuntimeId = "mystery")
        assertEquals("mystery", unknown.rows.first().name)
    }

    @Test
    fun optionsSayTheSuffixWhereTheHeadingDoesNotAndBadgeExperimental() {
        val copy =
            RuntimePickerCopy(
                groupCurrent = "Current runtime",
                groupReady = "Ready on this computer",
                groupNotAvailable = "Not available on this server",
                groupNeedsComputerUpdate = "Needs a Raft Computer update",
                groupNeedsAppUpdate = "Needs a Raft app update",
                groupNotInstalled = "Not installed on this computer",
                suffixNotInstalled = " (not installed)",
                suffixUpdateComputer = " (update Raft Computer)",
                suffixComingSoon = " (coming soon)",
                suffixDeprecated = " (deprecated)",
                needsAppUpdate = "Update the app to use this runtime",
                experimental = "Experimental"
            )
        val rows = runtimePickerRows(catalog, listOf("claude", "qwen"), selectedRuntimeId = "claude", showAll = true, currentRuntimeId = "pi").rows
        val options = runtimePickerOptions(rows, copy, descriptions = mapOf("claude" to "Claude Max · 5h 28% used")).associateBy { it.value }
        // The current runtime's group does not say "not installed": the label does.
        assertEquals("Pi (not installed)", options.getValue("pi").label)
        assertEquals("Current runtime", options.getValue("pi").group)
        // Under "Needs a Raft Computer update" the list drops the suffix; the closed field keeps it.
        assertEquals("Built-in Pi", options.getValue("builtin").label)
        assertEquals("Built-in Pi (update Raft Computer)", options.getValue("builtin").triggerLabel)
        assertEquals("Experimental", options.getValue("qwen").badge)
        assertEquals("Qwen Code", options.getValue("qwen").label)
        assertNull(options.getValue("claude").badge)
        assertEquals("Claude Max · 5h 28% used", options.getValue("claude").description)
        assertEquals("Update the app to use this runtime", options.getValue("future").description)
        assertFalse(options.getValue("future").enabled)
    }

    @Test
    fun creatableRuntimesNeedSupportNoDeprecationAndAKnownForm() {
        assertEquals(setOf("claude", "codex", "builtin", "pi", "qwen"), catalog.creatableRuntimeIds())
    }

    @Test
    fun theBundledTableOffersTodaysRuntimesWithQwenAndQoderExperimental() {
        val picker = runtimePickerRows(BundledRuntimeCatalog, listOf("claude", "codex", "qwen", "qoder"), selectedRuntimeId = "claude", showAll = true)
        val ids = picker.rows.map { it.id }
        assertFalse("antigravity" in ids || "kimi" in ids || "copilot" in ids || "gemini" in ids, "ids=$ids")
        assertTrue(picker.rows.single { it.id == "qwen" }.experimental)
        assertTrue(picker.rows.single { it.id == "qoder" }.experimental)
        assertEquals(RuntimePickerGroup.NeedsComputerUpdate, picker.rows.single { it.id == "kimi-sdk" }.group)
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
