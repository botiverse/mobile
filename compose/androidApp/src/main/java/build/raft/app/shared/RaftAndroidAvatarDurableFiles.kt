package build.raft.app.shared

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

internal sealed interface RaftAndroidAvatarFileRead {
    data class Present(val bytes: ByteArray) : RaftAndroidAvatarFileRead
    data object Missing : RaftAndroidAvatarFileRead
    data object Failed : RaftAndroidAvatarFileRead
}

/**
 * All paths are relative to one caller-provided avatar namespace.
 *
 * A successful mutating call means both the file operation and the containing-directory fsync
 * completed. A false result deliberately says nothing about whether the operation became visible:
 * recovery must reread the namespace because a crash or directory-fsync failure can leave either
 * side of the operation durable.
 */
internal interface RaftAndroidAvatarDurableFileSystem {
    fun read(path: String): RaftAndroidAvatarFileRead
    fun listFiles(): Set<String>?
    fun fileSize(path: String): Long?
    fun exists(path: String): Boolean
    fun writeAtomically(path: String, bytes: ByteArray): Boolean
    fun writeNewDurably(path: String, bytes: ByteArray): Boolean
    fun moveAtomically(fromPath: String, toPath: String): Boolean
    fun deleteDurably(path: String): Boolean
    fun syncParent(path: String): Boolean
}

internal class RaftAndroidAvatarRealFileSystem(
    private val root: File,
    private val identityFactory: RaftAndroidAvatarIdentityFactory =
        RaftAndroidAvatarSecureIdentityFactory()
) : RaftAndroidAvatarDurableFileSystem {
    override fun read(path: String): RaftAndroidAvatarFileRead {
        val file = resolve(path) ?: return RaftAndroidAvatarFileRead.Failed
        if (!file.exists()) return RaftAndroidAvatarFileRead.Missing
        if (!file.isFile) return RaftAndroidAvatarFileRead.Failed
        return runCatching { RaftAndroidAvatarFileRead.Present(file.readBytes()) }
            .getOrDefault(RaftAndroidAvatarFileRead.Failed)
    }

    override fun listFiles(): Set<String>? =
        runCatching {
            if (!root.exists()) return@runCatching emptySet()
            if (!root.isDirectory) return null
            Files.walk(root.toPath()).use { paths ->
                buildSet {
                    paths
                        .filter { path -> Files.isRegularFile(path) }
                        .forEach { path ->
                            add(root.toPath().relativize(path).joinToString(separator = "/"))
                        }
                }
            }
        }.getOrNull()

    override fun fileSize(path: String): Long? {
        val file = resolve(path) ?: return null
        if (!file.isFile) return null
        return runCatching { Files.size(file.toPath()) }.getOrNull()
    }

    override fun exists(path: String): Boolean =
        resolve(path)?.let { file -> Files.isRegularFile(file.toPath()) } == true

    override fun writeAtomically(path: String, bytes: ByteArray): Boolean {
        val target = resolve(path) ?: return false
        val parent = target.parentFile ?: return false
        if (!ensureDirectory(parent)) return false
        val temp = File(parent, "metadata-tmp-${identityFactory.nextOpaqueIdentity()}")
        return try {
            FileOutputStream(temp).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
            fsyncDirectory(parent)
        } catch (_: Throwable) {
            runCatching { Files.deleteIfExists(temp.toPath()) }
            false
        }
    }

    override fun writeNewDurably(path: String, bytes: ByteArray): Boolean {
        val target = resolve(path) ?: return false
        val parent = target.parentFile ?: return false
        if (!ensureDirectory(parent)) return false
        return try {
            FileChannel.open(
                target.toPath(),
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
            ).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            fsyncDirectory(parent)
        } catch (_: Throwable) {
            false
        }
    }

    override fun moveAtomically(fromPath: String, toPath: String): Boolean {
        val source = resolve(fromPath) ?: return false
        val target = resolve(toPath) ?: return false
        if (source.parentFile != target.parentFile) return false
        val parent = target.parentFile ?: return false
        if (!ensureDirectory(parent)) return false
        return try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            fsyncDirectory(parent)
        } catch (_: Throwable) {
            false
        }
    }

    override fun deleteDurably(path: String): Boolean {
        val target = resolve(path) ?: return false
        val parent = target.parentFile ?: return false
        return try {
            Files.deleteIfExists(target.toPath())
            !parent.exists() || fsyncDirectory(parent)
        } catch (_: Throwable) {
            false
        }
    }

    override fun syncParent(path: String): Boolean {
        val target = resolve(path) ?: return false
        val parent = target.parentFile ?: return false
        return !parent.exists() || fsyncDirectory(parent)
    }

    private fun resolve(relativePath: String): File? {
        val components = relativePath.split('/')
        if (relativePath.isBlank() || components.any { it.isBlank() || it == "." || it == ".." }) {
            return null
        }
        if (File(relativePath).isAbsolute) return null
        return components.fold(root) { current, component -> File(current, component) }
    }

    private fun ensureDirectory(directory: File): Boolean =
        runCatching {
            val missing = mutableListOf<File>()
            var current: File? = directory
            while (current != null && !current.exists()) {
                missing += current
                current = current.parentFile
            }
            if (current != null && !current.isDirectory) return false
            missing.asReversed().forEach { child ->
                val parent = child.parentFile ?: return false
                if (!child.mkdir() && !child.isDirectory) return false
                if (!fsyncDirectory(parent)) return false
            }
            directory.isDirectory
        }.getOrDefault(false)

    private fun fsyncDirectory(directory: File): Boolean {
        if (!directory.isDirectory) return false
        return runCatching {
            val descriptor =
                Os.open(
                    directory.absolutePath,
                    OsConstants.O_RDONLY,
                    0
                )
            try {
                Os.fsync(descriptor)
            } finally {
                Os.close(descriptor)
            }
            true
        }.getOrDefault(false)
    }
}
