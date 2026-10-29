package build.raft.app

import java.net.URI
import java.security.SecureRandom
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** What the desktop host does with a navigation the native engine asks about. */
enum class RaftDesktopWebViewNavigation {
    /** Passes the top-level fence: load it. */
    Allow,

    /**
     * Fails the top-level fence, but may be a sub-frame. The engine (Wry) reports only the URL, not
     * the frame, so a content frame and a top-level navigation look the same here. Load it; the
     * host checks the top-level address right after ([RaftDesktopWebViewPolicy.isTrustedTopLevel])
     * and takes the page back if the top-level document left the fence. Every script and the host
     * channel are guarded in JS by top frame + origin, so such a document never gets them.
     */
    AllowUnverifiedFrame,

    /** Never loads here (a scheme no frame of ours loads): handed to `onNavigationBlocked`. */
    Block,
}

/**
 * The desktop WebView's security rules, one immutable instance per native WebView (a change of
 * any of these rebuilds the WebView). Pure: no native calls, so it is unit-tested on the JVM.
 *
 * Mapping of Tenny's WebView rules (RaftArtifactViewer.kt) and the iOS/Android hosts:
 * 1. Scripts (the session) run only in a top-level document of the configured origin: the
 *    document-start script and every runtime script are wrapped in a JS guard on `window.top` and
 *    `location.origin` ([documentStartScript], [guardRuntimeScript]); runtime scripts are also
 *    refused on the host side unless the current top-level address is trusted ([canRunRuntimeScript]).
 * 2. Navigation fence: [navigation] decides per URL, [isTrustedTopLevel] checks the committed
 *    top-level address afterwards (see [RaftDesktopWebViewNavigation.AllowUnverifiedFrame]).
 * 3. Host channel: messages carry a per-WebView secret held only inside the guarded top-level
 *    script's closure, so a sub-frame (or any other document) cannot post host messages
 *    ([unwrapChannelMessage]).
 */
class RaftDesktopWebViewPolicy(
    val url: String,
    val allowNavigation: Boolean,
    /** Normalized (lower case, no ':'); empty = any scheme. */
    val allowedNavigationSchemes: Set<String>,
    /** `https://host[:port]` origin lock, or null. */
    mainFrameOrigin: String?,
    val hostEventsEnabled: Boolean,
    val injectedJavaScript: String,
    private val channelSecret: String = newChannelSecret(),
) {
    val mainFrameOrigin: String? = mainFrameOrigin?.let(::raftWebOriginOf)
    private val src = url.trim()
    private val srcUri: URI? = parse(src)
    private val srcOrigin: String? = raftWebOriginOf(src)

    /** The URL to load first, or null when [url] itself fails the fence (report it blocked). */
    fun initialUrl(): String? =
        when {
            src.isEmpty() -> "about:blank"
            isTrustedTopLevel(src) -> src
            else -> null
        }

    fun navigation(target: String): RaftDesktopWebViewNavigation {
        if (isTrustedTopLevel(target)) return RaftDesktopWebViewNavigation.Allow
        val scheme = schemeOf(target)
        return if (scheme in FrameSchemes) RaftDesktopWebViewNavigation.AllowUnverifiedFrame else RaftDesktopWebViewNavigation.Block
    }

    /** Whether a top-level document at [target] may stay (the iOS/Android main-frame fence). */
    fun isTrustedTopLevel(target: String): Boolean {
        val value = target.trim()
        if (src.isEmpty() && value == "about:blank") return true
        if (!isSchemeAllowed(value)) return false
        if (mainFrameOrigin != null && raftWebOriginOf(value) != mainFrameOrigin) return false
        return allowNavigation || isAllowedMainDocument(value)
    }

    /** Host-side gate of a runtime script (the iOS `sameTrustedDocument`). */
    fun canRunRuntimeScript(topLevelUrl: String): Boolean {
        val value = topLevelUrl.trim()
        if (srcOrigin == null || raftWebOriginOf(value) != srcOrigin) return false
        return isTrustedTopLevel(value)
    }

    /** Wraps a runtime script so it does nothing unless the document is still the trusted one. */
    fun guardRuntimeScript(script: String): String = "if (${documentCondition(sameOriginAsUrl = true)}) {\n$script\n}"

    /**
     * The single document-start script (Wry has one): host channel helpers and [injectedJavaScript],
     * each behind its guard. Wry injects it into the main frame only on macOS/Linux and into every
     * frame on Windows, hence the `window.top` check inside.
     */
    fun documentStartScript(): String {
        val secret = js(channelSecret)
        return buildString {
            append("(function(){\n")
            append("if (window.top !== window) return;\n")
            // JSON.stringify captured before any page script: a later override never sees the secret.
            append("var stringify = JSON.stringify;\n")
            append("var post = function(kind, value) { try { window.ipc.postMessage(stringify({s: $secret, k: kind, v: String(value)})); } catch (e) {} };\n")
            // iOS hands `target=_blank` / `window.open` to the system browser (createWindowBlocked);
            // Wry drops them, so the top-level document reports them itself. User activation only,
            // as iOS (javaScriptCanOpenWindowsAutomatically = false).
            append(
                """
                var activated = function() { return !navigator.userActivation || navigator.userActivation.isActive; };
                var nativeOpen = window.open;
                window.open = function(u) {
                  if (!activated() || u === undefined || u === null || u === '') return nativeOpen.apply(window, arguments);
                  try { post('open', String(new URL(String(u), document.baseURI))); } catch (e) {}
                  return null;
                };
                window.addEventListener('click', function(e) {
                  if (!e.isTrusted || e.defaultPrevented || e.button !== 0) return;
                  var a = e.target && e.target.closest ? e.target.closest('a[href]') : null;
                  if (!a || (a.target || '').toLowerCase() !== '_blank') return;
                  e.preventDefault();
                  post('open', a.href);
                });
                """.trimIndent(),
            )
            append('\n')
            if (hostEventsEnabled) {
                append("if (${documentCondition(sameOriginAsUrl = true)}) {\n")
                append(
                    "Object.defineProperty(window,\"$RaftWebViewHostPortName\",{value:Object.freeze({postMessage:function(m){if(typeof m===\"string\")post('host',m);}}),writable:false,configurable:false});\n" +
                        "Object.defineProperty(window,\"RaftHost\",{value:Object.freeze({version:\"raft-host-v1\",capabilities:[\"raft-feedback-v1\"]," +
                        "emit:function(k,p){if(typeof k===\"string\")post('host',JSON.stringify({kind:k,payload:(p&&typeof p===\"object\"&&!Array.isArray(p))?p:{}}));}})," +
                        "writable:false,configurable:false});\n",
                )
                append("}\n")
            }
            append("})();\n")
            if (injectedJavaScript.isNotEmpty()) {
                append("if (window.top === window && (${documentCondition()})) {\n")
                append(injectedJavaScript)
                append("\n}\n")
            }
        }
    }

    /**
     * Decodes a raw Wry IPC message. Null unless it carries this WebView's secret (so it came from
     * the guarded top-level script) and a known kind.
     */
    fun unwrapChannelMessage(raw: String): RaftDesktopWebViewChannelMessage? {
        val parsed = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
        if ((parsed["s"] as? JsonPrimitive)?.contentOrNull != channelSecret) return null
        val value = (parsed["v"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        return when ((parsed["k"] as? JsonPrimitive)?.contentOrNull) {
            "host" -> if (hostEventsEnabled) RaftDesktopWebViewChannelMessage.Host(value) else null
            "open" -> RaftDesktopWebViewChannelMessage.OpenWindow(value)
            else -> null
        }
    }

    /**
     * JS condition for "this is the document the scripts are for". [sameOriginAsUrl]: the host
     * channel and runtime scripts (iOS: the message frame / active URL is on [url]'s origin).
     * Otherwise the injected script: the origin lock, else [url]'s origin when navigation is fenced.
     */
    private fun documentCondition(sameOriginAsUrl: Boolean = false): String {
        val origin =
            if (sameOriginAsUrl) {
                if (srcOrigin == null || (mainFrameOrigin != null && mainFrameOrigin != srcOrigin)) return "false"
                srcOrigin
            } else {
                mainFrameOrigin ?: srcOrigin.takeIf { !allowNavigation }
            }
        val parts = mutableListOf<String>()
        if (origin != null) parts += "window.location.origin === ${js(origin)}"
        if (!allowNavigation && srcUri != null && origin != null) {
            parts += "window.location.pathname === ${js(srcUri.rawPath.orEmpty().ifEmpty { "/" })}"
            parts += "window.location.search === ${js(srcUri.rawQuery?.let { "?$it" }.orEmpty())}"
        }
        return parts.joinToString(" && ").ifEmpty { "true" }
    }

    private fun isSchemeAllowed(value: String): Boolean {
        if (allowedNavigationSchemes.isEmpty()) return true
        val scheme = schemeOf(value)
        return scheme.isNotEmpty() && scheme in allowedNavigationSchemes
    }

    /** iOS `isAllowedMainDocumentURL`: the configured URL, fragment aside. */
    private fun isAllowedMainDocument(value: String): Boolean {
        if (value == src) return true
        val allowed = srcUri ?: return false
        val target = parse(value) ?: return false
        return allowed.scheme.orEmpty().equals(target.scheme.orEmpty(), ignoreCase = true) &&
            allowed.host.orEmpty().equals(target.host.orEmpty(), ignoreCase = true) &&
            allowed.port == target.port &&
            allowed.rawPath.orEmpty().ifEmpty { "/" } == target.rawPath.orEmpty().ifEmpty { "/" } &&
            allowed.rawQuery.orEmpty() == target.rawQuery.orEmpty()
    }

    companion object {
        /** Schemes a sub-frame of our pages loads (content frame, srcdoc/blank, blob/data media). */
        private val FrameSchemes = setOf("http", "https", "about", "blob", "data")

        /** The `""+location.href` result Wry hands back (a JSON string literal), or null. */
        fun decodeEvaluatedString(result: String): String? =
            runCatching { (Json.parseToJsonElement(result) as? JsonPrimitive)?.takeIf { it.isString }?.content }.getOrNull()

        private fun schemeOf(value: String): String = value.trim().substringBefore(':', "").lowercase()

        private fun parse(value: String): URI? = runCatching { URI(value.trim()) }.getOrNull()?.takeIf { it.isAbsolute }

        private fun js(value: String): String = Json.encodeToString(String.serializer(), value)

        private fun newChannelSecret(): String {
            val bytes = ByteArray(24)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}

sealed interface RaftDesktopWebViewChannelMessage {
    /** A `raftHostPort` / `RaftHost.emit` message (raw, decoded by [RaftWebViewEvents.dispatchHostMessage]). */
    data class Host(val raw: String) : RaftDesktopWebViewChannelMessage

    /** `target=_blank` / `window.open` from the top-level document: opens outside, as on iOS. */
    data class OpenWindow(val url: String) : RaftDesktopWebViewChannelMessage
}
