package build.raft.app.data

import build.raft.app.model.ServerId
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Server feature-flag evaluation — the KMP mirror of web `packages/web/src/store/serverFeatureFlags.ts`.
 * Both ends consume the SAME batch endpoint and registry so a flag is gated identically cross-platform.
 *
 * Wire contract (web-verified): POST `feature-flags/evaluate` with `{serverId, keys}` (batch, per-server;
 * userId is derived server-side from auth, NOT in the body), returning `{evaluations:[{key, enabled}]}`.
 * A `platform` field will be added to the body once the server accepts it (slock #186) — see [PLATFORM_TODO].
 */
const val SYNC_CORE_MESSAGES_FEATURE_FLAG_KEY: String = "sync_core_messages_v0"
const val SYNC_CORE_NOTIFICATION_PREFS_FEATURE_FLAG_KEY: String = "sync_core_notification_prefs_v0"
const val ATTACHMENT_COMMENTS_FEATURE_FLAG_KEY: String = "attachment_comments_v0"
const val READ_RECEIPTS_FEATURE_FLAG_KEY: String = "read_receipts_v0"

/** Mirror of web `REGISTERED_SERVER_FEATURE_FLAG_KEYS` — the batch evaluated at prefetch. */
val REGISTERED_SERVER_FEATURE_FLAG_KEYS: List<String> = listOf(
    SYNC_CORE_MESSAGES_FEATURE_FLAG_KEY,
    SYNC_CORE_NOTIFICATION_PREFS_FEATURE_FLAG_KEY,
    ATTACHMENT_COMMENTS_FEATURE_FLAG_KEY,
    READ_RECEIPTS_FEATURE_FLAG_KEY,
)

// PLATFORM_TODO (slock #186 / Cody): once the server evaluate accepts `platform`, add it to
// [FeatureFlagEvaluateRequestBody] so the sync-core rollout can be gated mobile-only. Until then the
// body omits it (old contract = all-platform hit) and mobile-only targeting uses user/server rules.

private val featureFlagsJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    coerceInputValues = true
}

@Serializable
private data class FeatureFlagEvaluateRequestBody(
    val serverId: String,
    val keys: List<String>,
)

@Serializable
private data class FeatureFlagEvaluation(
    val key: String,
    val enabled: Boolean = false,
)

@Serializable
private data class FeatureFlagEvaluateResponseBody(
    val evaluations: List<FeatureFlagEvaluation> = emptyList(),
)

/** A resolved per-server feature-flag snapshot. `resolved=false` = not yet fetched (all keys default off). */
data class ServerFeatureFlagSnapshot(
    val serverId: ServerId?,
    val resolved: Boolean,
    val values: Map<String, Boolean>,
) {
    fun isEnabled(key: String): Boolean = values[key] == true

    companion object {
        val Unresolved: ServerFeatureFlagSnapshot = ServerFeatureFlagSnapshot(null, resolved = false, values = emptyMap())
    }
}

interface SlockFeatureFlagsApi {
    suspend fun evaluate(serverId: ServerId, keys: List<String>): HomeNetworkResponse
}

internal class SlockFeatureFlagsNetworkApi(
    private val networkClient: SlockNetworkClient,
) : SlockFeatureFlagsApi {
    override suspend fun evaluate(serverId: ServerId, keys: List<String>): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Background,
            path = "feature-flags/evaluate",
            method = "POST",
            serverIdOverride = serverId,
            bodyText = featureFlagsJson.encodeToString(
                FeatureFlagEvaluateRequestBody(serverId = serverId.value, keys = keys),
            ),
        )
}

/**
 * Caches the registered flag evaluation per server. [prefetch] issues the batch request (this is what
 * makes `sync_core_messages_v0` traffic appear at `feature-flags/evaluate`) and updates the snapshot;
 * [snapshotFor]/[isEnabled] are safe defaults-off reads before/without a resolve. A failed fetch leaves
 * the prior snapshot (or Unresolved) — flags default OFF, so the gated feature stays dark, never broken.
 */
class SharedServerFeatureFlags(
    private val api: SlockFeatureFlagsApi,
    private val keys: List<String> = REGISTERED_SERVER_FEATURE_FLAG_KEYS,
) {
    private val snapshots = mutableMapOf<String, ServerFeatureFlagSnapshot>()

    fun snapshotFor(serverId: ServerId?): ServerFeatureFlagSnapshot =
        serverId?.let { snapshots[it.value] } ?: ServerFeatureFlagSnapshot.Unresolved

    fun isEnabled(serverId: ServerId?, key: String): Boolean = snapshotFor(serverId).isEnabled(key)

    /** Evaluate the registered flags for [serverId] and cache the snapshot. Returns the resolved snapshot. */
    suspend fun prefetch(serverId: ServerId): ServerFeatureFlagSnapshot {
        val response = api.evaluate(serverId, keys)
        val body = response.bodyText
        if (!response.isSuccessful || body == null) return snapshotFor(serverId)
        val decoded = try {
            featureFlagsJson.decodeFromString<FeatureFlagEvaluateResponseBody>(body)
        } catch (_: SerializationException) {
            return snapshotFor(serverId)
        }
        val values = keys.associateWith { false }.toMutableMap()
        for (evaluation in decoded.evaluations) {
            if (values.containsKey(evaluation.key)) values[evaluation.key] = evaluation.enabled
        }
        val snapshot = ServerFeatureFlagSnapshot(serverId = serverId, resolved = true, values = values.toMap())
        snapshots[serverId.value] = snapshot
        return snapshot
    }
}
