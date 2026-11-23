package build.raft.app.data

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import build.raft.app.ResolvedRuntimeRaftCapabilities
import build.raft.app.model.RUNTIMES
import build.raft.app.model.RUNTIME_MODELS
import build.raft.app.model.RuntimeInfo
import build.raft.app.model.RuntimeModelInfo
import build.raft.app.model.ServerId
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/*
 * Server runtime catalog: `GET /api/servers/:id/runtimes`.
 *
 * Contract: botiverse/slock `docs/contracts/server-runtime-catalog.md`, verified at f5fe2ca864
 * (PR #12065, branch agent/feat/server-runtime-catalog):
 * - route `packages/server/src/routes/servers.ts` (`serverRouter.get("/:id/runtimes")`), session
 *   auth, any member of the server; a non-member is refused (403 from the server-scope middleware,
 *   404 from the route's own role check);
 * - projection `packages/server/src/services/serverRuntimeCatalogService.ts`;
 * - types `packages/shared/src/index.ts` `ServerRuntimeCatalog` / `ServerRuntimeCatalogEntry` /
 *   `ServerRuntimeCatalogForm` / `ServerRuntimeCatalogModelsSource`.
 *
 * `{ catalogVersion, runtimes: [{ id, displayName, abbreviation, binary, supported, experimental,
 * deprecated, oarOnly, models: [{ id, label }], modelsSource: "static" | "fallback",
 * forms: [{ kind: "generic" | "v1" | "v2", schemaVersion? }] }] }`
 *
 * Additive only (catalogVersion goes up on an addition). The decode is lenient on purpose: an
 * unknown key, an unknown enum value or an unknown form kind never fails the response (1.12/1.13
 * failed the whole runtime-options decode on one new field); a single malformed row is dropped,
 * the rest still read.
 */

private val serverRuntimeCatalogJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
}

@Serializable
private data class ServerRuntimeCatalogWire(
    val catalogVersion: Int = 0,
    // Required: any other JSON object a 200 can carry (`{}`, `{"error":…}`, a proxy's page) is not a
    // catalog. Rows decode one by one ([decodeServerRuntimeCatalog]): one bad row must not drop the others.
    val runtimes: List<JsonElement>
)

@Serializable
private data class ServerRuntimeCatalogEntryWire(
    val id: String,
    val displayName: String = "",
    val abbreviation: String = "",
    val binary: String = "",
    // Missing means "not said": never offer what the server did not say it supports.
    val supported: Boolean = false,
    val experimental: Boolean = false,
    val deprecated: Boolean = false,
    val oarOnly: Boolean = false,
    val models: List<JsonElement> = emptyList(),
    // A string, not an enum: a future source must read as Unknown, not fail the row.
    val modelsSource: String? = null,
    val forms: List<JsonElement> = emptyList(),
    // Additive in catalogVersion >= 2 (slock #16178): server-default Raft capabilities (`source: "default"`).
    val raftCapabilities: JsonElement? = null
)

@Serializable
private data class ServerRuntimeCatalogModelWire(val id: String, val label: String = "")

@Serializable
private data class ServerRuntimeCatalogFormWire(val kind: String, val schemaVersion: String? = null)

/** `modelsSource`: whether [RuntimeCatalogEntry.models] is the whole list or the Computer's live detection decides. */
internal enum class RuntimeCatalogModelsSource {
    /** The list is the runtime's closed source: the full list. */
    Static,

    /** The Computer's live detection wins; the list is the backup and may be empty. */
    Fallback,

    /** A value this app does not know: treated as [Fallback] (live detection first). */
    Unknown
}

/** One `forms` entry. A kind this app does not know stays [Unknown]: skipped, never mistaken for another. */
internal sealed interface RuntimeCatalogForm {
    data object Generic : RuntimeCatalogForm

    data class V1(val schemaVersion: String) : RuntimeCatalogForm

    data object V2 : RuntimeCatalogForm

    data class Unknown(val kind: String) : RuntimeCatalogForm
}

internal data class RuntimeCatalogEntry(
    val id: String,
    val displayName: String,
    val abbreviation: String = "",
    val binary: String = "",
    val supported: Boolean = true,
    val experimental: Boolean = false,
    val deprecated: Boolean = false,
    val oarOnly: Boolean = false,
    val models: List<RuntimeModelInfo> = emptyList(),
    val modelsSource: RuntimeCatalogModelsSource = RuntimeCatalogModelsSource.Fallback,
    val forms: List<RuntimeCatalogForm> = emptyList(),
    /**
     * Server-default Raft capabilities resolved from this server's host-route switches
     * (`GET /api/servers/:id/runtimes`, catalogVersion >= 2, `source: "default"`, slock #16178);
     * null on older servers (`catalogVersion == 1`) or the bundled table.
     */
    val raftCapabilities: ResolvedRuntimeRaftCapabilities? = null
)

/** Where a [ServerRuntimeCatalog] came from: the server now, its last answer on disk, or the bundled table. */
internal enum class RuntimeCatalogSource { Server, Cache, Bundled }

internal data class ServerRuntimeCatalog(
    val catalogVersion: Int,
    val runtimes: List<RuntimeCatalogEntry>,
    val source: RuntimeCatalogSource
) {
    fun entry(runtimeId: String): RuntimeCatalogEntry? = runtimes.firstOrNull { it.id == runtimeId.trim() }
}

/**
 * The lenient decode of a catalog body, or null when it is not a catalog: not an object, no
 * `runtimes`, or a `catalogVersion` below 1 (the contract starts at 1). Null is never cached nor
 * published, so a stray JSON answer cannot empty the runtime list.
 */
internal fun decodeServerRuntimeCatalog(body: String, source: RuntimeCatalogSource): ServerRuntimeCatalog? {
    val wire =
        try {
            serverRuntimeCatalogJson.decodeFromString(ServerRuntimeCatalogWire.serializer(), body)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return null
        }
    if (wire.catalogVersion < 1) return null
    val runtimes =
        wire.runtimes
            .mapNotNull { element -> element.decodeOrNull(ServerRuntimeCatalogEntryWire.serializer()) }
            .filter { it.id.isNotBlank() }
            .distinctBy { it.id.trim() }
            .map { it.toDomain() }
    return ServerRuntimeCatalog(catalogVersion = wire.catalogVersion, runtimes = runtimes, source = source)
}

private fun <T> JsonElement.decodeOrNull(serializer: kotlinx.serialization.KSerializer<T>): T? =
    try {
        serverRuntimeCatalogJson.decodeFromJsonElement(serializer, this)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

private fun ServerRuntimeCatalogEntryWire.toDomain(): RuntimeCatalogEntry {
    val cleanId = id.trim()
    return RuntimeCatalogEntry(
        id = cleanId,
        displayName = displayName.trim().ifBlank { cleanId },
        abbreviation = abbreviation.trim(),
        binary = binary.trim(),
        supported = supported,
        experimental = experimental,
        deprecated = deprecated,
        oarOnly = oarOnly,
        models =
            models
                .mapNotNull { it.decodeOrNull(ServerRuntimeCatalogModelWire.serializer()) }
                .filter { it.id.isNotBlank() }
                .map { RuntimeModelInfo(id = it.id, label = it.label.ifBlank { it.id }) },
        modelsSource =
            when (modelsSource) {
                "static" -> RuntimeCatalogModelsSource.Static
                "fallback" -> RuntimeCatalogModelsSource.Fallback
                else -> RuntimeCatalogModelsSource.Unknown
            },
        forms = forms.mapNotNull { it.decodeOrNull(ServerRuntimeCatalogFormWire.serializer())?.toDomain() },
        raftCapabilities = decodeResolvedRuntimeRaftCapabilities(raftCapabilities)
    )
}

private fun ServerRuntimeCatalogFormWire.toDomain(): RuntimeCatalogForm =
    when (kind) {
        "generic" -> RuntimeCatalogForm.Generic
        // A v1 entry without its schemaVersion names no form this app can load.
        "v1" -> schemaVersion?.trim()?.takeIf { it.isNotBlank() }?.let(RuntimeCatalogForm::V1) ?: RuntimeCatalogForm.Unknown("v1")
        "v2" -> RuntimeCatalogForm.V2
        else -> RuntimeCatalogForm.Unknown(kind)
    }

// ── Which forms this app can configure ──

/**
 * The runtime forms this app can create and switch an agent with. Today only runtime form v2
 * (`.../runtime-forms/v2/:runtimeId`): v1 and the generic payload were retired with task #8
 * (`SharedCreateAgentDialog` sends only `runtimeFormV2`), so neither is listed here. A runtime
 * whose `forms` holds none of these asks for an app update instead of a half form.
 */
internal object MobileRuntimeForms {
    val supportsGeneric: Boolean = false
    val supportedV1SchemaVersions: Set<String> = emptySet()
    val supportsV2: Boolean = true

    fun supports(form: RuntimeCatalogForm): Boolean =
        when (form) {
            RuntimeCatalogForm.Generic -> supportsGeneric
            is RuntimeCatalogForm.V1 -> form.schemaVersion in supportedV1SchemaVersions
            RuntimeCatalogForm.V2 -> supportsV2
            is RuntimeCatalogForm.Unknown -> false
        }
}

/** At least one of the entry's forms is one this app knows; never inferred from a missing entry. */
internal fun RuntimeCatalogEntry.hasMobileForm(isSupported: (RuntimeCatalogForm) -> Boolean = MobileRuntimeForms::supports): Boolean =
    forms.any(isSupported)

/** A runtime a new agent may be given here: supported, not deprecated, with a form this app knows. */
internal fun RuntimeCatalogEntry.creatableHere(isSupported: (RuntimeCatalogForm) -> Boolean = MobileRuntimeForms::supports): Boolean =
    supported && !deprecated && hasMobileForm(isSupported)

/**
 * The model list to offer for this runtime: `static` is the full list (the Computer's detection
 * does not add to it); `fallback` (and an unknown source) lets the Computer's [liveModels] win
 * and keeps this list as the backup, which may be empty.
 */
internal fun RuntimeCatalogEntry.modelsFor(liveModels: List<RuntimeModelInfo>?): List<RuntimeModelInfo> =
    when (modelsSource) {
        RuntimeCatalogModelsSource.Static -> models
        RuntimeCatalogModelsSource.Fallback, RuntimeCatalogModelsSource.Unknown -> liveModels?.takeIf { it.isNotEmpty() } ?: models
    }

// ── The bundled fallback ──

/**
 * The bundled table as a catalog: the last resort when neither the server nor its cache answers.
 * Its forms are today's behaviour (runtime form v2, the runtime-options marker decides per
 * Computer), stated explicitly rather than inferred.
 */
internal val BundledRuntimeCatalog: ServerRuntimeCatalog by lazy {
    ServerRuntimeCatalog(
        catalogVersion = 0,
        runtimes = RUNTIMES.map { it.toBundledEntry() },
        source = RuntimeCatalogSource.Bundled
    )
}

/** slock `STATIC_RUNTIME_MODEL_SOURCE_IDS`: the runtimes whose bundled models are their closed source. */
private val StaticBundledModelSources: Set<String> by lazy { setOf("claude", "copilot", "gemini") }

private fun RuntimeInfo.toBundledEntry(): RuntimeCatalogEntry =
    RuntimeCatalogEntry(
        id = id,
        displayName = displayName,
        abbreviation = abbreviation,
        binary = binary,
        supported = supported,
        experimental = experimental,
        deprecated = deprecated,
        oarOnly = id == "qwen" || id == "qoder" || id == "dsh",
        models = RUNTIME_MODELS[id].orEmpty(),
        modelsSource = if (id in StaticBundledModelSources) RuntimeCatalogModelsSource.Static else RuntimeCatalogModelsSource.Fallback,
        forms = listOf(RuntimeCatalogForm.V2)
    )

// ── Names ──

/**
 * The catalogs read in this process, for naming a runtime anywhere (subtitles, chips, profile
 * cards). Backed by a [MutableStateFlow] rather than a top-level Compose `mutableStateOf`:
 * initializing a process-wide `mutableStateOf` lazily on a background thread while the main thread
 * holds an open `Recomposer` `MutableSnapshot` creates the state in a newer snapshot ID and throws
 * `IllegalStateException: Reading a state that was created after the snapshot was taken` (Hands
 * `25b91174`, `#proj-mobile-release:#35`). Composables subscribe via [observeCatalog] /
 * [observeCatalogs], which collect inside their own composition snapshot.
 *
 * A runtime's lifecycle (deprecated, experimental) is a per-server answer (Antigravity is offered
 * again where the server says so): [entry] reads only the catalog of the server asked for, else the
 * bundled table, never another server's catalog (web `useRuntimeDeprecatedHere`: the current
 * server's catalog, else the bundled one). Only a bare name ([displayNameEntry]) may come from any
 * catalog read here when neither the server asked for nor the bundled table knows the runtime.
 */
internal class RaftRuntimeCatalogNameRegistry {
    private val catalogsFlow = MutableStateFlow<Map<String, ServerRuntimeCatalog>>(emptyMap())

    // Fetches for different servers publish in parallel off main: the read-modify-write of the map
    // is one step under this lock, so neither server's catalog is lost.
    private val publishLock = SynchronizedObject()

    fun publish(serverId: String, catalog: ServerRuntimeCatalog) {
        val key = serverId.trim()
        if (key.isBlank()) return
        synchronized(publishLock) {
            val current = catalogsFlow.value
            if (current[key] != catalog) catalogsFlow.value = current + (key to catalog)
        }
    }

    fun clear() {
        synchronized(publishLock) { catalogsFlow.value = emptyMap() }
    }

    /** [serverId]'s entry for [runtimeId], else (no server, or a catalog not read yet) the bundled one. */
    fun entry(runtimeId: String, serverId: String? = null): RuntimeCatalogEntry? {
        val id = runtimeId.trim()
        val server = serverId?.trim()?.takeIf { it.isNotBlank() }
        return server?.let { catalogsFlow.value[it]?.entry(id) } ?: BundledRuntimeCatalog.entry(id)
    }

    /** For a name only: [entry], else the first catalog read here that lists [runtimeId]. */
    fun displayNameEntry(runtimeId: String, serverId: String? = null): RuntimeCatalogEntry? =
        entry(runtimeId, serverId) ?: runtimeId.trim().let { id -> catalogsFlow.value.values.firstNotNullOfOrNull { it.entry(id) } }

    fun catalog(serverId: String?): ServerRuntimeCatalog? = serverId?.trim()?.let { catalogsFlow.value[it] }

    /** Reactive flow of [serverId]'s catalog. */
    fun catalogFlow(serverId: String): Flow<ServerRuntimeCatalog?> {
        val key = serverId.trim()
        return catalogsFlow.map { it[key] }.distinctUntilChanged()
    }

    /** Composition-safe reactive observation of every server catalog read in this process. */
    @Composable
    fun observeCatalogs(): Map<String, ServerRuntimeCatalog> = catalogsFlow.collectAsState().value

    /** Composition-safe reactive observation of [serverId]'s catalog only. */
    @Composable
    fun observeCatalog(serverId: String?): ServerRuntimeCatalog? {
        val serverKey = serverId?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return key(this, serverKey) {
            val flow = remember { catalogFlow(serverKey) }
            flow.collectAsState(initial = catalog(serverKey)).value
        }
    }
}

/** The process-wide registry every runtime name on screen reads. */
internal val RaftRuntimeCatalogNames: RaftRuntimeCatalogNameRegistry = RaftRuntimeCatalogNameRegistry()

/** The name of [runtimeId]: [serverId]'s catalog → bundled table → any catalog read → the id itself. */
internal fun runtimeCatalogDisplayName(runtimeId: String, serverId: String? = null): String =
    RaftRuntimeCatalogNames.displayNameEntry(runtimeId, serverId)?.displayName ?: runtimeId

/** Composition-reactive counterpart of [runtimeCatalogDisplayName]. */
@Composable
internal fun observeRuntimeCatalogDisplayName(runtimeId: String, serverId: String? = null): String {
    val catalogs = RaftRuntimeCatalogNames.observeCatalogs()
    val id = runtimeId.trim()
    val server = serverId?.trim()?.takeIf { it.isNotBlank() }
    val resolved =
        server?.let { catalogs[it]?.entry(id) }
            ?: BundledRuntimeCatalog.entry(id)
            ?: catalogs.values.firstNotNullOfOrNull { it.entry(id) }
    return resolved?.displayName ?: runtimeId
}

/** Deprecated on [serverId] (its catalog, else the bundled table); no server: the bundled table. */
internal fun runtimeCatalogIsDeprecated(runtimeId: String, serverId: String?): Boolean =
    RaftRuntimeCatalogNames.entry(runtimeId, serverId)?.deprecated == true

/** Composition-reactive counterpart of [runtimeCatalogIsDeprecated]. */
@Composable
internal fun observeRuntimeCatalogIsDeprecated(runtimeId: String, serverId: String?): Boolean {
    val catalog = RaftRuntimeCatalogNames.observeCatalog(serverId)
    val id = runtimeId.trim()
    val entry = catalog?.entry(id) ?: BundledRuntimeCatalog.entry(id)
    return entry?.deprecated == true
}

// ── Transport, cache, repository ──

internal interface RaftServerRuntimeCatalogApi {
    suspend fun runtimes(serverId: ServerId, priority: DataRequestPriority): HomeNetworkResponse
}

internal class RaftServerRuntimeCatalogNetworkApi(private val networkClient: RaftNetworkClient) : RaftServerRuntimeCatalogApi {
    override suspend fun runtimes(serverId: ServerId, priority: DataRequestPriority): HomeNetworkResponse =
        networkClient.request(
            priority = priority,
            path = "servers/${serverId.value}/runtimes",
            serverIdOverride = serverId
        )
}

internal const val SERVER_RUNTIME_CATALOG_CACHE_KEY_PREFIX = "serverRuntimeCatalog.v1:"

/**
 * The last catalog body per (environment, account, server), as the server sent it: read back
 * through the same lenient decode, so a field this build does not know survives the round trip.
 */
internal class ServerRuntimeCatalogCache(private val store: SharedPreferenceStore) {
    fun read(scope: String, serverId: String): ServerRuntimeCatalog? =
        store.getString(key(scope, serverId))?.let { decodeServerRuntimeCatalog(it, RuntimeCatalogSource.Cache) }

    fun write(scope: String, serverId: String, body: String) {
        store.putString(key(scope, serverId), body)
    }

    private fun key(scope: String, serverId: String): String = "$SERVER_RUNTIME_CATALOG_CACHE_KEY_PREFIX$scope|${serverId.trim()}"
}

/**
 * Loads a server's catalog and never fails: the server's answer (cached on success), else the
 * last cached answer for this server, else the bundled table. One request per server in flight.
 * Every catalog it returns is published to [RaftRuntimeCatalogNames].
 */
internal class SharedServerRuntimeCatalogRepository(
    private val api: RaftServerRuntimeCatalogApi,
    /**
     * Where the request runs: the repository's scope, not the caller's, so a page that closes
     * mid-request cancels only its own wait, never another page waiting on the same request.
     */
    private val scope: CoroutineScope,
    private val cache: ServerRuntimeCatalogCache?,
    /** `environment|account` of the session, or null without one (nothing is cached then). */
    private val scopeProvider: suspend () -> String?,
    private val names: RaftRuntimeCatalogNameRegistry = RaftRuntimeCatalogNames,
    private val logger: (String) -> Unit = {},
    private val timeSource: TimeSource = TimeSource.Monotonic
) {
    private val mutex = Mutex()
    private val inFlight = mutableMapOf<String, Deferred<ServerRuntimeCatalog>>()

    /** When [ensureLoaded] last asked each server (answered or not). */
    private val ensuredAt = mutableMapOf<String, TimeMark>()

    /** The cached catalog of [serverId] (else bundled), without a request. */
    suspend fun cached(serverId: String): ServerRuntimeCatalog {
        val scope = scopeProvider()
        val catalog = scope?.let { cache?.read(it, serverId) } ?: BundledRuntimeCatalog
        if (catalog.source == RuntimeCatalogSource.Cache) names.publish(serverId, catalog)
        return catalog
    }

    /**
     * One request per server in flight; every caller awaits it. A 403/404 or any other failure is
     * one request answered from the cache or the bundled table, never retried here.
     */
    suspend fun load(serverId: String, priority: DataRequestPriority): ServerRuntimeCatalog {
        val key = serverId.trim()
        if (key.isBlank()) return BundledRuntimeCatalog
        val request =
            mutex.withLock {
                inFlight[key] ?: scope.async(start = CoroutineStart.LAZY) { fetch(key, priority) }.also { started ->
                    inFlight[key] = started
                    started.invokeOnCompletion {
                        // Not suspending: drop the entry if it is still this request's.
                        scope.launch { mutex.withLock { if (inFlight[key] === started) inFlight.remove(key) } }
                    }
                    started.start()
                }
            }
        return request.await()
    }

    /**
     * For a label that names a runtime (a row, a chip, a card; web `useServerRuntimeCatalog`, one
     * shared read per server, a settled answer reused for a minute): publishes [serverId]'s cached
     * answer at once, then asks the server. Every other call within [ENSURE_REUSE] (each row of a
     * list) is free: it neither waits nor asks. A server switch refreshes through [load] instead.
     */
    suspend fun ensureLoaded(serverId: String, priority: DataRequestPriority) {
        val key = serverId.trim()
        if (key.isBlank()) return
        val ask =
            mutex.withLock {
                val last = ensuredAt[key]
                (last == null || last.elapsedNow() >= ENSURE_REUSE).also { if (it) ensuredAt[key] = timeSource.markNow() }
            }
        if (!ask) return
        if (names.catalog(key) == null) cached(key)
        load(key, priority)
    }

    private companion object {
        /** web `CATALOG_REUSE_MS`. */
        val ENSURE_REUSE = 1.minutes
    }

    private suspend fun fetch(serverId: String, priority: DataRequestPriority): ServerRuntimeCatalog {
        val scope = scopeProvider()
        val response =
            try {
                api.runtimes(ServerId(serverId), priority)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logger("server_runtime_catalog_failed server=$serverId reason=transport")
                null
            }
        val body = response?.takeIf { it.isSuccessful }?.bodyText?.takeIf { it.isNotBlank() }
        val fresh = body?.let { decodeServerRuntimeCatalog(it, RuntimeCatalogSource.Server) }
        val catalog =
            if (fresh != null) {
                if (scope != null) cache?.write(scope, serverId, body)
                fresh
            } else {
                if (response != null) {
                    logger("server_runtime_catalog_failed server=$serverId status=${response.code} decoded=false")
                }
                scope?.let { cache?.read(it, serverId) } ?: BundledRuntimeCatalog
            }
        logger("server_runtime_catalog server=$serverId source=${catalog.source} runtimes=${catalog.runtimes.size}")
        if (catalog.source != RuntimeCatalogSource.Bundled) names.publish(serverId, catalog)
        return catalog
    }
}

/**
 * Brings what a page shows for [serverId] up to date, [shown] first: the cached answer when only
 * the bundled table is shown, then the server's. Each step goes to [emit] when it differs. Every
 * step is [serverId]'s own (never the catalog another server read last), and a load that falls
 * back to the bundled table never replaces a server or cached catalog already shown.
 */
internal suspend fun SharedServerRuntimeCatalogRepository.followServerRuntimeCatalog(
    serverId: String,
    shown: ServerRuntimeCatalog,
    priority: DataRequestPriority = DataRequestPriority.Visible,
    emit: (ServerRuntimeCatalog) -> Unit
) {
    val clean = serverId.trim()
    if (clean.isBlank()) return
    var current = shown
    if (current.source == RuntimeCatalogSource.Bundled) {
        val cached = cached(clean)
        if (cached != current) {
            current = cached
            emit(cached)
        }
    }
    val loaded = load(clean, priority)
    if (loaded != current && !(loaded.source == RuntimeCatalogSource.Bundled && current.source != RuntimeCatalogSource.Bundled)) {
        emit(loaded)
    }
}
