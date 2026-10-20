package build.raft.app.shared

import build.raft.app.ThreadComposerTemporaryFileRetirement
import build.raft.app.ThreadComposerTemporaryFileRetirementHandler
import build.raft.app.ThreadComposerTemporaryFileRetirementResult
import build.raft.app.core.data.diagnostics.RaftAppLog
import android.content.Context
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

internal data class FilePreviewTemporaryFileRetirementResult(
    val requestedCount: Int,
    val admittedCount: Int,
    val deletedCount: Int
) {
    val rejectedCount: Int
        get() = requestedCount - admittedCount
}

/**
 * Installs the Android physical-file owner for the shared draft persistence registry.
 *
 * The registry invokes this handler while its process-wide draft lock is held.  Consequently the
 * rename/quarantine phase is deliberately synchronous; only deletion of the exact sibling
 * tombstones is sent to the background executor.  Keeping this adapter in the Android host
 * module also means the common store never needs to know about Context, cache roots, or File.
 */
internal fun installAndroidThreadComposerTemporaryFileRetirement(context: Context) {
    val appContext = context.applicationContext
    ThreadComposerTemporaryFileRetirement.install(
        ThreadComposerTemporaryFileRetirementHandler { sessionId, fileRefs ->
            val quarantine =
                quarantineFilePreviewTemporaryFiles(
                    cacheRoots = listOfNotNull(appContext.cacheDir, appContext.externalCacheDir),
                    fileRefs = fileRefs.toList()
                )
            if (quarantine.quarantineFiles.isNotEmpty()) {
                RaftThreadAdapter.execute("file-preview-temp-tombstone-delete") {
                    quarantine.quarantineFiles.forEach { tombstone ->
                        runCatching { tombstone.delete() }
                    }
                }
            }
            val failed = quarantine.failedFileRefs
            ThreadComposerTemporaryFileRetirementResult(
                sessionId = sessionId,
                requestedFileRefs = quarantine.requestedFileRefs,
                admittedFileRefs = quarantine.quarantinedFileRefs + failed,
                quarantinedFileRefs = quarantine.quarantinedFileRefs,
                rejectedFileRefs = quarantine.rejectedFileRefs,
                failedFileRefs = failed,
                tombstoneByFileRef = quarantine.tombstoneByFileRef.mapValues { (_, file) -> file.toURI().toString() },
                errorCode = failed.takeIf { it.isNotEmpty() }?.let {
                    ThreadComposerTemporaryFileRetirementResult.HandlerFailureCode
                }
            )
        }
    )
    RaftAppLog.record("SlockFilePreviewCleanup", "android_retirement_handler_installed")
}

/**
 * Result of the synchronous ownership transition.  A ref is retired only after its original
 * path has been atomically moved to a unique sibling tombstone.  The tombstones are deliberately
 * returned to the host so their byte deletion can happen off the renderer/context thread.
 */
internal data class FilePreviewTemporaryFileQuarantineResult(
    val requestedFileRefs: Set<String>,
    val quarantinedFileRefs: Set<String>,
    val rejectedFileRefs: Set<String>,
    val failedFileRefs: Set<String>,
    val tombstoneByFileRef: Map<String, File>
) {
    val requestedCount: Int
        get() = requestedFileRefs.size

    val admittedCount: Int
        get() = quarantinedFileRefs.size + failedFileRefs.size

    val quarantinedCount: Int
        get() = quarantinedFileRefs.size

    val rejectedCount: Int
        get() = rejectedFileRefs.size

    val failedCount: Int
        get() = failedFileRefs.size

    val quarantineFiles: List<File>
        get() = tombstoneByFileRef.values.toList()
}

private data class FilePreviewCacheRoot(
    val admittedPaths: List<Path>,
    val canonicalPath: Path
)

/**
 * Deletes only regular files whose canonical path is a descendant of an app cache root.
 * Non-file URI schemes, malformed refs, directories and symlink escapes all fail closed.
 */
internal fun retireFilePreviewTemporaryFiles(
    cacheRoots: List<File>,
    fileRefs: List<String>,
    deleteFile: (File) -> Boolean = File::delete
): FilePreviewTemporaryFileRetirementResult {
    val quarantined = quarantineFilePreviewTemporaryFiles(cacheRoots, fileRefs)
    var deletedCount = 0
    quarantined.quarantineFiles.forEach { tombstone ->
        if (runCatching { deleteFile(tombstone) }.getOrDefault(false)) {
            deletedCount += 1
        }
    }
    return FilePreviewTemporaryFileRetirementResult(
        requestedCount = quarantined.requestedCount,
        admittedCount = quarantined.admittedCount,
        deletedCount = deletedCount
    )
}

/**
 * Synchronously transitions cache-owned refs out of their public path.  This is the primitive
 * used by the renderer bridge: it must complete before the shared draft ownership lock is
 * released.  Deleting the returned tombstones is intentionally a separate, asynchronous step.
 *
 * The move is required to be atomic and same-directory.  A failed move never falls back to
 * unlinking the original path; that fail-closed rule is what prevents a delayed cleanup from
 * deleting a newer owner which reused the old pathname.
 */
internal fun quarantineFilePreviewTemporaryFiles(
    cacheRoots: List<File>,
    fileRefs: List<String>,
    moveToQuarantine: (File) -> File? = ::atomicQuarantineMove
): FilePreviewTemporaryFileQuarantineResult {
    val roots = cacheRoots.mapNotNull(File::toFilePreviewCacheRoot).distinctBy { it.canonicalPath }
    val requestedRefs =
        fileRefs
            .map(String::trim)
            .filter(String::isNotBlank)
            .toCollection(linkedSetOf())
    val quarantinedRefs = linkedSetOf<String>()
    val rejectedRefs = linkedSetOf<String>()
    val failedRefs = linkedSetOf<String>()
    val tombstoneByRef = linkedMapOf<String, File>()
    requestedRefs.forEach { fileRef ->
        val target = fileRef.toCacheOwnedTemporaryFile(roots)
        if (target == null) {
            rejectedRefs += fileRef
            return@forEach
        }
        val tombstone = runCatching { moveToQuarantine(target) }.getOrNull()
        if (tombstone != null) {
            quarantinedRefs += fileRef
            tombstoneByRef[fileRef] = tombstone
        } else {
            failedRefs += fileRef
        }
    }
    return FilePreviewTemporaryFileQuarantineResult(
        requestedFileRefs = requestedRefs,
        quarantinedFileRefs = quarantinedRefs,
        rejectedFileRefs = rejectedRefs,
        failedFileRefs = failedRefs,
        tombstoneByFileRef = tombstoneByRef
    )
}

private fun atomicQuarantineMove(source: File): File? {
    val parent = source.parentFile ?: return null
    if (!readAttributesNoFollow(source.toPath()).isRegularFile) return null
    repeat(4) {
        val tombstone = File(parent, ".${source.name}.retired-${UUID.randomUUID()}")
        // Do not replace an existing tombstone.  UUID collisions are extraordinarily unlikely,
        // but treating one as a failed admission keeps the operation fail-closed.
        if (Files.exists(tombstone.toPath(), LinkOption.NOFOLLOW_LINKS)) return@repeat
        return try {
            Files.move(source.toPath(), tombstone.toPath(), StandardCopyOption.ATOMIC_MOVE)
            tombstone
        } catch (_: Throwable) {
            null
        }
    }
    return null
}

private fun File.toFilePreviewCacheRoot(): FilePreviewCacheRoot? {
    val absolutePath = runCatching { toPath().toAbsolutePath().normalize() }.getOrNull() ?: return null
    val rootAttributes = readAttributesNoFollow(absolutePath)
    if (!rootAttributes.isDirectory || rootAttributes.isSymbolicLink) return null
    val canonicalPath = runCatching { canonicalFile.toPath().toAbsolutePath().normalize() }.getOrNull() ?: return null
    val canonicalAttributes = readAttributesNoFollow(canonicalPath)
    if (!canonicalAttributes.isDirectory || canonicalAttributes.isSymbolicLink) return null
    return FilePreviewCacheRoot(
        admittedPaths = listOf(absolutePath, canonicalPath).distinct(),
        canonicalPath = canonicalPath
    )
}

private fun String.toCacheOwnedTemporaryFile(roots: List<FilePreviewCacheRoot>): File? {
    if (any(Char::isISOControl)) return null
    val path =
        when {
            startsWith("/") -> this
            else ->
                runCatching { URI(this) }
                    .getOrNull()
                    ?.takeIf { uri ->
                        uri.scheme.equals("file", ignoreCase = true) &&
                            uri.host.isNullOrBlank() &&
                            uri.userInfo == null &&
                            uri.query == null &&
                            uri.fragment == null &&
                            !uri.isOpaque
                    }
                    ?.path
        }?.takeIf(String::isNotBlank) ?: return null
    if (path.any(Char::isISOControl)) return null
    val rawPath = runCatching { File(path).toPath() }.getOrNull() ?: return null
    if (!rawPath.isAbsolute || rawPath.any { segment -> segment.toString() == "." || segment.toString() == ".." }) {
        return null
    }
    val absolutePath = rawPath.toAbsolutePath().normalize()
    for (root in roots) {
        for (admittedRoot in root.admittedPaths) {
            if (absolutePath == admittedRoot || !absolutePath.startsWith(admittedRoot)) continue
            val relativePath = admittedRoot.relativize(absolutePath)
            if (relativePath.nameCount == 0) continue
            var cursor = admittedRoot
            var valid = true
            for (index in 0 until relativePath.nameCount) {
                val segment = relativePath.getName(index)
                val component = segment.toString()
                if (component.isBlank() || component == "." || component == "..") {
                    valid = false
                    break
                }
                cursor = cursor.resolve(segment)
                val attributes = readAttributesNoFollow(cursor)
                val finalComponent = index == relativePath.nameCount - 1
                if (attributes.isSymbolicLink ||
                    (finalComponent && !attributes.isRegularFile) ||
                    (!finalComponent && !attributes.isDirectory)
                ) {
                    valid = false
                    break
                }
            }
            if (!valid) continue
            val canonicalTarget =
                runCatching { cursor.toFile().canonicalFile.toPath().toAbsolutePath().normalize() }
                    .getOrNull()
                    ?: continue
            if (canonicalTarget.startsWith(root.canonicalPath) && canonicalTarget != root.canonicalPath) {
                // Preserve the validated raw pathname.  Renaming a canonicalized target would
                // reintroduce the very symlink/alias ambiguity the component walk rejected.
                return cursor.toFile()
            }
        }
    }
    return null
}

private fun readAttributesNoFollow(path: Path): BasicFileAttributes =
    try {
        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    } catch (_: Throwable) {
        MissingFilePreviewAttributes
    }

private val MissingFilePreviewAttributes =
    object : BasicFileAttributes {
        override fun lastModifiedTime() = java.nio.file.attribute.FileTime.fromMillis(0L)
        override fun lastAccessTime() = java.nio.file.attribute.FileTime.fromMillis(0L)
        override fun creationTime() = java.nio.file.attribute.FileTime.fromMillis(0L)
        override fun isRegularFile() = false
        override fun isDirectory() = false
        override fun isSymbolicLink() = false
        override fun isOther() = false
        override fun size() = 0L
        override fun fileKey(): Any? = null
    }
