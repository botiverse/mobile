package build.raft.app.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.pow

internal const val SearchHistoryLimit = 15
internal const val SearchFrequentEntityLimit = 10
internal const val SearchUsageEventsPerEntityLimit = 24
internal const val SearchUsageEntityLimit = 64
internal const val SearchPersonalizationScopeLimit = 32
internal const val SearchUsageWindowMillis = 90L * 24L * 60L * 60L * 1_000L
internal const val SearchUsageHalfLifeMillis = 7L * 24L * 60L * 60L * 1_000L
private const val SearchQueryLengthLimit = 200
private const val SearchEntityIdLengthLimit = 200
private const val SearchFutureTimestampToleranceMillis = 5L * 60L * 1_000L
private const val SearchPersonalizationStorageKey = "search:personalization:v1"

private val searchPersonalizationStorageMutex = Mutex()
private val searchPersonalizationJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

internal data class SearchPersonalizationOwner(
    val serverId: String,
    val principalId: String,
) {
    companion object {
        fun create(serverId: String?, principalId: String?): SearchPersonalizationOwner? {
            val normalizedServerId = serverId?.trim()?.takeIf { it.isNotBlank() } ?: return null
            val normalizedPrincipalId = principalId?.trim()?.takeIf { it.isNotBlank() } ?: return null
            return SearchPersonalizationOwner(normalizedServerId, normalizedPrincipalId)
        }
    }
}

enum class SearchPersonalizationEntityKind(val storagePrefix: String) {
    Channel("channel"),
    DirectMessage("dm"),
    Human("human"),
    Agent("agent");

    companion object {
        fun fromStoragePrefix(value: String): SearchPersonalizationEntityKind? =
            entries.firstOrNull { it.storagePrefix == value }
    }
}

data class SearchPersonalizationEntityKey(
    val kind: SearchPersonalizationEntityKind,
    val id: String,
) {
    val storageValue: String
        get() = "${kind.storagePrefix}:$id"

    companion object {
        fun create(kind: SearchPersonalizationEntityKind, id: String?): SearchPersonalizationEntityKey? {
            val normalizedId = id?.trim()?.takeIf { it.isValidSearchPersonalizationEntityId() } ?: return null
            return SearchPersonalizationEntityKey(kind, normalizedId)
        }

        fun parse(value: String): SearchPersonalizationEntityKey? {
            val separator = value.indexOf(':')
            if (separator <= 0 || separator == value.lastIndex) return null
            val kind = SearchPersonalizationEntityKind.fromStoragePrefix(value.substring(0, separator)) ?: return null
            return create(kind, value.substring(separator + 1))
        }
    }
}

internal data class SearchPersonalizationSnapshot(
    val owner: SearchPersonalizationOwner,
    val history: List<String> = emptyList(),
    val usage: Map<String, List<Long>> = emptyMap(),
)

data class SearchFrequentEntityRank(
    val score: Double,
    val lastOpenedAtMillis: Long,
)

internal interface SearchPersonalizationRepository {
    suspend fun read(owner: SearchPersonalizationOwner, nowMillis: Long): SearchPersonalizationSnapshot

    suspend fun recordSuccessfulOpen(
        owner: SearchPersonalizationOwner,
        rawQuery: String,
        entityKey: SearchPersonalizationEntityKey?,
        openedAtMillis: Long,
    ): SearchPersonalizationSnapshot

    suspend fun removeHistoryEntry(
        owner: SearchPersonalizationOwner,
        rawQuery: String,
        nowMillis: Long,
    ): SearchPersonalizationSnapshot

    suspend fun clearHistory(owner: SearchPersonalizationOwner, nowMillis: Long): SearchPersonalizationSnapshot
}

internal class PersistentSearchPersonalizationRepository(
    private val store: SharedPreferenceStore,
) : SearchPersonalizationRepository {
    override suspend fun read(
        owner: SearchPersonalizationOwner,
        nowMillis: Long,
    ): SearchPersonalizationSnapshot =
        searchPersonalizationStorageMutex.withLock {
            readEnvelope(nowMillis).scope(owner, nowMillis).toSnapshot(owner)
        }

    override suspend fun recordSuccessfulOpen(
        owner: SearchPersonalizationOwner,
        rawQuery: String,
        entityKey: SearchPersonalizationEntityKey?,
        openedAtMillis: Long,
    ): SearchPersonalizationSnapshot =
        searchPersonalizationStorageMutex.withLock {
            val envelope = readEnvelope(openedAtMillis)
            val previous = envelope.scope(owner, openedAtMillis)
            val nextHistory = addSearchHistoryEntry(previous.history, rawQuery)
            val nextUsage =
                if (entityKey == null) {
                    previous.usage
                } else {
                    recordSearchEntityOpen(previous.usage, entityKey, openedAtMillis)
                }
            val nextScope =
                SearchPersonalizationWireScope(
                    serverId = owner.serverId,
                    principalId = owner.principalId,
                    history = nextHistory,
                    usage = nextUsage,
                    lastTouchedAtMillis = openedAtMillis,
                ).normalized(openedAtMillis)
            if (writeEnvelope(envelope.withScope(nextScope, openedAtMillis), openedAtMillis)) {
                nextScope.toSnapshot(owner)
            } else {
                previous.toSnapshot(owner)
            }
        }

    override suspend fun removeHistoryEntry(
        owner: SearchPersonalizationOwner,
        rawQuery: String,
        nowMillis: Long,
    ): SearchPersonalizationSnapshot =
        searchPersonalizationStorageMutex.withLock {
            val envelope = readEnvelope(nowMillis)
            val previous = envelope.scope(owner, nowMillis)
            val nextScope =
                previous.copy(
                    history = removeSearchHistoryEntry(previous.history, rawQuery),
                    lastTouchedAtMillis = nowMillis,
                ).normalized(nowMillis)
            if (writeEnvelope(envelope.withScope(nextScope, nowMillis), nowMillis)) {
                nextScope.toSnapshot(owner)
            } else {
                previous.toSnapshot(owner)
            }
        }

    override suspend fun clearHistory(
        owner: SearchPersonalizationOwner,
        nowMillis: Long,
    ): SearchPersonalizationSnapshot =
        searchPersonalizationStorageMutex.withLock {
            val envelope = readEnvelope(nowMillis)
            val previous = envelope.scope(owner, nowMillis)
            val nextScope =
                previous.copy(
                    history = emptyList(),
                    lastTouchedAtMillis = nowMillis,
                ).normalized(nowMillis)
            if (writeEnvelope(envelope.withScope(nextScope, nowMillis), nowMillis)) {
                nextScope.toSnapshot(owner)
            } else {
                previous.toSnapshot(owner)
            }
        }

    /**
     * Every scope of every account. Sign-out keeps them: scopes are keyed by (server, principal)
     * (owner decision 2026-10-27, see AccountLocalData).
     */
    suspend fun clearAll() {
        searchPersonalizationStorageMutex.withLock {
            runCatching { store.putString(SearchPersonalizationStorageKey, null) }
        }
    }

    /**
     * Removed from (or deleted) server: that server's searches name channels and people the account
     * can no longer see. Drops the server's scopes of [principalId] (the account that lost it; another
     * account on this device keeps its own), or of every principal when null.
     */
    suspend fun dropServer(serverId: String, principalId: String?, nowMillis: Long) {
        val key = serverId.trim().takeIf { it.isNotBlank() } ?: return
        val principal = principalId?.trim()
        fun SearchPersonalizationWireScope.dropped() = this.serverId == key && (principal == null || this.principalId == principal)
        searchPersonalizationStorageMutex.withLock {
            val envelope = readEnvelope(nowMillis)
            if (envelope.scopes.none { it.dropped() }) return@withLock
            writeEnvelope(envelope.copy(scopes = envelope.scopes.filterNot { it.dropped() }), nowMillis)
        }
    }

    private fun readEnvelope(nowMillis: Long): SearchPersonalizationWireEnvelope {
        val raw = runCatching { store.getString(SearchPersonalizationStorageKey) }.getOrNull()
        if (raw.isNullOrBlank()) return SearchPersonalizationWireEnvelope()
        return runCatching {
            searchPersonalizationJson.decodeFromString<SearchPersonalizationWireEnvelope>(raw)
        }.getOrNull()?.normalized(nowMillis) ?: SearchPersonalizationWireEnvelope()
    }

    private fun writeEnvelope(envelope: SearchPersonalizationWireEnvelope, nowMillis: Long): Boolean {
        val normalized = envelope.normalized(nowMillis)
        return runCatching {
            store.putString(
                SearchPersonalizationStorageKey,
                searchPersonalizationJson.encodeToString(normalized),
            )
        }.isSuccess
    }
}

@Serializable
private data class SearchPersonalizationWireEnvelope(
    val version: Int = 1,
    val scopes: List<SearchPersonalizationWireScope> = emptyList(),
)

@Serializable
private data class SearchPersonalizationWireScope(
    val serverId: String = "",
    val principalId: String = "",
    val history: List<String> = emptyList(),
    val usage: Map<String, List<Long>> = emptyMap(),
    val lastTouchedAtMillis: Long = 0L,
)

private fun SearchPersonalizationWireEnvelope.normalized(nowMillis: Long): SearchPersonalizationWireEnvelope =
    SearchPersonalizationWireEnvelope(
        version = 1,
        scopes =
            scopes
                .map { it.normalized(nowMillis) }
                .filter { it.ownerOrNull() != null && (it.history.isNotEmpty() || it.usage.isNotEmpty()) }
                .groupBy { "${it.serverId}\u001f${it.principalId}" }
                .values
                .mapNotNull { ownerScopes ->
                    ownerScopes.maxWithOrNull(
                        compareBy<SearchPersonalizationWireScope> { it.lastTouchedAtMillis }
                            .thenBy { it.history.size }
                            .thenBy { it.usage.size }
                    )
                }
                .sortedWith(
                    compareByDescending<SearchPersonalizationWireScope> { it.lastTouchedAtMillis }
                        .thenBy { it.serverId }
                        .thenBy { it.principalId }
                )
                .take(SearchPersonalizationScopeLimit),
    )

private fun SearchPersonalizationWireEnvelope.scope(
    owner: SearchPersonalizationOwner,
    nowMillis: Long,
): SearchPersonalizationWireScope =
    scopes.firstOrNull { it.serverId == owner.serverId && it.principalId == owner.principalId }
        ?.normalized(nowMillis)
        ?: SearchPersonalizationWireScope(serverId = owner.serverId, principalId = owner.principalId)

private fun SearchPersonalizationWireEnvelope.withScope(
    scope: SearchPersonalizationWireScope,
    nowMillis: Long,
): SearchPersonalizationWireEnvelope {
    val owner = scope.ownerOrNull() ?: return normalized(nowMillis)
    val retained = scopes.filterNot { it.serverId == owner.serverId && it.principalId == owner.principalId }
    val nextScopes =
        if (scope.history.isEmpty() && scope.usage.isEmpty()) retained else listOf(scope) + retained
    return copy(scopes = nextScopes).normalized(nowMillis)
}

private fun SearchPersonalizationWireScope.normalized(nowMillis: Long): SearchPersonalizationWireScope {
    val normalizedOwner = ownerOrNull()
    val normalizedUsage = normalizeSearchEntityUsage(usage, nowMillis)
    val normalizedHistory = normalizeSearchHistory(history)
    val touched =
        listOfNotNull(
            lastTouchedAtMillis.takeIf { it.isValidSearchTimestamp(nowMillis) },
            normalizedUsage.values.flatten().maxOrNull(),
        ).maxOrNull() ?: 0L
    return SearchPersonalizationWireScope(
        serverId = normalizedOwner?.serverId.orEmpty(),
        principalId = normalizedOwner?.principalId.orEmpty(),
        history = normalizedHistory,
        usage = normalizedUsage,
        lastTouchedAtMillis = touched,
    )
}

private fun SearchPersonalizationWireScope.ownerOrNull(): SearchPersonalizationOwner? =
    SearchPersonalizationOwner.create(serverId, principalId)

private fun SearchPersonalizationWireScope.toSnapshot(owner: SearchPersonalizationOwner): SearchPersonalizationSnapshot =
    SearchPersonalizationSnapshot(owner = owner, history = history, usage = usage)

internal fun normalizeSearchHistory(values: List<String>): List<String> {
    val seen = mutableSetOf<String>()
    return values.mapNotNull { raw ->
        val query = raw.trim().take(SearchQueryLengthLimit).takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val key = query.lowercase()
        if (!seen.add(key)) return@mapNotNull null
        query
    }.take(SearchHistoryLimit)
}

internal fun addSearchHistoryEntry(history: List<String>, rawQuery: String): List<String> {
    val query = rawQuery.trim().take(SearchQueryLengthLimit).takeIf { it.isNotBlank() } ?: return normalizeSearchHistory(history)
    val key = query.lowercase()
    return normalizeSearchHistory(listOf(query) + history.filterNot { it.trim().lowercase() == key })
}

internal fun removeSearchHistoryEntry(history: List<String>, rawQuery: String): List<String> {
    val key = rawQuery.trim().lowercase()
    if (key.isBlank()) return normalizeSearchHistory(history)
    return normalizeSearchHistory(history.filterNot { it.trim().lowercase() == key })
}

internal fun normalizeSearchEntityUsage(
    usage: Map<String, List<Long>>,
    nowMillis: Long,
): Map<String, List<Long>> =
    usage.mapNotNull { (rawKey, rawTimestamps) ->
        val key = SearchPersonalizationEntityKey.parse(rawKey) ?: return@mapNotNull null
        val timestamps =
            rawTimestamps
                .filter { it.isValidSearchTimestamp(nowMillis) }
                .distinct()
                .sortedDescending()
                .take(SearchUsageEventsPerEntityLimit)
        if (timestamps.isEmpty()) null else key.storageValue to timestamps
    }
        .sortedWith(compareByDescending<Pair<String, List<Long>>> { it.second.first() }.thenBy { it.first })
        .take(SearchUsageEntityLimit)
        .toMap(linkedMapOf())

internal fun recordSearchEntityOpen(
    usage: Map<String, List<Long>>,
    entityKey: SearchPersonalizationEntityKey,
    openedAtMillis: Long,
): Map<String, List<Long>> =
    normalizeSearchEntityUsage(
        usage + (entityKey.storageValue to (listOf(openedAtMillis) + usage[entityKey.storageValue].orEmpty())),
        openedAtMillis,
    )

internal fun searchFrequentEntityRank(
    timestamps: List<Long>,
    nowMillis: Long,
): SearchFrequentEntityRank {
    var score = 0.0
    var lastOpenedAt = 0L
    timestamps.forEach { timestamp ->
        if (!timestamp.isValidSearchTimestamp(nowMillis)) return@forEach
        val age = (nowMillis - timestamp).coerceAtLeast(0L)
        score += 2.0.pow(-(age.toDouble() / SearchUsageHalfLifeMillis.toDouble()))
        lastOpenedAt = maxOf(lastOpenedAt, timestamp)
    }
    return SearchFrequentEntityRank(score = score, lastOpenedAtMillis = lastOpenedAt)
}

private fun String.isValidSearchPersonalizationEntityId(): Boolean =
    length in 1..SearchEntityIdLengthLimit && none { it.isISOControl() || it == ':' }

private fun Long.isValidSearchTimestamp(nowMillis: Long): Boolean =
    this >= nowMillis - SearchUsageWindowMillis && this <= nowMillis + SearchFutureTimestampToleranceMillis
