package build.raft.app

import build.raft.app.model.isExternalAgentRuntime
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
import build.raft.app.data.SharedCreateAgentRuntimeOption
import build.raft.app.data.deprecatedHere
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
 *   let the person pick here ([serverSelectable] = false, from the runtime-options rows) is not
 *   listed: only as the field's [selectedRuntimeId] does it show, under Not available on this
 *   server (web #14543, slock f553f366).
 * - A runtime with no form this app knows ([isFormSupported]) is Needs an app update, never a
 *   half form.
 * - Not installed rows show only with [showAll], or when nothing else would be listed, and the
 *   [selectedRuntimeId] always shows so the field never names a value its list lacks.
 *
 * With the server's runtime-options [options] (a computer's `new_agent` rows, or an agent's
 * `existing_agent` rows), those rows ARE the list (botiverse/slock #14568 / #14584, #proj-runtime:#57):
 * what is offered, installed and selectable is each row's answer, and "(deprecated)" its
 * `admissionReason`; [catalog] only names them and says which forms this app knows. No
 * `deprecated` flag of the catalog (let alone the bundled table) drops or blocks a row. Without
 * rows (still loading, or the request failed) the picker falls back to [catalog] as above.
 */
internal fun runtimePickerRows(
    catalog: ServerRuntimeCatalog,
    machineRuntimeIds: List<String>,
    selectedRuntimeId: String,
    showAll: Boolean,
    currentRuntimeId: String? = null,
    serverSelectable: (String) -> Boolean? = { null },
    isFormSupported: (RuntimeCatalogForm) -> Boolean = MobileRuntimeForms::supports,
    options: List<SharedCreateAgentRuntimeOption>? = null
): RuntimePickerRows {
    val current = currentRuntimeId?.trim()?.takeIf { it.isNotBlank() }
    val classified =
        if (options != null) {
            serverRowPickerRows(catalog, options, current, isFormSupported)
        } else {
            catalogPickerRows(catalog, machineRuntimeIds, current, serverSelectable, isFormSupported)
        }
    // Not offered here (rollout or admission): nothing to do about it, so not listed unless it is
    // the field's value, and it doesn't count toward "only not-installed rows" either.
    val listable = classified.filter { it.group != RuntimePickerGroup.NotAvailable || it.id == selectedRuntimeId }
    val notInstalled = listable.count { it.group == RuntimePickerGroup.NotInstalled }
    val onlyNotInstalled = listable.isNotEmpty() && notInstalled == listable.size
    val showNotInstalled = showAll || onlyNotInstalled
    val rows =
        listable
            .filter { it.group != RuntimePickerGroup.NotInstalled || showNotInstalled || it.id == selectedRuntimeId }
            .sortedBy { it.group.ordinal }
    return RuntimePickerRows(rows = rows, canToggle = notInstalled > 0 && !onlyNotInstalled)
}

/** The entry that names [runtimeId]: [catalog] (the server's, its cache, or the bundled table), else the bundled table. */
private fun ServerRuntimeCatalog.namingEntry(runtimeId: String): RuntimeCatalogEntry? =
    entry(runtimeId) ?: BundledRuntimeCatalog.entry(runtimeId)

/**
 * Whether this app can set up [row]'s runtime: a form it knows in the naming entry; a runtime no
 * catalog names goes by the row's own v2 marker.
 */
internal fun ServerRuntimeCatalog.knowsFormFor(
    row: SharedCreateAgentRuntimeOption,
    isFormSupported: (RuntimeCatalogForm) -> Boolean = MobileRuntimeForms::supports
): Boolean =
    namingEntry(row.runtimeId)?.hasMobileForm(isFormSupported)
        ?: (row.runtimeFormV2 && isFormSupported(RuntimeCatalogForm.V2))

/** The rows of the server's runtime-options, in the server's order, named from [catalog]. */
private fun serverRowPickerRows(
    catalog: ServerRuntimeCatalog,
    options: List<SharedCreateAgentRuntimeOption>,
    current: String?,
    isFormSupported: (RuntimeCatalogForm) -> Boolean
): List<RuntimePickerRow> {
    val rows =
        options.map { row ->
            val entry = catalog.namingEntry(row.runtimeId)
            val suffix =
                when (row.capabilityStatus) {
                    "available" -> RuntimePickerSuffix.None
                    "update_required" -> RuntimePickerSuffix.UpdateComputer
                    else -> RuntimePickerSuffix.NotInstalled
                }
            // Current only for the agent's own runtime id: rows read before a switch mark the old one.
            val isCurrent = row.runtimeId == current
            val group =
                when {
                    isCurrent -> RuntimePickerGroup.Current
                    !catalog.knowsFormFor(row, isFormSupported) -> RuntimePickerGroup.NeedsAppUpdate
                    suffix == RuntimePickerSuffix.None ->
                        if (row.canSelectInThisContext) RuntimePickerGroup.Ready else RuntimePickerGroup.NotAvailable
                    suffix == RuntimePickerSuffix.UpdateComputer -> RuntimePickerGroup.NeedsComputerUpdate
                    else -> RuntimePickerGroup.NotInstalled
                }
            val deprecated = isCurrent && row.current && row.deprecatedHere
            RuntimePickerRow(
                id = row.runtimeId,
                name = entry?.displayName?.ifBlank { null } ?: runtimeCatalogDisplayName(row.runtimeId),
                group = group,
                enabled = group == RuntimePickerGroup.Current || group == RuntimePickerGroup.Ready,
                suffix = suffix,
                deprecated = deprecated,
                experimental = entry?.experimental == true && !deprecated
            )
        }
    if (current == null || rows.any { it.id == current }) return rows
    // The server's rows always carry the agent's runtime; rows that lack it (read before a switch)
    // still pin it first, named as the catalog fallback does.
    return catalogPickerRows(catalog, emptyList(), current, { null }, isFormSupported).filter { it.id == current } + rows
}

/** The fallback without the server's rows: [catalog]'s runtimes, a deprecated one only as [current]. */
private fun catalogPickerRows(
    catalog: ServerRuntimeCatalog,
    machineRuntimeIds: List<String>,
    current: String?,
    serverSelectable: (String) -> Boolean?,
    isFormSupported: (RuntimeCatalogForm) -> Boolean
): List<RuntimePickerRow> {
    val available = machineRuntimeIds.map { it.trim() }.filter { it.isNotBlank() }.toSet()
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
    return entries.map { entry ->
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
}

/**
 * The runtime ids a new agent may be created with here (the create dialog's submit gate). With the
 * computer's runtime-options [options], the rows the server lets this person pick
 * (`canSelectInThisContext`) whose form this app knows, whatever the catalog's `deprecated` says
 * (Antigravity where its OAR flag is on); without rows, the catalog's supported, not deprecated
 * runtimes with a known form.
 */
internal fun ServerRuntimeCatalog.creatableRuntimeIds(
    isFormSupported: (RuntimeCatalogForm) -> Boolean = MobileRuntimeForms::supports,
    options: List<SharedCreateAgentRuntimeOption>? = null
): Set<String> =
    if (options != null) {
        options.filter { it.canSelectInThisContext && knowsFormFor(it, isFormSupported) }.mapTo(linkedSetOf()) { it.runtimeId }
    } else {
        runtimes.filter { it.supported && !it.deprecated && it.hasMobileForm(isFormSupported) }.mapTo(linkedSetOf()) { it.id }
    }

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
 * Web shared `getRuntimeDisplayName`: the external sentinel (external and cloud agents alike) reads
 * "External agent", never the raw id "external"; any other id its catalog name.
 */
internal fun runtimeIdentityDisplayName(runtimeId: String, serverId: String? = null): String =
    if (isExternalAgentRuntime(runtimeId)) ExternalAgentRuntimeDisplayName else runtimeCatalogDisplayName(runtimeId, serverId)

/**
 * A runtime named as an identity (a subtitle, a chip, a profile card), web
 * `formatRuntimeLabelWithStatus`: the name plus " (deprecated)" or " (experimental)". Prose
 * (a runtime inside a sentence) uses the bare [runtimeCatalogDisplayName].
 */
@Composable
internal fun runtimeLabelWithStatus(
    runtimeId: String,
    serverId: String? = null,
    /** The server's answer for this agent's runtime ([runtimeDeprecatedForAgent]); null: the catalog's. */
    deprecated: Boolean? = null
): String {
    val name = runtimeIdentityDisplayName(runtimeId, serverId)
    return when {
        deprecated ?: runtimeCatalogIsDeprecated(runtimeId, serverId) -> name + stringResource(Res.string.runtime_suffix_deprecated)
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
