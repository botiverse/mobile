package build.raft.app.data

import build.raft.app.model.ServerId
import kotlin.concurrent.Volatile
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Server feature-flag evaluation — the KMP mirror of web `packages/web/src/store/serverFeatureFlags.ts`.
 * Both ends consume the SAME batch endpoint and registry so a flag is gated identically cross-platform.
 *
 * Wire contract (server-verified): POST `feature-flags/evaluate` with `{serverId, keys, platform}` (batch,
 * per-server; userId is derived server-side from auth, NOT in the body), returning `{evaluations:[{key,
 * enabled}]}`. `platform` (slock #186, landed) is a `"web" | "mobile"` rule stage — evaluated between the
 * user and server stages — so a rollout like `sync_core_messages_v0` can target mobile-only. This client
 * always sends `"mobile"` ([FEATURE_FLAG_PLATFORM_MOBILE]); the server treats every KMP surface as mobile.
 */
const val SYNC_CORE_MESSAGES_FEATURE_FLAG_KEY: String = "sync_core_messages_v0"
const val SYNC_CORE_MESSAGES_V2_NORMALIZED_FEATURE_FLAG_KEY: String = "sync_core_messages_v2_normalized_v0"
const val SYNC_CORE_NOTIFICATION_PREFS_FEATURE_FLAG_KEY: String = "sync_core_notification_prefs_v0"
const val ATTACHMENT_COMMENTS_FEATURE_FLAG_KEY: String = "attachment_comments_v0"
const val READ_RECEIPTS_FEATURE_FLAG_KEY: String = "read_receipts_v0"

/** Mirror of web `REGISTERED_SERVER_FEATURE_FLAG_KEYS` — the batch evaluated at prefetch. */
val REGISTERED_SERVER_FEATURE_FLAG_KEYS: List<String> = listOf(
    SYNC_CORE_MESSAGES_FEATURE_FLAG_KEY,
    SYNC_CORE_MESSAGES_V2_NORMALIZED_FEATURE_FLAG_KEY,
    SYNC_CORE_NOTIFICATION_PREFS_FEATURE_FLAG_KEY,
    ATTACHMENT_COMMENTS_FEATURE_FLAG_KEY,
    READ_RECEIPTS_FEATURE_FLAG_KEY,
)

/**
 * The `platform` value this client reports to feature-flag evaluation. The server's `FeatureFlagPlatform`
 * is coarse (`"web" | "mobile"`), so every KMP surface (Android / iOS / OHOS) reports `"mobile"`.
 */
const val FEATURE_FLAG_PLATFORM_MOBILE: String = "mobile"

private val featureFlagsJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    coerceInputValues = true
}

@Serializable
private data class FeatureFlagEvaluateRequestBody(
    val serverId: String,
    val keys: List<String>,
    // Sent so the server can apply platform-scoped rules (slock #186). `featureFlagsJson` has
    // encodeDefaults=true, so the default is emitted on the wire; the server treats an omitted platform as
    // all-platform, so sending "mobile" is what makes a mobile-only rollout target this client.
    val platform: String = FEATURE_FLAG_PLATFORM_MOBILE,
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
    private val diagnosticsLogger: ((String) -> Unit)? = null,
) {
    // Written on the prefetch coroutine (background), read via isEnabled on the UI thread — so the map
    // itself must publish safely across threads. @Volatile + copy-on-write: writes are rare (once per
    // server change), reads always see a consistent immutable map (no bare-HashMap resize race). (LiBai)
    @Volatile
    private var snapshots: Map<String, ServerFeatureFlagSnapshot> = emptyMap()

    fun snapshotFor(serverId: ServerId?): ServerFeatureFlagSnapshot =
        serverId?.let { snapshots[it.value] } ?: ServerFeatureFlagSnapshot.Unresolved

    fun isEnabled(serverId: ServerId?, key: String): Boolean = snapshotFor(serverId).isEnabled(key)

    /**
     * True if [key] is enabled in ANY resolved server snapshot. Used by the sync-core shadow-compare gate,
     * which is a global diagnostic (not per-server user-facing behavior) and runs before the per-event
     * serverId is resolved. Once the server evaluate accepts `platform` (slock #186) and shadow becomes
     * per-server, replace this with a serverId-scoped read.
     */
    fun isEnabledForAnyResolvedServer(key: String): Boolean =
        snapshots.values.any { it.resolved && it.isEnabled(key) }

    /** Evaluate the registered flags for [serverId] and cache the snapshot. Returns the resolved snapshot. */
    suspend fun prefetch(serverId: ServerId): ServerFeatureFlagSnapshot {
        val response = api.evaluate(serverId, keys)
        val body = response.bodyText
        if (!response.isSuccessful || body == null) {
            diagnosticsLogger?.invoke("feature_flags evaluate failed server=${serverId.value} code=${response.code}")
            return snapshotFor(serverId)
        }
        val decoded = try {
            featureFlagsJson.decodeFromString<FeatureFlagEvaluateResponseBody>(body)
        } catch (_: SerializationException) {
            diagnosticsLogger?.invoke("feature_flags evaluate malformed body server=${serverId.value}")
            return snapshotFor(serverId)
        }
        val values = keys.associateWith { false }.toMutableMap()
        for (evaluation in decoded.evaluations) {
            if (values.containsKey(evaluation.key)) values[evaluation.key] = evaluation.enabled
        }
        val snapshot = ServerFeatureFlagSnapshot(serverId = serverId, resolved = true, values = values.toMap())
        snapshots = snapshots + (serverId.value to snapshot) // copy-on-write publish
        return snapshot
    }
}
