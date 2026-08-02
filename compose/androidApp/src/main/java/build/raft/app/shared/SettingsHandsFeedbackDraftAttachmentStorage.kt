package build.raft.app.shared

import java.io.File
import java.io.InputStream
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

internal data class SettingsHandsFeedbackDraftAttachmentSource(
    val id: String,
    val fileName: String,
    val mimeType: String,
    val fileRef: String
)

internal data class SettingsHandsFeedbackDraftAttachment(
    val id: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val fileRef: String,
    val previewUri: String
)

internal class SettingsHandsFeedbackDraftAttachmentStorage(
    private val rootDirectory: File,
    private val openSource: (String) -> InputStream
) {
    fun promoteGroup(
        accountId: String,
        groupId: String,
        sources: List<SettingsHandsFeedbackDraftAttachmentSource>,
        maxBytes: Long
    ): List<SettingsHandsFeedbackDraftAttachment> {
        require(groupId.isNotBlank() && sources.isNotEmpty() && sources.size <= MaxAttachments)
        require(maxBytes > 0L)
        val accountDirectory = File(rootDirectory, stablePathSegment(accountId.ifBlank { "anonymous" }))
        requireDirectory(accountDirectory)
        cleanupStagingDirectories(accountDirectory)
        val groupDirectory = File(accountDirectory, safePathSegment(groupId).ifBlank { "group" })
        existingGroup(groupDirectory, sources, maxBytes)?.let { return it }

        val stagingDirectory =
            File(accountDirectory, ".${groupDirectory.name}-${UUID.randomUUID().toString().lowercase(Locale.US)}")
        requireDirectory(stagingDirectory)
        var committed = false
        try {
            val staged =
                sources.mapIndexed { index, source ->
                    require(source.id.isNotBlank() && source.fileRef.isNotBlank())
                    val target = File(stagingDirectory, targetFileName(index, source.fileName, source.mimeType))
                    val copied =
                        openSource(source.fileRef).use { input ->
                            target.outputStream().use { output ->
                                HandsFeedbackAttachmentPolicy.copyBounded(input, output, maxBytes)
                            }
                        }
                    if (!target.isFile || !target.canRead() || target.length() != copied || copied <= 0L) {
                        throw FeedbackAttachmentReadException("Unable to promote feedback attachment")
                    }
                    source.toDraftAttachment(target, copied)
                }
            if (groupDirectory.exists() && !groupDirectory.deleteRecursively()) {
                throw FeedbackAttachmentReadException("Unable to replace feedback draft attachment group")
            }
            if (!stagingDirectory.renameTo(groupDirectory)) {
                throw FeedbackAttachmentReadException("Unable to commit feedback draft attachment group")
            }
            committed = true
            return staged.map { attachment ->
                val fileName = File(URI(attachment.fileRef)).name
                val committedFile = File(groupDirectory, fileName)
                val committedUri = committedFile.toRenderableLocalFileUri()
                attachment.copy(
                    fileRef = committedUri,
                    previewUri = committedUri
                )
            }
        } finally {
            if (!committed) {
                stagingDirectory.deleteRecursively()
            }
        }
    }

    fun discard(accountId: String, fileRefs: List<String>) {
        val accountDirectory =
            File(rootDirectory, stablePathSegment(accountId.ifBlank { "anonymous" })).canonicalFile
        val accountPrefix = accountDirectory.path + File.separator
        fileRefs.distinct().forEach { fileRef ->
            val path = runCatching { URI(fileRef).path }.getOrNull() ?: return@forEach
            val file = runCatching { File(path).canonicalFile }.getOrNull() ?: return@forEach
            if (!file.path.startsWith(accountPrefix) || !file.isFile) return@forEach
            runCatching { file.delete() }
            val groupDirectory = file.parentFile
            if (groupDirectory != null && groupDirectory.path.startsWith(accountPrefix)) {
                if (groupDirectory.listFiles().isNullOrEmpty()) {
                    runCatching { groupDirectory.delete() }
                }
            }
        }
    }

    private fun existingGroup(
        groupDirectory: File,
        sources: List<SettingsHandsFeedbackDraftAttachmentSource>,
        maxBytes: Long
    ): List<SettingsHandsFeedbackDraftAttachment>? {
        if (!groupDirectory.isDirectory) return null
        val attachments =
            sources.mapIndexed { index, source ->
                val file = File(groupDirectory, targetFileName(index, source.fileName, source.mimeType))
                val size = file.length()
                if (!file.isFile || !file.canRead() || size !in 1L..maxBytes) return null
                source.toDraftAttachment(file, size)
            }
        return attachments.takeIf { groupDirectory.listFiles()?.count(File::isFile) == sources.size }
    }

    private fun SettingsHandsFeedbackDraftAttachmentSource.toDraftAttachment(
        file: File,
        actualSizeBytes: Long
    ): SettingsHandsFeedbackDraftAttachment {
        val uri = file.toRenderableLocalFileUri()
        return SettingsHandsFeedbackDraftAttachment(
            id = id,
            fileName = coherentDisplayFileName(fileName, file),
            mimeType = mimeType,
            sizeBytes = actualSizeBytes,
            fileRef = uri,
            previewUri = uri
        )
    }

    private fun targetFileName(index: Int, fileName: String, mimeType: String): String {
        val rawName = fileName.substringAfterLast('/').substringAfterLast('\\')
        val nameExtension =
            rawName.substringAfterLast('.', missingDelimiterValue = "")
                .takeIf { it.matches(Regex("[A-Za-z0-9]{1,10}")) }
                ?.let { ".${it.lowercase(Locale.US)}" }
        val mimeExtension = extensionForMimeType(mimeType).takeUnless { it == ".bin" }
        val extension = mimeExtension ?: nameExtension ?: ".bin"
        val stem =
            rawName.substringBeforeLast('.', missingDelimiterValue = rawName)
                .replace(Regex("[^A-Za-z0-9_-]"), "_")
                .trim('_', '-', '.')
                .take(64)
                .ifBlank { "attachment" }
        return index.toString().padStart(2, '0') + "-" + stem + extension
    }

    private fun coherentDisplayFileName(fileName: String, target: File): String {
        val rawName = fileName.substringAfterLast('/').substringAfterLast('\\')
        val targetExtension = target.extension
        if (rawName.isBlank()) return target.name.substringAfter('-', target.name)
        if (targetExtension.isBlank()) return rawName
        val stem = rawName.substringBeforeLast('.', missingDelimiterValue = rawName).ifBlank { "attachment" }
        return "$stem.$targetExtension"
    }

    private fun File.toRenderableLocalFileUri(): String =
        URI("file", "", absoluteFile.path, null, null).toASCIIString()

    private fun extensionForMimeType(mimeType: String): String =
        when (mimeType.substringBefore(';').trim().lowercase(Locale.US)) {
            "image/avif" -> ".avif"
            "image/gif" -> ".gif"
            "image/heic" -> ".heic"
            "image/heif" -> ".heif"
            "image/jpeg", "image/jpg" -> ".jpg"
            "image/png" -> ".png"
            "image/webp" -> ".webp"
            else -> ".bin"
        }

    private fun cleanupStagingDirectories(accountDirectory: File) {
        val cutoff = System.currentTimeMillis() - StagingMaxAgeMillis
        accountDirectory.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith('.') && it.lastModified() < cutoff }
            ?.forEach { directory -> runCatching { directory.deleteRecursively() } }
    }

    private fun requireDirectory(directory: File) {
        if ((!directory.isDirectory && !directory.mkdirs()) || !directory.isDirectory) {
            throw FeedbackAttachmentReadException("Feedback draft storage is unavailable")
        }
    }

    private fun stablePathSegment(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.encodeToByteArray())
            .take(16)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun safePathSegment(value: String): String =
        value.replace(Regex("[^A-Za-z0-9_-]"), "_").trim('_', '-').take(96)

    private companion object {
        const val MaxAttachments = 9
        const val StagingMaxAgeMillis = 24L * 60L * 60L * 1000L
    }
}
