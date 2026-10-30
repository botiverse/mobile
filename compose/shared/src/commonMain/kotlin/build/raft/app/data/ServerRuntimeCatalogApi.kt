package build.raft.app.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import build.raft.app.model.RUNTIMES
import build.raft.app.model.RUNTIME_MODELS
import build.raft.app.model.RuntimeInfo
import build.raft.app.model.RuntimeModelInfo
import build.raft.app.model.ServerId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

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
    val forms: List<JsonElement> = emptyList()
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
    val forms: List<RuntimeCatalogForm> = emptyList()
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
        forms = forms.mapNotNull { it.decodeOrNull(ServerRuntimeCatalogFormWire.serializer())?.toDomain() }
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
        oarOnly = id == "qwen" || id == "qoder",
        models = RUNTIME_MODELS[id].orEmpty(),
        modelsSource = if (id in StaticBundledModelSources) RuntimeCatalogModelsSource.Static else RuntimeCatalogModelsSource.Fallback,
        forms = listOf(RuntimeCatalogForm.V2)
    )

// ── Names ──

/**
 * The catalogs read in this process, for naming a runtime anywhere (subtitles, chips, profile
 * cards) without a server in hand. Snapshot state, so a name on screen follows a catalog that
 * lands later. Lookup: the catalog of the server asked for (else the last one read) → the bundled
 * table → the raw id. A runtime missing from every list never errors: it reads as its id.
 */
internal class RaftRuntimeCatalogNameRegistry {
    private var catalogs by mutableStateOf<Map<String, ServerRuntimeCatalog>>(emptyMap())
    private var lastServerId by mutableStateOf<String?>(null)

    fun publish(serverId: String, catalog: ServerRuntimeCatalog) {
        val key = serverId.trim()
        if (key.isBlank()) return
        if (catalogs[key] != catalog) catalogs = catalogs + (key to catalog)
        if (lastServerId != key) lastServerId = key
    }

    fun clear() {
        catalogs = emptyMap()
        lastServerId = null
    }

    fun entry(runtimeId: String, serverId: String? = null): RuntimeCatalogEntry? {
        val id = runtimeId.trim()
        val server = serverId?.trim()?.takeIf { it.isNotBlank() } ?: lastServerId
        return server?.let { catalogs[it]?.entry(id) } ?: BundledRuntimeCatalog.entry(id)
    }

    fun catalog(serverId: String?): ServerRuntimeCatalog? = serverId?.trim()?.let { catalogs[it] }

    /** The catalog read last (the current server's, in practice). */
    fun latest(): ServerRuntimeCatalog? = lastServerId?.let { catalogs[it] }
}

/** The process-wide registry every runtime name on screen reads. */
internal val RaftRuntimeCatalogNames: RaftRuntimeCatalogNameRegistry = RaftRuntimeCatalogNameRegistry()

/** The name of [runtimeId]: catalog → bundled table → the id itself. */
internal fun runtimeCatalogDisplayName(runtimeId: String, serverId: String? = null): String =
    RaftRuntimeCatalogNames.entry(runtimeId, serverId)?.displayName ?: runtimeId

internal fun runtimeCatalogIsDeprecated(runtimeId: String, serverId: String? = null): Boolean =
    RaftRuntimeCatalogNames.entry(runtimeId, serverId)?.deprecated == true

internal fun runtimeCatalogIsExperimental(runtimeId: String, serverId: String? = null): Boolean =
    RaftRuntimeCatalogNames.entry(runtimeId, serverId)?.experimental == true

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
    private val logger: (String) -> Unit = {}
) {
    private val mutex = Mutex()
    private val inFlight = mutableMapOf<String, Deferred<ServerRuntimeCatalog>>()

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
