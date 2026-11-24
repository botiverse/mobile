package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import build.raft.app.model.ServerId
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/*
 * Server model-label catalog: `GET /api/servers/:id/model-label-catalog`
 * (botiverse/slock #15780, task #wg-mobile-sync:#193, #triage:#548).
 *
 * The daemon reports each runtime's model list (`id` + runtime's own display `label`, e.g.
 * `fable` -> `Fable 5.2`); the server stores it per machine and serves it to any member of the
 * server so author rows, agent profiles, preview cards, members lists, and computer pages resolve
 * the same model name as the runtime-config dropdown.
 *
 * Wire shape (`packages/shared/src/runtimeModelCatalog.ts` `ServerModelLabelCatalog`):
 * `{ machines: { [machineId]: { runtimes: { [runtimeId]: { models: [{ id, label }], updatedAt } } } } }`
 */

private val modelLabelCatalogJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

/** C0/C1 control chars (`packages/shared/src/runtimeModelCatalog.ts` `CONTROL_CHARS`). */
private val ModelLabelControlCharRegex = Regex("[\\u0000-\\u001F\\u007F-\\u009F]")

private const val MODEL_LABEL_CATALOG_MAX_ENTRIES = 500
private const val MODEL_LABEL_CATALOG_MAX_ID_LENGTH = 200
private const val MODEL_LABEL_CATALOG_MAX_LABEL_LENGTH = 200

/**
 * One server's model-label catalog: `machineId -> runtimeId -> (modelId -> displayLabel)`.
 * Pure display names reported by each machine's daemon.
 */
@RaftCoreInternalApi data class ServerModelLabelCatalog(
    val machines: Map<String, Map<String, Map<String, String>>> = emptyMap()
) {
    /** Web `modelLabelFromCatalog`: null when the catalog has no entry for this (machine, runtime, model). */
    fun label(machineId: String?, runtimeId: String?, modelId: String?): String? {
        val machine = machineId?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val runtime = runtimeId?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val model = modelId?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return machines[machine]?.get(runtime)?.get(model)
    }
}

/**
 * Defensive shape check & decode matching web `isServerModelLabelCatalog` and shared
 * `safeParseRuntimeModelCatalogEntries`. Returns null for non-catalog JSON so a malformed
 * payload never replaces a good catalog.
 */
@RaftCoreInternalApi fun decodeServerModelLabelCatalog(body: String): ServerModelLabelCatalog? {
    val root =
        try {
            modelLabelCatalogJson.parseToJsonElement(body)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return null
        } as? JsonObject ?: return null
    val rawMachines = root["machines"] as? JsonObject ?: return null
    val machines = LinkedHashMap<String, Map<String, Map<String, String>>>()
    for ((machineId, machineElement) in rawMachines) {
        val machineObj = machineElement as? JsonObject ?: return null
        val rawRuntimes = machineObj["runtimes"] as? JsonObject ?: return null
        val cleanMachineId = machineId.trim()
        if (cleanMachineId.isBlank()) continue
        val runtimes = LinkedHashMap<String, Map<String, String>>()
        for ((runtimeId, runtimeElement) in rawRuntimes) {
            val runtimeObj = runtimeElement as? JsonObject ?: continue
            val cleanRuntimeId = runtimeId.trim()
            if (cleanRuntimeId.isBlank()) continue
            val rawModels = runtimeObj["models"] as? JsonArray ?: continue
            if (rawModels.size > MODEL_LABEL_CATALOG_MAX_ENTRIES) continue
            val byId = LinkedHashMap<String, String>()
            for (modelElement in rawModels) {
                val modelObj = modelElement as? JsonObject ?: continue
                val id = (modelObj["id"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim().orEmpty()
                if (id.isEmpty() || id.length > MODEL_LABEL_CATALOG_MAX_ID_LENGTH || ModelLabelControlCharRegex.containsMatchIn(id)) continue
                val rawLabel = (modelObj["label"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull.orEmpty()
                val label = rawLabel.replace(ModelLabelControlCharRegex, "").trim()
                if (label.isEmpty() || label.length > MODEL_LABEL_CATALOG_MAX_LABEL_LENGTH) continue
                byId[id] = label
            }
            if (byId.isNotEmpty()) runtimes[cleanRuntimeId] = byId
        }
        if (runtimes.isNotEmpty()) machines[cleanMachineId] = runtimes
    }
    return ServerModelLabelCatalog(machines = machines)
}

/**
 * Process-wide reactive registry of per-server model-label catalogs.
 *
 * Backed solely by a [MutableStateFlow] (no top-level Compose `mutableStateOf`) so lazy cold-start
 * initialization off the main thread can never create snapshot state with a snapshot ID newer than
 * an in-flight `Recomposer` `MutableSnapshot` (Hands `25b91174`, `#proj-mobile-release:#35`).
 * Composables observe updates via [observeCatalog] / [observeLabel] and store-driven resolvers via
 * [catalogFlow].
 */
@RaftCoreInternalApi class RaftModelLabelCatalogRegistry {
    private val stateFlow = MutableStateFlow<Map<String, ServerModelLabelCatalog>>(emptyMap())
    private val publishLock = SynchronizedObject()

    fun publish(serverId: String, catalog: ServerModelLabelCatalog) {
        val key = serverId.trim()
        if (key.isBlank()) return
        synchronized(publishLock) {
            val current = stateFlow.value
            if (current[key] != catalog) {
                stateFlow.value = current + (key to catalog)
            }
        }
    }

    fun clear() {
        synchronized(publishLock) {
            stateFlow.value = emptyMap()
        }
    }

    /** Snapshot-free immediate read of [serverId]'s catalog. */
    fun catalog(serverId: String?): ServerModelLabelCatalog? = currentCatalog(serverId)

    /** Non-snapshot read (safe outside composition). */
    fun currentCatalog(serverId: String?): ServerModelLabelCatalog? =
        serverId?.trim()?.takeIf { it.isNotBlank() }?.let { stateFlow.value[it] }

    /** Snapshot-free label lookup for `(serverId, machineId, runtimeId, modelId)`. */
    fun label(serverId: String?, machineId: String?, runtimeId: String?, modelId: String?): String? =
        currentCatalog(serverId)?.label(machineId, runtimeId, modelId)

    /** Reactive flow of [serverId]'s catalog for store-driven resolvers (message author rows). */
    fun catalogFlow(serverId: String): Flow<ServerModelLabelCatalog?> {
        val key = serverId.trim()
        return stateFlow.map { it[key] }.distinctUntilChanged()
    }

    /** Composition-safe reactive observation of [serverId]'s catalog. */
    @Composable
    fun observeCatalog(serverId: String?): ServerModelLabelCatalog? {
        val serverKey = serverId?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return key(this, serverKey) {
            val flow = remember { catalogFlow(serverKey) }
            flow.collectAsState(initial = currentCatalog(serverKey)).value
        }
    }

    /** Composition-safe reactive label lookup for `(serverId, machineId, runtimeId, modelId)`. */
    @Composable
    fun observeLabel(serverId: String?, machineId: String?, runtimeId: String?, modelId: String?): String? =
        observeCatalog(serverId)?.label(machineId, runtimeId, modelId)
}

/** The process-wide registry every model-label surface reads. */
@RaftCoreInternalApi val RaftModelLabelCatalogs: RaftModelLabelCatalogRegistry = RaftModelLabelCatalogRegistry()

// ── Transport & repository ──

@RaftCoreInternalApi interface RaftServerModelLabelCatalogApi {
    suspend fun modelLabelCatalog(serverId: ServerId, priority: DataRequestPriority): HomeNetworkResponse
}

@RaftCoreInternalApi class RaftServerModelLabelCatalogNetworkApi(
    private val networkClient: RaftNetworkClient
) : RaftServerModelLabelCatalogApi {
    override suspend fun modelLabelCatalog(serverId: ServerId, priority: DataRequestPriority): HomeNetworkResponse =
        networkClient.request(
            priority = priority,
            path = "servers/${serverId.value}/model-label-catalog",
            serverIdOverride = serverId
        )
}

/**
 * Loads `GET /api/servers/:id/model-label-catalog` per server with in-flight deduplication,
 * 5-minute cache window, and 60-second failure retry backoff (web `modelLabelCatalogStore.ts`).
 *
 * A failed or malformed fetch never replaces a previously loaded catalog.
 * [refreshAfterLiveDetect] force-reloads only when a catalog is already held for that server
 * (web `refreshModelLabelCatalogAfterLiveDetect`, slock #15780).
 */
@RaftCoreInternalApi class SharedServerModelLabelCatalogRepository(
    private val api: RaftServerModelLabelCatalogApi,
    private val scope: CoroutineScope,
    private val registry: RaftModelLabelCatalogRegistry = RaftModelLabelCatalogs,
    private val logger: (String) -> Unit = {},
    private val timeSource: TimeSource = TimeSource.Monotonic
) {
    private val mutex = Mutex()
    private val inFlight = mutableMapOf<String, Deferred<ServerModelLabelCatalog?>>()
    private val fetchedAt = mutableMapOf<String, TimeMark>()
    private val lastAttemptAt = mutableMapOf<String, TimeMark>()

    suspend fun load(
        serverId: String,
        priority: DataRequestPriority = DataRequestPriority.Background,
        force: Boolean = false
    ): ServerModelLabelCatalog? {
        val key = serverId.trim()
        if (key.isBlank()) return null
        val request =
            mutex.withLock {
                val existing = registry.currentCatalog(key)
                val lastFetched = fetchedAt[key]
                if (!force && existing != null && lastFetched != null && lastFetched.elapsedNow() < CACHE_WINDOW) {
                    return existing
                }
                inFlight[key]?.takeIf { it.isActive }?.let { return@withLock it }
                val lastAttempt = lastAttemptAt[key]
                if (!force && lastAttempt != null && lastAttempt.elapsedNow() < RETRY_BACKOFF) {
                    return existing
                }
                lastAttemptAt[key] = timeSource.markNow()
                var self: Deferred<ServerModelLabelCatalog?>? = null
                val started =
                    scope.async(start = CoroutineStart.LAZY) {
                        try {
                            fetch(key, priority)
                        } finally {
                            withContext(NonCancellable) {
                                mutex.withLock {
                                    if (inFlight[key] === self) inFlight.remove(key)
                                }
                            }
                        }
                    }
                self = started
                inFlight[key] = started
                started.start()
                started
            }
        return request.await()
    }

    /**
     * Web `refreshModelLabelCatalogAfterLiveDetect` (slock #15780): a live model detect just
     * upserted fresh labels on the server; force-reload our copy only when we already hold one
     * for [serverId], never starting a fetch from nothing.
     */
    fun refreshAfterLiveDetect(serverId: String?) {
        val key = serverId?.trim()?.takeIf { it.isNotBlank() } ?: return
        if (registry.currentCatalog(key) == null) return
        scope.launch { load(key, priority = DataRequestPriority.Background, force = true) }
    }

    private suspend fun fetch(serverId: String, priority: DataRequestPriority): ServerModelLabelCatalog? {
        val response =
            try {
                api.modelLabelCatalog(ServerId(serverId), priority)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                logger("server_model_label_catalog_failed server=$serverId reason=transport")
                return registry.currentCatalog(serverId)
            }
        val body = response.takeIf { it.isSuccessful }?.bodyText?.takeIf { it.isNotBlank() }
        val decoded = body?.let(::decodeServerModelLabelCatalog)
        if (decoded != null) {
            mutex.withLock { fetchedAt[serverId] = timeSource.markNow() }
            registry.publish(serverId, decoded)
            logger("server_model_label_catalog server=$serverId machines=${decoded.machines.size}")
            return decoded
        }
        logger("server_model_label_catalog_failed server=$serverId status=${response.code} decoded=false")
        return registry.currentCatalog(serverId)
    }

    private companion object {
        /** web `MODEL_LABEL_CATALOG_CACHE_MS`. */
        val CACHE_WINDOW = 5.minutes

        /** web `MODEL_LABEL_CATALOG_RETRY_MS`. */
        val RETRY_BACKOFF = 1.minutes
    }
}

/**
 * Ensures [serverId]'s model-label catalog is loaded into [RaftModelLabelCatalogs] for labels
 * rendered outside the shell's initial server-selection pass (no-op in isolated previews/tests).
 */
@Composable
@RaftCoreInternalApi fun EnsureServerModelLabelCatalog(serverId: String?) {
    val clean = serverId?.trim().orEmpty()
    LaunchedEffect(clean) {
        if (clean.isBlank()) return@LaunchedEffect
        RaftDataStoreRegistry.currentOrNull()?.ensureServerModelLabelCatalog(clean)
    }
}
