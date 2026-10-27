package build.raft.app.data

import build.raft.app.ThreadComposerTemporaryFileRetirement
import build.raft.app.ThreadComposerTemporaryFileRetirementHandler
import build.raft.app.ThreadComposerTemporaryFileRetirementResult
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.concurrent.thread

/**
 * The desktop owner of composer temporary files: pasted images in the clipboard cache. The shared
 * draft store retires them (after send, removal or a cleared draft) through
 * [ThreadComposerTemporaryFileRetirement]; this handler moves each one to a sibling tombstone
 * before returning and deletes the tombstones in the background (the Android handler's contract).
 * Only regular files directly inside the clipboard cache are admitted; anything else is rejected
 * and left untouched.
 */
object RaftDesktopComposerTemporaryFiles {
    /** Pasted images older than this are left over from drafts that never sent; removed on start. */
    private const val STALE_AFTER_MILLIS = 3L * 24 * 60 * 60 * 1000
    private const val TOMBSTONE_PREFIX = ".retired-"

    /** Call once at app start: installs the retirement handler and sweeps stale clipboard files. */
    fun install() {
        val dir = RaftDesktopClipboardAttachments.clipboardDir
        ThreadComposerTemporaryFileRetirement.install(
            ThreadComposerTemporaryFileRetirementHandler { sessionId, fileRefs -> retire(dir, sessionId, fileRefs) }
        )
        thread(isDaemon = true, name = "raft-clipboard-cache-sweep") { sweep(dir, System.currentTimeMillis()) }
    }

    fun retire(
        dir: File,
        sessionId: String,
        fileRefs: Set<String>,
        deleteLater: (List<File>) -> Unit = { tombstones ->
            thread(isDaemon = true, name = "raft-clipboard-tombstone-delete") { tombstones.forEach { it.delete() } }
        },
    ): ThreadComposerTemporaryFileRetirementResult {
        val root = runCatching { dir.canonicalFile }.getOrNull()
        val admitted = fileRefs.filter { ref -> root != null && ownedFile(root, ref) != null }.toSet()
        val tombstones = linkedMapOf<String, File>()
        val failed = mutableSetOf<String>()
        admitted.forEach { ref ->
            val file = File(ref)
            val tombstone = File(file.parentFile, "$TOMBSTONE_PREFIX${UUID.randomUUID()}")
            val moved =
                runCatching { Files.move(file.toPath(), tombstone.toPath(), StandardCopyOption.ATOMIC_MOVE) }.isSuccess
            if (moved) tombstones[ref] = tombstone else failed += ref
        }
        if (tombstones.isNotEmpty()) deleteLater(tombstones.values.toList())
        return ThreadComposerTemporaryFileRetirementResult(
            sessionId = sessionId,
            requestedFileRefs = fileRefs,
            admittedFileRefs = admitted,
            quarantinedFileRefs = tombstones.keys,
            rejectedFileRefs = fileRefs - admitted,
            failedFileRefs = failed,
            tombstoneByFileRef = tombstones.mapValues { (_, file) -> file.absolutePath },
            errorCode = failed.takeIf { it.isNotEmpty() }?.let { ThreadComposerTemporaryFileRetirementResult.HandlerFailureCode },
        )
    }

    /** Deletes tombstones, and pasted images not touched for [STALE_AFTER_MILLIS]. */
    fun sweep(dir: File, nowMillis: Long) {
        dir.listFiles().orEmpty().forEach { file ->
            if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return@forEach
            val stale =
                file.name.startsWith(TOMBSTONE_PREFIX) ||
                    (file.name.startsWith(RaftDesktopClipboardAttachments.PASTED_IMAGE_PREFIX) &&
                        nowMillis - file.lastModified() > STALE_AFTER_MILLIS)
            if (stale) file.delete()
        }
    }

    /** [ref] as a regular, non-symlink file directly inside [root], else null. */
    private fun ownedFile(root: File, ref: String): File? {
        val file = File(ref)
        if (!file.isAbsolute) return null
        val path = file.toPath()
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return null
        val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return null
        return canonical.takeIf { it.parentFile == root && it.name.startsWith(RaftDesktopClipboardAttachments.PASTED_IMAGE_PREFIX) }
    }
}
