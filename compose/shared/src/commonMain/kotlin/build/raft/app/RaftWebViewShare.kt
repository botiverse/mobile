package build.raft.app

import build.raft.app.compat.json.JSONObject
import build.raft.app.downloads.RaftShareFileName
import build.raft.app.matches
import build.raft.app.settings.matches
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * The Web Share API (with files) for a hosted page whose WebView has none (Android's WebView; iOS
 * WKWebView has it natively, Web Share level 2), as a browser offers it (slock #15772: the
 * Artifact viewer's ⋯ › Share, shown only when `navigator.canShare({ files })` answers true).
 *
 * Installed at document start in the page's own origin, top frame only (the app takes the port's
 * messages from the main frame alone, so a subframe's share could never settle): `navigator.canShare`
 * answers synchronously; `navigator.share` returns a Promise, reads each File/Blob as base64 in
 * chunks and posts them to [RaftWebViewDownloadPortName] (the origin-bound port the page's downloads
 * use), one chunk at a time: the next is read once the app has written the last
 * ([raftWebViewShareAckScript]), so neither side holds more than a chunk. The app opens the system
 * share sheet, then settles the Promise with [raftWebViewShareSettleScript]: resolved once the sheet
 * is shown, rejected (`AbortError`, `DataError`, ...) when it could not be.
 *
 * As the Web Share spec has it, `share` rejects with `InvalidStateError` while an earlier share of
 * this page is still in flight, with `NotAllowedError` without a user activation (where the WebView
 * reports one), and with `TypeError` for data it cannot share.
 */
internal fun raftWebViewShareShimScript(maxBytes: Long = RaftWebViewShareMaxBytes): String =
    "(function(){if(window.__raftShareSettle)return;" +
        "try{if(window.top!==window)return;}catch(e){return;}" +
        "var n=navigator;" +
        "if(typeof n.share==='function'&&typeof n.canShare==='function')return;" +
        "var port=function(){return window.$RaftWebViewDownloadPortName;};" +
        "var pending={},seq=0,active=null;" +
        "function isFile(f){return typeof Blob!=='undefined'&&f instanceof Blob;}" +
        "function canShare(d){if(!d||typeof d!=='object'||!port())return false;" +
        "var f=d.files;if(f!==undefined&&f!==null){if(!f.length||f.length>$RaftWebViewShareMaxFiles)return false;var total=0;" +
        "for(var i=0;i<f.length;i++){if(!isFile(f[i]))return false;total+=f[i].size;}return total<=$maxBytes;}" +
        "return typeof d.url==='string'||typeof d.text==='string'||typeof d.title==='string';}" +
        "function err(name,msg){var e;try{e=new DOMException(msg,name);}catch(x){e=new Error(msg);e.name=name;}return e;}" +
        "function settle(id,name){var p=pending[id];if(!p)return;delete pending[id];if(active===id)active=null;" +
        "if(name)p.rej(err(name,'Share failed'));else p.res();}" +
        "Object.defineProperty(window,'__raftShareSettle',{value:settle,writable:false,configurable:false});" +
        "Object.defineProperty(window,'__raftShareAck',{value:function(id){var p=pending[id];" +
        "if(p&&p.next){var f=p.next;p.next=null;f();}},writable:false,configurable:false});" +
        "n.canShare=canShare;" +
        "n.share=function(d){return new Promise(function(res,rej){" +
        "if(active!==null){rej(err('InvalidStateError','A share is already in progress'));return;}" +
        "var ua=n.userActivation;if(ua&&ua.isActive===false){rej(err('NotAllowedError','Sharing needs a user gesture'));return;}" +
        "if(!canShare(d)){rej(new TypeError('Cannot share this data'));return;}" +
        "var p=port(),id='s'+(++seq)+'_'+Date.now(),files=d.files?Array.prototype.slice.call(d.files):[];" +
        "var entry={res:res,rej:rej,next:null};pending[id]=entry;active=id;" +
        "function post(o){o.s=id;try{p.postMessage(JSON.stringify(o));return true;}catch(x){settle(id,'DataError');return false;}}" +
        "if(!post({k:'share-start',title:typeof d.title==='string'?d.title:'',text:typeof d.text==='string'?d.text:''," +
        "url:typeof d.url==='string'?d.url:'',files:files.map(function(f){return{name:f.name||'',type:f.type||'',size:f.size};})}))return;" +
        "var fi=0,off=0,ci=0,size=393216;" +
        "function next(){if(pending[id]!==entry)return;if(fi>=files.length){post({k:'share-end'});return;}var f=files[fi];" +
        "if(off>=f.size){fi++;off=0;ci=0;next();return;}" +
        "var fr=new FileReader();fr.onload=function(){if(pending[id]!==entry)return;var s=String(fr.result);var c=s.lastIndexOf(',');" +
        "entry.next=function(){ci++;off+=size;next();};" +
        "post({k:'share-chunk',f:fi,i:ci,d:c>=0?s.substring(c+1):''});};" +
        "fr.onerror=function(){if(pending[id]!==entry)return;post({k:'share-error'});settle(id,'DataError');};" +
        "fr.readAsDataURL(f.slice(off,Math.min(off+size,f.size)));}next();});};})();"

/** Settles the page's `navigator.share` Promise [id]: resolved when [errorName] is null, else rejected with it. */
internal fun raftWebViewShareSettleScript(id: String, errorName: String?): String =
    "(function(){var s=window.__raftShareSettle;if(s)s(${JSONObject.quote(id)},${if (errorName == null) "null" else JSONObject.quote(errorName)});})();"

/** Tells the page's share [id] that its last chunk is written, so it reads and posts the next. */
internal fun raftWebViewShareAckScript(id: String): String =
    "(function(){var a=window.__raftShareAck;if(a)a(${JSONObject.quote(id)});})();"

/** At most this many bytes in one share (the files together). */
internal const val RaftWebViewShareMaxBytes: Long = 100L * 1024L * 1024L

/** At most this many files in one share. */
internal const val RaftWebViewShareMaxFiles: Int = 64

/** At most this many shares being received at once (a page has one in flight; this bounds a page that posts by hand). */
internal const val RaftWebViewShareMaxPending: Int = 4

/** A share whose page has posted nothing for this long is given up (`AbortError`). */
internal const val RaftWebViewShareIdleTimeoutMs: Long = 2L * 60L * 1_000L

/** One received file: its safe on-disk [name] (unique in the share), the page's type as given, its byte count. */
internal class RaftWebViewShareFile(val name: String, val mimeType: String, val size: Long)

internal class RaftWebViewShareRequest(
    val id: String,
    val title: String,
    val text: String,
    val url: String,
    val files: List<RaftWebViewShareFile>,
)

/** What the app does after a share message ([RaftWebViewShareReceiver.accept]). */
internal sealed interface RaftWebViewShareSignal {
    /** The chunk is written; the page may post the next ([raftWebViewShareAckScript]). */
    data class Ack(val id: String) : RaftWebViewShareSignal

    /** Every file is complete in the sink; the share sheet can open. */
    class Ready(val request: RaftWebViewShareRequest) : RaftWebViewShareSignal

    /** The share is over; the page's Promise is to be rejected with [errorName]. */
    data class Failed(val id: String, val errorName: String, val reason: String) : RaftWebViewShareSignal
}

/**
 * Where a share's bytes go as they arrive (Android: files in a staging folder). Calls come from the
 * receiver's own thread, in message order; a throw fails the share (`DataError`).
 */
internal interface RaftWebViewShareSink {
    /** A new share [id] with these files (safe, unique names), all empty so far. */
    fun open(id: String, fileNames: List<String>)

    /** The next [bytes] of file [fileIndex] of share [id]. */
    fun append(id: String, fileIndex: Int, bytes: ByteArray)

    /** Share [id] failed or was given up: drop what was written. Unknown ids are fine. */
    fun discard(id: String)
}

/**
 * Whether a port message is part of a share rather than a download read. Exact and constant-time
 * (the message may be a 500 KB chunk on the UI thread): the shim serialises every message with its
 * kind `k` first (`{"k":"share-chunk",...}`), and a download read's messages never start so, whatever
 * names or bytes they carry. [RaftWebViewShareReceiver.accept] still reads the parsed kind.
 */
internal fun raftWebViewIsShareMessage(raw: String): Boolean = raw.startsWith(RaftWebViewShareMessagePrefix)

private const val RaftWebViewShareMessagePrefix = "{\"k\":\"share-"

/** A share id as the shim makes it (`s3_1700000000000`): letters, digits and `_`, never empty. */
internal fun raftWebViewShareIdIsValid(id: String): Boolean =
    id.length in 1..64 && id.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' }

/**
 * Receives the page's share messages, per share id: the files announced at `share-start`, their
 * chunks in order and to their announced sizes, under [maxBytes] together. Each decoded chunk goes
 * straight to [sink]; no file is held in memory. Not thread-safe: one thread (not the UI thread; a
 * chunk is parsed and decoded here), messages in the order the page posted them.
 *
 * Every share that starts ends in exactly one [RaftWebViewShareSignal.Ready] or
 * [RaftWebViewShareSignal.Failed] (or the page's own `share-error`, which it has settled itself),
 * so the page's Promise always settles: a bad message fails it, one evicted by newer shares or
 * silent for [idleTimeoutMs] ([expire]) or still open at [abortAll] is aborted.
 */
internal class RaftWebViewShareReceiver(
    private val sink: RaftWebViewShareSink,
    private val nowMillis: () -> Long,
    private val maxBytes: Long = RaftWebViewShareMaxBytes,
    private val maxFiles: Int = RaftWebViewShareMaxFiles,
    private val idleTimeoutMs: Long = RaftWebViewShareIdleTimeoutMs,
) {
    private class Pending(
        val title: String,
        val text: String,
        val url: String,
        val names: List<String>,
        val types: List<String>,
        val sizes: List<Long>,
        var lastMessageAt: Long,
    ) {
        val received = LongArray(sizes.size)
        val nextChunk = IntArray(sizes.size)
        var nextFile = 0
    }

    private val pending = LinkedHashMap<String, Pending>()

    val pendingCount: Int get() = pending.size

    /** The signals [raw] leads to (often none), or null when it is not a share message at all. */
    fun accept(raw: String): List<RaftWebViewShareSignal>? {
        val message = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val kind = message.optString("k")
        if (!kind.startsWith("share-")) return null
        val now = nowMillis()
        val out = ArrayList<RaftWebViewShareSignal>()
        expireInto(now, out)
        val id = message.optString("s")
        if (id.isBlank()) return out
        if (!raftWebViewShareIdIsValid(id)) {
            // Never a folder or a key; the page (if it is waiting on this id at all) is told.
            if (id.length <= 128) out += RaftWebViewShareSignal.Failed(id, "DataError", "id")
            return out
        }
        when (kind) {
            "share-start" -> start(id, message, now, out)
            "share-chunk" -> chunk(id, message, now, out)
            "share-end" -> {
                val share = pending.remove(id) ?: return out
                if (share.sizes.indices.any { share.received[it] != share.sizes[it] }) {
                    discard(id)
                    out += RaftWebViewShareSignal.Failed(id, "DataError", "size_mismatch")
                } else {
                    val files =
                        share.sizes.indices.map { RaftWebViewShareFile(share.names[it], share.types[it], share.sizes[it]) }
                    out += RaftWebViewShareSignal.Ready(RaftWebViewShareRequest(id, share.title, share.text, share.url, files))
                }
            }
            "share-error" -> if (pending.remove(id) != null) discard(id)
        }
        return out
    }

    /** Gives up the shares whose page has been silent for [idleTimeoutMs]. */
    fun expire(): List<RaftWebViewShareSignal> = ArrayList<RaftWebViewShareSignal>().also { expireInto(nowMillis(), it) }

    /** Gives up every share still being received (the page is going away). */
    fun abortAll(reason: String = "closed"): List<RaftWebViewShareSignal> {
        val ids = pending.keys.toList()
        pending.clear()
        return ids.map { id ->
            discard(id)
            RaftWebViewShareSignal.Failed(id, "AbortError", reason)
        }
    }

    private fun expireInto(now: Long, out: MutableList<RaftWebViewShareSignal>) {
        val expired = pending.filter { (_, share) -> now - share.lastMessageAt > idleTimeoutMs }.keys.toList()
        expired.forEach { id ->
            pending.remove(id)
            discard(id)
            out += RaftWebViewShareSignal.Failed(id, "AbortError", "expired")
        }
    }

    private fun start(id: String, message: JSONObject, now: Long, out: MutableList<RaftWebViewShareSignal>) {
        if (pending.containsKey(id)) return fail(id, "restart", out)
        val files = message.optJSONArray("files")
        val count = files?.length() ?: 0
        if (count > maxFiles) return fail(id, "too_many_files", out)
        val names = ArrayList<String>(count)
        val types = ArrayList<String>(count)
        val sizes = ArrayList<Long>(count)
        val used = HashSet<String>()
        var total = 0L
        for (index in 0 until count) {
            val file = files?.optJSONObject(index) ?: return fail(id, "file_meta", out)
            val size = file.optLong("size", -1L)
            if (size < 0L) return fail(id, "file_meta", out)
            // Page-supplied sizes: each one bounded on its own, and the sum kept from wrapping.
            if (size > maxBytes || size > maxBytes - total) return fail(id, "too_large", out)
            total += size
            val type = file.optString("type")
            names += raftWebViewShareFileName(file.optString("name"), type, used)
            types += type
            sizes += size
        }
        while (pending.size >= RaftWebViewShareMaxPending) {
            val oldest = pending.keys.first()
            pending.remove(oldest)
            discard(oldest)
            out += RaftWebViewShareSignal.Failed(oldest, "AbortError", "evicted")
        }
        if (count > 0 && runCatching { sink.open(id, names) }.isFailure) return fail(id, "stage_open", out)
        pending[id] =
            Pending(message.optString("title"), message.optString("text"), message.optString("url"), names, types, sizes, now)
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun chunk(id: String, message: JSONObject, now: Long, out: MutableList<RaftWebViewShareSignal>) {
        val share = pending[id] ?: return
        share.lastMessageAt = now
        val file = message.optInt("f", -1)
        if (file < share.nextFile || file >= share.sizes.size) return fail(id, "chunk_order", out)
        // Files are read one after another; an empty one sends no chunk.
        if (file > share.nextFile && (share.nextFile until file).any { share.received[it] != share.sizes[it] }) {
            return fail(id, "chunk_order", out)
        }
        share.nextFile = file
        if (message.optInt("i", -1) != share.nextChunk[file]) return fail(id, "chunk_order", out)
        val data = message.optString("d")
        val remaining = share.sizes[file] - share.received[file]
        // Base64 of n characters is at least n / 4 * 3 - 2 bytes: too much is refused before it is decoded.
        if (data.length.toLong() / 4L * 3L - 2L > remaining) return fail(id, "size_mismatch", out)
        val bytes = runCatching { Base64.decode(data) }.getOrNull() ?: return fail(id, "chunk_decode", out)
        if (bytes.isEmpty()) return fail(id, "chunk_decode", out)
        if (bytes.size > remaining) return fail(id, "size_mismatch", out)
        if (runCatching { sink.append(id, file, bytes) }.isFailure) return fail(id, "stage_write", out)
        share.received[file] += bytes.size.toLong()
        share.nextChunk[file]++
        out += RaftWebViewShareSignal.Ack(id)
    }

    private fun fail(id: String, reason: String, out: MutableList<RaftWebViewShareSignal>) {
        pending.remove(id)
        discard(id)
        out += RaftWebViewShareSignal.Failed(id, "DataError", reason)
    }

    private fun discard(id: String) {
        runCatching { sink.discard(id) }
    }
}

/**
 * The on-disk name of a shared file: the page's [name] as one safe path component
 * ([RaftShareFileName.sanitize]: no path, no control or reserved characters, at most
 * [RaftShareFileName.MAX_UTF8_BYTES] bytes; `download` with the type's extension when the page gave
 * none), made unique among the share's files ([used]).
 */
internal fun raftWebViewShareFileName(name: String, mimeType: String, used: MutableSet<String>): String =
    raftWebViewShareUniqueName(
        RaftShareFileName.sanitize(raftWebViewDownloadFileName(name.takeIf { it.isNotBlank() }, null, "", mimeType), null),
        used,
    )

/**
 * [name], or `name (2).ext`, ... when another file of the same share already has it (compared
 * without case, as some filesystems do), still within [RaftShareFileName.MAX_UTF8_BYTES].
 */
internal fun raftWebViewShareUniqueName(name: String, used: MutableSet<String>): String {
    val safe = RaftShareFileName.sanitize(name, null)
    if (used.add(safe.lowercase())) return safe
    val dot = safe.lastIndexOf('.').takeIf { it > 0 && safe.length - it <= 17 } ?: safe.length
    val stem = safe.substring(0, dot)
    val extension = safe.substring(dot)
    var index = 2
    while (true) {
        val suffix = " ($index)$extension"
        val candidate =
            raftWebViewShareUtf8Prefix(stem, RaftShareFileName.MAX_UTF8_BYTES - suffix.encodeToByteArray().size) + suffix
        if (used.add(candidate.lowercase())) return candidate
        index++
    }
}

/** The longest prefix of [text] within [maxBytes] of UTF-8, no surrogate pair split. */
private fun raftWebViewShareUtf8Prefix(text: String, maxBytes: Int): String {
    if (text.encodeToByteArray().size <= maxBytes) return text
    var used = 0
    var index = 0
    while (index < text.length) {
        val step = if (text[index].isHighSurrogate() && index + 1 < text.length) 2 else 1
        val bytes = text.substring(index, index + step).encodeToByteArray().size
        if (used + bytes > maxBytes) break
        used += bytes
        index += step
    }
    return text.substring(0, index)
}

/**
 * The type of the share intent: the files' one type, else their one top-level type (`image/` + `*`),
 * else any type. [mimeTypes] are already normalised (`type/subtype`, no parameters).
 */
internal fun raftWebViewShareIntentType(mimeTypes: List<String>): String {
    val distinct = mimeTypes.distinct()
    distinct.singleOrNull()?.let { return it }
    val major = distinct.map { it.substringBefore('/') }.distinct().singleOrNull()?.takeIf { it.isNotBlank() && it != "*" }
    return if (major != null) "$major/*" else "*/*"
}

/** The folder under the app's cache that shares are staged in; the attachment share's own (`slock-share-files`). */
internal const val RaftWebViewShareStagingDirName: String = "slock-share-files"

/** The staging lifetime rules of the attachment share (`sweepAndroidAttachmentShareStaging`), which sweeps the same folder. */
internal const val RaftWebViewShareStagingMaxAgeMs: Long = 24L * 60L * 60L * 1_000L
internal const val RaftWebViewShareStagingMaxEntries: Int = 16

private val RaftWebViewShareStagingEntryPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

/** Whether [name] is a share entry of the staging folder: a lower-case UUID, as the attachment share names its own. */
internal fun raftWebViewShareStagingIsEntryName(name: String): Boolean = name.matches(RaftWebViewShareStagingEntryPattern)

/**
 * The staging entries (name to last-modified) to remove before a new share is staged, by the
 * attachment share's rules: those older than [maxAgeMs] and all but the newest [maxEntries]. A
 * receiver may keep reading a granted URI after the chooser returns, so a fresh entry stays. Names
 * that are not share entries are left to the attachment sweep.
 */
internal fun raftWebViewShareStaleStagingEntries(
    entries: List<Pair<String, Long>>,
    nowMillis: Long,
    maxAgeMs: Long = RaftWebViewShareStagingMaxAgeMs,
    maxEntries: Int = RaftWebViewShareStagingMaxEntries,
): List<String> {
    val own = entries.filter { raftWebViewShareStagingIsEntryName(it.first) }
    val (kept, expired) = own.partition { (nowMillis - it.second).coerceAtLeast(0L) <= maxAgeMs }
    val overflow = kept.sortedByDescending { it.second }.drop(maxEntries.coerceAtLeast(0))
    return (expired + overflow).map { it.first }
}

/** The text an Android text-only share sends: title, text and url, each once, on their own lines. */
internal fun raftWebViewShareText(title: String, text: String, url: String): String =
    listOf(text, url).filter { it.isNotBlank() }.distinct().joinToString("\n").ifBlank { title }
