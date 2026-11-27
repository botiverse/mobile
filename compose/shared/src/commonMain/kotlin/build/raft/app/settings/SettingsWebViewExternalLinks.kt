package build.raft.app.settings

import build.raft.app.RaftAppEntryRequest
import build.raft.app.RaftInstalledAppOriginMatch
import build.raft.app.shell.RaftLinkOpenTarget
import build.raft.app.data.LinkOpenMode
import build.raft.app.get
import build.raft.app.origin
import build.raft.app.raftAppEntryOriginOf
import build.raft.app.raftWebOriginOf
import build.raft.app.shell.resolveRaftLinkOpenDecision
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

private const val SETTINGS_HOST_OPEN_EXTERNAL_NAME = "host=open-external"
private const val SETTINGS_HOST_OPEN_EXTERNAL_PREFIX = "host=open-external&to="
private const val SETTINGS_HOST_OPEN_APP_NAME = "host=open-app"
private const val SETTINGS_HOST_OPEN_APP_PREFIX = "host=open-app&to="

/** Whether an embedded Settings page asked with `#host=open-external` or `#host=open-app` (`slock` #16090). */
internal enum class SettingsWebViewHostOpenKind {
    External,
    App,
}

/** Minimum time before an identical `#host=open-external&to=…` or `#host=open-app&to=…` tap is handled again after clearing. */
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
 * Which `#host=open-*` command [pageUrl] carries on [webOrigin], or null when absent or off-origin:
 * - [SettingsWebViewHostOpenKind.External]: `#host=open-external` (`botiverse/slock#16053`)
 * - [SettingsWebViewHostOpenKind.App]: `#host=open-app` (`botiverse/slock#16090`, App homepage)
 *
 * Requests on any other origin return null so third-party documents in unlocked Settings WebViews
 * can never trigger host link actions.
 */
internal fun settingsWebViewHostOpenKind(
    pageUrl: String,
    webOrigin: String,
): SettingsWebViewHostOpenKind? {
    val expectedOrigin = raftWebOriginOf(webOrigin) ?: return null
    if (raftWebOriginOf(pageUrl) != expectedOrigin) return null
    val fragment = pageUrl.substringAfter('#', "")
    return when {
        fragment == SETTINGS_HOST_OPEN_EXTERNAL_NAME ||
            fragment.startsWith("$SETTINGS_HOST_OPEN_EXTERNAL_NAME&") -> SettingsWebViewHostOpenKind.External
        fragment == SETTINGS_HOST_OPEN_APP_NAME ||
            fragment.startsWith("$SETTINGS_HOST_OPEN_APP_NAME&") -> SettingsWebViewHostOpenKind.App
        else -> null
    }
}

/**
 * Whether [pageUrl] is on [webOrigin] and carries a `#host=open-external` request fragment
 * (`botiverse/slock#16053`: `#host=open-external&to=<encodeURIComponent(http(s) url)>`).
 */
internal fun settingsWebViewAsksHostOpenExternal(
    pageUrl: String,
    webOrigin: String,
): Boolean = settingsWebViewHostOpenKind(pageUrl, webOrigin) == SettingsWebViewHostOpenKind.External

/**
 * Whether [pageUrl] is on [webOrigin] and carries a `#host=open-app` request fragment
 * (`botiverse/slock#16090`: `#host=open-app&to=<encodeURIComponent(https url)>`).
 */
internal fun settingsWebViewAsksHostOpenApp(
    pageUrl: String,
    webOrigin: String,
): Boolean = settingsWebViewHostOpenKind(pageUrl, webOrigin) == SettingsWebViewHostOpenKind.App

/** Whether [pageUrl] on [webOrigin] carries either `#host=open-external` or `#host=open-app`. */
internal fun settingsWebViewAsksHostOpen(
    pageUrl: String,
    webOrigin: String,
): Boolean = settingsWebViewHostOpenKind(pageUrl, webOrigin) != null

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
    return parseSettingsHostOpenToUrl(
        fragment = pageUrl.substringAfter('#', ""),
        prefix = SETTINGS_HOST_OPEN_EXTERNAL_PREFIX,
        allowedSchemes = setOf("http", "https"),
    )
}

/**
 * Extracts and validates the `https` URL from a `#host=open-app&to=…` fragment on [webOrigin]
 * (`botiverse/slock#16090`, no other parameters), or returns null when absent, off-origin,
 * malformed, or not an `https` URL.
 */
internal fun settingsWebViewHostOpenAppUrl(
    pageUrl: String,
    webOrigin: String,
): String? {
    if (!settingsWebViewAsksHostOpenApp(pageUrl, webOrigin)) return null
    return parseSettingsHostOpenToUrl(
        fragment = pageUrl.substringAfter('#', ""),
        prefix = SETTINGS_HOST_OPEN_APP_PREFIX,
        allowedSchemes = setOf("https"),
    )
}

private fun parseSettingsHostOpenToUrl(
    fragment: String,
    prefix: String,
    allowedSchemes: Set<String>,
): String? {
    if (!fragment.startsWith(prefix)) return null
    val encoded = fragment.removePrefix(prefix)
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
    if (scheme !in allowedSchemes) return null
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
 * Strips a handled `#host=open-external…` or `#host=open-app…` request off the page's document URL
 * via `history.replaceState` (restoring [previousHash] when non-empty and not another `#host=`
 * command). Guarded to top-level documents on [webOrigin]; fails closed to empty string without a
 * valid origin.
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
            if (/^#host=open-(?:external|app)(?:&|$)/.test(window.location.hash)) {
              var url = new URL(window.location.href);
              url.hash = $encodedHash;
              window.history.replaceState(window.history.state, '', url.href);
            }
          } catch (_) {}
        })();
    """.trimIndent()
}

internal data class SettingsWebViewHostOpenExternalDecision(
    /** Validated destination to open, or null if the request should be dropped and logged. */
    val targetUrl: String?,
    /** Non-`#host=` fragment the document had before the request, restored when clearing the hash. */
    val previousHash: String,
    /** Raw page URL carrying the request (used as key/identity for deferred cleanup). */
    val requestPageUrl: String,
    /** Whether the page asked with `#host=open-external` or `#host=open-app` (#proj-workspace:#356). */
    val kind: SettingsWebViewHostOpenKind = SettingsWebViewHostOpenKind.External,
)

/**
 * Deduplicates `#host=open-external&to=…` and `#host=open-app&to=…` observations across
 * `onUrlChange` and `onNavigationStateChange` (including iOS/Desktop URL polls) so a single tap
 * opens once while still allowing deliberate repeat taps once the page actually returns to a
 * non-request URL.
 */
internal class SettingsWebViewHostOpenExternalDeduper(
    private val repeatWindowMs: Long = SETTINGS_HOST_OPEN_EXTERNAL_REPEAT_MS,
) {
    private var lastHandledUrl: String? = null
    private var lastHandledAtMs: Long = Long.MIN_VALUE
    private var clearedSinceHandled: Boolean = true
    private var hashBeforeRequest: String = ""

    /** True while the last handled `#host=open-*` URL has not yet been observed cleared. */
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
        val kind = settingsWebViewHostOpenKind(pageUrl, webOrigin)
        if (kind == null) {
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
        val targetUrl =
            when (kind) {
                SettingsWebViewHostOpenKind.External -> settingsWebViewHostOpenExternalUrl(pageUrl, webOrigin)
                SettingsWebViewHostOpenKind.App -> settingsWebViewHostOpenAppUrl(pageUrl, webOrigin)
            }
        return SettingsWebViewHostOpenExternalDecision(
            targetUrl = targetUrl,
            previousHash = hashBeforeRequest,
            requestPageUrl = pageUrl,
            kind = kind,
        )
    }
}

/**
 * Dispatches a validated `#host=open-external` or `#host=open-app` decision from a Settings WebView
 * (#proj-workspace:#356 Decision #9).
 *
 * Only surfaces with [opensAppEntries] = true (Marketplace) promote `#host=open-app` to
 * [openAppEntry], and only after [verifyInstalledAppOrigin] confirms the destination origin matches
 * the `homepageUrl` origin of an App installed on [currentServerId]. Any mismatch, missing server,
 * failed verification, or non-Marketplace surface falls back to ordinary [openExternal] (no
 * `Raft-Host` header).
 */
internal suspend fun dispatchSettingsWebViewHostOpen(
    decision: SettingsWebViewHostOpenExternalDecision,
    opensAppEntries: Boolean,
    currentServerId: String?,
    verifyInstalledAppOrigin: (suspend (serverId: String, targetOrigin: String) -> RaftInstalledAppOriginMatch?)?,
    openAppEntry: ((String, RaftAppEntryRequest) -> Unit)?,
    openExternal: (String) -> Unit,
): String {
    val target = decision.targetUrl ?: return "dropped"
    if (decision.kind == SettingsWebViewHostOpenKind.App && opensAppEntries && openAppEntry != null) {
        val targetOrigin = raftAppEntryOriginOf(target)
        val serverId = currentServerId?.trim()?.takeIf { it.isNotEmpty() }
        if (targetOrigin != null && serverId != null && verifyInstalledAppOrigin != null) {
            val matched =
                try {
                    verifyInstalledAppOrigin(serverId, targetOrigin)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    null
                }
            if (matched != null && matched.origin == targetOrigin) {
                openAppEntry(
                    target,
                    RaftAppEntryRequest(
                        scopeOrigin = matched.origin,
                        appName = matched.appName,
                        preferredServerId = serverId,
                        appLogoUrl = matched.logoUrl,
                    ),
                )
                return "entry"
            }
        }
        openExternal(target)
        return "fallback_external"
    }
    openExternal(target)
    return "open"
}
