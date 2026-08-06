package build.raft.app.shared

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale

internal val SlockAndroidAvatarOpaqueComponentPattern = Regex("[0-9a-f]{64}")

internal data class SlockAndroidAvatarOpaqueKey(
    val partitionKey: String,
    val ownerKey: String,
    val subjectKey: String,
    val bytesKey: String
) {
    init {
        require(partitionKey.matches(SlockAndroidAvatarOpaqueComponentPattern))
        require(ownerKey.matches(SlockAndroidAvatarOpaqueComponentPattern))
        require(subjectKey.matches(SlockAndroidAvatarOpaqueComponentPattern))
        require(bytesKey.matches(SlockAndroidAvatarOpaqueComponentPattern))
    }

    val relativeDirectory: String
        get() = "p/$partitionKey/o/$ownerKey/s/$subjectKey/b/$bytesKey"

    val indexPath: String
        get() = "$relativeDirectory/index"

    val journalPath: String
        get() = "$relativeDirectory/journal"

    fun matches(scope: SlockAndroidAvatarTerminalScope): Boolean =
        when (scope) {
            SlockAndroidAvatarTerminalScope.All -> true
            is SlockAndroidAvatarTerminalScope.Owner ->
                partitionKey == scope.partitionKey && ownerKey == scope.ownerKey
            is SlockAndroidAvatarTerminalScope.Subject ->
                partitionKey == scope.partitionKey &&
                    ownerKey == scope.ownerKey &&
                    subjectKey == scope.subjectKey
        }

    companion object {
        fun fromManagedPath(path: String): SlockAndroidAvatarOpaqueKey? {
            val match = ManagedPathPattern.matchEntire(path) ?: return null
            return runCatching {
                SlockAndroidAvatarOpaqueKey(
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

internal sealed interface SlockAndroidAvatarTerminalScope {
    data object All : SlockAndroidAvatarTerminalScope

    data class Owner(
        val partitionKey: String,
        val ownerKey: String
    ) : SlockAndroidAvatarTerminalScope {
        init {
            require(partitionKey.matches(SlockAndroidAvatarOpaqueComponentPattern))
            require(ownerKey.matches(SlockAndroidAvatarOpaqueComponentPattern))
        }
    }

    data class Subject(
        val partitionKey: String,
        val ownerKey: String,
        val subjectKey: String
    ) : SlockAndroidAvatarTerminalScope {
        init {
            require(partitionKey.matches(SlockAndroidAvatarOpaqueComponentPattern))
            require(ownerKey.matches(SlockAndroidAvatarOpaqueComponentPattern))
            require(subjectKey.matches(SlockAndroidAvatarOpaqueComponentPattern))
        }
    }
}

internal val SlockAndroidAvatarTerminalScope.markerPath: String
    get() =
        when (this) {
            SlockAndroidAvatarTerminalScope.All -> "terminal-all"
            is SlockAndroidAvatarTerminalScope.Owner ->
                "terminal-owner-$partitionKey-$ownerKey"
            is SlockAndroidAvatarTerminalScope.Subject ->
                "terminal-subject-$partitionKey-$ownerKey-$subjectKey"
        }

internal fun slockAndroidAvatarTerminalScopeFromMarkerPath(
    path: String
): SlockAndroidAvatarTerminalScope? {
    if (path == "terminal-all") return SlockAndroidAvatarTerminalScope.All
    OwnerTerminalMarkerPattern.matchEntire(path)?.let { match ->
        return SlockAndroidAvatarTerminalScope.Owner(
            partitionKey = match.groupValues[1],
            ownerKey = match.groupValues[2]
        )
    }
    SubjectTerminalMarkerPattern.matchEntire(path)?.let { match ->
        return SlockAndroidAvatarTerminalScope.Subject(
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

internal data class SlockAndroidAvatarCommitIdentity(
    val commitGeneration: Long,
    val nonce: String
) {
    init {
        require(commitGeneration > 0L)
        require(nonce.matches(SlockAndroidAvatarOpaqueComponentPattern))
    }
}

internal data class SlockAndroidAvatarEntryIdentity(
    val key: SlockAndroidAvatarOpaqueKey,
    val nativeRevision: String,
    val txId: String,
    val commitIdentity: SlockAndroidAvatarCommitIdentity,
    val fileIdentity: String,
    val sha256: String,
    val byteLength: Long,
    val contentType: String
) {
    init {
        require(nativeRevision.matches(SlockAndroidAvatarOpaqueComponentPattern))
        require(txId.matches(SlockAndroidAvatarOpaqueComponentPattern))
        require(fileIdentity.matches(SlockAndroidAvatarOpaqueComponentPattern))
        require(sha256.matches(SlockAndroidAvatarOpaqueComponentPattern))
        require(byteLength >= 0L)
        require(contentType == contentType.slockNormalizedAvatarContentType())
    }

    val entryPath: String
        get() = "${key.relativeDirectory}/entry-$nativeRevision-$fileIdentity"

    val stagePath: String
        get() = "${key.relativeDirectory}/stage-$txId-$fileIdentity"
}

internal data class SlockAndroidAvatarEntryRecord(
    val identity: SlockAndroidAvatarEntryIdentity,
    val lastAccessOrdinal: Long
) {
    init {
        require(lastAccessOrdinal >= 0L)
    }
}

internal data class SlockAndroidAvatarTerminalEpochs(
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

internal enum class SlockAndroidAvatarDurablePhase {
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

internal enum class SlockAndroidAvatarTombstoneReason {
    InvalidBytes,
    AuthoritativeRetirement,
    BudgetEviction,
    TerminalRetirement,
    CorruptMetadata,
    OrphanArtifact,
    RecoveryFailure
}

internal data class SlockAndroidAvatarDurableState(
    val key: SlockAndroidAvatarOpaqueKey,
    val stateSequence: Long = 0L,
    val phase: SlockAndroidAvatarDurablePhase,
    val transactionId: String?,
    val capturedEpochs: SlockAndroidAvatarTerminalEpochs?,
    val current: SlockAndroidAvatarEntryRecord?,
    val previous: SlockAndroidAvatarEntryRecord?,
    val candidate: SlockAndroidAvatarEntryRecord?,
    val cleanupEntries: List<SlockAndroidAvatarEntryIdentity>,
    val cleanupArtifacts: Set<String>,
    val tombstoneReason: SlockAndroidAvatarTombstoneReason?
) {
    init {
        require(stateSequence >= 0L)
        transactionId?.let { require(it.matches(SlockAndroidAvatarOpaqueComponentPattern)) }
        require(current?.identity?.key == null || current.identity.key == key)
        require(previous?.identity?.key == null || previous.identity.key == key)
        require(candidate?.identity?.key == null || candidate.identity.key == key)
        require(candidate == null || candidate.identity.txId == transactionId)
        require(cleanupEntries.all { it.key == key })
        require(cleanupEntries.size <= MAX_CLEANUP_ARTIFACTS)
        require(cleanupArtifacts.size <= MAX_CLEANUP_ARTIFACTS)
        require(cleanupArtifacts.all { path -> key.ownsCleanupArtifactPath(path) })
        if (phase == SlockAndroidAvatarDurablePhase.Idle) {
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
                SlockAndroidAvatarDurablePhase.Prepared,
                SlockAndroidAvatarDurablePhase.CandidateDurable,
                SlockAndroidAvatarDurablePhase.SharedCommitted,
                SlockAndroidAvatarDurablePhase.CandidateRenamed -> {
                    require(candidate != null)
                    require(current == previous)
                }
                SlockAndroidAvatarDurablePhase.IndexSwitched,
                SlockAndroidAvatarDurablePhase.CommitCleanup -> {
                    require(candidate != null)
                    require(current == candidate)
                }
                SlockAndroidAvatarDurablePhase.RollbackRepointed,
                SlockAndroidAvatarDurablePhase.RollbackCleanup -> Unit
                SlockAndroidAvatarDurablePhase.Idle -> error("Idle handled above")
            }
        }
    }

    fun allEntryRecords(): List<SlockAndroidAvatarEntryRecord> =
        listOfNotNull(current, previous, candidate)

    fun allEntryIdentities(): Set<SlockAndroidAvatarEntryIdentity> =
        buildSet {
            allEntryRecords().forEach { add(it.identity) }
            cleanupEntries.forEach(::add)
        }

    companion object {
        const val MAX_CLEANUP_ARTIFACTS = 256

        fun idle(
            key: SlockAndroidAvatarOpaqueKey,
            current: SlockAndroidAvatarEntryRecord? = null
        ): SlockAndroidAvatarDurableState =
            SlockAndroidAvatarDurableState(
                key = key,
                phase = SlockAndroidAvatarDurablePhase.Idle,
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

internal fun SlockAndroidAvatarOpaqueKey.ownsManagedPath(path: String): Boolean {
    if (!path.startsWith("$relativeDirectory/")) return false
    val name = path.substringAfterLast('/')
    return name == "index" ||
        name == "journal" ||
        name.startsWith("entry-") ||
        name.startsWith("stage-") ||
        name.startsWith("metadata-tmp-")
}

internal fun SlockAndroidAvatarOpaqueKey.ownsCleanupArtifactPath(path: String): Boolean =
    ownsManagedPath(path) &&
        (path.isSlockAndroidAvatarDataPath() ||
            path.substringAfterLast('/').startsWith("metadata-tmp-"))

internal fun String.isSlockAndroidAvatarDataPath(): Boolean {
    val name = substringAfterLast('/')
    return name.startsWith("entry-") || name.startsWith("stage-")
}

internal fun String.slockNormalizedAvatarContentType(): String =
    trim()
        .substringBefore(';')
        .trim()
        .lowercase(Locale.ROOT)
        .also { normalized ->
            require(normalized.matches(ContentTypePattern))
        }

private val ContentTypePattern = Regex("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+")

internal fun ByteArray.slockSha256Hex(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

internal interface SlockAndroidAvatarIdentityFactory {
    fun nextOpaqueIdentity(): String
}

internal class SlockAndroidAvatarSecureIdentityFactory : SlockAndroidAvatarIdentityFactory {
    private val random = SecureRandom()

    override fun nextOpaqueIdentity(): String =
        ByteArray(32)
            .also(random::nextBytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
}

internal enum class SlockAndroidAvatarDurableRecordKind {
    Index,
    Journal
}

internal object SlockAndroidAvatarDurableStateCodec {
    private const val Magic = 0x52414654
    private const val SchemaVersion = 1
    private const val DigestLength = 32
    private const val MaxRecordBytes = 256 * 1024

    fun encode(
        kind: SlockAndroidAvatarDurableRecordKind,
        state: SlockAndroidAvatarDurableState
    ): ByteArray {
        val payload =
            ByteArrayOutputStream().use { byteOutput ->
                DataOutputStream(byteOutput).use { output ->
                    output.writeInt(Magic)
                    output.writeInt(SchemaVersion)
                    output.writeUTF(kind.name)
                    output.writeKey(state.key)
                    output.writeLong(state.stateSequence)
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
        expectedKind: SlockAndroidAvatarDurableRecordKind,
        bytes: ByteArray
    ): SlockAndroidAvatarDurableState? =
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
                val phase = SlockAndroidAvatarDurablePhase.valueOf(input.readUTF())
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
                    input.readNullableString()?.let(SlockAndroidAvatarTombstoneReason::valueOf)
                require(input.available() == 0)
                SlockAndroidAvatarDurableState(
                    key = key,
                    stateSequence = stateSequence,
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

    private fun DataOutputStream.writeKey(key: SlockAndroidAvatarOpaqueKey) {
        writeUTF(key.partitionKey)
        writeUTF(key.ownerKey)
        writeUTF(key.subjectKey)
        writeUTF(key.bytesKey)
    }

    private fun DataInputStream.readKey(): SlockAndroidAvatarOpaqueKey =
        SlockAndroidAvatarOpaqueKey(
            partitionKey = readUTF(),
            ownerKey = readUTF(),
            subjectKey = readUTF(),
            bytesKey = readUTF()
        )

    private fun DataOutputStream.writeIdentity(identity: SlockAndroidAvatarEntryIdentity) {
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

    private fun DataInputStream.readIdentity(): SlockAndroidAvatarEntryIdentity =
        SlockAndroidAvatarEntryIdentity(
            key = readKey(),
            nativeRevision = readUTF(),
            txId = readUTF(),
            commitIdentity =
                SlockAndroidAvatarCommitIdentity(
                    commitGeneration = readLong(),
                    nonce = readUTF()
                ),
            fileIdentity = readUTF(),
            sha256 = readUTF(),
            byteLength = readLong(),
            contentType = readUTF()
        )

    private fun DataOutputStream.writeNullableRecord(record: SlockAndroidAvatarEntryRecord?) {
        writeBoolean(record != null)
        if (record != null) {
            writeIdentity(record.identity)
            writeLong(record.lastAccessOrdinal)
        }
    }

    private fun DataInputStream.readNullableRecord(): SlockAndroidAvatarEntryRecord? =
        if (readBoolean()) {
            SlockAndroidAvatarEntryRecord(
                identity = readIdentity(),
                lastAccessOrdinal = readLong()
            )
        } else {
            null
        }

    private fun DataOutputStream.writeNullableEpochs(epochs: SlockAndroidAvatarTerminalEpochs?) {
        writeBoolean(epochs != null)
        if (epochs != null) {
            writeLong(epochs.global)
            writeLong(epochs.owner)
            writeLong(epochs.subject)
        }
    }

    private fun DataInputStream.readNullableEpochs(): SlockAndroidAvatarTerminalEpochs? =
        if (readBoolean()) {
            SlockAndroidAvatarTerminalEpochs(
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
            require(count in 0..SlockAndroidAvatarDurableState.MAX_CLEANUP_ARTIFACTS)
        }
}
