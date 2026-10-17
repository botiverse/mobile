package build.raft.app.data

import build.raft.app.model.ServerId
import kotlin.concurrent.Volatile
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
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
 * user and server stages — so a rollout like `sync_core_messages_v1` can target mobile-only. This client
 * always sends `"mobile"` ([FEATURE_FLAG_PLATFORM_MOBILE]); the server treats every KMP surface as mobile.
 */
// v1 since 1.13.0: 1.12 and the web client read `sync_core_messages_v0` without the offline-cache fixes, so a
// per-server rule on this key reaches 1.13.0+ mobile builds only (artin, #raft-mobile-reconcile:82129b92).
const val SYNC_CORE_MESSAGES_FEATURE_FLAG_KEY: String = "sync_core_messages_v1"
const val SYNC_CORE_NOTIFICATION_PREFS_FEATURE_FLAG_KEY: String = "sync_core_notification_prefs_v0"
const val SYNC_CORE_READ_STATE_V1_FEATURE_FLAG_KEY: String = "sync_core_read_state_v1"
const val ATTACHMENT_COMMENTS_FEATURE_FLAG_KEY: String = "attachment_comments_v0"
const val READ_RECEIPTS_FEATURE_FLAG_KEY: String = "read_receipts_v0"
const val MOBILE_THREAD_PAGE_SIZE_FEATURE_FLAG_KEY: String = "mobile_thread_page_size_v0"
const val MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY: String = "mobile_activity_stable_order_v1"
const val WEBVIEW_ONBOARDING_V0_FEATURE_FLAG_KEY: String = "enable_webview_onboarding_v0"
const val PRODUCT_FEEDBACK_MULTI_APP_FEATURE_FLAG_KEY: String = "product_feedback_multi_app"
/**
 * #367 Thread first-frame render: single-paragraph light markdown path.
 * Default OFF everywhere (server-unresolved evaluates false); local A/B goes
 * through the diagnostics "Local Feature Overrides" ForceOn/ForceOff latch.
 */
const val THREAD_MARKDOWN_SINGLE_PARAGRAPH_LIGHT_FEATURE_FLAG_KEY: String =
    "thread_markdown_single_paragraph_light_v0"

/**
 * Thread message list laid out bottom-up (`reverseLayout`): the newest row is item 0 and renders
 * at the bottom from the first frame, with no scroll-to-bottom pass. Default ON in every build
 * (see [SERVER_FEATURE_FLAG_CLIENT_DEFAULT_ON_KEYS]); the server turns it off with an explicit
 * decision such as a kill switch. Local A/B goes through the diagnostics "Local Feature Overrides"
 * ForceOn/ForceOff latch. Decided once per thread open.
 */
const val MOBILE_THREAD_REVERSE_LAYOUT_FEATURE_FLAG_KEY: String = "mobile_thread_reverse_layout_v0"

/**
 * Positive default-on flag whose backend `kill_switch` reason is persisted by Android as the emergency
 * transport-off latch. Operators must provision it with `enabled=true` and `defaultEnabled=true`.
 */
const val ANDROID_REUSED_H2_RECOVERY_FEATURE_FLAG_KEY: String = "android_reused_h2_recovery_v0"
/** Process-wide mobile transport variant. Missing/disabled/invalid evaluates to the product default curl_h2. */
const val MOBILE_CURL_TRANSPORT_FEATURE_FLAG_KEY: String = "mobile_curl_transport_v1"

/**
 * Task #153 DNS-over-HTTPS fallback for the curl transport (see SlockDohFallbackPolicy). Default ON
 * (see [SERVER_FEATURE_FLAG_CLIENT_DEFAULT_ON_KEYS]); the server turns it off with an explicit
 * decision such as a kill switch. Kept in the last-known store so a cold start during a DNS outage,
 * when evaluate itself cannot reach the server, still honours the last explicit decision; internal
 * Android builds can also force it through Local Feature Overrides.
 */
const val MOBILE_DOH_FALLBACK_FEATURE_FLAG_KEY: String = "mobile_doh_fallback_v0"

/**
 * Task #154: realtime (Socket.IO) over NetworkKMM's curl client instead of the platform Socket.IO
 * library. Default OFF (an ordinary flag: no decision reads off); read per server at each connect
 * and reconnect (see SlockCurlSocketIoPolicy), so turning it on or off takes effect on the next
 * connection. Internal Android builds can force it through Local Feature Overrides.
 */
const val MOBILE_CURL_SOCKETIO_FEATURE_FLAG_KEY: String = "mobile_curl_socketio_v0"

/**
 * Keys that are ON unless the server explicitly decides otherwise (artin 2026-10-10: DoH defaults on,
 * the server can push it off). For these keys "no decision" reads ON: no evaluation yet, an evaluate
 * failure or malformed body, no stored last-known value, the key missing from the response, or the
 * server reporting `missing_flag` (no flag row exists). Every other server reason (`kill_switch`,
 * `flag_disabled`, any rule, `default`) is an explicit decision and is followed as sent.
 */
// sync_core_messages_v1 joined on 2026-10-11: artin wants offline cache on by default (an offline cold
// start or a server without the flag row used to read it as off).
// mobile_thread_reverse_layout_v0 joined on 2026-10-12: artin wants the bottom-up thread list on by default.
val SERVER_FEATURE_FLAG_CLIENT_DEFAULT_ON_KEYS: Set<String> =
    setOf(
        MOBILE_DOH_FALLBACK_FEATURE_FLAG_KEY,
        SYNC_CORE_MESSAGES_FEATURE_FLAG_KEY,
        MOBILE_THREAD_REVERSE_LAYOUT_FEATURE_FLAG_KEY,
    )

/** Server evaluation reason for a key that has no flag row at all (slock featureFlagService). */
private const val FEATURE_FLAG_REASON_MISSING_FLAG: String = "missing_flag"

/**
 * Runtime form protocol v2 for the create/edit agent forms (generic renderer). The same key the
 * web reads (slock `RUNTIME_FORM_V2_WEB_FLAG_KEY`), so one switch turns v2 on for every client.
 * Off, or when the v2 endpoints fail, the pinned v1 form is used unchanged.
 */
const val RUNTIME_FORM_V2_FEATURE_FLAG_KEY: String = "runtime_form_v2_web"

/** Web-shared product flags plus mobile-only runtime safety flags evaluated in the same prefetch batch. */
val REGISTERED_SERVER_FEATURE_FLAG_KEYS: List<String> = listOf(
    SYNC_CORE_MESSAGES_FEATURE_FLAG_KEY,
    SYNC_CORE_NOTIFICATION_PREFS_FEATURE_FLAG_KEY,
    SYNC_CORE_READ_STATE_V1_FEATURE_FLAG_KEY,
    ATTACHMENT_COMMENTS_FEATURE_FLAG_KEY,
    READ_RECEIPTS_FEATURE_FLAG_KEY,
    MOBILE_THREAD_PAGE_SIZE_FEATURE_FLAG_KEY,
    MOBILE_ACTIVITY_STABLE_ORDER_FEATURE_FLAG_KEY,
    WEBVIEW_ONBOARDING_V0_FEATURE_FLAG_KEY,
    PRODUCT_FEEDBACK_MULTI_APP_FEATURE_FLAG_KEY,
    THREAD_MARKDOWN_SINGLE_PARAGRAPH_LIGHT_FEATURE_FLAG_KEY,
    MOBILE_THREAD_REVERSE_LAYOUT_FEATURE_FLAG_KEY,
    ANDROID_REUSED_H2_RECOVERY_FEATURE_FLAG_KEY,
    MOBILE_CURL_TRANSPORT_FEATURE_FLAG_KEY,
    MOBILE_DOH_FALLBACK_FEATURE_FLAG_KEY,
    MOBILE_CURL_SOCKETIO_FEATURE_FLAG_KEY,
    RUNTIME_FORM_V2_FEATURE_FLAG_KEY,
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
    // task #1144: lets server rules require an OS and a minimum build. Null = unknown; the server treats
    // it as "rule does not match".
    val os: String? = null,
    val appVersion: String? = null,
    val buildNumber: String? = null,
    val buildType: String? = null,
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
    private val clientContext: () -> FeatureFlagClientContext = ::currentFeatureFlagClientContext,
) : SlockFeatureFlagsApi {
    override suspend fun evaluate(serverId: ServerId, keys: List<String>): HomeNetworkResponse {
        val client = clientContext()
        return networkClient.request(
            priority = DataRequestPriority.Background,
            path = "feature-flags/evaluate",
            method = "POST",
            serverIdOverride = serverId,
            bodyText = featureFlagsJson.encodeToString(
                FeatureFlagEvaluateRequestBody(
                    serverId = serverId.value,
                    keys = keys,
                    os = client.os,
                    appVersion = client.appVersion,
                    buildNumber = client.buildNumber,
                    buildType = client.buildType,
                ),
            ),
        )
    }
}

/**
 * Caches the registered flag evaluation per server. [prefetch] issues the batch request (this is what
 * makes `sync_core_messages_v1` traffic appear at `feature-flags/evaluate`) and updates the snapshot;
 * [snapshotFor]/[isEnabled] are safe defaults-off reads before/without a resolve. A failed fetch leaves
 * the prior snapshot (or Unresolved) — flags default OFF, so the gated feature stays dark, never broken.
 */
/**
 * Process-local diagnostic override seam for the boolean reads ([SharedServerFeatureFlags.isEnabled] /
 * [SharedServerFeatureFlags.isEnabledForAnyResolvedServer]). It receives the server-effective value and returns
 * the final one, so the diagnostics "Local Feature Overrides" page can still show server / override / final
 * side by side. `null` (the default, and every host that never entitles the override registry) reads the raw
 * snapshot; [SharedServerFeatureFlags.snapshotFor] is always raw. `serverId` is null for the process-wide
 * `isEnabledForAnyResolvedServer` shape.
 */
fun interface ServerFeatureFlagDiagnosticOverride {
    fun finalEffective(serverId: ServerId?, key: String, serverEffective: Boolean): Boolean
}

class SharedServerFeatureFlags(
    private val api: SlockFeatureFlagsApi,
    private val keys: List<String> = REGISTERED_SERVER_FEATURE_FLAG_KEYS,
    private val diagnosticsLogger: ((String) -> Unit)? = null,
    private val diagnosticOverride: ServerFeatureFlagDiagnosticOverride? = null,
    /**
     * Durable last-known values (task #1133). Consulted by [isEnabled] only while this process has no
     * online evaluation for the server yet; every accepted evaluation overwrites it.
     */
    private val lastKnown: ServerFeatureFlagLastKnownStore? = null,
    private val clientDefaultOnKeys: Set<String> = SERVER_FEATURE_FLAG_CLIENT_DEFAULT_ON_KEYS,
    // Kept last so existing trailing-lambda constructions keep binding here.
    private val onEvaluation: ((ServerId, String, Boolean, String?, String?, String?, Long) -> Unit)? = null,
) {
    private val stateLock = SynchronizedObject()
    private val nextRequestGenerationByServer = mutableMapOf<String, Long>()
    private val acceptedGenerationByServer = mutableMapOf<String, Long>()
    /**
     * Feature flags are read by more than one startup owner (the selected-server
     * collector and the Home admission host).  Keep the transport request
     * process-owned so a lifecycle cancellation of one caller cannot leave the
     * other caller with a second request or a permanently unresolved gate.
     */
    private val inFlightByServer = mutableMapOf<String, InFlightFeatureFlagRequest>()
    private val requestScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Written under [stateLock], read via isEnabled on the UI thread. @Volatile + copy-on-write lets reads
    // observe a complete immutable map without taking the writer lock; the lock prevents concurrent
    // per-server prefetches from losing one another's snapshot updates.
    @Volatile
    private var snapshots: Map<String, ServerFeatureFlagSnapshot> = emptyMap()

    fun snapshotFor(serverId: ServerId?): ServerFeatureFlagSnapshot =
        serverId?.let { snapshots[it.value] } ?: ServerFeatureFlagSnapshot.Unresolved

    fun isEnabled(serverId: ServerId?, key: String): Boolean =
        applyDiagnosticOverride(serverId, key, serverEffective(serverId, key))

    /**
     * An online evaluation from this process always decides. Before the first one lands (offline cold
     * start, or the short window before evaluate returns) a stored last-known value decides instead.
     */
    private fun serverEffective(serverId: ServerId?, key: String): Boolean {
        val online = serverId?.let { snapshots[it.value] }
        if (online != null) return online.isEnabled(key)
        if (serverId == null) return key in clientDefaultOnKeys
        val stored = lastKnown?.read(serverId, key) ?: return key in clientDefaultOnKeys
        logLastKnownUse(serverId, key, stored)
        return stored
    }

    private val loggedLastKnownUses = mutableSetOf<String>()

    private fun logLastKnownUse(serverId: ServerId, key: String, value: Boolean) {
        val logger = diagnosticsLogger ?: return
        val first = synchronized(stateLock) { loggedLastKnownUses.add("${serverId.value}|$key|$value") }
        if (first) logger("feature_flags last_known_used server=${serverId.value} key=$key enabled=$value")
    }

    private fun applyDiagnosticOverride(serverId: ServerId?, key: String, serverEffective: Boolean): Boolean =
        diagnosticOverride?.finalEffective(serverId, key, serverEffective) ?: serverEffective

    /**
     * True if [key] is enabled in ANY resolved server snapshot. Used by the sync-core shadow-compare gate,
     * which is a global diagnostic (not per-server user-facing behavior) and runs before the per-event
     * serverId is resolved. Once the server evaluate accepts `platform` (slock #186) and shadow becomes
     * per-server, replace this with a serverId-scoped read.
     */
    fun isEnabledForAnyResolvedServer(key: String): Boolean =
        applyDiagnosticOverride(null, key, snapshots.values.any { it.resolved && it.isEnabled(key) })

    /** Evaluate the registered flags for [serverId] and cache the snapshot. Returns the resolved snapshot. */
    suspend fun prefetch(
        serverId: ServerId,
        evaluationContext: String? = null,
        forceRefresh: Boolean = false,
    ): ServerFeatureFlagSnapshot {
        if (forceRefresh) {
            return prefetchUnshared(serverId, evaluationContext)
        }
        val selection = synchronized(stateLock) {
            val current = inFlightByServer[serverId.value]
            if (current != null && !current.result.isCompleted) {
                // A collector may know the environment while the admission
                // host does not. Preserve the richer context if the owner has
                // not started yet; the request itself is still one per server.
                if (current.evaluationContext == null && evaluationContext != null) {
                    current.evaluationContext = evaluationContext
                }
                current.waiters += 1
                FeatureFlagFlightSelection.Join(current)
            } else {
                val created =
                    InFlightFeatureFlagRequest(
                        result = CompletableDeferred(),
                        waiters = 1,
                        evaluationContext = evaluationContext,
                    )
                inFlightByServer[serverId.value] = created
                FeatureFlagFlightSelection.Owner(created)
            }
        }
        val flight = selection.flight

        if (selection is FeatureFlagFlightSelection.Owner) {
            // The transport owner is independent from any one Compose
            // lifecycle. A cancelled caller only relinquishes its waiter slot;
            // a surviving admission/collector caller can still receive the
            // same result.
            val ownerJob =
                requestScope.launch(start = CoroutineStart.LAZY) {
                    try {
                        val context = synchronized(stateLock) { flight.evaluationContext }
                        val snapshot =
                            prefetchUnshared(
                                serverId = serverId,
                                evaluationContext = context,
                                shouldPublish = {
                                    synchronized(stateLock) {
                                        flight.waiters > 0 && inFlightByServer[serverId.value] === flight
                                    }
                                },
                            )
                        flight.result.complete(snapshot)
                    } catch (cancellation: CancellationException) {
                        flight.result.cancel(cancellation)
                    } catch (error: Throwable) {
                        flight.result.completeExceptionally(error)
                    } finally {
                        synchronized(stateLock) {
                            if (inFlightByServer[serverId.value] === flight) {
                                inFlightByServer.remove(serverId.value)
                            }
                        }
                    }
                }
            synchronized(stateLock) {
                flight.ownerJob = ownerJob
                if (flight.cancelWhenStarted) {
                    ownerJob.cancel()
                } else {
                    ownerJob.start()
                }
            }
        } else {
            diagnosticsLogger?.invoke("feature_flags evaluate_join server=${serverId.value}")
        }

        return try {
            flight.result.await()
        } finally {
            synchronized(stateLock) {
                flight.waiters = (flight.waiters - 1).coerceAtLeast(0)
                if (
                    flight.waiters == 0 &&
                    !flight.result.isCompleted &&
                    inFlightByServer[serverId.value] === flight
                ) {
                    inFlightByServer.remove(serverId.value)
                    flight.ownerJob?.cancel() ?: run { flight.cancelWhenStarted = true }
                }
            }
        }
    }

    /** Performs one network evaluation. Callers normally enter through [prefetch]. */
    private suspend fun prefetchUnshared(
        serverId: ServerId,
        evaluationContext: String?,
        shouldPublish: () -> Boolean = { true },
    ): ServerFeatureFlagSnapshot {
        val globalRequestGeneration = featureFlagRequestGeneration.incrementAndGet()
        // Captured before the request: the result is stored as last-known only for the binding that asked.
        val lastKnownScope = lastKnown?.scopeFor(serverId)
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
        val values = keys.associateWith { it in clientDefaultOnKeys }.toMutableMap()
        val variants = linkedMapOf<String, String>()
        val observedEvaluations = linkedMapOf<String, FeatureFlagEvaluation>()
        for (evaluation in decoded.evaluations) {
            if (values.containsKey(evaluation.key)) {
                values[evaluation.key] =
                    if (evaluation.key in clientDefaultOnKeys && evaluation.reason == FEATURE_FLAG_REASON_MISSING_FLAG) {
                        true
                    } else {
                        evaluation.enabled
                    }
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
            if (!shouldPublish()) return@synchronized null
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
        if (accepted == null) return snapshotFor(serverId)
        if (accepted && lastKnown != null) {
            val stored = lastKnownScope?.let { scope -> lastKnown.writeIfCurrent(scope, snapshot.values) } == true
            val persistedKeys = snapshot.values.keys.filter { it in lastKnown.keys }
            if (persistedKeys.isNotEmpty()) {
                diagnosticsLogger?.invoke(
                    "feature_flags last_known_${if (stored) "stored" else "skipped"} server=${serverId.value} " +
                        persistedKeys.joinToString(" ") { "$it=${snapshot.values[it] == true}" }
                )
            }
        }
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

    private class InFlightFeatureFlagRequest(
        val result: CompletableDeferred<ServerFeatureFlagSnapshot>,
        var waiters: Int,
        var evaluationContext: String?,
        var ownerJob: Job? = null,
        var cancelWhenStarted: Boolean = false,
    )

    private sealed interface FeatureFlagFlightSelection {
        val flight: InFlightFeatureFlagRequest

        data class Owner(override val flight: InFlightFeatureFlagRequest) : FeatureFlagFlightSelection

        data class Join(override val flight: InFlightFeatureFlagRequest) : FeatureFlagFlightSelection
    }
}
