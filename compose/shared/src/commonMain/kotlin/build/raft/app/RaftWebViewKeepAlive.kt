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

/** The engine's default page background (opaque white). */
internal const val RAFT_WEB_VIEW_DEFAULT_BACKGROUND_ARGB: Int = -0x1

/**
 * The background a native WebView shows ([RaftWebViewConfig.backgroundArgb]): the app's canvas
 * colour until its first page painted, then the engine's default white (pages without a background
 * of their own read as in a browser). [placeholderArgb] 0: white throughout.
 */
internal fun raftWebViewBackgroundArgb(placeholderArgb: Int, painted: Boolean): Int =
    if (painted || placeholderArgb == 0) RAFT_WEB_VIEW_DEFAULT_BACKGROUND_ARGB else placeholderArgb

/**
 * Whether a native WebView is shown before its page's document committed (iOS): not while a
 * canvas colour is set ([placeholderArgb] not 0), since until the commit it still shows what it
 * had (the spare's blank warm-up document, white in dark mode) and the canvas container shows
 * through instead; always once [committed].
 */
internal fun raftWebViewShownBeforeCommit(placeholderArgb: Int, committed: Boolean): Boolean =
    committed || placeholderArgb == 0

/**
 * The one spare WebView a platform keeps for the next page (iOS `RaftIosWebViewSpare`). A spare is
 * handed out only once its own warm-up load has ended ([markReady] with the generation [put]
 * returned): before that its navigation callbacks could still reach the page that took it and
 * look like the page's own load. Main thread only.
 */
internal class RaftWebViewSpareSlot<T : Any>(private val release: (T) -> Unit = {}) {
    private var view: T? = null
    private var ready = false
    private var generation = 0

    val isEmpty: Boolean get() = view == null

    /** Holds [next] (releasing any spare held); returns its generation for [markReady]. */
    fun put(next: T): Int {
        view?.let(release)
        view = next
        ready = false
        generation += 1
        return generation
    }

    /** The warm-up load of the spare of [generation] ended (finished or failed). */
    fun markReady(generation: Int) {
        if (generation == this.generation && view != null) ready = true
    }

    /** The ready spare (now the caller's), or null: none, or still warming up (it stays). */
    fun take(): T? {
        if (!ready) return null
        val taken = view ?: return null
        view = null
        ready = false
        return taken
    }

    /** Releases the spare, ready or not; whether there was one. */
    fun drop(): Boolean {
        val dropped = view ?: return false
        view = null
        ready = false
        generation += 1
        release(dropped)
        return true
    }
}

/**
 * Whether a page's WebView must ignore a navigation callback for [navigationUrl]: on a spare
 * WebView ([usingSpare]) the spare's own blank warm-up document is not the page's load, unless the
 * page's source is itself blank.
 */
internal fun raftWebViewIgnoresSpareBlankNavigation(usingSpare: Boolean, navigationUrl: String?, src: String): Boolean =
    usingSpare && navigationUrl == RAFT_WEB_VIEW_SPARE_BLANK_URL &&
        src.isNotEmpty() && src != RAFT_WEB_VIEW_SPARE_BLANK_URL

internal const val RAFT_WEB_VIEW_SPARE_BLANK_URL = "about:blank"

/**
 * Document-start script for a page shown before its first paint: until the page's own load
 * ends, its root gets the app's canvas colour and colour scheme, so the engine's default white
 * canvas does not flash in dark mode before the page's stylesheet and theme script apply.
 *
 * - The rule is `:where(html)` (zero specificity) in a constructed, adopted stylesheet: any
 *   background or `color-scheme` of the page's own wins, and CSSOM sheets are not inline styles
 *   for a CSP. Without constructable sheets: the root's inline style (CSSOM, not CSP-blocked).
 * - Removed two frames after `load`: from then on the page is what it paints itself (a page that
 *   paints no background is white, as in a browser).
 * - Top-level document only; it reads and sends nothing.
 */
internal fun raftWebViewFirstPaintThemeScript(canvasArgb: Int, dark: Boolean): String {
    val rgb = raftWebViewCssRgb(canvasArgb)
    val scheme = if (dark) "dark" else "light"
    return "(function(){try{if(window.top!==window)return;var d=document,s=null,inl=false;" +
        "try{s=new CSSStyleSheet();s.replaceSync(':where(html){background-color:$rgb;color-scheme:$scheme}');" +
        "d.adoptedStyleSheets=d.adoptedStyleSheets.concat([s]);}catch(e){s=null;var r=d.documentElement;" +
        "if(r){r.style.setProperty('background-color','$rgb');r.style.setProperty('color-scheme','$scheme');inl=true;}}" +
        "function off(){try{if(s){d.adoptedStyleSheets=d.adoptedStyleSheets.filter(function(x){return x!==s;});}" +
        "else if(inl){var r=d.documentElement;r.style.removeProperty('background-color');r.style.removeProperty('color-scheme');}}catch(e){}}" +
        "window.addEventListener('load',function(){requestAnimationFrame(function(){requestAnimationFrame(off);});},{once:true});" +
        "}catch(e){}})();"
}

/**
 * Paints the document a spare WebView already shows (its blank warm-up document, [RAFT_WEB_VIEW_SPARE_BLANK_URL])
 * in the app's canvas colour and scheme. The taking page's request does not replace it until the
 * new document commits (the whole server round trip, ~1 s on a cold start); that blank document
 * was laid out while the spare was opaque, so it showed as a white page in dark mode. Run on the
 * blank document right before the page's own load; the document is discarded with it.
 */
internal fun raftWebViewSpareBlankCanvasScript(canvasArgb: Int, dark: Boolean): String {
    val rgb = raftWebViewCssRgb(canvasArgb)
    val scheme = if (dark) "dark" else "light"
    return "(function(){try{var r=document.documentElement;if(!r)return;" +
        "r.style.setProperty('background-color','$rgb');r.style.setProperty('color-scheme','$scheme');}catch(e){}})();"
}

/** `rgb(r,g,b)` of [argb] (alpha dropped). */
private fun raftWebViewCssRgb(argb: Int): String =
    "rgb(${(argb ushr 16) and 0xFF},${(argb ushr 8) and 0xFF},${argb and 0xFF})"
