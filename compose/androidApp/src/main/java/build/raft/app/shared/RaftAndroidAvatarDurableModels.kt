package build.raft.app.shared

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale

internal val RaftAndroidAvatarOpaqueComponentPattern = Regex("[0-9a-f]{64}")

internal data class RaftAndroidAvatarOpaqueKey(
    val partitionKey: String,
    val ownerKey: String,
    val subjectKey: String,
    val bytesKey: String
) {
    init {
        require(partitionKey.matches(RaftAndroidAvatarOpaqueComponentPattern))
        require(ownerKey.matches(RaftAndroidAvatarOpaqueComponentPattern))
        require(subjectKey.matches(RaftAndroidAvatarOpaqueComponentPattern))
        require(bytesKey.matches(RaftAndroidAvatarOpaqueComponentPattern))
    }

    val relativeDirectory: String
        get() = "p/$partitionKey/o/$ownerKey/s/$subjectKey/b/$bytesKey"

    val indexPath: String
        get() = "$relativeDirectory/index"

    val journalPath: String
        get() = "$relativeDirectory/journal"

    fun matches(scope: RaftAndroidAvatarTerminalScope): Boolean =
        when (scope) {
            RaftAndroidAvatarTerminalScope.All -> true
            is RaftAndroidAvatarTerminalScope.Owner ->
                partitionKey == scope.partitionKey && ownerKey == scope.ownerKey
            is RaftAndroidAvatarTerminalScope.Subject ->
                partitionKey == scope.partitionKey &&
                    ownerKey == scope.ownerKey &&
                    subjectKey == scope.subjectKey
        }

    companion object {
        fun fromManagedPath(path: String): RaftAndroidAvatarOpaqueKey? {
            val match = ManagedPathPattern.matchEntire(path) ?: return null
            return runCatching {
                RaftAndroidAvatarOpaqueKey(
                    partitionKey = match.groupValues[1],
                    ownerKey = match.groupValues[2],
                    subjectKey = match.groupValues[3],
                    bytesKey = match.groupValues[4]
                )
            }.getOrNull()
        }

        private val ManagedPathPattern =
            Regex(
                "p/([0-9a-f]{64})/o/([0-9a-f]{64})/s/([0-9a-f]{64})/" +
                    "b/([0-9a-f]{64})/[^/]+"
            )
    }
}

internal sealed interface RaftAndroidAvatarTerminalScope {
    data object All : RaftAndroidAvatarTerminalScope

    data class Owner(
        val partitionKey: String,
        val ownerKey: String
    ) : RaftAndroidAvatarTerminalScope {
        init {
            require(partitionKey.matches(RaftAndroidAvatarOpaqueComponentPattern))
            require(ownerKey.matches(RaftAndroidAvatarOpaqueComponentPattern))
        }
    }

    data class Subject(
        val partitionKey: String,
        val ownerKey: String,
        val subjectKey: String
    ) : RaftAndroidAvatarTerminalScope {
        init {
            require(partitionKey.matches(RaftAndroidAvatarOpaqueComponentPattern))
            require(ownerKey.matches(RaftAndroidAvatarOpaqueComponentPattern))
            require(subjectKey.matches(RaftAndroidAvatarOpaqueComponentPattern))
        }
    }
}

internal val RaftAndroidAvatarTerminalScope.markerPath: String
    get() =
        when (this) {
            RaftAndroidAvatarTerminalScope.All -> "terminal-all"
            is RaftAndroidAvatarTerminalScope.Owner ->
                "terminal-owner-$partitionKey-$ownerKey"
            is RaftAndroidAvatarTerminalScope.Subject ->
                "terminal-subject-$partitionKey-$ownerKey-$subjectKey"
        }

internal fun raftAndroidAvatarTerminalScopeFromMarkerPath(
    path: String
): RaftAndroidAvatarTerminalScope? {
    if (path == "terminal-all") return RaftAndroidAvatarTerminalScope.All
    OwnerTerminalMarkerPattern.matchEntire(path)?.let { match ->
        return RaftAndroidAvatarTerminalScope.Owner(
            partitionKey = match.groupValues[1],
            ownerKey = match.groupValues[2]
        )
    }
    SubjectTerminalMarkerPattern.matchEntire(path)?.let { match ->
        return RaftAndroidAvatarTerminalScope.Subject(
            partitionKey = match.groupValues[1],
            ownerKey = match.groupValues[2],
            subjectKey = match.groupValues[3]
        )
    }
    return null
}

private val OwnerTerminalMarkerPattern =
    Regex("terminal-owner-([0-9a-f]{64})-([0-9a-f]{64})")
private val SubjectTerminalMarkerPattern =
    Regex("terminal-subject-([0-9a-f]{64})-([0-9a-f]{64})-([0-9a-f]{64})")

internal data class RaftAndroidAvatarCommitIdentity(
    val commitGeneration: Long,
    val nonce: String
) {
    init {
        require(commitGeneration > 0L)
        require(nonce.matches(RaftAndroidAvatarOpaqueComponentPattern))
    }
}

internal data class RaftAndroidAvatarEntryIdentity(
    val key: RaftAndroidAvatarOpaqueKey,
    val nativeRevision: String,
    val txId: String,
    val commitIdentity: RaftAndroidAvatarCommitIdentity,
    val fileIdentity: String,
    val sha256: String,
    val byteLength: Long,
    val contentType: String
) {
    init {
        require(nativeRevision.matches(RaftAndroidAvatarOpaqueComponentPattern))
        require(txId.matches(RaftAndroidAvatarOpaqueComponentPattern))
        require(fileIdentity.matches(RaftAndroidAvatarOpaqueComponentPattern))
        require(sha256.matches(RaftAndroidAvatarOpaqueComponentPattern))
        require(byteLength >= 0L)
        require(contentType == contentType.raftNormalizedAvatarContentType())
    }

    val entryPath: String
        get() = "${key.relativeDirectory}/entry-$nativeRevision-$fileIdentity"

    val stagePath: String
        get() = "${key.relativeDirectory}/stage-$txId-$fileIdentity"
}

internal data class RaftAndroidAvatarEntryRecord(
    val identity: RaftAndroidAvatarEntryIdentity,
    val lastAccessOrdinal: Long
) {
    init {
        require(lastAccessOrdinal >= 0L)
    }
}

internal data class RaftAndroidAvatarTerminalEpochs(
    val global: Long,
    val owner: Long,
    val subject: Long
) {
    init {
        require(global >= 0L)
        require(owner >= 0L)
        require(subject >= 0L)
    }
}

internal enum class RaftAndroidAvatarDurablePhase {
    Idle,
    Prepared,
    CandidateDurable,
    SharedCommitted,
    CandidateRenamed,
    IndexSwitched,
    RollbackRepointed,
    CommitCleanup,
    RollbackCleanup
}

internal enum class RaftAndroidAvatarTombstoneReason {
    InvalidBytes,
    AuthoritativeRetirement,
    BudgetEviction,
    TerminalRetirement,
    CorruptMetadata,
    OrphanArtifact,
    RecoveryFailure
}

internal data class RaftAndroidAvatarDurableState(
    val key: RaftAndroidAvatarOpaqueKey,
    val stateSequence: Long = 0L,
    /**
     * Highest generation reserved inside this live native key namespace. Prepared writes,
     * rollback, ordinary retirement, and restart never lower it. A successful terminal retirement
     * physically removes the whole key namespace, including this authority; the next canonical
     * full-body fetch starts a fresh namespace rather than retaining per-key history forever.
     */
    val commitGenerationHighWater: Long,
    val phase: RaftAndroidAvatarDurablePhase,
    val transactionId: String?,
    val capturedEpochs: RaftAndroidAvatarTerminalEpochs?,
    val current: RaftAndroidAvatarEntryRecord?,
    val previous: RaftAndroidAvatarEntryRecord?,
    val candidate: RaftAndroidAvatarEntryRecord?,
    val cleanupEntries: List<RaftAndroidAvatarEntryIdentity>,
    val cleanupArtifacts: Set<String>,
    val tombstoneReason: RaftAndroidAvatarTombstoneReason?
) {
    init {
        require(stateSequence >= 0L)
        require(commitGenerationHighWater >= 0L)
        transactionId?.let { require(it.matches(RaftAndroidAvatarOpaqueComponentPattern)) }
        require(current?.identity?.key == null || current.identity.key == key)
        require(previous?.identity?.key == null || previous.identity.key == key)
        require(candidate?.identity?.key == null || candidate.identity.key == key)
        require(candidate == null || candidate.identity.txId == transactionId)
        require(cleanupEntries.all { it.key == key })
        require(
            allEntryIdentities().all { identity ->
                identity.commitIdentity.commitGeneration <= commitGenerationHighWater
            }
        )
        require(cleanupEntries.size <= MAX_CLEANUP_ARTIFACTS)
        require(cleanupArtifacts.size <= MAX_CLEANUP_ARTIFACTS)
        require(cleanupArtifacts.all { path -> key.ownsCleanupArtifactPath(path) })
        if (phase == RaftAndroidAvatarDurablePhase.Idle) {
            require(transactionId == null)
            require(capturedEpochs == null)
            require(previous == null)
            require(candidate == null)
            require(cleanupEntries.isEmpty())
            require(cleanupArtifacts.isEmpty())
            require(tombstoneReason == null)
        } else {
            require(transactionId != null)
            require(capturedEpochs != null)
            when (phase) {
                RaftAndroidAvatarDurablePhase.Prepared,
                RaftAndroidAvatarDurablePhase.CandidateDurable,
                RaftAndroidAvatarDurablePhase.SharedCommitted,
                RaftAndroidAvatarDurablePhase.CandidateRenamed -> {
                    require(candidate != null)
                    require(current == previous)
                }
                RaftAndroidAvatarDurablePhase.IndexSwitched,
                RaftAndroidAvatarDurablePhase.CommitCleanup -> {
                    require(candidate != null)
                    require(current == candidate)
                }
                RaftAndroidAvatarDurablePhase.RollbackRepointed,
                RaftAndroidAvatarDurablePhase.RollbackCleanup -> Unit
                RaftAndroidAvatarDurablePhase.Idle -> error("Idle handled above")
            }
        }
    }

    fun allEntryRecords(): List<RaftAndroidAvatarEntryRecord> =
        listOfNotNull(current, previous, candidate)

    fun allEntryIdentities(): Set<RaftAndroidAvatarEntryIdentity> =
        buildSet {
            allEntryRecords().forEach { add(it.identity) }
            cleanupEntries.forEach(::add)
        }

    companion object {
        const val MAX_CLEANUP_ARTIFACTS = 256

        fun idle(
            key: RaftAndroidAvatarOpaqueKey,
            current: RaftAndroidAvatarEntryRecord? = null,
            commitGenerationHighWater: Long =
                current?.identity?.commitIdentity?.commitGeneration ?: 0L
        ): RaftAndroidAvatarDurableState =
            RaftAndroidAvatarDurableState(
                key = key,
                commitGenerationHighWater = commitGenerationHighWater,
                phase = RaftAndroidAvatarDurablePhase.Idle,
                transactionId = null,
                capturedEpochs = null,
                current = current,
                previous = null,
                candidate = null,
                cleanupEntries = emptyList(),
                cleanupArtifacts = emptySet(),
                tombstoneReason = null
            )
    }
}

internal fun RaftAndroidAvatarOpaqueKey.ownsManagedPath(path: String): Boolean {
    if (!path.startsWith("$relativeDirectory/")) return false
    val name = path.substringAfterLast('/')
    return name == "index" ||
        name == "journal" ||
        name.startsWith("entry-") ||
        name.startsWith("stage-") ||
        name.startsWith("metadata-tmp-")
}

internal fun RaftAndroidAvatarOpaqueKey.ownsCleanupArtifactPath(path: String): Boolean =
    ownsManagedPath(path) &&
        (path.isRaftAndroidAvatarDataPath() ||
            path.substringAfterLast('/').startsWith("metadata-tmp-"))

internal fun String.isRaftAndroidAvatarDataPath(): Boolean {
    val name = substringAfterLast('/')
    return name.startsWith("entry-") || name.startsWith("stage-")
}

internal fun String.raftNormalizedAvatarContentType(): String =
    trim()
        .substringBefore(';')
        .trim()
        .lowercase(Locale.ROOT)
        .also { normalized ->
            require(normalized.matches(ContentTypePattern))
        }

private val ContentTypePattern = Regex("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+")

internal fun ByteArray.raftSha256Hex(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

internal interface RaftAndroidAvatarIdentityFactory {
    fun nextOpaqueIdentity(): String
}

internal class RaftAndroidAvatarSecureIdentityFactory : RaftAndroidAvatarIdentityFactory {
    private val random = SecureRandom()

    override fun nextOpaqueIdentity(): String =
        ByteArray(32)
            .also(random::nextBytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
}

internal enum class RaftAndroidAvatarDurableRecordKind {
    Index,
    Journal
}

internal object RaftAndroidAvatarDurableStateCodec {
    private const val Magic = 0x52414654
    private const val SchemaVersion = 2
    private const val DigestLength = 32
    private const val MaxRecordBytes = 256 * 1024

    fun encode(
        kind: RaftAndroidAvatarDurableRecordKind,
        state: RaftAndroidAvatarDurableState
    ): ByteArray {
        val payload =
            ByteArrayOutputStream().use { byteOutput ->
                DataOutputStream(byteOutput).use { output ->
                    output.writeInt(Magic)
                    output.writeInt(SchemaVersion)
                    output.writeUTF(kind.name)
                    output.writeKey(state.key)
                    output.writeLong(state.stateSequence)
                    output.writeLong(state.commitGenerationHighWater)
                    output.writeUTF(state.phase.name)
                    output.writeNullableString(state.transactionId)
                    output.writeNullableEpochs(state.capturedEpochs)
                    output.writeNullableRecord(state.current)
                    output.writeNullableRecord(state.previous)
                    output.writeNullableRecord(state.candidate)
                    output.writeInt(state.cleanupEntries.size)
                    state.cleanupEntries.forEach { identity -> output.writeIdentity(identity) }
                    output.writeInt(state.cleanupArtifacts.size)
                    state.cleanupArtifacts.sorted().forEach(output::writeUTF)
                    output.writeNullableString(state.tombstoneReason?.name)
                }
                byteOutput.toByteArray()
            }
        val digest = MessageDigest.getInstance("SHA-256").digest(payload)
        return digest + payload
    }

    fun decode(
        expectedKind: RaftAndroidAvatarDurableRecordKind,
        bytes: ByteArray
    ): RaftAndroidAvatarDurableState? =
        runCatching {
            require(bytes.size in (DigestLength + 1)..MaxRecordBytes)
            val expectedDigest = bytes.copyOfRange(0, DigestLength)
            val payload = bytes.copyOfRange(DigestLength, bytes.size)
            val actualDigest = MessageDigest.getInstance("SHA-256").digest(payload)
            require(MessageDigest.isEqual(expectedDigest, actualDigest))
            DataInputStream(ByteArrayInputStream(payload)).use { input ->
                require(input.readInt() == Magic)
                require(input.readInt() == SchemaVersion)
                require(input.readUTF() == expectedKind.name)
                val key = input.readKey()
                val stateSequence = input.readLong()
                val commitGenerationHighWater = input.readLong()
                val phase = RaftAndroidAvatarDurablePhase.valueOf(input.readUTF())
                val transactionId = input.readNullableString()
                val epochs = input.readNullableEpochs()
                val current = input.readNullableRecord()
                val previous = input.readNullableRecord()
                val candidate = input.readNullableRecord()
                val cleanupEntryCount = input.readBoundedCount()
                val cleanupEntries = List(cleanupEntryCount) { input.readIdentity() }
                val cleanupArtifactCount = input.readBoundedCount()
                val cleanupArtifacts = buildSet {
                    repeat(cleanupArtifactCount) { add(input.readUTF()) }
                }
                val tombstoneReason =
                    input.readNullableString()?.let(RaftAndroidAvatarTombstoneReason::valueOf)
                require(input.available() == 0)
                RaftAndroidAvatarDurableState(
                    key = key,
                    stateSequence = stateSequence,
                    commitGenerationHighWater = commitGenerationHighWater,
                    phase = phase,
                    transactionId = transactionId,
                    capturedEpochs = epochs,
                    current = current,
                    previous = previous,
                    candidate = candidate,
                    cleanupEntries = cleanupEntries,
                    cleanupArtifacts = cleanupArtifacts,
                    tombstoneReason = tombstoneReason
                )
            }
        }.getOrNull()

    private fun DataOutputStream.writeKey(key: RaftAndroidAvatarOpaqueKey) {
        writeUTF(key.partitionKey)
        writeUTF(key.ownerKey)
        writeUTF(key.subjectKey)
        writeUTF(key.bytesKey)
    }

    private fun DataInputStream.readKey(): RaftAndroidAvatarOpaqueKey =
        RaftAndroidAvatarOpaqueKey(
            partitionKey = readUTF(),
            ownerKey = readUTF(),
            subjectKey = readUTF(),
            bytesKey = readUTF()
        )

    private fun DataOutputStream.writeIdentity(identity: RaftAndroidAvatarEntryIdentity) {
        writeKey(identity.key)
        writeUTF(identity.nativeRevision)
        writeUTF(identity.txId)
        writeLong(identity.commitIdentity.commitGeneration)
        writeUTF(identity.commitIdentity.nonce)
        writeUTF(identity.fileIdentity)
        writeUTF(identity.sha256)
        writeLong(identity.byteLength)
        writeUTF(identity.contentType)
    }

    private fun DataInputStream.readIdentity(): RaftAndroidAvatarEntryIdentity =
        RaftAndroidAvatarEntryIdentity(
            key = readKey(),
            nativeRevision = readUTF(),
            txId = readUTF(),
            commitIdentity =
                RaftAndroidAvatarCommitIdentity(
                    commitGeneration = readLong(),
                    nonce = readUTF()
                ),
            fileIdentity = readUTF(),
            sha256 = readUTF(),
            byteLength = readLong(),
            contentType = readUTF()
        )

    private fun DataOutputStream.writeNullableRecord(record: RaftAndroidAvatarEntryRecord?) {
        writeBoolean(record != null)
        if (record != null) {
            writeIdentity(record.identity)
            writeLong(record.lastAccessOrdinal)
        }
    }

    private fun DataInputStream.readNullableRecord(): RaftAndroidAvatarEntryRecord? =
        if (readBoolean()) {
            RaftAndroidAvatarEntryRecord(
                identity = readIdentity(),
                lastAccessOrdinal = readLong()
            )
        } else {
            null
        }

    private fun DataOutputStream.writeNullableEpochs(epochs: RaftAndroidAvatarTerminalEpochs?) {
        writeBoolean(epochs != null)
        if (epochs != null) {
            writeLong(epochs.global)
            writeLong(epochs.owner)
            writeLong(epochs.subject)
        }
    }

    private fun DataInputStream.readNullableEpochs(): RaftAndroidAvatarTerminalEpochs? =
        if (readBoolean()) {
            RaftAndroidAvatarTerminalEpochs(
                global = readLong(),
                owner = readLong(),
                subject = readLong()
            )
        } else {
            null
        }

    private fun DataOutputStream.writeNullableString(value: String?) {
        writeBoolean(value != null)
        value?.let(::writeUTF)
    }

    private fun DataInputStream.readNullableString(): String? =
        if (readBoolean()) readUTF() else null

    private fun DataInputStream.readBoundedCount(): Int =
        readInt().also { count ->
            require(count in 0..RaftAndroidAvatarDurableState.MAX_CLEANUP_ARTIFACTS)
        }
}
