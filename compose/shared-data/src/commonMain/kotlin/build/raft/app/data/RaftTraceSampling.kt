package build.raft.app.data

import build.raft.app.RaftBuildFlags
import build.raft.app.RaftCoreInternalApi
import build.raft.app.model.ServerId
import build.raft.app.network.RaftAppIdentityRegistry
import kotlin.random.Random

/**
 * Trace sampling as a platform setting: the App's mirror of botiverse/slock
 * `packages/shared/src/traceSampling.ts` (docs/observability/trace-sampling.md, slock #15673 at
 * 624b7701b). One feature flag per sampled trace, key `trace_sample.<trace name or prefix>`, whose
 * variant is the rate. Keep the two in step; `RaftTraceSamplingTest` carries the same vectors.
 */
const val TRACE_SAMPLE_FLAG_PREFIX: String = "trace_sample"

/** Sampled mobile trace span / event names (`trace_sample.<name>` hierarchy). */
const val MOBILE_THREAD_OPEN_TRACE_NAME: String = "mobile.thread_open"
const val MOBILE_FRAMES_NAV_TRANSITION_TRACE_NAME: String = "mobile.frames.nav_transition"
const val MOBILE_FRAMES_RESUME_TRACE_NAME: String = "mobile.frames.resume"
const val MOBILE_FRAMES_SCROLL_TRACE_NAME: String = "mobile.frames.scroll"
const val MOBILE_HTTP_ERROR_TRACE_NAME: String = "mobile.http_error"
const val MOBILE_UI_ERROR_TRACE_NAME: String = "mobile.ui_error"
const val MOBILE_WEBVIEW_TRACE_NAME: String = "mobile.webview"
const val MOBILE_WEB_CONTAINER_TRACE_NAME: String = "mobile.web_container"

/** The evaluate answer fields the rate is read from (a subset of the server's FeatureFlagEvaluation). */
data class TraceSampleEvaluation(
    val key: String,
    val enabled: Boolean,
    val reason: String,
    val variant: String? = null,
)

/**
 * The flag keys that can set [traceName]'s rate, most specific first:
 * `mobile.thread_open` -> trace_sample.mobile.thread_open, trace_sample.mobile, trace_sample.
 */
fun traceSampleKeys(traceName: String): List<String> {
    val parts = traceName.split(".").filter { it.isNotEmpty() }
    val keys = ArrayList<String>(parts.size + 1)
    for (n in parts.size downTo 1) keys += "$TRACE_SAMPLE_FLAG_PREFIX.${parts.subList(0, n).joinToString(".")}"
    keys += TRACE_SAMPLE_FLAG_PREFIX
    return keys
}

private val TraceSampleRatePattern = Regex("""^(?:0(?:\.[0-9]+)?|1(?:\.0+)?|\.[0-9]+)$""")

/** A variant as a rate in [0, 1]; null when it is not one ("10%", "0.l", "1e-1", "1.5", ""). */
fun parseTraceSampleRate(variant: String?): Double? {
    val trimmed = variant?.trim() ?: return null
    if (!TraceSampleRatePattern.matches(trimmed)) return null
    val rate = trimmed.toDoubleOrNull() ?: return null
    return rate.takeIf { it.isFinite() && it >= 0.0 && it <= 1.0 }
}

/**
 * The rate for [traceName]: the most specific key the answer has a flag for (anything but
 * `missing_flag`) decides. Off -> 0; on with no variant -> 1; on with a rate variant -> that rate.
 * When no key has a flag, or the deciding variant is not a rate, [fallback] (the App's built-in
 * default) applies.
 */
fun resolveTraceSampleRate(traceName: String, evaluations: List<TraceSampleEvaluation>, fallback: Double): Double {
    val byKey = evaluations.associateBy { it.key }
    for (key in traceSampleKeys(traceName)) {
        val evaluation = byKey[key] ?: continue
        if (evaluation.reason == FEATURE_FLAG_REASON_MISSING_FLAG) continue
        if (!evaluation.enabled) return 0.0
        if (evaluation.variant == null || evaluation.variant == "") return 1.0
        return parseTraceSampleRate(evaluation.variant) ?: fallback
    }
    return fallback
}

/** One sampling decision per unit of work (one open): sampled in when random < rate. */
fun traceSampled(rate: Double, random: () -> Double = { Random.nextDouble() }): Boolean =
    rate >= 1.0 || (rate > 0.0 && random() < rate)

/**
 * The evaluate answers a snapshot holds for [traceName]'s keys. The snapshot keeps a trimmed variant
 * only for an enabled flag ([ServerFeatureFlagSnapshot.variants]); a disabled flag reads 0 either
 * way, so only one case differs from the web: a whitespace-only variant reads as "no variant" (1).
 * An unresolved snapshot holds none, so the App default applies.
 */
fun ServerFeatureFlagSnapshot.traceSampleEvaluations(traceName: String): List<TraceSampleEvaluation> {
    if (!resolved) return emptyList()
    return traceSampleKeys(traceName).mapNotNull { key ->
        val reason = reasons[key] ?: return@mapNotNull null
        TraceSampleEvaluation(key = key, enabled = values[key] == true, reason = reason, variant = variants[key])
    }
}

/** The App's built-in rates when no flag decides: every open on alpha and debug builds, 1 in 10 on release. */
const val TRACE_SAMPLE_DEFAULT_RELEASE_RATE: Double = 0.1
const val TRACE_SAMPLE_SCROLL_DEFAULT_RELEASE_RATE: Double = 0.02
const val TRACE_SAMPLE_DEFAULT_PRERELEASE_RATE: Double = 1.0

/** Built-in default for failure/error families (`mobile.http_error`, `mobile.ui_error`, `mobile.web_container`). */
const val TRACE_SAMPLE_ERROR_DEFAULT_RATE: Double = 1.0

fun defaultTraceSampleRate(
    buildType: String? = RaftAppIdentityRegistry.current()?.buildType,
    debugBuild: Boolean = RaftBuildFlags.isDebugBuild,
): Double =
    if (debugBuild || buildType == "alpha" || buildType == "debug") TRACE_SAMPLE_DEFAULT_PRERELEASE_RATE else TRACE_SAMPLE_DEFAULT_RELEASE_RATE

/** Scroll sessions fire much more frequently than nav transitions or resumes: 2% on release builds by default. */
fun defaultScrollFrameTraceSampleRate(
    buildType: String? = RaftAppIdentityRegistry.current()?.buildType,
    debugBuild: Boolean = RaftBuildFlags.isDebugBuild,
): Double =
    if (debugBuild || buildType == "alpha" || buildType == "debug") TRACE_SAMPLE_DEFAULT_PRERELEASE_RATE else TRACE_SAMPLE_SCROLL_DEFAULT_RELEASE_RATE

/** Smooth `mobile.webview.load` foreground opens: 10% on release, 100% on alpha/debug (#proj-mobile:#399). */
fun defaultWebViewForegroundTraceSampleRate(
    buildType: String? = RaftAppIdentityRegistry.current()?.buildType,
    debugBuild: Boolean = RaftBuildFlags.isDebugBuild,
): Double = defaultTraceSampleRate(buildType, debugBuild)

/** Smooth `mobile.webview.load` background prewarms: 2% on release, 100% on alpha/debug (#proj-mobile:#399). */
fun defaultWebViewPrewarmTraceSampleRate(
    buildType: String? = RaftAppIdentityRegistry.current()?.buildType,
    debugBuild: Boolean = RaftBuildFlags.isDebugBuild,
): Double = defaultScrollFrameTraceSampleRate(buildType, debugBuild)

/**
 * Resolves [traceName]'s sampling rate for [serverId] against the active [SharedServerFeatureFlags]
 * trace-sampling snapshot (or [flags] when provided), falling back to [fallback] when unresolved or unset.
 */
@RaftCoreInternalApi fun resolveActiveServerTraceSampleRate(
    serverId: String,
    traceName: String,
    fallback: Double,
    flags: ServerFeatureFlagSnapshot? = null,
): Double {
    val snapshot =
        flags ?: runCatching {
            RaftDataStoreRegistry.currentOrNull()?.serverFeatureFlags?.traceSampleSnapshotFor(ServerId(serverId))
        }.getOrNull() ?: ServerFeatureFlagSnapshot.Unresolved
    return resolveTraceSampleRate(traceName, snapshot.traceSampleEvaluations(traceName), fallback)
}
