package build.raft.app.data

import build.raft.app.model.ServerId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class SharedCreateAgentMachineFormKey(
    val serverId: String,
    val machineId: String
)

internal data class SharedCreateAgentExactFormKey(
    val serverId: String,
    val machineId: String,
    val ref: SharedRuntimeFormDefinitionRef
)

internal data class SharedCreateAgentFormStoreState(
    val runtimeCatalogs: Map<SharedCreateAgentMachineFormKey, SharedCreateAgentRuntimeCatalog> = emptyMap(),
    val resolvedForms: Map<SharedCreateAgentExactFormKey, SharedResolvedAgentCreateForm> = emptyMap(),
    val loadingRuntimeCatalogs: Set<SharedCreateAgentMachineFormKey> = emptySet(),
    val loadingForms: Set<SharedCreateAgentExactFormKey> = emptySet()
)

internal class SharedCreateAgentFormStore(
    private val computersApiClient: SlockComputersApiClient,
    private val scope: CoroutineScope,
    private val logger: (String) -> Unit = {}
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(SharedCreateAgentFormStoreState())
    val state: StateFlow<SharedCreateAgentFormStoreState> = _state.asStateFlow()

    private val runtimeCatalogLoads = mutableMapOf<SharedCreateAgentMachineFormKey, Deferred<DataResult<SharedCreateAgentRuntimeCatalog>>>()
    private val runtimeCatalogGenerations = mutableMapOf<SharedCreateAgentMachineFormKey, Long>()
    private val formLoads = mutableMapOf<SharedCreateAgentExactFormKey, Deferred<DataResult<SharedResolvedAgentCreateForm>>>()
    private val formGenerations = mutableMapOf<SharedCreateAgentExactFormKey, Long>()

    suspend fun loadRuntimeCatalog(
        serverId: String,
        machineId: String,
        force: Boolean = false
    ): DataResult<SharedCreateAgentRuntimeCatalog> {
        val key = normalizedMachineKey(serverId, machineId)
            ?: return DataResult.Error(ScreenError("runtime_options_identity_required", retryable = false))
        val deferred = mutex.withLock {
            if (!force) {
                _state.value.runtimeCatalogs[key]?.let { return DataResult.Content(it) }
                runtimeCatalogLoads[key]?.let { return@withLock it }
            }
            val generation = runtimeCatalogGenerations.getOrElse(key) { 0L } + 1L
            runtimeCatalogGenerations[key] = generation
            _state.value = _state.value.copy(loadingRuntimeCatalogs = _state.value.loadingRuntimeCatalogs + key)
            scope.async {
                val result = runCatchingPreservingCancellation {
                    computersApiClient.runtimeOptions(ServerId(key.serverId), key.machineId)
                }.getOrElse {
                    DataResult.Error(ScreenError("runtime_options_load_failed", retryable = true))
                }
                publishRuntimeCatalog(key, generation, result)
                result
            }.also { runtimeCatalogLoads[key] = it }
        }
        return deferred.await()
    }

    suspend fun loadExactForm(
        serverId: String,
        machineId: String,
        ref: SharedRuntimeFormDefinitionRef,
        force: Boolean = false
    ): DataResult<SharedResolvedAgentCreateForm> {
        val machineKey = normalizedMachineKey(serverId, machineId)
            ?: return DataResult.Error(ScreenError("runtime_form_identity_required", retryable = false))
        if (!ref.isSupportedBuiltInPi) {
            return DataResult.Error(ScreenError("runtime_form_ref_unsupported", retryable = false))
        }
        val key = SharedCreateAgentExactFormKey(machineKey.serverId, machineKey.machineId, ref)
        val deferred = mutex.withLock {
            if (!force) {
                _state.value.resolvedForms[key]?.let { return DataResult.Content(it) }
                formLoads[key]?.let { return@withLock it }
            }
            val generation = formGenerations.getOrElse(key) { 0L } + 1L
            formGenerations[key] = generation
            _state.value = _state.value.copy(loadingForms = _state.value.loadingForms + key)
            scope.async {
                val result = runCatchingPreservingCancellation {
                    resolveExactForm(key)
                }.getOrElse {
                    DataResult.Error(ScreenError("runtime_form_load_failed", retryable = true))
                }
                publishExactForm(key, generation, result)
                result
            }.also { formLoads[key] = it }
        }
        return deferred.await()
    }

    suspend fun loadSupportedBuiltInForm(
        serverId: String,
        machineId: String,
        force: Boolean = false
    ): DataResult<SharedResolvedAgentCreateForm> {
        val catalog = loadRuntimeCatalog(serverId, machineId, force)
        if (catalog !is DataResult.Content) return catalog.asResolvedFormResult()
        val option = catalog.data.options.singleOrNull { it.runtimeId == CREATE_AGENT_BUILTIN_RUNTIME_ID }
            ?: return unavailableBuiltInForm("runtime_missing")
        val ref = option.formDefinitionRef
            ?: return unavailableBuiltInForm("form_ref_missing")
        if (!option.selectable || !ref.isSupportedBuiltInPi) {
            return unavailableBuiltInForm("runtime_not_selectable")
        }
        return loadExactForm(serverId, machineId, ref, force)
    }

    private suspend fun resolveExactForm(
        key: SharedCreateAgentExactFormKey
    ): DataResult<SharedResolvedAgentCreateForm> {
        val definitionResult = computersApiClient.runtimeFormDefinition(ServerId(key.serverId), key.machineId, key.ref)
        if (definitionResult !is DataResult.Content) return definitionResult.asResolvedFormResult()
        val resolvedSources = linkedMapOf<String, SharedAgentCreateFormOptionSource>()
        for ((sourceId, sourceRef) in definitionResult.data.optionSources) {
            val sourceResult = computersApiClient.runtimeFormOptionSource(
                ServerId(key.serverId),
                key.machineId,
                key.ref,
                sourceRef
            )
            if (sourceResult !is DataResult.Content) return sourceResult.asResolvedFormResult()
            resolvedSources[sourceId] = sourceResult.data
        }
        val resolved = SharedResolvedAgentCreateForm(definitionResult.data, resolvedSources)
        return if (resolved.hasConsistentBuiltInSources()) {
            DataResult.Content(resolved)
        } else {
            DataResult.Error(ScreenError("runtime_form_sources_contract_mismatch", retryable = false))
        }
    }

    private suspend fun publishRuntimeCatalog(
        key: SharedCreateAgentMachineFormKey,
        generation: Long,
        result: DataResult<SharedCreateAgentRuntimeCatalog>
    ) {
        mutex.withLock {
            if (runtimeCatalogGenerations[key] == generation) {
                runtimeCatalogLoads.remove(key)
                _state.value = _state.value.copy(loadingRuntimeCatalogs = _state.value.loadingRuntimeCatalogs - key)
                if (result is DataResult.Content) {
                    _state.value = _state.value.copy(
                        runtimeCatalogs = _state.value.runtimeCatalogs + (key to result.data)
                    )
                }
            }
        }
        logResult("runtime_options", key.serverId, key.machineId, result)
    }

    private suspend fun publishExactForm(
        key: SharedCreateAgentExactFormKey,
        generation: Long,
        result: DataResult<SharedResolvedAgentCreateForm>
    ) {
        mutex.withLock {
            if (formGenerations[key] == generation) {
                formLoads.remove(key)
                _state.value = _state.value.copy(loadingForms = _state.value.loadingForms - key)
                if (result is DataResult.Content) {
                    _state.value = _state.value.copy(
                        resolvedForms = _state.value.resolvedForms + (key to result.data)
                    )
                }
            }
        }
        logResult("runtime_form", key.serverId, key.machineId, result)
    }

    private fun logResult(domain: String, serverId: String, machineId: String, result: DataResult<*>) {
        val outcome = when (result) {
            is DataResult.Content -> "content"
            is DataResult.Empty -> "empty"
            is DataResult.Error -> "error:${result.error.code}"
            is DataResult.NoPermission -> "no_permission:${result.blocker.code}"
        }
        logger("create_agent_form_load domain=$domain serverId=$serverId machineId=$machineId result=$outcome")
    }
}

private fun normalizedMachineKey(serverId: String, machineId: String): SharedCreateAgentMachineFormKey? {
    val cleanServerId = serverId.trim()
    val cleanMachineId = machineId.trim()
    return if (cleanServerId.isBlank() || cleanMachineId.isBlank()) null
    else SharedCreateAgentMachineFormKey(cleanServerId, cleanMachineId)
}

private fun unavailableBuiltInForm(reason: String): DataResult.Error =
    DataResult.Error(ScreenError("runtime_form_unavailable_$reason", retryable = false))

private fun <T> DataResult<T>.asResolvedFormResult(): DataResult<SharedResolvedAgentCreateForm> =
    when (this) {
        is DataResult.Content -> error("Content must be mapped before conversion")
        is DataResult.Empty -> this
        is DataResult.Error -> this
        is DataResult.NoPermission -> this
    }
