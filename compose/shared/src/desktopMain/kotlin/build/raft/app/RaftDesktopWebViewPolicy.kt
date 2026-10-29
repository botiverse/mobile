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
     * Fails the top-level fence but is a declared sub-frame: an https page under one of the
     * surface's [RaftDesktopWebViewPolicy.frameHosts] (the Artifact content domain), or
     * about:/blob:/data:. The engine (Wry) reports only the URL, not the frame, so it loads; the
     * host then checks the top-level address and takes the page back if the top-level document
     * left the fence, or if the page does not answer the check in time. Scripts and the host
     * channel are guarded in JS by top frame + origin, so such a document never gets them.
     */
    AllowUnverifiedFrame,

    /** Off the fence and not a declared frame: refused before it loads, handed to `onNavigationBlocked`. */
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
 * 2. Navigation fence: [navigation] decides per URL before it loads; only declared frames
 *    ([frameHosts], about:/blob:/data:) load without passing the top-level fence, and
 *    [isTrustedTopLevel] checks the committed top-level address afterwards (the host also takes the
 *    page back when that check gets no answer; see [RaftDesktopWebViewNavigation.AllowUnverifiedFrame]).
 * 3. Host channel: messages carry a per-WebView secret held only inside the closure of the script
 *    of a top-level document on [url]'s origin, so a sub-frame or a foreign document cannot post
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
    /** [RaftWebViewConfig.frameHosts]: lowercase hostnames, each with its subdomains. */
    frameHosts: Set<String> = emptySet(),
    private val channelSecret: String = newChannelSecret(),
) {
    val frameHosts: Set<String> = frameHosts.map { it.trim().lowercase().removeSuffix(".") }.filter { it.isNotEmpty() }.toSet()
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
        if (scheme in LocalFrameSchemes) return RaftDesktopWebViewNavigation.AllowUnverifiedFrame
        if (scheme == "https" && isDeclaredFrameHost(target)) return RaftDesktopWebViewNavigation.AllowUnverifiedFrame
        return RaftDesktopWebViewNavigation.Block
    }

    private fun isDeclaredFrameHost(target: String): Boolean {
        if (frameHosts.isEmpty()) return false
        val uri = parse(target) ?: return false
        if (uri.rawUserInfo != null) return false
        val host = uri.host?.lowercase()?.removeSuffix(".") ?: return false
        return frameHosts.any { host == it || host.endsWith(".$it") }
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
    fun documentStartScript(): String =
        buildString {
            append("(function(){\n")
            append("if (window.top !== window) return;\n")
            // Everything that holds the secret exists only in a top-level document on [url]'s origin
            // (and page, when fenced): a foreign top-level document returns here and never gets it.
            append("if (!(${documentCondition(sameOriginAsUrl = true)})) return;\n")
            // The engine's post function and JSON.stringify are captured before any page script runs,
            // so a later override of window.ipc / JSON never sees the secret.
            append("var ipc = window.ipc; if (!ipc || typeof ipc.postMessage !== 'function') return;\n")
            append("var ipcPost = Function.prototype.bind.call(ipc.postMessage, ipc);\n")
            append("var stringify = JSON.stringify;\n")
            append("var post = function(kind, value) { try { ipcPost(stringify({s: ${js(channelSecret)}, k: kind, v: String(value)})); } catch (e) {} };\n")
            // iOS hands `target=_blank` / `window.open` to the system browser (createWindowBlocked);
            // Wry drops them, so the trusted document reports them itself. User activation only, as
            // iOS (javaScriptCanOpenWindowsAutomatically = false).
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
                append(
                    "Object.defineProperty(window,\"$RaftWebViewHostPortName\",{value:Object.freeze({postMessage:function(m){if(typeof m===\"string\")post('host',m);}}),writable:false,configurable:false});\n" +
                        "Object.defineProperty(window,\"RaftHost\",{value:Object.freeze({version:\"raft-host-v1\",capabilities:[\"raft-feedback-v1\"]," +
                        "emit:function(k,p){if(typeof k===\"string\")post('host',stringify({kind:k,payload:(p&&typeof p===\"object\"&&!Array.isArray(p))?p:{}}));}})," +
                        "writable:false,configurable:false});\n",
                )
            }
            append("})();\n")
            if (injectedJavaScript.isNotEmpty()) {
                append("if (window.top === window && (${documentCondition()})) {\n")
                append(injectedJavaScript)
                append("\n}\n")
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
        /** Local sub-frame schemes (srcdoc/blank, blob/data media); no remote document. */
        private val LocalFrameSchemes = setOf("about", "blob", "data")

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

/**
 * The after-the-fact top-level check that follows an [RaftDesktopWebViewNavigation.AllowUnverifiedFrame]
 * navigation. The address is read from the page (`location.href`), so a page that blocks its main
 * thread would never answer: once a declared-frame navigation happened, an address request that
 * stays unanswered for [timeoutMs] counts as untrusted (fail closed). The check stands down once
 * the trusted document answered with the page idle at least [settleMs] after the navigation.
 *
 * [markUnverified] is called on the engine's thread; everything else on the UI thread. Pure (the
 * clock is passed in), so it is unit-tested.
 */
class RaftDesktopWebViewTopLevelCheck(
    private val timeoutMs: Long = 500,
    private val settleMs: Long = 1_000,
) {
    enum class Verdict { Trusted, Untrusted, Stale }

    private val unverifiedAt = java.util.concurrent.atomic.AtomicLong(0)
    private var outstandingSince = 0L
    private var token = 0L

    val armed: Boolean get() = unverifiedAt.get() != 0L

    fun markUnverified(nowMs: Long) = unverifiedAt.set(nowMs.coerceAtLeast(1))

    /** A token for a new address request, or null while one is still unanswered. */
    fun beginRequest(nowMs: Long): Long? {
        if (outstandingSince != 0L) return null
        outstandingSince = nowMs.coerceAtLeast(1)
        token += 1
        return token
    }

    fun answer(requestToken: Long, trusted: Boolean, idle: Boolean, nowMs: Long): Verdict {
        if (requestToken != token || outstandingSince == 0L) return Verdict.Stale
        outstandingSince = 0L
        if (!trusted) return Verdict.Untrusted
        val since = unverifiedAt.get()
        if (since != 0L && idle && nowMs - since >= settleMs) unverifiedAt.compareAndSet(since, 0L)
        return Verdict.Trusted
    }

    /** True when a declared-frame navigation is pending and the page has not answered in time. */
    fun timedOut(nowMs: Long): Boolean = armed && outstandingSince != 0L && nowMs - outstandingSince > timeoutMs

    /** After the host took the page back: forget the pending request (its late answer is stale). */
    fun reset() {
        unverifiedAt.set(0L)
        outstandingSince = 0L
        token += 1
    }
}
