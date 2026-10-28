package build.raft.app

import androidx.compose.runtime.Composable

/**
 * One native WebView kept past its page ([RaftWebViewConfig.keepAliveKey]), so the next page with
 * the same key shows its document at once instead of booting the web app again. Used by the
 * Artifact viewer only; its key carries the web origin, the account and the server
 * ([RaftArtifactViewerScope.keepAliveKey]), so a kept document is never shown to anyone else.
 *
 * A platform without keep-alive (iOS and desktop for now) answers null / false everywhere and
 * every page creates and destroys its own WebView, as before.
 */
internal data class RaftWebViewKeptDocument(
    /** The source the kept WebView was configured with (its [RaftWebViewConfig.url]). */
    val src: String,
    /** The top-level document's URL now (after in-page navigation). */
    val currentUrl: String,
    /** Whether that document finished loading. */
    val loaded: Boolean,
)

/** The document kept under [key], or null (nothing kept, another key, or in use by a page). */
internal expect fun raftWebViewKeptDocument(key: String): RaftWebViewKeptDocument?

/**
 * Runs [script] in the kept top-level document under [key] when it is on [origin] and loaded.
 * Never in a sub-frame. Returns whether it ran.
 */
internal expect fun raftWebViewEvaluateInKept(key: String, origin: String, script: String): Boolean

/** Destroys every kept WebView (sign-out, an account or server switch, a 403/404). */
internal expect fun raftWebViewKeepAliveClear()

/**
 * Creates and keeps a WebView for [config] (which has a [RaftWebViewConfig.keepAliveKey]) ahead
 * of its page, unless one is kept under that key already. No-op where keep-alive isn't supported.
 */
@Composable
internal expect fun RaftWebViewPrewarmEffect(config: RaftWebViewConfig?)
