package build.raft.app.data

import build.raft.app.RaftCoreInternalApi
import build.raft.app.model.MobilePlacesUpdated
import build.raft.app.model.ServerId
import build.raft.app.network.raftValidTraceIdOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

// Per-server mobile & tablet navigation places (botiverse/slock #16964; #proj-mobile:#329):
//
//   packages/shared/src/railPlaces.ts     RAIL_MOBILE_PLACE_IDS
//   GET   /api/servers/:id/sidebar-order  returns optional `mobilePlaces`, `mobilePlacesVersion`, `placeableApps`
//   PATCH /api/servers/:id/sidebar-order  { mobilePlaces: string[] | null, mobilePlacesVersion: number }
//                                         (NO 50-key cap on mobile writes)
//                                         409 MOBILE_PLACES_VERSION_CONFLICT returns current
//                                         { error, code, mobilePlacesVersion, mobilePlaces }
//   Socket `mobile_places:updated`        { serverId, mobilePlaces, mobilePlacesVersion } to `user:<id>`

/** Built-in phone & tablet navigation places in default order (slock `RAIL_MOBILE_PLACE_IDS`). */
@RaftCoreInternalApi val RaftMobileBuiltInPlaceIds: List<String> =
    listOf("chat", "activity", "tasks", "search", "artifacts", "members", "computers", "apps")

@RaftCoreInternalApi const val MobilePlacesVersionConflictCode: String = "MOBILE_PLACES_VERSION_CONFLICT"

private val MobilePlaceBuiltInSet: Set<String> = RaftMobileBuiltInPlaceIds.toSet()
private val mobilePlacesJson = Json { ignoreUnknownKeys = true }

/** Per-server mobile & tablet navigation places snapshot carried by `GET/PATCH /api/servers/:id/sidebar-order`. */
@Serializable
data class RaftMobilePlacesSnapshot(
    val mobilePlaces: List<RaftRailPlace>,
    val mobilePlacesVersion: Int,
    val placeableApps: List<RaftRailPlaceApp> = emptyList(),
)

/** Whether [value] is one of Raft's built-in mobile place ids (`RAIL_MOBILE_PLACE_IDS`). */
@RaftCoreInternalApi fun isRaftMobileBuiltInPlaceId(value: String): Boolean = value in MobilePlaceBuiltInSet

/**
 * Canonical mobile place key (lowercasing App UUIDs), or null for desktop-only (`assistant`, `code`)
 * and unknown keys. `search` IS a built-in mobile place here.
 */
@RaftCoreInternalApi fun canonicalRaftMobilePlaceKey(value: String): String? {
    if (isRaftMobileBuiltInPlaceId(value)) return value
    val clientId = raftRailAppPlaceClientId(value) ?: return null
    return "$RaftRailAppPlacePrefix$clientId"
}

private fun parseMobilePlaceAppElement(element: JsonElement): RaftRailPlaceApp? {
    val obj = element as? JsonObject ?: return null
    val clientId = obj.strictMobileStringOrNull("clientId")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val name = obj.strictMobileStringOrNull("name")?.takeIf { it.isNotBlank() } ?: return null
    val homepageUrl = obj.strictMobileStringOrNull("homepageUrl")?.takeIf { it.isNotBlank() } ?: return null
    val iconUrl = obj.strictMobileStringOrNull("iconUrl")?.takeIf { it.isNotBlank() }
    return RaftRailPlaceApp(
        clientId = clientId.lowercase(),
        name = name,
        iconUrl = iconUrl,
        homepageUrl = homepageUrl,
    )
}

/** Parses a wire `mobilePlaces` array, deduplicating canonical mobile keys and dropping malformed/unknown entries. */
@RaftCoreInternalApi fun parseRaftMobilePlacesArray(element: JsonElement?): List<RaftRailPlace> {
    val array = element as? JsonArray ?: return emptyList()
    val seen = hashSetOf<String>()
    val result = mutableListOf<RaftRailPlace>()
    for (item in array) {
        val obj = item as? JsonObject ?: continue
        val kind = obj.strictMobileStringOrNull("kind") ?: continue
        val rawKey = obj.strictMobileStringOrNull("key") ?: continue
        val key = canonicalRaftMobilePlaceKey(rawKey) ?: continue
        if (key in seen) continue
        if (kind == RaftRailPlace.KIND_BUILT_IN && isRaftMobileBuiltInPlaceId(key)) {
            seen += key
            result += RaftRailPlace.builtIn(key)
            continue
        }
        if (kind == RaftRailPlace.KIND_APP) {
            val app = obj["app"]?.let(::parseMobilePlaceAppElement) ?: continue
            if (raftRailAppPlaceClientId(key) == app.clientId) {
                seen += key
                result += RaftRailPlace(kind = RaftRailPlace.KIND_APP, key = key, app = app)
            }
        }
    }
    return result
}

/**
 * Parses `mobilePlaces`, `mobilePlacesVersion`, and `placeableApps` from a sidebar-order response
 * JSON object. Returns null when the response omits `mobilePlaces` or `mobilePlacesVersion`
 * (older server pre-slock #16964).
 */
@RaftCoreInternalApi fun parseRaftMobilePlacesObject(obj: JsonObject): RaftMobilePlacesSnapshot? {
    val placesElement = obj["mobilePlaces"] as? JsonArray ?: return null
    val version = obj.strictMobileNonNegativeIntOrNull("mobilePlacesVersion") ?: return null
    return RaftMobilePlacesSnapshot(
        mobilePlaces = parseRaftMobilePlacesArray(placesElement),
        mobilePlacesVersion = version,
        placeableApps = parseRaftRailPlaceAppsArray(obj["placeableApps"]),
    )
}

/** Parses a raw `GET/PATCH /servers/:id/sidebar-order` JSON body for its mobile places fields. */
@RaftCoreInternalApi fun parseRaftMobilePlacesBody(bodyText: String?): RaftMobilePlacesSnapshot? {
    val text = bodyText?.takeIf { it.isNotBlank() } ?: return null
    val obj = runCatching { mobilePlacesJson.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
    return parseRaftMobilePlacesObject(obj)
}

/** Parsed body of a `409 MOBILE_PLACES_VERSION_CONFLICT` response. */
@RaftCoreInternalApi data class MobilePlacesConflictPayload(
    val mobilePlacesVersion: Int,
    val mobilePlaces: List<RaftRailPlace>,
)

/** Parses a `409 MOBILE_PLACES_VERSION_CONFLICT` JSON body; null for any other 409 response. */
@RaftCoreInternalApi fun parseRaftMobilePlacesConflictBody(bodyText: String?): MobilePlacesConflictPayload? {
    val text = bodyText?.takeIf { it.isNotBlank() } ?: return null
    val obj = runCatching { mobilePlacesJson.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
    if (obj.strictMobileStringOrNull("code") != MobilePlacesVersionConflictCode) return null
    val placesElement = obj["mobilePlaces"] as? JsonArray ?: return null
    val version = obj.strictMobileNonNegativeIntOrNull("mobilePlacesVersion") ?: return null
    return MobilePlacesConflictPayload(
        mobilePlacesVersion = version,
        mobilePlaces = parseRaftMobilePlacesArray(placesElement),
    )
}

/**
 * Builds the `PATCH /servers/:id/sidebar-order` JSON body for a mobile places write.
 * Emits explicit `"mobilePlaces": null` when resetting to the default list (leaving desktop `places`
 * untouched). Mobile writes have NO 50-key cap (server accepts full list even with >42 Apps).
 */
@RaftCoreInternalApi fun buildRaftMobilePlacesUpdateBody(
    mobilePlaces: List<String>?,
    mobilePlacesVersion: Int,
): String =
    buildJsonObject {
        if (mobilePlaces == null) {
            put("mobilePlaces", JsonNull)
        } else {
            put(
                "mobilePlaces",
                buildJsonArray {
                    mobilePlaces.forEach { add(JsonPrimitive(it)) }
                },
            )
        }
        put("mobilePlacesVersion", mobilePlacesVersion)
    }.toString()

/** Projects [keys] into [RaftRailPlace] entries using known App metadata from [knownPlaces] and [placeableApps]. */
@RaftCoreInternalApi fun raftMobilePlacesForKeys(
    keys: List<String>,
    knownPlaces: List<RaftRailPlace>,
    placeableApps: List<RaftRailPlaceApp>,
): List<RaftRailPlace> {
    val appsById = linkedMapOf<String, RaftRailPlaceApp>()
    placeableApps.forEach { app -> appsById[app.clientId.lowercase()] = app }
    knownPlaces.forEach { place -> place.app?.let { app -> appsById[app.clientId.lowercase()] = app } }
    val seen = hashSetOf<String>()
    return keys.mapNotNull { raw ->
        val key = canonicalRaftMobilePlaceKey(raw) ?: return@mapNotNull null
        if (!seen.add(key)) return@mapNotNull null
        if (isRaftMobileBuiltInPlaceId(key)) {
            RaftRailPlace.builtIn(key)
        } else {
            val clientId = raftRailAppPlaceClientId(key) ?: return@mapNotNull null
            appsById[clientId]?.let { app -> RaftRailPlace(kind = RaftRailPlace.KIND_APP, key = key, app = app) }
        }
    }
}

/**
 * Applies a reorder of the visible place keys ([visibleOrder]) onto the server's full stored list
 * ([fullKeys]), preserving hidden places (e.g. feature-flagged / guest-gated built-ins and `app:`
 * entries in Phase 1) in their existing slots. Returns [fullKeys] unchanged if [visibleOrder] is
 * not a permutation of visible entries present in [fullKeys].
 */
@RaftCoreInternalApi fun raftMobilePlacesWithVisibleReorder(
    fullKeys: List<String>,
    visibleOrder: List<String>,
): List<String> {
    val canonicalFull = fullKeys.mapNotNull(::canonicalRaftMobilePlaceKey).distinct()
    val shown = visibleOrder.mapNotNull(::canonicalRaftMobilePlaceKey).filter { it in canonicalFull }.distinct()
    if (shown.size != visibleOrder.size) return canonicalFull
    val shownSet = shown.toSet()
    val moved = shown.iterator()
    return canonicalFull.map { key -> if (key in shownSet) moved.next() else key }
}

/**
 * Merges a caller-supplied ordered key list ([requestedKeys]) with the server's stored [fullKeys]
 * so hidden places (e.g. unavailable built-ins or unshown `app:` entries) are never dropped:
 * - When every canonical key in [requestedKeys] already exists in [fullKeys], applies a
 *   slot-preserving reorder ([raftMobilePlacesWithVisibleReorder]).
 * - When [requestedKeys] includes newly added keys not yet in [fullKeys], keeps [requestedKeys]
 *   in order and appends any unseen keys from [fullKeys] at the tail.
 */
@RaftCoreInternalApi fun mergeRaftMobilePlacesOrder(
    fullKeys: List<String>,
    requestedKeys: List<String>,
): List<String> {
    val canonicalFull = fullKeys.mapNotNull(::canonicalRaftMobilePlaceKey).distinct()
    val canonicalRequested = requestedKeys.mapNotNull(::canonicalRaftMobilePlaceKey).distinct()
    if (canonicalRequested.isEmpty()) return canonicalFull
    val fullSet = canonicalFull.toSet()
    if (canonicalRequested.all { it in fullSet }) {
        return raftMobilePlacesWithVisibleReorder(canonicalFull, canonicalRequested)
    }
    val requestedSet = canonicalRequested.toSet()
    return canonicalRequested + canonicalFull.filter { it !in requestedSet }
}

/** Wire result of `PATCH /servers/:id/sidebar-order` for mobile places. */
@RaftCoreInternalApi sealed interface MobilePlacesSaveWireResult {
    data class Saved(val snapshot: RaftMobilePlacesSnapshot) : MobilePlacesSaveWireResult
    data class Conflict(val payload: MobilePlacesConflictPayload, val traceId: String?) : MobilePlacesSaveWireResult
    data class Failed(val error: ScreenError) : MobilePlacesSaveWireResult
}

@RaftCoreInternalApi interface MobilePlacesDataSource {
    /** Reads `GET /servers/:id/sidebar-order` and returns its mobile places snapshot (null on older servers). */
    suspend fun readServerMobilePlaces(serverId: String): DataResult<RaftMobilePlacesSnapshot?>

    /** Writes `PATCH /servers/:id/sidebar-order` with `{ mobilePlaces, mobilePlacesVersion }`. */
    suspend fun saveServerMobilePlaces(
        serverId: String,
        mobilePlaces: List<String>?,
        mobilePlacesVersion: Int,
    ): MobilePlacesSaveWireResult
}

@RaftCoreInternalApi class NetworkMobilePlacesDataSource(
    private val networkClient: RaftNetworkClient,
) : MobilePlacesDataSource {
    override suspend fun readServerMobilePlaces(serverId: String): DataResult<RaftMobilePlacesSnapshot?> =
        try {
            val response =
                networkClient.request(
                    priority = DataRequestPriority.Background,
                    path = "servers/$serverId/sidebar-order",
                    serverIdOverride = ServerId(serverId),
                )
            val traceId = if (response.code == 0) null else raftValidTraceIdOrNull(response.traceId)
            if (!response.isSuccessful) {
                val body = response.errorBodyText?.takeIf { it.isNotBlank() } ?: response.bodyText
                val envelope = if (response.code > 0) parseHttpErrorEnvelope(body) else null
                DataResult.Error(
                    ScreenError(
                        code = "mobile_places_http_${response.code}",
                        retryable = response.code >= 500 || response.code == 0,
                        serverMessage = envelope?.serverMessage,
                        serverCode = envelope?.serverCode,
                        traceId = traceId,
                    ),
                )
            } else {
                DataResult.Content(parseRaftMobilePlacesBody(response.bodyText))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            DataResult.Error(ScreenError(code = "network_unavailable", retryable = true, traceId = null))
        }

    override suspend fun saveServerMobilePlaces(
        serverId: String,
        mobilePlaces: List<String>?,
        mobilePlacesVersion: Int,
    ): MobilePlacesSaveWireResult =
        try {
            val response =
                networkClient.request(
                    priority = DataRequestPriority.Foreground,
                    path = "servers/$serverId/sidebar-order",
                    method = "PATCH",
                    serverIdOverride = ServerId(serverId),
                    bodyText = buildRaftMobilePlacesUpdateBody(mobilePlaces, mobilePlacesVersion),
                )
            val traceId = if (response.code == 0) null else raftValidTraceIdOrNull(response.traceId)
            val body =
                if (response.isSuccessful) {
                    response.bodyText
                } else {
                    response.errorBodyText?.takeIf { it.isNotBlank() } ?: response.bodyText
                }
            when {
                response.isSuccessful -> {
                    val parsed = parseRaftMobilePlacesBody(body)
                    if (parsed != null) {
                        MobilePlacesSaveWireResult.Saved(parsed)
                    } else {
                        MobilePlacesSaveWireResult.Failed(
                            ScreenError(code = "mobile_places_contract_mismatch", retryable = false, traceId = traceId),
                        )
                    }
                }
                response.code == 409 -> {
                    val conflict = parseRaftMobilePlacesConflictBody(body)
                    if (conflict != null) {
                        MobilePlacesSaveWireResult.Conflict(conflict, traceId)
                    } else {
                        val envelope = parseHttpErrorEnvelope(body)
                        MobilePlacesSaveWireResult.Failed(
                            ScreenError(
                                code = "mobile_places_http_409",
                                retryable = false,
                                serverMessage = envelope?.serverMessage,
                                serverCode = envelope?.serverCode,
                                traceId = traceId,
                            ),
                        )
                    }
                }
                else -> {
                    val envelope = if (response.code > 0) parseHttpErrorEnvelope(body) else null
                    MobilePlacesSaveWireResult.Failed(
                        ScreenError(
                            code = "mobile_places_http_${response.code}",
                            retryable = response.code >= 500 || response.code == 0,
                            serverMessage = envelope?.serverMessage,
                            serverCode = envelope?.serverCode,
                            traceId = traceId,
                        ),
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            MobilePlacesSaveWireResult.Failed(ScreenError(code = "network_unavailable", retryable = true, traceId = null))
        }
}

@RaftCoreInternalApi data class ServerMobilePlacesEntry(
    val snapshot: RaftMobilePlacesSnapshot,
    val loadedAtMs: Long,
)

@RaftCoreInternalApi data class SharedMobileRailPlacesState(
    val accountId: String? = null,
    val activeServerId: String? = null,
    val byServer: Map<String, ServerMobilePlacesEntry> = emptyMap(),
    val effect: SharedRailPlacesEffect? = null,
) {
    val activeSnapshot: RaftMobilePlacesSnapshot?
        get() = activeServerId?.let { byServer[it]?.snapshot }
}

/**
 * Per-server phone & native tablet/foldable navigation places store (botiverse/slock #16964,
 * `#proj-mobile:#329`).
 *
 * - Gated per server via [isServerEnabled] (`tab_bar_places_v0`, never enabled on Desktop Compose).
 * - Seeded from `GET /servers/:id/sidebar-order` snapshots loaded by `SharedHomeStore`, with
 *   single-flight reloads on `permission-updates:changed`, foreground return, and reconnect.
 * - Live `mobile_places:updated` events update `mobilePlaces` + `mobilePlacesVersion` immediately
 *   when the version is at least the stored one (preserving `placeableApps`).
 * - Writes serialize per server so rapid edits chain onto the version returned by the prior write;
 *   [saveOrder] preserves hidden places cleanly; [reset] sends `mobilePlaces: null`; `409
 *   MOBILE_PLACES_VERSION_CONFLICT` adopts the server's latest list and surfaces
 *   [SharedRailPlacesEffect.Conflict]; other errors restore the previous list.
 * - Main-thread confined: [scope] runs on the main dispatcher.
 */
@RaftCoreInternalApi class SharedMobileRailPlacesStore(
    private val dataSource: MobilePlacesDataSource,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = ::defaultNowMillis,
    private val logger: (String) -> Unit = {},
    private val isServerEnabled: (String) -> Boolean = { true },
) {
    private val mutableState = MutableStateFlow(SharedMobileRailPlacesState())
    val state: StateFlow<SharedMobileRailPlacesState> = mutableState.asStateFlow()

    private var accountEpoch = 0L
    private var surfaceActive = false
    private val saveMutexByServer = linkedMapOf<String, Mutex>()
    private val savesInFlightByServer = linkedMapOf<String, Int>()
    private val saveGenerationByServer = linkedMapOf<String, Long>()
    private val reloadDeferredByServer = linkedMapOf<String, CompletableDeferred<Unit>>()

    fun setAccount(accountId: String?) {
        val current = mutableState.value
        if (current.accountId == accountId) return
        accountEpoch += 1
        savesInFlightByServer.clear()
        saveGenerationByServer.clear()
        reloadDeferredByServer.values.forEach { it.complete(Unit) }
        reloadDeferredByServer.clear()
        mutableState.value = SharedMobileRailPlacesState(accountId = accountId, activeServerId = current.activeServerId)
    }

    fun setActiveServer(serverId: String?) {
        val current = mutableState.value
        if (current.activeServerId == serverId) return
        mutableState.value = current.copy(activeServerId = serverId)
        if (serverId != null && surfaceActive && isServerEnabled(serverId) && serverId !in current.byServer) {
            reloadAsync(serverId)
        }
    }

    fun setSurfaceActive(active: Boolean) {
        val becameActive = active && !surfaceActive
        surfaceActive = active
        val serverId = mutableState.value.activeServerId
        if (becameActive && serverId != null && isServerEnabled(serverId) && serverId !in mutableState.value.byServer) {
            reloadAsync(serverId)
        }
    }

    fun consumeEffect() {
        if (mutableState.value.effect != null) {
            mutableState.value = mutableState.value.copy(effect = null)
        }
    }

    fun followAppLifecycle(appForeground: Flow<Boolean>): Job =
        scope.launch {
            var previous: Boolean? = null
            appForeground.collect { foreground ->
                val returned = foreground && previous == false
                previous = foreground
                val serverId = mutableState.value.activeServerId
                if (returned && surfaceActive && serverId != null && isServerEnabled(serverId)) {
                    reloadAsync(serverId)
                }
            }
        }

    /** Seeds or refreshes from any `GET/PATCH /servers/:id/sidebar-order` snapshot. */
    fun applySidebarOrderSnapshot(serverId: String, snapshot: RaftMobilePlacesSnapshot?) {
        if (snapshot == null) return
        if (!isServerEnabled(serverId)) return
        if ((savesInFlightByServer[serverId] ?: 0) > 0) return
        val existing = mutableState.value.byServer[serverId]?.snapshot
        if (existing != null && snapshot.mobilePlacesVersion < existing.mobilePlacesVersion) return
        updateServerEntry(serverId, ServerMobilePlacesEntry(snapshot = snapshot, loadedAtMs = nowMs()))
    }

    /** Applies a live `mobile_places:updated` socket event when it is at or ahead of the current version. */
    fun applyRealtimeUpdated(event: MobilePlacesUpdated) {
        val serverId = event.serverId.value
        if (!isServerEnabled(serverId)) return
        if ((savesInFlightByServer[serverId] ?: 0) > 0) return
        val existing = mutableState.value.byServer[serverId]
        if (existing != null && event.mobilePlacesVersion < existing.snapshot.mobilePlacesVersion) return
        val nextSnapshot =
            if (existing != null) {
                existing.snapshot.copy(
                    mobilePlaces = event.mobilePlaces,
                    mobilePlacesVersion = event.mobilePlacesVersion,
                )
            } else {
                RaftMobilePlacesSnapshot(
                    mobilePlaces = event.mobilePlaces,
                    mobilePlacesVersion = event.mobilePlacesVersion,
                    placeableApps = event.mobilePlaces.mapNotNull { it.app },
                )
            }
        updateServerEntry(
            serverId,
            ServerMobilePlacesEntry(
                snapshot = nextSnapshot,
                loadedAtMs = existing?.loadedAtMs ?: nowMs(),
            ),
        )
    }

    /** Handles `permission-updates:changed`: reloads mobile places if [serverId] is currently loaded/active. */
    fun onPermissionUpdatesChanged(serverId: String) {
        if (!isServerEnabled(serverId)) return
        val state = mutableState.value
        if (state.activeServerId == serverId || serverId in state.byServer) {
            reloadAsync(serverId)
        }
    }

    fun isFresh(serverId: String, maxAgeMs: Long = RaftRailPlacesFreshMs): Boolean {
        if (!isServerEnabled(serverId)) return false
        val entry = mutableState.value.byServer[serverId] ?: return false
        if (serverId in reloadDeferredByServer) return false
        return (nowMs() - entry.loadedAtMs) < maxAgeMs
    }

    fun usableApp(serverId: String, clientId: String): RaftRailPlaceApp? {
        if (!isServerEnabled(serverId)) return null
        val snapshot = mutableState.value.byServer[serverId]?.snapshot ?: return null
        val target = clientId.lowercase()
        return snapshot.placeableApps.firstOrNull { it.clientId.lowercase() == target }
    }

    fun reloadAsync(serverId: String) {
        if (!isServerEnabled(serverId)) return
        scope.launch { reload(serverId) }
    }

    /** Alias for [reload]: single-flight refresh of `GET /servers/:id/sidebar-order` for [serverId]. */
    suspend fun refresh(serverId: String) = reload(serverId)

    /** Single-flight reload of `GET /servers/:id/sidebar-order` for [serverId]. */
    suspend fun reload(serverId: String) {
        if (!isServerEnabled(serverId)) return
        reloadDeferredByServer[serverId]?.let { existing ->
            existing.await()
            return
        }
        val deferred = CompletableDeferred<Unit>()
        reloadDeferredByServer[serverId] = deferred
        val epoch = accountEpoch
        val gen = saveGenerationByServer[serverId] ?: 0L
        try {
            val result = dataSource.readServerMobilePlaces(serverId)
            if (epoch != accountEpoch) return
            if (!isServerEnabled(serverId)) return
            if (gen != (saveGenerationByServer[serverId] ?: 0L)) return
            if ((savesInFlightByServer[serverId] ?: 0) > 0) return
            if (result is DataResult.Content && result.data != null) {
                val parsed = result.data
                val existing = mutableState.value.byServer[serverId]?.snapshot
                if (existing == null || parsed.mobilePlacesVersion >= existing.mobilePlacesVersion) {
                    updateServerEntry(serverId, ServerMobilePlacesEntry(snapshot = parsed, loadedAtMs = nowMs()))
                }
            }
        } finally {
            if (reloadDeferredByServer[serverId] === deferred) {
                reloadDeferredByServer.remove(serverId)
            }
            deferred.complete(Unit)
        }
    }

    /**
     * Saves a reordered/updated mobile place list ([keys]) for [serverId], preserving any hidden
     * built-ins or `app:` entries from the server's stored list cleanly.
     */
    suspend fun saveOrder(
        serverId: String,
        keys: List<String>,
        emitEffect: Boolean = true,
    ): RailPlacesSaveOutcome {
        if (!isServerEnabled(serverId)) return RailPlacesSaveOutcome.Skipped
        val beforeEntry = mutableState.value.byServer[serverId] ?: return RailPlacesSaveOutcome.Skipped
        val mergedKeys = mergeRaftMobilePlacesOrder(beforeEntry.snapshot.mobilePlaces.map { it.key }, keys)
        return saveWireKeys(serverId = serverId, wireKeys = mergedKeys, emitEffect = emitEffect)
    }

    /** Resets [serverId]'s mobile places to the default order (`"mobilePlaces": null`), leaving desktop `places` untouched. */
    suspend fun reset(
        serverId: String,
        emitEffect: Boolean = true,
    ): RailPlacesSaveOutcome =
        saveWireKeys(serverId = serverId, wireKeys = null, emitEffect = emitEffect)

    private suspend fun saveWireKeys(
        serverId: String,
        wireKeys: List<String>?,
        emitEffect: Boolean,
    ): RailPlacesSaveOutcome {
        if (!isServerEnabled(serverId)) return RailPlacesSaveOutcome.Skipped
        val beforeEntry = mutableState.value.byServer[serverId] ?: return RailPlacesSaveOutcome.Skipped
        val canonicalKeys = wireKeys?.mapNotNull(::canonicalRaftMobilePlaceKey)?.distinct()
        val epoch = accountEpoch
        val gen = (saveGenerationByServer[serverId] ?: 0L) + 1L
        saveGenerationByServer[serverId] = gen
        val optimistic =
            if (canonicalKeys != null) {
                raftMobilePlacesForKeys(
                    keys = canonicalKeys,
                    knownPlaces = beforeEntry.snapshot.mobilePlaces,
                    placeableApps = beforeEntry.snapshot.placeableApps,
                )
            } else {
                RaftMobileBuiltInPlaceIds.map(RaftRailPlace::builtIn) +
                    beforeEntry.snapshot.placeableApps
                        .sortedWith(compareBy<RaftRailPlaceApp> { it.name.lowercase() }.thenBy { it.clientId })
                        .map(RaftRailPlace::app)
            }
        updateServerEntry(
            serverId,
            beforeEntry.copy(snapshot = beforeEntry.snapshot.copy(mobilePlaces = optimistic)),
        )
        val mutex = saveMutexByServer.getOrPut(serverId) { Mutex() }
        return mutex.withLock {
            if (epoch != accountEpoch) return@withLock RailPlacesSaveOutcome.Skipped
            val currentEntry = mutableState.value.byServer[serverId] ?: return@withLock RailPlacesSaveOutcome.Skipped
            savesInFlightByServer[serverId] = (savesInFlightByServer[serverId] ?: 0) + 1
            logger("mobile_places_save_start serverId=$serverId version=${currentEntry.snapshot.mobilePlacesVersion} count=${canonicalKeys?.size ?: -1}")
            val wire =
                try {
                    dataSource.saveServerMobilePlaces(
                        serverId = serverId,
                        mobilePlaces = canonicalKeys,
                        mobilePlacesVersion = currentEntry.snapshot.mobilePlacesVersion,
                    )
                } finally {
                    if (epoch == accountEpoch) {
                        val remaining = (savesInFlightByServer[serverId] ?: 1) - 1
                        if (remaining <= 0) savesInFlightByServer.remove(serverId) else savesInFlightByServer[serverId] = remaining
                    }
                }
            if (epoch != accountEpoch) return@withLock RailPlacesSaveOutcome.Skipped
            when (wire) {
                is MobilePlacesSaveWireResult.Saved -> {
                    logger("mobile_places_save_result serverId=$serverId outcome=saved version=${wire.snapshot.mobilePlacesVersion}")
                    if (gen == (saveGenerationByServer[serverId] ?: 0L)) {
                        updateServerEntry(serverId, ServerMobilePlacesEntry(snapshot = wire.snapshot, loadedAtMs = nowMs()))
                    } else {
                        val latest = mutableState.value.byServer[serverId]
                        if (latest != null) {
                            updateServerEntry(
                                serverId,
                                latest.copy(
                                    snapshot =
                                        latest.snapshot.copy(
                                            mobilePlacesVersion = wire.snapshot.mobilePlacesVersion,
                                            placeableApps = wire.snapshot.placeableApps,
                                        ),
                                    loadedAtMs = nowMs(),
                                ),
                            )
                        }
                    }
                    RailPlacesSaveOutcome.Saved
                }
                is MobilePlacesSaveWireResult.Conflict -> {
                    logger("mobile_places_save_result serverId=$serverId outcome=conflict version=${wire.payload.mobilePlacesVersion}")
                    val latest = mutableState.value.byServer[serverId] ?: currentEntry
                    updateServerEntry(
                        serverId,
                        ServerMobilePlacesEntry(
                            snapshot =
                                latest.snapshot.copy(
                                    mobilePlaces = wire.payload.mobilePlaces,
                                    mobilePlacesVersion = wire.payload.mobilePlacesVersion,
                                ),
                            loadedAtMs = nowMs(),
                        ),
                        effect = if (emitEffect) SharedRailPlacesEffect.Conflict(wire.traceId) else mutableState.value.effect,
                    )
                    RailPlacesSaveOutcome.Conflict
                }
                is MobilePlacesSaveWireResult.Failed -> {
                    logger("mobile_places_save_result serverId=$serverId outcome=failed code=${wire.error.code}")
                    if (gen == (saveGenerationByServer[serverId] ?: 0L)) {
                        val latest = mutableState.value.byServer[serverId] ?: beforeEntry
                        updateServerEntry(
                            serverId,
                            latest.copy(snapshot = latest.snapshot.copy(mobilePlaces = beforeEntry.snapshot.mobilePlaces)),
                            effect = if (emitEffect) SharedRailPlacesEffect.SaveFailed(wire.error.traceId) else mutableState.value.effect,
                        )
                    }
                    RailPlacesSaveOutcome.Failed
                }
            }
        }
    }

    private fun updateServerEntry(
        serverId: String,
        entry: ServerMobilePlacesEntry,
        effect: SharedRailPlacesEffect? = mutableState.value.effect,
    ) {
        val current = mutableState.value
        mutableState.value =
            current.copy(
                byServer = current.byServer + (serverId to entry),
                effect = effect,
            )
    }
}

private fun JsonObject.strictMobileStringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.strictMobileNonNegativeIntOrNull(key: String): Int? {
    val primitive = (this[key] as? JsonPrimitive)?.takeIf { !it.isString } ?: return null
    val intValue = primitive.intOrNull ?: return null
    return intValue.takeIf { it >= 0 && primitive.content == intValue.toString() }
}
