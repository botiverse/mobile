package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

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
 * same way the last online session did (task #1133). A cold start reads their last value until the
 * evaluation lands (or fails); every other flag keeps the defaults-off-until-evaluated contract.
 * - `mobile_server_setup_v1`: offline, the setup check then fails open to Home.
 * - `mobile_activity_inplace_v1` (#triage:#244): the Activity list and its
 *   badge read by one rule from the first frame, not by today's off path until the evaluation lands
 *   and then by the flag's (the first load's rows and badge counted one way, the live patches after
 *   it the other).
 * - `mobile_web_container_v1` (dm:@artin:#6): persisted across cold restarts so Android's
 *   first-frame off-main-thread `WebViewCompat.startUpWebView` warmup and offline cold opens
 *   follow the last server evaluation instead of defaulting off before network lands.
 */
val SERVER_FEATURE_FLAG_LAST_KNOWN_KEYS: Set<String> =
    setOf(
        MOBILE_SERVER_SETUP_V1_FEATURE_FLAG_KEY,
        MOBILE_ACTIVITY_INPLACE_V1_FEATURE_FLAG_KEY,
        MOBILE_WEB_CONTAINER_V1_FEATURE_FLAG_KEY,
        MOBILE_EARLY_ACCESS_VISIBLE_FEATURE_FLAG_KEY,
        MOBILE_EARLY_ACCESS_DEFAULT_ON_FEATURE_FLAG_KEY,
    )

/** The auth binding a last-known value belongs to. Token rotation does not change it. */
data class ServerFeatureFlagLastKnownBinding(
    val environmentId: String,
    val accountId: String,
)

/** Drops the auth generation on purpose: a token refresh must keep reading the same stored value. */
@RaftCoreInternalApi fun ProcessPresenceAuthContext.toServerFeatureFlagLastKnownBinding(): ServerFeatureFlagLastKnownBinding =
    ServerFeatureFlagLastKnownBinding(environmentId = environmentId, accountId = accountId)

@RaftCoreInternalApi data class ServerFeatureFlagLastKnownScope(
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

    @RaftCoreInternalApi fun scopeFor(serverId: ServerId): ServerFeatureFlagLastKnownScope? {
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
    @RaftCoreInternalApi fun writeIfCurrent(scope: ServerFeatureFlagLastKnownScope, values: Map<String, Boolean>): Boolean {
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

    companion object {
        private val lastKnownJson = Json { ignoreUnknownKeys = true }

        /**
         * Reads the last accepted server evaluation for [key] directly from [store] during early
         * process cold start (before `RaftSharedStoreRegistry` or `accountBinding` are bound).
         *
         * Because [writeIfCurrent] retains entries for only the single active `(environment, account)`
         * and [clearAll] wipes storage on sign-out:
         * - When [preferredServerId] is provided, only that server's stored value is consulted so
         *   an explicit `false` on the active server is never overridden by another server's `true`.
         * - When [preferredServerId] is `null` (pre-auth-store first frame), returns `true` iff any
         *   server of the last signed-in account had [key] enabled.
         */
        fun readColdStartFlag(
            store: SharedPreferenceStore,
            key: String,
            preferredServerId: String? = null,
            preferredEnvironmentId: String? = null,
            preferredAccountId: String? = null,
        ): Boolean {
            if (key !in SERVER_FEATURE_FLAG_LAST_KNOWN_KEYS) return false
            val encoded = runCatching { store.getString(SERVER_FEATURE_FLAG_LAST_KNOWN_STORAGE_KEY) }.getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: return false
            val targetEnv = preferredEnvironmentId?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
            val targetAccount = preferredAccountId?.trim()?.takeIf { it.isNotEmpty() }
            val entries =
                runCatching {
                    lastKnownJson.decodeFromString<PersistedServerFeatureFlagLastKnownDocument>(encoded).entries
                }.getOrNull()
                    .orEmpty()
                    .filter { entry ->
                        entry.environmentId.isNotBlank() &&
                            entry.accountId.isNotBlank() &&
                            entry.serverId.isNotBlank() &&
                            (targetEnv == null || entry.environmentId.trim().trimEnd('/') == targetEnv) &&
                            (targetAccount == null || entry.accountId.trim() == targetAccount)
                    }
            if (entries.isEmpty()) return false
            val targetServer = preferredServerId?.trim()?.takeIf { it.isNotEmpty() }
            if (targetServer != null) {
                return entries.firstOrNull { it.serverId == targetServer }?.values?.get(key) == true
            }
            return entries.any { it.values[key] == true }
        }
    }
}
