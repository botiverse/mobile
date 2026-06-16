package ai.slock.compose

import ai.slock.compose.components.SlockBrutalActionButton
import ai.slock.compose.components.SlockBrutalSectionLabel
import ai.slock.compose.components.SlockBrutalTextField
import ai.slock.compose.model.RuntimeConfig
import ai.slock.compose.model.RuntimeProviderMode
import ai.slock.compose.model.RUNTIME_FAST_MODE_RUNTIMES
import ai.slock.compose.model.RUNTIME_MODELS
import ai.slock.compose.model.RUNTIME_PROVIDER_MODE_CUSTOM
import ai.slock.compose.model.RUNTIME_PROVIDER_MODE_DEFAULT
import ai.slock.compose.model.RUNTIMES
import ai.slock.compose.model.REASONING_EFFORTS
import ai.slock.compose.model.buildRuntimeConfig
import ai.slock.compose.model.getDefaultModel
import ai.slock.compose.model.getModelLabel
import ai.slock.compose.model.getRuntimeDisplayName
import ai.slock.compose.model.RuntimeModelInfo
import ai.slock.compose.model.RUNTIME_CONFIG_VERSION
import ai.slock.compose.model.RuntimeInfo
import ai.slock.compose.model.RuntimeModeConfig
import ai.slock.compose.model.RuntimeModelConfig
import ai.slock.compose.model.RuntimeProviderConfig
import ai.slock.compose.model.supportsRuntimeApiUrl
import ai.slock.compose.model.supportsRuntimeCommand
import ai.slock.compose.model.supportsRuntimeCustomModelName
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
import com.tencent.kuikly.compose.foundation.layout.PaddingValues
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.Spacer
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.foundation.lazy.LazyColumn
import com.tencent.kuikly.compose.foundation.shape.RoundedCornerShape
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp

internal data class SharedCreateAgentDialogSubmit(
    val name: String,
    val description: String?,
    val machineId: String?,
    val runtimeConfig: RuntimeConfig? = null,
    val external: Boolean = false
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

private const val DEFAULT_CREATE_AGENT_DESCRIPTION_MAX_LENGTH = 3000

@Composable
internal fun SharedCreateAgentDialog(
    submitting: Boolean,
    errorMessage: String?,
    initialName: String = "",
    initialDescription: String = "",
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
    var runtime by remember { mutableStateOf(RUNTIMES.first().id) }
    var model by remember(runtime) { mutableStateOf(getDefaultModel(runtime)) }
    var customModelMode by remember { mutableStateOf(false) }
    var customModelName by remember { mutableStateOf("") }
    var providerMode by remember { mutableStateOf<RuntimeProviderMode>(RUNTIME_PROVIDER_MODE_DEFAULT) }
    var providerApiUrl by remember { mutableStateOf("") }
    var providerApiKey by remember { mutableStateOf("") }
    var fastMode by remember { mutableStateOf(false) }
    var reasoningEffort by remember { mutableStateOf<String?>(null) }
    var command by remember { mutableStateOf("") }
    var envVarEntries by remember {
        mutableStateOf(listOf<Pair<String, String>>())
    }
    var advancedOpen by remember { mutableStateOf(false) }
    var selectedMachineId by remember {
        mutableStateOf<String?>(
            (requiredComputer ?: suggestedComputer)?.takeIf { id ->
                machines.any { it.id == id }
            }
        )
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
    val canSubmit = trimmedName.isNotBlank() &&
        inlineNameError == null &&
        !descriptionTooLong &&
        !atLimit &&
        !submitting &&
        (external || selectedMachineId != null)
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
                            val newRuntime = machines.firstOrNull { it.id == id }
                                ?.runtimes?.firstOrNull { r -> r == runtime } ?: runtime
                            if (newRuntime != runtime) runtime = newRuntime
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
                    modifier = Modifier.fillMaxWidth(),
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
                    onModelChange = { model = it },
                    customModelMode = customModelMode,
                    onCustomModelModeChange = { customModelMode = it },
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
            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(((machines.size * 42) + 8).dp.coerceAtMost(160.dp))
            ) {
                items(count = machines.size, key = { idx -> machines[idx].id }) { idx ->
                    val machine = machines[idx]
                    val isOffline = machine.status != "online"
                    val blockedByRequired = !requiredComputer.isNullOrBlank() && machine.id != requiredComputer
                    val isSelected = machine.id == selectedMachineId
                    val isSelectable = enabled && !isOffline && !blockedByRequired
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = isSelectable) { onSelect(machine.id) }
                            .background(if (isSelected) SlockColor.SurfaceCream else SlockColor.SurfaceWhite)
                            .border(SlockStroke.Thin, SlockColor.BorderBlack)
                            .padding(horizontal = SlockSpacing.Sm, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = if (isSelected) "✓" else " ",
                            style = SlockType.Caption,
                            color = SlockColor.TextBlack,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = machine.name + if (isOffline) " (offline)" else "",
                            style = SlockType.Caption,
                            color = if (isSelectable) SlockColor.TextBlack else SlockColor.TextMutedGray
                        )
                    }
                }
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
    val modelOptions: List<RuntimeModelInfo> = runtimeModelsOverride
        .takeIf { it.isNotEmpty() }
        ?.map { RuntimeModelInfo(it.id, it.label, it.verified) }
        ?: RUNTIME_MODELS[runtime].orEmpty()
    val effectiveModel = if (customModelMode) customModelName else model
    val providerUrlRequired = supportsRuntimeApiUrl(runtime) && providerMode == RUNTIME_PROVIDER_MODE_CUSTOM
    val providerUrlInvalid = providerUrlRequired &&
        !Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(providerApiUrl.trim())
    val providerKeyInvalid = providerUrlRequired && providerApiKey.isBlank()

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SlockBrutalSectionLabel(label = "Runtime", required = true)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            supportedRuntimes.take(4).forEach { r ->
                RuntimeChip(
                    label = r.displayName,
                    selected = r.id == runtime,
                    enabled = enabled,
                    onClick = { onRuntimeChange(r.id) }
                )
            }
        }
        if (supportedRuntimes.size > 4) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                supportedRuntimes.drop(4).forEach { r ->
                    RuntimeChip(
                        label = r.displayName,
                        selected = r.id == runtime,
                        enabled = enabled,
                        onClick = { onRuntimeChange(r.id) }
                    )
                }
            }
        }
        SlockBrutalSectionLabel(label = "Model", required = true)
        if (supportsRuntimeCustomModelName(runtime)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = customModelMode,
                    onCheckedChange = { onCustomModelModeChange(it) },
                    enabled = enabled
                )
                Text(
                    text = "Use custom model name",
                    style = SlockType.Caption,
                    color = SlockColor.TextBlack
                )
            }
        }
        if (customModelMode) {
            SlockBrutalTextField(
                label = "Custom model",
                required = true,
                value = customModelName,
                onValueChange = { onCustomModelNameChange(it) },
                placeholder = "Enter model name",
                enabled = enabled
            )
        } else {
            ModelChipRow(
                models = modelOptions,
                selectedId = model,
                enabled = enabled,
                onSelect = onModelChange
            )
        }
        if (supportsRuntimeApiUrl(runtime)) {
            SlockBrutalSectionLabel(label = "Provider", required = false)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                RuntimeChip(
                    label = "Default",
                    selected = providerMode == RUNTIME_PROVIDER_MODE_DEFAULT,
                    enabled = enabled,
                    onClick = { onProviderModeChange(RUNTIME_PROVIDER_MODE_DEFAULT) }
                )
                RuntimeChip(
                    label = "Custom",
                    selected = providerMode == RUNTIME_PROVIDER_MODE_CUSTOM,
                    enabled = enabled,
                    onClick = { onProviderModeChange(RUNTIME_PROVIDER_MODE_CUSTOM) }
                )
            }
            if (providerMode == RUNTIME_PROVIDER_MODE_CUSTOM) {
                SlockBrutalTextField(
                    label = "Provider API URL",
                    required = true,
                    value = providerApiUrl,
                    onValueChange = { onProviderApiUrlChange(it) },
                    placeholder = "https://api.example.com",
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
                    label = "Provider API key",
                    required = true,
                    value = providerApiKey,
                    onValueChange = { onProviderApiKeyChange(it) },
                    placeholder = "sk_...",
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
        if (supportsRuntimeFastMode(runtime)) {
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
                if (supportsRuntimeReasoningEffort(runtime)) {
                    SlockBrutalSectionLabel(label = "Reasoning effort")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        REASONING_EFFORTS.forEach { (id, label) ->
                            RuntimeChip(
                                label = label,
                                selected = reasoningEffort == id,
                                enabled = enabled,
                                onClick = {
                                    onReasoningEffortChange(if (reasoningEffort == id) null else id)
                                }
                            )
                        }
                    }
                }
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
        Text(
            text = if (open) "▾" else "▸",
            style = SlockType.Caption,
            color = SlockColor.TextMutedGray
        )
        Text(
            text = "Advanced",
            style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold),
            color = SlockColor.TextMutedGray
        )
    }
}

@Composable
private fun RuntimeChip(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .border(SlockStroke.Thin, SlockColor.BorderBlack)
            .background(
                when {
                    !enabled -> SlockColor.SurfaceCream
                    selected -> SlockColor.BrandYellow
                    else -> SlockColor.SurfaceWhite
                }
            )
            .let { if (enabled) it.clickable { onClick() } else it }
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold),
            color = if (enabled) SlockColor.TextBlack else SlockColor.TextMutedGray
        )
    }
}

@Composable
private fun ModelChipRow(
    models: List<RuntimeModelInfo>,
    selectedId: String,
    enabled: Boolean,
    onSelect: (String) -> Unit
) {
    if (models.isEmpty()) {
        Text(
            text = "No models available for this runtime yet",
            style = SlockType.Caption,
            color = SlockColor.TextMutedGray
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        models.chunked(3).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                row.forEach { m ->
                    RuntimeChip(
                        label = m.label,
                        selected = selectedId == m.id,
                        enabled = enabled,
                        onClick = { onSelect(m.id) }
                    )
                }
            }
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
