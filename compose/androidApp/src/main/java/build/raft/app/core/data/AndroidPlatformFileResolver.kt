package build.raft.app.core.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import build.raft.app.data.SlockPlatformFileChunkSource
import build.raft.app.data.SlockPlatformFileReadResult
import build.raft.app.data.SlockPlatformFileResolver
import build.raft.app.data.SlockPlatformFilePersistResult
import build.raft.app.data.SlockPlatformFileStatResult
import build.raft.app.data.SlockSharedPlatformFileResolverProvider
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class AndroidPlatformFileResolver(
    context: Context
) : SlockPlatformFileResolver {
    private val appContext = context.applicationContext

    override suspend fun readFile(fileRef: String): SlockPlatformFileReadResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val bytes = openFileRefStream(Uri.parse(fileRef)).use { input -> input.readBytes() }
                SlockPlatformFileReadResult(code = 200, bytes = bytes, sizeBytes = bytes.size.toLong())
            }.getOrElse { throwable ->
                SlockPlatformFileReadResult(
                    code = 400,
                    bytes = ByteArray(0),
                    errorCode = "attachment_read_failed",
                    message = throwable.message ?: "Unable to read file"
                )
            }
        }

    override suspend fun statFile(fileRef: String): SlockPlatformFileStatResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val uri = Uri.parse(fileRef)
                openFileRefStream(uri).use { }
                val sizeBytes = fileRefSize(uri)
                    ?: throw IOException("Attachment size unavailable")
                SlockPlatformFileStatResult(
                    code = 200,
                    sizeBytes = sizeBytes
                )
            }.getOrElse { throwable ->
                SlockPlatformFileStatResult(
                    code = 400,
                    errorCode = "attachment_read_failed",
                    message = throwable.message ?: "Unable to read file"
                )
            }
        }

    override suspend fun persistImmutableFile(
        ownerKey: String,
        scopeKey: String,
        index: Int,
        bytes: ByteArray
    ): SlockPlatformFilePersistResult =
        withContext(Dispatchers.IO) {
            runCatching {
                require(ownerKey.isImmutableFeedbackKey())
                require(scopeKey.isImmutableFeedbackKey())
                require(index in 0..2)
                require(bytes.isNotEmpty())
                val directory = immutableFeedbackScopeDirectory(ownerKey, scopeKey).apply { mkdirs() }
                val target = File(directory, "$index.bin")
                if (target.isFile) {
                    require(target.readBytes().contentEquals(bytes)) { "Immutable feedback snapshot conflict" }
                } else {
                    val temporary = File(directory, ".$index-${System.nanoTime()}.tmp")
                    try {
                        temporary.outputStream().use { it.write(bytes) }
                        require(temporary.renameTo(target)) { "Unable to commit feedback snapshot" }
                    } finally {
                        temporary.delete()
                    }
                }
                SlockPlatformFilePersistResult(200, Uri.fromFile(target).toString())
            }.getOrElse { throwable ->
                SlockPlatformFilePersistResult(
                    code = 400,
                    errorCode = "immutable_file_persist_failed",
                    message = throwable.message ?: "Unable to persist feedback snapshot"
                )
            }
        }

    override suspend fun retireImmutableScope(ownerKey: String, scopeKey: String): Boolean =
        withContext(Dispatchers.IO) {
            if (!ownerKey.isImmutableFeedbackKey() || !scopeKey.isImmutableFeedbackKey()) return@withContext false
            immutableFeedbackScopeDirectory(ownerKey, scopeKey).deleteRecursively()
        }

    override suspend fun retireImmutableOwner(ownerKey: String): Boolean =
        withContext(Dispatchers.IO) {
            if (!ownerKey.isImmutableFeedbackKey()) return@withContext false
            File(immutableFeedbackRoot(), ownerKey).deleteRecursively()
        }

    override suspend fun retireAllImmutableOwners(): Boolean =
        withContext(Dispatchers.IO) { immutableFeedbackRoot().deleteRecursively() }

    override suspend fun openFileStream(
        fileRef: String,
        expectedSizeBytes: Long
    ): SlockPlatformFileChunkSource =
        withContext(Dispatchers.IO) {
            val uri = Uri.parse(fileRef)
            val input = openFileRefStream(uri)
            val actualSizeBytes = fileRefSize(uri)
            if (actualSizeBytes == null || actualSizeBytes != expectedSizeBytes) {
                input.close()
                throw IOException(
                    "Attachment size changed: expected=$expectedSizeBytes actual=${actualSizeBytes ?: "unknown"}"
                )
            }
            input.toSlockPlatformFileChunkSource()
        }

    private fun openFileRefStream(uri: Uri): java.io.InputStream {
        if (uri.toString().isBlank()) throw IOException("No readable file")
        return if (uri.scheme.equals("file", ignoreCase = true)) {
            val path = uri.path ?: throw IOException("No file path")
            File(path).takeIf { it.isFile }?.inputStream()
                ?: throw IOException("Unable to read file")
        } else {
            appContext.contentResolver.openInputStream(uri)
                ?: throw IOException("Unable to read file")
        }
    }

    private fun fileRefSize(uri: Uri): Long? =
        if (uri.scheme.equals("file", ignoreCase = true)) {
            uri.path?.let(::File)?.takeIf { it.isFile }?.length()?.takeIf { it > 0L }
        } else {
            appContext.contentResolver.openAssetFileDescriptor(uri, "r")
                ?.use { descriptor -> descriptor.length.takeIf { it > 0L } }
                ?: appContext.contentResolver
                    .query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                    ?.use { cursor ->
                        if (cursor.moveToFirst() && !cursor.isNull(0)) {
                            cursor.getLong(0).takeIf { it > 0L }
                        } else {
                            null
                        }
                    }
        }

    private fun immutableFeedbackRoot(): File = File(appContext.filesDir, "product-feedback-retry")

    private fun immutableFeedbackScopeDirectory(ownerKey: String, scopeKey: String): File =
        File(File(immutableFeedbackRoot(), ownerKey), scopeKey)
}

private fun String.isImmutableFeedbackKey(): Boolean =
    length == 64 && all { it in '0'..'9' || it in 'a'..'f' }

internal fun InputStream.toSlockPlatformFileChunkSource(): SlockPlatformFileChunkSource {
    val input = this
    return object : SlockPlatformFileChunkSource {
        override suspend fun read(maxBytes: Int): ByteArray =
            withContext(Dispatchers.IO) {
                var read: Int
                do {
                    val buffer = ByteArray(maxBytes)
                    read = input.read(buffer)
                    if (read < 0) return@withContext ByteArray(0)
                    if (read > 0) {
                        return@withContext if (read == buffer.size) buffer else buffer.copyOf(read)
                    }
                } while (read == 0)
                ByteArray(0)
            }

        override fun close() {
            runCatching { input.close() }
        }
    }
}

fun installAndroidAppScopePlatformFileResolver(context: Context) {
    val appContext = context.applicationContext
    SlockSharedPlatformFileResolverProvider.installAppScopeResolver {
        AndroidPlatformFileResolver(appContext)
    }
}
