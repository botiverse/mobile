package build.raft.app.shared

import java.io.File
import java.net.URI

internal data class FilePreviewTemporaryFileRetirementResult(
    val requestedCount: Int,
    val admittedCount: Int,
    val deletedCount: Int
) {
    val rejectedCount: Int
        get() = requestedCount - admittedCount
}

/**
 * Deletes only regular files whose canonical path is a descendant of an app cache root.
 * Non-file URI schemes, malformed refs, directories and symlink escapes all fail closed.
 */
internal fun retireFilePreviewTemporaryFiles(
    cacheRoots: List<File>,
    fileRefs: List<String>,
    deleteFile: (File) -> Boolean = File::delete
): FilePreviewTemporaryFileRetirementResult {
    val canonicalRoots =
        cacheRoots
            .mapNotNull { root -> runCatching { root.canonicalFile }.getOrNull() }
            .distinctBy(File::getPath)
    val distinctRefs = fileRefs.map(String::trim).filter(String::isNotBlank).distinct()
    var admittedCount = 0
    var deletedCount = 0
    distinctRefs.forEach { fileRef ->
        val target = fileRef.toCacheOwnedTemporaryFile(canonicalRoots) ?: return@forEach
        admittedCount += 1
        if (runCatching { deleteFile(target) }.getOrDefault(false)) {
            deletedCount += 1
        }
    }
    return FilePreviewTemporaryFileRetirementResult(
        requestedCount = distinctRefs.size,
        admittedCount = admittedCount,
        deletedCount = deletedCount
    )
}

private fun String.toCacheOwnedTemporaryFile(canonicalRoots: List<File>): File? {
    val path =
        when {
            startsWith("/") -> this
            else ->
                runCatching { URI(this) }
                    .getOrNull()
                    ?.takeIf { uri -> uri.scheme.equals("file", ignoreCase = true) && uri.host.isNullOrBlank() }
                    ?.path
        }?.takeIf(String::isNotBlank) ?: return null
    val target = runCatching { File(path).canonicalFile }.getOrNull() ?: return null
    if (!target.isFile) return null
    val targetPath = target.path
    val insideCache =
        canonicalRoots.any { root ->
            targetPath.startsWith(root.path.trimEnd(File.separatorChar) + File.separator)
        }
    return target.takeIf { insideCache }
}
