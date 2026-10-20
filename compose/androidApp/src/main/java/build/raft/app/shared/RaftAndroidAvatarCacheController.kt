package build.raft.app.shared

import android.content.Context
import android.graphics.BitmapFactory
import build.raft.app.data.RaftAvatarCommitIdentity
import build.raft.app.data.RaftAvatarMountedNativeInspection
import build.raft.app.data.RaftAvatarMountedNativeProvider
import build.raft.app.data.RaftAvatarMountedNativeProviderRegistry
import build.raft.app.data.RaftAvatarMountedRenderDescriptor
import build.raft.app.data.RaftAvatarNativeOperationResult
import build.raft.app.data.RaftAvatarNativeReservation
import build.raft.app.data.RaftAvatarNativeReservationRequest
import build.raft.app.data.RaftAvatarNativeReserveResult
import build.raft.app.data.RaftAvatarNativeSlot
import build.raft.app.data.RaftAvatarNativeStageFence
import build.raft.app.data.RaftAvatarNativeStageFinish
import build.raft.app.data.RaftAvatarNativeStageOpen
import build.raft.app.data.RaftAvatarNativeStageSink
import build.raft.app.data.RaftAvatarNativeStagedCandidate
import build.raft.app.core.data.diagnostics.RaftAppLog
import com.caverock.androidsvg.SVG
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Cache dir the avatar store writes and Settings → Storage measures as the image cache. */
internal const val RAFT_ANDROID_IMAGE_CACHE_DIR_NAME = "image_cache"
private const val RAFT_ANDROID_AVATAR_NAMESPACE = "avatar_durable_v1"
private const val RAFT_ANDROID_AVATAR_STAGING_DIRECTORY = "transport-staging"
private const val RAFT_ANDROID_AVATAR_MAX_ENTRY_BYTES = 5L * 1024L * 1024L
private const val RAFT_ANDROID_AVATAR_MAX_MANAGED_BYTES = 64L * 1024L * 1024L
private const val RAFT_ANDROID_AVATAR_KERNEL_PROTOCOL =
    "slock_avatar_cache_v1|typed_avatar_only|opaque_partition_subject_bytes_v1|avatar_durable_v1"

private val RaftAndroidAvatarKernelPartitionKey =
    RAFT_ANDROID_AVATAR_KERNEL_PROTOCOL.toByteArray(StandardCharsets.UTF_8).raftSha256Hex()

internal fun interface RaftAndroidAvatarContentInspector {
    /** Returns a normalized image content type only when the complete body is decodable. */
    fun inspect(bytes: ByteArray): String?
}

private object DefaultRaftAndroidAvatarContentInspector : RaftAndroidAvatarContentInspector {
    override fun inspect(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return null
        val prefix =
            bytes.copyOfRange(0, minOf(bytes.size, 1024))
                .toString(StandardCharsets.UTF_8)
                .trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        if (prefix.startsWith("<svg", ignoreCase = true) ||
            (prefix.startsWith("<?xml", ignoreCase = true) && prefix.contains("<svg", ignoreCase = true))
        ) {
            return runCatching {
                SVG.getFromInputStream(ByteArrayInputStream(bytes))
                "image/svg+xml"
            }.getOrNull()
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sampleSize = 1
        while (maxOf(bounds.outWidth / sampleSize, bounds.outHeight / sampleSize) > 1024 &&
            sampleSize <= Int.MAX_VALUE / 2
        ) {
            sampleSize *= 2
        }
        val decoded =
            BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sampleSize }
            ) ?: return null
        decoded.recycle()
        return bounds.outMimeType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.startsWith("image/") }
            ?: "image/unknown"
    }
}

/**
 * Android consumer for the shared mounted-avatar contract.
 *
 * Network ownership stays in SharedBinaryDownloader. This class owns only opaque-key mapping,
 * one-flight transport temp files, native identity reservation, durable kernel calls, render
 * delivery leases, and terminal barriers. Transport temp files live under the sole avatar root but
 * deliberately outside the kernel's p/... managed-entry grammar, so they can never become a
 * renderable or enumerable second byte authority.
 */
internal class RaftAndroidAvatarCacheController internal constructor(
    private val kernel: RaftAndroidAvatarDurableKernel,
    private val stagingRoot: File,
    private val startupStagingRecovered: Boolean,
    private val contentInspector: RaftAndroidAvatarContentInspector =
        DefaultRaftAndroidAvatarContentInspector,
    private val deleteTransportFile: (File) -> Boolean = { file ->
        !file.exists() || file.delete()
    },
    private val deleteTransportTree: (File) -> Boolean = { root ->
        !root.exists() || (root.deleteRecursively() && !root.exists())
    },
    private val terminalAdmissionObserver: (RaftAndroidAvatarTerminalScope) -> Unit = {}
) : RaftAvatarMountedNativeProvider {
    private enum class OperationPhase {
        Open,
        Finished,
        Reserved,
        Bound,
        Finalized,
        RolledBack,
        Aborted,
        Revoked
    }

    private data class Operation(
        val sharedFence: RaftAvatarNativeStageFence,
        val key: RaftAndroidAvatarOpaqueKey,
        val kernelFence: RaftAndroidAvatarRequestFence,
        val file: File,
        var output: FileOutputStream?,
        var phase: OperationPhase = OperationPhase.Open,
        var sizeBytes: Long = 0L,
        var staged: RaftAvatarNativeStagedCandidate? = null,
        var reservation: RaftAvatarNativeReservation? = null,
        var transportCleanupPending: Boolean = false
    )

    private val lock = ReentrantLock(true)
    private val renderDrained = lock.newCondition()
    private val operations = mutableMapOf<String, Operation>()
    private val requestGenerations = mutableMapOf<RaftAndroidAvatarOpaqueKey, Long>()
    private val activeRenders = mutableMapOf<RaftAndroidAvatarOpaqueKey, Int>()
    private val retiringScopes = linkedSetOf<RaftAndroidAvatarTerminalScope>()
    private val retiringKeys = linkedSetOf<RaftAndroidAvatarOpaqueKey>()
    private val kernelReady =
        startupStagingRecovered &&
            kernel.startupRecovery.status == RaftAndroidAvatarOperationStatus.Applied

    override suspend fun inspect(slot: RaftAvatarNativeSlot): RaftAvatarMountedNativeInspection =
        withContext(Dispatchers.IO) { inspectBlocking(slot) }

    private fun inspectBlocking(slot: RaftAvatarNativeSlot): RaftAvatarMountedNativeInspection {
        // Once this provider is installed, the managed protocol is recognized. Runtime recovery,
        // admission, and storage failures must stay supported-but-unavailable: shared reserves
        // supported=false exclusively for a genuinely absent method/provider and otherwise would
        // reopen the ordinary raw-URL loader as a second byte authority.
        if (!kernelReady) return RaftAvatarMountedNativeInspection(true, false, false)
        val key = slot.toAndroidKey()
        if (!acquireRenderAdmission(key)) {
            return RaftAvatarMountedNativeInspection(true, false, false)
        }
        return try {
            val current = kernel.readCurrent(key)
            val snapshot = current.snapshot
            if (current.status != RaftAndroidAvatarCurrentReadStatus.Available || snapshot == null) {
                RaftAvatarMountedNativeInspection(true, false, false)
            } else {
                val decodable = contentInspector.inspect(snapshot.bytes) != null
                if (!decodable) {
                    kernel.retireCurrentExact(key, snapshot.entryIdentity.commitIdentity)
                }
                RaftAvatarMountedNativeInspection(
                    supported = true,
                    bytesAvailable = decodable,
                    bytesDecodable = decodable
                )
            }
        } finally {
            releaseRenderAdmission(key)
        }
    }

    override fun beginStage(fence: RaftAvatarNativeStageFence): RaftAvatarNativeStageOpen =
        lock.withLock {
            if (!kernelReady) return@withLock RaftAvatarNativeStageOpen.StorageFailure
            val key = fence.slot.toAndroidKey()
            if (key in retiringKeys || retiringScopes.any(key::matches)) {
                return@withLock RaftAvatarNativeStageOpen.AdmissionClosed
            }
            operations.values
                .filter { operation -> operation.key == key && operation.transportCleanupPending }
                .forEach { operation -> operation.closeAndDelete() }
            operations.entries.removeAll { (_, operation) ->
                operation.key == key && operation.isFullyTerminal()
            }
            operations[fence.requestNonce]?.let { existing ->
                if (existing.sharedFence != fence) return@withLock RaftAvatarNativeStageOpen.AdmissionClosed
                return@withLock if (existing.phase == OperationPhase.Open) {
                    RaftAvatarNativeStageOpen.Opened(StageSink(fence.requestNonce))
                } else {
                    RaftAvatarNativeStageOpen.BusyCleanup
                }
            }
            val conflicting =
                operations.values.firstOrNull { operation ->
                    operation.key == key &&
                        !operation.isFullyTerminal()
                }
            if (conflicting != null) return@withLock RaftAvatarNativeStageOpen.BusyCleanup
            val generation = (requestGenerations[key] ?: 0L).nextRequestGenerationOrNull()
                ?: return@withLock RaftAvatarNativeStageOpen.StorageFailure
            val request = kernel.beginRequest(key, generation)
            val kernelFence = request.fence
            if (request.status != RaftAndroidAvatarRequestStatus.Accepted || kernelFence == null) {
                return@withLock when (request.status) {
                    RaftAndroidAvatarRequestStatus.Stale -> RaftAvatarNativeStageOpen.BusyCleanup
                    RaftAndroidAvatarRequestStatus.AdmissionClosed ->
                        RaftAvatarNativeStageOpen.AdmissionClosed
                    RaftAndroidAvatarRequestStatus.Accepted ->
                        RaftAvatarNativeStageOpen.StorageFailure
                }
            }
            requestGenerations[key] = generation
            val file = stagingRoot.resolve("${fence.requestNonce}.tmp")
            operations[fence.requestNonce] =
                Operation(
                    sharedFence = fence,
                    key = key,
                    kernelFence = kernelFence,
                    file = file,
                    output = null
                )
            RaftAvatarNativeStageOpen.Opened(StageSink(fence.requestNonce))
        }

    override suspend fun reserve(
        request: RaftAvatarNativeReservationRequest
    ): RaftAvatarNativeReserveResult =
        withContext(Dispatchers.IO) {
            lock.withLock {
            val operation = operations[request.fence.requestNonce]
                ?: return@withLock RaftAvatarNativeReserveResult.Stale
            if (operation.sharedFence != request.fence) {
                return@withLock RaftAvatarNativeReserveResult.ExpectedIdentityMismatch
            }
            if (operation.phase in terminalOperationPhases) {
                return@withLock RaftAvatarNativeReserveResult.Stale
            }
            operation.reservation?.let { existing ->
                return@withLock if (existing.candidateModelRevision == request.candidateModelRevision) {
                    RaftAvatarNativeReserveResult.Reserved(existing)
                } else {
                    RaftAvatarNativeReserveResult.ExpectedIdentityMismatch
                }
            }
            if (operation.phase != OperationPhase.Finished || operation.staged == null) {
                return@withLock if (operation.phase in terminalOperationPhases) {
                    RaftAvatarNativeReserveResult.Stale
                } else {
                    RaftAvatarNativeReserveResult.BusyCleanup
                }
            }
            val result =
                kernel.reserveCandidateIdentity(
                    fence = operation.kernelFence,
                    expectedCurrentCommitIdentity = request.fence.expectedCommitIdentity.toAndroidOrNull()
                )
            if (result.status != RaftAndroidAvatarOperationStatus.Applied || result.commitIdentity == null) {
                return@withLock result.status.toSharedReserveResult()
            }
            val reservation =
                RaftAvatarNativeReservation(
                    requestNonce = request.fence.requestNonce,
                    commitIdentity = result.commitIdentity.toShared(),
                    candidateModelRevision = request.candidateModelRevision
                )
            operation.reservation = reservation
            operation.phase = OperationPhase.Reserved
            RaftAvatarNativeReserveResult.Reserved(reservation)
            }
        }

    override suspend fun bindStage(
        staged: RaftAvatarNativeStagedCandidate,
        reservation: RaftAvatarNativeReservation
    ): RaftAvatarNativeOperationResult =
        withContext(Dispatchers.IO) {
            lock.withLock {
            val operation = operations[reservation.requestNonce]
                ?: return@withLock RaftAvatarNativeOperationResult.Stale
            if (operation.staged != staged || operation.reservation != reservation) {
                return@withLock RaftAvatarNativeOperationResult.ExpectedIdentityMismatch
            }
            if (operation.phase == OperationPhase.Bound || operation.phase == OperationPhase.Finalized) {
                return@withLock if (operation.closeAndDelete()) {
                    RaftAvatarNativeOperationResult.Accepted
                } else {
                    RaftAvatarNativeOperationResult.BusyCleanup
                }
            }
            if (operation.phase != OperationPhase.Reserved) {
                return@withLock if (operation.phase in terminalOperationPhases) {
                    RaftAvatarNativeOperationResult.Stale
                } else {
                    RaftAvatarNativeOperationResult.BusyCleanup
                }
            }
            val bytes =
                runCatching { operation.file.readBytes() }.getOrNull()
                    ?.takeIf { it.size.toLong() == staged.sizeBytes }
                    ?: return@withLock RaftAvatarNativeOperationResult.StorageFailure
            val result =
                kernel.stageCandidate(
                    fence = operation.kernelFence,
                    expectedCurrentCommitIdentity = operation.sharedFence.expectedCommitIdentity.toAndroidOrNull(),
                    candidateCommitIdentity = reservation.commitIdentity.toAndroid(),
                    bytes = bytes,
                    contentType = staged.contentType ?: "image/unknown"
                )
            val mapped = result.status.toSharedOperationResult(notFoundIsAccepted = false)
            if (mapped == RaftAvatarNativeOperationResult.Accepted) {
                operation.phase = OperationPhase.Bound
                if (!operation.closeAndDelete()) {
                    return@withLock RaftAvatarNativeOperationResult.BusyCleanup
                }
            }
            mapped
            }
        }

    override suspend fun abortStage(
        staged: RaftAvatarNativeStagedCandidate
    ): RaftAvatarNativeOperationResult =
        withContext(Dispatchers.IO) {
            lock.withLock {
            val operation = operations[staged.requestNonce]
                ?: return@withLock RaftAvatarNativeOperationResult.Accepted
            if (operation.staged != null && operation.staged != staged) {
                return@withLock RaftAvatarNativeOperationResult.ExpectedIdentityMismatch
            }
            if (operation.phase in setOf(OperationPhase.Bound, OperationPhase.Finalized)) {
                return@withLock RaftAvatarNativeOperationResult.BusyCleanup
            }
            operation.phase = OperationPhase.Aborted
            if (operation.closeAndDelete()) {
                RaftAvatarNativeOperationResult.Accepted
            } else {
                RaftAvatarNativeOperationResult.BusyCleanup
            }
            }
        }

    override suspend fun finalize(
        reservation: RaftAvatarNativeReservation
    ): RaftAvatarNativeOperationResult =
        withContext(Dispatchers.IO) {
            lock.withLock {
            val operation = operations[reservation.requestNonce]
                ?: return@withLock RaftAvatarNativeOperationResult.Stale
            if (operation.reservation != reservation) {
                return@withLock RaftAvatarNativeOperationResult.ExpectedIdentityMismatch
            }
            if (operation.phase == OperationPhase.Finalized) {
                return@withLock RaftAvatarNativeOperationResult.Accepted
            }
            if (operation.phase != OperationPhase.Bound) {
                return@withLock if (operation.phase in terminalOperationPhases) {
                    RaftAvatarNativeOperationResult.Stale
                } else {
                    RaftAvatarNativeOperationResult.BusyCleanup
                }
            }
            val result =
                kernel.finalizeCandidate(
                    fence = operation.kernelFence,
                    candidateCommitIdentity = reservation.commitIdentity.toAndroid()
                )
            val mapped = result.status.toSharedOperationResult(notFoundIsAccepted = false)
            if (mapped == RaftAvatarNativeOperationResult.Accepted) {
                operation.phase = OperationPhase.Finalized
                val budget = kernel.trimToBudget(RAFT_ANDROID_AVATAR_MAX_MANAGED_BYTES)
                if (budget.managedBytes !in 0L..RAFT_ANDROID_AVATAR_MAX_MANAGED_BYTES ||
                    budget.status in
                        setOf(
                            RaftAndroidAvatarBudgetStatus.Pinned,
                            RaftAndroidAvatarBudgetStatus.CleanupPending,
                            RaftAndroidAvatarBudgetStatus.StorageFailure
                        )
                ) {
                    return@withLock RaftAvatarNativeOperationResult.BusyCleanup
                }
            }
            mapped
            }
        }

    override suspend fun rollback(
        reservation: RaftAvatarNativeReservation
    ): RaftAvatarNativeOperationResult =
        withContext(Dispatchers.IO) {
            lock.withLock {
            val operation = operations[reservation.requestNonce]
                ?: return@withLock RaftAvatarNativeOperationResult.Accepted
            if (operation.reservation != reservation) {
                return@withLock RaftAvatarNativeOperationResult.ExpectedIdentityMismatch
            }
            if (operation.phase in setOf(
                    OperationPhase.RolledBack,
                    OperationPhase.Aborted,
                    OperationPhase.Revoked
                )
            ) {
                return@withLock if (operation.closeAndDelete()) {
                    RaftAvatarNativeOperationResult.Accepted
                } else {
                    RaftAvatarNativeOperationResult.BusyCleanup
                }
            }
            val result =
                when (operation.phase) {
                    OperationPhase.Bound ->
                        kernel.rollbackCandidate(operation.key, reservation.commitIdentity.toAndroid())
                    OperationPhase.Finalized ->
                        // Shared may lose its final mount/CAS fence after native finalize. The old
                        // entry is already cleaned, so exact-retire the unpublishable new entry and
                        // force the next mount to full-fetch rather than leaving SQL/native split.
                        kernel.retireCurrentExact(operation.key, reservation.commitIdentity.toAndroid())
                    OperationPhase.Open,
                    OperationPhase.Finished,
                    OperationPhase.Reserved ->
                        RaftAndroidAvatarOperationResult(RaftAndroidAvatarOperationStatus.AlreadyApplied)
                    OperationPhase.RolledBack,
                    OperationPhase.Aborted,
                    OperationPhase.Revoked -> error("handled above")
                }
            val mapped = result.status.toSharedOperationResult(notFoundIsAccepted = true)
            if (mapped == RaftAvatarNativeOperationResult.Accepted) {
                operation.phase = OperationPhase.RolledBack
                if (!operation.closeAndDelete()) {
                    return@withLock RaftAvatarNativeOperationResult.BusyCleanup
                }
            }
            mapped
            }
        }

    override suspend fun retireExact(
        slot: RaftAvatarNativeSlot,
        expectedCommitIdentity: RaftAvatarCommitIdentity?
    ): RaftAvatarNativeOperationResult =
        withContext(Dispatchers.IO) {
            retireExactWithRenderDrain(slot.toAndroidKey(), expectedCommitIdentity.toAndroidOrNull())
        }

    override suspend fun retireSubject(
        slot: RaftAvatarNativeSlot
    ): RaftAvatarNativeOperationResult =
        withContext(Dispatchers.IO) {
            retireScope(
                RaftAndroidAvatarTerminalScope.Subject(
                    partitionKey = RaftAndroidAvatarKernelPartitionKey,
                    ownerKey = slot.nativePartitionKey,
                    subjectKey = slot.nativeSubjectKey
                )
            )
        }

    override suspend fun retireOwner(
        nativePartitionKey: String
    ): RaftAvatarNativeOperationResult =
        withContext(Dispatchers.IO) {
            retireScope(
                RaftAndroidAvatarTerminalScope.Owner(
                    partitionKey = RaftAndroidAvatarKernelPartitionKey,
                    ownerKey = nativePartitionKey
                )
            )
        }

    override suspend fun clearAll(): RaftAvatarNativeOperationResult =
        withContext(Dispatchers.IO) { retireScope(RaftAndroidAvatarTerminalScope.All) }

    internal fun retireLegacySubject(
        nativePartitionKey: String,
        nativeSubjectKey: String
    ): RaftAvatarNativeOperationResult =
        retireScope(
            RaftAndroidAvatarTerminalScope.Subject(
                partitionKey = RaftAndroidAvatarKernelPartitionKey,
                ownerKey = nativePartitionKey,
                subjectKey = nativeSubjectKey
            )
        )

    internal fun managedBytes(): Long? = kernel.managedBytes()

    internal fun beginRender(
        descriptor: RaftAvatarMountedRenderDescriptor
    ): RaftAndroidAvatarRenderRead? {
        if (!kernelReady) return null
        val key = descriptor.slot.toAndroidKey()
        if (!acquireRenderAdmission(key)) return null
        val current = kernel.readCurrent(key)
        val snapshot = current.snapshot
        val expected = descriptor.commitIdentity.toAndroid()
        if (current.status != RaftAndroidAvatarCurrentReadStatus.Available ||
            snapshot == null ||
            snapshot.entryIdentity.commitIdentity != expected
        ) {
            releaseRenderAdmission(key)
            return null
        }
        return RaftAndroidAvatarRenderRead(
            controller = this,
            key = key,
            snapshot = snapshot
        )
    }

    private fun retireScope(
        scope: RaftAndroidAvatarTerminalScope
    ): RaftAvatarNativeOperationResult {
        val boundReservations = mutableListOf<Pair<RaftAndroidAvatarOpaqueKey, RaftAvatarNativeReservation>>()
        val matchingOperations = mutableListOf<Operation>()
        lock.withLock {
            if (!kernelReady) return RaftAvatarNativeOperationResult.StorageFailure
            retiringScopes += scope
            terminalAdmissionObserver(scope)
            operations.values.filter { it.key.matches(scope) }.forEach { operation ->
                operation.reservation?.takeIf { operation.phase == OperationPhase.Bound }?.let { reservation ->
                    boundReservations += operation.key to reservation
                }
                operation.phase = OperationPhase.Revoked
                operation.closeAndDelete()
                matchingOperations += operation
            }
            while (activeRenders.any { (key, count) -> count > 0 && key.matches(scope) }) {
                try {
                    renderDrained.await()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return RaftAvatarNativeOperationResult.StorageFailure
                }
            }
        }
        boundReservations.forEach { (key, reservation) ->
            kernel.rollbackCandidate(key, reservation.commitIdentity.toAndroid())
        }
        val terminal =
            when (scope) {
                RaftAndroidAvatarTerminalScope.All -> kernel.clearAll()
                is RaftAndroidAvatarTerminalScope.Owner ->
                    kernel.retireOwner(scope.partitionKey, scope.ownerKey)
                is RaftAndroidAvatarTerminalScope.Subject ->
                    kernel.retireSubject(scope.partitionKey, scope.ownerKey, scope.subjectKey)
            }
        val trackedTempZero =
            lock.withLock {
                matchingOperations.all { operation ->
                    operation.closeAndDelete() && operation.file.isPhysicallyZero()
                }
            }
        val rootZero =
            scope !is RaftAndroidAvatarTerminalScope.All ||
                (trackedTempZero && stagingRoot.deleteTreeAndVerify())
        val tempZero = trackedTempZero && rootZero
        lock.withLock {
            if (terminal.status == RaftAndroidAvatarTerminalStatus.Complete && tempZero) {
                operations.entries.removeAll { (_, operation) -> operation.key.matches(scope) }
                requestGenerations.keys.removeAll { key -> key.matches(scope) }
                // A successful wider terminal proves physical zero for its full contained domain,
                // so it also owns convergence of cleanup debt left by earlier narrower attempts.
                // Mirroring the kernel's terminal-scope containment prevents a healed owner/global
                // clear from leaving controller-only subject/key admission tombstones behind.
                retiringScopes.removeAll { pending -> scope.containsControllerHold(pending) }
                retiringKeys.removeAll { key -> key.matches(scope) }
            } else {
                // Keep admission closed until a later terminal retry verifies both managed and
                // transport staging physical zero.
                retiringScopes += scope
            }
        }
        return when {
            terminal.status == RaftAndroidAvatarTerminalStatus.Complete && tempZero ->
                RaftAvatarNativeOperationResult.Accepted
            terminal.status == RaftAndroidAvatarTerminalStatus.CleanupPending || !tempZero ->
                RaftAvatarNativeOperationResult.BusyCleanup
            else -> RaftAvatarNativeOperationResult.StorageFailure
        }
    }

    private fun retireExactWithRenderDrain(
        key: RaftAndroidAvatarOpaqueKey,
        expectedCommitIdentity: RaftAndroidAvatarCommitIdentity?
    ): RaftAvatarNativeOperationResult {
        val matchingOperations = mutableListOf<Operation>()
        lock.withLock {
            if (!kernelReady) return RaftAvatarNativeOperationResult.StorageFailure
            retiringKeys += key
            operations.values.filter { it.key == key }.forEach { operation ->
                operation.phase = OperationPhase.Revoked
                operation.closeAndDelete()
                matchingOperations += operation
            }
            while ((activeRenders[key] ?: 0) > 0) {
                try {
                    renderDrained.await()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return RaftAvatarNativeOperationResult.StorageFailure
                }
            }
        }
        val result =
            kernel.retireCurrentExact(key, expectedCommitIdentity)
                .status
                .toSharedOperationResult(notFoundIsAccepted = true)
        val tempZero =
            lock.withLock {
                matchingOperations.all { operation ->
                    operation.closeAndDelete() && operation.file.isPhysicallyZero()
                }
            }
        lock.withLock {
            val cleanupDebtResolved =
                tempZero &&
                    when (result) {
                        RaftAvatarNativeOperationResult.Accepted,
                        RaftAvatarNativeOperationResult.ExpectedIdentityMismatch,
                        RaftAvatarNativeOperationResult.Stale -> true
                        else -> false
                    }
            if (cleanupDebtResolved) {
                operations.entries.removeAll { (_, operation) -> operation.key == key }
                retiringKeys -= key
            } else {
                retiringKeys += key
            }
        }
        return if (!tempZero) RaftAvatarNativeOperationResult.BusyCleanup else result
    }

    private fun acquireRenderAdmission(key: RaftAndroidAvatarOpaqueKey): Boolean =
        lock.withLock {
            if (!kernelReady || key in retiringKeys || retiringScopes.any(key::matches)) {
                return@withLock false
            }
            activeRenders[key] = (activeRenders[key] ?: 0) + 1
            true
        }

    private fun releaseRenderAdmission(key: RaftAndroidAvatarOpaqueKey) {
        lock.withLock {
            val remaining = (activeRenders[key] ?: 0) - 1
            if (remaining <= 0) activeRenders.remove(key) else activeRenders[key] = remaining
            renderDrained.signalAll()
        }
    }

    private fun RaftAndroidAvatarTerminalScope.containsControllerHold(
        candidate: RaftAndroidAvatarTerminalScope
    ): Boolean =
        when (this) {
            RaftAndroidAvatarTerminalScope.All -> true
            is RaftAndroidAvatarTerminalScope.Owner ->
                when (candidate) {
                    RaftAndroidAvatarTerminalScope.All -> false
                    is RaftAndroidAvatarTerminalScope.Owner ->
                        partitionKey == candidate.partitionKey && ownerKey == candidate.ownerKey
                    is RaftAndroidAvatarTerminalScope.Subject ->
                        partitionKey == candidate.partitionKey && ownerKey == candidate.ownerKey
                }
            is RaftAndroidAvatarTerminalScope.Subject -> this == candidate
        }

    private fun registerDecoded(
        key: RaftAndroidAvatarOpaqueKey,
        snapshot: RaftAndroidAvatarCurrentReadSnapshot,
        width: Int,
        height: Int,
        scaleIdentity: String
    ): Boolean =
        kernel.registerDecodedCurrent(
            entryIdentity = snapshot.entryIdentity,
            variant =
                RaftAndroidAvatarDecodedVariant(
                    width = width,
                    height = height,
                    scaleIdentity = scaleIdentity
                )
        ) && snapshot.entryIdentity.key == key

    private inner class StageSink(
        private val requestNonce: String
    ) : RaftAvatarNativeStageSink {
        override fun write(bytes: ByteArray): Boolean =
            lock.withLock {
                val operation = operations[requestNonce] ?: return@withLock false
                if (operation.phase != OperationPhase.Open || bytes.isEmpty()) return@withLock false
                val nextSize = operation.sizeBytes + bytes.size.toLong()
                if (nextSize > RAFT_ANDROID_AVATAR_MAX_ENTRY_BYTES) {
                    operation.phase = OperationPhase.Aborted
                    operation.closeAndDelete()
                    return@withLock false
                }
                val output =
                    operation.output ?: run {
                        if ((!stagingRoot.exists() && !stagingRoot.mkdirs()) ||
                            !stagingRoot.isDirectory ||
                            !operation.closeAndDelete()
                        ) {
                            operation.phase = OperationPhase.Aborted
                            return@withLock false
                        }
                        runCatching { FileOutputStream(operation.file, false) }.getOrNull()
                            ?.also { operation.output = it }
                            ?: run {
                                operation.phase = OperationPhase.Aborted
                                operation.closeAndDelete()
                                return@withLock false
                            }
                    }
                val wrote = runCatching { output.write(bytes); true }.getOrDefault(false)
                if (!wrote) {
                    operation.phase = OperationPhase.Aborted
                    operation.closeAndDelete()
                    return@withLock false
                }
                operation.sizeBytes = nextSize
                true
            }

        override fun finish(): RaftAvatarNativeStageFinish =
            lock.withLock {
                val operation = operations[requestNonce]
                    ?: return@withLock RaftAvatarNativeStageFinish.StorageFailure
                if (operation.phase == OperationPhase.Finished) {
                    return@withLock operation.staged
                        ?.let { staged -> RaftAvatarNativeStageFinish.Ready(staged) }
                        ?: RaftAvatarNativeStageFinish.StorageFailure
                }
                if (operation.phase != OperationPhase.Open) {
                    return@withLock if (operation.phase == OperationPhase.Aborted) {
                        RaftAvatarNativeStageFinish.InvalidContent
                    } else {
                        RaftAvatarNativeStageFinish.StorageFailure
                    }
                }
                if (operation.sizeBytes <= 0L) {
                    operation.phase = OperationPhase.Aborted
                    operation.closeAndDelete()
                    return@withLock RaftAvatarNativeStageFinish.InvalidContent
                }
                val closed =
                    runCatching {
                        operation.output?.flush()
                        operation.output?.fd?.sync()
                        operation.output?.close()
                        operation.output = null
                        true
                    }.getOrDefault(false)
                if (!closed) {
                    operation.phase = OperationPhase.Aborted
                    operation.closeAndDelete()
                    return@withLock RaftAvatarNativeStageFinish.StorageFailure
                }
                val bytes = runCatching { operation.file.readBytes() }.getOrNull()
                    ?.takeIf { it.size.toLong() == operation.sizeBytes }
                val contentType = bytes?.let(contentInspector::inspect)
                if (contentType == null) {
                    operation.phase = OperationPhase.Aborted
                    operation.closeAndDelete()
                    return@withLock RaftAvatarNativeStageFinish.InvalidContent
                }
                val staged =
                    RaftAvatarNativeStagedCandidate(
                        requestNonce = requestNonce,
                        sizeBytes = operation.sizeBytes,
                        contentType = contentType
                    )
                operation.staged = staged
                operation.phase = OperationPhase.Finished
                RaftAvatarNativeStageFinish.Ready(staged)
            }

        override fun abort() {
            lock.withLock {
                val operation = operations[requestNonce] ?: return
                if (operation.phase in setOf(OperationPhase.Bound, OperationPhase.Finalized)) return
                operation.phase = OperationPhase.Aborted
                operation.closeAndDelete()
            }
        }
    }

    private fun Operation.closeAndDelete(): Boolean {
        val currentOutput = output
        if (currentOutput != null) {
            val closed = runCatching { currentOutput.close(); true }.getOrDefault(false)
            if (!closed) {
                transportCleanupPending = true
                return false
            }
            output = null
        }
        val deleted =
            runCatching {
                if (!file.exists()) true else deleteTransportFile(file)
            }.getOrDefault(false)
        val zero = file.isPhysicallyZero()
        // A false/throwing delete is not a best-effort detail. Preserve the operation as cleanup
        // authority so bind/abort/rollback/terminal callers return BusyCleanup and can retry.
        transportCleanupPending = !(deleted && zero)
        return !transportCleanupPending
    }

    private fun Operation.isFullyTerminal(): Boolean =
        phase in terminalOperationPhases && !transportCleanupPending && file.isPhysicallyZero()

    private fun File.isPhysicallyZero(): Boolean =
        runCatching { !exists() }.getOrDefault(false)

    private fun File.deleteTreeAndVerify(): Boolean {
        val deleted = runCatching { deleteTransportTree(this) }.getOrDefault(false)
        return deleted && runCatching { !exists() }.getOrDefault(false)
    }

    companion object {
        private val terminalOperationPhases =
            setOf(
                OperationPhase.Finalized,
                OperationPhase.RolledBack,
                OperationPhase.Aborted,
                OperationPhase.Revoked
            )

        internal fun create(context: Context): RaftAndroidAvatarCacheController {
            val root =
                context.applicationContext.cacheDir
                    .resolve(RAFT_ANDROID_IMAGE_CACHE_DIR_NAME)
                    .resolve(RAFT_ANDROID_AVATAR_NAMESPACE)
            val stagingRoot = root.resolve(RAFT_ANDROID_AVATAR_STAGING_DIRECTORY)
            val stagingRecovered = recoverRaftAndroidAvatarTransportStaging(stagingRoot)
            val kernel =
                RaftAndroidAvatarDurableKernel(
                    files = RaftAndroidAvatarRealFileSystem(root)
                )
            return RaftAndroidAvatarCacheController(
                kernel = kernel,
                stagingRoot = stagingRoot,
                startupStagingRecovered = stagingRecovered
            )
        }
    }

    internal class RaftAndroidAvatarRenderRead internal constructor(
        private val controller: RaftAndroidAvatarCacheController,
        private val key: RaftAndroidAvatarOpaqueKey,
        private val snapshot: RaftAndroidAvatarCurrentReadSnapshot
    ) : AutoCloseable {
        private var closed = false

        val bytes: ByteArray
            get() = snapshot.bytes

        val contentType: String
            get() = snapshot.entryIdentity.contentType

        fun registerDecoded(
            width: Int,
            height: Int,
            scaleIdentity: String
        ): Boolean =
            !closed && controller.registerDecoded(key, snapshot, width, height, scaleIdentity)

        override fun close() {
            if (closed) return
            synchronized(this) {
                if (closed) return
                closed = true
            }
            controller.releaseRenderAdmission(key)
        }
    }
}

internal fun recoverRaftAndroidAvatarTransportStaging(stagingRoot: File): Boolean =
    !stagingRoot.exists() ||
        (stagingRoot.deleteRecursively() && !stagingRoot.exists())

internal class RaftAndroidAvatarStartupGate {
    private val scheduled = AtomicBoolean(false)

    fun trySchedule(): Boolean = scheduled.compareAndSet(false, true)

    fun reset(): Unit = scheduled.set(false)
}

internal class RaftAndroidAvatarStartupScheduler(
    private val executor: Executor,
    private val create: () -> Unit,
    private val publish: () -> Unit,
    private val onFailure: (Throwable) -> Unit,
) {
    private val gate = RaftAndroidAvatarStartupGate()

    fun start(): Boolean {
        if (!gate.trySchedule()) return false
        executor.execute {
            try {
                create()
                publish()
            } catch (error: Throwable) {
                gate.reset()
                onFailure(error)
            }
        }
        return true
    }
}

internal object RaftAndroidAvatarCacheProcess {
    private val lock = Any()
    private val startupExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "slock-avatar-cache-startup").apply { isDaemon = true }
    }

    @Volatile
    private var controller: RaftAndroidAvatarCacheController? = null
    private val startupGate = RaftAndroidAvatarStartupGate()

    fun install(context: Context): RaftAndroidAvatarCacheController =
        synchronized(lock) {
            controller ?: RaftAndroidAvatarCacheController.create(context).also { created ->
                startupGate.trySchedule()
                controller = created
                RaftAvatarMountedNativeProviderRegistry.install(created)
            }
        }

    /** Starts durable-cache recovery without putting Files.walk/stat on the UI thread. */
    fun startAsync(context: Context) {
        if (controller != null || !startupGate.trySchedule()) return
        startupExecutor.execute {
            try {
                val created = RaftAndroidAvatarCacheController.create(context)
                synchronized(lock) {
                    if (controller == null) {
                        controller = created
                        RaftAvatarMountedNativeProviderRegistry.install(created)
                    }
                }
            } catch (error: Throwable) {
                startupGate.reset()
                RaftAppLog.e(
                    "SlockAndroidAvatarCache",
                    "startup recovery failed: ${error::class.simpleName}: ${error.message}",
                )
            }
        }
    }

    fun currentOrNull(): RaftAndroidAvatarCacheController? = controller
}

private fun RaftAvatarNativeSlot.toAndroidKey(): RaftAndroidAvatarOpaqueKey =
    RaftAndroidAvatarOpaqueKey(
        partitionKey = RaftAndroidAvatarKernelPartitionKey,
        ownerKey = nativePartitionKey,
        subjectKey = nativeSubjectKey,
        bytesKey = nativeBytesKey
    )

private fun RaftAvatarCommitIdentity?.toAndroidOrNull(): RaftAndroidAvatarCommitIdentity? =
    this?.let { RaftAndroidAvatarCommitIdentity(it.commitGeneration, it.nonce) }

private fun RaftAvatarCommitIdentity.toAndroid(): RaftAndroidAvatarCommitIdentity =
    RaftAndroidAvatarCommitIdentity(commitGeneration, nonce)

private fun RaftAndroidAvatarCommitIdentity.toShared(): RaftAvatarCommitIdentity =
    RaftAvatarCommitIdentity(commitGeneration, nonce)

private fun Long.nextRequestGenerationOrNull(): Long? =
    takeIf { it < Long.MAX_VALUE }?.plus(1L)

private fun RaftAndroidAvatarOperationStatus.toSharedReserveResult(): RaftAvatarNativeReserveResult =
    when (this) {
        RaftAndroidAvatarOperationStatus.StaleRequest -> RaftAvatarNativeReserveResult.Stale
        RaftAndroidAvatarOperationStatus.AdmissionClosed ->
            RaftAvatarNativeReserveResult.AdmissionClosed
        RaftAndroidAvatarOperationStatus.IdentityMismatch,
        RaftAndroidAvatarOperationStatus.NotFound ->
            RaftAvatarNativeReserveResult.ExpectedIdentityMismatch
        RaftAndroidAvatarOperationStatus.Busy,
        RaftAndroidAvatarOperationStatus.CleanupPending ->
            RaftAvatarNativeReserveResult.BusyCleanup
        RaftAndroidAvatarOperationStatus.StorageFailure,
        RaftAndroidAvatarOperationStatus.CorruptState ->
            RaftAvatarNativeReserveResult.StorageFailure
        RaftAndroidAvatarOperationStatus.Applied,
        RaftAndroidAvatarOperationStatus.AlreadyApplied ->
            error("successful reservation requires an identity")
    }

private fun RaftAndroidAvatarOperationStatus.toSharedOperationResult(
    notFoundIsAccepted: Boolean
): RaftAvatarNativeOperationResult =
    when (this) {
        RaftAndroidAvatarOperationStatus.Applied,
        RaftAndroidAvatarOperationStatus.AlreadyApplied ->
            RaftAvatarNativeOperationResult.Accepted
        RaftAndroidAvatarOperationStatus.StaleRequest -> RaftAvatarNativeOperationResult.Stale
        RaftAndroidAvatarOperationStatus.AdmissionClosed ->
            RaftAvatarNativeOperationResult.AdmissionClosed
        RaftAndroidAvatarOperationStatus.IdentityMismatch ->
            RaftAvatarNativeOperationResult.ExpectedIdentityMismatch
        RaftAndroidAvatarOperationStatus.Busy,
        RaftAndroidAvatarOperationStatus.CleanupPending ->
            RaftAvatarNativeOperationResult.BusyCleanup
        RaftAndroidAvatarOperationStatus.NotFound ->
            if (notFoundIsAccepted) {
                RaftAvatarNativeOperationResult.Accepted
            } else {
                RaftAvatarNativeOperationResult.ExpectedIdentityMismatch
            }
        RaftAndroidAvatarOperationStatus.StorageFailure,
        RaftAndroidAvatarOperationStatus.CorruptState ->
            RaftAvatarNativeOperationResult.StorageFailure
    }
