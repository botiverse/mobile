package build.raft.webcontainer

import build.raft.webcontainer.jsb.JsbPolicy
import kotlin.time.TimeSource

private val ProcessMonotonicOrigin = TimeSource.Monotonic.markNow()

/** Monotonic milliseconds elapsed since process clock initialization (platform-independent). */
internal fun webContainerMonotonicMillis(): Long = ProcessMonotonicOrigin.elapsedNow().inWholeMilliseconds

/**
 * Storage and session isolation boundary for a [WebContainerView].
 *
 * - [Default]: platform default persistent store.
 * - [Ephemeral]: non-persistent memory-only store (when supported by the platform engine).
 * - [Named]: dedicated persistent partition identified by [id] (Android `MULTI_PROFILE`,
 *   iOS 17+ `WKWebsiteDataStore(forIdentifier:)`, or desktop partition).
 */
sealed interface WebIsolationScope {
    data object Default : WebIsolationScope

    data object Ephemeral : WebIsolationScope

    data class Named(val id: String) : WebIsolationScope {
        init {
            require(isValidScopeId(id)) {
                "Invalid WebIsolationScope.Named id '$id': must match [a-zA-Z0-9._-]{1,64} and not be reserved."
            }
        }
    }

    companion object {
        private val AllowedPattern = Regex("^[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}$")
        private val ReservedIds = setOf("default", "ephemeral")

        fun isValidScopeId(raw: String): Boolean {
            if (!AllowedPattern.matches(raw)) return false
            if (raw.contains("..")) return false
            return raw.lowercase() !in ReservedIds
        }
    }
}

/**
 * Normalized origin (`scheme://host[:non-default-port]`) used for navigation locks, header
 * scoping, document-start script allowlists, and JSB origin verification.
 */
data class WebContainerOrigin(
    val scheme: String,
    val host: String,
    val port: Int? = null,
) {
    init {
        require(scheme.isNotBlank() && scheme == scheme.lowercase()) {
            "Origin scheme must be non-blank lowercase: '$scheme'"
        }
        require(host.isNotBlank() && host == host.lowercase()) {
            "Origin host must be non-blank lowercase: '$host'"
        }
        require(port == null || port in 1..65535) {
            "Origin port out of range: $port"
        }
    }

    /** Effective port, resolving 80 for `http` and 443 for `https` when [port] is null. */
    val effectivePort: Int?
        get() = port ?: defaultPortForScheme(scheme)

    /** Canonical origin string suitable for Android `allowedOriginRules` and JS `location.origin`. */
    fun asOriginString(): String {
        val defaultPort = defaultPortForScheme(scheme)
        val formattedHost = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
        return if (port == null || port == defaultPort) {
            "$scheme://$formattedHost"
        } else {
            "$scheme://$formattedHost:$port"
        }
    }

    override fun toString(): String = asOriginString()

    companion object {
        fun defaultPortForScheme(scheme: String): Int? =
            when (scheme.lowercase()) {
                "http", "ws" -> 80
                "https", "wss" -> 443
                else -> null
            }
    }
}

/**
 * Strictly parses a URL string into a normalized [WebContainerOrigin], or returns `null` when
 * the URL is opaque, malformed, contains control characters, or embeds userinfo credentials.
 */
fun parseWebContainerOrigin(rawUrl: String): WebContainerOrigin? {
    val trimmed = rawUrl.trim()
    if (trimmed.isEmpty()) return null
    if (trimmed.any { it.code < 0x20 || it.isWhitespace() }) return null
    val schemeSep = trimmed.indexOf("://")
    if (schemeSep <= 0) return null
    val scheme = trimmed.substring(0, schemeSep).lowercase()
    if (!scheme[0].isLetter()) return null
    for (i in 1 until scheme.length) {
        val ch = scheme[i]
        if (!ch.isLetterOrDigit() && ch != '+' && ch != '-' && ch != '.') return null
    }
    val afterScheme = trimmed.substring(schemeSep + 3)
    val authorityEnd =
        afterScheme.indexOfAny(charArrayOf('/', '?', '#')).let { if (it < 0) afterScheme.length else it }
    val authority = afterScheme.substring(0, authorityEnd)
    if (authority.isEmpty()) return null
    // Reject embedded credentials (user:pass@host) to prevent origin spoofing.
    if (authority.contains('@') || authority.contains('\\')) return null

    val host: String
    val port: Int?
    if (authority.startsWith("[")) {
        val closeBracket = authority.indexOf(']')
        if (closeBracket <= 1) return null
        host = authority.substring(1, closeBracket).lowercase()
        val remainder = authority.substring(closeBracket + 1)
        if (remainder.isEmpty()) {
            port = null
        } else if (remainder.startsWith(":")) {
            val portStr = remainder.substring(1)
            val parsedPort = portStr.toIntOrNull() ?: return null
            if (parsedPort !in 1..65535) return null
            port = parsedPort
        } else {
            return null
        }
    } else {
        val colonIdx = authority.indexOf(':')
        if (colonIdx < 0) {
            host = authority.lowercase()
            port = null
        } else {
            if (authority.indexOf(':', colonIdx + 1) >= 0) return null
            host = authority.substring(0, colonIdx).lowercase()
            val portStr = authority.substring(colonIdx + 1)
            val parsedPort = portStr.toIntOrNull() ?: return null
            if (parsedPort !in 1..65535) return null
            port = parsedPort
        }
    }
    if (host.isEmpty()) return null
    val defaultPort = WebContainerOrigin.defaultPortForScheme(scheme)
    val normalizedPort = if (port == defaultPort) null else port
    return WebContainerOrigin(scheme = scheme, host = host, port = normalizedPort)
}

/**
 * Origin-scoped HTTP header attached to main-frame GET requests on [allowedOrigin] and stripped
 * automatically when redirects leave [allowedOrigin].
 */
data class WebRequestHeaderRule(
    val headerName: String,
    val headerValue: String,
    val allowedOrigin: WebContainerOrigin,
    val maxSameOriginReissues: Int = 5,
) {
    init {
        require(headerName.isNotBlank() && headerName.none { it.isWhitespace() || it == ':' }) {
            "Invalid HTTP header name: '$headerName'"
        }
        require(headerValue.none { it == '\r' || it == '\n' }) {
            "HTTP header value must not contain CR/LF."
        }
        require(maxSameOriginReissues >= 0) {
            "maxSameOriginReissues must be >= 0"
        }
    }
}

/** Navigation, origin lock, subframe host allowlist, and restore-URL sanitization policy. */
data class WebNavigationPolicy(
    val allowNavigation: Boolean = false,
    val allowedSchemes: Set<String> = setOf("https"),
    val lockedMainFrameOrigin: WebContainerOrigin? = null,
    val allowedSubframeHosts: Set<String> = emptySet(),
    val allowNewWindows: Boolean = false,
    val requestHeaderRule: WebRequestHeaderRule? = null,
    val restoreStripQueryKeys: Set<String> = DefaultRestoreStripQueryKeys,
    val restoreStripFragments: Boolean = true,
    val restoreBootstrapPathMarkers: Set<String> = emptySet(),
) {
    val normalizedAllowedSchemes: Set<String> =
        allowedSchemes.mapTo(LinkedHashSet()) { it.trim().lowercase() }.filterTo(LinkedHashSet()) { it.isNotEmpty() }

    val normalizedSubframeHosts: Set<String> =
        allowedSubframeHosts.mapTo(LinkedHashSet()) { it.trim().lowercase() }.filterTo(LinkedHashSet()) { it.isNotEmpty() }

    companion object {
        val DefaultRestoreStripQueryKeys: Set<String> =
            setOf("token", "access_token", "code", "state", "ticket", "auth", "nonce")
    }
}

/** Script injected at document start on matching origins (main frame only by default). */
data class WebDocumentStartScript(
    val id: String,
    val source: String,
    val allowedOrigins: Set<WebContainerOrigin> = emptySet(),
    val mainFrameOnly: Boolean = true,
) {
    init {
        require(id.isNotBlank()) { "WebDocumentStartScript.id must not be blank." }
    }
}

/**
 * Offline bundle metadata exposed to the web document via `window.__RAFT_OFFLINE_BUNDLE__` and
 * optional custom scheme routing on iOS.
 */
data class WebOfflineBundleDescriptor(
    val bundleId: String,
    val pageSessionId: String,
    val protocolVersion: Int = 1,
    val iosCustomScheme: String? = null,
    val metadata: Map<String, String> = emptyMap(),
) {
    init {
        require(bundleId.isNotBlank()) { "bundleId must not be blank." }
        require(pageSessionId.isNotBlank()) { "pageSessionId must not be blank." }
        require(protocolVersion >= 1) { "protocolVersion must be >= 1." }
    }
}

/**
 * How Android `WindowInsets` reach the underlying Chromium `WebView` inside [WebContainerView].
 *
 * Follows Chromium WebView's inset contract (`android_webview/docs/insets.md`): consumed inset
 * types are zeroed with `Insets.NONE` (never short-circuited via `CONSUMED`) so dynamic
 * transitions clear Chromium's cached insets.
 */
enum class WebContainerAndroidInsetMode {
    /**
     * Host handles all insets (`ime()`, `systemBars()`, `displayCutout()`).
     * The WebView listener zeroes all three before forwarding to `WebView.onApplyWindowInsets`
     * so disabling `ComposeView.consumeWindowInsets` never causes double-padding on legacy
     * WebViews or surfaces that apply host padding.
     */
    HostHandled,

    /**
     * Chromium M139-M143: WebView natively handles `ime()` (`window.visualViewport` resize
     * without resizing the Android `WebView` View), while the host applies static bottom
     * navigation-bar padding and top status-bar chrome. The WebView listener zeroes
     * `systemBars()` and `displayCutout()` and passes `ime()` unconsumed to WebView.
     */
    NativeImeHostNavBar,

    /**
     * Chromium M144+: WebView natively handles both `ime()` (`window.visualViewport`) and
     * bottom `systemBars()` / `displayCutout()` (`env(safe-area-inset-*)`), while the host owns
     * the top status bar / header chrome. The WebView listener zeroes top insets (`statusBars()`,
     * `captionBar()`, top of `navigationBars()`/`displayCutout()`) and passes `ime()` and
     * bottom/horizontal navigation-bar/cutout insets unconsumed to WebView.
     */
    NativeImeAndBottomSystemBars,
}

/**
 * Resolves the effective native WebView background ARGB.
 *
 * When [persistAfterPaint] is `false` (default, suitable for external/unthemed web pages),
 * [backgroundArgb] acts only as a pre-first-paint placeholder and reverts to opaque white
 * (`0xFFFFFFFF`) once [painted] is `true`. When [persistAfterPaint] is `true` and
 * [backgroundArgb] is non-zero, [backgroundArgb] stays applied after paint and follows live
 * theme updates.
 */
fun resolveWebContainerBackgroundArgb(
    backgroundArgb: Int,
    painted: Boolean,
    persistAfterPaint: Boolean = false,
): Int =
    if (backgroundArgb != 0 && (!painted || persistAfterPaint)) {
        backgroundArgb
    } else {
        0xFFFFFFFF.toInt()
    }

/** Top-level configuration for [WebContainerView]. */
data class WebContainerConfig(
    val url: String,
    val surfaceId: String = "default",
    val isolationScope: WebIsolationScope = WebIsolationScope.Ephemeral,
    val allowScripts: Boolean = true,
    val domStorageEnabled: Boolean = false,
    val cacheEnabled: Boolean = true,
    val allowPinchZoom: Boolean = false,
    val desktopViewportMode: Boolean = false,
    val backgroundArgb: Int = 0,
    val persistBackgroundAfterPaint: Boolean = false,
    val androidInsetMode: WebContainerAndroidInsetMode = WebContainerAndroidInsetMode.HostHandled,
    val isDarkTheme: Boolean = false,
    val coverActive: Boolean = false,
    val allowsBackForwardNavigationGestures: Boolean = false,
    val showsFormAccessoryBar: Boolean = false,
    val allowJsDialogs: Boolean = false,
    val keepAliveKey: String? = null,
    val navigationPolicy: WebNavigationPolicy = WebNavigationPolicy(),
    val jsbPolicy: JsbPolicy = JsbPolicy.Disabled,
    val documentStartScripts: List<WebDocumentStartScript> = emptyList(),
    val offlineBundle: WebOfflineBundleDescriptor? = null,
    val maxRuntimeJavaScriptLength: Int = DEFAULT_MAX_RUNTIME_JS_LENGTH,
) {
    companion object {
        const val DEFAULT_MAX_RUNTIME_JS_LENGTH: Int = 64 * 1024
    }
}

/** Immutable snapshot of a WebView's live navigation state. */
data class WebNavigationState(
    val url: String = "",
    val title: String = "",
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val isLoading: Boolean = false,
    val progress: Int = 0,
    val loadGeneration: Int = 0,
)

/** Who initiated a main-frame navigation chain. */
enum class WebNavigationStarter {
    Launch,
    Page,
    Link,
    Form,
    BackForward,
    Reload,
    Other,
}

/** Context passed to host-side main-frame interception hooks. */
data class WebMainFrameInterceptContext(
    val url: String,
    val method: String = "GET",
    val isUserGesture: Boolean = false,
    val isRedirect: Boolean = false,
    val starter: WebNavigationStarter = WebNavigationStarter.Other,
    val priorHops: List<String> = emptyList(),
)

/** Context passed to host-side main-frame external routing hooks. */
data class WebMainFrameRouteContext(
    val url: String,
    val isRedirect: Boolean = false,
    val isUserGesture: Boolean = false,
    val committedDocumentUrl: String = "",
)

/** Result of an in-container download handoff. */
sealed interface WebDownloadResult {
    data class Bytes(
        val data: ByteArray,
        val fileName: String,
        val mimeType: String?,
    ) : WebDownloadResult {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Bytes) return false
            return fileName == other.fileName && mimeType == other.mimeType && data.contentEquals(other.data)
        }

        override fun hashCode(): Int {
            var result = data.contentHashCode()
            result = 31 * result + fileName.hashCode()
            result = 31 * result + (mimeType?.hashCode() ?: 0)
            return result
        }
    }

    data class Url(
        val url: String,
        val pageFileName: String? = null,
        val contentDisposition: String? = null,
        val mimeType: String? = null,
    ) : WebDownloadResult

    data class Failed(val reason: String) : WebDownloadResult
}

/**
 * Derives a deterministic RFC-4122 version-5-formatted UUID string from [scopeId] using pure
 * Kotlin SHA-256 (zero platform crypto dependency, identical output on Android, iOS, and Desktop).
 */
fun deriveDeterministicScopeUuid(scopeId: String): String {
    val input = "build.raft.webcontainer.scope:$scopeId".encodeToByteArray()
    val digest = sha256PureKotlin(input)
    val bytes = digest.copyOf(16)
    // Set version 5 (0101) and RFC 4122 variant (10xx).
    bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x50).toByte()
    bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()

    val hex = CharArray(36)
    val digits = "0123456789ABCDEF"
    var out = 0
    for (i in 0 until 16) {
        if (i == 4 || i == 6 || i == 8 || i == 10) {
            hex[out++] = '-'
        }
        val b = bytes[i].toInt() and 0xFF
        hex[out++] = digits[b ushr 4]
        hex[out++] = digits[b and 0x0F]
    }
    return hex.concatToString()
}

private val Sha256K =
    intArrayOf(
        0x428a2f98, 0x71374491, 0xb5c0fbcf.toInt(), 0xe9b5dba5.toInt(),
        0x3956c25b, 0x59f111f1, 0x923f82a4.toInt(), 0xab1c5ed5.toInt(),
        0xd807aa98.toInt(), 0x12835b01, 0x243185be, 0x550c7dc3,
        0x72be5d74, 0x80deb1fe.toInt(), 0x9bdc06a7.toInt(), 0xc19bf174.toInt(),
        0xe49b69c1.toInt(), 0xefbe4786.toInt(), 0x0fc19dc6, 0x240ca1cc,
        0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152.toInt(), 0xa831c66d.toInt(), 0xb00327c8.toInt(), 0xbf597fc7.toInt(),
        0xc6e00bf3.toInt(), 0xd5a79147.toInt(), 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
        0x650a7354, 0x766a0abb, 0x81c2c92e.toInt(), 0x92722c85.toInt(),
        0xa2bfe8a1.toInt(), 0xa81a664b.toInt(), 0xc24b8b70.toInt(), 0xc76c51a3.toInt(),
        0xd192e819.toInt(), 0xd6990624.toInt(), 0xf40e3585.toInt(), 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5,
        0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814.toInt(), 0x8cc70208.toInt(),
        0x90befffa.toInt(), 0xa4506ceb.toInt(), 0xbef9a3f7.toInt(), 0xc67178f2.toInt(),
    )

internal fun sha256PureKotlin(message: ByteArray): ByteArray {
    val bitLength = message.size.toLong() * 8L
    val paddedLen = (((message.size + 9) + 63) / 64) * 64
    val padded = ByteArray(paddedLen)
    message.copyInto(padded)
    padded[message.size] = 0x80.toByte()
    for (i in 0 until 8) {
        padded[paddedLen - 1 - i] = ((bitLength ushr (i * 8)) and 0xFFL).toByte()
    }

    var h0 = 0x6a09e667
    var h1 = 0xbb67ae85.toInt()
    var h2 = 0x3c6ef372
    var h3 = 0xa54ff53a.toInt()
    var h4 = 0x510e527f
    var h5 = 0x9b05688c.toInt()
    var h6 = 0x1f83d9ab
    var h7 = 0x5be0cd19

    val w = IntArray(64)
    var offset = 0
    while (offset < paddedLen) {
        for (i in 0 until 16) {
            val base = offset + i * 4
            w[i] =
                ((padded[base].toInt() and 0xFF) shl 24) or
                    ((padded[base + 1].toInt() and 0xFF) shl 16) or
                    ((padded[base + 2].toInt() and 0xFF) shl 8) or
                    (padded[base + 3].toInt() and 0xFF)
        }
        for (i in 16 until 64) {
            val s0 = w[i - 15].rotateRight(7) xor w[i - 15].rotateRight(18) xor (w[i - 15] ushr 3)
            val s1 = w[i - 2].rotateRight(17) xor w[i - 2].rotateRight(19) xor (w[i - 2] ushr 10)
            w[i] = w[i - 16] + s0 + w[i - 7] + s1
        }
        var a = h0
        var b = h1
        var c = h2
        var d = h3
        var e = h4
        var f = h5
        var g = h6
        var h = h7
        for (i in 0 until 64) {
            val s1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
            val ch = (e and f) xor (e.inv() and g)
            val temp1 = h + s1 + ch + Sha256K[i] + w[i]
            val s0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
            val maj = (a and b) xor (a and c) xor (b and c)
            val temp2 = s0 + maj
            h = g
            g = f
            f = e
            e = d + temp1
            d = c
            c = b
            b = a
            a = temp1 + temp2
        }
        h0 += a
        h1 += b
        h2 += c
        h3 += d
        h4 += e
        h5 += f
        h6 += g
        h7 += h
        offset += 64
    }

    val out = ByteArray(32)
    val words = intArrayOf(h0, h1, h2, h3, h4, h5, h6, h7)
    for (i in words.indices) {
        val v = words[i]
        out[i * 4] = ((v ushr 24) and 0xFF).toByte()
        out[i * 4 + 1] = ((v ushr 16) and 0xFF).toByte()
        out[i * 4 + 2] = ((v ushr 8) and 0xFF).toByte()
        out[i * 4 + 3] = (v and 0xFF).toByte()
    }
    return out
}
