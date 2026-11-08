package build.raft.app.downloads

/**
 * The on-disk name of a file handed to another app (share sheet, Open with, Save to Files) or
 * taken in from one (picker upload).
 *
 * Receivers name and type the file from its last path component (iOS file URL, Android content
 * URI), so the file must be called by its display name: Unicode kept (发票.pdf stays 发票.pdf),
 * only path separators, control characters and characters that common filesystems reject are
 * replaced, no leading dots, an extension derived from the type when the name has none, and a
 * bounded UTF-8 length that keeps the extension. Android and iOS (`SharedRaftShareFileName`) share
 * this one implementation.
 */
object RaftShareFileName {
    const val FALLBACK_NAME: String = "attachment"
    const val MAX_UTF8_BYTES: Int = 200
    private const val MAX_EXTENSION_CHARS = 16
    private const val RESERVED = "<>:\"|?*"

    /** Types that say nothing about the content; the file extension is a better answer. */
    private val GenericMimeTypes =
        setOf(
            "application/octet-stream",
            "binary/octet-stream",
            "application/binary",
            "application/unknown",
            "application/x-download",
            "application/force-download",
            "application/download",
        )

    /** `type/subtype` lower-cased without parameters, or null when [mimeType] is not a type. */
    fun normalizedMimeType(mimeType: String?): String? =
        mimeType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
            ?.takeIf { type ->
                val slash = type.indexOf('/')
                slash > 0 && slash < type.length - 1 && type.indexOf('/', slash + 1) < 0 &&
                    type.none { it.isWhitespace() }
            }

    /** True when [mimeType] is missing, malformed or one of the generic "some bytes" types. */
    fun isGenericMimeType(mimeType: String?): Boolean {
        val normalized = normalizedMimeType(mimeType) ?: return true
        return normalized in GenericMimeTypes
    }

    /**
     * The type a receiver should see: [mimeType] normalised, or — when that is generic — the type
     * [typeForExtension] gives for [displayName]'s extension, else `application/octet-stream`.
     */
    fun resolveMimeType(
        displayName: String,
        mimeType: String?,
        typeForExtension: (String) -> String?,
    ): String {
        val normalized = normalizedMimeType(mimeType)
        if (normalized != null && normalized !in GenericMimeTypes) return normalized
        val extension = extensionOf(displayName)?.lowercase()
        val fromExtension = extension?.let { runCatching { typeForExtension(it) }.getOrNull() }
        return normalizedMimeType(fromExtension) ?: normalized ?: "application/octet-stream"
    }

    /** The extension of [name] (without the dot), or null for none / a leading-dot-only name. */
    fun extensionOf(name: String): String? {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return null
        return name.substring(dot + 1)
    }

    /**
     * One safe path component for [displayName]. [fallbackExtension] (for example the extension of
     * the file's MIME type) is appended only when the cleaned name has no extension.
     */
    fun sanitize(displayName: String?, fallbackExtension: String?): String {
        val leaf = displayName.orEmpty().substringAfterLast('/').substringAfterLast('\\')
        val cleaned =
            buildString(leaf.length) {
                leaf.forEach { char ->
                    append(if (char.isISOControlChar() || char in RESERVED) '_' else char)
                }
            }
                .trim()
                .trimStart('.')
                .trimEnd('.', ' ')
        var name = cleaned.ifBlank { FALLBACK_NAME }
        if (extensionOf(name) == null) {
            val extension =
                fallbackExtension
                    ?.trim()
                    ?.trimStart('.')
                    ?.takeIf { ext -> ext.isNotEmpty() && ext.length <= MAX_EXTENSION_CHARS && ext.all { it.isLetterOrDigit() } }
            if (extension != null) name = "$name.$extension"
        }
        return truncateKeepingExtension(name, MAX_UTF8_BYTES)
    }

    private fun Char.isISOControlChar(): Boolean = this in '\u0000'..'\u001f' || this in '\u007f'..'\u009f'

    private fun utf8Size(text: String): Int = text.encodeToByteArray().size

    private fun truncateKeepingExtension(name: String, maxBytes: Int): String {
        if (utf8Size(name) <= maxBytes) return name
        val dot = name.lastIndexOf('.')
        val suffix = if (dot > 0 && name.length - dot <= MAX_EXTENSION_CHARS + 1) name.substring(dot) else ""
        val stem = if (suffix.isEmpty()) name else name.substring(0, dot)
        val budget = maxBytes - utf8Size(suffix)
        val out = StringBuilder()
        var used = 0
        var index = 0
        while (index < stem.length) {
            val step = if (stem[index].isHighSurrogate() && index + 1 < stem.length) 2 else 1
            val piece = stem.substring(index, index + step)
            val bytes = utf8Size(piece)
            if (used + bytes > budget) break
            out.append(piece)
            used += bytes
            index += step
        }
        return out.toString().trimEnd('.', ' ').ifEmpty { FALLBACK_NAME } + suffix
    }
}
