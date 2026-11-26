package build.raft.app.data

import build.raft.app.RaftCoreInternalApi
import build.raft.app.model.RailPlacesUpdated
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
import kotlin.math.floor

// Per-server desktop rail places (botiverse/slock #16682 + #16684 + #16773 + #16808 + #16857 + #16864 + #16957 + #16960;
// #wg-mobile-sync:#294 + #wg-mobile-sync:#322):
//
//   packages/shared/src/railPlaces.ts     RAIL_FIXED_TAB_IDS (search only), RAIL_KEPT_PLACE_IDS
//                                         (chat, activity: moved like any place, never removed),
//                                         RAIL_BUILT_IN_PLACE_IDS, canonicalRailPlaceKey,
//                                         railAppPlaceClientId, railPlacesFromAccountOrder,
//                                         withRailKeptPlaces
//   GET   /api/servers/:id/sidebar-order  returns optional `desktopPlaces` (whole list, Chat and
//                                         Activity in their spots), `places` (without Chat and
//                                         Activity for older clients), `placesVersion`, `placeableApps`
//   PATCH /api/servers/:id/sidebar-order  { places: string[] | null, placesVersion: number }
//                                         409 RAIL_PLACES_VERSION_CONFLICT returns current
//                                         { error, code, placesVersion, places, desktopPlaces }
//   Socket `rail_places:updated`          { serverId, places, desktopPlaces, placesVersion } to `user:<id>`

/** Tabs fixed at the top of the desktop rail; never part of the per-server `desktopPlaces` list (`RAIL_FIXED_TAB_IDS`). */
@RaftCoreInternalApi val RaftRailFixedTabIds: List<String> = listOf("search")

/** Built-in places present on every desktop rail list, first by default: moved like any place, never removed (`RAIL_KEPT_PLACE_IDS`). */
@RaftCoreInternalApi val RaftRailKeptPlaceIds: List<String> = listOf("chat", "activity")

/** Built-in desktop rail places in default order (slock `RAIL_BUILT_IN_PLACE_IDS`, Chat and Activity first). */
@RaftCoreInternalApi val RaftRailBuiltInPlaceIds: List<String> =
    RaftRailKeptPlaceIds + listOf("tasks", "artifacts", "members", "computers", "apps", "assistant")

/** Retired desktop rail place ids ignored on read and dropped by the server on write (`RETIRED_RAIL_PLACE_IDS`). */
@RaftCoreInternalApi val RaftRailRetiredPlaceIds: Set<String> = setOf("code")

/** Prefix of an installed App place key (`app:<clientId>`). */
@RaftCoreInternalApi const val RaftRailAppPlacePrefix: String = "app:"

/** Server cap on keys accepted in one PATCH `places` write (`RAIL_PLACES_MAX`). */
@RaftCoreInternalApi const val RaftRailPlacesMax: Int = 50

/** Max age before opening an App place re-reads the server's places (slock `RAIL_PLACES_FRESH_MS`). */
@RaftCoreInternalApi const val RaftRailPlacesFreshMs: Long = 15_000L

@RaftCoreInternalApi const val RailPlacesVersionConflictCode: String = "RAIL_PLACES_VERSION_CONFLICT"

private val RailPlaceBuiltInSet: Set<String> = RaftRailBuiltInPlaceIds.toSet()
private val RailPlaceKeptSet: Set<String> = RaftRailKeptPlaceIds.toSet()
private val RailAppPlaceUuidRegex =
    Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", RegexOption.IGNORE_CASE)

private val railPlacesJson = Json { ignoreUnknownKeys = true }

/** An installed App eligible to appear on the person's rail for one server (slock `RailPlaceApp`). */
@Serializable
data class RaftRailPlaceApp(
    val clientId: String,
    val name: String,
    val iconUrl: String? = null,
    val homepageUrl: String,
)

/**
 * One entry in the person's per-server rail places list (slock `RailPlace`): either a built-in
 * destination ([KIND_BUILT_IN]) or an installed App ([KIND_APP]).
 */
@Serializable
data class RaftRailPlace(
    val kind: String,
    val key: String,
    val app: RaftRailPlaceApp? = null,
) {
    val isBuiltIn: Boolean get() = kind == KIND_BUILT_IN
    val isApp: Boolean get() = kind == KIND_APP && app != null

    companion object {
        const val KIND_BUILT_IN: String = "built_in"
        const val KIND_APP: String = "app"

        fun builtIn(key: String): RaftRailPlace = RaftRailPlace(kind = KIND_BUILT_IN, key = key)

        fun app(app: RaftRailPlaceApp): RaftRailPlace {
            val normalizedId = app.clientId.lowercase()
            return RaftRailPlace(
                kind = KIND_APP,
                key = "$RaftRailAppPlacePrefix$normalizedId",
                app = app.copy(clientId = normalizedId),
            )
        }
    }
}

/** Per-server rail places snapshot carried by `GET/PATCH /api/servers/:id/sidebar-order`. */
@Serializable
data class RaftRailPlacesSnapshot(
    val places: List<RaftRailPlace>,
    val placesVersion: Int,
    val placeableApps: List<RaftRailPlaceApp> = emptyList(),
)

/** Whether [value] is Chat or Activity: kept on every desktop rail list, moved like any place, never removed (`isRailKeptPlaceId`). */
@RaftCoreInternalApi fun isRaftRailKeptPlaceId(value: String): Boolean = value in RailPlaceKeptSet

/** Whether [value] is one of Raft's built-in desktop place ids (`RAIL_BUILT_IN_PLACE_IDS`, Chat and Activity included). */
@RaftCoreInternalApi fun isRaftRailBuiltInPlaceId(value: String): Boolean = value in RailPlaceBuiltInSet

/** The lowercased App clientId inside an `app:<uuid>` key, or null when [key] is not one. */
@RaftCoreInternalApi fun raftRailAppPlaceClientId(key: String): String? {
    if (!key.startsWith(RaftRailAppPlacePrefix)) return null
    val id = key.removePrefix(RaftRailAppPlacePrefix)
    return if (RailAppPlaceUuidRegex.matches(id)) id.lowercase() else null
}

/** Canonical desktop place key (built-ins including `chat`/`activity`, plus lowercased `app:<uuid>`), or null for `search`, retired, or unknown keys. */
@RaftCoreInternalApi fun canonicalRaftRailPlaceKey(value: String): String? {
    if (isRaftRailBuiltInPlaceId(value)) return value
    val clientId = raftRailAppPlaceClientId(value) ?: return null
    return "$RaftRailAppPlacePrefix$clientId"
}

/**
 * [keys] with Chat or Activity prepended (in their default order) when missing: a list stored
 * before they could move (slock `withRailKeptPlaces`, #16773).
 */
@RaftCoreInternalApi fun withRaftRailKeptPlaceKeys(keys: List<String>): List<String> {
    val missing = RaftRailKeptPlaceIds.filter { it !in keys }
    return if (missing.isEmpty()) keys else missing + keys
}

/**
 * [places] with Chat or Activity prepended as built-in entries when missing, preserving the stored
 * positions of any kept places already present (slock `parseListOf` / `withRailKeptPlaces`).
 */
@RaftCoreInternalApi fun withRaftRailKeptPlaces(places: List<RaftRailPlace>): List<RaftRailPlace> {
    val present = places.mapTo(hashSetOf()) { it.key }
    val missing = RaftRailKeptPlaceIds.filter { it !in present }.map(RaftRailPlace::builtIn)
    return if (missing.isEmpty()) places else missing + places
}

/**
 * Default desktop places derived from an account-level `users.rail_tab_order` when the member has
 * never saved per-server places (slock `railPlacesFromAccountOrder`, #16773): Chat and Activity
 * first, then the remaining built-in places in the account's rail order (never `search`).
 */
@RaftCoreInternalApi fun raftRailPlacesFromAccountOrder(accountOrder: List<String>?): List<String> =
    RaftRailKeptPlaceIds +
        normalizeRaftRailTabOrder(accountOrder).filter { isRaftRailBuiltInPlaceId(it) && !isRaftRailKeptPlaceId(it) }

/** Parses one `RailPlaceApp` wire object (`clientId` lowercased; `name` and `homepageUrl` required). */
private fun parseRailPlaceAppElement(element: JsonElement): RaftRailPlaceApp? {
    val obj = element as? JsonObject ?: return null
    val clientId = obj.strictStringOrNull("clientId")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val name = obj.strictStringOrNull("name")?.takeIf { it.isNotBlank() } ?: return null
    val homepageUrl = obj.strictStringOrNull("homepageUrl")?.takeIf { it.isNotBlank() } ?: return null
    val iconUrl = obj.strictStringOrNull("iconUrl")?.takeIf { it.isNotBlank() }
    return RaftRailPlaceApp(
        clientId = clientId.lowercase(),
        name = name,
        iconUrl = iconUrl,
        homepageUrl = homepageUrl,
    )
}

/** Parses a wire `desktopPlaces` or `places` array, deduplicating canonical keys and dropping malformed entries (web `parsePlaces`). */
@RaftCoreInternalApi fun parseRaftRailPlacesArray(element: JsonElement?): List<RaftRailPlace> {
    val array = element as? JsonArray ?: return emptyList()
    val seen = hashSetOf<String>()
    val result = mutableListOf<RaftRailPlace>()
    for (item in array) {
        val obj = item as? JsonObject ?: continue
        val kind = obj.strictStringOrNull("kind") ?: continue
        val rawKey = obj.strictStringOrNull("key") ?: continue
        val key = canonicalRaftRailPlaceKey(rawKey) ?: continue
        if (key in seen) continue
        if (kind == RaftRailPlace.KIND_BUILT_IN && isRaftRailBuiltInPlaceId(key)) {
            seen += key
            result += RaftRailPlace.builtIn(key)
            continue
        }
        if (kind == RaftRailPlace.KIND_APP) {
            val app = obj["app"]?.let(::parseRailPlaceAppElement) ?: continue
            if (raftRailAppPlaceClientId(key) == app.clientId) {
                seen += key
                result += RaftRailPlace(kind = RaftRailPlace.KIND_APP, key = key, app = app)
            }
        }
    }
    return result
}

/**
 * Parses the whole desktop places list from a response, 409 conflict, or `rail_places:updated`
 * object (slock #16773 `parseListOf`): prefers `desktopPlaces` when present as an array, else
 * falls back to `places` (pre-#16773 server), and guarantees Chat & Activity are present via
 * [withRaftRailKeptPlaces]. Returns null when neither field is a JSON array.
 */
@RaftCoreInternalApi fun parseRaftRailPlacesList(obj: JsonObject): List<RaftRailPlace>? {
    val rawArray = (obj["desktopPlaces"] as? JsonArray) ?: (obj["places"] as? JsonArray) ?: return null
    return withRaftRailKeptPlaces(parseRaftRailPlacesArray(rawArray))
}

/** Parses a wire `placeableApps` array. */
@RaftCoreInternalApi fun parseRaftRailPlaceAppsArray(element: JsonElement?): List<RaftRailPlaceApp> {
    val array = element as? JsonArray ?: return emptyList()
    return array.mapNotNull(::parseRailPlaceAppElement)
}

/**
 * Parses `desktopPlaces` (falling back to `places` + kept places), `placesVersion`, and
 * `placeableApps` from a sidebar-order response JSON object. Returns null when the response omits
 * places fields or `placesVersion` (older server pre-slock #16682).
 */
@RaftCoreInternalApi fun parseRaftRailPlacesObject(obj: JsonObject): RaftRailPlacesSnapshot? {
    val places = parseRaftRailPlacesList(obj) ?: return null
    val version = obj.strictNonNegativeIntOrNull("placesVersion") ?: return null
    return RaftRailPlacesSnapshot(
        places = places,
        placesVersion = version,
        placeableApps = parseRaftRailPlaceAppsArray(obj["placeableApps"]),
    )
}

/** Parses a raw `GET/PATCH /servers/:id/sidebar-order` JSON body for its rail places fields. */
@RaftCoreInternalApi fun parseRaftRailPlacesBody(bodyText: String?): RaftRailPlacesSnapshot? {
    val text = bodyText?.takeIf { it.isNotBlank() } ?: return null
    val obj = runCatching { railPlacesJson.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
    return parseRaftRailPlacesObject(obj)
}

/** Parsed body of a `409 RAIL_PLACES_VERSION_CONFLICT` response. */
@RaftCoreInternalApi data class RailPlacesConflictPayload(
    val placesVersion: Int,
    val places: List<RaftRailPlace>,
)

/** Parses a `409 RAIL_PLACES_VERSION_CONFLICT` JSON body; null for any other 409 response. */
@RaftCoreInternalApi fun parseRaftRailPlacesConflictBody(bodyText: String?): RailPlacesConflictPayload? {
    val text = bodyText?.takeIf { it.isNotBlank() } ?: return null
    val obj = runCatching { railPlacesJson.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
    if (obj.strictStringOrNull("code") != RailPlacesVersionConflictCode) return null
    val places = parseRaftRailPlacesList(obj) ?: return null
    val version = obj.strictNonNegativeIntOrNull("placesVersion") ?: return null
    return RailPlacesConflictPayload(
        placesVersion = version,
        places = places,
    )
}

/**
 * Builds the `PATCH /servers/:id/sidebar-order` JSON body for a rail places write.
 * Emits explicit `"places": null` when resetting to the account default.
 */
@RaftCoreInternalApi fun buildRaftRailPlacesUpdateBody(places: List<String>?, placesVersion: Int): String =
    buildJsonObject {
        if (places == null) {
            put("places", JsonNull)
        } else {
            put(
                "places",
                buildJsonArray {
                    places.forEach { add(JsonPrimitive(it)) }
                },
            )
        }
        put("placesVersion", placesVersion)
    }.toString()

/**
 * Projects [keys] into [RaftRailPlace] entries using known App metadata from [knownPlaces] and
 * [placeableApps] (web `railPlacesForKeys`).
 */
@RaftCoreInternalApi fun raftRailPlacesForKeys(
    keys: List<String>,
    knownPlaces: List<RaftRailPlace>,
    placeableApps: List<RaftRailPlaceApp>,
): List<RaftRailPlace> {
    val appsById = linkedMapOf<String, RaftRailPlaceApp>()
    placeableApps.forEach { app -> appsById[app.clientId.lowercase()] = app }
    knownPlaces.forEach { place -> place.app?.let { app -> appsById[app.clientId.lowercase()] = app } }
    val seen = hashSetOf<String>()
    return keys.mapNotNull { raw ->
        val key = canonicalRaftRailPlaceKey(raw) ?: return@mapNotNull null
        if (!seen.add(key)) return@mapNotNull null
        if (isRaftRailBuiltInPlaceId(key)) {
            RaftRailPlace.builtIn(key)
        } else {
            val clientId = raftRailAppPlaceClientId(key) ?: return@mapNotNull null
            appsById[clientId]?.let { app -> RaftRailPlace(kind = RaftRailPlace.KIND_APP, key = key, app = app) }
        }
    }
}

/**
 * Applies a reorder of the visible place keys ([visibleOrder]) onto the server's full stored list
 * ([fullKeys]), preserving hidden places (e.g. feature-flagged or guest-gated built-ins) in their
 * existing slots. Returns [fullKeys] unchanged if [visibleOrder] is not a permutation of visible
 * entries present in [fullKeys].
 */
@RaftCoreInternalApi fun raftRailPlacesWithVisibleReorder(
    fullKeys: List<String>,
    visibleOrder: List<String>,
): List<String> {
    val canonicalFull = fullKeys.mapNotNull(::canonicalRaftRailPlaceKey).distinct()
    val shown = visibleOrder.mapNotNull(::canonicalRaftRailPlaceKey).filter { it in canonicalFull }.distinct()
    if (shown.size != visibleOrder.size) return canonicalFull
    val shownSet = shown.toSet()
    val moved = shown.iterator()
    return canonicalFull.map { key -> if (key in shownSet) moved.next() else key }
}

/** Moves [key] one visible step (`direction` = -1 or +1) preserving hidden places in their slots (web `movePlace`). */
@RaftCoreInternalApi fun raftRailMovePlace(
    keys: List<String>,
    visibleKeys: List<String>,
    key: String,
    direction: Int,
): List<String>? {
    if (direction == 0) return null
    val at = visibleKeys.indexOf(key)
    val neighbour = visibleKeys.getOrNull(at + direction) ?: return null
    return raftRailMovePlaceTo(keys, key, neighbour)
}

/** Puts [key] at [target]'s index in [keys]; null if either is absent or equal (web `movePlaceTo`). */
@RaftCoreInternalApi fun raftRailMovePlaceTo(
    keys: List<String>,
    key: String,
    target: String,
): List<String>? {
    val from = keys.indexOf(key)
    val to = keys.indexOf(target)
    if (from < 0 || to < 0 || from == to) return null
    val next = keys.toMutableList()
    val moved = next.removeAt(from)
    next.add(to, moved)
    return next
}

/**
 * Places [key] immediately before or [after] [target] in [keys] (web `placeNextTo`, #16808/#16857/#16864):
 * - If [key] is already on [keys] (dragged along the rail or from More's overflow), moves it so it never appears twice.
 * - If [key] is not on [keys] (dragged from More's "More places"), adds it at that slot.
 * - Returns null when [key] or [target] is not a canonical place key, [key] == [target], or [target] is not on [keys].
 */
@RaftCoreInternalApi fun raftRailPlaceNextTo(
    keys: List<String>,
    key: String,
    target: String,
    after: Boolean,
): List<String>? {
    val canonicalKey = canonicalRaftRailPlaceKey(key) ?: return null
    val canonicalTarget = canonicalRaftRailPlaceKey(target) ?: return null
    if (canonicalKey == canonicalTarget) return null
    val next = keys.filterNot { it == canonicalKey }.toMutableList()
    val at = next.indexOf(canonicalTarget)
    if (at < 0) return null
    next.add(at + if (after) 1 else 0, canonicalKey)
    return next
}

/** Removes [key] from [keys]; unchanged for Chat and Activity which stay on every desktop list (web `removePlace`, #16773). */
@RaftCoreInternalApi fun raftRailRemovePlace(keys: List<String>, key: String): List<String> {
    val canonical = canonicalRaftRailPlaceKey(key) ?: return keys
    if (isRaftRailKeptPlaceId(canonical)) return keys
    return keys.filterNot { it == canonical }
}

/** Appends [key] to [keys] if valid and not already present (web `addPlace`). */
@RaftCoreInternalApi fun raftRailAddPlace(keys: List<String>, key: String): List<String> {
    val canonical = canonicalRaftRailPlaceKey(key) ?: return keys
    return if (canonical in keys) keys else keys + canonical
}

/**
 * How many rail items fit in [availablePx] with [itemPx] height and [gapPx] spacing, reserving one
 * gap for the frame decoration strip (web `railSlots`). Unmeasured (`<= 0`), returns [Int.MAX_VALUE].
 */
@RaftCoreInternalApi fun raftRailSlots(availablePx: Float, itemPx: Float, gapPx: Float): Int {
    if (availablePx <= 0f || itemPx <= 0f) return Int.MAX_VALUE
    val stride = itemPx + gapPx.coerceAtLeast(0f)
    return floor((availablePx / stride).toDouble()).toInt().coerceAtLeast(0)
}

/**
 * How many of [placeCount] places fit on the rail given [slots] total slots, [fixedCount] fixed
 * items (Search), [trailingCount] trailing items, and 1 slot always reserved for More (web `visiblePlaceCount`).
 */
@RaftCoreInternalApi fun raftVisiblePlaceCount(
    placeCount: Int,
    slots: Int,
    fixedCount: Int,
    trailingCount: Int = 0,
): Int {
    if (placeCount <= 0) return 0
    if (slots == Int.MAX_VALUE) return placeCount
    return (slots - fixedCount - trailingCount - 1).coerceIn(0, placeCount)
}

/** Normalizes an http(s) URL to its lowercase `scheme://host[:port]` origin without default port, or null. */
@RaftCoreInternalApi fun raftRailPlaceOriginOf(url: String): String? {
    val trimmed = url.trim()
    val schemeEnd = trimmed.indexOf("://")
    if (schemeEnd <= 0) return null
    val scheme = trimmed.substring(0, schemeEnd).lowercase()
    if (scheme != "https" && scheme != "http") return null
    val afterScheme = trimmed.substring(schemeEnd + 3)
    val authorityEnd = afterScheme.indexOfAny(charArrayOf('/', '?', '#')).let { if (it < 0) afterScheme.length else it }
    val authority = afterScheme.substring(0, authorityEnd)
    if (authority.isEmpty() || '@' in authority) return null
    val host: String
    val port: Int?
    if (authority.startsWith("[")) {
        val close = authority.indexOf(']')
        if (close <= 1) return null
        host = authority.substring(0, close + 1).lowercase()
        val rest = authority.substring(close + 1)
        port =
            when {
                rest.isEmpty() -> null
                rest.startsWith(":") -> rest.substring(1).toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
                else -> return null
            }
    } else {
        val colon = authority.indexOf(':')
        if (colon < 0) {
            host = authority.lowercase()
            port = null
        } else {
            if (authority.indexOf(':', colon + 1) >= 0) return null
            host = authority.substring(0, colon).lowercase()
            port = authority.substring(colon + 1).toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        }
    }
    if (host.isEmpty() || host.any { it.isWhitespace() }) return null
    val defaultPort = if (scheme == "https") 443 else 80
    val explicitPort = port?.takeUnless { it == defaultPort }
    return if (explicitPort != null) "$scheme://$host:$explicitPort" else "$scheme://$host"
}

/** Built-in places the viewer can see that are not currently on [keys], in default order (web `builtInsNotOnList`). */
@RaftCoreInternalApi fun raftRailBuiltInsNotOnList(
    keys: List<String>,
    canSee: (String) -> Boolean,
): List<String> {
    val present = keys.mapNotNull(::canonicalRaftRailPlaceKey).toSet()
    return RaftRailBuiltInPlaceIds.filter { key -> canSee(key) && key !in present }
}

/** Placeable installed Apps not currently on [keys], preserving server sort order (web `appsNotOnList`). */
@RaftCoreInternalApi fun raftRailAppsNotOnList(
    keys: List<String>,
    placeableApps: List<RaftRailPlaceApp>,
): List<RaftRailPlaceApp> {
    val present = keys.mapNotNull(::canonicalRaftRailPlaceKey).toSet()
    return placeableApps.filter { app -> "$RaftRailAppPlacePrefix${app.clientId.lowercase()}" !in present }
}

/** Wire result of `PATCH /servers/:id/sidebar-order` for rail places. */
@RaftCoreInternalApi sealed interface RailPlacesSaveWireResult {
    data class Saved(val snapshot: RaftRailPlacesSnapshot) : RailPlacesSaveWireResult
    data class Conflict(val payload: RailPlacesConflictPayload, val traceId: String?) : RailPlacesSaveWireResult
    data class Failed(val error: ScreenError) : RailPlacesSaveWireResult
}

@RaftCoreInternalApi interface RailPlacesDataSource {
    /** Reads `GET /servers/:id/sidebar-order` and returns its rail places snapshot (null on older servers). */
    suspend fun readServerPlaces(serverId: String): DataResult<RaftRailPlacesSnapshot?>

    /** Writes `PATCH /servers/:id/sidebar-order` with `{ places, placesVersion }`. */
    suspend fun saveServerPlaces(
        serverId: String,
        places: List<String>?,
        placesVersion: Int,
    ): RailPlacesSaveWireResult
}

@RaftCoreInternalApi class NetworkRailPlacesDataSource(
    private val networkClient: RaftNetworkClient,
) : RailPlacesDataSource {
    override suspend fun readServerPlaces(serverId: String): DataResult<RaftRailPlacesSnapshot?> =
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
                        code = "rail_places_http_${response.code}",
                        retryable = response.code >= 500 || response.code == 0,
                        serverMessage = envelope?.serverMessage,
                        serverCode = envelope?.serverCode,
                        traceId = traceId,
                    ),
                )
            } else {
                DataResult.Content(parseRaftRailPlacesBody(response.bodyText))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            DataResult.Error(ScreenError(code = "network_unavailable", retryable = true, traceId = null))
        }

    override suspend fun saveServerPlaces(
        serverId: String,
        places: List<String>?,
        placesVersion: Int,
    ): RailPlacesSaveWireResult =
        try {
            val response =
                networkClient.request(
                    priority = DataRequestPriority.Foreground,
                    path = "servers/$serverId/sidebar-order",
                    method = "PATCH",
                    serverIdOverride = ServerId(serverId),
                    bodyText = buildRaftRailPlacesUpdateBody(places, placesVersion),
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
                    val parsed = parseRaftRailPlacesBody(body)
                    if (parsed != null) {
                        RailPlacesSaveWireResult.Saved(parsed)
                    } else {
                        RailPlacesSaveWireResult.Failed(
                            ScreenError(code = "rail_places_contract_mismatch", retryable = false, traceId = traceId),
                        )
                    }
                }
                response.code == 409 -> {
                    val conflict = parseRaftRailPlacesConflictBody(body)
                    if (conflict != null) {
                        RailPlacesSaveWireResult.Conflict(conflict, traceId)
                    } else {
                        val envelope = parseHttpErrorEnvelope(body)
                        RailPlacesSaveWireResult.Failed(
                            ScreenError(
                                code = "rail_places_http_409",
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
                    RailPlacesSaveWireResult.Failed(
                        ScreenError(
                            code = "rail_places_http_${response.code}",
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
            RailPlacesSaveWireResult.Failed(ScreenError(code = "network_unavailable", retryable = true, traceId = null))
        }
}

@RaftCoreInternalApi enum class RailPlacesSaveOutcome {
    Saved,
    Conflict,
    Failed,
    Skipped,
}

@RaftCoreInternalApi sealed interface SharedRailPlacesEffect {
    data class SaveFailed(val traceId: String?) : SharedRailPlacesEffect
    data class Conflict(val traceId: String?) : SharedRailPlacesEffect
}

@RaftCoreInternalApi data class ServerRailPlacesEntry(
    val snapshot: RaftRailPlacesSnapshot,
    val loadedAtMs: Long,
)

@RaftCoreInternalApi data class SharedRailPlacesState(
    val accountId: String? = null,
    val activeServerId: String? = null,
    val byServer: Map<String, ServerRailPlacesEntry> = emptyMap(),
    val effect: SharedRailPlacesEffect? = null,
) {
    val activeSnapshot: RaftRailPlacesSnapshot?
        get() = activeServerId?.let { byServer[it]?.snapshot }
}

/**
 * Per-server rail places store (botiverse/slock #16682 + #16684, web `railPlacesStore.ts`).
 *
 * - Seeded directly from `GET /servers/:id/sidebar-order` snapshots loaded by `SharedHomeStore`,
 *   with single-flight reloads on `permission-updates:changed`, foreground return, reconnect, and
 *   15 s freshness checks before opening an App place.
 * - Live `rail_places:updated` events update `places` + `placesVersion` immediately when the
 *   version is at least the stored one (preserving `placeableApps`).
 * - Writes serialize one after another per server so rapid edits chain onto the version returned
 *   by the prior write; `409 RAIL_PLACES_VERSION_CONFLICT` adopts the server's latest list and
 *   surfaces [SharedRailPlacesEffect.Conflict]; other errors restore the previous list.
 * - Main-thread confined: [scope] runs on the main dispatcher.
 */
@RaftCoreInternalApi class SharedRailPlacesStore(
    private val dataSource: RailPlacesDataSource,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = ::defaultNowMillis,
    private val logger: (String) -> Unit = {},
    private val isServerEnabled: (String) -> Boolean = { true },
) {
    private val mutableState = MutableStateFlow(SharedRailPlacesState())
    val state: StateFlow<SharedRailPlacesState> = mutableState.asStateFlow()

    private var accountEpoch = 0L
    private var railShown = false
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
        mutableState.value = SharedRailPlacesState(accountId = accountId, activeServerId = current.activeServerId)
    }

    fun setActiveServer(serverId: String?) {
        val current = mutableState.value
        if (current.activeServerId == serverId) return
        mutableState.value = current.copy(activeServerId = serverId)
        if (serverId != null && railShown && isServerEnabled(serverId) && serverId !in current.byServer) {
            reloadAsync(serverId)
        }
    }

    fun setRailShown(shown: Boolean) {
        val becameShown = shown && !railShown
        railShown = shown
        val serverId = mutableState.value.activeServerId
        if (becameShown && serverId != null && isServerEnabled(serverId) && serverId !in mutableState.value.byServer) {
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
                if (returned && railShown && serverId != null && isServerEnabled(serverId)) {
                    reloadAsync(serverId)
                }
            }
        }

    /** Seeds or refreshes from any `GET/PATCH /servers/:id/sidebar-order` snapshot. */
    fun applySidebarOrderSnapshot(serverId: String, snapshot: RaftRailPlacesSnapshot?) {
        if (snapshot == null) return
        if (!isServerEnabled(serverId)) return
        if ((savesInFlightByServer[serverId] ?: 0) > 0) return
        val existing = mutableState.value.byServer[serverId]?.snapshot
        if (existing != null && snapshot.placesVersion < existing.placesVersion) return
        val normalized = snapshot.copy(places = withRaftRailKeptPlaces(snapshot.places))
        updateServerEntry(serverId, ServerRailPlacesEntry(snapshot = normalized, loadedAtMs = nowMs()))
    }

    /** Applies a live `rail_places:updated` socket event when it is at or ahead of the current version. */
    fun applyRealtimeUpdated(event: RailPlacesUpdated) {
        val serverId = event.serverId.value
        if (!isServerEnabled(serverId)) return
        val existing = mutableState.value.byServer[serverId] ?: return
        if ((savesInFlightByServer[serverId] ?: 0) > 0) return
        if (event.placesVersion < existing.snapshot.placesVersion) return
        updateServerEntry(
            serverId,
            existing.copy(
                snapshot =
                    existing.snapshot.copy(
                        places = withRaftRailKeptPlaces(event.places),
                        placesVersion = event.placesVersion,
                    ),
            ),
        )
    }

    /** Handles `permission-updates:changed`: reloads places if [serverId] is currently loaded/active. */
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

    /**
     * The usable App on [serverId] (from `placeableApps`, on the rail or not) whose homepage is on
     * [url]'s origin, or null (botiverse/slock #16957 `usableRailAppOn`).
     */
    fun usableAppOn(serverId: String, url: String): RaftRailPlaceApp? {
        if (!isServerEnabled(serverId)) return null
        val origin = raftRailPlaceOriginOf(url) ?: return null
        val snapshot = mutableState.value.byServer[serverId]?.snapshot ?: return null
        return snapshot.placeableApps.firstOrNull { app -> raftRailPlaceOriginOf(app.homepageUrl) == origin }
    }

    /**
     * Resolves an App place tap immediately when the last cached read already lists [clientId] as
     * a usable App on [serverId] (botiverse/slock #16960). If that cached snapshot is older than
     * [maxAgeMs], kicks off a non-blocking background refresh so the App opens without waiting on
     * network I/O. Returns null when the cached snapshot does not list [clientId] (or the flag is
     * off), letting the caller wait for [ensureFreshAndResolveApp].
     */
    fun resolveAppImmediateOrNull(
        serverId: String,
        clientId: String,
        maxAgeMs: Long = RaftRailPlacesFreshMs,
    ): RaftRailPlaceApp? {
        val cached = usableApp(serverId, clientId) ?: return null
        if (!isFresh(serverId, maxAgeMs)) {
            reloadAsync(serverId)
        }
        return cached
    }

    fun reloadAsync(serverId: String) {
        if (!isServerEnabled(serverId)) return
        scope.launch { reload(serverId) }
    }

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
            val result = dataSource.readServerPlaces(serverId)
            if (epoch != accountEpoch) return
            if (!isServerEnabled(serverId)) return
            if (gen != (saveGenerationByServer[serverId] ?: 0L)) return
            if ((savesInFlightByServer[serverId] ?: 0) > 0) return
            if (result is DataResult.Content && result.data != null) {
                val parsed = result.data.copy(places = withRaftRailKeptPlaces(result.data.places))
                val existing = mutableState.value.byServer[serverId]?.snapshot
                if (existing == null || parsed.placesVersion >= existing.placesVersion) {
                    updateServerEntry(serverId, ServerRailPlacesEntry(snapshot = parsed, loadedAtMs = nowMs()))
                }
            }
        } finally {
            if (reloadDeferredByServer[serverId] === deferred) {
                reloadDeferredByServer.remove(serverId)
            }
            deferred.complete(Unit)
        }
    }

    /** Ensures places for [serverId] were read within [maxAgeMs], then resolves [clientId] from `placeableApps`. */
    suspend fun ensureFreshAndResolveApp(
        serverId: String,
        clientId: String,
        maxAgeMs: Long = RaftRailPlacesFreshMs,
    ): RaftRailPlaceApp? {
        if (!isServerEnabled(serverId)) return null
        if (!isFresh(serverId, maxAgeMs)) {
            reload(serverId)
        }
        return usableApp(serverId, clientId)
    }

    /**
     * Saves [keys] (or null to reset to default) for [serverId] through `PATCH /servers/:id/sidebar-order`.
     * Updates state optimistically, serializes writes per server, handles 409 conflicts with the
     * server's authoritative list, and rolls back on other errors. Kept places (`chat`, `activity`)
     * are always retained on non-null saves.
     */
    suspend fun savePlaces(
        serverId: String,
        keys: List<String>?,
        emitEffect: Boolean = true,
    ): RailPlacesSaveOutcome {
        if (!isServerEnabled(serverId)) return RailPlacesSaveOutcome.Skipped
        val beforeEntry = mutableState.value.byServer[serverId] ?: return RailPlacesSaveOutcome.Skipped
        val canonicalKeys = keys?.mapNotNull(::canonicalRaftRailPlaceKey)?.distinct()?.let(::withRaftRailKeptPlaceKeys)
        val epoch = accountEpoch
        val gen = (saveGenerationByServer[serverId] ?: 0L) + 1L
        saveGenerationByServer[serverId] = gen
        if (canonicalKeys != null) {
            val optimistic =
                raftRailPlacesForKeys(
                    keys = canonicalKeys,
                    knownPlaces = beforeEntry.snapshot.places,
                    placeableApps = beforeEntry.snapshot.placeableApps,
                )
            updateServerEntry(
                serverId,
                beforeEntry.copy(snapshot = beforeEntry.snapshot.copy(places = optimistic)),
            )
        }
        val mutex = saveMutexByServer.getOrPut(serverId) { Mutex() }
        return mutex.withLock {
            if (epoch != accountEpoch) return@withLock RailPlacesSaveOutcome.Skipped
            val currentEntry = mutableState.value.byServer[serverId] ?: return@withLock RailPlacesSaveOutcome.Skipped
            savesInFlightByServer[serverId] = (savesInFlightByServer[serverId] ?: 0) + 1
            logger("rail_places_save_start serverId=$serverId version=${currentEntry.snapshot.placesVersion} count=${canonicalKeys?.size ?: -1}")
            val wire =
                try {
                    dataSource.saveServerPlaces(
                        serverId = serverId,
                        places = canonicalKeys,
                        placesVersion = currentEntry.snapshot.placesVersion,
                    )
                } finally {
                    if (epoch == accountEpoch) {
                        val remaining = (savesInFlightByServer[serverId] ?: 1) - 1
                        if (remaining <= 0) savesInFlightByServer.remove(serverId) else savesInFlightByServer[serverId] = remaining
                    }
                }
            if (epoch != accountEpoch) return@withLock RailPlacesSaveOutcome.Skipped
            when (wire) {
                is RailPlacesSaveWireResult.Saved -> {
                    val savedSnapshot = wire.snapshot.copy(places = withRaftRailKeptPlaces(wire.snapshot.places))
                    logger("rail_places_save_result serverId=$serverId outcome=saved version=${savedSnapshot.placesVersion}")
                    if (gen == (saveGenerationByServer[serverId] ?: 0L)) {
                        updateServerEntry(serverId, ServerRailPlacesEntry(snapshot = savedSnapshot, loadedAtMs = nowMs()))
                    } else {
                        // A newer queued write exists; update placesVersion & placeableApps so it chains cleanly.
                        val latest = mutableState.value.byServer[serverId]
                        if (latest != null) {
                            updateServerEntry(
                                serverId,
                                latest.copy(
                                    snapshot =
                                        latest.snapshot.copy(
                                            placesVersion = savedSnapshot.placesVersion,
                                            placeableApps = savedSnapshot.placeableApps,
                                        ),
                                    loadedAtMs = nowMs(),
                                ),
                            )
                        }
                    }
                    RailPlacesSaveOutcome.Saved
                }
                is RailPlacesSaveWireResult.Conflict -> {
                    logger("rail_places_save_result serverId=$serverId outcome=conflict version=${wire.payload.placesVersion}")
                    val latest = mutableState.value.byServer[serverId] ?: currentEntry
                    updateServerEntry(
                        serverId,
                        ServerRailPlacesEntry(
                            snapshot =
                                latest.snapshot.copy(
                                    places = withRaftRailKeptPlaces(wire.payload.places),
                                    placesVersion = wire.payload.placesVersion,
                                ),
                            loadedAtMs = nowMs(),
                        ),
                        effect = if (emitEffect) SharedRailPlacesEffect.Conflict(wire.traceId) else mutableState.value.effect,
                    )
                    RailPlacesSaveOutcome.Conflict
                }
                is RailPlacesSaveWireResult.Failed -> {
                    logger("rail_places_save_result serverId=$serverId outcome=failed code=${wire.error.code}")
                    if (gen == (saveGenerationByServer[serverId] ?: 0L)) {
                        val latest = mutableState.value.byServer[serverId] ?: beforeEntry
                        updateServerEntry(
                            serverId,
                            latest.copy(snapshot = latest.snapshot.copy(places = beforeEntry.snapshot.places)),
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
        entry: ServerRailPlacesEntry,
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

private fun JsonObject.strictStringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.strictNonNegativeIntOrNull(key: String): Int? {
    val primitive = (this[key] as? JsonPrimitive)?.takeIf { !it.isString } ?: return null
    val intValue = primitive.intOrNull ?: return null
    return intValue.takeIf { it >= 0 && primitive.content == intValue.toString() }
}
