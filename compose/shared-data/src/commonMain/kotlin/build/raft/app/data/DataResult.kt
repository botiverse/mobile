package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import build.raft.app.network.RaftFailedRequestTraceIds

sealed interface DataResult<out T> {
    data class Content<T>(val data: T) : DataResult<T>
    data class Empty(val reason: EmptyReason) : DataResult<Nothing>
    data class Error(val error: ScreenError) : DataResult<Nothing>
    data class NoPermission(val blocker: PermissionBlocker) : DataResult<Nothing>
}

enum class EmptyReason {
    NoChannels,
    NoMessages,
    NoTasks,
    NoMembers,
    NoInboxItems,
    NoSettingsAvailable,
    NoSearchResults
}

data class ScreenError(
    val code: String,
    val retryable: Boolean = true,
    /**
     * task #193 (honest-states contract): the server's raw human-readable
     * error text, carried so displays can always fall back to showing the
     * real reason instead of a bare code. Humane localized special-cases
     * remain overlays on top of this fallback, never a replacement for it.
     */
    val serverMessage: String? = null,
    /**
     * Structured recovery identifiers which a domain operation discovered while rejecting a
     * malformed response.  These are deliberately kept out of [serverMessage]: callers can use
     * them for compensating cleanup without leaking opaque/session identifiers into user-facing
     * copy or ordinary diagnostics.  The field is currently used by direct attachment-session
     * create, where a response may be malformed after the server has already reserved a session.
     */
    val recoverySessionIds: Set<String> = emptySet(),
    /**
     * Structured cleanup evidence retained when a multi-step operation fails after creating
     * server-side reservations.  This is diagnostic/domain data, never user-facing copy: the
     * primary [code] remains the original upload/projection failure even when compensation is
     * incomplete or uncertain.
     */
    val cleanupEvidence: String? = null,
    /**
     * The server's machine-readable error code, when one was present in the response envelope.
     * Keeping it separate from [code] lets presentation retain the stable local key while direct
     * upload retry policy can classify an explicit transient/terminal code without guessing from
     * HTTP status or human text.
     */
    val serverCode: String? = null,
    /**
     * Runtime form v2: the fields a 4xx response's issues point at (`/formValues/<field>`),
     * so the form can mark them. Empty for every other error.
     */
    val formFieldKeys: List<String> = emptyList(),
    /**
     * A 429's wait in seconds: the body's own field where the route sends one, else the
     * `Retry-After` header (delta-seconds). Null when the response named no wait.
     */
    val retryAfterSeconds: Long? = null,
    /**
     * A refusal's other machine-readable string fields a caller needs to tell refusals apart
     * (an install card's 409 `current_text_revision_id`). Never user-facing copy.
     */
    val serverFields: Map<String, String> = emptyMap(),
    /**
     * A 403 `second_factor_step_up_required` (SecondFactorStepUpApi.kt): the refused action
     * needs a fresh second step; [SecondFactorStepUpCoordinator] asks for it and retries.
     */
    val secondFactorStepUp: SecondFactorStepUpChallenge? = null,
    /**
     * A `messages/context` 404 with `code: "history_limited"` (MessageContextHistoryLimitedApi.kt):
     * the message it points at, behind the plan's history window, as the server shows it. Null for
     * every other error.
     */
    val historyLimitedTarget: MessageContextHiddenTarget? = null,
    /**
     * The failed request's server trace id (`X-Trace-Id`), shown with a copy action under the
     * error so a report can be found on the server (AGENTS.md "Errors carry the trace ID").
     * Defaults to the failure the HTTP layer saw just now ([RaftFailedRequestTraceIds], as the
     * web's error toasts do), so every repository's error carries it without threading it.
     * Null for a request that got no response.
     */
    val traceId: String? = RaftFailedRequestTraceIds.recent(),
)

data class PermissionBlocker(
    val code: String,
    val requiredCapability: String? = null,
    /**
     * The route-specific adapter's decoded human-readable server reason.
     *
     * Classification as [DataResult.NoPermission] must not discard this wire
     * fact. Presentation still decides whether localized copy or this raw
     * reason wins (401 always uses localized session copy; a non-blank 403
     * reason wins over the generic localized permission sentence).
     */
    val serverMessage: String? = null,
    /**
     * The refusing body's own `code` / `errorCode`, when it has one (null: an uncoded refusal), so a
     * caller can tell a coded 403 (device sessions, email verification...) from a plain one.
     */
    val serverCode: String? = null,
    /** Structured cleanup facts retained when a multi-step operation cannot retract all work. */
    val cleanupEvidence: String? = null,
    /** As [ScreenError.traceId]: the refusing response's server trace id. */
    val traceId: String? = RaftFailedRequestTraceIds.recent(),
)

/** Stable, locale-independent keys emitted by the data/domain layer. */
enum class ErrorPresentationKey {
    SessionExpired,
    ServerUnreachable,
    RequestTimedOut,
    NetworkConnectionFailed,
    ServerUnavailable,
    ItemNotFound,
    PermissionDenied,
    EdgeBlocked,
    AttachmentTooLarge,
    AttachmentMonthlyQuotaExceeded,
    ChannelReadOnly,
    ChannelArchived,
    TooManyAttempts,
    ResponseUnreadable,
    ServerVersionIncompatible,
    Generic,
    EmptyNoChannels,
    EmptyNoMessages,
    EmptyNoTasks,
    EmptyNoMembers,
    EmptyNoInboxItems,
    EmptyNoSettingsAvailable,
    EmptyNoSearchResults
}

enum class RawServerMessagePriority {
    LocalizedCopyFirst,
    ServerMessageFirst
}

/**
 * Locale-free presentation facts. UI resolves [key] through MR resources.
 * [rawServerMessage] is deliberately retained verbatim and never translated.
 */
data class ErrorPresentation(
    val key: ErrorPresentationKey,
    val rawServerMessage: String? = null,
    val rawServerMessagePriority: RawServerMessagePriority = RawServerMessagePriority.LocalizedCopyFirst,
    /** The failed request's server trace id; the UI shows it with a copy action when present. */
    val traceId: String? = null,
)

fun PermissionBlocker.isAuthSessionBlocker(): Boolean =
    code == "auth_required" || requiredCapability == "auth_session"

fun ScreenError.isAuthSessionError(): Boolean =
    code == "auth_required" || code == "auth_session" || code == "unauthenticated"

fun String?.isAuthSessionErrorCode(): Boolean =
    this == "auth_required" || this == "auth_session" || this == "unauthenticated"

/**
 * Server HTTP 403 refusal codes that represent plan, quota, capacity, or channel read-only
 * limits rather than missing role/channel access permissions. Classifying these as
 * [DataResult.Error] (non-retryable) preserves their contract code end-to-end and prevents
 * callers from treating them as permission denial or channel access loss.
 */
@RaftCoreInternalApi fun isNonPermissionPlanOrQuota403Code(serverCode: String?): Boolean =
    when (serverCode?.trim()?.lowercase()) {
        "file_upload_quota_exceeded",
        "upload_quota_exceeded",
        "external_limit_frozen",
        "channel_read_only",
        "quota_read_only",
        "attachment_upload_disabled",
        "migration_pro_plan_required",
        "free_external_limit",
        "joint_channel_free_limit_reached",
        "joint_free_server_limit",
        "agent_limit_reached",
        "human_seat_limit_reached" -> true
        else -> false
    }

/**
 * Machine-readable marker the network executor substitutes for edge/CDN
 * intercepts (CloudFront-style 403s whose body is HTML rather than the API
 * envelope). Localized network copy must always win over this opaque marker.
 */
@RaftCoreInternalApi fun String?.isEdgeBlockedMarker(): Boolean =
    this?.trim()?.lowercase() == "edge_blocked"

@RaftCoreInternalApi fun ScreenError.errorPresentation(): ErrorPresentation {
    val normalizedCode = code.trim().lowercase()
    val normalizedServerCode = serverCode?.trim()?.lowercase()
    if (
        serverMessage.isEdgeBlockedMarker() ||
        normalizedServerCode == "edge_blocked" ||
        normalizedCode == "edge_blocked"
    ) {
        // The marker is an internal wire fact, not human-readable copy; the
        // localized network-blocked sentence always wins.
        return ErrorPresentation(
            key = ErrorPresentationKey.EdgeBlocked,
            rawServerMessage = null,
            rawServerMessagePriority = RawServerMessagePriority.LocalizedCopyFirst,
            traceId = traceId,
        )
    }
    val serverCodeKey = serverCode.serverCodePresentationKeyOrNull()
    val codeExactKey = code.serverCodePresentationKeyOrNull()
    val codeKey =
        if (!normalizedServerCode.isNullOrEmpty() && normalizedCode == normalizedServerCode) {
            serverCodeKey
        } else {
            codeExactKey ?: code.errorPresentationKeyOrNull()
        }
    // Tier 1a (401 / auth session): localized session-expired copy always wins (matches
    // PermissionBlocker and ServerRefusalResolver ordering).
    if (
        serverCodeKey == ErrorPresentationKey.SessionExpired ||
        codeKey == ErrorPresentationKey.SessionExpired ||
        normalizedCode.isAuthSessionErrorCode() ||
        normalizedServerCode.isAuthSessionErrorCode()
    ) {
        return ErrorPresentation(
            key = ErrorPresentationKey.SessionExpired,
            rawServerMessage = null,
            rawServerMessagePriority = RawServerMessagePriority.LocalizedCopyFirst,
            traceId = traceId,
        )
    }
    // Tier 1b (5xx / server_unavailable / internal_error): never surface raw server text.
    val isServerUnavailable =
        serverCodeKey == ErrorPresentationKey.ServerUnavailable ||
        codeKey == ErrorPresentationKey.ServerUnavailable ||
        normalizedCode.isHttp5xxPresentationCode()
    if (isServerUnavailable || normalizedServerCode == "internal_error" || normalizedCode == "internal_error") {
        return ErrorPresentation(
            key = if (isServerUnavailable) ErrorPresentationKey.ServerUnavailable else ErrorPresentationKey.Generic,
            rawServerMessage = null,
            rawServerMessagePriority = RawServerMessagePriority.LocalizedCopyFirst,
            traceId = traceId,
        )
    }
    // Tier 1c (network/dns/timeout/parse/version): localized copy always wins.
    val infraKey = listOfNotNull(codeKey, serverCodeKey).firstOrNull { it.isInfrastructureLocalizedOnly() }
    if (infraKey != null) {
        return ErrorPresentation(
            key = infraKey,
            rawServerMessage = null,
            rawServerMessagePriority = RawServerMessagePriority.LocalizedCopyFirst,
            traceId = traceId,
        )
    }
    // Tier 2 (specific localized domain codes): exact serverCode takes precedence over generic
    // HTTP status codes in `code` (e.g. code="http_403", serverCode="file_upload_quota_exceeded").
    val specificDomainKey = listOfNotNull(serverCodeKey, codeKey).firstOrNull { it.isSpecificLocalizedDomainCode() }
    if (specificDomainKey != null) {
        return ErrorPresentation(
            key = specificDomainKey,
            rawServerMessage = usableServerRefusalMessage(serverMessage),
            rawServerMessagePriority = RawServerMessagePriority.LocalizedCopyFirst,
            traceId = traceId,
        )
    }
    // Tier 3 / 4 (generic 4xx categories like ItemNotFound / PermissionDenied, or unknown codes):
    // match PermissionBlocker.errorPresentation() — show a usable server refusal sentence verbatim
    // when present, otherwise fall back to localized category / generic copy.
    return ErrorPresentation(
        key = serverCodeKey ?: codeKey ?: ErrorPresentationKey.Generic,
        rawServerMessage = usableServerRefusalMessage(serverMessage),
        rawServerMessagePriority = RawServerMessagePriority.ServerMessageFirst,
        traceId = traceId,
    )
}

@RaftCoreInternalApi fun PermissionBlocker.errorPresentation(): ErrorPresentation {
    val normalizedCode = code.trim().lowercase()
    val normalizedServerCode = serverCode?.trim()?.lowercase()
    if (
        serverMessage.isEdgeBlockedMarker() ||
        normalizedServerCode == "edge_blocked" ||
        normalizedCode == "edge_blocked"
    ) {
        return ErrorPresentation(
            key = ErrorPresentationKey.EdgeBlocked,
            rawServerMessage = null,
            rawServerMessagePriority = RawServerMessagePriority.LocalizedCopyFirst,
            traceId = traceId,
        )
    }
    if (
        isAuthSessionBlocker() ||
        normalizedCode.isHttpStatusPresentationCode(401) ||
        normalizedCode.isAuthSessionErrorCode() ||
        normalizedServerCode.isAuthSessionErrorCode()
    ) {
        return ErrorPresentation(
            key = ErrorPresentationKey.SessionExpired,
            rawServerMessage = null,
            rawServerMessagePriority = RawServerMessagePriority.LocalizedCopyFirst,
            traceId = traceId,
        )
    }
    val serverCodeKey = serverCode.serverCodePresentationKeyOrNull()
    val codeExactKey = code.serverCodePresentationKeyOrNull()
    val isServerUnavailable =
        serverCodeKey == ErrorPresentationKey.ServerUnavailable ||
        codeExactKey == ErrorPresentationKey.ServerUnavailable ||
        normalizedCode.isHttp5xxPresentationCode()
    if (isServerUnavailable || normalizedServerCode == "internal_error" || normalizedCode == "internal_error") {
        return ErrorPresentation(
            key = if (isServerUnavailable) ErrorPresentationKey.ServerUnavailable else ErrorPresentationKey.Generic,
            rawServerMessage = null,
            rawServerMessagePriority = RawServerMessagePriority.LocalizedCopyFirst,
            traceId = traceId,
        )
    }
    // Only exact wire-code matches override PermissionDenied (never substring heuristics such as
    // `"rate"` or `"mismatch"` that would misclassify `federated_*` or `server_mismatch`).
    val specificDomainKey = listOfNotNull(serverCodeKey, codeExactKey).firstOrNull { it.isSpecificLocalizedDomainCode() }
    if (specificDomainKey != null) {
        return ErrorPresentation(
            key = specificDomainKey,
            rawServerMessage = usableServerRefusalMessage(serverMessage),
            rawServerMessagePriority = RawServerMessagePriority.LocalizedCopyFirst,
            traceId = traceId,
        )
    }
    return ErrorPresentation(
        key = ErrorPresentationKey.PermissionDenied,
        rawServerMessage = usableServerRefusalMessage(serverMessage),
        // Route-specific typed adapters only populate serverMessage from
        // the endpoint's confirmed human-readable field. A usable non-blank
        // value is therefore the deterministic "specific reason" signal;
        // do not guess actionability from English keywords.
        rawServerMessagePriority = RawServerMessagePriority.ServerMessageFirst,
        traceId = traceId,
    )
}

private fun ErrorPresentationKey.isInfrastructureLocalizedOnly(): Boolean =
    when (this) {
        ErrorPresentationKey.EdgeBlocked,
        ErrorPresentationKey.SessionExpired,
        ErrorPresentationKey.ServerUnreachable,
        ErrorPresentationKey.RequestTimedOut,
        ErrorPresentationKey.NetworkConnectionFailed,
        ErrorPresentationKey.ServerUnavailable,
        ErrorPresentationKey.ResponseUnreadable,
        ErrorPresentationKey.ServerVersionIncompatible -> true
        else -> false
    }

private fun ErrorPresentationKey.isSpecificLocalizedDomainCode(): Boolean =
    when (this) {
        ErrorPresentationKey.AttachmentTooLarge,
        ErrorPresentationKey.AttachmentMonthlyQuotaExceeded,
        ErrorPresentationKey.ChannelReadOnly,
        ErrorPresentationKey.ChannelArchived,
        ErrorPresentationKey.TooManyAttempts -> true
        else -> false
    }

/**
 * Exact-match mapping for wire `serverCode` values carried in API JSON error envelopes.
 * Deliberately avoids substring heuristics (such as `"host"`, `"auth"`, `"rate"`, or `"mismatch"`)
 * that belong only to client-synthesized local [ScreenError.code] identifiers and would
 * misclassify real server refusal codes like `joint_channel_host_limit`, `oauth_not_connected`,
 * `federated_channel_...`, or `channel_conversion_fence_mismatch`.
 */
@RaftCoreInternalApi fun String?.serverCodePresentationKeyOrNull(): ErrorPresentationKey? =
    when (this?.trim()?.lowercase()) {
        "edge_blocked" -> ErrorPresentationKey.EdgeBlocked
        "auth_required",
        "auth_session",
        "unauthenticated" -> ErrorPresentationKey.SessionExpired
        "server_unavailable",
        "service_unavailable" -> ErrorPresentationKey.ServerUnavailable
        "attachment_too_large",
        "upload_too_large" -> ErrorPresentationKey.AttachmentTooLarge
        "file_upload_quota_exceeded",
        "upload_quota_exceeded" -> ErrorPresentationKey.AttachmentMonthlyQuotaExceeded
        "external_limit_frozen",
        "channel_read_only",
        "quota_read_only" -> ErrorPresentationKey.ChannelReadOnly
        "channel_archived" -> ErrorPresentationKey.ChannelArchived
        "rate_limited",
        "too_many_requests",
        "too_many_attempts" -> ErrorPresentationKey.TooManyAttempts
        else -> null
    }

@RaftCoreInternalApi fun EmptyReason.errorPresentation(): ErrorPresentation =
    ErrorPresentation(
        key =
            when (this) {
                EmptyReason.NoChannels -> ErrorPresentationKey.EmptyNoChannels
                EmptyReason.NoMessages -> ErrorPresentationKey.EmptyNoMessages
                EmptyReason.NoTasks -> ErrorPresentationKey.EmptyNoTasks
                EmptyReason.NoMembers -> ErrorPresentationKey.EmptyNoMembers
                EmptyReason.NoInboxItems -> ErrorPresentationKey.EmptyNoInboxItems
                EmptyReason.NoSettingsAvailable -> ErrorPresentationKey.EmptyNoSettingsAvailable
                EmptyReason.NoSearchResults -> ErrorPresentationKey.EmptyNoSearchResults
            }
    )

@RaftCoreInternalApi fun String?.errorPresentation(): ErrorPresentation =
    ErrorPresentation(key = errorPresentationKeyOrNull() ?: ErrorPresentationKey.Generic)

@RaftCoreInternalApi fun DataResult<*>.errorPresentationOrNull(): ErrorPresentation? =
    when (this) {
        is DataResult.Content -> null
        is DataResult.Empty -> reason.errorPresentation()
        is DataResult.Error -> error.errorPresentation()
        is DataResult.NoPermission -> blocker.errorPresentation()
    }

@RaftCoreInternalApi fun String?.errorPresentationKeyOrNull(): ErrorPresentationKey? {
    val normalized = this?.trim().orEmpty()
    if (normalized.isBlank()) return null
    if (!normalized.looksLikeErrorCode()) return null
    val lower = normalized.lowercase()
    return when {
        "edge_blocked" in lower -> ErrorPresentationKey.EdgeBlocked
        lower.isAuthSessionErrorCode() || lower.isHttpStatusPresentationCode(401) ||
            "auth" in lower || "unauthenticated" in lower -> ErrorPresentationKey.SessionExpired
        "dns" in lower || "host" in lower || "unreachable" in lower ->
            ErrorPresentationKey.ServerUnreachable
        "timeout" in lower || "timed_out" in lower ->
            ErrorPresentationKey.RequestTimedOut
        "network" in lower || "socket" in lower || lower == "code_0" || lower == "http_0" || lower == "0" ||
            lower.endsWith("_http_0") ->
            ErrorPresentationKey.NetworkConnectionFailed
        "server_unavailable" in lower || "service_unavailable" in lower || lower.isHttp5xxPresentationCode() ->
            ErrorPresentationKey.ServerUnavailable
        "not_found" in lower || lower.isHttpStatusPresentationCode(404) ->
            ErrorPresentationKey.ItemNotFound
        lower.isHttpStatusPresentationCode(403) || "permission" in lower || "forbidden" in lower ||
            "admin_required" in lower || "no_permission" in lower -> ErrorPresentationKey.PermissionDenied
        lower == "attachment_too_large" || lower == "upload_too_large" ->
            ErrorPresentationKey.AttachmentTooLarge
        lower == "file_upload_quota_exceeded" || lower == "upload_quota_exceeded" ->
            ErrorPresentationKey.AttachmentMonthlyQuotaExceeded
        lower == "external_limit_frozen" || lower == "channel_read_only" || lower == "quota_read_only" ->
            ErrorPresentationKey.ChannelReadOnly
        lower == "channel_archived" ->
            ErrorPresentationKey.ChannelArchived
        "rate" in lower || "too_many" in lower ->
            ErrorPresentationKey.TooManyAttempts
        "contract" in lower || "parse" in lower || "mismatch" in lower ->
            ErrorPresentationKey.ResponseUnreadable
        "outdated" in lower || "incompatible" in lower ->
            ErrorPresentationKey.ServerVersionIncompatible
        else -> null
    }
}

private fun String.isHttpStatusPresentationCode(status: Int): Boolean =
    this == status.toString() || this == "http_$status" || endsWith("_$status")

private fun String.isHttp5xxPresentationCode(): Boolean =
    matches(Regex("(^|.*_)5\\d\\d$"))

private fun String.looksLikeErrorCode(): Boolean =
    matches(Regex("[a-z0-9_]+")) ||
        matches(Regex("[A-Z0-9_]+")) ||
        matches(Regex("[a-zA-Z]+_[a-zA-Z0-9_]+")) ||
        matches(Regex("http_\\d+")) ||
        matches(Regex("\\d+"))

fun authSessionRequiredResult(): DataResult.NoPermission =
    authSessionRequiredResult(serverMessage = null)

fun authSessionRequiredResult(serverMessage: String?): DataResult.NoPermission =
    DataResult.NoPermission(
        PermissionBlocker(
            code = "auth_required",
            requiredCapability = "auth_session",
            serverMessage = serverMessage
        )
    )

inline fun <T, R> DataResult<T>.map(transform: (T) -> R): DataResult<R> =
    when (this) {
        is DataResult.Content -> DataResult.Content(transform(data))
        is DataResult.Empty -> this
        is DataResult.Error -> this
        is DataResult.NoPermission -> this
    }

fun <T> T.asDataResult(): DataResult<T> = DataResult.Content(this)
