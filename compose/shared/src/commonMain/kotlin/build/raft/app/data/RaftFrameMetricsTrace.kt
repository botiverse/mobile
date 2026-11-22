package build.raft.app.data

import build.raft.app.model.ServerId
import build.raft.app.model.raftEpochMillisUtcIso
import build.raft.app.randomTraceHex
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Client-trace reporting for mobile frame metrics (`#proj-mobile:#270`):
 * - `mobile.frames.nav_transition`
 * - `mobile.frames.resume`
 * - `mobile.frames.scroll`
 *
 * Schema and sampling contract agreed with AndyLok (`#proj-mobile:#270 msg=98b26fdb`):
 * - Uses `RaftClientTraceEvents.emitSpan` (`/api/web-traces`, which attaches `clientPlatform` and
 *   `appVersion` and honors `RAFT_PRIVACY_MODE`).
 * - Keeps `refresh_rate_hz` (dropping `vsync_ms` and `min_frame_ms`), bare `device_model`, major
 *   `os_version`, allowlisted route/list template names only (never channel/thread IDs or titles),
 *   and renames nav `kind` to `nav_kind`.
 * - Every record carries the exact `sample_rate` it was sampled at plus `record_condition`:
 *   jank or long frames go at `1.0` (`record_condition = "jank"`); smooth sessions go at the
 *   resolved `RaftTraceSampling` base rate (`record_condition = "trace_sample"`). Weighting by
 *   `1 / sample_rate` gives unbiased fleet totals. An explicit flag off / `0.0` suppresses all
 *   records, including jank.
 */
internal const val FRAME_METRICS_LONG_FRAME_THRESHOLD_MS: Long = 100L
internal const val FRAME_METRICS_CONDITION_JANK: String = "jank"
internal const val FRAME_METRICS_CONDITION_TRACE_SAMPLE: String = "trace_sample"

private const val MAX_DEVICE_MODEL_LENGTH = 64

private val ALLOWED_FRAME_TRACE_PAGES: Set<String> =
    setOf(
        "compose_slock_app_shell",
        "compose_home",
        "compose_home_activity",
        "compose_home_saved",
        "compose_home_drafts",
        "compose_home_search",
        "compose_thread",
        "compose_thread_members",
        "compose_thread_settings",
        "compose_thread_forward_bundle_page",
        "compose_channel_settings",
        "compose_file_preview",
        "compose_artifact_viewer",
        "compose_artifacts_home",
        "compose_agent_migration_log",
        "compose_in_app_browser",
        "compose_members",
        "compose_member_detail",
        "compose_settings_home",
        "compose_settings_account",
        "compose_settings_billing",
        "compose_settings_computers",
        "compose_settings_feedback_tickets",
        "compose_settings_hands_feedback",
        "compose_settings_labs",
        "compose_settings_native_detail",
        "compose_settings_storage",
        "compose_settings_webview",
        "compose_login",
        "compose_auth_choose_server",
        "compose_auth_profile_setup",
        "compose_auth_developer",
        "compose_server_join",
        "compose_external_invite",
        "compose_assistant",
        "compose_app_update",
        "compose_release_notes",
        "compose_task_pending",
        "compose_message_link_not_found",
        "compose_message_link_public_preview",
        "compose_app_shell_child_tab",
        "compose_app_shell_server_page",
    )

private val ALLOWED_FRAME_TRACE_LISTS: Set<String> =
    setOf(
        "home",
        "thread",
        "inbox",
        "activity",
        "tasks",
        "search",
        "members",
        "files",
        "settings",
        "saved",
        "drafts",
        "artifacts",
    )

private val ALLOWED_NAV_KINDS: Set<String> = setOf("push", "pop", "predictive_back")
private val ALLOWED_NAV_OUTCOMES: Set<String> = setOf("completed", "cancelled", "interrupted")
private val ALLOWED_RESUME_REASONS: Set<String> = setOf("window", "hidden", "paused", "superseded")
private val ALLOWED_SCROLL_REASONS: Set<String> = setOf("stopped", "paused", "paused_skipped")

internal fun normalizeFrameTracePageName(raw: String?): String {
    val trimmed = raw?.trim().orEmpty()
    return if (trimmed in ALLOWED_FRAME_TRACE_PAGES) trimmed else "other"
}

internal fun normalizeFrameTraceListName(raw: String?): String {
    val trimmed = raw?.trim().orEmpty()
    return if (trimmed in ALLOWED_FRAME_TRACE_LISTS) trimmed else "other"
}

internal fun normalizeFrameTraceNavKind(raw: String?): String {
    val trimmed = raw?.trim().orEmpty()
    return if (trimmed in ALLOWED_NAV_KINDS) trimmed else "other"
}

internal fun normalizeFrameTraceNavOutcome(raw: String?): String {
    val trimmed = raw?.trim().orEmpty()
    return if (trimmed in ALLOWED_NAV_OUTCOMES) trimmed else "other"
}

internal fun normalizeFrameTraceResumeReason(raw: String?): String {
    val trimmed = raw?.trim().orEmpty()
    return if (trimmed in ALLOWED_RESUME_REASONS) trimmed else "other"
}

internal fun normalizeFrameTraceScrollReason(raw: String?): String {
    val trimmed = raw?.trim().orEmpty()
    return if (trimmed in ALLOWED_SCROLL_REASONS) trimmed else "other"
}

/** Bare device model code (e.g. `23117RK66C`), trimmed and stripped of control chars. */
internal fun normalizeFrameTraceDeviceModel(raw: String?): String? {
    val cleaned =
        raw
            ?.trim()
            ?.filter { it in ' '..'~' }
            ?.take(MAX_DEVICE_MODEL_LENGTH)
            ?.trim()
            .orEmpty()
    return cleaned.takeIf { it.isNotEmpty() }
}

/** Major OS version only (e.g. `"14.2.1"` -> `"14"`, `"12"` -> `"12"`). */
internal fun normalizeFrameTraceOsMajorVersion(raw: String?): String? {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return null
    val start = text.indexOfFirst { it in '0'..'9' }
    if (start < 0) return null
    var end = start
    while (end < text.length && text[end] in '0'..'9') {
        end += 1
    }
    return text.substring(start, end).takeIf { it.isNotEmpty() }
}

internal fun frameIntervalNanosToRefreshRateHz(frameIntervalNanos: Long): Int? {
    if (frameIntervalNanos <= 0L) return null
    val hz = (1_000_000_000.0 / frameIntervalNanos.toDouble()).roundToInt()
    return hz.takeIf { it in 1..1000 }
}

internal data class FrameMetricsTraceSampleDecision(
    val sampleRate: Double,
    val recordCondition: String,
)

internal fun isNavTransitionFrameMetricsJankOrLong(
    jankFrames: Int,
    longestFrameMs: Long,
    firstFrameMs: Long,
): Boolean =
    jankFrames > 0 ||
        longestFrameMs >= FRAME_METRICS_LONG_FRAME_THRESHOLD_MS ||
        firstFrameMs >= FRAME_METRICS_LONG_FRAME_THRESHOLD_MS

internal fun isResumeFrameMetricsJankOrLong(
    jankFrames: Int,
    longestFrameMs: Long,
    blockedMs: Long,
    firstFrameMs: Long,
): Boolean =
    jankFrames > 0 ||
        longestFrameMs >= FRAME_METRICS_LONG_FRAME_THRESHOLD_MS ||
        blockedMs >= FRAME_METRICS_LONG_FRAME_THRESHOLD_MS ||
        firstFrameMs >= FRAME_METRICS_LONG_FRAME_THRESHOLD_MS

internal fun isScrollFrameMetricsJankOrLong(
    reason: String,
    jankFrames: Int,
    longestFrameMs: Long,
    firstFrameMs: Long,
    skippedSessions: Int,
    skippedJankFrames: Int,
    skippedLongestFrameMs: Long,
): Boolean {
    val isSkippedFlush = normalizeFrameTraceScrollReason(reason) == "paused_skipped"
    val primaryJank =
        !isSkippedFlush && (
            jankFrames > 0 ||
                longestFrameMs >= FRAME_METRICS_LONG_FRAME_THRESHOLD_MS ||
                firstFrameMs >= FRAME_METRICS_LONG_FRAME_THRESHOLD_MS
            )
    val skippedJank =
        skippedSessions > 0 && (
            skippedJankFrames > 0 ||
                skippedLongestFrameMs >= FRAME_METRICS_LONG_FRAME_THRESHOLD_MS
            )
    return primaryJank || skippedJank
}

/**
 * Deterministic-partition sampling decision so weighting each emitted record by `1 / sample_rate`
 * yields unbiased fleet totals:
 * - If the flag hierarchy disables the trace (`baseRate <= 0.0` or non-finite), nothing is emitted.
 * - Records with jank or a long frame emit at `sampleRate = 1.0`, `recordCondition = "jank"`.
 * - Smooth records sample at `baseRate` (clamped to `<= 1.0`) with `recordCondition = "trace_sample"`.
 */
internal fun decideFrameMetricsTraceSampling(
    traceName: String,
    flags: ServerFeatureFlagSnapshot,
    fallbackBaseRate: Double,
    hasJankOrLongFrame: Boolean,
    random: () -> Double = { Random.nextDouble() },
): FrameMetricsTraceSampleDecision? {
    val baseRate = resolveTraceSampleRate(traceName, flags.traceSampleEvaluations(traceName), fallbackBaseRate)
    if (!baseRate.isFinite() || baseRate <= 0.0) return null
    val clampedBaseRate = baseRate.coerceAtMost(1.0)
    if (hasJankOrLongFrame) {
        return FrameMetricsTraceSampleDecision(
            sampleRate = 1.0,
            recordCondition = FRAME_METRICS_CONDITION_JANK,
        )
    }
    return if (traceSampled(clampedBaseRate, random)) {
        FrameMetricsTraceSampleDecision(
            sampleRate = clampedBaseRate,
            recordCondition = FRAME_METRICS_CONDITION_TRACE_SAMPLE,
        )
    } else {
        null
    }
}

internal fun buildNavTransitionFrameTraceSpan(
    navKind: String,
    outcome: String,
    fromPage: String,
    toPage: String,
    firstFrameMs: Long,
    durationMs: Long,
    frames: Int,
    jankFrames: Int,
    longestFrameMs: Long,
    p95FrameMs: Long,
    refreshRateHz: Int?,
    refreshRateEndHz: Int?,
    deviceModel: String?,
    osVersion: String?,
    sampleRate: Double,
    recordCondition: String,
    endEpochMillis: Long,
    random: Random = Random,
): RaftWebSpanRecord {
    val clampedDurationMs = durationMs.coerceAtLeast(0L)
    val startEpochMillis = (endEpochMillis - clampedDurationMs).coerceAtLeast(0L)
    val attrs =
        buildMap {
            put("nav_kind", JsonPrimitive(normalizeFrameTraceNavKind(navKind)))
            put("outcome", JsonPrimitive(normalizeFrameTraceNavOutcome(outcome)))
            put("from_page", JsonPrimitive(normalizeFrameTracePageName(fromPage)))
            put("to_page", JsonPrimitive(normalizeFrameTracePageName(toPage)))
            put("duration_ms", JsonPrimitive(clampedDurationMs))
            put("frames", JsonPrimitive(frames.coerceAtLeast(0)))
            put("jank_frames", JsonPrimitive(jankFrames.coerceAtLeast(0)))
            put("longest_frame_ms", JsonPrimitive(longestFrameMs.coerceAtLeast(0L)))
            put("p95_frame_ms", JsonPrimitive(p95FrameMs.coerceAtLeast(0L)))
            if (firstFrameMs >= 0L) put("first_frame_ms", JsonPrimitive(firstFrameMs))
            refreshRateHz?.let { put("refresh_rate_hz", JsonPrimitive(it)) }
            refreshRateEndHz?.let { put("refresh_rate_end_hz", JsonPrimitive(it)) }
            normalizeFrameTraceDeviceModel(deviceModel)?.let { put("device_model", JsonPrimitive(it)) }
            normalizeFrameTraceOsMajorVersion(osVersion)?.let { put("os_version", JsonPrimitive(it)) }
            put("sample_rate", JsonPrimitive(sampleRate))
            put("record_condition", JsonPrimitive(recordCondition))
        }
    return RaftWebSpanRecord(
        traceId = randomTraceHex(16, random),
        spanId = randomTraceHex(8, random),
        name = MOBILE_FRAMES_NAV_TRANSITION_TRACE_NAME,
        startTime = raftEpochMillisUtcIso(startEpochMillis),
        endTime = raftEpochMillisUtcIso(endEpochMillis),
        attrs = JsonObject(attrs),
    )
}

internal fun buildResumeFrameTraceSpan(
    page: String,
    reason: String,
    backgroundMs: Long,
    startToResumeMs: Long,
    firstFrameMs: Long,
    windowMs: Long,
    frames: Int,
    jankFrames: Int,
    blockedMs: Long,
    longestFrameMs: Long,
    refreshRateHz: Int?,
    deviceModel: String?,
    osVersion: String?,
    sampleRate: Double,
    recordCondition: String,
    endEpochMillis: Long,
    random: Random = Random,
): RaftWebSpanRecord {
    val clampedWindowMs = windowMs.coerceAtLeast(0L)
    val startEpochMillis = (endEpochMillis - clampedWindowMs).coerceAtLeast(0L)
    val attrs =
        buildMap {
            put("page", JsonPrimitive(normalizeFrameTracePageName(page)))
            put("reason", JsonPrimitive(normalizeFrameTraceResumeReason(reason)))
            if (backgroundMs >= 0L) put("background_ms", JsonPrimitive(backgroundMs))
            if (startToResumeMs >= 0L) put("start_to_resume_ms", JsonPrimitive(startToResumeMs))
            put("window_ms", JsonPrimitive(clampedWindowMs))
            put("frames", JsonPrimitive(frames.coerceAtLeast(0)))
            put("jank_frames", JsonPrimitive(jankFrames.coerceAtLeast(0)))
            put("blocked_ms", JsonPrimitive(blockedMs.coerceAtLeast(0L)))
            put("longest_frame_ms", JsonPrimitive(longestFrameMs.coerceAtLeast(0L)))
            if (firstFrameMs >= 0L) put("first_frame_ms", JsonPrimitive(firstFrameMs))
            refreshRateHz?.let { put("refresh_rate_hz", JsonPrimitive(it)) }
            normalizeFrameTraceDeviceModel(deviceModel)?.let { put("device_model", JsonPrimitive(it)) }
            normalizeFrameTraceOsMajorVersion(osVersion)?.let { put("os_version", JsonPrimitive(it)) }
            put("sample_rate", JsonPrimitive(sampleRate))
            put("record_condition", JsonPrimitive(recordCondition))
        }
    return RaftWebSpanRecord(
        traceId = randomTraceHex(16, random),
        spanId = randomTraceHex(8, random),
        name = MOBILE_FRAMES_RESUME_TRACE_NAME,
        startTime = raftEpochMillisUtcIso(startEpochMillis),
        endTime = raftEpochMillisUtcIso(endEpochMillis),
        attrs = JsonObject(attrs),
    )
}

internal fun buildScrollFrameTraceSpan(
    page: String,
    list: String,
    reason: String,
    firstFrameMs: Long,
    durationMs: Long,
    frames: Int,
    jankFrames: Int,
    longestFrameMs: Long,
    p95FrameMs: Long,
    refreshRateHz: Int?,
    refreshRateEndHz: Int?,
    skippedSessions: Int,
    skippedJankFrames: Int,
    skippedLongestFrameMs: Long,
    deviceModel: String?,
    osVersion: String?,
    sampleRate: Double,
    recordCondition: String,
    endEpochMillis: Long,
    random: Random = Random,
): RaftWebSpanRecord {
    val normalizedReason = normalizeFrameTraceScrollReason(reason)
    val isSkippedFlush = normalizedReason == "paused_skipped"
    val clampedDurationMs = if (isSkippedFlush) 0L else durationMs.coerceAtLeast(0L)
    val startEpochMillis = (endEpochMillis - clampedDurationMs).coerceAtLeast(0L)
    val attrs =
        buildMap {
            put("page", JsonPrimitive(normalizeFrameTracePageName(page)))
            put("list", JsonPrimitive(normalizeFrameTraceListName(list)))
            put("reason", JsonPrimitive(normalizedReason))
            if (!isSkippedFlush) {
                put("duration_ms", JsonPrimitive(clampedDurationMs))
                put("frames", JsonPrimitive(frames.coerceAtLeast(0)))
                put("jank_frames", JsonPrimitive(jankFrames.coerceAtLeast(0)))
                put("longest_frame_ms", JsonPrimitive(longestFrameMs.coerceAtLeast(0L)))
                put("p95_frame_ms", JsonPrimitive(p95FrameMs.coerceAtLeast(0L)))
                if (firstFrameMs >= 0L) put("first_frame_ms", JsonPrimitive(firstFrameMs))
                refreshRateHz?.let { put("refresh_rate_hz", JsonPrimitive(it)) }
                refreshRateEndHz?.let { put("refresh_rate_end_hz", JsonPrimitive(it)) }
            }
            val safeSkippedSessions = skippedSessions.coerceAtLeast(0)
            put("skipped_sessions", JsonPrimitive(safeSkippedSessions))
            if (safeSkippedSessions > 0) {
                put("skipped_jank_frames", JsonPrimitive(skippedJankFrames.coerceAtLeast(0)))
                put("skipped_longest_frame_ms", JsonPrimitive(skippedLongestFrameMs.coerceAtLeast(0L)))
            }
            normalizeFrameTraceDeviceModel(deviceModel)?.let { put("device_model", JsonPrimitive(it)) }
            normalizeFrameTraceOsMajorVersion(osVersion)?.let { put("os_version", JsonPrimitive(it)) }
            put("sample_rate", JsonPrimitive(sampleRate))
            put("record_condition", JsonPrimitive(recordCondition))
        }
    return RaftWebSpanRecord(
        traceId = randomTraceHex(16, random),
        spanId = randomTraceHex(8, random),
        name = MOBILE_FRAMES_SCROLL_TRACE_NAME,
        startTime = raftEpochMillisUtcIso(startEpochMillis),
        endTime = raftEpochMillisUtcIso(endEpochMillis),
        attrs = JsonObject(attrs),
    )
}

/**
 * Non-blocking, non-throwing entry point called by host frame probes (Android `RaftNavTransitionFrameProbe`,
 * `RaftResumeFrameProbe`, `RaftScrollFrameProbe`; iOS probes later). Performs lock-free in-memory
 * session/flag reads (zero disk I/O or suspend/Mutex waits on the main thread) and in-memory trace enqueue.
 */
object RaftFrameMetricsTraceReporter {
    private fun currentServerAndFlags(): Pair<String, ServerFeatureFlagSnapshot>? {
        val provider = RaftSharedStoreRegistry.currentOrNull() ?: return null
        val serverId =
            provider.authStore
                .readInMemorySessionSnapshot()
                ?.session
                ?.currentServerId
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: return null
        return serverId to provider.serverFeatureFlags.snapshotFor(ServerId(serverId))
    }

    fun reportNavTransition(
        navKind: String,
        outcome: String,
        fromPage: String,
        toPage: String,
        firstFrameMs: Long,
        durationMs: Long,
        frames: Int,
        jankFrames: Int,
        longestFrameMs: Long,
        p95FrameMs: Long,
        frameIntervalNanos: Long,
        endFrameIntervalNanos: Long,
        deviceModel: String?,
        osVersion: String?,
    ) {
        runCatching {
            val (serverId, flags) = currentServerAndFlags() ?: return
            val hasJankOrLong =
                isNavTransitionFrameMetricsJankOrLong(
                    jankFrames = jankFrames,
                    longestFrameMs = longestFrameMs,
                    firstFrameMs = firstFrameMs,
                )
            val decision =
                decideFrameMetricsTraceSampling(
                    traceName = MOBILE_FRAMES_NAV_TRANSITION_TRACE_NAME,
                    flags = flags,
                    fallbackBaseRate = defaultTraceSampleRate(),
                    hasJankOrLongFrame = hasJankOrLong,
                ) ?: return
            val span =
                buildNavTransitionFrameTraceSpan(
                    navKind = navKind,
                    outcome = outcome,
                    fromPage = fromPage,
                    toPage = toPage,
                    firstFrameMs = firstFrameMs,
                    durationMs = durationMs,
                    frames = frames,
                    jankFrames = jankFrames,
                    longestFrameMs = longestFrameMs,
                    p95FrameMs = p95FrameMs,
                    refreshRateHz = frameIntervalNanosToRefreshRateHz(frameIntervalNanos),
                    refreshRateEndHz = frameIntervalNanosToRefreshRateHz(endFrameIntervalNanos),
                    deviceModel = deviceModel,
                    osVersion = osVersion,
                    sampleRate = decision.sampleRate,
                    recordCondition = decision.recordCondition,
                    endEpochMillis = ProcessRaftDatetimeFoundationOwner.nowEpochMillis(),
                )
            RaftClientTraceEvents.emitSpan(serverId, span, emptyList())
        }
    }

    fun reportResume(
        page: String,
        reason: String,
        backgroundMs: Long,
        startToResumeMs: Long,
        firstFrameMs: Long,
        windowMs: Long,
        frames: Int,
        jankFrames: Int,
        blockedMs: Long,
        longestFrameMs: Long,
        frameIntervalNanos: Long,
        deviceModel: String?,
        osVersion: String?,
    ) {
        runCatching {
            val (serverId, flags) = currentServerAndFlags() ?: return
            val hasJankOrLong =
                isResumeFrameMetricsJankOrLong(
                    jankFrames = jankFrames,
                    longestFrameMs = longestFrameMs,
                    blockedMs = blockedMs,
                    firstFrameMs = firstFrameMs,
                )
            val decision =
                decideFrameMetricsTraceSampling(
                    traceName = MOBILE_FRAMES_RESUME_TRACE_NAME,
                    flags = flags,
                    fallbackBaseRate = defaultTraceSampleRate(),
                    hasJankOrLongFrame = hasJankOrLong,
                ) ?: return
            val span =
                buildResumeFrameTraceSpan(
                    page = page,
                    reason = reason,
                    backgroundMs = backgroundMs,
                    startToResumeMs = startToResumeMs,
                    firstFrameMs = firstFrameMs,
                    windowMs = windowMs,
                    frames = frames,
                    jankFrames = jankFrames,
                    blockedMs = blockedMs,
                    longestFrameMs = longestFrameMs,
                    refreshRateHz = frameIntervalNanosToRefreshRateHz(frameIntervalNanos),
                    deviceModel = deviceModel,
                    osVersion = osVersion,
                    sampleRate = decision.sampleRate,
                    recordCondition = decision.recordCondition,
                    endEpochMillis = ProcessRaftDatetimeFoundationOwner.nowEpochMillis(),
                )
            RaftClientTraceEvents.emitSpan(serverId, span, emptyList())
        }
    }

    fun reportScroll(
        page: String,
        list: String,
        reason: String,
        firstFrameMs: Long,
        durationMs: Long,
        frames: Int,
        jankFrames: Int,
        longestFrameMs: Long,
        p95FrameMs: Long,
        frameIntervalNanos: Long,
        endFrameIntervalNanos: Long,
        skippedSessions: Int,
        skippedJankFrames: Int,
        skippedLongestFrameMs: Long,
        deviceModel: String?,
        osVersion: String?,
    ) {
        runCatching {
            val (serverId, flags) = currentServerAndFlags() ?: return
            val hasJankOrLong =
                isScrollFrameMetricsJankOrLong(
                    reason = reason,
                    jankFrames = jankFrames,
                    longestFrameMs = longestFrameMs,
                    firstFrameMs = firstFrameMs,
                    skippedSessions = skippedSessions,
                    skippedJankFrames = skippedJankFrames,
                    skippedLongestFrameMs = skippedLongestFrameMs,
                )
            val decision =
                decideFrameMetricsTraceSampling(
                    traceName = MOBILE_FRAMES_SCROLL_TRACE_NAME,
                    flags = flags,
                    fallbackBaseRate = defaultScrollFrameTraceSampleRate(),
                    hasJankOrLongFrame = hasJankOrLong,
                ) ?: return
            val span =
                buildScrollFrameTraceSpan(
                    page = page,
                    list = list,
                    reason = reason,
                    firstFrameMs = firstFrameMs,
                    durationMs = durationMs,
                    frames = frames,
                    jankFrames = jankFrames,
                    longestFrameMs = longestFrameMs,
                    p95FrameMs = p95FrameMs,
                    refreshRateHz = frameIntervalNanosToRefreshRateHz(frameIntervalNanos),
                    refreshRateEndHz = frameIntervalNanosToRefreshRateHz(endFrameIntervalNanos),
                    skippedSessions = skippedSessions,
                    skippedJankFrames = skippedJankFrames,
                    skippedLongestFrameMs = skippedLongestFrameMs,
                    deviceModel = deviceModel,
                    osVersion = osVersion,
                    sampleRate = decision.sampleRate,
                    recordCondition = decision.recordCondition,
                    endEpochMillis = ProcessRaftDatetimeFoundationOwner.nowEpochMillis(),
                )
            RaftClientTraceEvents.emitSpan(serverId, span, emptyList())
        }
    }

    fun reportScrollSkippedFlush(
        page: String,
        list: String,
        skippedSessions: Int,
        skippedJankFrames: Int,
        skippedLongestFrameMs: Long,
        deviceModel: String?,
        osVersion: String?,
    ) {
        if (skippedSessions <= 0) return
        reportScroll(
            page = page,
            list = list,
            reason = "paused_skipped",
            firstFrameMs = -1L,
            durationMs = 0L,
            frames = 0,
            jankFrames = 0,
            longestFrameMs = 0L,
            p95FrameMs = 0L,
            frameIntervalNanos = 0L,
            endFrameIntervalNanos = 0L,
            skippedSessions = skippedSessions,
            skippedJankFrames = skippedJankFrames,
            skippedLongestFrameMs = skippedLongestFrameMs,
            deviceModel = deviceModel,
            osVersion = osVersion,
        )
    }
}
