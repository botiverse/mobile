package build.raft.app.shared

import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

internal enum class FakeAvatarFileOperation {
    Read,
    List,
    Size,
    AtomicWrite,
    NewWrite,
    Move,
    Delete,
    SyncParent
}

internal data class FakeAvatarFileEvent(
    val operation: FakeAvatarFileOperation,
    val path: String,
    val secondPath: String? = null
)

internal class FakeAvatarDurableFileSystem : SlockAndroidAvatarDurableFileSystem {
    private data class Failure(
        val operation: FakeAvatarFileOperation,
        val path: String?,
        val afterMutation: Boolean
    )

    private val bytesByPath = linkedMapOf<String, ByteArray>()
    private val failures = mutableListOf<Failure>()
    val events = mutableListOf<FakeAvatarFileEvent>()

    @Synchronized
    override fun read(path: String): SlockAndroidAvatarFileRead {
        events += FakeAvatarFileEvent(FakeAvatarFileOperation.Read, path)
        if (consumeFailure(FakeAvatarFileOperation.Read, path, afterMutation = false)) {
            return SlockAndroidAvatarFileRead.Failed
        }
        return bytesByPath[path]
            ?.copyOf()
            ?.let(SlockAndroidAvatarFileRead::Present)
            ?: SlockAndroidAvatarFileRead.Missing
    }

    @Synchronized
    override fun listFiles(): Set<String>? {
        events += FakeAvatarFileEvent(FakeAvatarFileOperation.List, "")
        if (consumeFailure(FakeAvatarFileOperation.List, null, afterMutation = false)) return null
        return bytesByPath.keys.toSet()
    }

    @Synchronized
    override fun fileSize(path: String): Long? {
        events += FakeAvatarFileEvent(FakeAvatarFileOperation.Size, path)
        if (consumeFailure(FakeAvatarFileOperation.Size, path, afterMutation = false)) return null
        return bytesByPath[path]?.size?.toLong()
    }

    @Synchronized
    override fun exists(path: String): Boolean = path in bytesByPath

    @Synchronized
    override fun writeAtomically(path: String, bytes: ByteArray): Boolean {
        events += FakeAvatarFileEvent(FakeAvatarFileOperation.AtomicWrite, path)
        if (consumeFailure(FakeAvatarFileOperation.AtomicWrite, path, afterMutation = false)) {
            return false
        }
        bytesByPath[path] = bytes.copyOf()
        return !consumeFailure(FakeAvatarFileOperation.AtomicWrite, path, afterMutation = true)
    }

    @Synchronized
    override fun writeNewDurably(path: String, bytes: ByteArray): Boolean {
        events += FakeAvatarFileEvent(FakeAvatarFileOperation.NewWrite, path)
        if (consumeFailure(FakeAvatarFileOperation.NewWrite, path, afterMutation = false)) {
            return false
        }
        if (path in bytesByPath) return false
        bytesByPath[path] = bytes.copyOf()
        return !consumeFailure(FakeAvatarFileOperation.NewWrite, path, afterMutation = true)
    }

    @Synchronized
    override fun moveAtomically(fromPath: String, toPath: String): Boolean {
        events += FakeAvatarFileEvent(FakeAvatarFileOperation.Move, fromPath, toPath)
        if (consumeFailure(FakeAvatarFileOperation.Move, fromPath, afterMutation = false)) {
            return false
        }
        val bytes = bytesByPath[fromPath] ?: return false
        if (toPath in bytesByPath) return false
        bytesByPath.remove(fromPath)
        bytesByPath[toPath] = bytes
        return !consumeFailure(FakeAvatarFileOperation.Move, fromPath, afterMutation = true)
    }

    @Synchronized
    override fun deleteDurably(path: String): Boolean {
        events += FakeAvatarFileEvent(FakeAvatarFileOperation.Delete, path)
        if (consumeFailure(FakeAvatarFileOperation.Delete, path, afterMutation = false)) {
            return false
        }
        bytesByPath.remove(path)
        return !consumeFailure(FakeAvatarFileOperation.Delete, path, afterMutation = true)
    }

    @Synchronized
    override fun syncParent(path: String): Boolean {
        events += FakeAvatarFileEvent(FakeAvatarFileOperation.SyncParent, path)
        return !consumeFailure(FakeAvatarFileOperation.SyncParent, path, afterMutation = false)
    }

    @Synchronized
    fun failNext(
        operation: FakeAvatarFileOperation,
        path: String? = null,
        afterMutation: Boolean = false
    ) {
        failures += Failure(operation, path, afterMutation)
    }

    @Synchronized
    fun clearFailures() {
        failures.clear()
    }

    @Synchronized
    fun corrupt(path: String) {
        check(path in bytesByPath) { "No file to corrupt: $path" }
        bytesByPath[path] = "corrupt-$path".toByteArray()
    }

    @Synchronized
    fun removeWithoutSync(path: String) {
        bytesByPath.remove(path)
    }

    @Synchronized
    fun put(path: String, bytes: ByteArray) {
        bytesByPath[path] = bytes.copyOf()
    }

    @Synchronized
    fun bytes(path: String): ByteArray? = bytesByPath[path]?.copyOf()

    @Synchronized
    fun paths(): Set<String> = bytesByPath.keys.toSet()

    @Synchronized
    private fun consumeFailure(
        operation: FakeAvatarFileOperation,
        path: String?,
        afterMutation: Boolean
    ): Boolean {
        val index = failures.indexOfFirst { failure ->
            failure.operation == operation &&
                failure.afterMutation == afterMutation &&
                (failure.path == null || failure.path == path)
        }
        if (index < 0) return false
        failures.removeAt(index)
        return true
    }
}

internal class DeterministicAvatarIdentityFactory : SlockAndroidAvatarIdentityFactory {
    private val next = AtomicLong(1L)

    override fun nextOpaqueIdentity(): String =
        "avatar-identity-${next.getAndIncrement()}".toByteArray().slockSha256Hex()
}

internal class FailableAvatarDecodedIdentityStore(
    private val beforeMatchingEviction: ((SlockAndroidAvatarTerminalScope) -> Unit)? = null
) : SlockAndroidAvatarDecodedIdentityStore {
    private val delegate = SlockAndroidAvatarInMemoryDecodedIdentityStore()
    var failNextExactEviction = false

    override fun register(identity: SlockAndroidAvatarDecodedIdentity): Boolean =
        delegate.register(identity)

    override fun evictExact(entryIdentity: SlockAndroidAvatarEntryIdentity): Boolean {
        if (failNextExactEviction) {
            failNextExactEviction = false
            return false
        }
        return delegate.evictExact(entryIdentity)
    }

    override fun evictKey(key: SlockAndroidAvatarOpaqueKey): Boolean =
        delegate.evictKey(key)

    override fun evictMatching(scope: SlockAndroidAvatarTerminalScope): Boolean {
        beforeMatchingEviction?.invoke(scope)
        return delegate.evictMatching(scope)
    }

    override fun containsExact(entryIdentity: SlockAndroidAvatarEntryIdentity): Boolean =
        delegate.containsExact(entryIdentity)

    override fun containsKey(key: SlockAndroidAvatarOpaqueKey): Boolean =
        delegate.containsKey(key)

    override fun containsMatching(scope: SlockAndroidAvatarTerminalScope): Boolean =
        delegate.containsMatching(scope)

    override fun count(): Int = delegate.count()
}

internal class SimulatedAvatarCrash(val cut: SlockAndroidAvatarDurableCut) :
    RuntimeException("Simulated crash after $cut")

internal data class AvatarKernelTestRig(
    val files: FakeAvatarDurableFileSystem = FakeAvatarDurableFileSystem(),
    val identities: DeterministicAvatarIdentityFactory = DeterministicAvatarIdentityFactory(),
    val decoded: SlockAndroidAvatarDecodedIdentityStore =
        SlockAndroidAvatarInMemoryDecodedIdentityStore()
) {
    fun kernel(
        crashAt: SlockAndroidAvatarDurableCut? = null,
        terminalObserver: SlockAndroidAvatarTerminalObserver =
            SlockAndroidAvatarTerminalObserver { _, _ -> }
    ): SlockAndroidAvatarDurableKernel =
        SlockAndroidAvatarDurableKernel(
            files = files,
            identityFactory = identities,
            decodedStore = decoded,
            durableObserver =
                SlockAndroidAvatarDurableObserver { _, cut ->
                    if (cut == crashAt) throw SimulatedAvatarCrash(cut)
                },
            terminalObserver = terminalObserver
        )
}

internal fun avatarOpaque(seed: String): String = seed.toByteArray().slockSha256Hex()

internal fun avatarKey(
    partition: String = "partition-a",
    owner: String = "owner-a",
    subject: String = "subject-a",
    bytes: String = "bytes-a"
): SlockAndroidAvatarOpaqueKey =
    SlockAndroidAvatarOpaqueKey(
        partitionKey = avatarOpaque(partition),
        ownerKey = avatarOpaque(owner),
        subjectKey = avatarOpaque(subject),
        bytesKey = avatarOpaque(bytes)
    )

internal fun avatarCommit(
    generation: Long,
    nonceSeed: String = "commit-$generation"
): SlockAndroidAvatarCommitIdentity =
    SlockAndroidAvatarCommitIdentity(
        commitGeneration = generation,
        nonce = avatarOpaque(nonceSeed)
    )

internal fun SlockAndroidAvatarDurableKernel.acceptedFence(
    key: SlockAndroidAvatarOpaqueKey,
    generation: Long
): SlockAndroidAvatarRequestFence {
    val result = beginRequest(key, generation)
    assertEquals(SlockAndroidAvatarRequestStatus.Accepted, result.status)
    return assertNotNull(result.fence).let { result.fence!! }
}

internal fun SlockAndroidAvatarDurableKernel.commitCandidate(
    key: SlockAndroidAvatarOpaqueKey,
    requestGeneration: Long,
    commitIdentity: SlockAndroidAvatarCommitIdentity,
    bytes: ByteArray,
    expectedCurrent: SlockAndroidAvatarCommitIdentity? = null
): SlockAndroidAvatarEntryIdentity {
    val fence = acceptedFence(key, requestGeneration)
    val staged =
        stageCandidate(
            fence = fence,
            expectedCurrentCommitIdentity = expectedCurrent,
            candidateCommitIdentity = commitIdentity,
            bytes = bytes,
            contentType = "image/png; charset=binary"
        )
    assertEquals(SlockAndroidAvatarOperationStatus.Applied, staged.status)
    val finalized = finalizeCandidate(fence, commitIdentity)
    assertEquals(SlockAndroidAvatarOperationStatus.Applied, finalized.status)
    return assertNotNull(finalized.entry).let { finalized.entry!! }
}

internal fun SlockAndroidAvatarDurableKernel.readHit(
    key: SlockAndroidAvatarOpaqueKey,
    requestGeneration: Long,
    commitIdentity: SlockAndroidAvatarCommitIdentity
): SlockAndroidAvatarReadResult.Hit {
    val result = readSnapshot(acceptedFence(key, requestGeneration), commitIdentity)
    assertTrue("expected cache hit, got $result", result is SlockAndroidAvatarReadResult.Hit)
    return result as SlockAndroidAvatarReadResult.Hit
}
