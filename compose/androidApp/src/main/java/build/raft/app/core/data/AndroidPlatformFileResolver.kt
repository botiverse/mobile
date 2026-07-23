package build.raft.app.core.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import build.raft.app.data.SlockPlatformFileChunkSource
import build.raft.app.data.SlockPlatformFileReadResult
import build.raft.app.data.SlockPlatformFileResolver
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
}

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
