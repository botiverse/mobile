package build.raft.app.data

import build.raft.app.data.runtimeform.v2.RuntimeFormV2Access
import build.raft.app.data.runtimeform.v2.RuntimeFormV2Loader
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

internal data class SharedCreateAgentFormStoreState(
    val owner: SharedCreateAgentFormOwner? = null,
    val runtimeCatalogs: Map<SharedCreateAgentMachineFormKey, SharedCreateAgentRuntimeCatalog> = emptyMap(),
    val loadingRuntimeCatalogs: Set<SharedCreateAgentMachineFormKey> = emptySet()
)

internal data class SharedCreateAgentFormOwner(
    val environmentId: String,
    val accountId: String,
    val sessionGeneration: String
)

internal fun sharedCreateAgentFormOwner(
    session: StoredAuthSession,
    environmentId: String,
    loginGeneration: String
): SharedCreateAgentFormOwner? {
    val cleanEnvironmentId = environmentId.trim().trimEnd('/')
    val accountId = session.user.id.trim()
    if (cleanEnvironmentId.isBlank() || accountId.isBlank()) return null
    return SharedCreateAgentFormOwner(cleanEnvironmentId, accountId, loginGeneration)
}

internal class SharedCreateAgentFormStore(
    private val computersApiClient: RaftComputersApiClient,
    private val scope: CoroutineScope,
    private val logger: (String) -> Unit = {},
    private val ownerProvider: suspend () -> SharedCreateAgentFormOwner? = {
        SharedCreateAgentFormOwner("test-environment", "test-account", "test-session")
    },
    private val runtimeFormV2Loader: RuntimeFormV2Loader? = null,
    /** The server runtime catalog (`GET servers/:id/runtimes`); null: the bundled table only. */
    private val serverRuntimeCatalogs: SharedServerRuntimeCatalogRepository? = null,
    /** The last create's Computer and runtime per server; null: nothing is remembered. */
    private val lastConfigs: CreateAgentLastConfigRepository? = null
) {
    /** The runtime of the last agent created on [serverId] from this device, if any. */
    fun rememberedCreateRuntime(serverId: String?): String? = lastConfigs?.read(serverId)?.runtime

    /**
     * Remembers a create that succeeded on [serverId] (web writes after the create, not on submit).
     * An external agent has no Computer or runtime and is not remembered.
     */
    fun rememberCreatedAgent(serverId: String?, machineId: String?, runtime: String?) {
        val machine = machineId?.trim().orEmpty()
        val runtimeId = runtime?.trim().orEmpty()
        if (machine.isEmpty() || runtimeId.isEmpty()) return
        lastConfigs?.write(serverId, CreateAgentLastConfig(machine, runtimeId))
    }

    /**
     * The runtimes [serverId] offers: the server's answer, else its cached answer, else the bundled
     * table. Never fails ([SharedServerRuntimeCatalogRepository]).
     */
    suspend fun loadServerRuntimeCatalog(serverId: String, priority: DataRequestPriority): ServerRuntimeCatalog =
        serverRuntimeCatalogs?.load(serverId, priority) ?: BundledRuntimeCatalog

    /** [serverId]'s cached catalog (else bundled), without a request: what to show before the load lands. */
    suspend fun cachedServerRuntimeCatalog(serverId: String): ServerRuntimeCatalog =
        serverRuntimeCatalogs?.cached(serverId) ?: BundledRuntimeCatalog

    /** Runtime form v2 for this server (the only runtime form), or null without a loader or server. */
    fun runtimeFormV2Access(serverId: String): RuntimeFormV2Access? {
        val loader = runtimeFormV2Loader ?: return null
        val cleanServerId = serverId.trim().takeIf { it.isNotBlank() }?.let(::ServerId) ?: return null
        return RuntimeFormV2Access(loader, cleanServerId)
    }

    private val mutex = Mutex()
    private val _state = MutableStateFlow(SharedCreateAgentFormStoreState())
    val state: StateFlow<SharedCreateAgentFormStoreState> = _state.asStateFlow()

    private val runtimeCatalogLoads = mutableMapOf<SharedCreateAgentMachineFormKey, Deferred<DataResult<SharedCreateAgentRuntimeCatalog>>>()
    private val runtimeCatalogGenerations = mutableMapOf<SharedCreateAgentMachineFormKey, Long>()
    private var currentOwner: SharedCreateAgentFormOwner? = null

    suspend fun reconcileOwner() {
        val owner = ownerProvider()
        mutex.withLock {
            if (owner == null) clearOwnerLocked() else installOwner(owner)
        }
    }

    suspend fun loadRuntimeCatalog(
        serverId: String,
        machineId: String,
        force: Boolean = false
    ): DataResult<SharedCreateAgentRuntimeCatalog> {
        val owner = ownerProvider()
            ?: return clearForMissingOwner("runtime_options_owner_required")
        val key = normalizedMachineKey(serverId, machineId)
            ?: return DataResult.Error(ScreenError("runtime_options_identity_required", retryable = false))
        val deferred = mutex.withLock {
            installOwner(owner)
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
                publishRuntimeCatalog(owner, key, generation, result)
                result
            }.also { runtimeCatalogLoads[key] = it }
        }
        return deferred.await()
    }

    private suspend fun publishRuntimeCatalog(
        owner: SharedCreateAgentFormOwner,
        key: SharedCreateAgentMachineFormKey,
        generation: Long,
        result: DataResult<SharedCreateAgentRuntimeCatalog>
    ) {
        mutex.withLock {
            if (currentOwner == owner && runtimeCatalogGenerations[key] == generation) {
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

    private fun installOwner(owner: SharedCreateAgentFormOwner) {
        if (currentOwner == owner) return
        currentOwner = owner
        runtimeCatalogLoads.clear()
        runtimeCatalogGenerations.clear()
        _state.value = SharedCreateAgentFormStoreState(owner = owner)
    }

    private fun clearOwnerLocked() {
        currentOwner = null
        runtimeCatalogLoads.clear()
        runtimeCatalogGenerations.clear()
        _state.value = SharedCreateAgentFormStoreState()
    }

    private suspend fun <T> clearForMissingOwner(code: String): DataResult<T> {
        mutex.withLock {
            clearOwnerLocked()
        }
        return DataResult.Error(ScreenError(code, retryable = false))
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
