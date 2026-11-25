package build.raft.webcontainer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** Hard memory cap for in-container `blob:` / `data:` / `WKDownload` byte transfers (200 MiB). */
const val WEB_CONTAINER_DOWNLOAD_MAX_BYTES: Long = 200L * 1024L * 1024L

/** Dedicated Android `WebMessageListener` port name for in-page `blob:`/`data:` download streaming. */
const val WEB_CONTAINER_DOWNLOAD_PORT_NAME: String = "raftContainerDownloadPort"

private const val MAX_DOWNLOAD_FILE_NAME_LENGTH: Int = 180
private val ContentDispositionStarRegex = Regex("""filename\*\s*=\s*([^;\r\n]+)""", RegexOption.IGNORE_CASE)
private val ContentDispositionQuotedRegex = Regex("""filename\s*=\s*"((?:\\.|[^"\\])*)"""", RegexOption.IGNORE_CASE)
private val ContentDispositionPlainRegex = Regex("""filename\s*=\s*([^;\r\n]+)""", RegexOption.IGNORE_CASE)

/** Sanitizes a proposed download filename into a safe single path segment, or `null` if unusable. */
fun sanitizeWebDownloadFileName(raw: String?): String? {
    val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val normalized =
        buildString(trimmed.length) {
            for (ch in trimmed) {
                when {
                    ch == '/' || ch == '\\' || ch == '\u0000' -> append('_')
                    ch.code < 0x20 || ch.code == 0x7F -> continue
                    else -> append(ch)
                }
            }
        }.trim { it.isWhitespace() || it == '.' }
    if (normalized.isEmpty() || normalized == "." || normalized == "..") return null
    return if (normalized.length <= MAX_DOWNLOAD_FILE_NAME_LENGTH) {
        normalized
    } else {
        normalized.substring(0, MAX_DOWNLOAD_FILE_NAME_LENGTH)
            .trimEnd { it.isWhitespace() || it == '.' }
            .takeIf { it.isNotEmpty() }
    }
}

/** Extracts a safe filename from an HTTP `Content-Disposition` header (`filename*=` then `filename=`). */
fun extractContentDispositionFileName(header: String?): String? {
    val raw = header?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val starMatch = ContentDispositionStarRegex.find(raw)?.groupValues?.getOrNull(1)?.trim()
    if (!starMatch.isNullOrEmpty()) {
        val unquoted = starMatch.trim('"', '\'')
        val encodedPart =
            if (unquoted.contains("''")) {
                unquoted.substringAfter("''")
            } else {
                unquoted
            }
        sanitizeWebDownloadFileName(percentDecodeUtf8(encodedPart))?.let { return it }
    }
    val quotedMatch = ContentDispositionQuotedRegex.find(raw)?.groupValues?.getOrNull(1)
    if (!quotedMatch.isNullOrEmpty()) {
        val unescaped = quotedMatch.replace("\\\"", "\"").replace("\\\\", "\\")
        sanitizeWebDownloadFileName(unescaped)?.let { return it }
    }
    val plainMatch = ContentDispositionPlainRegex.find(raw)?.groupValues?.getOrNull(1)?.trim()?.trim('"', '\'')
    if (!plainMatch.isNullOrEmpty()) {
        sanitizeWebDownloadFileName(plainMatch)?.let { return it }
    }
    return null
}

/** Extracts a safe filename from the final non-empty path segment of an http(s) URL. */
fun extractUrlLastSegmentFileName(url: String): String? {
    val trimmed = url.trim()
    if (trimmed.isEmpty()) return null
    val lower = trimmed.lowercase()
    if (lower.startsWith("blob:") || lower.startsWith("data:")) return null
    val withoutQueryOrFragment = trimmed.substringBefore('#').substringBefore('?')
    val afterAuthority =
        if (withoutQueryOrFragment.contains("://")) {
            val rest = withoutQueryOrFragment.substringAfter("://")
            val slashIdx = rest.indexOf('/')
            if (slashIdx < 0) return null else rest.substring(slashIdx + 1)
        } else {
            withoutQueryOrFragment
        }
    val lastSegment = afterAuthority.trimEnd('/').substringAfterLast('/')
    if (lastSegment.isEmpty()) return null
    return sanitizeWebDownloadFileName(percentDecodeUtf8(lastSegment))
}

/**
 * Chooses a safe download filename: explicit page `download` attribute -> `Content-Disposition` ->
 * URL last segment -> `"download"`, appending a standard extension from [mimeType] when absent.
 */
fun resolveWebDownloadFileName(
    pageFileName: String?,
    contentDisposition: String?,
    url: String,
    mimeType: String?,
): String {
    val chosen =
        sanitizeWebDownloadFileName(pageFileName)
            ?: extractContentDispositionFileName(contentDisposition)
            ?: extractUrlLastSegmentFileName(url)
            ?: "download"
    if ('.' in chosen) return chosen
    val ext = inferDownloadExtensionFromMime(mimeType) ?: return chosen
    return "$chosen.$ext"
}

private fun inferDownloadExtensionFromMime(mimeType: String?): String? =
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

private fun percentDecodeUtf8(raw: String): String {
    if ('%' !in raw) return raw
    val out = ArrayList<Byte>(raw.length)
    var i = 0
    while (i < raw.length) {
        val ch = raw[i]
        if (ch == '%' && i + 2 < raw.length) {
            val hi = raw[i + 1].digitToIntOrNull(16)
            val lo = raw[i + 2].digitToIntOrNull(16)
            if (hi != null && lo != null) {
                out.add(((hi shl 4) or lo).toByte())
                i += 3
                continue
            }
        }
        val encoded = ch.toString().encodeToByteArray()
        for (b in encoded) out.add(b)
        i += 1
    }
    return ByteArray(out.size) { idx -> out[idx] }.decodeToString()
}

/**
 * Document-start helper recording `<a download="name">` filenames before navigation triggers
 * Android's `DownloadListener` (which drops the HTML `download` attribute).
 */
internal const val WEB_CONTAINER_DOWNLOAD_NAME_HOOK_SCRIPT: String =
    "(function(){if(window.top!==window.self)return;if(window.__raftWcDownloadNames)return;var names={},order=[];" +
        "function keep(a){try{if(!a||typeof a.download!=='string'||!a.download||!a.href)return;" +
        "if(!(a.href in names))order.push(a.href);names[a.href]=a.download;" +
        "while(order.length>20){delete names[order.shift()];}}catch(_){}}" +
        "Object.defineProperty(window,'__raftWcDownloadNames',{value:names,writable:false,configurable:false});" +
        "var click=HTMLAnchorElement.prototype.click;" +
        "HTMLAnchorElement.prototype.click=function(){keep(this);return click.apply(this,arguments);};" +
        "document.addEventListener('click',function(e){var t=e.target;" +
        "var a=t&&t.closest?t.closest('a[download]'):null;keep(a);},true);})();"

/**
 * Reads an in-page `blob:` or `data:` download URL inside the trusted main document and streams
 * base64 chunks back over [WEB_CONTAINER_DOWNLOAD_PORT_NAME].
 */
internal fun buildWebContainerBlobReadScript(
    url: String,
    token: String,
    maxBytes: Long = WEB_CONTAINER_DOWNLOAD_MAX_BYTES,
): String {
    val urlLiteral = JsonPrimitive(url).toString()
    val tokenLiteral = JsonPrimitive(token).toString()
    return "(function(){var p=window.$WEB_CONTAINER_DOWNLOAD_PORT_NAME;if(!p)return;var u=$urlLiteral,t=$tokenLiteral;" +
        "function post(o){o.t=t;p.postMessage(JSON.stringify(o));}" +
        "var n=(window.__raftWcDownloadNames&&window.__raftWcDownloadNames[u])||(window.__raftDownloadNames&&window.__raftDownloadNames[u])||'';" +
        "fetch(u).then(function(r){if(!r.ok)throw new Error('status '+r.status);return r.blob();}).then(function(b){" +
        "if(b.size>$maxBytes){post({k:'error',m:'too_large'});return;}" +
        "post({k:'start',name:n,type:b.type||'',size:b.size});" +
        "var size=393216,i=0,off=0;" +
        "function next(){if(off>=b.size){post({k:'end',n:i});return;}" +
        "var fr=new FileReader();fr.onload=function(){var s=String(fr.result);var c=s.indexOf(',');" +
        "post({k:'chunk',i:i,d:c>=0?s.substring(c+1):''});i++;off+=size;next();};" +
        "fr.onerror=function(){post({k:'error',m:'read_failed'});};" +
        "fr.readAsDataURL(b.slice(off,Math.min(off+size,b.size)));}next();" +
        "}).catch(function(){post({k:'error',m:'blob_read_failed'});});})();"
}

/**
 * Reassembles chunked `blob:`/`data:` messages produced by [buildWebContainerBlobReadScript]
 * with strict chunk ordering, exact size verification, and a hard byte cap ([maxBytes]).
 */
internal class WebContainerBlobDownloadAssembler(
    private val token: String,
    private val url: String,
    private val maxBytes: Long = WEB_CONTAINER_DOWNLOAD_MAX_BYTES,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var name: String? = null
    private var mimeType: String? = null
    private var announced: Long = -1L
    private var nextIndex: Int = 0
    private val parts = ArrayList<ByteArray>()
    private var received: Long = 0L

    var finished: Boolean = false
        private set

    @OptIn(ExperimentalEncodingApi::class)
    fun accept(raw: String): WebDownloadResult? {
        if (finished) return null
        val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
        return accept(root)
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun accept(root: JsonObject): WebDownloadResult? {
        if (finished) return null
        val msgToken = (root["t"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        if (msgToken != token) return null
        val kind = (root["k"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return null
        return when (kind) {
            "start" -> {
                if (announced >= 0L) return fail("duplicate_start")
                name = (root["name"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }
                mimeType = (root["type"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }
                val size = (root["size"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: -1L
                if (size < 0L || size > maxBytes) {
                    fail(if (size > maxBytes) "too_large" else "invalid_size")
                } else {
                    announced = size
                    null
                }
            }
            "chunk" -> {
                val index = (root["i"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: -1
                if (announced < 0L || index != nextIndex) return fail("chunk_order")
                val encoded = (root["d"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return fail("chunk_decode")
                val remaining = minOf(announced, maxBytes) - received
                val maxEncodedChars = ((remaining + 2L) / 3L) * 4L
                if (encoded.length.toLong() > maxEncodedChars) return fail("too_large")
                val bytes = runCatching { Base64.decode(encoded) }.getOrNull() ?: return fail("chunk_decode")
                received += bytes.size
                if (received > announced || received > maxBytes) return fail("too_large")
                parts += bytes
                nextIndex += 1
                null
            }
            "end" -> {
                val count = (root["n"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: -1
                if (announced < 0L || received != announced || count != nextIndex) return fail("size_mismatch")
                finished = true
                val all = ByteArray(received.toInt())
                var offset = 0
                for (part in parts) {
                    part.copyInto(all, offset)
                    offset += part.size
                }
                parts.clear()
                WebDownloadResult.Bytes(
                    data = all,
                    fileName = resolveWebDownloadFileName(name, null, url, mimeType),
                    mimeType = mimeType,
                )
            }
            "error" -> {
                val reason = (root["m"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }
                fail(reason ?: "blob_read_failed")
            }
            else -> null
        }
    }

    private fun fail(reason: String): WebDownloadResult {
        finished = true
        parts.clear()
        return WebDownloadResult.Failed(reason)
    }
}
