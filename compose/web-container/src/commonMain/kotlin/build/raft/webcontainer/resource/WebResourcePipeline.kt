package build.raft.webcontainer.resource

import build.raft.webcontainer.pool.WebContainerTelemetryEvent
import build.raft.webcontainer.pool.WebContainerTelemetrySink
import build.raft.webcontainer.sanitizeUrlForTelemetry

/** Origin source of an intercepted local/cached resource response. */
enum class WebResourceSource {
    OFFLINE_BUNDLE,
    HOST_CACHE,
    CUSTOM_INTERCEPTOR,
}

/** Platform-agnostic representation of a WebView resource request. */
data class WebResourceRequest(
    val url: String,
    val method: String = "GET",
    val isMainFrame: Boolean = false,
    val headers: Map<String, String> = emptyMap(),
)

/** Local response returned by a [WebResourceInterceptor] to bypass the network. */
data class WebResourceResponseData(
    val mimeType: String,
    val encoding: String = "utf-8",
    val statusCode: Int = 200,
    val reasonPhrase: String = "OK",
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray,
    val source: WebResourceSource = WebResourceSource.CUSTOM_INTERCEPTOR,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WebResourceResponseData) return false
        return mimeType == other.mimeType &&
            encoding == other.encoding &&
            statusCode == other.statusCode &&
            reasonPhrase == other.reasonPhrase &&
            headers == other.headers &&
            source == other.source &&
            body.contentEquals(other.body)
    }

    override fun hashCode(): Int {
        var result = mimeType.hashCode()
        result = 31 * result + encoding.hashCode()
        result = 31 * result + statusCode
        result = 31 * result + reasonPhrase.hashCode()
        result = 31 * result + headers.hashCode()
        result = 31 * result + source.hashCode()
        result = 31 * result + body.contentHashCode()
        return result
    }
}

/**
 * Pluggable resource interceptor.
 *
 * Returning `null` falls through cleanly to the next interceptor or the network.
 */
fun interface WebResourceInterceptor {
    fun intercept(request: WebResourceRequest): WebResourceResponseData?
}

/** Chains multiple [WebResourceInterceptor]s; first non-null wins, exceptions fail safe to network. */
class CompositeWebResourceInterceptor(
    private val interceptors: List<WebResourceInterceptor>,
    private val surfaceId: String = "default",
    private val telemetrySink: WebContainerTelemetrySink? = null,
) : WebResourceInterceptor {
    override fun intercept(request: WebResourceRequest): WebResourceResponseData? {
        for (interceptor in interceptors) {
            val response =
                try {
                    interceptor.intercept(request)
                } catch (_: Throwable) {
                    telemetrySink?.onEvent(
                        WebContainerTelemetryEvent.ResourceIntercepted(
                            surfaceId = surfaceId,
                            sanitizedUrl = sanitizeUrlForTelemetry(request.url),
                            hit = false,
                            source = null,
                            fallbackReason = "interceptor_exception",
                        ),
                    )
                    null
                }
            if (response != null) {
                telemetrySink?.onEvent(
                    WebContainerTelemetryEvent.ResourceIntercepted(
                        surfaceId = surfaceId,
                        sanitizedUrl = sanitizeUrlForTelemetry(request.url),
                        hit = true,
                        source = response.source,
                        fallbackReason = null,
                    ),
                )
                return response
            }
        }
        return null
    }
}

/** Storage-agnostic asset reader backed by a local verified offline bundle directory or archive. */
fun interface WebOfflineBundleAssetLoader {
    fun readAsset(normalizedRelativePath: String): ByteArray?
}

/**
 * Serves static assets from a local offline bundle with strict path-traversal protection and
 * automatic network fallback on missing or unreadable assets.
 *
 * Matches either:
 * - [httpsBaseUrlPrefix] (e.g., `https://app.raft.build/bundle/` on Android `shouldInterceptRequest`), or
 * - [iosCustomScheme] (e.g., `raft-bundle` -> `raft-bundle://<host>/<relativePath>` for iOS `WKURLSchemeHandler`).
 */
class WebOfflineBundleInterceptor(
    private val httpsBaseUrlPrefix: String? = null,
    private val iosCustomScheme: String? = null,
    private val defaultEntryPath: String = "index.html",
    private val assetLoader: WebOfflineBundleAssetLoader,
    private val mimeTypeResolver: (String) -> String = ::inferWebMimeType,
    private val surfaceId: String = "default",
    private val telemetrySink: WebContainerTelemetrySink? = null,
) : WebResourceInterceptor {
    init {
        require(httpsBaseUrlPrefix != null || iosCustomScheme != null) {
            "At least one of httpsBaseUrlPrefix or iosCustomScheme must be configured."
        }
        if (iosCustomScheme != null) {
            require(isValidIosCustomScheme(iosCustomScheme)) {
                "Invalid iOS custom scheme '$iosCustomScheme': http/https and built-in schemes are forbidden by WebKit."
            }
        }
    }

    private val normalizedHttpsPrefix: String? = httpsBaseUrlPrefix?.trim()?.trimEnd('/')
    private val normalizedIosScheme: String? = iosCustomScheme?.trim()?.lowercase()

    override fun intercept(request: WebResourceRequest): WebResourceResponseData? {
        if (!request.method.equals("GET", ignoreCase = true)) return null
        val rawRelativePath = extractRelativePath(request.url) ?: return null
        val safePath = sanitizeRelativeBundlePath(rawRelativePath, defaultEntryPath)
        if (safePath == null) {
            telemetrySink?.onEvent(
                WebContainerTelemetryEvent.ResourceIntercepted(
                    surfaceId = surfaceId,
                    sanitizedUrl = sanitizeUrlForTelemetry(request.url),
                    hit = false,
                    source = WebResourceSource.OFFLINE_BUNDLE,
                    fallbackReason = "path_traversal_rejected",
                ),
            )
            return null
        }
        val bytes =
            try {
                assetLoader.readAsset(safePath)
            } catch (_: Throwable) {
                telemetrySink?.onEvent(
                    WebContainerTelemetryEvent.ResourceIntercepted(
                        surfaceId = surfaceId,
                        sanitizedUrl = sanitizeUrlForTelemetry(request.url),
                        hit = false,
                        source = WebResourceSource.OFFLINE_BUNDLE,
                        fallbackReason = "asset_read_error",
                    ),
                )
                return null
            }
        if (bytes == null) {
            telemetrySink?.onEvent(
                WebContainerTelemetryEvent.ResourceIntercepted(
                    surfaceId = surfaceId,
                    sanitizedUrl = sanitizeUrlForTelemetry(request.url),
                    hit = false,
                    source = WebResourceSource.OFFLINE_BUNDLE,
                    fallbackReason = "asset_missing",
                ),
            )
            return null
        }
        val mime = mimeTypeResolver(safePath)
        val response =
            WebResourceResponseData(
                mimeType = mime,
                encoding = "utf-8",
                statusCode = 200,
                reasonPhrase = "OK",
                headers = mapOf("Content-Type" to mime, "Cache-Control" to "no-cache"),
                body = bytes,
                source = WebResourceSource.OFFLINE_BUNDLE,
            )
        telemetrySink?.onEvent(
            WebContainerTelemetryEvent.ResourceIntercepted(
                surfaceId = surfaceId,
                sanitizedUrl = sanitizeUrlForTelemetry(request.url),
                hit = true,
                source = WebResourceSource.OFFLINE_BUNDLE,
                fallbackReason = null,
            ),
        )
        return response
    }

    private fun extractRelativePath(rawUrl: String): String? {
        val noFragmentOrQuery = rawUrl.trim().substringBefore('#').substringBefore('?')
        val prefix = normalizedHttpsPrefix
        if (prefix != null) {
            if (noFragmentOrQuery.equals(prefix, ignoreCase = true)) {
                return ""
            }
            if (noFragmentOrQuery.startsWith("$prefix/", ignoreCase = true)) {
                return noFragmentOrQuery.substring(prefix.length + 1)
            }
        }
        val scheme = normalizedIosScheme
        if (scheme != null && noFragmentOrQuery.startsWith("$scheme://", ignoreCase = true)) {
            val afterScheme = noFragmentOrQuery.substring(scheme.length + 3)
            val slashIdx = afterScheme.indexOf('/')
            return if (slashIdx < 0) "" else afterScheme.substring(slashIdx + 1)
        }
        return null
    }
}

private val IosForbiddenBuiltInSchemes =
    setOf("http", "https", "about", "file", "data", "blob", "javascript", "ws", "wss", "ftp")

private val CustomSchemePattern = Regex("^[a-z][a-z0-9+.-]{1,31}$")

/**
 * Validates whether [scheme] can be registered with Apple `WKWebViewConfiguration.setURLSchemeHandler`.
 *
 * Apple WebKit throws `NSInvalidArgumentException` for built-in schemes (`http`, `https`, `file`,
 * `about`, `data`, `blob`, `javascript`, `ws`, `wss`), so callers MUST pass a custom scheme such as
 * `raft-bundle`.
 */
fun isValidIosCustomScheme(scheme: String): Boolean {
    val normalized = scheme.trim().lowercase()
    if (!CustomSchemePattern.matches(normalized)) return false
    return normalized !in IosForbiddenBuiltInSchemes
}

/**
 * Normalizes and validates a bundle-relative path, returning `null` if any segment attempts path
 * traversal (`..`, encoded `%2e%2e`, backslashes, null bytes, or drive-letter prefixes).
 */
fun sanitizeRelativeBundlePath(
    rawRelativePath: String,
    defaultEntryPath: String = "index.html",
): String? {
    val candidate = rawRelativePath.ifBlank { defaultEntryPath }
    if (candidate.contains('\u0000') || candidate.contains('\\')) return null
    val lower = candidate.lowercase()
    if (lower.contains("%00") || lower.contains("%5c") || lower.contains("%2e") || lower.contains("%2f")) {
        return null
    }
    if (candidate.startsWith("/") || (candidate.length >= 2 && candidate[1] == ':')) return null
    val segments = candidate.split('/').filter { it.isNotEmpty() }
    if (segments.isEmpty()) return defaultEntryPath
    for (segment in segments) {
        if (segment == "." || segment == ".." || segment.any { it.code < 0x20 }) return null
    }
    return segments.joinToString("/")
}

/** Infers a standard web MIME type from a file path extension. */
fun inferWebMimeType(path: String): String {
    val ext = path.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "html", "htm" -> "text/html"
        "js", "mjs" -> "application/javascript"
        "css" -> "text/css"
        "json" -> "application/json"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "ico" -> "image/x-icon"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        "ttf" -> "font/ttf"
        "otf" -> "font/otf"
        "wasm" -> "application/wasm"
        "txt" -> "text/plain"
        "xml" -> "application/xml"
        else -> "application/octet-stream"
    }
}
