package build.raft.app

import build.raft.app.data.RaftLastCountsPersistStorage
import build.raft.app.data.RaftRailAppPlacePrefix
import build.raft.app.data.SharedPreferenceStore
import build.raft.app.data.defaultNowMillis
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Maximum number of recently opened Artifact / Raft App entries kept per account × server. */
const val MOBILE_RECENTLY_USED_MAX_ENTRIES: Int = 4

/** Lists every persisted recently-used scope written to [SharedPreferenceStore]. */
internal const val MOBILE_RECENTLY_USED_INDEX_KEY: String = "mobileRecentlyUsed:v1:index"

private const val MOBILE_RECENTLY_USED_VERSION: Int = 1
private const val MOBILE_RECENTLY_USED_MAX_SERVERS: Int = 64

/** Per-account × per-server preference key matching [RaftLastCountsPersistStorage]'s `:user:<id>:server:<id>` scope. */
internal fun mobileRecentlyUsedStorageKey(accountId: String, serverId: String): String =
    "mobileRecentlyUsed:v1:user:$accountId:server:$serverId"

/** Account × server key for [MobileRecentlyUsedStore]. */
data class MobileRecentlyUsedScope(
    val accountId: String,
    val serverId: String,
)

/**
 * One recently opened Artifact or installed Raft App recorded by [MobileRecentlyUsedStore] for the
 * mobile Server Tab Popover (`#proj-mobile:#329`).
 */
sealed interface MobileRecentlyUsedEntry {
    /** Canonical dedupe & tab-bar exclusion key (`artifact:<id>` or `app:<lowercase-clientId>`). */
    val placeKey: String

    /** Human-readable title displayed in the Recently used section. */
    val title: String

    data class Artifact(
        val id: String,
        override val title: String,
    ) : MobileRecentlyUsedEntry {
        override val placeKey: String get() = "artifact:$id"
    }

    data class App(
        val clientId: String,
        val name: String,
        val iconUrl: String? = null,
        val homepageUrl: String,
    ) : MobileRecentlyUsedEntry {
        override val placeKey: String get() = "$RaftRailAppPlacePrefix${clientId.lowercase()}"
        override val title: String get() = name
    }
}

internal fun encodeMobileRecentlyUsedEntries(entries: List<MobileRecentlyUsedEntry>): String =
    buildJsonObject {
        put("v", MOBILE_RECENTLY_USED_VERSION)
        putJsonArray("items") {
            for (entry in entries) {
                add(
                    when (entry) {
                        is MobileRecentlyUsedEntry.Artifact ->
                            buildJsonObject {
                                put("kind", "artifact")
                                put("id", entry.id)
                                put("title", entry.title)
                            }
                        is MobileRecentlyUsedEntry.App ->
                            buildJsonObject {
                                put("kind", "app")
                                put("clientId", entry.clientId)
                                put("name", entry.name)
                                entry.iconUrl?.let { put("iconUrl", it) }
                                put("homepageUrl", entry.homepageUrl)
                            }
                    },
                )
            }
        }
    }.toString()

internal fun decodeMobileRecentlyUsedEntries(
    raw: String?,
    maxEntries: Int = MOBILE_RECENTLY_USED_MAX_ENTRIES,
): List<MobileRecentlyUsedEntry> {
    if (raw.isNullOrBlank()) return emptyList()
    val root = runCatching { Json.parseToJsonElement(raw) }.getOrNull() as? JsonObject ?: return emptyList()
    val version = (root["v"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
    val items = root["items"] as? JsonArray
    if (version != MOBILE_RECENTLY_USED_VERSION || items == null) return emptyList()
    val limit = maxEntries.coerceAtLeast(1)
    val decoded = ArrayList<MobileRecentlyUsedEntry>(minOf(items.size, limit))
    val seenKeys = HashSet<String>()
    for (element in items) {
        if (decoded.size >= limit) break
        val obj = element as? JsonObject ?: continue
        val entry =
            when (obj.jsonString("kind")) {
                "artifact" -> {
                    val id = obj.jsonString("id")?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                    val title = obj.jsonString("title")?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                    MobileRecentlyUsedEntry.Artifact(id = id, title = title)
                }
                "app" -> {
                    val clientId = obj.jsonString("clientId")?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: continue
                    val name = obj.jsonString("name")?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                    val homepageUrl = obj.jsonString("homepageUrl")?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                    val iconUrl = obj.jsonString("iconUrl")?.trim()?.takeIf { it.isNotEmpty() }
                    MobileRecentlyUsedEntry.App(
                        clientId = clientId,
                        name = name,
                        iconUrl = iconUrl,
                        homepageUrl = homepageUrl,
                    )
                }
                else -> continue
            }
        if (seenKeys.add(entry.placeKey)) {
            decoded += entry
        }
    }
    return decoded
}

private fun JsonObject.jsonString(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

/**
 * Pure-Kotlin per-account × per-server recently-used store for mobile navigation (`#proj-mobile:#329`):
 * records up to [MOBILE_RECENTLY_USED_MAX_ENTRIES] (4) most-recently-opened Artifact and Raft App
 * entries, deduplicating by kind + id, moving re-opened items to the front, filtering out any place
 * key currently on the tab bar, and persisting across restarts when backed by [SharedPreferenceStore].
 */
class MobileRecentlyUsedStore(
    private val maxEntries: Int = MOBILE_RECENTLY_USED_MAX_ENTRIES,
    preferenceStore: SharedPreferenceStore? = null,
    nowMillis: () -> Long = ::defaultNowMillis,
) {
    private val lock = SynchronizedObject()
    private val loadedScopes = HashSet<MobileRecentlyUsedScope>()
    private val storage: RaftLastCountsPersistStorage? =
        preferenceStore?.let { store ->
            RaftLastCountsPersistStorage(
                store = store,
                indexKey = MOBILE_RECENTLY_USED_INDEX_KEY,
                nowMillis = nowMillis,
                maxAgeMillis = Long.MAX_VALUE,
                maxServersPerUser = MOBILE_RECENTLY_USED_MAX_SERVERS,
            )
        }
    private val mutableState = MutableStateFlow<Map<MobileRecentlyUsedScope, List<MobileRecentlyUsedEntry>>>(emptyMap())
    val state: StateFlow<Map<MobileRecentlyUsedScope, List<MobileRecentlyUsedEntry>>> = mutableState.asStateFlow()

    fun recordArtifact(
        accountId: String?,
        serverId: String?,
        artifactId: String,
        title: String,
    ) {
        val scope = scopeOrNull(accountId, serverId) ?: return
        val normalizedId = artifactId.trim().takeIf { it.isNotEmpty() } ?: return
        val normalizedTitle = title.trim().takeIf { it.isNotEmpty() } ?: return
        record(scope, MobileRecentlyUsedEntry.Artifact(id = normalizedId, title = normalizedTitle))
    }

    fun recordApp(
        accountId: String?,
        serverId: String?,
        clientId: String,
        name: String,
        iconUrl: String? = null,
        homepageUrl: String,
    ) {
        val scope = scopeOrNull(accountId, serverId) ?: return
        val normalizedClientId = clientId.trim().lowercase().takeIf { it.isNotEmpty() } ?: return
        val normalizedName = name.trim().takeIf { it.isNotEmpty() } ?: return
        val normalizedHomepageUrl = homepageUrl.trim().takeIf { it.isNotEmpty() } ?: return
        val normalizedIconUrl = iconUrl?.trim()?.takeIf { it.isNotEmpty() }
        record(
            scope,
            MobileRecentlyUsedEntry.App(
                clientId = normalizedClientId,
                name = normalizedName,
                iconUrl = normalizedIconUrl,
                homepageUrl = normalizedHomepageUrl,
            ),
        )
    }

    /**
     * Returns recently opened entries for ([accountId], [serverId]) in most-recent-first order,
     * excluding any entry whose canonical [MobileRecentlyUsedEntry.placeKey] is in [excludedPlaceKeys].
     */
    fun recentItems(
        accountId: String?,
        serverId: String?,
        excludedPlaceKeys: Set<String> = emptySet(),
    ): List<MobileRecentlyUsedEntry> {
        val scope = scopeOrNull(accountId, serverId) ?: return emptyList()
        val entries = synchronized(lock) { ensureLoadedLocked(scope) }
        if (entries.isEmpty() || excludedPlaceKeys.isEmpty()) return entries
        val normalizedExcluded = excludedPlaceKeys.mapTo(hashSetOf(), ::normalizeExcludedPlaceKey)
        return entries.filter { entry -> normalizeExcludedPlaceKey(entry.placeKey) !in normalizedExcluded }
    }

    fun clearServer(accountId: String?, serverId: String?) {
        val scope = scopeOrNull(accountId, serverId) ?: return
        synchronized(lock) {
            loadedScopes -= scope
            val current = mutableState.value
            if (scope in current) mutableState.value = current - scope
            storage?.let { store ->
                runCatching { store.remove(mobileRecentlyUsedStorageKey(scope.accountId, scope.serverId)) }
            }
        }
    }

    /** Drops persisted and in-memory entries when access to [serverId] is lost (for [accountId], or all accounts if null). */
    fun dropServer(serverId: String, accountId: String?) {
        val normalizedServer = serverId.trim().takeIf { it.isNotEmpty() } ?: return
        val normalizedAccount = accountId?.trim()?.takeIf { it.isNotEmpty() }
        synchronized(lock) {
            loadedScopes.removeAll { scope ->
                scope.serverId == normalizedServer && (normalizedAccount == null || scope.accountId == normalizedAccount)
            }
            mutableState.value =
                mutableState.value.filterKeys { scope ->
                    !(scope.serverId == normalizedServer && (normalizedAccount == null || scope.accountId == normalizedAccount))
                }
            storage?.let { store ->
                runCatching { store.dropServer(normalizedServer, normalizedAccount) }
            }
        }
    }

    /** Clears in-memory cache on sign-out while keeping account-scoped entries on disk (owner decision 2026-10-27). */
    fun clearMemory() {
        synchronized(lock) {
            loadedScopes.clear()
            mutableState.value = emptyMap()
        }
    }

    fun clearAccount(accountId: String?) {
        val normalizedAccount = accountId?.trim()?.takeIf { it.isNotEmpty() } ?: return
        synchronized(lock) {
            val matching = (loadedScopes + mutableState.value.keys).filter { it.accountId == normalizedAccount }
            loadedScopes.removeAll { it.accountId == normalizedAccount }
            mutableState.value = mutableState.value.filterKeys { it.accountId != normalizedAccount }
            storage?.let { store ->
                matching.forEach { scope ->
                    runCatching { store.remove(mobileRecentlyUsedStorageKey(scope.accountId, scope.serverId)) }
                }
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            val existingScopes = (loadedScopes + mutableState.value.keys).toList()
            loadedScopes.clear()
            mutableState.value = emptyMap()
            storage?.let { store ->
                existingScopes.forEach { scope ->
                    runCatching { store.remove(mobileRecentlyUsedStorageKey(scope.accountId, scope.serverId)) }
                }
            }
        }
    }

    private fun record(scope: MobileRecentlyUsedScope, entry: MobileRecentlyUsedEntry) {
        val limit = maxEntries.coerceAtLeast(1)
        synchronized(lock) {
            val existing = ensureLoadedLocked(scope)
            val updated = (listOf(entry) + existing.filterNot { sameEntry(it, entry) }).take(limit)
            mutableState.value = mutableState.value + (scope to updated)
            storage?.let { store ->
                runCatching {
                    store.write(
                        mobileRecentlyUsedStorageKey(scope.accountId, scope.serverId),
                        encodeMobileRecentlyUsedEntries(updated),
                    )
                }
            }
        }
    }

    private fun ensureLoadedLocked(scope: MobileRecentlyUsedScope): List<MobileRecentlyUsedEntry> {
        if (scope in loadedScopes) return mutableState.value[scope].orEmpty()
        loadedScopes += scope
        val store = storage ?: return mutableState.value[scope].orEmpty()
        val decoded =
            runCatching {
                decodeMobileRecentlyUsedEntries(
                    raw = store.read(mobileRecentlyUsedStorageKey(scope.accountId, scope.serverId)),
                    maxEntries = maxEntries.coerceAtLeast(1),
                )
            }.getOrDefault(emptyList())
        if (decoded.isNotEmpty()) {
            mutableState.value = mutableState.value + (scope to decoded)
        }
        return decoded
    }

    private fun sameEntry(a: MobileRecentlyUsedEntry, b: MobileRecentlyUsedEntry): Boolean =
        when {
            a is MobileRecentlyUsedEntry.Artifact && b is MobileRecentlyUsedEntry.Artifact -> a.id == b.id
            a is MobileRecentlyUsedEntry.App && b is MobileRecentlyUsedEntry.App ->
                a.clientId.equals(b.clientId, ignoreCase = true)
            else -> false
        }

    private fun scopeOrNull(accountId: String?, serverId: String?): MobileRecentlyUsedScope? {
        val normalizedAccount = accountId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val normalizedServer = serverId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return MobileRecentlyUsedScope(accountId = normalizedAccount, serverId = normalizedServer)
    }

    private fun normalizeExcludedPlaceKey(raw: String): String {
        val trimmed = raw.trim()
        return if (trimmed.startsWith(RaftRailAppPlacePrefix, ignoreCase = true)) {
            "$RaftRailAppPlacePrefix${trimmed.substring(RaftRailAppPlacePrefix.length).lowercase()}"
        } else {
            trimmed
        }
    }
}
