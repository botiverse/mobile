package build.raft.app.data

import build.raft.app.model.ServerId
import kotlin.concurrent.Volatile
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val SERVER_FEATURE_FLAG_LAST_KNOWN_STORAGE_KEY: String = "serverFeatureFlagLastKnown.v1"

/**
 * Flags whose last server evaluation is kept on disk so a cold start WITHOUT network still gates the
 * same way the last online session did (task #1133): the offline-cache line. Every other flag keeps the
 * defaults-off-until-evaluated contract.
 */
val SERVER_FEATURE_FLAG_LAST_KNOWN_KEYS: Set<String> =
    setOf(
        SYNC_CORE_MESSAGES_FEATURE_FLAG_KEY,
        MOBILE_DOH_FALLBACK_FEATURE_FLAG_KEY,
    )

/** The auth binding a last-known value belongs to. Token rotation does not change it. */
data class ServerFeatureFlagLastKnownBinding(
    val environmentId: String,
    val accountId: String,
)

/** Drops the auth generation on purpose: a token refresh must keep reading the same stored value. */
internal fun ProcessPresenceAuthContext.toServerFeatureFlagLastKnownBinding(): ServerFeatureFlagLastKnownBinding =
    ServerFeatureFlagLastKnownBinding(environmentId = environmentId, accountId = accountId)

internal data class ServerFeatureFlagLastKnownScope(
    val environmentId: String,
    val accountId: String,
    val serverId: String,
)

@Serializable
private data class PersistedServerFeatureFlagLastKnown(
    val environmentId: String,
    val accountId: String,
    val serverId: String,
    val values: Map<String, Boolean>,
)

@Serializable
private data class PersistedServerFeatureFlagLastKnownDocument(
    val version: Int = 1,
    val entries: List<PersistedServerFeatureFlagLastKnown> = emptyList(),
)

/**
 * Durable last-known evaluation per (environment, account, server).
 *
 * - Written only from an accepted online evaluation, and only if the binding that issued the request is
 *   still the current one when the result lands; entries of any other (environment, account) are dropped
 *   on that write, so a switched-away account never leaves values behind for the next one to read.
 * - Read only when no online evaluation exists in this process yet — a fresh evaluation always wins,
 *   including a server-side kill (the next online evaluate overwrites the stored value with false).
 * - [clearAll] runs on auth clear (logout / auth loss).
 */
class ServerFeatureFlagLastKnownStore(
    private val store: SharedPreferenceStore,
    private val currentBinding: () -> ServerFeatureFlagLastKnownBinding?,
    val keys: Set<String> = SERVER_FEATURE_FLAG_LAST_KNOWN_KEYS,
) {
    private val lock = SynchronizedObject()

    @Volatile
    private var cached: List<PersistedServerFeatureFlagLastKnown>? = null

    internal fun scopeFor(serverId: ServerId): ServerFeatureFlagLastKnownScope? {
        val binding = currentBinding() ?: return null
        val environment = binding.environmentId.trim().trimEnd('/').takeIf { it.isNotBlank() } ?: return null
        val account = binding.accountId.trim().takeIf { it.isNotBlank() } ?: return null
        val server = serverId.value.trim().takeIf { it.isNotBlank() } ?: return null
        return ServerFeatureFlagLastKnownScope(environment, account, server)
    }

    /** The stored value of [key] for the CURRENT binding, or null when nothing is stored for it. */
    fun read(serverId: ServerId, key: String): Boolean? {
        if (key !in keys) return null
        val scope = scopeFor(serverId) ?: return null
        return entries().firstOrNull { it.matches(scope) }?.values?.get(key)
    }

    /**
     * Stores [values] for [scope] if [scope] is still the current binding for its server. Returns false
     * (and writes nothing) when the binding changed while the evaluation was in flight.
     */
    internal fun writeIfCurrent(scope: ServerFeatureFlagLastKnownScope, values: Map<String, Boolean>): Boolean {
        val persisted = values.filterKeys { it in keys }
        if (persisted.isEmpty()) return false
        return synchronized(lock) {
            if (scopeFor(ServerId(scope.serverId)) != scope) return@synchronized false
            val replacement =
                PersistedServerFeatureFlagLastKnown(
                    environmentId = scope.environmentId,
                    accountId = scope.accountId,
                    serverId = scope.serverId,
                    values = persisted,
                )
            val next =
                entries().filter { entry ->
                    entry.environmentId == scope.environmentId &&
                        entry.accountId == scope.accountId &&
                        entry.serverId != scope.serverId
                } + replacement
            store.putString(
                SERVER_FEATURE_FLAG_LAST_KNOWN_STORAGE_KEY,
                lastKnownJson.encodeToString(PersistedServerFeatureFlagLastKnownDocument(entries = next)),
            )
            cached = next
            true
        }
    }

    fun clearAll() {
        synchronized(lock) {
            store.putString(SERVER_FEATURE_FLAG_LAST_KNOWN_STORAGE_KEY, null)
            cached = emptyList()
        }
    }

    private fun entries(): List<PersistedServerFeatureFlagLastKnown> =
        cached ?: synchronized(lock) {
            cached ?: decode().also { cached = it }
        }

    private fun decode(): List<PersistedServerFeatureFlagLastKnown> =
        store.getString(SERVER_FEATURE_FLAG_LAST_KNOWN_STORAGE_KEY)
            ?.takeIf { it.isNotBlank() }
            ?.let { encoded ->
                runCatching {
                    lastKnownJson.decodeFromString<PersistedServerFeatureFlagLastKnownDocument>(encoded).entries
                }.getOrNull()
            }
            .orEmpty()
            .filter { entry ->
                entry.environmentId.isNotBlank() && entry.accountId.isNotBlank() && entry.serverId.isNotBlank()
            }

    private fun PersistedServerFeatureFlagLastKnown.matches(scope: ServerFeatureFlagLastKnownScope): Boolean =
        environmentId == scope.environmentId && accountId == scope.accountId && serverId == scope.serverId

    private companion object {
        val lastKnownJson = Json { ignoreUnknownKeys = true }
    }
}
