package build.raft.app.data

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
    /** Structured cleanup facts retained when a multi-step operation cannot retract all work. */
    val cleanupEvidence: String? = null,
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
    val rawServerMessagePriority: RawServerMessagePriority = RawServerMessagePriority.LocalizedCopyFirst
)

fun PermissionBlocker.isAuthSessionBlocker(): Boolean =
    code == "auth_required" || requiredCapability == "auth_session"

fun ScreenError.isAuthSessionError(): Boolean =
    code == "auth_required" || code == "auth_session" || code == "unauthenticated"

fun String?.isAuthSessionErrorCode(): Boolean =
    this == "auth_required" || this == "auth_session" || this == "unauthenticated"

/**
 * Machine-readable marker the network executor substitutes for edge/CDN
 * intercepts (CloudFront-style 403s whose body is HTML rather than the API
 * envelope). Localized network copy must always win over this opaque marker.
 */
internal fun String?.isEdgeBlockedMarker(): Boolean =
    this?.trim()?.lowercase() == "edge_blocked"

internal fun ScreenError.errorPresentation(): ErrorPresentation {
    if (serverMessage.isEdgeBlockedMarker()) {
        // The marker is an internal wire fact, not human-readable copy; the
        // localized network-blocked sentence always wins.
        return ErrorPresentation(
            key = ErrorPresentationKey.EdgeBlocked,
            rawServerMessage = serverMessage,
            rawServerMessagePriority = RawServerMessagePriority.LocalizedCopyFirst
        )
    }
    val key = code.errorPresentationKeyOrNull()
    return ErrorPresentation(
        key = key ?: ErrorPresentationKey.Generic,
        rawServerMessage = serverMessage,
        rawServerMessagePriority =
            if (key == null) {
                RawServerMessagePriority.ServerMessageFirst
            } else {
                RawServerMessagePriority.LocalizedCopyFirst
            }
    )
}

internal fun PermissionBlocker.errorPresentation(): ErrorPresentation =
    if (serverMessage.isEdgeBlockedMarker()) {
        ErrorPresentation(
            key = ErrorPresentationKey.EdgeBlocked,
            rawServerMessage = serverMessage,
            rawServerMessagePriority = RawServerMessagePriority.LocalizedCopyFirst
        )
    } else if (isAuthSessionBlocker() || code.isHttpStatusPresentationCode(401)) {
        ErrorPresentation(
            key = ErrorPresentationKey.SessionExpired,
            rawServerMessage = serverMessage,
            rawServerMessagePriority = RawServerMessagePriority.LocalizedCopyFirst
        )
    } else {
        ErrorPresentation(
            key = ErrorPresentationKey.PermissionDenied,
            rawServerMessage = serverMessage,
            // Route-specific typed adapters only populate serverMessage from
            // the endpoint's confirmed human-readable field. A non-blank
            // value is therefore the deterministic "specific reason" signal;
            // do not guess actionability from English keywords.
            rawServerMessagePriority = RawServerMessagePriority.ServerMessageFirst
        )
    }

internal fun EmptyReason.errorPresentation(): ErrorPresentation =
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

internal fun String?.errorPresentation(): ErrorPresentation =
    ErrorPresentation(key = errorPresentationKeyOrNull() ?: ErrorPresentationKey.Generic)

internal fun DataResult<*>.errorPresentationOrNull(): ErrorPresentation? =
    when (this) {
        is DataResult.Content -> null
        is DataResult.Empty -> reason.errorPresentation()
        is DataResult.Error -> error.errorPresentation()
        is DataResult.NoPermission -> blocker.errorPresentation()
    }

internal fun String?.errorPresentationKeyOrNull(): ErrorPresentationKey? {
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
        "network" in lower || "socket" in lower || lower == "code_0" || lower == "0" ->
            ErrorPresentationKey.NetworkConnectionFailed
        "server_unavailable" in lower || "http_5" in lower || lower.endsWith("_503") ->
            ErrorPresentationKey.ServerUnavailable
        "not_found" in lower || "http_404" in lower ->
            ErrorPresentationKey.ItemNotFound
        lower.isHttpStatusPresentationCode(403) || "permission" in lower || "forbidden" in lower ||
            "admin_required" in lower || "no_permission" in lower -> ErrorPresentationKey.PermissionDenied
        lower == "attachment_too_large" || lower == "upload_too_large" ->
            ErrorPresentationKey.AttachmentTooLarge
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

private fun String.looksLikeErrorCode(): Boolean =
    matches(Regex("[a-z0-9_]+")) ||
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
