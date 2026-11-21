package build.raft.app

import build.raft.app.data.LinkOpenMode
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

private const val SETTINGS_HOST_OPEN_EXTERNAL_NAME = "host=open-external"
private const val SETTINGS_HOST_OPEN_EXTERNAL_PREFIX = "host=open-external&to="

/** Minimum time before an identical `#host=open-external&to=…` tap is handled again after clearing. */
internal const val SETTINGS_HOST_OPEN_EXTERNAL_REPEAT_MS = 500L

/**
 * Filters a WebView `onNavigationBlocked` URL (e.g. a `target="_blank"` link or a cross-origin
 * navigation stopped by `mainFrameOrigin`) before handing it to [RaftLinkNavigator].
 *
 * Non-navigable popup placeholders such as `about:blank` (used by MCP OAuth `window.open`),
 * `javascript:`, `blob:`, `data:`, `file:`, and malformed values return null so the host ignores
 * them quietly without launching a browser or showing a rejection toast.
 */
internal fun settingsWebViewBlockedNavigationUrlOrNull(rawUrl: String): String? {
    val trimmed = rawUrl.trim()
    if (trimmed.isEmpty()) return null
    val decision = resolveRaftLinkOpenDecision(trimmed, LinkOpenMode.SystemBrowser)
    if (decision.target == RaftLinkOpenTarget.Rejected) return null
    return decision.url
}

/** Safe, bounded scheme token for structured logs when an ignored blocked navigation arrives. */
internal fun settingsWebViewSchemeForLog(rawUrl: String): String =
    rawUrl.trim()
        .substringBefore(':', missingDelimiterValue = "")
        .lowercase()
        .filter { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }
        .take(24)
        .ifEmpty { "none" }

/**
 * Whether [pageUrl] is on [webOrigin] and carries a `#host=open-external` request fragment
 * (`botiverse/slock#16053`: `#host=open-external&to=<encodeURIComponent(http(s) url)>`).
 *
 * Requests on any other origin return false so third-party documents in unlocked Settings WebViews
 * can never trigger host link actions.
 */
internal fun settingsWebViewAsksHostOpenExternal(
    pageUrl: String,
    webOrigin: String,
): Boolean {
    val expectedOrigin = raftWebOriginOf(webOrigin) ?: return false
    if (raftWebOriginOf(pageUrl) != expectedOrigin) return false
    val fragment = pageUrl.substringAfter('#', "")
    return fragment == SETTINGS_HOST_OPEN_EXTERNAL_NAME ||
        fragment.startsWith("$SETTINGS_HOST_OPEN_EXTERNAL_NAME&")
}

/**
 * Extracts and validates the external http(s) URL from a `#host=open-external&to=…` fragment on
 * [webOrigin], or returns null when the request is absent, off-origin, malformed, or not an
 * `http`/`https` URL.
 */
internal fun settingsWebViewHostOpenExternalUrl(
    pageUrl: String,
    webOrigin: String,
): String? {
    if (!settingsWebViewAsksHostOpenExternal(pageUrl, webOrigin)) return null
    val fragment = pageUrl.substringAfter('#', "")
    if (!fragment.startsWith(SETTINGS_HOST_OPEN_EXTERNAL_PREFIX)) return null
    val encoded = fragment.removePrefix(SETTINGS_HOST_OPEN_EXTERNAL_PREFIX)
    // Single-pass `encodeURIComponent(url)` leaves no `&`, `/`, `?`, `#`, `+`, whitespace, or control chars.
    if (
        encoded.isEmpty() ||
        encoded.any { it == '&' || it == '/' || it == '?' || it == '#' || it == '+' || it.isWhitespace() || it.code < 0x20 || it.code == 0x7f }
    ) {
        return null
    }
    val decoded = strictPercentDecodeUtf8OrNull(encoded) ?: return null
    if (decoded.isEmpty() || decoded.any { it.isWhitespace() || it.code < 0x20 || it.code == 0x7f }) {
        return null
    }
    val scheme = decoded.substringBefore(':', missingDelimiterValue = "").lowercase()
    if (scheme != "http" && scheme != "https") return null
    val decision = resolveRaftLinkOpenDecision(decoded, LinkOpenMode.SystemBrowser)
    if (decision.target != RaftLinkOpenTarget.ExternalBrowser) return null
    return decision.url
}

/**
 * Strict RFC 3986 / `decodeURIComponent` UTF-8 decoder: does NOT treat `+` as space, requires two
 * ASCII hex digits after `%`, and rejects invalid UTF-8 byte sequences instead of emitting U+FFFD.
 */
internal fun strictPercentDecodeUtf8OrNull(encoded: String): String? {
    val bytes = ByteArray(encoded.length)
    var outIndex = 0
    var i = 0
    while (i < encoded.length) {
        val ch = encoded[i]
        if (ch == '%') {
            if (i + 2 >= encoded.length) return null
            val hi = hexDigitOrMinusOne(encoded[i + 1])
            val lo = hexDigitOrMinusOne(encoded[i + 2])
            if (hi < 0 || lo < 0) return null
            bytes[outIndex++] = ((hi shl 4) or lo).toByte()
            i += 3
        } else {
            val encodedChar = ch.toString().encodeToByteArray()
            if (outIndex + encodedChar.size > bytes.size) {
                val expanded = ByteArray(outIndex + encodedChar.size + (encoded.length - i) * 3)
                bytes.copyInto(expanded, endIndex = outIndex)
                return strictPercentDecodeUtf8SlowOrNull(encoded, i, expanded, outIndex)
            }
            encodedChar.copyInto(bytes, destinationOffset = outIndex)
            outIndex += encodedChar.size
            i += 1
        }
    }
    return try {
        bytes.decodeToString(startIndex = 0, endIndex = outIndex, throwOnInvalidSequence = true)
    } catch (_: CharacterCodingException) {
        null
    }
}

private fun strictPercentDecodeUtf8SlowOrNull(
    encoded: String,
    startIndex: Int,
    initialBuffer: ByteArray,
    initialLength: Int,
): String? {
    val buffer = ArrayList<Byte>(initialLength + (encoded.length - startIndex) * 3)
    for (j in 0 until initialLength) buffer.add(initialBuffer[j])
    var i = startIndex
    while (i < encoded.length) {
        val ch = encoded[i]
        if (ch == '%') {
            if (i + 2 >= encoded.length) return null
            val hi = hexDigitOrMinusOne(encoded[i + 1])
            val lo = hexDigitOrMinusOne(encoded[i + 2])
            if (hi < 0 || lo < 0) return null
            buffer.add(((hi shl 4) or lo).toByte())
            i += 3
        } else {
            for (b in ch.toString().encodeToByteArray()) buffer.add(b)
            i += 1
        }
    }
    return try {
        buffer.toByteArray().decodeToString(throwOnInvalidSequence = true)
    } catch (_: CharacterCodingException) {
        null
    }
}

private fun hexDigitOrMinusOne(ch: Char): Int =
    when (ch) {
        in '0'..'9' -> ch - '0'
        in 'a'..'f' -> ch - 'a' + 10
        in 'A'..'F' -> ch - 'A' + 10
        else -> -1
    }

/**
 * Strips a handled `#host=open-external…` request off the page's document URL via
 * `history.replaceState` (restoring [previousHash] when non-empty and not another `#host=` command).
 * Guarded to top-level documents on [webOrigin]; fails closed to empty string without a valid origin.
 */
internal fun settingsWebViewHostOpenExternalTakenScript(
    webOrigin: String,
    previousHash: String = "",
): String {
    val origin = raftWebOriginOf(webOrigin) ?: return ""
    val encodedOrigin = Json.encodeToString(String.serializer(), origin)
    val safeHash =
        previousHash
            .removePrefix("#")
            .takeUnless { it.startsWith("host=") }
            .orEmpty()
    val encodedHash = Json.encodeToString(String.serializer(), safeHash)
    return """
        (function() {
          try {
            if (window.top !== window || window.location.origin !== $encodedOrigin) return;
            if (/^#host=open-external(?:&|$)/.test(window.location.hash)) {
              var url = new URL(window.location.href);
              url.hash = $encodedHash;
              window.history.replaceState(window.history.state, '', url.href);
            }
          } catch (_) {}
        })();
    """.trimIndent()
}

internal data class SettingsWebViewHostOpenExternalDecision(
    /** Validated `http`/`https` destination to open, or null if the request should be dropped and logged. */
    val targetUrl: String?,
    /** Non-`#host=` fragment the document had before the request, restored when clearing the hash. */
    val previousHash: String,
    /** Raw page URL carrying the request (used as key/identity for deferred cleanup). */
    val requestPageUrl: String,
)

/**
 * Deduplicates `#host=open-external&to=…` observations across `onUrlChange` and
 * `onNavigationStateChange` (including iOS/Desktop URL polls) so a single tap opens once while
 * still allowing deliberate repeat taps once the page actually returns to a non-request URL.
 */
internal class SettingsWebViewHostOpenExternalDeduper(
    private val repeatWindowMs: Long = SETTINGS_HOST_OPEN_EXTERNAL_REPEAT_MS,
) {
    private var lastHandledUrl: String? = null
    private var lastHandledAtMs: Long = Long.MIN_VALUE
    private var clearedSinceHandled: Boolean = true
    private var hashBeforeRequest: String = ""

    /** True while the last handled `#host=open-external` URL has not yet been observed cleared. */
    val awaitingHashClear: Boolean
        get() = !clearedSinceHandled && lastHandledUrl != null

    /** The non-`#host=` fragment captured before the active request (for retrying cleanup if needed). */
    val activePreviousHash: String
        get() = hashBeforeRequest

    fun observe(
        pageUrl: String,
        webOrigin: String,
        nowMs: Long,
    ): SettingsWebViewHostOpenExternalDecision? {
        if (!settingsWebViewAsksHostOpenExternal(pageUrl, webOrigin)) {
            clearedSinceHandled = true
            val expectedOrigin = raftWebOriginOf(webOrigin)
            if (expectedOrigin != null && raftWebOriginOf(pageUrl) == expectedOrigin) {
                val fragment = pageUrl.substringAfter('#', "")
                if (!fragment.startsWith("host=")) {
                    hashBeforeRequest = fragment
                }
            }
            return null
        }
        val sameAsLast = pageUrl == lastHandledUrl
        val withinRepeatWindow = lastHandledAtMs != Long.MIN_VALUE && (nowMs - lastHandledAtMs) < repeatWindowMs
        if (sameAsLast && (!clearedSinceHandled || withinRepeatWindow)) {
            return null
        }
        lastHandledUrl = pageUrl
        lastHandledAtMs = nowMs
        clearedSinceHandled = false
        return SettingsWebViewHostOpenExternalDecision(
            targetUrl = settingsWebViewHostOpenExternalUrl(pageUrl, webOrigin),
            previousHash = hashBeforeRequest,
            requestPageUrl = pageUrl,
        )
    }
}
