package build.raft.app.shared

import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.UUID

internal data class SlockAndroidAvatarDiskEntry(
    val key: SlockAndroidAvatarEntryKey,
    val fileName: String,
    val sizeBytes: Long,
    val contentType: String?,
    val lastAccessMillis: Long,
    val generation: Long
)

internal data class SlockAndroidAvatarStage(
    val key: SlockAndroidAvatarEntryKey,
    val generation: Long,
    val file: File,
    val sizeBytes: Long
)

internal data class SlockAndroidAvatarEvictedBackup(
    val entry: SlockAndroidAvatarDiskEntry,
    val backupFile: File?
)

internal data class SlockAndroidAvatarRollbackRecord(
    val newEntry: SlockAndroidAvatarDiskEntry,
    val previousEntry: SlockAndroidAvatarDiskEntry?,
    val previousBackupFile: File?,
    val evicted: List<SlockAndroidAvatarEvictedBackup>
)

/**
 * Avatar-only atomic disk/index store. It never scans or evicts sibling files
 * in the existing image_cache root.
 */
internal class SlockAndroidAvatarDiskStore(
    private val root: File,
    private val maxEntryBytes: Long = SlockAndroidAvatarCacheContract.MaxEntryBytes.toLong(),
    private val maxManagedBytes: Long = SlockAndroidAvatarCacheContract.MaxManagedBytes,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val beforeIndexCommit: () -> Unit = {},
    private val deleteManagedFile: (File) -> Boolean = { file -> !file.exists() || file.delete() },
    private val deleteManagedRoot: (File) -> Boolean = { directory ->
        !directory.exists() || directory.deleteRecursively()
    },
    private val onEntriesRemoved: (Set<SlockAndroidAvatarEntryKey>) -> Unit = {}
) {
    private companion object {
        const val IndexFileName = "managed-index-v1.tsv"
        const val IndexVersion = "slock-avatar-index-v1"
        const val StageSuffix = ".stage"
        const val RollbackSuffix = ".rollback"
    }

    private val lock = Any()
    private val entries = linkedMapOf<SlockAndroidAvatarEntryKey, SlockAndroidAvatarDiskEntry>()
    private var loaded = false

    init {
        require(maxEntryBytes > 0L)
        require(maxManagedBytes >= maxEntryBytes)
    }

    fun managedBytes(): Long = synchronized(lock) {
        ensureLoadedLocked()
        entries.values.sumOf { it.sizeBytes }
    }

    fun entry(key: SlockAndroidAvatarEntryKey): SlockAndroidAvatarDiskEntry? = synchronized(lock) {
        ensureLoadedLocked()
        validatedEntryLocked(key)
    }

    fun read(key: SlockAndroidAvatarEntryKey): SlockAndroidAvatarBytePayload? = synchronized(lock) {
        ensureLoadedLocked()
        val entry = validatedEntryLocked(key) ?: return@synchronized null
        val bytes = runCatching { root.resolve(entry.fileName).readBytes() }.getOrNull()
        if (bytes == null || bytes.size.toLong() != entry.sizeBytes) {
            removeEntriesLocked(setOf(key))
            return@synchronized null
        }
        val touched = entry.copy(lastAccessMillis = clockMillis().coerceAtLeast(entry.lastAccessMillis))
        entries[key] = touched
        runCatching { persistIndexLocked() }
        SlockAndroidAvatarBytePayload(bytes, entry.contentType)
    }

    fun stage(
        key: SlockAndroidAvatarEntryKey,
        generation: Long,
        bytes: ByteArray
    ): SlockAndroidAvatarStage? = synchronized(lock) {
        ensureLoadedLocked()
        if (generation <= 0L || bytes.isEmpty() || bytes.size.toLong() > maxEntryBytes) {
            return@synchronized null
        }
        ensureRootLocked()
        val stageFile = root.resolve("${key.bytesKey}.$generation$StageSuffix")
        runCatching {
            stageFile.delete()
            val physicalBytes =
                root.listFiles().orEmpty()
                    .filter { file ->
                        file.isFile &&
                            (file.name.endsWith(".bin") ||
                                file.name.endsWith(StageSuffix) ||
                                file.name.endsWith(RollbackSuffix))
                    }
                    .sumOf(File::length)
            if (physicalBytes + bytes.size > maxManagedBytes + maxEntryBytes) {
                return@synchronized null
            }
            FileOutputStream(stageFile).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            if (stageFile.length() != bytes.size.toLong()) error("avatar stage length mismatch")
            SlockAndroidAvatarStage(key, generation, stageFile, bytes.size.toLong())
        }.getOrElse {
            stageFile.delete()
            null
        }
    }

    fun promote(
        stage: SlockAndroidAvatarStage,
        contentType: String?
    ): SlockAndroidAvatarRollbackRecord? = synchronized(lock) {
        ensureLoadedLocked()
        if (
            stage.generation <= 0L ||
            stage.sizeBytes !in 1L..maxEntryBytes ||
            !stage.file.isFile ||
            stage.file.parentFile?.canonicalFile != root.canonicalFile ||
            stage.file.length() != stage.sizeBytes
        ) {
            stage.file.delete()
            return@synchronized null
        }
        ensureRootLocked()
        val previous = validatedEntryLocked(stage.key)
        val target = root.resolve("${stage.key.bytesKey}.bin")
        val previousBackup = root.resolve("${stage.key.bytesKey}.${stage.generation}.replace$RollbackSuffix")
        val previousEntries = LinkedHashMap(entries)
        val evictedBackups = mutableListOf<SlockAndroidAvatarEvictedBackup>()
        var previousWasBackedUp = false
        try {
            previousBackup.delete()
            if (target.isFile) {
                atomicMove(target, previousBackup)
                previousWasBackedUp = true
            }
            atomicMove(stage.file, target)
            val candidate =
                SlockAndroidAvatarDiskEntry(
                    key = stage.key,
                    fileName = target.name,
                    sizeBytes = stage.sizeBytes,
                    contentType = contentType?.trim()?.takeIf(String::isNotBlank),
                    lastAccessMillis = clockMillis().coerceAtLeast(0L),
                    generation = stage.generation
                )
            entries[stage.key] = candidate
            val evictionKeys = evictionKeysLocked(excluding = stage.key)
            evictionKeys.forEach { key ->
                val entry = entries.remove(key) ?: return@forEach
                val file = root.resolve(entry.fileName)
                val backup = root.resolve("evict-${stage.generation}-${entry.key.bytesKey}$RollbackSuffix")
                backup.delete()
                val admittedBackup =
                    if (file.isFile) {
                        atomicMove(file, backup)
                        backup
                    } else {
                        null
                    }
                evictedBackups += SlockAndroidAvatarEvictedBackup(entry, admittedBackup)
            }
            if (entries.values.sumOf { it.sizeBytes } > maxManagedBytes) {
                error("avatar managed budget cannot admit candidate")
            }
            persistIndexLocked()
            onEntriesRemoved(evictionKeys + stage.key)
            return@synchronized SlockAndroidAvatarRollbackRecord(
                newEntry = candidate,
                previousEntry = previous,
                previousBackupFile = previousBackup.takeIf { previousWasBackedUp },
                evicted = evictedBackups.toList()
            )
        } catch (_: Throwable) {
            target.delete()
            if (previousWasBackedUp && previousBackup.isFile) {
                runCatching { atomicMove(previousBackup, target) }
            }
            evictedBackups.forEach { backup ->
                val destination = root.resolve(backup.entry.fileName)
                if (backup.backupFile?.isFile == true) {
                    runCatching { atomicMove(backup.backupFile, destination) }
                }
            }
            entries.clear()
            entries.putAll(previousEntries)
            runCatching { persistIndexLocked() }
            stage.file.delete()
            return@synchronized null
        }
    }

    fun rollback(record: SlockAndroidAvatarRollbackRecord): Boolean = synchronized(lock) {
        ensureLoadedLocked()
        val current = entries[record.newEntry.key]
        if (current?.generation != record.newEntry.generation) {
            cleanupRollbackFilesLocked(record)
            return@synchronized true
        }
        val snapshot = LinkedHashMap(entries)
        val target = root.resolve(record.newEntry.fileName)
        var rollbackIndexCommitted = false
        return@synchronized runCatching {
            // Publish the old index first. If the process dies during the file
            // moves, startup recovery sees the old generation and restores the
            // still-present rollback files instead of admitting candidate bytes.
            entries.remove(record.newEntry.key)
            record.previousEntry?.let { entries[it.key] = it }
            record.evicted.forEach { entries[it.entry.key] = it.entry }
            persistIndexLocked()
            rollbackIndexCommitted = true

            if (target.exists() && !target.delete()) error("avatar rollback could not remove candidate")
            val previous = record.previousEntry
            if (previous != null) {
                val destination = root.resolve(previous.fileName)
                if (record.previousBackupFile?.isFile == true) {
                    atomicMove(record.previousBackupFile, destination)
                }
                if (!destination.isFile || destination.length() != previous.sizeBytes) {
                    error("avatar rollback previous bytes unavailable")
                }
            } else {
                record.previousBackupFile?.delete()
            }
            record.evicted.forEach { evicted ->
                val destination = root.resolve(evicted.entry.fileName)
                evicted.backupFile?.takeIf(File::isFile)?.let { backup ->
                    atomicMove(backup, destination)
                }
                if (!destination.isFile || destination.length() != evicted.entry.sizeBytes) {
                    error("avatar rollback evicted bytes unavailable")
                }
            }
            onEntriesRemoved(setOf(record.newEntry.key))
            true
        }.getOrElse {
            if (!rollbackIndexCommitted) {
                entries.clear()
                entries.putAll(snapshot)
                runCatching { persistIndexLocked() }
            }
            onEntriesRemoved(setOf(record.newEntry.key))
            false
        }
    }

    fun commitRollback(record: SlockAndroidAvatarRollbackRecord) = synchronized(lock) {
        cleanupRollbackFilesLocked(record)
    }

    fun deleteStage(stage: SlockAndroidAvatarStage?) {
        stage?.file?.delete()
    }

    fun retireOwner(partitionKey: String): Boolean = synchronized(lock) {
        ensureLoadedLocked()
        removeEntriesLocked(entries.keys.filterTo(linkedSetOf()) { it.partitionKey == partitionKey })
    }

    fun invalidate(key: SlockAndroidAvatarEntryKey): Boolean = synchronized(lock) {
        ensureLoadedLocked()
        removeEntriesLocked(setOf(key))
    }

    fun retireSubject(partitionKey: String, subjectKey: String): Boolean = synchronized(lock) {
        ensureLoadedLocked()
        removeEntriesLocked(
            entries.keys.filterTo(linkedSetOf()) {
                it.partitionKey == partitionKey && it.subjectKey == subjectKey
            }
        )
    }

    fun clearAll(): Boolean = synchronized(lock) {
        ensureLoadedLocked()
        if (!removeEntriesLocked(entries.keys.toSet())) return@synchronized false
        val deleted = runCatching { deleteManagedRoot(root) }.getOrDefault(false)
        if (!deleted) return@synchronized false
        loaded = true
        true
    }

    internal fun indexedEntries(): List<SlockAndroidAvatarDiskEntry> = synchronized(lock) {
        ensureLoadedLocked()
        entries.values.toList()
    }

    private fun validatedEntryLocked(key: SlockAndroidAvatarEntryKey): SlockAndroidAvatarDiskEntry? {
        val entry = entries[key] ?: return null
        val file = root.resolve(entry.fileName)
        if (!file.isFile || file.length() != entry.sizeBytes || entry.sizeBytes !in 1L..maxEntryBytes) {
            removeEntriesLocked(setOf(key))
            return null
        }
        return entry
    }

    private fun removeEntriesLocked(keys: Set<SlockAndroidAvatarEntryKey>): Boolean {
        if (keys.isEmpty()) return true
        val removed = keys.mapNotNull(entries::get)
        val filesDeleted =
            runCatching {
                removed.all { entry -> deleteManagedFile(root.resolve(entry.fileName)) }
            }.getOrDefault(false)
        if (!filesDeleted) return false
        val snapshot = LinkedHashMap(entries)
        removed.forEach { entries.remove(it.key) }
        return runCatching {
            persistIndexLocked()
            onEntriesRemoved(removed.mapTo(linkedSetOf()) { it.key })
            true
        }.getOrElse {
            entries.clear()
            entries.putAll(snapshot)
            false
        }
    }

    private fun evictionKeysLocked(excluding: SlockAndroidAvatarEntryKey): Set<SlockAndroidAvatarEntryKey> {
        var projected = entries.values.sumOf { it.sizeBytes }
        if (projected <= maxManagedBytes) return emptySet()
        val selected = linkedSetOf<SlockAndroidAvatarEntryKey>()
        entries.values
            .asSequence()
            .filter { it.key != excluding }
            .sortedWith(compareBy<SlockAndroidAvatarDiskEntry> { it.lastAccessMillis }.thenBy { it.key.bytesKey })
            .forEach { entry ->
                if (projected > maxManagedBytes) {
                    selected += entry.key
                    projected -= entry.sizeBytes
                }
            }
        return selected
    }

    private fun ensureLoadedLocked() {
        if (loaded) return
        ensureRootLocked()
        entries.clear()
        val index = root.resolve(IndexFileName)
        if (index.isFile) {
            runCatching {
                val lines = index.readLines(StandardCharsets.UTF_8)
                if (lines.firstOrNull() != IndexVersion) error("avatar index version mismatch")
                lines.drop(1).filter(String::isNotBlank).forEach { line ->
                    parseEntry(line)?.let { entry -> entries[entry.key] = entry }
                }
            }.onFailure {
                entries.clear()
                index.delete()
            }
        }
        recoverRollbackFilesLocked()
        val invalid =
            entries.values.filterTo(linkedSetOf()) { entry ->
                val file = root.resolve(entry.fileName)
                !file.isFile || file.length() != entry.sizeBytes
            }.mapTo(linkedSetOf()) { it.key }
        invalid.forEach(entries::remove)
        pruneBudgetLocked()
        root.listFiles().orEmpty().forEach { file ->
            if (file.name.endsWith(StageSuffix) || file.name.endsWith(RollbackSuffix)) file.delete()
            if (file.name.endsWith(".bin") && entries.values.none { it.fileName == file.name }) file.delete()
        }
        runCatching { persistIndexLocked() }
        if (invalid.isNotEmpty()) onEntriesRemoved(invalid)
        loaded = true
    }

    private fun recoverRollbackFilesLocked() {
        root.listFiles().orEmpty().filter { it.name.endsWith(RollbackSuffix) }.forEach { backup ->
            val replaceMatch = Regex("^([0-9a-f]{64})\\.([0-9]+)\\.replace\\.rollback$").matchEntire(backup.name)
            if (replaceMatch != null) {
                val bytesKey = replaceMatch.groupValues[1]
                val generation = replaceMatch.groupValues[2].toLongOrNull()
                val indexed = entries.values.firstOrNull { it.key.bytesKey == bytesKey }
                if (generation != null && indexed?.generation != generation) {
                    runCatching { atomicMove(backup, root.resolve("$bytesKey.bin")) }
                } else {
                    backup.delete()
                }
                return@forEach
            }
            val evictMatch = Regex("^evict-[0-9]+-([0-9a-f]{64})\\.rollback$").matchEntire(backup.name)
            val indexed = evictMatch?.groupValues?.getOrNull(1)?.let { bytesKey ->
                entries.values.firstOrNull { it.key.bytesKey == bytesKey }
            }
            if (indexed != null && !root.resolve(indexed.fileName).isFile) {
                runCatching { atomicMove(backup, root.resolve(indexed.fileName)) }
            } else {
                backup.delete()
            }
        }
    }

    private fun pruneBudgetLocked() {
        var total = entries.values.sumOf { it.sizeBytes }
        if (total <= maxManagedBytes) return
        entries.values
            .sortedWith(compareBy<SlockAndroidAvatarDiskEntry> { it.lastAccessMillis }.thenBy { it.key.bytesKey })
            .forEach { entry ->
                if (total > maxManagedBytes && entries.remove(entry.key) != null) {
                    total -= entry.sizeBytes
                    root.resolve(entry.fileName).delete()
                    onEntriesRemoved(setOf(entry.key))
                }
            }
    }

    private fun persistIndexLocked() {
        ensureRootLocked()
        beforeIndexCommit()
        val index = root.resolve(IndexFileName)
        val temp = root.resolve("$IndexFileName.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temp).use { output ->
                val text = buildString {
                    appendLine(IndexVersion)
                    entries.values.sortedBy { it.key.bytesKey }.forEach { appendLine(serializeEntry(it)) }
                }
                output.write(text.toByteArray(StandardCharsets.UTF_8))
                output.flush()
                output.fd.sync()
            }
            atomicMove(temp, index)
        } finally {
            temp.delete()
        }
    }

    private fun serializeEntry(entry: SlockAndroidAvatarDiskEntry): String =
        listOf(
            entry.key.partitionKey,
            entry.key.subjectKey,
            entry.key.bytesKey,
            entry.fileName,
            entry.sizeBytes.toString(),
            entry.lastAccessMillis.toString(),
            entry.generation.toString(),
            entry.contentType?.let {
                Base64.getUrlEncoder().withoutPadding().encodeToString(it.toByteArray(StandardCharsets.UTF_8))
            }.orEmpty()
        ).joinToString("\t")

    private fun parseEntry(line: String): SlockAndroidAvatarDiskEntry? {
        val fields = line.split('\t')
        if (fields.size != 8) return null
        val partition = fields[0]
        val subject = fields[1]
        val bytes = fields[2]
        if (
            !with(SlockAndroidAvatarCacheContract) { partition.isOpaqueKey() } ||
            !with(SlockAndroidAvatarCacheContract) { subject.isOpaqueKey() } ||
            !with(SlockAndroidAvatarCacheContract) { bytes.isOpaqueKey() }
        ) return null
        if (fields[3] != "$bytes.bin") return null
        val size = fields[4].toLongOrNull()?.takeIf { it in 1L..maxEntryBytes } ?: return null
        val lastAccess = fields[5].toLongOrNull()?.takeIf { it >= 0L } ?: return null
        val generation = fields[6].toLongOrNull()?.takeIf { it > 0L } ?: return null
        val contentType =
            fields[7].takeIf(String::isNotEmpty)?.let { encoded ->
                runCatching {
                    String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8)
                        .trim()
                        .takeIf { it.startsWith("image/", ignoreCase = true) }
                }.getOrNull()
            }
        val key = SlockAndroidAvatarEntryKey(partition, subject, bytes)
        return SlockAndroidAvatarDiskEntry(key, fields[3], size, contentType, lastAccess, generation)
    }

    private fun cleanupRollbackFilesLocked(record: SlockAndroidAvatarRollbackRecord) {
        record.previousBackupFile?.delete()
        record.evicted.forEach { it.backupFile?.delete() }
    }

    private fun ensureRootLocked() {
        if (!root.isDirectory && !root.mkdirs() && !root.isDirectory) {
            error("avatar cache directory unavailable")
        }
    }

    private fun atomicMove(source: File, destination: File) {
        destination.parentFile?.mkdirs()
        try {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
