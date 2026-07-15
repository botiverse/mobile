package build.raft.app

import build.raft.app.components.SlockBrutalActionButton
import build.raft.app.components.SlockBrutalSelectField
import build.raft.app.components.SlockBrutalSelectOption
import build.raft.app.components.SlockBrutalSectionLabel
import build.raft.app.components.SlockBrutalTextField
import build.raft.app.components.SlockCheckbox
import build.raft.app.components.SlockRaftUiBanner
import build.raft.app.components.SlockRaftUiBannerSize
import build.raft.app.components.SlockRaftUiBannerStatus
import build.raft.app.model.RuntimeConfig
import build.raft.app.model.RuntimeProviderMode
import build.raft.app.model.RUNTIME_MODELS
import build.raft.app.model.RUNTIME_PROVIDER_MODE_CUSTOM
import build.raft.app.model.RUNTIME_PROVIDER_MODE_DEFAULT
import build.raft.app.model.RUNTIMES
import build.raft.app.model.REASONING_EFFORTS
import build.raft.app.model.buildRuntimeConfig
import build.raft.app.model.getDefaultModel
import build.raft.app.model.getModelLabel
import build.raft.app.model.getRuntimeDisplayName
import build.raft.app.model.RuntimeModelInfo
import build.raft.app.model.supportsRuntimeApiUrl
import build.raft.app.model.supportsRuntimeCommand
import build.raft.app.model.supportsRuntimeFastMode
import build.raft.app.model.supportsRuntimeCustomModelName
import build.raft.app.model.supportsRuntimeReasoningEffort
import build.raft.app.model.validateAgentName
import build.raft.app.resources.MR
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.clickable
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.Spacer
import com.tencent.kuikly.compose.foundation.layout.fillMaxHeight
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.heightIn
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.foundation.lazy.LazyColumn
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.platform.LocalFocusManager
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.tmm.kmmresource.compose.stringResource
import com.tencent.tmm.kmmresource.compose.localized
import com.tencent.tmm.kmmresource.resource.format
import com.tencent.kuikly.core.pager.Pager

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
    val runtimeConfig: RuntimeConfig? = null,
    val external: Boolean = false
)

internal const val SharedCreateAgentDialogHostRoute = "create-agent"

@Composable
internal fun CreateAgentDialogVisualContent() {
    val params = (LocalActivity.current.getPager() as Pager).pageData.params
    val runtime = params.optString("runtime").ifBlank { "codex" }
    val providerMode =
        if (params.optString("providerMode") == RUNTIME_PROVIDER_MODE_CUSTOM) {
            RUNTIME_PROVIDER_MODE_CUSTOM
        } else {
            RUNTIME_PROVIDER_MODE_DEFAULT
        }
    val machineRuntimes = (listOf(runtime, "codex", "claude") + RUNTIMES.map { it.id }).distinct()
    val runtimeModels =
        RUNTIME_MODELS[runtime].orEmpty().map { model ->
            SharedCreateAgentRuntimeModelOption(model.id, model.label, model.verified)
        }
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(SlockColor.SurfaceWhite)
    ) {
        SharedCreateAgentDialog(
            submitting = false,
            errorMessage = params.optString("errorMessage").takeIf { it.isNotBlank() },
            initialName = params.optString("name").ifBlank { "Product-QA-Bot" },
            initialDescription = "Watches release screenshots and reports visual regressions before merge.",
            initialRuntime = runtime,
            initialModel = params.optString("model").ifBlank { getDefaultModel(runtime) },
            initialProviderMode = providerMode,
            initialProviderApiUrl = params.optString("providerApiUrl"),
            initialProviderApiKey = params.optString("providerApiKey"),
            initialFastMode = params.optBoolean("fastMode"),
            initialReasoningEffort = params.optString("reasoningEffort").takeIf { it.isNotBlank() },
            initialCommand = params.optString("command"),
            initialAdvancedOpen = params.optBoolean("advancedOpen"),
            suggestedComputer = "computer-mbp",
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
            runtimeModelsByMachineAndRuntime =
                mapOf(
                    ("computer-mbp" to runtime) to
                        SharedCreateAgentRuntimeModelCatalog(
                            models = runtimeModels,
                            defaultModel = getDefaultModel(runtime)
                        )
                ),
            currentAgentCount = 4,
            maxAgents = 12,
            onDismiss = {},
            onSubmit = {}
        )
    }
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
        runtimeConfig = runtimeConfig,
        external = external,
        avatarUrl = randomCreateAgentAvatarWireValue()
    )

internal fun buildSharedCreateAgentDialogSubmit(
    name: String,
    description: String,
    selectedMachineId: String?,
    requiredComputer: String?,
    external: Boolean,
    runtime: String,
    model: String,
    customModelMode: Boolean,
    customModelName: String,
    providerMode: RuntimeProviderMode,
    providerApiUrl: String,
    providerApiKey: String,
    fastMode: Boolean,
    reasoningEffort: String?,
    envVarEntries: List<Pair<String, String>>,
    command: String
): SharedCreateAgentDialogSubmit {
    val finalMachineId =
        if (external) null
        else (requiredComputer?.takeIf { it.isNotBlank() } ?: selectedMachineId)
    val runtimeConfig =
        if (external) {
            null
        } else {
            buildRuntimeConfig(
                runtime = runtime,
                model = model,
                customModelMode = customModelMode,
                customModelName = customModelName.takeIf { customModelMode },
                providerMode = providerMode,
                providerApiUrl = providerApiUrl,
                providerApiKey = providerApiKey,
                fastMode = fastMode,
                reasoningEffort = reasoningEffort,
                envVars = createAgentRuntimeEnvVars(envVarEntries),
                command = command.takeIf { supportsRuntimeCommand(runtime) }
            )
        }
    return SharedCreateAgentDialogSubmit(
        name = name.trim().removePrefix("@"),
        description = description.trim().takeIf { it.isNotBlank() },
        machineId = finalMachineId,
        runtimeConfig = runtimeConfig,
        external = external
    )
}

private fun createAgentRuntimeEnvVars(entries: List<Pair<String, String>>): Map<String, String>? {
    val values =
        entries
            .mapNotNull { (key, value) ->
                key.trim().takeIf { it.isNotBlank() }?.let { it to value }
            }
            .toMap()
    return values.takeIf { it.isNotEmpty() }
}

private fun randomCreateAgentAvatarWireValue(): String {
    val keys = PixelAvatarPickerKeys
    val key = if (keys.isEmpty()) PixelAvatarActiveDefaultKey else keys.random()
    return "pixel:$key"
}

internal data class SharedCreateAgentMachineOption(
    val id: String,
    val name: String,
    val status: String,
    val runtimes: List<String> = emptyList()
)

internal data class SharedCreateAgentRuntimeModelOption(
    val id: String,
    val label: String,
    val verified: String? = null
)

internal data class SharedCreateAgentRuntimeModelCatalog(
    val models: List<SharedCreateAgentRuntimeModelOption>,
    val defaultModel: String? = null
)

internal const val CREATE_AGENT_CUSTOM_MODEL_ID = "__custom_model__"
internal const val CREATE_AGENT_DEFAULT_REASONING_ID = "__default_reasoning_effort__"

internal data class CreateAgentModelDropdownItem(
    val id: String,
    val label: String,
    val custom: Boolean = false
)

internal data class CreateAgentDropdownItem(
    val id: String,
    val label: String,
    val custom: Boolean = false,
    val enabled: Boolean = true
)

private const val DEFAULT_CREATE_AGENT_DESCRIPTION_MAX_LENGTH = 3000
internal const val CREATE_AGENT_NO_MACHINE_RUNTIMES_ERROR =
    "Selected computer has no runtime data. Refresh computers or choose another computer."

private fun normalizeCreateAgentRuntimeIds(runtimeIds: List<String>): List<String> =
    runtimeIds
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()

internal fun createAgentRuntimeDropdownItems(
    machineRuntimeIds: List<String> = emptyList(),
    allowStaticFallback: Boolean = true,
    comingSoonSuffix: String = " (coming soon)",
    notInstalledSuffix: String = " (not installed)"
): List<CreateAgentDropdownItem> {
    val machineRuntimeIdsNormalized = normalizeCreateAgentRuntimeIds(machineRuntimeIds)
    if (machineRuntimeIdsNormalized.isEmpty()) {
        return if (allowStaticFallback) {
            RUNTIMES.filter { it.supported }.map { runtime ->
                CreateAgentDropdownItem(id = runtime.id, label = runtime.displayName)
            }
        } else {
            emptyList()
        }
    }
    val available = machineRuntimeIdsNormalized.toSet()
    return RUNTIMES.map { runtime ->
        val selectable = runtime.supported && runtime.id in available
        val unavailableSuffix =
            when {
                !runtime.supported -> comingSoonSuffix
                runtime.id !in available -> notInstalledSuffix
                else -> ""
            }
        CreateAgentDropdownItem(
            id = runtime.id,
            label = runtime.displayName + unavailableSuffix,
            enabled = selectable
        )
    }
}

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

internal fun createAgentModelDropdownItems(
    runtime: String,
    runtimeModelsOverride: List<SharedCreateAgentRuntimeModelOption> = emptyList(),
    allowCustomModel: Boolean = true,
    customLabel: String = "Custom"
): List<CreateAgentModelDropdownItem> {
    val modelOptions =
        runtimeModelsOverride
            .takeIf { it.isNotEmpty() }
            ?.map { RuntimeModelInfo(it.id, it.label, it.verified) }
            ?: RUNTIME_MODELS[runtime].orEmpty()
    val presets = modelOptions.map { CreateAgentModelDropdownItem(id = it.id, label = it.label) }
    return if (allowCustomModel) {
        presets + CreateAgentModelDropdownItem(id = CREATE_AGENT_CUSTOM_MODEL_ID, label = customLabel, custom = true)
    } else {
        presets
    }
}

internal fun createAgentModelDropdownItemsForSelection(
    runtime: String,
    runtimeModelsOverride: List<SharedCreateAgentRuntimeModelOption> = emptyList(),
    allowCustomModel: Boolean = true,
    selectedModel: String,
    customModelMode: Boolean,
    retainUnavailableSelectedPreset: Boolean,
    customLabel: String = "Custom",
    unavailableSuffix: String = " (not in this computer's config)"
): List<CreateAgentModelDropdownItem> {
    val options =
        createAgentModelDropdownItems(
            runtime = runtime,
            runtimeModelsOverride = runtimeModelsOverride,
            allowCustomModel = allowCustomModel,
            customLabel = customLabel
        )
    if (!retainUnavailableSelectedPreset || customModelMode || selectedModel.isBlank() ||
        options.any { it.id == selectedModel }
    ) {
        return options
    }
    val unavailable =
        CreateAgentModelDropdownItem(
            id = selectedModel,
            label = getModelLabel(runtime, selectedModel) + unavailableSuffix
        )
    val customIndex = options.indexOfFirst { it.custom }
    return if (customIndex >= 0) {
        options.toMutableList().apply { add(customIndex, unavailable) }
    } else {
        options + unavailable
    }
}

internal fun createAgentModelSelectLabel(
    customModelMode: Boolean,
    model: String,
    modelOptions: List<CreateAgentModelDropdownItem>,
    customLabel: String = "Custom",
    selectModelLabel: String = "Select model"
): String =
    if (customModelMode) {
        customLabel
    } else {
        modelOptions.firstOrNull { it.id == model }?.label ?: model.ifBlank { selectModelLabel }
    }

internal fun createAgentModelPresetIds(
    runtime: String,
    runtimeModelsOverride: List<SharedCreateAgentRuntimeModelOption> = emptyList()
): List<String> =
    createAgentModelDropdownItems(runtime, runtimeModelsOverride)
        .filterNot { it.custom }
        .map { it.id }

internal fun createAgentModelSelectionForValue(
    runtime: String,
    model: String,
    runtimeModelsOverride: List<SharedCreateAgentRuntimeModelOption> = emptyList()
): Pair<Boolean, String> {
    val trimmed = model.trim()
    val presetIds = createAgentModelPresetIds(runtime, runtimeModelsOverride)
    return if (trimmed.isNotBlank() && trimmed !in presetIds) {
        true to trimmed
    } else {
        false to trimmed.ifBlank { getDefaultModel(runtime) }
    }
}

internal fun createAgentPreferredModelId(
    runtime: String,
    runtimeModelsOverride: List<SharedCreateAgentRuntimeModelOption> = emptyList(),
    runtimeModelsDefault: String? = null
): String {
    val presetIds = createAgentModelPresetIds(runtime, runtimeModelsOverride)
    return runtimeModelsDefault?.takeIf { it in presetIds }
        ?: presetIds.firstOrNull()
        ?: getDefaultModel(runtime)
}

internal fun createAgentProviderDropdownItems(
    defaultLabel: String = "Default",
    customLabel: String = "Custom"
): List<CreateAgentDropdownItem> =
    listOf(
        CreateAgentDropdownItem(id = RUNTIME_PROVIDER_MODE_DEFAULT, label = defaultLabel),
        CreateAgentDropdownItem(id = RUNTIME_PROVIDER_MODE_CUSTOM, label = customLabel)
    )

internal fun createAgentReasoningDropdownItems(
    defaultLabel: String = "Default",
    effortLabels: Map<String, String> = emptyMap()
): List<CreateAgentDropdownItem> =
    listOf(CreateAgentDropdownItem(id = CREATE_AGENT_DEFAULT_REASONING_ID, label = defaultLabel)) +
        REASONING_EFFORTS.map { (id, label) ->
            CreateAgentDropdownItem(id = id, label = effortLabels[id] ?: label)
        }

private fun List<CreateAgentDropdownItem>.toBrutalSelectOptions(): List<SlockBrutalSelectOption> =
    map { item -> SlockBrutalSelectOption(id = item.id, label = item.label, enabled = item.enabled) }

@Composable
internal fun SharedCreateAgentDialog(
    submitting: Boolean,
    errorMessage: String?,
    initialName: String = "",
    initialDescription: String = "",
    initialRuntime: String = "",
    initialModel: String? = null,
    initialProviderMode: RuntimeProviderMode = RUNTIME_PROVIDER_MODE_DEFAULT,
    initialProviderApiUrl: String = "",
    initialProviderApiKey: String = "",
    initialFastMode: Boolean = false,
    initialReasoningEffort: String? = null,
    initialCommand: String = "",
    initialAdvancedOpen: Boolean = false,
    draftHint: String? = null,
    suggestedComputer: String? = null,
    requiredComputer: String? = null,
    machines: List<SharedCreateAgentMachineOption> = emptyList(),
    machinesLoading: Boolean = false,
    machinesErrorMessage: String? = null,
    runtimeModelsByMachineAndRuntime: Map<Pair<String, String>, SharedCreateAgentRuntimeModelCatalog> = emptyMap(),
    runtimeModelLoadsInFlight: Set<Pair<String, String>> = emptySet(),
    onRuntimeModelsRequest: (machineId: String, runtime: String, force: Boolean) -> Unit = { _, _, _ -> },
    currentAgentCount: Int = 0,
    maxAgents: Int = -1,
    external: Boolean = false,
    onDismiss: () -> Unit,
    onSubmit: (SharedCreateAgentDialogSubmit) -> Unit
) {
    val focusManager = LocalFocusManager.current
    var name by remember(initialName) { mutableStateOf(initialName) }
    var description by remember(initialDescription) { mutableStateOf(initialDescription) }
    var runtime by remember(initialRuntime) { mutableStateOf(initialRuntime.ifBlank { RUNTIMES.first().id }) }
    var model by remember(runtime, initialModel) { mutableStateOf(initialModel ?: getDefaultModel(runtime)) }
    var customModelMode by remember { mutableStateOf(false) }
    var customModelName by remember { mutableStateOf("") }
    var providerMode by remember(initialProviderMode) { mutableStateOf(initialProviderMode) }
    var providerApiUrl by remember(initialProviderApiUrl) { mutableStateOf(initialProviderApiUrl) }
    var providerApiKey by remember(initialProviderApiKey) { mutableStateOf(initialProviderApiKey) }
    var fastMode by remember(initialFastMode) { mutableStateOf(initialFastMode) }
    var reasoningEffort by remember(initialReasoningEffort) { mutableStateOf(initialReasoningEffort) }
    var command by remember(initialCommand) { mutableStateOf(initialCommand) }
    var envVarEntries by remember {
        mutableStateOf(listOf<Pair<String, String>>())
    }
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
    var machineRuntimeInitialized by remember(initialRuntime) { mutableStateOf(initialRuntime.isNotBlank()) }
    val supportedRuntimeIds = remember { RUNTIMES.filter { it.supported }.map { it.id }.toSet() }
    val runtimeModelKey = selectedMachineId?.let { it to runtime }
    val runtimeModelsCatalog = runtimeModelKey?.let { runtimeModelsByMachineAndRuntime[it] }
    val runtimeModelsOverride = runtimeModelsCatalog?.models.orEmpty()
    val runtimeModelsLoading = runtimeModelKey?.let { it in runtimeModelLoadsInFlight } ?: false

    fun resetRuntimeForMachine(machineId: String) {
        val machine = machines.firstOrNull { it.id == machineId }
        val newRuntime = machine?.runtimes?.firstOrNull { it in supportedRuntimeIds }
            ?: machine?.runtimes?.firstOrNull()
            ?: runtime
        if (newRuntime != runtime) {
            runtime = newRuntime
        }
        if (!customModelMode) {
            model = getDefaultModel(newRuntime)
        }
        customModelMode = false
        providerMode = RUNTIME_PROVIDER_MODE_DEFAULT
        providerApiUrl = ""
        providerApiKey = ""
        fastMode = false
        command = ""
        reasoningEffort = null
    }

    LaunchedEffect(machines, requiredComputer, suggestedComputer, selectedMachineId) {
        if (external) return@LaunchedEffect
        val nextMachineId = selectedMachineId
            ?: createAgentInitialMachineSelection(machines, requiredComputer, suggestedComputer)
            ?: return@LaunchedEffect
        if (selectedMachineId == null) {
            selectedMachineId = nextMachineId
        }
        if (!machineRuntimeInitialized) {
            resetRuntimeForMachine(nextMachineId)
            machineRuntimeInitialized = true
        }
    }

    LaunchedEffect(selectedMachineId, runtime, external) {
        val machineId = selectedMachineId ?: return@LaunchedEffect
        if (!external && runtime.isNotBlank()) {
            onRuntimeModelsRequest(machineId, runtime, false)
        }
    }

    // Reset model when runtime changes or machine-specific model data arrives if current model is not available.
    LaunchedEffect(runtime, runtimeModelsOverride) {
        if (!customModelMode) {
            val currentModels = createAgentModelPresetIds(runtime, runtimeModelsOverride)
            if (currentModels.isNotEmpty() && model !in currentModels) {
                model =
                    createAgentPreferredModelId(
                        runtime = runtime,
                        runtimeModelsOverride = runtimeModelsOverride,
                        runtimeModelsDefault = runtimeModelsCatalog?.defaultModel
                    )
            }
        }
    }

    val trimmedName = name.trim()
    val inlineNameError = if (trimmedName.isNotEmpty()) validateAgentName(trimmedName) else null
    val localizedInlineNameError = inlineNameError?.let { localizedCreateAgentNameError(trimmedName, it) }
    val descriptionTooLong = description.length > DEFAULT_CREATE_AGENT_DESCRIPTION_MAX_LENGTH
    val atLimit = !external && maxAgents >= 0 && currentAgentCount >= maxAgents
    val customModelMissing = !external && customModelMode && customModelName.trim().isBlank()
    val selectedMachine = machines.firstOrNull { it.id == selectedMachineId }
    val selectedMachineRuntimeIds =
        normalizeCreateAgentRuntimeIds(selectedMachine?.runtimes.orEmpty())
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
        !customModelMissing &&
        !atLimit &&
        !submitting &&
        !selectedMachineBlocked &&
        runtimeAvailabilityError == null
    val showMachinePicker = !external

    LaunchedEffect(selectedMachineId, selectedMachineRuntimeIds, runtime) {
        val machineId = selectedMachineId ?: return@LaunchedEffect
        if (!external && selectedMachineRuntimeIds.isNotEmpty() && runtime !in selectedMachineRuntimeIds) {
            resetRuntimeForMachine(machineId)
        }
    }

    SlockUnifiedDialog(
        title =
            if (external) {
                stringResource(MR.strings.agent_create_title_external)
            } else {
                stringResource(MR.strings.agent_create_title)
            },
        dismissEnabled = !submitting,
        onDismiss = onDismiss,
        hostRoute = SharedCreateAgentDialogHostRoute
    ) {
        // #55 visual parity fix: the dialog body used to be capped at
        // `CREATE_AGENT_DIALOG_BODY_MAX_HEIGHT = 430.dp` with Cancel/Create
        // floating inside a `LazyColumn` mid-scroll, so a 390dp-tall viewport
        // could only show COMPUTER → DESCRIPTION → RUNTIME → partial MODEL
        // before cutting off. React's `card-brutal p-6` lets the dialog fill
        // the viewport and pins the action buttons at the bottom; we mirror
        // that here by:
        //   1. Removing the heightIn(max) cap so the LazyColumn can grow
        //      to whatever space is available.
        //   2. Pinning the Column to `fillMaxHeight()` so the inner
        //      `LazyColumn(weight(1f))` consumes the leftover vertical space
        //      between the title chrome and the bottom action row.
        //   3. Keeping Cancel/Create as a fixed Row at the bottom of the
        //      Column so they always sit under the scrollable form area.
        // `CREATE_AGENT_DIALOG_BODY_MAX_HEIGHT` is left in place as a
        // reference value (kept for any future caller that wants the legacy
        // cap); the dialog itself no longer reads it.
        Column(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                draftHint?.takeIf { it.isNotBlank() }?.let { hint ->
                    item(key = "draft-hint") {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .border(SlockStroke.Thin, SlockColor.BorderBlack.copy(alpha = 0.2f))
                                    .padding(SlockSpacing.Xs)
                        ) {
                            Text(
                                text = hint,
                                style = SlockType.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp, color = SlockColor.TextMutedGray),
                                color = SlockColor.TextMutedGray
                            )
                        }
                    }
                }
                if (atLimit) {
                    item(key = "plan-limit") { PlanLimitBanner(currentAgentCount = currentAgentCount, maxAgents = maxAgents) }
                }
                errorMessage?.takeIf { !atLimit && it.isNotBlank() }?.let { error ->
                    item(key = "submit-error") {
                        SlockRaftUiBanner(
                            status = SlockRaftUiBannerStatus.Warning,
                            description = error,
                            size = SlockRaftUiBannerSize.Medium
                        )
                    }
                }
                if (showMachinePicker) {
                    item(key = "machine-picker") {
                        MachinePicker(
                            machines = machines,
                            machinesLoading = machinesLoading,
                            machinesErrorMessage = machinesErrorMessage,
                            selectedMachineId = selectedMachineId,
                            requiredComputer = requiredComputer,
                            suggestedComputer = suggestedComputer,
                            enabled = !submitting,
                            onSelect = { id ->
                                if (requiredComputer.isNullOrBlank() || id == requiredComputer) {
                                    focusManager.clearFocus(force = true)
                                    selectedMachineId = id
                                    resetRuntimeForMachine(id)
                                }
                            }
                        )
                    }
                }
                item(key = "name") {
                    SlockBrutalTextField(
                        label = stringResource(MR.strings.agent_create_label_name),
                        required = true,
                        value = name,
                        onValueChange = { name = it },
                        placeholder = stringResource(MR.strings.agent_create_placeholder_name),
                        enabled = !submitting,
                        autoFocus = true
                    )
                }
                if (inlineNameError != null) {
                    item(key = "name-error") {
                        Text(
                            text = localizedInlineNameError ?: inlineNameError,
                            style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(color = SlockColor.AccentRed),
                            color = SlockColor.AccentRed
                        )
                    }
                }
                item(key = "description") {
                    SlockBrutalTextField(
                        label = stringResource(MR.strings.agent_create_label_description),
                        optional = true,
                        value = description,
                        onValueChange = {
                            if (it.length <= DEFAULT_CREATE_AGENT_DESCRIPTION_MAX_LENGTH) {
                                description = it
                            }
                        },
                        placeholder = stringResource(MR.strings.agent_create_placeholder_description),
                        enabled = !submitting,
                        maxLines = 3,
                        fieldMinHeight = 82.dp
                    )
                }
                item(key = "description-count") {
                    if (descriptionTooLong) {
                        Text(
                            text =
                                MR.strings.agent_create_error_description_length
                                    .format(listOf(DEFAULT_CREATE_AGENT_DESCRIPTION_MAX_LENGTH))
                                    .localized(),
                            style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(color = SlockColor.AccentRed),
                            color = SlockColor.AccentRed
                        )
                    } else {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Spacer(Modifier.weight(1f))
                            Text(
                                text = "${description.length}/$DEFAULT_CREATE_AGENT_DESCRIPTION_MAX_LENGTH",
                                style = SlockType.Caption,
                                color = SlockColor.TextMutedGray
                            )
                        }
                    }
                }
                if (!external) {
                    item(key = "runtime") {
                        AdvancedRuntimeSection(
                            runtime = runtime,
                            onRuntimeChange = { newRuntime ->
                                runtime = newRuntime
                                if (!customModelMode) {
                                    model = getDefaultModel(newRuntime)
                                }
                            },
                            model = model,
                            onModelChange = { selectedModel ->
                                model = selectedModel
                                customModelMode = false
                            },
                            customModelMode = customModelMode,
                            onCustomModelModeChange = { enabled ->
                                customModelMode = enabled
                            },
                            customModelName = customModelName,
                            onCustomModelNameChange = { customModelName = it },
                            runtimeModelsOverride = runtimeModelsOverride,
                            runtimeModelsLoading = runtimeModelsLoading,
                            onRuntimeModelsRefresh = {
                                selectedMachineId?.let { machineId ->
                                    onRuntimeModelsRequest(machineId, runtime, true)
                                }
                            },
                            machineRuntimeIds = selectedMachineRuntimeIds,
                            runtimeAvailabilityError = runtimeAvailabilityError,
                            enabled = !submitting,
                            providerMode = providerMode,
                            onProviderModeChange = { providerMode = it },
                            providerApiUrl = providerApiUrl,
                            onProviderApiUrlChange = { providerApiUrl = it },
                            providerApiKey = providerApiKey,
                            onProviderApiKeyChange = { providerApiKey = it },
                            fastMode = fastMode,
                            onFastModeChange = { fastMode = it },
                            reasoningEffort = reasoningEffort,
                            onReasoningEffortChange = { reasoningEffort = it },
                            envVarEntries = envVarEntries,
                            onEnvVarEntriesChange = { envVarEntries = it },
                            command = command,
                            onCommandChange = { command = it },
                            advancedOpen = advancedOpen,
                            onAdvancedOpenChange = { advancedOpen = it }
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(Modifier.weight(1f))
                SlockBrutalActionButton(
                    label = stringResource(MR.strings.common_cancel),
                    enabled = !submitting,
                    backgroundColor = SlockColor.SurfaceWhite,
                    onClick = onDismiss
                )
                SlockBrutalActionButton(
                    label =
                        if (submitting) {
                            stringResource(MR.strings.agent_create_action_creating)
                        } else {
                            stringResource(MR.strings.agent_create_action_create)
                        },
                    enabled = canSubmit,
                    minWidth = 126.dp,
                    backgroundColor = SlockColor.AccentPink,
                    onClick = {
                        onSubmit(
                            buildSharedCreateAgentDialogSubmit(
                                name = trimmedName,
                                description = description,
                                selectedMachineId = selectedMachineId,
                                requiredComputer = requiredComputer,
                                external = external,
                                runtime = runtime,
                                model = model,
                                customModelMode = customModelMode,
                                customModelName = customModelName,
                                providerMode = providerMode,
                                providerApiUrl = providerApiUrl,
                                providerApiKey = providerApiKey,
                                fastMode = fastMode,
                                reasoningEffort = reasoningEffort,
                                envVarEntries = envVarEntries,
                                command = command
                            )
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun localizedCreateAgentNameError(name: String, fallback: String): String =
    when {
        " is reserved." in fallback ->
            MR.strings.agent_create_error_name_reserved
                .format(listOf(name.removePrefix("@").lowercase()))
                .localized()
        "at least" in fallback -> stringResource(MR.strings.agent_create_error_name_min)
        "at most" in fallback -> stringResource(MR.strings.agent_create_error_name_max)
        "must start with a letter" in fallback -> stringResource(MR.strings.agent_create_error_name_characters)
        else -> fallback
    }

// Suppress lint helper: previously used to clear errorMessage local var on input change.
// Kept for call-site compatibility with the prior signature; no-op now that the field
// is managed by the parent caller.
@Suppress("UNUSED_PARAMETER")
private fun onDismissOnce() {
    // intentionally empty
}

@Composable
private fun PlanLimitBanner(currentAgentCount: Int, maxAgents: Int) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .border(SlockStroke.Thin, SlockColor.AccentOrange)
            .background(SlockColor.SurfaceWhite)
            .padding(SlockSpacing.Sm)
    ) {
        Text(
            text =
                MR.strings.agent_create_error_limit
                    .format(listOf(currentAgentCount, maxAgents))
                    .localized(),
            style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(color = SlockColor.TextBlack),
            color = SlockColor.TextBlack
        )
    }
}

@Composable
private fun MachinePicker(
    machines: List<SharedCreateAgentMachineOption>,
    machinesLoading: Boolean,
    machinesErrorMessage: String?,
    selectedMachineId: String?,
    requiredComputer: String?,
    suggestedComputer: String?,
    enabled: Boolean,
    onSelect: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SlockBrutalSectionLabel(label = stringResource(MR.strings.agent_create_label_computer), required = true)
        when {
            machinesLoading -> Text(
                text = stringResource(MR.strings.agent_create_state_computers_loading),
                style = SlockType.Caption,
                color = SlockColor.TextMutedGray
            )
            !machinesErrorMessage.isNullOrBlank() -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(SlockStroke.Thin, SlockColor.AccentRed.copy(alpha = 0.5f))
                    .background(SlockColor.SurfaceCream)
                    .padding(SlockSpacing.Sm)
            ) {
                Text(
                    text = machinesErrorMessage,
                    style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold),
                    color = SlockColor.AccentRed
                )
            }
            machines.isEmpty() -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(SlockStroke.Thin, SlockColor.BorderBlack.copy(alpha = 0.3f))
                    .background(SlockColor.SurfaceCream)
                    .padding(SlockSpacing.Sm)
            ) {
                Text(
                    text = stringResource(MR.strings.agent_create_state_computers_empty),
                    style = SlockType.Caption,
                    color = SlockColor.TextMutedGray
                )
            }
            else -> {
                val machineItems =
                    createAgentMachineDropdownItems(
                        machines = machines,
                        requiredComputer = requiredComputer,
                        offlineSuffix = stringResource(MR.strings.agent_create_suffix_offline)
                    )
                SlockBrutalSelectField(
                    options = machineItems.toBrutalSelectOptions(),
                    selectedId = selectedMachineId.orEmpty(),
                    selectedLabel =
                        machineItems.firstOrNull { it.id == selectedMachineId }?.label
                            ?: stringResource(MR.strings.agent_create_placeholder_select_computer),
                    placeholder = stringResource(MR.strings.agent_create_placeholder_select_computer),
                    enabled = enabled,
                    rowHeight = 40.dp,
                    onSelect = { item -> onSelect(item.id) }
                )
            }
        }
        if (!requiredComputer.isNullOrBlank()) {
            Text(
                text = MR.strings.agent_create_hint_required_computer.format(listOf(requiredComputer)).localized(),
                style = SlockType.Caption,
                color = SlockColor.TextMutedGray
            )
        } else if (!suggestedComputer.isNullOrBlank()) {
            Text(
                text = MR.strings.agent_create_hint_suggested_computer.format(listOf(suggestedComputer)).localized(),
                style = SlockType.Caption,
                color = SlockColor.TextMutedGray
            )
        }
    }
}

@Composable
internal fun AdvancedRuntimeSection(
    runtime: String,
    onRuntimeChange: (String) -> Unit,
    model: String,
    onModelChange: (String) -> Unit,
    customModelMode: Boolean,
    onCustomModelModeChange: (Boolean) -> Unit,
    customModelName: String,
    onCustomModelNameChange: (String) -> Unit,
    runtimeModelsOverride: List<SharedCreateAgentRuntimeModelOption>,
    runtimeModelsLoading: Boolean = false,
    onRuntimeModelsRefresh: () -> Unit = {},
    retainUnavailableSelectedPreset: Boolean = false,
    machineRuntimeIds: List<String> = emptyList(),
    runtimeAvailabilityError: String? = null,
    enabled: Boolean,
    providerMode: RuntimeProviderMode,
    onProviderModeChange: (RuntimeProviderMode) -> Unit,
    providerApiUrl: String,
    onProviderApiUrlChange: (String) -> Unit,
    providerApiKey: String,
    onProviderApiKeyChange: (String) -> Unit,
    fastMode: Boolean,
    onFastModeChange: (Boolean) -> Unit,
    reasoningEffort: String?,
    onReasoningEffortChange: (String?) -> Unit,
    envVarEntries: List<Pair<String, String>>,
    onEnvVarEntriesChange: (List<Pair<String, String>>) -> Unit,
    command: String,
    onCommandChange: (String) -> Unit,
    advancedOpen: Boolean,
    onAdvancedOpenChange: (Boolean) -> Unit
) {
    val supportedRuntimes = RUNTIMES.filter { it.supported }
    val runtimeInfo = supportedRuntimes.firstOrNull { it.id == runtime } ?: supportedRuntimes.first()
    val customModelSupported = supportsRuntimeCustomModelName(runtime)
    val modelOptions =
        createAgentModelDropdownItemsForSelection(
            runtime = runtime,
            runtimeModelsOverride = runtimeModelsOverride,
            allowCustomModel = customModelSupported,
            selectedModel = model,
            customModelMode = customModelMode,
            retainUnavailableSelectedPreset = retainUnavailableSelectedPreset,
            customLabel = stringResource(MR.strings.agent_create_label_custom),
            unavailableSuffix = " " + stringResource(MR.strings.agent_create_suffix_model_unavailable)
        )
    val runtimeOptions =
        createAgentRuntimeDropdownItems(
            machineRuntimeIds = machineRuntimeIds,
            allowStaticFallback = runtimeAvailabilityError == null,
            comingSoonSuffix = stringResource(MR.strings.agent_create_suffix_coming_soon),
            notInstalledSuffix = stringResource(MR.strings.agent_create_suffix_not_installed)
        )
    val runtimeEnabled = enabled && runtimeAvailabilityError == null && runtimeOptions.isNotEmpty()
    val providerUrlRequired = supportsRuntimeApiUrl(runtime) && providerMode == RUNTIME_PROVIDER_MODE_CUSTOM
    val providerUrlInvalid = providerUrlRequired &&
        !Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(providerApiUrl.trim())
    val providerKeyInvalid = providerUrlRequired && providerApiKey.isBlank()
    val focusManager = LocalFocusManager.current

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SlockBrutalSectionLabel(label = stringResource(MR.strings.agent_create_label_runtime))
        SlockBrutalSelectField(
            options = runtimeOptions.toBrutalSelectOptions(),
            selectedId = runtime,
            selectedLabel =
                runtimeOptions.firstOrNull { it.id == runtime }?.label
                    ?: runtime.takeIf { runtimeAvailabilityError == null }?.let { getRuntimeDisplayName(it) }
                    ?: stringResource(MR.strings.agent_create_state_no_runtimes),
            placeholder = stringResource(MR.strings.agent_create_placeholder_select_runtime),
            enabled = runtimeEnabled,
            rowHeight = 36.dp,
            onSelect = { option ->
                focusManager.clearFocus(force = true)
                onRuntimeChange(option.id)
            }
        )
        runtimeAvailabilityError?.let { error ->
            Text(
                text =
                    if (error == CREATE_AGENT_NO_MACHINE_RUNTIMES_ERROR) {
                        stringResource(MR.strings.agent_create_error_no_machine_runtimes)
                    } else {
                        error
                    },
                style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(color = SlockColor.AccentRed),
                color = SlockColor.AccentRed
            )
        }
        if (runtimeAvailabilityError == null) {
        if (supportsRuntimeApiUrl(runtime)) {
            SlockBrutalSectionLabel(label = stringResource(MR.strings.agent_create_label_provider), required = false)
            val providerOptions =
                createAgentProviderDropdownItems(
                    defaultLabel = stringResource(MR.strings.agent_create_action_default),
                    customLabel = stringResource(MR.strings.agent_create_label_custom)
                )
            SlockBrutalSelectField(
                options = providerOptions.toBrutalSelectOptions(),
                selectedId = providerMode,
                selectedLabel =
                    providerOptions.firstOrNull { it.id == providerMode }?.label
                        ?: stringResource(MR.strings.agent_create_action_default),
                placeholder = stringResource(MR.strings.agent_create_placeholder_select_provider),
                enabled = enabled,
                rowHeight = 36.dp,
                onSelect = { option ->
                    focusManager.clearFocus(force = true)
                    onProviderModeChange(option.id)
                    if (option.id != RUNTIME_PROVIDER_MODE_CUSTOM) {
                        onProviderApiUrlChange("")
                        onProviderApiKeyChange("")
                    }
                }
            )
            Text(
                text = stringResource(MR.strings.agent_create_hint_provider),
                style = SlockType.Caption,
                color = SlockColor.TextMutedGray
            )
            if (providerMode == RUNTIME_PROVIDER_MODE_CUSTOM) {
                SlockBrutalTextField(
                    label = stringResource(MR.strings.agent_create_label_api_url),
                    required = true,
                    value = providerApiUrl,
                    onValueChange = { onProviderApiUrlChange(it) },
                    placeholder = "https://gateway.example.com",
                    enabled = enabled
                )
                if (providerApiUrl.isNotBlank() && providerUrlInvalid) {
                    Text(
                        text = stringResource(MR.strings.agent_create_error_provider_url),
                        style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(color = SlockColor.AccentRed),
                        color = SlockColor.AccentRed
                    )
                }
                SlockBrutalTextField(
                    label = stringResource(MR.strings.agent_create_label_api_key),
                    required = true,
                    value = providerApiKey,
                    // Task #43 QA-4 fix (artin's instruction: same component
                    // shape as the login page password field): use a
                    // native password-typed text field. Keep the underlying
                    // value intact; do not mirror masked bullets into state.
                    password = true,
                    onValueChange = { onProviderApiKeyChange(it) },
                    placeholder = "sk-ant-...",
                    enabled = enabled
                )
                if (providerKeyInvalid) {
                    Text(
                        text = stringResource(MR.strings.agent_create_error_provider_key),
                        style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(color = SlockColor.AccentRed),
                        color = SlockColor.AccentRed
                    )
                }
            }
        }
        CreateAgentSectionLabelWithAction(
            label = stringResource(MR.strings.agent_create_label_model),
            required = false,
            actionIcon = SlockLucideIconName.RefreshCw,
            actionEnabled = enabled && !runtimeModelsLoading,
            loading = runtimeModelsLoading,
            onAction = onRuntimeModelsRefresh
        )
        val modelSelectItems = modelOptions.map { CreateAgentDropdownItem(id = it.id, label = it.label, custom = it.custom) }
        SlockBrutalSelectField(
            options = modelSelectItems.toBrutalSelectOptions(),
            selectedId = if (customModelMode) CREATE_AGENT_CUSTOM_MODEL_ID else model,
            selectedLabel =
                createAgentModelSelectLabel(
                    customModelMode = customModelMode,
                    model = model,
                    modelOptions = modelOptions,
                    customLabel = stringResource(MR.strings.agent_create_label_custom),
                    selectModelLabel = stringResource(MR.strings.agent_create_placeholder_select_model)
                ),
            placeholder = stringResource(MR.strings.agent_create_placeholder_select_model),
            enabled = enabled,
            rowHeight = 36.dp,
            onSelect = { item ->
                if (item.id == CREATE_AGENT_CUSTOM_MODEL_ID) {
                    focusManager.clearFocus(force = true)
                    onCustomModelModeChange(true)
                } else {
                    if (customModelMode) {
                        focusManager.clearFocus(force = true)
                    }
                    onModelChange(item.id)
                    onCustomModelModeChange(false)
                }
            }
        )
        key(customModelMode) {
            if (customModelMode) {
                SlockBrutalTextField(
                    label = stringResource(MR.strings.agent_create_label_custom_model),
                    required = true,
                    value = customModelName,
                    onValueChange = { onCustomModelNameChange(it) },
                    placeholder = stringResource(MR.strings.agent_create_placeholder_model),
                    enabled = enabled
                )
                if (customModelName.isBlank()) {
                    Text(
                        text = stringResource(MR.strings.agent_create_error_custom_model),
                        style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(color = SlockColor.AccentRed),
                        color = SlockColor.AccentRed
                    )
                }
            }
        }
        if (supportsRuntimeReasoningEffort(runtime)) {
            SlockBrutalSectionLabel(label = stringResource(MR.strings.agent_create_label_reasoning))
            val reasoningOptions =
                createAgentReasoningDropdownItems(
                    defaultLabel = stringResource(MR.strings.agent_create_action_default),
                    effortLabels =
                        mapOf(
                            "low" to stringResource(MR.strings.agent_create_reasoning_low),
                            "medium" to stringResource(MR.strings.agent_create_reasoning_medium),
                            "high" to stringResource(MR.strings.agent_create_reasoning_high),
                            "xhigh" to stringResource(MR.strings.agent_create_reasoning_extra_high)
                        )
                )
            val reasoningSelectedId = reasoningEffort ?: CREATE_AGENT_DEFAULT_REASONING_ID
            SlockBrutalSelectField(
                options = reasoningOptions.toBrutalSelectOptions(),
                selectedId = reasoningSelectedId,
                selectedLabel =
                    reasoningOptions.firstOrNull { it.id == reasoningSelectedId }?.label
                        ?: stringResource(MR.strings.agent_create_action_default),
                placeholder = stringResource(MR.strings.agent_create_action_default),
                enabled = enabled,
                rowHeight = 36.dp,
                onSelect = { option ->
                    focusManager.clearFocus(force = true)
                    onReasoningEffortChange(
                        if (option.id == CREATE_AGENT_DEFAULT_REASONING_ID) null else option.id
                    )
                }
            )
        }
        if (supportsRuntimeFastMode(runtime)) {
            SlockBrutalSectionLabel(label = stringResource(MR.strings.agent_create_label_mode))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SlockCheckbox(
                    checked = fastMode,
                    onCheckedChange = { onFastModeChange(it) },
                    enabled = enabled
                )
                Text(
                    text = stringResource(MR.strings.agent_create_label_fast_mode),
                    style = SlockType.Caption,
                    color = SlockColor.TextBlack
                )
            }
        }
        AdvancedToggleButton(
            open = advancedOpen,
            onToggle = {
                focusManager.clearFocus(force = true)
                onAdvancedOpenChange(!advancedOpen)
            }
        )
        if (advancedOpen) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(SlockStroke.Thin, SlockColor.BorderBlack.copy(alpha = 0.2f))
                    .padding(SlockSpacing.Sm),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                EnvVarsEditor(
                    entries = envVarEntries,
                    onChange = onEnvVarEntriesChange,
                    enabled = enabled
                )
                if (supportsRuntimeCommand(runtime)) {
                    SlockBrutalTextField(
                        label = stringResource(MR.strings.agent_create_label_command),
                        optional = true,
                        value = command,
                        onValueChange = { onCommandChange(it) },
                        placeholder = "/usr/local/bin/${runtimeInfo.binary}",
                        enabled = enabled
                    )
                    Text(
                        text = stringResource(MR.strings.agent_create_hint_command),
                        style = SlockType.Caption,
                        color = SlockColor.TextMutedGray
                    )
                }
            }
        }
        }
    }
}

@Composable
private fun AdvancedToggleButton(open: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        SlockLucideIcon(
            if (open) SlockLucideIconName.ChevronDown else SlockLucideIconName.ChevronRight,
            modifier = Modifier.size(16.dp),
            color = SlockColor.TextMutedGrayArgb
        )
        Text(
            text = stringResource(MR.strings.agent_create_label_advanced),
            style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold),
            color = SlockColor.TextMutedGray
        )
    }
}

@Composable
private fun CreateAgentSectionLabelWithAction(
    label: String,
    required: Boolean = false,
    actionIcon: SlockLucideIconName,
    actionEnabled: Boolean,
    loading: Boolean = false,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SlockBrutalSectionLabel(label = label, required = required, modifier = Modifier.weight(1f))
        Box(
            modifier =
                Modifier
                    .size(22.dp)
                    .clickable(enabled = actionEnabled) { onAction() },
            contentAlignment = Alignment.Center
        ) {
            if (loading) {
                SlockCreateAgentSpinnerIcon()
            } else {
                SlockLucideIcon(
                    actionIcon,
                    modifier = Modifier.size(13.dp),
                    color = if (actionEnabled) SlockColor.TextMutedGrayArgb else "#33000000"
                )
            }
        }
    }
}

@Composable
private fun SlockCreateAgentSpinnerIcon() {
    SlockLucideIcon(
        SlockLucideIconName.LoaderCircle,
        modifier = Modifier.size(13.dp),
        color = SlockColor.TextMutedGrayArgb,
        spinning = true
    )
}

@Composable
private fun EnvVarsEditor(
    entries: List<Pair<String, String>>,
    onChange: (List<Pair<String, String>>) -> Unit,
    enabled: Boolean
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SlockBrutalSectionLabel(label = stringResource(MR.strings.agent_create_label_environment_variables), optional = true)
        entries.forEachIndexed { idx, (key, value) ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SlockBrutalTextField(
                    label = stringResource(MR.strings.agent_create_label_key),
                    value = key,
                    onValueChange = { newKey ->
                        onChange(entries.toMutableList().also { it[idx] = newKey to value })
                    },
                    placeholder = "FOO",
                    enabled = enabled,
                    fieldMinHeight = 32.dp,
                    modifier = Modifier.weight(1f)
                )
                SlockBrutalTextField(
                    label = stringResource(MR.strings.agent_create_label_value),
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
                    SlockLucideIcon(
                        name = SlockLucideIconName.X,
                        color = if (enabled) SlockColor.AccentRedArgb else SlockColor.TextMutedGrayArgb,
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
                text = stringResource(MR.strings.agent_create_action_add_env_var),
                style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold),
                color = if (enabled) SlockColor.TextBlack else SlockColor.TextMutedGray
            )
        }
    }
}
