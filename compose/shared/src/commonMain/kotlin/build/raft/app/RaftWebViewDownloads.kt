package build.raft.app

import build.raft.app.compat.json.JSONObject
import build.raft.app.network.raftContentDispositionFileName
import build.raft.app.network.raftSafeDownloadFileName
import build.raft.app.network.raftUrlLastSegmentFileName
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * A file a hosted page saves, as a browser would save it (#proj-mobile:#202): the app's web
 * container keeps it in the app, never handing the page to the system browser (where the person
 * is not signed in).
 */
internal sealed interface RaftWebViewDownload {
    /**
     * The file's bytes, read in the page (a `blob:` / `data:` save: Android reads it with the page's
     * own `fetch`) or downloaded by the WebView itself (iOS WKDownload, with the page's cookies).
     * [fileName] is already chosen: the page's `download` name, else the response's
     * Content-Disposition, else the address's last segment.
     */
    class Bytes(val bytes: ByteArray, val fileName: String, val mimeType: String?) : RaftWebViewDownload

    /**
     * An http(s) address on the page's own origin to download (Android: the WebView hands it over
     * without its bytes); the host fetches it itself, with its session.
     */
    data class Url(
        val url: String,
        /** The page's `download` name for it, when the page gave one. */
        val pageFileName: String?,
        val contentDisposition: String?,
        val mimeType: String?,
    ) : RaftWebViewDownload

    /** The save could not be read ([reason] for the log: `blob_read_failed`, `too_large`, `off_origin`, ...). */
    data class Failed(val reason: String) : RaftWebViewDownload
}

/** At most this many bytes are read from a page's `blob:` save into memory. */
internal const val RaftWebViewDownloadMaxBytes: Long = 200L * 1024L * 1024L

/** The page's message port for its `blob:` reads (Android `addWebMessageListener`, its origin only). */
internal const val RaftWebViewDownloadPortName = "raftDownloadPort"

/**
 * The name a save gets: the page's `download` attribute, else Content-Disposition (`filename*`,
 * then `filename`), else the address's last segment, else `download` (with an extension from the
 * type when it has none and the type says one).
 */
internal fun raftWebViewDownloadFileName(
    pageFileName: String?,
    contentDisposition: String?,
    url: String,
    mimeType: String?,
): String {
    val chosen =
        pageFileName?.let(::raftSafeDownloadFileName)
            ?: raftContentDispositionFileName(contentDisposition)
            ?: url.takeUnless { it.startsWith("blob:", ignoreCase = true) || it.startsWith("data:", ignoreCase = true) }
                ?.let(::raftUrlLastSegmentFileName)
            ?: "download"
    if ('.' in chosen) return chosen
    val extension = raftWebViewDownloadExtension(mimeType) ?: return chosen
    return "$chosen.$extension"
}

private fun raftWebViewDownloadExtension(mimeType: String?): String? =
    when (mimeType?.substringBefore(';')?.trim()?.lowercase()) {
        "application/zip", "application/x-zip-compressed" -> "zip"
        "application/pdf" -> "pdf"
        "text/html" -> "html"
        "text/markdown" -> "md"
        "text/plain" -> "txt"
        "text/csv" -> "csv"
        "application/json" -> "json"
        "image/png" -> "png"
        "image/jpeg" -> "jpg"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "image/svg+xml" -> "svg"
        else -> null
    }

/**
 * Installed at document start in the page's main frame (its origin only): remembers the
 * `download` name of the last `<a download>` links clicked (the web saves through
 * `link.download = name; link.click()` on a link never put in the document, so the prototype's
 * `click` is wrapped, and a capturing listener covers links in the document), so the app can name
 * the file as the page does. Changes nothing the page sees.
 */
internal const val RaftWebViewDownloadNameHookScript: String =
    "(function(){if(window.__raftDownloadNames)return;var names={},order=[];" +
        "function keep(a){try{if(!a||typeof a.download!=='string'||!a.download||!a.href)return;" +
        "if(!(a.href in names))order.push(a.href);names[a.href]=a.download;" +
        "while(order.length>20){delete names[order.shift()];}}catch(e){}}" +
        "Object.defineProperty(window,'__raftDownloadNames',{value:names,writable:false,configurable:false});" +
        "var click=HTMLAnchorElement.prototype.click;" +
        "HTMLAnchorElement.prototype.click=function(){keep(this);return click.apply(this,arguments);};" +
        "document.addEventListener('click',function(e){var t=e.target;" +
        "var a=t&&t.closest?t.closest('a[download]'):null;keep(a);},true);})();"

/**
 * Reads the page's `blob:` (or `data:`) save at [url] in the page itself (only it can: the bytes
 * live in its memory) and posts them to [RaftWebViewDownloadPortName] in base64 chunks, tagged
 * [token]: `start {name,type,size}`, `chunk {i,d}`, `end {n}`, or `error {m}`. Run right away: the
 * web revokes the address shortly after the click (10 s today, slock #203 makes it 60 s).
 */
internal fun raftWebViewBlobReadScript(url: String, token: String, maxBytes: Long = RaftWebViewDownloadMaxBytes): String {
    val urlJson = JSONObject.quote(url)
    val tokenJson = JSONObject.quote(token)
    return "(function(){var p=window.$RaftWebViewDownloadPortName;if(!p)return;var u=$urlJson,t=$tokenJson;" +
        "function post(o){o.t=t;p.postMessage(JSON.stringify(o));}" +
        "var n=(window.__raftDownloadNames&&window.__raftDownloadNames[u])||'';" +
        "fetch(u).then(function(r){if(!r.ok)throw new Error('status '+r.status);return r.blob();}).then(function(b){" +
        "if(b.size>$maxBytes){post({k:'error',m:'too_large'});return;}" +
        "post({k:'start',name:n,type:b.type||'',size:b.size});" +
        "var size=393216,i=0,off=0;" +
        "function next(){if(off>=b.size){post({k:'end',n:i});return;}" +
        "var fr=new FileReader();fr.onload=function(){var s=String(fr.result);var c=s.indexOf(',');" +
        "post({k:'chunk',i:i,d:c>=0?s.substring(c+1):''});i++;off+=size;next();};" +
        "fr.onerror=function(){post({k:'error',m:'read_failed'});};" +
        "fr.readAsDataURL(b.slice(off,Math.min(off+size,b.size)));}next();" +
        "}).catch(function(e){post({k:'error',m:'blob_read_failed'});});})();"
}

/**
 * Puts one `blob:` read's messages ([raftWebViewBlobReadScript]) back together: chunks in order,
 * the size the page announced, under [maxBytes]. [accept] answers the finished download (or a
 * [RaftWebViewDownload.Failed]) once, else null while it is still coming.
 */
internal class RaftWebViewBlobDownloadAssembler(
    private val token: String,
    private val url: String,
    private val maxBytes: Long = RaftWebViewDownloadMaxBytes,
) {
    private var name: String? = null
    private var type: String? = null
    private var announced: Long = -1L
    private var nextIndex = 0
    private val parts = ArrayList<ByteArray>()
    private var received = 0L
    var finished: Boolean = false
        private set

    @OptIn(ExperimentalEncodingApi::class)
    fun accept(raw: String): RaftWebViewDownload? {
        if (finished) return null
        val message = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        if (message.optString("t") != token) return null
        return when (message.optString("k")) {
            "start" -> {
                name = message.optString("name").takeIf { it.isNotBlank() }
                type = message.optString("type").takeIf { it.isNotBlank() }
                announced = message.optLong("size", -1L)
                if (announced > maxBytes) fail("too_large") else null
            }
            "chunk" -> {
                if (announced < 0 || message.optInt("i", -1) != nextIndex) return fail("chunk_order")
                val bytes = runCatching { Base64.decode(message.optString("d")) }.getOrNull() ?: return fail("chunk_decode")
                received += bytes.size
                if (received > announced || received > maxBytes) return fail("too_large")
                parts += bytes
                nextIndex++
                null
            }
            "end" -> {
                if (announced < 0 || received != announced || message.optInt("n", -1) != nextIndex) return fail("size_mismatch")
                finished = true
                val all = ByteArray(received.toInt())
                var offset = 0
                parts.forEach { part ->
                    part.copyInto(all, offset)
                    offset += part.size
                }
                parts.clear()
                RaftWebViewDownload.Bytes(
                    bytes = all,
                    fileName = raftWebViewDownloadFileName(name, null, url, type),
                    mimeType = type,
                )
            }
            "error" -> fail(message.optString("m").ifBlank { "blob_read_failed" })
            else -> null
        }
    }

    private fun fail(reason: String): RaftWebViewDownload {
        finished = true
        parts.clear()
        return RaftWebViewDownload.Failed(reason)
    }
}
