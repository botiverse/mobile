package build.raft.app.core.data

import android.content.Context
import android.net.Uri
import build.raft.app.data.SlockPlatformFileReadResult
import build.raft.app.data.SlockPlatformFileResolver
import build.raft.app.data.SlockSharedPlatformFileResolverProvider
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class AndroidPlatformFileResolver(
    context: Context
) : SlockPlatformFileResolver {
    private val appContext = context.applicationContext

    override suspend fun readFile(fileRef: String): SlockPlatformFileReadResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val uri = Uri.parse(fileRef)
                val bytes =
                    openFileRefStream(uri)
                        .use { input -> input.readBytes() }
                SlockPlatformFileReadResult(
                    code = 200,
                    bytes = bytes,
                    sizeBytes = bytes.size.toLong()
                )
            }.getOrElse { throwable ->
                SlockPlatformFileReadResult(
                    code = 400,
                    bytes = ByteArray(0),
                    errorCode = "attachment_read_failed",
                    message = throwable.message ?: "Unable to read file"
                )
            }
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
}

fun installAndroidAppScopePlatformFileResolver(context: Context) {
    val appContext = context.applicationContext
    SlockSharedPlatformFileResolverProvider.installAppScopeResolver {
        AndroidPlatformFileResolver(appContext)
    }
}
