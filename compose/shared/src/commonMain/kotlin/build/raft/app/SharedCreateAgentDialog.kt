package build.raft.app

import build.raft.app.components.SlockBrutalActionButton
import build.raft.app.components.SlockBrutalIconButton
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
import build.raft.app.model.BASE_REASONING_EFFORT_IDS
import build.raft.app.model.RUNTIME_MODELS
import build.raft.app.model.RUNTIME_PROVIDER_MODE_CUSTOM
import build.raft.app.model.RUNTIME_PROVIDER_MODE_DEFAULT
import build.raft.app.model.RUNTIMES
import build.raft.app.model.REASONING_EFFORTS
import build.raft.app.model.buildRuntimeConfig
import build.raft.app.model.getDefaultModel
import build.raft.app.model.getModelLabel
import build.raft.app.model.suggestAgentName
import build.raft.app.model.getRuntimeDisplayName
import build.raft.app.model.reconcileRuntimeReasoningEffort
import build.raft.app.model.RuntimeModelInfo
import build.raft.app.model.supportsRuntimeApiUrl
import build.raft.app.model.supportsRuntimeCommand
import build.raft.app.model.supportsRuntimeFastMode
import build.raft.app.model.supportsRuntimeCustomModelName
import build.raft.app.model.supportsRuntimeReasoningEffort
import build.raft.app.model.validateAgentName
import build.raft.app.data.DataResult
import build.raft.app.data.SharedAgentCreateFormOptionSource
import build.raft.app.data.SharedBuiltInWriteOnlyIdentity
import build.raft.app.data.SharedCreateAgentExactFormKey
import build.raft.app.data.SharedCreateAgentFormStore
import build.raft.app.data.SharedCreateAgentFormStoreState
import build.raft.app.data.SharedCreateAgentMachineFormKey
import build.raft.app.data.CREATE_AGENT_KIMI_SDK_RUNTIME_ID
import build.raft.app.data.SharedResolvedAgentCreateForm
import build.raft.app.data.buildBuiltInRuntimeConfig
import build.raft.app.data.runtimeform.v2.RuntimeFormV2Loaded
import build.raft.app.data.runtimeform.v2.RuntimeFormV2Access
import build.raft.app.data.runtimeform.v2.RuntimeFormV2SubmitBody
import build.raft.app.data.runtimeform.v2.RUNTIME_FORM_V2_FORM_REVISION_STALE
import build.raft.app.data.runtimeform.v2.applyRuntimeFormV2Change
import build.raft.app.data.runtimeform.v2.initialRuntimeFormV2Values
import build.raft.app.data.runtimeform.v2.runtimeFormV2Submission
import build.raft.app.data.runtimeform.v2.runtimeFormV2ValuesAfterSourceReload
import build.raft.app.data.runtimeform.v2.validateRuntimeFormV2
import build.raft.app.data.shouldClearBuiltInWriteOnlySecret
import build.raft.app.resources.MR
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import com.tencent.kuikly.compose.ui.text.SpanStyle
import com.tencent.kuikly.compose.ui.text.TextRange
import com.tencent.kuikly.compose.ui.text.buildAnnotatedString
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.input.TextFieldValue
import com.tencent.kuikly.compose.ui.text.style.TextDecoration
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.tmm.kmmresource.compose.stringResource
import com.tencent.tmm.kmmresource.compose.localized
import com.tencent.tmm.kmmresource.resource.format
import com.tencent.kuikly.core.pager.Pager
import kotlinx.coroutines.flow.MutableStateFlow

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
    val formDefinitionRef: build.raft.app.data.SharedRuntimeFormDefinitionRef? = null,
    val external: Boolean = false,
    /** Runtime form v2: sent instead of runtimeConfig/formDefinitionRef. */
    val runtimeFormV2: RuntimeFormV2SubmitBody? = null
)

private sealed interface RuntimeFormV2DialogLoad {
    data object Idle : RuntimeFormV2DialogLoad
    data object Loading : RuntimeFormV2DialogLoad
    data class Ready(val loaded: RuntimeFormV2Loaded) : RuntimeFormV2DialogLoad
    data class Failed(val code: String) : RuntimeFormV2DialogLoad
}


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
    val machineRuntimes =
        (listOf(runtime, "codex", "claude") + RUNTIMES.filterNot { it.deprecated }.map { it.id }).distinct()
    val runtimeModels =
        RUNTIME_MODELS[runtime].orEmpty().map { model ->
            SharedCreateAgentRuntimeModelOption(
                id = model.id,
                label = model.label,
                verified = model.verified,
                supportedReasoningEfforts = model.supportedReasoningEfforts,
                defaultReasoningEffort = model.defaultReasoningEffort
            )
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
        formDefinitionRef = formDefinitionRef,
        external = external,
        avatarUrl = randomCreateAgentAvatarWireValue(),
        runtimeFormV2 = runtimeFormV2
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
    providerApiKey: TextFieldValue,
    fastMode: Boolean,
    reasoningEffort: String?,
    envVarEntries: List<Pair<String, String>>,
    command: String,
    piProviderId: String = "configured",
    piProviderApiKey: TextFieldValue = TextFieldValue(""),
    builtInForm: SharedResolvedAgentCreateForm? = null,
    builtInProviderId: String = "",
    builtInApiKey: TextFieldValue = TextFieldValue(""),
    builtInBaseUrl: String = "",
    builtInSupportsImageInput: Boolean = false,
    builtInLoadLocalPlugins: Boolean = false,
    builtInModel: String = "",
    runtimeModelsOverride: List<SharedCreateAgentRuntimeModelOption> = emptyList(),
    kimiSdkForm: SharedResolvedAgentCreateForm? = null
): SharedCreateAgentDialogSubmit {
    val finalMachineId =
        if (external) null
        else (requiredComputer?.takeIf { it.isNotBlank() } ?: selectedMachineId)
    // kimi-sdk (task #275): the wire decision is proven by live capability + exact ref.
    // Without either, both the effort and the ref are stripped (fail closed) — even if a
    // stale draft value is still present.
    val kimiSdkWireSelection =
        if (runtime == CREATE_AGENT_KIMI_SDK_RUNTIME_ID) {
            val effectiveModel = if (customModelMode) customModelName.trim() else model
            kimiSdkCreateReasoningWireSelection(
                capability = kimiSdkLiveReasoningCapability(runtime, effectiveModel, runtimeModelsOverride),
                formRef = kimiSdkReasoningFormRef(kimiSdkForm),
                chosenEffort = reasoningEffort
            )
        } else {
            null
        }
    val runtimeConfig =
        if (external) {
            null
        } else if (runtime == "builtin") {
            builtInForm?.buildBuiltInRuntimeConfig(
                providerId = builtInProviderId,
                apiKey = builtInApiKey.text,
                baseUrl = builtInBaseUrl,
                supportsImageInput = builtInSupportsImageInput,
                loadLocalPlugins = builtInLoadLocalPlugins,
                model = builtInModel,
                envVars = createAgentRuntimeEnvVars(envVarEntries)
            )
        } else {
            val effectiveModel = if (customModelMode) customModelName.trim() else model
            val reasoningMetadata =
                createAgentReasoningModelMetadata(runtime, effectiveModel, runtimeModelsOverride)
            buildRuntimeConfig(
                runtime = runtime,
                model = model,
                customModelMode = customModelMode,
                customModelName = customModelName.takeIf { customModelMode },
                providerMode = providerMode,
                providerApiUrl = providerApiUrl,
                providerApiKey = providerApiKey.text,
                piProviderId = piProviderId,
                piProviderApiKey = piProviderApiKey.text,
                fastMode = fastMode,
                reasoningEffort = reasoningEffort,
                reasoningSupportedEfforts = reasoningMetadata.supportedReasoningEfforts,
                reasoningDefaultEffort = reasoningMetadata.defaultReasoningEffort,
                envVars = createAgentRuntimeEnvVars(envVarEntries),
                command = command.takeIf { supportsRuntimeCommand(runtime) }
            ).let { config ->
                if (kimiSdkWireSelection != null) {
                    // buildRuntimeConfig nulls kimi effort via the static gate; the proven live
                    // capability decision re-applies it (null = Default/unmanaged, omitted on wire).
                    config.copy(reasoningEffort = kimiSdkWireSelection.reasoningEffort)
                } else {
                    config
                }
            }
        }
    return SharedCreateAgentDialogSubmit(
        name = name.trim().removePrefix("@"),
        description = description.trim().takeIf { it.isNotBlank() },
        machineId = finalMachineId,
        runtimeConfig = runtimeConfig,
        formDefinitionRef =
            builtInForm?.definition?.ref.takeIf { runtime == "builtin" }
                ?: kimiSdkWireSelection?.formDefinitionRef,
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
    val verified: String? = null,
    val supportedReasoningEfforts: List<String> = emptyList(),
    val defaultReasoningEffort: String? = null
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

internal const val CREATE_AGENT_DESCRIPTION_MAX_LENGTH = 3000
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
    currentRuntimeId: String? = null,
    comingSoonSuffix: String = " (coming soon)",
    notInstalledSuffix: String = " (not installed)"
): List<CreateAgentDropdownItem> {
    val machineRuntimeIdsNormalized = normalizeCreateAgentRuntimeIds(machineRuntimeIds)
    if (machineRuntimeIdsNormalized.isEmpty()) {
        return if (allowStaticFallback) {
            RUNTIMES.filter { it.supported && (!it.deprecated || it.id == currentRuntimeId) }.map { runtime ->
                CreateAgentDropdownItem(id = runtime.id, label = runtime.displayName)
            }
        } else {
            emptyList()
        }
    }
    val available = machineRuntimeIdsNormalized.toSet()
    val legacyItems = RUNTIMES.filter { !it.deprecated || it.id == currentRuntimeId }.map { runtime ->
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
    return legacyItems
}

internal fun canEnableBuiltInRuntime(
    hasFormOverride: Boolean,
    hasSupportedExactRef: Boolean,
    hasResolvedExactForm: Boolean
): Boolean = hasFormOverride || (hasSupportedExactRef && hasResolvedExactForm)

internal suspend fun refreshCreateAgentDialogBuiltInForm(
    formStore: SharedCreateAgentFormStore,
    serverId: String,
    machineId: String
): DataResult<SharedResolvedAgentCreateForm> =
    formStore.loadSupportedBuiltInForm(serverId, machineId, force = true)

internal data class CreateAgentDialogFormRefreshUiState(
    val loading: Boolean = false,
    val admitted: Boolean = false,
    val failureVisible: Boolean = false
) {
    val retryAvailable: Boolean
        get() = failureVisible && !loading
}

internal fun createAgentDialogFormRefreshInitialState(
    shouldRefresh: Boolean,
    hasFormOverride: Boolean
): CreateAgentDialogFormRefreshUiState =
    when {
        hasFormOverride -> CreateAgentDialogFormRefreshUiState(admitted = true)
        shouldRefresh -> CreateAgentDialogFormRefreshUiState(loading = true)
        else -> CreateAgentDialogFormRefreshUiState()
    }

internal fun createAgentDialogFormRefreshStarted(): CreateAgentDialogFormRefreshUiState =
    CreateAgentDialogFormRefreshUiState(loading = true)

internal fun createAgentDialogFormRefreshCompleted(result: DataResult<*>): CreateAgentDialogFormRefreshUiState =
    if (result is DataResult.Content) {
        CreateAgentDialogFormRefreshUiState(admitted = true)
    } else {
        CreateAgentDialogFormRefreshUiState(failureVisible = true)
    }

internal fun isCreateAgentRuntimeContractReady(
    runtime: String,
    builtInFormRefreshAdmitted: Boolean,
    builtInFormLoading: Boolean,
    hasBuiltInRuntimeConfig: Boolean
): Boolean =
    runtime != "builtin" ||
        (builtInFormRefreshAdmitted && !builtInFormLoading && hasBuiltInRuntimeConfig)

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

// CREATE_AGENT_RUNTIME_REQUEST_ADMISSION_START
internal fun executeCreateAgentRuntimeModelsRequestIfAvailable(
    machines: List<SharedCreateAgentMachineOption>,
    selectedMachineId: String?,
    runtime: String,
    external: Boolean,
    force: Boolean,
    request: (machineId: String, runtime: String, force: Boolean) -> Unit
): Boolean {
    val machineId = createAgentAvailableMachineId(machines, selectedMachineId) ?: return false
    if (external || runtime.isBlank()) return false
    request(machineId, runtime, force)
    return true
}

internal suspend fun <T> executeCreateAgentBuiltInFormRefreshIfAvailable(
    machines: List<SharedCreateAgentMachineOption>,
    selectedMachineId: String?,
    serverId: String,
    external: Boolean,
    hasFormOverride: Boolean,
    onStarted: () -> Unit,
    request: suspend (serverId: String, machineId: String) -> DataResult<T>
): DataResult<T>? {
    val machineId = createAgentAvailableMachineId(machines, selectedMachineId) ?: return null
    val normalizedServerId = serverId.trim()
    if (external || hasFormOverride || normalizedServerId.isBlank()) return null
    onStarted()
    return request(normalizedServerId, machineId)
}
// CREATE_AGENT_RUNTIME_REQUEST_ADMISSION_END

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
    runtime: String = "",
    model: String = "",
    runtimeModelsOverride: List<SharedCreateAgentRuntimeModelOption> = emptyList()
): List<CreateAgentDropdownItem> {
    val metadata = createAgentReasoningModelMetadata(runtime, model, runtimeModelsOverride)
    val allowedIds =
        metadata.supportedReasoningEfforts
            .takeIf { it.isNotEmpty() }
            ?.toSet()
            ?: BASE_REASONING_EFFORT_IDS
    return listOf(CreateAgentDropdownItem(id = CREATE_AGENT_DEFAULT_REASONING_ID, label = defaultLabel)) +
        REASONING_EFFORTS
            .filter { (id, _) -> id in allowedIds }
            .map { (id, label) -> CreateAgentDropdownItem(id = id, label = label) }
}

internal data class CreateAgentReasoningModelMetadata(
    val supportedReasoningEfforts: List<String> = emptyList(),
    val defaultReasoningEffort: String? = null
)

internal fun createAgentReasoningModelMetadata(
    runtime: String,
    model: String,
    runtimeModelsOverride: List<SharedCreateAgentRuntimeModelOption>
): CreateAgentReasoningModelMetadata {
    val live = runtimeModelsOverride.firstOrNull { it.id == model }
    if (live?.supportedReasoningEfforts?.isNotEmpty() == true) {
        return CreateAgentReasoningModelMetadata(
            supportedReasoningEfforts = live.supportedReasoningEfforts,
            defaultReasoningEffort = live.defaultReasoningEffort
        )
    }
    val fallback = RUNTIME_MODELS[runtime].orEmpty().firstOrNull { it.id == model }
    return CreateAgentReasoningModelMetadata(
        supportedReasoningEfforts = fallback?.supportedReasoningEfforts.orEmpty(),
        defaultReasoningEffort = fallback?.defaultReasoningEffort ?: live?.defaultReasoningEffort
    )
}

internal fun reconcileCreateAgentReasoningEffort(
    runtime: String,
    model: String,
    current: String?,
    runtimeModelsOverride: List<SharedCreateAgentRuntimeModelOption> = emptyList()
): String? {
    val metadata = createAgentReasoningModelMetadata(runtime, model, runtimeModelsOverride)
    return reconcileRuntimeReasoningEffort(
        runtime = runtime,
        model = model,
        current = current,
        supportedReasoningEfforts = metadata.supportedReasoningEfforts,
        defaultReasoningEffort = metadata.defaultReasoningEffort
    )
}

internal data class CreateAgentRuntimeModelReasoningSelection(
    val runtime: String,
    val model: String,
    val reasoningEffort: String?
)

internal fun createAgentRuntimeModelReasoningSelection(
    runtime: String,
    model: String,
    currentReasoningEffort: String?,
    runtimeModelsOverride: List<SharedCreateAgentRuntimeModelOption> = emptyList()
): CreateAgentRuntimeModelReasoningSelection =
    CreateAgentRuntimeModelReasoningSelection(
        runtime = runtime,
        model = model,
        reasoningEffort =
            reconcileCreateAgentReasoningEffort(
                runtime = runtime,
                model = model,
                current = currentReasoningEffort,
                runtimeModelsOverride = runtimeModelsOverride
            )
    )

internal fun List<CreateAgentDropdownItem>.toBrutalSelectOptions(): List<SlockBrutalSelectOption> =
    map { item -> SlockBrutalSelectOption(id = item.id, label = item.label, enabled = item.enabled) }

// ── kimi-sdk reasoning effort (task #275) ────────────────────────────────────
// kimi-sdk stays OUT of the static REASONING_EFFORT_RUNTIMES set: its selector is
// offered only from LIVE server capability — the selected computer+model catalog
// carrying non-empty supportedReasoningEfforts with a valid default, plus the exact
// kimi-sdk form ref. No static fallback, no version inference.

/** Live reasoning capability for kimi-sdk, proven only by the live per-model catalog. */
internal data class KimiSdkLiveReasoningCapability(
    val supportedReasoningEfforts: List<String>,
    val defaultReasoningEffort: String
)

internal fun kimiSdkLiveReasoningCapability(
    runtime: String,
    model: String,
    runtimeModelsOverride: List<SharedCreateAgentRuntimeModelOption>
): KimiSdkLiveReasoningCapability? {
    if (runtime != CREATE_AGENT_KIMI_SDK_RUNTIME_ID) return null
    val cleanModel = model.trim()
    if (cleanModel.isEmpty()) return null
    val live = runtimeModelsOverride.firstOrNull { it.id == cleanModel } ?: return null
    val efforts = live.supportedReasoningEfforts
    val default = live.defaultReasoningEffort
    if (efforts.isEmpty() || default.isNullOrBlank() || default !in efforts) return null
    return KimiSdkLiveReasoningCapability(supportedReasoningEfforts = efforts, defaultReasoningEffort = default)
}

/** The resolved kimi-sdk form's exact ref, or null when absent/unsupported. */
internal fun kimiSdkReasoningFormRef(
    form: SharedResolvedAgentCreateForm?
): build.raft.app.data.SharedRuntimeFormDefinitionRef? =
    form?.definition?.ref?.takeIf { it.isSupportedKimiSdk }

/** Selector items for kimi-sdk: the live efforts ONLY, plus the Default entry. */
internal fun createAgentKimiSdkReasoningDropdownItems(
    capability: KimiSdkLiveReasoningCapability,
    defaultLabel: String = "Default"
): List<CreateAgentDropdownItem> {
    val labelsById = REASONING_EFFORTS.toMap()
    return listOf(CreateAgentDropdownItem(id = CREATE_AGENT_DEFAULT_REASONING_ID, label = defaultLabel)) +
        capability.supportedReasoningEfforts.map { id ->
            CreateAgentDropdownItem(id = id, label = labelsById[id] ?: id)
        }
}

/**
 * Reconcile a draft effort against live capability: keep a still-valid choice,
 * anything else (or no capability) drops to Default (null = unmanaged), never
 * inventing a value from a static table.
 */
internal fun reconcileKimiSdkReasoningEffort(
    capability: KimiSdkLiveReasoningCapability?,
    current: String?
): String? {
    if (capability == null) return null
    return current?.takeIf { it in capability.supportedReasoningEfforts }
}

/** What the kimi create wire actually carries once capability + ref are proven. */
internal data class KimiSdkReasoningWireSelection(
    val reasoningEffort: String?,
    val formDefinitionRef: build.raft.app.data.SharedRuntimeFormDefinitionRef
)

internal fun kimiSdkCreateReasoningWireSelection(
    capability: KimiSdkLiveReasoningCapability?,
    formRef: build.raft.app.data.SharedRuntimeFormDefinitionRef?,
    chosenEffort: String?
): KimiSdkReasoningWireSelection? {
    val ref = formRef?.takeIf { it.isSupportedKimiSdk } ?: return null
    if (capability == null) return null
    return KimiSdkReasoningWireSelection(
        reasoningEffort = chosenEffort?.takeIf { it in capability.supportedReasoningEfforts },
        formDefinitionRef = ref
    )
}

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
    onMachinesRetry: () -> Unit = {},
    runtimeModelsByMachineAndRuntime: Map<Pair<String, String>, SharedCreateAgentRuntimeModelCatalog> = emptyMap(),
    runtimeModelLoadsInFlight: Set<Pair<String, String>> = emptySet(),
    onRuntimeModelsRequest: (machineId: String, runtime: String, force: Boolean) -> Unit = { _, _, _ -> },
    createAgentFormStore: SharedCreateAgentFormStore? = null,
    createAgentServerId: String? = null,
    builtInFormOverride: SharedResolvedAgentCreateForm? = null,
    kimiSdkForm: SharedResolvedAgentCreateForm? = null,
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
    var runtime by remember(initialRuntime) { mutableStateOf(initialRuntime.ifBlank { RUNTIMES.first().id }) }
    var model by remember(runtime, initialModel) { mutableStateOf(initialModel ?: getDefaultModel(runtime)) }
    var customModelMode by remember { mutableStateOf(false) }
    var customModelName by remember { mutableStateOf("") }
    var providerMode by remember(initialProviderMode) { mutableStateOf(initialProviderMode) }
    var providerApiUrl by remember(initialProviderApiUrl) { mutableStateOf(initialProviderApiUrl) }
    var providerApiKey by remember(initialProviderApiKey) {
        mutableStateOf(
            TextFieldValue(
                text = initialProviderApiKey,
                selection = TextRange(initialProviderApiKey.length)
            )
        )
    }
    var piProviderId by remember { mutableStateOf("configured") }
    var piProviderApiKey by remember { mutableStateOf(TextFieldValue("")) }
    var fastMode by remember(initialFastMode) { mutableStateOf(initialFastMode) }
    var reasoningEffort by remember(initialReasoningEffort) {
        mutableStateOf(
            initialReasoningEffort
                ?: reconcileCreateAgentReasoningEffort(runtime, model, current = null)
        )
    }
    var command by remember(initialCommand) { mutableStateOf(initialCommand) }
    var builtInProviderId by remember { mutableStateOf("") }
    var builtInApiKey by remember { mutableStateOf(TextFieldValue("")) }
    var builtInBaseUrl by remember { mutableStateOf("") }
    var builtInSupportsImageInput by remember { mutableStateOf(false) }
    var builtInLoadLocalPlugins by remember { mutableStateOf(false) }
    var builtInModel by remember { mutableStateOf("") }
    var builtInSecretIdentity by remember { mutableStateOf<SharedBuiltInWriteOnlyIdentity?>(null) }
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
    val availableSelectedMachineId = createAgentAvailableMachineId(machines, selectedMachineId)
    var machineRuntimeInitialized by remember(initialRuntime) { mutableStateOf(initialRuntime.isNotBlank()) }
    val supportedRuntimeIds = remember {
        RUNTIMES.filter { it.supported && !it.deprecated }.map { it.id }.toSet()
    }
    val runtimeModelKey = availableSelectedMachineId?.let { it to runtime }
    val runtimeModelsCatalog = runtimeModelKey?.let { runtimeModelsByMachineAndRuntime[it] }
    val runtimeModelsOverride = runtimeModelsCatalog?.models.orEmpty()
    val runtimeModelsLoading = runtimeModelKey?.let { it in runtimeModelLoadsInFlight } ?: false
    val emptyFormStoreState = remember { MutableStateFlow(SharedCreateAgentFormStoreState()) }
    val createAgentFormState by remember(createAgentFormStore) {
        createAgentFormStore?.state ?: emptyFormStoreState
    }.collectAsState()
    val cleanCreateAgentServerId = createAgentServerId?.trim().orEmpty()
    val createAgentMachineKey = availableSelectedMachineId?.let { machineId ->
        SharedCreateAgentMachineFormKey(cleanCreateAgentServerId, machineId)
    }
    val builtInCatalog = createAgentMachineKey?.let(createAgentFormState.runtimeCatalogs::get)
    val builtInRef = builtInCatalog?.options
        ?.singleOrNull { it.runtimeId == "builtin" && it.selectable }
        ?.formDefinitionRef
        ?.takeIf { it.isSupportedBuiltInPi }
        ?: builtInFormOverride?.definition?.ref
    val builtInExactKey = builtInRef?.let { ref ->
        availableSelectedMachineId?.let { machineId -> SharedCreateAgentExactFormKey(cleanCreateAgentServerId, machineId, ref) }
    }
    val builtInForm = builtInFormOverride ?: builtInExactKey?.let(createAgentFormState.resolvedForms::get)
    // kimi-sdk (task #275): the exact form ref comes from the live runtime catalog (or an
    // explicit override in fixtures); the resolved form proves the server speaks the schema.
    val kimiSdkCatalogRef =
        if (runtime != CREATE_AGENT_KIMI_SDK_RUNTIME_ID) {
            null
        } else {
            createAgentMachineKey?.let { key ->
                createAgentFormState.runtimeCatalogs[key]?.options
                    ?.singleOrNull { it.runtimeId == CREATE_AGENT_KIMI_SDK_RUNTIME_ID && it.selectable }
                    ?.formDefinitionRef
                    ?.takeIf { it.isSupportedKimiSdk }
            }
        }
    val kimiSdkRef = kimiSdkCatalogRef ?: kimiSdkReasoningFormRef(kimiSdkForm)
    val kimiSdkExactKey = kimiSdkRef?.let { ref ->
        availableSelectedMachineId?.let { machineId ->
            SharedCreateAgentExactFormKey(cleanCreateAgentServerId, machineId, ref)
        }
    }
    val resolvedKimiSdkForm = kimiSdkForm ?: kimiSdkExactKey?.let(createAgentFormState.resolvedForms::get)
    val shouldRefreshDialogBuiltInForm =
        createAgentFormStore != null &&
            cleanCreateAgentServerId.isNotBlank() &&
            availableSelectedMachineId != null &&
            !external &&
            builtInFormOverride == null
    var dialogBuiltInFormRefreshState by remember(
        createAgentFormStore,
        cleanCreateAgentServerId,
        availableSelectedMachineId,
        external,
        builtInFormOverride
    ) {
        mutableStateOf(
            createAgentDialogFormRefreshInitialState(
                shouldRefresh = shouldRefreshDialogBuiltInForm,
                hasFormOverride = builtInFormOverride != null
            )
        )
    }
    var dialogBuiltInFormRefreshAttempt by remember(
        createAgentFormStore,
        cleanCreateAgentServerId,
        availableSelectedMachineId,
        external,
        builtInFormOverride
    ) {
        mutableStateOf(0)
    }
    // Runtime form v2 (flag `runtime_form_v2_web`): null keeps the v1 form untouched.
    // Read on every recomposition (value-equal), so a flag that resolves after open still applies.
    val runtimeFormV2 = runtimeFormV2Override ?: createAgentFormStore?.runtimeFormV2Access(cleanCreateAgentServerId)
    // Runtime form v2 needs only the live catalog to offer Built-in; its form comes from v2.
    val builtInV2Offered =
        runtimeFormV2 != null &&
            builtInCatalog?.options?.any { it.runtimeId == "builtin" && it.selectable } == true
    val builtInRuntimeEnabled =
        canEnableBuiltInRuntime(
            hasFormOverride = builtInFormOverride != null,
            hasSupportedExactRef = builtInRef != null,
            hasResolvedExactForm = builtInForm != null
        ) || builtInV2Offered
    val v2Candidate =
        // Any runtime: the server decides which runtimes have a v2 form (404 otherwise, then v1),
        // so a runtime moving to v2 needs no app release.
        runtimeFormV2 != null && !external && availableSelectedMachineId != null && runtime.isNotBlank()
    var v2LoadAttempt by remember(runtimeFormV2, availableSelectedMachineId, runtime, external) { mutableStateOf(0) }
    // A form that went stale mid-session (`form_revision_stale`) is reloaded whole, once; after that it
    // fails like any other load (v1 form, or the retry banner).
    var v2StaleReloads by remember(runtimeFormV2, availableSelectedMachineId, runtime, external) { mutableStateOf(0) }
    var v2Load by remember(runtimeFormV2, availableSelectedMachineId, runtime, external, v2LoadAttempt) {
        mutableStateOf<RuntimeFormV2DialogLoad>(if (v2Candidate) RuntimeFormV2DialogLoad.Loading else RuntimeFormV2DialogLoad.Idle)
    }
    val v2Loaded = (v2Load as? RuntimeFormV2DialogLoad.Ready)?.loaded
    // v2 owns the runtime section while it loads or is ready; a failure falls back to v1.
    val v2Active = v2Candidate && (v2Load is RuntimeFormV2DialogLoad.Loading || v2Loaded != null)
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
    val builtInFormLoading =
        dialogBuiltInFormRefreshState.loading ||
            createAgentMachineKey?.let { it in createAgentFormState.loadingRuntimeCatalogs } == true ||
            builtInExactKey?.let { it in createAgentFormState.loadingForms } == true
    val builtInOwner = createAgentFormState.owner
    val builtInWriteOnlyIdentity = SharedBuiltInWriteOnlyIdentity(
        environmentId = builtInOwner?.environmentId.orEmpty(),
        accountId = builtInOwner?.accountId.orEmpty(),
        sessionGeneration = builtInOwner?.sessionGeneration.orEmpty(),
        serverId = cleanCreateAgentServerId,
        machineId = availableSelectedMachineId.orEmpty(),
        runtimeId = runtime,
        ref = builtInRef,
        providerId = builtInProviderId,
        baseUrl = builtInBaseUrl.trim()
    )
    val effectiveBuiltInApiKey =
        builtInApiKey.takeIf { builtInSecretIdentity == builtInWriteOnlyIdentity }
            ?: TextFieldValue("")

    fun resetRuntimeForMachine(machineId: String) {
        val machine = machines.firstOrNull { it.id == machineId }
        val authoritativeRuntimeIds = machine?.runtimes.orEmpty().filterNot { it == "builtin" } +
            if (builtInRuntimeEnabled) {
                listOf("builtin")
            } else emptyList()
        val newRuntime = authoritativeRuntimeIds.firstOrNull { it in supportedRuntimeIds || it == "builtin" }
            ?: authoritativeRuntimeIds.firstOrNull()
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
        providerApiKey = TextFieldValue("")
        piProviderId = "configured"
        piProviderApiKey = TextFieldValue("")
        builtInApiKey = TextFieldValue("")
        builtInSecretIdentity = null
        builtInSupportsImageInput = false
        builtInLoadLocalPlugins = false
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

    LaunchedEffect(runtimeFormV2, availableSelectedMachineId, runtime, external, v2LoadAttempt) {
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

    LaunchedEffect(availableSelectedMachineId, runtime, external) {
        executeCreateAgentRuntimeModelsRequestIfAvailable(
            machines = machines,
            selectedMachineId = selectedMachineId,
            runtime = runtime,
            external = external,
            force = false,
            request = onRuntimeModelsRequest
        )
    }

    LaunchedEffect(
        createAgentFormStore,
        cleanCreateAgentServerId,
        availableSelectedMachineId,
        external,
        builtInFormOverride,
        dialogBuiltInFormRefreshAttempt
    ) {
        val formStore = createAgentFormStore ?: return@LaunchedEffect
        val result =
            executeCreateAgentBuiltInFormRefreshIfAvailable(
                machines = machines,
                selectedMachineId = selectedMachineId,
                serverId = cleanCreateAgentServerId,
                external = external,
                hasFormOverride = builtInFormOverride != null,
                onStarted = { dialogBuiltInFormRefreshState = createAgentDialogFormRefreshStarted() },
                request = { serverId, machineId ->
                    refreshCreateAgentDialogBuiltInForm(formStore, serverId, machineId)
                }
            )
        if (result != null) {
            dialogBuiltInFormRefreshState = createAgentDialogFormRefreshCompleted(result)
        }
    }

    // kimi-sdk (task #275): load the exact form through the shared runtime-form chain when the
    // kimi runtime is selected. Silent fail-closed — no capability evidence, no selector.
    LaunchedEffect(
        createAgentFormStore,
        cleanCreateAgentServerId,
        availableSelectedMachineId,
        runtime,
        external,
        kimiSdkRef
    ) {
        if (runtime != CREATE_AGENT_KIMI_SDK_RUNTIME_ID || external) return@LaunchedEffect
        val formStore = createAgentFormStore ?: return@LaunchedEffect
        val serverId = cleanCreateAgentServerId.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        val machineId = availableSelectedMachineId ?: return@LaunchedEffect
        kimiSdkRef ?: return@LaunchedEffect
        formStore.loadSupportedKimiSdkForm(serverId, machineId)
    }

    val kimiSdkReasoningModel = if (customModelMode) customModelName.trim() else model
    val kimiSdkReasoningCapability =
        if (runtime == CREATE_AGENT_KIMI_SDK_RUNTIME_ID && kimiSdkReasoningFormRef(resolvedKimiSdkForm) != null) {
            kimiSdkLiveReasoningCapability(runtime, kimiSdkReasoningModel, runtimeModelsOverride)
        } else {
            null
        }

    // kimi-sdk: reconcile the draft effort when live capability arrives; if it is lost
    // mid-edit, fail closed back to Default with no explicit value left to submit.
    LaunchedEffect(runtime, kimiSdkReasoningCapability) {
        if (runtime != CREATE_AGENT_KIMI_SDK_RUNTIME_ID) return@LaunchedEffect
        val reconciled = reconcileKimiSdkReasoningEffort(kimiSdkReasoningCapability, reasoningEffort)
        if (reconciled != reasoningEffort) {
            reasoningEffort = reconciled
        }
    }

    LaunchedEffect(builtInForm) {
        val providerSource = builtInForm?.optionSources?.get("provider") as? SharedAgentCreateFormOptionSource.Select
            ?: return@LaunchedEffect
        val nextProvider = providerSource.defaultValue
        val modelSource = builtInForm.optionSources["model"] as? SharedAgentCreateFormOptionSource.DependentSelect
            ?: return@LaunchedEffect
        builtInProviderId = nextProvider
        builtInModel = modelSource.defaultValueByValue[nextProvider]
            ?: modelSource.optionsByValue[nextProvider]?.firstOrNull()?.value.orEmpty()
        builtInBaseUrl = ""
        builtInApiKey = TextFieldValue("")
        builtInSupportsImageInput = false
        builtInLoadLocalPlugins = false
        builtInSecretIdentity = null
    }

    LaunchedEffect(builtInOwner, cleanCreateAgentServerId, availableSelectedMachineId, runtime, builtInRef, builtInProviderId, builtInBaseUrl) {
        val previousIdentity = builtInSecretIdentity
        if (shouldClearBuiltInWriteOnlySecret(previousIdentity, builtInWriteOnlyIdentity)) {
            builtInApiKey = TextFieldValue("")
        }
        builtInSecretIdentity = builtInWriteOnlyIdentity
    }

    // Reset model when runtime changes or machine-specific model data arrives if current model is not available.
    LaunchedEffect(runtime, runtimeModelsOverride) {
        if (!customModelMode) {
            val currentModels = createAgentModelPresetIds(runtime, runtimeModelsOverride)
            if (currentModels.isNotEmpty() && model !in currentModels) {
                val nextModel =
                    createAgentPreferredModelId(
                        runtime = runtime,
                        runtimeModelsOverride = runtimeModelsOverride,
                        runtimeModelsDefault = runtimeModelsCatalog?.defaultModel
                    )
                val selection =
                    createAgentRuntimeModelReasoningSelection(
                        runtime = runtime,
                        model = nextModel,
                        currentReasoningEffort = reasoningEffort,
                        runtimeModelsOverride = runtimeModelsOverride
                    )
                model = selection.model
                reasoningEffort = selection.reasoningEffort
            }
        }
    }

    val trimmedName = name.trim()
    val inlineNameError = if (trimmedName.isNotEmpty()) validateAgentName(trimmedName) else null
    val localizedInlineNameError = inlineNameError?.let { localizedCreateAgentNameError(trimmedName, it) }
    val descriptionTooLong = description.text.length > CREATE_AGENT_DESCRIPTION_MAX_LENGTH
    val atLimit = !external && maxAgents >= 0 && currentAgentCount >= maxAgents
    val customModelMissing = !external && customModelMode && customModelName.trim().isBlank()
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
    val piProviderKeyInvalid =
        runtime == "pi" && piProviderId != "configured" && piProviderApiKey.text.isBlank()
    val builtInRuntimeConfig =
        if (!external && runtime == "builtin") {
            builtInForm?.buildBuiltInRuntimeConfig(
                providerId = builtInProviderId,
                apiKey = effectiveBuiltInApiKey.text,
                baseUrl = builtInBaseUrl,
                supportsImageInput = builtInSupportsImageInput,
                loadLocalPlugins = builtInLoadLocalPlugins,
                model = builtInModel,
                envVars = createAgentRuntimeEnvVars(envVarEntries)
            )
        } else {
            null
        }
    val canSubmit = trimmedName.isNotBlank() &&
        inlineNameError == null &&
        !descriptionTooLong &&
        !customModelMissing &&
        !piProviderKeyInvalid &&
        !atLimit &&
        !submitting &&
        !selectedMachineBlocked &&
        runtimeAvailabilityError == null &&
        (external || runtime in supportedRuntimeIds) &&
        (external ||
            if (v2Active) {
                v2Loaded != null && v2Errors.isEmpty()
            } else {
                isCreateAgentRuntimeContractReady(
                    runtime = runtime,
                    builtInFormRefreshAdmitted = dialogBuiltInFormRefreshState.admitted,
                    builtInFormLoading = builtInFormLoading,
                    hasBuiltInRuntimeConfig = builtInRuntimeConfig != null
                )
            })
    val showMachinePicker = !external

    LaunchedEffect(selectedMachineId, selectedMachineRuntimeIds, runtime) {
        val machineId = selectedMachineId ?: return@LaunchedEffect
        if (!external && selectedMachineRuntimeIds.isNotEmpty() && (runtime !in selectedMachineRuntimeIds || runtime !in supportedRuntimeIds)) {
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
        keyboardAvoidanceEnabled = true,
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
                    item(key = "plan-limit") {
                        PlanLimitBanner(
                            currentAgentCount = currentAgentCount,
                            maxAgents = maxAgents,
                            planLabel = limitPlanLabel
                        )
                    }
                }
                if (dialogBuiltInFormRefreshState.failureVisible) {
                    item(key = "builtin-form-refresh-error") {
                        SlockRaftUiBanner(
                            status = SlockRaftUiBannerStatus.Warning,
                            description = stringResource(MR.strings.agent_create_error_form_refresh_failed),
                            size = SlockRaftUiBannerSize.Medium,
                            action = {
                                SlockBrutalActionButton(
                                    label = stringResource(MR.strings.common_retry),
                                    onClick = {
                                        dialogBuiltInFormRefreshState = createAgentDialogFormRefreshStarted()
                                        dialogBuiltInFormRefreshAttempt += 1
                                    },
                                    enabled = dialogBuiltInFormRefreshState.retryAvailable && !submitting,
                                    leadingIcon = SlockLucideIconName.RefreshCw,
                                    minWidth = 72.dp,
                                    minHeight = 32.dp,
                                    shadowOffset = SlockShadow.BrutalSmall
                                )
                            }
                        )
                    }
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
                            onRetry = onMachinesRetry,
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
                            style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(color = SlockColor.AccentRed),
                            color = SlockColor.AccentRed
                        )
                    }
                }
                item(key = "description") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        SlockBrutalTextField(
                            label = stringResource(MR.strings.agent_create_label_description),
                            optional = true,
                            value = description,
                            onValueChange = { description = it.coerceTextLength(CREATE_AGENT_DESCRIPTION_MAX_LENGTH) },
                            placeholder = stringResource(MR.strings.agent_create_placeholder_description),
                            enabled = !submitting,
                            maxLines = 3,
                            fieldMinHeight = 82.dp
                        )
                        if (descriptionTooLong) {
                            Text(
                                text =
                                    MR.strings.agent_create_error_description_length
                                        .format(listOf(CREATE_AGENT_DESCRIPTION_MAX_LENGTH))
                                        .localized(),
                                style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold).copy(color = SlockColor.AccentRed),
                                color = SlockColor.AccentRed
                            )
                        } else {
                            Row(modifier = Modifier.fillMaxWidth()) {
                                Spacer(Modifier.weight(1f))
                                Text(
                                    text = "${description.text.length}/$CREATE_AGENT_DESCRIPTION_MAX_LENGTH",
                                    style = SlockType.Caption,
                                    color = SlockColor.TextMutedGray
                                )
                            }
                        }
                    }
                }
                val v2FailureCode = (v2Load as? RuntimeFormV2DialogLoad.Failed)?.code
                val v1FormMissing =
                    (runtime == "builtin" && builtInForm == null) ||
                        (runtime == CREATE_AGENT_KIMI_SDK_RUNTIME_ID && resolvedKimiSdkForm == null)
                if (!external && v2Candidate && v2FailureCode != null && v1FormMissing) {
                    // v2 failed and there is no v1 form to fall back to: say so instead of a blank section.
                    item(key = "runtime-form-v2-failure") {
                        SlockRaftUiBanner(
                            status = SlockRaftUiBannerStatus.Warning,
                            description =
                                MR.strings.runtime_form_v2_load_failed.format(listOf(v2FailureCode)).localized(),
                            size = SlockRaftUiBannerSize.Medium,
                            action = {
                                SlockBrutalActionButton(
                                    label = stringResource(MR.strings.common_retry),
                                    onClick = { v2LoadAttempt += 1 },
                                    enabled = !submitting,
                                    leadingIcon = SlockLucideIconName.RefreshCw,
                                    minWidth = 72.dp,
                                    minHeight = 32.dp,
                                    shadowOffset = SlockShadow.BrutalSmall
                                )
                            }
                        )
                    }
                }
                if (!external) {
                    item(key = "runtime") {
                        if (v2Active) {
                            RuntimeFormV2RuntimeSection(
                                runtime = runtime,
                                onRuntimeChange = { newRuntime ->
                                    val nextCatalog =
                                        availableSelectedMachineId?.let { machineId ->
                                            runtimeModelsByMachineAndRuntime[machineId to newRuntime]
                                        }
                                    val selection =
                                        createAgentRuntimeModelReasoningSelection(
                                            runtime = newRuntime,
                                            model = createAgentPreferredModelId(
                                                runtime = newRuntime,
                                                runtimeModelsOverride = nextCatalog?.models.orEmpty(),
                                                runtimeModelsDefault = nextCatalog?.defaultModel
                                            ),
                                            currentReasoningEffort = reasoningEffort,
                                            runtimeModelsOverride = nextCatalog?.models.orEmpty()
                                        )
                                    runtime = selection.runtime
                                    model = selection.model
                                    reasoningEffort = selection.reasoningEffort
                                    customModelMode = false
                                },
                                machineRuntimeIds = selectedMachineRuntimeIds,
                                runtimeAvailabilityError = runtimeAvailabilityError,
                                enabled = !submitting
                            ) {
                                if (v2Loaded == null) {
                                    Text(
                                        text = stringResource(MR.strings.agent_create_state_computers_loading),
                                        style = SlockType.Caption,
                                        color = SlockColor.TextMutedGray
                                    )
                                } else {
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
                        } else if (runtime == "builtin") {
                            BuiltInRuntimeSection(
                                runtime = runtime,
                                onRuntimeChange = { newRuntime ->
                                    if (newRuntime != runtime) {
                                        builtInApiKey = TextFieldValue("")
                                        builtInSupportsImageInput = false
                                        builtInLoadLocalPlugins = false
                                    }
                                    val nextCatalog =
                                        availableSelectedMachineId?.let { machineId ->
                                            runtimeModelsByMachineAndRuntime[machineId to newRuntime]
                                        }
                                    val nextModel =
                                        createAgentPreferredModelId(
                                            runtime = newRuntime,
                                            runtimeModelsOverride = nextCatalog?.models.orEmpty(),
                                            runtimeModelsDefault = nextCatalog?.defaultModel
                                        )
                                    val selection =
                                        createAgentRuntimeModelReasoningSelection(
                                            runtime = newRuntime,
                                            model = nextModel,
                                            currentReasoningEffort = reasoningEffort,
                                            runtimeModelsOverride = nextCatalog?.models.orEmpty()
                                        )
                                    runtime = selection.runtime
                                    model = selection.model
                                    reasoningEffort = selection.reasoningEffort
                                },
                                machineRuntimeIds = selectedMachineRuntimeIds,
                                runtimeAvailabilityError = runtimeAvailabilityError,
                                form = builtInForm,
                                loading = builtInFormLoading,
                                providerId = builtInProviderId,
                                onProviderIdChange = {
                                    if (it != builtInProviderId) builtInApiKey = TextFieldValue("")
                                    builtInProviderId = it
                                    builtInSupportsImageInput = false
                                    builtInLoadLocalPlugins = false
                                },
                                apiKey = effectiveBuiltInApiKey,
                                onApiKeyChange = {
                                    builtInSecretIdentity = builtInWriteOnlyIdentity
                                    builtInApiKey = it
                                },
                                baseUrl = builtInBaseUrl,
                                onBaseUrlChange = {
                                    if (it.trim() != builtInBaseUrl.trim()) builtInApiKey = TextFieldValue("")
                                    builtInBaseUrl = it
                                },
                                supportsImageInput = builtInSupportsImageInput,
                                onSupportsImageInputChange = { builtInSupportsImageInput = it },
                                loadLocalPlugins = builtInLoadLocalPlugins,
                                onLoadLocalPluginsChange = { builtInLoadLocalPlugins = it },
                                model = builtInModel,
                                onModelChange = { builtInModel = it },
                                envVarEntries = envVarEntries,
                                onEnvVarEntriesChange = { envVarEntries = it },
                                advancedOpen = advancedOpen,
                                onAdvancedOpenChange = { advancedOpen = it },
                                enabled = !submitting
                            )
                        } else {
                            AdvancedRuntimeSection(
                            runtime = runtime,
                            kimiSdkReasoningCapability = kimiSdkReasoningCapability,
                            onRuntimeChange = { newRuntime ->
                                val nextCatalog =
                                    availableSelectedMachineId?.let { machineId ->
                                        runtimeModelsByMachineAndRuntime[machineId to newRuntime]
                                    }
                                val nextModel =
                                    if (customModelMode) {
                                        customModelName.trim()
                                    } else {
                                        createAgentPreferredModelId(
                                            runtime = newRuntime,
                                            runtimeModelsOverride = nextCatalog?.models.orEmpty(),
                                            runtimeModelsDefault = nextCatalog?.defaultModel
                                        )
                                    }
                                val selection =
                                    createAgentRuntimeModelReasoningSelection(
                                        runtime = newRuntime,
                                        model = nextModel,
                                        currentReasoningEffort = reasoningEffort,
                                        runtimeModelsOverride = nextCatalog?.models.orEmpty()
                                    )
                                runtime = selection.runtime
                                if (!customModelMode) {
                                    model = selection.model
                                }
                                reasoningEffort = selection.reasoningEffort
                            },
                            model = model,
                            onModelSelectionChange = { selection ->
                                model = selection.model
                                reasoningEffort = selection.reasoningEffort
                                customModelMode = false
                            },
                            customModelMode = customModelMode,
                            onCustomModelModeChange = { enabled ->
                                customModelMode = enabled
                                if (enabled) {
                                    reasoningEffort =
                                        reconcileCreateAgentReasoningEffort(
                                            runtime = runtime,
                                            model = customModelName.trim(),
                                            current = reasoningEffort,
                                            runtimeModelsOverride = emptyList()
                                        )
                                }
                            },
                            customModelName = customModelName,
                            onCustomModelNameChange = { customModelName = it },
                            runtimeModelsOverride = runtimeModelsOverride,
                            runtimeModelsLoading = runtimeModelsLoading,
                            onRuntimeModelsRefresh = {
                                executeCreateAgentRuntimeModelsRequestIfAvailable(
                                    machines = machines,
                                    selectedMachineId = selectedMachineId,
                                    runtime = runtime,
                                    external = external,
                                    force = true,
                                    request = onRuntimeModelsRequest
                                )
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
                            piProviderId = piProviderId,
                            onPiProviderIdChange = {
                                piProviderId = it
                                if (it == "configured") piProviderApiKey = TextFieldValue("")
                            },
                            piProviderApiKey = piProviderApiKey,
                            onPiProviderApiKeyChange = { piProviderApiKey = it },
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
            }
            SlockDialogFooter {
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
                        val v2Submit = (v2Form ?: v2Loaded)?.takeIf { v2Active }?.let { loaded ->
                            SharedCreateAgentDialogSubmit(
                                name = trimmedName.removePrefix("@"),
                                description = description.text.trim().takeIf { it.isNotBlank() },
                                machineId = requiredComputer?.takeIf { it.isNotBlank() } ?: selectedMachineId,
                                runtimeFormV2 = RuntimeFormV2SubmitBody(
                                    runtimeId = loaded.form.runtimeId.ifBlank { runtime },
                                    formValues = runtimeFormV2Submission(loaded.form, v2Values, loaded.sources)
                                )
                            )
                        }
                        if (v2Submit != null) {
                            onSubmit(v2Submit)
                            return@SlockBrutalActionButton
                        }
                        onSubmit(
                            buildSharedCreateAgentDialogSubmit(
                                name = trimmedName,
                                description = description.text,
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
                                piProviderId = piProviderId,
                                piProviderApiKey = piProviderApiKey,
                                fastMode = fastMode,
                                reasoningEffort = reasoningEffort,
                                envVarEntries = envVarEntries,
                                command = command,
                                builtInForm = builtInForm,
                                builtInProviderId = builtInProviderId,
                                builtInApiKey = effectiveBuiltInApiKey,
                                builtInBaseUrl = builtInBaseUrl,
                                builtInSupportsImageInput = builtInSupportsImageInput,
                                builtInLoadLocalPlugins = builtInLoadLocalPlugins,
                                builtInModel = builtInModel,
                                runtimeModelsOverride = runtimeModelsOverride,
                                kimiSdkForm = resolvedKimiSdkForm
                            )
                        )
                    }
                )
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
private fun PlanLimitBanner(currentAgentCount: Int, maxAgents: Int, planLabel: String?) {
    if (planLabel.isNullOrBlank()) {
        SlockRaftUiBanner(
            status = SlockRaftUiBannerStatus.Warning,
            description =
                MR.strings.agent_create_error_limit
                    .format(listOf(currentAgentCount, maxAgents))
                    .localized(),
            size = SlockRaftUiBannerSize.Medium
        )
        return
    }

    val summary =
        MR.strings.agent_create_error_limit_plan
            .format(listOf(currentAgentCount, maxAgents, planLabel))
            .localized()
    val action = stringResource(MR.strings.agent_create_action_upgrade_for_more)
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(SlockRaftUiBannerStatus.Warning.background)
                .border(SlockStroke.Default, SlockColor.BorderBlack)
                .padding(SlockRaftUiBannerSize.Medium.padding)
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
            style = SlockType.Body.withSlockFontWeight(FontWeight.Bold).copy(fontSize = 14.sp, lineHeight = 18.sp),
            color = SlockColor.TextBlack
        )
    }
}

@Composable
private fun MachinePicker(
    machines: List<SharedCreateAgentMachineOption>,
    machinesLoading: Boolean,
    machinesErrorMessage: String?,
    onRetry: () -> Unit,
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
            !machinesErrorMessage.isNullOrBlank() ->
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            // deliberate softened error border over flat cream (no shadow compositing risk); triaged token-audit #1063
                            .border(SlockStroke.Thin, SlockColor.AccentRed.copy(alpha = 0.5f))
                            .background(SlockColor.SurfaceCream)
                            .padding(SlockSpacing.Sm),
                    verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
                ) {
                    Text(
                        text = machinesErrorMessage,
                        style = SlockType.Caption.withSlockFontWeight(FontWeight.Bold),
                        color = SlockColor.AccentRed
                    )
                    SlockBrutalActionButton(
                        label = stringResource(MR.strings.common_retry),
                        onClick = onRetry,
                        enabled = enabled,
                        leadingIcon = SlockLucideIconName.RefreshCw,
                        minWidth = 72.dp,
                        minHeight = 32.dp,
                        shadowOffset = SlockShadow.BrutalSmall
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
internal fun BuiltInRuntimeSection(
    runtime: String,
    onRuntimeChange: (String) -> Unit,
    machineRuntimeIds: List<String>,
    runtimeAvailabilityError: String?,
    form: SharedResolvedAgentCreateForm?,
    loading: Boolean,
    providerId: String,
    onProviderIdChange: (String) -> Unit,
    apiKey: TextFieldValue,
    onApiKeyChange: (TextFieldValue) -> Unit,
    baseUrl: String,
    onBaseUrlChange: (String) -> Unit,
    supportsImageInput: Boolean,
    onSupportsImageInputChange: (Boolean) -> Unit,
    loadLocalPlugins: Boolean,
    onLoadLocalPluginsChange: (Boolean) -> Unit,
    model: String,
    onModelChange: (String) -> Unit,
    envVarEntries: List<Pair<String, String>>,
    onEnvVarEntriesChange: (List<Pair<String, String>>) -> Unit,
    advancedOpen: Boolean,
    onAdvancedOpenChange: (Boolean) -> Unit,
    enabled: Boolean
) {
    val focusManager = LocalFocusManager.current
    val runtimeOptions = createAgentRuntimeDropdownItems(
        machineRuntimeIds = machineRuntimeIds,
        allowStaticFallback = false,
        comingSoonSuffix = stringResource(MR.strings.agent_create_suffix_coming_soon),
        notInstalledSuffix = stringResource(MR.strings.agent_create_suffix_not_installed)
    )
    val providerSource = form?.optionSources?.get("provider") as? SharedAgentCreateFormOptionSource.Select
    val modelSource = form?.optionSources?.get("model") as? SharedAgentCreateFormOptionSource.DependentSelect
    val providerOptions = providerSource?.options.orEmpty()
    val modelOptions = modelSource?.optionsByValue?.get(providerId).orEmpty()
    val customModelAllowed = modelSource?.customValueAllowedByValue?.get(providerId) == true
    val visiblePointers = form?.definition?.uiSchema?.visibility.orEmpty().filter { rule ->
        rule.dependsOnPointer != "/providerId" || providerId in rule.allowedValues
    }.mapTo(mutableSetOf()) { it.pointer }
    val orderedFields = form?.definition?.uiSchema?.order.orEmpty()
    val fieldCopy = form?.definition?.uiSchema?.localization.orEmpty()

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SlockBrutalSectionLabel(label = stringResource(MR.strings.agent_create_label_runtime))
        SlockBrutalSelectField(
            options = runtimeOptions.toBrutalSelectOptions(),
            selectedId = runtime,
            selectedLabel = runtimeOptions.firstOrNull { it.id == runtime }?.label ?: "Built-in (Pi)",
            placeholder = stringResource(MR.strings.agent_create_placeholder_select_runtime),
            enabled = enabled && runtimeAvailabilityError == null,
            rowHeight = 36.dp,
            onSelect = { option ->
                focusManager.clearFocus(force = true)
                onRuntimeChange(option.id)
            }
        )
        if (loading && form == null) {
            Text(
                text = stringResource(MR.strings.agent_create_state_computers_loading),
                style = SlockType.Caption,
                color = SlockColor.TextMutedGray
            )
        }
        if (form != null) {
            orderedFields.filterNot { "/$it" in form.definition.uiSchema.advancedPointers }.forEach { field ->
                val copy = fieldCopy[field]
                when (field) {
                    "providerId" -> {
                        SlockBrutalSectionLabel(label = copy?.label ?: "Provider", required = true)
                        SlockBrutalSelectField(
                            options = providerOptions.map { SlockBrutalSelectOption(it.value, it.label) },
                            selectedId = providerId,
                            selectedLabel = providerOptions.firstOrNull { it.value == providerId }?.label.orEmpty(),
                            placeholder = copy?.placeholder ?: "Provider",
                            enabled = enabled,
                            rowHeight = 36.dp,
                            onSelect = { option ->
                                focusManager.clearFocus(force = true)
                                onProviderIdChange(option.id)
                                val nextModel = modelSource?.defaultValueByValue?.get(option.id)
                                    ?: modelSource?.optionsByValue?.get(option.id)?.firstOrNull()?.value.orEmpty()
                                onModelChange(nextModel)
                                onBaseUrlChange("")
                            }
                        )
                        copy?.hint?.let { Text(it, style = SlockType.Caption, color = SlockColor.TextMutedGray) }
                    }
                    "apiKey" -> {
                        SlockBrutalTextField(
                            label = copy?.label ?: "API Key",
                            required = true,
                            value = apiKey,
                            onValueChange = onApiKeyChange,
                            placeholder = copy?.placeholder.orEmpty(),
                            password = true,
                            enabled = enabled
                        )
                    }
                    "baseUrl" -> if ("/baseUrl" in visiblePointers) {
                        SlockBrutalTextField(
                            label = copy?.label ?: "Base URL",
                            required = true,
                            value = baseUrl,
                            onValueChange = onBaseUrlChange,
                            placeholder = copy?.placeholder.orEmpty(),
                            enabled = enabled
                        )
                    }
                    "supportsImageInput" -> if ("/supportsImageInput" in visiblePointers) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SlockCheckbox(
                                checked = supportsImageInput,
                                onCheckedChange = onSupportsImageInputChange,
                                enabled = enabled
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = copy?.label ?: stringResource(MR.strings.agent_create_label_supports_image_input),
                                    style = SlockType.Caption,
                                    color = SlockColor.TextBlack
                                )
                                copy?.hint?.let {
                                    Text(it, style = SlockType.Caption, color = SlockColor.TextMutedGray)
                                }
                            }
                        }
                    }
                    "model" -> {
                        if (customModelAllowed) {
                            SlockBrutalTextField(
                                label = copy?.label ?: "Model",
                                required = true,
                                value = model,
                                onValueChange = onModelChange,
                                placeholder = copy?.placeholder.orEmpty(),
                                enabled = enabled
                            )
                        } else {
                            SlockBrutalSectionLabel(label = copy?.label ?: "Model", required = true)
                            SlockBrutalSelectField(
                                options = modelOptions.map { SlockBrutalSelectOption(it.value, it.label) },
                                selectedId = model,
                                selectedLabel = modelOptions.firstOrNull { it.value == model }?.label.orEmpty(),
                                placeholder = copy?.placeholder ?: "Model",
                                enabled = enabled,
                                rowHeight = 36.dp,
                                onSelect = { onModelChange(it.id) }
                            )
                        }
                    }
                }
            }
            if (form.definition.uiSchema.advancedPointers.isNotEmpty()) {
                AdvancedToggleButton(advancedOpen) {
                    focusManager.clearFocus(force = true)
                    onAdvancedOpenChange(!advancedOpen)
                }
                if (advancedOpen) {
                    form.definition.uiSchema.advancedPointers.forEach { pointer ->
                        val field = pointer.removePrefix("/")
                        val copy = fieldCopy[field]
                        when (field) {
                            "loadLocalPlugins" -> {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    SlockCheckbox(
                                        checked = loadLocalPlugins,
                                        onCheckedChange = onLoadLocalPluginsChange,
                                        enabled = enabled
                                    )
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(
                                            text = copy?.label ?: "Load local Pi plugins",
                                            style = SlockType.Caption,
                                            color = SlockColor.TextBlack
                                        )
                                        copy?.hint?.let {
                                            Text(it, style = SlockType.Caption, color = SlockColor.TextMutedGray)
                                        }
                                    }
                                }
                            }
                            "envVars" -> EnvVarsEditor(envVarEntries, onEnvVarEntriesChange, enabled)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun AdvancedRuntimeSection(
    runtime: String,
    onRuntimeChange: (String) -> Unit,
    model: String,
    onModelSelectionChange: (CreateAgentRuntimeModelReasoningSelection) -> Unit,
    customModelMode: Boolean,
    onCustomModelModeChange: (Boolean) -> Unit,
    customModelName: String,
    kimiSdkReasoningCapability: KimiSdkLiveReasoningCapability? = null,
    onCustomModelNameChange: (String) -> Unit,
    runtimeModelsOverride: List<SharedCreateAgentRuntimeModelOption>,
    runtimeModelsLoading: Boolean = false,
    onRuntimeModelsRefresh: () -> Unit = {},
    retainUnavailableSelectedPreset: Boolean = false,
    existingRuntimeId: String? = null,
    machineRuntimeIds: List<String> = emptyList(),
    runtimeAvailabilityError: String? = null,
    enabled: Boolean,
    providerMode: RuntimeProviderMode,
    onProviderModeChange: (RuntimeProviderMode) -> Unit,
    providerApiUrl: String,
    onProviderApiUrlChange: (String) -> Unit,
    providerApiKey: TextFieldValue,
    onProviderApiKeyChange: (TextFieldValue) -> Unit,
    piProviderId: String = "configured",
    onPiProviderIdChange: (String) -> Unit = {},
    piProviderApiKey: TextFieldValue = TextFieldValue(""),
    onPiProviderApiKeyChange: (TextFieldValue) -> Unit = {},
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
            currentRuntimeId = existingRuntimeId,
            comingSoonSuffix = stringResource(MR.strings.agent_create_suffix_coming_soon),
            notInstalledSuffix = stringResource(MR.strings.agent_create_suffix_not_installed)
        )
    val runtimeEnabled = enabled && runtimeAvailabilityError == null && runtimeOptions.isNotEmpty()
    val providerUrlRequired = supportsRuntimeApiUrl(runtime) && providerMode == RUNTIME_PROVIDER_MODE_CUSTOM
    val providerUrlInvalid = providerUrlRequired &&
        !Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(providerApiUrl.trim())
    val providerKeyInvalid = providerUrlRequired && providerApiKey.text.isBlank()
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
                        onProviderApiKeyChange(TextFieldValue(""))
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
                    // Keep the exact native TextFieldValue while masking only its presentation.
                    // Product state reads `.text` at the submit boundary; bullets never enter it.
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
        if (runtime == "pi") {
            SlockBrutalSectionLabel(label = stringResource(MR.strings.agent_create_label_provider))
            SlockBrutalSelectField(
                options =
                    listOf(
                        SlockBrutalSelectOption(
                            id = "configured",
                            label = stringResource(MR.strings.agent_create_action_default)
                        ),
                        SlockBrutalSelectOption(id = "deepseek", label = "DeepSeek")
                    ),
                selectedId = piProviderId,
                selectedLabel =
                    if (piProviderId == "deepseek") "DeepSeek"
                    else stringResource(MR.strings.agent_create_action_default),
                placeholder = stringResource(MR.strings.agent_create_placeholder_select_provider),
                enabled = enabled,
                rowHeight = 36.dp,
                onSelect = { option ->
                    focusManager.clearFocus(force = true)
                    onPiProviderIdChange(option.id)
                    if (option.id == "configured") onPiProviderApiKeyChange(TextFieldValue(""))
                }
            )
            if (piProviderId != "configured") {
                SlockBrutalTextField(
                    label = stringResource(MR.strings.agent_create_label_api_key),
                    required = true,
                    value = piProviderApiKey,
                    password = true,
                    onValueChange = onPiProviderApiKeyChange,
                    placeholder = "sk-...",
                    enabled = enabled
                )
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
                    onModelSelectionChange(
                        createAgentRuntimeModelReasoningSelection(
                            runtime = runtime,
                            model = item.id,
                            currentReasoningEffort = reasoningEffort,
                            runtimeModelsOverride = runtimeModelsOverride
                        )
                    )
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
            val reasoningModel =
                if (customModelMode) customModelName.trim() else model
            SlockBrutalSectionLabel(label = stringResource(MR.strings.agent_create_label_reasoning))
            val reasoningOptions =
                createAgentReasoningDropdownItems(
                    defaultLabel = stringResource(MR.strings.agent_create_action_default),
                    runtime = runtime,
                    model = reasoningModel,
                    runtimeModelsOverride = runtimeModelsOverride
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
        // kimi-sdk (task #275): separate gate — live catalog + resolved form ref only,
        // never the static REASONING_EFFORT_RUNTIMES/BASE fallbacks above.
        if (runtime == CREATE_AGENT_KIMI_SDK_RUNTIME_ID && kimiSdkReasoningCapability != null) {
            SlockBrutalSectionLabel(label = stringResource(MR.strings.agent_create_label_reasoning))
            val kimiReasoningOptions =
                createAgentKimiSdkReasoningDropdownItems(
                    capability = kimiSdkReasoningCapability,
                    defaultLabel = stringResource(MR.strings.agent_create_action_default)
                )
            val kimiReasoningSelectedId = reasoningEffort ?: CREATE_AGENT_DEFAULT_REASONING_ID
            SlockBrutalSelectField(
                options = kimiReasoningOptions.toBrutalSelectOptions(),
                selectedId = kimiReasoningSelectedId,
                selectedLabel =
                    kimiReasoningOptions.firstOrNull { it.id == kimiReasoningSelectedId }?.label
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
internal fun AdvancedToggleButton(open: Boolean, onToggle: () -> Unit) {
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
internal fun CreateAgentSectionLabelWithAction(
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
    SlockBrutalIconButton(
        iconName = SlockLucideIconName.Dices,
        onClick = onClick,
        contentDescription = stringResource(MR.strings.agent_create_random_name),
        enabled = enabled,
        backgroundColor = Color.Transparent,
        disabledBackgroundColor = Color.Transparent,
        borderColor = Color.Transparent,
        disabledBorderColor = Color.Transparent,
        shadowColor = Color.Transparent,
        disabledShadowColor = Color.Transparent,
        iconColor = SlockColor.TextBlackArgb,
        disabledIconColor = SlockColor.TextMutedGrayArgb,
        size = 22.dp,
        shadowOffset = 0.dp,
        iconSize = 13.dp
    )
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
internal fun EnvVarsEditor(
    entries: List<Pair<String, String>>,
    onChange: (List<Pair<String, String>>) -> Unit,
    enabled: Boolean,
    label: String? = null,
    required: Boolean = false
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SlockBrutalSectionLabel(
            label = label ?: stringResource(MR.strings.agent_create_label_environment_variables),
            required = required,
            optional = !required
        )
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
