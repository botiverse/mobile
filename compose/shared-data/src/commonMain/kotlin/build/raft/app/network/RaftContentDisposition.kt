package build.raft.app.network

import build.raft.app.RaftCoreInternalApi

import build.raft.app.data.percentDecodeStrict

/**
 * The file name a response's `Content-Disposition` gives (RFC 6266 with RFC 5987 / 8187
 * parameters): `filename*=UTF-8''<percent-encoded>` first (so a Chinese or other non-ASCII name
 * survives), then `filename="..."` / `filename=token`; null when the header has neither, or only
 * an unusable one. Path separators and control characters are not allowed in what is returned: a
 * name is one file, never a path (`a/b.txt` → `b.txt`).
 *
 * The server writes both forms (slock `packages/server/src/apps/artifacts/blobStore.ts`
 * `attachmentDisposition`): `attachment; filename="<ascii>"; filename*=UTF-8''<encoded>`.
 */
fun raftContentDispositionFileName(header: String?): String? {
    if (header.isNullOrBlank()) return null
    val params = raftContentDispositionParams(header)
    val extended =
        params["filename*"]?.let { value ->
            // ext-value = charset "'" [ language ] "'" value-chars; UTF-8 (any case) is the one the
            // server writes; ISO-8859-1 is the other charset RFC 5987 names. Anything else: skipped.
            val first = value.indexOf('\'')
            val second = if (first >= 0) value.indexOf('\'', first + 1) else -1
            if (first < 0 || second < 0) return@let null
            val charset = value.substring(0, first).trim().lowercase()
            val encoded = value.substring(second + 1)
            when (charset) {
                "utf-8" -> percentDecodeStrict(encoded)
                "iso-8859-1" -> raftLatin1PercentDecode(encoded)
                else -> null
            }
        }?.let(::raftSafeDownloadFileName)
    if (extended != null) return extended
    return params["filename"]?.let(::raftSafeDownloadFileName)
}

/**
 * The name a downloaded file is saved as: the response's `Content-Disposition`
 * ([raftContentDispositionFileName]) when it gives one, else the URL's last path segment
 * (percent-decoded, without its query or fragment), else [fallback].
 */
fun raftDownloadFileName(contentDisposition: String?, url: String, fallback: String = "download"): String =
    raftContentDispositionFileName(contentDisposition)
        ?: raftUrlLastSegmentFileName(url)
        ?: fallback

@RaftCoreInternalApi fun raftUrlLastSegmentFileName(url: String): String? {
    val path =
        url.substringBefore('#').substringBefore('?').let { withoutQuery ->
            val schemeEnd = withoutQuery.indexOf("://")
            if (schemeEnd < 0) withoutQuery else withoutQuery.substring(schemeEnd + 3).let { rest ->
                val slash = rest.indexOf('/')
                if (slash < 0) "" else rest.substring(slash)
            }
        }
    val segment = path.trimEnd('/').substringAfterLast('/')
    if (segment.isEmpty()) return null
    return raftSafeDownloadFileName(percentDecodeStrict(segment) ?: segment)
}

/** One file's name: the last part of any path, no control characters, not `.`/`..`; null when nothing is left. */
@RaftCoreInternalApi fun raftSafeDownloadFileName(raw: String): String? {
    val lastPart = raw.substringAfterLast('/').substringAfterLast('\\')
    val cleaned = lastPart.filter { it >= ' ' && it != '\u007f' }.trim()
    if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") return null
    return cleaned
}

/** `name=value` parameters after the disposition type, names lower-cased; quoted values unescaped. */
@RaftCoreInternalApi fun raftContentDispositionParams(header: String): Map<String, String> {
    val params = LinkedHashMap<String, String>()
    var index = header.indexOf(';').takeIf { it >= 0 } ?: return params
    index += 1
    val length = header.length
    while (index < length) {
        while (index < length && (header[index] == ' ' || header[index] == '\t' || header[index] == ';')) index++
        val nameStart = index
        while (index < length && header[index] != '=' && header[index] != ';') index++
        val name = header.substring(nameStart, index).trim().lowercase()
        if (index >= length || header[index] == ';') {
            continue
        }
        index++ // '='
        while (index < length && (header[index] == ' ' || header[index] == '\t')) index++
        val value: String
        if (index < length && header[index] == '"') {
            index++
            val builder = StringBuilder()
            while (index < length && header[index] != '"') {
                if (header[index] == '\\' && index + 1 < length) index++
                builder.append(header[index])
                index++
            }
            index++ // closing quote
            value = builder.toString()
            while (index < length && header[index] != ';') index++
        } else {
            val valueStart = index
            while (index < length && header[index] != ';') index++
            value = header.substring(valueStart, index).trim()
        }
        if (name.isNotEmpty() && name !in params) params[name] = value
    }
    return params
}

private fun raftLatin1PercentDecode(raw: String): String? {
    val builder = StringBuilder()
    var index = 0
    while (index < raw.length) {
        val char = raw[index]
        if (char == '%') {
            if (index + 2 > raw.lastIndex) return null
            val code = raw.substring(index + 1, index + 3).toIntOrNull(16) ?: return null
            builder.append(code.toChar())
            index += 3
        } else {
            builder.append(char)
            index++
        }
    }
    return builder.toString()
}

/** [raftDownloadFileName] for the iOS host (Objective-C: `SharedRaftDownloadFileNames.shared`). */
object RaftDownloadFileNames {
    fun fileName(contentDisposition: String?, url: String, fallback: String): String =
        raftDownloadFileName(contentDisposition, url, fallback)
}
