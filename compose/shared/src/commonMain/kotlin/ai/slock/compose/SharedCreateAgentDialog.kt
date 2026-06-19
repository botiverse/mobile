package ai.slock.compose

import ai.slock.compose.components.SlockBrutalActionButton
import ai.slock.compose.components.SlockBrutalSelectField
import ai.slock.compose.components.SlockBrutalSelectOption
import ai.slock.compose.components.SlockBrutalSectionLabel
import ai.slock.compose.components.SlockBrutalTextField
import ai.slock.compose.model.RuntimeConfig
import ai.slock.compose.model.RuntimeProviderMode
import ai.slock.compose.model.RUNTIME_MODELS
import ai.slock.compose.model.RUNTIME_PROVIDER_MODE_CUSTOM
import ai.slock.compose.model.RUNTIME_PROVIDER_MODE_DEFAULT
import ai.slock.compose.model.RUNTIMES
import ai.slock.compose.model.REASONING_EFFORTS
import ai.slock.compose.model.buildRuntimeConfig
import ai.slock.compose.model.getDefaultModel
import ai.slock.compose.model.getRuntimeDisplayName
import ai.slock.compose.model.RuntimeModelInfo
import ai.slock.compose.model.supportsRuntimeApiUrl
import ai.slock.compose.model.supportsRuntimeCommand
import ai.slock.compose.model.supportsRuntimeFastMode
import ai.slock.compose.model.supportsRuntimeReasoningEffort
import ai.slock.compose.model.validateAgentName
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.kuikly.compose.setContent
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.pager.Pager

private const val CREATE_AGENT_DIALOG_VISUAL_PAGE = "compose_create_agent_dialog_visual"

@Page(CREATE_AGENT_DIALOG_VISUAL_PAGE, supportInLocal = true)
internal class ComposeCreateAgentDialogVisualPage : ai.slock.compose.base.BasePager() {
    override fun willInit() {
        super.willInit()
        setContent { CreateAgentDialogVisualContent() }
    }
}

internal data class SharedCreateAgentDialogSubmit(
    val name: String,
    val description: String?,
    val machineId: String?,
    val runtimeConfig: RuntimeConfig? = null,
    val external: Boolean = false
)

@Composable
private fun CreateAgentDialogVisualContent() {
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
            errorMessage = null,
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
                    ("computer-mbp" to runtime) to runtimeModels
                ),
            currentAgentCount = 4,
            maxAgents = 12,
            onDismiss = {},
            onSubmit = {}
        )
    }
}

/**
 * Convert a dialog submit to the data-layer [ai.slock.compose.data.CreateAgentRequest].
 * Keeps the data layer free of UI types — the dialog knows about runtime
 * config, the coordinator knows about the body shape; the mapping lives
 * here at the boundary.
 */
internal fun SharedCreateAgentDialogSubmit.toCreateAgentRequest(): ai.slock.compose.data.CreateAgentRequest =
    ai.slock.compose.data.CreateAgentRequest(
        name = name,
        description = description,
        machineId = machineId,
        runtimeConfig = runtimeConfig,
        external = external
    )

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

internal fun createAgentRuntimeDropdownItems(): List<CreateAgentDropdownItem> =
    RUNTIMES.filter { it.supported }.map { runtime ->
        CreateAgentDropdownItem(id = runtime.id, label = runtime.displayName)
    }

internal fun createAgentMachineDropdownItems(
    machines: List<SharedCreateAgentMachineOption>,
    requiredComputer: String?
): List<CreateAgentDropdownItem> =
    machines.map { machine ->
        val isOffline = machine.status != "online"
        val blockedByRequired = !requiredComputer.isNullOrBlank() && machine.id != requiredComputer
        CreateAgentDropdownItem(
            id = machine.id,
            label = machine.name + if (isOffline) " (offline)" else "",
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
    runtimeModelsOverride: List<SharedCreateAgentRuntimeModelOption> = emptyList()
): List<CreateAgentModelDropdownItem> {
    val modelOptions =
        runtimeModelsOverride
            .takeIf { it.isNotEmpty() }
            ?.map { RuntimeModelInfo(it.id, it.label, it.verified) }
            ?: RUNTIME_MODELS[runtime].orEmpty()
    return modelOptions.map { CreateAgentModelDropdownItem(id = it.id, label = it.label) } +
        CreateAgentModelDropdownItem(id = CREATE_AGENT_CUSTOM_MODEL_ID, label = "Custom...", custom = true)
}

internal fun createAgentModelSelectionForValue(
    runtime: String,
    model: String,
    runtimeModelsOverride: List<SharedCreateAgentRuntimeModelOption> = emptyList()
): Pair<Boolean, String> {
    val trimmed = model.trim()
    val presetIds = createAgentModelDropdownItems(runtime, runtimeModelsOverride)
        .filterNot { it.custom }
        .map { it.id }
    return if (trimmed.isNotBlank() && trimmed !in presetIds) {
        true to trimmed
    } else {
        false to trimmed.ifBlank { getDefaultModel(runtime) }
    }
}

internal fun createAgentProviderDropdownItems(): List<CreateAgentDropdownItem> =
    listOf(
        CreateAgentDropdownItem(id = RUNTIME_PROVIDER_MODE_DEFAULT, label = "Default"),
        CreateAgentDropdownItem(id = RUNTIME_PROVIDER_MODE_CUSTOM, label = "Custom")
    )

internal fun createAgentReasoningDropdownItems(): List<CreateAgentDropdownItem> =
    listOf(CreateAgentDropdownItem(id = CREATE_AGENT_DEFAULT_REASONING_ID, label = "Default")) +
        REASONING_EFFORTS.map { (id, label) ->
            CreateAgentDropdownItem(id = id, label = label)
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
    runtimeModelsByMachineAndRuntime: Map<Pair<String, String>, List<SharedCreateAgentRuntimeModelOption>> = emptyMap(),
    currentAgentCount: Int = 0,
    maxAgents: Int = -1,
    external: Boolean = false,
    onDismiss: () -> Unit,
    onSubmit: (SharedCreateAgentDialogSubmit) -> Unit
) {
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

    // Reset model when runtime changes if current model not in new runtime's catalog.
    LaunchedEffect(runtime) {
        if (!customModelMode) {
            val currentModels = RUNTIME_MODELS[runtime].orEmpty().map { it.id }
            if (currentModels.isNotEmpty() && model !in currentModels) {
                model = getDefaultModel(runtime)
            }
        }
    }

    val trimmedName = name.trim()
    val inlineNameError = if (trimmedName.isNotEmpty()) validateAgentName(trimmedName) else null
    val descriptionTooLong = description.length > DEFAULT_CREATE_AGENT_DESCRIPTION_MAX_LENGTH
    val atLimit = !external && maxAgents >= 0 && currentAgentCount >= maxAgents
    val customModelMissing = !external && customModelMode && customModelName.trim().isBlank()
    val selectedMachine = machines.firstOrNull { it.id == selectedMachineId }
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
        !selectedMachineBlocked
    val showMachinePicker = !external

    SlockUnifiedDialog(
        title = if (external) "CREATE EXTERNAL AGENT" else "CREATE AGENT",
        dismissEnabled = !submitting,
        onDismiss = onDismiss
    ) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            draftHint?.takeIf { it.isNotBlank() }?.let { hint ->
                Text(
                    text = hint,
                    style = SlockType.Caption.copy(fontSize = 12.sp, lineHeight = 16.sp, color = SlockColor.TextMutedGray),
                    color = SlockColor.TextMutedGray,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .border(SlockStroke.Thin, SlockColor.BorderBlack.copy(alpha = 0.2f))
                            .padding(SlockSpacing.Xs)
                )
            }
            if (atLimit) {
                PlanLimitBanner(currentAgentCount = currentAgentCount, maxAgents = maxAgents)
            }
            if (showMachinePicker) {
                MachinePicker(
                    machines = machines,
                    machinesLoading = machinesLoading,
                    selectedMachineId = selectedMachineId,
                    requiredComputer = requiredComputer,
                    suggestedComputer = suggestedComputer,
                    enabled = !submitting,
                    onSelect = { id ->
                        if (requiredComputer.isNullOrBlank() || id == requiredComputer) {
                            selectedMachineId = id
                            resetRuntimeForMachine(id)
                        }
                    }
                )
            }
            SlockBrutalTextField(
                label = "Name",
                required = true,
                value = name,
                onValueChange = { name = it },
                placeholder = "e.g. Noel",
                enabled = !submitting,
                autoFocus = true
            )
            if (inlineNameError != null) {
                Text(
                    text = inlineNameError,
                    style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(color = SlockColor.AccentRed),
                    color = SlockColor.AccentRed
                )
            }
            SlockBrutalTextField(
                label = "Description",
                optional = true,
                value = description,
                onValueChange = {
                    if (it.length <= DEFAULT_CREATE_AGENT_DESCRIPTION_MAX_LENGTH) {
                        description = it
                    }
                },
                placeholder = "Leave blank for a general-purpose agent, or describe a role...",
                enabled = !submitting,
                maxLines = 3,
                fieldMinHeight = 82.dp
            )
            if (descriptionTooLong) {
                Text(
                    text = "Description must be at most $DEFAULT_CREATE_AGENT_DESCRIPTION_MAX_LENGTH characters.",
                    style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(color = SlockColor.AccentRed),
                    color = SlockColor.AccentRed
                )
            } else {
                Text(
                    text = "${description.length}/$DEFAULT_CREATE_AGENT_DESCRIPTION_MAX_LENGTH",
                    style = SlockType.Caption,
                    color = SlockColor.TextMutedGray,
                    modifier = Modifier.align(Alignment.End)
                )
            }
            if (!external) {
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
                    runtimeModelsOverride = runtimeModelsByMachineAndRuntime[selectedMachineId to runtime].orEmpty(),
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
            errorMessage?.takeIf { it.isNotBlank() }?.let { error ->
                Text(
                    text = error,
                    style =
                        SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            color = SlockColor.AccentRed
                        ),
                    color = SlockColor.AccentRed
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(Modifier.weight(1f))
                SlockBrutalActionButton(
                    label = "Cancel",
                    enabled = !submitting,
                    backgroundColor = SlockColor.SurfaceWhite,
                    onClick = onDismiss
                )
                SlockBrutalActionButton(
                    label = if (submitting) "Creating..." else "Create Agent",
                    enabled = canSubmit,
                    minWidth = 126.dp,
                    backgroundColor = SlockColor.AccentPink,
                    onClick = {
                        val finalMachineId =
                            if (external) null
                            else (requiredComputer?.takeIf { it.isNotBlank() }
                                ?: selectedMachineId)
                        val finalRuntimeConfig =
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
                                    envVars = envVarEntries
                                        .filter { it.first.isNotBlank() }
                                        .toMap()
                                        .takeIf { it.isNotEmpty() },
                                    command = command.takeIf { supportsRuntimeCommand(runtime) }
                                )
                            }
                        onSubmit(
                            SharedCreateAgentDialogSubmit(
                                name = trimmedName.removePrefix("@"),
                                description = description.trim().takeIf { it.isNotBlank() },
                                machineId = finalMachineId,
                                runtimeConfig = finalRuntimeConfig,
                                external = external
                            )
                        )
                    }
                )
            }
        }
    }
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
            text = "Agent limit reached ($currentAgentCount/$maxAgents on this server). Upgrade for more.",
            style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(color = SlockColor.TextBlack),
            color = SlockColor.TextBlack
        )
    }
}

@Composable
private fun MachinePicker(
    machines: List<SharedCreateAgentMachineOption>,
    machinesLoading: Boolean,
    selectedMachineId: String?,
    requiredComputer: String?,
    suggestedComputer: String?,
    enabled: Boolean,
    onSelect: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SlockBrutalSectionLabel(label = "Computer", required = true)
        when {
            machinesLoading -> Text(
                text = "Loading computers...",
                style = SlockType.Caption,
                color = SlockColor.TextMutedGray
            )
            machines.isEmpty() -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(SlockStroke.Thin, SlockColor.BorderBlack.copy(alpha = 0.3f))
                    .background(SlockColor.SurfaceCream)
                    .padding(SlockSpacing.Sm)
            ) {
                Text(
                    text = "No computers. Connect a computer first.",
                    style = SlockType.Caption,
                    color = SlockColor.TextMutedGray
                )
            }
            else -> {
                val machineItems = createAgentMachineDropdownItems(machines, requiredComputer)
                SlockBrutalSelectField(
                    options = machineItems.toBrutalSelectOptions(),
                    selectedId = selectedMachineId.orEmpty(),
                    selectedLabel =
                        machineItems.firstOrNull { it.id == selectedMachineId }?.label
                            ?: "Select computer",
                    placeholder = "Select computer",
                    enabled = enabled,
                    rowHeight = 40.dp,
                    onSelect = { item -> onSelect(item.id) }
                )
            }
        }
        if (!requiredComputer.isNullOrBlank()) {
            Text(
                text = "Required computer: $requiredComputer",
                style = SlockType.Caption,
                color = SlockColor.TextMutedGray
            )
        } else if (!suggestedComputer.isNullOrBlank()) {
            Text(
                text = "Suggested computer: $suggestedComputer",
                style = SlockType.Caption,
                color = SlockColor.TextMutedGray
            )
        }
    }
}

@Composable
private fun AdvancedRuntimeSection(
    runtime: String,
    onRuntimeChange: (String) -> Unit,
    model: String,
    onModelChange: (String) -> Unit,
    customModelMode: Boolean,
    onCustomModelModeChange: (Boolean) -> Unit,
    customModelName: String,
    onCustomModelNameChange: (String) -> Unit,
    runtimeModelsOverride: List<SharedCreateAgentRuntimeModelOption>,
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
    val modelOptions = createAgentModelDropdownItems(runtime, runtimeModelsOverride)
    val runtimeOptions = createAgentRuntimeDropdownItems()
    val providerUrlRequired = supportsRuntimeApiUrl(runtime) && providerMode == RUNTIME_PROVIDER_MODE_CUSTOM
    val providerUrlInvalid = providerUrlRequired &&
        !Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(providerApiUrl.trim())
    val providerKeyInvalid = providerUrlRequired && providerApiKey.isBlank()

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SlockBrutalSectionLabel(label = "Runtime")
        SlockBrutalSelectField(
            options = runtimeOptions.toBrutalSelectOptions(),
            selectedId = runtime,
            selectedLabel = runtimeOptions.firstOrNull { it.id == runtime }?.label ?: getRuntimeDisplayName(runtime),
            placeholder = "Select runtime",
            enabled = enabled,
            rowHeight = 36.dp,
            onSelect = { option -> onRuntimeChange(option.id) }
        )
        if (supportsRuntimeApiUrl(runtime)) {
            SlockBrutalSectionLabel(label = "Provider", required = false)
            val providerOptions = createAgentProviderDropdownItems()
            SlockBrutalSelectField(
                options = providerOptions.toBrutalSelectOptions(),
                selectedId = providerMode,
                selectedLabel = providerOptions.firstOrNull { it.id == providerMode }?.label ?: "Default",
                placeholder = "Select provider",
                enabled = enabled,
                rowHeight = 36.dp,
                onSelect = { option ->
                    onProviderModeChange(option.id)
                    if (option.id != RUNTIME_PROVIDER_MODE_CUSTOM) {
                        onProviderApiUrlChange("")
                        onProviderApiKeyChange("")
                    }
                }
            )
            Text(
                text = "Default leaves Claude Code provider settings untouched. Custom sets ANTHROPIC_BASE_URL and ANTHROPIC_API_KEY for this agent.",
                style = SlockType.Caption,
                color = SlockColor.TextMutedGray
            )
            if (providerMode == RUNTIME_PROVIDER_MODE_CUSTOM) {
                SlockBrutalTextField(
                    label = "API URL",
                    required = true,
                    value = providerApiUrl,
                    onValueChange = { onProviderApiUrlChange(it) },
                    placeholder = "https://gateway.example.com",
                    enabled = enabled
                )
                if (providerApiUrl.isNotBlank() && providerUrlInvalid) {
                    Text(
                        text = "Provider URL must start with http:// or https://",
                        style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(color = SlockColor.AccentRed),
                        color = SlockColor.AccentRed
                    )
                }
                SlockBrutalTextField(
                    label = "API Key",
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
                        text = "Provider API key is required for custom provider",
                        style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(color = SlockColor.AccentRed),
                        color = SlockColor.AccentRed
                    )
                }
            }
        }
        CreateAgentSectionLabelWithAction(
            label = "Model",
            required = false,
            actionIcon = SlockLucideIconName.RefreshCw,
            actionEnabled = enabled,
            onAction = {}
        )
        val modelSelectItems = modelOptions.map { CreateAgentDropdownItem(id = it.id, label = it.label, custom = it.custom) }
        SlockBrutalSelectField(
            options = modelSelectItems.toBrutalSelectOptions(),
            selectedId = if (customModelMode) CREATE_AGENT_CUSTOM_MODEL_ID else model,
            selectedLabel =
                if (customModelMode) {
                    customModelName.trim().ifBlank { "Custom..." }
                } else {
                    modelOptions.firstOrNull { it.id == model }?.label ?: model.ifBlank { "Select model" }
                },
            placeholder = "Select model",
            enabled = enabled,
            rowHeight = 36.dp,
            onSelect = { item ->
                if (item.id == CREATE_AGENT_CUSTOM_MODEL_ID) {
                    onCustomModelModeChange(true)
                } else {
                    onModelChange(item.id)
                    onCustomModelModeChange(false)
                }
            }
        )
        if (customModelMode) {
            SlockBrutalTextField(
                label = "Custom model",
                required = true,
                value = customModelName,
                onValueChange = { onCustomModelNameChange(it) },
                placeholder = "Enter model name",
                enabled = enabled
            )
            if (customModelName.isBlank()) {
                Text(
                    text = "Custom model is required.",
                    style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(color = SlockColor.AccentRed),
                    color = SlockColor.AccentRed
                )
            }
        }
        if (modelOptions.size == 1) {
            Text(
                text = "No preset models available for this runtime yet.",
                style = SlockType.Caption,
                color = SlockColor.TextMutedGray
            )
        }
        if (supportsRuntimeReasoningEffort(runtime)) {
            SlockBrutalSectionLabel(label = "Reasoning")
            val reasoningOptions = createAgentReasoningDropdownItems()
            val reasoningSelectedId = reasoningEffort ?: CREATE_AGENT_DEFAULT_REASONING_ID
            SlockBrutalSelectField(
                options = reasoningOptions.toBrutalSelectOptions(),
                selectedId = reasoningSelectedId,
                selectedLabel = reasoningOptions.firstOrNull { it.id == reasoningSelectedId }?.label ?: "Default",
                placeholder = "Default",
                enabled = enabled,
                rowHeight = 36.dp,
                onSelect = { option ->
                    onReasoningEffortChange(
                        if (option.id == CREATE_AGENT_DEFAULT_REASONING_ID) null else option.id
                    )
                }
            )
        }
        if (supportsRuntimeFastMode(runtime)) {
            SlockBrutalSectionLabel(label = "Mode")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = fastMode,
                    onCheckedChange = { onFastModeChange(it) },
                    enabled = enabled
                )
                Text(
                    text = "Fast mode",
                    style = SlockType.Caption,
                    color = SlockColor.TextBlack
                )
            }
        }
        AdvancedToggleButton(
            open = advancedOpen,
            onToggle = { onAdvancedOpenChange(!advancedOpen) }
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
                        label = "Command",
                        optional = true,
                        value = command,
                        onValueChange = { onCommandChange(it) },
                        placeholder = "/usr/local/bin/${runtimeInfo.binary}",
                        enabled = enabled
                    )
                    Text(
                        text = "Spawn-time command. Slock passes stream-json args; the executable must read stream-json from stdin and emit Claude-compatible JSON on stdout. Enter only a path or command name; flags are not shell-parsed.",
                        style = SlockType.Caption,
                        color = SlockColor.TextMutedGray
                    )
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
            text = "Advanced",
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
            SlockLucideIcon(
                actionIcon,
                modifier = Modifier.size(13.dp),
                color = if (actionEnabled) SlockColor.TextMutedGrayArgb else "#33000000"
            )
        }
    }
}

@Composable
private fun EnvVarsEditor(
    entries: List<Pair<String, String>>,
    onChange: (List<Pair<String, String>>) -> Unit,
    enabled: Boolean
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SlockBrutalSectionLabel(label = "Environment variables", optional = true)
        entries.forEachIndexed { idx, (key, value) ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SlockBrutalTextField(
                    label = "Key",
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
                    label = "Value",
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
                Text(
                    text = "×",
                    style = SlockType.Caption,
                    color = if (enabled) SlockColor.AccentRed else SlockColor.TextMutedGray,
                    modifier = Modifier
                        .let { if (enabled) it.clickable { onChange(entries.toMutableList().also { it.removeAt(idx) }) } else it }
                        .padding(horizontal = 8.dp)
                )
            }
        }
        Text(
            text = "+ Add env var",
            style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold),
            color = if (enabled) SlockColor.TextBlack else SlockColor.TextMutedGray,
            modifier = Modifier
                .let { if (enabled) it.clickable { onChange(entries + ("" to "")) } else it }
                .padding(vertical = 4.dp)
        )
    }
}

@Composable
private fun Checkbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean
) {
    Box(
        modifier = Modifier
            .size(18.dp)
            .border(SlockStroke.Thin, SlockColor.BorderBlack)
            .background(if (checked) SlockColor.BrandYellow else SlockColor.SurfaceWhite)
            .let { if (enabled) it.clickable { onCheckedChange(!checked) } else it },
        contentAlignment = Alignment.Center
    ) {
        if (checked) {
            Text(
                text = "✓",
                style = SlockType.Caption,
                color = SlockColor.TextBlack
            )
        }
    }
}
