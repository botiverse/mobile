package build.raft.app.data

import build.raft.app.model.RuntimeConfig
import build.raft.app.model.RuntimeHostUserState
import build.raft.app.model.RuntimeModelConfig
import build.raft.app.model.RuntimeModeConfig
import build.raft.app.model.RuntimeProviderConfig
import kotlinx.serialization.Serializable

internal const val CREATE_AGENT_FORM_PROTOCOL_VERSION = 1
internal const val CREATE_AGENT_BUILTIN_RUNTIME_ID = "builtin"
internal const val CREATE_AGENT_BUILTIN_PI_SCHEMA_VERSION = "builtin-pi.create.v3"
internal const val CREATE_AGENT_KIMI_SDK_RUNTIME_ID = "kimi-sdk"
internal const val CREATE_AGENT_KIMI_SDK_SCHEMA_VERSION = "kimi-sdk.create.v1"

@Serializable
data class SharedRuntimeFormDefinitionRef(
    val protocolVersion: Int,
    val runtimeId: String,
    val schemaVersion: String
) {
    val identityKey: String
        get() = "$protocolVersion:$runtimeId:$schemaVersion"

    val isSupportedBuiltInPi: Boolean
        get() =
            protocolVersion == CREATE_AGENT_FORM_PROTOCOL_VERSION &&
                runtimeId == CREATE_AGENT_BUILTIN_RUNTIME_ID &&
                schemaVersion == CREATE_AGENT_BUILTIN_PI_SCHEMA_VERSION

    val isSupportedKimiSdk: Boolean
        get() =
            protocolVersion == CREATE_AGENT_FORM_PROTOCOL_VERSION &&
                runtimeId == CREATE_AGENT_KIMI_SDK_RUNTIME_ID &&
                schemaVersion == CREATE_AGENT_KIMI_SDK_SCHEMA_VERSION

    val isSupportedRuntimeForm: Boolean
        get() = isSupportedBuiltInPi || isSupportedKimiSdk
}

internal data class SharedCreateAgentRuntimeCatalog(
    val machineId: String,
    val options: List<SharedCreateAgentRuntimeOption>
)

internal data class SharedCreateAgentRuntimeOption(
    val runtimeId: String,
    val capabilityStatus: String,
    val admissionStatus: String,
    val admissionReason: String?,
    val current: Boolean,
    val availableForNew: Boolean,
    val manageableForCurrentAgent: Boolean,
    val canSelectInThisContext: Boolean,
    val formDefinitionRef: SharedRuntimeFormDefinitionRef?
) {
    val selectable: Boolean
        get() =
            canSelectInThisContext &&
                capabilityStatus == "available" &&
                admissionStatus == "available_for_new" &&
                admissionReason == null &&
                availableForNew &&
                !current
}

internal sealed interface SharedAgentCreateFieldSchema {
    val title: String

    data class StringField(
        override val title: String,
        val minLength: Int?,
        val format: String?,
        val writeOnly: Boolean
    ) : SharedAgentCreateFieldSchema

    data class StringMapField(
        override val title: String
    ) : SharedAgentCreateFieldSchema

    data class BooleanField(
        override val title: String
    ) : SharedAgentCreateFieldSchema
}

internal data class SharedAgentCreateDataSchema(
    val required: Set<String>,
    val properties: Map<String, SharedAgentCreateFieldSchema>
)

internal data class SharedAgentCreateVisibilityRule(
    val pointer: String,
    val dependsOnPointer: String,
    val allowedValues: Set<String>
)

internal data class SharedAgentCreateFieldCopy(
    val label: String,
    val hint: String?,
    val placeholder: String?
)

internal data class SharedAgentCreateUiSchema(
    val order: List<String>,
    val advancedPointers: Set<String>,
    val visibility: List<SharedAgentCreateVisibilityRule>,
    val localization: Map<String, SharedAgentCreateFieldCopy>
)

internal data class SharedAgentCreateFormCapabilities(
    val providerKinds: Set<String>,
    val writeOnlyPointers: Set<String>,
    val forbiddenPointers: Set<String>
)

internal sealed interface SharedAgentCreateFormOptionSourceRef {
    val ref: SharedRuntimeFormDefinitionRef
    val sourceId: String
    val pointer: String

    data class Select(
        override val ref: SharedRuntimeFormDefinitionRef,
        override val sourceId: String,
        override val pointer: String
    ) : SharedAgentCreateFormOptionSourceRef

    data class DependentSelect(
        override val ref: SharedRuntimeFormDefinitionRef,
        override val sourceId: String,
        override val pointer: String,
        val dependsOn: String
    ) : SharedAgentCreateFormOptionSourceRef
}

internal data class SharedAgentCreateFormDefinition(
    val ref: SharedRuntimeFormDefinitionRef,
    val dataSchema: SharedAgentCreateDataSchema,
    val uiSchema: SharedAgentCreateUiSchema,
    val capabilities: SharedAgentCreateFormCapabilities,
    val optionSources: Map<String, SharedAgentCreateFormOptionSourceRef>
)

internal data class SharedAgentCreateFormOption(
    val value: String,
    val label: String,
    val providerKind: String?
)

internal sealed interface SharedAgentCreateFormOptionSource {
    val ref: SharedRuntimeFormDefinitionRef
    val sourceId: String
    val pointer: String

    data class Select(
        override val ref: SharedRuntimeFormDefinitionRef,
        override val sourceId: String,
        override val pointer: String,
        val options: List<SharedAgentCreateFormOption>,
        val defaultValue: String
    ) : SharedAgentCreateFormOptionSource

    data class DependentSelect(
        override val ref: SharedRuntimeFormDefinitionRef,
        override val sourceId: String,
        override val pointer: String,
        val dependsOn: String,
        val optionsByValue: Map<String, List<SharedAgentCreateFormOption>>,
        val defaultValueByValue: Map<String, String>,
        val customValueAllowedByValue: Map<String, Boolean>
    ) : SharedAgentCreateFormOptionSource
}

internal data class SharedResolvedAgentCreateForm(
    val definition: SharedAgentCreateFormDefinition,
    val optionSources: Map<String, SharedAgentCreateFormOptionSource>
)

internal fun SharedResolvedAgentCreateForm.hasConsistentBuiltInSources(): Boolean {
    if (!definition.ref.isSupportedBuiltInPi) return false
    val providerSource = optionSources["provider"] as? SharedAgentCreateFormOptionSource.Select ?: return false
    val modelSource = optionSources["model"] as? SharedAgentCreateFormOptionSource.DependentSelect ?: return false
    if (optionSources.keys != definition.optionSources.keys) return false
    val providersById = providerSource.options.associateBy { it.value }
    if (providersById.size != providerSource.options.size || providersById.keys != modelSource.optionsByValue.keys) return false
    if (providersById.values.mapNotNull { it.providerKind }.toSet() != definition.capabilities.providerKinds) return false
    return providersById.all { (providerId, provider) ->
        val models = modelSource.optionsByValue.getValue(providerId)
        when (provider.providerKind) {
            "preset" ->
                modelSource.customValueAllowedByValue[providerId] == false &&
                    models.isNotEmpty() &&
                    modelSource.defaultValueByValue[providerId] in models.map { it.value }
            "gateway" ->
                modelSource.customValueAllowedByValue[providerId] == true &&
                    models.isEmpty() &&
                    providerId !in modelSource.defaultValueByValue
            else -> false
        }
    }
}

/**
 * Kimi SDK form consistency. The kimi schema carries no provider fields; its
 * single option source is the flat model `select`. Reasoning-effort authority
 * deliberately stays with the live runtime-models catalog
 * ([SharedCreateAgentRuntimeModelOption.supportedReasoningEfforts]), never with
 * this form — the form only proves the server speaks the schema and supplies
 * the exact ref to echo back.
 */
internal fun SharedResolvedAgentCreateForm.hasConsistentKimiSdkSources(): Boolean {
    if (!definition.ref.isSupportedKimiSdk) return false
    if (optionSources.keys != definition.optionSources.keys) return false
    val modelSource = optionSources["model"] as? SharedAgentCreateFormOptionSource.Select ?: return false
    return modelSource.options.isNotEmpty() &&
        modelSource.options.map { it.value }.distinct().size == modelSource.options.size &&
        modelSource.defaultValue in modelSource.options.map { it.value }
}

internal data class SharedBuiltInWriteOnlyIdentity(
    val environmentId: String = "",
    val accountId: String = "",
    val sessionGeneration: String = "",
    val serverId: String = "",
    val machineId: String,
    val runtimeId: String,
    val ref: SharedRuntimeFormDefinitionRef?,
    val providerId: String,
    val baseUrl: String
)

internal fun shouldClearBuiltInWriteOnlySecret(
    previous: SharedBuiltInWriteOnlyIdentity?,
    next: SharedBuiltInWriteOnlyIdentity
): Boolean = previous != null && previous != next

internal fun SharedResolvedAgentCreateForm.buildBuiltInRuntimeConfig(
    providerId: String,
    apiKey: String,
    baseUrl: String,
    model: String,
    envVars: Map<String, String>?,
    supportsImageInput: Boolean = false,
    loadLocalPlugins: Boolean = false
): RuntimeConfig? {
    if (!hasConsistentBuiltInSources()) return null
    val cleanProviderId = providerId.trim()
    val cleanApiKey = apiKey.trim()
    val cleanBaseUrl = baseUrl.trim()
    val cleanModel = model.trim()
    if (cleanProviderId.isBlank() || cleanApiKey.isBlank() || cleanModel.isBlank()) return null
    val providerSource = optionSources["provider"] as? SharedAgentCreateFormOptionSource.Select ?: return null
    val provider = providerSource.options.singleOrNull { it.value == cleanProviderId } ?: return null
    val modelSource = optionSources["model"] as? SharedAgentCreateFormOptionSource.DependentSelect ?: return null
    val providerModels = modelSource.optionsByValue[cleanProviderId] ?: return null
    val providerConfig: RuntimeProviderConfig
    val modelConfig: RuntimeModelConfig
    when (provider.providerKind) {
        "preset" -> {
            if (providerModels.none { it.value == cleanModel }) return null
            providerConfig = RuntimeProviderConfig.Preset(cleanProviderId, cleanApiKey)
            modelConfig = RuntimeModelConfig.Preset(cleanModel)
        }
        "gateway" -> {
            if (modelSource.customValueAllowedByValue[cleanProviderId] != true || cleanBaseUrl.isBlank()) return null
            providerConfig = RuntimeProviderConfig.Gateway(
                providerId = cleanProviderId,
                baseUrl = cleanBaseUrl,
                apiKey = cleanApiKey,
                supportsImageInput = supportsImageInput
            )
            modelConfig = RuntimeModelConfig.Custom(cleanModel)
        }
        else -> return null
    }
    return RuntimeConfig(
        runtime = CREATE_AGENT_BUILTIN_RUNTIME_ID,
        provider = providerConfig,
        model = modelConfig,
        mode = RuntimeModeConfig.Default,
        reasoningEffort = null,
        envVars = envVars,
        loadLocalPlugins = loadLocalPlugins,
        hostUserState = RuntimeHostUserState.Forbidden
    )
}
