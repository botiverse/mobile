package build.raft.app

import build.raft.app.resources.*
import build.raft.app.components.RaftIconSpinner
import build.raft.designsystem.components.RaftTextFieldDefaults
import build.raft.designsystem.components.RaftComboboxOption
import build.raft.designsystem.components.RaftFieldLabel
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftButtonSize
import build.raft.app.components.RaftBrutalActionButton
import build.raft.app.components.RaftBrutalIconButton
import build.raft.app.components.RaftBrutalTextField
import build.raft.app.components.RaftUiBanner
import build.raft.app.components.RaftUiBannerSize
import build.raft.app.components.RaftUiBannerStatus
import build.raft.app.model.RUNTIMES
import build.raft.app.model.suggestAgentName
import build.raft.app.model.validateAgentName
import build.raft.app.data.DataResult
import build.raft.app.data.RuntimeFormV2Availability
import build.raft.app.data.SharedCreateAgentRuntimeCatalog
import build.raft.app.data.BundledRuntimeCatalog
import build.raft.app.data.createAgentRuntimeForOptions
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.RaftRuntimeCatalogNames
import build.raft.app.data.RuntimeCatalogSource
import build.raft.app.data.runtimeFormV2Availability
import build.raft.app.data.runtimeform.v2.RuntimeFormV2Values
import build.raft.app.data.SharedCreateAgentFormStore
import build.raft.app.data.runtimeform.v2.RuntimeFormV2Loaded
import build.raft.app.data.runtimeform.v2.RuntimeFormV2Access
import build.raft.app.data.runtimeform.v2.RuntimeFormV2SubmitBody
import build.raft.app.data.runtimeform.v2.RUNTIME_FORM_V2_FORM_REVISION_STALE
import build.raft.app.data.runtimeform.v2.applyRuntimeFormV2Change
import build.raft.app.data.runtimeform.v2.initialRuntimeFormV2Values
import build.raft.app.data.runtimeform.v2.runtimeFormV2Submission
import build.raft.app.data.runtimeform.v2.runtimeFormV2ValuesAfterSourceReload
import build.raft.app.data.runtimeform.v2.validateRuntimeFormV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import build.raft.app.compat.pager.LocalActivity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import build.raft.app.compat.pager.Pager

// #55 visual parity: the legacy `CREATE_AGENT_DIALOG_BODY_MAX_HEIGHT = 430.dp`
// cap has been removed. The dialog body now fills the viewport via
// `Column.fillMaxHeight()` and the LazyColumn uses `weight(1f)` so the
// Cancel/Create row stays pinned at the bottom. The cap value was kept
// commented out below for historical reference only; do not reintroduce
// without also reverting the layout change.
// private val CREATE_AGENT_DIALOG_BODY_MAX_HEIGHT = 430.dp

internal data class SharedCreateAgentDialogSubmit(
    val name: String,
    val description: String?,
    val machineId: String?,
    val external: Boolean = false,
    /** The runtime form v2 submit; null only for an external agent. */
    val runtimeFormV2: RuntimeFormV2SubmitBody? = null
)

/** What the create dialog's runtime section shows (runtime form v2, the only runtime form). */
internal sealed interface CreateAgentRuntimeSection {
    data object Loading : CreateAgentRuntimeSection
    data class Ready(val loaded: RuntimeFormV2Loaded) : CreateAgentRuntimeSection
    /** The load failed: a warning with Retry. */
    data class Failed(val code: String) : CreateAgentRuntimeSection
    /** The runtime has no v2 form (no `runtimeFormV2` marker): a plain "unavailable" notice. */
    data object Unavailable : CreateAgentRuntimeSection
}

/**
 * The runtime section in every state. A runtime the server marks without a v2 form is
 * [CreateAgentRuntimeSection.Unavailable] (no endless Retry); a failed load is
 * [CreateAgentRuntimeSection.Failed]. Nothing falls back to v1 (task #8, retire runtime form v1).
 */
internal fun createAgentRuntimeSection(
    availability: RuntimeFormV2Availability,
    load: RuntimeFormV2DialogLoad
): CreateAgentRuntimeSection =
    if (availability == RuntimeFormV2Availability.Unavailable) {
        CreateAgentRuntimeSection.Unavailable
    } else {
        when (load) {
            is RuntimeFormV2DialogLoad.Ready -> CreateAgentRuntimeSection.Ready(load.loaded)
            is RuntimeFormV2DialogLoad.Failed -> CreateAgentRuntimeSection.Failed(load.code)
            RuntimeFormV2DialogLoad.Loading -> CreateAgentRuntimeSection.Loading
        }
    }

/** Create needs the loaded v2 form and no field errors. */
internal fun createAgentRuntimeFormV2SubmitReady(formLoaded: Boolean, hasErrors: Boolean): Boolean =
    formLoaded && !hasErrors

internal sealed interface RuntimeFormV2DialogLoad {
    data object Loading : RuntimeFormV2DialogLoad
    data class Ready(val loaded: RuntimeFormV2Loaded) : RuntimeFormV2DialogLoad
    data class Failed(val code: String) : RuntimeFormV2DialogLoad
}

/**
 * The create submit: an external agent sends no runtime; any other agent sends the v2 form
 * ([loaded] with [values]), or nothing while the form has not loaded.
 */
internal fun createAgentDialogSubmit(
    name: String,
    description: String,
    machineId: String?,
    external: Boolean,
    runtime: String,
    loaded: RuntimeFormV2Loaded?,
    values: RuntimeFormV2Values
): SharedCreateAgentDialogSubmit? {
    val cleanName = name.trim().removePrefix("@")
    val cleanDescription = description.trim().takeIf { it.isNotBlank() }
    if (external) return SharedCreateAgentDialogSubmit(cleanName, cleanDescription, machineId = null, external = true)
    val form = loaded ?: return null
    return SharedCreateAgentDialogSubmit(
        name = cleanName,
        description = cleanDescription,
        machineId = machineId,
        runtimeFormV2 =
            RuntimeFormV2SubmitBody(
                runtimeId = form.form.runtimeId.ifBlank { runtime },
                formValues = runtimeFormV2Submission(form.form, values, form.sources)
            )
    )
}

internal const val SharedCreateAgentDialogHostRoute = "create-agent"

@Composable
internal fun CreateAgentDialogVisualContent() {
    val params = (LocalActivity.current.getPager() as Pager).pageData.params
    val runtime = params.optString("runtime").ifBlank { "codex" }
    val machineRuntimes =
        (listOf(runtime, "codex", "claude") + RUNTIMES.filterNot { it.deprecated }.map { it.id }).distinct()
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(RaftColor.SurfaceWhite)
    ) {
        SharedCreateAgentDialog(
            submitting = false,
            errorMessage = params.optString("errorMessage").takeIf { it.isNotBlank() },
            initialName = params.optString("name").ifBlank { "Product-QA-Bot" },
            initialDescription = "Watches release screenshots and reports visual regressions before merge.",
            initialRuntime = runtime,
            initialAdvancedOpen = params.optBoolean("advancedOpen"),
            machines =
                listOf(
                    SharedCreateAgentMachineOption(
                        id = "computer-mbp",
                        name = "Jiachengs-MacBook-Pro",
                        status = "online",
                        runtimes = machineRuntimes
                    ),
                    SharedCreateAgentMachineOption(
                        id = "computer-studio",
                        name = "Studio Test Rig",
                        status = "offline",
                        runtimes = listOf("codex")
                    )
                ),
            // React's deterministic visual fixture seeds the Free plan at its
            // 2-agent capacity, so both providers must render the same warning
            // state instead of comparing React's banner with Android's normal form.
            currentAgentCount = 2,
            maxAgents = 2,
            limitPlanLabel = "Free",
            onDismiss = {},
            onSubmit = {}
        )
    }
}

/**
 * After a create succeeds: remembers its Computer and runtime for the next dialog on [serverId]
 * (web writes the last config after the create). External agents have neither and are skipped.
 */
internal fun SharedCreateAgentFormStore.rememberCreatedAgent(serverId: String?, submit: SharedCreateAgentDialogSubmit) {
    if (submit.external) return
    rememberCreatedAgent(serverId, submit.machineId, submit.runtimeFormV2?.runtimeId)
}

/**
 * Convert a dialog submit to the data-layer [build.raft.app.data.CreateAgentRequest].
 * Keeps the data layer free of UI types — the dialog knows about runtime
 * config, the coordinator knows about the body shape; the mapping lives
 * here at the boundary.
 */
internal fun SharedCreateAgentDialogSubmit.toCreateAgentRequest(): build.raft.app.data.CreateAgentRequest =
    build.raft.app.data.CreateAgentRequest(
        name = name,
        description = description,
        machineId = machineId,
        external = external,
        avatarUrl = randomCreateAgentAvatarWireValue(),
        runtimeFormV2 = runtimeFormV2
    )

private fun randomCreateAgentAvatarWireValue(): String {
    val keys = PixelAvatarPickerKeys
    val key = if (keys.isEmpty()) PixelAvatarActiveDefaultKey else keys.random()
    return "pixel:$key"
}

internal data class SharedCreateAgentMachineOption(
    val id: String,
    val name: String,
    val status: String,
    val runtimes: List<String> = emptyList(),
    /** For the quota lines ([build.raft.app.data.canViewMachineRuntimeAccountUsage]). */
    val attachedByCurrentUser: Boolean = false,
    val creatorId: String? = null,
    /** False when the option came without the machine list (route params): the server decides. */
    val usageFactsKnown: Boolean = false
)

/** Each computer's quota line: the usage of [runtimeId] (the runtime being picked) on it. */
@Composable
internal fun rememberCreateAgentMachineQuotaLines(
    access: MembersRuntimeAccountUsageAccess?,
    machines: List<SharedCreateAgentMachineOption>,
    runtimeId: String
): Map<String, String> =
    rememberRuntimeQuotaLines(access, remember(machines, runtimeId) { machines.map { it.quotaRequest(runtimeId) } })

/** The computer picker's options, each with its quota line as the description. */
internal fun createAgentMachineChoiceOptions(
    items: List<CreateAgentDropdownItem>,
    quotaLines: Map<String, String>
): List<RaftComboboxOption<String>> =
    items.map { item -> RaftComboboxOption(item.id, item.label, description = quotaLines[item.id], enabled = item.enabled) }

internal data class SharedCreateAgentRuntimeModelOption(
    val id: String,
    val label: String,
    val verified: String? = null,
    val supportedReasoningEfforts: List<String> = emptyList(),
    val defaultReasoningEffort: String? = null
)

internal data class SharedCreateAgentRuntimeModelCatalog(
    val models: List<SharedCreateAgentRuntimeModelOption>,
    val defaultModel: String? = null
)

internal data class CreateAgentDropdownItem(
    val id: String,
    val label: String,
    val custom: Boolean = false,
    val enabled: Boolean = true
)

internal const val CREATE_AGENT_DESCRIPTION_MAX_LENGTH = 3000
internal const val CREATE_AGENT_NO_MACHINE_RUNTIMES_ERROR =
    "Selected computer has no runtime data. Refresh computers or choose another computer."

private fun normalizeCreateAgentRuntimeIds(runtimeIds: List<String>): List<String> =
    runtimeIds
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()

internal fun createAgentRuntimeAvailabilityError(
    selectedMachineId: String?,
    machineRuntimeIds: List<String>,
    external: Boolean
): String? =
    if (!external && !selectedMachineId.isNullOrBlank() && normalizeCreateAgentRuntimeIds(machineRuntimeIds).isEmpty()) {
        CREATE_AGENT_NO_MACHINE_RUNTIMES_ERROR
    } else {
        null
    }

internal fun createAgentMachineDropdownItems(
    machines: List<SharedCreateAgentMachineOption>,
    requiredComputer: String?,
    offlineSuffix: String = " (offline)"
): List<CreateAgentDropdownItem> =
    machines.map { machine ->
        val isOffline = machine.status != "online"
        val blockedByRequired = !requiredComputer.isNullOrBlank() && machine.id != requiredComputer
        CreateAgentDropdownItem(
            id = machine.id,
            label = machine.name + if (isOffline) offlineSuffix else "",
            enabled = !isOffline && !blockedByRequired
        )
    }

internal fun createAgentInitialMachineSelection(
    machines: List<SharedCreateAgentMachineOption>,
    requiredComputer: String?,
    suggestedComputer: String?
): String? {
    fun online(id: String?): String? =
        id?.takeIf { candidate -> machines.any { it.id == candidate && it.status == "online" } }
    if (!requiredComputer.isNullOrBlank()) {
        return online(requiredComputer)
    }
    return online(suggestedComputer) ?: machines.firstOrNull { it.status == "online" }?.id
}

internal fun createAgentAvailableMachineId(
    machines: List<SharedCreateAgentMachineOption>,
    selectedMachineId: String?
): String? =
    selectedMachineId?.takeIf { selectedId -> machines.any { machine -> machine.id == selectedId } }

@Composable
internal fun SharedCreateAgentDialog(
    submitting: Boolean,
    errorMessage: String?,
    initialName: String = "",
    initialDescription: String = "",
    initialRuntime: String = "",
    initialAdvancedOpen: Boolean = false,
    draftHint: String? = null,
    suggestedComputer: String? = null,
    requiredComputer: String? = null,
    machines: List<SharedCreateAgentMachineOption> = emptyList(),
    machinesLoading: Boolean = false,
    machinesErrorMessage: String? = null,
    onMachinesRetry: () -> Unit = {},
    createAgentFormStore: SharedCreateAgentFormStore? = null,
    createAgentServerId: String? = null,
    currentAgentCount: Int = 0,
    maxAgents: Int = -1,
    limitPlanLabel: String? = null,
    external: Boolean = false,
    runtimeFormV2Override: RuntimeFormV2Access? = null,
    errorFieldKeys: Set<String> = emptySet(),
    takenAgentNames: List<String> = emptyList(),
    onDismiss: () -> Unit,
    onSubmit: (SharedCreateAgentDialogSubmit) -> Unit
) {
    val focusManager = LocalFocusManager.current
    // A fresh create starts with a suggested `adjective-noun` name instead of an empty
    // field (react CreateAgentDialog parity); the dice button beside the label rolls
    // another. Prefilled names (action cards, typed member query) are kept as-is.
    var name by remember(initialName) {
        mutableStateOf(initialName.ifBlank { suggestAgentName(takenAgentNames) })
    }
    var description by remember(initialDescription) { mutableStateOf(textFieldValueAtEnd(initialDescription)) }
    // Nothing until the selected computer's runtime-options land (web: `""` until then); see
    // [createAgentRuntimeForOptions] below.
    var runtime by remember(initialRuntime) { mutableStateOf(initialRuntime.trim()) }
    var advancedOpen by remember(initialAdvancedOpen) { mutableStateOf(initialAdvancedOpen) }
    var selectedMachineId by remember {
        mutableStateOf<String?>(
            createAgentInitialMachineSelection(
                machines = machines,
                requiredComputer = requiredComputer,
                suggestedComputer = suggestedComputer
            )
        )
    }
    val availableSelectedMachineId = createAgentAvailableMachineId(machines, selectedMachineId)
    // Read here: dialog content is hosted at the page root, outside the route's usage access.
    val runtimeUsageAccess = LocalMembersRuntimeAccountUsageAccess.current
    val selectedQuotaMachine =
        machines.firstOrNull { it.id == availableSelectedMachineId }?.quotaMachine()?.copy(access = runtimeUsageAccess)
    val cleanCreateAgentServerId = createAgentServerId?.trim().orEmpty()
    // The runtimes this server offers (GET servers/:id/runtimes): the cached answer first, then the
    // server's; the bundled table until either lands. Never blocks the dialog.
    var serverRuntimeCatalog by remember(createAgentFormStore, cleanCreateAgentServerId) {
        mutableStateOf(RaftRuntimeCatalogNames.catalog(cleanCreateAgentServerId) ?: BundledRuntimeCatalog)
    }
    LaunchedEffect(createAgentFormStore, cleanCreateAgentServerId) {
        val formStore = createAgentFormStore ?: return@LaunchedEffect
        if (cleanCreateAgentServerId.isBlank()) return@LaunchedEffect
        if (serverRuntimeCatalog.source == RuntimeCatalogSource.Bundled) {
            formStore.cachedServerRuntimeCatalog(cleanCreateAgentServerId).let { if (it != serverRuntimeCatalog) serverRuntimeCatalog = it }
        }
        formStore.loadServerRuntimeCatalog(cleanCreateAgentServerId, DataRequestPriority.Visible).let {
            if (it != serverRuntimeCatalog) serverRuntimeCatalog = it
        }
    }
    val supportedRuntimeIds = remember(serverRuntimeCatalog) { serverRuntimeCatalog.creatableRuntimeIds() }
    // The computer's runtime-options rows: which runtimes have a v2 form (`runtimeFormV2` marker) and
    // whether Built-in is offered. Reloaded on every computer change; a failure leaves it unknown.
    var runtimeCatalogAttempt by remember { mutableStateOf(0) }
    var runtimeCatalog by remember(createAgentFormStore, cleanCreateAgentServerId, availableSelectedMachineId, external) {
        mutableStateOf<SharedCreateAgentRuntimeCatalog?>(null)
    }
    LaunchedEffect(createAgentFormStore, cleanCreateAgentServerId, availableSelectedMachineId, external, runtimeCatalogAttempt) {
        val formStore = createAgentFormStore ?: return@LaunchedEffect
        val machineId = availableSelectedMachineId ?: return@LaunchedEffect
        if (external || cleanCreateAgentServerId.isBlank()) return@LaunchedEffect
        val result = formStore.loadRuntimeCatalog(cleanCreateAgentServerId, machineId, force = true)
        runtimeCatalog = (result as? DataResult.Content)?.data
    }
    val builtInRuntimeEnabled = runtimeCatalog?.options?.any { it.runtimeId == "builtin" && it.selectable } == true
    // The default comes from the computer's runtime-options alone (installed and allowed here); the
    // catalog only names, groups and gives forms. The last create's runtime on this server wins while
    // it is still one of them (web CreateAgentDialog `pickCreateAgentRuntime`).
    val rememberedRuntime =
        remember(createAgentFormStore, cleanCreateAgentServerId, external) {
            if (external) null else createAgentFormStore?.rememberedCreateRuntime(cleanCreateAgentServerId)
        }
    val runtimeFormV2 = runtimeFormV2Override ?: createAgentFormStore?.runtimeFormV2Access(cleanCreateAgentServerId)
    // No v2 access (no form store or server) means no form can load: say so instead of loading forever.
    val v2Availability =
        if (runtimeFormV2 == null) RuntimeFormV2Availability.Unavailable else runtimeCatalog.runtimeFormV2Availability(runtime)
    val v2Candidate =
        runtimeFormV2 != null &&
            !external &&
            availableSelectedMachineId != null &&
            runtime.isNotBlank() &&
            v2Availability != RuntimeFormV2Availability.Unavailable
    var v2LoadAttempt by remember(runtimeFormV2, availableSelectedMachineId, runtime, external) { mutableStateOf(0) }
    // A form that went stale mid-session (`form_revision_stale`) is reloaded whole, once; after that it
    // fails like any other load (the retry banner).
    var v2StaleReloads by remember(runtimeFormV2, availableSelectedMachineId, runtime, external) { mutableStateOf(0) }
    var v2Load by remember(runtimeFormV2, availableSelectedMachineId, runtime, external, v2LoadAttempt) {
        mutableStateOf<RuntimeFormV2DialogLoad>(RuntimeFormV2DialogLoad.Loading)
    }
    val runtimeSection = createAgentRuntimeSection(v2Availability, v2Load)
    val v2Loaded = (runtimeSection as? CreateAgentRuntimeSection.Ready)?.loaded
    var v2Values by remember(v2Loaded) {
        mutableStateOf(v2Loaded?.let { initialRuntimeFormV2Values(it.form, it.sources) }.orEmpty())
    }
    var v2Touched by remember(v2Loaded) { mutableStateOf(emptySet<String>()) }
    // Re-probed option sources sit on top of the loaded form; render, validate and submit against them.
    val (v2Form, v2SourceRefresh) =
        rememberRuntimeFormV2SourceRefresh(
            runtimeFormV2,
            availableSelectedMachineId,
            v2Loaded,
            onFormStale = {
                if (v2StaleReloads < 1) {
                    v2StaleReloads += 1
                    v2LoadAttempt += 1
                } else {
                    v2Load = RuntimeFormV2DialogLoad.Failed(RUNTIME_FORM_V2_FORM_REVISION_STALE)
                }
            }
        ) { updated, sourceId ->
            v2Values = runtimeFormV2ValuesAfterSourceReload(updated.form, updated.sources, v2Values, sourceId)
        }
    val v2Errors = v2Form?.let { validateRuntimeFormV2(it.form, it.sources, v2Values) }.orEmpty()

    LaunchedEffect(machines, requiredComputer, suggestedComputer, selectedMachineId) {
        if (external) return@LaunchedEffect
        if (selectedMachineId == null) {
            selectedMachineId = createAgentInitialMachineSelection(machines, requiredComputer, suggestedComputer)
        }
    }

    // Keeps a choice only while this computer's runtime-options still offer it; otherwise picks the
    // default. No usable runtime leaves the field empty instead of guessing; options still loading
    // (or another computer's) change nothing.
    LaunchedEffect(availableSelectedMachineId, runtimeCatalog, supportedRuntimeIds, rememberedRuntime, external) {
        if (external) return@LaunchedEffect
        val next =
            createAgentRuntimeForOptions(
                current = runtime,
                machineId = availableSelectedMachineId,
                options = runtimeCatalog,
                supported = supportedRuntimeIds,
                remembered = rememberedRuntime
            )
        if (next != runtime) runtime = next
    }

    LaunchedEffect(runtimeFormV2, availableSelectedMachineId, runtime, external, v2LoadAttempt, v2Candidate) {
        val source = runtimeFormV2 ?: return@LaunchedEffect
        val machineId = availableSelectedMachineId ?: return@LaunchedEffect
        if (!v2Candidate) return@LaunchedEffect
        v2Load = when (val result = source.loader.loadCreate(source.serverId, machineId, runtime)) {
            is DataResult.Content -> RuntimeFormV2DialogLoad.Ready(result.data)
            is DataResult.Error -> RuntimeFormV2DialogLoad.Failed(result.error.code)
            is DataResult.NoPermission -> RuntimeFormV2DialogLoad.Failed(result.blocker.code)
            is DataResult.Empty -> RuntimeFormV2DialogLoad.Failed(result.reason.name)
        }
    }

    val trimmedName = name.trim()
    val inlineNameError = if (trimmedName.isNotEmpty()) validateAgentName(trimmedName) else null
    val localizedInlineNameError = inlineNameError?.let { localizedCreateAgentNameError(trimmedName, it) }
    val descriptionTooLong = description.text.length > CREATE_AGENT_DESCRIPTION_MAX_LENGTH
    val atLimit = !external && maxAgents >= 0 && currentAgentCount >= maxAgents
    val selectedMachine = machines.firstOrNull { it.id == selectedMachineId }
    val selectedMachineRuntimeIds =
        normalizeCreateAgentRuntimeIds(
            selectedMachine?.runtimes.orEmpty().filterNot { it == "builtin" } +
                if (builtInRuntimeEnabled) {
                    listOf("builtin")
                } else {
                    emptyList()
                }
        )
    val runtimeAvailabilityError =
        createAgentRuntimeAvailabilityError(
            selectedMachineId = selectedMachine?.id,
            machineRuntimeIds = selectedMachineRuntimeIds,
            external = external
        )
    val selectedMachineBlocked = !external &&
        (selectedMachine == null ||
            selectedMachine.status != "online" ||
            (!requiredComputer.isNullOrBlank() && selectedMachine.id != requiredComputer))
    val canSubmit = trimmedName.isNotBlank() &&
        inlineNameError == null &&
        !descriptionTooLong &&
        !atLimit &&
        !submitting &&
        !selectedMachineBlocked &&
        runtimeAvailabilityError == null &&
        (external || runtime in supportedRuntimeIds) &&
        (external || createAgentRuntimeFormV2SubmitReady(v2Loaded != null, v2Errors.isNotEmpty()))
    val showMachinePicker = !external

    RaftUnifiedDialog(
        title =
            if (external) {
                stringResource(Res.string.agent_create_title_external)
            } else {
                stringResource(Res.string.agent_create_title)
            },
        dismissEnabled = !submitting,
        onDismiss = onDismiss,
        keyboardAvoidanceEnabled = true,
        hostRoute = SharedCreateAgentDialogHostRoute
    ) {
        // #55 visual parity: no body height cap. The Column fills the viewport, the
        // LazyColumn takes the leftover space and Cancel/Create stay pinned at the bottom
        // (React's `card-brutal p-6` dialog).
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
            // React's normal dialog form uses space-y-4. Keep each field
            // group at the same 16dp rhythm; field-internal helper/count
            // spacing stays inside the group below.
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
                draftHint?.takeIf { it.isNotBlank() }?.let { hint ->
                    item(key = "draft-hint") {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .border(RaftAppStroke.Thin, RaftColor.BorderBlack.copy(alpha = 0.2f))
                                    .padding(RaftAppSpacing.Xs)
                        ) {
                            Text(
                                text = hint,
                                style = RaftType.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp, color = RaftColor.TextMutedGray),
                                color = RaftColor.TextMutedGray
                            )
                        }
                    }
                }
                if (atLimit) {
                    item(key = "plan-limit") {
                        PlanLimitBanner(
                            currentAgentCount = currentAgentCount,
                            maxAgents = maxAgents,
                            planLabel = limitPlanLabel
                        )
                    }
                }
                errorMessage?.takeIf { !atLimit && it.isNotBlank() }?.let { error ->
                    item(key = "submit-error") {
                        RaftUiBanner(
                            status = RaftUiBannerStatus.Warning,
                            description = error,
                            size = RaftUiBannerSize.Medium
                        )
                    }
                }
                if (showMachinePicker) {
                    item(key = "machine-picker") {
                        MachinePicker(
                            machines = machines,
                            machinesLoading = machinesLoading,
                            machinesErrorMessage = machinesErrorMessage,
                            onRetry = onMachinesRetry,
                            selectedMachineId = selectedMachineId,
                            requiredComputer = requiredComputer,
                            suggestedComputer = suggestedComputer,
                            enabled = !submitting,
                            runtimeId = runtime,
                            usageAccess = runtimeUsageAccess,
                            onSelect = { id ->
                                if (requiredComputer.isNullOrBlank() || id == requiredComputer) {
                                    focusManager.clearFocus(force = true)
                                    if (id != selectedMachineId) {
                                        selectedMachineId = id
                                        // Another computer: its own runtime-options decide (web clears it too).
                                        runtime = ""
                                    }
                                }
                            }
                        )
                    }
                }
                item(key = "name") {
                    RaftBrutalTextField(
                        label = stringResource(Res.string.agent_create_label_name),
                        required = true,
                        value = name,
                        keyboardOptions = RaftTextFieldDefaults.NoAutoCorrectKeyboardOptions,
                        onValueChange = { name = it },
                        placeholder = stringResource(Res.string.agent_create_placeholder_name),
                        enabled = !submitting,
                        autoFocus = true,
                        labelAccessory = {
                            CreateAgentRandomNameButton(
                                enabled = !submitting,
                                onClick = { name = suggestAgentName(listOf(name) + takenAgentNames) }
                            )
                        }
                    )
                }
                if (inlineNameError != null) {
                    item(key = "name-error") {
                        Text(
                            text = localizedInlineNameError ?: inlineNameError,
                            style = RaftType.Caption.withRaftFontWeight(FontWeight.Bold).copy(color = RaftColor.AccentRed),
                            color = RaftColor.AccentRed
                        )
                    }
                }
                item(key = "description") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        RaftBrutalTextField(
                            label = stringResource(Res.string.agent_create_label_description),
                            optional = true,
                            value = description,
                            onValueChange = { description = it.coerceTextLength(CREATE_AGENT_DESCRIPTION_MAX_LENGTH) },
                            placeholder = stringResource(Res.string.agent_create_placeholder_description),
                            enabled = !submitting,
                            maxLines = 3,
                            fieldMinHeight = 82.dp
                        )
                        if (descriptionTooLong) {
                            Text(
                                text =
                                    Res.string.agent_create_error_description_length
                                        .format(listOf(CREATE_AGENT_DESCRIPTION_MAX_LENGTH))
                                        .localized(),
                                style = RaftType.Caption.withRaftFontWeight(FontWeight.Bold).copy(color = RaftColor.AccentRed),
                                color = RaftColor.AccentRed
                            )
                        } else {
                            Row(modifier = Modifier.fillMaxWidth()) {
                                Spacer(Modifier.weight(1f))
                                Text(
                                    text = "${description.text.length}/$CREATE_AGENT_DESCRIPTION_MAX_LENGTH",
                                    style = RaftType.Caption,
                                    color = RaftColor.TextMutedGray
                                )
                            }
                        }
                    }
                }
                if (!external && availableSelectedMachineId != null) {
                    when (runtimeSection) {
                        is CreateAgentRuntimeSection.Failed ->
                            item(key = "runtime-form-v2-failure") {
                                RaftUiBanner(
                                    status = RaftUiBannerStatus.Warning,
                                    description =
                                        Res.string.runtime_form_v2_load_failed.format(listOf(runtimeSection.code)).localized(),
                                    size = RaftUiBannerSize.Medium,
                                    action = {
                                        RaftBrutalActionButton(
                                            label = stringResource(Res.string.common_retry),
                                            onClick = {
                                                v2StaleReloads = 0
                                                v2LoadAttempt += 1
                                                runtimeCatalogAttempt += 1
                                            },
                                            enabled = !submitting,
                                            leadingIcon = RaftLucideIconName.RefreshCw,
                                            minWidth = 72.dp,
                                            minHeight = 32.dp
                                        )
                                    }
                                )
                            }
                        CreateAgentRuntimeSection.Unavailable ->
                            item(key = "runtime-form-v2-unavailable") {
                                RaftUiBanner(
                                    status = RaftUiBannerStatus.Warning,
                                    description = stringResource(Res.string.runtime_form_v2_unavailable),
                                    size = RaftUiBannerSize.Medium
                                )
                            }
                        else -> Unit
                    }
                }
                if (!external) {
                    item(key = "runtime") {
                        RuntimeFormV2RuntimeSection(
                            runtime = runtime,
                            catalog = serverRuntimeCatalog,
                            serverSelectable = { id -> runtimeCatalog?.options?.firstOrNull { it.runtimeId == id }?.canSelectInThisContext },
                            quotaMachine = selectedQuotaMachine,
                            onRuntimeChange = { newRuntime -> runtime = newRuntime },
                            machineRuntimeIds = selectedMachineRuntimeIds,
                            runtimeAvailabilityError = runtimeAvailabilityError,
                            enabled = !submitting
                        ) {
                            if (runtimeSection == CreateAgentRuntimeSection.Loading && availableSelectedMachineId != null) {
                                Text(
                                    text = stringResource(Res.string.agent_create_state_computers_loading),
                                    style = RaftType.Caption,
                                    color = RaftColor.TextMutedGray
                                )
                            } else if (v2Loaded != null) {
                                RuntimeFormV2Fields(
                                    loaded = v2Form ?: v2Loaded,
                                    values = v2Values,
                                    onChange = { key, value ->
                                        val current = v2Form ?: v2Loaded
                                        v2Values = applyRuntimeFormV2Change(current.form, current.sources, v2Values, key, value)
                                        v2Touched = v2Touched + key
                                    },
                                    errors = runtimeFormV2ShownErrors(v2Errors, v2Touched + errorFieldKeys),
                                    serverErrorKeys = errorFieldKeys,
                                    serverMessage = null,
                                    editing = false,
                                    advancedOpen = advancedOpen,
                                    onAdvancedOpenChange = { advancedOpen = it },
                                    enabled = !submitting,
                                    sourceRefresh = v2SourceRefresh
                                )
                            }
                        }
                    }
                }
            }
            RaftDialogFooter {
                RaftButton(
                    text = stringResource(Res.string.common_cancel),
                    enabled = !submitting,
                    onClick = onDismiss,
                    variant = RaftButtonVariant.Ghost,
                    size = RaftButtonSize.Lg
                )
                RaftBrutalActionButton(
                    label =
                        if (submitting) {
                            stringResource(Res.string.agent_create_action_creating)
                        } else {
                            stringResource(Res.string.agent_create_submit)
                        },
                    enabled = canSubmit,
                    minWidth = 126.dp,
                    backgroundColor = RaftColor.AccentPink,
                    onClick = {
                        val submit =
                            createAgentDialogSubmit(
                                name = trimmedName,
                                description = description.text,
                                machineId = requiredComputer?.takeIf { it.isNotBlank() } ?: selectedMachineId,
                                external = external,
                                runtime = runtime,
                                loaded = v2Form ?: v2Loaded,
                                values = v2Values
                            ) ?: return@RaftBrutalActionButton
                        onSubmit(submit)
                    }
                )
            }
        }
    }

@Composable
private fun localizedCreateAgentNameError(name: String, fallback: String): String =
    when {
        " is reserved." in fallback ->
            Res.string.agent_create_error_name_reserved
                .format(listOf(name.removePrefix("@").lowercase()))
                .localized()
        "at least" in fallback -> stringResource(Res.string.agent_create_error_name_min)
        "at most" in fallback -> stringResource(Res.string.agent_create_error_name_max)
        "must start with a letter" in fallback -> stringResource(Res.string.agent_create_error_name_characters)
        else -> fallback
    }

@Composable
private fun PlanLimitBanner(currentAgentCount: Int, maxAgents: Int, planLabel: String?) {
    if (planLabel.isNullOrBlank()) {
        RaftUiBanner(
            status = RaftUiBannerStatus.Warning,
            description =
                Res.string.agent_create_error_limit
                    .format(listOf(currentAgentCount, maxAgents))
                    .localized(),
            size = RaftUiBannerSize.Medium
        )
        return
    }

    val summary =
        Res.string.agent_create_error_limit_plan
            .format(listOf(currentAgentCount, maxAgents, planLabel))
            .localized()
    val action = stringResource(Res.string.agent_create_action_upgrade_for_more)
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(RaftUiBannerStatus.Warning.background)
                .border(RaftAppStroke.Default, RaftColor.BorderBlack)
                .padding(RaftUiBannerSize.Medium.padding)
    ) {
        Text(
            text =
                buildAnnotatedString {
                    append(summary)
                    append(" ")
                    pushStyle(SpanStyle(textDecoration = TextDecoration.Underline))
                    append(action)
                    pop()
                },
            style = RaftType.Body.withRaftFontWeight(FontWeight.Bold).copy(fontSize = 14.sp, lineHeight = 18.sp),
            color = RaftColor.TextBlack
        )
    }
}

@Composable
internal fun MachinePicker(
    machines: List<SharedCreateAgentMachineOption>,
    machinesLoading: Boolean,
    machinesErrorMessage: String?,
    onRetry: () -> Unit,
    selectedMachineId: String?,
    requiredComputer: String?,
    suggestedComputer: String?,
    enabled: Boolean,
    runtimeId: String,
    usageAccess: MembersRuntimeAccountUsageAccess?,
    onSelect: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        RaftFieldLabel(text = stringResource(Res.string.agent_create_label_computer), required = true)
        when {
            machinesLoading -> Text(
                text = stringResource(Res.string.agent_create_state_computers_loading),
                style = RaftType.Caption,
                color = RaftColor.TextMutedGray
            )
            !machinesErrorMessage.isNullOrBlank() ->
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            // deliberate softened error border over flat cream (no shadow compositing risk); triaged token-audit #1063
                            .border(RaftAppStroke.Thin, RaftColor.AccentRed.copy(alpha = 0.5f))
                            .background(RaftColor.SurfaceCream)
                            .padding(RaftAppSpacing.Sm),
                    verticalArrangement = Arrangement.spacedBy(RaftAppSpacing.Sm)
                ) {
                    Text(
                        text = machinesErrorMessage,
                        style = RaftType.Caption.withRaftFontWeight(FontWeight.Bold),
                        color = RaftColor.AccentRed
                    )
                    RaftBrutalActionButton(
                        label = stringResource(Res.string.common_retry),
                        onClick = onRetry,
                        enabled = enabled,
                        leadingIcon = RaftLucideIconName.RefreshCw,
                        minWidth = 72.dp,
                        minHeight = 32.dp
                    )
                }
            machines.isEmpty() -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(RaftAppStroke.Thin, RaftColor.BorderBlack.copy(alpha = 0.3f))
                    .background(RaftColor.SurfaceCream)
                    .padding(RaftAppSpacing.Sm)
            ) {
                Text(
                    text = stringResource(Res.string.agent_create_state_computers_empty),
                    style = RaftType.Caption,
                    color = RaftColor.TextMutedGray
                )
            }
            else -> {
                val machineItems =
                    createAgentMachineDropdownItems(
                        machines = machines,
                        requiredComputer = requiredComputer,
                        offlineSuffix = stringResource(Res.string.agent_create_suffix_offline)
                    )
                // Jasper's picker rule: radios up to 5 computers, a sheet list up to 10, then search.
                // Each computer carries the chosen runtime's quota line there.
                val quotaLines = rememberCreateAgentMachineQuotaLines(usageAccess, machines, runtimeId)
                RuntimePickerChoice(
                    value = selectedMachineId,
                    options = createAgentMachineChoiceOptions(machineItems, quotaLines),
                    onValueChange = onSelect,
                    title = stringResource(Res.string.agent_create_label_computer),
                    placeholder = stringResource(Res.string.agent_create_placeholder_select_computer),
                    enabled = enabled
                )
            }
        }
        if (!requiredComputer.isNullOrBlank()) {
            Text(
                text = Res.string.agent_create_hint_required_computer.format(listOf(requiredComputer)).localized(),
                style = RaftType.Caption,
                color = RaftColor.TextMutedGray
            )
        } else if (!suggestedComputer.isNullOrBlank()) {
            Text(
                text = Res.string.agent_create_hint_suggested_computer.format(listOf(suggestedComputer)).localized(),
                style = RaftType.Caption,
                color = RaftColor.TextMutedGray
            )
        }
    }
}

@Composable
internal fun AdvancedToggleButton(open: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        RaftLucideIcon(
            if (open) RaftLucideIconName.ChevronDown else RaftLucideIconName.ChevronRight,
            modifier = Modifier.size(16.dp),
            color = RaftColor.TextMutedGrayArgb
        )
        Text(
            text = stringResource(Res.string.agent_create_label_advanced),
            style = RaftType.Caption.withRaftFontWeight(FontWeight.Bold),
            color = RaftColor.TextMutedGray
        )
    }
}

@Composable
internal fun CreateAgentSectionLabelWithAction(
    label: String,
    required: Boolean = false,
    actionIcon: RaftLucideIconName,
    actionEnabled: Boolean,
    loading: Boolean = false,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RaftFieldLabel(text = label, required = required, modifier = Modifier.weight(1f))
        Box(
            modifier =
                Modifier
                    .size(22.dp)
                    .clickable(enabled = actionEnabled) { onAction() },
            contentAlignment = Alignment.Center
        ) {
            if (loading) {
                RaftCreateAgentSpinnerIcon()
            } else {
                RaftLucideIcon(
                    actionIcon,
                    modifier = Modifier.size(13.dp),
                    color = if (actionEnabled) RaftColor.TextMutedGrayArgb else if (RaftAppThemeState.isDark) raftArgbHex(RaftColor.ForegroundDisabled) else "#33000000"
                )
            }
        }
    }
}

/**
 * Dice button beside the Name label that rolls another suggested name (react
 * `create-agent-random-name`: ghost, icon-xs, Dices 12px). Ghost = no fill, border or
 * shadow, so it reads as a label accessory rather than a primary control.
 */
@Composable
private fun CreateAgentRandomNameButton(
    enabled: Boolean,
    onClick: () -> Unit
) {
    RaftBrutalIconButton(
        iconName = RaftLucideIconName.Dices,
        onClick = onClick,
        contentDescription = stringResource(Res.string.agent_create_random_name),
        enabled = enabled,
        backgroundColor = Color.Transparent,
        disabledBackgroundColor = Color.Transparent,
        borderColor = Color.Transparent,
        disabledBorderColor = Color.Transparent,
        shadowColor = Color.Transparent,
        disabledShadowColor = Color.Transparent,
        iconColor = RaftColor.TextBlackArgb,
        disabledIconColor = RaftColor.TextMutedGrayArgb,
        size = 22.dp,
        iconSize = 13.dp
    )
}

@Composable
private fun RaftCreateAgentSpinnerIcon() {
    RaftIconSpinner(size = 13.dp, color = RaftColor.TextMutedGray)
}

@Composable
internal fun EnvVarsEditor(
    entries: List<Pair<String, String>>,
    onChange: (List<Pair<String, String>>) -> Unit,
    enabled: Boolean,
    label: String? = null,
    required: Boolean = false
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        RaftFieldLabel(
            text = label ?: stringResource(Res.string.agent_create_label_environment_variables),
            required = required,
            optional = !required
        )
        entries.forEachIndexed { idx, (key, value) ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RaftBrutalTextField(
                    label = stringResource(Res.string.agent_create_label_key),
                    value = key,
                    keyboardOptions = RaftTextFieldDefaults.NoAutoCorrectKeyboardOptions,
                    onValueChange = { newKey ->
                        onChange(entries.toMutableList().also { it[idx] = newKey to value })
                    },
                    placeholder = "FOO",
                    enabled = enabled,
                    fieldMinHeight = 32.dp,
                    modifier = Modifier.weight(1f)
                )
                RaftBrutalTextField(
                    label = stringResource(Res.string.agent_create_label_value),
                    value = value,
                    onValueChange = { newValue ->
                        onChange(entries.toMutableList().also { it[idx] = key to newValue })
                    },
                    placeholder = "bar",
                    enabled = enabled,
                    fieldMinHeight = 32.dp,
                    modifier = Modifier.weight(1.5f)
                )
                Spacer(Modifier.width(4.dp))
                Box(
                    modifier =
                        Modifier
                            .let { if (enabled) it.clickable { onChange(entries.toMutableList().also { it.removeAt(idx) }) } else it }
                            .padding(horizontal = 8.dp)
                ) {
                    // Icon affordances are Lucide, never text glyphs (task #494).
                    RaftLucideIcon(
                        name = RaftLucideIconName.X,
                        color = if (enabled) RaftColor.AccentRedArgb else RaftColor.TextMutedGrayArgb,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
        Box(
            modifier =
                Modifier
                    .let { if (enabled) it.clickable { onChange(entries + ("" to "")) } else it }
                    .padding(vertical = 4.dp)
        ) {
            Text(
                text = stringResource(Res.string.agent_create_action_add_env_var),
                style = RaftType.Caption.withRaftFontWeight(FontWeight.Bold),
                color = if (enabled) RaftColor.TextBlack else RaftColor.TextMutedGray
            )
        }
    }
}
