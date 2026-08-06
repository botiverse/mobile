package build.raft.app.shared

import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.IdentityHashMap
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

private data class SlockAndroidAvatarRecoveredTransient(
    val file: File,
    val key: SlockAndroidAvatarEntryKey?
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
    private val rollbackPendingKeys = linkedSetOf<SlockAndroidAvatarEntryKey>()
    private val openRollbackRecords =
        IdentityHashMap<SlockAndroidAvatarRollbackRecord, Set<SlockAndroidAvatarEntryKey>>()
    private val openRollbackProtectionCounts = mutableMapOf<SlockAndroidAvatarEntryKey, Int>()
    private val recoveredTransients = linkedMapOf<String, SlockAndroidAvatarRecoveredTransient>()
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

    /**
     * Reads the just-promoted candidate without mutating its index membership.
     * A failed validation must leave the rollback record authoritative so the
     * previous generation can still be restored.
     */
    fun readPromoted(record: SlockAndroidAvatarRollbackRecord): SlockAndroidAvatarBytePayload? =
        synchronized(lock) {
            ensureLoadedLocked()
            val expected = record.newEntry
            val current = entries[expected.key] ?: return@synchronized null
            if (
                current.generation != expected.generation ||
                current.fileName != expected.fileName ||
                current.sizeBytes != expected.sizeBytes
            ) {
                return@synchronized null
            }
            val file = root.resolve(current.fileName)
            if (!file.isFile || file.length() != current.sizeBytes) return@synchronized null
            val bytes = runCatching { file.readBytes() }.getOrNull() ?: return@synchronized null
            if (bytes.size.toLong() != current.sizeBytes) return@synchronized null
            SlockAndroidAvatarBytePayload(bytes, current.contentType)
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
        if (hasRecoveredArtifactsLocked(key)) {
            return@synchronized null
        }
        ensureRootLocked()
        val stageFile = root.resolve("${key.transientPrefix()}.$generation$StageSuffix")
        if (!deleteRecoveredFileLocked(stageFile, key)) return@synchronized null
        runCatching {
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
            deleteRecoveredFileLocked(stageFile, key)
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
            deleteRecoveredFileLocked(stage.file, stage.key)
            return@synchronized null
        }
        ensureRootLocked()
        val previous = validatedEntryLocked(stage.key)
        val target = root.resolve("${stage.key.bytesKey}.bin")
        val previousBackup =
            root.resolve("${stage.key.transientPrefix()}.${stage.generation}.replace$RollbackSuffix")
        val previousEntries = LinkedHashMap(entries)
        val evictedBackups = mutableListOf<SlockAndroidAvatarEvictedBackup>()
        var previousWasBackedUp = false
        try {
            if (!deleteRecoveredFileLocked(previousBackup, stage.key)) return@synchronized null
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
                val backup =
                    root.resolve("evict-${stage.generation}-${entry.key.transientPrefix()}$RollbackSuffix")
                if (!deleteRecoveredFileLocked(backup, entry.key)) {
                    error("avatar eviction backup cleanup incomplete")
                }
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
            val record =
                SlockAndroidAvatarRollbackRecord(
                    newEntry = candidate,
                    previousEntry = previous,
                    previousBackupFile = previousBackup.takeIf { previousWasBackedUp },
                    evicted = evictedBackups.toList()
                )
            rememberOpenRollbackLocked(record)
            return@synchronized record
        } catch (_: Throwable) {
            var targetResolved = !target.exists() || runCatching { deleteManagedFile(target) }.getOrDefault(false)
            if (previousWasBackedUp && previousBackup.isFile) {
                val restored =
                    runCatching {
                        atomicMove(previousBackup, target)
                        true
                    }.getOrDefault(false)
                if (restored) {
                    targetResolved = true
                } else {
                    rollbackPendingKeys += stage.key
                    rememberRecoveredTransientLocked(previousBackup, stage.key)
                }
            }
            if (!targetResolved && target.exists()) {
                rollbackPendingKeys += stage.key
                rememberRecoveredTransientLocked(target, stage.key)
            }
            evictedBackups.forEach { backup ->
                val destination = root.resolve(backup.entry.fileName)
                if (backup.backupFile?.isFile == true) {
                    val restored =
                        runCatching {
                            atomicMove(backup.backupFile, destination)
                            true
                        }.getOrDefault(false)
                    if (!restored) {
                        rollbackPendingKeys += backup.entry.key
                        rememberRecoveredTransientLocked(backup.backupFile, backup.entry.key)
                    }
                }
            }
            entries.clear()
            entries.putAll(previousEntries)
            runCatching { persistIndexLocked() }
            deleteRecoveredFileLocked(stage.file, stage.key)
            return@synchronized null
        }
    }

    fun rollback(record: SlockAndroidAvatarRollbackRecord): Boolean = synchronized(lock) {
        ensureLoadedLocked()
        val current = entries[record.newEntry.key]
        val previous = record.previousEntry
        val candidateIsCurrent = current?.generation == record.newEntry.generation
        val candidateWasCurrent = candidateIsCurrent
        val previousIndexIsCurrent =
            if (previous == null) {
                current == null
            } else {
                current?.generation == previous.generation && current.fileName == previous.fileName
            }
        if (!candidateIsCurrent && !previousIndexIsCurrent) {
            val cleaned = cleanupRollbackFilesLocked(record)
            if (cleaned) {
                rollbackPendingKeys.remove(record.newEntry.key)
                releaseOpenRollbackLocked(record)
            }
            return@synchronized cleaned
        }
        val snapshot = LinkedHashMap(entries)
        val target = root.resolve(record.newEntry.fileName)
        if (!previousIndexIsCurrent) {
            // Publish the old index first. If the process dies during the file
            // moves, startup recovery sees the old generation and restores the
            // still-present rollback files instead of admitting candidate bytes.
            entries.remove(record.newEntry.key)
            previous?.let { entries[it.key] = it }
            record.evicted.forEach { entries[it.entry.key] = it.entry }
            val indexCommitted =
                runCatching {
                    persistIndexLocked()
                    true
                }.getOrElse {
                    entries.clear()
                    entries.putAll(snapshot)
                    false
                }
            if (!indexCommitted) return@synchronized false
        }
        rollbackPendingKeys += record.newEntry.key
        val restored =
            runCatching {
                if (previous != null) {
                    val destination = root.resolve(previous.fileName)
                    if (record.previousBackupFile?.isFile == true) {
                        atomicMove(record.previousBackupFile, destination)
                    } else if (candidateWasCurrent) {
                        error("avatar rollback previous backup unavailable")
                    }
                    if (!destination.isFile || destination.length() != previous.sizeBytes) {
                        error("avatar rollback previous bytes unavailable")
                    }
                } else {
                    if (!deleteManagedFile(target)) error("avatar rollback could not remove candidate")
                }
                record.evicted.forEach { evicted ->
                    val destination = root.resolve(evicted.entry.fileName)
                    evicted.backupFile?.let { backup ->
                        if (backup.isFile) {
                            atomicMove(backup, destination)
                        } else if (candidateWasCurrent) {
                            error("avatar rollback evicted backup unavailable")
                        }
                    }
                    if (!destination.isFile || destination.length() != evicted.entry.sizeBytes) {
                        error("avatar rollback evicted bytes unavailable")
                    }
                }
                if (!cleanupRollbackFilesLocked(record)) {
                    error("avatar rollback cleanup incomplete")
                }
                true
            }.getOrDefault(false)
        if (restored) {
            rollbackPendingKeys.remove(record.newEntry.key)
            releaseOpenRollbackLocked(record)
            onEntriesRemoved(setOf(record.newEntry.key))
        }
        restored
    }

    fun commitRollback(record: SlockAndroidAvatarRollbackRecord): Boolean = synchronized(lock) {
        val cleaned = cleanupRollbackFilesLocked(record)
        if (cleaned) {
            rollbackPendingKeys.remove(record.newEntry.key)
            releaseOpenRollbackLocked(record)
        }
        cleaned
    }

    fun deleteRollbackArtifacts(record: SlockAndroidAvatarRollbackRecord): Boolean = synchronized(lock) {
        val target = root.resolve(record.newEntry.fileName)
        val targetDeleted = !target.exists() || runCatching { deleteManagedFile(target) }.getOrDefault(false)
        if (targetDeleted) recoveredTransients.remove(target.name)
        val backupsDeleted = cleanupRollbackFilesLocked(record)
        val deleted = targetDeleted && backupsDeleted
        if (deleted) {
            rollbackPendingKeys.remove(record.newEntry.key)
            releaseOpenRollbackLocked(record)
        }
        deleted
    }

    fun deleteStage(stage: SlockAndroidAvatarStage?): Boolean = synchronized(lock) {
        if (stage == null) return@synchronized true
        val deleted = !stage.file.exists() || runCatching { deleteManagedFile(stage.file) }.getOrDefault(false)
        if (deleted) recoveredTransients.remove(stage.file.name)
        deleted
    }

    fun hasRecoveredArtifacts(key: SlockAndroidAvatarEntryKey): Boolean = synchronized(lock) {
        ensureLoadedLocked()
        hasRecoveredArtifactsLocked(key)
    }

    fun retireOwner(partitionKey: String): Boolean = synchronized(lock) {
        ensureLoadedLocked()
        val entriesRemoved =
            removeEntriesLocked(entries.keys.filterTo(linkedSetOf()) { it.partitionKey == partitionKey })
        val transientsRemoved =
            cleanupRecoveredTransientsLocked { key -> key == null || key.partitionKey == partitionKey }
        entriesRemoved && transientsRemoved
    }

    fun invalidate(key: SlockAndroidAvatarEntryKey): Boolean = synchronized(lock) {
        ensureLoadedLocked()
        val entryRemoved = removeEntriesLocked(setOf(key))
        val transientsRemoved =
            cleanupRecoveredTransientsLocked { recoveredKey -> recoveredKey == null || recoveredKey == key }
        entryRemoved && transientsRemoved
    }

    fun retireSubject(partitionKey: String, subjectKey: String): Boolean = synchronized(lock) {
        ensureLoadedLocked()
        val entriesRemoved =
            removeEntriesLocked(
                entries.keys.filterTo(linkedSetOf()) {
                    it.partitionKey == partitionKey && it.subjectKey == subjectKey
                }
            )
        val transientsRemoved =
            cleanupRecoveredTransientsLocked { key ->
                key == null || (key.partitionKey == partitionKey && key.subjectKey == subjectKey)
            }
        entriesRemoved && transientsRemoved
    }

    fun clearAll(): Boolean = synchronized(lock) {
        ensureLoadedLocked()
        if (!removeEntriesLocked(entries.keys.toSet())) return@synchronized false
        val deleted = runCatching { deleteManagedRoot(root) }.getOrDefault(false)
        if (!deleted) return@synchronized false
        recoveredTransients.clear()
        rollbackPendingKeys.clear()
        openRollbackRecords.clear()
        openRollbackProtectionCounts.clear()
        loaded = true
        true
    }

    internal fun indexedEntries(): List<SlockAndroidAvatarDiskEntry> = synchronized(lock) {
        ensureLoadedLocked()
        entries.values.toList()
    }

    private fun validatedEntryLocked(key: SlockAndroidAvatarEntryKey): SlockAndroidAvatarDiskEntry? {
        if (key in rollbackPendingKeys) return null
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
            rollbackPendingKeys.removeAll(removed.mapTo(linkedSetOf()) { it.key })
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
            .filter { it.key != excluding && !isRollbackProtectedLocked(it.key) }
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
        recoveredTransients.clear()
        rollbackPendingKeys.clear()
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
            if (file.name in recoveredTransients) return@forEach
            val isTransient = file.name.endsWith(StageSuffix) || file.name.endsWith(RollbackSuffix)
            val isOrphanBin =
                file.name.endsWith(".bin") && entries.values.none { it.fileName == file.name }
            if (isTransient || isOrphanBin) {
                deleteRecoveredFileLocked(file, transientKeyForFileLocked(file))
            }
        }
        runCatching { persistIndexLocked() }
        if (invalid.isNotEmpty()) onEntriesRemoved(invalid)
        loaded = true
    }

    private fun recoverRollbackFilesLocked() {
        root.listFiles().orEmpty().filter { it.name.endsWith(RollbackSuffix) }.forEach { backup ->
            val keyHint = transientKeyForFileLocked(backup)
            val replaceIdentity = replaceRollbackIdentity(backup.name)
            if (replaceIdentity != null) {
                val bytesKey = replaceIdentity.first
                val generation = replaceIdentity.second
                val indexed = entries.values.firstOrNull { it.key.bytesKey == bytesKey }
                val restoringPrevious = indexed?.generation != generation
                val resolved =
                    if (restoringPrevious) {
                        runCatching {
                            atomicMove(backup, root.resolve("$bytesKey.bin"))
                            true
                        }.getOrDefault(false)
                    } else {
                        runCatching { deleteManagedFile(backup) }.getOrDefault(false)
                    }
                if (!resolved && backup.exists()) {
                    val recoveryKey = keyHint ?: indexed?.key
                    if (restoringPrevious) recoveryKey?.let(rollbackPendingKeys::add)
                    rememberRecoveredTransientLocked(backup, recoveryKey)
                }
                return@forEach
            }
            val evictedBytesKey = evictedRollbackBytesKey(backup.name)
            if (evictedBytesKey != null) {
                val indexed = entries.values.firstOrNull { it.key.bytesKey == evictedBytesKey }
                val resolved =
                    if (indexed != null && !root.resolve(indexed.fileName).isFile) {
                        runCatching {
                            atomicMove(backup, root.resolve(indexed.fileName))
                            true
                        }.getOrDefault(false)
                    } else {
                        runCatching { deleteManagedFile(backup) }.getOrDefault(false)
                    }
                if (!resolved && backup.exists()) {
                    rememberRecoveredTransientLocked(backup, keyHint ?: indexed?.key)
                }
            } else {
                deleteRecoveredFileLocked(backup, keyHint)
            }
        }
    }

    private fun cleanupRecoveredTransientsLocked(
        matches: (SlockAndroidAvatarEntryKey?) -> Boolean
    ): Boolean {
        var cleaned = true
        val cleanedKeys = linkedSetOf<SlockAndroidAvatarEntryKey>()
        val iterator = recoveredTransients.entries.iterator()
        while (iterator.hasNext()) {
            val recovered = iterator.next().value
            if (!matches(recovered.key)) continue
            val deleted =
                !recovered.file.exists() ||
                    runCatching { deleteManagedFile(recovered.file) }.getOrDefault(false)
            if (deleted) {
                recovered.key?.let(cleanedKeys::add)
                iterator.remove()
            } else {
                cleaned = false
            }
        }
        rollbackPendingKeys.removeAll { pendingKey ->
            pendingKey in cleanedKeys && recoveredTransients.values.none { it.key == pendingKey }
        }
        return cleaned
    }

    private fun hasRecoveredArtifactsLocked(key: SlockAndroidAvatarEntryKey): Boolean =
        recoveredTransients.values.any { recovered -> recovered.key == null || recovered.key == key }

    private fun deleteRecoveredFileLocked(
        file: File,
        keyHint: SlockAndroidAvatarEntryKey?
    ): Boolean {
        val deleted = !file.exists() || runCatching { deleteManagedFile(file) }.getOrDefault(false)
        if (deleted) {
            recoveredTransients.remove(file.name)
        } else {
            rememberRecoveredTransientLocked(file, keyHint)
        }
        return deleted
    }

    private fun rememberRecoveredTransientLocked(
        file: File,
        keyHint: SlockAndroidAvatarEntryKey?
    ) {
        if (file.exists()) {
            recoveredTransients[file.name] =
                SlockAndroidAvatarRecoveredTransient(file, keyHint ?: transientKeyForFileLocked(file))
        }
    }

    private fun transientKeyForFileLocked(file: File): SlockAndroidAvatarEntryKey? {
        if (!file.name.endsWith(StageSuffix) && !file.name.endsWith(RollbackSuffix)) {
            return file.name.removeSuffix(".bin").takeIf { file.name.endsWith(".bin") }?.let { bytesKey ->
                entries.values.firstOrNull { it.key.bytesKey == bytesKey }?.key
            }
        }
        val full =
            Regex(
                "^(?:evict-[0-9]+-)?([0-9a-f]{64})\\.([0-9a-f]{64})\\.([0-9a-f]{64})(?:\\..+)?$"
            ).matchEntire(file.name)
        if (full != null) {
            return SlockAndroidAvatarEntryKey(
                partitionKey = full.groupValues[1],
                subjectKey = full.groupValues[2],
                bytesKey = full.groupValues[3]
            )
        }
        val legacyBytesKey =
            when {
                file.name.startsWith("evict-") ->
                    Regex("^evict-[0-9]+-([0-9a-f]{64})\\.rollback$")
                        .matchEntire(file.name)
                        ?.groupValues
                        ?.get(1)
                else ->
                    Regex("^([0-9a-f]{64})\\.[0-9]+(?:\\.replace)?\\.(?:stage|rollback)$")
                        .matchEntire(file.name)
                        ?.groupValues
                        ?.get(1)
            }
        return legacyBytesKey?.let { bytesKey ->
            entries.values.firstOrNull { it.key.bytesKey == bytesKey }?.key
        }
    }

    private fun replaceRollbackIdentity(fileName: String): Pair<String, Long>? {
        val current =
            Regex(
                "^[0-9a-f]{64}\\.[0-9a-f]{64}\\.([0-9a-f]{64})\\.([0-9]+)\\.replace\\.rollback$"
            ).matchEntire(fileName)
        if (current != null) {
            return current.groupValues[1] to (current.groupValues[2].toLongOrNull() ?: return null)
        }
        val legacy =
            Regex("^([0-9a-f]{64})\\.([0-9]+)\\.replace\\.rollback$").matchEntire(fileName)
                ?: return null
        return legacy.groupValues[1] to (legacy.groupValues[2].toLongOrNull() ?: return null)
    }

    private fun evictedRollbackBytesKey(fileName: String): String? {
        val current =
            Regex(
                "^evict-[0-9]+-[0-9a-f]{64}\\.[0-9a-f]{64}\\.([0-9a-f]{64})\\.rollback$"
            ).matchEntire(fileName)
        if (current != null) return current.groupValues[1]
        return Regex("^evict-[0-9]+-([0-9a-f]{64})\\.rollback$")
            .matchEntire(fileName)
            ?.groupValues
            ?.get(1)
    }

    private fun SlockAndroidAvatarEntryKey.transientPrefix(): String =
        "$partitionKey.$subjectKey.$bytesKey"

    private fun pruneBudgetLocked() {
        var total = entries.values.sumOf { it.sizeBytes }
        if (total <= maxManagedBytes) return
        entries.values
            .filterNot { isRollbackProtectedLocked(it.key) }
            .sortedWith(compareBy<SlockAndroidAvatarDiskEntry> { it.lastAccessMillis }.thenBy { it.key.bytesKey })
            .forEach { entry ->
                val file = root.resolve(entry.fileName)
                if (
                    total > maxManagedBytes &&
                    (!file.exists() || runCatching { deleteManagedFile(file) }.getOrDefault(false)) &&
                    entries.remove(entry.key) != null
                ) {
                    total -= entry.sizeBytes
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

    private fun cleanupRollbackFilesLocked(record: SlockAndroidAvatarRollbackRecord): Boolean {
        var cleaned = true
        record.previousBackupFile?.let { backup ->
            val deleted = !backup.exists() || runCatching { deleteManagedFile(backup) }.getOrDefault(false)
            if (deleted) recoveredTransients.remove(backup.name)
            cleaned = deleted && cleaned
        }
        record.evicted.forEach { evicted ->
            evicted.backupFile?.let { backup ->
                val deleted = !backup.exists() || runCatching { deleteManagedFile(backup) }.getOrDefault(false)
                if (deleted) recoveredTransients.remove(backup.name)
                cleaned = deleted && cleaned
            }
        }
        return cleaned
    }

    private fun rememberOpenRollbackLocked(record: SlockAndroidAvatarRollbackRecord) {
        if (openRollbackRecords.containsKey(record)) return
        val protectedKeys =
            buildSet {
                add(record.newEntry.key)
                record.previousEntry?.let { add(it.key) }
                record.evicted.forEach { add(it.entry.key) }
            }
        openRollbackRecords[record] = protectedKeys
        protectedKeys.forEach { key ->
            openRollbackProtectionCounts[key] = (openRollbackProtectionCounts[key] ?: 0) + 1
        }
    }

    private fun releaseOpenRollbackLocked(record: SlockAndroidAvatarRollbackRecord) {
        val protectedKeys = openRollbackRecords.remove(record) ?: return
        protectedKeys.forEach { key ->
            val remaining = (openRollbackProtectionCounts[key] ?: 1) - 1
            if (remaining <= 0) {
                openRollbackProtectionCounts.remove(key)
            } else {
                openRollbackProtectionCounts[key] = remaining
            }
        }
    }

    private fun isRollbackProtectedLocked(key: SlockAndroidAvatarEntryKey): Boolean =
        key in rollbackPendingKeys || (openRollbackProtectionCounts[key] ?: 0) > 0

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
