package build.raft.app.shared

import android.content.Context
import android.graphics.BitmapFactory
import build.raft.app.data.SlockAvatarCommitIdentity
import build.raft.app.data.SlockAvatarMountedNativeInspection
import build.raft.app.data.SlockAvatarMountedNativeProvider
import build.raft.app.data.SlockAvatarMountedNativeProviderRegistry
import build.raft.app.data.SlockAvatarMountedRenderDescriptor
import build.raft.app.data.SlockAvatarNativeOperationResult
import build.raft.app.data.SlockAvatarNativeReservation
import build.raft.app.data.SlockAvatarNativeReservationRequest
import build.raft.app.data.SlockAvatarNativeReserveResult
import build.raft.app.data.SlockAvatarNativeSlot
import build.raft.app.data.SlockAvatarNativeStageFence
import build.raft.app.data.SlockAvatarNativeStageFinish
import build.raft.app.data.SlockAvatarNativeStageOpen
import build.raft.app.data.SlockAvatarNativeStageSink
import build.raft.app.data.SlockAvatarNativeStagedCandidate
import com.caverock.androidsvg.SVG
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.Executors
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Cache dir the avatar store writes and Settings → Storage measures as the image cache. */
internal const val SLOCK_ANDROID_IMAGE_CACHE_DIR_NAME = "image_cache"
private const val SLOCK_ANDROID_AVATAR_NAMESPACE = "avatar_durable_v1"
private const val SLOCK_ANDROID_AVATAR_STAGING_DIRECTORY = "transport-staging"
private const val SLOCK_ANDROID_AVATAR_MAX_ENTRY_BYTES = 5L * 1024L * 1024L
private const val SLOCK_ANDROID_AVATAR_MAX_MANAGED_BYTES = 64L * 1024L * 1024L
private const val SLOCK_ANDROID_AVATAR_KERNEL_PROTOCOL =
    "slock_avatar_cache_v1|typed_avatar_only|opaque_partition_subject_bytes_v1|avatar_durable_v1"

private val SlockAndroidAvatarKernelPartitionKey =
    SLOCK_ANDROID_AVATAR_KERNEL_PROTOCOL.toByteArray(StandardCharsets.UTF_8).slockSha256Hex()

internal fun interface SlockAndroidAvatarContentInspector {
    /** Returns a normalized image content type only when the complete body is decodable. */
    fun inspect(bytes: ByteArray): String?
}

private object DefaultSlockAndroidAvatarContentInspector : SlockAndroidAvatarContentInspector {
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
internal class SlockAndroidAvatarCacheController internal constructor(
    private val kernel: SlockAndroidAvatarDurableKernel,
    private val stagingRoot: File,
    private val startupStagingRecovered: Boolean,
    private val contentInspector: SlockAndroidAvatarContentInspector =
        DefaultSlockAndroidAvatarContentInspector,
    private val deleteTransportFile: (File) -> Boolean = { file ->
        !file.exists() || file.delete()
    },
    private val deleteTransportTree: (File) -> Boolean = { root ->
        !root.exists() || (root.deleteRecursively() && !root.exists())
    },
    private val terminalAdmissionObserver: (SlockAndroidAvatarTerminalScope) -> Unit = {}
) : SlockAvatarMountedNativeProvider {
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
        val sharedFence: SlockAvatarNativeStageFence,
        val key: SlockAndroidAvatarOpaqueKey,
        val kernelFence: SlockAndroidAvatarRequestFence,
        val file: File,
        var output: FileOutputStream?,
        var phase: OperationPhase = OperationPhase.Open,
        var sizeBytes: Long = 0L,
        var staged: SlockAvatarNativeStagedCandidate? = null,
        var reservation: SlockAvatarNativeReservation? = null,
        var transportCleanupPending: Boolean = false
    )

    private val lock = ReentrantLock(true)
    private val renderDrained = lock.newCondition()
    private val operations = mutableMapOf<String, Operation>()
    private val requestGenerations = mutableMapOf<SlockAndroidAvatarOpaqueKey, Long>()
    private val activeRenders = mutableMapOf<SlockAndroidAvatarOpaqueKey, Int>()
    private val retiringScopes = linkedSetOf<SlockAndroidAvatarTerminalScope>()
    private val retiringKeys = linkedSetOf<SlockAndroidAvatarOpaqueKey>()
    private val kernelReady =
        startupStagingRecovered &&
            kernel.startupRecovery.status == SlockAndroidAvatarOperationStatus.Applied

    override suspend fun inspect(slot: SlockAvatarNativeSlot): SlockAvatarMountedNativeInspection =
        withContext(Dispatchers.IO) { inspectBlocking(slot) }

    private fun inspectBlocking(slot: SlockAvatarNativeSlot): SlockAvatarMountedNativeInspection {
        // Once this provider is installed, the managed protocol is recognized. Runtime recovery,
        // admission, and storage failures must stay supported-but-unavailable: shared reserves
        // supported=false exclusively for a genuinely absent method/provider and otherwise would
        // reopen the ordinary raw-URL loader as a second byte authority.
        if (!kernelReady) return SlockAvatarMountedNativeInspection(true, false, false)
        val key = slot.toAndroidKey()
        if (!acquireRenderAdmission(key)) {
            return SlockAvatarMountedNativeInspection(true, false, false)
        }
        return try {
            val current = kernel.readCurrent(key)
            val snapshot = current.snapshot
            if (current.status != SlockAndroidAvatarCurrentReadStatus.Available || snapshot == null) {
                SlockAvatarMountedNativeInspection(true, false, false)
            } else {
                val decodable = contentInspector.inspect(snapshot.bytes) != null
                if (!decodable) {
                    kernel.retireCurrentExact(key, snapshot.entryIdentity.commitIdentity)
                }
                SlockAvatarMountedNativeInspection(
                    supported = true,
                    bytesAvailable = decodable,
                    bytesDecodable = decodable
                )
            }
        } finally {
            releaseRenderAdmission(key)
        }
    }

    override fun beginStage(fence: SlockAvatarNativeStageFence): SlockAvatarNativeStageOpen =
        lock.withLock {
            if (!kernelReady) return@withLock SlockAvatarNativeStageOpen.StorageFailure
            val key = fence.slot.toAndroidKey()
            if (key in retiringKeys || retiringScopes.any(key::matches)) {
                return@withLock SlockAvatarNativeStageOpen.AdmissionClosed
            }
            operations.values
                .filter { operation -> operation.key == key && operation.transportCleanupPending }
                .forEach { operation -> operation.closeAndDelete() }
            operations.entries.removeAll { (_, operation) ->
                operation.key == key && operation.isFullyTerminal()
            }
            operations[fence.requestNonce]?.let { existing ->
                if (existing.sharedFence != fence) return@withLock SlockAvatarNativeStageOpen.AdmissionClosed
                return@withLock if (existing.phase == OperationPhase.Open) {
                    SlockAvatarNativeStageOpen.Opened(StageSink(fence.requestNonce))
                } else {
                    SlockAvatarNativeStageOpen.BusyCleanup
                }
            }
            val conflicting =
                operations.values.firstOrNull { operation ->
                    operation.key == key &&
                        !operation.isFullyTerminal()
                }
            if (conflicting != null) return@withLock SlockAvatarNativeStageOpen.BusyCleanup
            val generation = (requestGenerations[key] ?: 0L).nextRequestGenerationOrNull()
                ?: return@withLock SlockAvatarNativeStageOpen.StorageFailure
            val request = kernel.beginRequest(key, generation)
            val kernelFence = request.fence
            if (request.status != SlockAndroidAvatarRequestStatus.Accepted || kernelFence == null) {
                return@withLock when (request.status) {
                    SlockAndroidAvatarRequestStatus.Stale -> SlockAvatarNativeStageOpen.BusyCleanup
                    SlockAndroidAvatarRequestStatus.AdmissionClosed ->
                        SlockAvatarNativeStageOpen.AdmissionClosed
                    SlockAndroidAvatarRequestStatus.Accepted ->
                        SlockAvatarNativeStageOpen.StorageFailure
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
            SlockAvatarNativeStageOpen.Opened(StageSink(fence.requestNonce))
        }

    override suspend fun reserve(
        request: SlockAvatarNativeReservationRequest
    ): SlockAvatarNativeReserveResult =
        withContext(Dispatchers.IO) {
            lock.withLock {
            val operation = operations[request.fence.requestNonce]
                ?: return@withLock SlockAvatarNativeReserveResult.Stale
            if (operation.sharedFence != request.fence) {
                return@withLock SlockAvatarNativeReserveResult.ExpectedIdentityMismatch
            }
            if (operation.phase in terminalOperationPhases) {
                return@withLock SlockAvatarNativeReserveResult.Stale
            }
            operation.reservation?.let { existing ->
                return@withLock if (existing.candidateModelRevision == request.candidateModelRevision) {
                    SlockAvatarNativeReserveResult.Reserved(existing)
                } else {
                    SlockAvatarNativeReserveResult.ExpectedIdentityMismatch
                }
            }
            if (operation.phase != OperationPhase.Finished || operation.staged == null) {
                return@withLock if (operation.phase in terminalOperationPhases) {
                    SlockAvatarNativeReserveResult.Stale
                } else {
                    SlockAvatarNativeReserveResult.BusyCleanup
                }
            }
            val result =
                kernel.reserveCandidateIdentity(
                    fence = operation.kernelFence,
                    expectedCurrentCommitIdentity = request.fence.expectedCommitIdentity.toAndroidOrNull()
                )
            if (result.status != SlockAndroidAvatarOperationStatus.Applied || result.commitIdentity == null) {
                return@withLock result.status.toSharedReserveResult()
            }
            val reservation =
                SlockAvatarNativeReservation(
                    requestNonce = request.fence.requestNonce,
                    commitIdentity = result.commitIdentity.toShared(),
                    candidateModelRevision = request.candidateModelRevision
                )
            operation.reservation = reservation
            operation.phase = OperationPhase.Reserved
            SlockAvatarNativeReserveResult.Reserved(reservation)
            }
        }

    override suspend fun bindStage(
        staged: SlockAvatarNativeStagedCandidate,
        reservation: SlockAvatarNativeReservation
    ): SlockAvatarNativeOperationResult =
        withContext(Dispatchers.IO) {
            lock.withLock {
            val operation = operations[reservation.requestNonce]
                ?: return@withLock SlockAvatarNativeOperationResult.Stale
            if (operation.staged != staged || operation.reservation != reservation) {
                return@withLock SlockAvatarNativeOperationResult.ExpectedIdentityMismatch
            }
            if (operation.phase == OperationPhase.Bound || operation.phase == OperationPhase.Finalized) {
                return@withLock if (operation.closeAndDelete()) {
                    SlockAvatarNativeOperationResult.Accepted
                } else {
                    SlockAvatarNativeOperationResult.BusyCleanup
                }
            }
            if (operation.phase != OperationPhase.Reserved) {
                return@withLock if (operation.phase in terminalOperationPhases) {
                    SlockAvatarNativeOperationResult.Stale
                } else {
                    SlockAvatarNativeOperationResult.BusyCleanup
                }
            }
            val bytes =
                runCatching { operation.file.readBytes() }.getOrNull()
                    ?.takeIf { it.size.toLong() == staged.sizeBytes }
                    ?: return@withLock SlockAvatarNativeOperationResult.StorageFailure
            val result =
                kernel.stageCandidate(
                    fence = operation.kernelFence,
                    expectedCurrentCommitIdentity = operation.sharedFence.expectedCommitIdentity.toAndroidOrNull(),
                    candidateCommitIdentity = reservation.commitIdentity.toAndroid(),
                    bytes = bytes,
                    contentType = staged.contentType ?: "image/unknown"
                )
            val mapped = result.status.toSharedOperationResult(notFoundIsAccepted = false)
            if (mapped == SlockAvatarNativeOperationResult.Accepted) {
                operation.phase = OperationPhase.Bound
                if (!operation.closeAndDelete()) {
                    return@withLock SlockAvatarNativeOperationResult.BusyCleanup
                }
            }
            mapped
            }
        }

    override suspend fun abortStage(
        staged: SlockAvatarNativeStagedCandidate
    ): SlockAvatarNativeOperationResult =
        withContext(Dispatchers.IO) {
            lock.withLock {
            val operation = operations[staged.requestNonce]
                ?: return@withLock SlockAvatarNativeOperationResult.Accepted
            if (operation.staged != null && operation.staged != staged) {
                return@withLock SlockAvatarNativeOperationResult.ExpectedIdentityMismatch
            }
            if (operation.phase in setOf(OperationPhase.Bound, OperationPhase.Finalized)) {
                return@withLock SlockAvatarNativeOperationResult.BusyCleanup
            }
            operation.phase = OperationPhase.Aborted
            if (operation.closeAndDelete()) {
                SlockAvatarNativeOperationResult.Accepted
            } else {
                SlockAvatarNativeOperationResult.BusyCleanup
            }
            }
        }

    override suspend fun finalize(
        reservation: SlockAvatarNativeReservation
    ): SlockAvatarNativeOperationResult =
        withContext(Dispatchers.IO) {
            lock.withLock {
            val operation = operations[reservation.requestNonce]
                ?: return@withLock SlockAvatarNativeOperationResult.Stale
            if (operation.reservation != reservation) {
                return@withLock SlockAvatarNativeOperationResult.ExpectedIdentityMismatch
            }
            if (operation.phase == OperationPhase.Finalized) {
                return@withLock SlockAvatarNativeOperationResult.Accepted
            }
            if (operation.phase != OperationPhase.Bound) {
                return@withLock if (operation.phase in terminalOperationPhases) {
                    SlockAvatarNativeOperationResult.Stale
                } else {
                    SlockAvatarNativeOperationResult.BusyCleanup
                }
            }
            val result =
                kernel.finalizeCandidate(
                    fence = operation.kernelFence,
                    candidateCommitIdentity = reservation.commitIdentity.toAndroid()
                )
            val mapped = result.status.toSharedOperationResult(notFoundIsAccepted = false)
            if (mapped == SlockAvatarNativeOperationResult.Accepted) {
                operation.phase = OperationPhase.Finalized
                val budget = kernel.trimToBudget(SLOCK_ANDROID_AVATAR_MAX_MANAGED_BYTES)
                if (budget.managedBytes !in 0L..SLOCK_ANDROID_AVATAR_MAX_MANAGED_BYTES ||
                    budget.status in
                        setOf(
                            SlockAndroidAvatarBudgetStatus.Pinned,
                            SlockAndroidAvatarBudgetStatus.CleanupPending,
                            SlockAndroidAvatarBudgetStatus.StorageFailure
                        )
                ) {
                    return@withLock SlockAvatarNativeOperationResult.BusyCleanup
                }
            }
            mapped
            }
        }

    override suspend fun rollback(
        reservation: SlockAvatarNativeReservation
    ): SlockAvatarNativeOperationResult =
        withContext(Dispatchers.IO) {
            lock.withLock {
            val operation = operations[reservation.requestNonce]
                ?: return@withLock SlockAvatarNativeOperationResult.Accepted
            if (operation.reservation != reservation) {
                return@withLock SlockAvatarNativeOperationResult.ExpectedIdentityMismatch
            }
            if (operation.phase in setOf(
                    OperationPhase.RolledBack,
                    OperationPhase.Aborted,
                    OperationPhase.Revoked
                )
            ) {
                return@withLock if (operation.closeAndDelete()) {
                    SlockAvatarNativeOperationResult.Accepted
                } else {
                    SlockAvatarNativeOperationResult.BusyCleanup
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
                        SlockAndroidAvatarOperationResult(SlockAndroidAvatarOperationStatus.AlreadyApplied)
                    OperationPhase.RolledBack,
                    OperationPhase.Aborted,
                    OperationPhase.Revoked -> error("handled above")
                }
            val mapped = result.status.toSharedOperationResult(notFoundIsAccepted = true)
            if (mapped == SlockAvatarNativeOperationResult.Accepted) {
                operation.phase = OperationPhase.RolledBack
                if (!operation.closeAndDelete()) {
                    return@withLock SlockAvatarNativeOperationResult.BusyCleanup
                }
            }
            mapped
            }
        }

    override suspend fun retireExact(
        slot: SlockAvatarNativeSlot,
        expectedCommitIdentity: SlockAvatarCommitIdentity?
    ): SlockAvatarNativeOperationResult =
        withContext(Dispatchers.IO) {
            retireExactWithRenderDrain(slot.toAndroidKey(), expectedCommitIdentity.toAndroidOrNull())
        }

    override suspend fun retireSubject(
        slot: SlockAvatarNativeSlot
    ): SlockAvatarNativeOperationResult =
        withContext(Dispatchers.IO) {
            retireScope(
                SlockAndroidAvatarTerminalScope.Subject(
                    partitionKey = SlockAndroidAvatarKernelPartitionKey,
                    ownerKey = slot.nativePartitionKey,
                    subjectKey = slot.nativeSubjectKey
                )
            )
        }

    override suspend fun retireOwner(
        nativePartitionKey: String
    ): SlockAvatarNativeOperationResult =
        withContext(Dispatchers.IO) {
            retireScope(
                SlockAndroidAvatarTerminalScope.Owner(
                    partitionKey = SlockAndroidAvatarKernelPartitionKey,
                    ownerKey = nativePartitionKey
                )
            )
        }

    override suspend fun clearAll(): SlockAvatarNativeOperationResult =
        withContext(Dispatchers.IO) { retireScope(SlockAndroidAvatarTerminalScope.All) }

    internal fun retireLegacySubject(
        nativePartitionKey: String,
        nativeSubjectKey: String
    ): SlockAvatarNativeOperationResult =
        retireScope(
            SlockAndroidAvatarTerminalScope.Subject(
                partitionKey = SlockAndroidAvatarKernelPartitionKey,
                ownerKey = nativePartitionKey,
                subjectKey = nativeSubjectKey
            )
        )

    internal fun managedBytes(): Long? = kernel.managedBytes()

    internal fun beginRender(
        descriptor: SlockAvatarMountedRenderDescriptor
    ): SlockAndroidAvatarRenderRead? {
        if (!kernelReady) return null
        val key = descriptor.slot.toAndroidKey()
        if (!acquireRenderAdmission(key)) return null
        val current = kernel.readCurrent(key)
        val snapshot = current.snapshot
        val expected = descriptor.commitIdentity.toAndroid()
        if (current.status != SlockAndroidAvatarCurrentReadStatus.Available ||
            snapshot == null ||
            snapshot.entryIdentity.commitIdentity != expected
        ) {
            releaseRenderAdmission(key)
            return null
        }
        return SlockAndroidAvatarRenderRead(
            controller = this,
            key = key,
            snapshot = snapshot
        )
    }

    private fun retireScope(
        scope: SlockAndroidAvatarTerminalScope
    ): SlockAvatarNativeOperationResult {
        val boundReservations = mutableListOf<Pair<SlockAndroidAvatarOpaqueKey, SlockAvatarNativeReservation>>()
        val matchingOperations = mutableListOf<Operation>()
        lock.withLock {
            if (!kernelReady) return SlockAvatarNativeOperationResult.StorageFailure
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
                    return SlockAvatarNativeOperationResult.StorageFailure
                }
            }
        }
        boundReservations.forEach { (key, reservation) ->
            kernel.rollbackCandidate(key, reservation.commitIdentity.toAndroid())
        }
        val terminal =
            when (scope) {
                SlockAndroidAvatarTerminalScope.All -> kernel.clearAll()
                is SlockAndroidAvatarTerminalScope.Owner ->
                    kernel.retireOwner(scope.partitionKey, scope.ownerKey)
                is SlockAndroidAvatarTerminalScope.Subject ->
                    kernel.retireSubject(scope.partitionKey, scope.ownerKey, scope.subjectKey)
            }
        val trackedTempZero =
            lock.withLock {
                matchingOperations.all { operation ->
                    operation.closeAndDelete() && operation.file.isPhysicallyZero()
                }
            }
        val rootZero =
            scope !is SlockAndroidAvatarTerminalScope.All ||
                (trackedTempZero && stagingRoot.deleteTreeAndVerify())
        val tempZero = trackedTempZero && rootZero
        lock.withLock {
            if (terminal.status == SlockAndroidAvatarTerminalStatus.Complete && tempZero) {
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
            terminal.status == SlockAndroidAvatarTerminalStatus.Complete && tempZero ->
                SlockAvatarNativeOperationResult.Accepted
            terminal.status == SlockAndroidAvatarTerminalStatus.CleanupPending || !tempZero ->
                SlockAvatarNativeOperationResult.BusyCleanup
            else -> SlockAvatarNativeOperationResult.StorageFailure
        }
    }

    private fun retireExactWithRenderDrain(
        key: SlockAndroidAvatarOpaqueKey,
        expectedCommitIdentity: SlockAndroidAvatarCommitIdentity?
    ): SlockAvatarNativeOperationResult {
        val matchingOperations = mutableListOf<Operation>()
        lock.withLock {
            if (!kernelReady) return SlockAvatarNativeOperationResult.StorageFailure
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
                    return SlockAvatarNativeOperationResult.StorageFailure
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
                        SlockAvatarNativeOperationResult.Accepted,
                        SlockAvatarNativeOperationResult.ExpectedIdentityMismatch,
                        SlockAvatarNativeOperationResult.Stale -> true
                        else -> false
                    }
            if (cleanupDebtResolved) {
                operations.entries.removeAll { (_, operation) -> operation.key == key }
                retiringKeys -= key
            } else {
                retiringKeys += key
            }
        }
        return if (!tempZero) SlockAvatarNativeOperationResult.BusyCleanup else result
    }

    private fun acquireRenderAdmission(key: SlockAndroidAvatarOpaqueKey): Boolean =
        lock.withLock {
            if (!kernelReady || key in retiringKeys || retiringScopes.any(key::matches)) {
                return@withLock false
            }
            activeRenders[key] = (activeRenders[key] ?: 0) + 1
            true
        }

    private fun releaseRenderAdmission(key: SlockAndroidAvatarOpaqueKey) {
        lock.withLock {
            val remaining = (activeRenders[key] ?: 0) - 1
            if (remaining <= 0) activeRenders.remove(key) else activeRenders[key] = remaining
            renderDrained.signalAll()
        }
    }

    private fun SlockAndroidAvatarTerminalScope.containsControllerHold(
        candidate: SlockAndroidAvatarTerminalScope
    ): Boolean =
        when (this) {
            SlockAndroidAvatarTerminalScope.All -> true
            is SlockAndroidAvatarTerminalScope.Owner ->
                when (candidate) {
                    SlockAndroidAvatarTerminalScope.All -> false
                    is SlockAndroidAvatarTerminalScope.Owner ->
                        partitionKey == candidate.partitionKey && ownerKey == candidate.ownerKey
                    is SlockAndroidAvatarTerminalScope.Subject ->
                        partitionKey == candidate.partitionKey && ownerKey == candidate.ownerKey
                }
            is SlockAndroidAvatarTerminalScope.Subject -> this == candidate
        }

    private fun registerDecoded(
        key: SlockAndroidAvatarOpaqueKey,
        snapshot: SlockAndroidAvatarCurrentReadSnapshot,
        width: Int,
        height: Int,
        scaleIdentity: String
    ): Boolean =
        kernel.registerDecodedCurrent(
            entryIdentity = snapshot.entryIdentity,
            variant =
                SlockAndroidAvatarDecodedVariant(
                    width = width,
                    height = height,
                    scaleIdentity = scaleIdentity
                )
        ) && snapshot.entryIdentity.key == key

    private inner class StageSink(
        private val requestNonce: String
    ) : SlockAvatarNativeStageSink {
        override fun write(bytes: ByteArray): Boolean =
            lock.withLock {
                val operation = operations[requestNonce] ?: return@withLock false
                if (operation.phase != OperationPhase.Open || bytes.isEmpty()) return@withLock false
                val nextSize = operation.sizeBytes + bytes.size.toLong()
                if (nextSize > SLOCK_ANDROID_AVATAR_MAX_ENTRY_BYTES) {
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

        override fun finish(): SlockAvatarNativeStageFinish =
            lock.withLock {
                val operation = operations[requestNonce]
                    ?: return@withLock SlockAvatarNativeStageFinish.StorageFailure
                if (operation.phase == OperationPhase.Finished) {
                    return@withLock operation.staged
                        ?.let { staged -> SlockAvatarNativeStageFinish.Ready(staged) }
                        ?: SlockAvatarNativeStageFinish.StorageFailure
                }
                if (operation.phase != OperationPhase.Open) {
                    return@withLock if (operation.phase == OperationPhase.Aborted) {
                        SlockAvatarNativeStageFinish.InvalidContent
                    } else {
                        SlockAvatarNativeStageFinish.StorageFailure
                    }
                }
                if (operation.sizeBytes <= 0L) {
                    operation.phase = OperationPhase.Aborted
                    operation.closeAndDelete()
                    return@withLock SlockAvatarNativeStageFinish.InvalidContent
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
                    return@withLock SlockAvatarNativeStageFinish.StorageFailure
                }
                val bytes = runCatching { operation.file.readBytes() }.getOrNull()
                    ?.takeIf { it.size.toLong() == operation.sizeBytes }
                val contentType = bytes?.let(contentInspector::inspect)
                if (contentType == null) {
                    operation.phase = OperationPhase.Aborted
                    operation.closeAndDelete()
                    return@withLock SlockAvatarNativeStageFinish.InvalidContent
                }
                val staged =
                    SlockAvatarNativeStagedCandidate(
                        requestNonce = requestNonce,
                        sizeBytes = operation.sizeBytes,
                        contentType = contentType
                    )
                operation.staged = staged
                operation.phase = OperationPhase.Finished
                SlockAvatarNativeStageFinish.Ready(staged)
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

        internal fun create(context: Context): SlockAndroidAvatarCacheController {
            val root =
                context.applicationContext.cacheDir
                    .resolve(SLOCK_ANDROID_IMAGE_CACHE_DIR_NAME)
                    .resolve(SLOCK_ANDROID_AVATAR_NAMESPACE)
            val stagingRoot = root.resolve(SLOCK_ANDROID_AVATAR_STAGING_DIRECTORY)
            val stagingRecovered = recoverSlockAndroidAvatarTransportStaging(stagingRoot)
            val kernel =
                SlockAndroidAvatarDurableKernel(
                    files = SlockAndroidAvatarRealFileSystem(root)
                )
            return SlockAndroidAvatarCacheController(
                kernel = kernel,
                stagingRoot = stagingRoot,
                startupStagingRecovered = stagingRecovered
            )
        }
    }

    internal class SlockAndroidAvatarRenderRead internal constructor(
        private val controller: SlockAndroidAvatarCacheController,
        private val key: SlockAndroidAvatarOpaqueKey,
        private val snapshot: SlockAndroidAvatarCurrentReadSnapshot
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

internal fun recoverSlockAndroidAvatarTransportStaging(stagingRoot: File): Boolean =
    !stagingRoot.exists() ||
        (stagingRoot.deleteRecursively() && !stagingRoot.exists())

internal object SlockAndroidAvatarCacheProcess {
    private val lock = Any()
    private val startupExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "slock-avatar-cache-startup").apply { isDaemon = true }
    }

    @Volatile
    private var controller: SlockAndroidAvatarCacheController? = null

    fun install(context: Context): SlockAndroidAvatarCacheController =
        synchronized(lock) {
            controller ?: SlockAndroidAvatarCacheController.create(context).also { created ->
                controller = created
                SlockAvatarMountedNativeProviderRegistry.install(created)
            }
        }

    /** Starts durable-cache recovery without putting Files.walk/stat on the UI thread. */
    fun startAsync(context: Context) {
        synchronized(lock) {
            if (controller != null) return
            startupExecutor.execute {
                synchronized(lock) {
                    if (controller == null) {
                        controller = SlockAndroidAvatarCacheController.create(context)
                        SlockAvatarMountedNativeProviderRegistry.install(controller!!)
                    }
                }
            }
        }
    }

    fun currentOrNull(): SlockAndroidAvatarCacheController? = controller
}

private fun SlockAvatarNativeSlot.toAndroidKey(): SlockAndroidAvatarOpaqueKey =
    SlockAndroidAvatarOpaqueKey(
        partitionKey = SlockAndroidAvatarKernelPartitionKey,
        ownerKey = nativePartitionKey,
        subjectKey = nativeSubjectKey,
        bytesKey = nativeBytesKey
    )

private fun SlockAvatarCommitIdentity?.toAndroidOrNull(): SlockAndroidAvatarCommitIdentity? =
    this?.let { SlockAndroidAvatarCommitIdentity(it.commitGeneration, it.nonce) }

private fun SlockAvatarCommitIdentity.toAndroid(): SlockAndroidAvatarCommitIdentity =
    SlockAndroidAvatarCommitIdentity(commitGeneration, nonce)

private fun SlockAndroidAvatarCommitIdentity.toShared(): SlockAvatarCommitIdentity =
    SlockAvatarCommitIdentity(commitGeneration, nonce)

private fun Long.nextRequestGenerationOrNull(): Long? =
    takeIf { it < Long.MAX_VALUE }?.plus(1L)

private fun SlockAndroidAvatarOperationStatus.toSharedReserveResult(): SlockAvatarNativeReserveResult =
    when (this) {
        SlockAndroidAvatarOperationStatus.StaleRequest -> SlockAvatarNativeReserveResult.Stale
        SlockAndroidAvatarOperationStatus.AdmissionClosed ->
            SlockAvatarNativeReserveResult.AdmissionClosed
        SlockAndroidAvatarOperationStatus.IdentityMismatch,
        SlockAndroidAvatarOperationStatus.NotFound ->
            SlockAvatarNativeReserveResult.ExpectedIdentityMismatch
        SlockAndroidAvatarOperationStatus.Busy,
        SlockAndroidAvatarOperationStatus.CleanupPending ->
            SlockAvatarNativeReserveResult.BusyCleanup
        SlockAndroidAvatarOperationStatus.StorageFailure,
        SlockAndroidAvatarOperationStatus.CorruptState ->
            SlockAvatarNativeReserveResult.StorageFailure
        SlockAndroidAvatarOperationStatus.Applied,
        SlockAndroidAvatarOperationStatus.AlreadyApplied ->
            error("successful reservation requires an identity")
    }

private fun SlockAndroidAvatarOperationStatus.toSharedOperationResult(
    notFoundIsAccepted: Boolean
): SlockAvatarNativeOperationResult =
    when (this) {
        SlockAndroidAvatarOperationStatus.Applied,
        SlockAndroidAvatarOperationStatus.AlreadyApplied ->
            SlockAvatarNativeOperationResult.Accepted
        SlockAndroidAvatarOperationStatus.StaleRequest -> SlockAvatarNativeOperationResult.Stale
        SlockAndroidAvatarOperationStatus.AdmissionClosed ->
            SlockAvatarNativeOperationResult.AdmissionClosed
        SlockAndroidAvatarOperationStatus.IdentityMismatch ->
            SlockAvatarNativeOperationResult.ExpectedIdentityMismatch
        SlockAndroidAvatarOperationStatus.Busy,
        SlockAndroidAvatarOperationStatus.CleanupPending ->
            SlockAvatarNativeOperationResult.BusyCleanup
        SlockAndroidAvatarOperationStatus.NotFound ->
            if (notFoundIsAccepted) {
                SlockAvatarNativeOperationResult.Accepted
            } else {
                SlockAvatarNativeOperationResult.ExpectedIdentityMismatch
            }
        SlockAndroidAvatarOperationStatus.StorageFailure,
        SlockAndroidAvatarOperationStatus.CorruptState ->
            SlockAvatarNativeOperationResult.StorageFailure
    }
