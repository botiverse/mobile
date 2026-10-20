package build.raft.app.shared

import android.content.Context
import android.net.Uri
import build.raft.app.network.SharedBinaryDownloader
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking

internal data class RaftReleaseNotesDocument(
    val bytes: ByteArray,
    val mimeType: String,
    val source: String
)

/**
 * Release notes are treated as immutable per versionCode and URL. Disk hits are
 * served without background revalidation; if Hands ever supports editing an
 * already-published note for the same version, this is the single place to add
 * serve-from-disk plus async refresh semantics.
 */
internal class RaftReleaseNotesDocumentCache(
    context: Context
) {
    private val cacheDir = File(context.applicationContext.cacheDir, CACHE_DIR_NAME)

    fun loadForWebView(url: String): RaftReleaseNotesDocument? {
        if (!isReleaseNotesUrl(url)) return null
        read(url)?.let { return it.copy(source = "disk") }
        return fetchAndStore(url)
    }

    fun prefetch(url: String): Boolean {
        if (!isReleaseNotesUrl(url)) return false
        if (read(url) != null) return true
        return fetchAndStore(url) != null
    }

    private fun read(url: String): RaftReleaseNotesDocument? {
        val file = cacheFile(url)
        if (!file.isFile || file.length() <= 0L || file.length() > MAX_BYTES) return null
        return runCatching {
            RaftReleaseNotesDocument(
                bytes = file.readBytes(),
                mimeType = "text/html",
                source = "disk"
            )
        }.getOrNull()
    }

    private fun fetchAndStore(url: String): RaftReleaseNotesDocument? =
        runCatching {
            runBlocking {
                SharedBinaryDownloader.fetchBytes(url)
            }
        }.mapCatching { downloaded ->
            val bytes = downloaded.bytes.takeIf { it.isNotEmpty() && it.size <= MAX_BYTES }
                ?: return@mapCatching null
            val mimeType =
                downloaded.contentType
                    ?.substringBefore(';')
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: "text/html"
            if (!mimeType.equals("text/html", ignoreCase = true)) return@mapCatching null
            write(url, bytes)
            RaftReleaseNotesDocument(
                bytes = bytes,
                mimeType = mimeType,
                source = "network"
            )
        }.getOrNull()

    private fun write(url: String, bytes: ByteArray) {
        if (!cacheDir.exists() && !cacheDir.mkdirs()) return
        cleanup()
        val file = cacheFile(url)
        val tempFile = File(cacheDir, "${file.name}.part")
        runCatching {
            tempFile.writeBytes(bytes)
            if (file.exists()) file.delete()
            if (!tempFile.renameTo(file)) {
                tempFile.copyTo(file, overwrite = true)
                tempFile.delete()
            }
        }.onFailure {
            runCatching { tempFile.delete() }
        }
    }

    private fun cacheFile(url: String): File {
        val versionCode = releaseNotesVersionCode(url).orEmpty()
        return File(cacheDir, "${versionCode}_${sha256(url)}.html")
    }

    private fun cleanup() {
        cacheDir.listFiles()
            ?.filter { it.isFile }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_FILES)
            ?.forEach { file -> runCatching { file.delete() } }
    }

    companion object {
        private const val CACHE_DIR_NAME = "release_notes_cache"
        private const val MAX_BYTES = 256 * 1024
        private const val MAX_FILES = 8

        fun isReleaseNotesUrl(url: String): Boolean {
            val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
            return uri.scheme == "https" &&
                uri.host == "hands.build" &&
                uri.path?.startsWith("/notes/") == true &&
                releaseNotesVersionCode(url) != null
        }

        private fun releaseNotesVersionCode(url: String): String? =
            runCatching { Uri.parse(url).getQueryParameter("version_code") }
                .getOrNull()
                ?.takeIf { value -> value.isNotBlank() && value.all(Char::isDigit) }

        private fun sha256(value: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
        }
    }
}
