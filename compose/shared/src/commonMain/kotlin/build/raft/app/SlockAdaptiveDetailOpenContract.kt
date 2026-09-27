package build.raft.app

import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlinx.serialization.json.Json

internal const val SLOCK_ADAPTIVE_DETAIL_OPEN_EVENT = "slockAdaptiveDetailOpen"
internal const val SLOCK_ADAPTIVE_DETAIL_OPEN_ACK_EVENT = "slockAdaptiveDetailOpenAck"
internal const val SLOCK_ADAPTIVE_DETAIL_READY_EVENT = "slockAdaptiveDetailReady"

/**
 * Window fact carried inside an adoption request. Mirrors the host emitter fields so the shell can
 * replay [SlockAdaptiveWorkspaceIntent.WindowChanged] with the fact the covered shell missed while
 * a standalone Thread held the top of the native stack. `capturedAtMillis` is the device wall-clock
 * stamp taken by the requesting pager at send time and is the freshness ordering field — sequences
 * are only comparable within the same [SlockAdaptiveDetailOpenRequest.hostId].
 */
internal data class SlockAdaptiveOpenWindowFact(
    val widthPx: Int,
    val heightPx: Int,
    val density: Float,
    val posture: String,
    val capturedAtMillis: Long,
) {
    fun toJson(): JSONObject =
        JSONObject()
            .put(KEY_WIDTH_PX, widthPx)
            .put(KEY_HEIGHT_PX, heightPx)
            .put(KEY_DENSITY, density.toDouble())
            .put(KEY_POSTURE, posture)
            .put(KEY_CAPTURED_AT_MS, capturedAtMillis.toDouble())

    fun isValid(): Boolean =
        widthPx > 0 && heightPx > 0 && density > 0f && capturedAtMillis > 0L

    fun toSlockAdaptivePaneEvent(): SlockAdaptivePaneEvent =
        SlockAdaptivePaneEvent(
            widthPx = widthPx,
            heightPx = heightPx,
            density = density,
            posture =
                when (posture.trim().lowercase()) {
                    "flat", "expanded" -> SlockAdaptivePanePosture.Flat
                    "folded" -> SlockAdaptivePanePosture.Folded
                    "half_folded" -> SlockAdaptivePanePosture.HalfFolded
                    else -> SlockAdaptivePanePosture.Unknown
                }
        )

    companion object {
        internal const val KEY_WIDTH_PX = "widthPx"
        internal const val KEY_HEIGHT_PX = "heightPx"
        internal const val KEY_DENSITY = "density"
        internal const val KEY_POSTURE = "posture"
        internal const val KEY_CAPTURED_AT_MS = "capturedAtMillis"
    }
}

internal fun JSONObject.toSlockAdaptiveOpenWindowFactOrNull(): SlockAdaptiveOpenWindowFact? {
    val widthPx = optDouble(SlockAdaptiveOpenWindowFact.KEY_WIDTH_PX, 0.0).toInt()
    val heightPx = optDouble(SlockAdaptiveOpenWindowFact.KEY_HEIGHT_PX, 0.0).toInt()
    val density = optDouble(SlockAdaptiveOpenWindowFact.KEY_DENSITY, 0.0).toFloat()
    val capturedAtMillis =
        optDouble(SlockAdaptiveOpenWindowFact.KEY_CAPTURED_AT_MS, 0.0).toLong()
    val fact =
        SlockAdaptiveOpenWindowFact(
            widthPx = widthPx,
            heightPx = heightPx,
            density = density,
            posture = optString(SlockAdaptiveOpenWindowFact.KEY_POSTURE),
            capturedAtMillis = capturedAtMillis,
        )
    return fact.takeIf { it.isValid() }
}

/**
 * Adoption request posted by the top-of-stack standalone Thread (or an external THREAD_PAGE entry
 * point) that just learned the window became DualPane. Carries only navigation intent — server
 * switch and authority checks run through the shell's existing reduce path.
 *
 * - [requestId] deduplicates retries; the shell ACKs the same requestId idempotently.
 * - [hostId] identifies the emitting host/pager so freshness comparison stays within one sequence;
 *   requests from a different host are ordered by [SlockAdaptiveOpenWindowFact.capturedAtMillis].
 */
internal data class SlockAdaptiveDetailOpenRequest(
    val shellInstanceId: String,
    val requestId: String,
    val hostId: String,
    val identity: SlockAdaptiveDetailIdentity,
    val launchPayloadJson: String,
    val windowFact: SlockAdaptiveOpenWindowFact,
) {
    init {
        require(shellInstanceId.isNotBlank()) { "Adaptive open requires a shell id" }
        require(requestId.isNotBlank()) { "Adaptive open requires a request id" }
        require(hostId.isNotBlank()) { "Adaptive open requires a host id" }
        require(launchPayloadJson.isNotBlank()) { "Adaptive open requires a launch payload" }
        require(windowFact.isValid()) { "Adaptive open requires a valid window fact" }
    }

    fun toJson(): JSONObject =
        JSONObject()
            .put(KEY_SHELL_ID, shellInstanceId)
            .put(KEY_REQUEST_ID, requestId)
            .put(KEY_HOST_ID, hostId)
            .put(KEY_SERVER_ID, identity.serverId)
            .put(KEY_THREAD_ID, identity.threadId)
            .put(KEY_SURFACE_KIND, identity.surfaceKind)
            .put(KEY_CHANNEL_ID, identity.channelId.orEmpty())
            .put(KEY_STREAM_CHANNEL_ID, identity.streamChannelId.orEmpty())
            .put(KEY_PARENT_CHANNEL_ID, identity.parentChannelId.orEmpty())
            .put(KEY_PARENT_MESSAGE_ID, identity.parentMessageId.orEmpty())
            .put(KEY_FOCUSED_MESSAGE_ID, identity.focusedMessageId.orEmpty())
            .put(KEY_LAUNCH_PAYLOAD, launchPayloadJson)
            .put(KEY_WINDOW_FACT, windowFact.toJson())

    companion object {
        internal const val KEY_SHELL_ID = "shellInstanceId"
        internal const val KEY_REQUEST_ID = "requestId"
        internal const val KEY_HOST_ID = "hostId"
        internal const val KEY_SERVER_ID = "serverId"
        internal const val KEY_THREAD_ID = "threadId"
        internal const val KEY_SURFACE_KIND = "surfaceKind"
        internal const val KEY_CHANNEL_ID = "channelId"
        internal const val KEY_STREAM_CHANNEL_ID = "streamChannelId"
        internal const val KEY_PARENT_CHANNEL_ID = "parentChannelId"
        internal const val KEY_PARENT_MESSAGE_ID = "parentMessageId"
        internal const val KEY_FOCUSED_MESSAGE_ID = "focusedMessageId"
        internal const val KEY_LAUNCH_PAYLOAD = "launchPayload"
        internal const val KEY_WINDOW_FACT = "windowFact"
    }
}

internal fun JSONObject.toSlockAdaptiveDetailOpenRequestOrNull(): SlockAdaptiveDetailOpenRequest? {
    val shellInstanceId = optString(SlockAdaptiveDetailOpenRequest.KEY_SHELL_ID).trim()
    val requestId = optString(SlockAdaptiveDetailOpenRequest.KEY_REQUEST_ID).trim()
    val hostId = optString(SlockAdaptiveDetailOpenRequest.KEY_HOST_ID).trim()
    val serverId = optString(SlockAdaptiveDetailOpenRequest.KEY_SERVER_ID).trim()
    val threadId = optString(SlockAdaptiveDetailOpenRequest.KEY_THREAD_ID).trim()
    val surfaceKind = optString(SlockAdaptiveDetailOpenRequest.KEY_SURFACE_KIND).trim()
    val launchPayloadJson = optString(SlockAdaptiveDetailOpenRequest.KEY_LAUNCH_PAYLOAD).trim()
    val windowFactJson =
        optJSONObject(SlockAdaptiveDetailOpenRequest.KEY_WINDOW_FACT) ?: return null
    val windowFact = windowFactJson.toSlockAdaptiveOpenWindowFactOrNull() ?: return null
    if (
        shellInstanceId.isBlank() || requestId.isBlank() || hostId.isBlank() ||
        serverId.isBlank() || threadId.isBlank() || surfaceKind.isBlank() ||
        launchPayloadJson.isBlank()
    ) {
        return null
    }
    val identity =
        SlockAdaptiveDetailIdentity(
            serverId = serverId,
            threadId = threadId,
            surfaceKind = surfaceKind,
            channelId =
                optString(SlockAdaptiveDetailOpenRequest.KEY_CHANNEL_ID).trim()
                    .takeIf { it.isNotEmpty() },
            streamChannelId =
                optString(SlockAdaptiveDetailOpenRequest.KEY_STREAM_CHANNEL_ID).trim()
                    .takeIf { it.isNotEmpty() },
            parentChannelId =
                optString(SlockAdaptiveDetailOpenRequest.KEY_PARENT_CHANNEL_ID).trim()
                    .takeIf { it.isNotEmpty() },
            parentMessageId =
                optString(SlockAdaptiveDetailOpenRequest.KEY_PARENT_MESSAGE_ID).trim()
                    .takeIf { it.isNotEmpty() },
            focusedMessageId =
                optString(SlockAdaptiveDetailOpenRequest.KEY_FOCUSED_MESSAGE_ID).trim()
                    .takeIf { it.isNotEmpty() },
        )
    return SlockAdaptiveDetailOpenRequest(
        shellInstanceId = shellInstanceId,
        requestId = requestId,
        hostId = hostId,
        identity = identity,
        launchPayloadJson = launchPayloadJson,
        windowFact = windowFact,
    )
}

/** Result of the shell's acceptance evaluation for one [SlockAdaptiveDetailOpenRequest]. */
internal sealed interface SlockAdaptiveDetailOpenDecision {
    /** Shell will install the detail; ack follows after the child page reports ready. */
    data class Accepted(val generation: Long) : SlockAdaptiveDetailOpenDecision

    /** Shell refused; the source page keeps its native stack position (retry or fall back). */
    data class Rejected(val reason: String) : SlockAdaptiveDetailOpenDecision
}

/**
 * Freshness gate for replaying a carried window fact into the shell's pane state. Window facts are
 * only ordered within the same host sequence; cross-host requests order by wall-clock capture time
 * (same device). A request strictly older than the fact the shell already applied cannot resurrect
 * a stale DualPane.
 */
internal fun adaptiveWindowFactReplayAccepted(
    lastHostId: String?,
    lastCapturedAtMillis: Long,
    requestHostId: String,
    requestCapturedAtMillis: Long,
): Boolean =
    if (lastHostId == requestHostId) {
        requestCapturedAtMillis >= lastCapturedAtMillis
    } else {
        requestCapturedAtMillis > lastCapturedAtMillis
    }

internal enum class SlockAdaptiveDetailOpenRejectReason(val wireName: String) {
    /** Native guarded top-pop is not available; the migration path must stay inert (task #18). */
    NATIVE_COMMIT_UNAVAILABLE("native_commit_unavailable"),

    /** Request fact older than the freshest fact the shell applied; window may have narrowed. */
    STALE_WINDOW_FACT("stale_window_fact"),

    /** Request targets a different authority (server switched since the request was captured). */
    AUTHORITY_MISMATCH("authority_mismatch"),

    /** Workspace/pane is no longer DualPane after replaying the carried fact. */
    NOT_DUAL_PANE("not_dual_pane"),

    /** Launch payload failed to decode into a routable thread payload. */
    PAYLOAD_INVALID("payload_invalid"),
}

internal data class SlockAdaptiveDetailOpenAck(
    val shellInstanceId: String,
    val requestId: String,
    val accepted: Boolean,
    val generation: Long = 0L,
    val reason: String = "",
) {
    init {
        require(shellInstanceId.isNotBlank()) { "Adaptive open ack requires a shell id" }
        require(requestId.isNotBlank()) { "Adaptive open ack requires a request id" }
    }

    fun toJson(): JSONObject =
        JSONObject()
            .put(SlockAdaptiveDetailOpenRequest.KEY_SHELL_ID, shellInstanceId)
            .put(SlockAdaptiveDetailOpenRequest.KEY_REQUEST_ID, requestId)
            .put(KEY_ACCEPTED, accepted)
            .put(KEY_GENERATION, generation.toDouble())
            .put(KEY_REASON, reason)

    companion object {
        internal const val KEY_ACCEPTED = "accepted"
        internal const val KEY_GENERATION = "generation"
        internal const val KEY_REASON = "reason"
    }
}

internal fun JSONObject.toSlockAdaptiveDetailOpenAckOrNull(): SlockAdaptiveDetailOpenAck? {
    val shellInstanceId = optString(SlockAdaptiveDetailOpenRequest.KEY_SHELL_ID).trim()
    val requestId = optString(SlockAdaptiveDetailOpenRequest.KEY_REQUEST_ID).trim()
    if (shellInstanceId.isBlank() || requestId.isBlank()) return null
    return SlockAdaptiveDetailOpenAck(
        shellInstanceId = shellInstanceId,
        requestId = requestId,
        accepted = optBoolean(SlockAdaptiveDetailOpenAck.KEY_ACCEPTED, false),
        generation = optDouble(SlockAdaptiveDetailOpenAck.KEY_GENERATION, 0.0).toLong(),
        reason = optString(SlockAdaptiveDetailOpenAck.KEY_REASON).trim(),
    )
}

private val adaptiveDetailOpenJson = Json { ignoreUnknownKeys = true }

/** Decodes the carried launch payload; returns null when it is not a routable thread payload. */
internal fun SlockAdaptiveDetailOpenRequest.decodeLaunchPayload(): WireThreadLaunchPayload? =
    runCatching {
        adaptiveDetailOpenJson
            .decodeFromString(WireThreadLaunchPayload.serializer(), launchPayloadJson)
            .takeIf { it.hasRouteIdentity() }
    }.getOrNull()

internal fun WireThreadLaunchPayload.encodeForAdaptiveDetailOpen(): String =
    adaptiveDetailOpenJson.encodeToString(WireThreadLaunchPayload.serializer(), this)

/**
 * Shell-side evaluation of one [SlockAdaptiveDetailOpenRequest] against the current session.
 *
 * Dedupe order matters: a replayed requestId is answered with the same pending entry (idempotent),
 * never re-reduced. Freshness and authority checks run only on first-seen requestIds. Returns the
 * session to persist plus the decision — callers write the session first so a crash between accept
 * and ack does not lose the pending fence.
 */
internal fun evaluateSlockAdaptiveDetailOpen(
    request: SlockAdaptiveDetailOpenRequest,
    workspaceEnabled: Boolean,
    session: build.raft.app.data.SharedAdaptiveWorkspaceSession,
    nativeGuardedCommitAvailable: Boolean = false,
): Pair<build.raft.app.data.SharedAdaptiveWorkspaceSession, SlockAdaptiveDetailOpenDecision> {
    // Kill-switch (task #18): closing the source page is still a bare router pop with no native
    // route/controller token verification. Until the guarded commit action lands, every adoption
    // request is rejected so the in-flight protocol can merge without letting runtime trigger it.
    if (!nativeGuardedCommitAvailable) {
        return session to
            SlockAdaptiveDetailOpenDecision.Rejected(
                SlockAdaptiveDetailOpenRejectReason.NATIVE_COMMIT_UNAVAILABLE.wireName
            )
    }
    val workspace = session.workspace
        ?: return session to
            SlockAdaptiveDetailOpenDecision.Rejected(
                SlockAdaptiveDetailOpenRejectReason.NOT_DUAL_PANE.wireName
            )

    // Idempotent retry: same requestId already pending → re-report its generation, no re-reduce.
    session.pendingDetailOpens[request.requestId]?.let { pending ->
        return session to SlockAdaptiveDetailOpenDecision.Accepted(pending.detailGeneration)
    }

    if (!workspaceEnabled) {
        return session to
            SlockAdaptiveDetailOpenDecision.Rejected(
                SlockAdaptiveDetailOpenRejectReason.NOT_DUAL_PANE.wireName
            )
    }

    // Freshness: a request captured before the last fact the shell applied is stale — the window
    // may have narrowed after the source posted. Comparisons across different hosts use wall-clock
    // capture; same-host sequences require only monotonicity.
    if (
        !adaptiveWindowFactReplayAccepted(
            lastHostId = session.lastWindowFactHostId,
            lastCapturedAtMillis = session.lastWindowFactCapturedAtMillis,
            requestHostId = request.hostId,
            requestCapturedAtMillis = request.windowFact.capturedAtMillis,
        )
    ) {
        return session to
            SlockAdaptiveDetailOpenDecision.Rejected(
                SlockAdaptiveDetailOpenRejectReason.STALE_WINDOW_FACT.wireName
            )
    }

    // Authority: the request must target the workspace's current server. Server switch while the
    // request was in flight invalidates it; the shell does not switch authority on request data.
    if (request.identity.serverId != workspace.serverId) {
        return session to
            SlockAdaptiveDetailOpenDecision.Rejected(
                SlockAdaptiveDetailOpenRejectReason.AUTHORITY_MISMATCH.wireName
            )
    }

    val launchPayload = request.decodeLaunchPayload()
        ?: return session to
            SlockAdaptiveDetailOpenDecision.Rejected(
                SlockAdaptiveDetailOpenRejectReason.PAYLOAD_INVALID.wireName
            )

    // Replay the carried fact (the covered shell missed it), then evaluate OpenDetail. The reducer
    // is authoritative: if after replay the layout is still not DualPane, reject honestly.
    // Reduce the carried fact through the same pane reducer the host emitter uses, so the
    // replayed layout is computed rather than defaulted.
    val paneEvent = request.windowFact.toSlockAdaptivePaneEvent()
    val replayedPane = reduceSlockAdaptivePane(workspace.pane, paneEvent)
    val replayed =
        reduceSlockAdaptiveWorkspace(
            workspace,
            SlockAdaptiveWorkspaceIntent.WindowChanged(replayedPane),
        )
    val opened =
        reduceSlockAdaptiveWorkspace(
            replayed,
            SlockAdaptiveWorkspaceIntent.OpenDetail(request.identity),
        )
    if (opened.selection?.identity != request.identity) {
        return session to
            SlockAdaptiveDetailOpenDecision.Rejected(
                SlockAdaptiveDetailOpenRejectReason.NOT_DUAL_PANE.wireName
            )
    }

    val generation = opened.selection.generation
    val next =
        session.copy(
            workspace = opened,
            launchPayload = launchPayload,
            pendingDetailOpens =
                session.pendingDetailOpens +
                    (request.requestId to
                        build.raft.app.data.PendingAdaptiveDetailOpen(
                            requestId = request.requestId,
                            hostId = request.hostId,
                            identity = request.identity,
                            detailGeneration = generation,
                            windowFactCapturedAtMillis = request.windowFact.capturedAtMillis,
                        )),
            lastWindowFactHostId = request.hostId,
            lastWindowFactCapturedAtMillis = request.windowFact.capturedAtMillis,
        )
    return next to SlockAdaptiveDetailOpenDecision.Accepted(generation)
}

/**
 * Resolves a pending request once the destination pane child reports its readiness (mount +
 * draft/viewport restoration complete). Returns the updated session and the pending entry, or nulls
 * when the report does not match (late/foreign generation) — callers then send no ack.
 */
internal fun completeSlockAdaptiveDetailOpen(
    identityKey: String,
    generation: Long,
    session: build.raft.app.data.SharedAdaptiveWorkspaceSession,
): Pair<build.raft.app.data.SharedAdaptiveWorkspaceSession, build.raft.app.data.PendingAdaptiveDetailOpen?> {
    // The destination reports only the detail envelope it knows (identity + generation); the
    // pending entry is located by generation so the requestId never has to reach the child page.
    val match =
        session.pendingDetailOpens.values.firstOrNull { it.detailGeneration == generation }
            ?: return session to null
    val matches =
        session.workspace?.selection?.identity?.key == identityKey &&
            session.workspace.selection?.generation == generation
    return if (matches) {
        session.copy(pendingDetailOpens = session.pendingDetailOpens - match.requestId) to match
    } else {
        session to null
    }
}

/** Drops a pending request without an ack path (cancel/timeout/authority change). */
internal fun dropSlockAdaptiveDetailOpen(
    requestId: String,
    session: build.raft.app.data.SharedAdaptiveWorkspaceSession,
): build.raft.app.data.SharedAdaptiveWorkspaceSession =
    if (requestId in session.pendingDetailOpens) {
        session.copy(pendingDetailOpens = session.pendingDetailOpens - requestId)
    } else {
        session
    }
