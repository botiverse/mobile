package build.raft.app.data

import build.raft.app.model.ServerId
import kotlin.concurrent.Volatile
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
 * enabled, reason}]}`. `platform` (slock #186, landed) is a `"web" | "mobile"` rule stage — evaluated between the
 * user and server stages — so a rollout like `sync_core_messages_v0` can target mobile-only. This client
 * always sends `"mobile"` ([FEATURE_FLAG_PLATFORM_MOBILE]); the server treats every KMP surface as mobile.
 */
const val SYNC_CORE_MESSAGES_FEATURE_FLAG_KEY: String = "sync_core_messages_v0"
const val SYNC_CORE_NOTIFICATION_PREFS_FEATURE_FLAG_KEY: String = "sync_core_notification_prefs_v0"
const val SYNC_CORE_READ_STATE_V1_FEATURE_FLAG_KEY: String = "sync_core_read_state_v1"
const val ATTACHMENT_COMMENTS_FEATURE_FLAG_KEY: String = "attachment_comments_v0"
const val READ_RECEIPTS_FEATURE_FLAG_KEY: String = "read_receipts_v0"
const val MOBILE_THREAD_PAGE_SIZE_FEATURE_FLAG_KEY: String = "mobile_thread_page_size_v0"
const val MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY: String = "mobile_activity_stable_order_v1"

/**
 * Positive default-on flag whose backend `kill_switch` reason is persisted by Android as the emergency
 * transport-off latch. Operators must provision it with `enabled=true` and `defaultEnabled=true`.
 */
const val ANDROID_REUSED_H2_RECOVERY_FEATURE_FLAG_KEY: String = "android_reused_h2_recovery_v0"
/** Process-wide mobile transport variant. Missing/disabled/invalid evaluates to the product default curl_h3. */
const val MOBILE_CURL_TRANSPORT_FEATURE_FLAG_KEY: String = "mobile_curl_transport_v1"

/** Web-shared product flags plus mobile-only runtime safety flags evaluated in the same prefetch batch. */
val REGISTERED_SERVER_FEATURE_FLAG_KEYS: List<String> = listOf(
    SYNC_CORE_MESSAGES_FEATURE_FLAG_KEY,
    SYNC_CORE_NOTIFICATION_PREFS_FEATURE_FLAG_KEY,
    SYNC_CORE_READ_STATE_V1_FEATURE_FLAG_KEY,
    ATTACHMENT_COMMENTS_FEATURE_FLAG_KEY,
    READ_RECEIPTS_FEATURE_FLAG_KEY,
    MOBILE_THREAD_PAGE_SIZE_FEATURE_FLAG_KEY,
    MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY,
    ANDROID_REUSED_H2_RECOVERY_FEATURE_FLAG_KEY,
    MOBILE_CURL_TRANSPORT_FEATURE_FLAG_KEY,
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

private val featureFlagRequestGeneration = atomic(0L)

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
    val reason: String? = null,
    val variant: String? = null,
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
    val variants: Map<String, String> = emptyMap(),
) {
    fun isEnabled(key: String): Boolean = values[key] == true
    fun variant(key: String): String? = variants[key]

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
    private val onEvaluation: ((ServerId, String, Boolean, String?, String?, String?, Long) -> Unit)? = null,
) {
    private val stateLock = SynchronizedObject()
    private val nextRequestGenerationByServer = mutableMapOf<String, Long>()
    private val acceptedGenerationByServer = mutableMapOf<String, Long>()

    // Written under [stateLock], read via isEnabled on the UI thread. @Volatile + copy-on-write lets reads
    // observe a complete immutable map without taking the writer lock; the lock prevents concurrent
    // per-server prefetches from losing one another's snapshot updates.
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
    suspend fun prefetch(
        serverId: ServerId,
        evaluationContext: String? = null
    ): ServerFeatureFlagSnapshot {
        val globalRequestGeneration = featureFlagRequestGeneration.incrementAndGet()
        val requestGeneration = synchronized(stateLock) {
            (nextRequestGenerationByServer[serverId.value] ?: 0L).plus(1L).also { generation ->
                nextRequestGenerationByServer[serverId.value] = generation
            }
        }
        val response = api.evaluate(serverId, keys)
        currentCoroutineContext().ensureActive()
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
        val variants = linkedMapOf<String, String>()
        val observedEvaluations = linkedMapOf<String, FeatureFlagEvaluation>()
        for (evaluation in decoded.evaluations) {
            if (values.containsKey(evaluation.key)) {
                values[evaluation.key] = evaluation.enabled
                evaluation.variant
                    ?.trim()
                    ?.takeIf { evaluation.enabled && it.isNotEmpty() }
                    ?.let { variants[evaluation.key] = it }
                observedEvaluations[evaluation.key] = evaluation
            }
        }
        val snapshot =
            ServerFeatureFlagSnapshot(
                serverId = serverId,
                resolved = true,
                values = values.toMap(),
                variants = variants.toMap()
            )
        val accepted = synchronized(stateLock) {
            val latestAcceptedGeneration = acceptedGenerationByServer[serverId.value] ?: 0L
            if (requestGeneration < latestAcceptedGeneration) {
                false
            } else {
                acceptedGenerationByServer[serverId.value] = requestGeneration
                snapshots = snapshots + (serverId.value to snapshot)
                true
            }
        }
        // The snapshot uses a per-server latest-success fence. The optional observer may own a broader
        // global fact (Android's environment-wide transport kill), so it receives every decoded success
        // with the global request generation and performs its own ordering/active-selection checks.
        for (evaluation in observedEvaluations.values) {
            runCatching {
                onEvaluation?.invoke(
                    serverId,
                    evaluation.key,
                    evaluation.enabled,
                    evaluation.reason,
                    evaluation.variant,
                    evaluationContext,
                    globalRequestGeneration
                )
            }.onFailure { error ->
                diagnosticsLogger?.invoke(
                    "feature_flags evaluation_observer_failed server=${serverId.value} " +
                        "key=${evaluation.key} reason=${error::class.simpleName ?: "unknown"}"
                )
            }
        }
        if (!accepted) {
            diagnosticsLogger?.invoke(
                "feature_flags evaluate stale_ignored server=${serverId.value} generation=$requestGeneration"
            )
            return snapshotFor(serverId)
        }
        return snapshot
    }
}
