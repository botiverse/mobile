package build.raft.app.data

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** web `ARTIFACT_DASHBOARD_VIEWS`: tiles (the default) or one row per Artifact. */
enum class ArtifactsHomeView(val wire: String) {
    Grid("grid"), List("list");

    companion object {
        val Default = Grid
    }
}

/** What the Artifacts home keeps on the device (web `artifactDashboardPrefs.ts`): the view and the sort. */
data class ArtifactsHomePrefs(
    val view: ArtifactsHomeView = ArtifactsHomeView.Default,
    val sort: ArtifactsHomeSort = ArtifactsHomeSort.Default,
)

/**
 * The web's one key (`raft:artifacts:dashboard:v1`) and shape (`view`, `listSort`), so the two read
 * alike in a diagnostics dump. Anything unreadable is the default, so a newer or older build never
 * breaks on another's value. The table's column widths are web-only (the app has no table).
 */
class ArtifactsHomePreferenceRepository(private val store: SharedPreferenceStore) {
    fun read(): ArtifactsHomePrefs = parseArtifactsHomePrefs(runCatching { store.getString(KEY) }.getOrNull())

    fun write(prefs: ArtifactsHomePrefs) {
        // Storage full or blocked: the choice holds until the page closes.
        runCatching { store.putString(KEY, encodeArtifactsHomePrefs(prefs)) }
    }

    companion object {
        const val KEY = "raft:artifacts:dashboard:v1"
    }
}

private val artifactsHomePrefsJson = Json { ignoreUnknownKeys = true }

internal fun parseArtifactsHomePrefs(raw: String?): ArtifactsHomePrefs {
    val root = raw?.let { runCatching { artifactsHomePrefsJson.parseToJsonElement(it) }.getOrNull() } as? JsonObject ?: return ArtifactsHomePrefs()
    val viewRaw = (root["view"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val sort = (root["listSort"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val view =
        if (viewRaw == "table") {
            ArtifactsHomeView.List
        } else {
            ArtifactsHomeView.entries.firstOrNull { it.wire == viewRaw } ?: ArtifactsHomeView.Default
        }
    return ArtifactsHomePrefs(
        view = view,
        sort = ArtifactsHomeSort.entries.firstOrNull { it.wire == sort } ?: ArtifactsHomeSort.Default,
    )
}

internal fun encodeArtifactsHomePrefs(prefs: ArtifactsHomePrefs): String =
    buildJsonObject {
        put("view", prefs.view.wire)
        put("listSort", prefs.sort.wire)
    }.toString()

// ---------------------------------------------------------------------------------------------
// Per-(account, server) persisted filters (web `artifactDashboardFilters.ts`, slock #16153/#16182)

/**
 * All 5 Artifacts home filters plus cached author metadata so empty reloads still render the chosen
 * author's name and avatar without an extra `dashboardAuthors` request.
 */
data class ArtifactsHomeFilters(
    val place: ArtifactsHomePlace? = null,
    val author: String? = null,
    val authorActor: ArtifactsHomeActor? = null,
    val status: ArtifactsHomeStatusFilter = ArtifactsHomeStatusFilter.Default,
    val search: String = "",
    val relevant: Boolean = false,
) {
    val isDefault: Boolean
        get() = place == null &&
            author == null &&
            authorActor == null &&
            status == ArtifactsHomeStatusFilter.Default &&
            search.isEmpty() &&
            !relevant

    /** True when any server-queried filter (`dashboardAll` narrowed) is active. */
    val narrowed: Boolean
        get() = place != null || author != null || search.trim().isNotEmpty() || relevant

    companion object {
        val Default = ArtifactsHomeFilters()
    }
}

private const val FILTERS_STORAGE_PREFIX = "raft:artifacts:filters:v1:"
private const val FILTERS_INDEX_PREFIX = "raft:artifacts:filters:v1:servers:"
private const val FILTERS_STORAGE_VERSION = 1
internal const val ARTIFACTS_HOME_PERSISTED_MAX_SERVERS = 16
private const val MAX_ID_LEN = 200
private const val MAX_NAME_LEN = 200
private const val MAX_UPDATED_AT_LEN = 100
private const val MAX_URL_LEN = 2048

private val SAFE_KEY_PART = Regex("^[0-9A-Za-z_-]+$")
private val AUTHOR_UUID_REGEX = Regex("^(user|agent):[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
private val VALID_CONVERSATION_TYPES = setOf("channel", "private", "dm", "joint")
private val VALID_GONE_VALUES = setOf("deleted", "system")

/** Exact web storage key: `raft:artifacts:filters:v1:<readerId>:<serverId>`. */
internal fun artifactsHomeFiltersStorageKey(readerId: String?, serverId: String?): String? {
    if (readerId.isNullOrEmpty() || serverId.isNullOrEmpty()) return null
    if (!SAFE_KEY_PART.matches(readerId) || !SAFE_KEY_PART.matches(serverId)) return null
    return "$FILTERS_STORAGE_PREFIX$readerId:$serverId"
}

private fun filtersIndexKey(readerId: String?): String? {
    if (readerId.isNullOrEmpty() || !SAFE_KEY_PART.matches(readerId)) return null
    return "$FILTERS_INDEX_PREFIX$readerId"
}

internal fun isValidArtifactsHomeAuthorFilter(value: String?): Boolean =
    value != null && (value == "me" || value == "my-agents" || AUTHOR_UUID_REGEX.matches(value))

internal fun normalizeArtifactsHomeAuthorFilter(value: String?): String? =
    if (isValidArtifactsHomeAuthorFilter(value)) value!!.lowercase() else null

private fun isSafeArtifactAvatarUrl(url: String): Boolean =
    url.startsWith("https://") ||
        url.startsWith("http://") ||
        (url.startsWith("/") && !url.startsWith("//")) ||
        url.startsWith("pixel:") ||
        url.startsWith("pixel-human:")

/** Clamps a search string to [ARTIFACTS_HOME_SEARCH_MAX_CHARS] Unicode code points (web `clampSearch`). */
internal fun clampArtifactsHomeSearch(raw: String?): String {
    if (raw == null) return ""
    val trimmed = raw.trim()
    val trimmedCodePoints = countCodePoints(trimmed)
    if (trimmedCodePoints > ARTIFACTS_HOME_SEARCH_MAX_CHARS) {
        return takeCodePoints(trimmed, ARTIFACTS_HOME_SEARCH_MAX_CHARS)
    }
    if (raw.length > ARTIFACTS_HOME_SEARCH_MAX_CHARS * 4) return trimmed
    return raw
}

private fun countCodePoints(text: String): Int {
    var count = 0
    var i = 0
    while (i < text.length) {
        val c = text[i]
        i += if (c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) 2 else 1
        count++
    }
    return count
}

private fun takeCodePoints(text: String, maxCodePoints: Int): String {
    var count = 0
    var i = 0
    while (i < text.length && count < maxCodePoints) {
        val c = text[i]
        i += if (c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) 2 else 1
        count++
    }
    return text.substring(0, i)
}

private fun JsonObject.stringOrNull(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.strictIntOrNull(key: String): Int? {
    val prim = this[key] as? JsonPrimitive ?: return null
    if (prim.isString) return null
    val asInt = prim.intOrNull ?: return null
    // Reject floats like `1.5` (intOrNull already returns null for `1.5`, double-check raw text).
    if ('.' in prim.content || 'e' in prim.content || 'E' in prim.content) return null
    return asInt
}

private fun parseStoredBehalf(raw: JsonObject?): ArtifactsHomeBehalf? {
    if (raw == null) return null
    val id = raw.stringOrNull("id")?.takeIf { it.isNotBlank() && it.length <= MAX_ID_LEN } ?: return null
    val name = raw.stringOrNull("name")?.takeIf { it.length <= MAX_NAME_LEN } ?: return null
    val displayName = raw.stringOrNull("displayName")?.takeIf { it.length <= MAX_NAME_LEN } ?: return null
    // Keep `name` validated as web does; domain model holds id + displayName.
    return ArtifactsHomeBehalf(id = id, displayName = displayName.ifEmpty { name })
}

private fun parseStoredActor(raw: JsonObject?): ArtifactsHomeActor? {
    if (raw == null) return null
    val type = raw.stringOrNull("type")?.takeIf { it == "user" || it == "agent" } ?: return null
    val id = raw.stringOrNull("id")?.takeIf { it.isNotBlank() && it.length <= MAX_ID_LEN } ?: return null
    val name = raw.stringOrNull("name")?.takeIf { it.length <= MAX_NAME_LEN } ?: return null
    val displayName = raw.stringOrNull("displayName")?.takeIf { it.length <= MAX_NAME_LEN } ?: return null
    val avatarElem = raw["avatarUrl"]
    val avatarUrl =
        when (avatarElem) {
            null, JsonNull -> null
            is JsonPrimitive -> {
                if (!avatarElem.isString) return null
                val url = avatarElem.content
                if (url.length > MAX_URL_LEN || !isSafeArtifactAvatarUrl(url)) return null
                url
            }
            else -> return null
        }
    val goneElem = raw["gone"]
    val gone =
        when (goneElem) {
            null, JsonNull -> null
            is JsonPrimitive -> {
                if (!goneElem.isString || goneElem.content !in VALID_GONE_VALUES) return null
                goneElem.content
            }
            else -> return null
        }
    val behalfElem = raw["onBehalfOf"]
    val onBehalfOf =
        when (behalfElem) {
            null, JsonNull -> null
            is JsonObject -> {
                if (type != "agent") return null
                parseStoredBehalf(behalfElem) ?: return null
            }
            else -> return null
        }
    return ArtifactsHomeActor(
        type = type,
        id = id,
        name = name,
        displayName = displayName,
        avatarUrl = avatarUrl,
        gone = gone,
        onBehalfOf = onBehalfOf,
    )
}

private fun sanitizeStoredAuthorActor(author: String?, actor: ArtifactsHomeActor?): ArtifactsHomeActor? {
    if (author == null || author == "me" || author == "my-agents" || actor == null) return null
    val id = actor.id?.takeIf { it.isNotBlank() && it.length <= MAX_ID_LEN } ?: return null
    if (actor.type != "user" && actor.type != "agent") return null
    if (actor.name.length > MAX_NAME_LEN || actor.displayName.length > MAX_NAME_LEN) return null
    if (actor.avatarUrl != null && (actor.avatarUrl.length > MAX_URL_LEN || !isSafeArtifactAvatarUrl(actor.avatarUrl))) return null
    if (actor.gone != null && actor.gone !in VALID_GONE_VALUES) return null
    if (actor.onBehalfOf != null && actor.type != "agent") return null
    val filterValue = "${actor.type}:$id".lowercase()
    return actor.takeIf { filterValue == author }
}

private fun parseStoredPlace(raw: JsonObject?): ArtifactsHomePlace? {
    if (raw == null) return null
    return when (raw.stringOrNull("kind")) {
        "home" -> {
            val home = raw.stringOrNull("home") ?: return null
            val scope = ArtifactsHomeScope.entries.firstOrNull { it.wire == home } ?: return null
            ArtifactsHomePlace.Home(scope)
        }
        "conversation" -> {
            val c = raw["conversation"] as? JsonObject ?: return null
            val id = c.stringOrNull("id")?.takeIf { it.isNotBlank() && it.length <= MAX_ID_LEN } ?: return null
            val name = c.stringOrNull("name")?.takeIf { it.length <= MAX_NAME_LEN } ?: return null
            val type = c.stringOrNull("type")?.takeIf { it in VALID_CONVERSATION_TYPES } ?: return null
            val count = c.strictIntOrNull("count")?.takeIf { it >= 0 } ?: return null
            val updatedAt = c.stringOrNull("updatedAt")?.takeIf { it.isNotBlank() && it.length <= MAX_UPDATED_AT_LEN } ?: return null
            if (type != "joint" && type != "dm" && name.isBlank()) return null
            val archivedElem = c["archived"]
            val archived =
                when (archivedElem) {
                    null, JsonNull -> false
                    is JsonPrimitive -> {
                        if (archivedElem.isString) return null
                        archivedElem.booleanOrNull ?: return null
                    }
                    else -> return null
                }
            if (type != "dm" && (c.containsKey("peerDisplayName") && c["peerDisplayName"] != JsonNull || c.containsKey("peer") && c["peer"] != JsonNull)) {
                return null
            }
            val peerDisplayElem = c["peerDisplayName"]
            val peerDisplayName =
                when (peerDisplayElem) {
                    null, JsonNull -> null
                    is JsonPrimitive -> {
                        if (!peerDisplayElem.isString || peerDisplayElem.content.length > MAX_NAME_LEN) return null
                        peerDisplayElem.content
                    }
                    else -> return null
                }
            val peerElem = c["peer"]
            val peer =
                when (peerElem) {
                    null, JsonNull -> null
                    is JsonObject -> parseStoredActor(peerElem) ?: return null
                    else -> return null
                }
            ArtifactsHomePlace.Conversation(
                ArtifactsHomeConversation(
                    id = id,
                    name = name,
                    type = type,
                    peerDisplayName = peerDisplayName,
                    peer = peer,
                    count = count,
                    updatedAt = updatedAt,
                    archived = archived,
                ),
            )
        }
        else -> null
    }
}

/** Parse and strictly validate stored filter JSON (web `parseStoredDashboardFilters`). */
internal fun parseStoredArtifactsHomeFilters(raw: String?): ArtifactsHomeFilters {
    if (raw.isNullOrBlank()) return ArtifactsHomeFilters.Default
    val root = runCatching { artifactsHomePrefsJson.parseToJsonElement(raw) }.getOrNull() as? JsonObject ?: return ArtifactsHomeFilters.Default
    val versionElem = root["v"]
    if (versionElem != null && versionElem != JsonNull) {
        val v = root.strictIntOrNull("v")
        if (v != FILTERS_STORAGE_VERSION) return ArtifactsHomeFilters.Default
    }
    val status = ArtifactsHomeStatusFilter.fromWire(root.stringOrNull("status"))
    val author = normalizeArtifactsHomeAuthorFilter(root.stringOrNull("author"))
    val authorActor = sanitizeStoredAuthorActor(author, parseStoredActor(root["authorActor"] as? JsonObject))
    val relevantElem = root["relevant"] as? JsonPrimitive
    val relevant = relevantElem != null && !relevantElem.isString && relevantElem.booleanOrNull == true
    val candidate =
        ArtifactsHomeFilters(
            place = parseStoredPlace(root["place"] as? JsonObject),
            author = author,
            authorActor = authorActor,
            status = status,
            search = clampArtifactsHomeSearch(root.stringOrNull("search")),
            relevant = relevant,
        )
    return if (candidate.isDefault) ArtifactsHomeFilters.Default else candidate
}

private fun encodeActorJson(actor: ArtifactsHomeActor): JsonObject =
    buildJsonObject {
        put("type", actor.type)
        actor.id?.let { put("id", it) }
        put("name", actor.name)
        put("displayName", actor.displayName)
        val avatar = actor.avatarUrl
        if (avatar != null) put("avatarUrl", avatar) else put("avatarUrl", JsonNull)
        actor.gone?.let { put("gone", it) }
        actor.onBehalfOf?.let { behalf ->
            putJsonObject("onBehalfOf") {
                put("id", behalf.id)
                put("name", behalf.displayName)
                put("displayName", behalf.displayName)
            }
        }
    }

internal fun encodeStoredArtifactsHomeFilters(filters: ArtifactsHomeFilters, atMillis: Long): String =
    buildJsonObject {
        put("v", FILTERS_STORAGE_VERSION)
        put("at", atMillis)
        when (val place = filters.place) {
            null -> put("place", JsonNull)
            is ArtifactsHomePlace.Home ->
                putJsonObject("place") {
                    put("kind", "home")
                    put("home", place.scope.wire)
                }
            is ArtifactsHomePlace.Conversation ->
                putJsonObject("place") {
                    put("kind", "conversation")
                    putJsonObject("conversation") {
                        val c = place.conversation
                        put("id", c.id)
                        put("name", c.name)
                        put("type", c.type)
                        put("count", c.count.coerceAtLeast(0))
                        put("updatedAt", c.updatedAt?.takeIf { it.isNotBlank() } ?: "1970-01-01T00:00:00.000Z")
                        if (c.archived) put("archived", true)
                        if (c.type == "dm") {
                            c.peerDisplayName?.let { put("peerDisplayName", it) }
                            c.peer?.let { put("peer", encodeActorJson(it)) }
                        }
                    }
                }
        }
        if (filters.author != null) put("author", filters.author) else put("author", JsonNull)
        val actor = sanitizeStoredAuthorActor(filters.author, filters.authorActor)
        if (actor != null) put("authorActor", encodeActorJson(actor)) else put("authorActor", JsonNull)
        put("status", filters.status.wire)
        put("search", clampArtifactsHomeSearch(filters.search))
        put("relevant", filters.relevant)
    }.toString()

/**
 * Per-(readerId, serverId) persisted Artifacts home filters (web `artifactDashboardFilters.ts`,
 * slock #16153/#16182):
 * - Reads never mutate storage (write-only 16-server LRU pruning per reader).
 * - Returning to all defaults removes the stored entry cleanly.
 * - Quota/blocked storage falls back to in-memory per-session state (including shadowing defaults
 *   if removal throws, so stale disk entries never resurrect cleared filters).
 * - Account isolation via `(readerId, serverId)` keys; [dropServer] cleans up on server access loss
 *   and [forget] clears all servers for a signed-out reader.
 */
internal class ArtifactsHomeFilterRepository(
    private val store: SharedPreferenceStore,
    private val nowMillis: () -> Long = ::defaultNowMillis,
) {
    private val lock = SynchronizedObject()
    private val memoryFallback = LinkedHashMap<String, ArtifactsHomeFilters>()

    fun read(readerId: String?, serverId: String?): ArtifactsHomeFilters {
        val cleanServer = serverId?.trim()?.takeIf { it.isNotEmpty() } ?: return ArtifactsHomeFilters.Default
        val cleanReader = readerId?.trim()?.takeIf { it.isNotEmpty() }
        return synchronized(lock) {
            memoryFallback[memKey(cleanReader, cleanServer)]?.let { return@synchronized it }
            val key = artifactsHomeFiltersStorageKey(cleanReader, cleanServer) ?: return@synchronized ArtifactsHomeFilters.Default
            val raw = runCatching { store.getString(key) }.getOrNull() ?: return@synchronized ArtifactsHomeFilters.Default
            parseStoredArtifactsHomeFilters(raw)
        }
    }

    fun write(
        readerId: String?,
        serverId: String?,
        filters: ArtifactsHomeFilters,
        atMillis: Long = nowMillis(),
    ) {
        val cleanServer = serverId?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val cleanReader = readerId?.trim()?.takeIf { it.isNotEmpty() }
        val normalizedAuthor = normalizeArtifactsHomeAuthorFilter(filters.author)
        val normalized =
            ArtifactsHomeFilters(
                place = filters.place,
                author = normalizedAuthor,
                authorActor = sanitizeStoredAuthorActor(normalizedAuthor, filters.authorActor),
                status = filters.status,
                search = clampArtifactsHomeSearch(filters.search),
                relevant = filters.relevant,
            ).let { if (it.isDefault) ArtifactsHomeFilters.Default else it }
        synchronized(lock) {
            val mk = memKey(cleanReader, cleanServer)
            val key = artifactsHomeFiltersStorageKey(cleanReader, cleanServer)
            if (normalized.isDefault) {
                if (key != null) {
                    val removed = runCatching { store.putString(key, null) }.isSuccess
                    if (removed) {
                        memoryFallback.remove(mk)
                        removeFromIndexLocked(cleanReader!!, cleanServer)
                    } else {
                        // Keep defaults in memoryFallback so a stale persisted entry does not shadow reset.
                        putMemoryFallbackLocked(cleanReader, cleanServer, ArtifactsHomeFilters.Default, shadowDefault = true)
                    }
                } else {
                    memoryFallback.remove(mk)
                }
                return@synchronized
            }
            if (key != null) {
                val encoded = encodeStoredArtifactsHomeFilters(normalized, atMillis)
                val wrote = runCatching { store.putString(key, encoded) }.isSuccess
                if (wrote) {
                    memoryFallback.remove(mk)
                    recordAndPruneIndexLocked(cleanReader!!, cleanServer, atMillis)
                    return@synchronized
                }
            }
            putMemoryFallbackLocked(cleanReader, cleanServer, normalized, shadowDefault = false)
        }
    }

    /** Clears stored filters for [serverId] (and [readerId], or all readers tracked in memory when null). */
    fun dropServer(serverId: String, readerId: String? = null) {
        val cleanServer = serverId.trim().takeIf { it.isNotEmpty() } ?: return
        val cleanReader = readerId?.trim()?.takeIf { it.isNotEmpty() }
        synchronized(lock) {
            if (cleanReader != null) {
                memoryFallback.remove(memKey(cleanReader, cleanServer))
                artifactsHomeFiltersStorageKey(cleanReader, cleanServer)?.let { key ->
                    runCatching { store.putString(key, null) }
                }
                removeFromIndexLocked(cleanReader, cleanServer)
            } else {
                val suffix = "\u0000$cleanServer"
                val matching = memoryFallback.keys.filter { it.endsWith(suffix) }
                matching.forEach(memoryFallback::remove)
            }
        }
    }

    /** Forgets all stored Artifacts filters for [readerId] across every server. */
    fun forget(readerId: String?) {
        val cleanReader = readerId?.trim()?.takeIf { it.isNotEmpty() } ?: return
        synchronized(lock) {
            val memPrefix = "$cleanReader\u0000"
            memoryFallback.keys.filter { it.startsWith(memPrefix) }.forEach(memoryFallback::remove)
            val indexKey = filtersIndexKey(cleanReader) ?: return@synchronized
            val entries = readIndexLocked(cleanReader)
            entries.keys.forEach { serverId ->
                artifactsHomeFiltersStorageKey(cleanReader, serverId)?.let { key ->
                    runCatching { store.putString(key, null) }
                }
            }
            runCatching { store.putString(indexKey, null) }
        }
    }

    /** Drops only in-memory fallback caches without touching backing storage (simulates reload in tests). */
    internal fun clearMemoryOnlyForTests() {
        synchronized(lock) { memoryFallback.clear() }
    }

    private fun memKey(readerId: String?, serverId: String): String = "${readerId.orEmpty()}\u0000$serverId"

    private fun putMemoryFallbackLocked(
        readerId: String?,
        serverId: String,
        filters: ArtifactsHomeFilters,
        shadowDefault: Boolean,
    ) {
        val prefix = "${readerId.orEmpty()}\u0000"
        val mk = "$prefix$serverId"
        memoryFallback.remove(mk)
        if (!shadowDefault && filters.isDefault) return
        memoryFallback[mk] = filters
        var count = 0
        var oldestForReader: String? = null
        for (key in memoryFallback.keys) {
            if (!key.startsWith(prefix)) continue
            if (oldestForReader == null) oldestForReader = key
            count++
        }
        if (count > ARTIFACTS_HOME_PERSISTED_MAX_SERVERS && oldestForReader != null) {
            memoryFallback.remove(oldestForReader)
        }
    }

    private fun readIndexLocked(readerId: String): LinkedHashMap<String, Long> {
        val key = filtersIndexKey(readerId) ?: return LinkedHashMap()
        val raw = runCatching { store.getString(key) }.getOrNull() ?: return LinkedHashMap()
        val root = runCatching { artifactsHomePrefsJson.parseToJsonElement(raw) }.getOrNull() as? JsonObject ?: return LinkedHashMap()
        val result = LinkedHashMap<String, Long>()
        for ((serverId, elem) in root) {
            if (serverId.isBlank() || serverId.length > MAX_ID_LEN) continue
            val at = (elem as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: 0L
            result[serverId] = at
        }
        return result
    }

    private fun writeIndexLocked(readerId: String, entries: Map<String, Long>) {
        val key = filtersIndexKey(readerId) ?: return
        if (entries.isEmpty()) {
            runCatching { store.putString(key, null) }
        } else {
            val encoded =
                buildJsonObject {
                    entries.forEach { (serverId, at) -> put(serverId, at) }
                }.toString()
            runCatching { store.putString(key, encoded) }
        }
    }

    private fun removeFromIndexLocked(readerId: String, serverId: String) {
        val entries = readIndexLocked(readerId)
        if (entries.remove(serverId) != null) {
            writeIndexLocked(readerId, entries)
        }
    }

    private fun recordAndPruneIndexLocked(readerId: String, currentServerId: String, currentAt: Long) {
        val entries = readIndexLocked(readerId)
        entries[currentServerId] = currentAt
        if (entries.size > ARTIFACTS_HOME_PERSISTED_MAX_SERVERS) {
            val sorted =
                entries.entries.sortedWith(
                    compareBy<Map.Entry<String, Long>>({ it.value }, { if (it.key == currentServerId) 1 else 0 }, { it.key }),
                )
            val excess = sorted.size - ARTIFACTS_HOME_PERSISTED_MAX_SERVERS
            for (i in 0 until excess) {
                val doomedServer = sorted[i].key
                entries.remove(doomedServer)
                artifactsHomeFiltersStorageKey(readerId, doomedServer)?.let { key ->
                    runCatching { store.putString(key, null) }
                }
            }
        }
        writeIndexLocked(readerId, entries)
    }
}

// ---------------------------------------------------------------------------------------------
// Per-(account, server) remembered settled support (web `useArtifactHomeGroups.ts`, slock #16182)

private const val SETTLED_SEEN_PREFIX = "raft:artifacts:settled-seen:v1:"

internal fun artifactsHomeSettledSeenStorageKey(readerId: String?): String? {
    if (readerId.isNullOrEmpty() || !SAFE_KEY_PART.matches(readerId)) return null
    return "$SETTLED_SEEN_PREFIX$readerId"
}

/**
 * Remembers per `(readerId, serverId)` whether settled support (`card.settled` present on loaded
 * rows) was seen on that server so filtering to 0 rows or reloading preserves the Status picker
 * without leaking across servers or accounts. Enforces a 16-server LRU cap per reader.
 */
internal class ArtifactsHomeSettledSeenRepository(private val store: SharedPreferenceStore) {
    private val lock = SynchronizedObject()
    private val seenByReader = HashMap<String, LinkedHashSet<String>>()
    private val hydratedReaders = HashSet<String>()

    fun read(readerId: String?, serverId: String?): Boolean {
        val cleanServer = serverId?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val cleanReader = readerId?.trim()?.takeIf { it.isNotEmpty() }
        return synchronized(lock) { ensureLoadedLocked(cleanReader).contains(cleanServer) }
    }

    /**
     * Records observed settled support for [serverId] once [rowsLoaded] is true:
     * - [detected] == true: records [serverId] as most-recently-seen (16-server LRU cap).
     * - [rowsLoaded] && ![detected]: removes [serverId] (server flag turned off).
     * Returns the resulting `settledEnabled` value.
     */
    fun observe(readerId: String?, serverId: String?, detected: Boolean, rowsLoaded: Boolean): Boolean {
        val cleanServer = serverId?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val cleanReader = readerId?.trim()?.takeIf { it.isNotEmpty() }
        return synchronized(lock) {
            val servers = ensureLoadedLocked(cleanReader)
            if (detected) {
                val alreadyLast = servers.contains(cleanServer) && servers.lastOrNull() == cleanServer
                if (!alreadyLast) {
                    servers.remove(cleanServer)
                    servers.add(cleanServer)
                    while (servers.size > ARTIFACTS_HOME_PERSISTED_MAX_SERVERS) {
                        val oldest = servers.firstOrNull() ?: break
                        servers.remove(oldest)
                    }
                    persistLocked(cleanReader, servers)
                }
                true
            } else if (rowsLoaded) {
                if (servers.remove(cleanServer)) {
                    persistLocked(cleanReader, servers)
                }
                false
            } else {
                servers.contains(cleanServer)
            }
        }
    }

    fun dropServer(serverId: String, readerId: String? = null) {
        val cleanServer = serverId.trim().takeIf { it.isNotEmpty() } ?: return
        val cleanReader = readerId?.trim()?.takeIf { it.isNotEmpty() }
        synchronized(lock) {
            if (cleanReader != null) {
                val servers = ensureLoadedLocked(cleanReader)
                if (servers.remove(cleanServer)) persistLocked(cleanReader, servers)
            } else {
                seenByReader.forEach { (rk, servers) ->
                    if (servers.remove(cleanServer) && rk.isNotEmpty()) persistLocked(rk, servers)
                }
            }
        }
    }

    fun forget(readerId: String?) {
        val cleanReader = readerId?.trim()?.takeIf { it.isNotEmpty() } ?: return
        synchronized(lock) {
            seenByReader.remove(cleanReader)
            hydratedReaders.remove(cleanReader)
            artifactsHomeSettledSeenStorageKey(cleanReader)?.let { key ->
                runCatching { store.putString(key, null) }
            }
        }
    }

    internal fun clearMemoryOnlyForTests() {
        synchronized(lock) {
            seenByReader.clear()
            hydratedReaders.clear()
        }
    }

    private fun ensureLoadedLocked(readerId: String?): LinkedHashSet<String> {
        val rk = readerId.orEmpty()
        val existing = seenByReader[rk]
        if (existing != null && (readerId == null || rk in hydratedReaders)) return existing
        val set = existing ?: LinkedHashSet()
        val key = artifactsHomeSettledSeenStorageKey(readerId)
        if (key != null && rk !in hydratedReaders) {
            hydratedReaders += rk
            val raw = runCatching { store.getString(key) }.getOrNull()
            parseStoredSettledServers(raw).forEach { set.add(it) }
        }
        seenByReader[rk] = set
        return set
    }

    private fun persistLocked(readerId: String?, servers: Set<String>) {
        val key = artifactsHomeSettledSeenStorageKey(readerId) ?: return
        if (servers.isEmpty()) {
            runCatching { store.putString(key, null) }
        } else {
            val encoded = buildJsonArray { servers.forEach { add(JsonPrimitive(it)) } }.toString()
            runCatching { store.putString(key, encoded) }
        }
    }
}

internal fun parseStoredSettledServers(raw: String?): List<String> {
    if (raw.isNullOrBlank()) return emptyList()
    val array = runCatching { artifactsHomePrefsJson.parseToJsonElement(raw) }.getOrNull() as? JsonArray ?: return emptyList()
    val unique = LinkedHashSet<String>()
    for (entry in array) {
        val id = (entry as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() && it.length <= MAX_ID_LEN } ?: continue
        unique.remove(id)
        unique.add(id)
    }
    val list = unique.toList()
    return if (list.size > ARTIFACTS_HOME_PERSISTED_MAX_SERVERS) list.takeLast(ARTIFACTS_HOME_PERSISTED_MAX_SERVERS) else list
}
