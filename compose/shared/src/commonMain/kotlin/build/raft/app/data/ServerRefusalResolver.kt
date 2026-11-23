package build.raft.app.data

/**
 * Single refusal-to-copy resolution exit for server refusals (`#proj-mobile:#319`).
 *
 * Applies the same four-tier precedence to both whole-request HTTP refusals (4xx/5xx) and HTTP 200
 * per-item refusals (such as `POST /channels/:id/external-connect-servers` and
 * `POST /channels/:id/connect-my-servers`):
 *
 * 1. **Infrastructure / Auth / Network**: edge/CDN markers (`edge_blocked`), 401 auth errors, or
 *    transport/parse failures always resolve to app-owned localized copy and never surface raw
 *    proxy HTML or opaque markers.
 * 2. **Known localized code**: codes that have an explicit localized sentence (matching web locale
 *    entries) or require structured UI actions (Upgrade / Step-Up) resolve to that typed value.
 * 3. **Server-worded refusal**: unknown or dynamic-parameter refusal codes (e.g.
 *    `channel_conversion_too_large`, whose wording varies between fenced and online conversion
 *    modes — never hardcoded client-side) surface the server's human-readable `error` verbatim
 *    alongside the request `traceId`, provided it is not a 5xx / `internal_error` or opaque markup.
 * 4. **Generic fallback**: localized fallback copy + `traceId` only when the server supplied no
 *    usable refusal sentence (or returned 5xx / `internal_error`).
 */
internal data class ServerRefusalInput(
    val status: Int? = null,
    val code: String? = null,
    val serverMessage: String? = null,
    val traceId: String? = null,
)

/** Outcome of [resolveServerRefusal], preserving the exact tier that won and the trace ID. */
internal sealed interface ResolvedServerRefusal<out K> {
    val traceId: String?

    /** Tier 1: edge/CDN intercept, 401 auth expiry, or transport failure — never show raw body. */
    data class Infrastructure(val code: String?, val status: Int?, override val traceId: String?) : ResolvedServerRefusal<Nothing>

    /** Tier 2: known refusal code mapped to localized app copy (web locale parity). */
    data class KnownCode<out K>(val known: K, override val traceId: String?) : ResolvedServerRefusal<K>

    /** Tier 3: unknown or dynamic refusal code — display the server's own sentence verbatim. */
    data class ServerWords(val message: String, override val traceId: String?) : ResolvedServerRefusal<Nothing>

    /** Tier 4: server gave no usable explanation (or 5xx internal error) — fall back to generic copy. */
    data class Generic(val status: Int?, override val traceId: String?) : ResolvedServerRefusal<Nothing>
}

/**
 * Resolves a server refusal through the single four-tier exit.
 *
 * Tier 1 intercepts edge/CDN markers (`edge_blocked`), 401 auth errors, and status-0 transport
 * failures before [knownCode] runs, so [knownCode] may safely map both explicit known refusal codes
 * and surface-specific HTTP status fallbacks (such as 403/404/429 where web maps by status) without
 * swallowing infrastructure failures.
 */
internal fun <K : Any> resolveServerRefusal(
    input: ServerRefusalInput,
    knownCode: (normalizedCode: String?, status: Int?) -> K?,
): ResolvedServerRefusal<K> {
    val normalizedCode = input.code?.trim()?.takeIf { it.isNotEmpty() }?.lowercase()
    val status = input.status

    // Tier 1: infrastructure / auth / transport — never surface proxy HTML or internal markers.
    // Note: HTTP 200 per-item outcomes may omit `status` (wire default 0) while still supplying a
    // structured refusal `code` or `serverMessage`; only treat status == 0 as transport failure
    // when no refusal payload is present.
    val isTransportZero = status == 0 && normalizedCode == null && input.serverMessage.isNullOrBlank()
    if (
        input.serverMessage.isEdgeBlockedMarker() ||
        normalizedCode == "edge_blocked" ||
        status == 401 ||
        normalizedCode.isAuthSessionErrorCode() ||
        isTransportZero
    ) {
        return ResolvedServerRefusal.Infrastructure(normalizedCode, status, input.traceId)
    }

    // Tier 2: explicit known refusal code -> localized app sentence.
    knownCode(normalizedCode, status)?.let { mapped ->
        return ResolvedServerRefusal.KnownCode(mapped, input.traceId)
    }

    // Tier 3: non-5xx refusal (4xx or 200 per-item refused with non-5xx status) carrying a usable
    // human-readable server sentence -> display verbatim (never hardcoding dynamic limits).
    val isServerFault = (status != null && status >= 500) || normalizedCode == "internal_error"
    if (!isServerFault) {
        usableServerRefusalMessage(input.serverMessage)?.let { sentence ->
            return ResolvedServerRefusal.ServerWords(sentence, input.traceId)
        }
    }

    // Tier 4: no usable server sentence or 5xx internal error -> generic localized fallback.
    return ResolvedServerRefusal.Generic(status, input.traceId)
}

/**
 * Extracts a human-readable server refusal sentence, rejecting blank strings, opaque markers
 * (`edge_blocked`), HTML error pages, and raw stack traces.
 */
internal fun usableServerRefusalMessage(raw: String?): String? {
    val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (trimmed.isEdgeBlockedMarker()) return null
    val lower = trimmed.lowercase()
    if (lower.startsWith("<!doctype") || lower.startsWith("<html") || lower.startsWith("<head") || lower.startsWith("<body")) {
        return null
    }
    if (trimmed.contains("\n    at ") || trimmed.contains("\n\tat ")) {
        return null
    }
    return trimmed
}
