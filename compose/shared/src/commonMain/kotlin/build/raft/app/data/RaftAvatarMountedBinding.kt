package build.raft.app.data

import build.raft.app.network.SharedBinaryConditionalDownloadResult
import build.raft.app.network.SharedBinaryConditionalHttpException
import build.raft.app.network.SharedBinaryConditionalValidators
import build.raft.app.network.SharedBinaryDownloader
import build.raft.app.optString
import build.raft.app.parseRaftJsonObject
import build.raft.app.toRaftJsonString
import build.raft.app.raftAvatarDiagnosticsHash
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.TimeSource
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val RAFT_AVATAR_MOUNTED_MODEL_PREFIX = "slock-avatar-render-v1:"
private val RaftAvatarOpaqueKeyPattern = Regex("[0-9a-f]{64}")

data class RaftAvatarNativeSlot(
    val nativePartitionKey: String,
    val nativeSubjectKey: String,
    val nativeBytesKey: String
) {
    init {
        require(nativePartitionKey.matches(RaftAvatarOpaqueKeyPattern))
        require(nativeSubjectKey.matches(RaftAvatarOpaqueKeyPattern))
        require(nativeBytesKey.matches(RaftAvatarOpaqueKeyPattern))
    }
}

/** Render-only identity consumed by the platform image adapter; it carries no URL or bytes. */
data class RaftAvatarMountedRenderDescriptor(
    val slot: RaftAvatarNativeSlot,
    val commitIdentity: RaftAvatarCommitIdentity,
    val modelRevision: Long
) {
    init {
        require(modelRevision > 0L)
    }
}

data class RaftAvatarNativeStageFence(
    val slot: RaftAvatarNativeSlot,
    val requestNonce: String,
    val expectedCommitIdentity: RaftAvatarCommitIdentity?,
    val expectedModelRevision: Long
) {
    init {
        require(requestNonce.matches(Regex("[0-9a-f]{64}")))
        require(expectedModelRevision >= 0L)
        require((expectedCommitIdentity == null) == (expectedModelRevision == 0L))
    }
}

data class RaftAvatarNativeStagedCandidate(
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

sealed interface RaftAvatarNativeStageFinish {
    data class Ready(val candidate: RaftAvatarNativeStagedCandidate) : RaftAvatarNativeStageFinish
    data object InvalidContent : RaftAvatarNativeStageFinish
    data object StorageFailure : RaftAvatarNativeStageFinish
}

/** Native owns every byte received by this synchronous sink; shared never queues or aggregates it. */
interface RaftAvatarNativeStageSink {
    fun write(bytes: ByteArray): Boolean
    fun finish(): RaftAvatarNativeStageFinish
    fun abort()
}

sealed interface RaftAvatarNativeStageOpen {
    data class Opened(val sink: RaftAvatarNativeStageSink) : RaftAvatarNativeStageOpen
    data object AdmissionClosed : RaftAvatarNativeStageOpen
    data object BusyCleanup : RaftAvatarNativeStageOpen
    data object StorageFailure : RaftAvatarNativeStageOpen
    data object Unsupported : RaftAvatarNativeStageOpen
}

data class RaftAvatarNativeReservationRequest(
    val fence: RaftAvatarNativeStageFence,
    val candidateModelRevision: Long
) {
    init {
        require(candidateModelRevision > fence.expectedModelRevision)
    }
}

data class RaftAvatarNativeReservation(
    val requestNonce: String,
    val commitIdentity: RaftAvatarCommitIdentity,
    val candidateModelRevision: Long
) {
    init {
        require(requestNonce.matches(Regex("[0-9a-f]{64}")))
        require(candidateModelRevision > 0L)
    }
}

sealed interface RaftAvatarNativeReserveResult {
    data class Reserved(val reservation: RaftAvatarNativeReservation) : RaftAvatarNativeReserveResult
    data object Stale : RaftAvatarNativeReserveResult
    data object AdmissionClosed : RaftAvatarNativeReserveResult
    data object ExpectedIdentityMismatch : RaftAvatarNativeReserveResult
    data object BusyCleanup : RaftAvatarNativeReserveResult
    data object StorageFailure : RaftAvatarNativeReserveResult
    data object Unsupported : RaftAvatarNativeReserveResult
}

sealed interface RaftAvatarNativeOperationResult {
    data object Accepted : RaftAvatarNativeOperationResult
    data object Stale : RaftAvatarNativeOperationResult
    data object AdmissionClosed : RaftAvatarNativeOperationResult
    data object ExpectedIdentityMismatch : RaftAvatarNativeOperationResult
    data object BusyCleanup : RaftAvatarNativeOperationResult
    data object StorageFailure : RaftAvatarNativeOperationResult
    data object Unsupported : RaftAvatarNativeOperationResult
}

data class RaftAvatarMountedNativeInspection(
    val supported: Boolean,
    val bytesAvailable: Boolean,
    val bytesDecodable: Boolean
)

/** Consumer contract implemented by task #163; it performs no transport. */
interface RaftAvatarMountedNativeProvider {
    suspend fun inspect(slot: RaftAvatarNativeSlot): RaftAvatarMountedNativeInspection
    fun beginStage(fence: RaftAvatarNativeStageFence): RaftAvatarNativeStageOpen
    suspend fun reserve(request: RaftAvatarNativeReservationRequest): RaftAvatarNativeReserveResult
    suspend fun bindStage(
        staged: RaftAvatarNativeStagedCandidate,
        reservation: RaftAvatarNativeReservation
    ): RaftAvatarNativeOperationResult
    suspend fun abortStage(staged: RaftAvatarNativeStagedCandidate): RaftAvatarNativeOperationResult
    suspend fun finalize(reservation: RaftAvatarNativeReservation): RaftAvatarNativeOperationResult
    suspend fun rollback(reservation: RaftAvatarNativeReservation): RaftAvatarNativeOperationResult
    suspend fun retireExact(
        slot: RaftAvatarNativeSlot,
        expectedCommitIdentity: RaftAvatarCommitIdentity?
    ): RaftAvatarNativeOperationResult
    suspend fun retireSubject(slot: RaftAvatarNativeSlot): RaftAvatarNativeOperationResult
    suspend fun retireOwner(nativePartitionKey: String): RaftAvatarNativeOperationResult
    suspend fun clearAll(): RaftAvatarNativeOperationResult
}

internal object UnsupportedRaftAvatarMountedNativeProvider : RaftAvatarMountedNativeProvider {
    override suspend fun inspect(slot: RaftAvatarNativeSlot) =
        RaftAvatarMountedNativeInspection(false, false, false)

    override fun beginStage(fence: RaftAvatarNativeStageFence): RaftAvatarNativeStageOpen =
        RaftAvatarNativeStageOpen.Unsupported

    override suspend fun reserve(request: RaftAvatarNativeReservationRequest) =
        RaftAvatarNativeReserveResult.Unsupported

    override suspend fun bindStage(
        staged: RaftAvatarNativeStagedCandidate,
        reservation: RaftAvatarNativeReservation
    ) = RaftAvatarNativeOperationResult.Unsupported

    override suspend fun abortStage(staged: RaftAvatarNativeStagedCandidate) =
        RaftAvatarNativeOperationResult.Unsupported

    override suspend fun finalize(reservation: RaftAvatarNativeReservation) =
        RaftAvatarNativeOperationResult.Unsupported

    override suspend fun rollback(reservation: RaftAvatarNativeReservation) =
        RaftAvatarNativeOperationResult.Unsupported

    override suspend fun retireExact(
        slot: RaftAvatarNativeSlot,
        expectedCommitIdentity: RaftAvatarCommitIdentity?
    ) = RaftAvatarNativeOperationResult.Unsupported

    override suspend fun retireSubject(slot: RaftAvatarNativeSlot) =
        RaftAvatarNativeOperationResult.Unsupported

    override suspend fun retireOwner(nativePartitionKey: String) =
        RaftAvatarNativeOperationResult.Unsupported

    override suspend fun clearAll() = RaftAvatarNativeOperationResult.Unsupported
}

/** App-scope injection point. Platform registration changes no route/pageData or shared bytes owner. */
object RaftAvatarMountedNativeProviderRegistry {
    private val lock = SynchronizedObject()
    private var provider: RaftAvatarMountedNativeProvider = UnsupportedRaftAvatarMountedNativeProvider

    fun current(): RaftAvatarMountedNativeProvider = synchronized(lock) { provider }

    fun install(next: RaftAvatarMountedNativeProvider): RaftAvatarMountedNativeProvider =
        synchronized(lock) {
            val previous = provider
            provider = next
            previous
        }
}

internal fun interface RaftAvatarConditionalDownloader {
    suspend fun download(
        url: String,
        validators: SharedBinaryConditionalValidators?,
        onStart: (Long?) -> Unit,
        beforeRead: () -> Unit,
        onChunk: (ByteArray, Long, Long?) -> Unit
    ): SharedBinaryConditionalDownloadResult
}

internal object DefaultRaftAvatarConditionalDownloader : RaftAvatarConditionalDownloader {
    override suspend fun download(
        url: String,
        validators: SharedBinaryConditionalValidators?,
        onStart: (Long?) -> Unit,
        beforeRead: () -> Unit,
        onChunk: (ByteArray, Long, Long?) -> Unit
    ): SharedBinaryConditionalDownloadResult =
        SharedBinaryDownloader.downloadConditional(url, validators, onStart, beforeRead, onChunk)
}

internal class RaftAvatarMountedHandle internal constructor(
    internal val token: RaftAvatarMountToken?,
    internal val remoteUrl: String,
    internal var refreshNeeded: Boolean,
    private val mutableImageModel: MutableStateFlow<String?>
) {
    val imageModel: StateFlow<String?> = mutableImageModel
    internal fun update(model: String?) { mutableImageModel.value = model }
}

internal class RaftAvatarMountedBinding(
    private val coordinator: RaftAvatarCacheCoordinator,
    private val currentOwner: suspend (String?) -> RaftAvatarCacheOwner?,
    private val nativeProvider: () -> RaftAvatarMountedNativeProvider =
        RaftAvatarMountedNativeProviderRegistry::current,
    private val downloader: RaftAvatarConditionalDownloader = DefaultRaftAvatarConditionalDownloader,
    private val diagnostics: (String) -> Unit = {},
    private val session: RaftAvatarMountedSession = RaftAvatarMountedSession(),
    private val flightScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    private val handlesLock = SynchronizedObject()
    private val handles = mutableMapOf<Long, RaftAvatarMountedHandle>()
    private val lastKnownOwners = mutableMapOf<String?, RaftAvatarCacheOwner>()
    private val prewarmedOwners = mutableSetOf<RaftAvatarCacheOwner>()
    private val flightsMutex = Mutex()
    private val runningFlights = mutableMapOf<String, CompletableDeferred<Unit>>()

    suspend fun mount(
        subject: RaftAvatarCacheSubject,
        remoteUrl: String,
        serverId: String? = null
    ): RaftAvatarMountedHandle {
        val normalizedUrl = remoteUrl.trim()
        val owner = runCatchingPreservingCancellation { currentOwner(serverId) }.getOrNull()
        if (owner != null) {
            synchronized(handlesLock) {
                lastKnownOwners[serverId] = owner
            }
        }
        val key = owner?.let { runCatching { RaftAvatarCacheKey(it, subject, raftAvatarContentIdentity(normalizedUrl)) }.getOrNull() }
        val provider = nativeProvider()
        if (key == null) {
            diagnostics(
                "avatar_mount result=legacy reason=${if (owner == null) "owner_null" else "key_null"} " +
                    "serverHash=${serverId.raftAvatarDiagnosticsHash()} " +
                    "subjectHash=${subject.diagnosticsHash()}"
            )
            return legacyHandle(normalizedUrl)
        }
        val slot = key.nativeSlot()
        val inspection = runCatchingPreservingCancellation { provider.inspect(slot) }.getOrNull()
            ?: run {
                diagnostics(
                    "avatar_mount result=legacy reason=inspect_fail " +
                        "serverHash=${serverId.raftAvatarDiagnosticsHash()} " +
                        "subjectHash=${subject.diagnosticsHash()}"
                )
                return legacyHandle(normalizedUrl)
            }
        if (!inspection.supported) {
            diagnostics(
                "avatar_mount result=legacy reason=inspect_unsupported " +
                    "serverHash=${serverId.raftAvatarDiagnosticsHash()} " +
                    "subjectHash=${subject.diagnosticsHash()}"
            )
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
                diagnostics(
                    "avatar_mount result=legacy reason=prepare_fail " +
                        "serverHash=${serverId.raftAvatarDiagnosticsHash()} " +
                        "subjectHash=${subject.diagnosticsHash()}"
                )
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
                is RaftAvatarCacheReadDecision.RenderFresh,
                is RaftAvatarCacheReadDecision.RenderStaleAndRevalidate,
                is RaftAvatarCacheReadDecision.RenderStaleSuppressed -> snapshot?.managedImageModel()
                is RaftAvatarCacheReadDecision.Fetch,
                is RaftAvatarCacheReadDecision.Suppressed -> null
            }
        val handle =
            RaftAvatarMountedHandle(
                token = token,
                remoteUrl = normalizedUrl,
                refreshNeeded =
                    prepared.decision is RaftAvatarCacheReadDecision.Fetch ||
                        prepared.decision is RaftAvatarCacheReadDecision.RenderStaleAndRevalidate,
                mutableImageModel = MutableStateFlow(initialModel)
            )
        synchronized(handlesLock) { handles[token.mountId] = handle }
        return handle
    }

    /**
     * task #343: cold-start prewarm. On a fresh process the mounted session is empty, so
     * [peekInitialManagedModel] misses and the first frame cannot paint durable avatars. Once the
     * owner for [serverId] resolves, seed the session from the owner's exact committed rows whose
     * native bytes are present and decodable, so first-frame peeks hit.
     *
     * Safety: runs only after the owner resolves (retire/epoch chains stay authoritative); seeds via
     * [RaftAvatarMountedSession.seedIdle], which only fills slots untouched in this process, so a
     * live mount, flight, or in-memory commit always wins; rows suppressed by an unexpired failure or
     * without an exact commit identity are excluded by the store; validators are carried only when
     * the native bytes can back a 304 (task #289).
     *
     * @return the number of slots seeded.
     */
    suspend fun prewarmOwner(serverId: String?, limit: Long = PrewarmOwnerLimit): Int {
        val provider = nativeProvider()
        // Before marking the owner: a platform provider registered later must still get a prewarm.
        if (provider === UnsupportedRaftAvatarMountedNativeProvider) return 0
        val owner = runCatchingPreservingCancellation { currentOwner(serverId) }.getOrNull() ?: return 0
        val firstForOwner =
            synchronized(handlesLock) {
                // Same keying as mount: only the requested serverId. Writing the null key here
                // would pin it to the first owner across server switches.
                lastKnownOwners[serverId] = owner
                prewarmedOwners.add(owner)
            }
        // Auth metadata events fire often; one prewarm per owner per process is enough. Retirement
        // below re-arms it, since that clears the owner's session slots.
        if (!firstForOwner) return 0
        val rows =
            runCatchingPreservingCancellation { coordinator.committedForOwner(owner, limit) }
                .getOrElse {
                    diagnostics("avatar_prewarm result=read_fail serverHash=${serverId.raftAvatarDiagnosticsHash()}")
                    return 0
                }
        var seeded = 0
        for (row in rows) {
            val key = row.key
            if (key.owner != owner || !row.hasExactCommitIdentity) continue
            val identity = row.commitIdentity ?: continue
            // Cheap pre-check; seedIdle re-checks atomically, so a live mount always wins.
            if (session.snapshotFor(owner, key.subject) != null) continue
            val inspection =
                runCatchingPreservingCancellation { provider.inspect(key.nativeSlot()) }.getOrNull() ?: continue
            if (!inspection.supported || !inspection.bytesAvailable || !inspection.bytesDecodable) continue
            val validators =
                runCatching { RaftAvatarValidators(eTag = row.eTag, lastModified = row.lastModified) }.getOrNull()
            if (session.seedIdle(key, identity, validators, row.modelRevision)) seeded += 1
        }
        diagnostics(
            "avatar_prewarm result=ok rows=${rows.size} seeded=$seeded " +
                "serverHash=${serverId.raftAvatarDiagnosticsHash()}"
        )
        return seeded
    }

    fun peekInitialManagedModel(
        subject: RaftAvatarCacheSubject,
        remoteUrl: String,
        serverId: String? = null
    ): String? {
        val normalizedUrl = remoteUrl.trim()
        val content = runCatching { raftAvatarContentIdentity(normalizedUrl) }.getOrNull() ?: return null
        val owner = synchronized(handlesLock) { lastKnownOwners[serverId] }
        if (owner != null) {
            val key = RaftAvatarCacheKey(owner, subject, content)
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

    fun dispose(handle: RaftAvatarMountedHandle): Boolean {
        val token = handle.token ?: return false
        synchronized(handlesLock) { handles.remove(token.mountId) }
        return session.dispose(token)
    }

    suspend fun refresh(handle: RaftAvatarMountedHandle) {
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
            // The transport runs in flightScope, not this coroutine: composable churn cancels the
            // awaiting effect while the download and its session/native commit keep running, so a
            // cancelled refresh can no longer strand a live flight or leave durable failure rows
            // for a request that actually succeeded.
            val transport =
                flightScope.async {
                    try {
                        refreshOnce(handle.remoteUrl, flight)
                    } catch (cancellation: CancellationException) {
                        // A cancelled transport never resolved its generation: retire the flight
                        // so a later refresh starts a new one instead of joining a dead generation.
                        // No durable failure is written; a cancelled download is not evidence of a
                        // transport problem.
                        session.abandonFlight(flight)
                        throw cancellation
                    } catch (_: Throwable) {
                        resolveTemporary(flight)
                    }
                }
            try {
                transport.await()
                deferred.complete(Unit)
                return
            } catch (cancellation: CancellationException) {
                // The awaiting effect was cancelled, or the transport cancelled itself. Wait for
                // the detached transport to settle (non-cancellable: this coroutine may already be
                // cancelled) before handing the flight slot back.
                withContext(NonCancellable) { runCatching { transport.await() } }
                deferred.complete(Unit)
                return
            } finally {
                flightsMutex.withLock { runningFlights.remove(flight.requestNonce) }
            }
        }
    }

    suspend fun retireSubject(owner: RaftAvatarCacheOwner, subject: RaftAvatarCacheSubject): Boolean {
        val slot = session.snapshotFor(owner, subject)?.key?.nativeSlot()
        publish(session.retireSubject(owner, subject))
        if (slot == null) return true
        val provider = nativeProvider()
        if (provider === UnsupportedRaftAvatarMountedNativeProvider) return true
        return runCatchingPreservingCancellation { provider.retireSubject(slot) }.getOrNull() ==
            RaftAvatarNativeOperationResult.Accepted
    }

    suspend fun retireOwner(owner: RaftAvatarCacheOwner): Boolean {
        synchronized(handlesLock) { prewarmedOwners.remove(owner) }
        publish(session.retireOwner(owner))
        val provider = nativeProvider()
        if (provider === UnsupportedRaftAvatarMountedNativeProvider) return true
        return runCatchingPreservingCancellation { provider.retireOwner(owner.nativePartitionKey) }.getOrNull() ==
            RaftAvatarNativeOperationResult.Accepted
    }

    suspend fun clearAll(): Boolean {
        synchronized(handlesLock) { prewarmedOwners.clear() }
        publish(session.clearAll())
        val provider = nativeProvider()
        if (provider === UnsupportedRaftAvatarMountedNativeProvider) return true
        return runCatchingPreservingCancellation { provider.clearAll() }.getOrNull() ==
            RaftAvatarNativeOperationResult.Accepted
    }

    private suspend fun refreshOnce(remoteUrl: String, flight: RaftAvatarMountedFlight) {
        val provider = nativeProvider()
        val fence =
            RaftAvatarNativeStageFence(
                slot = flight.key.nativeSlot(),
                requestNonce = flight.requestNonce,
                expectedCommitIdentity = flight.expectedCommitIdentity,
                expectedModelRevision = flight.expectedModelRevision
            )
        val sink =
            (runCatching { provider.beginStage(fence) }.getOrNull() as? RaftAvatarNativeStageOpen.Opened)?.sink
                ?: return resolveTemporary(flight)
        var writeFailed = false
        val startedAt = TimeSource.Monotonic.markNow()
        val response =
            try {
                downloader.download(
                    url = remoteUrl,
                    validators = flight.validators?.toConditionalValidators(),
                    onStart = {},
                    beforeRead = {
                        if (!session.isFlightCurrent(flight)) {
                            throw RaftAvatarStaleFlightException
                        }
                    },
                    onChunk = { bytes, _, _ ->
                        if (!session.isFlightCurrent(flight)) {
                            throw RaftAvatarStaleFlightException
                        }
                        if (!sink.write(bytes)) {
                            writeFailed = true
                            throw RaftAvatarStageWriteException
                        }
                    }
                )
            } catch (failure: Throwable) {
                runCatching { sink.abort() }
                if (failure is CancellationException) throw failure
                diagnostics(
                    "avatar_refresh_failure ${failure.diagnosticsKind(writeFailed)} " +
                        "ms=${startedAt.elapsedNow().inWholeMilliseconds} " +
                        "serverHash=${flight.key.owner.serverId.raftAvatarDiagnosticsHash()} " +
                        "subjectHash=${flight.key.subject.diagnosticsHash()}"
                )
                when {
                    failure === RaftAvatarStaleFlightException -> Unit
                    writeFailed || failure === RaftAvatarStageWriteException -> {
                        if (session.isFlightCurrent(flight)) resolveTemporary(flight)
                    }
                    failure is SharedBinaryConditionalHttpException && failure.statusCode in setOf(404, 410) ->
                        retireAndClear(flight, provider, RaftAvatarMountedClearReason.ResourceAbsent)
                    else -> resolveTemporary(flight)
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
                    is RaftAvatarNativeStageFinish.Ready ->
                        commitModified(flight, provider, finished.candidate, response)
                    RaftAvatarNativeStageFinish.InvalidContent -> {
                        runCatching { sink.abort() }
                        retireAndClear(flight, provider, RaftAvatarMountedClearReason.InvalidContent)
                    }
                    RaftAvatarNativeStageFinish.StorageFailure,
                    null -> {
                        runCatching { sink.abort() }
                        resolveTemporary(flight)
                    }
                }
            }
        }
    }

    private suspend fun commitModified(
        flight: RaftAvatarMountedFlight,
        provider: RaftAvatarMountedNativeProvider,
        staged: RaftAvatarNativeStagedCandidate,
        response: SharedBinaryConditionalDownloadResult.Modified
    ) {
        var stagedNeedsAbort = true
        var reservation: RaftAvatarNativeReservation? = null
        var previousState: RaftAvatarCacheStoredState? = null
        var committedState: RaftAvatarCacheStoredState? = null
        var completed = false
        try {
            if (!session.isFlightCurrent(flight) || staged.requestNonce != flight.requestNonce) return
            val candidateRevision = nextMountedAvatarModelRevision(flight.expectedModelRevision)
            val reserved =
                provider.reserve(
                    RaftAvatarNativeReservationRequest(
                        fence =
                            RaftAvatarNativeStageFence(
                                slot = flight.key.nativeSlot(),
                                requestNonce = flight.requestNonce,
                                expectedCommitIdentity = flight.expectedCommitIdentity,
                                expectedModelRevision = flight.expectedModelRevision
                            ),
                        candidateModelRevision = candidateRevision
                    )
                ) as? RaftAvatarNativeReserveResult.Reserved ?: return
            val acceptedReservation = reserved.reservation
            reservation = acceptedReservation
            if (
                acceptedReservation.requestNonce != flight.requestNonce ||
                acceptedReservation.candidateModelRevision != candidateRevision ||
                acceptedReservation.commitIdentity.commitGeneration <=
                    (flight.expectedCommitIdentity?.commitGeneration ?: 0L) ||
                !session.isFlightCurrent(flight)
            ) return
            if (provider.bindStage(staged, acceptedReservation) != RaftAvatarNativeOperationResult.Accepted) return
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
            if (mutation != RaftAvatarCacheMutationResult.Accepted) return
            committedState = coordinator.storedState(flight.key).takeIf { state ->
                state.metadata?.commitIdentity == acceptedReservation.commitIdentity &&
                    state.metadata.modelRevision == candidateRevision
            } ?: return
            if (provider.finalize(acceptedReservation) != RaftAvatarNativeOperationResult.Accepted) return
            val resolution =
                session.resolve(
                    flight,
                    RaftAvatarMountedOutcome.Reload(
                        expectedFromCommitIdentity = flight.expectedCommitIdentity,
                        committedToCommitIdentity = acceptedReservation.commitIdentity,
                        validators = response.toMountedValidators(),
                        newModelRevision = candidateRevision
                    )
                )
            if (resolution !is RaftAvatarMountedResolution.Applied) return
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
        flight: RaftAvatarMountedFlight,
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
        if (mutation != RaftAvatarCacheMutationResult.Accepted && !session.isFlightCurrent(flight)) return
        publish(
            session.resolve(
                flight,
                RaftAvatarMountedOutcome.Keep(
                    flight.expectedCommitIdentity,
                    RaftAvatarMountedKeepReason.NotModified
                )
            )
        )
    }

    private suspend fun retireAndClear(
        flight: RaftAvatarMountedFlight,
        provider: RaftAvatarMountedNativeProvider,
        reason: RaftAvatarMountedClearReason
    ) {
        if (!session.isFlightCurrent(flight)) return
        if (runCatchingPreservingCancellation {
                provider.retireExact(flight.key.nativeSlot(), flight.expectedCommitIdentity)
            }.getOrNull() !=
            RaftAvatarNativeOperationResult.Accepted
        ) return resolveTemporary(flight)
        val kind =
            when (reason) {
                RaftAvatarMountedClearReason.ResourceAbsent -> RaftAvatarCacheFailureKind.ResourceAbsent
                RaftAvatarMountedClearReason.InvalidContent -> RaftAvatarCacheFailureKind.InvalidContent
                RaftAvatarMountedClearReason.Terminal -> error("terminal clear is not a network outcome")
            }
        val mutation =
            runCatchingPreservingCancellation {
                coordinator.reportFailure(flight.key, kind, flight.commitGuard(session))
            }.getOrNull()
        if (mutation != RaftAvatarCacheMutationResult.Accepted) {
            runCatchingPreservingCancellation {
                coordinator.clearSubject(flight.key.owner, flight.key.subject)
            }
        }
        publish(session.resolve(flight, RaftAvatarMountedOutcome.Clear(flight.expectedCommitIdentity, reason)))
    }

    private suspend fun resolveTemporary(flight: RaftAvatarMountedFlight) {
        runCatchingPreservingCancellation {
            coordinator.reportFailure(
                flight.key,
                RaftAvatarCacheFailureKind.TemporaryTransport,
                flight.commitGuard(session)
            )
        }
        publish(
            session.resolve(
                flight,
                RaftAvatarMountedOutcome.Keep(
                    flight.expectedCommitIdentity,
                    RaftAvatarMountedKeepReason.TemporaryFailure
                )
            )
        )
    }

    private suspend fun restorePreviousState(
        flight: RaftAvatarMountedFlight,
        expectedCommittedState: RaftAvatarCacheStoredState,
        previousState: RaftAvatarCacheStoredState
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

    private fun publish(resolution: RaftAvatarMountedResolution) {
        if (resolution is RaftAvatarMountedResolution.Applied) publish(resolution.transitions)
    }

    private fun publish(transitions: List<RaftAvatarMountedTransition>) {
        transitions.forEach { transition ->
            val handle =
                synchronized(handlesLock) {
                    handles[transition.token.mountId]?.also { it.refreshNeeded = false }
                } ?: return@forEach
            when (transition) {
                is RaftAvatarMountedTransition.Reload ->
                    handle.update(
                        RaftAvatarMountedSnapshot(
                            key = transition.token.key,
                            commitIdentity = transition.committedToCommitIdentity,
                            validators = null,
                            modelRevision = transition.newModelRevision
                        ).managedImageModel()
                    )
                is RaftAvatarMountedTransition.Keep -> Unit
                is RaftAvatarMountedTransition.Clear -> handle.update(null)
            }
        }
    }

    private fun legacyHandle(remoteUrl: String): RaftAvatarMountedHandle =
        RaftAvatarMountedHandle(null, remoteUrl, false, MutableStateFlow(remoteUrl))

    private fun isRefreshNeeded(handle: RaftAvatarMountedHandle): Boolean =
        synchronized(handlesLock) {
            val token = handle.token ?: return@synchronized false
            handles[token.mountId] === handle && handle.refreshNeeded
        }
}

private fun RaftAvatarCacheSubject.diagnosticsHash(): String =
    "$subjectKind:$subjectId".raftAvatarDiagnosticsHash()

/**
 * Bucketed failure kind for avatar refresh diagnostics. Never emits raw error text; the HTTP
 * status is the only transport detail surfaced (Argus: failure=timeout|http_<code>|other).
 */
private fun Throwable.diagnosticsKind(writeFailed: Boolean): String =
    when {
        this === RaftAvatarStaleFlightException -> "failure=stale_flight"
        writeFailed || this === RaftAvatarStageWriteException -> "failure=stage_write"
        this is SharedBinaryConditionalHttpException -> "failure=http_$statusCode"
        else -> "failure=transport"
    }

private object RaftAvatarStaleFlightException : IllegalStateException("stale avatar flight")
private object RaftAvatarStageWriteException : IllegalStateException("avatar stage write failed")

private fun RaftAvatarCacheKey.nativeSlot(): RaftAvatarNativeSlot =
    RaftAvatarNativeSlot(owner.nativePartitionKey, nativeSubjectKey, nativeBytesKey)

private fun RaftAvatarValidators.toConditionalValidators(): SharedBinaryConditionalValidators =
    SharedBinaryConditionalValidators(eTag, lastModified)

private fun RaftAvatarCacheValidators.toMountedValidators(): RaftAvatarValidators =
    RaftAvatarValidators(eTag, lastModified)

private fun SharedBinaryConditionalDownloadResult.toMountedValidators(): RaftAvatarValidators? =
    if (eTag != null || lastModified != null) RaftAvatarValidators(eTag, lastModified) else null

private fun RaftAvatarMountedFlight.commitGuard(session: RaftAvatarMountedSession) =
    RaftAvatarCacheCommitGuard(
        key = key,
        expectedCommit = RaftAvatarCacheCommitExpectation(key, expectedCommitIdentity, expectedModelRevision)
    ) { session.isFlightCurrent(this) }

private fun nextMountedAvatarModelRevision(current: Long): Long {
    check(current < Long.MAX_VALUE) { "avatar model revision exhausted" }
    return current + 1L
}

@OptIn(ExperimentalEncodingApi::class)
internal fun RaftAvatarMountedSnapshot.managedImageModel(): String? {
    val identity = commitIdentity ?: return null
    val json =
        buildJsonObject {
            put("protocol", "slock_avatar_render_v1")
            put("nativePartitionKey", key.owner.nativePartitionKey)
            put("nativeSubjectKey", key.nativeSubjectKey)
            put("nativeBytesKey", key.nativeBytesKey)
            put("commitGeneration", identity.commitGeneration.toString())
            put("commitNonce", identity.nonce)
            put("modelRevision", modelRevision.toString())
        }
    return RAFT_AVATAR_MOUNTED_MODEL_PREFIX + Base64.encode(json.toRaftJsonString().encodeToByteArray())
}

/** Parses only the managed render protocol; ordinary URLs/assets/base64/local models return null. */
@OptIn(ExperimentalEncodingApi::class)
fun parseRaftAvatarMountedRenderModel(model: String): RaftAvatarMountedRenderDescriptor? {
    if (!model.startsWith(RAFT_AVATAR_MOUNTED_MODEL_PREFIX)) return null
    val payload = model.removePrefix(RAFT_AVATAR_MOUNTED_MODEL_PREFIX)
    val json =
        runCatching { parseRaftJsonObject(Base64.decode(payload).decodeToString()) }.getOrNull()
            ?: return null
    if (json.optString("protocol") != "slock_avatar_render_v1") return null
    val generation = json.optString("commitGeneration").toLongOrNull()?.takeIf { it > 0L } ?: return null
    val revision = json.optString("modelRevision").toLongOrNull()?.takeIf { it > 0L } ?: return null
    return runCatching {
        RaftAvatarMountedRenderDescriptor(
            slot =
                RaftAvatarNativeSlot(
                    nativePartitionKey = json.optString("nativePartitionKey"),
                    nativeSubjectKey = json.optString("nativeSubjectKey"),
                    nativeBytesKey = json.optString("nativeBytesKey")
                ),
            commitIdentity =
                RaftAvatarCommitIdentity(
                    commitGeneration = generation,
                    nonce = json.optString("commitNonce")
                ),
            modelRevision = revision
        )
    }.getOrNull()
}

/** task #343: bounds cold-start prewarm (one native inspect per row). */
internal const val PrewarmOwnerLimit: Long = 300L
