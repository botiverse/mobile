package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import build.raft.app.data.BundledRuntimeCatalog
import build.raft.app.data.RaftRuntimeCatalogNames
import build.raft.app.data.SharedServerRuntimeCatalogRepository
import build.raft.app.data.followServerRuntimeCatalog
import build.raft.app.data.MobileRuntimeForms
import build.raft.app.data.RuntimeCatalogEntry
import build.raft.app.data.RuntimeCatalogForm
import build.raft.app.data.ServerRuntimeCatalog
import build.raft.app.data.hasMobileForm
import build.raft.app.data.runtimeCatalogDisplayName
import build.raft.app.data.runtimeCatalogIsDeprecated
import build.raft.app.data.runtimeCatalogIsExperimental
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftComboboxOption

/**
 * The runtime picker's groups, in list order (web `groupRuntimePickerRows`, botiverse/slock
 * #11783 / #11794 / #11822): the agent's current runtime first (switching), then what runs on
 * this computer, what the server keeps off here, what needs a Raft Computer update, what needs
 * an app update (a runtime whose forms this app knows none of), and, on request, what is not
 * installed on this computer.
 */
internal enum class RuntimePickerGroup { Current, Ready, NotAvailable, NeedsComputerUpdate, NeedsAppUpdate, NotInstalled }

/** What this computer lacks for a runtime, said after its name (web `RuntimeAvailabilitySuffix`). */
internal enum class RuntimePickerSuffix { None, NotInstalled, UpdateComputer, ComingSoon }

internal data class RuntimePickerRow(
    val id: String,
    /** The bare name ([runtimeCatalogDisplayName] chain): catalog → bundled → id. */
    val name: String,
    val group: RuntimePickerGroup,
    val enabled: Boolean,
    val suffix: RuntimePickerSuffix = RuntimePickerSuffix.None,
    val deprecated: Boolean = false,
    /** Drawn as the Experimental badge after the name (never with [deprecated]: deprecated wins). */
    val experimental: Boolean = false
) {
    /** The group heading already says the suffix, so the open list drops it (the closed field keeps it). */
    val suffixInList: Boolean
        get() = group != RuntimePickerGroup.NeedsComputerUpdate && group != RuntimePickerGroup.NotInstalled
}

internal data class RuntimePickerRows(
    val rows: List<RuntimePickerRow>,
    /** Whether Show All Supported / Show Installed Only has anything to show or hide. */
    val canToggle: Boolean
)

/**
 * The runtime picker of the create and switch dialogs, grouped by what the selected computer
 * can run. Grouping never makes a row pickable that was not; it only places it.
 *
 * - The runtimes are [catalog]'s, in its order. A deprecated one is listed only as the agent's
 *   [currentRuntimeId]; the current runtime is always listed, even when the catalog lacks it
 *   (the server turned it off), named through the bundled table or its id.
 * - [machineRuntimeIds]: what the computer reports. A runtime it runs that the server does not
 *   let the person pick here ([serverSelectable] = false, from the runtime-options rows) is
 *   Not available on this server.
 * - A runtime with no form this app knows ([isFormSupported]) is Needs an app update, never a
 *   half form.
 * - Not installed rows show only with [showAll], or when nothing else would be listed, and the
 *   [selectedRuntimeId] always shows so the field never names a value its list lacks.
 */
internal fun runtimePickerRows(
    catalog: ServerRuntimeCatalog,
    machineRuntimeIds: List<String>,
    selectedRuntimeId: String,
    showAll: Boolean,
    currentRuntimeId: String? = null,
    serverSelectable: (String) -> Boolean? = { null },
    isFormSupported: (RuntimeCatalogForm) -> Boolean = MobileRuntimeForms::supports
): RuntimePickerRows {
    val available = machineRuntimeIds.map { it.trim() }.filter { it.isNotBlank() }.toSet()
    val current = currentRuntimeId?.trim()?.takeIf { it.isNotBlank() }
    val entries =
        catalog.runtimes.filter { !it.deprecated || it.id == current }.let { listed ->
            if (current == null || listed.any { it.id == current }) {
                listed
            } else {
                // Rule 5: absent from the catalog (turned off) but this agent still runs it.
                val fallback = BundledRuntimeCatalog.entry(current) ?: RuntimeCatalogEntry(id = current, displayName = current, forms = emptyList())
                listOf(fallback) + listed
            }
        }
    val classified =
        entries.map { entry ->
            val suffix =
                when {
                    !entry.supported -> RuntimePickerSuffix.ComingSoon
                    entry.id in available -> RuntimePickerSuffix.None
                    entry.binary.isEmpty() -> RuntimePickerSuffix.UpdateComputer
                    else -> RuntimePickerSuffix.NotInstalled
                }
            val group =
                when {
                    entry.id == current -> RuntimePickerGroup.Current
                    !entry.hasMobileForm(isFormSupported) -> RuntimePickerGroup.NeedsAppUpdate
                    suffix == RuntimePickerSuffix.None ->
                        if (serverSelectable(entry.id) == false) RuntimePickerGroup.NotAvailable else RuntimePickerGroup.Ready
                    suffix == RuntimePickerSuffix.UpdateComputer -> RuntimePickerGroup.NeedsComputerUpdate
                    else -> RuntimePickerGroup.NotInstalled
                }
            RuntimePickerRow(
                id = entry.id,
                name = entry.displayName.ifBlank { runtimeCatalogDisplayName(entry.id) },
                group = group,
                enabled = group == RuntimePickerGroup.Current || group == RuntimePickerGroup.Ready,
                suffix = suffix,
                deprecated = entry.deprecated,
                experimental = entry.experimental && !entry.deprecated
            )
        }
    val notInstalled = classified.count { it.group == RuntimePickerGroup.NotInstalled }
    val onlyNotInstalled = classified.isNotEmpty() && notInstalled == classified.size
    val showNotInstalled = showAll || onlyNotInstalled
    val rows =
        classified
            .filter { it.group != RuntimePickerGroup.NotInstalled || showNotInstalled || it.id == selectedRuntimeId }
            .sortedBy { it.group.ordinal }
    return RuntimePickerRows(rows = rows, canToggle = notInstalled > 0 && !onlyNotInstalled)
}

/** The runtime ids a new agent may be created with here (the create dialog's submit gate). */
internal fun ServerRuntimeCatalog.creatableRuntimeIds(isFormSupported: (RuntimeCatalogForm) -> Boolean = MobileRuntimeForms::supports): Set<String> =
    runtimes.filter { it.supported && !it.deprecated && it.hasMobileForm(isFormSupported) }.mapTo(linkedSetOf()) { it.id }

/** The words of the runtime picker, resolved once per composition. */
internal data class RuntimePickerCopy(
    val groupCurrent: String,
    val groupReady: String,
    val groupNotAvailable: String,
    val groupNeedsComputerUpdate: String,
    val groupNeedsAppUpdate: String,
    val groupNotInstalled: String,
    val suffixNotInstalled: String,
    val suffixUpdateComputer: String,
    val suffixComingSoon: String,
    val suffixDeprecated: String,
    val needsAppUpdate: String,
    val experimental: String
) {
    fun group(group: RuntimePickerGroup): String =
        when (group) {
            RuntimePickerGroup.Current -> groupCurrent
            RuntimePickerGroup.Ready -> groupReady
            RuntimePickerGroup.NotAvailable -> groupNotAvailable
            RuntimePickerGroup.NeedsComputerUpdate -> groupNeedsComputerUpdate
            RuntimePickerGroup.NeedsAppUpdate -> groupNeedsAppUpdate
            RuntimePickerGroup.NotInstalled -> groupNotInstalled
        }

    fun suffix(suffix: RuntimePickerSuffix): String =
        when (suffix) {
            RuntimePickerSuffix.None -> ""
            RuntimePickerSuffix.NotInstalled -> suffixNotInstalled
            RuntimePickerSuffix.UpdateComputer -> suffixUpdateComputer
            RuntimePickerSuffix.ComingSoon -> suffixComingSoon
        }
}

@Composable
internal fun rememberRuntimePickerCopy(): RuntimePickerCopy =
    RuntimePickerCopy(
        groupCurrent = stringResource(Res.string.runtime_picker_group_current),
        groupReady = stringResource(Res.string.runtime_picker_group_ready),
        groupNotAvailable = stringResource(Res.string.runtime_picker_group_not_available),
        groupNeedsComputerUpdate = stringResource(Res.string.runtime_picker_group_needs_computer_update),
        groupNeedsAppUpdate = stringResource(Res.string.runtime_picker_group_needs_app_update),
        groupNotInstalled = stringResource(Res.string.runtime_picker_group_not_installed),
        suffixNotInstalled = stringResource(Res.string.agent_create_suffix_not_installed),
        suffixUpdateComputer = stringResource(Res.string.settings_computers_runtime_suffix_update_computer),
        suffixComingSoon = stringResource(Res.string.agent_create_suffix_coming_soon),
        suffixDeprecated = stringResource(Res.string.runtime_suffix_deprecated),
        needsAppUpdate = stringResource(Res.string.runtime_picker_needs_app_update),
        experimental = stringResource(Res.string.runtime_badge_experimental)
    )

/**
 * [rows] as picker options: the name (with " (deprecated)" for a deprecated current runtime),
 * the Experimental badge, the suffix where the heading doesn't say it (the closed field always
 * says it), the group heading, and [descriptions] (quota lines) or the app-update line.
 */
internal fun runtimePickerOptions(
    rows: List<RuntimePickerRow>,
    copy: RuntimePickerCopy,
    descriptions: Map<String, String> = emptyMap()
): List<RaftComboboxOption<String>> =
    rows.map { row ->
        val name = if (row.deprecated) row.name + copy.suffixDeprecated else row.name
        val suffix = copy.suffix(row.suffix)
        RaftComboboxOption(
            value = row.id,
            label = if (row.suffixInList) name + suffix else name,
            description = if (row.group == RuntimePickerGroup.NeedsAppUpdate) copy.needsAppUpdate else descriptions[row.id],
            group = copy.group(row.group),
            keywords = listOf(row.id),
            enabled = row.enabled,
            badge = if (row.experimental) copy.experimental else null,
            triggerLabel = name + suffix
        )
    }

/**
 * A runtime named as an identity (a subtitle, a chip, a profile card), web
 * `formatRuntimeLabelWithStatus`: the name plus " (deprecated)" or " (experimental)". Prose
 * (a runtime inside a sentence) uses the bare [runtimeCatalogDisplayName].
 */
@Composable
internal fun runtimeLabelWithStatus(runtimeId: String, serverId: String? = null): String {
    val name = runtimeCatalogDisplayName(runtimeId, serverId)
    return when {
        runtimeCatalogIsDeprecated(runtimeId, serverId) -> name + stringResource(Res.string.runtime_suffix_deprecated)
        runtimeCatalogIsExperimental(runtimeId, serverId) -> name + stringResource(Res.string.runtime_suffix_experimental)
        else -> name
    }
}

/**
 * [serverId]'s runtime catalog for a page: the one read in this process for that server, else the
 * bundled table on the first frame; then its cached answer and the server's
 * ([followServerRuntimeCatalog]). Never another server's catalog. No repository or server: bundled.
 */
@Composable
internal fun rememberServerRuntimeCatalog(
    repository: SharedServerRuntimeCatalogRepository?,
    serverId: String?
): ServerRuntimeCatalog {
    val clean = serverId?.trim().orEmpty()
    var catalog by remember(repository, clean) {
        mutableStateOf(RaftRuntimeCatalogNames.catalog(clean.takeIf { it.isNotBlank() }) ?: BundledRuntimeCatalog)
    }
    LaunchedEffect(repository, clean) {
        repository?.followServerRuntimeCatalog(clean, catalog) { catalog = it }
    }
    return catalog
}
