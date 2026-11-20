package build.raft.app

import build.raft.app.compat.json.JSONObject
import build.raft.app.network.raftSafeDownloadFileName
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * The Web Share API (with files) for a hosted page whose WebView has none (Android's WebView; iOS
 * WKWebView has it natively, Web Share level 2), as a browser offers it (slock #15772: the
 * Artifact viewer's ⋯ › Share, shown only when `navigator.canShare({ files })` answers true).
 *
 * Installed at document start in the page's own origin (main frame): `navigator.canShare` answers
 * synchronously; `navigator.share` returns a Promise, reads each File/Blob as base64 in chunks and
 * posts them to [RaftWebViewDownloadPortName] (the origin-bound port the page's downloads use).
 * The app writes the files and opens the system share sheet, then settles the Promise with
 * [raftWebViewShareSettleScript]: resolved once the sheet is shown, rejected (`AbortError`,
 * `DataError`, ...) when it could not be.
 */
internal fun raftWebViewShareShimScript(maxBytes: Long = RaftWebViewShareMaxBytes): String =
    "(function(){if(window.__raftShareSettle)return;var n=navigator;" +
        "if(typeof n.share==='function'&&typeof n.canShare==='function')return;" +
        "var port=function(){return window.$RaftWebViewDownloadPortName;};" +
        "var pending={},seq=0;" +
        "function isFile(f){return typeof Blob!=='undefined'&&f instanceof Blob;}" +
        "function canShare(d){if(!d||typeof d!=='object'||!port())return false;" +
        "var f=d.files;if(f!==undefined&&f!==null){if(!f.length)return false;var total=0;" +
        "for(var i=0;i<f.length;i++){if(!isFile(f[i]))return false;total+=f[i].size;}return total<=$maxBytes;}" +
        "return typeof d.url==='string'||typeof d.text==='string'||typeof d.title==='string';}" +
        "function err(name,msg){var e;try{e=new DOMException(msg,name);}catch(x){e=new Error(msg);e.name=name;}return e;}" +
        "Object.defineProperty(window,'__raftShareSettle',{value:function(id,name){var p=pending[id];if(!p)return;" +
        "delete pending[id];if(name)p.rej(err(name,'Share failed'));else p.res();},writable:false,configurable:false});" +
        "n.canShare=canShare;" +
        "n.share=function(d){return new Promise(function(res,rej){if(!canShare(d)){rej(new TypeError('Cannot share this data'));return;}" +
        "var p=port(),id='s'+(++seq)+'_'+Date.now(),files=d.files?Array.prototype.slice.call(d.files):[];" +
        "pending[id]={res:res,rej:rej};" +
        "function post(o){o.s=id;p.postMessage(JSON.stringify(o));}" +
        "post({k:'share-start',title:typeof d.title==='string'?d.title:'',text:typeof d.text==='string'?d.text:''," +
        "url:typeof d.url==='string'?d.url:'',files:files.map(function(f){return{name:f.name||'',type:f.type||'',size:f.size};})});" +
        "var fi=0,off=0,ci=0,size=393216;" +
        "function next(){if(fi>=files.length){post({k:'share-end'});return;}var f=files[fi];" +
        "if(off>=f.size){fi++;off=0;ci=0;next();return;}" +
        "var fr=new FileReader();fr.onload=function(){var s=String(fr.result);var c=s.indexOf(',');" +
        "post({k:'share-chunk',f:fi,i:ci,d:c>=0?s.substring(c+1):''});ci++;off+=size;next();};" +
        "fr.onerror=function(){post({k:'share-error'});window.__raftShareSettle(id,'DataError');};" +
        "fr.readAsDataURL(f.slice(off,Math.min(off+size,f.size)));}next();});};})();"

/** Settles the page's `navigator.share` Promise [id]: resolved when [errorName] is null, else rejected with it. */
internal fun raftWebViewShareSettleScript(id: String, errorName: String?): String =
    "(function(){var s=window.__raftShareSettle;if(s)s(${JSONObject.quote(id)},${if (errorName == null) "null" else JSONObject.quote(errorName)});})();"

/** At most this many bytes in one share (the files together). */
internal const val RaftWebViewShareMaxBytes: Long = 100L * 1024L * 1024L

internal class RaftWebViewShareFile(val name: String, val mimeType: String, val bytes: ByteArray)

internal class RaftWebViewShareRequest(
    val id: String,
    val title: String,
    val text: String,
    val url: String,
    val files: List<RaftWebViewShareFile>,
)

internal sealed interface RaftWebViewShareAssembly {
    class Ready(val request: RaftWebViewShareRequest) : RaftWebViewShareAssembly

    data class Failed(val id: String, val reason: String) : RaftWebViewShareAssembly
}

/** Whether a port message ([raftWebViewShareShimScript]) is part of a share rather than a download read. */
internal fun raftWebViewIsShareMessage(raw: String): Boolean = raw.contains("\"k\":\"share-")

/**
 * Puts the page's share messages back together, per share id: the files announced at `share-start`,
 * their chunks in order and to their announced sizes, under [maxBytes] together.
 */
internal class RaftWebViewShareAssembler(private val maxBytes: Long = RaftWebViewShareMaxBytes) {
    private class Pending(
        val title: String,
        val text: String,
        val url: String,
        val names: List<String>,
        val types: List<String>,
        val sizes: List<Long>,
    ) {
        val parts = sizes.map { ArrayList<ByteArray>() }
        val received = LongArray(sizes.size)
        val nextChunk = IntArray(sizes.size)
        var nextFile = 0
    }

    private val pending = LinkedHashMap<String, Pending>()

    @OptIn(ExperimentalEncodingApi::class)
    fun accept(raw: String): RaftWebViewShareAssembly? {
        val message = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val id = message.optString("s").takeIf { it.isNotBlank() } ?: return null
        when (message.optString("k")) {
            "share-start" -> {
                val files = message.optJSONArray("files")
                val names = ArrayList<String>()
                val types = ArrayList<String>()
                val sizes = ArrayList<Long>()
                for (index in 0 until (files?.length() ?: 0)) {
                    val file = files?.optJSONObject(index) ?: return fail(id, "file_meta")
                    names += file.optString("name")
                    types += file.optString("type")
                    sizes += file.optLong("size", -1L).also { if (it < 0) return fail(id, "file_meta") }
                }
                if (sizes.sum() > maxBytes) return fail(id, "too_large")
                while (pending.size >= 4) pending.remove(pending.keys.first())
                pending[id] = Pending(message.optString("title"), message.optString("text"), message.optString("url"), names, types, sizes)
                return null
            }
            "share-chunk" -> {
                val share = pending[id] ?: return null
                val file = message.optInt("f", -1)
                if (file < share.nextFile || file >= share.sizes.size) return fail(id, "chunk_order")
                // Files are read one after another; an empty one sends no chunk.
                if (file > share.nextFile && (share.nextFile until file).any { share.received[it] != share.sizes[it] }) {
                    return fail(id, "chunk_order")
                }
                share.nextFile = file
                if (message.optInt("i", -1) != share.nextChunk[file]) return fail(id, "chunk_order")
                val bytes = runCatching { Base64.decode(message.optString("d")) }.getOrNull() ?: return fail(id, "chunk_decode")
                share.received[file] += bytes.size
                if (share.received[file] > share.sizes[file]) return fail(id, "size_mismatch")
                share.parts[file] += bytes
                share.nextChunk[file]++
                return null
            }
            "share-end" -> {
                val share = pending.remove(id) ?: return null
                if (share.sizes.indices.any { share.received[it] != share.sizes[it] }) {
                    return RaftWebViewShareAssembly.Failed(id, "size_mismatch")
                }
                val used = HashSet<String>()
                val files =
                    share.sizes.indices.map { index ->
                        val all = ByteArray(share.sizes[index].toInt())
                        var offset = 0
                        share.parts[index].forEach { part ->
                            part.copyInto(all, offset)
                            offset += part.size
                        }
                        val type = share.types[index].ifBlank { "application/octet-stream" }
                        RaftWebViewShareFile(
                            name = raftWebViewShareUniqueName(
                                raftWebViewDownloadFileName(share.names[index].takeIf { it.isNotBlank() }, null, "", type),
                                used,
                            ),
                            mimeType = type,
                            bytes = all,
                        )
                    }
                return RaftWebViewShareAssembly.Ready(RaftWebViewShareRequest(id, share.title, share.text, share.url, files))
            }
            "share-error" -> {
                pending.remove(id)
                return null
            }
            else -> return null
        }
    }

    private fun fail(id: String, reason: String): RaftWebViewShareAssembly {
        pending.remove(id)
        return RaftWebViewShareAssembly.Failed(id, reason)
    }
}

/** [name], or `name (2).ext`, ... when another file of the same share already has it. */
internal fun raftWebViewShareUniqueName(name: String, used: MutableSet<String>): String {
    val safe = raftSafeDownloadFileName(name) ?: "file"
    if (used.add(safe)) return safe
    val dot = safe.lastIndexOf('.').takeIf { it > 0 } ?: safe.length
    var index = 2
    while (true) {
        val candidate = safe.substring(0, dot) + " ($index)" + safe.substring(dot)
        if (used.add(candidate)) return candidate
        index++
    }
}

/** The text an Android text-only share sends: title, text and url, each once, on their own lines. */
internal fun raftWebViewShareText(title: String, text: String, url: String): String =
    listOf(text, url).filter { it.isNotBlank() }.distinct().joinToString("\n").ifBlank { title }
