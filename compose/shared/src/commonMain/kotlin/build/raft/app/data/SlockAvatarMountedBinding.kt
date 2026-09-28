package build.raft.app.data

import build.raft.app.network.SharedBinaryConditionalDownloadResult
import build.raft.app.network.SharedBinaryConditionalHttpException
import build.raft.app.network.SharedBinaryConditionalValidators
import build.raft.app.network.SharedBinaryDownloader
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import build.raft.app.slockLogWarn
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private const val SLOCK_AVATAR_MOUNTED_MODEL_PREFIX = "slock-avatar-render-v1:"
private val SlockAvatarOpaqueKeyPattern = Regex("[0-9a-f]{64}")

data class SlockAvatarNativeSlot(
    val nativePartitionKey: String,
    val nativeSubjectKey: String,
    val nativeBytesKey: String
) {
    init {
        require(nativePartitionKey.matches(SlockAvatarOpaqueKeyPattern))
        require(nativeSubjectKey.matches(SlockAvatarOpaqueKeyPattern))
        require(nativeBytesKey.matches(SlockAvatarOpaqueKeyPattern))
    }
}

/** Render-only identity consumed by the platform image adapter; it carries no URL or bytes. */
data class SlockAvatarMountedRenderDescriptor(
    val slot: SlockAvatarNativeSlot,
    val commitIdentity: SlockAvatarCommitIdentity,
    val modelRevision: Long
) {
    init {
        require(modelRevision > 0L)
    }
}

data class SlockAvatarNativeStageFence(
    val slot: SlockAvatarNativeSlot,
    val requestNonce: String,
    val expectedCommitIdentity: SlockAvatarCommitIdentity?,
    val expectedModelRevision: Long
) {
    init {
        require(requestNonce.matches(Regex("[0-9a-f]{64}")))
        require(expectedModelRevision >= 0L)
        require((expectedCommitIdentity == null) == (expectedModelRevision == 0L))
    }
}

data class SlockAvatarNativeStagedCandidate(
    val requestNonce: String,
    val sizeBytes: Long,
    val contentType: String?
) {
    init {
        require(requestNonce.matches(Regex("[0-9a-f]{64}")))
        require(sizeBytes > 0L)
        require(contentType == null || contentType.startsWith("image/", ignoreCase = true))
    }
}

sealed interface SlockAvatarNativeStageFinish {
    data class Ready(val candidate: SlockAvatarNativeStagedCandidate) : SlockAvatarNativeStageFinish
    data object InvalidContent : SlockAvatarNativeStageFinish
    data object StorageFailure : SlockAvatarNativeStageFinish
}

/** Native owns every byte received by this synchronous sink; shared never queues or aggregates it. */
interface SlockAvatarNativeStageSink {
    fun write(bytes: ByteArray): Boolean
    fun finish(): SlockAvatarNativeStageFinish
    fun abort()
}

sealed interface SlockAvatarNativeStageOpen {
    data class Opened(val sink: SlockAvatarNativeStageSink) : SlockAvatarNativeStageOpen
    data object AdmissionClosed : SlockAvatarNativeStageOpen
    data object BusyCleanup : SlockAvatarNativeStageOpen
    data object StorageFailure : SlockAvatarNativeStageOpen
    data object Unsupported : SlockAvatarNativeStageOpen
}

data class SlockAvatarNativeReservationRequest(
    val fence: SlockAvatarNativeStageFence,
    val candidateModelRevision: Long
) {
    init {
        require(candidateModelRevision > fence.expectedModelRevision)
    }
}

data class SlockAvatarNativeReservation(
    val requestNonce: String,
    val commitIdentity: SlockAvatarCommitIdentity,
    val candidateModelRevision: Long
) {
    init {
        require(requestNonce.matches(Regex("[0-9a-f]{64}")))
        require(candidateModelRevision > 0L)
    }
}

sealed interface SlockAvatarNativeReserveResult {
    data class Reserved(val reservation: SlockAvatarNativeReservation) : SlockAvatarNativeReserveResult
    data object Stale : SlockAvatarNativeReserveResult
    data object AdmissionClosed : SlockAvatarNativeReserveResult
    data object ExpectedIdentityMismatch : SlockAvatarNativeReserveResult
    data object BusyCleanup : SlockAvatarNativeReserveResult
    data object StorageFailure : SlockAvatarNativeReserveResult
    data object Unsupported : SlockAvatarNativeReserveResult
}

sealed interface SlockAvatarNativeOperationResult {
    data object Accepted : SlockAvatarNativeOperationResult
    data object Stale : SlockAvatarNativeOperationResult
    data object AdmissionClosed : SlockAvatarNativeOperationResult
    data object ExpectedIdentityMismatch : SlockAvatarNativeOperationResult
    data object BusyCleanup : SlockAvatarNativeOperationResult
    data object StorageFailure : SlockAvatarNativeOperationResult
    data object Unsupported : SlockAvatarNativeOperationResult
}

data class SlockAvatarMountedNativeInspection(
    val supported: Boolean,
    val bytesAvailable: Boolean,
    val bytesDecodable: Boolean
)

/** Consumer contract implemented by task #163; it performs no transport. */
interface SlockAvatarMountedNativeProvider {
    suspend fun inspect(slot: SlockAvatarNativeSlot): SlockAvatarMountedNativeInspection
    fun beginStage(fence: SlockAvatarNativeStageFence): SlockAvatarNativeStageOpen
    suspend fun reserve(request: SlockAvatarNativeReservationRequest): SlockAvatarNativeReserveResult
    suspend fun bindStage(
        staged: SlockAvatarNativeStagedCandidate,
        reservation: SlockAvatarNativeReservation
    ): SlockAvatarNativeOperationResult
    suspend fun abortStage(staged: SlockAvatarNativeStagedCandidate): SlockAvatarNativeOperationResult
    suspend fun finalize(reservation: SlockAvatarNativeReservation): SlockAvatarNativeOperationResult
    suspend fun rollback(reservation: SlockAvatarNativeReservation): SlockAvatarNativeOperationResult
    suspend fun retireExact(
        slot: SlockAvatarNativeSlot,
        expectedCommitIdentity: SlockAvatarCommitIdentity?
    ): SlockAvatarNativeOperationResult
    suspend fun retireSubject(slot: SlockAvatarNativeSlot): SlockAvatarNativeOperationResult
    suspend fun retireOwner(nativePartitionKey: String): SlockAvatarNativeOperationResult
    suspend fun clearAll(): SlockAvatarNativeOperationResult
}

internal object UnsupportedSlockAvatarMountedNativeProvider : SlockAvatarMountedNativeProvider {
    override suspend fun inspect(slot: SlockAvatarNativeSlot) =
        SlockAvatarMountedNativeInspection(false, false, false)

    override fun beginStage(fence: SlockAvatarNativeStageFence): SlockAvatarNativeStageOpen =
        SlockAvatarNativeStageOpen.Unsupported

    override suspend fun reserve(request: SlockAvatarNativeReservationRequest) =
        SlockAvatarNativeReserveResult.Unsupported

    override suspend fun bindStage(
        staged: SlockAvatarNativeStagedCandidate,
        reservation: SlockAvatarNativeReservation
    ) = SlockAvatarNativeOperationResult.Unsupported

    override suspend fun abortStage(staged: SlockAvatarNativeStagedCandidate) =
        SlockAvatarNativeOperationResult.Unsupported

    override suspend fun finalize(reservation: SlockAvatarNativeReservation) =
        SlockAvatarNativeOperationResult.Unsupported

    override suspend fun rollback(reservation: SlockAvatarNativeReservation) =
        SlockAvatarNativeOperationResult.Unsupported

    override suspend fun retireExact(
        slot: SlockAvatarNativeSlot,
        expectedCommitIdentity: SlockAvatarCommitIdentity?
    ) = SlockAvatarNativeOperationResult.Unsupported

    override suspend fun retireSubject(slot: SlockAvatarNativeSlot) =
        SlockAvatarNativeOperationResult.Unsupported

    override suspend fun retireOwner(nativePartitionKey: String) =
        SlockAvatarNativeOperationResult.Unsupported

    override suspend fun clearAll() = SlockAvatarNativeOperationResult.Unsupported
}

/** App-scope injection point. Platform registration changes no route/pageData or shared bytes owner. */
object SlockAvatarMountedNativeProviderRegistry {
    private val lock = SynchronizedObject()
    private var provider: SlockAvatarMountedNativeProvider = UnsupportedSlockAvatarMountedNativeProvider

    fun current(): SlockAvatarMountedNativeProvider = synchronized(lock) { provider }

    fun install(next: SlockAvatarMountedNativeProvider): SlockAvatarMountedNativeProvider =
        synchronized(lock) {
            val previous = provider
            provider = next
            previous
        }
}

internal fun interface SlockAvatarConditionalDownloader {
    suspend fun download(
        url: String,
        validators: SharedBinaryConditionalValidators?,
        onStart: (Long?) -> Unit,
        beforeRead: () -> Unit,
        onChunk: (ByteArray, Long, Long?) -> Unit
    ): SharedBinaryConditionalDownloadResult
}

internal object DefaultSlockAvatarConditionalDownloader : SlockAvatarConditionalDownloader {
    override suspend fun download(
        url: String,
        validators: SharedBinaryConditionalValidators?,
        onStart: (Long?) -> Unit,
        beforeRead: () -> Unit,
        onChunk: (ByteArray, Long, Long?) -> Unit
    ): SharedBinaryConditionalDownloadResult =
        SharedBinaryDownloader.downloadConditional(url, validators, onStart, beforeRead, onChunk)
}

internal class SlockAvatarMountedHandle internal constructor(
    internal val token: SlockAvatarMountToken?,
    internal val remoteUrl: String,
    internal var refreshNeeded: Boolean,
    private val mutableImageModel: MutableStateFlow<String?>
) {
    val imageModel: StateFlow<String?> = mutableImageModel
    internal fun update(model: String?) { mutableImageModel.value = model }
}

internal class SlockAvatarMountedBinding(
    private val coordinator: SlockAvatarCacheCoordinator,
    private val currentOwner: suspend (String?) -> SlockAvatarCacheOwner?,
    private val nativeProvider: () -> SlockAvatarMountedNativeProvider =
        SlockAvatarMountedNativeProviderRegistry::current,
    private val downloader: SlockAvatarConditionalDownloader = DefaultSlockAvatarConditionalDownloader,
    private val session: SlockAvatarMountedSession = SlockAvatarMountedSession()
) {
    private val log: (String) -> Unit = { message -> slockLogWarn(LogTag, message) }
    private val handlesLock = SynchronizedObject()
    private val handles = mutableMapOf<Long, SlockAvatarMountedHandle>()
    private val lastKnownOwners = mutableMapOf<String?, SlockAvatarCacheOwner>()
    private val flightsMutex = Mutex()
    private val runningFlights = mutableMapOf<String, CompletableDeferred<Unit>>()

    suspend fun mount(
        subject: SlockAvatarCacheSubject,
        remoteUrl: String,
        serverId: String? = null
    ): SlockAvatarMountedHandle {
        val normalizedUrl = remoteUrl.trim()
        val owner = runCatchingPreservingCancellation { currentOwner(serverId) }.getOrNull()
        if (owner != null) {
            synchronized(handlesLock) {
                lastKnownOwners[serverId] = owner
            }
        }
        val key = owner?.let { runCatching { SlockAvatarCacheKey(it, subject, slockAvatarContentIdentity(normalizedUrl)) }.getOrNull() }
        val provider = nativeProvider()
        if (key == null) {
            log("avatar_mount result=legacy reason=${if (owner == null) "owner_null" else "key_null"}")
            return legacyHandle(normalizedUrl)
        }
        val slot = key.nativeSlot()
        val inspection = runCatchingPreservingCancellation { provider.inspect(slot) }.getOrNull()
            ?: run {
                log("avatar_mount result=legacy reason=inspect_fail")
                return legacyHandle(normalizedUrl)
            }
        if (!inspection.supported) {
            log("avatar_mount result=legacy reason=unsupported")
            return legacyHandle(normalizedUrl)
        }
        session.snapshotFor(key.owner, key.subject)?.key?.takeIf { it != key }?.let { displaced ->
            publish(session.retireSubject(key.owner, key.subject))
            runCatchingPreservingCancellation { provider.retireSubject(displaced.nativeSlot()) }
        }

        val token = session.mount(key)
        val prepared =
            runCatchingPreservingCancellation {
                coordinator.prepareRead(key, inspection.bytesAvailable, inspection.bytesDecodable)
            }.getOrElse {
                session.dispose(token)
                log("avatar_mount result=legacy reason=prepare_read_fail")
                return legacyHandle(normalizedUrl)
            }
        session.seedPrepared(
            token = token,
            commitIdentity = prepared.commitIdentity,
            validators =
                prepared.validators
                    ?.takeIf { inspection.bytesAvailable && inspection.bytesDecodable }
                    ?.toMountedValidators(),
            modelRevision = prepared.modelRevision
        )
        val snapshot = session.snapshot(token)
        val initialModel =
            when (prepared.decision) {
                is SlockAvatarCacheReadDecision.RenderFresh,
                is SlockAvatarCacheReadDecision.RenderStaleAndRevalidate,
                is SlockAvatarCacheReadDecision.RenderStaleSuppressed -> snapshot?.managedImageModel()
                is SlockAvatarCacheReadDecision.Fetch,
                is SlockAvatarCacheReadDecision.Suppressed -> null
            }
        val handle =
            SlockAvatarMountedHandle(
                token = token,
                remoteUrl = normalizedUrl,
                refreshNeeded =
                    prepared.decision is SlockAvatarCacheReadDecision.Fetch ||
                        prepared.decision is SlockAvatarCacheReadDecision.RenderStaleAndRevalidate,
                mutableImageModel = MutableStateFlow(initialModel)
            )
        synchronized(handlesLock) { handles[token.mountId] = handle }
        return handle
    }

    fun peekInitialManagedModel(
        subject: SlockAvatarCacheSubject,
        remoteUrl: String,
        serverId: String? = null
    ): String? {
        val normalizedUrl = remoteUrl.trim()
        val content = runCatching { slockAvatarContentIdentity(normalizedUrl) }.getOrNull() ?: return null
        val owner = synchronized(handlesLock) { lastKnownOwners[serverId] }
        if (owner != null) {
            val key = SlockAvatarCacheKey(owner, subject, content)
            val snapshot = session.snapshotFor(owner, subject)
            if (snapshot?.key == key && snapshot.commitIdentity != null) {
                return snapshot.managedImageModel()
            }
        }
        val fallbackSnapshot = session.snapshotForSubject(subject, content)
        if (fallbackSnapshot?.commitIdentity != null) {
            return fallbackSnapshot.managedImageModel()
        }
        return null
    }

    fun dispose(handle: SlockAvatarMountedHandle): Boolean {
        val token = handle.token ?: return false
        synchronized(handlesLock) { handles.remove(token.mountId) }
        return session.dispose(token)
    }

    suspend fun refresh(handle: SlockAvatarMountedHandle) {
        val token = handle.token ?: return
        while (isRefreshNeeded(handle) && session.isCurrent(token)) {
            val flight = session.beginFlight(token) ?: return
            val (deferred, ownsFlight) =
                flightsMutex.withLock {
                    runningFlights[flight.requestNonce]?.let { it to false }
                        ?: CompletableDeferred<Unit>().also { runningFlights[flight.requestNonce] = it } to true
                }
            if (!ownsFlight) {
                try {
                    deferred.await()
                    return
                } catch (cancellation: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    continue
                }
            }
            try {
                refreshOnce(handle.remoteUrl, flight)
                deferred.complete(Unit)
                return
            } catch (cancellation: CancellationException) {
                deferred.completeExceptionally(cancellation)
                throw cancellation
            } catch (_: Throwable) {
                resolveTemporary(flight)
                deferred.complete(Unit)
                return
            } finally {
                flightsMutex.withLock { runningFlights.remove(flight.requestNonce) }
            }
        }
    }

    suspend fun retireSubject(owner: SlockAvatarCacheOwner, subject: SlockAvatarCacheSubject): Boolean {
        val slot = session.snapshotFor(owner, subject)?.key?.nativeSlot()
        publish(session.retireSubject(owner, subject))
        if (slot == null) return true
        val provider = nativeProvider()
        if (provider === UnsupportedSlockAvatarMountedNativeProvider) return true
        return runCatchingPreservingCancellation { provider.retireSubject(slot) }.getOrNull() ==
            SlockAvatarNativeOperationResult.Accepted
    }

    suspend fun retireOwner(owner: SlockAvatarCacheOwner): Boolean {
        publish(session.retireOwner(owner))
        val provider = nativeProvider()
        if (provider === UnsupportedSlockAvatarMountedNativeProvider) return true
        return runCatchingPreservingCancellation { provider.retireOwner(owner.nativePartitionKey) }.getOrNull() ==
            SlockAvatarNativeOperationResult.Accepted
    }

    suspend fun clearAll(): Boolean {
        publish(session.clearAll())
        val provider = nativeProvider()
        if (provider === UnsupportedSlockAvatarMountedNativeProvider) return true
        return runCatchingPreservingCancellation { provider.clearAll() }.getOrNull() ==
            SlockAvatarNativeOperationResult.Accepted
    }

    private suspend fun refreshOnce(remoteUrl: String, flight: SlockAvatarMountedFlight) {
        val provider = nativeProvider()
        val fence =
            SlockAvatarNativeStageFence(
                slot = flight.key.nativeSlot(),
                requestNonce = flight.requestNonce,
                expectedCommitIdentity = flight.expectedCommitIdentity,
                expectedModelRevision = flight.expectedModelRevision
            )
        val sink =
            (runCatching { provider.beginStage(fence) }.getOrNull() as? SlockAvatarNativeStageOpen.Opened)?.sink
                ?: return resolveTemporary(flight)
        var writeFailed = false
        val response =
            try {
                downloader.download(
                    url = remoteUrl,
                    validators = flight.validators?.toConditionalValidators(),
                    onStart = {},
                    beforeRead = {
                        if (!session.isFlightCurrent(flight)) {
                            throw SlockAvatarStaleFlightException
                        }
                    },
                    onChunk = { bytes, _, _ ->
                        if (!session.isFlightCurrent(flight)) {
                            throw SlockAvatarStaleFlightException
                        }
                        if (!sink.write(bytes)) {
                            writeFailed = true
                            throw SlockAvatarStageWriteException
                        }
                    }
                )
            } catch (failure: Throwable) {
                runCatching { sink.abort() }
                if (failure is CancellationException) throw failure
                when {
                    failure === SlockAvatarStaleFlightException -> Unit
                    writeFailed || failure === SlockAvatarStageWriteException -> {
                        log("avatar_flight result=fail reason=stage_write")
                        if (session.isFlightCurrent(flight)) resolveTemporary(flight)
                    }
                    failure is SharedBinaryConditionalHttpException && failure.statusCode in setOf(404, 410) -> {
                        log("avatar_flight result=fail reason=http_${failure.statusCode}")
                        retireAndClear(flight, provider, SlockAvatarMountedClearReason.ResourceAbsent)
                    }
                    else -> {
                        log("avatar_flight result=fail reason=${failure::class.simpleName ?: "unknown"}")
                        resolveTemporary(flight)
                    }
                }
                return
            }

        when (response) {
            is SharedBinaryConditionalDownloadResult.NotModified -> {
                runCatching { sink.abort() }
                resolveNotModified(flight, response)
            }
            is SharedBinaryConditionalDownloadResult.Modified -> {
                when (val finished = runCatching { sink.finish() }.getOrNull()) {
                    is SlockAvatarNativeStageFinish.Ready ->
                        commitModified(flight, provider, finished.candidate, response)
                    SlockAvatarNativeStageFinish.InvalidContent -> {
                        log("avatar_flight result=fail reason=invalid_content")
                        runCatching { sink.abort() }
                        retireAndClear(flight, provider, SlockAvatarMountedClearReason.InvalidContent)
                    }
                    SlockAvatarNativeStageFinish.StorageFailure,
                    null -> {
                        log("avatar_flight result=fail reason=storage_failure")
                        runCatching { sink.abort() }
                        resolveTemporary(flight)
                    }
                }
            }
        }
    }

    private suspend fun commitModified(
        flight: SlockAvatarMountedFlight,
        provider: SlockAvatarMountedNativeProvider,
        staged: SlockAvatarNativeStagedCandidate,
        response: SharedBinaryConditionalDownloadResult.Modified
    ) {
        var stagedNeedsAbort = true
        var reservation: SlockAvatarNativeReservation? = null
        var previousState: SlockAvatarCacheStoredState? = null
        var committedState: SlockAvatarCacheStoredState? = null
        var completed = false
        try {
            if (!session.isFlightCurrent(flight) || staged.requestNonce != flight.requestNonce) return
            val candidateRevision = nextMountedAvatarModelRevision(flight.expectedModelRevision)
            val reserved =
                provider.reserve(
                    SlockAvatarNativeReservationRequest(
                        fence =
                            SlockAvatarNativeStageFence(
                                slot = flight.key.nativeSlot(),
                                requestNonce = flight.requestNonce,
                                expectedCommitIdentity = flight.expectedCommitIdentity,
                                expectedModelRevision = flight.expectedModelRevision
                            ),
                        candidateModelRevision = candidateRevision
                    )
                ) as? SlockAvatarNativeReserveResult.Reserved ?: return
            val acceptedReservation = reserved.reservation
            reservation = acceptedReservation
            if (
                acceptedReservation.requestNonce != flight.requestNonce ||
                acceptedReservation.candidateModelRevision != candidateRevision ||
                acceptedReservation.commitIdentity.commitGeneration <=
                    (flight.expectedCommitIdentity?.commitGeneration ?: 0L) ||
                !session.isFlightCurrent(flight)
            ) return
            if (provider.bindStage(staged, acceptedReservation) != SlockAvatarNativeOperationResult.Accepted) return
            stagedNeedsAbort = false

            previousState = coordinator.storedState(flight.key)
            val guard = flight.commitGuard(session)
            val mutation =
                coordinator.reportSuccessfulBytes(
                    key = flight.key,
                    sizeBytes = staged.sizeBytes,
                    expectedCommitIdentity = flight.expectedCommitIdentity,
                    expectedModelRevision = flight.expectedModelRevision,
                    commitIdentity = acceptedReservation.commitIdentity,
                    modelRevision = candidateRevision,
                    eTag = response.eTag,
                    lastModified = response.lastModified,
                    contentType = staged.contentType ?: response.contentType,
                    guard = guard
                )
            if (mutation != SlockAvatarCacheMutationResult.Accepted) return
            committedState = coordinator.storedState(flight.key).takeIf { state ->
                state.metadata?.commitIdentity == acceptedReservation.commitIdentity &&
                    state.metadata.modelRevision == candidateRevision
            } ?: return
            if (provider.finalize(acceptedReservation) != SlockAvatarNativeOperationResult.Accepted) return
            val resolution =
                session.resolve(
                    flight,
                    SlockAvatarMountedOutcome.Reload(
                        expectedFromCommitIdentity = flight.expectedCommitIdentity,
                        committedToCommitIdentity = acceptedReservation.commitIdentity,
                        validators = response.toMountedValidators(),
                        newModelRevision = candidateRevision
                    )
                )
            if (resolution !is SlockAvatarMountedResolution.Applied) return
            publish(resolution.transitions)
            completed = true
        } finally {
            if (!completed) {
                withContext(NonCancellable) {
                    reservation?.let { runCatching { provider.rollback(it) } }
                    if (stagedNeedsAbort) runCatching { provider.abortStage(staged) }
                    val before = previousState
                    val committed = committedState
                    if (before != null && committed != null) {
                        restorePreviousState(flight, committed, before)
                    }
                }
                if (session.isFlightCurrent(flight)) resolveTemporary(flight)
            }
        }
    }

    private suspend fun resolveNotModified(
        flight: SlockAvatarMountedFlight,
        response: SharedBinaryConditionalDownloadResult.NotModified
    ) {
        val mutation =
            runCatchingPreservingCancellation {
                coordinator.reportNotModified(
                    key = flight.key,
                    expectedCommitIdentity = flight.expectedCommitIdentity,
                    expectedModelRevision = flight.expectedModelRevision,
                    eTag = response.eTag,
                    lastModified = response.lastModified,
                    guard = flight.commitGuard(session)
                )
            }.getOrNull()
        if (mutation != SlockAvatarCacheMutationResult.Accepted && !session.isFlightCurrent(flight)) return
        publish(
            session.resolve(
                flight,
                SlockAvatarMountedOutcome.Keep(
                    flight.expectedCommitIdentity,
                    SlockAvatarMountedKeepReason.NotModified
                )
            )
        )
    }

    private suspend fun retireAndClear(
        flight: SlockAvatarMountedFlight,
        provider: SlockAvatarMountedNativeProvider,
        reason: SlockAvatarMountedClearReason
    ) {
        if (!session.isFlightCurrent(flight)) return
        if (runCatchingPreservingCancellation {
                provider.retireExact(flight.key.nativeSlot(), flight.expectedCommitIdentity)
            }.getOrNull() !=
            SlockAvatarNativeOperationResult.Accepted
        ) return resolveTemporary(flight)
        val kind =
            when (reason) {
                SlockAvatarMountedClearReason.ResourceAbsent -> SlockAvatarCacheFailureKind.ResourceAbsent
                SlockAvatarMountedClearReason.InvalidContent -> SlockAvatarCacheFailureKind.InvalidContent
                SlockAvatarMountedClearReason.Terminal -> error("terminal clear is not a network outcome")
            }
        val mutation =
            runCatchingPreservingCancellation {
                coordinator.reportFailure(flight.key, kind, flight.commitGuard(session))
            }.getOrNull()
        if (mutation != SlockAvatarCacheMutationResult.Accepted) {
            runCatchingPreservingCancellation {
                coordinator.clearSubject(flight.key.owner, flight.key.subject)
            }
        }
        publish(session.resolve(flight, SlockAvatarMountedOutcome.Clear(flight.expectedCommitIdentity, reason)))
    }

    private suspend fun resolveTemporary(flight: SlockAvatarMountedFlight) {
        runCatchingPreservingCancellation {
            coordinator.reportFailure(
                flight.key,
                SlockAvatarCacheFailureKind.TemporaryTransport,
                flight.commitGuard(session)
            )
        }
        publish(
            session.resolve(
                flight,
                SlockAvatarMountedOutcome.Keep(
                    flight.expectedCommitIdentity,
                    SlockAvatarMountedKeepReason.TemporaryFailure
                )
            )
        )
    }

    private suspend fun restorePreviousState(
        flight: SlockAvatarMountedFlight,
        expectedCommittedState: SlockAvatarCacheStoredState,
        previousState: SlockAvatarCacheStoredState
    ) {
        runCatching {
            coordinator.restoreAfterNativeFinalizeFailure(
                key = flight.key,
                expectedCommittedState = expectedCommittedState,
                previousState = previousState,
                guard = flight.commitGuard(session)
            )
        }
    }

    private fun publish(resolution: SlockAvatarMountedResolution) {
        if (resolution is SlockAvatarMountedResolution.Applied) publish(resolution.transitions)
    }

    private fun publish(transitions: List<SlockAvatarMountedTransition>) {
        transitions.forEach { transition ->
            val handle =
                synchronized(handlesLock) {
                    handles[transition.token.mountId]?.also { it.refreshNeeded = false }
                } ?: return@forEach
            when (transition) {
                is SlockAvatarMountedTransition.Reload ->
                    handle.update(
                        SlockAvatarMountedSnapshot(
                            key = transition.token.key,
                            commitIdentity = transition.committedToCommitIdentity,
                            validators = null,
                            modelRevision = transition.newModelRevision
                        ).managedImageModel()
                    )
                is SlockAvatarMountedTransition.Keep -> Unit
                is SlockAvatarMountedTransition.Clear -> handle.update(null)
            }
        }
    }

    private fun legacyHandle(remoteUrl: String): SlockAvatarMountedHandle =
        SlockAvatarMountedHandle(null, remoteUrl, false, MutableStateFlow(remoteUrl))

    private fun isRefreshNeeded(handle: SlockAvatarMountedHandle): Boolean =
        synchronized(handlesLock) {
            val token = handle.token ?: return@synchronized false
            handles[token.mountId] === handle && handle.refreshNeeded
        }
}

private const val LogTag = "SlockAvatarBinding"

private object SlockAvatarStaleFlightException : IllegalStateException("stale avatar flight")
private object SlockAvatarStageWriteException : IllegalStateException("avatar stage write failed")

private fun SlockAvatarCacheKey.nativeSlot(): SlockAvatarNativeSlot =
    SlockAvatarNativeSlot(owner.nativePartitionKey, nativeSubjectKey, nativeBytesKey)

private fun SlockAvatarValidators.toConditionalValidators(): SharedBinaryConditionalValidators =
    SharedBinaryConditionalValidators(eTag, lastModified)

private fun SlockAvatarCacheValidators.toMountedValidators(): SlockAvatarValidators =
    SlockAvatarValidators(eTag, lastModified)

private fun SharedBinaryConditionalDownloadResult.toMountedValidators(): SlockAvatarValidators? =
    if (eTag != null || lastModified != null) SlockAvatarValidators(eTag, lastModified) else null

private fun SlockAvatarMountedFlight.commitGuard(session: SlockAvatarMountedSession) =
    SlockAvatarCacheCommitGuard(
        key = key,
        expectedCommit = SlockAvatarCacheCommitExpectation(key, expectedCommitIdentity, expectedModelRevision)
    ) { session.isFlightCurrent(this) }

private fun nextMountedAvatarModelRevision(current: Long): Long {
    check(current < Long.MAX_VALUE) { "avatar model revision exhausted" }
    return current + 1L
}

@OptIn(ExperimentalEncodingApi::class)
internal fun SlockAvatarMountedSnapshot.managedImageModel(): String? {
    val identity = commitIdentity ?: return null
    val json =
        JSONObject()
            .put("protocol", "slock_avatar_render_v1")
            .put("nativePartitionKey", key.owner.nativePartitionKey)
            .put("nativeSubjectKey", key.nativeSubjectKey)
            .put("nativeBytesKey", key.nativeBytesKey)
            .put("commitGeneration", identity.commitGeneration.toString())
            .put("commitNonce", identity.nonce)
            .put("modelRevision", modelRevision.toString())
    return SLOCK_AVATAR_MOUNTED_MODEL_PREFIX + Base64.encode(json.toString().encodeToByteArray())
}

/** Parses only the managed render protocol; ordinary URLs/assets/base64/local models return null. */
@OptIn(ExperimentalEncodingApi::class)
fun parseSlockAvatarMountedRenderModel(model: String): SlockAvatarMountedRenderDescriptor? {
    if (!model.startsWith(SLOCK_AVATAR_MOUNTED_MODEL_PREFIX)) return null
    val payload = model.removePrefix(SLOCK_AVATAR_MOUNTED_MODEL_PREFIX)
    val json =
        runCatching { JSONObject(Base64.decode(payload).decodeToString()) }.getOrNull()
            ?: return null
    if (json.optString("protocol") != "slock_avatar_render_v1") return null
    val generation = json.optString("commitGeneration").toLongOrNull()?.takeIf { it > 0L } ?: return null
    val revision = json.optString("modelRevision").toLongOrNull()?.takeIf { it > 0L } ?: return null
    return runCatching {
        SlockAvatarMountedRenderDescriptor(
            slot =
                SlockAvatarNativeSlot(
                    nativePartitionKey = json.optString("nativePartitionKey"),
                    nativeSubjectKey = json.optString("nativeSubjectKey"),
                    nativeBytesKey = json.optString("nativeBytesKey")
                ),
            commitIdentity =
                SlockAvatarCommitIdentity(
                    commitGeneration = generation,
                    nonce = json.optString("commitNonce")
                ),
            modelRevision = revision
        )
    }.getOrNull()
}
